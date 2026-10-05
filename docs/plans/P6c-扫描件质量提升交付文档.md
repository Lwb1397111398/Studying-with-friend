# P6c 扫描件 OCR 质量提升 · 交付文档

> **本文档是 P6c 新对话的唯一开工依据**，自包含、不依赖前一会话记忆。执行前先读完 §1-§6，按 §4 路线顺序开工，验收看 §5，纪律看 §6，找文件看 §7。
> 立项授权：老板 2026-10-05 原话——「允许！而且希望你可以想一些更好的办法，比如换模型等等，现在的处理还是问题太大了，怎么能这么多不可用的呢，我们要想办法提升质量」。
> **老板核心诉求：质量优先。** 导入耗时翻倍可以接受（一次性成本）；模型体积涨到百 MB 级可以接受（走 Release 分发不进 APK）；不可用页率 87% 不可接受。

## 0. 人话背景（一分钟版）

扫描书导入功能用手机上的小模型（PP-OCRv5 mobile，21MB）把书页照片变成文字。P6b 实测：一本 461 页的扫描书（《损害赔偿》，90 年代台湾影印本，文件名 shpc），**只有 60 页识别质量过关，401 页被程序自己判为「没把握」转给了视觉兜底（AI 看图转写，贵且慢）**。

我们曾经怀疑是「及格线（0.85 置信度阈值）定错了」，2026-10-05 做了画线复核：把 401 个兜底页的原始识别记录全量拉出来重算，并渲染原书对照图逐档目视——结论写进 P6b-落地报告.md §9：**及格线是准的，错在「考生」**。median 0.847 的页（就差 0.003 过线）正文已经乱码；把线降到 0.80 只多放行 36 页、全是乱码页。所以 P6c 的方向不是改线，是**让同一页的分数本身涨上去**：换更大的模型、把给模型看的图弄得更清楚。

一个关键线索：**同一本书、同样 140dpi 的图，P6a 阶段 PC 上的 rapidocr 原型跑出 median 0.9751，手机上的 PP-OCRv5 mobile 只有 0.895**——差 8 个点。这中间有三个嫌疑：模型档位不同（mobile vs 更大档）、检测预处理把图缩小了（det limit=960 会把 1351px 长边缩 29%）、或置信度口径差异。P6c 的 Phase 0 就是把这三个嫌疑一个个排掉。

## 1. P6b 实测数据速览（全部 MEASURED，新对话可直接引用）

| 事实 | 数值 | 出处 |
| --- | --- | --- |
| 生产引擎 | PP-OCRv5 mobile：det 4.75MB + rec 16.5MB + dict 18383 行，共 21.08MB，onnxruntime-android 1.22.0 | P6 总览 |
| 页置信度 median | 全本 0.895；兜底 396 页无一 ≥0.85 | P6b 落地报告 §3/§9 |
| PC 原型对照 | rapidocr（PP-OCRv4 系）同书 140dpi median **0.9751** | P6a 判据标定源 |
| 兜底率 | 461 页中 401 页兜底（LOW_CONF=396 + DIAGRAM=5），三次导入逐位一致 | 同上 |
| 质量悬崖位置 | 恰压 0.85：median 0.847 的正文页已严重乱码（p289「慰抚金」→「助余」）；0.75-0.80 档「劳工保险条例」→「带工煤险条」 | §9 目视 12 页 |
| 降阈值收益 | T=0.80 只增收 36/396 页且全是乱码页 → **0.85 线实证正确，不动** | §9 |
| 模拟器耗时 | x86 2.0-2.8s/页，Pass1 15.8min/461 页（真机待测，预计显著更快） | §3 |
| 内存 | OCR 增量 ~208MB PSS（onnxruntime arena，整轮不回收，靠 engine.close 释放）；App 已开 largeHeap 576MB | M2 决策 32 |
| 三个擦线页 | p158/p266/p434 median≥0.85 仍兜底，全是规则 c 判的图示页（DIAGRAM），与置信度线无关 | §9 |
| 书架现状 | 模拟器已装 shpc-ocr（book 4，4 章 1293 段）+ mfzz | .e2e |

**既有决策注意**：S2b 曾决策「PP-OCRv6 出了也维持 v5」。当时背景是 v5 mobile 刚跑通、质量未实测。现在老板明确质量优先 + v5 mobile 实测不达标，该决策的前置条件已变化，P6c 可以重评（换 v4/v5 server 或其他 ONNX 模型都属授权范围）。

## 2. 根因三疑凶（Phase 0 逐个鉴别，别猜）

1. **模型档位**：mobile 系列是用速度换精度的裁剪版。老板已拍板质量优先 → 换 server/rec 大档是第一候选。但先做 2 和 3 的鉴别，避免换了大模型发现白换。
2. **检测输入被缩小**：P6a PoC 的 det 预处理 limit=960（P6 总览记录）；生产 `PpOcrEngine` 的取值**待读源码确认**（`android/app/src/main/java/com/studyfriend/app/data/importer/ocr/PpOcrEngine.kt`）。140dpi A4 渲染约 860×1351，若长边被 limit 压到 960，等于模型只看到 71% 尺寸的图——文字像素被白白扔掉。**若把 DPI 提到 200 而 limit 不联动，图更大、缩得更狠，等于白提**——DPI 与 limit 必须当一对变量扫。
3. **置信度口径**：PC rapidocr 0.9751 与手机 0.895 的差，可能部分来自两边对「行置信度」的算法差异（CTC 解码后 mean/min 口径、字典、归一化）。复刻对账实验（§4 Phase 0-2）直接回答。

## 3. 方案空间全表（Phase 0 用数据筛，不是全做）

| 方案 | 预期收益 | 代价/风险 | 判定实验 |
| --- | --- | --- | --- |
| A1 DPI 140→200 | 文字像素 +92%，det/rec 双受益 | 推理耗时 ×1.5-2（模拟器 Pass1 16→30min+）；位图内存 ×2 | Phase 0-3 参数扫描 |
| A2 det limit 960→1280/1536/不限 | 停止白白缩图，可能单独就救回一大截 | det 计算量上涨 | 同上（与 A1 联动扫） |
| A3 图像预处理（灰度+unsharp 锐化/对比度拉伸） | 针对影印本发灰、透印 | 过度处理伤字形；每页多一步 CPU | Phase 0-3 附带扫一组 |
| B1 换 PP-OCRv5 server rec（必要时 det 同换） | 最直接对症：大模型分数与精度双涨 | 模型体积（量级待查证官方发布页）、推理内存与耗时显著涨；需新 Release tag 分发 | Phase 0-4 同页跑分对照 |
| B2 换 rapidocr 同款模型（PP-OCRv4 系 ONNX） | PC 上已实证 0.9751；格式同为 ONNX，PpOcrEngine 小改即可吃 | v4 与 v5 的字典/后处理细节差异；版本文件名待查证 | Phase 0-4；**若 v4 mobile 手机复测仍 0.89 → 说明问题在配置不在模型档位，B1 也白换，先修配置** |
| B3 PP-OCRv6 | 官方最新档 | 未知项最多 | 仅当 B1/B2 数据不达标才评估 |
| C1 行级兜底替代页级 | 少兜底整页 | 段落残缺伤阅读与 AI 理解；P6b 选页级是保守正确 | 只在 B 方案后兜底仍多时评估 |
| C2 分层重识别（mobile 快扫→兜底页 server 重试） | 耗时可控的高质量兜底 | 管线复杂度+两套模型内存叠加风险 | Phase 2 可选 |
| ~~降 0.85 阈值~~ | **已证伪（§1）**：增收 36 页全乱码 | — | 不做 |

## 4. 推荐路线（Phase 0 全程在 PC 上做，分钟级迭代，不碰模拟器）

### Phase 0-1 查证清单（开工第一步，半天内）
1. 读 `PpOcrEngine.kt` 生产预处理参数（det limit、归一化、rec 输入尺寸、批处理），与 P6a PoC 差异逐项列表。
2. 查 PC rapidocr 环境用的具体模型文件与版本（pip 环境里 onnx 文件名+大小），确认「0.9751」到底是哪个模型跑出来的。
3. 查 PaddleOCR 官方（GitHub PaddlePaddle/PaddleOCR）v4/v5 mobile 与 server 两档 det/rec 的 ONNX 体积与下载地址，记录成表。

### Phase 0-2 复刻对账（最便宜的第一个实验）
用 onnxruntime-python + 现有三件套（v5 mobile det/rec/dict，从 GitHub Release `ocr-models-v1` 下载）在 PC 上复刻手机的预处理与解码，对 shpc 固定 20 页（从 `.e2e/drafts_shpc/` 的 median 分布挑：10 页乱码重的 + 10 页接近过线的）跑置信度：
- 与手机 median 0.895 对账。**一致** → 0.895 是模型真实水平，放心换模型；**不一致** → 先修配置再谈换模型。
- 这一步同时把「PC 实验台」建好，后续所有实验都在它上面跑，每轮分钟级。

### Phase 0-3 输入侧扫描（A1×A2×A3 联动）
在实验台上扫：DPI {140, 200, 250} × det limit {960, 1280, 1536, 不限} × 预处理 {无, 灰度+unsharp}，每组输出 20 页的 median 分布、按 0.85 线的过闸数、与 Phase 0-5 真值对照的 CER。选出输入侧最优组合。

### Phase 0-4 模型对照（B1/B2）
同一批页、最优输入组合下，跑：v5 mobile（基线）/ v4 mobile（rapidocr 同款）/ v5 server rec。每组记录：median、过闸数、CER、模型体积、PC 单页耗时（用于粗估手机端耗时倍数）。产出「模型×参数」对照总表 + 推荐组合。

### Phase 0-5 质量真值与 CER（把「目视感觉」变成数字）
抽 5 页（含乱码重灾区），用本机视觉模型（龙猫，凭据走 `C:\Agent\AImanager\tools\ai_client.py` 体系，见 AGENTS.md 测试用 AI 凭据节）整页转写作**近似真值**；CER 计算复用 P6a 已有链路（`.e2e/p6a/` 三视角 CER 工具）。注明局限：视觉转写错误率非零，是近似真值；如需金标准可请老板人工录 1-2 页。

### Phase 0 收口
对照表 + 推荐组合写成短报告（`docs/plans/P6c-Phase0-报告.md`），**报老板确认选型后**进 Phase 1（模型档位与体积是产品决策，虽然老板授权了方向，换哪个模型拿到数据后仍要让老板过目一眼再动）。

### Phase 1 移植与验证（新对话主体工程）
1. `PpOcrEngine` 参数化（det limit/DPI/预处理可配）+ 换模型文件：`OcrModelDownloader` 发新 Release tag（如 `ocr-models-v2`），体积/SHA256 清单同流程。
2. **新模型重新画线**：复用 P6b 的 pageDrafts 落盘机制（已建成）+ `.e2e/analyze_line.py`/`render_pages.py`（画线工具链已建成，见 §7），为新模型标定新阈值。**注意纪律：新模型新口径，重新标定 ≠ 放宽旧判据；但改 `CONF_THRESHOLD` 常量前必须带完整证据链报老板点头**（P6b 已验证这套画线流程好使）。
3. 模拟器重导 shpc 跑 §5 判据表 + 664 条单测回归 + 新参数单测。
4. 真机联测（老板插手机）：耗时、置信度分布与模拟器一致性。

### Phase 2 可选（B 方案后兜底仍 >70 页才启动）
C2 分层重识别或 C1 行级兜底，同样先在 PC 实验台模拟收益再动代码。

## 5. P6c 验收判据建议（Phase 0 数据齐后与老板确认定稿）

| # | 判据 | 目标 |
| --- | --- | --- |
| ① | shpc 兜底页数（真机口径） | ≤70 页（原判据④线；模拟器口径记录不判） |
| ② | 过闸页质量 | 抽 10 过闸页 CER ≤ Phase 0 定的基线（不再靠目视） |
| ③ | 章检出 | 8 章（该书目录实有 8 章）或锚点对账 ≥70% |
| ④ | 真机总耗时 | ≤16min（模拟器 ≤35min 记录） |
| ⑤ | 模型体积与内存 | 体积申报（Release 分发不占 APK）；真机内存峰值 <576MB largeHeap 红线 |
| ⑥ | 回归 | 全量单测绿 + 数字书/TXT 路径零影响（ocrMode 分支已有护栏） |

## 6. 工作纪律（对老板的硬性承诺，原文继承）

1. **每步先写计划案，AI 评分 <90 则按建议修改循环至 ≥90**；执行中对策略与代码做质量检测；**每个小功能实现后立即验证**，B 功能完成回头看 A 功能有无回归；最后整体质检，全部通过才推 GitHub。
2. 无需老板同意即可自主执行（2026-10-05 重申「允许」）；但**危险操作（删除/迁移/依赖变更/Git 破坏性操作）与判据数值变更必须人话报老板确认**。
3. 指标纪律：区分 MEASURED / UNMEASURED / DECLARED_AND_NOT_DISPROVED，禁止用「低风险/应该没问题」代替验证。
4. 改过代码必须同步更新 `docs/模块总览/` 受影响模块；完成前三查（`python "C:\Agent\AImanager\tools\check_session_close.py" <项目路径>`）全绿才有资格声称完成。
5. 完成后自动 commit + `python "C:\Agent\AImanager\tools\git_push_github.py" C:\AIWorkSpace\Studying-with-friend`（私有仓库，推送前人工过 diff）。
6. 凭据纪律：AI key 只走环境变量/本机档案库，输出打码；`.e2e/` 整目录不入库。
7. 成本申报：改变 LLM 调用次数/等待时间的改动，汇报写明前后对比。
8. 会话收尾闸门：改动必须 commit 或在汇报中声明未提交清单。

## 7. 资产清单（新对话直接按路径取用）

**代码**（`android/app/src/main/java/com/studyfriend/app/data/importer/ocr/`）：
`OcrEngine.kt`（接口）/ `PpOcrEngine.kt`（生产引擎，Phase 0-1 读它）/ `OcrTextPostProcessor.kt` / `OcrImportRunner.kt`（两遍法+PageGate+pageDrafts）/ `OcrModelDownloader.kt`（Release 分发）/ `OcrModelStore.kt` / `OcrVerticalDetector.kt`；UI 侧 `ui/screens/ImportViewModel.kt`（writeOcrDebugSample/质量备注）。测试 `OcrImportRunnerPageGateTest.kt` 等 75 条 P6b 新增。

**数据与工具**（`.e2e/`，gitignore 本地保留）：
- `shpc.pdf`——原书（461 页扫描版）
- `drafts_shpc/`——**401 页兜底页草稿（行文本+置信度）+24 页抽样图**（P6b 第三次导入全量留档，2026-10-05）
- `ref_pages/`——画线用的原书对照渲染图 17 页
- `analyze_line.py`——草稿分析器（重算 PageGate 判定量/模拟候选阈值/分桶抽样清单）
- `render_pages.py`——PyMuPDF 按 140dpi 渲染对照图（与生产 DPI 同规格）
- `p6a/`——PC 原型链路（rapidocr 跑批+三视角 CER 工具，Phase 0-2/0-5 直接复用）

**文档**：`docs/plans/P6b-落地报告.md`（§9 画线复核=P6c 立项证据）、`docs/plans/P6b-扫描件管线计划案.md`、`docs/plans/P6a-PoC报告.md`、`docs/模块总览/M2-导入与解析.md`（决策 32/33/34）、`docs/模块总览/P6-OCR-PoC模块总览.md`（P6a 已知坑）。

**模型**：GitHub Release tag `ocr-models-v1`（v5 mobile 三件套+SHA256SUMS），分发流程在 `OcrModelDownloader`。

## 8. 成本预估

| 阶段 | 耗时 | 说明 |
| --- | --- | --- |
| Phase 0 全程 | PC 上 0.5-1 天 | 每轮实验分钟级；视觉转写真值 5 次调用 |
| Phase 1 代码 | 小改动 | 参数化+换模型文件+新画线，复用已建成的 pageDrafts/画线工具链 |
| Phase 1 验证 | 模拟器每轮 17-35min × 2-3 轮 | 兜底页视觉调用视判据结果另计 |
| 风险 | server 模型内存/耗时超预期 | Phase 0 体积与耗时预估先行，真机 Phase 1 实测把关；实在超线退 C2 分层方案 |

---

**最后更新**：2026-10-05 · P6b 画线复核收口后立项，交付文档成稿（commit 0522fe1 之后）
