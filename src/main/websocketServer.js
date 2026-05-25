const WebSocket = require('ws');

class WebSocketServer {
  constructor() {
    this.wss = null;
    this.clients = new Set();
    this.port = 3001;
    this.currentStreamState = null;
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
      ws.send(JSON.stringify({
        event: 'handshake',
        data: {
          status: 'connected',
          stream: this.currentStreamState,
          recentLogs: this.logs.slice(-50)
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
    this.logSystem(`Stream state updated: ${state.liveState || 'idle'} (ID: ${state.videoId || 'none'})`);
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
