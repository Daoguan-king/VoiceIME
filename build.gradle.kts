plugins {
    // 与 Xime 相同的已验证组合：AGP 9.1 + KGP 2.4.10（AGP 9.3 强制内置 Kotlin，与外部 KGP 冲突）
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
