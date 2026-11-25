# File: app/proguard-rules.pro
# --- Folio Production Keep Rules (Backup Agent, Notification Actions & Glance Widgets) ---
-keep class com.folioledger.app.data.backup.** { *; }
-keep class com.folioledger.app.FolioNotificationAction** { *; }

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, RuntimeVisibleAnnotations

# Keep Jetpack Glance ActionCallback classes instantiated via reflection
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
