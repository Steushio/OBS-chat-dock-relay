const express = require('express');
const path = require('path');
const http = require('http');

class OverlayServer {
  constructor() {
    this.app = express();
    this.server = null;
    this.port = 3000;

    this.setupRoutes();
  }

  setupRoutes() {
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

    // Root status API
    this.app.get('/status', (req, res) => {
      const configStore = require('./configStore');
      res.json({ 
        status: 'running', 
        service: 'OBS Chat Relay HTTP Server',
        wsPort: configStore.get('wsPort') || 3001,
        lowResourceMode: configStore.get('lowResourceMode') || false
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

      this.server.listen(this.port, '127.0.0.1', () => {
        console.log(`[OverlayServer] HTTP Server listening on http://127.0.0.1:${this.port}`);
        resolve();
      });

      this.server.on('error', (err) => {
        console.error('[OverlayServer] Failed to start HTTP server:', err);
        reject(err);
      });
    });
  }

  stop() {
    if (this.server) {
      this.server.close(() => {
        console.log('[OverlayServer] HTTP Server stopped');
      });
      this.server = null;
    }
  }
}

module.exports = new OverlayServer();
