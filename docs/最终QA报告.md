# 最终 QA 报告（Studying with friend / 读书搭子 v0.1.0）

> 交付线：整体质检评分 ≥90 分。评审轨迹：**首轮 88 分 → 按建议修复 9 项 → 复审 90 → 终审残留清零、加权 90.45、确认交付**。
> 本文是项目总计划 §6 定义的最终整体质量检测的完整记录。

## 1. 功能核对表（对照总计划 §1 九条 v1 目标）

| # | 需求 | 状态 | 验证 |
| --- | --- | --- | --- |
| 1 | 导入 TXT / PDF / 粘贴三通道，自动分章 | ✅ | M2 测试 + 208 全量回归 |
| 2 | 粗读：AI 像朋友一样通读，★讲/合并讲/跳过自主决策（不段段都讲） | ✅ | RoughReadPlanner 测试 + 阅读页 UI 冒烟 |
| 3 | 段落讲解卡：通俗讲解 + 要点 + 自测问（可再生成） | ✅ | NotePlanner/NoteRunner 测试 + 卡片 UI 冒烟 |
| 4 | 章末总结五件套：总结 / 思维导图 / 记忆思路 TTS / 知识串联 / 自测 | ✅ | SummaryPlanner 测试 + ChapterAssetsScreen 冒烟；TTS 为系统引擎 |
| 5 | 总结包导出 Markdown（SAF）+ 导图导出 PNG | ✅ | 导出路径 Robolectric 冒烟 |
| 6 | 全书总览（≥2 章已总结才开） | ✅ | OverviewPlanner 测试 + 门控口径同 planner |
| 7 | 讲解详略三档：简略 / 标准 / 深入（默认标准） | ✅ | SettingsRepoTest + NotePlannerTest 各 +1 例 |
| 8 | 复习排期 1/3/7/14/30 天 + 错题本 | ✅ | ReviewModels/Daos 测试 + ReviewScreen 冒烟 |
| 9 | 双 APK 可安装（debug 签名直装；release 供自签） | ✅ | §5 构建产物 |

核对过程中发现唯一缺口：讲解详略三档（目标 7）此前只有设计未落地——已在最终 QA 阶段补齐（SettingsRepository 存取 + 设置页 FilterChip 即选即存 + NotePlanner 按 system 末尾注入指令 + 2 个测试例）。

## 2. 首轮整体质检：88 / 100（未达 90 交付线）

分项：A 正确性 88 / B 架构 84 / C 数据完整性 90 / D 安全 85 / E 可读性 88 / F 需求一致性 93。
无 P0（崩溃/丢数据必现级）；P1×1、P2×7。按流程 <90 → 按建议修改 → 复检。

## 3. 本轮修复清单（9 项全部落盘）

| 级别 | 问题 | 修复 | 位置 |
| --- | --- | --- | --- |
| P1 | 章目录页"重读"直接 start(force=true) 覆盖全章标注，无二次确认；阅读页同操作有确认弹窗，口径不一致 | 章目录页加 AlertDialog 二次确认（文案与阅读页一致），确认后才执行 | ChapterListScreen.kt |
| P2-2 | 讲解重生成"先删后造"：AI 调用失败会连旧卡一起丢 | 删前置删除，生成成功后才删旧插新 | NotePlanner.kt |
| P2-3 | TXT 导入 readBytes() 全量进内存，超大文件 OOM 直接崩进程（Error 不被 Exception catch 接住） | 加 catch(OutOfMemoryError) → 友好提示拆分/改粘贴，对齐 PdfLoader 兜底 | ImportViewModel.kt |
| P2-5 | RoughReadPlanner.PROMPT_VERSION 死常量，误导为已有版本戳 | 删除；粗读产物暂无版本戳，靠 force 重跑兜底（记入 §7 backlog） | RoughReadPlanner.kt |
| P2-6 | 排期种子唯一键含章 title：章改名后重生成总结会 IGNORE 失效 → 同章双排期、角标虚增 | 新增 ReviewItemDao.deleteForChapter：种子插入前先清该章**全部**排期行（含 done 行——已核实其无任何读取方，五个查询全部 done=0 过滤），重生成即开启新一轮 1/3/7/14/30 周期；同时根除"走完 30 天后重生成不再排期"的边缘场景（复审补充项） | Daos.kt + SummaryPlanner.kt |
| P2-7 | 设置保存 4 条 upsert 无事务，中途失败留半套配置 | 包进 db.withTransaction | SettingsRepository.kt |
| P2-8 | UI 层 7 处硬编码状态字面量（"READING"/"DONE"/"NONE"/"EXPLAIN"/"GROUP"/"SKIP"）绕过 DbValues 唯一定义处 | 全部换成 DbValues 常量；顺带删除 AiGate.busy 死 StateFlow（grep 确认无消费者） | ReadScreen.kt / ChapterListScreen.kt / AiGate.kt |
| P2-4 | usesCleartextTraffic 全局放行（安全项） | **决策保留**：自用场景，用户可能填 http:// 中转/本地网关地址，禁掉会破坏真实使用；见 §5 接受理由 | AndroidManifest.xml |
| RoughReadModels 注释项 | 核查为质检代理首轮引用错误（实际在 RoughReadPlanner.kt:60）；该处注释已拆行 | RoughReadPlanner.kt |
| 复审 nit：MindMapPanel 用 Log.w 打正常流程日志 | WebView console 按浏览器级别映射转发（ERROR→Log.e / WARNING→Log.w / 其余→Log.i），4 处正常流程日志降为 Log.i | MindMapPanel.kt |

## 4. 修复后回归验证（全部硬证据，最终轮）

- 编译：`:app:compileDebugKotlin` BUILD SUCCESSFUL（低内存 768m 参数）。
- 全量测试：`:app:testDebugUnitTest` **208 个测试，0 失败，0 错误**（awk 汇总 test-results XML：tests=208 errors=0 failures=0）。
- Lint：`:app:lintDebug` **0 errors, 10 warnings**（均为 §5 接受项）。
- 双 APK：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` 一条链 BUILD SUCCESSFUL；
  `app-debug.apk` 20.3MB、`app-release-unsigned.apk` 16.2MB（最终轮时间戳）。
- APK 安装性检查：applicationId com.studyfriend.app；仅声明 `INTERNET` 权限（无存储/电话等多余权限；文件读取走系统 SAF）；debug APK 自带 debug 签名可直接安装，release APK 未签名（自用自签后安装）。
- 低内存纪律：全程 768m~1536m 分级 JVM 参数、workers.max=1、--no-daemon，串行执行，未再触发 OOM 退出。

## 5. 接受项及理由（0 error 之外的 10 条 warning）

| Warning | 理由 |
| --- | --- |
| OldTargetApi (targetSdk 35) | 已是本项目 SDK 集可用的最新 target；lint 对"最新版本校验"的例行提醒 |
| SetJavaScriptEnabled（MindMapPanel WebView） | 思维导图渲染用 markmap，必须开 JS；内容全部来自本应用 own 生成/本地资产，无第三方不可信页面 |
| TrustAllX509TrustManager ×3 | 来自 bouncycastle 依赖 jar 内部代码，非本项目代码；本项目 HTTPS 走系统默认 TrustManager |
| 其余（图标/资源类提示） | 自适应图标三层齐全（foreground/monochrome/legacy），均为 lint 对历史命名习惯的提示 |

安全决策记录：`usesCleartextTraffic=true` 保留（自用，需兼容 http 中转地址）；应用数据面最小权限（仅 INTERNET）；API Key 经 AndroidKeyStore 加密落库，明文不出库。

## 6. 复审与终审评分（同一评审代理，同 rubric 六项加权）

**评审轨迹：88 → 90 → 90 确认交付（终审加权实值 90.45，无 P0/P1、无未处置 P2）。**

| 分项 | 权重 | 首轮 | 复审 | 终审 |
| --- | --- | --- | --- | --- |
| A 正确性与边界处理 | 30% | 88 | 91 | 91 |
| B 架构一致性 | 20% | 84 | 88 | 88 |
| C 数据完整性 | 15% | 90 | 92 | 93 |
| D 安全 | 15% | 85 | 86 | 86 |
| E 可读性与命名 | 10% | 88 | 91 | 92 |
| F 与需求一致性 | 10% | 93 | 95 | 95 |
| **总分（加权）** | | **88** | **90（90.2）** | **90（90.45）** |

复审/终审核验方式：评审代理逐项实读落盘代码核对修复（含"done 行零读取方"的独立验证、复习会话中重生成的边界核对），回归证据（208 测试/lint/双 APK）由主流程提供。

终审结论（代理原话摘要）：**"确认交付……无 P0/P1,无未处置 P2（唯一权衡项 cleartext 已书面记录理由）,数据完整性关键面（事务原子性、失败不覆盖、迁移、排期防重）全部闭环,208 单测绿。v0.1.0 可以发布。"**

架构遗留（评审明确为风格问题、非缺陷，不阻塞）：部分 Screen 直捅 DAO、部分走 BookRepository 的双轨访问风格，留待后续版本统一。

## 7. Backlog（v1 不做，供后续版本参考）

- 粗读产物版本戳：chapter.gist 暂无 promptVersion 列，prompt 升级后旧标注需手动 force 重跑。
- release 签名配置：目前出 unsigned 包，自用时用 Android Studio 或 apksigner 自签。
- 讲解详略可扩展为按书/按章覆盖。

## 8. v0.1.0 后功能迭代的质检线（持续追加）

本报告 §1-§7 固化 v0.1.0 交付时点；此后每个功能阶段沿用同一纪律（计划案 AI 评分 ≥90 循环 → 逐功能真书验证 → 代码 AI 质检 ≥90 循环），轨迹如下：

| 阶段 | 计划案评分 | 代码质检 | 备注 |
| --- | --- | --- | --- |
| P3a 字号证据链 | v6.1 = 90 | 93 | 真书假章 13→12 |
| P3b-1 目录视觉探针 | v13.1 = 94（13 轮） | 见 .e2e/review_p3b1_code.py | 两本真书三门全 PASS |
| P3b-2 目录驱动切章 | — | 84→90 | 八条 E2E 判据全 PASS |
| P5 章节树 UI | 四轮 84→87→87→92 | 随 P3b-2 管线 | E2E J1-J9 全过 |
| P4 示意图保留 | v1.11 = 91（11 轮） | 三轮 81→85→见下 | 六跑 630 页真书 E2E |

P4 补充（2026-10-04）：代码质检第三轮前发现 **P0 级运行时缺陷**——630 页真书导入在 192MB Java heap 上限贴线飞行，第五跑真机 OOM 崩溃（crash 缓冲区 `OutOfMemoryError thrown while trying to throw an exception` 铁证）。修复 `android:largeHeap="true"`（192→576MB，r12-P0-4），六跑全程无崩，meminfo 曲线 MEASURED（峰值 PSS 1252MB 瞬时 / 稳态 118MB）；代价：大书文本提取 ~170s→~9.5 分钟（大堆 GC 变懒），换导入稳定性。详见 docs/plans/P4-示意图保留计划案.md、.e2e/review_p4_code_reply.txt。
