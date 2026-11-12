# R8 rules for the release build (DESIGN.md sections 6 and 11).
#
# Covered by the libraries' own rules, so nothing is needed here (checked in the R8 mapping):
# - kotlinx.serialization (META-INF/com.android.tools/r8/kotlinx-serialization-*.pro): keeps the
#   `Companion` field, `INSTANCE` and `serializer()` of every @Serializable class. The DTOs and the
#   stored settings use explicit `X.serializer()` calls, and their JSON keys are string constants in
#   the generated serializers, so renaming fields and classes changes nothing on the wire or on disk.
# - Navigation 3 back stack: `rememberNavBackStack` saves a NavKey by `getClass().getName()` and
#   restores it with `Class.forName()` plus `serializer(KClass)`. The rules above keep what that
#   lookup reflects on, and the saved name always comes from the same APK.
# - WorkManager (work-runtime's proguard.txt): keeps the name and constructor of `SyncWorker`, which
#   WorkManager stores in its database and creates by reflection.
# - OkHttp/Okio, DataStore, Compose, Lifecycle: their consumer rules.
#
# The debug-only code (debug.DebugReceiver, timetree.FileRawResponseSink) lives in src/debug and
# never reaches the release variant; docs/RELEASING.md checks that in the APK.

# Release logs carry warnings and errors only (DESIGN.md section 6): drop Log.v/d/i calls.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# The app logs only an exception's class name (`e.javaClass.simpleName`), never its message
# (DESIGN.md section 6). Keep those names readable, or every warning would read "failed: a".
-keepnames class * extends java.lang.Throwable

# Stack traces with line numbers. Retrace them with the release's mapping.txt (docs/RELEASING.md).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
