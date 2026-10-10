const { BrowserWindow, session, ipcMain } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');

class BotAccountSession {
  constructor() {
    this.ytPartition = 'persist:youtube_bot';
    this.kickPartition = 'persist:kick_bot';

    this.ytLoginWindow = null;
    this.kickLoginWindow = null;

    this.ytBotChatWindow = null;
    this.ytBotVideoId = null;

    this.kickBotWindow = null;

    this.setupIpc();
  }

  setupIpc() {
    ipcMain.handle('show-youtube-bot-login', (event) => {
      const parent = BrowserWindow.fromWebContents(event.sender);
      this.showYouTubeBotLogin(parent);
    });

    ipcMain.handle('show-kick-bot-login', (event) => {
      const parent = BrowserWindow.fromWebContents(event.sender);
      this.showKickBotLogin(parent);
    });

    ipcMain.handle('check-youtube-bot-login', async () => {
      return await this.checkYouTubeBotLogin();
    });

    ipcMain.handle('check-kick-bot-login', async () => {
      return await this.checkKickBotLogin();
    });

    ipcMain.handle('logout-youtube-bot', async () => {
      const s = session.fromPartition(this.ytPartition);
      await s.clearStorageData();
      if (this.ytBotChatWindow && !this.ytBotChatWindow.isDestroyed()) {
        this.ytBotChatWindow.destroy();
        this.ytBotChatWindow = null;
      }
      websocketServer.logSystem('[YouTube Bot] Logged out from Bot account.');
      return true;
    });

    ipcMain.handle('logout-kick-bot', async () => {
      const s = session.fromPartition(this.kickPartition);
      await s.clearStorageData();
      if (this.kickBotWindow && !this.kickBotWindow.isDestroyed()) {
        this.kickBotWindow.destroy();
        this.kickBotWindow = null;
      }
      websocketServer.logSystem('[Kick Bot] Logged out from Bot account.');
      return true;
    });
  }

  // --- YouTube Bot Account ---
  showYouTubeBotLogin(parentWindow) {
    if (this.ytLoginWindow && !this.ytLoginWindow.isDestroyed()) {
      this.ytLoginWindow.focus();
      return;
    }

    this.ytLoginWindow = new BrowserWindow({
      width: 950,
      height: 720,
      parent: parentWindow || null,
      modal: true,
      title: 'Sign In / Select YouTube Bot Channel',
      icon: path.join(__dirname, '..', 'assets', 'icon.png'),
      webPreferences: {
        partition: this.ytPartition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true
      }
    });

    this.ytLoginWindow.setMenu(null);
    this.ytLoginWindow.loadURL('https://www.youtube.com/channel_switcher?next=https%3A%2F%2Fstudio.youtube.com%2F');

    websocketServer.logSystem('[YouTube Bot] Channel selection / login window opened');

    const checkNav = (url) => {
      try {
        const parsed = new URL(url);
        if (parsed.hostname === 'studio.youtube.com' && !parsed.pathname.includes('channel_switcher')) {
          websocketServer.logSystem('[YouTube Bot] Dedicated Bot channel authenticated!');
          if (parentWindow) {
            parentWindow.webContents.send('youtube-bot-login-changed', { loggedIn: true });
          }
          setTimeout(() => {
            if (this.ytLoginWindow && !this.ytLoginWindow.isDestroyed()) {
              this.ytLoginWindow.close();
            }
          }, 2000);
        }
      } catch (e) {}
    };

    this.ytLoginWindow.webContents.on('did-navigate', (e, url) => checkNav(url));
    this.ytLoginWindow.webContents.on('did-redirect-navigation', (e, url) => checkNav(url));

    this.ytLoginWindow.on('closed', () => {
      this.ytLoginWindow = null;
    });
  }

  async checkYouTubeBotLogin() {
    return new Promise((resolve) => {
      const probe = new BrowserWindow({
        width: 400,
        height: 400,
        show: false,
        webPreferences: {
          partition: this.ytPartition,
          nodeIntegration: false,
          contextIsolation: true,
          sandbox: true
        }
      });

      probe.loadURL('https://studio.youtube.com/');

      const timer = setTimeout(() => {
        if (!probe.isDestroyed()) probe.destroy();
        resolve(false);
      }, 12000);

      const checkUrl = (url) => {
        try {
          const parsed = new URL(url);
          if (parsed.hostname === 'accounts.google.com') {
            clearTimeout(timer);
            probe.destroy();
            resolve(false);
          } else if (parsed.hostname === 'studio.youtube.com') {
            clearTimeout(timer);
            probe.destroy();
            resolve(true);
          }
        } catch (e) {}
      };

      probe.webContents.on('did-navigate', (e, url) => checkUrl(url));
      probe.webContents.on('did-redirect-navigation', (e, url) => checkUrl(url));
    });
  }

  async sendYouTubeBotMessage(videoId, messageText) {
    if (!videoId) {
      websocketServer.logSystem('[YouTube Bot Account] Cannot send message: No active video ID found.', 'warning');
      return false;
    }

    const chatUrl = `https://www.youtube.com/live_chat?is_popout=1&v=${videoId}`;

    if (!this.ytBotChatWindow || this.ytBotChatWindow.isDestroyed()) {
      this.ytBotChatWindow = new BrowserWindow({
        width: 1024,
        height: 768,
        show: false,
        skipTaskbar: true,
        frame: false,
        webPreferences: {
          partition: this.ytPartition,
          nodeIntegration: false,
          contextIsolation: true,
          sandbox: true,
          backgroundThrottling: false
        }
      });
      this.ytBotChatWindow.setMenu(null);
      this.ytBotChatWindow.webContents.setAudioMuted(true);
      this.ytBotChatWindow.loadURL(chatUrl);
      this.ytBotVideoId = videoId;
      await new Promise(r => setTimeout(r, 4000));
    } else if (this.ytBotVideoId !== videoId) {
      this.ytBotChatWindow.loadURL(chatUrl);
      this.ytBotVideoId = videoId;
      await new Promise(r => setTimeout(r, 4000));
    }

    try {
      // 1. Focus input and clear previous text
      const prep = await this.ytBotChatWindow.webContents.executeJavaScript(`
        (function() {
          const inputEl = document.querySelector(
            'div#input[contenteditable="true"], ' +
            'yt-live-chat-text-input-field-renderer #input[contenteditable="true"], ' +
            '#input.yt-live-chat-text-input-field-renderer, ' +
            'yt-live-chat-message-input-renderer #input, ' +
            '[contenteditable="true"]'
          );

          if (!inputEl) {
            const signIn = document.querySelector('#sign-in-button, a[href*="accounts.google.com"], a[href*="signin"]');
            if (signIn) return { ok: false, error: 'Dedicated Bot account is not signed in to YouTube. Please click "🔑 Login YT Bot" on the Dashboard!' };
            return { ok: false, error: 'Chat input element not found in DOM' };
          }

          inputEl.focus();
          const sel = window.getSelection();
          sel.removeAllRanges();
          const range = document.createRange();
          range.selectNodeContents(inputEl);
          sel.addRange(range);
          document.execCommand('delete', false, null);

          return { ok: true };
        })()
      `);

      if (!prep || !prep.ok) {
        websocketServer.logSystem(`[YouTube Bot Account] Cannot send: ${prep?.error || 'Input element not found'}`, 'warning');
        return false;
      }

      // 2. Use Chromium native text insertion (trusted by YouTube Polymer components)
      await this.ytBotChatWindow.webContents.insertText(messageText);
      await new Promise(r => setTimeout(r, 350));

      // 3. Trigger submit and click Send button
      const clicked = await this.ytBotChatWindow.webContents.executeJavaScript(`
        (function() {
          const inputEl = document.querySelector(
            'div#input[contenteditable="true"], ' +
            'yt-live-chat-text-input-field-renderer #input, ' +
            '#input.yt-live-chat-text-input-field-renderer, ' +
            'yt-live-chat-message-input-renderer #input'
          );
          if (inputEl) {
            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
            inputEl.dispatchEvent(new Event('change', { bubbles: true }));
          }

          const sendBtn = document.querySelector(
            '#send-button button, ' +
            'yt-live-chat-message-input-renderer #send-button button, ' +
            '#send-button yt-button-shape button, ' +
            '#send-button yt-icon-button button, ' +
            'yt-button-renderer#send-button button, ' +
            '#send-button'
          );

          if (sendBtn) {
            sendBtn.removeAttribute('disabled');
            sendBtn.removeAttribute('aria-disabled');
            sendBtn.click();
            return true;
          }
          return false;
        })()
      `);

      // 4. Native Enter fallback
      this.ytBotChatWindow.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Return' });
      this.ytBotChatWindow.webContents.sendInputEvent({ type: 'char', keyCode: '\r' });
      this.ytBotChatWindow.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Return' });

      websocketServer.logSystem(`[YouTube Bot Account] Successfully posted to live chat: "${messageText}"`);
      return true;
    } catch (err) {
      websocketServer.logSystem(`[YouTube Bot Account] Send error: ${err.message}`, 'error');
      return false;
    }
  }

  // --- Kick Bot Account ---
  showKickBotLogin(parentWindow) {
    if (this.kickLoginWindow && !this.kickLoginWindow.isDestroyed()) {
      this.kickLoginWindow.focus();
      return;
    }

    this.kickLoginWindow = new BrowserWindow({
      width: 950,
      height: 720,
      parent: parentWindow || null,
      modal: true,
      title: 'Sign In to Kick Bot Account',
      icon: path.join(__dirname, '..', 'assets', 'icon.png'),
      webPreferences: {
        partition: this.kickPartition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true
      }
    });

    this.kickLoginWindow.setMenu(null);
    this.kickLoginWindow.loadURL('https://kick.com/login');

    websocketServer.logSystem('[Kick Bot] Login window opened');

    const checkKickNav = async (url) => {
      if (url.includes('kick.com') && !url.includes('kick.com/login')) {
        const loggedIn = await this.checkKickBotLogin();
        if (loggedIn) {
          websocketServer.logSystem('[Kick Bot] Dedicated Bot account authenticated!');
          if (parentWindow) {
            parentWindow.webContents.send('kick-bot-login-changed', { loggedIn: true });
          }
          setTimeout(() => {
            if (this.kickLoginWindow && !this.kickLoginWindow.isDestroyed()) {
              this.kickLoginWindow.close();
            }
          }, 2000);
        }
      }
    };

    this.kickLoginWindow.webContents.on('did-navigate', (e, url) => checkKickNav(url));
    this.kickLoginWindow.webContents.on('did-redirect-navigation', (e, url) => checkKickNav(url));

    this.kickLoginWindow.on('closed', () => {
      this.kickLoginWindow = null;
      if (parentWindow) {
        this.checkKickBotLogin().then(loggedIn => {
          parentWindow.webContents.send('kick-bot-login-changed', { loggedIn });
        });
      }
    });
  }

  async checkKickBotLogin() {
    try {
      const s = session.fromPartition(this.kickPartition);
      const cookies = await s.cookies.get({});
      
      const hasKickAuthCookie = cookies.some(c => 
        c.domain.includes('kick.com') && (
          c.name.includes('session') || 
          c.name.includes('token') || 
          c.name.includes('auth') ||
          c.name.includes('kick') ||
          c.name === 'cf_clearance'
        )
      );

      if (hasKickAuthCookie) {
        return true;
      }
    } catch (e) {
      console.error('[Kick Bot] Error checking partition cookies:', e);
    }

    return new Promise((resolve) => {
      const probe = new BrowserWindow({
        width: 400,
        height: 400,
        show: false,
        webPreferences: {
          partition: this.kickPartition,
          nodeIntegration: false,
          contextIsolation: true,
          sandbox: true
        }
      });

      probe.loadURL('https://kick.com/');

      const timer = setTimeout(() => {
        if (!probe.isDestroyed()) probe.destroy();
        resolve(false);
      }, 10000);

      probe.webContents.on('did-finish-load', async () => {
        try {
          const loggedIn = await probe.webContents.executeJavaScript(`
            (function() {
              const avatar = document.querySelector('button[aria-label="User profile"], #user-menu-button, [data-testid="user-profile-button"], img[alt*="avatar"], img[src*="user"]');
              const loginBtn = document.querySelector('button[href="/login"], a[href="/login"]');
              if (avatar) return true;
              if (loginBtn) return false;
              return false;
            })()
          `);
          clearTimeout(timer);
          probe.destroy();
          resolve(!!loggedIn);
        } catch (e) {
          clearTimeout(timer);
          probe.destroy();
          resolve(false);
        }
      });
    });
  }

  async sendKickBotMessage(chatroomId, username, messageText) {
    if (!this.kickBotWindow || this.kickBotWindow.isDestroyed()) {
      this.kickBotWindow = new BrowserWindow({
        width: 1024,
        height: 768,
        show: false,
        skipTaskbar: true,
        frame: false,
        webPreferences: {
          partition: this.kickPartition,
          nodeIntegration: false,
          contextIsolation: true,
          sandbox: true,
          backgroundThrottling: false
        }
      });
      this.kickBotWindow.setMenu(null);
      this.kickBotWindow.webContents.setAudioMuted(true);
      this.kickBotWindow.loadURL(`https://kick.com/${username || 'kick'}`);
      await new Promise(r => setTimeout(r, 4000));
    }

    try {
      return await this.kickBotWindow.webContents.executeJavaScript(`
        (async function(text, chatroomId) {
          try {
            if (chatroomId) {
              const res = await fetch('https://kick.com/api/v2/messages/send/' + chatroomId, {
                method: 'POST',
                headers: {
                  'Content-Type': 'application/json',
                  'Accept': 'application/json'
                },
                body: JSON.stringify({ content: text, type: 'message' })
              });
              if (res.ok) return true;
            }
            return false;
          } catch (e) {
            return false;
          }
        })(${JSON.stringify(messageText)}, ${JSON.stringify(chatroomId)})
      `);
    } catch (err) {
      websocketServer.logSystem(`[Kick Bot Account] Send error: ${err.message}`, 'error');
      return false;
    }
  }
}

module.exports = new BotAccountSession();
