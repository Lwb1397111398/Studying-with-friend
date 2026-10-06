# P6c·C3a/R1 结构保全重建计划案（A2）

版本：第 4 版（2026-10-06，按第 3 轮评审 87 分意见修订：测试断言口径声明、J5e-1 出处
说明、工具接口契约、token 拆分账、G1 回滚锚点锁定 2336eff；并已先行提交 C2 定稿
be22818 与 C3a 修复定稿 2336eff 两笔）。前置：R1 立案
（docs/plans/reviews/R1立案-c3a重跑章界漂移.md），老板拍板方向 A「结构保全重建」。
送 AI 评分，≥90 分方可落码。

## §0 目标（单一可验证陈述）

对仿真书重新导入（产生 bookId=8，OCR 与队列链路同 C3a 重跑）后，执行本计划重建，
使新书满足：**chapters=11 且 11 个 title 与基线快照 .e2e/p6c/c3a_after.db 的 bookId=6
逐字相等；未替换页逐页字符量与基线逐页相等；替换页（当次 fallbackPages）段落带当次
视觉缓存原文、pageNo 与 role 按 §2.3 规则落位**。§4 的 J5 断言系（每条带期望值与
`test`/`exit 1` 退出码）全部通过即为目标达成。

## §1 范围与非目标

改动文件仅 2 个：`android/app/src/main/java/com/studyfriend/app/data/vision/VisionRebuilder.kt`
（重写）+ `android/app/src/test/java/com/studyfriend/app/data/vision/` 下
VisionRebuilderPureTest.kt（增 3 删 1）与 VisionRebuilderTest.kt（增 1 改 0）。
不动 BookParser、导入路径、VisionWorker 触发时机、DB schema（零迁移）。不处理含
level=2 节行的书（G6 守卫拦截+跳过日志，宁可白跑，支持留 M2 待办）。

## §2 设计

### 2.1 资产复用清单（状态两态：现成=已核证签名/行为，待核=落码前 G4 复核）

| 资产 | 位置 | 状态 | 用途 |
| --- | --- | --- | --- |
| `ParagraphDao.byBookForRebuild(bookId)` | Daos.kt ParagraphDao 末尾（C3a 修复新增）；SQL=`SELECT p.* FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=:bookId ORDER BY p.pageNo, c.idx, p.idx` | 现成（C3a 修复包 J1 闸门已测） | 底稿行：chapterId/pageNo/role/idx/text 全带 |
| `ChapterDao.byBook(bookId)` | Daos.kt:116，`SELECT * FROM chapters WHERE bookId=:bookId ORDER BY idx` | 现成 | 现有章清单（G6 后全 level=1） |
| `BookRepository.replaceBookContent(bookId, book, pairs)` | BookRepository.kt:137 | **待核（G4）**：落码前 CodeGraph 核证第 2 参类型与事务行为 | 事务内删旧章重插+figures 按 pageNo 重挂 |
| `VisionCache.read(context, uri, pageNo)` | VisionCache.kt | 现成（C3a 两轮实测） | 读当次转写（key=uri hash+页号） |
| `inferTocLike`/`RE_TOC_ENTRY`/`TOC_INFER_MIN=3` | VisionRebuilder.kt:154-169 | 现成（JVM 6 例测） | 视觉目录页判定 |
| `coverageOk(newChars, expectedChars)` | VisionRebuilder.kt:178-179 | 现成（JVM 4 例测） | 覆盖守卫（80% 拍板值） |
| `joinTexts(footnotes)` | pdfpipeline | 现成 | 视觉脚注合并 |
| `DbValues.ROLE_BODY="BODY"/ROLE_TOC="TOC"/ROLE_FOOTNOTE="FOOTNOTE"/READ_NOT` | DbValues.kt:20-24 | 现成（本会话 grep 核证） | role 落位 |
| `ChapterEntity.level:Int=1 / parentOrder:Int?=null` | Entities.kt:43-57 | 现成（本会话 grep 核证） | 章元数据保全 |

### 2.2 精确删除清单（重解析路径退役）

VisionRebuilder.kt 删除以下 5 个 import 与其全部消费点：
`com.studyfriend.app.data.importer.BookParser`、`com.studyfriend.app.data.importer.PdfExtractResult`、
`com.studyfriend.app.data.importer.pdfpipeline.DocStats`、
`com.studyfriend.app.data.importer.pdfpipeline.PageOut`、
`com.studyfriend.app.data.importer.pdfpipeline.Para`。
保留 import：`com.studyfriend.app.data.importer.pdfpipeline.PageTranscription`（VisionCache.read
返回类型）、`com.studyfriend.app.data.importer.pdfpipeline.joinTexts`（脚注合并）。
类 KDoc 重写：结构保全（方向 A）设计、R1 立案引用、D1/D2 拍板值记录。

### 2.3 页归属规则（两条拍板值 + 两条既有语义）

数据定义：底稿行集 rows 按 byBookForRebuild 序（pageNo 升、章 idx 升、段 idx 升）；
`byPage = rows.groupBy { it.pageNo!! }`；`owners = byPage.mapValues { (_, rs) -> rs.last().chapterId }`；
替换页集 R = 队列 DONE 页号集。

- **D1（拍板值）**：页 p∈R 且 byPage[p] 非空 → p 的视觉段全部归入 `owners[p]`
  （=该页底稿最后一行所在章）。理由：章首跨页时新章保住开头，章末资产挂新章合阅读
  序。**量化代价（基线实测）**：c3a_after.db bookId=6 全书 19 个有字页中跨章页=0
  （SQL：`SELECT count(*) FROM (SELECT p.pageNo FROM paragraphs p JOIN chapters c ON
  p.chapterId=c.id WHERE c.bookId=6 AND p.pageNo IS NOT NULL GROUP BY p.pageNo HAVING
  count(DISTINCT p.chapterId)>1);` → 空集），本书 D1 代价恰为零；其他书跨章页占比
  未测（UNMEASURED），触发 G1 时按预案回退。
- **D2（拍板值）**：页 p∈R 且 byPage[p] 为空（孤儿页，如仿真书页 3 目录页）→ 归入
  owners 中页码 ≤p 的最大页号所属章（floor）；不存在则取页码 >p 的最小页号所属章
  （ceil）；两者皆无被既有守卫 rows.isEmpty() 前置拦截。
- **部分替换（既有语义保留）**：p∈R 但 VisionCache.read 返回 null → p 的底稿行原样
  保留原章，replaced 计数不加，并 `Log.w(TAG, "rebuild partial: cache miss page=$p")`。
- **未替换页（既有语义）**：底稿行原地不动原章——跨章页行级保全。

### 2.4 rebuildIfSafe 终版代码（落码 1:1，无待定项；落码约定：private data class NewPara/OutPara 声明在 object 顶层之内、rebuildIfSafe 之外）

```kotlin
object VisionRebuilder {
    private const val TAG = "VisionRebuilder"
    private data class NewPara(val text: String, val role: String, val pageNo: Long)
    private data class OutPara(val text: String, val role: String, val pageNo: Long, val sortPage: Long, val sortSeq: Int)

    suspend fun rebuildIfSafe(db: StudyDatabase, context: Context, bookId: Long): Boolean {
        val dao = db.visionQueueDao()
        val queue = dao.byBook(bookId)
        if (queue.isEmpty()) return false
        if (dao.hasAnyAiConsumption(bookId)) return false
        val doneItems = queue.filter { it.status == DbValues.VQ_DONE }
        if (doneItems.isEmpty()) return false

        val rows = db.paragraphDao().byBookForRebuild(bookId)
        if (rows.isEmpty() || rows.any { it.pageNo == null }) return false
        val chapters = db.chapterDao().byBook(bookId)
        if (chapters.any { it.level != 1 }) {
            Log.w(TAG, "rebuild skip bookId=$bookId: level-2 sections present")
            return false
        }
        val bookChars = rows.sumOf { it.text.length }
        val byPage = rows.groupBy { it.pageNo!! }
        val owners: Map<Long, Long> = byPage.mapValues { (_, rs) -> pageOwnerLast(rs) }

        val doneByPage = doneItems.associateBy { it.pageNo }
        val newRows = LinkedHashMap<Long, List<NewPara>>()
        var replaced = 0
        var replacedDbChars = 0
        var replacedVisionChars = 0
        for ((page, item) in doneByPage) {
            val t = VisionCache.read(context, item.uri, page)
            if (t == null) {
                Log.w(TAG, "rebuild partial: cache miss page=$page")
                continue
            }
            val tocPage = inferTocLike(t.body)
            val paras = buildList {
                t.body.forEach { add(NewPara(it, if (tocPage) DbValues.ROLE_TOC else DbValues.ROLE_BODY, page)) }
                if (t.footnotes.isNotEmpty()) add(NewPara(joinTexts(t.footnotes), DbValues.ROLE_FOOTNOTE, page))
            }
            newRows[page] = paras
            replaced++
            replacedDbChars += byPage[page]?.sumOf { it.text.length } ?: 0
            replacedVisionChars += paras.sumOf { it.text.length }
        }
        if (replaced == 0) return false

        val placed = HashMap<Long, MutableList<NewPara>>()
        for ((page, paras) in newRows) {
            val owner = orphanOwner(owners, page) ?: return false   // 不可达：rows 非空则 owners 非空
            placed.getOrPut(owner) { mutableListOf() }.addAll(paras)
        }
        val pairs = chapters.map { ch ->
            val kept = rows.filter { it.chapterId == ch.id && it.pageNo !in newRows.keys }
                .map { OutPara(it.text, it.role, it.pageNo!!, it.pageNo!!, 0) }
            val news = placed[ch.id].orEmpty().mapIndexed { i, np ->
                OutPara(np.text, np.role, np.pageNo, np.pageNo, 1_000_000 + i)
            }
            // 排序键 (sortPage, sortSeq)：页内不混排（替换页仅视觉段、未替换页仅底稿行）；
            // 同页多底稿行 seq 同 0 → compareBy 稳定排序保持 byBookForRebuild 原序
            val paras = (kept + news).sortedWith(compareBy({ it.sortPage }, { it.sortSeq }))
                .map { ParagraphEntity(chapterId = 0, idx = 0, text = it.text, role = it.role, pageNo = it.pageNo) }
            ChapterEntity(
                bookId = bookId, idx = ch.idx, title = ch.title,
                readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                level = ch.level, parentOrder = ch.parentOrder,
            ) to paras
        }
        val newChars = pairs.sumOf { (_, ps) -> ps.sumOf { it.text.length } }
        if (!coverageOk(newChars, bookChars - replacedDbChars + replacedVisionChars)) {
            Log.w(TAG, "rebuild aborted: coverage $newChars vs expected " +
                (bookChars - replacedDbChars + replacedVisionChars))
            return false
        }
        Log.i(TAG, "rebuild bookId=$bookId pages=${byPage.keys.size + newRows.size} " +
            "chars=$bookChars→$newChars chapters=${chapters.size} (preserved)")
        val book = db.bookDao().get(bookId) ?: return false
        BookRepository(db).replaceBookContent(
            bookId, book.copy(updatedAt = System.currentTimeMillis()), pairs,
        )
        return true
    }

    /** D1：页归属=该页底稿最后一行所在章（rows 已按 pageNo,c.idx,p.idx 序） */
    internal fun pageOwnerLast(rows: List<ParagraphEntity>): Long = rows.last().chapterId

    /** D2：孤儿页 floor→ceil；owners 空返回 null（调用侧 rows.isEmpty 守卫先行） */
    internal fun orphanOwner(owners: Map<Long, Long>, page: Long): Long? =
        owners.filterKeys { it <= page }.maxOrNull()?.let { owners[it] }
            ?: owners.filterKeys { it > page }.minOrNull()?.let { owners[it] }

    const val TOC_INFER_MIN = 3
    internal val RE_TOC_ENTRY = Regex("""^第[一二三四五六七八九十百千0-9]+[章节回][^0-9]*\d+\s*$""")
    internal fun inferTocLike(texts: List<String>): Boolean =
        texts.count { RE_TOC_ENTRY.containsMatchIn(it.trim()) } >= TOC_INFER_MIN
    internal fun coverageOk(newChars: Int, expectedChars: Int): Boolean =
        expectedChars == 0 || newChars.toLong() * 5 >= expectedChars.toLong() * 4
}
```
（kept 行 `it.role` 为 String 原样透传；`pages=` 口径=底稿有字页数+视觉命中页数=22。）

### 2.5 与 C3a 修复版的行为差异对照

| 维度 | C3a 修复版（现网） | 本计划（A2） |
| --- | --- | --- |
| 章结构 | BookParser 重推（11→5 漂移，R1 实证） | 原章原 title 原序逐字保全 |
| 段落 role | 重解析重推 | 底稿行原样；视觉段按 §2.3 规则 |
| 字符账 | 15245=14772+512−39（标题升格进 chapters.title） | 14772+当次视觉字符（无升格损耗；§4 step8 动态对账） |
| 退役能力 | — | 目录校准联动、跨页合并、字号标题判定（DB 无字号，重解析对扫描书不可靠——R1 根因） |

## §3 测试设计（构造与断言全文，数值一次算定）

### 3.1 VisionRebuilderPureTest

保留 10 例原样（inferTocLike 6 + coverageOk 4）；删除
`assemble_viaPdfExtractResult_formatLocked`（路径退役）；新增 3 例（断言值即终值）：

```kotlin
@Test pageOwnerLast_straddlePage_returnsLastRowChapter() {
    val rows = listOf(
        ParagraphEntity(chapterId = 1, idx = 0, text = "a", role = "BODY", pageNo = 8),
        ParagraphEntity(chapterId = 2, idx = 1, text = "b", role = "BODY", pageNo = 8),
    )
    assertEquals(2L, VisionRebuilder.pageOwnerLast(rows))          // D1：末行章
}
@Test orphanOwner_floor_thenCeil() {
    val owners = mapOf(2L to 10L, 6L to 11L)
    assertEquals(10L, VisionRebuilder.orphanOwner(owners, 3))      // floor：页3→页2
    assertEquals(10L, VisionRebuilder.orphanOwner(owners, 2))      // 自身命中
    assertEquals(11L, VisionRebuilder.orphanOwner(owners, 7))      // ceil：页7→页6
}
@Test orphanOwner_emptyOwners_returnsNull() {
    assertEquals(null, VisionRebuilder.orphanOwner(emptyMap(), 1))
}
```

### 3.2 VisionRebuilderTest（Room 内存库；既有 3 例零改动，理由：①单章单页替换断言在新实现下同样成立；②守卫 2 行为不变；③队列空/缓存 miss→replaced=0→false 不变）

新增用例构造全文（seedBook2：书→2 章→4 段→队列 2 页→缓存 2 页）：

```kotlin
@Test rebuild_preservesChapters_andPlacesVisionByOwner() = runBlocking {
    val uri = Uri.fromFile(pdfFile).toString()          // 复用既有 @Before 生成的临时 PDF
    val bookId = db.bookDao().insert(
        BookEntity(title = "结构保全测书", author = null, format = "PDF", uri = uri,
            importedAt = 1, updatedAt = 1))
    // 章与底稿段（chapterId 先 0，insert 章后回填）
    val jiaId = db.chapterDao().insert(ChapterEntity(bookId = bookId, idx = 0, title = "甲章",
        readState = DbValues.READ_NOT, gist = null, keyTermsJson = null))
    val yiId = db.chapterDao().insert(ChapterEntity(bookId = bookId, idx = 1, title = "乙章",
        readState = DbValues.READ_NOT, gist = null, keyTermsJson = null))
    db.paragraphDao().insertAll(listOf(
        ParagraphEntity(chapterId = jiaId, idx = 0, text = "甲一一", role = DbValues.ROLE_BODY, pageNo = 1),
        ParagraphEntity(chapterId = jiaId, idx = 1, text = "甲一二", role = DbValues.ROLE_BODY, pageNo = 1),
        ParagraphEntity(chapterId = jiaId, idx = 2, text = "甲二一", role = DbValues.ROLE_BODY, pageNo = 2),
        ParagraphEntity(chapterId = yiId, idx = 0, text = "乙三一", role = DbValues.ROLE_BODY, pageNo = 3),
    ))
    db.visionQueueDao().insertAll(listOf(
        VisionQueueEntity(bookId = bookId, uri = uri, pageNo = 2, originChars = 3,
            status = DbValues.VQ_DONE, attempts = 0, updatedAt = 1),
        VisionQueueEntity(bookId = bookId, uri = uri, pageNo = 3, originChars = 3,
            status = DbValues.VQ_DONE, attempts = 0, updatedAt = 1),
    ))
    VisionCache.write(context, uri, 2, PageTranscription(
        body = listOf("甲二视觉段"), footnotes = listOf("甲二脚注")))
    VisionCache.write(context, uri, 3, PageTranscription(
        body = listOf("目 录", "第一章 概述 .... 4", "第二章 保证 .... 6"), footnotes = emptyList()))

    assertTrue(VisionRebuilder.rebuildIfSafe(db, context, bookId))
    val chs = db.chapterDao().byBook(bookId)
    assertEquals(listOf("甲章", "乙章"), chs.map { it.title })            // J:章 title 逐字
    val jia = db.paragraphDao().byChapter(chs[0].id)
    val yi = db.paragraphDao().byChapter(chs[1].id)
    assertEquals(listOf("甲一一", "甲一二"), jia.filter { it.pageNo == 1L }.map { it.text })
    assertEquals(listOf("甲二视觉段"), jia.filter { it.role == DbValues.ROLE_BODY && it.pageNo == 2L }.map { it.text })
    assertEquals(listOf("甲二脚注"), jia.filter { it.role == DbValues.ROLE_FOOTNOTE }.map { it.text })
    assertEquals(listOf("目 录", "第一章 概述 .... 4", "第二章 保证 .... 6"),
        yi.filter { it.pageNo == 3L }.map { it.text })
    assertEquals(3, yi.count { it.role == DbValues.ROLE_TOC })            // 页3 全 TOC
    assertTrue(jia.none { it.text.isEmpty() } && yi.none { it.text.isEmpty() })
}
```
覆盖守卫数值（终值，一次算定，空格逐个计入）：底稿 4 段各 3 字 → bookChars=12；
替换页 {2,3} 底稿段C+段D → replacedDbChars=6；视觉字符=「甲二视觉段」5+「甲二脚注」4+
「目 录」3+「第一章 概述 .... 4」13+「第二章 保证 .... 6」13=38 → replacedVisionChars=38；
expected=12−6+38=44；输出段长度=3+3+5+4+3+13+13=44；coverageOk(44,44)：
44×5=220 ≥ 44×4=176 ✓ 过。**口径声明**：①assertEquals 中的全部字符串是测试自身构造的
**构造值**（数据先于断言写入缓存/库，非外部读取），断言与构造同源、必然逐字符成立；
②上述字符计数仅用于 coverage 公式余量的预先复核（两者独立，计数误差不影响任何断言）。
全量闸门：`cd android && ./gradlew test` 0 失败（既有 695+新增净 3 例全绿）。

## §4 A2 重跑执行手册（step0-8 完整命令；Bash=Git Bash on Windows，本机即开发机）

errata 摘要（C3a 修复包执行期登记，沿用）：adb push 目标路径必须加
`MSYS_NO_PATHCONV=1` 前缀；网络检查用 `dumpsys connectivity`（无 dumpsys network 服务）；
uiautomator dump 间歇空/陈旧→优先 `adb exec-out screencap -p > 文件` 截图目视；
导入 UI 三步=书架 FAB(≈953,1937)→「导入书籍」表单「选择 TXT / PDF 文件」按钮(≈271,790)
→系统 SAF。拉库三件套=study_friend.db、-wal、-shm 三文件分别
`MSYS_NO_PATHCONV=1 adb exec-out run-as com.studyfriend.app cat databases/<名>` 拉本地。

**工具接口契约·saf_import.py**（源码 .e2e/p6c/saf_import.py，66 行，pick=wait_and_tap
轮询 uiautomator dump 点选，confirm=两段按钮）：用法仅两种——
`python saf_import.py pick <文件名关键字>`（前置：调用方已把 SAF 文件选择器打开在
目标目录；在 15 轮×4s 内点中含关键字的文件项；成功 stdout=`pick 完成` exit 0，失败
AssertionError `SAF 未找到 <kw>` exit 非 0）；`python saf_import.py confirm`（OCR 完成
识别结果页出现后调用：段 1 等「下一步」按钮（15 轮×6s）→段 2 等「完成导入」
（15 轮×4s）；成功 stdout=`confirm 完成` exit 0，段 1 超时报「段1确认按钮未现（OCR
未完成？先跑 logcat 轮询）」、段 2 超时报「段2完成按钮未现」，均 exit 非 0）。
**工具接口契约·check_session_close.py**（收尾自检，本机 Windows 路径）：三项检查=
①模块总览时效（动过的模块总览 mtime 不旧于代码，check_modules 0 过期）②git 工作区
干净或 --allow-wip 带原因 ③敏感信息（无明文密钥/密码，>5MB 大文件记黄档）；
输出三项清单+退出码 0=全绿。

```bash
# step0 样书在位（4.8MB，Oct 5 验证在位；缺则重推）
MSYS_NO_PATHCONV=1 adb shell ls -l /sdcard/Download/sample_book_scanned.pdf \
  || MSYS_NO_PATHCONV=1 adb push .e2e/sample_book_scanned.pdf /sdcard/Download/
# step1 单测闸门（§3 全绿才继续；期望 BUILD SUCCESSFUL 0 failed）
cd android && ./gradlew test; cd ..
# step2 打包+覆盖安装（既授权流程，C3a 修复包同款第 3 次；期望 Success）
cd android && ./gradlew assembleDebug; cd ..
MSYS_NO_PATHCONV=1 adb install -r android/app/build/outputs/apk/debug/app-debug.apk
# step3 冷启动+logcat 落盘（新文件 a2_logcat.txt，勿混 C3a 旧录）
MSYS_NO_PATHCONV=1 adb logcat -c
(adb logcat > .e2e/p6c/a2_logcat.txt &)
MSYS_NO_PATHCONV=1 adb shell am start -n com.studyfriend.app/.MainActivity
# step4 UI 三步导入（截图目视定位；SAF 打开后）
python .e2e/p6c/saf_import.py pick sample_book_scanned      # 期望输出：pick 完成
# step5 等 OCR（40×20s≈13min 窗沿；超时即停）
for i in $(seq 1 40); do grep -q "Pass2 done" .e2e/p6c/a2_logcat.txt && break; sleep 20; done
grep -q "Pass2 done" .e2e/p6c/a2_logcat.txt || { echo "OCR TIMEOUT"; exit 1; }
grep -oE "Pass2 done: pages=[0-9]+ fallbacks=[0-9]+ byReason=\{[A-Z_]+=[0-9]+\} fallbackPages=[0-9,]+" .e2e/p6c/a2_logcat.txt
#   J5a 期望恰为：Pass2 done: pages=22 fallbacks=3 byReason={LOW_CONF=3} fallbackPages=3,4,5
#   （ML Kit 本地推理同输入同输出，两次历史跑均 3,4,5；漂移→预案 G7）
python .e2e/p6c/saf_import.py confirm                        # 期望输出：confirm 完成
# step6 等重建（队列 3 页缓存 miss ≈45s；8×15s=2min 窗沿）
for i in $(seq 1 8); do grep -q "(preserved)" .e2e/p6c/a2_logcat.txt && break; sleep 15; done
# step7 拉库三件套+BOOKID
MSYS_NO_PATHCONV=1 adb exec-out run-as com.studyfriend.app cat databases/study_friend.db > .e2e/p6c/a2.db
MSYS_NO_PATHCONV=1 adb exec-out run-as com.studyfriend.app cat databases/study_friend.db-wal > .e2e/p6c/a2.db-wal
MSYS_NO_PATHCONV=1 adb exec-out run-as com.studyfriend.app cat databases/study_friend.db-shm > .e2e/p6c/a2.db-shm
DB=.e2e/p6c/a2.db
BOOKID=$(sqlite3 "$DB" "SELECT max(id) FROM books;")
[ -n "$BOOKID" ] || { echo "BOOKID EMPTY"; exit 1; }
echo "BOOKID=$BOOKID   # 期望 8"
# step8 J5 断言块（每条同行标注期望；任何 test 失败即 exit 1）
sqlite3 "$DB" "SELECT status,count(*) FROM vision_queue WHERE bookId=$BOOKID GROUP BY status;"
#   J5b-1 期望唯一行：DONE|3
test "$(grep -c "VisionWorker: run bookId=$BOOKID" .e2e/p6c/a2_logcat.txt)" -le 2 || { echo "J5b-2 FAIL"; exit 1; }   # J5b-2 ≤2
# 动态期望（视觉非确定性对策）：基线未替换页合计 + 当次视觉字符；14772 不硬编码
BASE=$(sqlite3 .e2e/p6c/c3a_after.db "SELECT coalesce(sum(length(p.text)),0) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=6 AND p.pageNo NOT IN (3,4,5);")   # 期望 14772
HASH=$(MSYS_NO_PATHCONV=1 adb shell run-as com.studyfriend.app ls -t cache/ | grep -oE 'vision_-[0-9]+_3\.json' | head -1)
MSYS_NO_PATHCONV=1 adb shell run-as com.studyfriend.app cat "cache/$HASH" > .e2e/p6c/a2_p3.json
VC=$(python -c "import json;d=json.load(open('.e2e/p6c/a2_p3.json',encoding='utf-8'));print(sum(len(x) for x in d['body'])+sum(len(x) for x in d.get('footnotes',[])))")
P3N=$(python -c "import json;d=json.load(open('.e2e/p6c/a2_p3.json',encoding='utf-8'));print(len(d['body']))")
EXPECT=$((BASE + VC))
echo "BASE=$BASE VC=$VC P3N=$P3N EXPECT=$EXPECT"   # 参考值：BASE=14772 VC≈512 P3N≈8（视觉非确定，以当次为准）
grep -F "rebuild bookId=$BOOKID pages=22 chars=$BASE→$EXPECT chapters=11 (preserved)" .e2e/p6c/a2_logcat.txt || { echo "J5c FAIL"; exit 1; }   # J5c
test "$(sqlite3 "$DB" "SELECT count(*) FROM chapters WHERE bookId=$BOOKID;")" = 11 || { echo "J5d-1a FAIL"; exit 1; }   # J5d-1a =11
test "$(sqlite3 "$DB" "ATTACH '.e2e/p6c/c3a_after.db' AS bl; SELECT (SELECT group_concat(title,'|') FROM (SELECT title FROM chapters WHERE bookId=$BOOKID ORDER BY idx)) == (SELECT group_concat(title,'|') FROM (SELECT title FROM bl.chapters WHERE bookId=6 ORDER BY idx));")" = 1 || { echo "J5d-1b FAIL"; exit 1; }   # J5d-1b =1（含乱码标题逐字存活）
test "$(sqlite3 "$DB" "SELECT sum(length(text)) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=$BOOKID;")" = "$EXPECT" || { echo "J5d-2 FAIL"; exit 1; }   # J5d-2 =EXPECT
test "$(sqlite3 "$DB" "SELECT count(*) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=$BOOKID AND p.pageNo=3;")" = "$P3N" || { echo "J5d-3a FAIL"; exit 1; }   # J5d-3a =P3N
test "$(sqlite3 "$DB" "SELECT count(*) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=$BOOKID AND p.pageNo=3 AND role!='TOC';")" = 0 || { echo "J5d-3b FAIL"; exit 1; }   # J5d-3b =0
test "$(sqlite3 "$DB" "SELECT count(*) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=$BOOKID AND text='';")" = 0 || { echo "J5d-4 FAIL"; exit 1; }   # J5d-4 =0
test "$(sqlite3 "$DB" "SELECT (SELECT count(*) FROM chapters WHERE bookId=6)||'/'||(SELECT count(*) FROM paragraphs WHERE chapterId IN (SELECT id FROM chapters WHERE bookId=6));")" = "1/9" || { echo "J5e-1 FAIL"; exit 1; }   # J5e-1 =1/9
test "$(sqlite3 "$DB" "SELECT count(*) FROM chapters WHERE bookId=7;")" = 5 || { echo "J5e-2 FAIL"; exit 1; }   # J5e-2 =5（立案证据保全）
diff <(sqlite3 "$DB" "SELECT p.pageNo, sum(length(p.text)) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=$BOOKID AND p.pageNo NOT IN (3,4,5) GROUP BY p.pageNo ORDER BY p.pageNo;") \
     <(sqlite3 .e2e/p6c/c3a_after.db "SELECT p.pageNo, sum(length(p.text)) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=6 AND p.pageNo NOT IN (3,4,5) GROUP BY p.pageNo ORDER BY p.pageNo;") || { echo "J5f FAIL"; exit 1; }   # J5f diff 空=未替换页逐页相等
test "$(sqlite3 "$DB" "SELECT c.title FROM chapters c JOIN paragraphs p ON p.chapterId=c.id WHERE c.bookId=$BOOKID AND p.pageNo=3 LIMIT 1;")" = "开篇" || { echo "J5f-2 FAIL"; exit 1; }   # J5f-2 =开篇（D2 floor 实证）
echo "J5 ALL PASS"
```
**J5e-1 期望值 1/9 的出处说明（非矛盾）**：活设备库中的 bookId=6 是 C3a 原跑**被 P0 bug
洗书后的残存态**（1 章 9 段），实测于 .e2e/p6c/db_pull2.db（2026-10-06 18:20 拉取）SQL
`SELECT count(*) FROM chapters WHERE bookId=6;`→1、段数→9；C3a 修复包全程未写 bookId=6
（其 J4 断言即验证此点，两轮实测值恒 1/9）。11 章基线是**另一存储**——快照文件
.e2e/p6c/c3a_after.db（2026-10-06 16:01 生成，原跑导入完成态），仅作 J5d-1b/J5f 比对源。
故 J5e-1 验证「旧证据未动」期望=1/9 正确，与 J5d-1b 的 11 章不冲突（不同库）。
（页 4/5 历史两跑均空转写 26B——不产生段落；若当次非空，VC 公式扩为三页 JSON 求和、
J5f 替换页集如实代入。断言块跑完保留 .e2e/p6c/a2.db 作本轮证据快照。）

## §5 风险预案（触发判据客观 + 可执行回退）

| # | 风险 | 触发判据 | 可执行预案 |
| --- | --- | --- | --- |
| G1 | D1 跨章页归章失当 | J5f diff 非空 或 J5f-2 败 | ①保留现场：`cp .e2e/p6c/a2.db .e2e/p6c/a2_g1_evidence.db`；②回滚：`git checkout 2336eff -- android/app/src/main/java/com/studyfriend/app/data/vision/VisionRebuilder.kt`（**2336eff=C3a 修复定稿 commit，2026-10-06 已锁定**，`git log --oneline -1 2336eff` 可复核）；③以旧实现重跑一轮取对照数据；④两轮数据并报老板裁定，不自动放宽 |
| G2 | 缓存部分 miss | 日志出现 `rebuild partial: cache miss page=` | 语义合法不失败；按实际命中页集重算 VC 与 J5f 替换页集后对账；全 miss（replaced=0）→ 重建自动放弃（既有守卫），不立案 |
| G3 | J5 断言败（代码 Bug） | 任一 test 失败 | ①同 G1-① 留证；②`git diff` 留存当前实现；③回滚 VisionRebuilder.kt 至 C3a 修复版恢复可用态；④立案（断言输出+diff 附案卷）按老板闸门修复重跑本步 |
| G4 | replaceBookContent 签名与假设不符 | CodeGraph 核证不符 | 先修订本计划 §2.4 再落码，阻断盲写（核证动作：`codegraph explore "replaceBookContent BookRepository"`） |
| G5 | 0 段章输出 | J5d-1b 含 0 段章 | 设计即保留全部章（§2.4 pairs 遍历 byBook 全量），属预期；J5d-1b 败=落码 Bug 走 G3 |
| G6 | level=2 节行书 | `chapters.any { it.level != 1 }` | 唯一路径=跳过重建+`Log.w("rebuild skip … level-2 sections present")`（不降级子集重建——部分重建会破坏节行挂接一致性）；登记 M2 待办 |
| G7 | OCR fallback 页集漂移 | J5a 行 fallbackPages≠3,4,5 | ML Kit 本地推理同输入同输出、两历史跑均 3,4,5；若漂移：替换页集代入 J5f 的 NOT IN 集合与 VC 公式（拉对应页 JSON），阈值零改动 |

## §6 成本申报（口径对齐）

- 模拟器机时：落码+单测 19min + 打包安装 4min + 重导 OCR **13min**（step5 窗沿
  40×20s，历史实测 OCR ≈5min18s，窗沿为超时上限）+ 队列消化重建 2min + 拉库断言
  4min = **42min**（对比：C3a 修复包 17min、C3b shpc 95min）。
- 商汤调用：竖排目视 3 次（导入固定成本）+ 队列 3 页×1 次（SAF uri 每次全新→缓存必
  miss，实证 hash -34899318≠-6b997af4）≈6 次/轮，与 C3a 重跑同量级无增量。
- LLM 评分预算（.e2e/p6c/score_review_usage.jsonl 实录，est_tokens=字符×1.2 估算，
  上游 chat() 不暴露真实 usage——**标注：估算值 UNMEASURED 口径**）：v1 输入 5543 字符
  ≈6651 tok、v2 输入 19735 字符 ≈23682 tok、v3 输入 24864 字符 ≈29836 tok；输出
  （评审 JSON+意见）各 ≈1-1.5K tok。**无截断风险实证**：v3 评审逐字引用了文档末尾
  §8 表格内容=全文入上下文；输出侧每轮 <2K tok 远低于输出上限。**本 v4 为第 4 轮，
  最多再 1 轮（第 5 轮），仍 <90 即按 5 轮纪律线上报老板拍板，不无限循环**（超支停点；
  累计评分成本 5 轮上限 ≈150K tok 估算）。
- 代码量：VisionRebuilder.kt 净约 −40/+75 行；测试 +4 例 −1 例。

## §7 合规闸门（收尾清单，全勾才宣告完成）

- [ ] 覆盖安装=既授权流程（C3a 修复包同款第 3 次执行）；若需卸载重装（清 App 数据）
      属危险操作，须老板人话确认后执行。
- [ ] DB 写仅发生于新书 bookId（守卫 1-5+G6 保护旧书）；不手动改库；J5e-1/2 断言旧书
      未动。
- [ ] 凭据纪律：商汤 key 只存 App 设置库（C1 已配），不出现在日志/计划/代码/汇报；
      缓存文件名仅含 uri hash 无凭据。
- [ ] 模块总览：改完代码更新 `docs/模块总览/视觉兜底模块总览.md` 与
      `docs/模块总览/README.md` 索引行（实际文件名以该 README 现存条目为准，C5 收口
      前完成，本计划不单独宣称完成）。
- [ ] KDoc：VisionRebuilder 类注释随落码重写（R1 引用+D1/D2 拍板值）。
- [ ] 完成前三查 `python "C:/Agent/AImanager/tools/check_session_close.py" "C:/AIWorkSpace/Studying-with-friend"`
      （本工具为本机 Windows 专用，三项全绿；Unix 环境无此工具，本项目开发机即本机）
      期望输出三查全过 0 红；.e2e/ 不入库；push 前人工过 diff。

## §8 证据附录（口径统一：文件+SQL 全文/日志行原文；快照标注生成时刻）

| 数值/论断 | 出处（文件:时刻或 SQL 全文） | 标注 |
| --- | --- | --- |
| pages=22 fallbacks=3 LOW_CONF=3 fallbackPages=3,4,5 | .e2e/p6c/c3a_rerun_logcat.txt（2026-10-06 10:09-10:17 录制）行原文 `10-06 10:14:52.183 18121 18177 W OcrImport: Pass2 done: pages=22 fallbacks=3 byReason={LOW_CONF=3} fallbackPages=3,4,5 fragmentLinesMerged=0 pass2Ms=13` | MEASURED |
| 底稿输入 14772 字 | 同文件行原文 `10-06 10:16:33.040 18121 18639 I VisionRebuilder: rebuild bookId=7 pages=22 chars=14772→15245 chapters=5` 左侧值；与 SQL `SELECT sum(length(p.text)) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=6;`（库=.e2e/p6c/c3a_after.db，2026-10-06 16:01 生成）=14772 一致 | MEASURED |
| 重跑产物 15245 字/5 章/页3=8 段全 TOC/页4,5 零字 | 库 .e2e/p6c/db_pull2.db（2026-10-06 18:20 拉取）SQL：`SELECT count(*) FROM chapters WHERE bookId=7;`→5；`SELECT sum(length(text)) …bookId=7;`→15245；`SELECT count(*),sum(role='TOC') …AND p.pageNo=3;`→8,8 | MEASURED |
| 缓存 miss（uri 变→必 miss） | 设备 cache listing：`vision_-34899318_3.json` 682B(mtime 10:16) vs `vision_-6b997af4_3.json` 259B(mtime 08:15)；页 4/5 两 hash 均 26B | MEASURED |
| 目录条目带点线+尾页码（形态反转） | db_pull2.db SQL `SELECT p.idx,p.text …AND p.pageNo=3 ORDER BY p.idx;` 八行原文（`目 录第一章 担保法概述 …(52 点)… 4` 等） | MEASURED |
| 11 章基线全 level=1、无独立第X章段、标题乱码原文、跨章页=0 | c3a_after.db bookId=6：`SELECT level,count(*) FROM chapters WHERE bookId=6 GROUP BY level;`→1\|11；`SELECT count(*) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=6 AND p.text LIKE '第_章%';`→0；`…LIKE '参考文献%';`→0；跨章页 SQL（§2.3 D1）→空集；章标题清单含「第一章拍保法榔述」 | MEASURED |
| 15284 = 14772 + 512 | 推导式：14772（MEASURED，基线合计）+ 512（页 3 当次转写字符，MEASURED，db_pull2.db 页 3 sum(length)=512）=15284（INFERRED；A2 重跑以 §4 step8 `EXPECT=$BASE+$VC` 动态重算覆盖，不硬编码） | INFERRED |
| 队列消化 43698ms（miss 变体） | c3a_rerun_logcat.txt `10:15:49.352 VisionWorker: run bookId=7 pending=3` → `10:16:33.050 … Worker result SUCCESS` 时间差（J3b-计时脚本实测输出） | MEASURED |
| D1/D2 页归属规则、coverageOk 80%、TOC_INFER_MIN=3 | 本计划 §2.3-§2.4 与 VisionRebuilder KDoc；方向 A 经老板 2026-10-06 AskUserQuestion 明确选「A 结构保全重建（推荐）」 | DECLARED |
