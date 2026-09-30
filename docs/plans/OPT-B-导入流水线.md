# OPT-B 子计划案：PDF 导入进度 / 取消 / 省内存 + 分阶段反馈（R2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 逐任务执行。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 630 页 / 37.8MB 的真实书籍 PDF 导入全程有反馈（页数进度）、可取消、峰值内存显著下降；"正在读取与解析"黑盒拆成可感知的阶段。

**Architecture:** `PdfLoader.extract` 增加可选 `onProgress` / `isCancelled` 参数（默认值保持旧调用兼容）；文件经 cacheDir 临时文件 + `PDDocument.load(File)` 打开（finally 删除）；`ImportViewModel` 暴露 `progress: (page,total)?` 与 `phase` 状态、`cancelImport()`；`ImportScreen` 据此渲染确定性进度条 + 取消按钮 + 阶段文案（提取 PDF / 解析章节结构）。

**Tech Stack:** pdfbox-android 2.0.27（`PDDocument.load(File)` 已反编译确认存在，默认 `MemoryUsageSetting.setupMainMemoryOnly()`——收益是消除"原始 37.8MB 字节 + 解析结构"双驻留；需进一步降峰可换 `PDDocument.load(tmp, MemoryUsageSetting.setupTempFileOnly())`，该重载同样存在，默认先用主内存模式）、Compose M3。

---

### Task B1: PdfLoader 进度回调 + 取消检查点 + 临时文件路径

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/importer/PdfLoader.kt`
- Test: `android/app/src/test/java/com/studyfriend/app/data/importer/PdfLoaderTest.kt`（追加 4 例）

- [ ] **Step 1: 写失败测试**（复用既有 `pdfBytes`/`toUri` fixture 助手；追加）：

```kotlin
@Test
fun progressCallback_advancesPerPage_includingBoundaries() {
    val bytes = pdfBytes(listOf(fillerLines("Chapter 1 A", 4), fillerLines("Chapter 2 B", 4), fillerLines("Chapter 3 C", 4)))
    val seen = mutableListOf<Pair<Int, Int>>()
    // 尾随 lambda 会绑定到最后一个参数 isCancelled，进度回调必须用命名参数
    PdfLoader.extract(context, toUri(bytes), onProgress = { p, t -> seen.add(p to t) })
    assertEquals(0 to 3, seen.first())
    assertEquals(3 to 3, seen.last())
    assertEquals((0..3).toList(), seen.map { it.first }) // 单调不减且连续
}

@Test
fun cancelCheckpoint_throwsCancelledImport_andCleansTempFile() {
    val bytes = pdfBytes(listOf(fillerLines("Chapter 1 A", 4), fillerLines("Chapter 2 B", 4)))
    var calls = 0
    try {
        PdfLoader.extract(context, toUri(bytes), isCancelled = { ++calls > 1 }) // 在某个检查点触发（复制段/逐页段均可）
        throw AssertionError("应当抛出取消")
    } catch (e: PdfImportException) {
        assertTrue(e.message!!.contains("取消"))
    }
    assertTempFilesCleaned()
}

@Test
fun tempFile_removedAfterSuccess() {
    val bytes = pdfBytes(listOf(fillerLines("Chapter 1 A", 4)))
    PdfLoader.extract(context, toUri(bytes))
    assertTempFilesCleaned()
}

@Test
fun pageLoopCheckpoint_cancelsAfterProgressStarted() {
    // 专项覆盖逐页检查点（上一例对小 fixture 实际触发在复制段）：
    // 若页循环无检查点，extract 会正常完成 → AssertionError，本例即红
    val bytes = pdfBytes(
        listOf(fillerLines("Chapter 1 A", 4), fillerLines("Chapter 2 B", 4), fillerLines("Chapter 3 C", 4)),
    )
    var progressed = 0
    try {
        PdfLoader.extract(
            context, toUri(bytes),
            onProgress = { _, _ -> progressed++ },
            isCancelled = { progressed >= 2 }, // (0,total) 与第 1 页进度已发后，在页循环内中止
        )
        throw AssertionError("应当抛出取消")
    } catch (e: PdfImportException) {
        assertTrue(e.message!!.contains("取消"))
        assertTrue("取消应发生在页循环推进之后", progressed >= 2)
    }
    assertTempFilesCleaned()
}

/** 三路径（成功/取消/损坏）共用的清理断言 */
private fun assertTempFilesCleaned() {
    val leftovers = context.cacheDir.listFiles { f -> f.name.startsWith("import_") } ?: emptyArray()
    assertTrue("不应残留临时文件：${leftovers.toList()}", leftovers.isEmpty())
}
```

（`extract` 现签名是 `(context, uri)`；实现用默认参数单签名，测试传参一律命名参数。另在既有 `corruptedPdf_throwsFriendlyError` 的 catch 块之后补一行 `assertTempFilesCleaned()`——损坏路径同样必须清理。）

- [ ] **Step 2: 跑测试确认失败** — 编译错误（参数不存在）。
- [ ] **Step 3: 实现**：

```kotlin
fun extract(
    context: Context,
    uri: Uri,
    onProgress: (page: Int, total: Int) -> Unit = { _, _ -> },
    isCancelled: () -> Boolean = { false },
): String {
    PDFBoxResourceLoader.init(context)
    val tmp = File(context.cacheDir, "import_${System.nanoTime()}.pdf")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                // 分块复制 + 检查点：大文件复制段（秒级）也即时可断，而不是等 copyTo 整体结束
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled()) throw PdfImportException("已取消")
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
            }
        } ?: throw PdfImportException("读取文件失败")
        return try {
            PDDocument.load(tmp).use { doc ->
                val pages = doc.numberOfPages
                if (pages <= 0) throw PdfImportException("这个 PDF 没有可读取的页面")
                val stripper = PDFTextStripper()
                val sb = StringBuilder()
                onProgress(0, pages)
                for (page in 1..pages) {
                    if (isCancelled()) throw PdfImportException("已取消")
                    stripper.startPage = page
                    stripper.endPage = page
                    sb.append(stripper.getText(doc))
                    sb.append('\n')
                    onProgress(page, pages)
                }
                val text = sb.toString()
                if (text.length < pages * SCANNED_CHARS_PER_PAGE) {
                    throw PdfImportException("这看起来是扫描版 PDF（没有文字层），暂时无法导入；请换成带文字的 PDF 或 TXT")
                }
                text
            }
        } catch (e: InvalidPasswordException) {
            throw PdfImportException("PDF 已加密，请先解除密码保护再导入")
        } catch (e: OutOfMemoryError) {
            throw PdfImportException("这本书太大，内存装不下；建议拆分成几个文件分批导入")
        } catch (e: PdfImportException) {
            throw e
        } catch (e: Exception) {
            throw PdfImportException("PDF 解析失败：${e.message ?: "文件可能已损坏"}")
        }
    } finally {
        tmp.delete()
    }
}
```

（文件头补 `import java.io.File`。`PDDocument.load(tmp)` 已反编译确认存在；`load` 解析段本身不可取消（秒级），按"复制段分块即时可断、load 段取消延后生效"记账进验收。）

- [ ] **Step 4: 跑测试确认通过**；既有 3 例（双页解析/扫描版/损坏 PDF）全绿。

### Task B2: ImportViewModel 进度/阶段/取消状态

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/ui/screens/ImportViewModel.kt`
- [ ] **Step 1:** 增加状态：

```kotlin
/** PDF 提取进度 (page,total)；null=非提取阶段 */
var progress by mutableStateOf<Pair<Int, Int>?>(null)
    private set
/** 当前阶段文案：读取文件 / 提取 PDF / 解析章节结构；null=空闲 */
var phase by mutableStateOf<String?>(null)
    private set
private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

/** 用户取消：提取循环在下一个页检查点中止 */
fun cancelImport() {
    if (busy) cancelFlag.set(true)
}
```

（偏离母计划 §1 口径说明：不暴露 Job 句柄，用 AtomicBoolean+检查点——协程本体无需外部取消，靠检查点抛异常收尾即可；母计划该行已同步修订。）

- [ ] **Step 2:** `loadFile` 改造：入口 `if (busy) return`（防重复选择竞态）；`cancelFlag.set(false)`；IO 块内 PDF 分支改为（**phase 所有权：loadFile 只设"提取 PDF 文字"/"读取文件"，"解析章节结构"归 parse() 设/清**）：

```kotlin
val content = if (isPdfFile) {
    phase = "提取 PDF 文字"
    PdfLoader.extract(app, uri, onProgress = { p, t -> progress = p to t }, isCancelled = { cancelFlag.get() })
} else {
    phase = "读取文件"
    TextLoader.decode(...).text
}
```

`parse()` 内 `withContext(Default)` 前 `phase = "解析章节结构"`，尾部 `phase = null`。catch 顺序补（**语义钉死：取消≠失败，保留既有 chapters/sourceText**）：

```kotlin
} catch (e: PdfImportException) {
    if (cancelFlag.get()) parseNote = "已取消导入" // 不清空既有解析结果
    else failRead(e.message)
}
```

`finally { progress = null; phase = null }` 兜底。TXT 全量 readBytes 无检查点（TXT 读取秒级，可接受）；解析段无检查点，解析中点取消则结果照常展示，标志位在下一入口 `cancelFlag.set(false)` 复位。
- [ ] **Step 2b:** `reset()` 补 `progress = null`、`phase = null`、`cancelFlag.set(false)`。
- [ ] **Step 3:** `loadPasted` 与 `reparse` 同样加 `if (busy) return` 与 phase 复位纪律。
- [ ] **Step 4:** `compileDebugKotlin` 通过。

### Task B3: ImportScreen 确定性进度 + 取消按钮 + 阶段文案

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/ui/screens/ImportScreen.kt:81,143-151`
- [ ] **Step 1:** 合并两处 busy 展示：顶部保留 `LinearProgressIndicator`；原"正在读取与解析…"行改为：

```kotlin
if (vm.phase != null || vm.progress != null) {
    val p = vm.progress
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p != null) {
            LinearProgressIndicator(
                progress = { p.first.toFloat() / p.second },
                modifier = Modifier.weight(1f),
            )
            Text("提取 PDF ${p.first}/${p.second} 页", style = MaterialTheme.typography.bodySmall)
        } else {
            CircularProgressIndicator(Modifier.height(16.dp))
            Text(vm.phase ?: "", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { vm.cancelImport() }) { Text("取消") }
    }
}
```

（M3 新 API `progress: () -> Float` lambda 重载，BOM 2024.10 可用；原 `if (busy && chapters.isEmpty())` 旧块删除。**守卫是 `phase != null || progress != null` 而非 `busy`**：落库阶段（confirmImport，busy=true 但两态皆空）沿用顶部不定进度条、不渲染取消按钮——importBook 是不可中断事务，彼时的取消按钮必是假的；读取/提取/解析三阶段均有 phase 或 progress、行内反馈可见——其中仅提取段（含复制段）取消即时可达检查点，读取/解析段取消延后生效（语义见验收 #3）。）
- [ ] **Step 2:** `compileDebugKotlin` + 全量 `testDebugUnitTest` 0 失败。（全仓无 ImportScreen/ImportViewModel 自动化覆盖——androidTest 目录为空，勿引用不存在的冒烟测试；UI 行为在最终 E2E 冒烟里人工验证。）

### Task B4: 包验证与提交

- [ ] `./gradlew.bat testDebugUnitTest --no-daemon` 全绿（含 B1 新 4 例）。
- [ ] `git commit -m "feat(import): PDF 导入页数进度/可取消/临时文件省内存路径"`

## 验收标准（包级）

1. 进度回调单调且含 (0,total)→(total,total)；取消在检查点生效且文案含"取消"（含页循环检查点专项例：进度已推进后中止）；成功/取消/损坏三路径 cacheDir 均无 `import_*` 残留；复制段分块即时可断，`PDDocument.load` 段取消延后生效（秒级，已记账）。
2. 既有 PdfLoaderTest 3 例 + 全量单测保持绿。
3. VM：busy 期间重复 loadFile/loadPasted 被忽略；取消后 UI 无错误横幅、既有解析结果保留（parseNote="已取消导入"）；**取消语义（验收用例）**：提取段（含复制段）取消即时生效；解析段（BookParser，CPU 密集无挂起点）取消在解析完成后生效、结果照常展示；落库阶段不显示取消按钮。
