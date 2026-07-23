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
    this.wss = new WebSocket.Server({ port: this.port, host: '127.0.0.1' }, () => {
      this.logSystem(`WebSocket Server listening on ws://127.0.0.1:${this.port}`);
    });

    this.wss.on('connection', (ws) => {
      this.clients.add(ws);
      this.logSystem('OBS Dock connected');

      // Send initial handshake state
      const configStore = require('./configStore');
      ws.send(JSON.stringify({
        event: 'handshake',
        data: {
          status: 'connected',
          stream: this.currentStreamState,
          kickStream: this.currentKickState,
          recentLogs: this.logs.slice(-50),
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

      ws.on('close', () => {
        this.clients.delete(ws);
        this.logSystem('OBS Dock disconnected');
      });

      ws.on('error', (err) => {
        console.error('[WebSocketServer] Client error:', err);
        this.clients.delete(ws);
      });
    });

    this.wss.on('error', (err) => {
      this.logSystem(`Failed to start WebSocket server: ${err.message}`, 'error');
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
