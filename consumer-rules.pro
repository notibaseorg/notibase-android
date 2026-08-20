# Notibase SDK — keep the public surface for hosts that use R8/ProGuard.
-keep class com.notibase.sdk.Notibase { *; }
-keep class com.notibase.sdk.Notibase$Config { *; }
-keep class com.notibase.sdk.NotibaseMessagingService { *; }
-keep class com.notibase.sdk.NotibaseNotifications { *; }
-keep class com.notibase.sdk.internal.InboxItem { *; }
