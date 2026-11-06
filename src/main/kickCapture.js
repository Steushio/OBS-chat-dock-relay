const { BrowserWindow } = require('electron');
const WebSocket = require('ws');
const websocketServer = require('./websocketServer');

class KickCapture {
  constructor() {
    this.ws = null;
    this.chatroomId = '33438685';
    this.channelId = '33727011';
    this.username = 'steushio';
    this.isEnabled = false;
    this.tempWindow = null;
    this.reconnectTimeout = null;
    this.viewerPollInterval = null;
    this.viewers = 0;
    this.isLive = false;
    this.lastStatusText = 'Idle';
    this.pusherAppKey = '34bf7a0ff419a2a775b9';
    this.pusherCluster = 'us2';
  }

  async start(username, retryCount = 0) {
    if (!username) {
      const configStore = require('./configStore');
      username = configStore.get('kickUsername') || 'steushio';
    }
    
    if (retryCount === 0) {
      this.stop();
      this.username = username;
      this.isEnabled = true;
      websocketServer.logSystem(`[Kick] Starting capture for channel: ${username}`);
      try {
        const botAccountSession = require('./botAccountSession');
        botAccountSession.prewarmKickBotChat(username);
        botAccountSession.prewarmKickBroadcasterChat(username);
      } catch (e) {}
    }

    const configStore = require('./configStore');
    const cachedCid = this.chatroomId || configStore.get('kickChatroomId') || (username.toLowerCase() === 'steushio' ? '33438685' : null);

    if (cachedCid) {
      this.chatroomId = cachedCid;
      websocketServer.logSystem(`[Kick] Active Chatroom ID: ${this.chatroomId} (Channel: ${username})`);
      this.updateStatusUI('Connecting WebSocket...', this.viewers, this.isLive);
      this.connectWebSocket();
      this.startViewerPolling();
      return;
    }

    try {
      this.updateStatusUI(retryCount > 0 ? `Resolving Chatroom ID (Attempt ${retryCount + 1}/4)...` : 'Resolving Chatroom ID...', 0, false);
      const channelData = await this.resolveChannelData(username);
      this.chatroomId = channelData.chatroomId;
      this.viewers = channelData.viewers;
      this.isLive = channelData.isLive;

      if (!this.chatroomId) {
        throw new Error('Chatroom ID resolved as null or empty');
      }

      websocketServer.logSystem(`[Kick] Chatroom ID resolved: ${this.chatroomId} (Viewers: ${this.viewers}, Live: ${this.isLive})`);
      this.updateStatusUI('Connecting WebSocket...', this.viewers, this.isLive);
      this.connectWebSocket();
      this.startViewerPolling();
    } catch (err) {
      if (this.isEnabled && retryCount < 3) {
        websocketServer.logSystem(`[Kick] Chatroom ID resolution attempt ${retryCount + 1} failed: ${err.message}. Retrying in 3s...`, 'warn');
        this.updateStatusUI(`Retrying in 3s (${retryCount + 1}/4)...`, 0, false);
        this.reconnectTimeout = setTimeout(() => {
          if (this.isEnabled) this.start(username, retryCount + 1);
        }, 3000);
      } else {
        websocketServer.logSystem(`[Kick] Failed to resolve chatroom ID after retries: ${err.message}`, 'error');
        this.updateStatusUI(`Failed to resolve Chatroom ID`, 0, false);
      }
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
    return new Promise((resolve, reject) => {
      const timeoutId = setTimeout(() => {
        if (this.tempWindow && !this.tempWindow.isDestroyed()) {
          this.tempWindow.webContents.removeAllListeners('did-finish-load');
          this.tempWindow.webContents.removeAllListeners('did-fail-load');
        }
        reject(new Error('Timeout loading Kick channel page'));
      }, 25000);

      if (!this.tempWindow || this.tempWindow.isDestroyed()) {
        this.tempWindow = new BrowserWindow({
          width: 800,
          height: 600,
          show: false,
          skipTaskbar: true,
          frame: false,
          webPreferences: {
            partition: 'persist:kick',
            nodeIntegration: false,
            contextIsolation: true,
            sandbox: true,
            backgroundThrottling: false
          }
        });
        this.tempWindow.setMenu(null);
        if (this.tempWindow.webContents) {
          this.tempWindow.webContents.setAudioMuted(true);
          const chromeUA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
          this.tempWindow.webContents.setUserAgent(chromeUA);
        }
      }

      const win = this.tempWindow;

      const handleLoadFinish = async () => {
        clearTimeout(timeoutId);
        try {
          // Multi-strategy extraction script inside page context
          const json = await win.webContents.executeJavaScript(`
            (async function() {
              const username = ${JSON.stringify(username)};
              
              // Strategy 1: Check embedded __NEXT_DATA__ or DOM script tags
              try {
                const nextDataEl = document.getElementById('__NEXT_DATA__');
                if (nextDataEl && nextDataEl.textContent) {
                  const parsed = JSON.parse(nextDataEl.textContent);
                  const channel = parsed?.props?.pageProps?.channel;
                  if (channel?.chatroom?.id) {
                    return {
                      chatroom: channel.chatroom,
                      livestream: channel.livestream,
                      channelId: channel.id
                    };
                  }
                }
              } catch (e) {}

              // Strategy 2: Fetch API with retries & clearance delay
              const endpoints = [
                'https://kick.com/api/v2/channels/' + username,
                'https://kick.com/api/v1/channels/' + username,
                'https://kick.com/api/internal/v1/channels/' + username
              ];

              for (const endpoint of endpoints) {
                for (let attempt = 0; attempt < 3; attempt++) {
                  try {
                    const res = await fetch(endpoint, {
                      headers: { 'Accept': 'application/json' }
                    });
                    if (res.ok) {
                      const data = await res.json();
                      if (data?.chatroom?.id) return {
                        chatroom: data.chatroom,
                        livestream: data.livestream,
                        channelId: data.id || data.channel_id
                      };
                    }
                  } catch (e) {}
                  await new Promise(r => setTimeout(r, 1200));
                }
              }

              return null;
            })();
          `);

          if (!json) {
            reject(new Error('Failed to resolve Kick channel data from page context'));
            return;
          }

          const chatroomId = json?.chatroom?.id;
          const isLive = !!json?.livestream?.is_live;
          const viewers = isLive ? (json?.livestream?.viewer_count ?? json?.livestream?.viewers ?? 0) : 0;
          const channelId = json?.channelId || json?.id || null;
          if (channelId) {
            this.channelId = channelId;
          }
          if (chatroomId) {
            this.chatroomId = chatroomId;
            try {
              const configStore = require('./configStore');
              configStore.set('kickChatroomId', chatroomId);
              if (channelId) configStore.set('kickChannelId', channelId);
            } catch (e) {}
            resolve({ chatroomId, viewers, isLive, channelId });
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
        clearTimeout(timeoutId);
        win.webContents.removeAllListeners('did-finish-load');
        win.webContents.removeAllListeners('did-fail-load');
        reject(new Error(`Failed to load Kick channel page: ${errorDescription} (${errorCode})`));
      };

      win.webContents.on('did-finish-load', handleLoadFinish);
      win.webContents.on('did-fail-load', handleLoadFail);

      // Load main channel page so browser solves Cloudflare challenges
      win.loadURL(`https://kick.com/${username}`);
    });
  }

  async fetchPusherConfig(channelId = this.channelId) {
    if (!channelId) return null;
    try {
      const https = require('https');
      const postData = JSON.stringify({
        client: { id: 'obs-relay', type: 'web' },
        capabilities: { accepted_providers: [{ provider: 'pusher' }] }
      });

      const config = await new Promise((resolve) => {
        const req = https.request(`https://web.kick.com/api/v1/realtime/channels/${channelId}/chat/connection`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(postData),
            'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
          },
          timeout: 6000
        }, (res) => {
          let data = '';
          res.on('data', chunk => data += chunk);
          res.on('end', () => {
            try {
              const json = JSON.parse(data);
              const conn = json?.data?.connections?.find(c => c.provider === 'pusher');
              if (conn?.credentials?.app_key) {
                resolve({
                  appKey: conn.credentials.app_key,
                  cluster: conn.credentials.cluster || 'us2'
                });
                return;
              }
            } catch (e) {}
            resolve(null);
          });
        });
        req.on('error', () => resolve(null));
        req.on('timeout', () => { req.destroy(); resolve(null); });
        req.write(postData);
        req.end();
      });
      return config;
    } catch (e) {
      return null;
    }
  }

  async connectWebSocket() {
    if (!this.isEnabled) return;
    if (this.ws) {
      this.ws.close();
    }

    let appKey = this.pusherAppKey || '34bf7a0ff419a2a775b9';
    let cluster = this.pusherCluster || 'us2';

    try {
      const resolved = await this.fetchPusherConfig(this.channelId || '33727011');
      if (resolved?.appKey) {
        appKey = resolved.appKey;
        cluster = resolved.cluster || 'us2';
        this.pusherAppKey = appKey;
        this.pusherCluster = cluster;
      }
    } catch (e) {}

    const pusherUrl = `wss://ws-${cluster}.pusher.com/app/${appKey}?protocol=7&client=js&version=8.4.0-rc2&flash=false`;
    this.ws = new WebSocket(pusherUrl);

    this.ws.on('open', () => {
      websocketServer.logSystem(`[Kick] Pusher WebSocket connected (Key: ${appKey.substring(0, 6)}..., Cluster: ${cluster})!`);
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

    this.ws.on('close', (code, reason) => {
      const reasonStr = reason ? reason.toString() : '';
      websocketServer.logSystem(`[Kick] Pusher WebSocket disconnected (code: ${code}${reasonStr ? ', reason: ' + reasonStr : ''})`);
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

    const isChat = 
      event === 'App\\Events\\ChatMessageEvent' || 
      event === 'App\\Events\\ChatMessageSentEvent' ||
      event === 'ChatMessageEvent' ||
      event === 'ChatMessageSentEvent';

    if (isChat) {
      if (data.chatroom_id) {
        this.chatroomId = data.chatroom_id;
      }
      const id = data.id || `kick_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
      const username = data.sender?.username || data.user?.username || data.username || 'Anonymous';
      const message = data.content || data.message?.content || (typeof data.message === 'string' ? data.message : '');
      
      // Map badges
      const rawBadges = data.sender?.identity?.badges || data.user?.identity?.badges || data.badges || [];
      const badges = [];
      if (Array.isArray(rawBadges)) {
        rawBadges.forEach(b => {
          const type = (b && typeof b === 'object') ? b.type : b;
          if (type === 'moderator') badges.push('moderator');
          else if (type === 'broadcaster' || type === 'owner') badges.push('owner');
          else if (type === 'subscriber') badges.push('member');
          else if (type) badges.push(type);
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
      try {
        const ollamaBot = require('./ollamaBot');
        if (ollamaBot && typeof ollamaBot.handleChatMessage === 'function') {
          ollamaBot.handleChatMessage(chatData);
        } else {
          console.error('[Kick WS] ollamaBot.handleChatMessage not found');
        }
      } catch (err) {
        console.error('[Kick WS] Error routing chat to ollamaBot:', err);
      }
    } 
    else if (event === 'App\\Events\\SubscriptionEvent' || event === 'SubscriptionEvent') {
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
    else if (event === 'App\\Events\\GiftedSubscriptionsEvent' || event === 'GiftedSubscriptionsEvent') {
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
    else if (event === 'App\\Events\\FollowEvent' || event === 'FollowEvent') {
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

  async sendMessage(messageText) {
    if (!messageText || !messageText.trim()) return false;
    const botAccountSession = require('./botAccountSession');
    return await botAccountSession.sendKickBroadcasterMessage(this.chatroomId, this.username, messageText);
  }
}

module.exports = new KickCapture();
