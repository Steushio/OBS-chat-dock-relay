package com.obs.chat.relay.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obs.chat.relay.session.AccountType
import com.obs.chat.relay.ui.theme.AccentBlue
import com.obs.chat.relay.ui.theme.AccentPurple
import com.obs.chat.relay.ui.theme.BgDark
import com.obs.chat.relay.ui.theme.CardDark
import com.obs.chat.relay.ui.theme.KickGreen
import com.obs.chat.relay.ui.theme.TextMain
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AccountLoginDialog(
    accountType: AccountType,
    isChannelSwitcher: Boolean = false,
    isAccountSwitcher: Boolean = false,
    onDismiss: () -> Unit,
    onLoginSuccess: (username: String, email: String, token: String) -> Unit
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var showManualKickEntry by remember { mutableStateOf(false) }
    var manualUsername by remember { mutableStateOf("") }
    var activeCookieManager by remember { mutableStateOf<CookieManager?>(null) }

    val isKick = accountType == AccountType.KICK_BROADCASTER || accountType == AccountType.KICK_BOT

    val title = when {
        isChannelSwitcher -> "🔄 Switch Channel (${if (accountType == AccountType.YT_BOT) "Bot" else "Streamer"})"
        isAccountSwitcher -> "🔄 Switch Account (${if (accountType == AccountType.YT_BOT || accountType == AccountType.KICK_BOT) "Bot" else "Streamer"})"
        else -> when (accountType) {
            AccountType.YT_BROADCASTER -> "Sign In: YouTube Streamer Account"
            AccountType.YT_BOT -> "Sign In: YouTube Dedicated Bot Account"
            AccountType.KICK_BROADCASTER -> "Sign In: Kick Streamer Account"
            AccountType.KICK_BOT -> "Sign In: Kick Dedicated Bot Account"
        }
    }

    val initialUrl = when {
        isChannelSwitcher -> "https://www.youtube.com/channel_switcher?next=https%3A%2F%2Fstudio.youtube.com%2F"
        isAccountSwitcher -> when (accountType) {
            AccountType.YT_BROADCASTER, AccountType.YT_BOT ->
                "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fstudio.youtube.com%2F"
            AccountType.KICK_BROADCASTER, AccountType.KICK_BOT ->
                "https://kick.com/login"
        }
        else -> when (accountType) {
            AccountType.YT_BROADCASTER, AccountType.YT_BOT ->
                "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fstudio.youtube.com%2F"
            AccountType.KICK_BROADCASTER, AccountType.KICK_BOT ->
                "https://kick.com/login"
        }
    }

    // Genuine Mobile Chrome User Agent matching real Android device environment
    // Crucial for Google reCAPTCHA Enterprise & Cloudflare to pass human verification without 0.0 bot score
    val genuineAndroidUserAgent =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    val kickModalFixJs = """
    (function() {
        function checkAndNotifyKickUser() {
            try {
                let user = '';
                let email = '';

                // 1. Next.js pageProps
                try {
                    if (window.__NEXT_DATA__?.props?.pageProps?.user?.username) {
                        user = window.__NEXT_DATA__.props.pageProps.user.username;
                        email = window.__NEXT_DATA__.props.pageProps.user.email || '';
                    }
                } catch(e) {}

                // 2. Profile navbar button (Kick modern desktop & mobile UI)
                if (!user) {
                    const accountBtn = document.querySelector('[data-testid="navbar-account"], button[aria-label*="User profile"], [data-testid="user-profile-button"], #user-menu-button');
                    if (accountBtn) {
                        const img = accountBtn.querySelector('img');
                        if (img && img.alt && img.alt.length < 50 && !img.alt.toLowerCase().includes('log')) {
                            user = img.alt.trim();
                        } else {
                            const text = accountBtn.textContent.trim();
                            if (text && text.length < 30 && !text.toLowerCase().includes('log')) user = text;
                        }
                    }
                }

                // 3. Rounded avatar in header/nav
                if (!user) {
                    const anyAvatar = document.querySelector('header img[alt][class*="rounded-full"], nav img[alt][class*="rounded-full"], [data-testid="header"] img[alt]');
                    if (anyAvatar && anyAvatar.alt && anyAvatar.alt.length < 50 && !anyAvatar.alt.toLowerCase().includes('logo') && !anyAvatar.alt.toLowerCase().includes('kick')) {
                        user = anyAvatar.alt.trim();
                    }
                }

                // 4. LocalStorage
                if (!user) {
                    for (let i = 0; i < localStorage.length; i++) {
                        const k = localStorage.key(i);
                        if (k && (k.includes('user') || k.includes('auth') || k.includes('session'))) {
                            try {
                                const parsed = JSON.parse(localStorage.getItem(k));
                                if (parsed?.username) user = parsed.username;
                                else if (parsed?.user?.username) user = parsed.user.username;
                                if (parsed?.email) email = parsed.email;
                                else if (parsed?.user?.email) email = parsed.user.email;
                            } catch(e){}
                        }
                    }
                }

                if (user && window.KickAuthBridge) {
                    window.KickAuthBridge.onUserDetected(user, email);
                    return true;
                }
            } catch(e) {}
            return false;
        }

        function triggerKickFetch() {
            if (window.__kickFetchRunning) return;
            window.__kickFetchRunning = true;
            try {
                fetch('/api/v1/user', { credentials: 'include' })
                    .then(r => r.ok ? r.json() : null)
                    .then(d => {
                        if (d && d.username && window.KickAuthBridge) {
                            window.KickAuthBridge.onUserDetected(d.username, d.email || '');
                        }
                    })
                    .catch(() => {})
                    .finally(() => { window.__kickFetchRunning = false; });
            } catch(e) {
                window.__kickFetchRunning = false;
            }
        }

        function applyKickFixes() {
            try {
                // If user is already logged in, notify bridge and do not open login modal
                if (checkAndNotifyKickUser()) return;

                // Also trigger background fetch for user info
                triggerKickFetch();

                const isLoggedIn = !!document.querySelector('[data-testid="navbar-account"]');
                if (isLoggedIn) return;

                if (!document.getElementById('kick-fix-style')) {
                    const style = document.createElement('style');
                    style.id = 'kick-fix-style';
                    style.innerHTML = `
                        [role="dialog"], 
                        div[class*="z-dialog"], 
                        div[class*="max-h-xvh"],
                        div[class*="h-xvh"] {
                            height: auto !important;
                            min-height: 480px !important;
                            max-height: 95vh !important;
                            overflow-y: auto !important;
                            background-color: #191b1f !important;
                            border-radius: 12px !important;
                        }
                        #onetrust-consent-sdk, .onetrust-pc-dark {
                            display: none !important;
                        }
                        input[name="emailOrUsername"], input[name="password"] {
                            font-size: 16px !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }

                // Auto accept or dismiss OneTrust cookie popup if present
                const acceptCookies = document.getElementById('accept-recommended-btn-handler');
                if (acceptCookies) acceptCookies.click();

                // If login inputs are not in DOM yet, click the 'Log In' button
                const emailInput = document.querySelector('input[name="emailOrUsername"]');
                if (!emailInput) {
                    const btns = Array.from(document.querySelectorAll('button, a'));
                    const loginBtn = btns.find(b => (b.textContent || '').trim() === 'Log In' || (b.getAttribute('data-testid') === 'login'));
                    if (loginBtn) loginBtn.click();
                }
            } catch(e) {}
        }

        applyKickFixes();
        if (!window.__kickModalObserver) {
            window.__kickModalObserver = new MutationObserver(() => applyKickFixes());
            window.__kickModalObserver.observe(document.documentElement, { childList: true, subtree: true });
        }
    })();
    """.trimIndent()

    fun handleKickSuccess(user: String, email: String) {
        val cleanUser = user.trim().removePrefix("@")
        if (isVerifying || cleanUser.isBlank() || cleanUser.equals("Kick User", true) ||
            cleanUser.equals("Kick Account", true) || cleanUser.equals("null", true) ||
            cleanUser.equals("undefined", true)
        ) {
            return
        }
        isVerifying = true
        val cm = activeCookieManager ?: CookieManager.getInstance()
        val rawCookies = cm.getCookie("https://kick.com") ?: cm.getCookie("https://kick.com/") ?: ""
        var token = ""
        rawCookies.split(";").forEach { c ->
            val parts = c.trim().split("=")
            if (parts.size >= 2 && (parts[0] == "session_token" || parts[0] == "token" || parts[0] == "auth_token")) {
                token = parts[1]
            }
        }
        Toast.makeText(context, "🎉 Authenticated as Kick user: $cleanUser!", Toast.LENGTH_SHORT).show()
        onLoginSuccess(cleanUser, email.trim(), token.ifEmpty { rawCookies })
    }

    // Verification probe for Kick (Synchronous evaluation + bridge notification)
    fun checkKickAuth(web: WebView, cm: CookieManager?) {
        if (isVerifying) return
        web.evaluateJavascript(
            """
            (function() {
                try {
                    let user = '';
                    let email = '';
                    if (window.__NEXT_DATA__?.props?.pageProps?.user?.username) {
                        user = window.__NEXT_DATA__.props.pageProps.user.username;
                        email = window.__NEXT_DATA__.props.pageProps.user.email || '';
                    }
                    if (!user) {
                        const accountBtn = document.querySelector('[data-testid="navbar-account"], button[aria-label*="User profile"], [data-testid="user-profile-button"], #user-menu-button');
                        if (accountBtn) {
                            const img = accountBtn.querySelector('img');
                            if (img && img.alt && img.alt.length < 50 && !img.alt.toLowerCase().includes('log')) {
                                user = img.alt.trim();
                            } else {
                                const text = accountBtn.textContent.trim();
                                if (text && text.length < 30 && !text.toLowerCase().includes('log')) user = text;
                            }
                        }
                    }
                    if (!user) {
                        const anyAvatar = document.querySelector('header img[alt][class*="rounded-full"], nav img[alt][class*="rounded-full"], [data-testid="header"] img[alt]');
                        if (anyAvatar && anyAvatar.alt && anyAvatar.alt.length < 50 && !anyAvatar.alt.toLowerCase().includes('logo') && !anyAvatar.alt.toLowerCase().includes('kick')) {
                            user = anyAvatar.alt.trim();
                        }
                    }
                    if (!user) {
                        for (let i = 0; i < localStorage.length; i++) {
                            const k = localStorage.key(i);
                            if (k && (k.includes('user') || k.includes('auth') || k.includes('session'))) {
                                try {
                                    const parsed = JSON.parse(localStorage.getItem(k));
                                    if (parsed?.username) user = parsed.username;
                                    else if (parsed?.user?.username) user = parsed.user.username;
                                    if (parsed?.email) email = parsed.email;
                                    else if (parsed?.user?.email) email = parsed.user.email;
                                } catch(e){}
                            }
                        }
                    }
                    if (!user) {
                        try {
                            const xhr = new XMLHttpRequest();
                            xhr.open('GET', '/api/v1/user', false);
                            xhr.withCredentials = true;
                            xhr.send(null);
                            if (xhr.status === 200) {
                                const d = JSON.parse(xhr.responseText);
                                if (d?.username) user = d.username;
                                if (d?.email) email = d.email || '';
                            }
                        } catch(e) {}
                    }
                    if (!user) {
                        try {
                            const xhr = new XMLHttpRequest();
                            xhr.open('GET', '/api/internal/me', false);
                            xhr.withCredentials = true;
                            xhr.send(null);
                            if (xhr.status === 200) {
                                const d = JSON.parse(xhr.responseText);
                                const u = d?.username || d?.data?.username;
                                if (u) user = u;
                                if (d?.email || d?.data?.email) email = d?.email || d?.data?.email || '';
                            }
                        } catch(e) {}
                    }

                    if (user && window.KickAuthBridge) {
                        try { window.KickAuthBridge.onUserDetected(user, email); } catch(e) {}
                    }

                    return (user || '') + '|||' + (email || '');
                } catch(e) {
                    return '|||';
                }
            })()
            """.trimIndent()
        ) { result ->
            try {
                val clean = result?.removeSurrounding("\"")?.trim() ?: ""
                val parts = clean.split("|||")
                val user = parts.getOrNull(0)?.trim() ?: ""
                val email = parts.getOrNull(1)?.trim() ?: ""
                if (user.isNotBlank() && user != "null" && user != "undefined") {
                    handleKickSuccess(user, email)
                }
            } catch (e: Exception) {}
        }
    }

    // Periodic poll for Kick authentication state and auto-open login form
    LaunchedEffect(webViewInstance) {
        if (isKick) {
            // Rapid retries during initial page boot to display login form automatically
            for (i in 1..6) {
                delay(600)
                webViewInstance?.evaluateJavascript(kickModalFixJs, null)
            }
            while (true) {
                delay(2000)
                webViewInstance?.let { web ->
                    web.evaluateJavascript(kickModalFixJs, null)
                    checkKickAuth(web, activeCookieManager)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = CardDark
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgDark)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain)
                        Text(
                            if (isVerifying) "Verifying channel & login..." else currentUrl,
                            fontSize = 10.sp,
                            color = Color.Gray,
                            maxLines = 1
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isKick) {
                            // Quick Manual Username Entry fallback
                            IconButton(onClick = { showManualKickEntry = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Manual Username", tint = KickGreen)
                            }

                            // Manual Force Check/Verify button
                            IconButton(onClick = {
                                webViewInstance?.let { web ->
                                    checkKickAuth(web, activeCookieManager)
                                    web.evaluateJavascript(
                                        """
                                        fetch('/api/v1/user', { credentials: 'include' })
                                            .then(r => r.ok ? r.json() : null)
                                            .then(d => {
                                                if (d?.username && window.KickAuthBridge) {
                                                    window.KickAuthBridge.onUserDetected(d.username, d.email || '');
                                                }
                                            }).catch(() => {});
                                        """.trimIndent(), null
                                    )
                                }
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "Verify Login", tint = KickGreen)
                            }
                        }

                        // Open in External Chrome
                        IconButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl.ifBlank { initialUrl }))
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in Chrome", tint = AccentBlue)
                        }

                        // Clear Session / Cookies / LocalStorage
                        IconButton(onClick = {
                            activeCookieManager?.removeAllCookies(null)
                            activeCookieManager?.flush()
                            webViewInstance?.clearCache(true)
                            webViewInstance?.clearHistory()
                            webViewInstance?.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        localStorage.clear();
                                        sessionStorage.clear();
                                        window.location.href = 'https://kick.com/login';
                                    } catch(e) {
                                        window.location.reload();
                                    }
                                })()
                                """.trimIndent(), null
                            )
                            Toast.makeText(context, "🧹 Session, localStorage & cache cleared!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Cache & Logout", tint = Color.LightGray)
                        }

                        // Refresh
                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.LightGray)
                        }

                        // Close
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMain)
                        }
                    }
                }

                // Help & Quick Action banner
                Surface(
                    color = Color(0xFF1E1E24),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isKick) "💡 Tip: Enter your Kick Email/Username & Password below, or tap 'Open Form'" else "💡 Tip: You can also use 'Sync from PC' in Settings to copy your already logged-in session instantly!",
                            fontSize = 10.sp,
                            color = Color(0xFFA1A1AA),
                            modifier = Modifier.weight(1f)
                        )

                        if (isKick) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    webViewInstance?.evaluateJavascript(kickModalFixJs, null)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Open Form", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // WebView Container
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                    WebView.setWebContentsDebuggingEnabled(true)
                                }

                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.setSupportMultipleWindows(false)
                                settings.javaScriptCanOpenWindowsAutomatically = true

                                // Genuine Chrome User-Agent matching the device's real Linux/Android platform
                                // Prevents Google reCAPTCHA Enterprise & Cloudflare from flagging the session as a bot spoof
                                settings.userAgentString = genuineAndroidUserAgent

                                // Strip X-Requested-With header so Google reCAPTCHA and Cloudflare treat WebView as clean Chrome
                                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                    androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                                }

                                val profileName = when (accountType) {
                                    AccountType.YT_BROADCASTER -> "persist_youtube"
                                    AccountType.YT_BOT -> "persist_youtube_bot"
                                    AccountType.KICK_BROADCASTER -> "persist_kick"
                                    AccountType.KICK_BOT -> "persist_kick_bot"
                                }

                                val cookieManager: CookieManager = if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                                    val profile = androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profileName)
                                    androidx.webkit.WebViewCompat.setProfile(this, profileName)
                                    profile.cookieManager
                                } else {
                                    CookieManager.getInstance()
                                }

                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                activeCookieManager = cookieManager

                                if (isKick) {
                                    addJavascriptInterface(
                                        object {
                                            @android.webkit.JavascriptInterface
                                            fun onUserDetected(username: String?, email: String?) {
                                                val u = username ?: ""
                                                val e = email ?: ""
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    handleKickSuccess(u, e)
                                                }
                                            }
                                        },
                                        "KickAuthBridge"
                                    )
                                }

                                webChromeClient = WebChromeClient()

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        currentUrl = url ?: ""
                                        if (isKick) {
                                            view?.evaluateJavascript(kickModalFixJs, null)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        currentUrl = url ?: ""
                                        val targetUrl = url ?: ""

                                        // 1. YouTube Verification
                                        val isYouTubeLoggedIn = (targetUrl.contains("studio.youtube.com") ||
                                                (targetUrl.contains("youtube.com") && !targetUrl.contains("accounts.google.com") && !targetUrl.contains("signin") && !targetUrl.contains("ServiceLogin")))

                                        if (!isKick && isYouTubeLoggedIn && !targetUrl.contains("channel_switcher")) {
                                            isVerifying = true
                                            view?.evaluateJavascript(
                                                """
                                                (function() {
                                                    try {
                                                        let channelName = '';
                                                        const handleEl = document.querySelector('#channel-handle, [class*="channel-handle"], [class*="channel-handle-text"]');
                                                        if (handleEl && handleEl.textContent.trim()) {
                                                            channelName = handleEl.textContent.trim().replace(/^@/, '');
                                                        }
                                                        if (!channelName) {
                                                            const entity = document.querySelector('#entity-name') || document.querySelector('#channel-title') || document.querySelector('[class*="channel-name"]');
                                                            if (entity && entity.textContent.trim() && !entity.textContent.includes('Studio')) {
                                                                channelName = entity.textContent.trim();
                                                            }
                                                        }
                                                        if (!channelName) {
                                                            const avatar = document.querySelector('img#img[alt]');
                                                            if (avatar && avatar.alt && avatar.alt.length < 50 && !avatar.alt.includes('@') && !avatar.alt.includes('Studio') && !avatar.alt.includes('Google')) {
                                                                channelName = avatar.alt;
                                                            }
                                                        }
                                                        if (!channelName && window.ytcfg && window.ytcfg.get) {
                                                            const c = window.ytcfg.get('INNERTUBE_CONTEXT');
                                                            if (c && c.client && c.client.channelId) {
                                                                channelName = c.client.channelId;
                                                            }
                                                        }
                                                        if (!channelName && document.title && !document.title.includes('Channel switcher') && !document.title.includes('Sign in') && !document.title.includes('Studio')) {
                                                            channelName = document.title.replace(' - YouTube', '').trim();
                                                        }

                                                        let email = '';
                                                        const emailEl = document.querySelector('[data-email], #profileIdentifier, div[class*="account-email"], div[class*="profileIdentifier"]');
                                                        if (emailEl) {
                                                            email = emailEl.getAttribute('data-email') || emailEl.textContent.trim();
                                                        }
                                                        if (!email) {
                                                            const profileBtn = document.querySelector('button[aria-label*="@"], img[alt*="@"]');
                                                            if (profileBtn) {
                                                                const attr = profileBtn.getAttribute('aria-label') || profileBtn.getAttribute('alt') || '';
                                                                const match = attr.match(/([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/);
                                                                if (match) email = match[1];
                                                            }
                                                        }
                                                        if (!email) {
                                                            const textNodes = document.querySelectorAll('div, span, p');
                                                            for (let el of textNodes) {
                                                                const t = el.textContent ? el.textContent.trim() : '';
                                                                if (t.includes('@') && t.includes('.') && !t.includes(' ') && t.length < 50 && t.length > 5) {
                                                                    const match = t.match(/([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/);
                                                                    if (match) {
                                                                        email = match[1];
                                                                        break;
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        return (channelName || 'YouTube Channel') + '|||' + (email || '');
                                                    } catch(e) {
                                                        return 'YouTube Channel|||';
                                                    }
                                                })()
                                                """.trimIndent()
                                            ) { jsonResult ->
                                                var cleanName = "YouTube Channel"
                                                var cleanEmail = ""
                                                try {
                                                    val raw = jsonResult?.removeSurrounding("\"")?.trim() ?: ""
                                                    val parts = raw.split("|||")
                                                    cleanName = parts.getOrNull(0)?.trim()?.ifBlank { "YouTube Channel" } ?: "YouTube Channel"
                                                    cleanEmail = parts.getOrNull(1)?.trim() ?: ""
                                                } catch (e: Exception) {}
                                                val cookies = cookieManager.getCookie(targetUrl) ?: ""
                                                onLoginSuccess(cleanName, cleanEmail, cookies)
                                            }
                                        }

                                        // 2. Kick Verification & Form Auto-Opener
                                        if (isKick) {
                                            view?.evaluateJavascript(kickModalFixJs, null)
                                            checkKickAuth(this@apply, cookieManager)
                                        }
                                    }
                                }

                                loadUrl(initialUrl)
                            }
                        }
                    )
                }
            }
        }
    }

    // Manual Kick Username Entry Fallback Dialog
    if (showManualKickEntry) {
        AlertDialog(
            onDismissRequest = { showManualKickEntry = false },
            title = {
                Text(
                    "✏️ Quick Set Kick Username",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextMain
                )
            },
            text = {
                Column {
                    Text(
                        "If you are already logged in to Kick or Cloudflare is challenging the WebView, enter your Kick channel or bot username directly:",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualUsername,
                        onValueChange = { manualUsername = it },
                        label = { Text("Kick Username (e.g. steushio)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = manualUsername.trim().removePrefix("@")
                        if (clean.isNotBlank()) {
                            showManualKickEntry = false
                            val cm = activeCookieManager ?: CookieManager.getInstance()
                            val rawCookies = cm.getCookie("https://kick.com") ?: cm.getCookie("https://kick.com/") ?: ""
                            var token = ""
                            rawCookies.split(";").forEach { c ->
                                val parts = c.trim().split("=")
                                if (parts.size >= 2 && (parts[0] == "session_token" || parts[0] == "token" || parts[0] == "auth_token")) {
                                    token = parts[1]
                                }
                            }
                            onLoginSuccess(clean, "", token.ifEmpty { rawCookies })
                            Toast.makeText(context, "Saved Kick username: $clean", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Color.Black)
                ) {
                    Text("Save & Connect", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualKickEntry = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = CardDark
        )
    }
}
