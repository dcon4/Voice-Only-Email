plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

android {
    namespace = "com.example.voicegmail"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.voicegmail"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // ── OAuth client ID + redirect URI ─────────────────────────────────────────
        // The client ID is read from the OAUTH_CLIENT_ID environment variable so it
        // never has to be checked in to source control.
        //
        // For local development: set the env var before building, e.g.
        //   export OAUTH_CLIENT_ID="123456789000-abcdefghijklmnop.apps.googleusercontent.com"
        //   ./gradlew :app:assembleDebug
        //
        // For CI: add a repository secret named OAUTH_CLIENT_ID in
        //   Settings → Secrets and variables → Actions.
        val oauthClientId = System.getenv("OAUTH_CLIENT_ID").let { envValue ->
            if (envValue.isNullOrBlank()) {
                logger.warn(
                    "\n⚠️  OAUTH_CLIENT_ID env var is not set. " +
                    "The app will build but OAuth sign-in will not work. " +
                    "Set OAUTH_CLIENT_ID before building a usable APK.\n"
                )
                "YOUR_CLIENT_ID_PREFIX.apps.googleusercontent.com"
            } else {
                require(envValue.endsWith(".apps.googleusercontent.com")) {
                    "OAUTH_CLIENT_ID must end with '.apps.googleusercontent.com', got: $envValue"
                }
                envValue
            }
        }
        // Derive the reverse-client-ID scheme used as the OAuth redirect URI.
        val oauthPrefix = oauthClientId.removeSuffix(".apps.googleusercontent.com")
        val oauthRedirectScheme = "com.googleusercontent.apps.$oauthPrefix"
        manifestPlaceholders["appAuthRedirectScheme"] = oauthRedirectScheme
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
        buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"$oauthRedirectScheme\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // AppAuth for OAuth PKCE
    implementation("net.openid:appauth:0.11.1")

    // Networking (Gmail REST API)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
