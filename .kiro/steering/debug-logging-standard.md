# Debug Logging Standard

Every project should include a minimal, easily shareable debug log system with a toggle between normal and verbose logging. This pattern has been proven effective for diagnosing issues on devices where the developer cannot connect via USB or use logcat directly.

## Architecture

### 1. DebugLogger Singleton

A single `DebugLogger` object that:
- Writes timestamped log lines to a **plain text file** in the app's private storage
- Has two logging levels: `log()` (always writes) and `verbose()` (only writes when enabled)
- Has a `verboseEnabled` volatile boolean flag read at runtime
- Provides `getLogFile()` for sharing and `clearLog()` for resetting
- Is initialized once at app startup with context (to resolve the file path)

```kotlin
object DebugLogger {
    @Volatile var verboseEnabled: Boolean = false

    fun init(context: Context) { /* create/open log file */ }
    fun log(tag: String, message: String) { /* always writes */ }
    fun verbose(tag: String, message: String) { /* writes only if verboseEnabled */ }
    fun logException(tag: String, message: String, throwable: Throwable) { /* always writes */ }
    fun getLogFile(): File?
    fun clearLog()
}
```

### 2. What to log at each level

**Normal (always on):**
- App startup (version, build flavor, config state)
- Authentication events (sign-in start, success, failure — never log tokens)
- Command dispatch (what command was recognized and handled)
- Errors and exceptions
- State transitions (screen wake, service start/stop, mode changes)
- Feature flow entry/exit (e.g., "Browser flow started", "Bible flow started")

**Verbose (user-toggled):**
- Every TTS utterance text (truncated to ~80 chars)
- Every microphone open/close with retry counts
- Every recognition result (all candidates)
- Bluetooth SCO state transitions
- Network request start/complete
- Chunk-by-chunk reading progression
- Timer/timeout events

### 3. Persistence via SharedPreferences

Store the verbose toggle in SharedPreferences so it persists across app restarts:

```kotlin
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun isVerboseLogging(): Boolean = prefs.getBoolean("verbose_logging", false)
    fun setVerboseLogging(enabled: Boolean) {
        prefs.edit().putBoolean("verbose_logging", enabled).apply()
    }
}
```

Load it at app startup:
```kotlin
DebugLogger.verboseEnabled = appPreferences.isVerboseLogging()
```

### 4. Shareable via FileProvider

Include a FileProvider in the debug manifest to allow sharing the log file via the Android share sheet:

```xml
<!-- debug/AndroidManifest.xml -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.debug.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/debug_file_paths" />
</provider>
```

```xml
<!-- debug/res/xml/debug_file_paths.xml -->
<paths>
    <files-path name="debug_logs" path="." />
</paths>
```

Share intent:
```kotlin
fun getShareLogIntent(): Intent? {
    val file = DebugLogger.getLogFile()?.takeIf { it.exists() } ?: return null
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.debug.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
```

### 5. UI Toggle

Place the verbose logging toggle **at the top** of any settings screen so it's easily accessible:

```kotlin
Switch(
    checked = verboseLogging,
    onCheckedChange = { enabled ->
        preferences.setVerboseLogging(enabled)
        DebugLogger.verboseEnabled = enabled
        DebugLogger.log("Settings", "Verbose logging = $enabled")
    }
)
```

### 6. Build Variant Parity

Both debug and release variants must declare the same `DebugLogger` API surface (`log`, `verbose`, `verboseEnabled`, `logException`, `getLogFile`, `clearLog`). The release variant can choose to:
- Write to file (for field diagnostics) — current approach
- No-op everything (for production builds where no diagnostics are needed)

### 7. Log Line Format

```
yyyy-MM-dd HH:mm:ss.SSS [Tag] message
yyyy-MM-dd HH:mm:ss.SSS [Tag] [VERBOSE] message
```

### 8. Performance Considerations

- Normal logging (~20-50 lines per session): zero noticeable impact
- Verbose logging (~200-500 lines per session): ~1ms per write on modern flash storage
- Each `appendLine()` opens/writes/closes the file — simple but safe against corruption
- The toggle allows verbose mode to be OFF by default, only enabled when diagnosing an issue

### 9. Share Button Placement

Include a share button (icon) in the app's main UI that's always visible. For voice-first apps, also support a voice command like "share log" or place it in settings. The user should never need USB, ADB, or a computer to share the log.

## Why This Pattern Works

1. **No USB required** — the user shares the log via email, messaging, or any share target
2. **Timestamped** — correlates events with user-reported behavior ("it failed at 10:57")
3. **Two levels** — normal mode has no performance impact; verbose mode captures everything needed for diagnosis
4. **Persistent toggle** — survives app restart; user enables it once and the next session captures full detail
5. **Plain text** — readable by anyone, no special tools needed
6. **Proven** — this exact pattern diagnosed a race condition (stopAll vs cancelListening), a TTS engine visibility issue (Android 11+ package visibility), and Bluetooth SCO audio routing problems in this project
