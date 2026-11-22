pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // TDLight (TDLib JNI wrapper) is published on the MCHV repository.
        maven("https://mvn.mchv.eu/repository/mchv/")
    }
}

rootProject.name = "Unlimited-Cloud"

include(":composeApp")
include(":androidApp")
