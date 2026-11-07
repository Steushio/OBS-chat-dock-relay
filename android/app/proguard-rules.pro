# OBS Chat Relay ProGuard rules
-keep class com.obs.chat.relay.model.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
