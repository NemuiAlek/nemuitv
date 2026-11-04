# NemuiTV R8 rules. Libraries (OkHttp, kotlinx.serialization, Media3, Compose) ship their own consumer rules.

# libwebrtc calls Java through JNI by name; these must survive shrinking.
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
