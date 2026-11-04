#!/usr/bin/env python3
"""話題の切れ目の答えを採点する。results/topics/*.jsonl を読み、表と scores.json を書く。

比べるもの
- 切れ目の適合率、再現率、F1。ちょうど同じ行と、前後 1 行のずれを許したもの
- 混ざったメモ: 答えで分けたメモのうち、正解の話題を 2 つ以上含むものの割合。利用者が困るのはこれ
- 割れた話題: 正解の話題のうち、2 つ以上のメモに分かれたものの割合
- 話題が1つの例を、誤って分けた割合
- Pk: 窓の両端が同じ話題かどうかの食い違いの割合。窓の幅は正解の話題の平均の長さの半分
- 読めなかった答えの割合。読めないと、アプリはまとまり全体を1件のメモにする

比べる基準として、分けない(今までのアプリ)を同じ表に載せる。
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results" / "topics"


def segments(starts: list[int], n: int) -> list[int]:
    """各行(0 から)の話題の番号。"""
    out, topic = [], -1
    marks = set(starts)
    for i in range(1, n + 1):
        if i in marks:
            topic += 1
        out.append(max(topic, 0))
    return out


def matched(gold: set[int], pred: set[int], tolerance: int) -> int:
    """切れ目を 1 対 1 で、近いものから組にする。"""
    pairs = sorted((abs(g - p), g, p) for g in gold for p in pred if abs(g - p) <= tolerance)
    used_g, used_p, hits = set(), set(), 0
    for _, g, p in pairs:
        if g not in used_g and p not in used_p:
            used_g.add(g)
            used_p.add(p)
            hits += 1
    return hits


def score(records: list[dict]) -> dict:
    n_examples = len(records)
    unread = sum(1 for r in records if r["pred"] is None)
    gold_b = pred_b = hit0 = hit1 = 0
    pred_segs = mixed = gold_segs = split = 0
    single = single_split = exact = 0
    pk_err = pk_all = 0
    seg_lengths = [r["lines"] / len(r["gold"]) for r in records]
    k = max(1, round(sum(seg_lengths) / len(seg_lengths) / 2))
    for r in records:
        n = r["lines"]
        # 読めない答えは、アプリと同じく分けないものとして数える
        pred = r["pred"] or [1]
        gold = r["gold"]
        g, p = set(gold) - {1}, set(pred) - {1}
        gold_b += len(g)
        pred_b += len(p)
        hit0 += matched(g, p, 0)
        hit1 += matched(g, p, 1)
        gs, ps = segments(gold, n), segments(pred, n)
        for topic in set(ps):
            pred_segs += 1
            if len({gs[i] for i in range(n) if ps[i] == topic}) > 1:
                mixed += 1
        for topic in set(gs):
            gold_segs += 1
            if len({ps[i] for i in range(n) if gs[i] == topic}) > 1:
                split += 1
        if len(gold) == 1:
            single += 1
            single_split += bool(p)
        exact += sorted(set(pred)) == sorted(set(gold))
        for i in range(n - k):
            pk_all += 1
            pk_err += (gs[i] == gs[i + k]) != (ps[i] == ps[i + k])

    def ratio(a, b):
        return round(a / b, 3) if b else None

    precision0, recall0 = ratio(hit0, pred_b), ratio(hit0, gold_b)
    precision1, recall1 = ratio(hit1, pred_b), ratio(hit1, gold_b)

    def f1(p, r):
        return round(2 * p * r / (p + r), 3) if p and r else 0.0

    walls = [r["wall"] for r in records if "wall" in r]
    return {
        "examples": n_examples,
        "goldBoundaries": gold_b,
        "predBoundaries": pred_b,
        "unreadable": ratio(unread, n_examples),
        "exact": {"precision": precision0, "recall": recall0, "f1": f1(precision0, recall0)},
        "within1": {"precision": precision1, "recall": recall1, "f1": f1(precision1, recall1)},
        "mixedMemos": ratio(mixed, pred_segs),
        "memos": pred_segs,
        "splitTopics": ratio(split, gold_segs),
        "singleTopicSplit": ratio(single_split, single),
        "exactExamples": ratio(exact, n_examples),
        "pk": ratio(pk_err, pk_all),
        "pkWindow": k,
        "meanWallSeconds": round(sum(walls) / len(walls), 2) if walls else None,
    }


def baseline(records: list[dict]) -> list[dict]:
    return [{**r, "pred": [1], "output": "1"} for r in records]


def table(rows: list[tuple[str, dict]]) -> str:
    head = "| 対象 | 例 | 読めない | 切れ目 F1(一致) | 適合率 / 再現率(±1 行) | 混ざったメモ | 割れた話題 | 1 話題の誤分割 | Pk |\n"
    head += "|---|---|---|---|---|---|---|---|---|\n"
    for name, s in rows:
        w = s["within1"]
        single = "-" if s["singleTopicSplit"] is None else f"{s['singleTopicSplit']:.0%}"
        head += (
            f"| {name} | {s['examples']} | {s['unreadable']:.0%} | {s['exact']['f1']:.2f} | "
            f"{(w['precision'] or 0):.2f} / {(w['recall'] or 0):.2f} | {s['mixedMemos']:.0%} | "
            f"{s['splitTopics']:.0%} | {single} | {s['pk']:.2f} |\n"
        )
    return head


def main() -> None:
    scores = {}
    rows = []
    for path in sorted(RESULTS.glob("*.jsonl")):
        records = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()]
        name = path.stem
        for subset in ("concat", "natural", "all"):
            sub = [r for r in records if subset == "all" or r["set"] == subset]
            for label, rs in ((name, sub), ("分けない", baseline(sub))):
                key = f"{label}/{subset}"
                if key in scores:
                    continue
                scores[key] = score(rs)
                rows.append((f"{label} / {subset}", scores[key]))
    (RESULTS / "scores.json").write_text(json.dumps(scores, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(table(rows))


if __name__ == "__main__":
    main()
