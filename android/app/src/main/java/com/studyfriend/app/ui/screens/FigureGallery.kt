package com.studyfriend.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParagraphEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


// 从 ReadScreen.kt 拆出（2026-10-11 结构批次）：混排行构建/插图 LRU 解码/图行/全屏预览

// ==================== P4 示意图（§E） ====================

private const val TAG_FIG = "FigureRow"

/**
 * 暗色主题插图柔化（2026-11-21 实景体验轮第五波引入；第九波改**按图自适应**）：
 * 《侵权行为》装机实录——扫描书插图几乎全是白底黑字线图，暗色主题下纯白大块嵌在深棕
 * 背景里非常刺眼。wave-5 用定值 RGB×0.76（白底→约 194 灰底）；第九波主测《民法思维》
 * 实录发现定值压不住**高亮度彩色图**——原书双色印刷的青底比较表 ×0.76 后依然炸眼。
 * 处理：采样图的 P95 亮度，亮部越亮压得越狠，目标亮部 ≈0.55——白底线图（p95≈0.97）
 * 压到 0.57 系数、青底表格（p95≈0.85）压到 0.65，暗图（p95≤0.58）完全不动；
 * 下限 0.40 防压成墨团。不做黑白反转——反转会让彩色图变补色怪图。
 */
@Composable
internal fun figureToneFilter(bitmap: Bitmap?): ColorFilter? {
    // 阅读主题包（第六波）：墨玉/纯黑纸色下同样刺眼——暗色判据取「阅读页实际底色」
    // （固定主题优先，其次全局主题 surface）
    val rt = LocalReadingTheme.current
    val dark = (rt?.bg ?: MaterialTheme.colorScheme.surface).luminance() < 0.5f
    if (!dark) return null
    val peak = remember(bitmap) { bitmap?.let { imagePeakLuma(it) } ?: return@remember null } ?: return null
    // Compose ColorMatrix 是 identity 数组，直接改 RGB 对角系数（0/5/10 位）压亮度
    val factor = (TARGET_PEAK / peak).coerceIn(MIN_FACTOR, 1f)
    if (factor >= 0.97f) return null
    val m = ColorMatrix()
    m.values[0] = factor
    m.values[5] = factor
    m.values[10] = factor
    return ColorFilter.colorMatrix(m)
}

/** 暗色图目标亮部亮度（P95 被压到这个值附近）与系数下限 */
private const val TARGET_PEAK = 0.55f
private const val MIN_FACTOR = 0.40f

/**
 * 采样亮度 P95（0~1）：网格抽点（每边约 64 点）→ luma 排序取 95 分位。比均值更贴
 * 「亮部多扎眼」的体感——白底黑字线图均值被黑字拉低，P95 仍≈0.97。解码失败按最亮
 * 处理（1f → 按下限系数压，宁暗勿刺）。
 */
internal fun imagePeakLuma(b: Bitmap): Float {
    val w = b.width
    val h = b.height
    if (w <= 0 || h <= 0) return 1f
    val step = maxOf(1, minOf(w, h) / 64)
    val cols = (w + step - 1) / step
    val rows = (h + step - 1) / step
    val lumas = FloatArray(cols * rows)
    var n = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val px = b.getPixel(x, y)
            val r = (px shr 16 and 0xFF) / 255f
            val g = (px shr 8 and 0xFF) / 255f
            val bl = (px and 0xFF) / 255f
            if (n < lumas.size) {
                lumas[n++] = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            }
            x += step
        }
        y += step
    }
    if (n == 0) return 1f
    val arr = lumas.copyOf(n)
    arr.sort()
    return arr[((n - 1) * 95) / 100]
}

/** 混排行：段行 / 图行 */
internal sealed class ReadRow {
    data class Para(val p: ParagraphEntity) : ReadRow()
    data class Fig(val f: FigureEntity) : ReadRow()
}

/**
 * 段落流混排（P4 §E）：图插在同 pageNo 的第 ordAfterPara 段之后——「第 N 段」=
 * 阅读页渲染列表（页内全量段，含 TOC/FOOTNOTE/FRONT 次级段）序号，与 §3-B 锚定
 * 基准同口径零换算。ordAfterPara=-1 三态（r7-P2-9）：该页有段 → 页首段之前；
 * 该页无段 → 页码小于它的最后一段之后；全章没有更早段 → 章首。
 * ord 越界 coerceAtMost(页段数-1) 兜底页末（r9-P2-5）；同一插入位多图保持
 * byChapterFlow 排序（pageNo/ord/bboxY0）稳定展开。
 */
internal fun buildReadRows(
    paragraphs: List<ParagraphEntity>,
    figures: List<FigureEntity>,
): List<ReadRow> {
    if (figures.isEmpty()) return paragraphs.map { ReadRow.Para(it) }
    // 页号 → 该页段落在渲染列表中的下标集合（按出现序）；TXT/粘贴段 pageNo=null 不参与
    val indicesByPage = HashMap<Int, MutableList<Int>>()
    paragraphs.forEachIndexed { i, p ->
        p.pageNo?.let { indicesByPage.getOrPut(it) { mutableListOf() }.add(i) }
    }
    val figsAt = HashMap<Int, MutableList<FigureEntity>>()
    for (f in figures) {
        val pageIdxs = indicesByPage[f.pageNo]
        val at = when {
            !pageIdxs.isNullOrEmpty() -> {
                val ord = if (f.ordAfterPara < 0) -1 else f.ordAfterPara.coerceAtMost(pageIdxs.size - 1)
                if (ord < 0) pageIdxs.first() else pageIdxs[ord] + 1
            }
            else -> {
                // 该页无段（图页文字全空/段被替换）：页码更小的最后一段之后；全章无更早段 → 章首
                val lastEarlier = paragraphs.indexOfLast { it.pageNo != null && it.pageNo < f.pageNo }
                if (lastEarlier >= 0) lastEarlier + 1 else 0
            }
        }
        figsAt.getOrPut(at) { mutableListOf() }.add(f)
    }
    val rows = mutableListOf<ReadRow>()
    paragraphs.forEachIndexed { i, p ->
        figsAt[i]?.forEach { rows.add(ReadRow.Fig(it)) }
        rows.add(ReadRow.Para(p))
    }
    figsAt[paragraphs.size]?.forEach { rows.add(ReadRow.Fig(it)) }
    return rows
}

/**
 * 图片行 Bitmap 缓存（r4-P2-3 + r7-P1-5 按字节）：上限 = maxMemory×15% clamp
 * [15MB, 50MB]——按字节计才与设备内存挂钩（按数量 20×4.5MB=90MB 低端机偏大）。
 */
internal object FigureBitmapCache {
    /** 解码调度器（r5-P2-2）：并发 2，单张 <100ms 下排队延迟减半，不挤占其他解码 */
    val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)

    val lru = object : LruCache<Long, Bitmap>(
        run {
            val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            (maxKb * 15 / 100).coerceIn(15 * 1024, 50 * 1024)
        },
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
        // 驱逐即弃引用、不显式 recycle：API≥26 Bitmap 像素在 Java 堆，recycle 与
        // Compose 仍持有的引用有竞态；读取端 isRecycled 守卫兜底（计划「读前检查」）
    }
}

/** Android 官方采样算法：解出不超过目标宽高的最大 2^n 采样 */
internal fun calcInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    var size = 1
    if (srcW > reqW || srcH > reqH) {
        val halfW = srcW / 2
        val halfH = srcH / 2
        while (halfW / size >= reqW && halfH / size >= reqH) size *= 2
    }
    return size
}

/**
 * 图片行（P4 §E）：DB 尺寸 aspectRatio 先占位（无布局跳动）→ IO 按屏宽预算算
 * inSampleSize 解码刷帧。失败双态（r7-P2-7）：文件不在 →「图片已丢失」整块可点
 * 重试（仍失败维持提示）；解码 null →「图片已损坏」+ Log.w 记文件。LRU 命中免解码，
 * 读前 isRecycled 防竞态。圆角+1dp 描边+「图 N（p…）」角标。
 */
@Composable
internal fun FigureRow(
    f: FigureEntity,
    filesRoot: File,
    visionDonePages: Set<Int>,
    onOpenPreview: () -> Unit,
) {
    // r11-P2-2：vision 已替换页的锚定可能偏 1-2 段——logcat 直接定位错位图，替代逐页手工走查
    LaunchedEffect(f.id, visionDonePages) {
        if (f.pageNo in visionDonePages) {
            Log.w(TAG_FIG, "figure seq=${f.seqNo} page=${f.pageNo} 锚定可能偏 1-2 段（该页已 vision 替换）")
        }
    }
    val density = LocalDensity.current
    // 解码预算 = 屏宽物理像素（行内显示宽度上限；幸存图 ≤1200px 宽时采样恒 1）
    val budgetW = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.toInt().coerceAtLeast(1)
    val ratio = if (f.width > 0 && f.height > 0) f.width.toFloat() / f.height else 4f / 3f

    var attempt by remember(f.id) { mutableIntStateOf(0) }
    var bitmap by remember(f.id) { mutableStateOf<Bitmap?>(null) }
    var lost by remember(f.id) { mutableStateOf(false) }
    var corrupt by remember(f.id) { mutableStateOf(false) }

    LaunchedEffect(f.id, attempt) {
        val cached = FigureBitmapCache.lru.get(f.id)
        if (cached != null) {
            if (!cached.isRecycled) {
                bitmap = cached
                lost = false
                corrupt = false
                return@LaunchedEffect
            }
            FigureBitmapCache.lru.remove(f.id)
        }
        bitmap = null
        val file = File(filesRoot, f.file)
        if (!file.exists()) {
            lost = true
            corrupt = false
            return@LaunchedEffect
        }
        lost = false
        val decoded = withContext(FigureBitmapCache.decodeDispatcher) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val reqH = (budgetW.toFloat() * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, budgetW, reqH)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
        if (decoded == null) {
            Log.w(TAG_FIG, "figure decode null file=${f.file}")
            corrupt = true
        } else {
            corrupt = false
            bitmap = decoded
            FigureBitmapCache.lru.put(f.id, decoded)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = bitmap != null, onClickLabel = "全屏查看示意图") { onOpenPreview() },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null && !bmp.isRecycled -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "示意图，第${f.pageNo}页", // TODO(P6): caption
                contentScale = ContentScale.FillWidth,
                colorFilter = figureToneFilter(bmp),
                modifier = Modifier.fillMaxSize(),
            )
            lost -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "图片已丢失",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            corrupt -> Text(
                "图片已损坏",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 其余：解码中，灰底占位
        }
        // 失败态整块可点重试（r7-P2-7），盖在提示文案上
        if (lost || corrupt) {
            Box(Modifier.matchParentSize().clickable { attempt++ })
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        ) {
            Text(
                "图 ${f.seqNo}（p${f.pageNo}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 全屏预览（P2-1）：独立解码——不复用行内已缩 Bitmap、不占 LRU 配额，按预览显示
 * 尺寸重算采样（幸存图 ≤1.2MP 时 inSampleSize=1 直解，≈4.5MB 安全），放大不模糊；
 * 用完即回收。挂 ReadScreen 顶层（r5-P2-10）：开关不重建 LazyColumn，关闭即回原滚动位。
 */
@Composable
internal fun FigurePreviewDialog(f: FigureEntity, filesRoot: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var corrupt by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val screenW = with(density) {
            LocalConfiguration.current.screenWidthDp.dp.toPx()
        }.toInt().coerceAtLeast(1)
        val screenH = with(density) {
            LocalConfiguration.current.screenHeightDp.dp.toPx()
        }.toInt().coerceAtLeast(1)
        LaunchedEffect(f.id) {
            val decoded = withContext(FigureBitmapCache.decodeDispatcher) {
                val file = File(filesRoot, f.file)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, screenW, screenH)
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
            if (decoded != null) bitmap = decoded else corrupt = true
        }
        DisposableEffect(Unit) {
            onDispose {
                bitmap?.let { if (!it.isRecycled) it.recycle() } // 用完即回收（P2-1）
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                bmp != null && !bmp.isRecycled -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "示意图全屏预览，第${f.pageNo}页",
                    contentScale = ContentScale.Fit,
                    colorFilter = figureToneFilter(bmp),
                    modifier = Modifier.fillMaxSize(),
                )
                corrupt -> Text("图片已损坏", color = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator()
            }
        }
    }
}
