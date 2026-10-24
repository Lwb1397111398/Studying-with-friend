# bookocr-kit — 纸质书识别台

把**扫描版 PDF**（影印书、扫描书）转写成能导入手机阅读 App 的书稿：`书名.txt`（微信传手机直接导）+ `书名_插图版.pdf`（原图嵌回，App 自动显示插图）。

**两种识别模式**（kit 目录 `config.json` 的 `route` 键选，默认 AI 逐页看）：

- **AI 逐页看**（`subagent`，默认）：真 AI 一页一页看图转字，断句、脚注、表格都干净。子代理路线用你所在 agent 的额度（ZCode / Qoder / 反重力等都能跑，见下）；`aivision` 变体改为直连你自己配的识图 API，10 页约 23 秒。
- **高效率看**（`pull-first` / `pull-only`）：很多影印书底子里藏着出版社级文字层，程序直接拔出来整理——分钟级、零 AI 费用、零幻觉；只有拔不动的批才让 AI 看图。

识别完程序自动**切章对账**：封面/版权/各版序言/作者简介/目录/正文章/附录各自成章，并与书的目录页逐条核对，拿不准的少数疑点派 AI 裁决。打包出的 txt 头部带「章界元数据块」——**手机 App 导入时读到它就直接按这份章表分章，不再自己猜**（章节切错的根治方案）。

正文只写进文件、不占对话记忆——一本 300 页的书，主对话只花一万多 token。

## 换新电脑：三步就能用

1. **装 Python 3.10+**（python.org 下载，安装时勾选 Add to PATH），然后：
   ```
   pip install -r requirements.txt
   ```
   （只有 PyMuPDF 和 reportlab 两个包；不装 reportlab 也行，只是没有插图版 PDF。）
2. **让 agent 认识这个功能**（二选一）：
   - 把 `SKILL.md` 拷到 agent 的技能目录（ZCode：`C:\Users\<你>\.agents\skills\scan-book\`）；
   - 或者不拷，直接对 agent 说：「打开 bookocr-kit/SKILL.md，照着做，识别 D:\xxx.pdf 这本书」。
3. **开工**：把扫描版 PDF 放电脑任意位置，对 agent 说：「识别这本书，PDF 在 D:\...」。

> 整个 `bookocr-kit` 文件夹随身拷走（U盘/网盘）即可，识别工程（jobs/）留在各电脑本地，不用拷。

## 日常怎么用（本机已装好 skill）

跟 agent 说一句就行：**「识别这本书，PDF 在 D:\书\xxx.pdf」**。
识别完 agent 会汇报产出路径和章节情况，微信把 txt 传到手机 → App 书架「导入书籍」→ 选文件，App 自动按电脑侧章表分章，完事。

## 想白嫖其他 agent 的免费额度？

可以。这套工具**agent 无关**：spec.md（转写规范）自包含、批文件全落盘、SKILL.md 不绑定任何一家。Qoder / 反重力等定期送额度的 agent，直接对它说「打开 bookocr-kit/SKILL.md 照做」就能干活；跑到一半换一个 agent 接着跑也行（先 `status` 看进度）。注意各家子代理并发上限不同，SKILL.md 里写了试水方法。

## 设置（kit 目录 config.json，改完对之后 init 的新书生效）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `route` | `subagent` | `subagent` AI 逐页看（子代理）/ `aivision` AI 逐页看（识图 API 直连）/ `pull-first` 先拔文字层拔不动再视觉 / `pull-only` 只拔文字层 |
| `pages_per_batch` | 10 | 每批页数 |
| `dpi` | 200 | 页图渲染精度 |
| `aivision_workers` | 5 | 识图 API 并发数 |
| `chapter_review` | true | 章界疑点是否派子代理裁决（无疑点的书自动跳过） |
| `output` | `["txt","pdf"]` | 产出物开关 |

## 文件夹里都是什么

| 文件 | 干什么 |
| --- | --- |
| `bookocr.py` | 识别台程序（十一个子命令：init 建工程 / prep 渲染 / pull 文字层拔取 / aivision 视觉 API / patch 图版页补描述 / next 发任务 / check 质检 / retry / **chapters 切章对账** / status / pack 打包） |
| `SKILL.md` | agent 的操作手册（agent 无关：触发词、省 token 铁律、流程、故障处理） |
| `spec.md` | 转写规范（子代理干活前必读：怎么转字、图表怎么处理、三个输出文件） |
| `config.json` | 全局设置（上表；无密钥，随 kit 走） |
| `jobs/` | 每本书一个识别工程（页图、批次正文、台账、产出），已 gitignore 不进 GitHub |

## 常见问题

- **书稿内容会上传到网上吗？** 不会。`jobs/` 已被 gitignore，产出只存在本机；传手机用的是你自己的微信。
- **识别到一半中断了怎么办？** 没关系，对 agent 说「继续识别 xx 书」，它会 `status` 看进度从断点接着来（换 agent 接着跑也一样），完成的页和章节裁决都不会重来。
- **某几页糊得厉害？** agent 质检会揪出字数异常的批并列给你，你决定要不要处理。
- **图和表格呢？** 表格转成文字表格插在原位；真图额外产出插图版 PDF（App 里能看原图），txt 里在图的位置留一行图描述。
- **要花 AI 额度吗？** AI 逐页看模式逐页烧（子代理走对话额度、aivision 走识图 API 额度）；高效率模式几乎不花（文字直接拔，只有个别整页大图让 AI 写一句描述）。
- **章节会切错吗？** 电脑侧切完还和目录页对账，疑点让 AI 裁决；txt 带着章表进手机，App 照单分章不猜。真不满意，App 导入确认页里还能改名删章。
- **整页都是一张图（图版页）的书怎么办？** 程序把这类页记下来（thin_pages），正文写占位，用 `patch` 命令只让 AI 看这几页补图描述，不连累其他页。
