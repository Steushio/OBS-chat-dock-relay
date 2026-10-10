const { BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');
const youtubeSession = require('./youtubeSession');

const ollamaBot = require('./ollamaBot');

class ChatCapture {
  constructor() {
    this.chatWindow = null;
    this.currentVideoId = null;
    this.isCapturing = false;

    this.setupIpc();
  }

  setupIpc() {
    // Listen for events sent from the injected chat script
    ipcMain.on('chat-event', (event, chatData) => {
      if (!chatData) return;
      
      chatData.platform = 'youtube';

      // Classify and routing
      if (chatData.type === 'chat') {
        // Send normal chat message to ws clients
        websocketServer.broadcast('chat', chatData);
        // Forward to Ollama Bot
        ollamaBot.handleChatMessage(chatData);
      } else if (chatData.type === 'poll' || chatData.type === 'poll_ended') {
        websocketServer.broadcast('poll', chatData);
        websocketServer.broadcast('chat', chatData);
        if (chatData.type === 'poll') {
          websocketServer.logSystem(`📊 Live YouTube Poll captured: "${chatData.question}" (${chatData.totalVotes || 'active'})`);
        } else {
          websocketServer.logSystem(`📊 Live YouTube Poll ended`);
        }
      } else {
        // SuperChats, Stickers, Memberships, Gifts are all Alerts
        websocketServer.broadcast('alert', chatData);
        // Also feed alerts into the chat dock stream for multi-purpose views
        websocketServer.broadcast('chat', chatData);
        websocketServer.logSystem(`Alert Captured: [${chatData.type}] from ${chatData.username}: ${chatData.message || chatData.amount}`);
      }
    });

    ipcMain.on('chat-observer-ready', (event, status) => {
      websocketServer.logSystem('YouTube Chat DOM Observer successfully attached!');
    });
  }

  startCapture(videoId) {
    if (!videoId) {
      websocketServer.logSystem('Cannot start chat capture: No video ID provided', 'warning');
      return;
    }

    this.currentVideoId = videoId;
    this.isCapturing = true;

    const chatUrl = `https://www.youtube.com/live_chat?is_popout=1&v=${videoId}`;
    websocketServer.logSystem(`Initializing Chat Capture for video: ${videoId}`);

    try {
      const botAccountSession = require('./botAccountSession');
      botAccountSession.prewarmYouTubeBotChat(videoId);
    } catch (e) {}

    if (this.chatWindow && !this.chatWindow.isDestroyed()) {
      // Re-use existing window by navigating to the new URL
      websocketServer.logSystem(`Redirecting active chat window to new stream...`);
      this.chatWindow.loadURL(chatUrl);
      return;
    }

    this.chatWindow = new BrowserWindow({
      width: 1024,
      height: 768,
      show: false, // Background window
      skipTaskbar: true,
      frame: false,
      webPreferences: {
        partition: youtubeSession.sessionPartition,
        preload: path.join(__dirname, '..', 'inject', 'youtubeChatInject.js'),
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        backgroundThrottling: false,
        images: false,
        webgl: false,
        enableWebSQL: false
      }
    });

    this.chatWindow.setMenu(null);
    this.chatWindow.webContents.setAudioMuted(true);

    this.chatWindow.loadURL(chatUrl);

    this.chatWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription) => {
      websocketServer.logSystem(`Chat Capture failed to load page: ${errorDescription}`, 'warning');
    });

    this.chatWindow.on('closed', () => {
      if (this.isCapturing && this.currentVideoId) {
        websocketServer.logSystem('Chat window closed unexpectedly. Re-spawning...', 'warning');
        // Respawn if we are supposed to be active
        setTimeout(() => {
          if (this.isCapturing && this.currentVideoId) {
            this.startCapture(this.currentVideoId);
          }
        }, 3000);
      }
    });
  }

  stopCapture() {
    this.isCapturing = false;
    this.currentVideoId = null;
    if (this.chatWindow && !this.chatWindow.isDestroyed()) {
      // Temporarily remove listener to avoid infinite respawn
      this.chatWindow.removeAllListeners('closed');
      this.chatWindow.destroy();
    }
    this.chatWindow = null;
    websocketServer.logSystem('Chat capturing engine stopped');
  }

  switchStream(videoId) {
    if (videoId === this.currentVideoId) return;
    websocketServer.logSystem(`Switching chat capture stream: ${this.currentVideoId || 'none'} -> ${videoId}`);
    this.startCapture(videoId);
  }

  async sendMessage(messageText) {
    if (!this.chatWindow || this.chatWindow.isDestroyed()) {
      websocketServer.logSystem('[YouTube Broadcaster Chat] Cannot send chat message: Chat window not active.', 'warning');
      return false;
    }

    try {
      // 1. Focus WebContents
      this.chatWindow.webContents.focus();

      // 2. Focus input and prepare contenteditable container
      const prep = await this.chatWindow.webContents.executeJavaScript(`
        (function(text) {
          const inputEl = document.querySelector(
            'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
            'div#input[contenteditable="true"], ' +
            'yt-live-chat-text-input-field-renderer #input[contenteditable="true"], ' +
            '#input.yt-live-chat-text-input-field-renderer, ' +
            '[contenteditable="true"]'
          );

          if (!inputEl) {
            const signIn = document.querySelector('#sign-in-button, a[href*="accounts.google.com"], a[href*="signin"]');
            if (signIn) return { ok: false, error: 'Broadcaster account is not signed in to YouTube chat' };
            return { ok: false, error: 'Chat input element not found in DOM' };
          }

          inputEl.focus();
          const sel = window.getSelection();
          sel.removeAllRanges();
          const range = document.createRange();
          range.selectNodeContents(inputEl);
          sel.addRange(range);
          document.execCommand('delete', false, null);

          document.execCommand('insertText', false, text);
          if ((inputEl.textContent || '').trim() !== text.trim()) {
            inputEl.textContent = text;
          }

          inputEl.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
          inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
          inputEl.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

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

          return { ok: true };
        })(${JSON.stringify(messageText)})
      `);

      if (!prep || !prep.ok) {
        websocketServer.logSystem(`[YouTube Broadcaster Chat] Cannot send: ${prep?.error || 'Input element not found'}`, 'warning');
        return false;
      }

      await new Promise(r => setTimeout(r, 250));

      // 3. Trigger submit via synthetic Enter + Button click
      await this.chatWindow.webContents.executeJavaScript(`
        (function() {
          const inputEl = document.querySelector(
            'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
            'div#input[contenteditable="true"], ' +
            '[contenteditable="true"]'
          );
          if (inputEl) {
            const enterDown = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true, cancelable: true });
            const enterPress = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true, cancelable: true });
            const enterUp = new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true, cancelable: true });
            inputEl.dispatchEvent(enterDown);
            inputEl.dispatchEvent(enterPress);
            inputEl.dispatchEvent(enterUp);
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
            sendBtn.disabled = false;
            sendBtn.click();
          }

          const msgInput = document.querySelector('yt-live-chat-message-input-renderer');
          if (msgInput && typeof msgInput.onSendButtonClick_ === 'function') {
            msgInput.onSendButtonClick_();
          }
        })()
      `);

      // Native Enter fallback
      this.chatWindow.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Return' });
      this.chatWindow.webContents.sendInputEvent({ type: 'char', keyCode: '\r' });
      this.chatWindow.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Return' });

      await new Promise(r => setTimeout(r, 800));
      websocketServer.logSystem(`[YouTube Broadcaster Chat] Successfully posted to live chat: "${messageText}"`);
      return true;
    } catch (err) {
      websocketServer.logSystem(`[YouTube Broadcaster Chat] Error sending message: ${err.message}`, 'error');
      return false;
    }
  }
}

module.exports = new ChatCapture();
