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
2. **建工程 + 渲染**：
   ```
   python bookocr-kit/bookocr.py init <书名> --src <PDF绝对路径>
   python bookocr-kit/bookocr.py prep <书名>
   ```
   prep 输出总页数和批数。**>300 页的大书先跟老板报个预计规模**（每页约 2.5–3.5 千子代理 token）再继续。
3. **领批**：`python bookocr-kit/bookocr.py next <书名>` → 输出「任务卡」（批号、图片路径、三个输出文件路径）。
4. **派子代理**：任务卡原文 + 一句补充，作为 Agent 工具的 prompt。子代理 prompt 模板：
   > 你是书页转写员。先用 Read 读 <规范路径 spec.md>，严格按规范执行。然后逐张 Read 以下图片并转写：<任务卡>。写完三个文件后自查规范末尾的清单。只回报一行统计。
5. **质检**：`python bookocr-kit/bookocr.py check <书名> <批号>`。
   - 通过 → 回到第 3 步领下一批。
   - 页标记缺失/乱序、乱码 → 原批 retry 后重派一次（子代理 prompt 里附上失败原因，让它补）。
   - 页均字数过低 → 先判断是不是图表页/空白页密集：是 → `retry` 后 `check --force` 放行；否 → 重派。
6. **并行加速**（书 > 50 页时）：连跑两次 `next` 领出两批，**同一条消息里并行派 2–3 个子代理**；都回来后逐个 check。不要超过 3 个并发。
7. **全批 done 后打包**：
   ```
   python bookocr-kit/bookocr.py pack <书名>
   ```
   产出 `jobs/<书名>/output/书名.txt` 和 `书名_插图版.pdf`（没装 reportlab 则只有 txt）。
8. **汇报**（人话，给老板）：总页数、总字数、图张数、产出的两个文件完整路径、一句「微信传到手机 → 书架点导入书籍 → 选文件」。附 token 粗账（子代理约 X 万/页 × 页数）。

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
