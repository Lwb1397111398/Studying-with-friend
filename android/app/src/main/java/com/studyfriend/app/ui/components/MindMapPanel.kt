package com.studyfriend.app.ui.components

import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** JS → Android 回调（方法名不能和属性同名，否则递归调用自己） */
class MapBridge(
    private val onReadyCb: () -> Unit,
    private val onMapChangedCb: (String) -> Unit,
    private val onLogCb: (String) -> Unit,
    private val onPngCb: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun onReady() {
        onReadyCb()
    }

    @JavascriptInterface
    fun onMapChanged(json: String) {
        onMapChangedCb(json)
    }

    @JavascriptInterface
    fun onLog(msg: String) {
        onLogCb(msg)
    }

    @JavascriptInterface
    fun onPngReady(base64: String) {
        onPngCb(base64)
    }
}

/** Android → JS 控制器 */
class MapController(private val webView: WebView) {

    private fun eval(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun init(json: String, dark: Boolean) = eval("appInit(${jsonArg(json)}, $dark)")
    fun setData(json: String) = eval("appSetData(${jsonArg(json)})")
    fun setDark(dark: Boolean) = eval("appSetDark($dark)")
    fun setEditable(editable: Boolean) = eval("appSetEditable($editable)")
    fun undo() = eval("appUndo()")
    fun redo() = eval("appRedo()")
    fun toCenter() = eval("appToCenter()")
    fun exportPng() = eval("appExportPng()")

    companion object {
        private val json = Json
        fun jsonArg(s: String): String = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))
    }
}

/**
 * 思维导图画布（WebView + mind-elixir，本地资源离线可用）。
 * - 拖动/编辑节点后通过 onMapChanged 回传完整数据（页面内已做 500ms 防抖）
 * - 外部 mapJson 变化（AI 重新生成/回退版本）时自动刷新画布
 */
@Composable
fun MindMapPanel(
    mapJson: String,
    editable: Boolean,
    onMapChanged: (String) -> Unit,
    onController: (MapController) -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    onPngReady: (String) -> Unit = {},
) {
    val latestOnMapChanged = rememberUpdatedState(onMapChanged)
    val latestOnController = rememberUpdatedState(onController)
    val latestEditable = rememberUpdatedState(editable)
    val latestDark = rememberUpdatedState(darkTheme)
    val latestOnPngReady = rememberUpdatedState(onPngReady)

    var pageLoaded by remember { mutableStateOf(false) }
    // 最近一次本地产生的数据（自己拖动导致的变化不回推画布，防循环）
    var localJson by remember { mutableStateOf<String?>(null) }
    var initedJson by remember { mutableStateOf<String?>(null) }
    var lastEditable by remember { mutableStateOf<Boolean?>(null) }
    var lastDark by remember { mutableStateOf<Boolean?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.textZoom = 100
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 模拟器软件渲染下 WebView 硬件合成有 bug（内容不显示），强制软件绘制
                val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
                    android.os.Build.PRODUCT.contains("sdk_gphone") ||
                    android.os.Build.MODEL.contains("Emulator", ignoreCase = true)
                if (isEmulator) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setBackgroundColor(android.graphics.Color.WHITE)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                        val m = message ?: return true
                        // 按浏览器 console 级别转发到对应 Android 日志级别，不再一律 Log.w（最终 QA nit）
                        when (m.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                Log.e("MindMapJS", m.message())
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> Log.w("MindMapJS", m.message())
                            else -> Log.i("MindMapJS", m.message())
                        }
                        return true
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                        Log.i("MindMap", "page finished: $url")
                        pageLoaded = true
                    }
                }
                addJavascriptInterface(
                    MapBridge(
                        onReadyCb = { Log.i("MindMap", "js ready") },
                        onMapChangedCb = { json ->
                            localJson = json
                            latestOnMapChanged.value(json)
                        },
                        onLogCb = { Log.i("MindMap", it) },
                        onPngCb = { base64 -> latestOnPngReady.value(base64) },
                    ),
                    "AndroidBridge",
                )
                loadUrl("file:///android_asset/mindmap/index.html")
            }
        },
        onRelease = { wv ->
            wv.destroy()
        },
        update = { wv ->
            if (!pageLoaded) return@AndroidView
            val ctl = MapController(wv)
            val isInit = initedJson == null
            if (isInit || (localJson != null && mapJson != localJson && mapJson != initedJson)) {
                initedJson = mapJson
                localJson = mapJson
                if (isInit) {
                    Log.i("MindMap", "init map, ${mapJson.length} chars")
                    latestOnController.value(ctl)
                    ctl.init(mapJson, latestDark.value)
                } else {
                    ctl.setData(mapJson)
                }
            }
            if (lastEditable != latestEditable.value) {
                lastEditable = latestEditable.value
                ctl.setEditable(latestEditable.value)
            }
            if (lastDark != latestDark.value) {
                lastDark = latestDark.value
                ctl.setDark(latestDark.value)
            }
        },
    )
}
