---
name: scan-book
description: 识别扫描版 PDF 书籍为可导入阅读 App 的 txt / 插图版 PDF。触发词：「识别书」「扫书」「把这本书转成 txt」「书稿识别」。用 bookocr-kit 配套程序 + 子代理批量看图转写，正文只落文件不进对话，token 消耗极低。
---

# 识别书（scan-book）

把老板给的扫描版 PDF 逐页看图转写成干净书稿。**你（主对话）是调度员，不亲自看图转字。**

## 省 token 铁律（违反任何一条 = 白烧老板的额度）

1. **正文永远只落文件。** 主对话任何时刻不粘贴、不转述正文内容。
2. **每批派一个子代理**（Agent 工具），子代理自己 Read spec.md 和页图，干完即销毁。
3. **子代理回报只许一行统计**（批号 + 字数 + 脚注条数 + 图数），任务卡里写明。
4. **check 过了才领下一批**；失败的批 retry 后重派一次，再失败就把失败原因列给老板，不要反复硬转。
5. 主对话只调 bookocr.py 的子命令，每次输出只有几行——别自己另起炉灶解析 manifest。

## 标准流程

1. **问清素材**：老板给 PDF 路径。书名默认用文件名；老板没说参数就全用默认（dpi 200、每批 10 页）。
2. **建工程（init 直接建批，不渲染）**：
   ```
   python bookocr-kit/bookocr.py init <书名> --src <PDF绝对路径>
   ```
   **>300 页的大书先跟老板报个预计规模**再继续。
3. **先试文字层拔取（pull）——能省 95% 时间，且无需渲染页图**：很多「扫描书」其实带出版社 OCR 文字层。跑：
   ```
   python bookocr-kit/bookocr.py pull <书名>
   ```
   它逐批把文字层整理成批文件并自动质检（页眉页脚剔除、脚注按字号分区、段落按缩进重组、插图按原 PDF 图块坐标登记），分钟级完成、零幻觉。纯图版页（无文字、整页大图）写占位并记入 thin_pages，**不再连累整批走视觉**。实测《民法物权》577 页：57/58 批 1 分钟内拔完；唯一的失败批（图版页说明被当脚注滤走、页均字数不足）用 `check --force` 放行即符合预期。
4. **视觉路线（pull 拔不动的批）——首选 aivision 直连 API**：
   ```
   python bookocr-kit/bookocr.py aivision <书名> [--batch 批号] [--workers 5]
   ```
   用本机视觉 API 档案（Agent Manager 档案库 caps 含 vision 的档案，或 kit 目录 apis.json 自配）页级并发识图。**实测：10 页/批 23 秒、质量与子代理持平（相似度 0.985 vs 0.989）**，不受子代理并发上限约束。批量跑纯扫描书前，先抽 1 批与子代理/文字层对答案（`simcheck.py`）。API 端点超时/限流时降 --workers，连续失败就停手换子代理路线，不要硬烧额度。
   - 页图未渲染会提示 `prep --pages 起页-止页`——**懒渲染**：只渲染真正需要视觉的页。
5. **子代理路线（无 API 配置或 aivision 失败时）——领批**：`python bookocr-kit/bookocr.py next <书名>` → 输出「任务卡」（批号、图片路径、三个输出文件路径）。派子代理 prompt 模板：
   > 你是书页转写员。先用 Read 读 <规范路径 spec.md>，严格按规范执行。然后逐张 Read 以下图片并转写：<任务卡>。写完三个文件后自查规范末尾的清单。只回报一行统计。
6. **图版页补描述（patch）**：pull 记录的 thin_pages 由 patch 派视觉只补这几页的图描述：
   ```
   python bookocr-kit/bookocr.py prep <书名> --pages <起>-<止>   # 先懒渲染这几页
   python bookocr-kit/bookocr.py patch <书名>                    # 输出只含图版页的任务卡
   ```
   子代理/aivision 按卡补描述后跑 `check <书名> <批号> --force`。
7. **质检**：`python bookocr-kit/bookocr.py check <书名> <批号>`。
   - 通过 → 回到第 4 步。
   - 页标记缺失/乱序、乱码 → 原批 retry 后重派一次（prompt 里附上失败原因）。
   - 页均字数过低 → 先判断是不是图表页/图版页密集：是 → `retry` 后 `check --force` 放行；否 → 重转。
8. **并发实测教训（重要）**：部分套餐**子代理并发上限 = 1**——一次派 2 个以上会立刻报 `user concurrency limit exceeded` 全军覆没。对策：子代理路线必须**串行**；要并发就走 aivision。子代理若单批工具调用暴涨（>30 次）、耗时 >30 分钟，多半在自作主张裁剪放大图片（实测质量并不更好、时间 4 倍），检查 spec.md「工作方式硬约束」一节是否存在，缺了就补上再重派。
9. **全批 done 后打包**：
   ```
   python bookocr-kit/bookocr.py pack <书名>
   ```
   产出 `jobs/<书名>/output/书名.txt` 和 `书名_插图版.pdf`（没装 reportlab 则只有 txt）。
10. **汇报**（人话，给老板）：总页数、总字数、图张数、产出的两个文件完整路径、一句「微信传到手机 → 书架点导入书籍 → 选文件」。附 token 粗账（pull 批零消耗；aivision 批走本机 API 额度、10 页约 23 秒；子代理批约 50 万/10 页）。

## 断点续转（会话中断/换会话）

`python bookocr-kit/bookocr.py status <书名>` 看进度 → 直接从第 3 步继续。done 的批不会重转。**新会话先 status，别重 init。**

## 故障处理

| 症状 | 处置 |
| --- | --- |
| 子代理回报字数异常少 | check 会拦。看 fail_reason：缺页标记就重派并附原因 |
| 同一批连续 2 次质检不过 | stop。把批号、原因、对应页码列给老板，请他确认这几页是不是图表页/糊页 |
| PDF 路径失效（pack 时裁不了图） | txt 照出；插图版 PDF 跳过并告知老板 |
| 老板中途想换参数（每批页数等） | 别改已完成批；新参数只影响后续判断，必要时重 init 新工程 |

## 换一台电脑

见 kit 内 README.md：装 Python + `pip install -r requirements.txt`，把本 SKILL.md 拷进 `~\.agents\skills\scan-book\`（或直接对 agent 说「打开 bookocr-kit/SKILL.md 照着做」）。jobs/ 不随拷贝走，每台电脑识别自己的书。
