# P3a 小计划案：章节判定升级——字号证据链（OPT-G P3a）v6.1（评审 90 分通过）

> **v6.1 = v6 + 评审附带 4 条非阻塞修正（实施时执行）**：
> ① post-condition 用 `if (!cond) throw IllegalStateException(...)` 而非 `check()`
> （Kotlin check 抛 IllegalArgumentException，类型不匹配）；
> ② 补单测 12：小字号锚点（size=9f < 阈值）全链路 TocRegion 仍命中——验证 TOC 检测
> 不依赖 styleHint=true；
> ③ bodySize=NaN 防御声明：`size >= NaN` 恒 false（IEEE 754）=安全退化等价
> styleAware=false，比较运算符不得改为 `>`/`!=`；
> ④ Para 为管线内存数据类（持久化走 Entities.kt 的 Room 实体，ParsedPara→Entity
> 转换不含 size），加字段**无需 Room migration**；
> 沿革：v2 拆 TOC 反哺；v3 补数据流；v4 补反面推演/回退验证；v5 合并门禁/post-condition；
> v6 补推导/失败模式表/调用点清单。评分轨迹：72→83→86→89→86→90。

> 总体计划案 v3（93 分）P3a 定义：修 F6（章节判定无几何证据）。
> P1/P2 已闭环（P2 v3.2 复核 91 分，commit d518ff5）。
> **v6 变更（吸收 AI 评审 v5→86 分 10 条）**：阈值 1.5f 推导与收敛标准、扩样判据
> 与阈值关系、取 max 策略失败模式对比表、Para.size 单位 KDoc、单测 10 具体化为
> PdfCleaner 字号填写断言与单测 7 互补、验收 F 排除字段显式列表+SQL、过渡日志
> 填补拦截盲点、回退矩阵补用户缓解列、调用点 grep 范围扩展（实测清单）、
> 勘误独立 commit。
> 沿革：v2 拆 TOC 反哺；v3 补数据流；v4 补反面推演/回退验证；v5 合并门禁/post-condition。

## 1. 问题与实测证据

### 问题（F6 假章）
真书 13 章中的「第十二章权利的行使」实为版权页 OCR 行（pymupdf 实测 10.4pt、
HiddenHorzOCR 层），与正文同字号，被 A 档正则（`^第[一二三…]+章`）误判成章。
OPT-D 三护栏（>24 字/行中句读/章+节同行）拦不住它——它 9 字、无句读、无节号。

### 字号层级实测（2026-10-02，pdfplumber/pymupdf）

| 书 | 章标题 | 节标题 | 副标题 | 正文 | 页眉 | 脚注 |
| --- | --- | --- | --- | --- | --- | --- |
| 仿真书 | **18pt SimHei** | 13.5 | — | 10.5 SimSun | 9 | — |
| 真书 630 页 | **19pt SimSun** | 13.5 Bold | 13.5 仿宋 | 10.5/10 | 9 | 7.5 |
| 真书假章 | 10.4pt | — | — | — | — | — |

### 阈值推导与收敛标准

- **实测下界**：真章−正文字号差最小值=7.5pt（仿真书 18−10.5）。阈值必须 <7.5pt 才能
  确认真章。
- **取值推导**：阈值须高于字号测量噪声（docStats 众数误差实测 ±0.5pt，取 3 倍安全
  边际 ≈1.5pt），同时为更紧凑排版的书留出空间（7.5−1.5=6pt 下降空间）→ **取
  `bodySize + 1.5f`**，比较用 `>=`。
- **最小可确认差值**：真章 ≥ body+1.5pt 即确认；差值 <1.5pt 的紧凑排版书=漏确认
  （与现行为一致，无回归）。
- **bodySize 偏差敏感性**：偏差 ±2pt 使阈值在 10.5–13.5pt 浮动，真章 18–19pt 始终高于
  上限，不会误拒真章；偏差只可能让大字正文行（如 12pt 副标题）多打前缀——正则不
  命中即无影响。
- **OCR 字号误差（残余风险）**：假章 10.4pt vs 正文最小 10pt 差 0.4pt；若 pdfbox 对
  HiddenHorzOCR 页字号报错偏高 ≥+1.6pt 则漏拦。失败模式=维持现状（误差只影响
  「拦不拦」，不错拦）；根治归 P4 视觉兜底整页替换。
- **不对称风险**：误拦=该行降为正文（盲切/软合并兜底），比假章（错切整章、污染后续
  章号）轻得多——保守方向正确。
- **收敛标准（#P3a-followup）**：≥5 本不同排版风格书上实测均满足「真章−正文差 ≥
  2×阈值（3pt）」→ 阈值转稳定；否则按数据调整并在计划案补记推导。

### 扩样验证（**合并门禁**）

- **最低门槛（阻塞合并）**：≥1 本公开来源 PDF（下载页书源或任意公开书）完成三指标
  取证：bodySize 众数、真章最小字号、正文最大字号；目标 ≥3 本。
- **通过判据**：每本「真章−正文」字号差 ≥3pt（=**2×阈值**，余量覆盖 bodySize 估算
  偏差 ±0.5pt 与扩样本的 OCR 误差，确保判据通过即阈值在该书可用）→ 阈值方向得到
  第三方印证；<3pt 的反例记录归 #P3a-followup（P3a 不因此引入黑体形态判据或调整
  阈值——反例书失败模式仍是漏确认=维持现状）。

## 2. 方案：字号证据传递链（协议前缀，复用〔脚注〕先例）

1. `Para` 加 `val size: Float = 0f`（**参数列表末尾**，默认参数不影响既有位置构造）。
   KDoc 注明单位与语义：`/** 段内最大字号，单位 pt（磅），与 PLine.size 一致；0f=未知
   （不打前缀、不拦任何命中） */`。**0f 语义=字号未知 → 不打前缀 → 不拦 A/B 命中 →
   与 P2 行为一致（安全方向：只会漏确认，不会误拦）**，与 PLine.size ≤0=未知既有
   语义对齐。**生产构造点强制命名参数 `size =`**（编译期自文档+防参数顺序漂移）；
   测试 fixture 维持默认参数。实施时 grep `Para(` 全仓核对无其他生产构造点。
2. `ParagraphAssembler.flush` 记录段字号：**取段内最大字号**（`curMax`，遇更大字号行
   更新；初值 0f 仅作下界）。**策略失败模式对比**：

   | 策略 | 正常路径（断段生效） | 极端路径（标题+正文误接成段） |
   | --- | --- | --- |
   | 取 min | 与 max 无差别 | 段 size=10.5 → 不打前缀 → 漏确认（安全但丢信号） |
   | 取 max | 与 min 无差别 | 段 size=19 → 打前缀 → 正则不命中即无影响 |
   | **选 max** | — | 极端路径保留章信号（min 丢信号）；且断段失效已被单测 9 锁定为不发生 |

   前提声明：进入组装器的行已经过 PdfCleaner 删页眉/页码，均为正文区域行；若未来
   清洗管线变更让非正文行进入组装器，需重新评估。行内聚类（P1）按同 y 聚 span，
   19pt 标题与 10.5pt 正文不同 y 不会聚成一行。
3. `PdfCleaner.clean` 目录页直通分支同步填字号：锚点行「目 录」（仿真书实测 16pt）
   必须带证据，否则 styleAware 会误拦 A_FIXED 锚点、目录区识别回归。
4. `PdfLoader.assembleText(styleAware: Boolean = false)`：bodySize 数据流=该方法本就
   是 `PdfExtractResult` 成员方法，`stats: DocStats` 是既有属性，直接读 `stats.bodySize`。
   styleAware=true 时给 `size >= stats.bodySize + 1.5f` 的非脚注段打前缀 `〔标题〕`
   （`BookParser.TITLE_SIZE_MARK`）；false 时与现在逐字节一致。前缀打在段首、每段至
   多一个，与 FOOTNOTE_MARK 同构。浮点策略：`val threshold = stats.bodySize + 1.5f`
   只计算一次，单次加法无双精度累加误差。
5. `BookParser.parse(raw, customTitleRegex, styleAware: Boolean = false)`：
   - **styleHint 数据结构**：parse 局部 `List<BooleanArray>`（按块索引、块内按行索引），
     不改公共类。**索引对齐保证**：与 splitBlocks 产物同构（每块每行一一对应）；
     剥前缀发生在行预处理阶段、**先于 trim 与正则匹配**（`// 必须在 trim 之前剥除，
     否则行首空格导致前缀不匹配`）。
   - A/B 档命中（findTitleHits→matchTitle 层，TitleHit 自带 blockIdx+lineIdx 可查表）
     且该行 styleHint=false 且 styleAware → 不产出 TitleHit（拦假章）；
   - C 档不设字号门槛（真书 C 档序号小标题与正文同字号 10.5pt，实测）；
   - custom 路径不拦（用户手工重切优先）；
   - styleAware=false（TXT）完全保持现行为；
   - **过渡日志（填补拦截盲点）**：styleAware=true 且发生拦截时
     `println("P3a styleGuard blocked: ${title.take(20)}")`（logcat 可查，1 行代码，
     #P3a-followup 的计数器落地后删除）——合并期误拦/漏拦不完全不可见。
   - **前缀泄漏 post-condition**：parse 返回前
     `check(chapters.all { c -> c.paras.none { p -> p.text.lineSequence().any { l -> l.trimStart().startsWith(TITLE_SIZE_MARK) } } })`
     ——泄漏运行时抛 IllegalStateException 而非静默污染阅读文本。FOOTNOTE_MARK 不
     纳入：目录页单块多段场景下块中行首〔脚注〕是既有已验收行为。
6. 接线（**调用点实测清单**，grep `assembleText(`/`parse(` 全 main 源码确认）：
   - 生产调用点共 2 处，均在 `ImportViewModel`：
     `:167 result.assembleText()` → `assembleText(styleAware = true)`（PDF 分支唯一
     调用点）；`:403 BookParser.parse(text, currentRegex)` →
     `parse(text, currentRegex, styleAware = isPdf)`（PDF/TXT 共用，**isPdf 为 VM
     既有字段，与 167 行同源**，天然同步）。
   - TXT 路径不经过 assembleText（TextLoader 直出），parse 收到 isPdf=false →
     styleAware=false，行为不变。
   - 测试调用点：单测显式传参，不受影响。

### 回退矩阵（styleAware 即开关，无需新开关）

| 场景 | 行为 | 用户缓解 |
| --- | --- | --- |
| styleAware=false（TXT/回退） | 与 P2 现行为一致（验收 F 结构化验证）；已知代价=假章回归（P2 已知状态），紧急回退目的是止新损；不做书名硬编码黑名单 | 确认页点击假章改名/删除；或 custom 规则排除（如 `^第(?!十二章)` 手工正则） |
| `〔标题〕`前缀泄漏 | 阅读文本污染，比假章更严重。防护=单测 1/5 + 前缀只在 styleAware=true 打 + parse 末尾 post-condition 运行时强制 | post-condition 抛错=导入失败提示，不产生污染文本 |
| 正文以「〔标题〕」开头 | 剥前缀、styleHint=big，正则不命中即无影响；正则恰命中（概率极低）与 P2 假章同级（同 FOOTNOTE_MARK 先例） | 同上，确认页可改 |
| 坏字体页字号报错值 | P1 抗坏值重估兜底；字号证据只作确认信号，最坏退化为现行为；过渡日志可见 | — |
| 单点回退 | 独立 commit 可 revert；代码级回退=VM 改回 `assembleText()` 一行 | — |

**styleAware 用途唯一性声明**：P3a 中 styleAware 唯一用途=字号门槛，false 即完整
回退（等价 P2）。若 P3b 复用该参数增加新用途，必须拆分为独立开关。

## 3. 测试与验收

### 数据与基线来源（本机单机验收，非 CI 资产）

- 仿真书 PDF=项目根 `sample_book.pdf`（已在仓库）；真书 630 页 PDF=
  `C:\AIWorkSpace\.e2e\mfzz.pdf`（本机留存）。
- 基线快照 `.e2e/live/p2fix_book.db`（commit d518ff5 断网产物，gitignore 本机留存、
  不入库）：生成操作确定性强=固定 PDF + 固定代码 commit + 断网导入。
- 单测全部纯 JVM 可跑（整链除 PdfLineExtractor 外均为纯 Kotlin）。

### 单测（BookParserTest + PdfCleanerTest + ParagraphAssemblerTest 追加）

1. `〔标题〕`前缀剥除 + big 证据确认 A 档（styleAware=true，19pt 段）。**层级：
   BookParser 纯单测**。
2. A 档命中无 big 证据 → 降为正文。**层级：纯 JVM 集成小链路**——PLine 全参
   `PLine(text="第十二章权利的行使", x0=0f, x1=200f, y0=100f, size=10.4f)` → 组装 →
   `assembleText(styleAware=true)` → `parse(styleAware=true)`，断言 role=BODY。
3. **阈值边界（浮点安全）**：bodySize=10.0f 时 size=11.5f（二进制精确可表）→ 证据
   生效；size=11.4f → 不生效。锁定 `>=` 语义。
4. C 档无字号证据仍生效（10.5pt「1.XXX」行）。
5. styleAware=false（TXT）A 档无前缀照常生效 + assembleText(styleAware=false) 输出
   无 `〔标题〕` 前缀。
6. custom 正则路径不受字号门槛拦截。
7. 锚点全链路不回归：`PLine(text="目 录", x0=0f, x1=100f, y0=50f, size=16f)` →
   组装 → assembleText(styleAware=true) → 断言段以 `〔标题〕` 开头 →
   parse(styleAware=true) → 断言目录区识别仍命中（ROLE_TOC 产出）。
8. 多行大字标题（「第一章」+「私法绪论」两行 19pt 合并一段）段 size=19（组装器侧）。
9. 标题行后接正文行：字号跳变断段生效，标题段 size=19、正文段 size=10.5。
10. **目录直通段字号填写**（PdfCleanerTest，与单测 7 互补——7 验全链路、10 验
    PdfCleaner 层填写）：目录页直通分支产出的 Para.size==16f（锚点行）。
11. 前缀泄漏 post-condition：构造绕过剥除路径的输入（手工拼前缀+styleAware=true），
    断言抛 IllegalStateException。

### 验收（**飞行模式断网对照**，沿用 P2 方法论）

- A. 单测全绿（存量 333 + 新增）。
- B. 仿真书 PDF 路径断网重导：8 章标题 8/8 不回归，且 DB 无新增误判章。
  **勘误（2026-10-02 实测后）**：「DB 章数=8」为计划笔误——仿真书含既有固定词章「开篇」「参考文献」（`FIXED_WORDS` 既有功能，P2 截图 09_parsed.png 为证：P2 基线即「10 章 96 段」）。实测判据修正为「与 P2 基线逐字一致=10 章 96 段、8 个第X章真章不回归」，P3a 实测达成。
- C. 真书 630 页断网重导：假章「第十二章权利的行使」消失（13→12 章，拦截生效的
  间接证明；logcat 过渡日志可见被拦行=假章）；**真章不劣化**=12 个真章标题与基线
  逐字一致（trim+去空白后相等）；**段落标准**=正文段落内容与顺序逐段一致，预期唯一
  差异=假章消失、其段落内容不变并入前一章末尾（前一章末尾段数=基线+假章节段数）；
  **总字数差=0**；其他差异逐处归因后方可通过。
- D.（**合并门禁**，§1）≥1 本公开 PDF 三指标取证记录于计划案附录；反例归
  #P3a-followup。
- E. E2E 全流程：导入→确认页章列表截图核对→DB 章标题比对。
- F. **回退路径 E2E**：VM 临时改回 `assembleText()`（styleAware=false）重导真书，
  **结构化数据一致**——导出 SQL 口径显式化：
  ```sql
  SELECT c.title, p.idx, p.text, p.role FROM chapters c JOIN paragraphs p
   ON p.chapterId = c.id WHERE c.bookId = :bid ORDER BY c.idx, p.idx;
  ```
  仅对比 title/text/role/idx 序列（**排除全部元数据字段：id、bookId、chapterId、
  createdAt/updatedAt、sourceUri、file 相关字段**），与基线快照同口径导出 diff 为空。

### 已知边界（记入模块总览）

- 字体名形态判据未实现；HiddenHorzOCR 页整页处理归 P4 视觉兜底。
- 样本量 2 本 + 门禁扩样 ≥1 本（目标 ≥3）；OCR 误差 ≥+1.6pt 漏拦为残余风险；
  1.5pt 为临时阈值（收敛标准见 §1）；拦截计数器归 #P3a-followup（过渡日志临时补位）。
- **Para.size 演进路径**：当前为二值信号载体；P3b 若需多级样式需扩展结构并同步
  styleHints 数组类型。

## 4. 与后续计划的分工

- **P3a2（拆出，暂缓）**：文字层 TOC 反哺校验——待 P3b 视觉目录设计后协同评估。
- **P3a-followup（拆出）**：拦截计数器+阈值收敛跟踪——P3b observability 统一实施。
- **P3b**：视觉目录驱动章节树（兜底路径：文字层解析效果差时用视觉模型识别目录页）。
- 总体计划案勘误（「错误数 ≤167」→「假章 1→0 且真章不劣化」）：**独立 commit**
  （`docs(plan): 勘误`，与 P3a 功能 commit 分离、可独立 revert）。
