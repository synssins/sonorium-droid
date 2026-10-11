# kotlinx.serialization keeps its generated serializers through its own rules.

# Alpha builds: shrink but keep names, so the crash report the app shows can
# be read without a mapping file. Remove before Play releases.
-dontobfuscate
