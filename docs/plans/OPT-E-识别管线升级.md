# OPT-E 识别管线升级计划案：布局感知提取 + 页面清洗 + 龙猫视觉兜底

## 背景

老板反馈"识别还是非常有问题"。现状：pdfbox 纯文本提取（无坐标无字号）→ BookParser 按空行分块。
真书实证（pymupdf 侧分析，2026-10-01）：

| 书 | 页数 | 文字层 | 主要脏点 |
| --- | --- | --- | --- |
| 民法总则 2022 重排版（王泽鉴） | 630 | 完整（PUA=0，5 空页），**页面 rotation=90** | ① 每页页眉"笫一章私法绪论 29"混进正文（"第"被坏字体映射成"笫"，纯文本 regex 挡不住，但页眉字号 7.9~8.0 vs 正文 9.1~9.7）；② 页码独立行混入段落；③ 无段落结构（无空行 → 整页缩成 1~2 个巨段）；④ 正文圈码 ①② 被坏映射成"心/＠"（规则修不了，接受残留） |
| 民法物权 第2版（王泽鉴） | 577 | 完整（PUA=0，6 空页 12 短页），rotation=0 | 同类：页眉/页码/段落粗 |

另一根因：**扫描版 PDF 直接拒收**（平均每页 <100 字抛"扫描版无法导入"），而视觉转写可救。

经验来源：《PDF 识别管线经验帖》（论文项目 37 篇语料实战复盘，老板提供）。论文 vs 书籍差异按其 §11 适配：书籍单栏为主（两本真书均单栏）、页眉随章节变（重复检测降级为辅助、条带+字号为主信号）、扫描书页数大（上限控制 + 进度取消）。

## 龙猫视觉实测（2026-10-01，OpenAI 兼容接口）

- 平台：`https://api.longcat.chat/openai/v1`，模型列表仅 `LongCat-2.5-Preview`（1M ctx）与 `LongCat-2.0`。
- **视觉可用**：`content` 传 `[{type:"text"},{type:"image_url",image_url:{url:"data:image/png;base64,…"}}]`。
- 整页书页实测（民法总则 p100，783×1218@140dpi，420KB base64）：7 段全部正确，德文坏字（Ubungen→kaufmännische Übungen、吐lich→üblich）**比文字层修得对**；输出纯 JSON 无围栏；耗时 **60~70 秒/页**（推理模型，reasoning 5577 字符）。
- 结论：视觉只做兜底（选页判据 + 上限 + 进度 + 取消），不做整书无脑转写。

## 设计

新包 `data/importer/pdfpipeline/`（纯 JVM 可单测）+ 两个 Android 薄壳。

### 1. 行结构提取 PdfLineExtractor（pdfbox 依赖，`data/importer/`）

- `PDFTextStripper` 子类，`setSortByPosition(true)`；`writeString(String, List<TextPosition>)` 累积当前行，`writeLineSeparator()` 换行落行。
- 行结构 `PLine(text, x0, x1, y0, size)`：x 用 `getXDirAdj()`（旋转自适应），y0 用 `getYDirAdj()`（自顶向下），x1 = 末字符 XDirAdj+WidthDirAdj，size = max(`getFontSizeInPt()`)（全 0 回退 max `getHeightDir()`）。
- 页宽高：CropBox + rotation%180==90 时宽高互换。
- API 已 javap 实核：`getXDirAdj/getYDirAdj/getWidthDirAdj/getHeightDir/getFontSizeInPt/getUnicode`、`writeString(String,List)`、`setSortByPosition` 均存在。

### 2. 页面清洗 PdfCleaner（纯 JVM）

- **文档级统计**：正文众数字号 `bodySize`（0.5pt 桶、字符数加权，空回退 10.5）；左右边距众数（2pt 桶，仅 |size−body|≤1.0 行，空回退 x0=36/x1=页宽−36）；行距 pitch 直方图（1pt 桶主峰 + 谷点，阈值 = min(1.5×pitch, 谷点)，无谷点样本≥8 时 1.4×pitch，样本<3 无信号）。
- **页级清洗**（对 PLine 列表）：
  - 页码行删除（任何位置）：`^\d{1,4}$`、`^[−-]\d+[−-]$`、`^[·•‧]\d+[·•‧]$`、`^第?\d{1,4}页$`、`^\d{1,3}/\d{1,3}$`；
  - 页眉删除：顶部条带（y0 < max(56pt, 12% 页高)）内 size < body×0.95 且行长 ≤40 —— 条带+小字号双证据，书籍页眉随章节变，不做跨页重复检测；
  - 页脚小字保留为独立脚注段（底部条带内 size < body×0.95 → 每行独立一段，不并入正文）；
  - 控制字符剥离；PUA 计数**不删**（不丢字红线，计数供选页）；彝文标点页级守卫归一化（页含 ꎬꎮꎻ 且剔除后不含其他彝文音节 → 全页替换 ，。；）。
- **TOC 页直通**：页内点线条目（`…·.•‧]{2,}\s*\d+\s*$` 或孤页码行）≥3 → 该页不做段落组装，行以单换行输出（交给 BookParser tocEntries 重组）。
- 输出每页 `PageOut(paras: List<Para>, stats)`，`Para(text, footnote: Boolean)`；组装后的正文段落由 ParagraphAssembler 产出。

### 3. 段落组装 ParagraphAssembler（纯 JVM）

输入单页行序列（已清洗）+ 文档统计。规则（多信号必接、少信号才断）：

- **字号跳变**：size ≥ body×1.15 的行 → 独立段落（标题），绝不并入正文；连续大字行（dy ≤ pitch）合并为一段（标题折行）。
- **断段**：y 回跳 dy < −1.5×size；dy > pitch 阈值（无信号时跳过此判据）；下行首行缩进（x0 ≥ left+1.5×size）且上行句末；上行短行（x1 < right−1.5×size）且句末。
- **续接**：上行满行（x1 ≥ right−0.5×size）且无句末 → 必接（无论下行是否缩进）；其余默认续接。
- **句末谓词**：`。！？…”』;：?!` 收尾，或 ①-⑳/`[N]`/`〔N〕` 引注收尾。
- **跨页续接**（保守）：前页末段末行满行或近满（x1 ≥ right−3.0×size）且无句末，且次页首行非字号跳变、次页非 TOC 页 → 合并。
- 输出段落间以空行衔接；页面间不再有隐式连接（跨页合并已在段落层完成）。

### 4. 视觉兜底（三个文件）

- **PageSelector**（纯 JVM）：V1 页字符 <5；V2 PUA ≥2；V3 碎片（<25 字行 ≥5 且占比 ≥0.4）。普通书命中页上限 60（超出取前 60）；**全书扫描**（平均每页 <100 字）→ 全部页选中，页数 >80 抛友好错误（"建议拆分或找文字版"），≤80 全转。
- **VisionTranscriber**（纯 JVM 网络，HttpURLConnection）：POST `{base}/chat/completions`，temperature 0.1、max_tokens 8000、readTimeout 180s；提示词钉死只输出 JSON `{"body":[…],"footnotes":[…]}`；解析剥 ```json 围栏 → 取首个 `{…}` 片段；**长度守卫**：转写总长 < 原页文字总长×0.5 且原页 >200 字 → 判漏抄弃用；失败重试 1 次；单页失败保留文字层内容（不阻断导入）。body 段落替换该页正文段落，footnotes 追加为脚注段。
- **PdfPageRenderer**（Android 薄壳）：`android.graphics.pdf.PdfRenderer` + `contentResolver.openFileDescriptor(uri,"r")`（无需临时文件），140dpi（点数×140/72）RGB_565 → PNG → base64。

### 5. 配置与接线

- 设置新增 `vision_enabled`（默认 "1"）与 `vision_model`（默认 `LongCat-2.5-Preview`）；端点与 Key 复用 `api_base`/`api_key`。SettingsSnapshot/SettingsScreen 增补。
- `AiMessage` 增加 `images: List<String>`（默认空），`ChatRequest` 增加 `readTimeoutMs`（默认 90s）——加字段不破坏现有调用。
- ImportViewModel 流程：extract（规则管线，快）→ PageSelector → 命中且 vision 开且已配 Key → phase"视觉转写"逐页（进度 i/n + 可取消，取消=保留已转写页）→ 重组 sourceText → parse。全书扫描且 vision 未配置 → 原友好报错（补一句"可在设置开启视觉转写"）。
- `PdfLoader.extract` 签名改为返回 `PdfExtractResult{text, pages, scanned}`（破坏性变更，调用方 VM 与 PdfLoaderTest 同步改）；`allowScanned` 参数：VM 在视觉可用时传 true，扫描版判定从 extract 内异常改为结果字段，由 VM 决策。

### 6. 已知边界（本轮不做，记入总览）

双栏判定（真书均单栏，默认按单栏读序）；PUA cmap 修复（Android 无 fontTools）；竖排古籍；尾注章识别；BookParser 标题正则不动（护栏已在 OPT-D 落位）；章节字号档驱动分章（正则已够用）。

## TDD 步骤

- E1 PdfCleanerTest（纯 JVM 合成行）：页眉小字删除/正文大字保留、页码行删除、页脚小字独立段、PUA 计数、彝文标点归一、TOC 直通。
- E2 ParagraphAssemblerTest（纯 JVM）：缩进+句末断段、满行必接、pitch 断段、标题孤立、标题折行合并、跨页续接、TOC 跨页不续接。
- E3 PageSelectorTest（纯 JVM）：V1/V2/V3 命中、上限 60 截断、全书扫描 ≤80 全选 / >80 报错。
- E4 VisionTranscriberTest（MiniHttpServer）：请求体含 image_url parts、剥围栏解析、长度守卫弃用、失败重试 1 次。
- E5 PdfLoaderTest（Robolectric fixture）：带顶部小字页眉行 + 页码行的多页 fixture → 清洗后无页眉页码；段落以空行分隔；scanned 字段语义。
- E6 全量 `testDebugUnitTest` + `lintDebug` + `assembleDebug/Release`。

## 验收

1. E1~E6 全绿；现有测试零改动全绿（除 PdfLoaderTest 按新签名适配，断言语义不变）。
2. E2E 真书《民法总则》重导：页眉行"笫一章/私法绪论"不再出现在正文段（DB grep）；段落粒度显著变细（段落数 > 上轮的巨段数）；真章 12 章不回退；假章不增于上轮 15。
3. 视觉兜底路径：构造扫描 fixture（Robolectric 不支持 PdfRenderer，渲染壳以 E2E/实机冒烟为准；纯 JVM 层 E4 覆盖协议）。

## 计划自评（实现前）

- 正确性 28/30：判定阈值全部来自经验帖实测值 + 本轮真书字号实测（页眉 7.9~8.0 / 正文 9.1~9.7 / 标题 12.3）；风险点 pdfbox 旋转页坐标序由 E2E 实书兜底。
- 架构 18/20：清洗/组装/选页/转写协议全部纯 JVM，Android 只剩两个薄壳；破坏性签名变更限定 importer 内。
- 数据 15/15：不动 schema；角色沿用 BODY/FRONT/BACK/TOC。
- 安全 15/15：Key 走既有 SecretStore；对话/代码/文档不落明文。
- 可读性 9/10：常量中文注释 + 真书样例。
- 需求一致 10/10：直击老板"识别非常有问题"+ 视觉 KEY 落地。
- **合计 95/100 → 执行。**
