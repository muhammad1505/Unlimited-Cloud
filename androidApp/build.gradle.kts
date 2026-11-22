import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

// Signing material never lives in the repository: CI decodes the keystore from
// RELEASE_KEYSTORE_BASE64 into androidApp/release.keystore and passes the rest as
// environment variables, while local builds read the ignored local.properties.
fun localProperty(key: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply {
        file.inputStream().use { load(it) }
    }.getProperty(key)
}

fun setting(key: String): String? =
    providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() } ?: localProperty(key)

val releaseStorePath = setting("RELEASE_STORE_FILE")
val releaseStorePassword = setting("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = setting("RELEASE_KEY_ALIAS")
val releaseKeyPassword = setting("RELEASE_KEY_PASSWORD")

val releaseStore = releaseStorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
val hasReleaseSigning = releaseStore != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

// Thin packaging shell: no Kotlin sources of its own, all shared code (including
// MainActivity) comes from :composeApp. This keeps the AGP 9 rule that
// com.android.application cannot be applied to a Kotlin Multiplatform module.
android {
    namespace = "com.unlimitedcloud"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.unlimitedcloud"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // JNA resolves the TDLib JSON entry points from Java method names, which
            // is why proguard-rules.pro keeps those names explicitly.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // One APK per CPU architecture instead of a universal APK: libtdjson.so alone
    // is 31 MB (arm64) and 20 MB (v7a), so bundling both wasted space per device.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressed natives shrink the download by roughly 60%; the system extracts
        // them at install time. Uncompressed would keep ABI APKs around 43 MB.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":composeApp"))
}
