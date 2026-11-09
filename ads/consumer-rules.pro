# Adfacten Ads SDK — consumer R8/ProGuard rules (applied to the app automatically).

# The optional video artifact implements this interface and is created by reflection.
-keep interface com.adfacten.ads.spi.VideoPlayer { *; }
-keep interface com.adfacten.ads.spi.VideoPlayer$Listener { *; }

# Advertising id / App Set ID are read by reflection so Play Services stays optional.
# These rules only keep the few methods we call if the app already ships those libraries.
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient {
    public static com.google.android.gms.ads.identifier.AdvertisingIdClient$Info getAdvertisingIdInfo(android.content.Context);
}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info {
    public java.lang.String getId();
    public boolean isLimitAdTrackingEnabled();
}
-keep class com.google.android.gms.appset.AppSet {
    public static com.google.android.gms.appset.AppSetIdClient getClient(android.content.Context);
}
-keep interface com.google.android.gms.appset.AppSetIdClient {
    public com.google.android.gms.tasks.Task getAppSetIdInfo();
}
-keep class com.google.android.gms.appset.AppSetIdInfo {
    public java.lang.String getId();
}
-keep class com.google.android.gms.tasks.Tasks {
    public static java.lang.Object await(com.google.android.gms.tasks.Task, long, java.util.concurrent.TimeUnit);
}
-keep class com.google.android.gms.tasks.Task
