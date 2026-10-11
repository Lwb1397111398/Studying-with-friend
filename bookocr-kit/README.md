# bookocr-kit — 纸质书识别台

把**扫描版 PDF**（影印书、扫描书）转写成能导入手机阅读 App 的书稿：`书名.txt`（微信传手机直接导）+ `书名_插图版.pdf`（原图嵌回，App 自动显示插图）。

识别分两条路，agent 自动选：很多影印书自带出版社级文字层（肉眼是扫描图、底子里藏着字），`pull` 命令直接把字拔出来整理——分钟级、零 AI 费用、零幻觉；纯扫描书（真没字）才走视觉转写：优先 `aivision` 直连识图 API 并行看图，不行再派子代理看图转字。正文只写进文件、不占对话记忆——一本 300 页的书，主对话只花一万多 token，识别质量比传统 OCR 好（断句、脚注、表格都更干净）。

## 换新电脑：三步就能用

1. **装 Python 3.10+**（python.org 下载，安装时勾选 Add to PATH），然后：
   ```
   pip install -r requirements.txt
   ```
   （只有 PyMuPDF 和 reportlab 两个包；不装 reportlab 也行，只是没有插图版 PDF。）
2. **让 agent 认识这个功能**（二选一）：
   - 把 `SKILL.md` 拷到 `C:\Users\<你>\.agents\skills\scan-book\`（Mac/Linux 是 `~/.agents/skills/scan-book/`），以后说「识别书」就触发；
   - 或者不拷，直接对 agent 说：「打开 bookocr-kit/SKILL.md，照着做，识别 D:\xxx.pdf 这本书」。
3. **开工**：把扫描版 PDF 放电脑任意位置，对 agent 说：「识别这本书，PDF 在 D:\...」。

> 整个 `bookocr-kit` 文件夹随身拷走（U盘/网盘）即可，识别工程（jobs/）留在各电脑本地，不用拷。

## 日常怎么用（本机已装好 skill）

跟 agent 说一句就行：**「识别这本书，PDF 在 D:\书\xxx.pdf」**。
识别完 agent 会汇报产出路径，微信把 txt 传到手机 → App 书架「导入书籍」→ 选文件，完事。

## 文件夹里都是什么

| 文件 | 干什么 |
| --- | --- |
| `bookocr.py` | 识别台程序（十个子命令：init 建工程 / prep 懒渲染 / pull 文字层拔取 / aivision 视觉 API / patch 图版页补描述 / next 发任务 / check 质检 / retry / status / pack 打包） |
| `SKILL.md` | agent 的操作手册（触发词、省 token 铁律、流程、故障处理） |
| `spec.md` | 转写规范（子代理干活前必读：怎么转字、图表怎么处理、三个输出文件） |
| `jobs/` | 每本书一个识别工程（页图、批次正文、台账、产出），已 gitignore 不进 GitHub |

## 常见问题

- **书稿内容会上传到网上吗？** 不会。`jobs/` 已被 gitignore，产出只存在本机；传手机用的是你自己的微信。
- **识别到一半中断了怎么办？** 没关系，对 agent 说「继续识别 xx 书」，它会从断点接着来，完成的页不重转。
- **某几页糊得厉害？** agent 质检会揪出字数异常的批并列给你，你决定要不要处理。
- **图和表格呢？** 表格转成文字表格插在原位；真图额外产出插图版 PDF（App 里能看原图）。
- **「识别书」要花 AI 额度吗？** 有文字层的书几乎不花（文字直接拔，只有个别整页大图会让 AI 看图写一句描述）；纯扫描书才逐页看图——走 aivision 时用你自己配置的识图 API（本机 Agent Manager 档案库自动读取；换电脑可在 `bookocr-kit/apis.json` 填 base_url / api_key / model 三键，该文件已 gitignore 不会上传），走子代理时花 ZCode 额度。
- **整页都是一张图（图版页）的书怎么办？** 程序会把这类页记下来（thin_pages），正文写占位，用 `patch` 命令只让 AI 看这几页补图描述，不连累其他页。
