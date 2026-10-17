# Keep crash reports independent of local source filenames. The mapping stays local.
-renamesourcefileattribute SourceFile

# Do not ship verbose/debug application logs in the release APK.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
