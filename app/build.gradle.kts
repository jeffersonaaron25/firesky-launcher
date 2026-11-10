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

    defaultConfig {
        applicationId = "com.firesky.launcher"
        minSdk = 26
        // The TV runs Fire OS 7 (Android 9). Targeting it keeps background and listener behaviour predictable.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"

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
            signingConfig = signingConfigs.getByName("debug")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
