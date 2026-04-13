// build.gradle.kts
plugins {
    id("com.android.application") version "8.0.0" apply false
    id("kotlin-android") version "1.6.0" apply false
}

android {
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.voiceonlyemail"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.6.0")
    implementation("com.google.android.gms:play-services-auth:20.0.1")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
}
