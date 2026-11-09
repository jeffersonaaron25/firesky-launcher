plugins {
    id("com.android.application") version "9.3.3" apply false
    // Not applied: AGP 9 compiles Kotlin itself. Listed so the Kotlin compiler matches the Compose plugin.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
