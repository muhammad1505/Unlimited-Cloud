import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// Shared UI + domain code is published as an Android library and a desktop JVM
// application. AGP 9 refuses to combine com.android.application with the Kotlin
// Multiplatform plugin, so the APK shell lives in the :androidApp module.
kotlin {
    jvmToolchain(17)

    android {
        // The public API package stays com.unlimitedcloud; the Android namespace only
        // drives R/BuildConfig and must differ from the :androidApp module namespace.
        namespace = "com.unlimitedcloud.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            implementation(libs.okio)
            implementation(libs.filekit.dialogs.compose)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.sqldelight.android.driver)
        }

        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)

            // TDLib JNI wrapper (desktop targets). Native bundles are added per-OS
            // via the `tdlight-natives` classifiers when the engine is wired up.
            implementation(libs.tdlight.java)
        }
    }
}

sqldelight {
    databases {
        create("UnlimitedCloudDatabase") {
            packageName.set("com.unlimitedcloud.db")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.unlimitedcloud.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UnlimitedCloud"
            packageVersion = "1.0.0"
        }
    }
}
