package com.studyfriend.app.data.ai

import android.content.Context

/** assets/prompts 下 prompt 文本加载（单文件 <8KB，进程内缓存） */
object PromptLoader {

    private val cache = mutableMapOf<String, String>()

    fun load(context: Context, name: String): String = synchronized(cache) {
        cache.getOrPut(name) {
            context.assets.open("prompts/$name.txt").bufferedReader().use { it.readText() }
        }
    }
}
