const { ipcMain } = require('electron');
const configStore = require('./configStore');
const websocketServer = require('./websocketServer');
const chatCapture = require('./chatCapture');
const kickCapture = require('./kickCapture');
const botAccountSession = require('./botAccountSession');

class OllamaBot {
  constructor() {
    this.defaults = {
      enabled: false,
      ollamaUrl: 'http://127.0.0.1:11434',
      model: 'qwen2.5:3b',
      botName: 'AI Bot',
      systemPrompt: 'You are a hype, energetic, and casual live stream chat bot for a live gaming stream.\nRules:\n- Respond naturally like a streamer or chat moderator using casual stream slang, hype, or emojis.\n- NEVER sound like customer support, an assistant, or say "How can I help you today?".\n- Keep responses short, direct, and under 15 words.',
      autoRespond: 'mention_only', // 'off' | 'mention_only' | 'all_chat'
      cooldownSeconds: 10,
      firstWordsEnabled: true,
      firstWordsMode: 'template', // 'template' | 'ai'
      firstWordsTemplate: 'Welcome to the stream {username}! Thanks for chatting!',
      enableYouTube: true,
      enableKick: true,
      youtubeAccountMode: 'broadcaster', // 'broadcaster' | 'bot_account'
      kickAccountMode: 'broadcaster',      // 'broadcaster' | 'bot_account'
      commands: [
        { trigger: '!discord', response: 'Join our Discord community!' },
        { trigger: '!socials', response: 'Check out our socials!' }
      ],
      timers: [
        { enabled: true, intervalMinutes: 10, message: 'Enjoying the stream? Make sure to hit the follow button!' }
      ]
    };

    this.settings = this.loadSettings();
    this.seenChatters = new Set();
    this.lastAiResponseTime = 0;
    this.activeTimerIntervals = [];

    this.setupIpc();
    this.startTimers();
  }

  loadSettings() {
    const saved = configStore.get('ollamaBotSettings');
    return { ...this.defaults, ...saved };
  }

  saveSettings(newSettings) {
    this.settings = { ...this.settings, ...newSettings };
    configStore.set('ollamaBotSettings', this.settings);
    this.startTimers();
    websocketServer.logSystem('[Ollama Bot] Settings saved and applied successfully');
  }

  setupIpc() {
    ipcMain.handle('get-ollama-settings', () => this.settings);

    ipcMain.handle('save-ollama-settings', (event, settings) => {
      this.saveSettings(settings);
      return { success: true };
    });

    ipcMain.handle('fetch-ollama-models', async (event, url) => {
      return await this.fetchModels(url || this.settings.ollamaUrl);
    });

    ipcMain.handle('test-ollama-prompt', async (event, { prompt, url, model, systemPrompt }) => {
      return await this.generateAiResponse(
        prompt || 'Hello!',
        'Tester',
        'test',
        { url, model, systemPrompt }
      );
    });

    ipcMain.handle('reset-first-words-session', () => {
      this.resetFirstWordsSession();
      return { success: true };
    });
  }

  normalizeUrl(url) {
    if (!url) return 'http://127.0.0.1:11434';
    let clean = url.trim().replace(/\/$/, '');
    if (!clean.startsWith('http://') && !clean.startsWith('https://')) {
      clean = 'http://' + clean;
    }
    return clean.replace('://localhost', '://127.0.0.1');
  }

  // Fetch installed models from Ollama /api/tags using native Node fetch
  async fetchModels(ollamaUrl) {
    const baseUrl = this.normalizeUrl(ollamaUrl);
    const targetUrl = `${baseUrl}/api/tags`;
    try {
      const response = await fetch(targetUrl);
      if (response.ok) {
        const json = await response.json();
        const models = (json.models || []).map(m => m.name);
        return { success: true, models };
      }
      return { success: false, error: `HTTP ${response.status}: ${response.statusText}` };
    } catch (err) {
      return { success: false, error: err.message };
    }
  }

  // Generate AI Response using Ollama /api/generate via native Node fetch
  async generateAiResponse(userPrompt, username, platform, overrideConfig = null) {
    const baseUrl = this.normalizeUrl(overrideConfig?.url || this.settings.ollamaUrl);
    let model = overrideConfig?.model || this.settings.model || 'qwen2.5:3b';
    const systemPrompt = overrideConfig?.systemPrompt || this.settings.systemPrompt;

    const fullPrompt = `System Instructions: ${systemPrompt}

[Live Stream Chat Context]
Platform: ${platform}
Chatter: @${username}
Viewer Message: "${userPrompt}"

Instruction: Write a short 1-line live stream chat reply to @${username}. Do NOT say "How can I help you today?" or sound like customer support. Keep it casual, hype, and short (under 15 words)!
Chat Reply:`;

    try {
      const response = await fetch(`${baseUrl}/api/generate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: model,
          prompt: fullPrompt,
          stream: false,
          options: {
            temperature: 0.7,
            num_predict: 50
          }
        })
      });

      if (!response.ok) {
        throw new Error(`Ollama API returned HTTP ${response.status}`);
      }

      const json = await response.json();
      let aiText = (json.response || '').trim();

      // Clean up response: single line, strip quotes, max 200 chars
      aiText = aiText.replace(/[\r\n]+/g, ' ').replace(/^["']|["']$/g, '');
      if (aiText.length > 200) {
        aiText = aiText.substring(0, 197) + '...';
      }

      return { success: true, text: aiText };
    } catch (err) {
      return { success: false, error: err.message };
    }
  }

  // Handle incoming live chat message from YouTube or Kick
  async handleChatMessage(chatData) {
    if (!this.settings.enabled) return;
    if (!chatData || !chatData.username || !chatData.message) return;

    const platform = chatData.platform || 'youtube';
    if (platform === 'youtube' && !this.settings.enableYouTube) return;
    if (platform === 'kick' && !this.settings.enableKick) return;

    const username = chatData.username.trim();
    const rawMessage = chatData.message.trim();
    const lowerMessage = rawMessage.toLowerCase();
    const userKey = `${platform}:${username.toLowerCase()}`;

    // Ignore messages sent by the bot itself
    if (username.toLowerCase() === (this.settings.botName || 'ai bot').toLowerCase()) {
      return;
    }

    // 1. First Words Welcomer Check
    if (!this.seenChatters.has(userKey)) {
      this.seenChatters.add(userKey);
      if (this.settings.firstWordsEnabled) {
        this.processFirstWords(username, platform);
      }
    }

    // 2. Custom Commands Check (e.g. !discord, !socials)
    if (Array.isArray(this.settings.commands)) {
      for (const cmd of this.settings.commands) {
        if (cmd.trigger && lowerMessage.startsWith(cmd.trigger.toLowerCase())) {
          websocketServer.logSystem(`[Ollama Bot] Triggered command: ${cmd.trigger} for ${username}`);
          this.sendBotReply(platform, `@${username} ${cmd.response}`);
          return;
        }
      }
    }

    // 3. AI Response Check
    const botNameLower = (this.settings.botName || 'bot').toLowerCase();
    const isAiTriggered = 
      lowerMessage.startsWith('!ai ') || 
      lowerMessage.includes(`@${botNameLower}`) ||
      lowerMessage.includes(botNameLower);

    const shouldRespond = 
      (this.settings.autoRespond === 'all_chat') || 
      (this.settings.autoRespond === 'mention_only' && isAiTriggered);

    if (shouldRespond) {
      const now = Date.now();
      const cooldownMs = (this.settings.cooldownSeconds || 10) * 1000;
      if (now - this.lastAiResponseTime < cooldownMs) {
        return; // In cooldown
      }

      // Clean prompt (remove !ai prefix or @botName)
      let cleanPrompt = rawMessage
        .replace(/^!ai\s+/i, '')
        .replace(new RegExp(`@${this.settings.botName}`, 'gi'), '')
        .trim();

      if (!cleanPrompt) cleanPrompt = 'Hello!';

      this.lastAiResponseTime = now;
      websocketServer.logSystem(`[Ollama Bot] Generating AI response for ${username} (${platform}): "${cleanPrompt}"`);

      const aiResult = await this.generateAiResponse(cleanPrompt, username, platform);
      if (aiResult.success && aiResult.text) {
        const replyText = `@${username} ${aiResult.text}`;
        this.sendBotReply(platform, replyText);
      } else {
        websocketServer.logSystem(`[Ollama Bot] AI Generation failed: ${aiResult.error}`, 'warning');
      }
    }
  }

  async processFirstWords(username, platform) {
    if (this.settings.firstWordsMode === 'ai') {
      const aiResult = await this.generateAiResponse(`Welcome user ${username} who just sent their first chat message!`, username, platform);
      if (aiResult.success && aiResult.text) {
        this.sendBotReply(platform, aiResult.text);
        return;
      }
    }

    // Default template
    const template = this.settings.firstWordsTemplate || 'Welcome to the stream {username}!';
    const welcomeMsg = template.replace('{username}', username);
    this.sendBotReply(platform, welcomeMsg);
  }

  async sendBotReply(platform, messageText) {
    websocketServer.logSystem(`[Ollama Bot -> ${platform.toUpperCase()}] "${messageText}"`);

    // Broadcast bot reply to WebSocket overlays as system chat message
    websocketServer.broadcast('chat', {
      id: 'bot-' + Date.now(),
      platform: platform,
      type: 'chat',
      username: this.settings.botName || 'AI Bot',
      message: messageText,
      badges: ['bot'],
      avatar: ''
    });

    if (platform === 'youtube' && this.settings.enableYouTube) {
      if (this.settings.youtubeAccountMode === 'bot_account') {
        botAccountSession.sendYouTubeBotMessage(chatCapture.currentVideoId, messageText);
      } else {
        chatCapture.sendMessage(messageText);
      }
    } else if (platform === 'kick' && this.settings.enableKick) {
      if (this.settings.kickAccountMode === 'bot_account') {
        botAccountSession.sendKickBotMessage(kickCapture.chatroomId, kickCapture.username, messageText);
      } else {
        kickCapture.sendMessage(messageText);
      }
    }
  }

  startTimers() {
    // Clear existing timer intervals
    this.activeTimerIntervals.forEach(intervalId => clearInterval(intervalId));
    this.activeTimerIntervals = [];

    if (!this.settings.enabled || !Array.isArray(this.settings.timers)) return;

    this.settings.timers.forEach(timer => {
      if (timer.enabled && timer.message && timer.intervalMinutes > 0) {
        const ms = timer.intervalMinutes * 60 * 1000;
        const intervalId = setInterval(() => {
          if (!this.settings.enabled) return;
          websocketServer.logSystem(`[Ollama Bot Timer] Triggered recurring message (${timer.intervalMinutes}m)`);
          if (this.settings.enableYouTube) {
            this.sendBotReply('youtube', timer.message);
          }
          if (this.settings.enableKick) {
            this.sendBotReply('kick', timer.message);
          }
        }, ms);
        this.activeTimerIntervals.push(intervalId);
      }
    });
  }

  resetFirstWordsSession() {
    this.seenChatters.clear();
    websocketServer.logSystem('[Ollama Bot] Reset First Words session chatters.');
  }
}

module.exports = new OllamaBot();
