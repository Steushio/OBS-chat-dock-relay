const { ipcMain } = require('electron');
const configStore = require('./configStore');
const websocketServer = require('./websocketServer');
const botAccountSession = require('./botAccountSession');
const streamManager = require('./streamManager');
const webSearchHelper = require('./webSearchHelper');

class OllamaBot {
  constructor() {
    this.defaults = {
      enabled: false,
      ollamaUrl: 'http://127.0.0.1:11434',
      model: 'qwen2.5:3b',
      botName: 'Cutie Bot',
      ignoredUsers: 'cutie bot, cutiebot, cutiebot.0, cutie_bot, nightbot, streamelements, moobot, streamlabs',
      streamerKnowledge: 'Streamer Information, Gear & FAQ:\n- Broadcaster: Steushio (Cutie Bot)\n- Health / Surgery: Left hand surgery; challenges himself to reach Ascendant rank in Valorant with one hand using MMO mouse only!\n- Mouse: VXE R1se+ (main), Redragon 612 pro rgb (current)\n- Keyboard: Redragon Fizz (owns this keyboard, but plays mouse-only during gameplay due to surgery)\n- PC Hardware / Specs: CPU: Intel Core i5-10600K @ 4.10GHz | GPU: AMD Radeon RX 550 | RAM: 16 GB DDR4\n- Settings: DPI 1600 | In-game sens 0.3 / 1 (Valorant)\n- Game: Valorant (India)\n- Discord: https://discord.gg/UBsenpp',
      streamDescription: '',
      enableWebSearch: true,
      systemPrompt: 'You are an intelligent, friendly live stream chat bot for a live gaming stream.\nRules:\n- Answer viewer questions accurately using the stream description, streamer knowledge, and general facts.\n- If a viewer asks why the streamer plays without a keyboard or how they play, explain that it is due to health issues as mentioned in the stream info.\n- Welcome new viewers warmly.\n- Keep answers short, direct, and under 25 words.\n- NEVER sound like corporate customer support or say "How can I help you today?".',
      autoRespond: 'questions_and_mentions', // 'off' | 'mention_only' | 'questions_and_mentions' | 'all_chat'
      cooldownSeconds: 8,
      firstWordsEnabled: true,
      firstWordsMode: 'ai', // 'ai' | 'template'
      firstWordsTemplate: 'Welcome to the stream {username}! Thanks for chatting!',
      enableYouTube: true,
      enableKick: true,
      youtubeAccountMode: 'bot_account', // 'broadcaster' | 'bot_account'
      kickAccountMode: 'bot_account',      // 'broadcaster' | 'bot_account'
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
    this.recentBotReplies = new Map();
    this.recentChatHistory = []; // { username, message, isBot, timestamp }
    this.conversationHistory = []; // { role: 'user'|'assistant', content } multi-turn memory
    this.lastAiResponseTime = 0;
    this.activeTimerIntervals = [];
    this.resetTimerState();

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

    ipcMain.handle('test-ollama-prompt', async (event, { prompt, url, model, systemPrompt, streamerKnowledge, streamDescription }) => {
      return await this.generateAiResponse(
        prompt || 'why play without keyboard?',
        'Tester',
        'youtube',
        'chat',
        { url, model, systemPrompt, streamerKnowledge, streamDescription }
      );
    });

    ipcMain.handle('fetch-stream-description', async () => {
      return await this.fetchLiveStreamDescription();
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

  // Fetch live stream description from YouTube Studio or YouTube player API
  async fetchLiveStreamDescription() {
    const activeStream = streamManager.getActiveStream();
    if (activeStream?.description && activeStream.description.trim().length > 5) {
      this.settings.streamDescription = activeStream.description.trim();
      configStore.set('ollamaBotSettings', this.settings);
      return { success: true, description: activeStream.description.trim() };
    }

    const chatCapture = require('./chatCapture');
    const videoId = activeStream?.videoId || chatCapture.currentVideoId || configStore.get('lastVideoId');
    if (videoId) {
      websocketServer.logSystem(`[Ollama Bot] Querying YouTube player details for video: ${videoId}...`);
      const details = await streamManager.fetchYouTubeVideoDetails(videoId);
      if (details?.description && details.description.trim().length > 0) {
        this.settings.streamDescription = details.description.trim();
        configStore.set('ollamaBotSettings', this.settings);
        if (activeStream) activeStream.description = details.description.trim();
        return { success: true, description: details.description.trim() };
      }
    }

    if (this.settings.streamDescription && this.settings.streamDescription.trim().length > 0) {
      return { success: true, description: this.settings.streamDescription.trim() };
    }

    return { success: false, error: 'No stream description found from YouTube yet. You can paste your description directly in the box below.' };
  }

  // Check if a username is this bot itself or another configured bot (e.g. Nightbot)
  isSelfOrBot(username) {
    if (!username) return true;
    const cleanUser = username.toLowerCase().replace(/^@/, '').trim();
    const botName = (this.settings.botName || 'cutie bot').toLowerCase().replace(/^@/, '').trim();

    // Direct bot name match
    if (cleanUser === botName) return true;

    // Cutie Bot specific variants
    if (
      cleanUser === 'cutie bot' ||
      cleanUser === 'cutiebot' ||
      cleanUser === 'cutie_bot' ||
      cleanUser === 'cutiebot.0'
    ) {
      return true;
    }

    // Configured ignored users list (bots like nightbot, streamelements)
    const rawList = this.settings.ignoredUsers;
    const list = Array.isArray(rawList)
      ? rawList
      : (typeof rawList === 'string' ? rawList.split(',') : []);

    const kickUser = (configStore.get('kickUsername') || '').toLowerCase().trim();

    for (const item of list) {
      const cleanItem = item.toLowerCase().replace(/^@/, '').trim();
      if (!cleanItem) continue;
      // Do NOT classify the broadcaster account as a bot! Broadcaster is handled separately
      if (kickUser && (cleanItem === kickUser || cleanItem === `@${kickUser}`)) continue;
      if (cleanUser === cleanItem || cleanUser === cleanItem.replace(/\s+/g, '')) {
        return true;
      }
    }

    return false;
  }

  // Check if message is from the broadcaster account
  isBroadcaster(username, chatData = null) {
    if (!username) return false;
    const cleanUser = username.toLowerCase().replace(/^@/, '').trim();
    const kickUser = (configStore.get('kickUsername') || '').toLowerCase().trim();
    if (kickUser && (cleanUser === kickUser || cleanUser === `@${kickUser}`)) {
      return true;
    }
    if (chatData && Array.isArray(chatData.badges)) {
      if (chatData.badges.includes('owner') || chatData.badges.includes('broadcaster')) {
        return true;
      }
    }
    return false;
  }

  // Backwards compatibility helper
  isIgnoredUser(username) {
    return this.isSelfOrBot(username) || this.isBroadcaster(username);
  }

  // Fetch installed models from Ollama /api/tags
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

  // Generate AI Response using Ollama /api/chat with stream description, FAQ, and internet search
  async generateAiResponse(userPrompt, username, platform, contextType = 'chat', overrideConfig = null) {
    const baseUrl = this.normalizeUrl(overrideConfig?.url || this.settings.ollamaUrl);
    const model = overrideConfig?.model || this.settings.model || 'qwen2.5:3b';
    const systemPrompt = overrideConfig?.systemPrompt || this.settings.systemPrompt;

    const cleanUser = (username || '').replace(/^@+/, '').trim();

    // 1. Gather Live Stream Context
    const activeStream = streamManager.getActiveStream();
    const streamTitle = activeStream?.title || configStore.get('lastStreamTitle') || 'Live Stream';
    const streamDescription = overrideConfig?.streamDescription || activeStream?.description || this.settings.streamDescription || '';
    const streamerKnowledge = overrideConfig?.streamerKnowledge || this.settings.streamerKnowledge ||
      `Streamer Information, Gear & FAQ:
- Broadcaster: Steushio (Cutie Bot)
- Health / Surgery: Left hand surgery; challenges himself to reach Ascendant rank in Valorant with one hand using MMO mouse only!
- Mouse: VXE R1se+ (main), Redragon 612 pro rgb (current)
- Keyboard: Redragon Fizz (owns this keyboard, but plays mouse-only during gameplay due to left hand surgery)
- PC Hardware / Specs: CPU: Intel Core i5-10600K @ 4.10GHz | GPU: AMD Radeon RX 550 | RAM: 16 GB DDR4
- Settings: DPI 1600 | In-game sens 0.3 / 1 (Valorant)
- Game: Valorant (India)
- Discord: https://discord.gg/UBsenpp`;

    // 2. Determine if the question is covered by the Stream Description & FAQ
    const isStreamCovered = webSearchHelper.isCoveredByStreamContext(userPrompt);

    // 3. Live Internet Search if not covered by Stream Description
    let webSnippet = null;
    const isWebSearchEnabled = this.settings.enableWebSearch !== false;
    const isFactualQuery = webSearchHelper.isSearchableQuery(userPrompt);

    if (!isStreamCovered && contextType !== 'first_words') {
      if (isWebSearchEnabled && isFactualQuery) {
        websocketServer.logSystem(`[Ollama Bot] Question not in stream description. Checking the internet for: "${userPrompt}"...`);
        webSnippet = await webSearchHelper.search(userPrompt);
        if (webSnippet) {
          websocketServer.logSystem(`[Ollama Bot] Found internet answer: "${webSnippet.substring(0, 100)}..."`);
        } else {
          websocketServer.logSystem(`[Ollama Bot] Internet search found no answer for: "${userPrompt}".`);
        }
      }

      // If the viewer explicitly asked for outside factual trivia (e.g. "who is CEO of", "what is the capital of")
      // and neither the stream context nor the internet had an answer, skip to avoid hallucinating false facts.
      // For general chat, greetings, opinions, stream questions, or !ai, continue to AI generation!
      const isTriviaQuestion = /\b(who is|who was|what is the capital|release date|net worth|ceo of|price of|score of|president of|how old is|where was|population of)\b/i.test(userPrompt);
      if (isTriviaQuestion && !webSnippet) {
        websocketServer.logSystem(`[Ollama Bot] Trivia answer not found in stream description or internet for "${userPrompt}". Skipping reply as requested.`);
        return { success: false, skipped: true, reason: 'Answer not found in stream description or internet' };
      }
    }

    // 4. Assemble Conversational System Instructions
    const fullSystemInstruction = `${systemPrompt || 'You are Cutie Bot, the smart and friendly live stream chat assistant for streamer Steushio.'}

[PRIMARY SOURCE OF TRUTH: LIVE STREAM DESCRIPTION & STREAMER KNOWLEDGE]
Before answering ANY viewer question, you MUST search the stream description and broadcaster knowledge below:

=== LIVE STREAM DESCRIPTION ===
${streamDescription || 'No live stream description provided.'}
===============================

=== BROADCASTER FAQ & GEAR KNOWLEDGE ===
${streamerKnowledge}
========================================
${webSnippet ? `\n=== LIVE INTERNET FACTS ===\n${webSnippet}\n===========================\n` : ''}
[STRICT ANSWERING RULES]:
1. ALWAYS check the Live Stream Description and FAQ above first! Base your answer directly on those facts.
2. GEAR & HARDWARE:
   - Mouse: Look at description & FAQ (VXE R1se+ main, Redragon 612 pro rgb).
   - Keyboard: Look at description & FAQ! The streamer owns a "Redragon Fizz" keyboard. If asked what keyboard they have, answer Redragon Fizz! If asked why they don't use it in game, explain they play mouse-only due to left hand surgery.
   - PC Specs / Hardware: Look at the FAQ (CPU: Intel Core i5-10600K, GPU: AMD Radeon RX 550, RAM: 16GB). If a user asks about PC specs, hardware, CPU, or GPU, answer with these PC specs! NEVER reply with mouse controls when asked about PC specs or hardware!
   - Settings / Sens: Look at description & FAQ (DPI 1600, Sens 0.3 / 1 in Valorant).
3. HEALTH, SURGERY & PLAYSTYLE:
   - If asked why the streamer plays without a keyboard, one-handed, or about health: Explain that Steushio had left hand surgery and is doing a challenge to reach Ascendant rank in Valorant with one hand using MMO mouse only!
4. ANTI-REPETITION:
   - NEVER repeat the exact same sentence you gave previously. If the viewer asks a follow-up or new question (e.g. asking about specs or keyboard after mouse), answer that specific question with relevant details.
5. CONCISE & NATURAL:
   - Keep answers friendly, direct, and under 25 words (1-2 sentences).
   - NEVER sound like corporate customer support or say "How can I help you today?".
   - Write ONLY the chat reply text itself. Do NOT prepend "@${cleanUser}" and do NOT wrap the reply in quotes.
6. ${contextType === 'first_words' 
  ? `FIRST WORDS GREETING MODE:
   - Welcome this new chatter to the stream warmly and casually in 1 short sentence (under 15 words).` 
  : `NO HALLUCINATIONS / SKIP IF UNKNOWN:
   - If the verified answer is not found in the description, knowledge base, or internet facts, output ONLY: "NO_ANSWER".`}`;

    // 4. Assemble Multi-Turn Messages (Conversational like Gemini)
    const messages = [
      { role: 'system', content: fullSystemInstruction }
    ];

    // Append recent conversational turns for multi-turn context
    if (Array.isArray(this.conversationHistory) && this.conversationHistory.length > 0) {
      messages.push(...this.conversationHistory.slice(-6));
    }

    // Append current turn
    messages.push({
      role: 'user',
      content: contextType === 'first_words' 
        ? `${cleanUser}: (Just arrived in stream!) "${userPrompt}"` 
        : `${cleanUser}: "${userPrompt}"`
    });

    let aiText = '';

    // 5. Query Ollama /api/chat
    try {
      const response = await fetch(`${baseUrl}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: model,
          messages: messages,
          stream: false,
          options: {
            temperature: 0.7,
            num_predict: 100
          }
        })
      });

      if (response.ok) {
        const json = await response.json();
        aiText = (json.message?.content || '').trim();
      } else {
        // Fallback to /api/generate if /api/chat is not supported
        const fallbackPrompt = `${fullSystemInstruction}\n\nChatter: @${cleanUser}\nMessage: "${userPrompt}"\nChat Reply:`;
        const genRes = await fetch(`${baseUrl}/api/generate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            model: model,
            prompt: fallbackPrompt,
            stream: false,
            options: { temperature: 0.7, num_predict: 80 }
          })
        });
        if (genRes.ok) {
          const genJson = await genRes.json();
          aiText = (genJson.response || '').trim();
        } else {
          throw new Error(`Ollama returned HTTP ${response.status}`);
        }
      }

      // Clean up response: single line, strip quotes
      aiText = aiText.replace(/[\r\n]+/g, ' ').replace(/^["']|["']$/g, '').trim();

      // Check if Ollama could not find a verified answer or outputted NO_ANSWER
      const lowerAi = aiText.toLowerCase().trim();
      if (
        aiText.trim().toUpperCase() === 'NO_ANSWER' ||
        aiText.includes('NO_ANSWER') ||
        lowerAi.includes('no_answer') ||
        lowerAi.includes("i don't have information") ||
        lowerAi.includes("not mentioned in the description") ||
        lowerAi.includes("not listed in the stream description") ||
        lowerAi.includes("i couldn't find") ||
        lowerAi.includes("i could not find") ||
        lowerAi.includes("i don't know")
      ) {
        websocketServer.logSystem(`[Ollama Bot] Verified answer not found for "${userPrompt}". Skipping reply as requested.`);
        return { success: false, skipped: true, reason: 'Verified answer not found' };
      }

      // Crucial: Strip any leading @username or @username: or @bot prefix that Ollama might have generated
      const cleanPrefixRegex = new RegExp(`^@?(${cleanUser}|${username}|${this.settings.botName || 'bot'}|cutiebot)[:\\s,]+`, 'i');
      while (cleanPrefixRegex.test(aiText)) {
        aiText = aiText.replace(cleanPrefixRegex, '').trim();
      }
      aiText = aiText.replace(/^@+/, '').trim();

      if (aiText.length > 220) {
        aiText = aiText.substring(0, 217) + '...';
      }

      // Record in conversation history for multi-turn memory
      this.conversationHistory.push({ role: 'user', content: `${cleanUser}: "${userPrompt}"` });
      this.conversationHistory.push({ role: 'assistant', content: aiText });
      if (this.conversationHistory.length > 10) {
        this.conversationHistory = this.conversationHistory.slice(-10);
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

    // Clean up expired recent bot replies (> 45s)
    const now = Date.now();
    for (const [text, timestamp] of this.recentBotReplies.entries()) {
      if (now - timestamp > 45000) {
        this.recentBotReplies.delete(text);
      }
    }

    // 1. SAFETY: Ignore messages sent by cutie bot, other bots, or this bot's own text
    if (this.isSelfOrBot(username) || this.recentBotReplies.has(lowerMessage)) {
      return;
    }

    const isBroadcasterUser = this.isBroadcaster(username, chatData);

    // Track real viewer chat activity for activity-gated timer messages
    if (!isBroadcasterUser) {
      this.recordUserMessageForTimers(platform);
    }

    // 2. CUSTOM COMMANDS CHECK (e.g. !discord, !socials) - commands take precedence and work for BOTH streamer & viewers!
    if (Array.isArray(this.settings.commands)) {
      for (const cmd of this.settings.commands) {
        if (cmd.trigger && lowerMessage.startsWith(cmd.trigger.toLowerCase())) {
          this.seenChatters.add(userKey);
          websocketServer.logSystem(`[Ollama Bot] Triggered command: ${cmd.trigger} for ${username}`);
          this.sendBotReply(platform, `@${username} ${cmd.response}`);
          return;
        }
      }
    }

    // 3. BROADCASTER CASUAL CHAT FILTER:
    // Broadcaster's casual chat messages are ignored so the bot doesn't talk over the streamer.
    // However, if the streamer explicitly tests AI using "!ai ...", allow it!
    if (isBroadcasterUser) {
      if (!lowerMessage.startsWith('!ai ')) {
        return; // Ignore broadcaster's casual conversation
      }
    }

    // Record incoming message in chat history buffer
    this.recentChatHistory.push({ username, message: rawMessage, timestamp: now });
    if (this.recentChatHistory.length > 10) {
      this.recentChatHistory.shift();
    }

    // 4. FIRST WORDS WELCOMER CHECK (For viewers only, one greeting per session)
    if (!isBroadcasterUser && !this.seenChatters.has(userKey)) {
      this.seenChatters.add(userKey);
      if (this.settings.firstWordsEnabled) {
        await this.processFirstWords(username, platform, rawMessage);
        return; // Handled by first words welcomer, avoid double replying on their 1st message
      }
    }

    // 4. INTELLIGENT AI RESPONSE CHECK
    const botNameLower = (this.settings.botName || 'cutie bot').toLowerCase();
    const isMention = 
      lowerMessage.includes(`@${botNameLower}`) ||
      lowerMessage.includes(botNameLower) ||
      lowerMessage.startsWith('!ai ') ||
      lowerMessage.includes('cutiebot') ||
      lowerMessage.includes('cutie bot');

    // Detect if the message is a question (containing ? or starting with/containing question words)
    const isQuestion = 
      /[?]/i.test(rawMessage) || 
      /\b(why|how|hows|what|whats|wats|where|wheres|when|whens|who|whos|which|can you|can u|is that|tell me|explain|specs|hardware|keyboard|mouse|sens|rank|challenge|hands|ceo|president)\b/i.test(rawMessage);

    let shouldRespond = false;
    if (this.settings.autoRespond === 'all_chat') {
      shouldRespond = true;
    } else if (this.settings.autoRespond === 'questions_and_mentions') {
      shouldRespond = isMention || isQuestion;
    } else if (this.settings.autoRespond === 'mention_only') {
      shouldRespond = isMention;
    }

    if (shouldRespond) {
      const cooldownMs = (this.settings.cooldownSeconds || 8) * 1000;
      if (now - this.lastAiResponseTime < cooldownMs) {
        return; // In cooldown
      }

      // Clean prompt: remove !ai or bot tag
      let cleanPrompt = rawMessage
        .replace(/^!ai\s+/i, '')
        .replace(new RegExp(`@?${this.settings.botName}`, 'gi'), '')
        .replace(/@?cutie\s*bot(\.0)?/gi, '')
        .trim();

      if (!cleanPrompt) cleanPrompt = rawMessage;

      this.lastAiResponseTime = now;
      websocketServer.logSystem(`[Ollama Bot] Generating AI response for ${username} (${platform}): "${cleanPrompt}"`);

      const aiResult = await this.generateAiResponse(cleanPrompt, username, platform, 'chat');
      if (aiResult.success && aiResult.text) {
        const cleanUser = username.replace(/^@+/, '').trim();
        const replyText = `@${cleanUser} ${aiResult.text}`;
        this.sendBotReply(platform, replyText);
      } else if (aiResult.skipped) {
        websocketServer.logSystem(`[Ollama Bot] Skipping reply for "${cleanPrompt}" (no verified answer found in stream description or internet).`);
      } else {
        websocketServer.logSystem(`[Ollama Bot] AI Generation failed: ${aiResult.error}`, 'warning');
      }
    }
  }

  async processFirstWords(username, platform, rawMessage) {
    const cleanUser = username.replace(/^@+/, '').trim();
    if (this.settings.firstWordsMode === 'ai') {
      websocketServer.logSystem(`[Ollama Bot] Generating First Words AI greeting for ${cleanUser}: "${rawMessage}"`);
      const aiResult = await this.generateAiResponse(rawMessage, cleanUser, platform, 'first_words');
      if (aiResult.success && aiResult.text) {
        const replyText = `@${cleanUser} ${aiResult.text}`;
        this.sendBotReply(platform, replyText);
        return;
      }
    }

    // Default template fallback
    const template = this.settings.firstWordsTemplate || 'Welcome to the stream {username}! Thanks for chatting!';
    const welcomeMsg = template.replace('{username}', cleanUser);
    this.sendBotReply(platform, welcomeMsg);
  }

  async sendBotReply(platform, messageText) {
    // Sanitize any accidental double-at mentions
    messageText = messageText.replace(/^@+/, '@').trim();
    websocketServer.logSystem(`[Ollama Bot -> ${platform.toUpperCase()}] "${messageText}"`);

    // Track recently sent bot message to prevent loopbacks
    const cleanLower = messageText.toLowerCase().trim();
    this.recentBotReplies.set(cleanLower, Date.now());
    // Also track raw text without @username prefix to prevent sub-string matching loop
    const withoutPrefix = cleanLower.replace(/^@[^\s]+\s+/, '').trim();
    if (withoutPrefix) {
      this.recentBotReplies.set(withoutPrefix, Date.now());
    }

    // Record in rolling chat history
    this.recentChatHistory.push({
      username: this.settings.botName || 'Cutie Bot',
      message: messageText,
      timestamp: Date.now()
    });
    if (this.recentChatHistory.length > 10) {
      this.recentChatHistory.shift();
    }

    // Note: We do NOT broadcast a synthetic chat message to websocketServer here
    // because chatCapture (for YouTube) and kickCapture (for Kick) will capture the
    // actual posted message from live chat and broadcast it to the OBS dock.
    // Broadcasting here caused messages to appear twice in the dock.

    if (platform === 'youtube' && this.settings.enableYouTube) {
      const chatCapture = require('./chatCapture');
      const activeVideoId = chatCapture.currentVideoId || (streamManager && streamManager.activeStream ? streamManager.activeStream.videoId : null) || configStore.get('lastVideoId');
      
      if (this.settings.youtubeAccountMode === 'bot_account') {
        const ok = await botAccountSession.sendYouTubeBotMessage(activeVideoId, messageText);
        if (!ok) {
          websocketServer.logSystem('[YouTube Bot] Dedicated Bot account failed to post. Trying broadcaster fallback...', 'warning');
          await chatCapture.sendMessage(messageText);
        }
      } else {
        await chatCapture.sendMessage(messageText);
      }
    } else if (platform === 'kick' && this.settings.enableKick) {
      const kickCapture = require('./kickCapture');
      const kickUser = kickCapture.username || configStore.get('kickUsername') || 'steushio';
      const chatroomId = kickCapture.chatroomId || configStore.get('kickChatroomId') || (kickUser.toLowerCase() === 'steushio' ? '33438685' : null);
      if (this.settings.kickAccountMode === 'bot_account') {
        const ok = await botAccountSession.sendKickBotMessage(chatroomId, kickUser, messageText);
        if (!ok) {
          websocketServer.logSystem('[Kick Bot] Dedicated Bot account failed to post. Trying broadcaster fallback...', 'warning');
          await botAccountSession.sendKickBroadcasterMessage(chatroomId, kickUser, messageText);
        }
      } else {
        await botAccountSession.sendKickBroadcasterMessage(chatroomId, kickUser, messageText);
      }
    }
  }

  // Timer State Management (chat-activity gated)
  resetTimerState() {
    this.timerState = {
      kick: {
        totalUserMessages: 0,
        messagesSinceLastTimer: 0,
        neededMessages: 1, // Start with 1 so first timer pops after 1st user message
        lastPostTime: 0,
        firstTimerScheduled: false
      },
      youtube: {
        totalUserMessages: 0,
        messagesSinceLastTimer: 0,
        neededMessages: 1,
        lastPostTime: 0,
        firstTimerScheduled: false
      }
    };
  }

  getTimerState(platform) {
    if (!this.timerState) this.resetTimerState();
    if (!this.timerState[platform]) {
      this.timerState[platform] = {
        totalUserMessages: 0,
        messagesSinceLastTimer: 0,
        neededMessages: 1,
        lastPostTime: 0,
        firstTimerScheduled: false
      };
    }
    return this.timerState[platform];
  }

  // Record incoming user message and check if timer should pop
  recordUserMessageForTimers(platform) {
    const state = this.getTimerState(platform);
    state.totalUserMessages++;
    state.messagesSinceLastTimer++;

    // "like 1st message after 1st message of a user then random. always make sure it posts after welcome message for first user like first words."
    if (state.totalUserMessages === 1 && !state.firstTimerScheduled) {
      state.firstTimerScheduled = true;
      websocketServer.logSystem(`[Ollama Bot Timer] 1st user message detected on ${platform}. Scheduling first timer message to post after welcome greeting.`);
      setTimeout(() => {
        this.postNextTimer(platform, 'after 1st user welcome message');
      }, 5500); // 5.5s delay to ensure first words welcome is sent and rendered first
    } else if (state.totalUserMessages > 1) {
      this.checkTimerTrigger(platform);
    }
  }

  // Check if both time interval and user-message threshold (random 1-5) are satisfied
  checkTimerTrigger(platform) {
    if (!this.settings.enabled) return;
    if (platform === 'youtube' && !this.settings.enableYouTube) return;
    if (platform === 'kick' && !this.settings.enableKick) return;

    const enabledTimers = (this.settings.timers || []).filter(t => t.enabled && t.message && (parseFloat(t.intervalMinutes) || 0) > 0);
    if (!enabledTimers.length) return;

    const state = this.getTimerState(platform);

    // 1. Only pop if it saw active user messages on chat (>= neededMessages)
    if (state.messagesSinceLastTimer < state.neededMessages) {
      return;
    }

    // 2. Minimum interval elapsed check
    const minIntervalMinutes = Math.min(...enabledTimers.map(t => parseFloat(t.intervalMinutes) || 5));
    const minIntervalMs = minIntervalMinutes * 60 * 1000;
    const timeSinceLast = Date.now() - (state.lastPostTime || 0);

    if (state.lastPostTime > 0 && timeSinceLast < minIntervalMs) {
      return;
    }

    this.postNextTimer(platform, 'interval & chat activity threshold met');
  }

  // Post one timer message (randomly chosen from enabled timers, not all at once)
  postNextTimer(platform, reason = '') {
    if (!this.settings.enabled) return;
    if (platform === 'youtube' && !this.settings.enableYouTube) return;
    if (platform === 'kick' && !this.settings.enableKick) return;

    const enabledTimers = (this.settings.timers || []).filter(t => t.enabled && t.message && (parseFloat(t.intervalMinutes) || 0) > 0);
    if (!enabledTimers.length) return;

    const state = this.getTimerState(platform);

    // Safety: never post to a completely dead/empty chat
    if (state.totalUserMessages === 0 && state.messagesSinceLastTimer === 0) {
      return;
    }

    // Pick one timer randomly from enabled timers
    const selectedTimer = enabledTimers[Math.floor(Math.random() * enabledTimers.length)];

    websocketServer.logSystem(`[Ollama Bot Timer -> ${platform.toUpperCase()}] Triggered timer (${reason}): "${selectedTimer.message}" (after ${state.messagesSinceLastTimer} user messages)`);
    this.sendBotReply(platform, selectedTimer.message).catch(e => console.error(`[${platform} Timer Error]`, e));

    // Reset counters and randomly choose next threshold between 1 and 5 user messages
    state.lastPostTime = Date.now();
    state.messagesSinceLastTimer = 0;
    state.neededMessages = Math.floor(Math.random() * 5) + 1; // Random integer: 1, 2, 3, 4, or 5
    websocketServer.logSystem(`[Ollama Bot Timer] Next timer on ${platform} requires ${state.neededMessages} user messages.`);
  }

  startTimers() {
    // Clear existing timer intervals
    this.activeTimerIntervals.forEach(intervalId => clearInterval(intervalId));
    this.activeTimerIntervals = [];

    if (!this.settings.enabled || !Array.isArray(this.settings.timers)) return;

    const enabledTimers = this.settings.timers.filter(t => t.enabled && t.message && (parseFloat(t.intervalMinutes) || 0) > 0);
    if (!enabledTimers.length) return;

    // Periodic ticker every 15s to check if interval & user chat requirements have been met
    const intervalId = setInterval(() => {
      if (!this.settings.enabled) return;
      if (this.settings.enableYouTube) {
        this.checkTimerTrigger('youtube');
      }
      if (this.settings.enableKick) {
        this.checkTimerTrigger('kick');
      }
    }, 15000);

    this.activeTimerIntervals.push(intervalId);
    websocketServer.logSystem(`[Ollama Bot] Activity-gated recurring timer monitor active (${enabledTimers.length} timer templates, requiring real user chat to pop).`);
  }

  resetFirstWordsSession() {
    this.seenChatters.clear();
    this.conversationHistory = [];
    this.resetTimerState();
    websocketServer.logSystem('[Ollama Bot] Reset First Words session chatters, timer state, and conversation memory.');
  }
}

module.exports = new OllamaBot();
