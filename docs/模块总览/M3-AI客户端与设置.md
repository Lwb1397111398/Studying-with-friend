# M3 AI客户端与设置 · 模块总览

> 状态：✅ 完成（M3 原始 91 分质检通过；2026-10-11 模型页重构收口）
> 验证：全项目单测全绿（AiClientTest 14、SettingsRepoTest 9），assembleDebug 通过

## 模块职责

一句话：管 AI 的「连接」（OpenAI 兼容端点、Key 加密、SSE 流式、错误文案）和「配置面」（模型页 + 设置页）。

## 功能清单（2026-10-11 模型页重构后）

| 功能 | 所在页面 | 说明 |
| --- | --- | --- |
| 模型页（新） | ModelsScreen（设置页入口的二级页，Routes.MODELS） | 全部 AI 模型配置收拢一页：主力模型 / 讲解模型 / 视觉转写 + 备用视觉 / 本地 OCR。区块化展示，各区块 HorizontalDivider 分隔 |
| 主力模型 | 模型页 | API 地址 / Key（加密）/ 模型名 / 温度 / 测试连接（流式 ping + 实时预览）。用于章末总结、全书总览、思维导图、粗读章级合并 |
| 「查找模型」（新） | 模型页各模型输入框旁 | `AiClient.listModels`：GET `{base}/models`（Bearer）拉服务商模型列表 → 搜索弹窗点选回填。宽松解析 `data[].id`（兜底 `model`/`name` 字段、裸数组、只认字符串值），排序去重；解析不动返回空列表、401/404 等抛人话 AiException。**拉取失败仍可手填** |
| 讲解模型（新，按用途选模型） | 模型页 | 开关「讲解单独用低成本模型」+ 专属模型名（可查找）。**只有模型名，地址/Key 永远跟主力服务商**；空 = 跟随主力。生效点：NotePlanner 段落讲解 + RoughReadPlanner 逐块批注（两个逐段高频任务）；章级合并/总结/总览始终主力（出题质量要求高）。落库 `ParaNoteEntity.model` 记实际用的模型 |
| 视觉转写（OPT-E） | 模型页 | 开关即点即存 + 视觉模型名（可查找）+ 视觉专属地址/Key（留空回退主力）。扫描页/坏字页整页转写 |
| 备用视觉（R3 轮转） | 模型页 | 地址/模型名/Key 三键齐才入轮转列表；不回退主力 Key（异家 Key 打异家 URL 无意义） |
| 扫描书本地识别（P6b） | 模型页 | 从设置页迁入：开关 + 模型就绪态 + 「下载识别模型」按钮（~21MB，GitHub 令牌下载，进度条防重入） |
| 设置页（瘦身） | SettingsScreen | 只剩：模型入口卡片（主力模型名 + Key 状态 + 讲解/视觉摘要，LaunchedEffect 每次进入刷新）、讲解详略三档、应用更新（M7）。**「快速填入」预设与平铺的视觉三组已删除（老板 2026-10-11 反馈：太乱）** |
| Key 加密存储 | — | AndroidKeyStore AES-256 + SecretCrypto 协议（12B IV + 128 位 GCM tag，`Base64(iv||ct)`），明文 Key 永不落库 |
| 密钥失效自愈 | — | 解密异常保留密文（keystore 可能暂时故障）；仅「结构性损坏」（k1:/s1: 标记但非合法 Base64）删除重填 |
| URL 规范化 | — | trim → 去尾 / → 非 `/chat/completions` 结尾自动拼上；`normalizeModelsUrl` 同理拼 `/models` |
| SSE 流式 | — | OpenAI 兼容 `/chat/completions`，`stream:true`，onDelta 逐块回调 |
| 取消 | — | 哨兵子协程 `awaitCancellation()` + finally disconnect；取消型 IOException 还原 CancellationException |
| 错误分层 | — | 401/403→Key 无效、404→路径不存在、429→太频繁/额度尽；错误 body 限读 300 字节 |
| JSON 剥壳与修复重试 | — | stripFence + 配平扫描；chatJson 失败重试 1 次（空正文翻倍 maxTokens + 换提示） |

## 关键文件

```
data/ai/AiClient.kt            SSE 客户端 + listModels/normalizeModelsUrl/parseModelIds（查找模型底层）
data/ai/SecretCrypto.kt        AES/GCM 协议（纯 JVM object）
data/ai/JsonSlicer.kt          JSON 剥壳（配平扫描）
data/ai/SecretStore.kt         接口 + KeyStoreSecretStore + FakeSecretStore（测试）
data/SettingsRepository.kt     load/save/saveVision/saveVision2/saveNoteModel（新）/clear* /decrypt* /buildVisionTranscribers
ui/screens/ModelsViewModel.kt  （新）模型页状态机：三组配置 + 查找模型弹窗（ModelPickerTarget 四目标）+ OCR 下载 + 测试连接
ui/screens/ModelsScreen.kt     （新）模型页 UI：区块化 + ModelField（输入框+查找按钮）+ 搜索选择 AlertDialog
ui/screens/SettingsViewModel.kt（瘦身）只剩讲解详略 + refreshSummary（模型入口摘要）
ui/screens/SettingsScreen.kt   （瘦身）模型入口卡片 + 讲解详略 + 应用更新
ui/nav/AppNav.kt               Routes.MODELS + composable 接线（modelsVm）
data/study/NotePlanner.kt      讲解请求 model = cfg.noteModel.ifBlank { cfg.model }（落库同步记实际模型）
data/study/RoughReadPlanner.kt runBlockOnce（逐块批注）用讲解模型；mergeOverview（章级）用主力
test/.../MiniHttpServer.kt     迷你 HTTP（新增 MiniResponse.json() 供 listModels 用例）
test/.../AiClientTest.kt       14 用例（+4：listModels 解析/裸数组容错/脏 body/401）
test/.../SettingsRepoTest.kt   9 用例（-presets 用例，+noteModel 往返与清空回主力）
```

## 设计决策（新增部分）

10. **按用途拆模型，只拆一个角色**：盘点全项目 AI 任务——逐段类（段落讲解、粗读批注）量大、质量要求相对低；总结/总览/出题类量小、质量要求高（复习题源自章末总结）。故只拆「讲解模型」一个角色（note_model），总结/总览/章级合并固定主力，不再加复习专用模型（复习中心本身无 AI 调用，出题在 M5）。UI 开关默认关（跟随主力），老用户零感知。
11. **查找模型用 GET /models 宽松解析**：OpenAI 兼容端点理论上都实现该接口，但中转站形状不一（裸数组、model/name 字段、数字 id）。策略：字符串值才收、排序去重、解析失败给空列表（VM 层转「服务商没返回模型列表，直接手填」）；HTTP 错误走 friendlyHttp 人话。**手填永远保留**——模型列表接口不是所有网关都有。
12. **讲解模型只配模型名，不配地址/Key**：讲解跑在主力服务商端点上（cfg.baseUrl + decryptKeyOrNull）。拆独立服务商会让「服务商」升级为实体、需要迁移现有键，收益配不上复杂度；真要多服务商时再升级。
13. **查找的 Key 取值优先级**：输入框里未保存的新 Key > 已存密文（主组）；视觉组专属 Key > 主力 Key（复用 resolveVisionKeyOrNull）；备用组只认自己的 Key（与轮转语义一致）。
14. **picker 状态机**：`ModelPickerTarget` 四目标（MAIN/NOTE/VISION/VISION2），fetchModels 按目标解析端点、成功后 pickerModels 非空弹 AlertDialog（搜索框 + LazyColumn 380dp），pickModel 回填目标字段并提示「记得点保存」。NOTE 目标点选同时把 noteFollowMain 置 false。

## 测试矩阵（本轮新增）

- AiClient +4：listModels 正常解析（断言 GET path=/v1/models + Bearer 头 + 排序）/ 裸数组+model/name 字段+数字 id 不收 / HTML 脏 body 空列表不崩 / 401 人话
- SettingsRepo +1：noteModel 默认空、save→load 往返、trim、空串/null 清空回主力

## 已知坑与约束

- MiniHttpServer 仅支持单请求短连接；listModels 用例沿用该设施（AGP --release 无 jdk.httpserver）。
- 「查找模型」对未保存的地址也生效（fetchModels 直接用输入框值），用户可不保存先看列表；但模型选择后仍须点「保存」落库。
- 视觉开关即点即存会触发 VisionScheduler.reenqueueAllPending（OPT-F）；讲解模型变更不影响队列（讲解是按需同步调用）。
- 设置页入口摘要（refreshSummary）只在进入设置页时刷新；模型页改完 popBack 回来 LaunchedEffect 会重跑，无需额外事件总线。

## 对后续模块的接口承诺

- 新增 AI 功能时按「用量×质量」决定挂哪个角色：逐段高频 → noteModel（讲解）；低频高质量 → cfg.model（主力）。取值写法统一 `cfg.noteModel.ifBlank { cfg.model }`。
- 模型列表拉取复用 `AiClient.listModels(baseUrl, apiKey)`；VM 层照抄 ModelsViewModel.fetchModels 的目标解析模式即可加新角色。

## 最后更新

- 2026-10-11 模型页重构：新建 ModelsScreen/ModelsViewModel（Routes.MODELS），设置页瘦身（删快速填入与平铺视觉三组），AiClient.listModels，note_model 讲解模型角色接入 NotePlanner/RoughReadPlanner，测试 14+9。
- 2026-10-05 P6b S5 模型下载入口；2026-10-01 M7 接入。
