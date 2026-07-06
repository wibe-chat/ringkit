# ringkit consumer R8/ProGuard rules — merged into every consuming app's release
# build via `consumerProguardFiles` (§13). The plugin ships androidx.work for the
# §10.2 killed-state delivery host, whose Room database and workers are
# instantiated by REFLECTION. R8 strips their generated constructors in release,
# and the app then crashes at WorkManager init with
#   java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# — device-found on the SM-G781B release build; keep them.

# Room's generated *_Impl databases (WorkManager's WorkDatabase_Impl) are built
# reflectively by Room via their no-arg constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ListenableWorker/Worker subclasses — including ringkit's BackgroundDeliveryWorker
# — are instantiated by WorkManager's default WorkerFactory via their
# (Context, WorkerParameters) constructor.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Mode 2 (§7.3, D6): firebase-messaging is compileOnly. The disabled c2dm
# RingKitMessagingReceiver references com.google.firebase.messaging.RemoteMessage.
# A consumer WITHOUT firebase_messaging has no such class on its runtime classpath;
# since the receiver ships enabled="false" (never instantiated unless the app opts
# into Mode 2, which requires firebase_messaging), silence R8's missing-reference
# warning so a Firebase-free consumer's release build doesn't fail. Verified locally
# 2026-07-07 (minimal Firebase-free consumer, R8/minify release): WITH this rule the
# build succeeds; WITHOUT it R8 fails "Missing class ...messaging.RemoteMessage
# (referenced from RingKitMessagingReceiver.onReceive)" — so the rule is load-bearing.
-dontwarn com.google.firebase.messaging.**
