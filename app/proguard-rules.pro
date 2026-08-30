# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- R2sync (com.dissonance.r2sync) ---

# WorkManager instantiates workers reflectively from their class names; keep
# the project's workers so R2SyncWorker / FolderSyncWorker survive R8.
-keep class com.dissonance.r2sync.work.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The ContentProviders (com.dissonance.r2sync.provider.R2DocumentsProvider,
# R2HubContentProvider) and the launcher activity referenced from
# AndroidManifest.xml are kept automatically by AAPT's generated rules, so
# they need no explicit entries here.
