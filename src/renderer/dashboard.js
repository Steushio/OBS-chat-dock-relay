const { ipcRenderer } = require('electron');

// UI DOM Elements
const globalStatusDot = document.getElementById('global-status-dot');
const globalStatusText = document.getElementById('global-status-text');

const sessionStatusText = document.getElementById('session-status-text');
const btnLogin = document.getElementById('btn-login');
const btnLogout = document.getElementById('btn-logout');

const toggleMonitor = document.getElementById('toggle-monitor');
const streamStatus = document.getElementById('stream-status');
const streamVideoId = document.getElementById('stream-videoid');
const streamTitle = document.getElementById('stream-title');

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
  urlChat.innerText = `http://127.0.0.1:${port}/chatdock`;
  urlAlert.innerText = `http://127.0.0.1:${port}/alertdock`;
  urlEvent.innerText = `http://127.0.0.1:${port}/eventdock`;
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
      startMinimized: toggleStartMinimized.checked
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

  // Copy Buttons Clipboard handling
  document.querySelectorAll('.btn-copy').forEach(btn => {
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

  // Listen for active stream metadata changes
  ipcRenderer.on('stream-state-changed', (event, stream) => {
    if (stream) {
      streamStatus.innerText = stream.liveState.toUpperCase();
      streamStatus.style.color = stream.liveState === 'live' ? '#22c55e' : '#fb923c';
      streamVideoId.innerText = stream.videoId;
      streamTitle.innerText = stream.title;
      
      logActivity(`Stream detected: ${stream.title} [${stream.liveState}]`, 'system');
    } else {
      streamStatus.innerText = 'Idle';
      streamStatus.style.color = 'var(--text-muted)';
      streamVideoId.innerText = 'None';
      streamTitle.innerText = 'No stream active';
    }
  });

  // Listen for Live Activity Monitor ticks
  ipcRenderer.on('preview-log', (event, log) => {
    logActivity(`[Log] ${log.message}`, log.level);
  });

  ipcRenderer.on('preview-chat', (event, chat) => {
    if (chat.type === 'chat') {
      logActivity(`${chat.username}: ${chat.message}`, 'chat');
    } else {
      logActivity(`Alert: ${chat.username} - ${chat.message || chat.amount}`, 'alert');
    }
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

// Start
init();
