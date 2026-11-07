package com.obs.chat.relay.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.obs.chat.relay.util.PreferencesManager

class YouTubeBotChatSender(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val onLog: (String, String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var activeVideoId: String? = null
    private var isLoaded: Boolean = false
    private val gson = Gson()

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun prewarm(videoId: String) {
        if (videoId.isBlank()) return
        val cleanId = YouTubeChatCapture.extractVideoId(videoId)
        if (cleanId.isEmpty()) return

        if (cleanId == activeVideoId && webView != null && isLoaded) {
            return
        }

        activeVideoId = cleanId
        isLoaded = false

        mainHandler.post {
            try {
                if (webView == null) {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = desktopUserAgent
                        settings.loadsImagesAutomatically = false
                        settings.mediaPlaybackRequiresUserGesture = false

                        val mode = prefs.ytPostingMode
                        val profileName = if (mode == "broadcaster") "persist_youtube" else "persist_youtube_bot"

                        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                            ProfileStore.getInstance().getOrCreateProfile(profileName)
                            WebViewCompat.setProfile(this, profileName)
                        }

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoaded = true
                                onLog("[YouTube Bot] Chat typing window ready for video: $cleanId", "info")
                            }
                        }
                    }
                }

                val chatUrl = "https://www.youtube.com/live_chat?is_popout=1&v=$cleanId"
                onLog("[YouTube Bot] Loading live chat window for posting: $cleanId", "info")
                webView?.loadUrl(chatUrl)
            } catch (e: Exception) {
                onLog("[YouTube Bot] Error initializing chat sender: ${e.message}", "error")
            }
        }
    }

    fun postMessage(videoId: String, messageText: String) {
        if (messageText.isBlank()) return
        val vid = YouTubeChatCapture.extractVideoId(videoId.ifEmpty { activeVideoId ?: "" })
        if (vid.isEmpty()) {
            onLog("[YouTube Bot] Cannot post message: No video ID configured.", "warning")
            return
        }

        mainHandler.post {
            if (webView == null || activeVideoId != vid) {
                prewarm(vid)
                mainHandler.postDelayed({
                    executeSendMessage(messageText)
                }, 4000)
            } else {
                executeSendMessage(messageText)
            }
        }
    }

    private fun executeSendMessage(text: String) {
        val wv = webView ?: return
        val escapedText = gson.toJson(text)

        val js = """
            (function(text) {
                const inputEl = document.querySelector(
                    'yt-live-chat-message-input-renderer #input[contenteditable="true"], ' +
                    'div#input[contenteditable="true"], ' +
                    'yt-live-chat-text-input-field-renderer #input[contenteditable="true"], ' +
                    '#input.yt-live-chat-text-input-field-renderer, ' +
                    '[contenteditable="true"]'
                );

                if (!inputEl) {
                    const signIn = document.querySelector('#sign-in-button, a[href*="accounts.google.com"], a[href*="signin"], ytd-button-renderer a[href*="ServiceLogin"]');
                    if (signIn) return JSON.stringify({ ok: false, error: 'NOT_SIGNED_IN' });
                    return JSON.stringify({ ok: false, error: 'INPUT_NOT_FOUND' });
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

                setTimeout(() => {
                    const enterDown = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true });
                    const enterPress = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true });
                    const enterUp = new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true });
                    inputEl.dispatchEvent(enterDown);
                    inputEl.dispatchEvent(enterPress);
                    inputEl.dispatchEvent(enterUp);

                    // Check if Enter cleared the input; if not, click send button as fallback
                    setTimeout(() => {
                        const remaining = (inputEl.textContent || inputEl.innerText || '').trim();
                        if (remaining.length > 0) {
                            const sendBtn = document.querySelector(
                                '#send-button button, ' +
                                'yt-live-chat-message-input-renderer #send-button button, ' +
                                '#send-button yt-button-shape button, ' +
                                'button[aria-label="Send"], ' +
                                'yt-icon-button#send-button button'
                            ) || (document.querySelector('#send-button yt-button-shape')?.shadowRoot?.querySelector('button'));

                            if (sendBtn) {
                                sendBtn.click();
                            }
                        }
                    }, 150);
                }, 300);

                return JSON.stringify({ ok: true });
            })($escapedText);
        """.trimIndent()

        wv.evaluateJavascript(js) { result ->
            try {
                val clean = result?.trim('"', '\\', ' ') ?: ""
                val obj = gson.fromJson(clean, JsonObject::class.java)
                val ok = obj?.get("ok")?.asBoolean ?: false
                if (ok) {
                    onLog("[YouTube Bot] Successfully posted reply to live chat: \"$text\"", "success")
                } else {
                    val err = obj?.get("error")?.asString ?: "Unknown"
                    if (err == "NOT_SIGNED_IN") {
                        onLog("[YouTube Bot] Cannot post reply: Bot account is not signed in to YouTube. Please tap 'Sign In YouTube Bot' in Settings!", "warning")
                    } else {
                        onLog("[YouTube Bot] Chat input not ready ($err). Retrying in background...", "warning")
                    }
                }
            } catch (e: Exception) {
                // Ignore parsing errors from raw JS string returns
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
                webView = null
                activeVideoId = null
                isLoaded = false
            } catch (e: Exception) {}
        }
    }
}
