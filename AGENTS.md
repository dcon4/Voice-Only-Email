# VoiceGmail — project-level agent addendum

This file is project-specific. The **global** rules for how any AI
agent should work with this user live at
`~/.config/opencode/AGENTS.md`. Read that first; the rules below only
add to or override it for this project.

## About this project

Android voice-first Gmail client (Kotlin, Jetpack Compose, Hilt,
AppAuth, Gmail REST API). Built for a totally blind user — fully
hands-free. The user does not see the screen. Every screen and
interaction must work with TalkBack and the TTS voice loop.

## Build System
- Gradle Kotlin DSL (`build.gradle.kts`, `app/build.gradle.kts`)
- JDK 17, Android Gradle Plugin 8.5.2, Kotlin 1.9.24
- `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`

## Build Flavors (audio dimension)
| Flavor | Application ID | BLUETOOTH_AUDIO | Use Case |
|--------|----------------|-----------------|----------|
| `standard` | `com.example.voicegmail` | `false` | Phone speaker + built-in mic |
| `bt` | `com.example.voicegmail.bt` | `true` | Bluetooth headset SCO routing |

## Key Commands
```bash
# Debug builds (both flavors)
./gradlew :app:assembleDebug

# Release builds (requires keystore env vars)
./gradlew :app:assembleRelease

# Debug SHA-1 for Google OAuth console
./gradlew :app:signingReport

# Clean build
./gradlew clean :app:assembleDebug
```

## OAuth Configuration (Critical)
The app reads OAuth values from multiple sources with priority: env var > gradle property > checked-in default.

**Release overrides:**
- `OAUTH_CLIENT_ID` / `oauthClientId`
- `OAUTH_REDIRECT_SCHEME` / `oauthRedirectScheme`

**Debug overrides:**
- `DEBUG_OAUTH_CLIENT_ID` / `debugOauthClientId`
- `DEBUG_OAUTH_REDIRECT_SCHEME` / `debugOauthRedirectScheme`

**Redirect URI format:** `{OAUTH_REDIRECT_SCHEME}:/oauth2redirect`
- Must match Android OAuth client in Google Cloud Console
- Scheme prefix: `com.googleusercontent.apps.{client-id-prefix}`

**SHA-1 Mismatch Gotcha:**
- Debug builds use Android debug keystore (SHA-1 from `signingReport`)
- Release builds use keystore from GitHub secrets
- If Google Console only has release SHA-1, debug APK sign-in fails with misleading "User cancelled flow"
- Solution: Register separate Android OAuth clients for debug and release SHA-1s

## CI (GitHub Actions)
`.github/workflows/android.yml`:
- Builds debug APKs for both flavors on every push
- Builds signed release APKs when keystore secrets present
- Prints release keystore SHA-1/SHA-256 for Google Console registration
- Artifacts: `debug-apk-standard`, `debug-apk-bt`, `release-apks`

## Architecture Highlights
- **DI**: Hilt (`AppModule.kt` provides OkHttp, Retrofit ×3 for Gmail/People/Bible APIs)
- **Auth**: AppAuth PKCE, tokens in DataStore (`AuthRepository.kt`)
- **Scope Versioning**: `AuthConfig.SCOPE_VERSION` (currently 2) forces re-consent when scopes change
- **UI**: Compose, single Activity (`MainActivity.kt`), ViewModels for Inbox/Compose
- **Voice**: Android TTS + SpeechRecognizer, Bluetooth SCO routing in `bt` flavor (`BluetoothAudioRouter.kt`)
- **Debug Logging**: Dual implementation via source sets:
  - `debug/DebugLogger.kt` — logs only in `BuildConfig.DEBUG`
  - `release/DebugLogger.kt` — always logs to file for production OAuth debugging

## Testing
No unit/UI tests currently configured. `testImplementation` and `androidTestImplementation` deps exist but no test sources.

## Important Files to Know
| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | Flavor config, signing, OAuth value resolution, BuildConfig fields |
| `app/src/main/java/.../auth/AuthConfig.kt` | Scopes, endpoints, SCOPE_VERSION |
| `app/src/main/java/.../auth/AuthRepository.kt` | PKCE flow, token storage, re-consent logic |
| `app/src/main/java/.../di/AppModule.kt` | Hilt bindings for Retrofit/OkHttp |
| `app/src/main/java/.../VoiceGmailApp.kt` | Application class, DebugLogger init |
| `app/src/main/AndroidManifest.xml` | Permissions, AppAuth redirect activity |

## Common Pitfalls
1. **OAuth redirect mismatch** — Verify `appAuthRedirectScheme` manifest placeholder matches Google Console
2. **SHA-1 mismatch** — Debug vs release keystores need separate OAuth clients
3. **Scope version bump** — Increment `AuthConfig.SCOPE_VERSION` when adding/removing scopes
4. **Bluetooth flavor** — Install both `standard` and `bt` APKs; they have different application IDs
5. **ProGuard** — Currently disabled (`isMinifyEnabled = false`); rules file is empty

## Required features (per the global file)

- [x] **In-app debug log share.** A bug-report icon in the inbox
      `TopAppBar` opens `InboxViewModel.getShareLogIntent()`, which
      returns a `FileProvider`-backed `ACTION_SEND` chooser. Log file
      is written by `DebugLogger` in
      `app/src/{debug,release}/java/com/example/voicegmail/debug/`.
- [x] **Verbose logging toggle.** Exposed in
      `VoiceSettingsPanel` (gear icon → "Verbose logging"). Persisted
      in `SharedPreferences` key `verbose_logging` under
      `wake_prefs`. Read back in `VoiceGmailApp.onCreate` and applied
      via `DebugLogger.verboseEnabled` before the first log call.

## Required CI (per the global file)

- [x] `.github/workflows/android.yml` runs on push to `main` and on
      PRs against `main`, plus `workflow_dispatch`. It builds
      `:app:assembleDebug` for both `standard` and `bt` flavors and
      uploads them as `debug-apk-standard` and `debug-apk-bt`
      artifacts. Release builds only when `ANDROID_KEYSTORE_BASE64`
      secret is set.

If either of these gets removed or broken in a future change, the
project is no longer usable for the user. Treat the "share log"
button and the artifact upload as critical infrastructure, not
nice-to-haves.

## Project-specific rules (override the global file)

- **GitHub is the only source of truth for this project.** Local
  clones are disposable. Never commit to a local-only state and
  treat it as "done".
- **The non-programmer user cannot build, install, or test APKs on
  their own.** When work needs a real Android environment, stop and
  tell them what to run on their machine, and what to expect. Do not
  fake a build success — say plainly that you couldn't build.
- **Any new dangerous permission** (RECORD_AUDIO, POST_NOTIFICATIONS,
  READ_CONTACTS, READ_MEDIA_*, etc.) needs both a `<uses-permission>`
  declaration in the manifest **and** a runtime request in
  `MainActivity` via `ActivityResultContracts.RequestPermission`.
  Manifest-only is a known footgun (see commit `f124d18` / PR #21).
- **`VoiceWakeService` and any service with
  `FOREGROUND_SERVICE_TYPE_MICROPHONE` MUST guard `onCreate`** with
  a `RECORD_AUDIO` check and `stopSelf()` on denial. On Android 14+
  this throws `SecurityException` and crashes the process.
- **Author identity for AI-generated commits is `dcon4 <dcon4@gmail.com>`**
  unless the user says otherwise. Never leave the opencode default
  `dcon4@users.noreply.github.com` on a merged commit.

## CI Build Monitoring (required agent behavior)

After every push to `main`, the agent MUST:
1. Wait for the triggered GitHub Actions workflow run to finish
   (poll every ~60-120 s via the unauthenticated GitHub REST API).
2. Check jobs/steps progress if the build is slow, and report what
   step is currently running so the user knows it has not stalled.
3. If the build **succeeds**:
   - List all available artifacts (name + size) so the user knows
     which APKs are ready.
   - Report the run URL.
   - Clearly identify `debug-apk-standard` as the primary APK to
     install for normal testing.
4. If the build **fails**: fetch the build log (using the GitHub
   REST check-run annotations API, or scrape the job log if
   unauthenticated access allows), identify the root cause, fix it,
   and push a new commit.  Repeat until green.
5. After a green build, give a short summary of what changed and
   what the user should test / listen for.  Do NOT paste a full
   commit diff into the chat — one or two sentences is enough.

The user is blind and cannot read build logs themselves — build
failures are a hard block that makes new features unreachable.  Treat
a red build as an urgent bug regardless of the context in which it
was introduced.

## Testing Workflow (required agent behavior)

After every green build the agent MUST explain what was done and what to test,
using this pattern:

1. **Install the debug APK first.** The primary artifact is always
   `debug-apk-standard`. Tell the user to install it directly from the
   GitHub Actions run page (provide the URL).
2. **Explain the changes in one short paragraph** — what file(s) were
   touched and what the user should expect to hear differently.
3. **Give explicit test steps** — what to tap or say, and what the
   correct outcome sounds like.  Be concrete: "Open chapter X, listen
   for Y, expect Z."
4. **If there are multiple artifacts (debug, release),** tell the user
   to test the debug APK first.  When the user reports back that the
   debug build is correct, the agent should investigate any difference
   with the release build (code paths that differ when a Bible voice
   or engine is configured, ProGuard side-effects, signing-keystore
   quirks, source-set differences, etc.).
5. **After the user confirms the fix works**, check whether any stale
   or redundant UI (e.g., offline-download checkboxes for bundled
   data) should be cleaned up.  Ask before deleting code the user
   didn't explicitly request to remove.

### Typical test flow the user follows
- Installs `debug-apk-standard` from CI artifacts.
- Opens the app, says "Bible", picks a book and chapter.
- Listens for clean single-pass reading (no repeated sentences).
- Tests pause/resume (power button, then "continue").
- Switches between translations to confirm bundled data loads.
- If there are issues, reports which APK (debug or release) was used
  and which TTS engine/voice was active (IVONA, Supertonic, etc.).
