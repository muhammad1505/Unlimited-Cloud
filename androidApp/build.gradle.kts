import java.io.File
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

// Version comes from gradle.properties so the APK, the desktop package and the
// in-app About screen can never drift apart. scripts/bump-version.py updates it.
val appVersionName = providers.gradleProperty("unlimitedcloud.versionName").orNull ?: "1.0.0"
require(appVersionName.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
    "unlimitedcloud.versionName '$appVersionName' must be MAJOR.MINOR.PATCH"
}
val appVersionCode = appVersionName.split('.').map { it.toInt() }.let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

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
        versionCode = appVersionCode
        versionName = appVersionName
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

    // arm64 only: libtdjson.so alone is 31 MB and 32-bit devices are a rounding
    // error on the Play install base, so shipping a second APK doubled the build
    // time and the release size for no real audience.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
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

// Permissions are checked against the built APK by
// scripts/check-apk-permissions.py in CI, so a dependency that starts requesting
// a new permission fails the release build instead of reaching Play.

dependencies {
    implementation(project(":composeApp"))
}
