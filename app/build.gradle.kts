plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

fun resolveOAuthValue(envName: String, propertyName: String, defaultValue: String): String =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
        ?: defaultValue

val defaultOauthRedirectScheme = "com.googleusercontent.apps.359413552450-ifbmb206qrd37er7l56r0muoa80ck89g"
val defaultOauthClientId = "359413552450-ifbmb206qrd37er7l56r0muoa80ck89g.apps.googleusercontent.com"
val releaseOauthRedirectScheme = resolveOAuthValue(
    envName = "OAUTH_REDIRECT_SCHEME",
    propertyName = "oauthRedirectScheme",
    defaultValue = defaultOauthRedirectScheme
)
val releaseOauthClientId = resolveOAuthValue(
    envName = "OAUTH_CLIENT_ID",
    propertyName = "oauthClientId",
    defaultValue = defaultOauthClientId
)
val debugOauthRedirectScheme = resolveOAuthValue(
    envName = "DEBUG_OAUTH_REDIRECT_SCHEME",
    propertyName = "debugOauthRedirectScheme",
    defaultValue = releaseOauthRedirectScheme
)
val debugOauthClientId = resolveOAuthValue(
    envName = "DEBUG_OAUTH_CLIENT_ID",
    propertyName = "debugOauthClientId",
    defaultValue = releaseOauthClientId
)

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

        // Release values can be overridden with OAUTH_* env vars or Gradle properties.
        // Debug builds can override them separately with DEBUG_OAUTH_* values so local
        // debug APKs can use a different Android OAuth client/SHA-1 than release builds.
        manifestPlaceholders["appAuthRedirectScheme"] = releaseOauthRedirectScheme
        buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"$releaseOauthRedirectScheme\"")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$releaseOauthClientId\"")
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            val storePass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val alias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPass = System.getenv("ANDROID_KEY_PASSWORD")

            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
            }
            if (!storePass.isNullOrBlank()) {
                storePassword = storePass
            }
            if (!alias.isNullOrBlank()) {
                keyAlias = alias
            }
            if (!keyPass.isNullOrBlank()) {
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["appAuthRedirectScheme"] = debugOauthRedirectScheme
            buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"$debugOauthRedirectScheme\"")
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$debugOauthClientId\"")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
            // PdfBox-Android bundles its own copies of these files.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/*.kotlin_module"
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

    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("net.openid:appauth:0.11.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // PDF text extraction for reading PDF attachments aloud
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

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
