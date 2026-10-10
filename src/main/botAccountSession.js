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
