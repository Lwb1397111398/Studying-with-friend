# M9 书籍识别台（bookocr-kit）模块总览

> 这是项目里唯一不在安卓 App 里的模块：一套跑在电脑上的识别工具，产出物（txt / 插图版 PDF）用手机导入 App。

## 模块职责（一句话）

把扫描版 PDF（影印书）用 agent 的视觉能力逐页转写成干净书稿，产出可被 App「KIT 直入通道」按电脑侧章界直接导入的 `书名.txt`（头部带章表元数据块）、`书名_插图版.pdf` 和 `书名_Kit图包.zip`（章表+正文+真图文件三合一，App 端图文混排直入），全程正文不进对话上下文（token 极省）。agent 无关：ZCode / Qoder / 反重力等任何能读文件+派子代理的 agent 均可执行（SKILL.md 开头声明）。

## 运作流程（按真实数据流走）

1. **输入**：老板给一个扫描版 PDF 路径，说「识别这本书」。
2. **建工程**：`bookocr.py init 书名 --src PDF路径` 在 `bookocr-kit/jobs/书名/` 建工作区。**init 直接建批**（批大小取 kit 目录 `config.json` 的 `pages_per_batch`，缺省 10），台账 `manifest.json` 记录页清单/`rendered` 渲染台账/批次/状态/统计/**config 快照**（改全局 config 不影响进行中的书；正文永不入台账）。`config.json`（入库，无密钥）五键：`route`（subagent=AI 页页看，老板默认 / aivision / pull-first / pull-only）、`pages_per_batch`、`dpi`、`aivision_workers`、`chapter_review`（疑点是否派子代理裁决）、`output`（["txt","pdf"]）。
3. **渲染（懒渲染）**：`prep` 才渲染页图，支持 `--pages 521-530` 只渲染指定页——文字层书全程可能一张页图都不用渲；只有走视觉的批才渲染它需要的页。
4. **文字层拔取（pull，首选路线）**：很多"扫描书"自带出版社级 OCR 文字层。`pull` 直接把文字层整理成批文件并逐批自动质检——页眉页脚/页码按页面上落剔除、脚注按「页底 28% 区 + 字号 < 正文 0.88 倍」分区、段落按首行缩进（>1.4 字号）重组、插图按原 PDF 嵌入图块坐标（`get_image_rects`）登记（比视觉目测准）。**图占位行按 y 坐标插段间（2026-10-24）**：pull 拔正文时记每段 y0，图按图块 y0 用 `bisect` 二分插进段序列正确位置（占位行格式 `〔图：第N页插图k〕`，pack 时用台账语义重写），缝合守卫——上页末行或下页首行是图占位时不跨页拼段；**批文件段间补空行**（此前段段直接换行，App 端把整页拼成一段）。**分钟级、零幻觉、零 token**。**跨页缝合**：段落排满一页翻页续排是常态，pull 全书一次拔取后，下页首行顶格（相对全书左边距缩进 <0.6 字号）且上页末行/下页首行都非标题 → 下页首段拼回上页末段（批边界照样缝），修「请求|权基础」式跨页断句——实测 577 页缝 306 处，老板投诉的例句已连续；残余断口只余参考文献悬挂缩进条目/注释密集页等特例（~15-20 处，几何判据不适用）。**页级路由**：无文字但被大图占据（>30% 面积）的页=图版页，写占位记入 `thin_pages`，不再连累整批走视觉。**前序遗留两处增强（2026-10-11 提交）**：① `_page_lines` 同视觉行碎片拼回——两端对齐排版会把一行在西文词间大空隙拆成多个 line 对象，按 y0 聚簇（±2pt）后横向间隙 <4×字号 的碎片拼回一行（两端均 ASCII 字母时隔一个空格），否则缩进判据把碎片当新段首；② `_pull_page` 支持 cmd_pull 全书预算主字号+预收行传参——页内小样本会被脚注/年表等小字号块带偏主字号致正文行误判标题、脚注漏分区（全书主字号稳定）；selftest ALL PASS。实测《民法物权》577 页：57/58 批 1 分钟内拔完（唯一失败批是图版页说明被当脚注滤走致页均字数不足，`check --force` 放行即符合预期）。
5. **视觉路线**（pull 质检失败的批；纯扫描书全本走这条路）——**首选 aivision 直连 API，子代理兜底**：
   - `aivision <书名> [--batch 批号] [--workers 4]`：**直连视觉 API 的机器路线**（绕开子代理串行限制）——从 kit 目录 `apis.json`（gitignore，换电脑自配）或本机 Agent Manager 档案库挑第一个 caps 含 vision 的启用档案拿三键（base_url/key/model，key 只在内存流转不打印）；把 `spec.md` 全文当 prompt、页图 base64 内嵌，走 OpenAI 兼容 `chat/completions`；一批内页级并发（ThreadPoolExecutor workers 路），单页失败自动重试一次；模型输出按〔脚注〕/〔FIGURES〕分区解析落三个批文件（容忍漏写标记），批完自动 `check` 质检、失败标 failed。只处理 pending/failed 批，与子代理路线可混用。**实测：10 页/批 23 秒（并发 5），质量与子代理持平（与文字层相似度 0.985/0.986 vs 子代理 0.989）**。页图未渲染会提示懒渲染命令。
   - 子代理路线：`next` 领任务卡 → Agent 工具派子代理（读 spec + 10 张图 → 写批文件 → 一行统计 → 销毁）。**并发注意：部分套餐子代理并发上限=1，必须串行**。
   - `check` 质检：页标记对账（漏页/乱序/重复，永不豁免）、U+FFFD 乱码硬判、页均字数与中文占比（`--force` 豁免，给图表页/图版页用）；通过标 done，失败标 failed；`retry` 重置失败批。**rect 交叉校正（2026-10-24）**：批带 figures 时开原 PDF 对每图找 IoU 最大的嵌入图块 rect，0.3≤IoU<1.0 → 用 PDF 图块坐标替换视觉目测坐标（记 `rect_src:"pdf"`）——目测坐标常有偏移，交叉校正后裁图/插图版 PDF 都按精确坐标走。
   - `patch <书名> [--batch 批号]`：对 pull 记的 `thin_pages` 派视觉**只补图版页的图描述**——先 `prep --pages` 懒渲染这几页，patch 输出只含这几页的任务卡（子代理 Edit 批文件把「〔图版页〕」占位换成看图描述、figures.json 补记录），派出后 thin_pages 清空防重派。
   - `figure-desc <书名> [--workers N] [--dpi 200] [--dry-run]`（2026-10-24 新增）：对台账里 desc 仍是 pull 占位文案（`PULL_FIG_DESC`）的图逐张补语义——按 rect 裁图存 `figures/{id}_p{页:03d}.png` → 视觉 API（aivision 同源凭据链）单图问答返回 {title,desc,kind} 写回台账；**幂等**（有语义的图自动跳过）；**伪图判定**：裁出高 <48px 或高宽比 <0.06 的极扁横条=影印页装饰线/表格线，直接标「页面装饰线（自动识别，无图义）」跳过 API（实测商汤对 1250×68 装饰条报 413，减半重裁也救不回）；413 自动 dpi 减半重裁重试；`--dry-run` 只裁图写宽高入账不烧额度（96 图 ~1 分钟裁完可先目测再决定）。
6. **切章对账**：`chapters <书名> [--apply 裁决文件] [--no-review]`——**章界权威从手机端猜改为电脑侧定死**的关键步骤。门槛=全批 done。流程四步：①程序切章（`CH_NUM_RE` 章号族 + 前置词族前言/序言/各版序/凡例/作者简介等、后置词族参考文献/致谢/跋等 + B 档「第X节」二级），目录页整页剔除后独立成「目录」章；②程序对账（零额度确定性）——从目录页提取条目（`TOC_ENTRY_RE` 标题+点线+页码行族），与切出章逐条 difflib 相似度（阈值 0.55）对账，印刷页码→PDF 页号偏移按命中对众数换算；③三类疑点（`toc_miss` 目录有条目没切出章 / `gap` 章号断号 / `thin` 章内正文 <50 字）写 `chapters_review_task.txt` 裁决任务卡（每疑点一行上下文摘录+待裁决问题，不含大段正文），主 agent 把卡派给纯文本子代理（不需要看图），回答写回后 `chapters --apply` 吸收（ADD 补漏切章/DROP 删误切章/IGNORE 维持现状，持久记 manifest `chapter_rulings` 重跑不丢）；④裁决不了/仍存疑的记 manifest `chapter_warnings`，**不阻塞**随最终汇报列出。无疑点的书程序对账即过（大多数），`--no-review` 跳过任务卡。
7. **打包**：`pack`（门槛=done 且已切章；`--auto-chapters` 一条龙 / `--no-chapters` 旧格式；**thin_pages 非空即拦**——先 `patch` 补图版页或 `--force` 放行，老板拍板放行的图版页用 force）按页序合并各批 →
   - `output/书名.txt`（UTF-8 BOM；**头部写 KIT 元数据块**：`〔KIT-书 开始〕{"kit":1,"title":"…","pages":N,"chapters":[{"t":标题,"p":起始页,"lv":1章/2节,"r":FRONT/TOC/BODY/BACK,"a":标题前8字锚},…],"v":"bookocr-kit 1.2"}〔KIT-书 结束〕` 单行 JSON，App 端正则提取按章表直切章界；**〔页N〕页标记行保留**——App 端剥标记时把页号记进段落 pageNo，目录驱动跳转/页码显示靠它；图位置保留〔图：…〕描述行（**pack 归一化**：半角冒号笔误 `〔图:x〕` 统一改写为规范形态〔图：题〕desc——App 端按前缀识别图段，2026-10-24 两侧联审修复），脚注归书末「注释」区（**每条独立成段 + 〔脚注〕前缀**：App 端剥标落 FOOTNOTE 次级小字 + 粗读/总结 AI 全跳过；此前连续无空行会被 App softMerge 粘成一个 BODY 大段混进 AI 输入——同日修复；插图版 PDF 的注释区走独立 notes 参数渲染不受影响）；**段间空行**，正文 96 条图行全部带语义图题与描述）；
   - `output/书名_插图版.pdf`（reportlab 排版：msyh 中文字体、书名独占封面页、章标题 15pt/节标题 12.5pt、按 figures 台账的 rect 比例从原 PDF 裁真图嵌入；config.output 不含 pdf 时跳过）；
   - `output/书名_Kit图包.zip`（2026-10-24 新增，config.output 含 zip 时产出）：`kit.json`（章表 + figures 清单含 page/rect/kind/title/desc/file/w/h）+ `book.txt`（与产出 txt 同内容）+ `figures/*.png`（真图文件，命名 `{id}_p{页:03d}.png`）三件套，ZIP_DEFLATED 压缩——**手机导入首选产物**（txt 直入无图、插图版 PDF 走 App 端二次提取会丢装饰线过滤外的真图语义，zip 直入保真最高）。**图位分配算法**：每页图按 rect[1]（y0）排序一次性分配给该页正文里的 FIG_MARK 行（第 k 条图行吃第 k 张图），pull 写的 `〔图：第N页插图k〕` 占位行重写为 `〔图：{title or kind}〕{desc}`；**所有图占位行写 txt 前归一化为全角冒号规范形态**（kit 质检 FIG_MARK 容 [:：]、App 端按前缀识别，双保险 2026-10-24）；图多于图行的页把余图补到页尾（老 pull 批无占位行也走此兜底）。
8. **进 App**：微信传 zip 图包到手机 → 书架「导入书籍」选文件（按钮文案「选择 TXT / PDF / 图包」）。zip 走 M2 **图包直入通道**（`KitZipReader` 解压验协议 → `KitBookParser.parseMeta` 复用章表解析 → `KitFigureMatcher` 按页内第 k 条 FIGURE 段配对第 k 张图，ordAfterPara 锚定 → confirmImport 同事务落库挪图）；txt 头部带元数据块 → M2 **KIT 直入通道**（`KitBookParser` 按章表切章，0 次 AI 调用，章界不猜，但无真图）；无元数据的普通 txt 走 M2 纯文本链路不受影响；插图版 PDF 走 M2 数字书链路，`PdfFigureExtractor` 自动抽图进 figures 表，M4a 阅读页 FigureRow 图文混排。
9. **质量抽检**：`simcheck.py <书名> [批号]` 把成品正文与 PDF 自带文字层逐页做相似度（去空白标点后 difflib 比对），只出数字不贴正文。对纯扫描书无参照系、不可用。`status` 汇总进度（含「◆ 切章 N 章（X 疑点待裁决）| 已打包/未打包」）。

## 双端分段规则对照（各自标定，勿互相"对齐"）

电脑侧 `bookocr.py` 的 `pull` 与 App 侧 `PdfCleaner`/`ParagraphAssembler` 干的活同族（页眉剔除/脚注分区/缩进断段），但**输入源不同所以数值不同**：pull 吃出版社级 PDF 文字层（字号/坐标来自内嵌字体度量，可靠），分钟级批量零 token；App 侧伺候手机导入、数字书与 OCR 行混装（OCR 行 size=框高×0.68 有系统性偏差），判据保守+文本特征兜底。两侧职责声明已互指（bookocr.py `_pull_page` docstring ↔ PdfCleaner.kt 头注释）。**产出契约互不依赖**：pull 产出批文件喂子代理/质检，App 侧产物直接落库——改任何一侧都不要求同步另一侧。

| 规则 | bookocr.py `_pull_page`（电脑侧） | App `PdfCleaner`+`ParagraphAssembler` | 差异原因 |
| --- | --- | --- | --- |
| 页眉带 | 行底 y1n ≤ 6.2% 页高即删（无字号条件） | max(56pt, 12% 页高) 条带 且 字号<正文×0.95 且 ≤40 字 | App 加字号+字数双证据防误删正文（「规则优先、不丢字」铁律） |
| 页脚带 | 行顶 y0n ≥ 95.8% 即删；孤立页码行（纯页码正则且 y1n≤10% 或 y0n≥90%）删 | 无对称几何带；页码行按文本特征（页码正则/「页码 书名」形态） | App 靠文本特征兜底，几何带交给脚注规则 |
| 脚注分区 | y0n > 72% 且 字号 < 正文×0.88 → 脚注 | 底部 28% 条带（同 72% 线）且 字号<正文×0.85；另有行首脚注编号/引注形态文本路 + OCR 分隔线 y 整块收（P6g） | App 阈值更严（0.85<0.88）因为 OCR 字号有偏差要少收不误吞正文；pull 字号可靠可用 0.88 多收 |
| 缩进断段 | 行首缩进 > 正文×1.4 即新段（无上一行条件） | x0 ≥ 左边距+0.8×字号 **且上一行句末标点** 才断（INDENT_FACTOR=0.8；深缩进 1.6 臂默认关） | pull 是批量粗重组（宁多断）；App 是阅读段落精度（双条件防句中换行误断，碎段=头号投诉） |
| 标题独立 | 字号 ≥ 正文×1.15 或 章/节正则 → 独立行 | OCR 路径 1.4×（TITLE_FACTOR_OCR）；数字路径字号证据链（正文+1.5pt+目录守卫） | App 数字路径有完整字号证据链（P3a），不靠固定倍数 |

## 省 token 的机制

正文只落文件（子代理写 batches/，主对话只见一行统计）；子代理干完即销毁，正文不留在任何上下文；照片级大图先程序化缩放（PDF 源天然可控）；质检靠脚本不靠模型通读。实测：300 页书 ≈ 主对话 < 1.5 万 token。

## 入口文件清单

- `bookocr-kit/bookocr.py` — 全部十二个子命令（init/prep/pull/aivision/patch/figure-desc/next/check/retry/chapters/status/pack）
- `bookocr-kit/spec.md` — 转写规范（子代理必读；「看不清按上下文编通顺」是老板拍板的口径）+ **工作方式硬约束**（每张页图最多 Read 一次、禁止裁剪/放大/拼接衍生图、只读任务卡列出的页图、全批约 15 次工具调用内完成——实测整页直读相似度 0.98+ 不低于放大方案，违规=白烧额度）
- `bookocr-kit/SKILL.md` — agent 操作手册（**agent 无关**：ZCode/Qoder/反重力等均可执行；触发=打开 SKILL.md 照做；ZCode 实测子代理并发=1 串行，其他 agent 先派 2 个试水）
- `bookocr-kit/config.json` — 全局设置（route/pages_per_batch/dpi/aivision_workers/chapter_review/output；入库）
- `bookocr-kit/simcheck.py` — 成品 vs 自带文字层逐页相似度抽检（只出数字）
- `bookocr-kit/README.md` — 新电脑三步安装 + 两种模式人话说明
- `bookocr-kit/selftest.py` — 回归自测（合成 12 页假书全链路，**80 项断言**，含 pull 占位行插段间/figure-desc dry-run/pack 拦图版页/pack 拦占位图/zip 三件内容，换电脑即可跑）
- `bookocr-kit/requirements.txt` — PyMuPDF + reportlab

## 对外接口 / 被谁调用

- 被 agent（人话「识别书」触发 skill scan-book）调用；产出物被 M2 导入链路消费。
- 换电脑：整夹拷走 + `pip install -r requirements.txt` + 拷 SKILL.md 到 `~\.agents\skills\scan-book\`（或不注册，直接叫 agent 打开 SKILL.md 照做）。

## 依赖的上游模块

- 无代码依赖。产出的兼容性依赖 M2 的 `BookParser`（章正则档位）、`PdfFigureExtractor`（幸存图提取）、M4a 的 `FigureRow`（图显 示）——均已实机验证兼容。

## 关键数据结构

- `manifest.json`：`pages[]`（no/image）、`rendered[]`（已渲染出 PNG 的页号——懒渲染台账：init 只建批不渲染，prep 按需填，`next`/`aivision`/`patch` 都有渲染守卫）、`batches{bXXX}`（pages/status: pending→assigned→done|failed/body/notes/figures/stats/fail_reason/**thin_pages**——pull 记的图版页号，patch 派卡后清空防重派）、`figures[]`（id/page/rect 比例/kind/title/desc/**file/w/h**——figure-desc/pack 裁图后回填，**rect_src**——"pdf"=check 交叉校正过的精确坐标）、**`config`**（init 时快照的全局设置）、**`chapters[]`**（{t,p,lv,r,a} 章表）、**`chapter_rulings`**（ADD/DROP/IGNORE 持久裁决）、`chapter_ignored`/`chapter_warnings`/`chapter_review_pending`（疑点与裁决状态）、`packed`。
- 批正文协议：`〔页N〕` 整行=页边界；`〔图：标题〕` 整行=真图占位（txt 保留、PDF 换成嵌图+图题）；pull 批里图占位行临时格式为 `〔图：第N页插图k〕`（`PULL_FIG_MARK`，pack 时重写成正式格式）。
- **KIT 元数据协议**（txt 头部单行 JSON 块，App `KitBookParser` 消费）：`{"kit":1,"title":…,"pages":N,"chapters":[{t,p,lv,r,a}],"v":"bookocr-kit 1.2"}`，两行标记 `〔KIT-书 开始〕/〔KIT-书 结束〕` 夹住；损坏/版本不认时 App 回退通用解析，绝不因协议导入失败。
- **KIT 图包协议 v2**（zip，App `KitZipReader` 消费）：zip 三件——`kit.json`（`{"kit":1,"v":"bookocr-kit 1.2","chapters":[{t,p,lv,r,a}],"figures":[{page,rect,kind,title,desc,file,w,h}]}`）+ `book.txt`（带元数据块与〔页N〕标记的正文）+ `figures/*.png`（真图，文件名 {id}_p{页:03d}.png）；App 端按 figures 清单半包容错（缺图文件剔除并记录，不整包拒）。

## 已知坑与约束

- `jobs/` 已 gitignore——书稿与页图不进 GitHub；换电脑不拷 jobs/。
- PDF 版排版依赖 `C:\Windows\Fonts\msyh.ttc`（Windows 自带）；Mac/Linux 需换字体路径（pack_pdf 里字体探测链可扩）。
- 子代理产出质量靠 check 兜底：连续 2 次质检不过要停手问老板，不要硬转（SKILL.md 铁律）。
- 子代理并发上限：部分套餐 =1，并行派发全灭（2026-10-11 实测）；SKILL.md 已写明串行对策。
- 子代理「自作主张放大镜」病：单批工具调用 >30、耗时 >30 分钟即中招（自己用 PIL 裁条带逐条读），实测质量并不更好（相似度 0.976 vs 直读 0.989）、时间 4 倍；靠 spec.md「工作方式硬约束」一节治住（b006 治后 20 次调用/12 分钟恢复正常）。
- 真书前几页若是密集目录，PDF 变体的章节树可能碎（BookParser 对（页码）式目录行的既有行为，txt 主产出同样内容由目录校准消化）；实机验证用的是探针书，真书效果以第一本实战为准。
- aivision 对个别页可能**稳定超时**（《民法物权》p529 实测：3 次、并发 5/5/2 都 TimeoutError，且重试会把失败页越滚越多）——不要硬刷（白烧额度），按凭据纪律停手改走兜底：`pull` 重拔该批写占位 + `check --force` 放行，或子代理单转该页。
- 视觉路线失败页重试：aivision 批内有失败页=整批标 failed 不落文件（all-or-nothing），避免半批半批的残缺产物混进台账。
- **`PAGE_MARK` 带捕获组，切分只能用 `split` 的奇数位取页号**：`re.split(PAGE_MARK, text)` 返回「前文, 页号, 正文, 页号, 正文……」，偶数位是正文、奇数位才是页号；批C 的 check 图版页豁免分支从下标 0 起步取 `int(parts[0])`，遇 `int('')` 直接 ValueError 让整批 pull 崩（2026-10-26 修，见最后更新）。凡按页标记切正文，一律用已编译的 `PAGE_MARK.split()` + `range(1, len(parts)-1, 2)`，不要再手抄一份正则。
- 批C 的 pack 占位图拦截对 **pull 路线**生效：台账里 `desc == PULL_FIG_DESC`（「原书嵌入插图…」占位）的图非空即拦，`--force` 才放行。所以 pull 书要出成品有两条路——跑 `figure-desc`（需视觉 API）或 `pack --force` 带占位打包；`--dry-run` 只裁图不写语义，拦不住。
- **figure-desc 伪图**：影印页装饰线/表格线会被 PDF 登记成嵌入图块（极扁横条），视觉 API 对其报 413——已按「高<48px 或高宽比<0.06」自动标记跳过；若仍有个别 413 顽固页，减半 dpi 重试一次后放弃该图（记占位 desc），不阻塞整批。
- **老 pull 批（占位行缺失/无语义）**：pack 图位分配对「图多于图行」的页自动补页尾兜底；《民法物权》混合工程（视觉批 b001-b006+老 pull 批）实测 96/96 图全落位。老 pull 批建议跑一遍 `figure-desc` 补语义再 pack。
- Git Bash 下 adb push 中文/绝对路径：目标路径要用 `//sdcard/...` 双斜杠防 MSYS 路径转换；SAF「文件」应用对新 push 的文件可能要等 MediaStore 扫描（或从抽屉进内部存储路径）才可见。

## 验证记录（2026-10-11）

- selftest.py **80 项断言全绿**（2026-10-26 复跑 `ALL PASS`、退出码 0；含 pull 图占位行插段间/缝合守卫/段间空行、check rect 交叉校正、figure-desc dry-run、pack 拦 thin_pages、pack 拦 pull 占位图、zip 三件内容校验、注释区〔脚注〕前缀+空行分隔、半角冒号归一化、txt/zip 版本串统一）。
- **真书《民法物权》577 页图链路全通（2026-10-24，MEASURED）**：`figure-desc` 真跑 96/96 图语义补齐（26 table/64 diagram/6 photo，商汤 sensenova-6.8-flash-lite，2 张 413 伪图=装饰线自动标记）；`check` rect 交叉校正吸收 b003 目测偏移图；`pack` 三产出（txt 538059 字含章界块+124 章、插图版 PDF 14.6MB、**zip 图包 9.9MB 含 96 张真图 PNG**，正文 96 条图行全部带语义图题+描述，格式 `〔图：{title}〕{desc}`）；模拟器 SAF 导入 zip → 确认页「bookocr 成品书：章界已由电脑侧确认，**96 张插图原位导入**」+ 32 章 6276 段预填 → 完成导入 → Room 复核 **figures 表 96 行、96 行 ordAfterPara≥0 全锚定**（bookId=14）→ 阅读页附录章（15 段+8 图）真图渲染（原书图版照片 + 图题小字，截图为证）。
- App 单测 821 绿 0 失败 1 skipped（基线 802+19：KitZipReaderTest 5 + KitFigureMatcherTest 3 + KitBookParserTest 11）。
- 真书《损害赔偿》60 页样本：b001+b002 真实子代理转写 7981 字，CIP 页/序言逐字准确、跨页断句无缝续接；pack 双产出成功。
- 模拟器实机：自产插图 PDF 经 SAF 导入 → figures 表 2 行、`files/figures/9/` 2 张 jpg、阅读页 FigureRow 图文混排显示（截图为证）。
- 全本实战《民法物权》577 页：视觉路线 b001-b006 质检全过、与文字层相似度 0.989/0.991 峰值；pull 路线 57/58 批分钟级完成、质检全过、抽检相似度 0.968-0.996（差异=页眉剔除与脚注分离）；b053 最终按「pull 占位 + check --force」放行（该批为图版页说明，无正文属预期）。
- aivision 直连视觉 API 实测（2026-10-11）：10 页/批 23 秒（并发 5）、b007/b008 成品 vs 文字层相似度 0.985/0.986（子代理路线 0.989，持平）；p529 稳定超时改走 pull 兜底（见已知坑）。

## 最后更新
- 2026-10-26 **批C 遗留两处修复（做转移包前复跑 selftest 揪出，79→80 断言全绿）**：①`check` 图版页自动豁免分支崩——`re.split(PAGE_MARK, text)` 因正则带捕获组，返回值里页号落在**奇数位**、正文在偶数位，原代码 `range(0, len(parts)-1, 2)` 第一步就 `int('')` 抛 ValueError，pull 遇含大图页（归一化面积 >0.3）的批整批崩（`bookocr.py:963`，改用已编译的 `PAGE_MARK.split()` + `range(1, len(parts)-1, 2)`）。②selftest 没跟上批C 的 pack 占位图拦截——`_selftest_pull2` 清掉 thin_pages 后直接 pack，被「有 2 张图还没补语义」拦下报 FileNotFoundError；补一条拦截断言（`还没补语义` 必须出现），打包那步改 `pack --auto-chapters --force`（自测不联网，不能真跑 figure-desc 补语义）。③事实记录：批C 那次提交（a1714a7）自带红基线——本机实测 5 条 FAIL + 两处 traceback，且本总览未回写；本次一并补齐。**影响面（推断，未实测）**：崩的那条分支只在「pull 路线 + 批内有大图页 + check 不带 --force」时进入，《民法物权》在批C 之前就已 pack 完，所以还没有真书踩过，下一本 pull 的书会踩。
- 2026-10-24 **KIT 链路两侧联审四缝隙修复**：①pack 注释区每条脚注独立成段 + 〔脚注〕前缀（App 端剥标落 FOOTNOTE 次级小字 + 粗读/总结 AI 全跳过；原连续无空行被 App softMerge 粘成 BODY 大段混进 AI 输入；插图版 PDF 注释区不受影响）；②pack 图占位行归一化全角冒号（App 端旧版只认全角，双保险）；③txt 元数据 v 统一 "bookocr-kit 1.2"（原 1.1 与 zip 1.2 不一致）；④**协议纪律写死**：kit=1 是协议版本位，扩展只加可选字段勿 bump（旧 App 收 kit:2 zip 整包拒）。App 端配套：书名预填 preferredTitle（kit 元数据优先、zip 尾巴防护）、FIGURE_PREFIXES 兼容半角、确认页 KIT 书隐藏「识别规则」（详见 M2 总览）。selftest 76→79 断言全绿。
- 2026-10-24 **KIT 图包 zip + figure-desc + 图位精确化（本轮大改，「图片识别做的不好」投诉闭环）**：①新增 `figure-desc` 命令——pull 图语义批量补齐（裁图+视觉 API 逐图问答写回 title/desc/kind/file/w/h，幂等、--dry-run、伪图装饰线自动判定）；②`pull` 图占位行按图块 y 坐标 bisect 插进段间正确位置（不再一律页尾）+ 缝合守卫（图行两侧不跨页拼段）+ 批文件段间空行（修 App 端页内多段并一段）；③`check` rect 交叉校正——视觉目测坐标与 PDF 嵌入图块坐标 IoU≥0.3 时用 PDF 精确坐标替换（记 rect_src="pdf"）；④`pack` 三产出加 **zip 图包**（kit.json 章表+figures 清单 / book.txt / figures/*.png 真图三件套，KPI 图位分配：页内第 k 条图行吃第 k 张图、pull 占位行重写成正式格式、余图页尾兜底）+ thin_pages 挂账拦截 + 段间空行；⑤config.output 默认加 "zip"；⑥selftest 59→76 断言全绿。App 端配套 `KitZipReader`/`KitFigureMatcher`/ImportViewModel zip 分支（详见 M2 总览）。真书 96/96 图 E2E 全通（见验证记录）。
- 2026-10-24 **KIT 成品书直入协议（配合 App 端 M2 直入通道）**：①新增 `chapters` 切章对账命令——程序切章（章号族+前/后置词族，目录页独立成章）→ 目录条目 difflib 对账（偏移众数换算印刷页码）→ 三类疑点（toc_miss/gap/thin）出裁决任务卡 → 纯文本子代理裁决 `--apply` 吸收（ADD/DROP/IGNORE 持久台账）；无疑点自动跳过裁决。②`pack` 写 KIT 元数据块（章表 {t,p,lv,r,a}）进 txt 头部 + **保留〔页N〕页标记行**（App 端剥标记记 pageNo）；pack 门槛加「已切章」，`--auto-chapters`/`--no-chapters` 可选。③新增 `config.json`（route/pages_per_batch/dpi/aivision_workers/chapter_review/output 五键，init 快照进 manifest）。④SKILL.md/README 改 agent 无关版（ZCode/Qoder/反重力均可执行）。⑤selftest 扩到 59 项断言全绿（chapters 切章/对账/裁决、元数据块回读、config 兜底、页标记保留）。App 端配套：`KitBookParser` 直入 + role=FIGURE 图卡 + E2E 模拟器全链路验证（详见 M2 总览）。
- 2026-10-11 **pull 跨页段落缝合**（老板投诉「请求|权基础」页界断句后修）：①cmd_pull 改全书一次拔取+缝合——下页首行顶格且首末行非标题 → 拼回上页末段（577 页缝 306 处，批边界覆盖）；②`_page_lines` 同视觉行碎片拼回（两端对齐在西文词间拆行，「dingliche|Einigung」断词闭合）；③正文主字号改全书统计（页内小样本被脚注/年表小字号带偏，实测 43 页误判致正文行全被当标题+脚注漏分区）。selftest 37 项断言全绿（新增缝合正例/缩进反例/页末标题反例 5 项）；真书重拔重打包验证：老板例句、德文术语全部连续，随机 40 字窗口命中率 91.3%（未命中=脚注搬家正常错位）。残余断口：参考文献悬挂缩进/注释密集页特例，几何判据不适用，量级 ~15-20 处。
- 2026-10-11 **README 同步**：两条识别路（pull 文字层拔取 / aivision 视觉 API）写进人话说明；补 apis.json 三键配置 FAQ（gitignore）与 thin_pages/patch 图版页 FAQ。
- 2026-10-11 **任务流三项改善**：①页级路由——pull 的图版页判定从整批跳过改为页级记账（`thin_pages`），不再连累同批 9 页好文字走视觉；新增 `patch` 命令对图版页单独派视觉补描述（发卡后清空防重派、页未渲染不发卡只提示 prep --pages）。②`aivision` 直连视觉 API（页级并发、单页重试、批完自动 check、all-or-nothing）。③懒渲染——init 直接建批不渲染、prep 支持 `--pages` 子集渲染、manifest 记 `rendered` 台账，`next`/`aivision`/`patch` 三个出口都加渲染守卫；文字层书 init→pull→pack 全程零渲染。selftest 扩到 32 项断言全绿（新增：thin_pages 记账/占位/patch 卡/防重派/零渲染路线）；SKILL.md 重写为十步流程。
- 2026-10-11 **双端分段规则漂移治理（轻方案）**：pull 与 App 侧 PdfCleaner 的阈值差异对照表落 M9 总览（页眉 6.2% vs 12%+0.95×+40 字、脚注 0.72/0.88 vs 28%/0.85+文本路、缩进 1.4×无条件 vs 0.8×+句末双条件、标题 1.15×/正则 vs 字号证据链），两侧代码头注释互指职责声明——各自按输入源标定、改一侧不要求同步另一侧、产出契约互不依赖。不动 pull 产出契约。
- 2026-10-11 **新增 `aivision` 直连视觉 API 命令**（前序会话遗留改动补录入库）：一批内页级并发识图（--workers 4），绕开子代理并发上限=1 的串行瓶颈；API 三键从 kit 本地 apis.json 或 Agent Manager 档案库取（key 不打印不落库）；批完自动 check 质检。与子代理路线可混用，只处理 pending/failed 批。selftest 未覆盖（需真 API）。

- 2026-10-11 **pull 多批台账覆盖 bug 修复**：cmd_pull 循环每轮 save 外层旧 manifest，cmd_check 内部另开副本标 done，下一轮 save 把上一批 done 覆盖回 assigned、figures 台账只剩最后一批（多批 pull 后 pack 拒绝、打包丢插图）。修法：cmd_check 增加可选共享台账参数，pull 传外层同一份在其上改状态。selftest 补"多批 pull 全部 done"回归断言（旧代码实测红、修复后绿），31 项断言 ALL PASS。

- 2026-10-11 首建：bookocr-kit 全套 + selftest 全绿 + 60 页样本真实跑 + 模拟器抽图实机验证通过。
- 2026-10-11 全本实战《民法物权》后升级：新增 `pull` 文字层拔取命令（57/58 批分钟级，免视觉）、`simcheck.py` 质量抽检、spec.md 工作方式硬约束（治子代理裁图病）、SKILL.md 改 pull 优先 + 并发上限=1 的串行对策。
