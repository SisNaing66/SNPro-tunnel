# Keep WireGuard SDK Models and GoBackend
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# Keep Gson Models
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# Keep OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn kotlinx.coroutines.**
