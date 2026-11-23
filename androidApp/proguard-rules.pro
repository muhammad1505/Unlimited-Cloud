# R8 rules for the release build.
#
# The only unavoidable reflection in the app is JNA: it looks up native symbols
# from the Java method names of the library interface, so those names have to
# survive shrinking and obfuscation.

# --- JNA and the TDLib JSON bindings ---------------------------------------
-keep class com.unlimitedcloud.tdjson.** { *; }
-keep interface com.unlimitedcloud.tdjson.** { *; }

-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# --- kotlinx.serialization ------------------------------------------------
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.unlimitedcloud.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.unlimitedcloud.**$$serializer { *; }

# --- WorkManager ----------------------------------------------------------
# The periodic request stores the worker class name and instantiates it by
# reflection, so the constructor must survive shrinking.
-keep class com.unlimitedcloud.platform.GallerySyncWorker { *; }

# --- Koin -----------------------------------------------------------------
-keep class org.koin.** { *; }
-dontwarn org.koin.**
