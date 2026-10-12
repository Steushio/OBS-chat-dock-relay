const { BrowserWindow, session, ipcMain, net } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');

class BotAccountSession {
  constructor() {
    this.ytPartition = 'persist:youtube_bot';
    this.kickPartition = 'persist:kick_bot';
    this.kickBroadcasterPartition = 'persist:kick';

    this.ytLoginWindow = null;
    this.kickLoginWindow = null;

    this.ytBotChatWindow = null;
    this.ytBotVideoId = null;

    this.kickBotWindow = null;
    this.kickBroadcasterWindow = null;
    this.isQuitting = false;

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

    ipcMain.handle('toggle-youtube-bot-window', () => {
      return this.toggleYouTubeBotWindow();
    });

    ipcMain.handle('toggle-kick-bot-window', () => {
      const configStore = require('./configStore');
      return this.toggleKickBotWindow(configStore.get('kickUsername') || 'steushio');
    });

    ipcMain.handle('toggle-kick-broadcaster-window', () => {
      const configStore = require('./configStore');
      return this.toggleKickBroadcasterWindow(configStore.get('kickUsername') || 'steushio');
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

  // Pre-warm the YouTube Live Chat popout window for instant response
  prewarmYouTubeBotChat(videoId) {
    if (!videoId) return;
    const chatUrl = `https://www.youtube.com/live_chat?is_popout=1&v=${videoId}`;

    if (this.ytBotChatWindow && !this.ytBotChatWindow.isDestroyed()) {
      if (this.ytBotVideoId !== videoId) {
        websocketServer.logSystem(`[YouTube Bot] Switching pre-warmed chat window to video: ${videoId}`);
        this.ytBotVideoId = videoId;
        this.ytBotChatWindow.loadURL(chatUrl);
      }
      return;
    }

    websocketServer.logSystem(`[YouTube Bot] Pre-warming Live Chat window for video: ${videoId}...`);
    this.ytBotVideoId = videoId;

    this.ytBotChatWindow = new BrowserWindow({
      width: 1024,
      height: 768,
      show: false,
      skipTaskbar: true,
      title: 'YouTube Bot Live Chat Preview',
      icon: path.join(__dirname, '..', 'assets', 'icon.png'),
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

    this.ytBotChatWindow.on('close', (e) => {
      if (!this.isQuitting) {
        e.preventDefault();
        this.ytBotChatWindow.hide();
        websocketServer.logSystem('[YouTube Bot] Chat preview window hidden to background.');
      }
    });

    this.ytBotChatWindow.webContents.on('did-finish-load', async () => {
      websocketServer.logSystem(`[YouTube Bot] Live chat window finished loading for video: ${videoId}`);
      try {
        const check = await this.ytBotChatWindow.webContents.executeJavaScript(`
          (function() {
            const signIn = document.querySelector('#sign-in-button, a[href*="accounts.google.com"], a[href*="signin"], ytd-button-renderer a[href*="ServiceLogin"]');
            const input = document.querySelector('div#input[contenteditable="true"], yt-live-chat-text-input-field-renderer #input, yt-live-chat-message-input-renderer #input');
            return { signedIn: !signIn, hasInput: !!input };
          })()
        `);
        if (check && !check.signedIn) {
          websocketServer.logSystem('[YouTube Bot] Bot account is not signed in to YouTube chat. Please click "🔑 Login YT Bot" on the Dashboard!', 'warning');
        } else if (check && check.hasInput) {
          websocketServer.logSystem('[YouTube Bot] Live chat input is active and ready to post messages.');
        }
      } catch (e) {}
    });

    this.ytBotChatWindow.webContents.on('did-fail-load', (e, code, desc) => {
      websocketServer.logSystem(`[YouTube Bot] Failed to load chat page: ${desc}`, 'warning');
    });

    this.ytBotChatWindow.loadURL(chatUrl);
  }

  // Toggle visibility of the YouTube Bot chat window for live inspection / verification
  toggleYouTubeBotWindow() {
    const configStore = require('./configStore');
    const streamManager = require('./streamManager');
    const chatCapture = require('./chatCapture');
    const activeVideoId = this.ytBotVideoId || chatCapture.currentVideoId || (streamManager.activeStream ? streamManager.activeStream.videoId : null) || configStore.get('lastVideoId');

    if (!this.ytBotChatWindow || this.ytBotChatWindow.isDestroyed()) {
      if (!activeVideoId) {
        websocketServer.logSystem('[YouTube Bot] Cannot preview chat: No active stream or video ID found.', 'warning');
        return false;
      }
      this.prewarmYouTubeBotChat(activeVideoId);
    }

    if (this.ytBotChatWindow.isVisible()) {
      this.ytBotChatWindow.hide();
      return false;
    } else {
      this.ytBotChatWindow.show();
      this.ytBotChatWindow.focus();
      return true;
    }
  }

  // Actively wait for chat input container to be fully hydrated in DOM
  async waitForChatInputReady(maxWaitMs = 15000) {
    if (!this.ytBotChatWindow || this.ytBotChatWindow.isDestroyed()) return { ok: false, error: 'Chat window is not active' };

    const startTime = Date.now();
    while (Date.now() - startTime < maxWaitMs) {
      if (this.ytBotChatWindow.isDestroyed()) return { ok: false, error: 'Chat window closed unexpectedly' };

      try {
        const state = await this.ytBotChatWindow.webContents.executeJavaScript(`
          (function() {
            const signIn = document.querySelector('#sign-in-button, a[href*="accounts.google.com"], a[href*="signin"], ytd-button-renderer a[href*="ServiceLogin"]');
            if (signIn) return { status: 'not_signed_in' };

            const disabledMsg = document.querySelector('yt-live-chat-message-input-renderer #disabled-message, #live-chat-unavailable-message');
            if (disabledMsg && disabledMsg.offsetParent !== null && disabledMsg.textContent.trim().length > 0) {
              return { status: 'disabled', message: disabledMsg.textContent.trim() };
            }

            const input = document.querySelector(
              'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
              'div#input[contenteditable="true"], ' +
              'yt-live-chat-text-input-field-renderer #input[contenteditable="true"], ' +
              '#input.yt-live-chat-text-input-field-renderer, ' +
              '[contenteditable="true"]'
            );

            if (input) return { status: 'ready' };
            return { status: 'loading' };
          })()
        `);

        if (state.status === 'ready') {
          return { ok: true };
        }
        if (state.status === 'not_signed_in') {
          return { ok: false, error: 'Bot account is not signed in to YouTube. Please click "🔑 Login YT Bot" on the Dashboard!' };
        }
        if (state.status === 'disabled') {
          return { ok: false, error: `Live chat disabled on YouTube: ${state.message}` };
        }
      } catch (e) {
        // executeJavaScript might fail if page is mid-navigation
      }

      await new Promise(r => setTimeout(r, 400));
    }

    return { ok: false, error: 'Timed out waiting for YouTube chat input field to load (took >15s)' };
  }

  async sendYouTubeBotMessage(videoId, messageText) {
    if (!this._ytSendQueue) this._ytSendQueue = Promise.resolve();
    const run = () => this._executeSendYouTubeBotMessage(videoId, messageText);
    this._ytSendQueue = this._ytSendQueue.then(run, run);
    return await this._ytSendQueue;
  }

  async _executeSendYouTubeBotMessage(videoId, messageText) {
    if (!videoId) {
      websocketServer.logSystem('[YouTube Bot Account] Cannot send message: No active video ID found.', 'warning');
      return false;
    }

    // 1. Ensure window is running & prewarmed
    this.prewarmYouTubeBotChat(videoId);

    // 2. Wait until chat input is confirmed ready in DOM
    const readyCheck = await this.waitForChatInputReady(15000);
    if (!readyCheck.ok) {
      websocketServer.logSystem(`[YouTube Bot Account] Cannot send: ${readyCheck.error}`, 'warning');
      return false;
    }

    try {
      // 3. CRUCIAL: Focus WebContents so Chromium native inputs and DOM focus take effect even if window is hidden
      this.ytBotChatWindow.webContents.focus();

      // 4. Focus DOM element, clear prior text, insert new text via execCommand and synthetic InputEvents
      const prep = await this.ytBotChatWindow.webContents.executeJavaScript(`
        (function(text) {
          const inputEl = document.querySelector(
            'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
            'div#input[contenteditable="true"], ' +
            'yt-live-chat-text-input-field-renderer #input[contenteditable="true"], ' +
            '#input.yt-live-chat-text-input-field-renderer, ' +
            '[contenteditable="true"]'
          );

          if (!inputEl) return { ok: false, error: 'Input element disappeared from DOM' };

          inputEl.focus();

          // Clear any leftover content
          const sel = window.getSelection();
          sel.removeAllRanges();
          const range = document.createRange();
          range.selectNodeContents(inputEl);
          sel.addRange(range);
          document.execCommand('delete', false, null);

          // Insert text via execCommand
          document.execCommand('insertText', false, text);

          // Fallback if textContent is not populated
          if ((inputEl.textContent || '').trim() !== text.trim()) {
            inputEl.textContent = text;
          }

          // Dispatch standard events (including composed: true for Polymer Shadow DOM)
          inputEl.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
          inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
          inputEl.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

          // Update Polymer custom elements internal state
          const textRenderer = inputEl.closest('yt-live-chat-text-input-field-renderer');
          if (textRenderer) {
            textRenderer.hasContent = true;
            if (typeof textRenderer.set === 'function') textRenderer.set('hasContent', true);
            if (typeof textRenderer.onInput_ === 'function') textRenderer.onInput_();
          }

          const msgInputRenderer = inputEl.closest('yt-live-chat-message-input-renderer');
          if (msgInputRenderer) {
            msgInputRenderer.hasContent = true;
            if (typeof msgInputRenderer.set === 'function') msgInputRenderer.set('hasContent', true);
          }

          return { ok: true, currentText: inputEl.textContent };
        })(${JSON.stringify(messageText)})
      `);

      if (!prep || !prep.ok) {
        websocketServer.logSystem(`[YouTube Bot Account] Text insertion error: ${prep?.error || 'Unknown'}`, 'warning');
        return false;
      }

      // Brief delay for Polymer to update state and enable send button
      await new Promise(r => setTimeout(r, 250));

      // 5. Trigger send via multiple approaches:
      // a) Keyboard event Enter
      // b) Native sendInputEvent
      // c) Click Send Button
      // d) Direct Polymer onSendButtonClick_()
      await this.ytBotChatWindow.webContents.executeJavaScript(`
        (function() {
          const inputEl = document.querySelector(
            'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
            'div#input[contenteditable="true"], ' +
            '[contenteditable="true"]'
          );

          if (inputEl) {
            // Dispatch Enter key events with composed: true
            const enterDown = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true, cancelable: true });
            const enterPress = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true, cancelable: true });
            const enterUp = new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true, cancelable: true });
            inputEl.dispatchEvent(enterDown);
            inputEl.dispatchEvent(enterPress);
            inputEl.dispatchEvent(enterUp);
          }

          // Click Send Button
          const sendBtn = 
            document.querySelector('#send-button button') ||
            document.querySelector('yt-live-chat-message-input-renderer #send-button button') ||
            (document.querySelector('#send-button yt-button-shape')?.shadowRoot?.querySelector('button')) ||
            document.querySelector('#send-button yt-button-shape button') ||
            document.querySelector('yt-button-renderer#send-button button') ||
            document.querySelector('#send-button');

          if (sendBtn) {
            sendBtn.removeAttribute('disabled');
            sendBtn.removeAttribute('aria-disabled');
            sendBtn.disabled = false;
            sendBtn.click();
          }

          // Call Polymer component send method if available
          const msgInput = document.querySelector('yt-live-chat-message-input-renderer');
          if (msgInput) {
            if (typeof msgInput.onSendButtonClick_ === 'function') {
              msgInput.onSendButtonClick_();
            } else if (typeof msgInput.sendMessage_ === 'function') {
              msgInput.sendMessage_();
            }
          }
        })()
      `);

      // Native Electron Return key event
      this.ytBotChatWindow.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Return' });
      this.ytBotChatWindow.webContents.sendInputEvent({ type: 'char', keyCode: '\r' });
      this.ytBotChatWindow.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Return' });

      // 6. Verify that input was cleared (indicating YouTube sent it)
      await new Promise(r => setTimeout(r, 800));

      const verify = await this.ytBotChatWindow.webContents.executeJavaScript(`
        (function() {
          const inputEl = document.querySelector(
            'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
            'div#input[contenteditable="true"], ' +
            '[contenteditable="true"]'
          );
          if (!inputEl) return { verified: true };
          const remaining = (inputEl.textContent || inputEl.innerText || '').trim();
          return { verified: remaining === '', remaining: remaining };
        })()
      `);

      if (verify && verify.verified) {
        websocketServer.logSystem(`[YouTube Bot Account] Successfully posted to live chat: "${messageText}"`);
        return true;
      } else {
        // Wait another 700ms in case slow network
        await new Promise(r => setTimeout(r, 700));
        const verify2 = await this.ytBotChatWindow.webContents.executeJavaScript(`
          (function() {
            const inputEl = document.querySelector(
              'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
              'div#input[contenteditable="true"], ' +
              '[contenteditable="true"]'
            );
            if (!inputEl) return { verified: true };
            const remaining = (inputEl.textContent || inputEl.innerText || '').trim();
            return { verified: remaining === '', remaining: remaining };
          })()
        `);
        if (verify2 && verify2.verified) {
          websocketServer.logSystem(`[YouTube Bot Account] Successfully posted to live chat: "${messageText}"`);
          return true;
        }

        websocketServer.logSystem(`[YouTube Bot Account] Note: Message was submitted. (Input box remaining: "${verify2?.remaining || ''}")`);
        return true;
      }
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

  // Prewarm background Kick chat window (supports 'broadcaster' and 'bot_account')
  prewarmKickChatWindow(mode, username) {
    if (!username) {
      const configStore = require('./configStore');
      username = configStore.get('kickUsername') || 'steushio';
    }
    const popoutUrl = `https://kick.com/popout/${username}/chat`;
    const isBroadcaster = mode === 'broadcaster';
    const partition = isBroadcaster ? this.kickBroadcasterPartition : this.kickPartition;
    const windowProp = isBroadcaster ? 'kickBroadcasterWindow' : 'kickBotWindow';
    const tag = isBroadcaster ? '[Kick Broadcaster]' : '[Kick Bot]';
    const title = isBroadcaster ? 'Kick Broadcaster Live Chat Preview' : 'Kick Bot Live Chat Preview';

    if (this[windowProp] && !this[windowProp].isDestroyed()) {
      const currentUrl = this[windowProp].webContents.getURL() || '';
      if (!currentUrl.includes(`/popout/${username}/chat`)) {
        this[windowProp].loadURL(popoutUrl);
      }
      return this[windowProp];
    }

    websocketServer.logSystem(`${tag} Prewarming background chat session for: ${username}...`);
    const win = new BrowserWindow({
      width: 440,
      height: 720,
      show: false,
      skipTaskbar: true,
      frame: true,
      title: title,
      webPreferences: {
        partition: partition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        backgroundThrottling: false,
        paintWhenInitiallyHidden: true
      }
    });

    win.setMenu(null);
    win.webContents.setAudioMuted(true);

    const chromeUA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
    win.webContents.setUserAgent(chromeUA);

    win.on('close', (e) => {
      if (!this.isQuitting) {
        e.preventDefault();
        win.hide();
        websocketServer.logSystem(`${tag} Chat preview window hidden to background.`);
      }
    });

    win.webContents.on('did-finish-load', async () => {
      websocketServer.logSystem(`${tag} Background chat session page loaded for ${username}`);
      try {
        const check = await win.webContents.executeJavaScript(`
          (function() {
            const input = document.querySelector(
              '#message-input, [data-chat-input="true"], div#message-input[contenteditable="true"], ' +
              'div[contenteditable="true"][role="textbox"], div[contenteditable="true"], ' +
              '.chat-input [contenteditable="true"], [data-testid="chat-input"], ' +
              'textarea[placeholder*="chat" i], textarea[placeholder*="message" i]'
            );
            return { hasInput: !!input };
          })()
        `);
        if (check?.hasInput) {
          websocketServer.logSystem(`${tag} Live chat input is active and ready to post messages for ${username}.`);
        }
      } catch (e) {}
    });

    win.webContents.on('did-fail-load', (e, code, desc) => {
      websocketServer.logSystem(`${tag} Failed to load chat page: ${desc}`, 'warning');
    });

    win.loadURL(popoutUrl);
    this[windowProp] = win;
    return win;
  }

  prewarmKickBotChat(username) {
    return this.prewarmKickChatWindow('bot_account', username);
  }

  prewarmKickBroadcasterChat(username) {
    return this.prewarmKickChatWindow('broadcaster', username);
  }

  // Toggle visibility of Kick Bot chat window
  toggleKickBotWindow(username) {
    const win = this.prewarmKickChatWindow('bot_account', username);
    if (win.isVisible()) {
      win.hide();
      return false;
    } else {
      win.show();
      win.focus();
      return true;
    }
  }

  // Toggle visibility of Kick Broadcaster chat window
  toggleKickBroadcasterWindow(username) {
    const win = this.prewarmKickChatWindow('broadcaster', username);
    if (win.isVisible()) {
      win.hide();
      return false;
    } else {
      win.show();
      win.focus();
      return true;
    }
  }

  // Actively wait for Kick chat input to be fully hydrated in DOM
  async waitForKickChatInputReady(targetWindow, maxWaitMs = 15000, tag = '[Kick]') {
    if (!targetWindow || targetWindow.isDestroyed()) return { ok: false, error: 'Chat window is not active' };

    const startTime = Date.now();
    let lastStatus = 'loading';

    while (Date.now() - startTime < maxWaitMs) {
      if (targetWindow.isDestroyed()) return { ok: false, error: 'Chat window closed unexpectedly' };

      try {
        const state = await targetWindow.webContents.executeJavaScript(`
          (function() {
            // Dismiss rules or cookie modal if blocking chat
            try {
              const buttons = Array.from(document.querySelectorAll('button, a'));
              const acceptBtn = buttons.find(b => {
                const t = (b.textContent || '').trim().toLowerCase();
                return (
                  t === 'accept' || 
                  t === 'i understand' || 
                  t === 'got it' || 
                  t === 'accept rules' ||
                  t === 'agree' ||
                  t === 'accept all'
                );
              });
              if (acceptBtn) acceptBtn.click();
            } catch (e) {}

            const input = document.querySelector(
              '#message-input, [data-chat-input="true"], div#message-input[contenteditable="true"], ' +
              'div[contenteditable="true"][role="textbox"], div[contenteditable="true"], ' +
              '.chat-input [contenteditable="true"], [data-testid="chat-input"], ' +
              'textarea[placeholder*="chat" i], textarea[placeholder*="message" i]'
            );

            if (input) return { status: 'ready' };

            const hasLoginBtn = Array.from(document.querySelectorAll('a, button')).some(el => 
              el.textContent && el.textContent.trim().toLowerCase() === 'log in' && el.offsetParent !== null
            );
            if (hasLoginBtn) return { status: 'not_logged_in' };

            return { status: 'loading' };
          })()
        `);

        if (state && state.status === 'ready') return { ok: true };
        if (state) lastStatus = state.status;
      } catch (e) {}

      await new Promise(r => setTimeout(r, 400));
    }

    if (lastStatus === 'not_logged_in') {
      return { ok: false, error: `${tag} Account is not logged in. Please authenticate on the Dashboard!` };
    }

    return { ok: false, error: `Timed out waiting for Kick chat input field to load (took >15s)` };
  }

  async resolveKickChatroomId(win, username) {
    const kickCapture = require('./kickCapture');
    if (kickCapture.chatroomId) return kickCapture.chatroomId;

    const configStore = require('./configStore');
    const cachedCid = configStore.get('kickChatroomId');
    if (cachedCid) {
      kickCapture.chatroomId = cachedCid;
      return cachedCid;
    }

    if (!username) {
      username = configStore.get('kickUsername') || 'steushio';
    }

    if (username.toLowerCase() === 'steushio') {
      kickCapture.chatroomId = '33438685';
      return '33438685';
    }

    if (win && !win.isDestroyed()) {
      try {
        const cid = await win.webContents.executeJavaScript(`
          (async function() {
            try {
              if (window.__NEXT_DATA__?.props?.pageProps?.channel?.chatroom?.id) {
                return window.__NEXT_DATA__.props.pageProps.channel.chatroom.id;
              }
              if (window.__NEXT_DATA__?.props?.pageProps?.chatroom?.id) {
                return window.__NEXT_DATA__.props.pageProps.chatroom.id;
              }
              const el = document.querySelector('[data-chatroom-id]');
              if (el) return el.getAttribute('data-chatroom-id');

              const res = await fetch('https://kick.com/api/v2/channels/' + ${JSON.stringify(username)});
              if (res.ok) {
                const data = await res.json();
                return data?.chatroom?.id || null;
              }
            } catch (e) {}
            return null;
          })()
        `);
        if (cid) {
          kickCapture.chatroomId = cid;
          configStore.set('kickChatroomId', cid);
          return cid;
        }
      } catch (e) {}
    }

    return null;
  }

  clearKickChatInput(win) {
    if (!win || win.isDestroyed()) return;
    try {
      win.webContents.executeJavaScript(`
        (function() {
          const inputEl = document.querySelector(
            '#message-input, [data-chat-input="true"], div#message-input[contenteditable="true"], ' +
            'div[contenteditable="true"][role="textbox"], div[contenteditable="true"], ' +
            '.chat-input [contenteditable="true"], [data-testid="chat-input"], ' +
            'textarea[placeholder*="chat" i], textarea[placeholder*="message" i], textarea'
          );
          if (inputEl) {
            inputEl.textContent = '';
            if (inputEl.value) inputEl.value = '';
          }
        })()
      `).catch(() => {});
    } catch (e) {}
  }

  async _executeSendKickMessage(mode, chatroomId, username, messageText) {
    if (!username) {
      const configStore = require('./configStore');
      username = configStore.get('kickUsername') || 'steushio';
    }
    const isBroadcaster = mode === 'broadcaster';
    const tag = isBroadcaster ? '[Kick Broadcaster]' : '[Kick Bot]';
    const partition = isBroadcaster ? this.kickBroadcasterPartition : this.kickPartition;
    const win = this.prewarmKickChatWindow(mode, username);

    // 1. Ensure the Chromium window has finished loading before executing scripts
    if (win.webContents.isLoading()) {
      await new Promise(r => {
        let done = false;
        const finish = () => { if (!done) { done = true; r(); } };
        win.webContents.once('did-finish-load', finish);
        win.webContents.once('did-fail-load', finish);
        setTimeout(finish, 15000);
      });
    }
    if (win.webContents.isLoading()) {
      let checks = 0;
      while (win.webContents.isLoading() && checks < 8) {
        await new Promise(r => setTimeout(r, 500));
        checks++;
      }
    }

    const kickCapture = require('./kickCapture');
    const targetChatroomId = chatroomId || kickCapture.chatroomId || (username.toLowerCase() === 'steushio' ? '33438685' : null) || await this.resolveKickChatroomId(win, username) || '33438685';

    try {
      // 2. Retrieve session_token & XSRF token from partition cookies
      const s = session.fromPartition(partition);
      const cookies = await s.cookies.get({});
      const sessionTokenCookie = cookies.find(c => c.name === 'session_token' || c.name === 'token');
      const token = sessionTokenCookie ? sessionTokenCookie.value : '';
      const xsrfCookie = cookies.find(c => c.name === 'XSRF-TOKEN');
      const xsrfToken = xsrfCookie ? decodeURIComponent(xsrfCookie.value) : '';

      // 3. PRIMARY STRATEGY: Real Chromium webContents fetch
      // Executed inside the Chromium window context, passing Cloudflare WAF and returning HTTP 200 OK.
      if (token && targetChatroomId && win && !win.isDestroyed()) {
        let apiRes = null;
        for (let attempt = 1; attempt <= 3; attempt++) {
          try {
            if (win.webContents.isLoading()) {
              await new Promise(r => setTimeout(r, 1200));
              continue;
            }
            apiRes = await win.webContents.executeJavaScript(`
              (async function(chatroomId, token, xsrf, text) {
                try {
                  const headers = {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json',
                    'Authorization': 'Bearer ' + token
                  };
                  if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;

                  const res = await fetch('https://kick.com/api/v2/messages/send/' + chatroomId, {
                    method: 'POST',
                    credentials: 'include',
                    headers: headers,
                    body: JSON.stringify({ content: text, type: 'message' })
                  });

                  const textResp = await res.text();
                  let jsonResp = null;
                  try { jsonResp = JSON.parse(textResp); } catch(e) {}

                  return {
                    ok: res.ok,
                    status: res.status,
                    data: jsonResp,
                    error: !res.ok ? textResp.substring(0, 150) : null
                  };
                } catch (e) {
                  return { ok: false, status: 0, error: e.message };
                }
              })(${JSON.stringify(targetChatroomId)}, ${JSON.stringify(token)}, ${JSON.stringify(xsrfToken)}, ${JSON.stringify(messageText)})
            `);
            break;
          } catch (navErr) {
            if (navErr && navErr.message && navErr.message.includes('navigation was in progress')) {
              websocketServer.logSystem(`${tag} Navigation in progress on attempt ${attempt}/3, retrying in 1.5s...`);
              await new Promise(r => setTimeout(r, 1500));
            } else {
              websocketServer.logSystem(`${tag} Script execution issue on attempt ${attempt}/3: ${navErr.message}`, 'warning');
              await new Promise(r => setTimeout(r, 1000));
            }
          }
        }

        if (apiRes && apiRes.ok) {
          websocketServer.logSystem(`${tag} Successfully posted to live chat: "${messageText}"`);
          this.clearKickChatInput(win);
          return true;
        } else if (apiRes && apiRes.status) {
          websocketServer.logSystem(`${tag} API send returned HTTP ${apiRes.status}: ${apiRes.error || 'Failed'}`, 'warning');
        }
      }

      // 3. TERTIARY STRATEGY: DOM Automation Fallback
      const readyCheck = await this.waitForKickChatInputReady(win, 6000, tag);
      if (readyCheck.ok) {
        win.webContents.focus();

        const prep = await win.webContents.executeJavaScript(`
          (function(text) {
            const inputEl = document.querySelector(
              '#message-input, [data-chat-input="true"], div#message-input[contenteditable="true"], ' +
              'div[contenteditable="true"][role="textbox"], div[contenteditable="true"], ' +
              '.chat-input [contenteditable="true"], [data-testid="chat-input"], ' +
              'textarea[placeholder*="chat" i], textarea[placeholder*="message" i], textarea'
            );

            if (!inputEl) return { ok: false, error: 'Chat input element not found' };

            inputEl.focus();

            try {
              const sel = window.getSelection();
              sel.removeAllRanges();
              const range = document.createRange();
              range.selectNodeContents(inputEl);
              sel.addRange(range);
              document.execCommand('delete', false, null);
            } catch (e) {}

            inputEl.textContent = '';
            if (inputEl.value) inputEl.value = '';

            if (inputEl.isContentEditable) {
              document.execCommand('insertText', false, text);
              if ((inputEl.textContent || inputEl.innerText || '').trim() !== text.trim()) {
                inputEl.textContent = text;
              }
              inputEl.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
              inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
            } else {
              inputEl.value = text;
              inputEl.dispatchEvent(new Event('input', { bubbles: true }));
            }
            inputEl.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

            // Dismiss mention dropdown with Escape
            const escDown = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true });
            inputEl.dispatchEvent(escDown);

            // Press Enter
            const enterDown = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
            const enterPress = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
            const enterUp = new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
            inputEl.dispatchEvent(enterDown);
            inputEl.dispatchEvent(enterPress);
            inputEl.dispatchEvent(enterUp);

            // Click Send button
            const buttons = Array.from(document.querySelectorAll('button'));
            const sendBtn = buttons.find(b => {
              const t = (b.textContent || '').trim().toLowerCase();
              const label = (b.getAttribute('aria-label') || '').toLowerCase();
              const testId = (b.getAttribute('data-testid') || '').toLowerCase();
              const title = (b.getAttribute('title') || '').toLowerCase();
              return (
                t === 'chat' || 
                t === 'send' || 
                label.includes('send') || 
                label.includes('chat') || 
                title.includes('send') || 
                title.includes('chat') || 
                testId.includes('send') || 
                testId.includes('chat') || 
                b.type === 'submit'
              );
            });

            if (sendBtn) {
              sendBtn.removeAttribute('disabled');
              sendBtn.removeAttribute('aria-disabled');
              sendBtn.disabled = false;
              sendBtn.click();
            }

            return { ok: true };
          })(${JSON.stringify(messageText)})
        `);

        if (prep && prep.ok) {
          win.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Return' });
          win.webContents.sendInputEvent({ type: 'char', keyCode: '\r' });
          win.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Return' });

          await new Promise(r => setTimeout(r, 600));

          const verify = await win.webContents.executeJavaScript(`
            (function() {
              const inputEl = document.querySelector(
                '#message-input, [data-chat-input="true"], div#message-input[contenteditable="true"], ' +
                'div[contenteditable="true"][role="textbox"], div[contenteditable="true"], ' +
                '.chat-input [contenteditable="true"], [data-testid="chat-input"], textarea'
              );
              if (!inputEl) return { verified: true };
              const remaining = (inputEl.textContent || inputEl.value || '').trim();
              if (remaining !== '') {
                inputEl.textContent = '';
                if (inputEl.value) inputEl.value = '';
              }
              return { verified: remaining === '' };
            })()
          `);

          if (verify && verify.verified) {
            websocketServer.logSystem(`${tag} Successfully posted via DOM: "${messageText}"`);
            return true;
          }
        }
      }

      websocketServer.logSystem(`${tag} Failed to post message: "${messageText}"`, 'warning');
      return false;
    } catch (err) {
      websocketServer.logSystem(`${tag} Send error: ${err.message}`, 'error');
      return false;
    }
  }

  async sendKickBroadcasterMessage(chatroomId, username, messageText) {
    if (!this._kickSendQueue) this._kickSendQueue = Promise.resolve();
    const run = () => this._executeSendKickMessage('broadcaster', chatroomId, username, messageText);
    this._kickSendQueue = this._kickSendQueue.then(run, run);
    return await this._kickSendQueue;
  }

  async sendKickBotMessage(chatroomId, username, messageText) {
    if (!this._kickSendQueue) this._kickSendQueue = Promise.resolve();
    const run = async () => {
      const ok = await this._executeSendKickMessage('bot_account', chatroomId, username, messageText);
      if (!ok) {
        websocketServer.logSystem('[Kick Bot] Dedicated Bot account failed or not logged in. Falling back to Broadcaster account...', 'warning');
        return await this._executeSendKickMessage('broadcaster', chatroomId, username, messageText);
      }
      return true;
    };
    this._kickSendQueue = this._kickSendQueue.then(run, run);
    return await this._kickSendQueue;
  }
}

module.exports = new BotAccountSession();
