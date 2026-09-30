# M4a 阅读页与粗读流水线 · 模块总览

> 状态：✅ 完成（计划 v1 82 分不通过 → v2 92 分通过 → 实现 → 质检第一轮 82 分 → 6 P1 全修复 → 复验 90 分通过 → 6 项 P2 收尾）
> 验证：**102/102 单测全绿**（M4a 新增 38 例），assembleDebug + assembleRelease 双绿（debug 19.6MB / release 15.6MB）

## 功能清单

| 功能 | 说明 |
| --- | --- |
| 章目录页 | 路由 `BOOK/{bookId}`；书架点书进入；每章行 = 序号/标题/一行摘要，行尾操作按数据推导：未粗读→"粗读"、中断或断点数据（READING+gist 空）→"继续"、已完成→"重读"、进行中→"进度 x/y+停止"；点行进阅读页 |
| 阅读页 | 顶栏章标题+返回；本章要点卡（gist + key_terms，`byIdFlow` 收集，粗读完成即时刷新）；段落流按 aiAction 渲染：EXPLAIN/GROUP→"讲/合并讲"徽标+why 小字、SKIP→"已跳过"标签+全文半透明（alpha 0.55，仍可读）、NONE→正常文本 |
| 操作条七态 | 进行中（进度条+停止）/ 中断（消息+从断点继续+全部重读）/ 成功（消息+标记读完；degraded 时加"重试归并"）/ 失败（重试；keyIssue 时加"去设置"）/ 待归并（全标注+无 gist→"完成归并"）/ 未开始（"开始粗读"）/ 部分标注（数据推导断点入口，进程重启后仍可用） |
| force 二次确认 | 所有"重读/全部重读"先弹确认框（"覆盖现有标注"），确认后才 force=true——防误触覆盖 |
| 确定性分块 | RoughReadChunks：8000 字 + 40 段双上限、段不跨块、超长段独立成块不丢弃；同输入同划分（断点续跑前提） |
| 逐块粗读 | prompt 三占位符（block_pos/block_total/unit_budget）+ user 消息每段截 200 字；块级重试 3 次，401/403/404 直断不重试；块完成即落库（断点） |
| 解析容错 | PlanParser：action 归一化→越界 id 丢弃→group 连续+2~3 段校验（违者降级 explain）→重复 id 后到生效→缺标>30% 抛异常计入块失败；LenientInt：null/非法 id → -1（幻觉条目静默丢弃，不撞真实 idx=0） |
| 归并 | 单块章直用块 gist（不发归并调用，省额度）；多块一次归并（temp 0.2 / maxTokens 800）；失败降级三态：非 force+旧 gist 非空→保留旧值 / 首块 gist 可用→写降级值 / 无可写→维持空；完成态可用"重试归并"（mergeOnly 只跑归并） |
| 全局互斥 | RoughReadRunner 由 StudyApp 持单例：start 前 cancel 旧 job（同时只允许一章粗读），返回书架粗读不中断（后台继续落库）；状态 StateFlow 五态（Idle/Running/Succeeded/Interrupted/Failed） |
| 空转保护 | 全部已标+gist 非空+非 force+非 mergeOnly → 零 API 调用直接返回 |
| 结构化错误 | 无 Key/密钥失效 → `MissingKeyException`（`PlannerException` 子类）→ Failed.keyIssue=true → UI"去设置"按钮，避免文案字符串耦合 |
| 标记读完 | `app.appScope.launch` 写 readState=DONE——应用级作用域，用户点完立即返回上一页也不取消写库 |

## 关键文件

```
data/study/RoughReadModels.kt   RoughReadPlan/PlanEntry/MergedOverview、LenientIntSerializer、PlanParser、MissingKeyException
data/study/RoughReadChunks.kt   确定性分块（8000 字 + 40 段双上限）
data/study/RoughReadPlanner.kt  流水线 run(force/mergeOnly)：逐块+落库+归并降级+空转保护；ChatJsonFn 注入点
data/study/RoughReadRunner.kt   全局单例：唯一 Job、五态 StateFlow、start/stop/mergeOnly、keyIssue/degraded 结构化
data/study/KeyTermsCodec.kt     要点编解码（term+plain ListSerializer）
data/ai/PromptLoader.kt         assets prompt 加载（内存缓存）
assets/prompts/rough_read.txt   粗读 prompt（三占位符 + JSON 规格 + 宁少勿多）
assets/prompts/rough_read_merge.txt  归并 prompt
ui/screens/ChapterListScreen.kt 章目录页（行尾操作按数据推导）
ui/screens/ReadScreen.kt        阅读页 + ActionBar 七态 + ParaRow/ParaTag + force 确认弹窗
ui/nav/AppNav.kt                BOOK/READ 路由（READ 传 onOpenSettings）
StudyApp.kt                     database/appScope/roughReadRunner 三单例
test/.../RoughReadChunksTest.kt    5 例
test/.../PlanParserTest.kt         14 例（含 LenientInt null→-1 专测）
test/.../RoughReadPlannerTest.kt   14 例（Robolectric+真内存库+FakeChat 按 deserializer 分流）
test/.../RoughReadRunnerTest.kt    1 例（start A→start B 互斥，挂起门控）
test/.../M4aUiSmokeTest.kt         4 例（Compose 冒烟）
```

## 设计决策（与踩坑）

1. **Kotlin 禁止 `fun interface` 带泛型方法** → `ChatJsonFn` 用普通 interface + `suspend operator fun <T> invoke`（invoke 运算符约定保持调用处简洁）；Runner 次构造的泛型 SAM lambda（`ChatJsonFn { … }`）推断不可靠 → 改对象表达式 `object : ChatJsonFn { … }`。
2. **countUnits 必须从库重读段落**：blocks 里持有的是运行前实体引用（aiAction=NONE），标注只落库不回写内存——首次实现单元数恒 0；改为 run 末尾 `db.paragraphDao().byChapter(chapterId)` 重读。
3. **LenientInt 的 null/非法 → -1 而非 0**：0 会撞真实 idx=0 被"重复 id 后到生效"覆盖；-1 必不在块内 → 幻觉条目静默丢弃（专测锁定）。
4. **单块章直用不发归并**：省一次 API 调用；FakeChat 默认 onMerge 抛"意外的归并调用"，单块用例以 `mergeCalls==0` 断言锁定。
5. **Runner 取消防误抹**：`catch (CancellationException)` 里 `if (coroutineContext[Job] === job)` 才回 Idle——新任务 start 时旧 job 的取消异常不能抹掉新状态。
6. **空转保护与待归并并存**：全标注+gist 空（上次归并前中断）不触发空转（gist 条件排除），由"完成归并"入口走 mergeOnly；mergeOnly 不受空转保护限制。
7. **Robolectric 三坑**：chapters 表有 FK 必须先插父书；`ui-test-manifest` 必须 `debugImplementation`（testDebugUnitTest 读 debug variant merged manifest，testImplementation 不进）；Compose 测试视口小 → `waitUntil(5000)+assertExists` 替代 assertIsDisplayed。
8. **冒烟 seed 章必须 readState="NOT_READ"**：READING+gist 空会被数据推导判成"断点续跑"，"粗读"按钮变"继续"导致 ComposeTimeoutException；页内库经 StudyApp 拿（文件库落 Robolectric 沙盒），不 close（lazy 固化，关闭会污染同 JVM 后续测试）。
9. **归并类测试必须 2 块章**（paras=2, textLen=5000）：1 段=单块直用，归并路径根本不走；onBlock 按 call 序号返回对应块 plan，否则块 2 缺标 100% 触发重试噪音。
10. **`repeat {}` 里不能 break**：mergeOverview 改 `for (attempt in 0 until ATTEMPTS)`（成功 return、失败记录 lastError 继续循环）。
11. **构建备忘**：`lintVitalAnalyzeRelease` 在 768m 堆下 lint FIR 分析崩溃（`NoClassDefFoundError: ConeIntegerLiteralConstantTypeImpl`，分析 AiClient.kt 时类加载失败）——内存不足诱发的 lint 工具自身 bug，非代码问题；release 构建用 1280m 堆即稳定通过。
12. **SettingsSnapshot 是 SettingsRepository.kt 的顶层类**而非嵌套类，import 直接 `com.studyfriend.app.data.SettingsSnapshot`。

## 测试矩阵（M4a 新增 38 例，全项目 102/102 绿）

- Chunks（5）：确定性 / 段不跨块 / 8000 字恰好同块 / 40 段上限 / 超长段独立成块
- PlanParser（14）：合法混合 / 幻觉 id 丢 / action 归一 / group 非连续→explain / group>3→explain / 单段 group→explain / 重复 id 后到 / why 40 截断 / 缺标 30% 恰好过 / 缺标>30% 抛 / unitBudget 4:3 / 单块=10 / 宽松字符串数字 / **null+非法 id→-1 幻觉全丢不撞 idx=0**
- Planner（14，Robolectric+FakeChat）：单块直用（mergeCalls=0、占位符替换、200 字截断、terms 截断）/ 多块归并一次 / 归并失败保留旧值 / 归并失败写降级 / force 降级 / 块 2 失败中断+块 1 保留 / 缺标重试第二次过 / **重跑跳块（第二次 blockCalls=+1、gist=归并新值、onMerge 正常出值无噪音）** / force 单块覆盖 / 取消保留 / 无 Key 抛 MissingKeyException / 401 直断（blockCalls=2）/ 空转保护 / mergeOnly
- Runner（1）：start A（挂起门控）→ start B → A 取消 B 运行 + B 完成段落 EXPLAIN 落库
- UI 冒烟（4）：章目录渲染 / 阅读页段落流 / EXPLAIN 段 why 显示 / SKIP 半透明+GROUP 徽标

## 质检记录

| 轮次 | 分数 | 处理 |
| --- | --- | --- |
| 计划 v1 | 82 不通过（1 P0 + 6 P1 + 9 P2） | 逐条修订为 v2 |
| 计划 v2 | **92 通过** | 进入实现 |
| 实现质检第一轮 | 82（6 P1 + 5 P2，无 P0） | P1 全修复 |
| 复验 | **90 通过**（无 P0/P1，6 P2） | P2 趁热收尾 |

- **P1 六项**：单块直用不发归并 / 完成态待归并入口（数据推导）/ "标记本章读完"按钮 / 断点入口从数据推导（进程重启可用）/ Failed 态补操作按钮 / SKIP 段全文半透明仍可读
- **P2 六项收尾**：①完成态 degraded 时"重试归并"按钮；②force 二次确认弹窗；③onMarkDone 改应用级 appScope；④Failed 结构化 keyIssue（MissingKeyException）；⑤测试补强（LenientInt null→-1 专测、rerun 用例补 onMerge 断言消归并噪音）；⑥陈旧注释/测试名清理（ChapterListScreen"阅读态徽标"注释、冒烟测试改名 read_skipFullTextDimmedAndGroupTag）
- **P2 不修留档**：isLenient 不开（LenientInt 自定义序列化器已按字段粒度容错，全局 isLenient 反而放过裸 token 引入新风险，质检认可）；视觉规格延 M7（计划 §3.4 显式登记）
- 收尾后回归：**102/102 全绿 + 双 APK 重建成功**（debug 23:40 / release 00:03 时间戳）

## 对后续模块的接口承诺

- **M4b 讲解单元枚举**：`aiAction == "EXPLAIN"` 的段 + `aiAction == "GROUP" && groupId == idx` 的组首（groupId=组首段 idx，章内唯一、可从标注重建）；ParaNoteEntity 将带 promptVersion 落库（M4a 标注不落版本号的取舍已在计划 §2.1 声明）
- **M5 章末总结包**：chapter.gist/keyTermsJson 已就位；chapter_assets 表已建未写；ParaNote/讲解内容是总结包的输入
- **Runner/Planner 模式复用**：ChatJsonFn 构造注入 + StudyApp 单例 + StateFlow 五态 + 全局互斥——M4b 批量讲解队列沿用同一互斥纪律（讲解与粗读互斥或排队由 M4b 计划定）
