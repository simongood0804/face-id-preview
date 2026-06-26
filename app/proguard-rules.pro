# EvsSDK 相关 - 不混淆
-keep class com.android.car.evs.** { *; }

# Face ID 算法接口 - 不混淆（算法团队需实现）
-keep interface com.dvr.faceid.algorithm.IFaceIDAlgorithm { *; }
-keep class com.dvr.faceid.algorithm.IFaceIDAlgorithm$FaceIDResult { *; }
