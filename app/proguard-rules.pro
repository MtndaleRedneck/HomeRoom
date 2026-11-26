# Preserve named worker classes created reflectively by WorkManager.
-keep class app.homeroom.** extends androidx.work.ListenableWorker { public <init>(...); }
-keepattributes Signature,InnerClasses,EnclosingMethod
