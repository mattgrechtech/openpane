import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** The only version number to change; versionCode follows it (1.2.3 -> 10203). */
val appVersion = "0.1.0"

/**
 * Release signing, from keystore.properties (git-ignored) or OPENPANE_* environment variables
 * (used by the GitHub release workflow). Without either, release builds are left unsigned.
 */
val signing: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return@run props.stringPropertyNames().associateWith { props.getProperty(it) }
    }
    val env = System.getenv()
    val store = env["OPENPANE_KEYSTORE"] ?: return@run null
    mapOf(
        "storeFile" to store,
        "storePassword" to env["OPENPANE_KEYSTORE_PASSWORD"].orEmpty(),
        "keyAlias" to env["OPENPANE_KEY_ALIAS"].orEmpty(),
        "keyPassword" to env["OPENPANE_KEY_PASSWORD"].orEmpty(),
    )
}

android {
    namespace = "io.github.mattgrechtech.openpane"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // Permanent: changing it makes Android treat the app as new (users would have to reinstall).
        applicationId = "io.github.mattgrechtech.openpane"
        minSdk = 24
        targetSdk = 35
        versionName = appVersion
        versionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing["storePassword"]
                keyAlias = signing["keyAlias"]
                keyPassword = signing["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // The standard build has no wake word; the voice build adds it (models and TensorFlow Lite's
    // native library, about 6 MB more). ARM only: that's every TV, tablet and panel it targets. Same app id, so one replaces the other and keeps its settings.
    flavorDimensions += "build"
    productFlavors {
        create("standard") {
            dimension = "build"
            buildConfigField("String", "APK_PREFIX", "\"openpane-v\"")
        }
        create("voice") {
            dimension = "build"
            buildConfigField("String", "APK_PREFIX", "\"openpane-voice-v\"")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    compileOptions {
        // java.time and java.util.Base64 on Android 7 (API 24-25).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.google.zxing:core:3.5.3")
    "voiceImplementation"("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; this is the real implementation.
    testImplementation("org.json:json:20240303")
}
