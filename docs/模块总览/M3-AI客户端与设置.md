# M3 AI客户端与设置 · 模块总览

> 状态：✅ 完成（计划 96 分 → 实现 → 测试修复 4 个真问题 → 质检 91 分通过 → P1+5 条 P2 全部修复）
> 验证：**64/64 单测全绿**，assembleDebug + assembleRelease 双绿

## 功能清单

| 功能 | 说明 |
| --- | --- |
| 设置页 | API 地址（placeholder 提示"一般以 /v1 结尾"）/ 模型名 / 温度（0.0–1.0）/ API Key 密码框（打码显示、trailingIcon 清除） |
| 视觉兜底配置 | 开关 + 视觉 API 地址 + 视觉 API Key（独立加密存 `vision_key_enc`）+ 视觉模型名；地址/Key 留空回退主配置（文本走 A 家、视觉兜底走 B 家时才填专属值）； OPT-E 引入。**OPT-F 起：点「保存」若视觉队列有待转写页，自动重调度全部队列任务（`reenqueueAllPending`，REPLACE）——新 Key/模型立即生效开跑；App 启动还有 `restorePending` 续跑兜底（进程死亡/重启不丢队列）** |
| Key 加密存储 | AndroidKeyStore AES-256 密钥 + SecretCrypto 协议：12B 随机 IV + 128 位 GCM tag，payload = `Base64(iv||ct)` 存 settings 表 `api_key_enc`，明文 Key 永不落库 |
| 密钥失效自愈 | 解密任何异常（换机/清数据/密文损坏）→ 删旧密文 + 中文提示重填；encrypt 同样包装为 SecretCryptoException |
| URL 规范化 | trim → 去尾 `/` → 非 `/chat/completions` 结尾自动拼上；用户粘贴全路径/带尾斜杠都兼容 |
| 温度解析 | 兼容中文逗号小数；非法/越界回退 0.3；load 时 coerceIn(0.0, 1.0) 双保险 |
| SSE 流式 | OpenAI 兼容 `/chat/completions`，`stream:true`，onDelta 逐块回调（Compose mutableStateOf 线程安全直写） |
| 取消 | 哨兵子协程 `awaitCancellation()` 在 finally 里 disconnect；取消型 IOException 还原为 CancellationException 不冒充网络错误；responseCode 后补 ensureActive 防"连接阶段取消"挂到读超时 |
| 错误分层 | 401/403→"API Key 无效或无权限"、404→"接口路径不存在"、429→"请求太频繁或额度用尽"、其余带 HTTP 码+错误头 300 字节；网络/地址格式错误各有专属中文文案 |
| JSON 剥壳与修复重试 | stripFence + 首个 `{`/`[` 配平扫描（inString/escaped 状态机）；chatJson 解析失败自动重试 1 次（追加"只输出 JSON"提示），再失败抛含原始输出片段的异常 |
| 测试连接 | 发 ping（maxTokens=16）计时，实时显示首块预览；可取消；Key 输入框非空时优先用新 Key 测试（避免旧 Key 配新地址误导） |
| 布局 | 平板单列居中 720dp，与 M2 两屏一致 |

## 关键文件

```
data/ai/SecretCrypto.kt       AES/GCM 协议（纯 JVM object，encrypt/decrypt/newKey）
data/ai/JsonSlicer.kt         JSON 剥壳（配平扫描，JsonSliceException）
data/ai/AiClient.kt           SSE 客户端（normalizeChatUrl/parseTemperature/chat/chatJson/extractDelta）
data/ai/SecretStore.kt        SecretStore 接口 + KeyStoreSecretStore + FakeSecretStore（测试）
data/SettingsRepository.kt    load/save/clearKey/decryptKeyOrNull + saveVision/clearVisionKey/decryptVisionKeyOrNull/resolveVisionKeyOrNull（hasKey/hasVisionKey 只给"是否已存"）
data/db/Daos.kt               SettingDao.delete(key)（M3 新增）
ui/screens/SettingsViewModel.kt  save/testConnection/cancelTest/clearKey，busy/testing 双向防抖
ui/screens/SettingsScreen.kt  表单 UI + 实时流式预览 + 红/绿状态行
ui/nav/AppNav.kt              SETTINGS 路由接线 SettingsViewModel
test/.../MiniHttpServer.kt    手写 ServerSocket 迷你 HTTP（AGP --release 无 jdk.httpserver）
test/.../JsonSlicerTest.kt    10 用例（围栏/噪声/嵌套/字符串内括号/转义/数组/异常）
test/.../SecretCryptoTest.kt  6 用例（roundtrip/随机 IV/篡改/错钥/短 payload/非 Base64）
test/.../AiClientTest.kt      9 用例（SSE 顺序/请求体断言/401/404/取消 5s/URL 规范化/温度/重试成功/两次失败）
test/.../SettingsRepoTest.kt  4 用例（roundTrip/健康解密/失效删键/clearKey）
```

## 设计决策（与踩坑）

1. **取消必须用哨兵协程，不能用 `invokeOnCompletion` 默认重载**：后者 `onCancelling=false`，handler 要等 job **完全结束**才触发——正是要打断的阻塞读结束之后，为时已晚（finally 里 dispose 后甚至永不触发）。`onCancelling=true` 三参重载是 `@InternalCoroutinesApi`，故用公开惯用法：`launch { try { awaitCancellation() } finally { conn.disconnect() } }`，取消传播到哨兵的瞬间断开 socket。
2. **JDK 实测**：`HttpURLConnection.disconnect()` 能在 8ms 内打断阻塞 read（T.java/T2.java 实验，100ms 快速断开也有效）——问题从来不在 JDK，在协程 handler 触发时机。
3. **测试里 `CountDownLatch.await` 必须包 `withContext(Dispatchers.IO)`**：直接在 runBlocking 里阻塞等待会卡死 event loop，launch 的协程体（还在队列里）永远不执行——曾造成"chat=not-run"假象。改用 server 写头后 countDown，测试 await 后再 cancel，同时消除"cancel 抢在请求发出前"的竞态。
4. **AGP --release 编译限制**：unit test 编译时 `com.sun.net.httpserver`（jdk.httpserver 模块）Unresolved → 手写 MiniHttpServer（HTTP/1.0 + Connection: close，EOF 结束）；SSE delta 的 content 必须转义（手拼 JSON 会因 content 含引号而产出非法行）。
5. **全链路只用 java.util.Base64**（含 padding、无换行），禁止 android.util.Base64——协议两端一致是解密成功前提。
6. **Robolectric 的 AndroidKeyStore 不可靠**：协议层用 JVM SecretCryptoTest 覆盖，失效路径用 FakeSecretStore；KeyStore 实现推 M7 真机冒烟（计划已载明）。
7. **错误 body 限读 300 字节**（readErrorHead）：异常服务器返回大 body 时防 OOM——低内存机器的硬约束；errorStream 为 null 直接空串（原 `?: inputStream` 在非 2xx 下会抛 IOException 掩盖友好文案）。
8. **取消异常传播约定**：VM 层 catch(Exception) 前必须先 `catch (e: CancellationException) { throw e }`（save/clearKey/testConnection 统一）；AiClient 的 catch 链 CancellationException → AiException → IOException（还原取消）→ Exception。
9. **chatJson 重试只针对 JSON 解析失败**，网络错误不重试；重试请求附加第一次原始输出（截 2000 字）+ "只输出 JSON" 提示，再失败抛含原始输出片段（120 字）的异常供调试。

## 测试矩阵（M3 新增 29 用例，全项目 64/64 绿）

- JsonSlicer：直通 / json 围栏 / ``` 围栏 / 前后噪声 / 深嵌套 / 字符串内括号 / 转义反斜杠引号 / 数组 / 无 JSON 抛 / 未闭合抛
- SecretCrypto：中文符号 roundtrip / 两次密文不同 / 篡改抛 / 错钥抛 / 短 payload 抛 / 非 Base64 抛
- AiClient：SSE 顺序+全文 / 请求头与体（Bearer、stream、model、max_tokens）/ 401 / 404 / **取消 5s 返回（等 header 竞态消除）** / URL 规范化×3 / 温度解析×6 / 重试成功（calls=2、第二次含"只输出 JSON"）/ 两次失败抛含原文
- SettingsRepo：save+load roundTrip（默认值/hasKey/密文不含明文/不传 Key 不清密文）/ 健康解密 / 失效删键 hasKey 归 false / clearKey / 视觉独立配置（专属地址+Key 往返、未配回退主 Key、开关即点即存不覆盖、清除后回退）

## 质检记录

- 质检 91 分通过（计划符合 29/30、正确性 27/30、健壮性 17/20、可测试性 18/20），无 P0
- 修复：P1 错误 body 无上界读取 → readErrorHead 限 300 字节；P2×5：VM 吞取消异常×2、errorStream 兜底分支、MalformedURLException 文案、load 温度 clamp、测试连接优先用未保存的新 Key、encrypt 异常包装
- 修复后回归：64/64 全绿 + 双 APK 重建成功
- 暂缓（记录在案）：VM 构造注入重构（与 ImportViewModel 模式统一后一并做）、key() 并发生成（busy 互斥已实际规避）、URL 带查询串（无需求）

## 对后续模块的接口承诺

M4 段落讲解 / M5 章末总结将复用 `AiClient.chatJson(req, deserializer, onDelta)`：温度/maxTokens/messages 全可配、取消可传播（哨兵机制）、JSON 失败自动重试——批量队列只需管好自己的并发与限速。

## M7 追加（应用自更新的设置面）

- 设置页新增「应用更新」区块：当前版本（BuildConfig.VERSION_NAME）、手动「检查更新」、「自动检查更新」开关（即点即存）、GitHub 访问令牌输入（密码框 + 已存清除）+「保存令牌」+「如何获取令牌？」步骤指引弹窗。界面与状态机在 `UpdateViewModel/UpdateDialog`（详见 M7），本模块只提供存储面。
- `SettingsRepository` 新增：`github_token_enc`（与 AI Key 同一套 SecretCrypto 加密落库）、`update_auto_check`（缺省开）、`update_last_check_ms`（24h 节流）、`update_api_base`（测试覆盖用隐藏键，默认空 = 官方 GitHub API）。
- `SettingsSnapshot` 相应加 `hasGithubToken / updateAutoCheck / updateApiBase` 三字段。
- 最后更新：2026-10-01（M7 接入）。
