const { BrowserWindow, session } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');
const configStore = require('./configStore');

class KickSession {
  constructor() {
    this.sessionPartition = 'persist:kick';
    this.loginWindow = null;
  }

  getSession() {
    return session.fromPartition(this.sessionPartition);
  }

  // Opens the Kick Broadcaster login BrowserWindow
  showLoginWindow(parentWindow) {
    if (this.loginWindow && !this.loginWindow.isDestroyed()) {
      this.loginWindow.focus();
      return;
    }

    this.loginWindow = new BrowserWindow({
      width: 950,
      height: 720,
      parent: parentWindow || null,
      modal: true,
      title: 'Sign In to Kick (Broadcaster Account)',
      icon: path.join(__dirname, '..', 'assets', 'icon.png'),
      webPreferences: {
        partition: this.sessionPartition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true
      }
    });

    this.loginWindow.setMenu(null);

    // Set genuine Chrome desktop User-Agent
    const chromeUA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
    this.loginWindow.webContents.setUserAgent(chromeUA);

    this.loginWindow.loadURL('https://kick.com/login');
    websocketServer.logSystem('[Kick] Broadcaster login window opened');

    const checkNavigation = async (url) => {
      try {
        const parsed = new URL(url);
        if (parsed.hostname.includes('kick.com') && !parsed.pathname.startsWith('/login')) {
          // Probe username from page context
          let detectedUser = '';
          try {
            detectedUser = await this.loginWindow.webContents.executeJavaScript(`
              (function() {
                try {
                  if (window.__NEXT_DATA__?.props?.pageProps?.user?.username) {
                    return window.__NEXT_DATA__.props.pageProps.user.username;
                  }
                  for (let i = 0; i < localStorage.length; i++) {
                    const k = localStorage.key(i);
                    if (k && (k.includes('user') || k.includes('auth'))) {
                      try {
                        const parsed = JSON.parse(localStorage.getItem(k));
                        if (parsed?.username) return parsed.username;
                      } catch(e){}
                    }
                  }
                  const userBtn = document.querySelector('button[id*="user-menu"], [aria-label*="User profile"], [data-testid="user-profile-button"]');
                  if (userBtn) {
                    const text = userBtn.textContent.trim();
                    if (text && text.length < 30) return text;
                  }
                } catch(e) {}
                return '';
              })()
            `);
          } catch(e) {}

          if (detectedUser) {
            configStore.set('kickUsername', detectedUser);
          }

          // Verify login and extract username
          const status = await this.checkLoginStatus();
          if (status.loggedIn) {
            const finalUsername = detectedUser || status.username || configStore.get('kickUsername') || '';
            status.username = finalUsername;
            websocketServer.logSystem(`[Kick] Broadcaster authenticated as: ${finalUsername || 'Broadcaster'}`);
            if (finalUsername) {
              configStore.set('kickUsername', finalUsername);
              if (finalUsername.toLowerCase() === 'steushio') {
                configStore.set('kickChatroomId', '33438685');
              }
            }
            if (parentWindow && !parentWindow.isDestroyed()) {
              parentWindow.webContents.send('kick-login-status-changed', status);
            }
            if (configStore.get('kickEnabled')) {
              try {
                const kickCapture = require('./kickCapture');
                kickCapture.start(finalUsername);
              } catch (e) {}
            }
            setTimeout(() => {
              if (this.loginWindow && !this.loginWindow.isDestroyed()) {
                this.loginWindow.close();
              }
            }, 2000);
          }
        }
      } catch (e) {}
    };

    this.loginWindow.webContents.on('did-navigate', (event, url) => checkNavigation(url));
    this.loginWindow.webContents.on('did-redirect-navigation', (event, url) => checkNavigation(url));

    this.loginWindow.on('closed', () => {
      this.loginWindow = null;
      websocketServer.logSystem('[Kick] Broadcaster login window closed');
      if (parentWindow && !parentWindow.isDestroyed()) {
        this.checkLoginStatus().then(status => {
          parentWindow.webContents.send('kick-login-status-changed', status);
        });
      }
    });
  }

  // Check if Broadcaster account is logged in via cookies & DOM probe
  async checkLoginStatus() {
    try {
      const s = this.getSession();
      const cookies = await s.cookies.get({});
      
      const hasAuthCookie = cookies.some(c => 
        c.domain && c.domain.includes('kick.com') && (
          c.name === 'session_token' || 
          c.name === 'kick_session' || 
          c.name === 'kick_session_id' ||
          c.name.includes('remember_web') ||
          c.name.includes('token')
        )
      );

      let savedUser = configStore.get('kickUsername') || (hasAuthCookie ? 'steushio' : '');

      if (hasAuthCookie) {
        if (!configStore.get('kickUsername') && savedUser) {
          configStore.set('kickUsername', savedUser);
        }
        if (!configStore.get('kickChatroomId') && savedUser.toLowerCase() === 'steushio') {
          configStore.set('kickChatroomId', '33438685');
        }
        return { loggedIn: true, username: savedUser };
      }
    } catch (e) {
      console.error('[Kick Session] Error checking cookies:', e);
    }

    return { loggedIn: false, username: '' };
  }

  // Log out broadcaster from Kick session
  async logout() {
    try {
      const s = this.getSession();
      await s.clearStorageData();
      websocketServer.logSystem('[Kick] Broadcaster session logged out.');
      return true;
    } catch (e) {
      console.error('[Kick Session] Error logging out:', e);
      return false;
    }
  }
}

module.exports = new KickSession();
