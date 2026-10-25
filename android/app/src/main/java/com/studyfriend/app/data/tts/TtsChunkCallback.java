package com.studyfriend.app.data.tts;

import kotlin.jvm.functions.Function1;

/**
 * sherpa-onnx 的 JNI 用硬编码签名 invoke([F)Ljava/lang/Integer; 反查回调方法，
 * Kotlin 2.x 编译的 lambda（indy）没有这个桥接方法，会在合成首个分片时直接 abort。
 * 因此用 Java 实现回调，保证字节码签名精确匹配（听的项目同款坑，2026-09 实证）。
 */
public final class TtsChunkCallback implements Function1<float[], Integer> {

    /** 返回 true 继续合成，返回 false 让 native 停止（stop() 时置 playing=false） */
    public interface Handler {
        boolean onChunk(float[] samples);
    }

    private final Handler handler;

    public TtsChunkCallback(Handler handler) {
        this.handler = handler;
    }

    @Override
    public Integer invoke(float[] samples) {
        return handler.onChunk(samples) ? 1 : 0;
    }
}
