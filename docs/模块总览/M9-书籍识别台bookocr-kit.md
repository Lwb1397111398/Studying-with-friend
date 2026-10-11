# M9 书籍识别台（bookocr-kit）模块总览

> 这是项目里唯一不在安卓 App 里的模块：一套跑在电脑上的识别工具，产出物（txt / 插图版 PDF）用手机导入 App。

## 模块职责（一句话）

把扫描版 PDF（影印书）用 ZCode 的视觉子代理逐页转写成干净书稿，产出可被 App 现有导入链路直接吃掉的 `书名.txt` 和 `书名_插图版.pdf`，全程正文不进对话上下文（token 极省）。

## 运作流程（按真实数据流走）

1. **输入**：老板给一个扫描版 PDF 路径，说「识别这本书」。
2. **建工程**：`bookocr.py init 书名 --src PDF路径` 在 `bookocr-kit/jobs/书名/` 建工作区，台账 `manifest.json` 记录页清单/批次/状态/统计（正文永不入台账）。
3. **渲染**：`prep` 用 PyMuPDF 按 200dpi 把每页渲染成灰度 PNG（`images/0001.png…`），按每 10 页切批。
4. **识别循环**（调度员=主对话 agent，劳动力=子代理）：
   - `next` 领一批，输出「任务卡」（批号、10 张图绝对路径、三个输出文件路径、规范路径）；
   - 主对话用 Agent 工具派一个子代理：子代理读 `spec.md` + 10 张图 → 写 `batches/bXXX.txt`（每页首行〔页N〕）、`bXXX_notes.txt`（脚注）、`bXXX_figures.json`（真图页内比例位置）→ 只回一行统计 → 子代理销毁；
   - `check` 质检：页标记对账（漏页/乱序/重复，永不豁免）、U+FFFD 乱码硬判、页均字数与中文占比（`--force` 豁免，给图表页/空白页用）；通过标 done，失败标 failed；
   - `retry` 重置失败批；书大（>50 页）时主对话并行派 2–3 个子代理。
5. **打包**：`pack` 按页序合并各批 →
   - `output/书名.txt`（UTF-8 BOM，页标记剥除，图位置保留〔图：…〕描述行，脚注归书末「注释」区）；
   - `output/书名_插图版.pdf`（reportlab 排版：msyh 中文字体、书名独占封面页、章标题 15pt/节标题 12.5pt、按子代理标的 rect 比例从原 PDF 裁真图嵌入）。
6. **进 App**：微信传文件 → 书架「导入书籍」选文件。txt 走 M2 纯文本链路；插图版 PDF 走 M2 数字书链路（200dpi 文字层 153 字/页 > 100 字/页扫描线，不会被误判成扫描书），`PdfFigureExtractor` 自动抽图进 figures 表，M4a 阅读页 FigureRow 图文混排。

## 省 token 的机制

正文只落文件（子代理写 batches/，主对话只见一行统计）；子代理干完即销毁，正文不留在任何上下文；照片级大图先程序化缩放（PDF 源天然可控）；质检靠脚本不靠模型通读。实测：300 页书 ≈ 主对话 < 1.5 万 token。

## 入口文件清单

- `bookocr-kit/bookocr.py` — 全部七个子命令（init/prep/next/check/retry/status/pack）
- `bookocr-kit/spec.md` — 转写规范（子代理必读；「看不清按上下文编通顺」是老板拍板的口径）
- `bookocr-kit/SKILL.md` — agent 操作手册（本机已注册 skill `scan-book`）
- `bookocr-kit/README.md` — 新电脑三步安装
- `bookocr-kit/selftest.py` — 回归自测（合成 12 页假书，24 项断言，换电脑即可跑）
- `bookocr-kit/requirements.txt` — PyMuPDF + reportlab

## 对外接口 / 被谁调用

- 被 agent（人话「识别书」触发 skill scan-book）调用；产出物被 M2 导入链路消费。
- 换电脑：整夹拷走 + `pip install -r requirements.txt` + 拷 SKILL.md 到 `~\.agents\skills\scan-book\`（或不注册，直接叫 agent 打开 SKILL.md 照做）。

## 依赖的上游模块

- 无代码依赖。产出的兼容性依赖 M2 的 `BookParser`（章正则档位）、`PdfFigureExtractor`（幸存图提取）、M4a 的 `FigureRow`（图显 示）——均已实机验证兼容。

## 关键数据结构

- `manifest.json`：`pages[]`（no/image）、`batches{bXXX}`（pages/status: pending→assigned→done|failed/body/notes/figures/stats/fail_reason）、`figures[]`（id/page/rect 比例/kind/title/desc）。
- 批正文协议：`〔页N〕` 整行=页边界；`〔图：标题〕` 整行=真图占位（txt 保留、PDF 换成嵌图+图题）。

## 已知坑与约束

- `jobs/` 已 gitignore——书稿与页图不进 GitHub；换电脑不拷 jobs/。
- PDF 版排版依赖 `C:\Windows\Fonts\msyh.ttc`（Windows 自带）；Mac/Linux 需换字体路径（pack_pdf 里字体探测链可扩）。
- 子代理产出质量靠 check 兜底：连续 2 次质检不过要停手问老板，不要硬转（SKILL.md 铁律）。
- 真书前几页若是密集目录，PDF 变体的章节树可能碎（BookParser 对（页码）式目录行的既有行为，txt 主产出同样内容由目录校准消化）；实机验证用的是探针书，真书效果以第一本实战为准。

## 验证记录（2026-10-11）

- selftest.py 24 项断言全绿（状态机/质检/双产出/PDF 文字层与嵌图）。
- 真书《损害赔偿》60 页样本：b001+b002 真实子代理转写 7981 字，CIP 页/序言逐字准确、跨页断句无缝续接；pack 双产出成功。
- 模拟器实机：自产插图 PDF 经 SAF 导入 → figures 表 2 行、`files/figures/9/` 2 张 jpg、阅读页 FigureRow 图文混排显示（截图为证）。

## 最后更新

- 2026-10-11 首建：bookocr-kit 全套 + selftest 全绿 + 60 页样本真实跑 + 模拟器抽图实机验证通过。
