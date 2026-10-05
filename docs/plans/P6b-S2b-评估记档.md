# P6b S2b 评估记档（PP-OCRv6 评估 + D 判据独立验证）

日期：2026-10-05 ｜ 执行：计划案 v1.5 §S2b（PC 侧 90min）｜ 数据文件：`.e2e/p6a/t3_v6_eval.json`、`t3_v6_8page.json`、`t3_ocr_dval/`、`t3_dval_run.py`、`make_fixture_dval.py`

## 一、PP-OCRv6 评估（P6a 交接 #3）

**口径**：literal CER（`strip_citations.normalize_for_cer` + Levenshtein，gt_clean 基准，同 t3_run.py 原始口径）；140dpi fitz 渲染；v5 mobile 同脚本复算作公平基准（复算值与 t3_cer.json 完全一致，p032=5.85/p092=0.36/p122=5.31，口径自洽 ✓）。引擎 rapidocr 3.9.2（v6 ONNX 自动下载可得，medium 34.5M 超判据上限已排除记档）。

**8 页全量对比（v6-small 触发全量核查；v6-tiny 仅 3 页）**：

| 页 | v5 mobile | v6 tiny | v6 small | v6-small 判定 |
|---|---|---|---|---|
| p003 | 3.41 | — | 1.79 | 改善 |
| **p032（弱项）** | **5.85** | 5.20 | **3.81** | **相对改善 34.9%** |
| p062 | 1.73 | — | 0.00 | 改善 |
| p081（diagram 标定页） | 7.49 | — | 8.22 | **劣化 +0.73** |
| p092 | 0.36 | 0.72 | 0.48 | **劣化 +0.12** |
| p122 | 5.31 | 5.19 | 5.06 | 改善 |
| p247（diagram 标定页） | 12.40 | — | 9.38 | 改善 |
| p384 | 6.86 | — | 6.86 | 持平 |

**决策（计划案原文规则）**：v6-small p032 相对改善 34.9% ≥30% ✓，但 8 页 literal「不劣化」✗（p081/p092 两页劣化）→ **触发条件不完全满足，维持 v5，不记档升级候选**。p032（德文脚注）确有实质改善，PP-OCRv6 升级议题移交 P6c 再评估（届时若升级=管线变更，须重跑判据+sourceVersion 递增）。

## 二、D 判据独立验证（fixture 补跑，P2-1 训练/测试分离）

**fixture**：`android/app/src/test/resources/ocr/t3_dval.json`（6 页行级 pt 坐标，全部在 8 页标定集外）：

- **DIAGRAM 组（期望全命中）**：p135（横排主体+竖排侧标签，机制⑥）/ p218（行内竖排标签体系图，机制⑥同款，50 页抽样现成数据）/ p266（整页旋转 90° 体系图，机制④）——预检竖排框命中 4/3/12 个。
- **BODY 组（期望零误伤）**：p055/p119/p398（方向扫描 horizontal+非特殊形态清单+预检竖排框零命中）。

**结果**：JVM 单测 `OcrTextPostProcessorTest.rule c - independent validation 3 diagram hit and 3 body zero false positive` 通过——**3 个体系图页全命中 + 3 个正文页零误伤**，D 判据独立验证 ✅（v1.1 P2-1 升级条款闭合）。

**独立验证附带发现（记档）**：p219 为**横排树状图页**——树线字符 ┌ └ 被 OCR 认成汉字残迹「厂」「L」（`D_p219` 实测「厂给付不能」「L不完全给付」），页面无竖排框，规则 c 竖排框单特征按设计不覆盖此类页。树线字符特征在 OCR 输出侧不可靠正是 S3 证据驱动废弃该特征的原因（计划案 v1.4），p219 类横排树状图页移交 P6c PP-DocLayout（版面分析）解决。该页不入独立验证集（避免把「设计外形态」混入判据）。

## 三、产物清单

| 文件 | 说明 |
|---|---|
| `.e2e/p6a/t3_dval_run.py` | 体系图页 p135/p219/p266 补跑脚本（v5 mobile 同口径） |
| `.e2e/p6a/t3_ocr_dval/D_p{135,219,266}.json` | 补跑行级输出 |
| `.e2e/p6a/make_fixture_dval.py` | 6 页 fixture 合成（px→pt ×72/140，同 make_fixture_s3 口径） |
| `android/.../test/resources/ocr/t3_dval.json` | 独立验证 fixture（入库） |
| `.e2e/p6a/t3_v6_eval.py` + `t3_v6_eval.json` | v6 评估脚本（3 页×3 引擎） |
| `.e2e/p6a/t3_v6_8page.json` | v5 vs v6-small 全 8 页数据 |
