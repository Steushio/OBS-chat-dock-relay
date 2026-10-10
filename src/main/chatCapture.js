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
      websocketServer.logSystem('[YouTube Bot] Cannot send chat message: Chat window not active.', 'warning');
      return false;
    }

    try {
      // 1. Focus input and prepare contenteditable container
      const prep = await this.chatWindow.webContents.executeJavaScript(`
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

          return { ok: true };
        })()
      `);

      if (!prep || !prep.ok) {
        websocketServer.logSystem(`[YouTube Chat] Cannot send: ${prep?.error || 'Input element not found'}`, 'warning');
        return false;
      }

      // 2. Use Chromium native text insertion (trusted by YouTube Polymer components)
      await this.chatWindow.webContents.insertText(messageText);
      await new Promise(r => setTimeout(r, 350));

      // 3. Trigger submit and click Send button
      const clicked = await this.chatWindow.webContents.executeJavaScript(`
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
      this.chatWindow.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Return' });
      this.chatWindow.webContents.sendInputEvent({ type: 'char', keyCode: '\r' });
      this.chatWindow.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'Return' });

      websocketServer.logSystem(`[YouTube Chat] Successfully posted to live chat: "${messageText}"`);
      return true;
    } catch (err) {
      websocketServer.logSystem(`[YouTube Bot] Error sending message: ${err.message}`, 'error');
      return false;
    }
  }
}

module.exports = new ChatCapture();
