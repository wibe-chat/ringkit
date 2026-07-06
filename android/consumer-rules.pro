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
