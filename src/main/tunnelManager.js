const os = require('os');
const localtunnel = require('localtunnel');

class TunnelManager {
  constructor() {
    this.activeTunnel = null;
    this.tunnelUrl = null;
    this.wsUrl = null;
    this.tunnelHost = null;
    this.tunnelPort = null;
    this.mode = 'cloud'; // 'cloud' | 'local'
    this.status = 'idle'; // 'idle' | 'starting' | 'online' | 'error'
    this.errorMessage = null;
    this.localPort = 3000;
    this.connectedDevices = new Map();
    this.onStatusChangeCallbacks = [];
  }

  getLocalIp() {
    const nets = os.networkInterfaces();
    for (const name of Object.keys(nets)) {
      for (const net of nets[name]) {
        if (net.family === 'IPv4' && !net.internal) {
          return net.address;
        }
      }
    }
    return '127.0.0.1';
  }

  onStatusChange(cb) {
    if (typeof cb === 'function') {
      this.onStatusChangeCallbacks.push(cb);
    }
  }

  notifyStatus() {
    const statusData = this.getStatus();
    for (const cb of this.onStatusChangeCallbacks) {
      try {
        cb(statusData);
      } catch (e) {
        console.error('[TunnelManager] Status callback error:', e);
      }
    }
  }

  async startTunnel(options = {}) {
    this.mode = options.mode || 'cloud';
    this.localPort = options.localPort || 3000;

    if (this.mode === 'local') {
      const localIp = this.getLocalIp();
      this.status = 'online';
      this.tunnelHost = localIp;
      this.tunnelPort = this.localPort;
      this.tunnelUrl = `http://${localIp}:${this.localPort}`;
      this.wsUrl = `ws://${localIp}:${this.localPort}`;
      this.errorMessage = null;
      console.log(`[TunnelManager] Local network mode active: ${this.tunnelUrl} (${this.wsUrl})`);
      this.notifyStatus();
      return this.getStatus();
    }

    // Cloud tunnel mode (Localtunnel)
    this.status = 'starting';
    this.errorMessage = null;
    this.notifyStatus();

    try {
      if (this.activeTunnel) {
        try { this.activeTunnel.close(); } catch (e) {}
        this.activeTunnel = null;
      }

      const fixedSubdomain = options.subdomain || 'steushio-relay';
      console.log(`[TunnelManager] Opening zero-config cloud tunnel for port ${this.localPort} (subdomain: ${fixedSubdomain})...`);
      const tunnel = await localtunnel({
        port: this.localPort,
        subdomain: fixedSubdomain
      });

      this.activeTunnel = tunnel;
      this.tunnelUrl = tunnel.url;
      this.wsUrl = tunnel.url.replace(/^http:\/\//, 'ws://').replace(/^https:\/\//, 'wss://');

      try {
        const u = new URL(tunnel.url);
        this.tunnelHost = u.hostname;
        this.tunnelPort = u.port ? parseInt(u.port, 10) : (u.protocol === 'https:' ? 443 : 80);
      } catch (e) {
        this.tunnelHost = tunnel.url;
        this.tunnelPort = 443;
      }

      this.status = 'online';
      console.log(`[TunnelManager] Cloud tunnel online!`);
      console.log(`[TunnelManager] Connect Address: ${this.tunnelHost}:${this.tunnelPort}`);
      console.log(`[TunnelManager] HTTP URL: ${this.tunnelUrl}`);
      console.log(`[TunnelManager] WebSocket URL: ${this.wsUrl}`);

      tunnel.on('close', () => {
        console.log('[TunnelManager] Cloud tunnel closed, auto-restarting in 5s...');
        this.status = 'idle';
        this.notifyStatus();
        setTimeout(() => {
          if (this.status === 'idle') {
            this.startTunnel(options).catch(() => {});
          }
        }, 5000);
      });

      tunnel.on('error', (err) => {
        console.error('[TunnelManager] Tunnel error:', err.message || err);
        this.status = 'error';
        this.errorMessage = err.message || 'Tunnel error';
        this.notifyStatus();
        setTimeout(() => {
          if (this.status === 'error') {
            this.startTunnel(options).catch(() => {});
          }
        }, 8000);
      });

      this.notifyStatus();
      return this.getStatus();
    } catch (err) {
      console.error('[TunnelManager] Failed to start cloud tunnel:', err);
      this.status = 'error';
      this.errorMessage = err.message || 'Failed to establish cloud tunnel';
      this.notifyStatus();
      throw err;
    }
  }

  stopTunnel() {
    if (this.activeTunnel) {
      try {
        this.activeTunnel.close();
      } catch (e) {}
      this.activeTunnel = null;
    }
    this.status = 'idle';
    this.tunnelUrl = null;
    this.wsUrl = null;
    this.tunnelHost = null;
    this.tunnelPort = null;
    this.notifyStatus();
    console.log('[TunnelManager] Tunnel stopped');
    return this.getStatus();
  }

  registerDevice(deviceInfo) {
    if (!deviceInfo || !deviceInfo.deviceId) return;
    this.connectedDevices.set(deviceInfo.deviceId, {
      ...deviceInfo,
      lastSeen: Date.now()
    });
    this.notifyStatus();
  }

  unregisterDevice(deviceId) {
    if (deviceId && this.connectedDevices.has(deviceId)) {
      this.connectedDevices.delete(deviceId);
      this.notifyStatus();
    }
  }

  getStatus() {
    const localIp = this.getLocalIp();
    return {
      status: this.status,
      mode: this.mode,
      tunnelUrl: this.tunnelUrl,
      wsUrl: this.wsUrl,
      host: this.tunnelHost || (this.mode === 'local' ? localIp : null),
      port: this.tunnelPort || (this.mode === 'local' ? this.localPort : 443),
      localIp: localIp,
      localPort: this.localPort,
      errorMessage: this.errorMessage,
      connectedDevices: Array.from(this.connectedDevices.values())
    };
  }
}

module.exports = new TunnelManager();
