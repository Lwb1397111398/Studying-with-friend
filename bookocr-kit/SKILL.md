---
name: scan-book
description: 识别扫描版 PDF 书籍为可导入阅读 App 的 txt / 插图版 PDF。触发词：「识别书」「扫书」「把这本书转成 txt」「书稿识别」。用 bookocr-kit 配套程序 + 子代理批量看图转写，正文只落文件不进对话，token 消耗极低；任何能读文件、派子代理的 agent（ZCode / Qoder / 反重力等）都能执行本手册。
---

# 识别书（scan-book）

把老板给的扫描版 PDF 逐页看图转写成干净书稿。**你（执行本手册的 agent）是调度员，不亲自看图转字。**

## 本手册 agent 无关（白嫖各家额度的正确姿势）

- 只依赖三个能力：**读文件、写文件、派子代理**。ZCode / Qoder / 反重力等任意 agent 均可执行；产出（批文件/台账/txt）全落盘，换 agent 接着跑互不干扰。
- 各家子代理并发上限不同：**ZCode 实测=1，必须串行**；其他 agent 先派 2 个试水，没报并发错误（concurrency limit 之类）再逐步加大。
- 「子代理」= 你所在产品里的 Task / Agent 工具（独立上下文、干完即销毁的帮手）。没有该工具就串行自己干，正文照样只落文件。

## 省 token 铁律（违反任何一条 = 白烧老板的额度）

1. **正文永远只落文件。** 主对话任何时刻不粘贴、不转述正文内容。
2. **每批派一个子代理**（并发允许时可多批并行），子代理自己 Read spec.md 和页图，干完即销毁。
3. **子代理回报只许一行统计**（批号 + 字数 + 脚注条数 + 图数），任务卡里写明。
4. **check 过了才领下一批**；失败的批 retry 后重派一次，再失败就把失败原因列给老板，不要反复硬转。
5. 主对话只调 bookocr.py 的子命令，每次输出只有几行——别自己另起炉灶解析 manifest。

## 设置（kit 目录 config.json；init 时快照进工程，改全局不影响进行中的书）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `route` | `subagent` | 识别路线：`subagent`=AI 逐页看（子代理看图，老板默认）/ `aivision`=AI 逐页看（视觉 API 直连）/ `pull-first`=高效率模式（先拔文字层，拔不动的批再走视觉 API）/ `pull-only`=只拔文字层 |
| `pages_per_batch` | 10 | 每批页数 |
| `dpi` | 200 | 页图渲染精度 |
| `aivision_workers` | 5 | aivision 页级并发 |
| `chapter_review` | true | 章界疑点是否派子代理裁决 |
| `output` | `["txt","pdf"]` | 产出物开关（txt 永远产出） |

老板没说参数就全用 config 默认。

## 标准流程

1. **问清素材**：老板给 PDF 路径，书名默认用文件名。
2. **建工程（init 直接建批，不渲染）**：
   ```
   python bookocr-kit/bookocr.py init <书名> --src <PDF绝对路径>
   ```
   **>300 页的大书先跟老板报个预计规模**再继续。init 输出的「下一步」按 config 的 route 走，下面 3~4 步按 route 对号入座。
3. **AI 逐页看 · 子代理路线（route=subagent，老板默认）**：
   - 先 `prep <书名>` 渲染全书页图（AI 看图需要页图；下条 aivision 同样要）。
   - 循环：`next <书名>` 领任务卡 → 派子代理 → `check <书名> <批号>`，过了再领下一批。
   - 子代理 prompt 模板：
     > 你是书页转写员。先用 Read 读 <规范路径 spec.md>，严格按规范执行。然后逐张 Read 以下图片并转写：<任务卡>。写完三个文件后自查规范末尾的清单。只回报一行统计。
   - check 失败 → `retry` 后重派一次（prompt 附失败原因）；连续 2 次不过 → 停手把批号/原因/页码列给老板。
   - 子代理若单批工具调用暴涨（>30 次）、耗时 >30 分钟，多半在自作主张裁剪放大图片（实测质量并不更好、时间 4 倍），检查 spec.md「工作方式硬约束」一节是否存在，缺了补上再重派。
4. **AI 逐页看 · aivision 直连 API（route=aivision）**：
   ```
   python bookocr-kit/bookocr.py aivision <书名> [--workers 5]
   ```
   用 kit 目录 `apis.json`（gitignore，换电脑自配）或本机 Agent Manager 档案库里 caps 含 vision 的档案；页级并发、批完自动 check。超时/限流降 --workers；个别页稳定超时别硬刷（白烧额度），`pull` 重拔该批写占位 + `check --force` 放行，或子代理单转该页。
5. **高效率模式（route=pull-first / pull-only）**：
   ```
   python bookocr-kit/bookocr.py pull <书名>
   ```
   很多「扫描书」自带出版社 OCR 文字层，pull 分钟级拔完、零幻觉零额度；跨页段落自动缝合。pull 记录的图版页（thin_pages）用 `prep --pages` + `patch <书名>` 派视觉只补这几页的图描述，再 `check <批号> --force` 放行。
6. **切章（全书转完必跑——产出手机 App 直入的章界，替代 App 端猜测）**：
   ```
   python bookocr-kit/bookocr.py chapters <书名>
   ```
   程序自动：前置块（封面/版权/各版序言/作者简介）与目录、正文章、附录各自成章 → 与目录页条目逐条对账 → 章号断号/薄章体检。
   - 无疑点 → 直接 pack（大多数书到这步就完）。
   - 有疑点 → 会写 `<工程>/chapters_review_task.txt`。派一个**纯文本子代理**（不用看图）读任务卡，把每条裁决行补全动作（ADD 补漏切章 / DROP 删误切 / IGNORE 维持）写进 `<工程>/chapters_review.txt`，然后吸收重切：
     ```
     python bookocr-kit/bookocr.py chapters <书名> --apply <工程>/chapters_review.txt
     ```
     反跑到无疑点为止。个别裁决不了的疑点记台账不阻塞，最终汇报列出。不想派裁决就 `chapters <书名> --no-review`（疑点只记台账，pack 不拦）。
7. **全批 done 且切章后打包**：
   ```
   python bookocr-kit/bookocr.py pack <书名>
   ```
   产出 `jobs/<书名>/output/书名.txt`（**头部带章界元数据块——App 识别到即按电脑侧章表直入，不再自己猜章节**）和 `书名_插图版.pdf`（没装 reportlab 则只有 txt）。忘跑 chapters 会被拦，或 `pack --auto-chapters` 一条龙。
8. **汇报**（人话，给老板）：总页数、总字数、图张数、**章数与疑点裁决情况**、产出的两个文件完整路径、一句「微信传 txt 到手机 → 书架点导入书籍 → 选文件，App 自动按电脑侧章表分章」。附 token 粗账（pull 批零消耗；aivision 批走识图 API、10 页约 23 秒；子代理批约 50 万 token/10 页）。

## 断点续转（会话中断/换会话/换 agent）

```
python bookocr-kit/bookocr.py status <书名>
```
看进度 → 从对应步骤继续（转写循环 / 切章裁决 / 打包）。done 的批不重转，chapters 裁决持久在台账。**新会话先 status，别重 init。**

## 故障处理

| 症状 | 处置 |
| --- | --- |
| 子代理回报字数异常少 | check 会拦。看 fail_reason：缺页标记就重派并附原因 |
| 同一批连续 2 次质检不过 | stop。把批号、原因、对应页码列给老板，请他确认这几页是不是图表页/糊页 |
| PDF 路径失效（pack 时裁不了图） | txt 照出；插图版 PDF 跳过并告知老板 |
| 老板中途想换参数（每批页数等） | 别改已完成批；config 改动只影响新 init 的工程，必要时重 init 新工程 |
| aivision 单页稳定超时 | 降 --workers；仍失败改走 pull 兜底或子代理单转，不要硬刷 |
| chapters 疑点越裁越多 | 停：把疑点清单列给老板（多半目录页本身识别烂，或该书分册重编号） |
| pack 报「还没切章」 | 先跑 chapters，或直接 pack --auto-chapters |

## 换电脑 / 换 agent

见 kit 内 README.md：装 Python 3.10+ 与 `pip install -r requirements.txt`，把本 SKILL.md 拷进对应 agent 的技能目录（ZCode：`~\.agents\skills\scan-book\`），或直接对该 agent 说「打开 bookocr-kit/SKILL.md 照着做」。jobs/ 不随拷贝走，每台电脑识别自己的书。aivision 路线需在 kit 目录 `apis.json` 填 base_url/api_key/default_model 三键（已 gitignore）；子代理路线零配置。
