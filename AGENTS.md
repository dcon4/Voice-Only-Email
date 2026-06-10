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
