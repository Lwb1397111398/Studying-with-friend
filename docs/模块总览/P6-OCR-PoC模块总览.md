# P6 OCR PoC 模块总览

## 模块职责（一句话）
验证 PP-OCRv5 mobile（det+rec）在安卓端识别扫描书页面的可行性（P6a PoC），为 P6b 扫描件管线立项提供 Go/No-Go 数据。

## 运作流程（数据怎么流）
1. **入口**：`adb shell am start -n com.studyfriend.app/.OcrPocActivity --es dir <图片目录>`——从 intent extra `dir` 读页面 PNG 列表（140dpi 渲染图，须放应用私有目录 `/sdcard/Android/data/com.studyfriend.app/files/`，scoped storage 下 /sdcard/Download 应用只读）。
2. **模型**：`assets/ocr/` 三件套——ppocrv5-mobile-det.onnx（4.75MB）+ ppocrv5-mobile-rec.onnx（16.5MB）+ ppocrv5_dict.txt（18383 行），合计 21.08MB（判据④达标）。
3. **每页处理**（`OcrPocActivity.processPage`）：det 预处理（limit 960、/32 取整、(x/255-0.5)/0.5）→ det 推理（onnxruntime-android 1.22.0）→ 后处理（prob>0.3 阈值、8 连通 BFS、轴对齐框、扩张 25%）→ rec 批 8（48x320 拉伸、CTC greedy）→ 每页输出分阶段耗时 JSON。
4. **产出**：logcat `TAG=OcrPoc` 逐页 `detMs/detPostMs/recMs/totalMs/boxes/chars/nativeMB/javaMB` + 私有目录 `poc_result.json`；内存基线→峰值由 `dumpsys meminfo com.studyfriend.app` 采（TOTAL 行有前导空格，grep 别加 ^）。
5. **结论去向**：`.e2e/p6a/poc_report.md`（全量数据）→ `docs/plans/P6a-PoC报告.md`（归档）→ 老板拍板 fallback 口径（标点宽度豁免+树状图页降级视觉）→ 判据①②④ Go、⑤转正判待 P6b 放宽阈值、③真机留联测。

## 入口文件清单
- `android/app/src/main/java/com/studyfriend/app/OcrPocActivity.kt`（PoC 全部逻辑）
- `android/app/src/main/assets/ocr/`（模型+词典）
- `android/app/build.gradle.kts`（onnxruntime-android 依赖）
- `android/app/src/main/AndroidManifest.xml`（activity 注册）
- PC 侧工件：`.e2e/p6a/`（strip_citations/gt 链/t3 跑批/三视角 CER，全部 gitignore 本地保留）

## 对外接口 / 被谁调用
- 仅 adb am start 触发，不进 launcher；不被生产代码调用（P6b 落地时重写为 TextSource adapter，见决策案）。

## 依赖的上游模块
- M0 工程骨架（构建链）；M2 导入与解析（PdfPageRenderer 140dpi 口径对齐）；M3 AI客户端（GT 转写用商汤 sensenova vision，仅 PoC 标注用，不进 APK）。

## 关键数据结构
- 每页结果 JSON：`{page, detMs, detPostMs, recMs, preMs, totalMs, boxes, chars, nativeMB, javaMB}`
- 三视角 CER：`t3_cer_3views.json`（literal/punctfold/nopunct × 8 页）

## 已知坑与约束
- onnxruntime Java：类名 **OnnxTensor**（非 OrtTensor）；createTensor 收 **FloatBuffer** 不收 FloatArray；OnnxValue **无 asTensor()**，须 `out[0] as OnnxTensor`。
- Manifest：XML 注释不能含 `--`（`--es` 会炸 manifmerger）；**不能独立进程**（StudyApp.onCreate 的 VisionScheduler→WorkManager.getInstance 在非主进程崩）；**不能 Theme.NoDisplay**（不 finish 即 onResume 崩）→ 用 Translucent.NoTitleBar。
- adb shell/push 路径必须 `MSYS_NO_PATHCONV=1`（Git Bash 转换坑）。
- 模拟器 x86 耗时≠ARM 真机（判据③正式判定留真机）；OCR 增量内存 ~208MB PSS（onnxruntime arena 不回收）。
- PP-OCR v5 mobile 系统性把原书半角标点归一成全角（P6b 须做宽度映射后处理）；德文/英文小字弱；树状图页无法线性化（降级视觉兜底）。

## 最后更新
2026-02-10 · P6a PoC 完成：8 页模拟器跑通（中位 1.24-1.40s/页）、判据①②④ Go（老板拍板新口径）、报告归档 docs/plans/P6a-PoC报告.md
