import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.4.1 provides built-in Kotlin support; do NOT apply org.jetbrains.kotlin.android here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-1.12.23.aar",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.20/sherpa-onnx-1.12.20.aar"
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Local AARs (sherpa-onnx Android AAR for offline IndicConformer Malayalam ASR)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Pinned LiteRT-LM Kotlin SDK (0.18.0 — Java 25 bytecode / Kotlin 2.4 metadata supported by AGP 9.4.1 R8)
    implementation(libs.litertlm.android)

    // Core AndroidX & Lifecycle (including SavedState + Lifecycle for VoiceInteractionSession Compose overlay)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose + Material 3 (Lightweight Dark Gemini Theme)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room Local Database (Offline To-Do list for FunctionGemma + Pending Dialogue Review queue)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    ksp(libs.kotlin.metadata.jvm)

    // WorkManager for Sequential Low-RAM Audio Auto-Listen Pipeline
    implementation(libs.androidx.work.runtime.ktx)

    // SAF DocumentFile + DataStore Preferences
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    // Coroutines, JSON Serialization & Streaming Model Downloader
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
