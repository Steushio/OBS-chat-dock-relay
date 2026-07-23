const { ipcRenderer } = require('electron');

// UI DOM Elements
const globalStatusDot = document.getElementById('global-status-dot');
const globalStatusText = document.getElementById('global-status-text');

const sessionStatusText = document.getElementById('session-status-text');
const btnLogin = document.getElementById('btn-login');
const btnLogout = document.getElementById('btn-logout');

const toggleMonitor = document.getElementById('toggle-monitor');
const streamStatus = document.getElementById('stream-status');
const streamViewers = document.getElementById('stream-viewers');
const streamVideoId = document.getElementById('stream-videoid');
const streamTitle = document.getElementById('stream-title');

const inputKickUsername = document.getElementById('input-kick-username');
const toggleKick = document.getElementById('toggle-kick');
const kickStatus = document.getElementById('kick-status');
const kickViewers = document.getElementById('kick-viewers');
const kickViewersCard = document.getElementById('kick-viewers-card');

const activityPreview = document.getElementById('activity-preview');

const urlChat = document.getElementById('url-chat');
const urlAlert = document.getElementById('url-alert');
const urlEvent = document.getElementById('url-event');

const inputHttpPort = document.getElementById('input-http-port');
const inputWsPort = document.getElementById('input-ws-port');
const toggleLowResource = document.getElementById('toggle-low-resource');
const toggleAutostart = document.getElementById('toggle-autostart');
const toggleAutoStartMonitoring = document.getElementById('toggle-auto-start-monitoring');
const toggleStartMinimized = document.getElementById('toggle-start-minimized');
const btnSaveSettings = document.getElementById('btn-save-settings');

// Audio Settings Elements
const toggleChatSound = document.getElementById('toggle-chat-sound');
const selectChatSoundType = document.getElementById('select-chat-sound-type');
const sliderChatVolume = document.getElementById('slider-chat-volume');
const chatVolumeVal = document.getElementById('chat-volume-val');
const chatSoundFileGroup = document.getElementById('chat-sound-file-group');
const inputChatSoundFile = document.getElementById('input-chat-sound-file');
const btnBrowseChatSound = document.getElementById('btn-browse-chat-sound');
const btnTestChatSound = document.getElementById('btn-test-chat-sound');

const toggleAlertSound = document.getElementById('toggle-alert-sound');
const selectAlertSoundType = document.getElementById('select-alert-sound-type');
const sliderAlertVolume = document.getElementById('slider-alert-volume');
const alertVolumeVal = document.getElementById('alert-volume-val');
const alertSoundFileGroup = document.getElementById('alert-sound-file-group');
const inputAlertSoundFile = document.getElementById('input-alert-sound-file');
const btnBrowseAlertSound = document.getElementById('btn-browse-alert-sound');
const btnTestAlertSound = document.getElementById('btn-test-alert-sound');

const toastMessage = document.getElementById('toast-message');

// State Variables
let currentHttpPort = 3000;

// Initialize Dashboard
async function init() {
  setupEventListeners();
  await loadSettings();
  await checkYouTubeLogin();
  setupIpcListeners();
}

// 1. Fetch and apply settings configurations
async function loadSettings() {
  try {
    const config = await ipcRenderer.invoke('get-settings');
    
    currentHttpPort = config.httpPort;
    inputHttpPort.value = config.httpPort;
    inputWsPort.value = config.wsPort;
    toggleLowResource.checked = config.lowResourceMode;
    toggleAutostart.checked = config.autoStart;
    toggleAutoStartMonitoring.checked = config.autoStartMonitoring || false;
    toggleStartMinimized.checked = config.startMinimized || false;
    
    inputKickUsername.value = config.kickUsername || '';
    toggleKick.checked = config.kickEnabled || false;
    kickStatus.innerText = config.kickStatus || 'Idle';
    updateKickStatusColor(config.kickStatus || 'Idle');

    // Set Audio settings
    toggleChatSound.checked = config.chatSoundEnabled || false;
    selectChatSoundType.value = config.chatSoundType || 'default';
    sliderChatVolume.value = config.chatSoundVolume !== undefined ? config.chatSoundVolume : 100;
    chatVolumeVal.innerText = sliderChatVolume.value;
    inputChatSoundFile.value = config.chatSoundFile || '';
    chatSoundFileGroup.style.display = selectChatSoundType.value === 'custom' ? 'flex' : 'none';

    toggleAlertSound.checked = config.alertSoundEnabled || false;
    selectAlertSoundType.value = config.alertSoundType || 'default';
    sliderAlertVolume.value = config.alertSoundVolume !== undefined ? config.alertSoundVolume : 100;
    alertVolumeVal.innerText = sliderAlertVolume.value;
    inputAlertSoundFile.value = config.alertSoundFile || '';
    alertSoundFileGroup.style.display = selectAlertSoundType.value === 'custom' ? 'flex' : 'none';

    // Set background monitoring state
    toggleMonitor.checked = config.isMonitoringActive || false;

    updateDockUrls(config.httpPort);
    
    globalStatusDot.className = 'dot active';
    globalStatusText.innerText = 'Relay Engine Active';
  } catch (err) {
    console.error('Failed to load settings:', err);
    showToast('Failed to load local configuration');
  }
}

// Update the URLs in the DOM dynamically based on port
function updateDockUrls(port) {
  if (urlChat) urlChat.innerText = `http://127.0.0.1:${port}/chatdock`;
  if (urlAlert) urlAlert.innerText = `http://127.0.0.1:${port}/alertdock`;
  if (urlEvent) urlEvent.innerText = `http://127.0.0.1:${port}/eventdock`;
}

// 2. Check current YouTube Authentication status
async function checkYouTubeLogin() {
  sessionStatusText.innerText = 'Checking...';
  sessionStatusText.style.color = 'var(--text-muted)';
  
  const loggedIn = await ipcRenderer.invoke('check-login');
  updateLoginUI(loggedIn);
}

function updateLoginUI(loggedIn) {
  if (loggedIn) {
    sessionStatusText.innerText = 'Authenticated (YouTube Studio)';
    sessionStatusText.style.color = '#22c55e';
    btnLogin.style.display = 'none';
    btnLogout.style.display = 'inline-flex';
  } else {
    sessionStatusText.innerText = 'Not Authenticated';
    sessionStatusText.style.color = '#ef4444';
    btnLogin.style.display = 'inline-flex';
    btnLogout.style.display = 'none';
    
    // Disable scanner toggle if not logged in
    toggleMonitor.checked = false;
  }
}

// 3. Bind UI interactions
function setupEventListeners() {
  // Save Settings
  btnSaveSettings.addEventListener('click', async () => {
    const settings = {
      httpPort: parseInt(inputHttpPort.value),
      wsPort: parseInt(inputWsPort.value),
      lowResourceMode: toggleLowResource.checked,
      autoStart: toggleAutostart.checked,
      autoStartMonitoring: toggleAutoStartMonitoring.checked,
      startMinimized: toggleStartMinimized.checked,
      kickUsername: inputKickUsername.value.trim(),
      kickEnabled: toggleKick.checked,
      chatSoundEnabled: toggleChatSound.checked,
      chatSoundType: selectChatSoundType.value,
      chatSoundVolume: parseInt(sliderChatVolume.value),
      chatSoundFile: inputChatSoundFile.value,
      alertSoundEnabled: toggleAlertSound.checked,
      alertSoundType: selectAlertSoundType.value,
      alertSoundVolume: parseInt(sliderAlertVolume.value),
      alertSoundFile: inputAlertSoundFile.value
    };

    const result = await ipcRenderer.invoke('save-settings', settings);
    if (result.success) {
      currentHttpPort = settings.httpPort;
      updateDockUrls(settings.httpPort);
      showToast('Settings saved & servers restarted!');
    } else {
      showToast('Failed to apply settings. Port may be in use.');
    }
  });

  // Open YouTube login popup
  btnLogin.addEventListener('click', () => {
    ipcRenderer.send('open-login');
  });

  // Logout
  btnLogout.addEventListener('click', async () => {
    const success = await ipcRenderer.invoke('logout-youtube');
    if (success) {
      updateLoginUI(false);
      showToast('Successfully logged out!');
      // Force toggle scanner off
      toggleMonitor.checked = false;
      ipcRenderer.send('toggle-monitor', false);
    }
  });

  // Toggle Background Monitoring
  toggleMonitor.addEventListener('change', async (e) => {
    const active = e.target.checked;
    
    // Safety check: must be logged in to monitor
    const loggedIn = await ipcRenderer.invoke('check-login');
    if (!loggedIn && active) {
      showToast('Please authenticate with YouTube first!');
      e.target.checked = false;
      return;
    }

    ipcRenderer.send('toggle-monitor', active);
    showToast(active ? 'Background monitor active' : 'Monitor deactivated');
  });

  // Copy URL buttons
  const copyButtons = document.querySelectorAll('.btn-copy');
  copyButtons.forEach(btn => {
    btn.addEventListener('click', (e) => {
      const targetId = e.target.getAttribute('data-target');
      const textToCopy = document.getElementById(targetId).innerText;
      
      navigator.clipboard.writeText(textToCopy).then(() => {
        const originalText = e.target.innerHTML;
        e.target.innerHTML = '✅ Copied!';
        e.target.classList.add('btn-primary');
        e.target.classList.remove('btn-secondary');
        
        setTimeout(() => {
          e.target.innerHTML = originalText;
          e.target.classList.add('btn-secondary');
          e.target.classList.remove('btn-primary');
        }, 1500);
      });
    });
  });

  // Audio settings interactive logic
  selectChatSoundType.addEventListener('change', () => {
    chatSoundFileGroup.style.display = selectChatSoundType.value === 'custom' ? 'flex' : 'none';
  });

  selectAlertSoundType.addEventListener('change', () => {
    alertSoundFileGroup.style.display = selectAlertSoundType.value === 'custom' ? 'flex' : 'none';
  });

  sliderChatVolume.addEventListener('input', () => {
    chatVolumeVal.innerText = sliderChatVolume.value;
  });

  sliderAlertVolume.addEventListener('input', () => {
    alertVolumeVal.innerText = sliderAlertVolume.value;
  });

  btnBrowseChatSound.addEventListener('click', async () => {
    const filePath = await ipcRenderer.invoke('select-audio-file');
    if (filePath) {
      inputChatSoundFile.value = filePath;
    }
  });

  btnBrowseAlertSound.addEventListener('click', async () => {
    const filePath = await ipcRenderer.invoke('select-audio-file');
    if (filePath) {
      inputAlertSoundFile.value = filePath;
    }
  });

  btnTestChatSound.addEventListener('click', () => {
    playDashboardSound(selectChatSoundType.value, inputChatSoundFile.value, parseInt(sliderChatVolume.value), 'chat');
  });

  btnTestAlertSound.addEventListener('click', () => {
    playDashboardSound(selectAlertSoundType.value, inputAlertSoundFile.value, parseInt(sliderAlertVolume.value), 'alert');
  });
}

// 4. Set up IPC Push Event listeners
function setupIpcListeners() {
  // Listen for login status pushes
  ipcRenderer.on('login-status-changed', (event, { loggedIn }) => {
    updateLoginUI(loggedIn);
    if (!loggedIn) {
      toggleMonitor.checked = false;
    }
  });

  // Listen for Kick status pushes
  ipcRenderer.on('kick-status-changed', (event, data) => {
    const statusText = typeof data === 'string' ? data : (data?.status || 'Idle');
    const viewers = typeof data === 'object' && data?.viewers !== undefined ? data.viewers : 0;

    kickStatus.innerText = statusText;
    updateKickStatusColor(statusText);

    if (kickViewers) {
      kickViewers.innerText = viewers !== null && viewers !== undefined ? viewers : '0';
    }
    if (kickViewersCard) {
      kickViewersCard.innerText = viewers !== null && viewers !== undefined ? viewers : '0';
    }
  });

  // Listen for active stream metadata changes
  ipcRenderer.on('stream-state-changed', (event, stream) => {
    if (stream) {
      streamStatus.innerText = stream.liveState.toUpperCase();
      streamStatus.style.color = stream.liveState === 'live' ? '#22c55e' : '#fb923c';
      streamVideoId.innerText = stream.videoId;
      streamTitle.innerText = stream.title;
      streamViewers.innerText = stream.concurrentViewers !== undefined && stream.concurrentViewers !== null ? stream.concurrentViewers : '0';
      
      logActivity(`Stream detected: ${stream.title} [${stream.liveState}]`, 'system');
    } else {
      streamStatus.innerText = 'Idle';
      streamStatus.style.color = 'var(--text-muted)';
      streamVideoId.innerText = 'None';
      streamTitle.innerText = 'No stream active';
      streamViewers.innerText = '0';
    }
  });

  // Listen for Live Activity Monitor ticks
  ipcRenderer.on('preview-log', (event, log) => {
    logActivity(`[Log] ${log.message}`, log.level);
  });

  ipcRenderer.on('preview-chat', (event, chat) => {
    const platformLabel = chat.platform === 'kick' ? '[Kick] ' : '[YT] ';
    if (chat.type === 'chat') {
      logActivity(`${platformLabel}${chat.username}:${chat.message}`, 'chat');
    } else {
      logActivity(`${platformLabel}Alert: ${chat.username} - ${chat.message || chat.amount}`, 'alert');
    }
  });

  ipcRenderer.on('clear-activity', () => {
    activityPreview.innerHTML = '<div style="color: var(--text-muted); font-style: italic; text-align: center; padding: 20px 0;">Waiting for activity...</div>';
  });
}

// Helper to append elements to the Live Preview Monitor window
function logActivity(text, type = 'info') {
  // Clear waiting placeholder if present
  if (activityPreview.children.length === 1 && activityPreview.children[0].style.color === 'var(--text-muted)') {
    activityPreview.innerHTML = '';
  }

  // Cap size
  while (activityPreview.children.length >= 30) {
    activityPreview.removeChild(activityPreview.firstChild);
  }

  const row = document.createElement('div');
  row.className = 'preview-row';

  const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const timeSpan = `<span style="color: var(--text-muted)">[${time}]</span>`;

  if (type === 'chat') {
    const parts = text.split(':');
    const user = parts[0];
    const msg = parts.slice(1).join(':');
    row.innerHTML = `${timeSpan} <span class="preview-user">${user}:</span><span class="preview-text">${msg}</span>`;
  } 
  else if (type === 'alert') {
    row.innerHTML = `${timeSpan} <span class="preview-alert">⭐ ${text}</span>`;
  }
  else if (type === 'warning') {
    row.innerHTML = `${timeSpan} <span style="color: #fb923c">⚠️ ${text}</span>`;
  }
  else if (type === 'error') {
    row.innerHTML = `${timeSpan} <span style="color: #f87171">🚨 ${text}</span>`;
  }
  else {
    // Info/System logs
    row.innerHTML = `${timeSpan} <span style="color: var(--accent-cyan)">⚙️ ${text}</span>`;
  }

  activityPreview.appendChild(row);
  activityPreview.scrollTop = activityPreview.scrollHeight;
}

// Show standard premium saved toast notifications
function showToast(message) {
  toastMessage.innerText = message;
  toastMessage.classList.add('show');
  
  setTimeout(() => {
    toastMessage.classList.remove('show');
  }, 2500);
}

function updateKickStatusColor(status) {
  if (status === 'Connected') {
    kickStatus.style.color = '#22c55e';
  } else if (status === 'Connecting...' || status.startsWith('Resolving')) {
    kickStatus.style.color = '#fb923c';
  } else if (status.startsWith('Error')) {
    kickStatus.style.color = '#f87171';
  } else {
    kickStatus.style.color = 'var(--text-muted)';
  }
}

function playDashboardSound(type, customPath, volumeVal, category) {
  const volume = volumeVal / 100;
  if (type === 'default') {
    playSyntheticTone(category, volume);
  } else if (customPath) {
    const audio = new Audio(`http://127.0.0.1:${currentHttpPort}/audio?path=${encodeURIComponent(customPath)}`);
    audio.volume = volume;
    audio.play().catch(err => {
      console.error('Error playing custom sound:', err);
      logActivity(`Error playing audio file: ${err.message}`, 'error');
    });
  }
}

function playSyntheticTone(category, volume) {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    
    if (category === 'chat') {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.connect(gain);
      gain.connect(ctx.destination);
      
      osc.type = 'sine';
      osc.frequency.setValueAtTime(880, ctx.currentTime); // A5 note
      osc.frequency.exponentialRampToValueAtTime(1200, ctx.currentTime + 0.15);
      
      gain.gain.setValueAtTime(volume, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.3);
      
      osc.start();
      osc.stop(ctx.currentTime + 0.3);
    } else {
      // Alert chime (double ping)
      const osc1 = ctx.createOscillator();
      const gain1 = ctx.createGain();
      osc1.connect(gain1);
      gain1.connect(ctx.destination);
      
      osc1.type = 'sine';
      osc1.frequency.setValueAtTime(587.33, ctx.currentTime); // D5
      gain1.gain.setValueAtTime(volume, ctx.currentTime);
      gain1.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.2);
      
      osc1.start();
      osc1.stop(ctx.currentTime + 0.2);
      
      setTimeout(() => {
        try {
          const osc2 = ctx.createOscillator();
          const gain2 = ctx.createGain();
          osc2.connect(gain2);
          gain2.connect(ctx.destination);
          
          osc2.type = 'sine';
          osc2.frequency.setValueAtTime(880, ctx.currentTime); // A5
          gain2.gain.setValueAtTime(volume, ctx.currentTime);
          gain2.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.4);
          
          osc2.start();
          osc2.stop(ctx.currentTime + 0.4);
        } catch (e) {}
      }, 120);
    }
  } catch (err) {
    console.error('Error playing synthetic tone:', err);
  }
}

// Start
init();
