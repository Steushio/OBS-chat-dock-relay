const { BrowserWindow, session } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');

class YouTubeSession {
  constructor() {
    this.sessionPartition = 'persist:youtube';
    this.loginWindow = null;
  }

  getSession() {
    return session.fromPartition(this.sessionPartition);
  }

  // Opens the YouTube login BrowserWindow
  showLoginWindow(parentWindow) {
    if (this.loginWindow) {
      this.loginWindow.focus();
      return;
    }

    this.loginWindow = new BrowserWindow({
      width: 900,
      height: 700,
      parent: parentWindow || null,
      modal: true,
      title: 'Sign In to YouTube Studio',
      icon: path.join(__dirname, '..', 'assets', 'icon.png'),
      webPreferences: {
        partition: this.sessionPartition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true
      }
    });

    // Strip custom menus in login window
    this.loginWindow.setMenu(null);

    // Load Youtube Studio which prompts the Google sign-in
    this.loginWindow.loadURL('https://studio.youtube.com/');

    websocketServer.logSystem('YouTube login window opened');

    const checkNavigation = (url) => {
      if (url.includes('studio.youtube.com') && !url.includes('accounts.google.com')) {
        websocketServer.logSystem('YouTube login detected successfully!');
        if (parentWindow) {
          parentWindow.webContents.send('login-status-changed', { loggedIn: true });
        }
        setTimeout(() => {
          if (this.loginWindow && !this.loginWindow.isDestroyed()) {
            this.loginWindow.close();
          }
        }, 3000); // Give user a moment to see successful redirect
      }
    };

    this.loginWindow.webContents.on('did-navigate', (event, url) => {
      checkNavigation(url);
    });

    this.loginWindow.webContents.on('did-redirect-navigation', (event, url) => {
      checkNavigation(url);
    });

    this.loginWindow.on('closed', () => {
      this.loginWindow = null;
      websocketServer.logSystem('YouTube login window closed');
    });
  }

  // Background check if user is logged in
  async checkLoginStatus() {
    return new Promise((resolve) => {
      // Create a temporary hidden window to probe YouTube Studio
      const probeWindow = new BrowserWindow({
        width: 400,
        height: 400,
        show: false,
        webPreferences: {
          partition: this.sessionPartition,
          nodeIntegration: false,
          contextIsolation: true,
          sandbox: true
        }
      });

      probeWindow.loadURL('https://studio.youtube.com/');

      const timer = setTimeout(() => {
        if (!probeWindow.isDestroyed()) {
          probeWindow.destroy();
        }
        resolve(false);
      }, 15000); // 15s timeout fallback

      const checkUrl = (url) => {
        if (url.includes('accounts.google.com')) {
          clearTimeout(timer);
          probeWindow.destroy();
          resolve(false);
        } else if (url.includes('studio.youtube.com')) {
          clearTimeout(timer);
          probeWindow.destroy();
          resolve(true);
        }
      };

      probeWindow.webContents.on('did-navigate', (event, url) => {
        checkUrl(url);
      });

      probeWindow.webContents.on('did-redirect-navigation', (event, url) => {
        checkUrl(url);
      });
    });
  }

  // Log out of YouTube by clearing the session partition storage
  async logout() {
    const s = this.getSession();
    await s.clearStorageData();
    websocketServer.logSystem('YouTube session cleared (logged out)');
    return true;
  }
}

module.exports = new YouTubeSession();
