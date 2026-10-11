# kotlinx.serialization keeps its generated serializers through its own rules.

# Alpha builds: shrink but keep names, so the crash report the app shows can
# be read without a mapping file. Remove before Play releases.
-dontobfuscate

# Google's code scanner (ML Kit + Play services) fails in R8 full mode with a
# NullPointerException inside the library ("getClass() on a null object").
# Keep its packages whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.signin.** { *; }
-keep class com.google.android.gms.auth.api.** { *; }
-keep class com.google.android.gms.internal.base.** { *; }
