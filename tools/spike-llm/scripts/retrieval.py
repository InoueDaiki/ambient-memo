#!/usr/bin/env python3
"""検索の小実験。埋め込み(EmbeddingGemma 2 Text 270M)と文字 bigram の BM25 を比べる。

文書は2種類:
  transcript  文字起こしそのまま
  summary     --summary-from で指定した LLM の要約(LLM の結果を流してから使う)

使い方: python scripts/retrieval.py [--summary-from results/raw/xxx.cpu.test.jsonl]
ピークメモリは /usr/bin/time -l の下で再起動して記録する。
"""
import argparse
import collections
import json
import math
import pathlib
import re
import subprocess
import sys
import time
import unicodedata

import numpy as np
import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL = ROOT / "models" / "embeddinggemma-2-text-270m.litertlm"


def bigrams(s: str) -> list[str]:
    s = re.sub(r"\[\d\d:\d\d\]|話者\d|自分:", " ", s)
    s = re.sub(r"\s+", "", unicodedata.normalize("NFKC", s))
    return [s[i : i + 2] for i in range(len(s) - 1)]


class BM25:
    def __init__(self, docs, k1=1.5, b=0.75):
        self.toks = [collections.Counter(bigrams(d)) for d in docs]
        self.len = [sum(t.values()) for t in self.toks]
        self.avg = sum(self.len) / len(self.len)
        df = collections.Counter(w for t in self.toks for w in t)
        n = len(docs)
        self.idf = {w: math.log(1 + (n - c + 0.5) / (c + 0.5)) for w, c in df.items()}
        self.k1, self.b = k1, b

    def scores(self, q):
        out = []
        for t, l in zip(self.toks, self.len):
            s = 0.0
            for w in bigrams(q):
                f = t.get(w, 0)
                if f:
                    s += self.idf[w] * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * l / self.avg))
            out.append(s)
        return np.array(out)


def rank(scores):
    return list(np.argsort(-scores, kind="stable"))


def rrf(a, b, k=60):
    n = len(a)
    s = np.zeros(n)
    for r in (a, b):
        for i, d in enumerate(r):
            s[d] += 1 / (k + i + 1)
    return rank(s)


def metrics(ranks, qs, ids):
    rows = collections.defaultdict(list)
    for r, q in zip(ranks, qs):
        pos = [ids[i] for i in r].index(q["id"])
        for key in ("all", q["kind"]):
            rows[key].append(pos)
    return {
        k: {"n": len(v), "r@1": sum(p < 1 for p in v) / len(v), "r@3": sum(p < 3 for p in v) / len(v),
            "mrr": sum(1 / (p + 1) for p in v) / len(v)}
        for k, v in rows.items()
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary-from")
    ap.add_argument("--worker", action="store_true")
    args = ap.parse_args()
    mem = ROOT / "results" / "raw" / "embedding.mem.txt"
    if not args.worker:
        sys.exit(subprocess.call(["/usr/bin/time", "-l", "-o", str(mem), sys.executable, *sys.argv, "--worker"]))

    import litert_lm

    items = [x for x in yaml.safe_load((ROOT / "data" / "eval.yaml").read_text(encoding="utf-8")) if x["split"] == "test"]
    qs = yaml.safe_load((ROOT / "data" / "retrieval.yaml").read_text(encoding="utf-8"))
    ids = [x["id"] for x in items]
    corpora = {"transcript": [x["transcript"] for x in items]}
    if args.summary_from:
        by = {}
        for l in open(args.summary_from, encoding="utf-8"):
            r = json.loads(l)
            if r["task"] == "summary":
                by[r["id"]] = re.sub(r"<think>.*?</think>", "", r["text"], flags=re.S).strip()
        corpora["summary"] = [by[i] for i in ids]

    t0 = time.perf_counter()
    eng = litert_lm.EmbeddingEngine(str(MODEL), backend=litert_lm.Backend.CPU(thread_count=4))
    load = time.perf_counter() - t0

    def embed(texts):
        vs, ts = [], []
        for t in texts:
            s = time.perf_counter()
            r = eng.compute_embedding(t, litert_lm.EmbeddingOptions(normalize=True))
            ts.append(time.perf_counter() - s)
            vs.append(r.embedding)
        return np.array(vs, dtype=np.float32), ts

    qv, qt = embed([f"task: question answering | query: {q['q']}" for q in qs])
    res = {"load_s": load, "dim": qv.shape[1], "query_ms_median": float(np.median(qt) * 1000), "corpora": {}}
    for name, docs in corpora.items():
        dv, dt = embed([f"title: none | text: {d}" for d in docs])
        bm = BM25(docs)
        br = [rank(bm.scores(q["q"])) for q in qs]
        out = {"doc_ms_median": float(np.median(dt) * 1000), "bm25": metrics(br, qs, ids)}
        for dim in (768, 256, 128):
            a, b = qv[:, :dim], dv[:, :dim]
            a = a / np.linalg.norm(a, axis=1, keepdims=True)
            b = b / np.linalg.norm(b, axis=1, keepdims=True)
            er = [rank(v) for v in a @ b.T]
            out[f"embed{dim}"] = metrics(er, qs, ids)
            out[f"hybrid{dim}"] = metrics([rrf(x, y) for x, y in zip(er, br)], qs, ids)
        res["corpora"][name] = out
    (ROOT / "results" / "retrieval.json").write_text(json.dumps(res, ensure_ascii=False, indent=1) + "\n")
    for n, c in res["corpora"].items():
        for k, v in c.items():
            if isinstance(v, dict):
                print(n, k, {a: round(b, 3) for a, b in v["all"].items()}, "para r@3", round(v["paraphrase"]["r@3"], 3))
    print({k: v for k, v in res.items() if k != "corpora"})


if __name__ == "__main__":
    main()
