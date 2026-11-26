import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val sherpaLibsDir = file("libs")
val sherpaAarFile = file("libs/sherpa-onnx.aar")

val ensureSherpaOnnxAar by tasks.registering {
    description = "Downloads prebuilt sherpa-onnx Android AAR (CPU-only ONNX ASR) if not already cached in app/libs/"
    outputs.file(sherpaAarFile)
    onlyIf { !sherpaAarFile.exists() || sherpaAarFile.length() < 100_000L }
    doLast {
        sherpaLibsDir.mkdirs()
        val candidateUrls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-1.12.23.aar",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.20/sherpa-onnx-1.12.20.aar",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar"
        )
        var downloaded = false
        for (url in candidateUrls) {
            try {
                logger.lifecycle("Downloading sherpa-onnx AAR from $url ...")
                val conn = URI.create(url).toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "MalayalamAssistant-Gradle")
                }
                conn.getInputStream().use { input ->
                    Files.copy(input, sherpaAarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                if (sherpaAarFile.exists() && sherpaAarFile.length() > 100_000L) {
                    downloaded = true
                    logger.lifecycle("Downloaded sherpa-onnx.aar (${sherpaAarFile.length() / 1024} KB)")
                    break
                }
            } catch (e: Exception) {
                logger.warn("Could not fetch $url: ${e.message}")
            }
        }
        if (!downloaded) {
            logger.warn("sherpa-onnx AAR was not downloaded during build; reflective runtime loader remains safe.")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(ensureSherpaOnnxAar)
}

android {
    namespace = "com.malayalam.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.malayalam.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Keep APK lean and focused on ARM smartphones (4 GB RAM target) + x86_64 emulator
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += listOf(
                "lib/**/libc++_shared.so",
                "lib/**/libonnxruntime.so"
            )
        }
    }
}

dependencies {
    // Local AARs (sherpa-onnx Android AAR for offline IndicConformer Malayalam ASR)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // LiteRT-LM Kotlin SDK for EmbeddingGemma 2 (270M), FunctionGemma (270M), and Gemma 3 1B (CPU XNNPACK)
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Core AndroidX & Lifecycle (including SavedState + Lifecycle for VoiceInteractionSession Compose overlay)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose + Material 3 (Lightweight Dark Gemini Theme)
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room Local Database (Offline To-Do list for FunctionGemma + Pending Dialogue Review queue)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager for Sequential Low-RAM Audio Auto-Listen Pipeline
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // SAF DocumentFile + DataStore Preferences
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines, JSON Serialization & Streaming Model Downloader
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
