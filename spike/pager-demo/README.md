# Spike②：低成本分页手感对比 demo（第 5 期 P2-⑦）

**问题**：调研拍板「低成本分页体验验证（LazyColumn 预加载+按视口高度估算分页）——先验证
老板是否真在意假翻页，在意得厉害真分页就不是 P2 而是换阅读器内核的大决策」。
背景：产品 2026-11-15 体验优化轮已上线「点两侧/左右滑 = `animateScrollBy(±0.88 视口)`」的
假翻页（现款），落点是任意像素（页首可能是半截段落）。本 demo 对比三种翻页策略的
帧率/耗时/手感，回答「值不值得把翻页落点升级为视口估算分页」。

**独立项目说明**：本目录是独立 Gradle 项目（不在 `android/` 构建树内），CI 的
`assembleRelease` 不受影响；applicationId 独立（`com.studyfriend.spike.pagerdemo`），
装上后与产品 App 并存，测完可卸载。

## 三种模式

| 模式 | 策略 | 对应什么 |
| --- | --- | --- |
| A 现款滚屏 | `animateScrollBy(±视口×0.88, tween 340ms)` | 产品 ReadScreen 现行实现原样复刻 |
| B 估算分页 | 段高估算累计 ≥0.88 视口 → `animateScrollToItem` 锚段首 | 拍板里的「按视口高度估算分页」 |
| C 估算+预热 | B + 落点前 `scrollToItem(target-2)` 合成一帧再滚 | 拍板里的「LazyColumn 预加载」近似实现 |

正文 = 400 段法律教材风长文（固定种子可复现，零书稿内容），字号 18sp/行距 1.7/段间 12dp
与产品阅读页一致。手势与产品一致：点左 1/3 上页、右 1/3 下页、左右滑超 1/6 屏宽翻页。

## 复现步骤

```bash
cd spike/pager-demo
gradlew.bat assembleDebug --no-daemon      # APK: app/build/outputs/apk/debug/
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.studyfriend.spike.pagerdemo/.MainActivity
# 每个模式点 chip 切换 → 点「自动翻20页（下）」→ 看 UI 绿字与 logcat：
adb -s emulator-5554 logcat -d -s PagerDemo
```

- 「自动翻20页」从顶部开始连翻 20 次（每次间隔 120ms），Choreographer 逐帧统计，
  结果打印到 logcat（tag=PagerDemo）+ UI 卡片。每模式可重复点取平均。
- 指标口径：p50/p95/max 帧间隔（ms）、jank>32ms/>50ms 帧数、动画帧总耗时、20 页墙钟耗时。

## 结果与结论

见 `docs/模块总览/M4a-阅读页与粗读流水线.md` 2026-11-16 条目与 `docs/当前进度.md`（写完回填）。
demo APK 留给老板上手体验手感（三模式切换对比），手感拍板权在老板。
