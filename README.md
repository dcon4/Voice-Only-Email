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

### 3. Configure the OAuth Client ID

The Client ID is injected at build time via an environment variable so it never
touches source control.

#### Local development

Export the variable before building:
```bash
export OAUTH_CLIENT_ID="123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com"
./gradlew :app:assembleDebug
```

#### CI (GitHub Actions)

Add a repository secret named `OAUTH_CLIENT_ID`:
1. Go to **Settings → Secrets and variables → Actions → New repository secret**
2. Name: `OAUTH_CLIENT_ID`
3. Value: your full Client ID (e.g. `123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com`)

The workflow in `.github/workflows/android.yml` will automatically pick it up.

> **No manual file edits are needed.** `app/build.gradle.kts` reads the env var,
> derives the OAuth redirect scheme, and exposes both values to the app via
> `BuildConfig`. `AuthConfig.kt` reads them from `BuildConfig` at runtime.

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

