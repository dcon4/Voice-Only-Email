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

### 1. Create a Google Cloud project
1. Go to https://console.cloud.google.com
2. Create a new project, or select an existing one
3. Enable the **Gmail API** in **APIs & Services → Library**

### 2. Configure OAuth in Google Cloud Console
1. Go to **APIs & Services → OAuth consent screen**
2. Choose **External** if you are using normal Gmail accounts
3. Fill in:
   - App name
   - Support email
   - Developer contact email
4. Add yourself and any beta testers as **Test users** if the app is not published yet

### 3. Create an Android OAuth client
1. Go to **APIs & Services → Credentials**
2. Click **Create Credentials → OAuth client ID**
3. Choose **Android** as the application type
4. Enter the package name:

   `com.example.voicegmail`

5. Enter the **SHA-1 certificate fingerprint** for the APK you will actually install
6. Click **Create**

Use a separate Android OAuth client for each signing certificate you use:

- **Local Android Studio / `assembleDebug` build**: use the **debug** SHA-1 from `./gradlew :app:signingReport`
- **CI-built debug APK artifact**: use the SHA-1 printed by the workflow step `Verify debug APK certificate (SHA-1 for Google OAuth)`
- **GitHub Actions release APK**: use the SHA-1 printed by the workflow step `Verify release APK certificate`
- **Locally signed release APK**: use the SHA-1 of the local release keystore that signed that APK

Important:

- `app/build.gradle.kts` only customizes **release** signing
- local **debug** builds use the default Android debug keystore unless you override it
- if Google Cloud is configured only for a release SHA-1 and you install a debug APK, sign-in can fail with an AppAuth error like `User cancelled flow`

### 4. Configure the OAuth values used by the app
After creating the Android OAuth client, Google will show a Client ID like this:

`123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com`

The app reads its OAuth values from:

- `app/build.gradle.kts` for the checked-in default release values
- `OAUTH_CLIENT_ID` / `OAUTH_REDIRECT_SCHEME` for release overrides
- `DEBUG_OAUTH_CLIENT_ID` / `DEBUG_OAUTH_REDIRECT_SCHEME` for debug-only overrides
- Gradle properties with the same names in camelCase (`oauthClientId`, `oauthRedirectScheme`, `debugOauthClientId`, `debugOauthRedirectScheme`)

If you use separate Android OAuth clients for debug and release, set both pairs so each build variant uses the matching Client ID and redirect scheme.

### 5. Set the OAuth redirect scheme
In `app/build.gradle.kts`, the redirect URI must match the Android OAuth client:

- package name: `com.example.voicegmail`
- redirect URI: `com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect`

Example:
- Client ID: `123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com`
- Prefix: `123456789000-abcdefghijklmnopqrstuvwxyz012345`
- Redirect scheme: `com.googleusercontent.apps.123456789000-abcdefghijklmnopqrstuvwxyz012345`

The current checked-in values are:

- Client ID: `359413552450-ifbmb206qrd37er7l56r0muoa80ck89g.apps.googleusercontent.com`
- Redirect scheme: `com.googleusercontent.apps.359413552450-ifbmb206qrd37er7l56r0muoa80ck89g`
- Redirect URI: `com.googleusercontent.apps.359413552450-ifbmb206qrd37er7l56r0muoa80ck89g:/oauth2redirect`

### 6. GitHub Actions signing
This repo is set up to build a signed release APK in GitHub Actions using these secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

### 7. Build
GitHub Actions will produce the APK artifact.

If you want to inspect your local debug SHA-1, run:

```bash
./gradlew :app:signingReport
```

If you want to build locally for development, then run:

```bash
./gradlew :app:assembleDebug
```

## Architecture
- **Jetpack Compose** — UI
- **Hilt** — Dependency injection
- **AppAuth** — OAuth 2.0 PKCE for Google sign-in
- **Retrofit + OkHttp** — Gmail REST API calls
- **DataStore Preferences** — Token storage
- **Android TTS + SpeechRecognizer** — Voice I/O

## CI
GitHub Actions builds a debug APK on every push. See `.github/workflows/android.yml`.
