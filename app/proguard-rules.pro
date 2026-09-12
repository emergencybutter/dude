# kotlinx.serialization keeps its generated serializers by reflection off the companion.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class nyc.curbside.**$$serializer { *; }
-keepclassmembers class nyc.curbside.** {
    *** Companion;
}

# MapLibre reaches into its own native bindings reflectively.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.**

# Firestore deserialises into maps, but keeps model reflection warm.
-keepclassmembers class nyc.curbside.share.** { *; }
