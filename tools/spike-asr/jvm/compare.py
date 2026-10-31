#!/usr/bin/env python3
"""JVM の出力と Python の出力を発話ごとに比べる。使い方: python jvm/compare.py <モデル名> <JVM の TSV>"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from common import CACHE, cer_counts, eval_set, norm_text  # noqa: E402

model, jvm_tsv = sys.argv[1], sys.argv[2]
items = eval_set()
items = items[:: len(items) // 60][:60]
py = {}
for line in (CACHE / "out" / f"{model}__clean.tsv").read_text(encoding="utf-8").splitlines():
    c = line.split("\t")
    py[(c[0], c[1])] = c[3] if len(c) > 3 else ""
jv = {}
for line in Path(jvm_tsv).read_text(encoding="utf-8").splitlines():
    c = line.split("\t")
    jv[c[0]] = c[1] if len(c) > 1 else ""
same = sum(py[(u.spk, u.uid)] == jv[f"{u.spk}_{u.uid}"] for u in items)
ej = ep = n = 0
for u in items:
    r = norm_text(u.text)
    e, m = cer_counts(r, norm_text(jv[f"{u.spk}_{u.uid}"]))
    ej, n = ej + e, n + m
    ep += cer_counts(r, norm_text(py[(u.spk, u.uid)]))[0]
print(f"同一 {same}/{len(items)} CER JVM {ej / n * 100:.2f}% Python {ep / n * 100:.2f}%")
