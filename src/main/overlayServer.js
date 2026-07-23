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
