# M3 AI客户端与设置 · 模块总览

> 状态：✅ 完成（M3 原始 91 分质检通过；2026-11-15 档案体系多模型轮收口）
> 验证：全项目单测全绿，assembleDebug 通过

## 模块职责

一句话：管 AI 的「连接」（OpenAI 兼容端点、Key 加密、SSE 流式、错误文案）和「配置面」（模型页 + 设置页）；2026-11-15 起配置面升级为**档案体系**——多个服务商档案 + 四个任务角色各自指派。

## 功能清单（2026-11-15 档案体系后）

| 功能 | 所在页面 | 说明 |
| --- | --- | --- |
| 模型页（三张书房卡） | ModelsScreen（设置页入口二级页，Routes.MODELS） | ①模型档案（列表+新建+编辑弹层）②按任务指派（四行任务各挂档案胶囊+视觉开关）③本地识别（OCR） |
| 模型档案（LlmProfile） | 模型页 | 一个档案 = 一家服务商：名称 + API 地址 + Key（每档独立加密）+ 默认模型名 + 温度。档案列表永不为空（无配置自动建「主力」默认档）。编辑弹层含查找模型、测试连接（流式 ping）、删除（至少留一档；删除自动回收指派） |
| 按任务指派（LlmAssign） | 模型页 | 四角色各自指派档案，**即点即存**：主力（总结/总览/导图/粗读章级合并）、讲解（段落讲解/粗读批注/随手问/换个讲法/判卷——未指派跟随主力）、视觉转写（扫描页整页转写——未指派跟随主力）、备用视觉接力（主视觉限流时自动接力——未指派不启用，不回退主力 Key） |
| 「查找模型」 | 模型页编辑弹层 | `AiClient.listModels`：GET `{base}/models`（Bearer）拉服务商模型列表 → 搜索弹窗点选回填。宽松解析 `data[].id`（兜底 `model`/`name` 字段、裸数组、只认字符串值），排序去重；解析不动返回空列表、401/404 等抛人话 AiException。**拉取失败仍可手填**。地址/Key 用编辑器当前值（未保存的新 Key 优先） |
| Key 加密存储 | — | AndroidKeyStore AES-256 + SecretCrypto 协议（12B IV + 128 位 GCM tag，`Base64(iv||ct)`），明文 Key 永不落库。每档案独立密文 `llm_key_<id>_enc`（`_enc` 后缀=备份导出自动排除、恢复不碰） |
| 密钥失效自愈 | — | 解密异常保留密文（keystore 可能暂时故障）；仅「结构性损坏」（k1:/s1: 标记但非合法 Base64）删除重填（decryptProfileKeyOrNull） |
| 旧配置迁移 | — | 首次 loadLlm 一次性迁移：主力→「主力」档；讲解模型名≠主力模型→「讲解」档；视觉组→「视觉转写」档（保持旧默认 LongCat 行为）；备用视觉三键齐→「备用视觉」档。Key 密文原值平移（同一 SecretStore 格式）。旧键不删（降级安全），幂等（llm_profiles 键存在即跳过） |
| URL 规范化 | — | trim → 去尾 / → 非 `/chat/completions` 结尾自动拼上；`normalizeModelsUrl` 同理拼 `/models` |
| SSE 流式 | — | OpenAI 兼容 `/chat/completions`，`stream:true`，onDelta 逐块回调 |
| 取消 | — | 哨兵子协程 `awaitCancellation()` + finally disconnect；取消型 IOException 还原 CancellationException |
| 错误分层 | — | 401/403→Key 无效、404→路径不存在、429→太频繁/额度尽；错误 body 限读 300 字节 |
| JSON 剥壳与修复重试 | — | stripFence + 配平扫描；chatJson 失败重试 1 次（空正文翻倍 maxTokens + 换提示）。**P0-② 降档梯子**（借鉴 bilingual_book_maker，MIT）：`ChatRequest.jsonMode=true` 时请求体带 `response_format={"type":"json_object"}`（OpenAI 兼容 JSON mode）；网关 400/422 拒收或解析/校验不过 → 自动降纯 prompt 档（被拒档位本轮不重打），已有输出作 assistant 上下文带入；`validate` 钩子对解析结果做结构校验、失败带纠正语重试一次。`jsonMode=false` 调用方行为与旧版逐字节一致（签名 validate 在 onDelta 前，尾随 lambda 语义不变）。当前唯一消费方：QuizJudge 判卷 |

## 关键文件

```
data/LlmProfiles.kt            （新）LlmProfile/LlmAssign/LlmRole/ResolvedLlm/LlmSettings 数据类
data/SettingsRepository.kt     loadLlm/saveProfile/deleteProfile/saveAssign/resolveLlm/seedMainProfile（测试）
                               + migrateLegacyLlmIfNeeded（一次性迁移）+ buildVisionTranscribers（改档案源）
data/ai/AiClient.kt            SSE 客户端 + listModels/normalizeModelsUrl（查找模型底层）
data/ai/SecretCrypto.kt        AES/GCM 协议（纯 JVM object）
data/ai/SecretStore.kt         接口 + KeyStoreSecretStore + FakeSecretStore（测试）
ui/screens/ModelsViewModel.kt  （重写）档案列表/编辑弹层状态机（ProfileEditor）/指派/查找/测试连接/OCR
ui/screens/ModelsScreen.kt     （重写）三张书房卡 + 编辑弹层（12dp 圆角输入）+ 指派选择弹层 + 模型选择弹层
ui/components/SectionCard.kt   （新）书房分区卡共用组件（设置页/模型页）
ui/screens/SettingsViewModel.kt refreshSummary 改读 loadLlm（主力模型名/Key 状态/讲解/视觉摘要）
data/study/NotePlanner.kt      resolveLlm(NOTE)（讲解档案；落库记实际模型供缓存判定）
data/study/RoughReadPlanner.kt 逐块批注 resolveLlm(NOTE)；章级归并 resolveLlm(MAIN)
data/study/SummaryPlanner.kt   resolveLlm(MAIN)（落库 ChapterAssetEntity.model）
data/study/OverviewPlanner.kt  resolveLlm(MAIN)
data/study/QuizJudge.kt        resolveLlm(NOTE)（判卷跟讲解）
ui/screens/QuickQaSheet.kt     resolveLlm(NOTE)（随手问）
ui/screens/ReNoteSheet.kt      resolveLlm(NOTE)（换个讲法）
test/.../SettingsRepoTest.kt   档案 CRUD/回退/迁移 9 用例（重写 AI 部分）
test/.../MiniHttpServer.kt     迷你 HTTP（listModels 用例）
test/.../AiClientTest.kt       14 用例（listModels 解析/裸数组容错/脏 body/401）
```

## 设计决策

10. **（已由 15 取代）按用途拆模型只拆一个角色** → 15 扩为四角色档案指派。
11. **查找模型用 GET /models 宽松解析**：OpenAI 兼容端点理论上都实现该接口，但中转站形状不一（裸数组、model/name 字段、数字 id）。策略：字符串值才收、排序去重、解析失败给空列表（VM 层转「服务商没返回模型列表，直接手填」）；HTTP 错误走 friendlyHttp 人话。**手填永远保留**。
12. **（已由 15 取代）讲解只配模型名跟主力端点** → 15 升级为完整档案。
13. **Key 无跨档回退**：resolveLlm 对指派档案只用自己的 Key，没 Key 即该角色不可用（UI 显示「还没填 Key」）——不做「回退主力 Key」：异家 Key 打异家 URL 无意义，且配置错误该直接暴露。
14. **旧配置迁移平移不重加密**：迁移把旧 `api_key_enc` 密文**原值**拷到新档案键（同一 SecretStore 加解密格式，无需重加密）；旧键不删（降级/回滚安全）；迁移读在事务内做存在性检查防并发双跑。
15. **档案体系（2026-11-15）**：档案 = 服务商连接单元（名称/地址/Key/模型/温度，存 settings KV JSON），四角色指派即点即存。盘点全项目 AI 任务挂到角色：总结/总览/导图/粗读归并 → MAIN；讲解/粗读批注/随手问/换个讲法/判卷 → NOTE；扫描页转写/目录探针/前置探针 → VISION；备用轮转 → VISION2。**档案列表永不为空**（首次自动建默认档），MAIN 指派失效回退第一档、NOTE/VISION 失效或未指派跟随主力、VISION2 未指派不启用。
16. **模型页信息架构**：三张卡——档案（是什么）/指派（怎么用）/本地识别（OCR 独立）。档案行首字圆徽 + 角色徽标（secondaryContainer 4dp 小矩形）+ 缺 Key 红字提示；指派行=任务名+说明+描边胶囊（当前档案名）；编辑弹层 12dp 圆角输入框（DESIGN.md 输入规范），测试连接与查找模型内聚在弹层里。

## 测试矩阵（档案体系部分）

- SettingsRepo 重写 AI 部分 9 用例：全新安装自动建默认档+幂等 / saveProfile 往返+明文不落库+不传 Key 不清旧 / resolveLlm 四角色回退+没 Key 不跨档回退 / deleteProfile 回收指派与 Key / 解密失败保留+结构性损坏删除 / clearProfileKey / 完整旧配置迁移 4 档案 / 讲解=主力模型不立档 / 仅主力的迁移仍有默认视觉档
- Planner/Runner 测试播种改 `seedMainProfile`（8 处），NotePlannerTest 换讲解模型用例改「另立档案+指派」

## 已知坑与约束

- MiniHttpServer 仅支持单请求短连接；listModels 用例沿用该设施（AGP --release 无 jdk.httpserver）。
- 「查找模型」对未保存的地址也生效（fetchModels 直接用编辑器值），但 Key 必须已保存或在输入框里。
- 视觉开关与视觉/备用指派变更都会触发 VisionScheduler.reenqueueAllPending（OPT-F）；讲解/主力指派变更不影响队列（讲解按需同步调用）。
- 设置页入口摘要（refreshSummary）只在进入设置页时刷新；模型页改完 popBack 回来 LaunchedEffect 会重跑。
- 档案 temperature 只对「直接用 cfg.temperature 的调用」生效（旧模型页测试连接）；各 Planner 的温度均为硬编码（讲解 0.5、总结 0.4、粗读 0.2、判卷 0.2），档案温度不覆盖任务温度——行为与旧版一致。
- 新装用户视觉角色默认跟随主力档案：主力模型须带视觉，否则应在指派里给视觉单独指派带视觉的档案（编辑弹层副标题有提示）。

## 对后续模块的接口承诺

- 新增 AI 功能时按「用量×质量」决定挂哪个角色：逐段高频 → `resolveLlm(LlmRole.NOTE)`；低频高质量 → `resolveLlm(LlmRole.MAIN)`。返回 null = 未配置好（Key 缺失/无档案），调用方给「请到设置页填写」人话。
- 模型列表拉取复用 `AiClient.listModels(baseUrl, apiKey)`；档案编辑器内已内聚查找/测试，新角色只需加进 LlmRole 与指派卡。
- 加密键沿用 `_enc` 后缀约定，备份层（BackupManager SETTING_ENC_SUFFIX）自动排除，无需改备份代码。

## 最后更新

- 2026-11-28 **修「Edge 在线无法点击」**：`SettingsViewModel.chooseTtsEngine` 卫语句白名单漏了 edge（新增三选时没同步更新，点「Edge 在线」chip 被直接 return 弹掉——装机实录根因），三选补全。`tts_engine` 落库值域本来就是三值，仅 VM 层拦截错。
- 2026-11-22 **TTS 三模式+Edge 在线朗读设为默认（修「点朗读闪退」）**：设置页「朗读」卡引擎 chips 扩为三选（**Edge 在线（新默认）/手机自带/离线模型**），新增 Edge 音色行（`tts_edge_voice`：晓晓/云希，选 Edge 时显示）+ 联网说明文案；`tts_engine` 键值域扩 edge、**坏值/未设置回退从 system 改为 edge**（老板真机点朗读闪退、两条旧引擎路径真机从未出声验证过，Edge 零依赖绕开；显式选过引擎的用户保持其选择）。引入 `com.squareup.okhttp3:okhttp:4.12.0`（WebSocket，java.net.http 要 API 34+）；mockwebserver 同版本仅测试。引擎/协议细节见语音朗读模块总览。
- 2026-11-22 **AI 请求全局限流（第九波实景体验轮）**：新 `data/ai/AiRateLimiter.kt`（滑动窗口 RPM 限流，借鉴 anx-reader v1.14.0 "Add global AI RPM rate limiting" MIT 学思路自写）——每分钟默认 15 次（`DEFAULT_LIMIT_PER_MINUTE`），窗口内最早一次成功领号起算 60s，满了 acquire 挂起等最早记录滑出（+50ms 余量防时钟精度再睡一轮）；`nowMs`/`delayFn` 注入可测（假时钟推进；**实测坑：假 delayFn 若不推进时钟也不真挂起，acquire 的 while 循环热循环卡死测试**）。接入点唯一：`AiClient.chat` 入口 `rpmLimiter.acquire()`——chatJson 各档/视觉转写/随手问/判卷全部收口 chat 自动覆盖，`listModels` 不限（不是对话请求）。防「一键分析全书」批量链路一分钟打爆免费额度（撞 429 后退避重试反而更费）；正常单章链路（块串行每块数秒）不受影响。测试 AiRateLimiterTest 4 例（未满即领/满了等滑出含等待时长断言/滑窗释放/非法 limit 拒绝）。
- 2026-11-21 **实景体验轮第七波（本轮）设置层两键**：①`read_font_family`——阅读字体 id（"default"=系统 / "lxgw"=霞鹜文楷 Lite v1.530，OFL-1.1 随包再分发、许可文本 `assets/OFL-LXGWWenKai-Lite.txt`；字体文件 `res/font/lxgw_wenkai_lite_regular.ttf` 13.3MB，APK 相应增大），`readFontFamilyFlow()` watch 供阅读页即时切换（详见 M4a）；②`read_brightness`——阅读页亮度（-1=跟随系统 / 0.15~1.0 固定值，坏值回退 -1），滑条拖动内存态即时生效、关弹层落库，只覆盖阅读页窗口离页恢复（详见 M4a）。零 schema 变更（settings KV）。
- 2026-11-21 **实景体验轮第六波（上一轮）设置层两键**：①`read_theme`——阅读背景主题包 id（"follow"=跟随书房默认 / ReadingTheme.ALL 五套固定纸色），`readThemeFlow()` watch 供阅读页即时切换（详见 M4a）；②`read_volume_key`——音量键翻页开关（默认关），开时阅读页向 `StudyApp.volumeKeyTurnHook` 注册回调、MainActivity.dispatchKeyEvent 拦音量加/减转翻页并消费（防系统音量条弹出），离页注销。零 schema 变更（settings KV），Aa 弹层即开即存（借鉴 legado，详见 docs/调研/第九节）。

- 2026-11-21 **实景体验轮第四波（本轮，主测《袖珍民法典评注》条文评注体装机）AI 侧一件**：**推理模型 `<think>` 思考段抑制**——MiniMax-M2.5 等推理模型把思考过程以 `<think>…</think>` 内联在正文增量里流出，随手问装机实录整段英文思考草稿原文上屏。新 `ThinkSuppressor`（ThinkFilter.kt）：流式状态机收口在 `AiClient.chat` 增量链路——标签跨 SSE 分片不漏不提前上屏（半截开/闭标签扣住）、未闭合 think 不吃正文、正文以半截标签收尾时 finish 放行；onDelta 与返回值全部消费者一次过滤受益（随手问/换个讲法/讲解/总结/粗读/判卷/视觉），落库内容不再混思考段。AiClientTest +2（分片抑制/未闭合返回空）+ 新 ThinkSuppressorTest 7 例。装机复测：随手问回答只剩中文正文（截图 bookocr-kit/.e2e/user_exp4/03_qa_no_think.png）。

- 2026-11-21 **实景体验轮第三波（本轮，主测《债法原理-王泽鉴2022》装机）两件**：①**连接层错误人话化**——`AiClient.friendlyIo(e)` 新增：按 IOException 类型给人话建议（UnknownHost→域名解析失败/ConnectException→连不上服务器/SocketTimeout→连接超时或响应超时（按 message 含 connect 区分）/SSLException→HTTPS 握手失败/其余→网络连接失败），技术细节截 120 字附「（详情：…）」；chat 与 listModels 两处 `catch (IOException)` 从「网络异常：${e.message}」改走 friendlyIo——装机实录随手问把 OkHttp 连接超时 Java 原文（`failed to connect to vsllm.com/2a03:2880:… after 15000ms`）整段亮给用户。AiClientTest +1 例（4 类型断言+长度上限）。②**编辑档案弹层换 ModalBottomSheet**——原 AlertDialog 在键盘弹出时保存按钮被键盘盖住（compose ui 1.7 DialogProperties 无 softInputMode 参数、窗默认 ADJUST_PAN，decorFitsSystemWindows=false+imePadding+DialogWindowProvider 改 RESIZE 三招都救不动 M3 AlertDialog 不收缩的 text 区，dumpsys 实测三次复现）；换项目内久经键盘考验的 ModalBottomSheet（随手问/Aa 弹层同款），表单字段原样、取消/保存内嵌 sheet 底部，键盘弹出内容收缩按钮全程可达。装机复测：抽屉形态全字段+保存按钮可见（截图 bookocr-kit/.e2e/user_exp3/09_fix_save_reachable.png）。

- 2026-11-21 **实景体验轮第二波设置页一件**：AI 模型卡未配置文案「主力：gpt-4o-mini（未配置 API Key）」→「主力：未配置 API Key，点进去填好就能用」——装机实录新装机显示默认档案模型名是噪音（Key 都没填，模型名没有意义），文案改动作引导（SettingsScreen.kt）。已配置时仍显示「主力：<模型名>」不变。

- 2026-11-15 **P0-② 降档梯子轮**：AiClient.chatJson 加「JSON mode→纯 prompt+剥壳提取→校验重试」降档链（详见功能清单「JSON 剥壳与修复重试」行）；`ChatRequest` 加 `jsonMode` 字段（默认 false 零回归面）；chatJson 签名 validate 挂 onDelta 前（保持全部既有尾随 lambda 调用语义）；`AiClientChatJsonFn` 适配器改 onDelta 命名传参。AiClientTest +3 例（response_format 进请求体 / 400 降档不再带 / 校验失败纠正语重试后抛结构不合格）。接入方：QuizJudge（M5 同日条）。粗读归并等其余 chatJson 调用方行为零变化（后续任务按需接入 jsonMode）。

- 2026-11-15 **档案体系多模型轮（本轮，老板点名「很多 LLM 设置、不同功能自己选、界面要漂亮」）**：
  ①**数据层**：新增 `LlmProfile`（名称/地址/Key/模型/温度）与 `LlmAssign`（四角色指派），存 settings KV JSON；每档 Key 独立加密 `llm_key_<id>_enc`；`resolveLlm(role)` 统一解析端点（回退规则见决策 15）；旧配置（api_base/api_key_enc/note_model/vision 两组）首次 loadLlm 自动迁移成档案，密文原值平移、旧键不删、幂等；
  ②**消费方收口**：NotePlanner/RoughReadPlanner/SummaryPlanner/OverviewPlanner/QuizJudge/QuickQaSheet/ReNoteSheet 七处从 `cfg+decryptKeyOrNull` 改为 `resolveLlm(角色)`；buildVisionTranscribers/buildTocVisionParser/buildFrontMatterVisionParser 改 VISION/VISION2 角色；SettingsSnapshot 瘦身（只剩 visionEnabled/github token/更新/OCR 五个非端点键），save/saveVision/saveVision2/saveNoteModel/decryptKeyOrNull 等旧方法删除；
  ③**UI 重做**：ModelsScreen 三张书房卡（档案/指派/本地识别）+ 编辑弹层（12dp 圆角输入、查找模型、测试连接、删除）+ 指派选择弹层（radio 列表+跟随主力/不启用选项）+ 档案行角色徽标与缺 Key 提示；SectionCard 提取为共用组件（ui/components）；设置页入口卡摘要改读档案体系；
  ④**测试**：SettingsRepoTest AI 部分重写 9 用例（CRUD/回退/迁移），Planner 播种 8 处改 seedMainProfile，NotePlannerTest 换模型用例改档案指派。迁移中修一处 bug：视觉档案有专属 Key 时平移专属密文（原写漏 else 分支）。

- 2026-11-15 **P1-⑤ 激励统计轮新增设置项**：①「每日阅读目标」卡（讲解详略卡之后）：−/＋ 步进 5 分钟即点即存（`SettingsViewModel.changeDailyGoal`，coerceIn 5..600），KV `read_daily_goal_minutes` 默认 30——复习页统计卡进度环与热力图色阶的分母（见 M6 同日条）；②复习排序模式 KV `review_sort_mode`（`REVIEW_SORT_MODES=["risk","due","random"]`，`readReviewSortMode/saveReviewSortMode`）——复习页排序切换持久化（见 M6 同日条）。AI 配置零变化。

- 2026-11-15 **UI 打磨轮：设置页卡片化**：分区从「细分割线」改为 surfaceContainer 圆角 16dp 分区卡（SectionCard 私有组件，组头 labelLarge 在卡内），细线全删；朗读语速/朗读引擎/离线模型三块合进一张「朗读」卡（同一件事的三面）；备份与恢复、诊断各成一张卡。配置项与落库键零变化，纯视觉重排。模拟器浅/深两主题实测。本轮 SectionCard 已提取为 `ui/components/SectionCard.kt` 共用。

- 2026-10-26 **数据安全与体验增量轮：设置页「备份与恢复」+「诊断」区块 + AiGate 排队**：
  ①**备份与恢复**（BackupSection）：SAF CreateDocument 导出 JSON（默认名 studyfriend-backup-yyyyMMdd-HHmm.json）+ OpenDocument 选文件→先只 peek 解析校验（版本/引用完整性，不动库）→确认框明示「当前全部学习数据将被覆盖」→confirmRestore 才整库替换（BackupManager 见 M1）；恢复完成回读设置页五项状态防残留旧值；
  ②**诊断日志**（`data/DiagLog.kt` object，StudyApp.onCreate 挂载）：filesDir/logs/ 按天分文件滚动保留 7 天；崩溃捕获挂 Thread.setDefaultUncaughtExceptionHandler（先写盘再交原 handler）；AiTaskRunner 四 Runner 的失败/取消/占线原因写入（一处埋点覆盖四个任务）；设置页「导出诊断日志」按钮 SAF 存最近 7 天合并 txt（DiagLogTest 5 例）；
  ③**AI 排队**：AiGate 加 awaitBegin(tag, timeoutMs)（Mutex.lock 挂起+withTimeoutOrNull，取消即退出队列无半获得锁）→ AiTaskRunner 抢锁从 tryLock 立即拒绝改排队（Queued 态→UI「排队等待中」，10 分钟超时失败态；stop 可取消排队），AiGateTest 4 例。
  AI 配置零变化。

- 2026-10-25 **功能补齐轮新增设置项（SettingsScreen + SettingsViewModel + SettingsRepository）**：①外观三 FilterChip（system/light/dark → MainActivity watch 即时生效）；②朗读语速 Slider 0.5~2.0 倍（KV `tts_rate`，拖完落库，正文听书与记忆思路朗读共用）；③复习到期提醒 Switch（KV `review_reminder` 默认开，开关同步注册/取消 WorkManager 每日任务——ReviewReminderWorker.schedule/cancel 必须包 runCatching，Robolectric 沙盒 WorkManager 未初始化会炸 21 个 UI 冒烟测试）；Android 13+ 打开开关时顺带请求 POST_NOTIFICATIONS 权限（rememberLauncherForActivityResult）。AI 配置零变化。

- 2026-10-11 **优化批次**：AiBudgets 从单一输入预算扩为输出预算+超时集中地（OUTPUT_TOKENS_PLANNER=16000、粗读线性公式常量、视觉页 8000/判定 2000、连接/读超时五档）；NotePlanner/SummaryPlanner/OverviewPlanner/RoughReadPlanner/VisionTranscriber/TocVisionParser/AiClient 九处字面量收口引用。

- 2026-10-11 模型页重构：新建 ModelsScreen/ModelsViewModel（Routes.MODELS），设置页瘦身（删快速填入与平铺视觉三组），AiClient.listModels，note_model 讲解模型角色接入 NotePlanner/RoughReadPlanner，测试 14+9。
- 2026-10-05 P6b S5 模型下载入口；2026-10-01 M7 接入。
