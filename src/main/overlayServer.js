const express = require('express');
const path = require('path');
const fs = require('fs');
const http = require('http');

class OverlayServer {
  constructor() {
    this.app = express();
    this.server = null;
    this.port = 3000;

    this.setupRoutes();
  }

  setupRoutes() {
    // Enable CORS for all routes (to support Electron's file:// origin in dashboard)
    this.app.use((req, res, next) => {
      res.header('Access-Control-Allow-Origin', '*');
      res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept');
      next();
    });

    // Serve static files inside overlays directory (e.g. css/js if split, or direct files)
    const overlaysDir = path.join(__dirname, '..', 'overlays');
    this.app.use(express.static(overlaysDir));

    // Custom exact routes to match specification
    this.app.get('/chatdock', (req, res) => {
      res.sendFile(path.join(overlaysDir, 'chatdock.html'));
    });

    this.app.get('/alertdock', (req, res) => {
      res.sendFile(path.join(overlaysDir, 'alertdock.html'));
    });

    this.app.get('/eventdock', (req, res) => {
      res.sendFile(path.join(overlaysDir, 'eventdock.html'));
    });

    this.app.get('/tunnel-dock', (req, res) => {
      res.sendFile(path.join(overlaysDir, 'tunnel-dock.html'));
    });

    this.app.get('/tunnel-dock.html', (req, res) => {
      res.sendFile(path.join(overlaysDir, 'tunnel-dock.html'));
    });

    // Endpoint to stream local audio files for OBS docks
    this.app.get('/audio', (req, res) => {
      const filePath = req.query.path;
      if (!filePath) {
        return res.status(400).send('Path parameter is required');
      }
      res.sendFile(filePath, (err) => {
        if (err) {
          res.status(404).send('File not found');
        }
      });
    });

    // Route to manually refresh stream state and restart discovery
    this.app.get('/refresh-stream', (req, res) => {
      try {
        const streamManager = require('./streamManager');
        const chatCapture = require('./chatCapture');
        streamManager.clearState();
        chatCapture.stopCapture();
        res.json({ success: true, message: 'Stream search restarted successfully' });
      } catch (err) {
        res.status(500).json({ success: false, error: err.message });
      }
    });

    // Route to manually clear OBS chat dock
    this.app.get('/clear-chat', (req, res) => {
      try {
        const websocketServer = require('./websocketServer');
        websocketServer.clearChat();
        res.json({ success: true, message: 'OBS chat docks cleared' });
      } catch (err) {
        res.status(500).json({ success: false, error: err.message });
      }
    });

    // Route to toggle auto-clear old chat setting directly from OBS dock
    this.app.get('/toggle-clear-chat', (req, res) => {
      try {
        const configStore = require('./configStore');
        const websocketServer = require('./websocketServer');
        const currentState = !!configStore.get('clearOldChat');
        const newState = !currentState;
        configStore.set('clearOldChat', newState);

        websocketServer.broadcast('settings_update', {
          clearOldChat: newState,
          clearOldChatLimit: configStore.get('clearOldChatLimit') || 30,
          clearOldChatTimeout: configStore.get('clearOldChatTimeout') || 120
        });

        try {
          const { BrowserWindow } = require('electron');
          BrowserWindow.getAllWindows().forEach(w => {
            if (!w.isDestroyed()) {
              w.webContents.send('settings-updated', { clearOldChat: newState });
            }
          });
        } catch (e) {}

        res.json({ success: true, clearOldChat: newState });
      } catch (err) {
        res.status(500).json({ success: false, error: err.message });
      }
    });

    // Route to toggle Low Resource (Eco) Mode directly from OBS dock
    this.app.get('/toggle-low-resource', (req, res) => {
      try {
        const configStore = require('./configStore');
        const websocketServer = require('./websocketServer');
        const currentState = !!configStore.get('lowResourceMode');
        const newState = !currentState;
        configStore.set('lowResourceMode', newState);

        websocketServer.broadcast('settings_update', {
          lowResourceMode: newState
        });

        try {
          const { BrowserWindow } = require('electron');
          BrowserWindow.getAllWindows().forEach(w => {
            if (!w.isDestroyed()) {
              w.webContents.send('settings-updated', { lowResourceMode: newState });
            }
          });
        } catch (e) {}

        res.json({ success: true, lowResourceMode: newState });
      } catch (err) {
        res.status(500).json({ success: false, error: err.message });
      }
    });

    // Account & Session sync endpoint for mobile companion app
    this.app.get('/api/sync-accounts', (req, res) => {
      try {
        const configStore = require('./configStore');
        const streamManager = require('./streamManager');
        const activeStream = streamManager.getActiveStream();

        res.json({
          success: true,
          kickUsername: configStore.get('kickUsername') || 'steushio',
          kickBroadcasterEmail: configStore.get('kickBroadcasterEmail') || '',
          kickBotUsername: configStore.get('kickBotUsername') || configStore.get('kickUsername') || 'steushio',
          kickBotEmail: configStore.get('kickBotEmail') || '',
          kickChatroomId: configStore.get('kickChatroomId') || '33438685',
          youtubeChannel: (activeStream && activeStream.channelTitle) || configStore.get('lastStreamer') || 'YouTube Streamer',
          youtubeBroadcasterEmail: configStore.get('ytBroadcasterEmail') || '',
          youtubeBotChannel: configStore.get('youtubeBotChannel') || '',
          youtubeBotEmail: configStore.get('ytBotEmail') || '',
          youtubeVideoId: (activeStream && activeStream.videoId) || configStore.get('lastVideoId') || '',
          ollamaBotSettings: configStore.get('ollamaBotSettings') || {}
        });
      } catch (e) {
        res.status(500).json({ error: e.message });
      }
    });

    // Endpoint for mobile data relay to push events over HTTP
    this.app.post('/api/relay-event', express.json(), (req, res) => {
      try {
        const { event, data } = req.body;
        if (event && data) {
          const websocketServer = require('./websocketServer');
          websocketServer.broadcast(event, data);
          if (event === 'remote_transmitter_connected') {
            try {
              const tunnelManager = require('./tunnelManager');
              tunnelManager.registerDevice({
                deviceId: (data && data.device) || 'Android Mobile (HTTP)',
                device: (data && data.device) || 'Android Mobile (HTTP)',
                ip: req.ip || 'unknown',
                cloudRoom: data && data.cloudRoom,
                connectedAt: Date.now()
              });
            } catch (e) {}
          } else if (event === 'chat') {
            try {
              const ollamaBot = require('./ollamaBot');
              if (ollamaBot && typeof ollamaBot.handleChatMessage === 'function') {
                ollamaBot.handleChatMessage(data);
              }
            } catch (e) {}
          }
          return res.json({ success: true });
        }
        res.status(400).json({ error: 'Missing event or data' });
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });

    // Direct APK download route for mobile installation without USB cable
    this.app.get(['/app-debug.apk', '/download', '/apk'], (req, res) => {
      const apkPath = path.join(__dirname, '..', '..', 'app-debug.apk');
      if (fs.existsSync(apkPath)) {
        res.download(apkPath, 'OBS-Chat-Relay.apk');
      } else {
        res.status(404).send('APK not found. Please compile first.');
      }
    });

    // Tunnel and remote gateway APIs
    this.app.get('/api/tunnel-status', (req, res) => {
      try {
        const tunnelManager = require('./tunnelManager');
        res.json(tunnelManager.getStatus());
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });

    this.app.get('/api/tunnel-qr', async (req, res) => {
      try {
        const QRCode = require('qrcode');
        const tunnelManager = require('./tunnelManager');
        const status = tunnelManager.getStatus();
        const connectUrl = status.wsUrl || status.tunnelUrl || `ws://${status.localIp}:${status.localPort}`;
        
        // Structured QR payload with auto-fallback to raw URL
        const qrPayload = JSON.stringify({
          type: 'obs_chat_relay',
          version: 1,
          wsUrl: connectUrl,
          httpUrl: status.tunnelUrl || `http://${status.localIp}:${status.localPort}`,
          host: status.host,
          port: status.port,
          mode: status.mode
        });

        const qrDataUrl = await QRCode.toDataURL(qrPayload, {
          margin: 1,
          color: { dark: '#000000', light: '#ffffff' },
          width: 280
        });
        res.json({ qr: qrDataUrl, payload: qrPayload, url: connectUrl });
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });

    this.app.post('/api/tunnel-start', express.json(), async (req, res) => {
      try {
        const tunnelManager = require('./tunnelManager');
        const status = await tunnelManager.startTunnel(req.body || {});
        res.json(status);
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });

    this.app.post('/api/tunnel-stop', (req, res) => {
      try {
        const tunnelManager = require('./tunnelManager');
        const status = tunnelManager.stopTunnel();
        res.json(status);
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });

    // Root status API
    this.app.get('/status', (req, res) => {
      const configStore = require('./configStore');
      res.json({ 
        status: 'running', 
        service: 'OBS Chat Relay HTTP Server',
        wsPort: configStore.get('wsPort') || 3001,
        lowResourceMode: configStore.get('lowResourceMode') || false,
        clearOldChat: configStore.get('clearOldChat') || false,
        clearOldChatLimit: configStore.get('clearOldChatLimit') || 30,
        clearOldChatTimeout: configStore.get('clearOldChatTimeout') || 120
      });
    });
  }

  start(port) {
    return new Promise((resolve, reject) => {
      if (this.server) {
        this.stop();
      }

      this.port = port || 3000;
      this.server = http.createServer(this.app);

      this.server.listen(this.port, '0.0.0.0', () => {
        console.log(`[OverlayServer] HTTP Server listening on http://0.0.0.0:${this.port}`);
        
        // Attach WebSocket server so port 3000 and tunnels can handle both HTTP and WS
        try {
          const websocketServer = require('./websocketServer');
          websocketServer.attachToHttpServer(this.server);
        } catch (e) {
          console.error('[OverlayServer] Failed to attach WebSocket to HTTP server:', e);
        }

        // Auto-start zero-config cloud tunnel in background with permanent fixed subdomain
        try {
          const tunnelManager = require('./tunnelManager');
          tunnelManager.startTunnel({ localPort: this.port, subdomain: 'steushio-relay' }).catch((err) => {
            console.warn('[OverlayServer] Cloud tunnel background start warning:', err.message);
          });
        } catch (e) {}

        resolve();
      });

      this.server.on('error', (err) => {
        console.error('[OverlayServer] Failed to start HTTP server:', err);
        reject(err);
      });
    });
  }

  stop() {
    try {
      const tunnelManager = require('./tunnelManager');
      tunnelManager.stopTunnel();
    } catch (e) {}

    if (this.server) {
      this.server.close(() => {
        console.log('[OverlayServer] HTTP Server stopped');
      });
      this.server = null;
    }
  }
}

module.exports = new OverlayServer();
