# Voice Gmail — Android App

A voice-first Gmail client for Android, built with Kotlin, Jetpack Compose, Hilt, AppAuth, and the Gmail REST API.

> **Note:** This app was built to help a totally blind user manage email by voice.

## Features
- Sign in with Google (OAuth 2.0 PKCE via AppAuth)
- Read inbox via Gmail REST API
- TTS reads out email subjects and bodies
- Voice input for composing emails (speech-to-text)
- Send emails via Gmail API

## Setup (required before the app works)

### 1. Create a Google Cloud Project
1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Create a new project (or select an existing one)
3. Enable the **Gmail API** under "APIs & Services → Library"

### 2. Create OAuth 2.0 Credentials

> ⚠️ **Important:** You must create an **Android** type client, not "Web application".
> Google only accepts `com.googleusercontent.apps.<prefix>:/oauth2redirect` as a
> redirect URI for Android apps. A package-name scheme like `com.example.myapp:/…`
> will be rejected.

1. Go to "APIs & Services → Credentials"
2. Click "Create Credentials" → "OAuth client ID"
3. Application type: **Android**
4. Package name: `com.example.voicegmail`
5. Get your app's **SHA-1 fingerprint**:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
6. Paste the SHA-1 into the form and click **Create**
7. Note your **Client ID** — it looks like:
   ```
   123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com
   ```

### 3. Configure the app — two files to edit

#### a) `app/src/main/java/com/example/voicegmail/auth/AuthConfig.kt`

Replace the placeholder with your full Client ID:
```kotlin
const val CLIENT_ID = "123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com"
```

#### b) `app/build.gradle.kts` — set `oauthRedirectScheme` (line ~21)

The redirect scheme is the **reverse** of the prefix (the part before `.apps.googleusercontent.com`):
```kotlin
val oauthRedirectScheme = "com.googleusercontent.apps.123456789000-abcdefghijklmnopqrstuvwxyz012345"
```

> These two values must always match. `AuthConfig.REDIRECT_URI` is computed from the
> scheme automatically — you do not need to change it manually.

### 4. Build and Run
```bash
./gradlew :app:assembleDebug
```
Install on device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and click **Run**.

## Architecture
- **Jetpack Compose** — UI
- **Hilt** — Dependency injection
- **AppAuth** — OAuth 2.0 PKCE for Google sign-in
- **Retrofit + OkHttp** — Gmail REST API calls
- **DataStore Preferences** — Token storage
- **Android TTS + SpeechRecognizer** — Voice I/O

## CI
GitHub Actions builds a debug APK on every push. See `.github/workflows/android.yml`.

