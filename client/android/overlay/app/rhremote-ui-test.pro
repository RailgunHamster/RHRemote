# Only used with an explicitly isolated Release UI fixture. AndroidJUnitRunner
# calls these APIs from the separate test APK after the target has been shrunk.
-keep class androidx.tracing.** { *; }
# The runner's Kotlin closures are in the test APK, whose shared runtime is
# supplied by this target APK. Their references are invisible to target R8.
-keep class kotlin.** { *; }
