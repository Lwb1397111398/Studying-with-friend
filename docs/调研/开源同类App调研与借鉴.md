# 开源同类 App 调研与借鉴清单

> 2026-11-15 调研（四路侦察并行查 GitHub：阅读器 / AI 陪读 / 间隔重复 / 导图与激励）。
> 回答两个问题：**哪些思路值得学**、**哪些代码能直接抄**。
>
> **许可证纪律（先读这个）**：GPL / AGPL 系项目只能学思路、一行代码都不能搬——搬了整个 App 就必须跟着开源；MIT / Apache / BSD 可以放心引入或移植，无传染风险。下文每一条都已标注。

---

## 一、调研过的项目总览

| 项目 | 是什么 | Stars(约) | 许可证 | 对我们 |
|---|---|---|---|---|
| open-spaced-repetition/java-fsrs | FSRS-6 记忆算法 Java 官方实现 | 27 | **MIT** | ⭐⭐⭐⭐⭐ 直接 Gradle 引入 |
| yihong0618/bilingual_book_maker | LLM 整书翻译管线 | 9.9k | MIT | ⭐⭐⭐⭐ 抄「结构化输出降档梯子」范式 |
| Anxcye/anx-reader | 电子书阅读器+AI 划词讲解 | 9.2k | MIT | ⭐⭐⭐⭐ prompt 资产改写复用 |
| SSShooter/mind-elixir-core | 轻量 JS 思维导图核心 | 3.2k | **MIT** | ⭐⭐⭐⭐ WebView 集成升级导图 |
| johnfactotum/foliate-js | 纯 JS 电子书渲染引擎（EPUB/MOBI） | 1.2k | **MIT** | ⭐⭐⭐ 加 EPUB 支持的轻路线 |
| readium/kotlin-toolkit | Kotlin 原生 EPUB/PDF 导航器 | 400 | **BSD-3** | ⭐⭐⭐ 加 EPUB 支持的重路线（稳） |
| MODSetter/SurfSense | NotebookLM 替代 | 16.9k | Apache-2.0 | ⭐⭐⭐ 引用溯源机制 |
| lfnovo/open-notebook | NotebookLM 替代 | 42k | MIT | ⭐⭐ prompt 版本化管理思路 |
| FriesI23/mhabit | 习惯强度评分实现 | 1.7k | Apache-2.0 | ⭐⭐ 统计评分可参考代码 |
| dottxt-ai/outlines | 约束解码结构化输出 | 16k | Apache-2.0 | ⭐ 仅参考（本地模型场景） |
| 567-labs/instructor | pydantic 校验重试封装 | 14.1k | MIT | ⭐ 电脑侧脚本可用 |
| Ciallo-Reader | Kotlin 小阅读器 | 417 | MIT | ⭐ 翻页动画/zip 分批入库参考 |
| smuyyh/BookReader | 安卓阅读器（Java） | 7.0k | Apache-2.0 | ⭐ 仅 3D 翻页动画参考 |
| gedoor/legado（阅读） | 最强安卓阅读器 | 47.3k | GPL-3.0 | 🔒 只学思路（主仓库 2026-05 已被作者清空，看 fork：LegadoTeam/legado 等） |
| koreader/koreader | 墨水屏阅读器 | 31.1k | AGPL-3.0 | 🔒 只学思路 |
| readest/readest | 跨平台阅读器 | 26.3k | AGPL-3.0 | 🔒 只学思路 |
| khoj-ai/khoj | 个人 RAG 助手 | 38k | AGPL-3.0 | 🔒 只学思路 |
| ankidroid/Anki-Android | Anki 安卓版 | 12.3k | GPL-3.0 | 🔒 只学思路 |
| iSoron/uhabits（Loop Habit） | 习惯打卡 | 9k+ | GPL-3.0 | 🔒 只学思路 |
| mateusz-bak/openreads | 读书统计 App | 2k+ | GPL-2.0 | 🔒 只学思路 |

> ⚠️ legado 许可证口径说明：历史版本为 GPL-3.0；2026-05 主仓库被清空后代码在 fork 里延续。侦察两路对其「仓库是否还有 LICENSE」记录不一致——**无论哪种口径，一律按 GPL 对待，禁抄码**。

---

## 二、能直接复用的代码/库（按性价比排序）

### 1. FSRS 复习排期 → 装进 M6 复习中心（收益最大、成本最低）

- **是什么**：FSRS-6（Free Spaced Repetition Scheduler）是目前最先进的间隔重复算法——每道题存三个数：D 难度 / S 稳定性 / R 可提取性（当前记住概率），据此算出下次该复习的时间。比 SM-2（Anki 老算法，每卡自维护 ease 因子、无遗忘曲线）预测准得多，也比我们现在的「等级/降档」制度强一个时代。
- **怎么用**：`open-spaced-repetition/java-fsrs`，MIT，**Maven Central 一行依赖**：`io.github.open-spaced-repetition:fsrs`（要求 Java 17，与我们一致）。FSRS-6 全量：调度器 + 遗忘曲线 + fuzz，约 65KB 代码，**不用训练**——官方默认 21 参数在千万级复习记录上预训练过，Anki 内置 FSRS 也是默认参数起步；个性化训练需要每人约 200 条复习记录才划算，我们单用户量远不够。
- **反馈映射**：我们三档直接对上——**不会→Again / 模糊→Hard（「记得但吃力」正是 Hard 原义）/ 会→Good**；Easy 档弃用损失极小（官方有 4-button-benchmark 仓库专门验证过）。
- **额外能力**：`getCardRetrievability` 一行算出「这题当前记住概率」→ 可做「最快要忘的排前面」的考前排序，这是档位制给不了的。
- **迁移成本**：估计 1~2 天——Room 加一张 review_log 表 + review_items 每题存 D/S/state/last_review，旧档位数据一次性映射成 D/S 初值。
- **风险**：java-fsrs 最后推送 2025-07，比 py-fsrs / ts-fsrs（2026-11 仍活跃）慢，但 FSRS-6 算法已冻结、库仅约 2 千行，风险可控。备选：抄 py-fsrs 核心 `scheduler.py`（MIT，约 400 行纯函数）移植 Kotlin，1~2 天，但没必要。

### 2. 结构化输出「降档梯子」→ M5 出题 / 自测判卷的 JSON 稳定性

- **来源**：bilingual_book_maker 的 `book_maker/structured.py`（MIT，约 290 行纯 Python，零 SDK 依赖，可移植 Kotlin）。
- **是什么**：让 LLM 稳定吐合法 JSON 的「降档梯子」——第一档 json_schema 严格模式 → 第二档 JSON mode → 第三档纯 prompt 兜底。兜底层的关键手法：给**填好的示例实例**+枚举散文约束（实测直接给序列化 schema 模型会把信封原样吐回）；`extract_json_object` 括号平衡扫描 + required_keys 校验（防抓到内层片段）；剥回声信封；失败驱动降档、被拒档位记账不再付费；部分答案保底不整页丢弃。
- **对我们**：套在自测题生成（题目/选项/答案/解析）与 AI 判卷（得分点覆盖/遗漏/建议）上，JSON 解析失败率下降、失败时自动重试降档，不再整页作废。
- **成本影响**：只在解析失败时多付重试调用，正常路径零新增。

### 3. mind-elixir-core 思维导图 → 升级现有导图

- **来源**：`SSShooter/mind-elixir-core`，MIT，3.2k 星，2026-11 仍在维护。对比 markmap（MIT，13.2k 星）后选定它，理由：① 输入就是纯 JSON 节点树 `{topic, id, children, expanded}`，与 AI 产出的结构化树零转换（markmap 要先转 markdown）；② 每个节点官方支持自定义 `onclick` 回调——节点跳章不用碰 DOM；③ 节点折叠 + 滚轮缩放开箱即用，正好补齐现有导图短板。
- **怎么用**：WebView 里 `new MindElixir({el})` + `init(nodeData)` + `mind.refresh(data)`，JS Bridge 回传节点点击。
- **对比结论**：Compose 原生导图库没有能用的（compose-mindmap 0 星个人实验、MindMapView 2024 停更），自绘成本高，不推荐。

### 4. anx-reader 的 AI prompt 全集 → 改写进 assets/prompts/

- **来源**：`Anxcye/anx-reader`（MIT）`lib/enums/ai_prompts.dart`，场景与我们几乎一致（阅读器内 AI 讲解）。
- **值得抄的设计**：
  - 划词讲解两部分结构：先 1~2 词「快答」再详细分析；注入 `{{contextText}}` 阅读上下文消歧代词/语气；内含决策树（源语言=目标语言时转单语词典条目）；明确禁 markdown 列表防 UI 崩坏。
  - 章总结三段式（情节/人物/主题）8-10 句、禁套话；全书总结模板（中心冲突高亮、3 人物+动机、主题词 3-5、防剧透结局）。
  - 「重温旧书回忆前文」短总结 prompt，适合续读场景。
  - prompt 全存用户可改的资产里——与我们 assets/prompts/ 思路同源，可对照补强。
- **注意**：改写不是照搬——它是小说向的，我们的法律教科书场景要保留自己的术语表/学情注入体系，取其结构。

### 5. EPUB 支持：foliate-js（轻）或 readium（稳）——**拍板：暂不做**

> **2026-11-15 评审后拍板：直接砍。** 书源是扫描书，EPUB 试点没有真实用户需求支撑；foliate-js API 不稳、readium 偏重。什么时候真拿到 EPUB 书源什么时候再立项，现在做是「因为开源项目有」而非「用户需要」。下面记录保留作未来参考。

- **foliate-js**（MIT）：纯 JS ES 模块无构建步骤——`epub.js`+`epubcfi.js`（解析+定位）、`paginator.js`（CSS 多列分页）、`overlayer.js`（批注高亮层）、`tts.js`（自带朗读）、`mobi.js`。Android WebView 加载 + JS Bridge 回传选中/进度。注意：未发稳定版，API 会变，需锁版本。
- **readium/kotlin-toolkit**（BSD-3）：Gradle 直接引入的 Kotlin 原生 EPUB2/3 Navigator（reflow + 固定版式）+ PDF + Media Navigator（TTS 朗读导航）；有 alpha 版 Compose navigator。比 foliate-js 重（含 DRM 框架）但工程化程度高。
- **现实提醒**：老板的主要书源是扫描书 OCR，EPUB 收益取决于平时是否读电子书——建议先拿一本真 EPUB 小试点（WebView 渲染 → 抽章节正文喂现有导入链路），再决定全量做。

### 6. SurfSense 引用白名单 → 随手问「说这话的依据在哪」

- **来源**：`MODSetter/SurfSense`（Apache-2.0）`citations.py`。机制：检索时给内容块打标签，回复可引用范围=本会话搜索返回过的块白名单；citation 携带 (document_id, start_line, end_line, title)，前端可跳原文精确行。
- **对我们**：随手问/讲解的回答里标「依据出自第 X 章第 Y 段」，点击跳原文——法学生最吃「出处」这套。khoj（AGPL，仅思路）同款手法：先抽取相关原文片段再作答。

---

## 三、只能学思路的机制（GPL/AGPL，禁抄码）

### legado「阅读」（GPL-3.0）——真分页的正确打开方式

- **分页产物带字符坐标**：`ChapterProvider` 管页面几何，`TextChapterLayout` 按章异步排版——逐字用 `StaticLayout.getDesiredWidth` 测宽断行，产出的每个 `TextLine` 携带行矩形 + 章内绝对字符偏移；行累积超可见高度就封页成 `TextPage`。**划词/批注与真分页不冲突的秘密就在这**：点按命中测试直接遍历行矩形反推字符区间，不需要 Compose 文本组件那套手势系统。批注更是在排版阶段就预留行尾列宽。**我们当年否决真分页，是因为走 Compose 组件分页路线；legado 证明自绘 Canvas + 行级字符偏移是另一条走得通的路**——但工程量大（图混排/听书跟随/手势全要适配），要单独立项。
- **翻页动画**：`PageDelegate` 体系 7 个实现（仿真卷页/Cover/Slide/Scroll/NoAnim），翻页与内容渲染解耦。
- **TXT 章节切分**：可配多条目录正则规则（存 `TxtTocRule` 表），自动逐条试切、**按命中章节数选最优**（阈值 2）；超 100KB 长章自动拆「章名(1)(2)」；正则零命中每 10KB 兜底。比我们单套规则多了「自动选规则」和「长章拆分」两点。
- **TTS 高亮跟读**：跟踪当前朗读位置高亮正文。

### koreader（AGPL-3.0）——页级阅读时长统计

`page_stat_data` 表按页记录 `(id_book, page, start_time, duration, total_pages)`——精确到每页读了多久，据此画按月/周/日热力图。我们照此建模进 Room：「书+段/页+起始时间+时长」一张表，聚合出热力图/趋势/按书统计任意视图（现有 reading_stats 是日粒度，页级是它的超集）。

### AnkiDroid（GPL-3.0）——复习 UX 六招

1. **按钮上预写下次间隔**（「记住 · 约 4 天」），评分前知道后果——java-fsrs 能预演各档间隔。
2. **答「不会」的卡当天小步重现**（Anki learning steps：1 分钟/10 分钟），不隔天才见——术语卡盒尤其适用。
3. **一键撤销上次评分**——误触不毁进度。
4. **排序切换**：到期序 / 随机 / 按「最快要忘」（retrievability 升序，考前模式）。
5. **卡片搁置（bury/suspend）**：先跳过不打分，应对「这题想再看一眼书」。
6. **允许提前复习但缩短间隔**：应对「明天考试想多过一遍」。

### readest（AGPL-3.0）——朗读同步

EPUB 3 Media Overlays「朗读同步逐句高亮」，本地 EPUB 配 MP3/M4B 朗读——听书功能的长期参考。

### 激励统计（多来源，见下）

1. **年度阅读热力图**（GitHub 格子风）——AnkiDroid / Loop Habit Tracker（均 GPL）的日历图思路：Compose Canvas 自绘格子矩阵，数据=每日阅读分钟。
2. **每日目标 + 达成率**——Loop Habit 的 target 机制：每日 X 分钟目标，统计卡加「今日/目标」进度环，部分达成也给分。
3. **streak 里程碑通知**——现有连续天数升级：7/30 天达成推送庆祝。
4. **习惯强度评分**（0~100 指数加权）——uhabits 思路，断一天不清零更抗挫；`FriesI23/mhabit`（**Apache-2.0**）有可参考代码。
5. **年度阅读报告**——openreads（GPL-2.0）思路：「今年共读 X 小时，完成年度目标 Y%」。
6. **按书维度统计**——每本书时长排行/进度可视化（legado 桌面二改版思路）。

---

## 四、优先级拍板（经高智商评审修正后的终版）

| 期 | 内容 | 依据 | 工作量 |
|---|---|---|---|
| **P0** | ① FSRS 上线——**前置半天先确认复习数据模型能承载每题独立状态**（due/stability/difficulty/reps 挂到 review_items/quiz_attempts 上）；反馈映射改四档：**不会→Again / 模糊→Hard / 会→Good / 太简单→Easy**（只留 Good 撑不住长间隔）；含按钮预写下次间隔 + retrievability 排序 ② 出题/判卷接「降档梯子」——**校验不能只做括号平衡**：法律教材《》书名号、第X条嵌套频繁会误判，必须加 schema 字段级校验（如「来源章节号必须在正文真实存在」防幻觉出处） | 收益最大成本最低；FSRS 纯本地零新增 AI 调用；降档梯子正常路径零新增 | 各 1~2 天 + 前置半天 |
| **P1** | ③ **引用溯源提前到 P1 头部**（讲解/总结回答标「依据第X章第Y段」可跳原文，SurfSense 白名单思路；先做讲解/总结，出题判卷不动）④ prompt 法律向改写（anx-reader 结构 + 我们的术语表/学情体系）——**先攒 20~30 段典型教材做人工基准集**，否则改 prompt 纯靠感觉，单测管不到这层 ⑤ 激励统计包（页级时长表→热力图/每日目标/streak 里程碑）——**埋点走内存聚合+退出时落盘，不能每页写库卡帧** | 出处是法学生刚需，AI 讲错没出处第一周就失去信任；且溯源依赖 chunk 结构，越晚做越要回头改 prompt 输出格式 | 各 2~3 天 |
| **P2** | ⑥ 导图换 mind-elixir-core——**先做千节点性能 spike**（扫描书章节数可能上千，mind-elixir 对千级节点性能一般），且 WebView 桥接层（节点跳章/折叠状态同步/横竖屏重排）仍是自己维护，别按「换掉就完事」估工 ⑦ 低成本分页体验验证（LazyColumn 预加载+按视口高度估算分页）——先验证老板是否真在意假翻页，在意得厉害真分页就不是 P2 而是「换阅读器内核」的大决策 | 大工程先评估再动 | spike 半天，其余立项 |
| **不做** | EPUB（拿到真 EPUB 书源再立项）；真分页维持否决 | 见上方 EPUB 拍板与评审意见 | — |

> **评审意见里「单人单机=单点故障、要加本地备份」一条，本机已有覆盖**：M1 备份导出/整库恢复（BackupManager+BackupDao，数据安全轮）已入库，FSRS 状态进 Room 后自动搭上现有备份链，登记即可，无需新增工程。

---

## 七、高智商评审意见（2026-11-15，已并入第四节拍板）

- **台账**：档案「维云（大号）」（glm-5.3），用途「开源借鉴调研优先级评审」，耗时 75.7s，成功。
- **采纳的三处修正**：①FSRS 加前置数据模型确认 + 第四档 Easy 映射；②引用溯源从 P1 中部提前到 P1 头部；③降档梯子校验加法条级字段白名单（防幻觉出处）。
- **采纳的排期调整**：导图换库降 P2（千节点 spike 前置）；EPUB 砍掉；真分页维持否决、改为先做低成本分页体验验证；prompt 改写加基准集回归；页级统计埋点走内存聚合。
- **未采纳**：评审建议「加本地自动备份」——经核对 M1 已有整库备份链覆盖，不重复建设。

---

## 五、AI 调用量与成本影响申报

- P0-①FSRS：纯本地计算，零新增 AI 调用。
- P0-②降档梯子：正常路径零新增；仅解析失败时多付 1~2 次重试调用（原本整页作废重生成反而更贵）。
- P1-④prompt 改写：调用次数不变，请求体微变（加引用要求），讲解缓存版本号升一次属预期。
- P2：立项时单独申报。

## 六、后续落地规矩

- 引任何库前先核对当时仓库 LICENSE 文件（本文快照是 2026-11-15 的，库可能换许可证）。
- GPL/AGPL 机制落地时**重写实现**，只保留行为思路；diff 审查不得出现 GPL 代码片段。
- 每个借鉴项落地后回写对应模块总览（M1/M2/M4a/M5/M6）并在本文件登记状态。

## 七、落地状态登记

- **P0-① FSRS 复习排期**：✅ 2026-11-15 落地（java-fsrs 1.0.0，MIT）。四档映射+空学习步+关 fuzz+旧档迁移回填+review_log+备份 DTO。见 M1/M6 总览同日条。
- **P0-② 降档梯子**：✅ 2026-11-15 落地（范式移植 bilingual_book_maker structured.py，MIT；未引库，Kotlin 重写）。JSON mode→纯 prompt→校验重试，QuizJudge 首个接入方。见 M3/M5 总览同日条。**校验收窄说明**：评审建议的「来源章节号必须真实存在」逐字校验不适用于判卷（prompt 明确转述合法，逐字比对误杀）——该防幻觉校验归属 P1 引用溯源。
- P1（引用溯源/prompt 改写+基准集/激励统计）、P2（导图 mind-elixir spike/低成本分页验证）：未开工。

## 八、定向补充调研（2026-11-21 实景体验轮第五波）

> 老板点名「看 GitHub 优秀项目学习经验、做美化与新功能」。围绕实景抓到的三个体验问题做定向调研，当天落地（零引库，全部思路借鉴或自研）。

### 1. 暗色模式下的白底插图（实景痛点：《侵权行为》装机，大面积白图嵌深棕背景炸眼）

- **业界现状**：Kindle/Kobo 的暗色模式是**全屏反转**（连图片一起反）——白底黑字图反成黑底白字，扫描书线图效果最好，但彩色图会变补色怪图；LCD 阅读器普遍反馈反转后白图刺眼问题反而更糟（[Kobo Dark mode](https://help.kobo.com/hc/en-us/articles/360062231213-About-Dark-mode)、[How-To Geek Kindle dark mode](https://www.howtogeek.com/)）。EPUB 生态没有 per-image 的暗色适配通道（epubcheck 不允许 `<picture>`，[r/selfpublish 讨论](https://www.reddit.com/r/selfpublish/comments/11s41ez/how_to_make_graphics_in_ebooks_look_good_in_light)）。
- **我们的选择**：**不做反转，做亮度缩放**——暗色主题下 RGB 三通道 ×0.76（白底→194 灰底，黑白对比仍在；彩色插图整体变暗不失真；对原书青色印刷页等高饱和彩图只压亮度不改色相）。实现 `figureToneFilter()`（FigureGallery.kt），行内 FigureRow 与全屏预览 FigurePreviewDialog 双接入，`colorScheme.surface.luminance()` 判暗色（与主题三态解耦）。
- **验证**：装机实测白底/青底图均柔化不炸眼；彩色印刷图（p66-71 青底）色彩保真。

### 2. 翻页手感与落点（spike 拍板的 B 形态正式落地）

- **借鉴源**：自家 `spike/pager-demo`（第 5 期拍板：视口估算+锚段首，p50 帧 33ms→16ms、页首恒完整段）；落点估算模型同 demo（`PageTurn.kt` 纯函数 + PageTurnTest 9 例回归锁定）。Google Play Books / legado 的真分页维持否决（工程量失控，B 形态已消解半截段痛点）。
- **实测坑**：估算必须随字号/行距设置联动（demo 固定 18sp 不够）；gist 卡展开时 rows 下标 ↔ LazyColumn item 下标要平移（extraHeadItems）。

### 3. 阅读进度指示（实景痛点：长章不知读到哪）

- **业界惯例**：主流阅读器（Kindle/微信读书/legado）都是页脚百分比或页码；我们选**底部 2dp 细进度线**（primary 色 55% 进度段 + 14% 轨道段，沉浸模式随顶栏隐藏）——不动布局、不打断阅读心流。
- **实现坑（装机实录）**：`LinearProgressIndicator(progress={...})` 的 lambda 在 draw 阶段才执行，frac 若在 AnimatedVisibility content 里按普通 val 计算，首帧合成后**冻结在 0**——必须 `derivedStateOf`（依赖压到跨 item 才变）+ progress lambda 内读 derived state，draw 时取实时值。实测 12 次快速滚动进度线连续增长。

### 4. 划词浮条底板（实景痛点：浮条文字直接叠在正文上）

- 根因不是没底板（Surface 一直在），而是暗色主题下 `surface` 与页面背景同色系 + 阴影不可见，视觉上「无底板」。借鉴各类浮层设计惯例：底板提亮一档（`surfaceContainerHighest`）+ 0.5dp `outlineVariant` 描边，深浅两主题都浮得起来。零调研库依赖，纯 M3 设计语言修正。

## 九、定向补充调研（2026-11-21 实景体验轮第六波）

> 主测《中国民法典评注·条文选注（第3册）》装机实录 + 子代理第二轮开源调研（17 个新候选点，star/许可证经 gh api 实测；本轮避开一至八节已覆盖项）。本轮落地 6 件，其余候选存档按优先级排。

### 本轮落地（6 件，出处与许可证）

| 落地件 | 出处 | 许可证约束 | 落点 |
| --- | --- | --- | --- |
| 阅读背景主题包五套（纸白/羊皮纸/护眼绿/墨玉/纯黑） | legado（47.3k★，GPL，学思路）、Ominididact（Apache-2.0，「墨玉羊皮」研学主题）、华为/微信读书通行配色惯例 | 色值为公开通行方案，无风险 | ReadingTheme.kt + read_theme 键 + Aa 色卡行 |
| 音量键翻页 | legado `volumeKeyPage`（GPL，学思路——通用 Android 技巧） | 自写实现，无风险 | MainActivity.dispatchKeyEvent + StudyApp hook + read_volume_key 键 |
| 读书笔记导出 Markdown | readest（26.5k★，AGPL，只学模板字段思路）、anx-reader 笔记页结构（MIT） | 自写拼接，无风险 | NotesExporter.kt + 书架长按「导出笔记」+ FileProvider exports/ |
| 书名作者尾巴拆分 | 自家 wave-4 cleanSiteTail 增强（《条文选注第3册》`_(朱庆育)` 实录） | 无 | KitBookParser.splitAuthorTail |
| 前置/后置章徽标 | deepread（Zotero AI 精读插件，无明确许可证——只学交互思路）+ BookAnalysisScope 词族同源 | 无 | ChapterKinds.kt + 三处章行渲染 |
| 简目页码尾巴不判级 | 自家 ParaHeadings 误杀兜底补规则 | 无 | PAGE_TAIL 正则 |

### 存档候选（未排期，按性价比排序）

1. **霞鹜文楷字体**（lxgw/LxgwWenKai 27.1k★，OFL-1.1 可打包）：正文可选开源字体，APK +10~20MB 是唯一代价——DESIGN.md 现有「不引外部字体」决策需老板点头再动。
2. **选中段落长图分享卡**（legado 思路 + Compose GraphicsLayer / Capturable Apache-2.0）：原文+出处渲染成暖纸长图发微信，成本 M。
3. **章节伴读页**（ChampionGPT/readpilot 11★，MIT）：章末总结升级「概念/论证链/关键问题/复习提示」四要素可回访页面，结构化输出链路现成，成本 M。
4. **待弄懂清单**（deepread 思路）：划词「存疑」不烧 AI，攒一批课后集中问——与「记一笔」互补，成本 S~M。
5. **模板化封面生成器**（generator-cover/tenprintcover 均 MIT）：书架默认封面从首字色块升级渐变纸纹+噪点，成本 S~M。
6. **随手问对话树**（pi-tree AGPL 只学思路）：按段落锚点组织的可回访问答树，成本 M~L。
7. **导出离线 HTML 复习页**（Ominididact Apache-2.0）：全书 AI 产出单文件 HTML，与 Markdown 导出互补，成本 M~L。
8. **阅读页左侧亮度滑条**（legado 思路）：竖拖调软件亮度，与主题包组合成「阅读环境调节」，成本 S。
9. **桌面续读 Widget**（androidx.glance 官方库）：4x2 书名+进度+点击续读，成本 M。

### 明确不做（本轮调研复核）

双语对照（场景不成立+GPL）、AI 音色克隆（算力不匹配）、跨书知识图谱（与导图重叠+成本 L）、本地向量 RAG（平板内存代价+已有轻量方案）、自定义书源（与导入制定位冲突）、WeRead 笔记同步（协议逆向合规风险）。
