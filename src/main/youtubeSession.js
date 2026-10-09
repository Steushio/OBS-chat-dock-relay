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
      width: 950,
      height: 720,
      parent: parentWindow || null,
      modal: true,
      title: 'Sign In / Select YouTube Channel',
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

    // Direct user to YouTube channel switcher so they can select their desired channel/Brand account under their Gmail
    this.loginWindow.loadURL('https://www.youtube.com/channel_switcher?next=https%3A%2F%2Fstudio.youtube.com%2F');

    websocketServer.logSystem('YouTube channel selection / login window opened');

    const checkNavigation = (url) => {
      try {
        const parsed = new URL(url);
        // Only trigger completion when user has selected a channel and reached studio.youtube.com (not channel_switcher or accounts.google.com)
        if (parsed.hostname === 'studio.youtube.com' && !parsed.pathname.includes('channel_switcher')) {
          websocketServer.logSystem('YouTube channel selected & login verified!');
          if (parentWindow) {
            parentWindow.webContents.send('login-status-changed', { loggedIn: true });
          }
          setTimeout(() => {
            if (this.loginWindow && !this.loginWindow.isDestroyed()) {
              this.loginWindow.close();
            }
          }, 2500);
        }
      } catch (e) {
        // ignore invalid url
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
        try {
          const parsed = new URL(url);
          if (parsed.hostname === 'accounts.google.com') {
            clearTimeout(timer);
            probeWindow.destroy();
            resolve(false);
          } else if (parsed.hostname === 'studio.youtube.com') {
            clearTimeout(timer);
            probeWindow.destroy();
            resolve(true);
          }
        } catch (e) {}
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
