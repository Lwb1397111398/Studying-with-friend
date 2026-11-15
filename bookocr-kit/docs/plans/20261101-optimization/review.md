# bookocr-kit 优化审查清单（2026-11-01，待执行）

> 来源：两路并行细读——①代码审查（7 个 Python 文件共 4474 行全读 + pyflakes 复跑 + 沙箱验证关键行为）；②提示词审查（SKILL.md、spec.md、bookocr.py 内嵌任务卡/aivision/patch 提示、proofread.py AI 层、dispatch.py 逐个核对正则与代码行为）。
> 定位：本清单是优化积压 backlog；残留错字修复另见 `docs/plans/20261101-vision-repair/plan.md`（那边已覆盖：needs_vision 阈值、视觉 API fallback 路径）。两案不重叠。

## 总体结论

工程纪律好：状态机幂等、护栏注释带实测依据、pack 前置防呆成体系、selftest 93 项对 12 页合成书全链路回归、proofread 的「受控 JSON + 禁触清单 + 程序逐条校验」是全项目提示词样板。风险集中在**旁路通道**（非 Windows 平台、stitch/proofread 绕过作废逻辑、图配对盲配）与**规范-代码契约裂缝**（spec 承诺了代码不兑现的行为）。P0+P1 清完约一个工作日。

## P0（会造成崩溃或成品数据错误）

| # | 位置 | 问题 | 影响 | 修法 |
|---|---|---|---|---|
| P0-1 | bookocr.py:31（用于 150/160） | `import msvcrt`（Windows 专用文件锁模块）顶层无条件导入 | 非 Windows 机器上 `python bookocr.py --help` 都崩——直接违背「整个文件夹单机可拷走」的核心卖点 | try/except 导入：Windows 用 msvcrt、其他平台 fcntl.flock、再兜无锁实现（同文件十几行，零新依赖） |
| P0-2 | spec.md §转写原则第 2 条 | 只对「个别字词」授权推断补写；**整页糊图/图版页没有任何处置指令**；「保证语句通顺」措辞会把推断权放大成整句重写 | 子代理遇糊页只能整页编造，产出页标记齐全、qa 全绿，错误文本**零痕迹**进成书 | **按老板政策（2026-11-01 两轮定稿）改为三层推断指令**：①正文看不清的单字词/形近错字→按上下文推断出正确字（读者读得通优先，不机械死板逐字存档）；②**引用骨架不猜**——条号（第X条）、判决字号（X年台上字第X号）、数字、人名、书名→只按原图辨认，反复读图（多 dpi/裁剪放大/独立二次调用）仍认不清→行内〔辨认不清〕+ 台账登记（**最后手段**），绝不编造（编造的条号读者无法识别，引用即出事）；③**整页糊不走占位**：先穷尽读图手段，结合上下文重建正文，重建页 rule=inferred 落台账供老板抽检 + **双通道交叉核对**（一次带现文本对照改、一次只看图独立转写，diff 差异逐处裁定后才写回） |
| P0-3 | SKILL.md:49 手工模板 vs dispatch.py:61-69 | SKILL.md 教的手工派批模板比 dispatch.py 自动生成的**少关键护栏**（「不要运行 bookocr.py/dispatch.py/stitch.py 任何命令、不动别的批次、不改代码」）；SKILL.md 全文未提 dispatch.py/check_ready.py | 照手册派批时子代理可能擅自跑命令改 manifest，制造孤儿批，流程看似卡死 | SKILL.md 第 3 步改为「标准做法 `python dispatch.py <书名> [批数]`」；手工模板替换为 dispatch.py 的 header 原文 |

## P1（明确缺陷，特定条件触发）

### 代码侧

| # | 位置 | 问题 | 影响 | 修法 |
|---|---|---|---|---|
| P1-1 | bookocr.py:2329-2343（pack_pdf 同套计数器 2569-2587） | 图占位行与图注册表按「页内出现序 ↔ rect 纵向序」盲配：行数≠注册表数时，多的注册图**无条件追加页尾**；行序与 rect 序不一致时错位配对 | 多图页图文对应关系错、图跑页尾——成品级数据错误；pull 路线因占位行天然按 y 序而免疫，视觉路线可触发 | fi 超界或与 rect 明显矛盾时告警；多图页（len(figs_here)>1）列入 spotcheck 供目检 |
| P1-2 | bookocr.py:1996-2008 | `_detect_toc` 目录区要求逐页「hits≥2 且 ≥40%」，遇首个不达标页即 break | 目录区中间夹一张空白页/图版页 → 后续目录页全漏收 → 对账偏移算歪、成串假 toc_miss | break 改「连续 2 页不达标才断」 |
| P1-3 | bookocr.py:2044-2049 | `chapters --apply` 的 ADD 裁决硬编码 BODY lv1 | 补切参考文献/索引时 r 标成 BODY，App 端按正文章处理（阅读进度/AI 总结范围全错） | ADD 时对标题跑一遍 `_title_kind()`，认出 front/back 就用之，兜底 BODY |
| P1-4 | proofread.py:562-570 | `_load_trad` 用正则从 bookocr.py **源码文本**抽 `_TRAD_ONLY` 字表（不 import） | 三种静默坏法：字表写法变化→空字表繁体检测全失效；括号内加注释→注释混入字表→大量假繁体页误标烧 reocr 额度；bookocr.py 改名→裸 traceback | 字表落成 kit 目录数据文件 `trad_chars.txt`，两侧同读（最稳）；或 import + 正则回退 + 警告 |
| P1-5 | stitch.py:76-150；proofread.py:741-748 | ①stitch 独立运行不持 `_ManifestLock`（并发会话竞态），apply 后不作废章表缓存；②proofread 写回批文件后不删 `.stitched.json`/不重置 chapters/packed（对比 reocr 在 bookocr.py:747-752 有完整作废逻辑） | 多会话接力（本项目实际用法）下竞态；切章后跑校对若 AI merge 动到章标题段 → 章锚过期、App 定位错位 | stitch standalone 自取同一把锁（注意 chapters 持锁时子进程再取会自死锁，需内部 flag）；proofread 写回且有修改时照抄 reocr 的三行作废逻辑 |
| P1-6 | bookocr.py:2507-2521 | 插图版 PDF 中文字体只探测 `C:\Windows\Fonts` | 非 Windows 静默缺插图版 PDF | 候选列表按平台扩展（WINDIR 环境变量、Linux Noto/WQY 路径） |
| P1-7 | bookocr.py:473 + 367-372 | aivision 每页调用把 **spec.md 全文**塞进 prompt | 「约 15 次工具调用」「写三个文件」「回报一行」等子代理指令对纯 API 调用无意义且与 wrapper「别输出别的话」冲突——每页约 1/3 token 白烧（400 页书量级数十万 token），弱模型可能被带偏 | 维护一份删去「工作方式/输出文件/自查/回报」四节的精简 spec 供 aivision 拼接 |

### 提示词侧

| # | 位置 | 问题 | 影响 | 修法 |
|---|---|---|---|---|
| P1-8 | spec.md §转写原则第 4 条 vs bookocr.py:1929/1981 | spec 承诺「一、」式标题是切章依据，代码 SEC_NUM_RE 只认「第X节/讲」 | 用「一、二、三」分节的书切不出节界 → 目录对账批量假 toc_miss → 触发 SKILL.md 停手分支 | 二选一：spec 改口「节标题指『第X节/讲』；『一、』式保留单独成行即可」；或 SEC_NUM_RE 补 `[一二三四五六七八九十]+、` |
| P1-9 | bookocr.py:2167-2171 切章裁决任务卡 | 缺「判不了选 IGNORE」兜底、gap 类疑点无页首摘录、未写裁决文件绝对路径 | 子代理可能编造 ADD 污染章界，或写错目录 --apply 失败重派 | 卡首加「页号一律用 PDF 页号（〔页N〕的 N）；无把握一律 IGNORE，宁可不改；产出写到 <工程>/chapters_review.txt 绝对路径」 |
| P1-10 | dispatch.py:77-83 好设计未推广 | 衔接提示（防跨批幻觉）只在使用 dispatch.py 时存在 | 手工派批（SKILL.md 模板）丢掉这层保护 | 并入 P0-3 一起修：SKILL.md 模板统一从 dispatch.py 生成 |

## P2（卫生与打磨，顺手清）

- 死代码/未用变量：proofread.py:700 `if False`、718 `for f2 in [f]`、643-644 `job / b.get("body")`（body 缺失先 TypeError、fallback 永不生效）、667/730 未用变量；selftest.py:431；bookocr.py:99 补 `from typing import NoReturn`、500 的 `e` 打一行再重试。
- 异常吞没 7 处（bookocr.py:461/921/1701/2516/2527，proofread.py:273/294）——多数附近有日志可保留；bookocr.py:340-344 apis.json 坏 JSON 裸 traceback（proofread 同场景有 try/except，两处不一致）；bookocr.py:454-461 FIGURES 段解析失败整页图静默丢（至少 out 页号警告）。
- 标题正则四套口径漂移：bookocr.py:89/92（CH_RE 认 OCR 误字「笫」、SEC_RE 含「一、」、缺「卷/回」）vs bookocr.py:1918/1929（相反）vs proofread.py:51-53（第三份复制）——收敛为 bookocr.py 单一来源。
- 线程安全：bookocr.py:877 区域 figdesc 413 重试在多线程共用 PyMuPDF Document（非线程安全，可触发库级崩溃）——重裁移回主线程或加互斥锁。
- simcheck.py:24 只 import pymupdf 无 fitz 回退，与 load_fit() 口径不一；stitch.py:34 字符类「：：」重复且注释与类不符；dispatch.py:41-46 缺参 IndexError。
- 工具名绑定：dispatch.py:62-66、bookocr.py:326/1622 写死「Read/Write/Edit 工具」——改「用你的读/写文件工具（各家叫法可能是 Read/Write 或 read_file/write_to_file）」。
- spec.md「约 15 次工具调用」写死（pages_per_batch 可配，调 20 页时子代理会误判违规）——改公式「1（spec）+ 页数 + 3（写文件）」。
- 表格判据含糊：spec.md 第 6/7 条无对照句——补「第 6 条仅限无表格线版式；凡画表格线一律按第 7 条走图占位」。
- proofread.py:365-371 示例「女子的→好的」费解、「让文字读得通」授权偏宽——改对称示例「OCR 印出『淮确』→ find=淮确，replace=准确」+「禁止调整语序、增删成分」。
- SKILL.md 约 12% 冗余可删（pull 警戒段与故障表重复），估省 1.5-2KB。

## 测试缺口（selftest 93 项之外，按风险排序）

1. 目录形态夹具单一：只有「目录+`……3` 裸页码」一种；「简　　目」+全角括号页码（今天刚修的真 bug）无夹具；`_detect_toc` 夹空页断链行为无回归网（对应 P1-2）。
2. pack 图行/注册表不一致三场景：2 行 2 图顺序反、2 行 1 图、0 行 2 图（对应 P1-1）。
3. ADD 后置标题断言 r=="BACK"（对应 P1-3）。
4. `_load_trad` 失败分支（正则失配/空字表）零覆盖（对应 P1-4）。
5. reocr `--exact`/`--needs-vision` 重建路径、chapters 连跑两次（stitch 幂等）、锁等待超时：全无覆盖。

## 已核对无问题（审查证据，防重复劳动）

- 断点续跑口径与代码一致：next 只领 pending、retry 重置 failed/assigned→pending、reocr 按 reocr/pNNNN.json 续跑、stitch 有 .stitched.json 幂等、aivision 重跑只打 pending/failed。
- SKILL.md 对 proofread 的描述与 proofread.py 逐条吻合（受控 JSON/edits≤8/禁触/备份审计）——全项目最佳「提示词+代码双层护栏」范例。
- spec 格式与代码正则吻合项：〔页N〕整行、〔图：…〕全/半角冒号、图片文件名零填充、figdesc rect 比例坐标、patch 占位三行格式。
- 主程序锁覆盖正确（qa/pull/reocr 等全持锁，status 只读豁免）；性能无热点级问题（千页级全书载入约 20MB、对账 difflib 秒级）。

## 建议执行顺序

1. P0-1 msvcrt 兼容（半小时，解锁跨机器卖点）。
2. P0-2 + P0-3 + P1-10（提示词三连：spec 糊页指令、SKILL.md 模板统一、衔接提示推广）——下次识别剩余 4 本书之前必须完成。
3. P1-2 + P1-1 + P1-3（切章/pack 数据正确性三连）+ 对应测试缺口 1-3。
4. P1-4 + P1-5（旁路通道作废/锁）+ 测试缺口 4-5。
5. P1-6、P1-7（aivision 省 token，剩余 4 本若走 aivision 则提前）、P1-8、P1-9。
6. P2 顺手清。

## 启动提示词（复制给执行 Agent）

```
【项目路径】E:\AI Agent\work area\bookocr-kit_转移包_20261026\bookocr-kit

【开场动作】依次读完再动手：① docs\当前进度.md；② docs\plans\20261101-optimization\review.md（本次执行的完整清单，P0/P1/P2/测试缺口/已核对无问题五节都要读）；③ docs\模块总览\README.md 及受影响模块总览。

【目标与范围】按 review.md「建议执行顺序」逐批执行（至少完成 P0 全部 + P1 全部 + 测试缺口 1-5；P2 顺手清）。每修一条必须带对应 selftest 夹具（P1-1/P1-2/P1-3/P1-4 各有指定场景），跑 selftest 全绿才算这条完成。

【验收标准】①review.md 的 P0/P1 全部关闭且有 selftest 夹具兜底；②selftest 全绿；③pyflakes 无新增告警；④不动 jobs/ 内容、不动 manifest.src_pdf、不新增〔图：…〕行；⑤完成后更新 docs\当前进度.md 与受影响模块总览，提交推送。

【坑与约束】①改提示词时保持「提示词说的一定是代码真做的」——每改一处 spec/SKILL 先核对对应代码正则；②stitch 加锁注意 chapters 持锁时子进程再取同一把锁会自死锁（用内部 flag 区分内部调用）；③proofread 作废逻辑照抄 reocr 的三行，不要自创；④本仓库是台式机 monorepo 的同步镜像，直接提交推送即可。
```
