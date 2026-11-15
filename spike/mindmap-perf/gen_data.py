# -*- coding: utf-8 -*-
"""生成千节点导图测试数据（模拟全书总导图形态：书→编→章→节→要点）。

用法：python gen_data.py
产出：data/expand_N.json（全展开）与 data/collapsed_N.json（depth>=2 折叠），N ∈ {100,500,1000,2000}

形态依据产品真实数据（TreeText.toMapData 输出）：
  {nodeData: {id, topic, children, expanded?}, direction: 2, arrows: [], summaries: []}
层级配比近似真实书树：根(书) → 编 → 章 → 节 → 要点，topic 为 4~12 字法律概念。
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

WORDS = [
    "物权变动", "善意取得", "无权处分", "公示公信原则", "债权让与", "债务承担",
    "合同解除权", "违约责任", "不当得利", "无因管理", "占有改定", "指示交付",
    "预告登记", "异议登记", "担保物权", "抵押权顺位", "质权设立", "留置权成立",
    "买卖不破租赁", "一物数卖", "风险负担", "孳息归属", "添附", "混合担保",
    "保证期间", "诉讼时效中断", "格式条款", "缔约过失", "情势变更", "预期违约",
    "同时履行抗辩", "不安抗辩权", "代位权", "撤销权", "抵销", "提存",
    "要约承诺", "悬赏广告", "无权代理", "表见代理", "职务代理", "共同危险行为",
    "过错推定", "公平责任", "自助行为", "紧急避险", "正当防卫", "安全保障义务",
    "高空抛物", "饲养动物损害", "建筑物脱落", "堆放物倒塌", "林木折断", "地面施工",
]

# 层级子节点数范围（根/编/章/节/要点）：宽浅形态，贴近千章扫描书真实树
LEVEL_SPANS = [(3, 8), (8, 15), (5, 10), (1, 4), (0, 3)]


def gen_tree(target: int) -> dict:
    """生成恰好 target 节点的树：逐层按 span 随机扩张直到达标，末层截断微调。"""
    import random
    rnd = random.Random(20261116)
    counter = [0]

    def nid() -> str:
        counter[0] += 1
        return "n%05d" % counter[0]

    def topic() -> str:
        w = WORDS[rnd.randrange(len(WORDS))]
        return w if counter[0] % 3 == 0 else f"{w}（{rnd.randrange(100)}）"

    root = {"id": nid(), "topic": "《民法总论》全书导图", "children": []}
    level = [root]
    total = 1
    for lo, hi in LEVEL_SPANS:
        if total >= target:
            break
        next_level = []
        stop = False
        for node in level:
            n = rnd.randint(lo, hi) if lo != hi else lo
            for _ in range(n):
                if total >= target:
                    stop = True
                    break
                child = {"id": nid(), "topic": topic(), "children": []}
                node["children"].append(child)
                next_level.append(child)
                total += 1
            if stop:
                break
        if not next_level:  # 该层一个都没加（target 已达），结束
            break
        level = next_level
    return root


def wrap(root: dict) -> dict:
    return {"nodeData": root, "direction": 2, "arrows": [], "summaries": []}


def mark_collapsed(node: dict, depth: int) -> None:
    """depth>=2（章的子节点起）折叠：默认展开到章级，节/要点收起。"""
    if depth >= 2 and node.get("children"):
        node["expanded"] = False
    for c in node.get("children", []):
        mark_collapsed(c, depth + 1)


def count(node: dict) -> int:
    return 1 + sum(count(c) for c in node.get("children", []))


def main():
    for target in (100, 500, 1000, 2000):
        root = gen_tree(target)
        n = count(root)
        expand = wrap(root)
        croot = json.loads(json.dumps(root, ensure_ascii=False))
        mark_collapsed(croot, 0)
        collapsed = wrap(croot)
        for name, data in (("expand", expand), ("collapsed", collapsed)):
            path = os.path.join(HERE, "data", f"{name}_{n}.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
            # .js 变体：WebView Tester 环境下 fetch 大 JSON 会被渲染进程间歇性吞掉
            # （server 侧 200 但页面收不到），script 标签加载从未失败——数据走 JS 变量
            jspath = os.path.join(HERE, "data", f"{name}_{n}.js")
            with open(jspath, "w", encoding="utf-8") as f:
                f.write("window.__PROBE_DATA__=")
                json.dump(data, f, ensure_ascii=False)
                f.write(";")
        print(f"target={target} actual={n}  -> data/{name}_...json + .js（expand/collapsed ×{n}）")


if __name__ == "__main__":
    main()
