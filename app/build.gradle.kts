plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cosmos.voice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cosmos.voice"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // debug: defaults are fine (debuggable, unsigned-with-debug-key).
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
        // BuildConfig.versionName is shown in the UI and sent as `build` in
        // every /voice payload (AGP 8 defaults BuildConfig generation to off).
        buildConfig = true
    }
    composeOptions {
        // Compose compiler 1.5.14 pairs with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // VOSK on-device speech recognition (offline). JNA is required by vosk-android.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")

    // sherpa-onnx on-device text-to-speech (offline, Piper VITS voice) — the
    // speech-OUT twin of Vosk. The AAR bundles the native .so libs for all four
    // ABIs (arm64-v8a, armeabi-v7a, x86, x86_64); no NDK config needed. This is
    // the official k2-fsa release AAR served via JitPack (see settings.gradle.kts);
    // depend on this exact sub-module coordinate ONLY — the aggregator
    // com.github.k2-fsa:sherpa-onnx also drags in sherpa-onnx-jvm, which
    // duplicates every class in the AAR and breaks the dex merge.
    // APK grows ~50 MB from the native libs — accepted.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.6")

    // tar.bz2 extraction for the Piper voice archive (pure Java, no natives).
    implementation("org.apache.commons:commons-compress:1.27.1")
}
