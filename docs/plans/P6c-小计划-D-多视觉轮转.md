# P6c 小计划 D：多视觉模型轮转与并行提速

> 老板 2026-10-07 指令：①时间耗太多，加快效率；②新增美团龙猫视觉模型（key 已入本机档案库 `ai-apis.json`，id=20261007-longcat-vision）；③多视觉模型轮转——一个限流自动切下一个，循环接力，提升吞吐。

## 1. 前置实测（计划成立性证据，MEASURED）

| 实测项 | 结果 |
| --- | --- |
| 龙猫 vision 能力 | ✓ 准确描述测试截图内容（chat/completions 带 image_url） |
| 龙猫真实书页转写（原书 48 页图表页，1764×2872） | ✓ 26s/页，finish=stop 无截断 |
| 转写质量 | JSON 解析 OK：body 5 段 + footnotes 2 条，内容为「民事责任方式」正文 |
| 推理型 token 消耗 | reasoning 937 token，maxTokens=8000 足够（正文 968 字完整输出） |
| 与商汤对比 | 商汤 16~70s/页且不稳，龙猫 26s/页稳定 |

## 2. 设计

### 2.1 配置层（SettingsRepository）
- 新增备用视觉组三键：`vision2_base` / `vision2_model` / `vision2_key_enc`（key 同套 k1 加密）。
- `saveVision2()` / `clearVision2Key()`：与主视觉组同模式（null=保持不变）。
- `SettingsSnapshot` 增加 `vision2BaseUrl` / `vision2Model` / `hasVision2Key`。
- **`buildVisionTranscribers(): List<VisionTranscriber>`**（新入口，保留旧 `buildVisionTranscriber()` 委托其首个元素，导入同步路径与探针不动）：主组可用→入列；备用组可用→入列；空列表=视觉不可用。

### 2.2 Worker 层（VisionWorker）——渲染串行 + 模型并行流水线
- **渲染必须单线程**：Pdfium native 层非线程安全，多线程并发渲染同一 PDF 会崩/串页。
- 流水线：主协程逐页渲染 base64（~1s/页）→ 写入 `Channel(capacity=8)` → N 个模型协程各自绑定一个 transcriber 从 Channel 领任务 → 转写 → 写缓存 → 更新 DB（各自页，行级独立，Room 并发安全）→ 全部协程 join 后走原终态判断/rebuild。
- **轮转语义达成**：Channel 竞争=谁空闲谁领下一页；某模型限流只卡自己协程，其余协程继续消化——即「一个限流下一个顶上」；恢复后自动回到均衡（先到先得）。
- 单模型回退：transcribers.size==1 时行为与现状等价（单协程顺序消化）。
- 图页闸门/缓存命中短路仍在渲染协程（不进 Channel，免烧模型）。

### 2.3 UI 层（SettingsScreen + SettingsViewModel）
- 设置页视觉组下方新增「视觉 API 备用（可选）」三输入框（地址/模型名/Key）+「清除备用 Key」。
- 文案：「备用视觉模型：主模型限流时自动接力，多路并行提速」。
- ViewModel 新增 vision2 状态与 saveVision2 透传。

## 3. 验收判据
- **D1** 编译通过 + 既有 JVM 测试不新增红（VisionRebuilderTest 等）。
- **D2** 装机+配置龙猫后，日志可见两个模型交替转写（并行证据：任一时刻两页在不同模型在途）。
- **D3** mfzz 队列全部终态（PENDING=0；FAILED 页走 attempts 补跑机制）。
- **D4** mfzz 整书 rebuild 成功（`rebuild bookId=1 ... (preserved)` 日志）。
- **D5** 回退：备用组不填 = 现状单模型行为（buildVisionTranscribers 返回单元素）。

## 4. 风险与规避
| 风险 | 规避 |
| --- | --- |
| Pdfium 并发渲染崩溃 | 渲染单线程（流水线上游），模型线程只持 base64 |
| DB 并发写冲突 | 各页独立行 updateStatus，无共享可变状态；渲染/闸门在主协程 |
| 龙猫推理 token 挤占转写 | 实测 maxTokens=8000 够（§1）；转写内部 2 次重试兜底 |
| 页乱序完成 | rebuild 按 pageNo 排序（VisionRebuilder doneByPage/compareBy 已确认），无影响 |
| 装机杀进程丢当前页 | 按页缓存+状态落库，最多重跑在途 1-2 页 |

## 5. 成本申报
- LLM 调用次数：不变（页级一次转写，与单模型模式相同）。
- 墙钟时间：剩余 ~21 页从 ~38min（商汤单路 1.8min/页）→ 预计 ~20min（双路并行，受渲染单线程 ~1s/页 不构成瓶颈）。
- 新增依赖：无。

## 6. 评分记录
- v1 自评：92 / 100（实测依据 ✓ 风险全规避 ✓ 判据可验证 ✓ 回退完备 ✓ 成本申报 ✓；扣分：龙猫限流阈值未知，靠机制兜底而非预测）。≥90，直接执行。

## 7. 执行记录（2026-10-07 收口）

| 判据 | 结果 | 证据 |
| --- | --- | --- |
| D1 编译+测试 | ✓ | 四文件（SettingsRepository/VisionWorker/SettingsViewModel/SettingsScreen）+ AndroidManifest 三权限（FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS）+ SystemForegroundService dataSync 声明，编译绿、既有 JVM 测试无新增红 |
| D2 双模型加载 | ✓ | logcat `VisionWorker: models=[sensenova-6.8-flash-lite, LongCat-2.5-Preview]`（历次 run 逐字 MEASURED） |
| D2+ 双模型并行/交替 | ✓（6 页重置重转实测） | 同一 run（07:31:06 pending=6）内交替完成：商汤 page 3/7/22 + 龙猫 page 5（chars=667），4 页墙钟 3 分 52 秒；`page N done [模型]` 交替日志 MEASURED。**轮转救场活案例**：page 18 两轮被龙猫领走均 180s×2 推理超时，第三轮被商汤领走 111s 成（chars=651）——单模型会像 page 531 一样烧穿 attempts，轮转给了换模型翻盘的机会 |
| D3 队列终态 | ✓ | mfzz（bookId=1）146 行终态 **DONE 145 / FAILED 1**（唯一 FAILED=pageNo 531，见下立案；208 是全库口径=mfzz 146+bookId=5 47+mini 16，此前文档口径已修正）；bookId=5 47/47 全清 |
| D4 rebuild | ✓ | `rebuild bookId=1 pages=769 chars=535628→535628 chapters=12 (preserved)`（06:32:43）；并行实测后再次 `535628→535523 chapters=12 (preserved)`（07:58:50，page 17/18 新转写入库），Worker result SUCCESS |
| D5 单模型回退 | ✓（结构性） | `buildVisionTranscriber() = buildVisionTranscribers().firstOrNull()`（SettingsRepository.kt:265），不填备用组即单元素列表 |

### page 531 立案（唯一 FAILED 页，根因已定案）

- **现象**：attempts=5 定格 FAILED（跨商汤/龙猫两代模型累积）。
- **根因（MEASURED，宿主机等价复现：同 PDF 页 + 同 DPI140 渲染 + 同 PROMPT + 同 maxTokens=8000）**：龙猫对该页**推理超载**——179.8s 返回、`finish_reason: length`、content 0 字：推理型模型把 8000 token 配额全部烧在 reasoning 上没输出正文（普通页 26s/推理 937 token）。app 侧 readTimeout=180s 与 maxTokens=8000 均扛不住。
- **内容不丢**：设计兜底链——转写失败自动回退文字层内容，该页文字层 535 字已计入 rebuild（chars 535628 不变）。
- **处置**：不再为单页烧配额，接受 FAILED 终态归档；可选救法（未立项，性价比低）：maxTokens 上调至 16000（改常量须走小计划流程+成本翻倍）或该页定向换商汤。
- **附带发现**：mfzz.pdf 实际 630 页而 rebuild pages=769——DB 页数含导入链拆分页，非漂移（E2E 断言按 DB 口径）。

### 成本对账（申报）

- LLM 调用次数：不变（页级一次转写；重试语义与改前一致）。
- 单页实测：龙猫 26s/页（MEASURED）vs 商汤 16~70s 不稳。
- **并行实测（6 页重置重转，2026-10-07）**：07:31 run 同一窗口 4 页（商汤×3+龙猫×1）墙钟 3 分 52 秒——同页集串行粗估 8 分钟级，**约 2 倍提速（样本 1 轮 6 页，方向性结论 MEASURED、倍数精度 UNMEASURED）**；Page 18 案例实证轮转语义：某模型连续超时只卡自己协程，另一模型领页即翻盘。
- 新增依赖：无。
