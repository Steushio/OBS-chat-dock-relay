package com.obs.chat.relay.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.regex.Pattern

class YouTubeStudioMonitor(
    private val context: Context,
    private val onStreamData: (videoId: String, title: String, liveState: String, viewers: Int) -> Unit,
    private val onNewSubscriber: (username: String, avatar: String) -> Unit,
    private val onLog: (String, String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    var isMonitoring: Boolean = false
        private set

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val injectScript = """
        (function() {
            if (window.__obsStudioScannerActive) return;
            window.__obsStudioScannerActive = true;
            console.log('[OBS Studio Monitor] Initializing YouTube Studio interceptors...');

            function notifyStream(vid, title, liveState, viewers) {
                if (!vid || vid.length < 8) return;
                try {
                    if (window.StudioBridge) {
                        window.StudioBridge.postStreamData(vid, title || '', liveState || 'live', viewers || 0);
                    }
                } catch(e) {}
            }

            function handleApi(url, text) {
                if (!url || !text) return;
                try {
                    // 1. live_events/get_live_control_data
                    if (url.includes('live_events/get_live_control_data')) {
                        const data = JSON.parse(text);
                        const videoId = data?.videoContext?.videoData?.videoId;
                        const title = data?.videoContext?.videoData?.title;
                        if (videoId) notifyStream(videoId, title, 'live', 0);
                    }
                    // 2. metadata/get_video_metadata
                    if (url.includes('metadata/get_video_metadata')) {
                        const data = JSON.parse(text);
                        const videoId = data?.videoData?.videoId;
                        const title = data?.videoData?.title;
                        if (videoId) notifyStream(videoId, title, 'live', 0);
                    }
                    // 3. live/get_broadcast_status
                    if (url.includes('live/get_broadcast_status')) {
                        const data = JSON.parse(text);
                        let broadcast = data?.broadcast || data?.statusResponse || data?.result || {};
                        if (Array.isArray(data?.broadcastStatus) && data.broadcastStatus.length > 0) {
                            broadcast = data.broadcastStatus[0];
                        }
                        const videoId = broadcast?.videoId || data?.videoId;
                        const title = broadcast?.title || data?.title;
                        let liveState = 'live';
                        const status = broadcast?.status || broadcast?.liveBroadcastDetails?.status || broadcast?.lifeCycleStatus || data?.status;
                        if (status === 'STATUS_LIVE' || status === 'LIVE' || status === 'LIFE_CYCLE_LIVE') liveState = 'live';
                        else if (status === 'STATUS_UPCOMING' || status === 'UPCOMING' || status === 'LIFE_CYCLE_UPCOMING' || status === 'BROADCAST_STATUS_UPCOMING') liveState = 'upcoming';
                        else if (status === 'STATUS_ARCHIVED' || status === 'STATUS_COMPLETE' || status === 'COMPLETED' || status === 'LIFE_CYCLE_COMPLETED') liveState = 'completed';

                        let viewers = 0;
                        if (broadcast?.viewerStats?.concurrentViewersCount) {
                            viewers = parseInt(broadcast.viewerStats.concurrentViewersCount, 10) || 0;
                        } else if (broadcast?.viewerStats?.concurrentViewersTinyText?.simpleText) {
                            viewers = parseInt(broadcast.viewerStats.concurrentViewersTinyText.simpleText, 10) || 0;
                        }
                        if (videoId) notifyStream(videoId, title, liveState, viewers);
                    }
                } catch(e) {}
            }

            // Monkeypatch window.fetch
            const origFetch = window.fetch;
            window.fetch = async function(...args) {
                const response = await origFetch.apply(this, args);
                try {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
                    if (url.includes('youtubei/v1/')) {
                        const clone = response.clone();
                        clone.text().then(t => handleApi(url, t)).catch(() => {});
                    }
                } catch(e) {}
                return response;
            };

            // Monkeypatch XMLHttpRequest
            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._obsStudioUrl = url;
                return origOpen.apply(this, arguments);
            };
            const origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
                this.addEventListener('load', function() {
                    if (this._obsStudioUrl && this._obsStudioUrl.includes('youtubei/v1/')) {
                        try { handleApi(this._obsStudioUrl, this.responseText); } catch(e) {}
                    }
                });
                return origSend.apply(this, arguments);
            };

            // Scrape DOM and check URL periodically
            setInterval(function() {
                try {
                    // Check URL for /video/VIDEO_ID/livestreaming
                    const urlMatch = window.location.href.match(/\/video\/([^/]+)\/livestreaming/);
                    if (urlMatch && urlMatch[1]) {
                        const titleEl = document.querySelector('.title-text, #title-textarea textarea, [class*="stream-title"]');
                        const title = titleEl ? (titleEl.value || titleEl.innerText || '').trim() : '';
                        notifyStream(urlMatch[1], title || 'Active Studio Stream', 'live', 0);
                        return;
                    }
                    const editMatch = window.location.href.match(/\/video\/([^/]+)\/edit/);
                    if (editMatch && editMatch[1]) {
                        notifyStream(editMatch[1], 'Upcoming Studio Stream', 'upcoming', 0);
                        return;
                    }

                    // Check links on page
                    const vidLink = document.querySelector('[href*="watch?v="]');
                    if (vidLink) {
                        const href = vidLink.getAttribute('href') || '';
                        const match = href.match(/[?&]v=([^&]+)/);
                        if (match && match[1]) {
                            notifyStream(match[1], 'Studio Livestream', 'live', 0);
                        }
                    }
                } catch(e) {}
            }, 6000);

            // Subscriber Poller
            (function startSubscriberPoller() {
                let knownSubs = new Set();
                let baselineReady = false;

                async function pollSubscribers() {
                    try {
                        const apiKey = (window.ytcfg && window.ytcfg.get) ? window.ytcfg.get('INNERTUBE_API_KEY', '') : '';
                        const context = (window.ytcfg && window.ytcfg.get) ? window.ytcfg.get('INNERTUBE_CONTEXT', null) : null;
                        if (!apiKey || !context) return;

                        const res = await origFetch('/youtubei/v1/browse?key=' + apiKey, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ context, browseId: 'FEchannel_subscribers' })
                        });
                        const json = await res.json();
                        const newSubs = [];
                        function findSubs(obj) {
                            if (!obj || typeof obj !== 'object') return;
                            if (obj.channelRenderer) {
                                const cr = obj.channelRenderer;
                                const name = cr.title && cr.title.simpleText ? cr.title.simpleText : null;
                                const avatar = (cr.thumbnail && cr.thumbnail.thumbnails && cr.thumbnail.thumbnails.length)
                                    ? cr.thumbnail.thumbnails[cr.thumbnail.thumbnails.length - 1].url : '';
                                if (name) newSubs.push({ username: name, avatar: avatar });
                            }
                            for (const k in obj) findSubs(obj[k]);
                        }
                        findSubs(json);

                        if (!baselineReady) {
                            newSubs.forEach(s => knownSubs.add(s.username));
                            baselineReady = true;
                        } else {
                            newSubs.forEach(s => {
                                if (!knownSubs.has(s.username)) {
                                    knownSubs.add(s.username);
                                    if (window.StudioBridge) {
                                        window.StudioBridge.postNewSubscriber(s.username, s.avatar || '');
                                    }
                                }
                            });
                        }
                    } catch(e) {}
                }

                setInterval(pollSubscribers, 35000);
                setTimeout(pollSubscribers, 10000);
            })();
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        mainHandler.post {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }

                if (webView == null) {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = desktopUserAgent
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadsImagesAutomatically = false

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            settings.offscreenPreRaster = true
                        }

                        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                        }

                        val cookieManager = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                            val profile = ProfileStore.getInstance().getOrCreateProfile("persist_youtube")
                            WebViewCompat.setProfile(this, "persist_youtube")
                            profile.cookieManager
                        } else {
                            android.webkit.CookieManager.getInstance()
                        }
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Restore saved session cookies from preferences if present
                        val prefs = context.getSharedPreferences("obs_chat_relay_accounts", Context.MODE_PRIVATE)
                        val token = prefs.getString("yt_broadcaster_token", "") ?: ""
                        if (token.isNotBlank()) {
                            token.split(";").forEach { rawCookie ->
                                val c = rawCookie.trim()
                                if (c.isNotEmpty()) {
                                    cookieManager.setCookie("https://studio.youtube.com", c)
                                    cookieManager.setCookie("https://youtube.com", c)
                                }
                            }
                            cookieManager.flush()
                        }

                        addJavascriptInterface(
                            StudioBridge(
                                onStreamData = { vid, title, liveState, viewers ->
                                    mainHandler.post { onStreamData(vid, title, liveState, viewers) }
                                },
                                onNewSubscriber = { user, avatar ->
                                    mainHandler.post { onNewSubscriber(user, avatar) }
                                },
                                onLog = onLog
                            ),
                            "StudioBridge"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: ""
                                handleUrlNavigation(view, url)
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { handleUrlNavigation(view, it) }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let { handleUrlNavigation(view, it) }
                                view?.evaluateJavascript(injectScript, null)
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            const vidMatch = window.location.href.match(/\/video\/([^/]+)\/livestreaming/);
                                            if (vidMatch && vidMatch[1]) {
                                                const titleEl = document.querySelector('.title-text, #title-textarea textarea, [class*="stream-title"]');
                                                const title = titleEl ? (titleEl.value || titleEl.innerText || '').trim() : '';
                                                if (window.StudioBridge) window.StudioBridge.postStreamData(vidMatch[1], title || 'Studio Livestream', 'live', 0);
                                                return;
                                            }
                                            const links = document.querySelectorAll('a[href*="watch?v="]');
                                            for (let a of links) {
                                                const href = a.getAttribute('href') || '';
                                                const m = href.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
                                                if (m && m[1]) {
                                                    if (window.StudioBridge) window.StudioBridge.postStreamData(m[1], 'Studio Livestream', 'live', 0);
                                                    return;
                                                }
                                            }
                                        } catch(e) {}
                                    })()
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                    }
                }

                onLog("[Studio Monitor] Starting background YouTube Studio stream monitor...", "info")
                webView?.resumeTimers()
                webView?.loadUrl("https://studio.youtube.com/livestreaming")
            } catch (e: Exception) {
                onLog("[Studio Monitor] Failed to initialize Studio WebView: ${e.message}", "error")
            }
        }
    }

    private fun handleUrlNavigation(view: WebView?, url: String) {
        onLog("[Studio Monitor] Navigation URL: $url", "info")
        val livePattern = Pattern.compile("/video/([^/]+)/livestreaming")
        val liveMatcher = livePattern.matcher(url)
        if (liveMatcher.find()) {
            val vid = liveMatcher.group(1)
            if (!vid.isNullOrEmpty()) {
                mainHandler.post { onStreamData(vid, "Active Studio Stream", "live", 0) }
                return
            }
        }

        val editPattern = Pattern.compile("/video/([^/]+)/edit")
        val editMatcher = editPattern.matcher(url)
        if (editMatcher.find()) {
            val vid = editMatcher.group(1)
            if (!vid.isNullOrEmpty()) {
                mainHandler.post { onStreamData(vid, "Upcoming Studio Stream", "upcoming", 0) }
                return
            }
        }

        // Channel landing page -> redirect to Live Control Room
        val channelPattern = Pattern.compile("/channel/([^/]+)")
        val channelMatcher = channelPattern.matcher(url)
        if (channelMatcher.find() && !url.contains("/livestreaming") && !url.contains("accounts.google.com")) {
            val channelId = channelMatcher.group(1)
            mainHandler.postDelayed({
                if (isMonitoring && webView != null) {
                    webView?.loadUrl("https://studio.youtube.com/channel/$channelId/livestreaming")
                }
            }, 2500)
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
                webView = null
                onLog("[Studio Monitor] Stopped YouTube Studio monitor", "info")
            } catch (e: Exception) {}
        }
    }

    class StudioBridge(
        private val onStreamData: (String, String, String, Int) -> Unit,
        private val onNewSubscriber: (String, String) -> Unit,
        private val onLog: (String, String) -> Unit
    ) {
        @JavascriptInterface
        fun postStreamData(videoId: String?, title: String?, liveState: String?, viewers: Int) {
            val vid = videoId?.trim() ?: ""
            if (vid.isNotEmpty()) {
                onStreamData(vid, title ?: "", liveState ?: "live", viewers)
            }
        }

        @JavascriptInterface
        fun postNewSubscriber(username: String?, avatar: String?) {
            val u = username?.trim() ?: ""
            if (u.isNotEmpty()) {
                onNewSubscriber(u, avatar ?: "")
            }
        }

        @JavascriptInterface
        fun log(msg: String?) {
            onLog("[Studio Scanner] ${msg ?: ""}", "info")
        }
    }
}
