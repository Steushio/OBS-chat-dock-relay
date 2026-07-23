const { BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const websocketServer = require('./websocketServer');
const youtubeSession = require('./youtubeSession');

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
      width: 100,
      height: 100,
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
        paintWhenInitiallyHidden: false,
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
}

module.exports = new ChatCapture();
