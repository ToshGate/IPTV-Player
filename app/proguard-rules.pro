# Add project specific ProGuard rules here.

# Keep data/model classes intact: Room's generated code (entities, DAOs) and the app's model
# classes are accessed by field/property name at compile time by generated code, not reflection,
# but keeping them un-obfuscated avoids any edge cases and makes crash reports easier to read.
-keep class com.tosh.iptvplayer.model.** { *; }
-keep class com.tosh.iptvplayer.db.** { *; }

# Room, Media3/ExoPlayer, OkHttp and Coil all ship their own consumer ProGuard rules bundled in
# their AARs, which AGP merges in automatically — no extra rules needed for them here.
