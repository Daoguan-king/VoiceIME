import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // AGP 9 内置 Kotlin：不要应用 org.jetbrains.kotlin.android
    id("com.android.application")
}

// ---------------- ABI 构建选择 ----------------
// 用法：./gradlew :app:assembleDebug -Pabi=universal
// 可选值：arm64-v8a（默认）/ armeabi-v7a（v7a）/ x86 / x86_64 / universal（all，四 ABI 全打）
val abiArg = ((project.findProperty("abi") as? String) ?: "arm64-v8a").trim().lowercase()
val buildAbis: List<String> = when (abiArg) {
    "arm64-v8a", "arm64", "aarch64" -> listOf("arm64-v8a")
    "armeabi-v7a", "v7a", "arm32" -> listOf("armeabi-v7a")
    "x86" -> listOf("x86")
    "x86_64", "x64", "amd64" -> listOf("x86_64")
    "universal", "all" -> listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    else -> throw GradleException(
        "未知 ABI: '$abiArg'，可选：arm64-v8a / armeabi-v7a / x86 / x86_64 / universal",
    )
}
println("VoiceIME: 本次构建 ABI = $buildAbis（-Pabi=$abiArg）")

android {
    namespace = "com.voiceime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voiceime"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        // ABI 选择（命令行传入 -Pabi=...，默认 arm64-v8a）：
        //   arm64-v8a | armeabi-v7a(v7a) | x86 | x86_64 | universal(全部)
        ndk {
            abiFilters += buildAbis
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
    // 模型压缩包解压（zip / tar.bz2 / tar.gz）
    implementation("org.apache.commons:commons-compress:1.27.1")
}
