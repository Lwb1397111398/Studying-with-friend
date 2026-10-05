# 评分记录：m2_code_bundle.md · 第 7 轮 · 代码

**总分：87**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 9
- 5 风险预案: 8
- 6 成本预算: 9
- 7 资产复用: 9
- 8 无占位符: 9
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [4 验证闭环] run_ablation.py:73 仅捕获 subprocess.TimeoutExpired，未捕获 CalledProcessError——任一消融组 replica_ocr.py 非超时失败（如模型加载异常、OOM、参数解析错误）会抛出 CalledProcessError 并沿栈传播至 main() 循环，整条跑批直接崩溃；文档宣称「超时记 SKIPPED 不阻塞跑批」，但非超时失败同样不阻塞的保障缺失。 → 建议：在 except 块增加 except subprocess.CalledProcessError as e: print(f'{name} FAILED rc={e.returncode} 记 SKIPPED，继续下一组', flush=True); return None，使 CalledProcessError 与 TimeoutExpired 同路径处理。
- [10 可执行性] build_eval_pages.py:20 对 d*.txt 行执行 float(ln.rpartition('\t')[2])，未捕获 ValueError——若某行缺制表符（rpartition 返回 ('','',ln)，[2] 为整行文本），float() 抛 ValueError 导致整个脚本崩溃。match_report.py:17-24 对同类输入做了 try/except ValueError 防御，两处不一致。 → 建议：在列表推导外或内加 try/except ValueError 跳过坏行（与 match_report.py 同口径），或在脚本开头加一次格式校验并给出明确错误信息。
- [5 风险预案] 风险 2（det-limit 提升导致 det 框爆炸）预案为「耗时超 5s/页即记入报告并评估」——「评估」无后续动作定义（评估谁、评估什么指标、评估后做什么决策），属于泛泛而谈，不满足「具体预案」要求。 → 建议：明确评估输出：如「耗时超 5s/页时，在 m3_ablation.md 该组行末追加 ⚠SLOW 标记，并在 M6 收口时由作者提交该组是否纳入 Phase 1 候选的书面结论（含耗时/精度权衡表），不纳入则在模块总览已知坑中注明排除理由」。
- [3 方法可行] det_pre 函数末尾 h -= h % 32; w -= w % 32 未对 h 或 w 为零做防护——当输入图像短边 < 32px 且 det_limit=None（nolimit 消融组）时，h 或 w 可被减至 0，cv2.resize 接收 (0, h) 或 (w, 0) 抛 cv2.error。虽实际文档页远大于 32px，但消融矩阵含 S1_nolimit 组且 run_ablation 无输入尺寸校验。 → 建议：在 h -= h % 32; w -= w % 32 后增加 h = max(h, 32); w = max(w, 32) 兜底，或在 det_pre 入口加 if min(h,w) < 32: raise ValueError(f'输入尺寸过小 h={h} w={w}') 使失败早暴露而非 cv2 深层报错。
- [8 无占位符] det_post_unclip 返回 (cv2.boxPoints(r2), r2[1][0], r2[1][1]) 中的 bw/bh 在调用方（replica_ocr.py:249-264）被解包后从未使用——dyn 分支只用 pts 取 bbox，non-dyn 分支用 pts 走 crop_and_rec_pre 旋转路径。bw/bh 为死变量，增加接口认知负担。 → 建议：移除 det_post_unclip 返回值中的 bw/bh（改为直接返回 pts 列表），调用方同步解包为单值；或若后续 Phase 1 需按 bw/bh 过滤极小框，则在 docstring 注明保留用途。