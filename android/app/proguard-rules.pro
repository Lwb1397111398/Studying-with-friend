# ===== StudyFriend release 混淆规则（R8 于 2026-10 优化批次开启） =====
# Room / WorkManager / Compose / DataStore 均自带 consumer rules，无需重复。
# 应用代码零反射（Class.forName / getMethod / getDeclaredField 全工程无命中），
# 需要守护的只有两类：
#   1) kotlinx-serialization 编译期生成的 serializer：Json 按类的 Companion/serializer
#      方法查找，被 R8 改名/删除后运行期报 "Serializer for class ... not found"
#   2) onnxruntime 的 JNI 桥：native 层按 Java 类名与方法名回呼，不可混淆

-keepattributes *Annotation*, InnerClasses, Signature

-keep,includedescriptorclasses class com.studyfriend.app.**$$serializer { *; }
-keepclassmembers class com.studyfriend.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.studyfriend.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class ai.onnxruntime.** { *; }

# pdfbox-android 的 JPEG2000 解码依赖（com.gemalto.jp2）是可选包，未随库分发：
# 遇 JPX 书页时该 filter 自行报错（与未开 R8 前行为一致），声明缺类无妨
-dontwarn com.gemalto.jp2.**
