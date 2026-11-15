# Spike② 自动翻 20 页实测数据（auto20）

> 状态：**已完成**（2026-11-16，emulator-5554，1080x2400 / 420dpi / 60fps）。三模式各 2 遍，6 条原始数据全部有效，模式正确性以 logcat 行内 `[SMOOTH]/[ESTIMATED]/[WARMED]` 标记验证。

## 均值表（两遍均值）

| 模式 | 墙钟ms | 帧数 | p50 | p95 | max | jank>32 | jank>50 | 动画帧总耗时ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A 现款滚屏 (SMOOTH) | 6714 | 220 | 33ms | 49ms | 75ms | 120 | 9 | 6561 |
| B 估算分页 (ESTIMATED) | 6684 | 252 | 16ms | 49ms | 83ms | 103 | 4 | 6501 |
| C 估算+预热 (WARMED) | 2919 | 92 | 33ms | 49ms | 83ms | 56 | 2 | 2819 |

## 原始 logcat 行（每模式两遍）

```
11-15 18:46:29.837 I PagerDemo: 自动翻20页[SMOOTH] 墙钟6662ms 帧数220 p50=33ms p95=49ms max=83ms jank>32=120 jank>50=6 动画帧总耗时6511ms
11-15 18:47:13.439 I PagerDemo: 自动翻20页[SMOOTH] 墙钟6765ms 帧数219 p50=33ms p95=49ms max=66ms jank>32=120 jank>50=11 动画帧总耗时6611ms

11-15 18:53:33.684 I PagerDemo: 自动翻20页[ESTIMATED] 墙钟6681ms 帧数258 p50=16ms p95=49ms max=83ms jank>32=100 jank>50=3 动画帧总耗时6504ms
11-15 18:54:17.225 I PagerDemo: 自动翻20页[ESTIMATED] 墙钟6687ms 帧数246 p50=16ms p95=49ms max=83ms jank>32=106 jank>50=5 动画帧总耗时6498ms

11-15 18:55:08.323 I PagerDemo: 自动翻20页[WARMED] 墙钟2914ms 帧数93 p50=33ms p95=49ms max=99ms jank>32=56 jank>50=2 动画帧总耗时2827ms
11-15 18:55:52.484 I PagerDemo: 自动翻20页[WARMED] 墙钟2923ms 帧数91 p50=33ms p95=49ms max=66ms jank>32=56 jank>50=2 动画帧总耗时2810ms
```

## 快速读数（非结论，仅供对比）

- A→B：p50 从 33ms 降到 16ms（锚段首落点让 LazyColumn 只合成一页内容），jank>32 从 120 降到 103，墙钟基本持平（动画时长口径不同，见下）。
- B→C：墙钟从 6684ms 降到 2919ms、帧数减半——预热把「远距离动画滚」变成「瞬时跳 + 短距离动画」，单页耗时约从 334ms 降到 146ms。
- A/B 墙钟接近是巧合口径：A 是 tween 340ms 定长动画，B/C 经最小编译修复后走 `animateScrollToItem` 内置默认动画（原代码把 tween 误传给 scrollOffset 参数，见问题节）。
- 单次方差很小（各模式两遍差 <2%），两遍均值可信度尚可；样本仍只有 2，不足以给结论定论。

## 问题

### 1. APK 实际不存在 + 源码编译错误（做了最小编译修复，越权声明）
- 任务书称「APK 已构建好」，实际 `spike/pager-demo/app/build/` 整个不存在；首次 `gradlew assembleDebug` 失败，源码有 4 处编译错误。
- 最小修复（`MainActivity.kt`，共 3 处）：
  1. `Mode.ESTIMATED` 分支：`animateScrollToItem(target, tween(340, easing=...))` → `animateScrollToItem(target)`——该 API 签名是 `(index, scrollOffset)`，不收 AnimationSpec，tween 被误传给 scrollOffset（Int）参数；
  2. `Mode.WARMED` 分支：同上修复；
  3. 补 2 个缺失 import：`androidx.compose.foundation.gestures.animateScrollBy`、`androidx.compose.ui.input.pointer.positionChange`。
- **对测量的影响声明**：B/C 模式的翻页动画从「作者意图的 tween 340ms」变为 `animateScrollToItem` 内置默认 spring 动画（API 本不支持传 spec）。落点策略（估算锚段首 / 预热）未变。若后续要把 tween 340ms 保真进 B/C，需要改用 animateScrollBy + 自算目标像素，属于功能改动，未擅自动。
- 另外本次实测前源码从未编译通过 → 任何「历史 APK 数据」都不存在，本表是该项目首份实测数据。

### 2. 模拟器顶部 128px 触摸被系统窗口拦截（chip 点击自动化踩坑）
- 现象：三个 FilterChip（bounds y 16~142）用中心点/常规坐标点击均无效（MCP tap 与 `adb input tap` 都试过），按钮（y 169~274）一直正常。
- 定位：`dumpsys window windows` 显示 `StatusBar` 与 `ScreenDecorOverlay` 两个系统窗口 frame 均为 `[0,0][1080,128]`（挖孔屏模拟器配置），覆盖 chip 大半区域吞掉触摸；chip 仅剩 y 129~142 约 14px 窄条可点。
- 解决：chip 点击改用 **y=135**（x 仍用中心），三 chip 全部生效，选中态经截图核验。
- 附加发现：`adb shell input tap` 单次调用在本机对该模拟器部分场景无效（getevent 未见事件注入），MCP `android_ui_tap` 路径稳定可用；自动化建议一律用 MCP tap。

### 3. Spike① 独占等待超限（首轮未执行，本节为历史记录）
- 首轮任务轮询 20s/次累计约 26 分钟（15 分钟授权 + 10 分钟延伸）未见 `spike/mindmap-perf/results/summary.json`，期间前台始终为 `org.chromium.webview_shell`（ActivityRecord 每 ~2 分钟换实例，Spike① 活跃运行中），按边界停手未抢跑；随后 Spike① 收官、模拟器空闲，本任务重派后完成。

## 环境与口径

- 设备：emulator-5554（Android SDK built for x86_64，1080x2400，density 420，60fps）；demo 进程 PID 9515 全程未重启。
- 每遍流程：`logcat -c` → 点 chip（y=135）→ 点「自动翻20页（下）」→ sleep 35s → `logcat -d -s PagerDemo`；每遍开始时 demo 自动回顶（scrollToItem(0)），两遍间无手动复位。
- 模式判定：logcat 行 `[MODE]` 标记（chip 选中态另经截图核验）；混入的 3 条 `[SMOOTH]` 误触数据已剔除不记录。
- 截图：`shots/auto20_A.png`（A 第 2 遍后）、`auto20_B.png`（B 第 2 遍后）、`auto20_C.png`（C 第 2 遍后）；`shots/diag_*.png` 为 chip 触摸排查过程图，可删。
