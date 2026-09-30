# M4b 计划案：段落讲解生成 + 批量队列 + 讲解卡

> 里程碑定位（总计划 §M4）：M4b = 讲解生成+缓存+批量队列+讲解卡片。M4a 已交付标注（aiAction/groupId/why）与章级 gist；本计划把"标注"升级为"讲解"。
> 上游契约（M4a 总览）：讲解单元 = `aiAction=="EXPLAIN"` 的段 + `aiAction=="GROUP"` 的组（组首 `groupId==idx`，组员共享 groupId）；ParaNoteEntity 全字段已建未写。
> 本计划不含：章末总结包（M5）、自测问答 UI（M5，但 questions 字段本次一并生成落库省额度）、TTS、讲解编辑、单段生成按钮（延 M7）。

## §0 范围与非目标

**做**：讲解单元枚举 → 讲解 prompt（一次调用生成全部字段）→ 批量队列（缓存命中跳过、断点续跑、块级重试、中断保留）→ ParaNote 落库（同 paraIds 不堆积）→ AiGate 全局 AI 互斥（讲解⇄粗读）→ 阅读页讲解卡 + 讲解工具条（批量入口/进度/停止/force 重生成）。

**不做**：单段生成按钮（M7）、讲解流式逐字渲染（卡片等单元完成再刷新，Flow 天然支持）、讲解内容编辑、M5 自测 UI。

## §1 现状盘点

**已有**：`ParaNoteEntity(chapterId, paraIds: String, title, friendly, analogy, keyPointsJson, memoryHook, questionsJson, model, promptVersion, createdAt)`；`ParaNoteDao(byChapterFlow/insert/deleteForChapter)`；`RoughReadRunner`（全局单例+StateFlow 五态模式可复制）；`AiClient.chatJson(req, deserializer, onDelta)`；`PromptLoader`；`MissingKeyException` 结构化无 Key 错误；ReadScreen ParaRow 已按 aiAction 渲染。

**需补**：
- `ParaNoteDao` 补：`byChapterOnce(chapterId): List<ParaNoteEntity>`、`deleteByParaIds(paraIds: String)`、`deleteById(id: Long)`
- assets/prompts/explain_note.txt + NoteModels（单元枚举/输出模型/容错解析）+ NotePlanner（流水线）+ NoteRunner（互斥队列）+ AiGate（全局互斥）
- ReadScreen 加 NoteBar（批量入口）+ NoteCard（讲解卡）；StudyApp 注入 aiGate/noteRunner
- RoughReadRunner 接入 AiGate：跨任务互斥 + 自替换改为 join 旧 job 后重获（对外行为不变，§2.6）

## §2 设计

### 2.1 讲解单元枚举（NoteModels.enumerateUnits，纯函数可测）

**按 groupId 值聚合**（与 M4a countUnits 的 groupId 集合计数对齐，保证"共 N 个讲解单元"文案与实际卡数一致）：

- 输入按 idx 排序段落；输出 `List<ExplainUnit(anchor, members)>`：
  - EXPLAIN 段 → unit(anchor=自身, members=[自身])
  - GROUP 段按 `groupId` 值分组（每组一个单元）：**锚段 = 组内 `idx == groupId` 的成员**；锚段缺失（M4a 允许组首段被标 EXPLAIN/NONE 而组员仍指向它的孤儿数据，PlanParser 不禁止）→ 取组内 idx 最小的段当锚，**仍成组单元**（不丢组员卡，"合并讲"徽标与卡对齐）
  - 组员不足 2 段 → 退化为单段单元（members=[anchor]）；组员超过 3 段 → 截前 3 段
  - SKIP、NONE、groupId 为 null 的 GROUP → 不成单元
- **ExplainUnit.textForPrompt**：members 每段截 2500 字、合计截 6000 字（低内存 + token 预算）；带段间序号便于 AI 引用

### 2.2 讲解 prompt（assets/prompts/explain_note.txt，system）

**对齐总计划 §75 段落讲解规格**（评审 P1-4 复核基准；字段名沿用总计划）：

- 角色设定沿用总计划 §4.3 搭子语气（讲人话、短句、联系前文、偶尔反问、禁说教腔）
- 输出 JSON：`{"title":"≤16字","friendly":"300~600 字朋友口吻讲解","analogy":"≤80 字或空串","key_points":["≤30字","2~5 条"],"memory_hook":"≤40 字或空串","check_questions":[{"q":"≤40字","a":"≤80字"}]}`（check_questions 0~3 条，本次一并生成落库省额度，M5 自测卡直接消费）
- user 消息程序拼：`{"gist":"<章 gist 或空>","terms":"<章 key_terms 的 term 列表，提示避免重复解释>","why":"<组内各段 why 以'；'连接，单段即自身 why，或空>","context_prev":"<anchor 前一段截 800 字或空>","context_next":"<anchor 后一段截 800 字或空>","paragraphs":[{"id":0,"text":"<单元段全文，按 2.1 截断>"}]}`（jsonEsc 转义，同 M4a 手拼惯例）
- **讲解详略设置归属**：总计划 §14 的"简略/标准/深入"M4b 固定"标准"档（friendly 300~600 字写入 prompt）；档位选择 UI 延至 M7 设置页扩展（届时经占位符注入，prompt 版本号不变）
- 参数：temperature **0.5**、maxTokens **2000**（各字段上限合计约 1100 字 + JSON 结构，防截断引发剥壳失败→重试→额度放大）、promptVersion 常量 `NOTE_VERSION = "explain-note-v1"`（**落库**，缓存失效判定依据）

### 2.3 解析容错（NoteParser）

`@Serializable NotePlan(title, friendly, analogy, key_points, memory_hook, check_questions)` + `Json { ignoreUnknownKeys = true }`，校验规则：
- friendly 空白 → 抛 `PlannerException`（讲解主体缺失，计入单元失败走重试；与缺标同路径）
- title 空白 → 取 anchor 段前 16 字补位；key_points 过滤空白后截 5 条、每条截 30 字；analogy/memory_hook 截 80/40 字；check_questions 过滤 q 或 a 空白的，截 3 条
- **落库前规整为 ParaNoteEntity**：`paraIds = ParaIdsCodec.encode(members.map{it.id})`（`[1,2,3]` 确定性字符串，同单元恒同串）；**ParaIdsCodec 提供 encode+decode 成对**（UI 建 anchor→note 映射用 decode）；keyPointsJson 用 KeyTermsCodec 同款 ListSerializer；model/promptVersion/createdAt 填实

### 2.4 批量流水线（NotePlanner.run(chapterId, force=false)，注入 db/context/settings/chatJsonFn/NOTE_VERSION 常量）

1. 前置：章节/段落存在性、`decryptKeyOrNull`（无 → MissingKeyException，同 M4a）
2. 枚举单元（2.1）→ **孤儿 note 清理**（评审 P2-5 落点；force 粗读改变组结构后旧 paraIds 变孤儿行——byChapterFlow 仍返回但永不匹配当前单元，NoteBar 有效计数与卡片渲染会错位）：`byChapterOnce` 逐行比对当前单元 paraIds 集合，不在集合中的行 `deleteById` 删除。**清理在空单元早退之前执行**（评审 P2：章曾有 note、force 粗读后全部变 SKIP 时，早退分支也会残留孤儿行）
3. 空单元列表直接返回 Succeeded("本章没有需要讲解的段落"，unitCount=0)
4. 缓存判定（force=false）：按 paraIds 精确匹配已有 note，`promptVersion == NOTE_VERSION && friendly 非空` → 命中跳过；**promptVersion 过期或 friendly 空 → 视为待重生成**（先 `deleteByParaIds` 再生成，防堆积）；force=true 全部重生成（全部先删后建）
5. 逐单元串行（Dispatchers.IO，内存纪律）：`chatJson` → NoteParser → 先 deleteByParaIds 旧行再 insert 新行（**同 paraIds 唯一**）→ onProgress(done, total)；单元失败：ATTEMPTS=3 重试（401/403/404 直断，同 M4a UNRETRYABLE），仍败 → 中断退出，已完成单元已落库（**天然断点**：重跑时缓存命中跳过）
6. 返回 RoughReadOutcome 同构结果（unitCount=本次实际生成的讲解数）

### 2.5 AiGate 全局互斥（新增，data/ai/AiGate.kt）

```kotlin
class AiGate {
    private val mutex = Mutex()
    val label = MutableStateFlow<String?>(null)   // null=空闲，否则"粗读"/"讲解"
    val busy: StateFlow<Boolean>
    fun tryBegin(tag: String): Boolean            // 非阻塞：忙则 false（Mutex.tryLock 原子）
    fun end()                                     // 仅由 tryBegin 成功的持有者在 finally 调用
}
```

- NoteRunner 与 RoughReadRunner 构造注入同一 AiGate（StudyApp 创建一次）；gate 本身是并发防线
- UI 双保险：按钮 `enabled` 同时看对方 runner 状态与 `gate.busy`
- 可测性：纯协程类无 Android 依赖，测试直接 new（无需假 gate）

### 2.6 自替换竞态修复（评审 P0-1，两个 Runner 通用）

`job?.cancel()` 后旧 job 是**异步**退出的：新协程若立即 `tryBegin`，旧 job 可能尚未执行 finally `gate.end()`，"替换自己"被误判为"另一项 AI 任务进行中"（快速双击、既有测试 startB_cancelsA 必触发）。修复模式：

```kotlin
fun start(...) {
    val old = job
    old?.cancel()
    job = scope.launch {
        old?.join()                      // 等旧 job 完全退出（含其 finally gate.end()）
        if (!gate.tryBegin("讲解")) { _state.value = Failed(...); return@launch }
        try { /* planner.run → finishState */ } finally { gate.end() }
    }
}
```

- join 有界性：管道全链路响应取消（M3 哨兵 disconnect、M4a ensureActive），旧 job 取消后毫秒级退出，join 不长挂
- **stop() 保留 job 引用**（评审 P2：stop 置 job=null 会让紧随的 start 捕获不到旧 job、无法 join，旧 job 的 finally gate.end() 未跑完时 tryBegin 假失败）：stop() 只 cancel 不清引用；start 捕获的 old 即使已 cancel 也能 join
- **Running 置位时机**：`tryBegin` 成功后才置 Running（join/tryBegin 期间保持前一状态，UI 瞬时显示与 awaitState 式测试时序以此为准）
- 同 runner 自替换 = join 后重获 gate（语义不变）；跨 runner（讲解⇄粗读）= tryBegin 失败 → Failed("另一项 AI 任务进行中")
- RoughReadRunnerTest.startB_cancelsA 在 join 语义下仍成立（B 等 A 退出后运行）

### 2.7 NoteRunner（全局单例，复制 RoughReadRunner 模式 + §2.6 竞态修复）

- `NoteRunState`：Idle / Running(chapterId, done, total) / Succeeded(chapterId, message, unitCount) / Interrupted(chapterId, message) / Failed(chapterId, message, keyIssue=false)——与 RoughRunState 同构不复用（类型清晰，避免泛型化复杂度）
- `start(chapterId, force=false)`：按 §2.6 模式 → planner.run → finishState（中断文案"已完成 x/y 条讲解，中断：<原因>。已完成部分已保存，稍后可继续"）；`stop()`；取消时 `coroutineContext[Job] === job` 才回 Idle（M4a 踩坑 5）
- catch CancellationException 先行还原（M3 踩坑 8）；keyIssue = e is MissingKeyException

## §3 UI 设计（ReadScreen 扩展）

### 3.1 NoteBar（讲解工具条，插在本章要点卡之下）

> 实现期微调（实现质检 93 分 P2-1 留档）：实际渲染在要点卡之上（ActionBar 之后、要点卡之前）——要点卡要粗读完成后才出现，讲解入口放在其上方可保证任何时刻可达；功能与四态逻辑无影响。

状态按"单元总数 vs 已有有效 note 数"数据推导（进程重启可恢复，同 M4a 断点哲学）：
- 讲解进行中（NoteRunner.Running 本章）→ "搭子正在讲解 x/y" + 停止
- 无任何单元（enumerateUnits 空）→ 不渲染
- 全部有有效 note → "本章讲解已全部生成 (N)" + "重新生成讲解"（force，弹二次确认，复用 showForceConfirm 模式与文案风格）
- 部分/无 note → "让搭子讲解本章 (已有 x/N)" 主按钮 + force 重生成次按钮
- 粗读进行中或 gate.busy → 按钮禁用（enabled=false + "粗读完成后可生成讲解"）

### 3.2 NoteCard（讲解卡，渲染在 anchor 段 ParaRow 之下）

- 有 note（anchor.id ∈ note.paraIds 且 promptVersion 一致）：Surface 卡片——title 粗体 + friendly 正文 + analogy（"打个比方："前缀，空则不渲染）+ key_points 列表（• 前缀）+ memory_hook（"记忆钩子："前缀，空则不渲染）；questions 不显示（M5 自测卡用）
- 无 note 且本章讲解 Running → 该单元显示占位"搭子正在讲这一段…"（Running 态无当前单元 id，本章所有未成卡单元统一占位，不做单单元粒度）
- 无 note 未生成 → 不渲染卡（批量入口在 NoteBar；避免 N 个按钮）
- notes 来源：`ParaNoteDao.byChapterFlow` collect → `Map<Long, ParaNoteEntity>`（anchor.id 首个匹配；内存纪律：仅 UI 层持有，流水线不收集）

## §4 新增/修改文件清单

```
新增 data/ai/AiGate.kt                 全局 AI 互斥（Mutex + label StateFlow + tryBegin/end）
新增 data/study/NoteModels.kt          ExplainUnit/enumerateUnits/NotePlan/NoteParser/ParaIdsCodec(encode+decode)
新增 data/study/NotePlanner.kt         批量流水线（枚举→孤儿清理→缓存判定→逐单元→落库→进度）
新增 data/study/NoteRunner.kt          全局单例（AiGate 互斥 + join 自替换 + NoteRunState 五态）
新增 assets/prompts/explain_note.txt   讲解 prompt（总计划 §75 规格 + 搭子语气约束）
改   data/db/Daos.kt                   ParaNoteDao 补 byChapterOnce/deleteByParaIds/deleteById
改   data/study/RoughReadRunner.kt     构造注入 AiGate，start/mergeOnly 经 tryBegin + join 自替换（§2.6）
改   StudyApp.kt                       aiGate/noteRunner 单例
改   ui/screens/ReadScreen.kt          NoteBar + NoteCard（ReadScreen 收集 notesFlow/gate.busy/noteState）
新增 test/.../NoteModelsTest.kt        枚举+解析（11 例：5 枚举 + 1 截断 + 5 解析）
新增 test/.../NotePlannerTest.kt       流水线（10 例，Robolectric+FakeChat 复用 M4a 模式）
新增 test/.../NoteRunnerGateTest.kt    互斥+自替换（3 例：讲解拒粗读、粗读拒讲解、join 后重获）
新增 test/.../M4bUiSmokeTest.kt        NoteCard/NoteBar 冒烟（3 例）
```

## §5 测试矩阵（新增 27 例）

1. enumerateUnits（5）：EXPLAIN 成单元 / GROUP 按 groupId 聚合组首+组员 / **孤儿组员仍成组单元（锚=组内最小 idx，与 countUnits 口径一致）** / 组员<2 退化单段、>3 截前 3 / SKIP/NONE/groupId null 不成单元
2. textForPrompt（1）：每段 2500、合计 6000 截断（边界锁定）
3. NoteParser（5）：合法全量 / friendly 空抛 PlannerException / title 空补位段首 / key_points 过滤+截断 / check_questions 空白过滤+截 3
4. NotePlanner（10，Robolectric+FakeChat 按 deserializer 分流）：批量生成+落库（paraIds 确定性、字段映射）/ 缓存命中跳过（calls=缺失数）/ promptVersion 过期重生成（deleteByParaIds 后 insert，不堆积）/ 同单元重复生成不堆积 / **孤儿 note 清理（paraIds 不在当前单元集合的行被删）** / 中断保留已完成+重跑断点续跑 / 401 直断 / 无 Key 抛 MissingKeyException / force 全部重生成 / 空单元直接 Succeeded
5. AiGate + 自替换（3）：讲解 Running 时粗读 start 被拒（Failed 文案）/ 粗读 Running 时讲解 start 被拒 / **同 runner 自替换：Running 中再 start → join 后重获 gate 正常完成（不误报"另一项任务"）**
6. UI 冒烟（3）：NoteCard 渲染（friendly/analogy/keyPoints/memory_hook）/ NoteBar 无 note→"让搭子讲解本章" / 生成中进度与停止
7. Robolectric 口径：NotePlanner 10 + Gate 3 + UI 3 = 16 例（全项目 Robolectric 总量约 36，与现状同量级）；全项目 102 + 27 = **129 例**

## §6 验证与回归

- `:app:testDebugUnitTest` 全绿（102 + 27 = 129）
- `:app:assembleDebug :app:assembleRelease` 双绿（release 用 1280m 堆，M4a 备忘）
- M4a 回归：粗读互斥/断点/归并语义不变；RoughReadRunner 接 gate 后其测试构造补 gate 参数（默认参数兼容，断言不改）；join 自替换语义下 startB_cancelsA 仍成立；M3 AiClient 不动
- 内存自查：串行逐单元、输入截断 2500/6000（上下文 800）、流水线不持 note 集合、Robolectric 新增 16 例（口径 §5.7）

## §7 风险与对策

| 风险 | 对策 |
| --- | --- |
| 讲解质量飘/太长 | prompt 硬约束字数与条数 + NoteParser 截断；temperature 0.5；"宁短勿空" friendly 必填否则重试 |
| 批量耗额度 | 缓存命中跳过（promptVersion+friendly 双判）；中断断点续跑；讲解单元数受 M4a unit_budget 已控（宁少勿多） |
| 双 Runner 并发双倍内存 | AiGate 全局互斥为硬防线 + UI 禁用双保险 |
| note 堆积（同单元重复生成） | paraIds 确定性编码 + 先删后插唯一化（专测锁定） |
| 粗读 force 后组结构变化 | 枚举按 groupId 值聚合 + 锚缺失回退组内最小 idx（§2.1，孤儿数据不丢卡）；孤儿 note 由流水线开头清理 |
| 用户等待焦虑 | NoteBar 进度 x/y + NoteCard 生成中占位 + 停止按钮；中断保留已完成 |
