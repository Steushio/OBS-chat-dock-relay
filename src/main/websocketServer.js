const WebSocket = require('ws');

class WebSocketServer {
  constructor() {
    this.wss = null;
    this.clients = new Set();
    this.port = 3001;
    this.currentStreamState = null;
    this.currentKickState = null;
    this.logs = []; // Kept for eventdock initial sync
  }

  start(port) {
    if (this.wss) {
      this.stop();
    }

    this.port = port || 3001;
    this.wss = new WebSocket.Server({ port: this.port, host: '0.0.0.0' }, () => {
      this.logSystem(`WebSocket Server listening on ws://0.0.0.0:${this.port}`);
    });

    this.wss.on('connection', (ws, req) => {
      this.handleConnection(ws, req);
    });

    this.wss.on('error', (err) => {
      this.logSystem(`Failed to start WebSocket server: ${err.message}`, 'error');
    });
  }

  attachToHttpServer(httpServer) {
    if (!httpServer) return;
    try {
      if (this.httpWss) {
        try { this.httpWss.close(); } catch (e) {}
      }
      this.httpWss = new WebSocket.Server({ server: httpServer });
      this.httpWss.on('connection', (ws, req) => {
        this.handleConnection(ws, req);
      });
      this.logSystem('WebSocket server attached to HTTP server (port 3000 / cloud tunnel)');
    } catch (e) {
      console.error('[WebSocketServer] Error attaching to HTTP server:', e);
    }
  }

  handleConnection(ws, req) {
    this.clients.add(ws);
    const clientIp = (req && req.socket && req.socket.remoteAddress) || 'unknown';
    this.logSystem(`Client connected (${clientIp})`);

    // Send initial handshake state
    const configStore = require('./configStore');
    ws.send(JSON.stringify({
      event: 'handshake',
      data: {
        status: 'connected',
        stream: this.currentStreamState,
        kickStream: this.currentKickState,
        recentLogs: this.logs.slice(-50),
        lowResourceMode: configStore.get('lowResourceMode') || false,
        clearOldChat: configStore.get('clearOldChat') || false,
        clearOldChatLimit: configStore.get('clearOldChatLimit') || 30,
        clearOldChatTimeout: configStore.get('clearOldChatTimeout') || 120,
        audioSettings: {
          chatSoundEnabled: configStore.get('chatSoundEnabled'),
          chatSoundType: configStore.get('chatSoundType'),
          chatSoundVolume: configStore.get('chatSoundVolume'),
          chatSoundFile: configStore.get('chatSoundFile'),
          alertSoundEnabled: configStore.get('alertSoundEnabled'),
          alertSoundType: configStore.get('alertSoundType'),
          alertSoundVolume: configStore.get('alertSoundVolume'),
          alertSoundFile: configStore.get('alertSoundFile')
        }
      }
    }));

    let registeredDeviceId = null;

    ws.on('message', (message) => {
      try {
        const parsed = JSON.parse(message);
        if (parsed && parsed.event) {
          if (parsed.event === 'remote_transmitter_connected') {
            try {
              const tunnelManager = require('./tunnelManager');
              registeredDeviceId = (parsed.data && parsed.data.device) || 'Android Mobile';
              tunnelManager.registerDevice({
                deviceId: registeredDeviceId,
                device: registeredDeviceId,
                ip: clientIp,
                cloudRoom: parsed.data && parsed.data.cloudRoom,
                connectedAt: Date.now()
              });
              this.logSystem(`Mobile transmitter connected: ${registeredDeviceId} (${clientIp})`, 'success');
              this.broadcast('remote_device_connected', parsed.data);
            } catch (e) {}
          } else if (parsed.event === 'chat') {
            this.broadcast(parsed.event, parsed.data);
            try {
              const ollamaBot = require('./ollamaBot');
              if (ollamaBot && typeof ollamaBot.handleChatMessage === 'function') {
                ollamaBot.handleChatMessage(parsed.data);
              }
            } catch (e) {}
          } else if (parsed.data) {
            this.broadcast(parsed.event, parsed.data);
          }
        }
      } catch (err) {}
    });

    ws.on('close', () => {
      this.clients.delete(ws);
      if (registeredDeviceId) {
        try {
          const tunnelManager = require('./tunnelManager');
          tunnelManager.unregisterDevice(registeredDeviceId);
        } catch (e) {}
      }
      this.logSystem('Client disconnected');
    });

    ws.on('error', (err) => {
      console.error('[WebSocketServer] Client error:', err);
      this.clients.delete(ws);
      if (registeredDeviceId) {
        try {
          const tunnelManager = require('./tunnelManager');
          tunnelManager.unregisterDevice(registeredDeviceId);
        } catch (e) {}
      }
    });
  }

  stop() {
    if (this.wss) {
      this.broadcast('system_status', { status: 'disconnected', message: 'WebSocket Server shutting down' });
      this.wss.close(() => {
        console.log('[WebSocketServer] WebSocket server stopped');
      });
      this.wss = null;
    }
    this.clients.clear();
  }

  broadcast(event, data) {
    const payload = JSON.stringify({ event, data });
    for (const client of this.clients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(payload);
      }
    }
  }

  updateStreamState(state) {
    this.currentStreamState = state;
    this.broadcast('stream_update', state);
    if (state) {
      this.logSystem(`Stream state updated: ${state.liveState || 'idle'} (ID: ${state.videoId || 'none'})`);
    } else {
      this.logSystem('Stream state updated: offline (ID: none)');
    }
  }

  updateKickState(state) {
    this.currentKickState = state;
    this.broadcast('kick_update', state);
  }

  clearLogsAndChats() {
    this.logs = [];
    this.broadcast('clear_state', {});
  }

  clearChat() {
    this.broadcast('clear_chat', {});
    this.logSystem('OBS Chat Docks cleared');
  }

  logSystem(message, level = 'info') {
    const logEntry = {
      timestamp: new Date().toLocaleTimeString(),
      level,
      message
    };
    this.logs.push(logEntry);
    if (this.logs.length > 200) {
      this.logs.shift();
    }
    this.broadcast('log', logEntry);
    console.log(`[WebSocketServer Log] ${message}`);
  }
}

module.exports = new WebSocketServer();
