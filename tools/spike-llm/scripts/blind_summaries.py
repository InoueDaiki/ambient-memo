#!/usr/bin/env python3
"""要約の盲検採点用ファイルを作る、または採点結果を集計する。

  python scripts/blind_summaries.py export     # results/blind/sheet.md と key.json を作る
  python scripts/blind_summaries.py tally      # results/blind/grades.json を集計する

会話ごとにモデルの順番を入れ替え、A, B, ... の記号だけを見せる。
"""
import collections
import hashlib
import json
import pathlib
import re
import string
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
BLIND = ROOT / "results" / "blind"
SYSTEMS = ["gemma-e2b", "gemma-e4b", "lfm-jp", "qwen3-4b", "gemma-e2b-gguf", "gemma-e2b-think", "gemma-e4b-think"]


def load(sys_name):
    p = ROOT / "results" / "raw" / f"{sys_name}.gpu.test.jsonl"
    out = {}
    if p.exists():
        for l in p.open(encoding="utf-8"):
            r = json.loads(l)
            if r["task"] == "summary":
                out[r["id"]] = re.sub(r"<think>.*?</think>", "", r["text"], flags=re.S).strip()
    return out


def export():
    BLIND.mkdir(exist_ok=True)
    items = [x for x in yaml.safe_load((ROOT / "data" / "eval.yaml").read_text(encoding="utf-8")) if x["split"] == "test"]
    data = {s: load(s) for s in SYSTEMS}
    systems = [s for s in SYSTEMS if data[s]]
    key, lines = {}, []
    for it in items:
        order = sorted(systems, key=lambda s: hashlib.sha256((it["id"] + s).encode()).hexdigest())
        key[it["id"]] = {string.ascii_uppercase[i]: s for i, s in enumerate(order)}
        lines += [f"\n## {it['id']}(今日は {it['date']})\n", "```text", it["transcript"].strip(), "```\n"]
        for i, s in enumerate(order):
            t = data[s].get(it["id"], "") or "(空)"
            lines += [f"### {string.ascii_uppercase[i]}", t, ""]
    (BLIND / "sheet.md").write_text("\n".join(lines), encoding="utf-8")
    (BLIND / "key.json").write_text(json.dumps(key, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(len(items), "会話", len(systems), "システム")


def tally():
    key = json.loads((BLIND / "key.json").read_text(encoding="utf-8"))
    grades = json.loads((BLIND / "grades.json").read_text(encoding="utf-8"))
    agg = collections.defaultdict(lambda: collections.defaultdict(list))
    for cid, g in grades.items():
        for letter, v in g.items():
            for axis, score in v.items():
                agg[key[cid][letter]][axis].append(score)
    res = {s: {a: sum(v) / len(v) for a, v in d.items()} | {"n": len(d["overall"])} for s, d in agg.items()}
    (ROOT / "results" / "summary_grades.json").write_text(json.dumps(res, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for s, d in sorted(res.items(), key=lambda kv: -kv[1]["overall"]):
        print(s, {k: round(v, 2) for k, v in d.items()})


if __name__ == "__main__":
    {"export": export, "tally": tally}[sys.argv[1]]()
