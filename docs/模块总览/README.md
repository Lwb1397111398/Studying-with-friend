# 模块总览索引（bookocr-kit）

把扫描版 PDF 转成手机阅读 App 可导入的 txt / 插图版 PDF / KIT 图包。程序入口是 `bookocr.py`，共 13 个子命令。按功能切成 5 个模块，改哪块先读哪块的总览。

| 模块 | 一句话职责 | 总览文件 |
| --- | --- | --- |
| 文字层拔取 | 对自带文字层的 PDF 直接抽正文/脚注/图，分钟级零视觉调用；含 OCR 层纠错与图示区检测 | [文字层拔取模块总览.md](文字层拔取模块总览.md) |
| 视觉识别 | 把页图交给子代理或视觉 API 转写成正文（spec.md 是转写规范） | [视觉识别模块总览.md](视觉识别模块总览.md) |
| 质检与修复 | 逐页评分（qa）、批次硬质检（check）、按页视觉重识别（reocr --exact） | [质检与修复模块总览.md](质检与修复模块总览.md) |
| 切章与打包 | 切章对账（chapters）、图补语义（figure-desc）、产出 txt/PDF/zip（pack） | [切章与打包模块总览.md](切章与打包模块总览.md) |
| 工程与状态 | init/prep/status、manifest 台账、页图渲染与断点续跑 | [工程与状态模块总览.md](工程与状态模块总览.md) |

## 一句话数据流

`init` 建工程 →（pull 抽文字层 或 prep+aivision/子代理 看页图）→ 写 `batches/bXXX.txt|_notes.txt|_figures.json` → `check` 逐批质检 → `qa` 全书体检 → `reocr` 修坏页 → `figure-desc` 补图语义 → `chapters` 切章对账 → `pack` 出 `output/` 三件套。

## 当前状态

见 [../当前进度.md](../当前进度.md)。2026-10-27 完成 5 本书的修复与重打包，改动集中在文字层拔取与质检修复两块。
