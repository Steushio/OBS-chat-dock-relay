# OBS Chat Dock Relay for Android 📱

A standalone Android companion app for **OBS Chat Dock Relay**. It captures YouTube Live Stream chat messages, Super Chats, Memberships, Gifts, and Kick live chat directly on your mobile device, hosting a local HTTP & WebSocket relay server and providing **online remote network sharing** to transfer chat to your PC overlay over mobile cellular data (no shared Wi-Fi needed).

---

## 🚀 Key Highlights & New Capabilities

### 🌐 1. Remote Network Sharing (Mobile Data -> PC Overlay)
- **No Shared Wi-Fi Needed**: You can stream outdoor IRL or mobile games using **4G/5G mobile cellular data** while your PC at home runs OBS Studio.
- **Remote Transmitter Engine**:
  - The phone acts as a persistent WebSocket & HTTP client transmitting every incoming chat, Super Chat, alert, and poll straight over the internet to your PC overlay IP / Domain (e.g. `123.45.67.89:3001` or your DDNS/tunnel address).
  - Built-in **Connection Test**: Tap **Test Connection** in the app to instantly verify if your phone can reach your PC overlay server.
  - **Cloud Pairing Room**: Sync with a simple 6-digit room code.
  - Desktop server now listens on `0.0.0.0:3001` (WebSocket) and `0.0.0.0:3000` (`POST /api/relay-event`) with full CORS support.

### 🤖 2. Multi-Provider AI Live Stream Chat Bot (Gemini, ChatGPT, Ollama)
- **Google Gemini Integration**: Use Google Gemini (e.g. `gemini-1.5-flash`, `gemini-2.0-flash`) with a free API key from [aistudio.google.com](https://aistudio.google.com) — zero Termux installation needed, instant responses (<1s), 15 free requests/min with no credit card required.
- **OpenAI ChatGPT Integration**: Use OpenAI API keys (`gpt-4o-mini`, `gpt-4o`) or compatible endpoints (Groq, DeepSeek, OpenRouter).
- **Local / On-Device Ollama**: Connects to Termux on-device Ollama (`http://127.0.0.1:11434`) or PC network Ollama.
- **Streamer Knowledge & FAQ Context**: Feed your hardware specs, mouse, sensitivity, challenge, health background, and backstory into the prompt.
- **DuckDuckGo Live Internet Search**: Automatic instant fact-checking and web knowledge without needing any API keys.
- **Trigger Modes**:
  - `Questions & Mentions` (Default)
  - `Mentions Only`
  - `All Chat`
  - `Off`
- **First-Words Welcoming**: Welcomes new viewers on their very first chat message using personalized AI or custom greeting templates (`{username}`).
- **Custom Chat Commands**: Add, edit, or remove custom triggers like `!discord`, `!specs`, `!socials`, `!mouse`.
- **Automated Timers**: Periodic announcements (e.g., every 10 minutes: "Enjoying the stream? Make sure to follow!").
- **Interactive Test Prompt**: Test how your AI bot responds to viewer questions directly inside the app with a live preview.

### 📱 3. Mobile In-App Browser Docks & Floating HUD
- **In-App Mobile Docks**:
  - 💬 **Chat Dock** (`/chatdock.html`)
  - 🔔 **Alert Dock** (`/alertdock.html`)
  - 📋 **Event Log** (`/eventdock.html`)
- **Floating In-Game HUD**: Overlay a semi-transparent, draggable, resizable chat dock over your mobile games or camera apps.

### ⚙️ 4. Complete Relay Settings, Accounts & Backup
- **Settings Backup & Restore (Export / Import .json)**:
  - 1-tap **Export Settings (.json)** using Android's native file share sheet (save to Drive, WhatsApp, Downloads, or Files).
  - 1-tap **Import Settings (.json)** using the native system document picker, automatically restoring all AI prompts, ports, custom commands, and accounts.
  - Quick Copy/Paste raw settings JSON to clipboard.
- **Broadcaster & Dedicated Bot Account Logins**:
  - **YouTube Streamer Account**: Direct channel switcher login via in-app browser dialog.
  - **YouTube Dedicated Bot Account**: Log in with a separate bot account so AI answers are posted under your bot's name in YouTube live chat.
  - **Kick Streamer Account**: Sign in as the broadcaster to monitor & post.
  - **Kick Dedicated Bot Account**: Dedicated bot account login with Kick chatroom posting integration.
- HTTP & WebSocket port configuration.
- Eco / Low Resource Mode (disables image loading in scraper to save RAM and battery).
- Auto-clear old chat limits & timeouts.
- Synthesized audio tones for chat and Super Chat fanfares.
- Persistent Android Foreground Service with wake lock.

---

## 📱 Bottom Navigation Overview

| Tab | Icon | Description |
|---|---|---|
| **Dashboard** | 🚀 | Status banner, local IP, OBS URLs with 1-tap copy, YouTube/Kick toggles, live feed |
| **Remote PC** | 🌐 | Configure Target PC IP/Host, test remote connection, cloud room sync |
| **AI Bot** | 🤖 | Full Ollama AI Bot configuration, model selection, FAQ, commands, test tool |
| **Docks** | 💬 | Built-in full mobile view of Chat Dock, Alert Dock, and Event Log |
| **Settings** | ⚙️ | Ports, sound toggles, eco mode, screen awake toggle, clear chat timers |

---

## 🛠️ How to Connect from Mobile Data (No Same Wi-Fi)

1. **On your PC**:
   - Note your public IP address (or set up a free DDNS like DuckDNS, or use Tailscale/Cloudflare Tunnel/Ngrok).
   - Ensure port `3001` (WebSocket) and/or `3000` (HTTP) are forwarded on your router to your PC.
2. **On your Phone (OBS Chat Relay App)**:
   - Tap the **Remote PC** tab at the bottom.
   - Enter your PC's IP/Host & Port into **Target PC Overlay IP / Host** (e.g., `my-stream-pc.duckdns.org:3001`).
   - Tap **Test PC Overlay Connection**.
   - Turn on **Enable Remote Forwarding**.
3. **Start Streaming**:
   - Even when you are outdoor on mobile 4G/5G, every captured YouTube or Kick chat and Super Chat is transmitted straight to your PC OBS overlay in real-time!
