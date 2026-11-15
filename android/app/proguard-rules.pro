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

# sherpa-onnx（离线朗读，TTS 双模式）：JNI 按类名/方法名动态注册（含 TtsChunkCallback 的
# Function1 签名反查），R8 混淆会破坏绑定，全部 keep
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# pdfbox-android 的 JPEG2000 解码依赖（com.gemalto.jp2）是可选包，未随库分发：
# 遇 JPX 书页时该 filter 自行报错（与未开 R8 前行为一致），声明缺类无妨
-dontwarn com.gemalto.jp2.**

# java-fsrs（P0-① 复习排期）两类缺类放行（都不在真实执行路径上，R8 直接剥离）：
# 1) Card.toJson/fromJson 引用 Jackson——库自身唯一落盘入口，我们只走自己的列存取从不调它
#    （实测 Gradle 排除 Jackson 会让 fsrs 类在加载期就 NoClassDefFoundError，故依赖保留、
#    靠 R8 剥不可达路径，release 包不含 Jackson）
-dontwarn com.fasterxml.jackson.**
# 2) 库用 Lombok 编译，注解残留在字节码（Generated/NonNull），运行时注解类永不需要
-dontwarn lombok.Generated
-dontwarn lombok.NonNull
