const { BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');
const youtubeSession = require('./youtubeSession');
const configStore = require('./configStore');

class StreamManager {
  constructor() {
    this.monitorWindow = null;
    this.streamsMap = new Map(); // videoId -> streamInfo
    this.activeStream = null;   // Currently selected stream info
    this.onStreamChangeCallback = null;
    this.isMonitoring = false;

    this.setupIpc();
  }

  setupIpc() {
    // Listen to intercepted API responses from Studio monitor window
    ipcMain.on('studio-api-data', (event, { url, data }) => {
      try {
        const cleanUrl = url.split('?')[0];
        const apiPath = cleanUrl.substring(cleanUrl.indexOf('youtubei/v1'));
        websocketServer.logSystem(`[Monitor] Intercepted Studio API: ${apiPath}`);

        const parsed = JSON.parse(data);
        this.parseStudioApiData(url, parsed);
      } catch (err) {
        // Safe to ignore JSON parsing failures for partial chunks or non-JSON payloads
      }
    });

    // Listen to fallback DOM scraped values
    ipcMain.on('studio-dom-data', (event, data) => {
      if (data && data.videoId) {
        websocketServer.logSystem(`[Monitor] Fallback DOM scraped active Video ID: ${data.videoId}`);
        this.addOrUpdateStream({
          videoId: data.videoId,
          title: data.title || 'Studio Livestream',
          liveState: 'live',
          source: 'dom_scraped'
        });
      }
    });
  }

  parseStudioApiData(url, data) {
    // Live control data (legacy endpoint)
    if (url.includes('live_events/get_live_control_data')) {
      const videoId = data?.videoContext?.videoData?.videoId;
      const title = data?.videoContext?.videoData?.title;
      if (videoId) {
        this.addOrUpdateStream({
          videoId,
          title: title || 'Studio Livestream',
          liveState: 'live',
          source: 'api_live_control'
        });
      }
      return;
    }

    // Video metadata endpoint
    if (url.includes('metadata/get_video_metadata')) {
      const videoId = data?.videoData?.videoId;
      const title = data?.videoData?.title;
      if (videoId) {
        this.addOrUpdateStream({
          videoId,
          title: title || 'Studio Video',
          liveState: 'live',
          source: 'api_metadata'
        });
      }
      return;
    }

    // Broadcast status endpoint – provides live/upcoming/completed state
    if (url.includes('live/get_broadcast_status')) {
      let broadcast = data?.broadcast || data?.statusResponse || data?.result || {};
      if (Array.isArray(data?.broadcastStatus) && data.broadcastStatus.length > 0) {
        broadcast = data.broadcastStatus[0];
      }
      const videoId = broadcast?.videoId || data?.videoId;
      const title = broadcast?.title || data?.title;
      
      let liveState = 'unknown';
      const status = broadcast?.status || broadcast?.liveBroadcastDetails?.status || broadcast?.lifeCycleStatus || data?.status;
      if (status === 'STATUS_LIVE' || status === 'LIVE' || status === 'LIFE_CYCLE_LIVE') liveState = 'live';
      else if (status === 'STATUS_UPCOMING' || status === 'UPCOMING' || status === 'LIFE_CYCLE_UPCOMING' || status === 'BROADCAST_STATUS_UPCOMING') liveState = 'upcoming';
      else if (status === 'STATUS_ARCHIVED' || status === 'STATUS_COMPLETE' || status === 'COMPLETED' || status === 'LIFE_CYCLE_COMPLETED') liveState = 'completed';
      
      if (videoId) {
        websocketServer.logSystem(`[Monitor] Intercepted broadcast status for Video ID: ${videoId} (State: ${liveState})`);
        this.addOrUpdateStream({
          videoId,
          title: title || 'Studio Broadcast',
          liveState,
          source: 'api_broadcast_status'
        });
      }
      return;
    }

    // Fallback – log unknown API for debugging
    websocketServer.logSystem(`[Monitor] Unhandled API: ${url}`, 'debug');
  }

  startMonitoring() {
    if (this.isMonitoring) return;
    this.isMonitoring = true;

    websocketServer.logSystem('Starting YouTube Studio background monitor...');

    this.monitorWindow = new BrowserWindow({
      width: 1280,
      height: 900,
      show: false,
      webPreferences: {
        partition: youtubeSession.sessionPartition,
        preload: path.join(__dirname, '..', 'inject', 'studioMonitorInject.js'),
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        backgroundThrottling: false,
        paintWhenInitiallyHidden: false
      }
    });

    this.monitorWindow.setMenu(null);

    // ── URL change handler ──────────────────────────────────────────────────
    const handleUrlChange = (url) => {
      websocketServer.logSystem(`[Monitor] Studio Window navigated to: ${url}`);

      // 1. Channel landing → redirect to Live Control Room
      //    But only if not already on /livestreaming (avoids double-redirect)
      const channelMatch = url.match(/\/channel\/([^\/]+)/);
      if (channelMatch && !url.includes('/livestreaming') && !url.includes('accounts.google.com')) {
        const channelId = channelMatch[1];

        // Persist the Channel ID so next boot skips /dashboard entirely
        if (configStore.get('channelId') !== channelId) {
          configStore.set('channelId', channelId);
          websocketServer.logSystem(`[Monitor] Channel ID saved: ${channelId}`);
        }

        websocketServer.logSystem(`[Monitor] Channel landing detected. Redirecting to Live Control Room in 2s...`);

        // 2-second delay gives the SPA time to fully settle before we navigate away
        setTimeout(() => {
          if (this.monitorWindow && !this.monitorWindow.isDestroyed() && this.isMonitoring) {
            this.monitorWindow.loadURL(`https://studio.youtube.com/channel/${channelId}/livestreaming`);
          }
        }, 2000);
        return;
      }

      // 2. Active livestream control room → extract video ID
      //    Format: https://studio.youtube.com/video/VIDEO_ID/livestreaming
      const videoLiveMatch = url.match(/\/video\/([^/]+)\/livestreaming/);
      if (videoLiveMatch) {
        const videoId = videoLiveMatch[1];
        websocketServer.logSystem(`[Monitor] Active Video ID detected from URL: ${videoId}`);
        this.addOrUpdateStream({
          videoId,
          title: this.activeStream ? this.activeStream.title : 'Active Studio Stream',
          liveState: 'live',
          source: 'url_navigation'
        });
        return;
      }

      // 3. Video edit page → extract upcoming stream video ID
      //    Format: https://studio.youtube.com/video/VIDEO_ID/edit
      const videoEditMatch = url.match(/\/video\/([^/]+)\/edit/);
      if (videoEditMatch) {
        const videoId = videoEditMatch[1];
        websocketServer.logSystem(`[Monitor] Upcoming Video ID detected from edit URL: ${videoId}`);
        this.addOrUpdateStream({
          videoId,
          title: this.activeStream ? this.activeStream.title : 'Upcoming Studio Stream',
          liveState: 'upcoming',
          source: 'url_navigation_edit'
        });
      }
    };

    // Register BEFORE loadURL so we don't miss the first navigation event
    this.monitorWindow.webContents.on('did-navigate', (event, url) => {
      handleUrlChange(url);
    });

    this.monitorWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL, isMainFrame) => {
      // Only warn for the actual main page failing, not blocked tracking pixels / iframes
      if (isMainFrame && errorCode !== -3) { // -3 = ERR_ABORTED (expected during our redirect)
        websocketServer.logSystem(`Studio Monitor failed to load main page: ${errorDescription} (${errorCode})`, 'warning');
      }
    });

    // ── CSP-bypassing fetch/XHR monkeypatch injection ───────────────────────
    // YouTube Studio's Content Security Policy blocks inline <script> tag injection.
    // executeJavaScript() runs directly in the page's main world, bypassing CSP entirely.
    const MONKEYPATCH_CODE = `
      (function() {
        if (window.__obsChatRelayInstalled) return; // Prevent double-patching on SPA navigation
        window.__obsChatRelayInstalled = true;
        console.log('[OBS Chat Relay] Monkeypatching fetch and XHR in main world...');

        function sendData(url, responseText) {
          window.dispatchEvent(new CustomEvent('youtubei-api-data', {
            detail: { url: url, data: responseText }
          }));
        }

        // 1. Monkeypatch fetch
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
          const response = await origFetch.apply(this, args);
          try {
            const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
            if (url.includes('youtubei/v1/')) {
              const clone = response.clone();
              clone.text().then(text => sendData(url, text)).catch(() => {});
            }
          } catch(e) {}
          return response;
        };

        // 2. Monkeypatch XHR
        const origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
          this._obsUrl = url;
          return origOpen.apply(this, arguments);
        };
        const origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function() {
          this.addEventListener('load', function() {
            if (this._obsUrl && this._obsUrl.includes('youtubei/v1/')) {
              try { sendData(this._obsUrl, this.responseText); } catch(e) {}
            }
          });
          return origSend.apply(this, arguments);
        };

        // 3. DOM scraper fallback — runs every 5s to find video links on the page
        setInterval(function() {
          const videoIdEl = document.querySelector('[href*="watch?v="]');
          const titleEl   = document.querySelector('.title-text, #title-textarea textarea');
          if (videoIdEl) {
            const href  = videoIdEl.getAttribute('href') || '';
            const match = href.match(/[?&]v=([^&]+)/);
            if (match) {
              window.dispatchEvent(new CustomEvent('youtubei-dom-scraped', {
                detail: {
                  videoId: match[1],
                  title: titleEl ? (titleEl.value || titleEl.innerText || '').trim() : null
                }
              }));
            }
          }
        }, 5000);
      })();
    `;

    const injectMonkeypatch = () => {
      if (this.monitorWindow && !this.monitorWindow.isDestroyed()) {
        this.monitorWindow.webContents.executeJavaScript(MONKEYPATCH_CODE)
          .then(() => websocketServer.logSystem('[Monitor] Fetch/XHR monkeypatch injected successfully'))
          .catch(err => websocketServer.logSystem(`[Monitor] Monkeypatch injection failed: ${err.message}`, 'warning'));
      }
    };

    // Inject on every page load (dom-ready fires early enough to catch most SPA API calls)
    this.monitorWindow.webContents.on('dom-ready', injectMonkeypatch);

    // ── Decide initial URL ──────────────────────────────────────────────────
    // If we already know the Channel ID from a previous run, load /livestreaming directly.
    // This completely skips the /dashboard landing and eliminates the redirect race condition.
    const savedChannelId = configStore.get('channelId');
    if (savedChannelId) {
      websocketServer.logSystem(`[Monitor] Known Channel ID found (${savedChannelId}). Loading Live Control Room directly...`);
      this.monitorWindow.loadURL(`https://studio.youtube.com/channel/${savedChannelId}/livestreaming`);
    } else {
      websocketServer.logSystem('[Monitor] No Channel ID saved yet. Loading Studio dashboard to discover it...');
      this.monitorWindow.loadURL('https://studio.youtube.com/channel/live/dashboard');
    }

    // ── Periodic login check ────────────────────────────────────────────────
    this.checkInterval = setInterval(async () => {
      if (this.monitorWindow && !this.monitorWindow.isDestroyed()) {
        const currentUrl = this.monitorWindow.webContents.getURL();
        if (currentUrl.includes('accounts.google.com')) {
          websocketServer.logSystem('Studio Monitor redirected to Google login. User is logged out!', 'warning');
          this.stopMonitoring();
          BrowserWindow.getAllWindows().forEach(w => {
            if (!w.isDestroyed() && w !== this.monitorWindow) {
              w.webContents.send('login-status-changed', { loggedIn: false });
            }
          });
        }
      }
    }, 10000);

    // ── Periodic background sync ────────────────────────────────────────────
    // Reloads the hidden page every 45s to detect streams started externally
    this.reloadInterval = setInterval(() => {
      if (this.monitorWindow && !this.monitorWindow.isDestroyed() && this.isMonitoring) {
        websocketServer.logSystem('[Monitor] Periodic background sync checking for active streams...');
        this.monitorWindow.webContents.reload();
      }
    }, 45000);
  }

  stopMonitoring() {
    this.isMonitoring = false;
    if (this.checkInterval) {
      clearInterval(this.checkInterval);
      this.checkInterval = null;
    }
    if (this.reloadInterval) {
      clearInterval(this.reloadInterval);
      this.reloadInterval = null;
    }
    if (this.monitorWindow && !this.monitorWindow.isDestroyed()) {
      this.monitorWindow.destroy();
    }
    this.monitorWindow = null;
    websocketServer.logSystem('YouTube Studio background monitor stopped');
  }

  onStreamChange(callback) {
    this.onStreamChangeCallback = callback;
  }


  addOrUpdateStream(streamInfo) {
    if (!streamInfo.videoId) return;

    // Normalize liveState strings
    if (streamInfo.liveState === 'live' || streamInfo.liveState === 'STATUS_LIVE') {
      streamInfo.liveState = 'live';
    } else if (streamInfo.liveState === 'upcoming' || streamInfo.liveState === 'STATUS_UPCOMING') {
      streamInfo.liveState = 'upcoming';
    } else if (['completed', 'STATUS_ARCHIVED', 'STATUS_COMPLETE'].includes(streamInfo.liveState)) {
      streamInfo.liveState = 'completed';
    }

    // Never downgrade a stream we already know is live
    const existing = this.streamsMap.get(streamInfo.videoId);
    if (existing && existing.liveState === 'live' && streamInfo.liveState !== 'live') {
      streamInfo.liveState = 'live';
    }

    this.streamsMap.set(streamInfo.videoId, {
      ...existing,
      ...streamInfo,
      timestamp: Date.now()
    });

    this.evaluateActiveStream();
  }

  evaluateActiveStream() {
    const streams = Array.from(this.streamsMap.values());

    // Priority: live > upcoming (nearest first) > newest
    streams.sort((a, b) => {
      if (a.liveState === 'live' && b.liveState !== 'live') return -1;
      if (b.liveState === 'live' && a.liveState !== 'live') return 1;
      if (a.liveState === 'upcoming' && b.liveState !== 'upcoming') return -1;
      if (b.liveState === 'upcoming' && a.liveState !== 'upcoming') return 1;
      if (a.liveState === 'upcoming' && b.liveState === 'upcoming') {
        return (a.scheduledTime || 0) - (b.scheduledTime || 0);
      }
      return b.timestamp - a.timestamp;
    });

    if (streams.length === 0) return;

    const bestStream = streams[0];

    if (
      !this.activeStream ||
      this.activeStream.videoId !== bestStream.videoId ||
      this.activeStream.liveState !== bestStream.liveState
    ) {
      const oldId = this.activeStream ? this.activeStream.videoId : 'none';
      this.activeStream = bestStream;

      websocketServer.logSystem(
        `Stream Auto-Switch: ${oldId} → ${bestStream.videoId} "${bestStream.title}" [${bestStream.liveState}]`
      );

      websocketServer.updateStreamState(bestStream);

      if (this.onStreamChangeCallback) {
        this.onStreamChangeCallback(bestStream);
      }
    }
  }

  clearState() {
    this.streamsMap.clear();
    this.activeStream = null;
    websocketServer.updateStreamState(null);
  }

  getActiveStream() {
    return this.activeStream;
  }
}

module.exports = new StreamManager();
