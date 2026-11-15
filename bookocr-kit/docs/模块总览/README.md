# 模块总览索引（bookocr-kit）

把扫描版 PDF 转成手机阅读 App 可导入的 txt / 插图版 PDF / KIT 图包。程序入口是 `bookocr.py`，共 18 个子命令，工作区是三个文件夹（待识别/ → 识别中/ → 已识别/）。按功能切成 6 个模块，改哪块先读哪块的总览。

| 模块 | 一句话职责 | 总览文件 |
| --- | --- | --- |
| 工程与状态 | 三文件夹工作区（scan 登记/auto 调度/init/prep 多进程渲染/done 归档/clean 清页图）、manifest 台账与断点续跑 | [工程与状态模块总览.md](工程与状态模块总览.md) |
| 文字层拔取 | 对自带文字层的 PDF 直接抽正文/脚注/图，分钟级零视觉调用；含 OCR 层纠错与图示区检测 | [文字层拔取模块总览.md](文字层拔取模块总览.md) |
| 视觉识别 | 把页图交给子代理或视觉 API 转写成正文（spec.md 是转写规范） | [视觉识别模块总览.md](视觉识别模块总览.md) |
| 质检与修复 | 逐页评分（qa，--fix 一键修）、批次硬质检（check，含跨页断段预警）、按页视觉重识别（reocr --exact） | [质检与修复模块总览.md](质检与修复模块总览.md) |
| 智能校对 | 批文件两层校对：规则层清确定性 OCR 残留，AI 层通读改形近错字/粘连（判错金标准=读不通就是错字）；坏页打 needs_vision 回视觉闭环；完成度写台账供 pack 闸门 | [智能校对模块总览.md](智能校对模块总览.md) |
| 切章与打包 | 切章对账（chapters）、图补语义（figure-desc）、产出 txt/PDF/zip（pack，校对闸门）、done 归档 | [切章与打包模块总览.md](切章与打包模块总览.md) |

## 一句话数据流

书放 `待识别/` → `scan` 登记 → `auto` 调度（pull 抽文字层 或 prep+aivision/子代理 看页图）→ 写 `batches/bXXX.txt|_notes.txt|_figures.json` → `check` 逐批质检 → `qa --fix` 全书体检修坏页 → `proofread` 两层校对（规则+AI，写完成度台账）→ `figure-desc` 补图语义 → `chapters` 切章对账 → `pack` 三件套（校对闸门拦截）→ `done` 归档进 `已识别/<书名>/`。

## 当前状态

见 [../当前进度.md](../当前进度.md)。2026-11-01 整体并回台式机 monorepo（权威源）；2026-11-15 三文件夹工作区上线（scan/auto/done/clean 调度闭环 + pack 校对闸门 + prep 多进程 + qa --fix + junk 黑名单外置 + check 跨页断段预警），selftest 全量 ALL PASS。
