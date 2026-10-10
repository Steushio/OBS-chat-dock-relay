const { app, BrowserWindow, ipcMain, Tray, Menu } = require('electron');
const path = require('path');
const fs = require('fs');


// Disable GPU hardware acceleration globally for low resource hardware
app.disableHardwareAcceleration();

// Optimize Chromium flags for minimal CPU footprint
app.commandLine.appendSwitch('js-flags', '--max-old-space-size=128');
app.commandLine.appendSwitch('disable-gpu');
app.commandLine.appendSwitch('disable-gpu-rasterization');
app.commandLine.appendSwitch('disable-software-rasterizer');
app.commandLine.appendSwitch('disable-3d-apis');
app.commandLine.appendSwitch('disable-accelerated-2d-canvas');
app.commandLine.appendSwitch('disable-breakpad');
app.commandLine.appendSwitch('disable-component-update');
app.commandLine.appendSwitch('disable-ipc-flooding-protection');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const configStore = require('./configStore');
const overlayServer = require('./overlayServer');
const websocketServer = require('./websocketServer');
const youtubeSession = require('./youtubeSession');
const streamManager = require('./streamManager');
const chatCapture = require('./chatCapture');
const kickCapture = require('./kickCapture');
const botAccountSession = require('./botAccountSession');
const ollamaBot = require('./ollamaBot');

let mainWindow = null;
let tray = null;
let isQuitting = false;
let isMonitoringActive = false;

// 1. App Startup Flow
async function startApp() {
  const httpPort = configStore.get('httpPort');
  const wsPort = configStore.get('wsPort');


  // Start Express Overlay server
  try {
    await overlayServer.start(httpPort);
  } catch (err) {
    websocketServer.logSystem(`Express Server failed to start on port ${httpPort}: ${err.message}`, 'error');
  }

  // Start WebSocket relay server
  websocketServer.start(wsPort);

  // Initialize System Tray
  createTray();

  // Create UI Dashboard Window
  const startMinimized = configStore.get('startMinimized') || false;
  createMainWindow(startMinimized);
  if (startMinimized) {
    websocketServer.logSystem('Application started minimized to system tray');
  }

  // Handle Stream switching from StreamManager -> ChatCapture
  streamManager.onStreamChange((stream) => {
    if (stream && stream.videoId) {
      chatCapture.switchStream(stream.videoId);
      botAccountSession.prewarmYouTubeBotChat(stream.videoId);
      
      // Update UI dashboard
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('stream-state-changed', stream);
      }
    }
  });

  // Prewarm bot chat if a previous video ID is stored
  const lastVideoId = configStore.get('lastVideoId');
  if (lastVideoId) {
    botAccountSession.prewarmYouTubeBotChat(lastVideoId);
  }

  // Relay WS logs/chat previews to the Dashboard UI
  setupDashboardRelays();

  // Auto start monitoring if enabled and authenticated
  if (configStore.get('autoStartMonitoring')) {
    const loggedIn = await youtubeSession.checkLoginStatus();
    if (loggedIn) {
      websocketServer.logSystem('Auto-starting background stream monitor...');
      toggleBackgroundMonitor(true);
    } else {
      websocketServer.logSystem('Auto-start monitor skipped: YouTube session not authenticated.', 'warning');
    }
  }

  // Auto start Kick capture if enabled and username is provided
  if (configStore.get('kickEnabled') && configStore.get('kickUsername')) {
    websocketServer.logSystem(`[Kick] Auto-starting background chat monitor for: ${configStore.get('kickUsername')}...`);
    kickCapture.start(configStore.get('kickUsername'));
  }
}

// 2. Setup visual relays to dashboard
function setupDashboardRelays() {
  // Catch log events and send to dashboard window
  const originalLogSystem = websocketServer.logSystem.bind(websocketServer);
  websocketServer.logSystem = (message, level = 'info') => {
    originalLogSystem(message, level);
    if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isVisible()) {
      mainWindow.webContents.send('preview-log', { message, level });
    }
  };

  // Catch websocket broadcast messages to feed live chat preview in dashboard UI
  const originalBroadcast = websocketServer.broadcast.bind(websocketServer);
  websocketServer.broadcast = (event, data) => {
    originalBroadcast(event, data);
    if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isVisible()) {
      if (event === 'chat') {
        mainWindow.webContents.send('preview-chat', data);
      } else if (event === 'poll') {
        mainWindow.webContents.send('preview-poll', data);
        mainWindow.webContents.send('preview-chat', data);
      } else if (event === 'clear_state') {
        mainWindow.webContents.send('clear-activity');
      }
    }
  };
}

// 3. Create Main Dashboard Window
function createMainWindow(startMinimized = false) {
  const bounds = configStore.get('windowBounds');

  mainWindow = new BrowserWindow({
    width: bounds.width,
    height: bounds.height,
    minWidth: 800,
    minHeight: 600,
    show: !startMinimized,
    icon: path.join(__dirname, '..', 'assets', 'icon.png'),
    backgroundColor: '#09090b', // obsidian black matching dashboard UI
    webPreferences: {
      nodeIntegration: true, // Allow IPC communication safely
      contextIsolation: false, // Ease of renderer access for dashboard
      backgroundThrottling: false
    }
  });

  mainWindow.setMenu(null); // Clean menu bars

  mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'dashboard.html'));

  // Save window dimensions on resize
  mainWindow.on('resize', () => {
    if (!mainWindow.isMaximized()) {
      const b = mainWindow.getBounds();
      configStore.set('windowBounds', { width: b.width, height: b.height });
    }
  });

  // Minimize to Tray instead of closing
  mainWindow.on('close', (e) => {
    if (!isQuitting) {
      e.preventDefault();
      mainWindow.hide();
      websocketServer.logSystem('Application minimized to system tray');
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// 4. Create System Tray Icon & Context Menu
function createTray() {
  // Use a fallback solid png path inside overlays for the tray icon
  const iconPath = path.join(__dirname, '..', 'overlays', 'favicon.png');
  
  // Make sure at least a blank file exists to prevent Electron error logs
  if (!fs.existsSync(iconPath)) {
    const parentDir = path.dirname(iconPath);
    if (!fs.existsSync(parentDir)) {
      fs.mkdirSync(parentDir, { recursive: true });
    }
    // Write 1px transparent PNG buffer as dummy
    const dummyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64');
    fs.writeFileSync(iconPath, dummyPng);
  }

  tray = new Tray(iconPath);
  tray.setToolTip('OBS Chat Relay');

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Open Dashboard',
      click: () => {
        if (mainWindow) {
          mainWindow.show();
          mainWindow.focus();
        } else {
          createMainWindow();
        }
      }
    },
    { type: 'separator' },
    {
      label: 'Start Monitor',
      click: () => {
        toggleBackgroundMonitor(true);
      }
    },
    {
      label: 'Stop Monitor',
      click: () => {
        toggleBackgroundMonitor(false);
      }
    },
    { type: 'separator' },
    {
      label: 'Quit Application',
      click: () => {
        isQuitting = true;
        botAccountSession.isQuitting = true;
        chatCapture.stopCapture();
        streamManager.stopMonitoring();
        kickCapture.stop();
        overlayServer.stop();
        websocketServer.stop();
        app.quit();
      }
    }
  ]);

  tray.setContextMenu(contextMenu);

  // Toggle show/hide on double-click
  tray.on('double-click', () => {
    if (mainWindow && mainWindow.isVisible()) {
      mainWindow.hide();
    } else if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    } else {
      createMainWindow();
    }
  });
}

// 5. Toggle Background Scanning
function toggleBackgroundMonitor(active) {
  isMonitoringActive = active;
  if (active) {
    streamManager.startMonitoring();
  } else {
    streamManager.stopMonitoring();
    chatCapture.stopCapture();
  }
}

// 6. Linux Startup .desktop Entry Generator
function updateLinuxAutoStart(enabled) {
  if (process.platform !== 'linux') return;

  const autostartDir = path.join(app.getPath('home'), '.config', 'autostart');
  const desktopFilePath = path.join(autostartDir, 'obs-chat-relay.desktop');

  if (enabled) {
    try {
      if (!fs.existsSync(autostartDir)) {
        fs.mkdirSync(autostartDir, { recursive: true });
      }

      // CRITICAL FOR APPIMAGE:
      // When running inside an AppImage, process.execPath points to the temporary mount point
      // in /tmp/.mount_xxxx/ which disappears upon reboot/app close.
      // process.env.APPIMAGE contains the actual persistent path to the .AppImage file!
      let targetExec = process.env.APPIMAGE;
      if (!targetExec) {
        if (process.execPath && !process.execPath.includes('.mount_')) {
          targetExec = process.execPath;
        } else {
          const distAppImage = path.join(app.getPath('home'), 'Documents', 'OBS-chat-dock-relay', 'dist', 'OBS Chat Relay-1.0.0.AppImage');
          if (fs.existsSync(distAppImage)) {
            targetExec = distAppImage;
          } else {
            targetExec = process.execPath;
          }
        }
      }

      // Copy application icon to persistent userData for desktop entry
      const iconSrc = path.join(__dirname, '..', 'assets', 'icon.png');
      const iconDest = path.join(app.getPath('userData'), 'icon.png');
      let iconPath = 'utilities-terminal';
      try {
        if (fs.existsSync(iconSrc)) {
          fs.copyFileSync(iconSrc, iconDest);
          iconPath = iconDest;
        } else if (fs.existsSync(iconDest)) {
          iconPath = iconDest;
        }
      } catch (e) {}

      const desktopContent = `[Desktop Entry]
Type=Application
Version=1.0
Name=OBS Chat Relay
Comment=Auto-start YouTube OBS Chat Relay on Linux Login
Exec="${targetExec}" --minimized
StartupNotify=false
Terminal=false
Icon=${iconPath}
Categories=Utility;
X-GNOME-Autostart-enabled=true
`;

      fs.writeFileSync(desktopFilePath, desktopContent, 'utf-8');
      websocketServer.logSystem(`Linux login auto-start configured with target: ${targetExec}`);
    } catch (err) {
      console.error('Failed to create autostart desktop file:', err);
    }
  } else {
    try {
      if (fs.existsSync(desktopFilePath)) {
        fs.unlinkSync(desktopFilePath);
        websocketServer.logSystem('Linux login auto-start entry successfully removed.');
      }
    } catch (err) {
      console.error('Failed to remove autostart desktop file:', err);
    }
  }
}

// 7. IPC Handle definitions for Renderer Dashboard Interactions
function setupIpcHandlers() {
  // Sync Settings fetch
  ipcMain.handle('get-settings', () => {
    let kickStatus = 'Idle';
    if (kickCapture.isEnabled) {
      if (kickCapture.ws && kickCapture.ws.readyState === 1) {
        kickStatus = 'Connected';
      } else {
        kickStatus = 'Connecting...';
      }
    }
    return {
      ...configStore.getAll(),
      isMonitoringActive,
      kickStatus
    };
  });

  // Settings Save & dynamic server restarts
  ipcMain.handle('save-settings', async (event, settings) => {
    try {
      const oldHttpPort = configStore.get('httpPort');
      const oldWsPort = configStore.get('wsPort');
      const oldKickEnabled = configStore.get('kickEnabled');
      const oldKickUsername = configStore.get('kickUsername');

      configStore.set('httpPort', settings.httpPort);
      configStore.set('wsPort', settings.wsPort);
      configStore.set('lowResourceMode', settings.lowResourceMode);
      configStore.set('autoStart', settings.autoStart);
      configStore.set('autoStartMonitoring', settings.autoStartMonitoring);
      configStore.set('startMinimized', settings.startMinimized);
      configStore.set('kickUsername', settings.kickUsername);
      configStore.set('kickEnabled', settings.kickEnabled);
      configStore.set('chatSoundEnabled', settings.chatSoundEnabled);
      configStore.set('chatSoundType', settings.chatSoundType);
      configStore.set('chatSoundVolume', settings.chatSoundVolume);
      configStore.set('chatSoundFile', settings.chatSoundFile);
      configStore.set('alertSoundEnabled', settings.alertSoundEnabled);
      configStore.set('alertSoundType', settings.alertSoundType);
      configStore.set('alertSoundVolume', settings.alertSoundVolume);
      configStore.set('alertSoundFile', settings.alertSoundFile);

      // Trigger auto start update
      updateLinuxAutoStart(settings.autoStart);

      // Dynamically restart HTTP Server if port changed
      if (oldHttpPort !== settings.httpPort) {
        await overlayServer.start(settings.httpPort);
        websocketServer.logSystem(`HTTP Server dynamically moved to port ${settings.httpPort}`);
      }

      // Dynamically restart WS Server if port changed
      if (oldWsPort !== settings.wsPort) {
        websocketServer.start(settings.wsPort);
        websocketServer.logSystem(`WebSocket Server dynamically moved to port ${settings.wsPort}`);
      }

      // Dynamically start/stop or update Kick Capture if enabled or username changed
      if (oldKickEnabled !== settings.kickEnabled || oldKickUsername !== settings.kickUsername) {
        if (settings.kickEnabled) {
          kickCapture.stop();
          if (settings.kickUsername) {
            kickCapture.start(settings.kickUsername);
          }
        } else {
          kickCapture.stop();
        }
      }

      // Broadcast new audio settings to all connected websocket client docks
      websocketServer.broadcast('settings_update', {
        audioSettings: {
          chatSoundEnabled: settings.chatSoundEnabled,
          chatSoundType: settings.chatSoundType,
          chatSoundVolume: settings.chatSoundVolume,
          chatSoundFile: settings.chatSoundFile,
          alertSoundEnabled: settings.alertSoundEnabled,
          alertSoundType: settings.alertSoundType,
          alertSoundVolume: settings.alertSoundVolume,
          alertSoundFile: settings.alertSoundFile
        }
      });

      return { success: true };
    } catch (err) {
      websocketServer.logSystem(`Failed saving settings: ${err.message}`, 'error');
      return { success: false, error: err.message };
    }
  });

  // Settings Export to JSON file
  ipcMain.handle('export-settings', async () => {
    const { dialog } = require('electron');
    try {
      const result = await dialog.showSaveDialog(mainWindow, {
        title: 'Export App & Bot Settings',
        defaultPath: 'obs-chat-relay-settings.json',
        filters: [{ name: 'JSON Files (*.json)', extensions: ['json'] }]
      });

      if (!result.canceled && result.filePath) {
        const data = {
          exportedAt: new Date().toISOString(),
          version: '1.0.0',
          ...configStore.getAll()
        };
        fs.writeFileSync(result.filePath, JSON.stringify(data, null, 2), 'utf-8');
        websocketServer.logSystem(`[Settings] Exported settings to: ${result.filePath}`);
        return { success: true, filePath: result.filePath };
      }
      return { success: false, canceled: true };
    } catch (err) {
      websocketServer.logSystem(`[Settings] Export failed: ${err.message}`, 'error');
      return { success: false, error: err.message };
    }
  });

  // Settings Import from JSON file
  ipcMain.handle('import-settings', async () => {
    const { dialog } = require('electron');
    try {
      const result = await dialog.showOpenDialog(mainWindow, {
        title: 'Import App & Bot Settings',
        properties: ['openFile'],
        filters: [{ name: 'JSON Files (*.json)', extensions: ['json'] }]
      });

      if (!result.canceled && result.filePaths.length > 0) {
        const filePath = result.filePaths[0];
        const content = fs.readFileSync(filePath, 'utf-8');
        const parsed = JSON.parse(content);

        if (typeof parsed !== 'object' || parsed === null) {
          throw new Error('Invalid JSON settings format');
        }

        // If user imported a raw ollamaBotSettings object directly:
        if (parsed.botName || (parsed.systemPrompt && !parsed.ollamaBotSettings)) {
          configStore.set('ollamaBotSettings', {
            ...configStore.get('ollamaBotSettings'),
            ...parsed
          });
        } else {
          // Merge full settings
          for (const [key, val] of Object.entries(parsed)) {
            if (key !== 'exportedAt' && key !== 'version') {
              configStore.set(key, val);
            }
          }
        }

        // Reload Ollama bot with new settings and timers
        ollamaBot.settings = ollamaBot.loadSettings();
        ollamaBot.startTimers();

        websocketServer.logSystem(`[Settings] Successfully imported settings from: ${filePath}`);
        return { success: true, filePath, settings: configStore.getAll() };
      }
      return { success: false, canceled: true };
    } catch (err) {
      websocketServer.logSystem(`[Settings] Import failed: ${err.message}`, 'error');
      return { success: false, error: err.message };
    }
  });

  // Open native file explorer to pick a local audio file
  ipcMain.handle('select-audio-file', async () => {
    const { dialog } = require('electron');
    const result = await dialog.showOpenDialog(mainWindow, {
      properties: ['openFile'],
      filters: [
        { name: 'Audio Files', extensions: ['mp3', 'wav', 'ogg', 'aac', 'm4a'] }
      ]
    });
    if (!result.canceled && result.filePaths.length > 0) {
      return result.filePaths[0];
    }
    return null;
  });

  // YouTube Login Status Probe
  ipcMain.handle('check-login', async () => {
    return await youtubeSession.checkLoginStatus();
  });

  // Open Youtube login window
  ipcMain.on('open-login', () => {
    youtubeSession.showLoginWindow(mainWindow);
  });

  // Logout from YouTube
  ipcMain.handle('logout-youtube', async () => {
    configStore.set('channelId', null);
    streamManager.clearState();
    chatCapture.stopCapture();
    return await youtubeSession.logout();
  });

  // Monitor toggle from checkbox
  ipcMain.on('toggle-monitor', (event, active) => {
    toggleBackgroundMonitor(active);
  });
}

// 8. Lifecycle hooks
app.whenReady().then(() => {
  setupIpcHandlers();
  
  // Set up auto-start if settings have it enabled
  updateLinuxAutoStart(configStore.get('autoStart'));

  // Setup background network tracker/fonts/ads blocker for low resource footprint
  const { session } = require('electron');
  session.fromPartition('persist:youtube').webRequest.onBeforeRequest(
    { urls: ['*://*/*'] },
    (details, callback) => {
      const url = details.url.toLowerCase();
      const shouldBlock = 
        url.includes('.woff') || 
        url.includes('.ttf') || 
        url.includes('/log_event') || 
        url.includes('/api/stats/') || 
        url.includes('google-analytics') || 
        url.includes('doubleclick') || 
        url.includes('googleadservices');
        
      callback({ cancel: shouldBlock });
    }
  );

  startApp();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow();
    }
  });
});

// Single instance lock
const additionalData = { myKey: 'obs-chat-relay-lock' };
const gotTheLock = app.requestSingleInstanceLock(additionalData);

if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    // Standard tray application behavior: continue running in tray when dashboard is closed
  }
});

app.on('before-quit', () => {
  isQuitting = true;
  botAccountSession.isQuitting = true;
});



