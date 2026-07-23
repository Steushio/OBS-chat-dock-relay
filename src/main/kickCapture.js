const { BrowserWindow } = require('electron');
const WebSocket = require('ws');
const websocketServer = require('./websocketServer');

class KickCapture {
  constructor() {
    this.ws = null;
    this.chatroomId = null;
    this.username = null;
    this.isEnabled = false;
    this.tempWindow = null;
    this.reconnectTimeout = null;
    this.viewerPollInterval = null;
    this.viewers = 0;
    this.isLive = false;
    this.lastStatusText = 'Idle';
  }

  async start(username) {
    if (!username) return;
    
    // Stop any existing capture first
    this.stop();

    this.username = username;
    this.isEnabled = true;
    websocketServer.logSystem(`[Kick] Starting capture for channel: ${username}`);
    
    try {
      const channelData = await this.resolveChannelData(username);
      this.chatroomId = channelData.chatroomId;
      this.viewers = channelData.viewers;
      this.isLive = channelData.isLive;

      if (!this.chatroomId) {
        websocketServer.logSystem(`[Kick] Failed to resolve chatroom ID for ${username}`, 'error');
        this.updateStatusUI('Failed to resolve Chatroom ID', 0, false);
        return;
      }
      websocketServer.logSystem(`[Kick] Chatroom ID resolved: ${this.chatroomId} (Viewers: ${this.viewers}, Live: ${this.isLive})`);
      this.updateStatusUI('Connecting WebSocket...', this.viewers, this.isLive);
      this.connectWebSocket();
      this.startViewerPolling();
    } catch (err) {
      websocketServer.logSystem(`[Kick] Error resolving chatroom ID: ${err.message}`, 'error');
      this.updateStatusUI(`Error: ${err.message}`, 0, false);
    }
  }

  stop() {
    this.isEnabled = false;
    this.stopViewerPolling();
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    if (this.tempWindow && !this.tempWindow.isDestroyed()) {
      this.tempWindow.destroy();
    }
    this.tempWindow = null;
    this.viewers = 0;
    this.isLive = false;
    this.updateStatusUI('Idle', 0, false);
    websocketServer.logSystem('[Kick] Chat capture stopped.');
  }

  startViewerPolling() {
    this.stopViewerPolling();
    this.viewerPollInterval = setInterval(async () => {
      if (!this.isEnabled || !this.username) return;
      try {
        const channelData = await this.resolveChannelData(this.username);
        if (channelData && channelData.viewers !== undefined) {
          this.viewers = channelData.viewers;
          this.isLive = channelData.isLive;
          this.updateStatusUI(this.lastStatusText, this.viewers, this.isLive);
        }
      } catch (err) {
        // Silent catch for poll errors
      }
    }, 30000);
  }

  stopViewerPolling() {
    if (this.viewerPollInterval) {
      clearInterval(this.viewerPollInterval);
      this.viewerPollInterval = null;
    }
  }

  async resolveChannelData(username) {
    // Try lightweight zero-CPU net.fetch first
    try {
      const { net } = require('electron');
      const response = await net.fetch(`https://kick.com/api/v2/channels/${username}`, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
          'Accept': 'application/json'
        }
      });
      if (response.ok) {
        const json = await response.json();
        const chatroomId = json?.chatroom?.id;
        const isLive = !!json?.livestream?.is_live;
        const viewers = isLive ? (json?.livestream?.viewer_count ?? json?.livestream?.viewers ?? 0) : 0;
        if (chatroomId) {
          return { chatroomId, viewers, isLive };
        }
      }
    } catch (e) {
      // Fallback to BrowserWindow if blocked by Cloudflare
    }

    return new Promise((resolve, reject) => {
      this.updateStatusUI('Resolving Chatroom ID...', this.viewers || 0, this.isLive);
      
      if (!this.tempWindow || this.tempWindow.isDestroyed()) {
        this.tempWindow = new BrowserWindow({
          width: 100,
          height: 100,
          show: false,
          skipTaskbar: true,
          frame: false,
          webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            sandbox: true,
            backgroundThrottling: true,
            paintWhenInitiallyHidden: false,
            images: false,
            webgl: false,
            enableWebSQL: false
          }
        });
        this.tempWindow.setMenu(null);
        if (this.tempWindow.webContents) {
          this.tempWindow.webContents.setAudioMuted(true);
        }
      }

      const win = this.tempWindow;

      const handleLoadFinish = async () => {
        try {
          const bodyText = await win.webContents.executeJavaScript('document.body.innerText');
          const json = JSON.parse(bodyText);
          const chatroomId = json?.chatroom?.id;
          const isLive = !!json?.livestream?.is_live;
          const viewers = isLive ? (json?.livestream?.viewer_count ?? json?.livestream?.viewers ?? 0) : 0;
          if (chatroomId) {
            resolve({ chatroomId, viewers, isLive });
          } else {
            reject(new Error('Channel does not exist or chatroom ID not found'));
          }
        } catch (err) {
          reject(err);
        } finally {
          win.webContents.removeAllListeners('did-finish-load');
          win.webContents.removeAllListeners('did-fail-load');
        }
      };

      const handleLoadFail = (event, errorCode, errorDescription) => {
        win.webContents.removeAllListeners('did-finish-load');
        win.webContents.removeAllListeners('did-fail-load');
        reject(new Error(`Failed to load Kick API page: ${errorDescription} (${errorCode})`));
      };

      win.webContents.on('did-finish-load', handleLoadFinish);
      win.webContents.on('did-fail-load', handleLoadFail);

      win.loadURL(`https://kick.com/api/v2/channels/${username}`);
    });
  }

  connectWebSocket() {
    if (!this.isEnabled) return;
    if (this.ws) {
      this.ws.close();
    }

    const pusherUrl = 'wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679?protocol=7&client=js&version=8.4.0-rc2&flash=false';
    this.ws = new WebSocket(pusherUrl);

    this.ws.on('open', () => {
      websocketServer.logSystem('[Kick] Pusher WebSocket connected!');
      this.updateStatusUI('Connected');
      
      // Subscribe to chatroom
      const subMsg = {
        event: 'pusher:subscribe',
        data: {
          auth: '',
          channel: `chatrooms.${this.chatroomId}.v2`
        }
      };
      this.ws.send(JSON.stringify(subMsg));
    });

    this.ws.on('message', (data) => {
      try {
        const payload = JSON.parse(data.toString());
        this.handleMessage(payload);
      } catch (err) {
        console.error('[Kick WS] Error parsing message:', err);
      }
    });

    this.ws.on('close', () => {
      websocketServer.logSystem('[Kick] Pusher WebSocket disconnected');
      if (this.isEnabled) {
        this.updateStatusUI('Reconnecting...');
        this.reconnectTimeout = setTimeout(() => this.connectWebSocket(), 5000);
      } else {
        this.updateStatusUI('Idle');
      }
    });

    this.ws.on('error', (err) => {
      websocketServer.logSystem(`[Kick] Pusher WebSocket error: ${err.message}`, 'error');
    });
  }

  handleMessage(payload) {
    const { event, channel, data: dataStr } = payload;
    if (!dataStr) return;

    let data;
    try {
      data = JSON.parse(dataStr);
    } catch (err) {
      return;
    }

    if (event === 'App\\Events\\ChatMessageEvent') {
      const id = data.id || `kick_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
      const username = data.sender?.username || 'Anonymous';
      const message = data.content || '';
      
      // Map badges
      const badges = [];
      if (data.sender?.identity?.badges) {
        data.sender.identity.badges.forEach(b => {
          if (b.type === 'moderator') badges.push('moderator');
          else if (b.type === 'broadcaster' || b.type === 'owner') badges.push('owner');
          else if (b.type === 'subscriber') badges.push('member');
          else badges.push(b.type);
        });
      }

      const chatData = {
        platform: 'kick',
        type: 'chat',
        id,
        username,
        message,
        avatar: '', // Public WS doesn't expose avatar, but fallback handles empty gracefully
        badges,
        timestamp: Date.now()
      };

      websocketServer.broadcast('chat', chatData);
    } 
    else if (event === 'App\\Events\\SubscriptionEvent') {
      const id = `kick_sub_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
      const username = data.username || 'Subscriber';
      const months = data.duration || 1;
      const message = `Subscribed for ${months} month${months > 1 ? 's' : ''}!`;

      const alertData = {
        platform: 'kick',
        type: 'membership', // maps to Membership alert
        id,
        username,
        avatar: '',
        message,
        timestamp: Date.now()
      };

      websocketServer.broadcast('alert', alertData);
      websocketServer.broadcast('chat', alertData);
      websocketServer.logSystem(`[Kick Alert] New Subscriber: ${username} (Month ${months})`);
    } 
    else if (event === 'App\\Events\\GiftedSubscriptionsEvent') {
      const id = `kick_gift_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
      const gifter = data.gifter_username || 'Gifter';
      const count = data.gifted_usernames?.length || 1;
      const message = `Gifted ${count} subscription${count > 1 ? 's' : ''}!`;

      const alertData = {
        platform: 'kick',
        type: 'membership_gift', // maps to Membership Gift alert
        id,
        username: gifter,
        avatar: '',
        message,
        timestamp: Date.now()
      };

      websocketServer.broadcast('alert', alertData);
      websocketServer.broadcast('chat', alertData);
      websocketServer.logSystem(`[Kick Alert] Gift subscription: ${gifter} gifted ${count} subs`);
    }
    else if (event === 'App\\Events\\FollowEvent') {
      const id = `kick_follow_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
      const username = data.username || 'Follower';
      const message = 'Just followed!';

      const alertData = {
        platform: 'kick',
        type: 'subscription', // maps to New Subscriber (Follow) alert
        id,
        username,
        avatar: '',
        message,
        timestamp: Date.now()
      };

      websocketServer.broadcast('alert', alertData);
      websocketServer.broadcast('chat', alertData);
      websocketServer.logSystem(`[Kick Alert] New Follower: ${username}`);
    }
  }

  updateStatusUI(statusText, viewers = this.viewers || 0, isLive = this.isLive) {
    this.lastStatusText = statusText;
    this.viewers = viewers;
    if (isLive !== undefined) this.isLive = isLive;

    const kickState = {
      status: statusText,
      viewers: this.viewers,
      isLive: !!this.isLive,
      isEnabled: this.isEnabled
    };

    const { BrowserWindow } = require('electron');
    BrowserWindow.getAllWindows().forEach(w => {
      if (!w.isDestroyed()) {
        w.webContents.send('kick-status-changed', kickState);
      }
    });

    websocketServer.updateKickState(kickState);
  }
}

module.exports = new KickCapture();
