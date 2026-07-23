const { app } = require('electron');
const path = require('path');
const fs = require('fs');

class ConfigStore {
  constructor() {
    this.configPath = path.join(app.getPath('userData'), 'app-settings.json');
    this.defaults = {
      httpPort: 3000,
      wsPort: 3001,
      lowResourceMode: false,
      autoStart: false,
      autoStartMonitoring: false,
      startMinimized: false,
      kickUsername: '',
      kickEnabled: false,
      chatSoundEnabled: false,
      chatSoundType: 'default',
      chatSoundVolume: 100,
      chatSoundFile: '',
      alertSoundEnabled: false,
      alertSoundType: 'default',
      alertSoundVolume: 100,
      alertSoundFile: '',
      windowBounds: { width: 1000, height: 750 }
    };
    this.config = this.load();
  }

  load() {
    try {
      if (fs.existsSync(this.configPath)) {
        const data = fs.readFileSync(this.configPath, 'utf-8');
        return { ...this.defaults, ...JSON.parse(data) };
      }
    } catch (err) {
      console.error('Error loading config file, resetting to defaults:', err);
    }
    return { ...this.defaults };
  }

  save() {
    try {
      const dir = path.dirname(this.configPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(this.configPath, JSON.stringify(this.config, null, 2), 'utf-8');
    } catch (err) {
      console.error('Error saving config file:', err);
    }
  }

  get(key) {
    return this.config[key];
  }

  set(key, val) {
    this.config[key] = val;
    this.save();
  }

  getAll() {
    return this.config;
  }
}

module.exports = new ConfigStore();
