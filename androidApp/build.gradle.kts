plugins {
    alias(libs.plugins.androidApplication)
}

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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":composeApp"))
}
