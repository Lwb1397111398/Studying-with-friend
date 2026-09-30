# M4b 讲解生成与讲解卡 · 模块总览

> 状态：✅ 完成（计划 v1 76 分不通过 → v2 93 分通过 → 实现 → **实现质检 93 分通过**（无 P0/P1，5 P2 趁热全修））
> 验证：**130/130 单测全绿**（M4b 新增 28 例），assembleDebug + assembleRelease 双绿（debug 19.7MB / release 15.6MB）

## 功能清单

| 功能 | 说明 |
| --- | --- |
| AiGate 全局互斥 | Mutex.tryLock 原子占锁 + label/busy StateFlow；粗读与讲解共用一把锁，任何时刻只跑一个 AI 任务；end() 仅持有者 finally 调用（tryBegin 失败路径不触发 end） |
| join 模式自替换 | start 捕获旧 job → cancel → 新协程 `old?.join()` 后再 tryBegin——旧 job 的 finally gate.end() 必先于 join 返回，消除"cancel 异步、旧锁未释放、新任务假失败"竞态；stop() 只 cancel 不清 job 引用（供下次 join） |
| 讲解单元枚举 | `enumerateUnits`：EXPLAIN 段→单段单元；GROUP 按 groupId 值聚合（与 M4a countUnits 口径一致），锚=组内 idx==groupId 成员、缺失（孤儿组员）回退组内最小 idx；组员<2 退化单段、>3 截前 3；SKIP/NONE/groupId null 不成单元 |
| 批量讲解流水线 | NotePlanner：前置检查（无 Key→MissingKeyException）→ 枚举 → 孤儿 note 清理（在空单元早退之前）→ 空单元直接 Succeeded → 缓存判定 → 逐单元串行 chatJson（单元级重试 3 次，401/403/404 直断）→ 先删后插（同 paraIds 唯一化）→ 即时落库（天然断点，中断保留已完成） |
| 缓存判定 | paraIds 精确匹配 + promptVersion==NOTE_VERSION + friendly 非空三条件；版本过期/force 先删旧行再重生成，插入前再删一次防重 |
| prompt 输入 | 章 gist/terms + 组内 why（"；"连接）+ 前后段各 800 字 + 段落文本（每段 2500、合计 6000 封顶，余量耗尽即止不出空串条目）；temperature 0.5 / maxTokens 2000（总计划 §75） |
| 输出规整 | NoteParser：friendly 缺失按单元失败走重试；title 回退锚段前 16 字；analogy 80 / keyPoints 过滤截 5 条各 30 字 / memoryHook 40 / check_questions 过滤截 3（M5 自测卡输入） |
| 讲解工具条四态 | NoteBar（ActionBar 之下、要点卡之上）：进行中（进度 x/y+停止）/ 另一任务占用（禁用提示）/ 全部生成（N 处+重新生成）/ 部分·无（"让搭子讲解本章（共 N 处）"或"继续生成（已有 x/N）"）；中断原因以小字同现；单元空不渲染；状态按数据推导，进程重启可恢复"继续"入口 |
| force 二次确认 | "重新生成"先弹确认框（覆盖现有讲解），确认才 force=true |
| 讲解卡 | NoteCard 渲染在锚段 ParaRow 之下：标题+大白话+打个比方+要点列表+记忆钩子（primary 色）；生成中本章 Running 时全部未成卡单元统一"搭子正在讲这一段…"占位；只认 promptVersion 匹配且 friendly 非空的行 |
| 结构化错误 | 无 Key → Failed.keyIssue=true（UI 引导去设置）；占用拒绝 → Failed("另一项 AI 任务进行中") |

## 关键文件

```
data/ai/AiGate.kt               全局互斥 Gate（Mutex.tryLock + label/busy StateFlow）
data/study/NoteModels.kt        ExplainUnit/enumerateUnits/textsForPrompt/NotePlan/NoteParser/ParaIdsCodec/decode helpers
data/study/NotePlanner.kt       批量流水线：枚举→孤儿清理→缓存→逐单元重试→先删后插
data/study/NoteRunner.kt        全局单例：唯一 Job、五态 StateFlow、start/stop、join 模式自替换
data/study/RoughReadRunner.kt   join 模式改造 + gate 注入（与讲解共用 AiGate）
data/study/RoughReadPlanner.kt  AiClientChatJsonFn 注入点 + 共享顶层 jsonEsc
data/db/Daos.kt                 ParaNoteDao：byChapterFlow/byChapterOnce/deleteByParaIds/deleteById
StudyApp.kt                     database/appScope/aiGate/settingsRepo + 双 Runner 注入
ui/screens/ReadScreen.kt        NoteBar 四态 + NoteCard + 锚段卡片挂载 + force 确认框
assets/prompts/explain_note.txt 搭子人格 + user 字段说明 + JSON 规格（300~600 字主体/check_questions 0~3）
test/.../NoteModelsTest.kt      12 例（枚举 5 / 截断 1 / 解析 5 / codec 1）
test/.../NotePlannerTest.kt     10 例（Robolectric+真内存库+FakeChat 按 NotePlan.serializer() 分流）
test/.../NoteRunnerGateTest.kt  3 例（Gate 互斥 2 + 自替换 1）
test/.../M4bUiSmokeTest.kt      3 例（Compose 冒烟）
```

## 设计决策（与踩坑）

1. **自替换竞态（计划评审 P0）**：`old?.cancel()` 后旧 job 的 finally gate.end() 是异步的，新协程若立即 tryBegin 会假失败——join 模式让新协程先 `old?.join()`（join 返回时旧 job 已完全退出含 finally）再 tryBegin。stop() 保留 job 引用正是为了让下一次 start 能 join 到已停任务。
2. **枚举按 groupId 值聚合**（不是"组首段"）：PlanParser 保证 groupId=组首段 idx，但孤儿组员（锚段缺失/被截）仍需成组——锚缺失回退组内最小 idx；组员<2 退化单段单元，保证"共 N 处"计数与卡数一致。
3. **孤儿 note 清理在空单元早退之前**：force 粗读后全章变 SKIP 时 units 为空，若先早退旧 note 行永远残留；专测锁定（emptyUnitsSucceedsAndCleansOrphans）。
4. **CancellationException 全程重抛**（planner 单元循环 catch 首位、retry catch 首位、Runner 单独 catch）——中断是状态（Interrupted，保留已完成），取消不是失败；Runner 的 catch 里 `coroutineContext[Job] === job` 才回 Idle（防旧 job 取消异常抹掉新任务状态，沿 M4a 决策 5）。
5. **先删后插双保险**：过期/force 预删 + 插入前再删一次（同 paraIds 唯一化，重生成不累积行）；缓存快照在孤儿清理之后获取。
6. ** textsForPrompt 余量耗尽即止**（质检 P2-2 修复）：合计到 6000 后直接 break，后继成员整体不进 prompt——原实现 `take(0)` 仍 append 会产生 `{"id":n,"text":""}` 空串条目。
7. **jsonEsc 提取共享**（质检 P2-3）：粗读/讲解两份逐字重复的私有转义合并为 RoughReadPlanner.kt 顶层 internal fun（同包直接可见）。
8. **GateTest entered 信号**（实现期踩坑）：假 chat 靠"第 1 次调用"挂住旧 job，但 cancel 可抢在第 1 次调用落地前到达（旧 job 还在 planner 前置步骤），"挂住资格"错移给新 job → 新 job 挂死在永不完成的 hold 上、测试超时。修复：假实现在挂起前先 complete(entered)，测试确认第 1 次已被旧 job 消费后再触发自替换——生产代码无需改动。
9. **Robolectric 视口再踩**：M4b NoteBar 挤占纵向空间后，M4aUiSmokeTest 4 段 seed 的第 4 行（GROUP）落在懒列表合成区外，"合并讲"永远等不到——断言前 `performScrollToNode(hasText("合并讲"))`（该测试唯一改动）。M5 UI 测试注意预留滚动。
10. **委托属性不能 smart cast**：ReadScreen 里 `val noteStateV = noteState` 先取快照再做类型判断。
11. **NoteBar 位置微调留档**（质检 P2-1）：计划 §3.1 写"要点卡之下"，实现放要点卡之上——要点卡要粗读完成后才出现，讲解入口在其上方任何时刻可达；已在计划案 §3.1 补留档。
12. **Interrupted 原因小字展示**（质检 P2-4）：NoteBar 部分/无 note 分支下以 bodySmall 同现"已完成 x/y 条讲解，中断于第 N 条：原因"，对齐 M4a ActionBar 做法。
13. **StudyApp 共享 settingsRepo**（质检 P2-5）：SettingsRepository 无状态（读时解密），双 Runner 共享一份。
14. **构建备忘**：release 用 1280m 堆（M4a 踩坑 11 沿用）；测试与双 APK 一条命令跑齐 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`。

## 测试矩阵（M4b 新增 28 例，全项目 130/130 绿）

- NoteModels（12）：枚举 5（EXPLAIN 单段 / GROUP 按 groupId 值聚合 / 孤儿组员锚回退最小 idx / 组员<2 退化与 >3 截 3 / SKIP·NONE·groupId null 不成单元）+ textsForPrompt 每段 2500·合计 6000 封顶 + 解析 5（合法全保留 / friendly 缺失抛 PlannerException / title 空回退锚段前 16 字 / keyPoints 过滤空白截 5 各 30 字 / questions 过滤截 3）+ ParaIdsCodec 确定性往返
- NotePlanner（10，Robolectric+FakeChat）：批量生成落库（字段映射+questionsJson 解码断言）/ 缓存命中全跳（noteCalls=0）/ 版本过期重生成 / 重生成不累积（两行旧版→run 后 1 行）/ 中断保留已完成+续跑补齐（首次 4 调用成功、续跑 1 调用）/ 401 直断（1 调用）/ 无 Key 抛 MissingKeyException / force 全重生成 / 空单元 Succeeded+孤儿清理（rogue 行删净）/ 有效 note 清理保留
- NoteRunnerGate（3，共享同一 AiGate 实例）：讲解进行中拒粗读（"另一项 AI 任务"）/ 粗读进行中拒讲解 / 自替换 join 后重获 gate 正常 Succeeded（entered 信号消竞态）
- M4bUiSmoke（3，StudyApp 文件库沙盒）：NoteCard 全区块渲染 / 无 note 时生成按钮 / 全部生成态+重新生成

## 质检记录

| 轮次 | 分数 | 处理 |
| --- | --- | --- |
| 计划 v1 | 76 不通过（1 P0 + 3 P1 + 7 P2） | 逐条修订（join 模式/finally 守卫/聚合口径/对齐总计划 §75/P2 全落实） |
| 计划 v2 | **93 通过**（残余 5 P2 趁热修入定稿） | 进入实现 |
| 实现质检 | **93 通过**（无 P0/P1，5 P2） | P2 趁热全修 |

- **实现质检 P2 五项（全部已修）**：①NoteBar 位置微调在计划案留档；②textsForPrompt 余量耗尽即止不出空串条目；③jsonEsc 提取共享顶层函数；④NoteBar 补 Interrupted 原因小字；⑤StudyApp 双 Runner 共享 settingsRepo
- **实现期两项偏差均获认可**：GateTest entered 信号（仅测试内改动，修真实测试竞态）；M4aUiSmokeTest 滚动适配（最小合理改动，断言语义未变）
- 收尾后回归：**130/130 全绿 + 双 APK 重建成功**（09:03 / 09:04 时间戳）

## 对后续模块的接口承诺

- **M5 章末总结包**：`decodeCheckQuestions`/`check_questions` 已就位（自测卡直接消费）；ParaNoteEntity（paraIds/title/friendly/analogy/keyPointsJson/memoryHook）是总结的讲解上下文输入；NoteCard"卡只挂锚段"的渲染约定 M5 引用时保持
- **AiGate 是全应用互斥纪律**：M5 章末总结包的 AI 任务必须同样经 aiGate.tryBegin/end + join 模式，StudyApp 注入共享 gate
- **版本化缓存约定**：NOTE_VERSION="explain-note-v1"，prompt 语义变更必须升版本号触发全量重生成
- **UI 测试教训**：Robolectric 小视口 + 新增工具条会挤占懒列表合成区，断言屏外内容先 performScrollToNode
