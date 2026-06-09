# VoiceGmail — Agent Instructions

## About the User
The user is **not a programmer**. They are blind and rely on this app to read and send Gmail hands-free. Assume no coding knowledge.

**How to help:**
- Explain in plain language — avoid jargon, or define it immediately when used
- Give step-by-step instructions for anything they have to do on their computer, phone, or in a web browser
- When showing a command, explain what it does in everyday terms
- For build/install issues, guide them to what to download, click, or paste — not just the error
- Confirm before running any command that changes files, especially git commits or pushes
- When something requires a developer account, API key, or console setup, link to the exact page and tell them what to click
- Prefer short, concrete answers over technical depth unless they ask
- Never assume they know what terms like "keystore", "SHA-1", "PKCE", "scope", or "APK" mean

## Project Overview
Android voice-first Gmail client (Kotlin, Jetpack Compose, Hilt, AppAuth, Gmail REST API). Built for totally blind users — fully hands-free.

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