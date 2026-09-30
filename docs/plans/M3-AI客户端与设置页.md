# M3 计划案：AI 客户端与设置页

> 版本 v2（83 分评审后修订：DAO 补 delete、取消机制落地方案、URL 规范化、chatJson 重试测试、密钥失效测试、KeyStore 异常策略、Base64 约定、温度解析、转义配平）。
> 目标：打通"设置页配置 → 加密存 Key → AI 对话（流式）→ 返回 JSON 工具"的底座。
> 约束：无第三方网络库（HttpURLConnection）；开发机 7GB 内存（构建/测试低内存配置不变）；纯 JVM 可测部分尽量不碰 Android。

## §0 范围

**做**：设置页（端点/Key/模型/温度）、API Key 加密存储、OpenAI 兼容 `/chat/completions` 流式客户端、JSON 输出剥壳与修复重试、测试连接。
**不做**：具体 prompt 与讲解业务（M4）、对话 UI 气泡流（M4 复用）、多 provider 抽象（只 OpenAI 兼容格式，中转站通吃）、自签证书处理（提示错误即可）、无总时长上限控制（readTimeout 是字节间超时，M5 大包再议）。

## §1 设置页与数据

- SettingsScreen 实装（当前占位，AppNav `SettingsScreen()` 无参调用改为传 ViewModel）：
  - `api_base`：API 地址，默认 `https://api.openai.com/v1`，placeholder 提示"兼容 OpenAI 格式的中转地址，一般以 /v1 结尾"
  - `api_key`：密码框（视觉打码），编辑时若已存密文则显示占位"已保存（输入以更换）"；行尾"清除"文字按钮：delete("api_key_enc") 并恢复空态
  - `api_model`：模型名，默认 `gpt-4o-mini`
  - `api_temp`：温度，文本输入 0.0–1.0；解析前 `replace(',', '.')` 并 trim（中文输入法逗号），范围外或解析失败回退 0.3（纯函数 `parseTemperature`，单测覆盖 "0,3"/"abc"/空/1.5→0.3/1.0 合法）
- 保存按钮：upsert 到 `settings` 表；Key 单独走 SecretStore 加密后存 `api_key_enc`（Base64(iv||ciphertext)），明文不落库不进日志。
- **SettingDao 增加**：`@Query("DELETE FROM settings WHERE \`key\` = :key") suspend fun delete(key: String)`（§2 密钥失效与"清空 Key"功能使用；SettingsRepoTest 覆盖）。
- 测试连接按钮：发 1 条 `role=user "ping"`、`max_tokens=16` 的请求，流式读完整回复后显示"连接成功 · N 字 · M ms"；失败显示友好错误。可重复点击，进行中禁用。
- SettingsViewModel：进入时读键回填（Key 只显示占位不回显明文）；保存/测试互斥 busy；错误与成功状态独立。

## §2 SecretStore（AndroidKeyStore AES/GCM）

- 接口 `SecretStore { fun encrypt(plain: String): String; fun decrypt(payload: String): String }`。
- `SecretCrypto`（纯 JVM，协议层）：AES/GCM/NoPadding，随机 12B IV，payload = `Base64(iv || ciphertext)`。
  - **Base64 约定**：全链路只用 `java.util.Base64`（含 padding、无换行），**禁止 android.util.Base64**（默认 flag 差异会导致 decrypt 抛 IllegalArgumentException）。
- 实现 `KeyStoreSecretStore`：密钥来自 AndroidKeyStore（AES-256，KeyGenParameterSpec + GCM，无用户认证要求），加密解密复用 SecretCrypto 协议。
- **失效策略（评审修订）**：不枚举异常类型——取钥与解密路径 `catch (Exception)`（CancellationException 原样 rethrow），一律视为密钥失效：`settingDao.delete("api_key_enc")` 后抛友好异常"密钥已失效，请重新填写 API Key"。
- **测试策略**：Robolectric 的 AndroidKeyStore 不可靠 → SecretCrypto 单测覆盖 roundtrip/篡改密文失败/两次密文不同/错 IV 长度抛错；**密钥失效路径用 FakeSecretStore（构造时抛异常的假密钥源）单测**：断言 `api_key_enc` 被删除且抛出友好异常；KeyStore 实现只做编译级验证，M7 真机冒烟。

## §3 AiClient（OpenAI 兼容流式）

- `data/ai/AiClient.kt`：`suspend fun chat(req: ChatRequest, onDelta: (String) -> Unit): String`
  - `ChatRequest(baseUrl, apiKey, model, temperature, maxTokens, messages: List<Msg>)`
  - **URL 规范化**（纯函数 `normalizeChatUrl`，单测覆盖）：trim 空白；去尾部 `/`；以 `/chat/completions` 结尾直接用，以 `/v1` 结尾拼 `/chat/completions`，其余拼 `{base}/chat/completions`
  - HttpURLConnection POST，头 `Authorization: Bearer`，body 含 `stream: true`
  - connectTimeout 15s / readTimeout 90s（字节间超时）；读 SSE：按行取 `data: ` 前缀行，`[DONE]` 结束；每 delta 经 `onDelta` 回调（IO 线程回调，Compose 状态写入线程安全）
  - 返回拼接后的完整文本
- **取消机制（评审修订）**：`readLine()` 阻塞在 IO 线程，协程 cancel 不能中断它——因此注册 `coroutineContext.job.invokeOnCompletion { conn.disconnect() }`（协程取消/完成时 disconnect 使阻塞 read 抛 IOException 立即退出），读循环内每行间 `ensureActive()`；finally 里再兜底 disconnect。测试：本地服务器挂起不响应 → cancel 后 chat 调用在有限时间内返回。
- 错误分层（全部转 `AiException(message)`，message 中文可直显）：
  - HTTP 401/403 → "API Key 无效或无权限"
  - 404 → "接口路径不存在，检查 API 地址"
  - 429 → "请求太频繁或额度用尽"
  - 其他非 2xx → 读 body 前 200 字符附带
  - IOException/超时 → "网络异常：..."（若因取消产生的 IOException，rethrow CancellationException）
- JSON 请求/响应解析用 kotlinx.serialization（已有依赖），SSE 的 `choices[0].delta.content` 用 `JsonElement` 手取（宽松，容缺字段）。
- 测试用 `runBlocking`（真实 IO，不用 runTest 虚拟时间）。

## §4 JSON 输出剥壳与修复重试（M4/M5 共用）

- `data/ai/JsonSlicer.kt`（纯 JVM）：
  1. 剥 ```json / ``` 围栏
  2. 从首个 `{` 或 `[` 起**括号配平扫描**截出首个完整 JSON 值；**扫描需处理字符串字面量与转义**（`\"`、`\\` 内的括号与引号不参与配平，单测覆盖）
  3. 截不出/`kotlinx.serialization` 解析失败 → 抛 `JsonSliceException`
- `AiClient.chatJson<T>(...)` 扩展：chat → JsonSlicer → 反序列化；失败自动**重试 1 次**（重试 prompt 附加"上次输出不是合法 JSON，请只输出 JSON，不要任何其他文字"），再失败抛含原始输出的友好异常。
- 测试用例（补评审缺口）：本地服务器首答带噪声/围栏 → 断言重试请求的 messages 末尾含附加提示且第二次返回合法 JSON 时成功返回对象；两次均非法 → 抛异常且 message 含原始输出片段。

## §5 测试矩阵

| 测试 | 类型 | 覆盖 |
| --- | --- | --- |
| JsonSlicerTest | 纯 JVM | 围栏剥离/前后缀噪声/嵌套括号/字符串内括号与 `\"` `\\` 转义不参与配平/数组/非法抛错 |
| SecretCryptoTest | 纯 JVM | roundtrip/篡改密文失败/两次密文不同/错 IV 长度抛错 |
| 密钥失效（FakeSecretStore） | 纯 JVM 接口级 | 失效 → 删 `api_key_enc` + 友好异常 |
| AiClientTest | 纯 JVM（com.sun.net.httpserver 本地 127.0.0.1，runBlocking） | SSE 多 chunk 与 onDelta 顺序/[DONE]/401 与 404 友好错误/请求体含 stream:true 与 Authorization/**URL 规范化（全路径/尾斜杠/裸域名）**/**chatJson 重试（首答噪声→重试成功；两次失败→抛含原文异常）**/**取消：服务器挂起→cancel 有限时间返回** |
| SettingsRepoTest | Robolectric | settings 表 4 键 upsert/读回/覆盖/**delete 后键不存在** |
| parseTemperature | 纯 JVM | "0,3"/"abc"/空/1.5→回退 0.3/1.0 合法 |

## §6 验证标准

- 上述测试全绿；`testDebugUnitTest` 全量回归 ≥42 个用例全绿
- `assembleDebug` + `assembleRelease` 双绿
- UI 冒烟：设置页能渲染、保存后配置仍在（Robolectric 数据层测试代替真机）
- 真机测试连接与中转站兼容性人工验证放 M7
