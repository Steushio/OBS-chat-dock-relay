const { app, BrowserWindow, ipcMain, Tray, Menu } = require('electron');
const path = require('path');
const fs = require('fs');


const configStore = require('./configStore');
const overlayServer = require('./overlayServer');
const websocketServer = require('./websocketServer');
const youtubeSession = require('./youtubeSession');
const streamManager = require('./streamManager');
const chatCapture = require('./chatCapture');

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
      
      // Update UI dashboard
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('stream-state-changed', stream);
      }
    }
  });

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
}

// 2. Setup visual relays to dashboard
function setupDashboardRelays() {
  // Catch log events and send to dashboard window
  const originalLogSystem = websocketServer.logSystem.bind(websocketServer);
  websocketServer.logSystem = (message, level = 'info') => {
    originalLogSystem(message, level);
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('preview-log', { message, level });
    }
  };

  // Catch websocket broadcast messages to feed live chat preview in dashboard UI
  const originalBroadcast = websocketServer.broadcast.bind(websocketServer);
  websocketServer.broadcast = (event, data) => {
    originalBroadcast(event, data);
    if (mainWindow && !mainWindow.isDestroyed()) {
      if (event === 'chat') {
        mainWindow.webContents.send('preview-chat', data);
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
        chatCapture.stopCapture();
        streamManager.stopMonitoring();
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

      const desktopContent = `[Desktop Entry]
Type=Application
Version=1.0
Name=OBS Chat Relay
Comment=Auto-start YouTube OBS Chat Relay on Linux Login
Exec="${process.execPath}" --minimized
StartupNotify=false
Terminal=false
Icon=utilities-terminal
Categories=Utility;
`;

      fs.writeFileSync(desktopFilePath, desktopContent, 'utf-8');
      websocketServer.logSystem('Linux login auto-start entry successfully configured.');
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
    return {
      ...configStore.getAll(),
      isMonitoringActive
    };
  });

  // Settings Save & dynamic server restarts
  ipcMain.handle('save-settings', async (event, settings) => {
    try {
      const oldHttpPort = configStore.get('httpPort');
      const oldWsPort = configStore.get('wsPort');

      configStore.set('httpPort', settings.httpPort);
      configStore.set('wsPort', settings.wsPort);
      configStore.set('lowResourceMode', settings.lowResourceMode);
      configStore.set('autoStart', settings.autoStart);
      configStore.set('autoStartMonitoring', settings.autoStartMonitoring);
      configStore.set('startMinimized', settings.startMinimized);

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

      return { success: true };
    } catch (err) {
      websocketServer.logSystem(`Failed saving settings: ${err.message}`, 'error');
      return { success: false, error: err.message };
    }
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



