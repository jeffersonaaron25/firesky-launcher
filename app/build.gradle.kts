import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.firesky.launcher"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    // Your settings live in local.properties, which stays out of git. local.properties.example lists them.
    val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
    fun setting(key: String) = local.getProperty(key, "").trim()

    // A release has a version, given as -Pfiresky.version=1.2.3 (the release workflow takes it from the tag).
    // Only releases look for updates. Anything else is a build of your own, version "dev".
    val release = providers.gradleProperty("firesky.version").orNull?.trim()?.removePrefix("v")
        ?.takeIf { Regex("\\d+\\.\\d+\\.\\d+").matches(it) }

    // Releases are signed with FireSky's own key, so each one installs over the last. The release
    // workflow passes it in the environment; on a computer, signing= in local.properties names a file
    // like ~/.firesky/signing.properties. Without either, the debug key signs it.
    val keyFile = setting("signing").takeIf { it.isNotEmpty() }?.let { File(it.replaceFirst("~", System.getProperty("user.home"))) }
    val keys = Properties().apply { keyFile?.takeIf { it.exists() }?.inputStream()?.use(::load) }
    fun key(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keys.getProperty(prop)
    val store = key("FIRESKY_KEYSTORE", "storeFile")?.let(::File)?.takeIf { it.exists() }
    signingConfigs {
        if (store != null) create("release") {
            storeFile = store
            storePassword = key("FIRESKY_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = key("FIRESKY_KEY_ALIAS", "keyAlias")
            keyPassword = key("FIRESKY_KEY_PASSWORD", "keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.firesky.launcher"
        minSdk = 26
        // The TV runs Fire OS 7 (Android 9). Targeting it keeps background and listener behaviour predictable.
        targetSdk = 28
        // A build of your own is numbered just past the last release, so it installs over it.
        val last = runCatching {
            providers.exec { commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*"); isIgnoreExitValue = true }
                .standardOutput.asText.get().trim().removePrefix("v")
        }.getOrNull()?.takeIf { Regex("\\d+\\.\\d+\\.\\d+").matches(it) } ?: "0.0.0"
        val (major, minor, patch) = (release ?: last).split('.').map(String::toInt)
        versionCode = major * 10000 + minor * 100 + patch + if (release == null) 1 else 0
        versionName = release ?: "$major.$minor.${patch + 1}-dev"
        buildConfigField("boolean", "RELEASE", (release != null).toString())

        // Without a TMDB key, the New and Trending cards are off.
        buildConfigField("String", "TMDB_KEY", "\"${setting("tmdb.key")}\"")
        for ((field, key) in listOf(
            "DOCK" to "dock", "HIDE" to "hide", "TEMPERATURE" to "temperature", "WIND" to "wind",
            "CLOCK" to "clock", "REGION" to "region", "LANGUAGE" to "language",
        )) buildConfigField("String", field, "\"${setting(key).replace("\\", "").replace("\"", "")}\"")
        buildConfigField("int", "PS5_PORT", setting("ps5.port").toIntOrNull()?.toString() ?: "0")
    }

    buildTypes {
        release {
            // R8 trims and tightens the code, which Compose in particular is built to rely on.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // QR codes, for setting things up from a phone.
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
