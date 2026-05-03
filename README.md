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

5. Enter the **SHA-1 certificate fingerprint** for the release keystore used by GitHub Actions
6. Click **Create**

### 4. Copy the Client ID into the app
After creating the Android OAuth client, Google will show a Client ID like this:

`123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com`

Put that full value into:

`app/src/main/java/com/example/voicegmail/auth/AuthConfig.kt`

### 5. Set the OAuth redirect scheme
In:

`app/build.gradle.kts`

set the redirect scheme to the reverse of the Client ID prefix.

Example:
- Client ID: `123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com`
- Prefix: `123456789000-abcdefghijklmnopqrstuvwxyz012345`
- Redirect scheme: `com.googleusercontent.apps.123456789000-abcdefghijklmnopqrstuvwxyz012345`

### 6. GitHub Actions signing
The workflow produces a **signed** release APK when the four secrets below are set.
If any secret is missing (e.g., a fork PR build), Gradle skips signing and the APK
remains unsigned — the build still succeeds but the artifact cannot be installed on a
device without re-signing.

#### 6a. Generate a release keystore (one-time setup)
```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias my-release-key \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```
Keep `release.jks` safe — do **not** commit it to the repository.

#### 6b. Convert the keystore to a base64 string
```bash
base64 -w 0 release.jks > release.jks.b64
cat release.jks.b64
```
(On macOS omit `-w 0`; the output is already single-line.)

#### 6c. Add the four GitHub Secrets
Go to **Repository → Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret name                | Value                                         |
|----------------------------|-----------------------------------------------|
| `ANDROID_KEYSTORE_BASE64`  | full contents of `release.jks.b64`            |
| `ANDROID_KEYSTORE_PASSWORD`| password chosen for the keystore              |
| `ANDROID_KEY_ALIAS`        | alias used above (e.g. `my-release-key`)      |
| `ANDROID_KEY_PASSWORD`     | password for the key (may equal keystore pwd) |

#### 6d. Confirm the artifact is signed
After a successful push-triggered workflow run, download the `release-apk` artifact
and verify it with `apksigner`:

```bash
# Install build-tools if needed (change the version to whatever you have)
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs app-release.apk
```

A properly signed APK prints `Verified` and certificate details.
An unsigned APK prints `DOES NOT VERIFY` or raises an error.

### 7. Build
GitHub Actions will produce the APK artifact.

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
GitHub Actions builds a **signed release APK** on every push when the four signing secrets are configured. See `.github/workflows/android.yml` and section 6 above for setup details.
