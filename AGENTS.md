# AGENTS.md — Studying with friend（读书搭子）

安卓平板读书陪伴 App：AI 搭子陪读——段落讲解、章末总结、思维导图、复习排期。仓库三块：

- `android/` — 主 App（Kotlin 2.0.21 + Compose Material3 + Room 2.6.1，AGP 8.7.3 / Gradle 8.9，minSdk 26 / targetSdk 35）
- `bookocr-kit/` — 电脑侧扫描书识别工具（Python：PyMuPDF + reportlab），产出 txt/插图版 PDF 喂给 App 导入链路
- `docs/` — 全中文文档

## 必读文档（改代码前，硬性流程）

1. `docs/模块总览/README.md` — 模块索引（M0 骨架 / M1 数据层 / M2 导入与解析 / M3 AI 客户端 / M4a 阅读 / M4b 讲解 / M5 总结 / M6 复习 / M7 自更新 / M9 bookocr-kit）。改哪个模块先读对应总览，**改完代码立刻回写该总览**。
2. `docs/当前进度.md` — 现在做到哪、已否决的方案（避免重复踩坑）。
3. 动导入/OCR 前读 `docs/模块总览/M2-导入与解析.md`（决策记录很长，历史方案多数已在里面被否决过）；动 bookocr-kit 前读 `bookocr-kit/spec.md` 与 `SKILL.md`。

## 构建与测试（Windows）

```bash
cd android
gradlew.bat assembleDebug --no-daemon          # APK 在 app/build/outputs/apk/debug/
gradlew.bat :app:testDebugUnitTest --no-daemon # 全量单测，当前基线 802 绿 +1 skipped
gradlew.bat assembleRelease --no-daemon
```

- JDK 17 + Android SDK 在 `C:\Users\jingyue\AppData\Local\Android\Sdk`（env 已配好）。
- `GRADLE_USER_HOME` 必须指向英文路径（中文路径会让测试 worker 类加载失败）。
- `gradle.properties` 已按低内存机器调优（daemon 1.5G、in-process 编译、并行关闭），构建一律加 `--no-daemon`。

## 架构边界

- 包根 `com.studyfriend.app`：`data/`（ai / db / importer / study / tts / update / vision）+ `ui/`（screens / nav / components / theme）+ `mindmap/`。UI 依赖 data，不反向；AI 调用收口在 `data/ai`（OpenAI 兼容端点，Key 加密存储）。
- 导入解析全在 `data/importer/`（含 `ocr/`、`pdfpipeline/`），章节切分规则改这里；正文分段规则与 bookocr-kit 的 `pull` 各有一套、职责已在 docs 里对照标定，改一侧要核对另一侧。
- Room schema 导出在 `android/app/schemas/`，改实体必须同步导出 schema。
- AI prompt 资产在 `app/src/main/assets/prompts/`，不在 Kotlin 代码里。

## 版本、签名与发布（易踩坑）

- `versionCode` = git 提交数、`versionName` = `0.1.<提交数>`——**必须保留完整 git 历史**。
- 签名密钥 `android/keystore/app.keystore` **不入库**（CI 从 secret 注入）；debug 与 release 用同一把钥匙，保证跨机器/CI 构建的包可互相覆盖安装。本地无 keystore 时 release 包未签名不可安装。
- 推 `main` → GitHub Actions（`.github/workflows/build-release.yml`）自动构建并发布到 latest Release，手机端「检查更新」即走这条链路。

## 隐私与入库红线

- 书稿内容绝不入库：`bookocr-kit/jobs/` 已 gitignore，别把识别出的书文本写进任何入库文件或对话外产物。
- `bookocr-kit/apis.json`（API base_url/key/model 三键）与 `.e2e/`（设备数据、截图现场）均不入库。
- 任何 key 只走环境变量/加密存储，不进对话与文档。

## 提交约定

中文 commit message，格式 `type(scope): 描述`，scope 常用 `import` / `ocr` / `ocr-kit` / `docs`；示例：`fix(import): 黑屏断连三件套——ETA 实测均速+Pass1 逐页断点续跑+导入期屏幕常亮`。前序会话遗留改动需单独提交或逐项列明。
