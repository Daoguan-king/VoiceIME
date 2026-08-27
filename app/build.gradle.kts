import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // AGP 9 内置 Kotlin：不要应用 org.jetbrains.kotlin.android
    id("com.android.application")
}

android {
    namespace = "com.voiceime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voiceime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 只打包 arm64-v8a 以减小体积；老 32 位设备请改为 armeabi-v7a 或两个都加
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 与 trime 的 AndroidBaseConventionPlugin 相同：通过 KotlinCompile 任务设置 jvmTarget
tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // sherpa-onnx Kotlin API + JNI + onnxruntime（四 ABI，见 app/libs）
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
