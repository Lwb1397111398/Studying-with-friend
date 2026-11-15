# Spike①：mind-elixir 千节点导图性能对比（第 5 期 P2-⑥）

**问题**：调研拍板「导图换 mind-elixir-core 前先做千节点 spike」——扫描书章节数可能上千，
mind-elixir 对千级节点性能一般（调研原话）。落地时发现：**产品现用构建本来就是 mind-elixir**
（`assets/mindmap/MindElixirFull.iife.js`，2026-09 迁移自旧开发机、版本无记录），
所以本 spike 实际回答两个问题：

1. 千节点下现有构建到底什么表现（init/展开/平移帧率/内存）？
2. 若表现不行，升到官方最新版（v4.4.0 Full/Lite）能否解决？→ 「换/不换」结论。

## 文件

| 文件 | 作用 |
| --- | --- |
| `mind-elixir.current.js` | 产品现用构建原样拷贝（版本无记录，压缩包内无版本字符串） |
| `mind-elixir.v4-full.iife.js` | 官方 v4.4.0 全量 IIFE（jsdelivr CDN 下载） |
| `mind-elixir.v4-lite.iife.js` | 官方 v4.4.0 Lite IIFE（无插件的轻量构建） |
| `gen_data.py` | 生成四档测试树（100/500/1000/2000 节点，书→编→章→节→要点形态，全展开+折叠两版） |
| `probe.html` | 探针页：`?lib=cur|full|lite&nodes=N&fold=0|1`，自动 init 计时→(fold=1) refresh 展开计时→5s rAF 帧采样→POST 上报 |
| `server.py` | 本地 HTTP：GET 静态 + POST /report 落盘 results/ |
| `run_spike1.py` | 驱动：跑 3 库 × 4 档 × 2 折叠 = 24 组，期间 adb swipe 四向平移，出汇总表 |
| `results/` | 每组原始报文 + summary.json |
| `shots/` | 关键场景截图 |

## 复现步骤

```bash
# 1) 起本地 server（窗口 1）
cd spike/mindmap-perf && python server.py 8123
# 2) 模拟器在线后跑全矩阵（窗口 2）
python run_spike1.py            # 全 24 组约 8 分钟；--quick 只跑冒烟
# 3) 看 results/summary.json
```

- 浏览器用模拟器自带的 **WebView Browser Tester**（`org.chromium.webview_shell`）——
  与产品 App 同一系统 WebView 引擎，比 Chrome 更贴近真实。
- 模拟器访问宿主机：`http://10.0.2.2:8123/`。
- 已知坑：WebView 冷启动后立刻动态加载子资源偶发秒失败（探针已带诊断 fetch 与驱动重试兜底）。
- 模拟器是软件渲染（产品 MindMapPanel 在模拟器上强制 LAYER_TYPE_SOFTWARE），帧率数据偏保守，
  真机只会更好——本 spike 目的为「量级判断」而非精确帧率。

## 指标口径

- `init`：`new MindElixir + init(data)` 返回后等 2 个 rAF（含 layout+首次 paint）。
- `expand`：fold=1 时 `refresh(全展开数据)` 的同口径耗时（对应「折叠图展开全部」交互）。
- 帧率：5 秒 rAF 采样窗口，期间 adb swipe 四向平移各一次；p50/p95/max 单位 ms，
  jank32/jank50 = 帧间隔 >32ms / >50ms 的帧数。
- `heap`：performance.memory.usedJSHeapSize（Chrome/WebView 专有）。

## 结果与结论（2026-11-16 实测，24/24 组全出数）

| 指标 | cur 现构建 | full v4.4.0 | lite v4.4.0 |
| --- | --- | --- | --- |
| 折叠态 init（100~2000 全档） | **30~61ms** | 38~64ms | 32~79ms |
| 全展开 init @1000 节点 | 340ms | 313ms | 292ms |
| 全展开 init @2000 节点 | 1157ms | 566ms | 629ms |
| 折叠图展开全部 @2000 节点 | 2008ms | 598ms | 669ms |
| swipe 平移 jank>32ms 占比（5s 窗口 ~266 帧） | **1.5~7%**（n500 复测两遍 8/19） | 10~28%（n500 复测两遍 74/71） | 14~31% |
| swipe 平移 p50 帧间隔 | 16.7ms（满帧） | 16.7ms | 16.7ms |

- 明细：`results/summary_final.json`（合并全矩阵+补跑+jank 复测）；关键场景截图 `shots/`。
- **结论：不换库**。现构建平移最流畅（jank 占比 1/4~1/8 于 v4.4，拖动是导图最高频交互），
  v4 的渲染速度优势只在「一次性全展开」低频场景；换库另需迁移 WebView 桥接层。
  千章书真正解法=默认折叠（折叠态 init <60ms），`TreeText.toMapData` 一行改动即可。
- 全量结论与产品语境见 `docs/模块总览/M6-全书总览与复习中心.md` 2026-11-16 条目。
