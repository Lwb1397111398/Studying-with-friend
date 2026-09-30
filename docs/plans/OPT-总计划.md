# OPT 全面优化总计划案（v0.2.0 实测问题修复）· 修订版 R1

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 逐任务执行。步骤用 `- [ ]` 勾选跟踪。
> **流程约束（用户指令）：** 本计划与三个子计划（OPT-A/B/C）均须 AI 评审 ≥90 分后执行；执行中策略与代码随做随检；每个子包完成后单独验证 + 与已完成子包交叉回归；三包合流后整体质检 ≥90 才允许推送 GitHub。

**Goal:** 修复用户实测暴露的三类问题——API Key 无法保存、大 PDF 导入卡死无反馈、目录/页眉被当正文拆出垃圾章节——并按实测证据全面加固导入-阅读-学习链路。

**Architecture:** 保持既有分层（importer 纯 Kotlin 解析 → Repository → Runner/Planner → Compose UI）。三个子包各自独立可测：A 密钥来源弹性链（KeyStore→软件密钥文件，密文带来源标记）+ 服务商预设 + 推理模型预算；B PdfLoader 进度/取消/省内存（cacheDir 临时文件路径）+ 导入 UI 分阶段反馈；C BookParser 增加 TOC 区域识别（ROLE_TOC）+ 页眉护栏，UI 与 AI 流水线消费新角色（在既有 byChapter 结果上过滤，不新增注入点）。

**Tech Stack:** Kotlin 2.0 / Compose M3 / Room 2.6.1 / pdfbox-android 2.0.27 / JUnit4 + Robolectric。

---

## 0. 实测根因证据（2026-09-30 排查，全部为第一手证据）

### P1 — API Key 无法保存
- 证据 1：`.e2e/after_save.png`（模拟器实拍）显示保存报错 **「保存失败：密钥不可用，无法保存 API Key」**，即 `KeyStoreSecretStore.encrypt` 首次失败、自愈重建（删 Key 重生成）仍失败后抛出 `SecretCryptoException`（SecretStore.kt:47-63）。
- 证据 2：`.e2e/check.db`（18:29 快照）`settings` 表为空 = 该时刻保存确实未落库；18:34 后的快照才有 `api_key_enc` = 模拟器上重试才偶然成功。
- 根因：密钥保存链**单点依赖 AndroidKeyStore**。模拟器/部分真机 keystore 服务异常（生成或 caller-nonce 加密失败）时，现兜底只是"删了重建再试一次"，仍失败则用户永远存不了 Key，且报错不含底层原因。

### P2 — 导入书籍一直显示「正在读取与解析」
- 证据 3：用户提供书籍《民法总则：2022年重排版》（王泽鉴）PDF 实测 **630 页 / 37.8MB / 有文字层**（pypdf 读取验证）。
- 根因 1（真慢）：`PdfLoader.extract`（PdfLoader.kt:21-55）逐页 `PDFTextStripper`，630 页在低内存平板上需数分钟；期间 UI 只有**不定长**进度条（ImportScreen.kt:81,143-151），无页数进度、无取消——用户感知即"卡死"。busy 窗口还含 `BookParser.parse`（ImportViewModel.kt:240-267），大书在 Default 线程也需数秒，同样无反馈。
- 根因 2（内存）：37.8MB 全量 `readBytes()` 进内存 + `PDDocument.load(bytes)`，低内存设备 OOM 风险高（已有 OOM 兜底但那是失败，不是成功导入）。
- 根因 3（不可取消）：`loadFile` 的协程无 Job 句柄暴露，PdfLoader 逐页循环无取消检查点，用户唯一退出方式是杀进程。**取消语义记录**：`BookParser.parse` 为 CPU 密集单次调用（无挂起点），取消在解析完成/下一个检查点生效——提取段（分钟级）即时可断，解析段（秒级）跑完即止，属可接受语义，写入 OPT-B 验收。

### P3 — 目录/页眉被当正文与章节
- 证据 4：该 PDF 目录页（p28-31）实测存在**三种**现行护栏防不住的形态：
  - a) 裸条目行 `第一章 私法绪论`（无点线无页码，命中 A 档 `RE_A_HAN`）→ 假章节；
  - b) 标签单独成行 `第四节`（命中 B 档 `RE_B`）→ 假章节；
  - c) 点线字符含 `•`（`索弓 1·•···595`），`RE_TOC_LINE` 的 `[…·.]` 不含 `•` → 漏拦。
- 证据 5：正文每页页眉 `第一章 私法绪论 3`（标题+印刷页码，无点线，命中 A 档）→ 每页开头都被拆成新章，630 页 ≈ 数百个垃圾章节。
- 证据 6：`grep` 全仓证实 `ROLE_FRONT/ROLE_BACK` 只在 BookParser 赋值、无任何消费方——目录即便识别为"目录"章，仍按普通段落软合并成巨型段、进粗读/总结的 AI 输入。"目录特殊处理"目前不存在。

### P4 — 推理模型 token 预算不足（连带的必现失败）
- 证据 7：LongCat-2.5-Preview 实测（curl，2026-09-30）：
  - 是**推理模型**：SSE 先吐 `reasoning_content` 再吐 `content`；`usage.completion_tokens_details.reasoning_tokens>0`；
  - `max_tokens=64` 时正文字段**整个缺失**、`finish_reason=length`（思考 token 计入 max_tokens 吃光预算）；
  - 非流式/流式/OpenAI 兼容路径正常；错误 Key → 401，错误模型 → 400。
- 根因 4：`RoughReadPlanner` 块预算 `coerceIn(8192, 32_768)`、归并 `8192`（RoughReadPlanner.kt:252,319），讲解 `10_000`（NotePlanner.kt:184），测试连接 `512`（SettingsViewModel.kt:106）。对 3 万字法律文本块，思考轻松破 8192 → 正文为空 → JSON 解析失败 → 整章粗读失败。这会在用户修好导入后立刻撞上。

### P5 — 服务商预设缺失
- 证据 8：`SettingsRepository.PRESETS` 只有商汤三家；用户实际用美团龙猫 `https://api.longcat.chat/openai/v1` + `LongCat-2.5-Preview`，需手填全部四项，且默认地址是 api.openai.com（国内不可达）。

## 1. 工作包分解（每个子包独立计划案 + 独立评分 + 独立验证）

| 包 | 内容 | 涉及 | 验证方式 |
| --- | --- | --- | --- |
| OPT-A | 密钥来源弹性链：`ResilientSecretStore`（KeyStore→`noBackupFilesDir` 软件密钥文件），**密文带来源标记 `k1:`/`s1:`**（无标记=遗留 k1），解密按标记选实现；LongCat 预设首位；测试连接 512→2048；粗读块下限 8192→16384、归并 8192→16384、讲解 10000→16000；`chatJson` 空正文重试预算翻倍；backup rules 排除密钥文件 | SecretStore.kt、SettingsRepository.kt、StudyApp.kt、SettingsViewModel.kt、SettingsScreen.kt、AiClient.kt、RoughReadPlanner.kt、NotePlanner.kt、AndroidManifest.xml、res/xml/* | ResilientSecretStoreTest（Robolectric 真跑兜底路径）+ 预设断言 + 既有 SettingsRepoTest/SecretCryptoTest 全绿 |
| OPT-B | PdfLoader：cacheDir 临时文件 + `PDDocument.load(File)` 替代全量 readBytes（finally 删除临时文件）；`onProgress(page,total)` 逐页回调 + 逐页 `isCancelled` 检查点；ImportViewModel 暴露进度/阶段状态与 AtomicBoolean 取消标志（不暴露 Job 句柄：协程靠检查点异常收尾，无需外部取消）、`cancelImport()` 贯通到检查点；解析段（BookParser）阶段提示"解析章节结构…"；ImportScreen 确定性进度条 + 取消按钮 | PdfLoader.kt、ImportViewModel.kt、ImportScreen.kt | PdfLoaderTest 增进度回调/取消（含页循环专项）/临时文件清理 4 例；既有 3 例全绿 |
| OPT-C | BookParser TOC 区域识别：**以 `目录/目次/Contents` 标题行为锚**（缺失时按密度独立划区），区域判定 = 点线行（含 `•`）密度 + `第X章/第X节` 前缀行密度双阈值划区，区段内所有行降为 ROLE_TOC 且不产生标题命中；**页眉护栏**：A/B 档内置命中行若以 **CJK+数字** 结尾（如"第一章 私法绪论 3"）仅判为正文行（英文标题与 custom 正则路径豁免）；阅读页 ROLE_TOC 段落按条目样式渲染（不软合并）；粗读/总结在 `paragraphDao().byChapter` 结果上过滤 ROLE_TOC（不新增注入点；**OverviewPlanner 只读 chapterAssets，总结侧过滤后自然继承，无需改动**）；目录章无剩余内容时给出友好提示而非报错 | BookParser.kt、DbValues.kt、ReadScreen.kt、RoughReadPlanner.kt、SummaryPlanner.kt | TOC 单测样本**必须含证据 4a/4b/4c 三种形态各 ≥1 行 + 页眉行**（取自真实书页文本）；既有 BookParserTest 全绿（其中两条 FRONT→TOC 断言按 C R3 验收 5 有意改写） |

**TOC 区域判定草案（OPT-C 细化的基准，防止"裸条目行"翻车；**阈值以 OPT-C 落地值为准**）：点线行强信号；从锚点行起的连续块序列中，若块内"点线行 + 纯页码行 + 第X章/节前缀行"占比 ≥60% 则整块划入 TOC 区；锚点后首个占比 <60% 的块终结区域。区域内的裸条目行（4a）与裸标签行（4b）因此不会成为标题命中。**锚点缺失兜底**：未找到 `目录/目次` 锚点时，退化为"首个满足块级 TOC 判定（isTocBlock）且自身 dot+pageOnly 行 ≥3 的块起划区（单点 stray 行不触发）"，并对划出的区域保持"区内不产标题命中"语义；实测书籍《民法总则》目录页已确认存在 `目录` 锚点行（p28）。

**合流标准（整体质检门）**
- [ ] `testDebugUnitTest` 全量 0 失败（基线 213 个）
- [ ] `lintDebug` 0 errors
- [ ] `assembleDebug` + `assembleRelease` 成功
- [ ] 模拟器 E2E 冒烟：导入《民法总则》PDF（进度可见、可取消、无垃圾章节、目录特殊展示）→ 保存 LongCat Key → **sqlite 断言 settings 表 `api_key_enc` 非空（对齐本次排查手段）** → 测试连接 → 粗读 → 讲解卡
- [ ] 三包交叉回归：A 改动不碰 importer；B 不碰解析规则；C 不碰密钥/提取路径——按 diff 复核
- [ ] 全仓 grep 确认真实 Key 字符串不入 git

## 2. 执行顺序与依赖

A、B、C 相互独立，但验证上有先后：**A → B → C**（C 的单测用文本样本，不依赖 B 的设备验证；A 最小先落地建立"改-测"节奏）。每包完成后：
1. 包内全部新测试 + 相关既有测试通过；
2. 跑一次全量单测防交叉破坏（A 完成后验 B 基线，B 完成后验 A 回归，C 完成后验 A+B 回归）；
3. git 单独 commit（A：`fix(settings): …`；B：`feat(import): …`；C：`feat(parser): …`），便于回滚与审阅。

## 3. 风险与决策记录（修订 R1）

- **软件密钥兜底的安全性**：AndroidKeyStore 不可用时退化为"应用私有 `noBackupFilesDir` 文件存 AES 密钥 + DB 存密文"。密文与密钥同在应用私有沙箱，安全等级与"DB 明文可见性"同级；而现状是"存不了 Key = 功能完全不可用"。自用 App 场景下可用性优先，决策记录于此。**配套加固（零成本）**：密钥文件放 `noBackupFilesDir`，并新增 `res/xml/data_extraction_rules.xml` + `res/xml/backup_rules.xml` 显式排除之，堵住 adb/云备份带走的暴露面。
- **密文迁移/共存策略（兜底引入后的新旧密文归属）**：密文统一加来源前缀——`k1:`=AndroidKeyStore、`s1:`=软件密钥文件；无前缀的存量密文按 `k1` 兼容解读（历史上只有 KeyStore 写入方）。解密按标记直接选对应实现；标记实现失败即抛"请重新填写"（`SettingsRepository.decryptKeyOrNull` 不再静默删除密文，仅"结构性损坏"删除——带 k1:/s1: 标记但 body 非法 Base64，机制为 repo 侧纯格式检查，零 SecretStore 接口变更）——keystore 可能只是暂时故障（重启可愈），静默删除会毁掉本可恢复的 Key；新保存一律写当前可用实现的前缀。
- **TOC 丢弃 vs 保留**：保留（ROLE_TOC），阅读页可看、AI 全链路跳过。丢弃会丢失书籍结构信息，且用户明确要求"特殊处理"而非"删除"。
- **max_tokens 预算（数值钉死）**：测试连接 512→**2048**；粗读块 `(段数×130+600).coerceIn(16384, 32768)`；归并 8192→**16384**；讲解 10000→**16000**；`chatJson` 首次响应正文为空（思考吃满）时重试请求 maxTokens **×2**。依据：证据 7 实测思考 token 计入 max_tokens，LongCat 官方示例上限 100 万，以上数值留足思考余量且不盲目放大。
- **PDF 内存路径（方向钉死）**：内容流复制到 `cacheDir/import_tmp.pdf` → `PDDocument.load(File)`（随机访问，峰值内存远低于 byte[]+全量文本驻留）→ finally 删除临时文件；仍保留 OOM 兜底文案。
- **页眉护栏误伤说明**：护栏正则要求数字前紧邻 CJK（`[\u4e00-\u9FFF]\s*\d{1,4}\s*$`），英文标题（Chapter 12 等）不受影响；护栏只挂在 `matchTitle` 内置 A/B 档分支，自定义正则路径不经过护栏（TocConfirmScreen 重切兜底对数字尾行仍有效）。残余误伤：真中文章标题恰好以数字结尾（如"第一章 2022"）会被降级为正文行，损失该次切分；兜底出口是自定义正则重切与手工改名，可接受，记录于此。另注：C 档内置命中（如"一、概述 3"）不经过 A/B 护栏，以数字结尾仍可成章——已知小垃圾源，留待实测反馈。
