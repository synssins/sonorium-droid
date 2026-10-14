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

# Called only from Python (Chaquopy, by reflection): R8 can't see these uses, so it removed them
# (alpha30: nothing played on the device). Every class and member Python reaches is kept here; the
# QA build (minified like release) and its smoke test in CI catch a missing one.
# - app/src/main/python/phone.py: jclass("app.sonorium.audio.LiveDecoder").open / .durationSeconds,
#   and the Stream it returns: read(), close(), getHeaderFrames()
-keep class app.sonorium.audio.LiveDecoder { *; }
-keep class app.sonorium.audio.LiveDecoder$Stream { *; }

# SkippablePlayer passes Player.Listener callbacks on by method name (a proxy)
-keep interface androidx.media3.common.Player$Listener { *; }
