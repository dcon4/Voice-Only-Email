# Voice Gmail — Hands-Free Gmail for Android

A **Gmail-only** Android app built in Kotlin (Jetpack Compose) that lets totally blind users send, receive, and navigate email entirely by voice.

## Features

- 🎙️ **Voice commands** — say "read inbox", "compose", "send to address@example.com", "subject …", "send", "settings", "sign out"
- 🔊 **Text-to-speech** — every screen reads itself aloud on arrival; configurable speech rate
- 🔒 **Secure OAuth 2.0 + PKCE** — uses AppAuth; no password stored; tokens in DataStore
- 📧 **Gmail API** — loads inbox, reads full messages, sends email (MIME via JavaMail)
- ♿ **Accessibility-first** — every interactive element has a `contentDescription`

## Architecture

```
com.example.voicegmail/
├── auth/          AuthConfig, AuthRepository (AppAuth PKCE), TokenStore (DataStore)
├── data/          EmailMessage, GmailRepository (interface), GoogleGmailRepository
├── di/            AppModule (Hilt bindings)
├── ui/
│   ├── theme/     VoiceGmailTheme (Material 3, dynamic color)
│   └── screens/   SignIn, Inbox, MessageDetail, Compose, Settings
├── viewmodel/     AuthViewModel, InboxViewModel, ComposeViewModel, SettingsViewModel
├── voice/         VoiceCommand, VoiceCommandParser, VoiceController, VoiceManager (TTS)
├── MainActivity.kt  (NavHost + OAuth result + speech recognition launcher)
├── Screen.kt       (navigation route constants)
└── VoiceGmailApp.kt (@HiltAndroidApp)
```

## ⚠️ Required Setup Before Running

### 1 — Google Cloud Console

1. Go to <https://console.cloud.google.com/apis/credentials>
2. Enable the **Gmail API** under *APIs & Services → Library*
3. Create an **OAuth 2.0 Client ID** of type **Android**:
   - Package name: `com.example.voicegmail`
   - SHA-1 certificate fingerprint: run `./gradlew signingReport` in Android Studio to get it
4. Copy the generated Client ID (looks like `NUMBERS-HASH.apps.googleusercontent.com`)

### 2 — Set the Client ID in the app

Open `app/src/main/java/com/example/voicegmail/auth/AuthConfig.kt` and replace:

```kotlin
const val CLIENT_ID = "YOUR_ANDROID_OAUTH_CLIENT_ID.apps.googleusercontent.com"
```

with your real Client ID. **Do not commit a real Client ID to a public repository.**

### 3 — Build

```bash
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio** and click ▶ Run.

## CI — GitHub Actions

Every push to `main` or a pull request triggers `.github/workflows/android-apk.yml`, which
builds a debug APK and uploads it as an artifact you can download from the *Actions* tab.

## Attribution

This project is a Kotlin/Compose rebuild inspired by the original
[voice-based-email-for-blind](https://github.com/hacky1997/voice-based-email-for-blind) by hacky1997.
