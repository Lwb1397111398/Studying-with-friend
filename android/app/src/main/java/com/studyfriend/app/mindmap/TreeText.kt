package com.studyfriend.app.mindmap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 一行一个节点、TAB 缩进的通用树节点 */
data class TreeNote(
    val topic: String,
    val children: MutableList<TreeNote> = mutableListOf(),
)

/**
 * 「听的思维」的导图文本格式：与用户示例一致——一行一个节点，TAB 缩进表示层级。
 * 该格式同时作为 LLM 的输出格式与回传格式（AI 看得懂、用户也能手改）。
 */
object TreeText {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** 解析缩进树文本为多棵树（forest）。TAB 缩进优先，纯空格按每 4 个算一层。 */
    fun parse(text: String): List<TreeNote> {
        val roots = mutableListOf<TreeNote>()
        // 栈里保存 (depth, node)
        val stack = ArrayDeque<Pair<Int, TreeNote>>()
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val line = raw.trimEnd()
            val indentStr = line.takeWhile { it == '\t' || it == ' ' }
            val depth = if (indentStr.contains('\t')) {
                indentStr.count { it == '\t' }
            } else {
                indentStr.length / 4
            }
            val topic = cleanTopic(line)
            if (topic.isEmpty()) continue
            val node = TreeNote(topic)
            while (stack.isNotEmpty() && stack.last().first >= depth) stack.removeLast()
            if (stack.isEmpty()) roots.add(node) else stack.last().second.children.add(node)
            stack.addLast(depth to node)
        }
        return roots
    }

    /** 剥掉 AI 可能混进节点文字的 markdown 记号：列表符、标题符、成对加粗/斜体 */
    private fun cleanTopic(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^[\\-•·*]\\s+"), "")
        s = s.replace(Regex("^#{1,6}\\s*"), "")
        s = s.trimStart('•', '·')
        if (s.length >= 4 && s.startsWith("**") && s.endsWith("**")) {
            s = s.substring(2, s.length - 2)
        } else if (s.length >= 2 && s.startsWith("*") && s.endsWith("*")) {
            s = s.substring(1, s.length - 1)
        }
        // 成对记号之外，开头残留的未闭合星号（如 *强调*文字）也剥掉
        s = s.trimStart('*')
        return s.trim()
    }

    /** 多棵树序列化为缩进文本（每个节点一行，内部换行替换为空格） */
    fun serialize(forest: List<TreeNote>): String = buildString {
        fun walk(node: TreeNote, depth: Int) {
            repeat(depth) { append('\t') }
            append(node.topic.replace('\n', ' ').replace('\r', ' ').trim())
            append('\n')
            for (c in node.children) walk(c, depth + 1)
        }
        forest.forEach { walk(it, 0) }
    }.trimEnd()

    /**
     * 缩进文本 → mind-elixir 数据 JSON。
     * 单个顶层主题直接作为根；多个顶层主题包一层虚拟根（title 传入笔记标题）。
     * @return Pair(mapJson, virtualRoot)
     */
    fun toMapData(forest: List<TreeNote>, virtualRootTitle: String): Pair<String, Boolean> {
        require(forest.isNotEmpty()) { "空导图" }
        val virtualRoot = forest.size > 1
        val root = if (virtualRoot) {
            TreeNote(virtualRootTitle.ifBlank { "思维导图" }, forest.toMutableList())
        } else {
            forest.first()
        }
        val data = buildJsonObject {
            put("nodeData", nodeToJson(root, isRoot = true))
            put("direction", JsonPrimitive(2)) // SIDE
            put("arrows", JsonArray(emptyList()))
            put("summaries", JsonArray(emptyList()))
        }
        return json.encodeToString(JsonObject.serializer(), data) to virtualRoot
    }

    /** mind-elixir 数据 JSON → 缩进文本（virtualRoot 为 true 时展开虚拟根） */
    fun fromMapData(mapJson: String, virtualRoot: Boolean): String {
        val data = json.parseToJsonElement(mapJson).jsonObject
        val nodeData = data["nodeData"]?.jsonObject ?: return ""
        val root = nodeFromJson(nodeData)
        val forest = if (virtualRoot) root.children else listOf(root)
        return serialize(forest)
    }

    /** mind-elixir 数据 JSON → 树的 JSON（只取 nodeData，避免把 arrows 等也回传给 LLM） */
    fun mapJsonToTreeJson(mapJson: String): String {
        val data = json.parseToJsonElement(mapJson).jsonObject
        val nodeData = data["nodeData"] ?: return "{}"
        return json.encodeToString(JsonObject.serializer(), nodeData.jsonObject)
    }

    // ---------- 内部 ----------

    private var idCounter = 0L

    private fun newId(): String {
        idCounter += 1
        return "n${System.nanoTime()}-$idCounter"
    }

    private fun nodeToJson(node: TreeNote, isRoot: Boolean): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(if (isRoot) "me-root" else newId()))
        put("topic", JsonPrimitive(node.topic))
        if (node.children.isNotEmpty()) {
            put("children", JsonArray(node.children.map { nodeToJson(it, false) }))
        }
    }

    private fun nodeFromJson(obj: JsonObject): TreeNote {
        val topic = obj["topic"]?.jsonPrimitive?.content ?: ""
        val children = obj["children"]?.jsonArray
            ?.map { nodeFromJson(it.jsonObject) }
            ?: emptyList()
        return TreeNote(topic, children.toMutableList())
    }
}
