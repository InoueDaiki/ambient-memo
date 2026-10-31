#!/usr/bin/env python3
"""1つのモデルを1つの条件で評価する。プロセスを分けて走らせ、ピークメモリを測る。

例: python asr_eval.py --model sensevoice-2025 --cond clean --threads 2
結果の集計は results/asr/<model>__<cond>__t<threads>.json。
書き起こしの本文は JVS のテキストを含むので .cache/out/ にだけ置く。
"""
import argparse
import resource
import time

import numpy as np

from common import (CACHE, RESULTS, SR, bootstrap_ci, cer_counts, degrade, eval_set, load_16k,
                    norm_text, to_kana, write_json)
from models import MODELS, model_dir


def dir_mb(name: str) -> float:
    """推論に使うファイルの合計。同じ役割の int8 版があれば int8 だけを数える。"""
    d = model_dir(name)
    files = [p for p in d.rglob("*") if p.is_file() and "test_wavs" not in str(p) and p.suffix != ".md"]
    int8 = {str(p).replace(".int8", "") for p in files if ".int8." in p.name}
    return sum(p.stat().st_size for p in files if str(p) not in int8) / 1e6


def rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6


def decode_online(rec, x: np.ndarray, lang: str = ""):
    """100ms ごとに流し込む。戻り値は (本文, 最初に部分結果が出た音声位置 秒)。"""
    st = rec.create_stream()
    if lang:
        st.set_option("language", lang)
    first = None
    step = SR // 10
    for i in range(0, len(x), step):
        st.accept_waveform(SR, x[i : i + step])
        while rec.is_ready(st):
            rec.decode_stream(st)
        if first is None and rec.get_result(st):
            first = (i + step) / SR
    st.accept_waveform(SR, np.zeros(int(0.8 * SR), dtype=np.float32))
    st.input_finished()
    while rec.is_ready(st):
        rec.decode_stream(st)
    return rec.get_result(st), first


def decode_offline(rec, x: np.ndarray, lang: str = ""):
    st = rec.create_stream()
    st.accept_waveform(SR, x)
    rec.decode_stream(st)
    return st.result.text, None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--cond", default="clean")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    kind, build = MODELS[a.model]
    items = eval_set()
    if a.limit:
        items = items[:: max(1, len(items) // a.limit)][: a.limit]
    audio = [degrade(load_16k(u.wav), a.cond, 1000 + i).astype(np.float32) for i, u in enumerate(items)]
    baseline = rss_mb()
    t0 = time.perf_counter()
    rec = build(a.threads)
    load_s = time.perf_counter() - t0
    after_load = rss_mb()
    decode = decode_online if kind == "online" else decode_offline

    rows, proc, durs, firsts = [], [], [], []
    for u, x in zip(items, audio):
        t = time.perf_counter()
        hyp, first = decode(rec, x, "ja" if a.model.startswith("nemotron") else "")
        proc.append(time.perf_counter() - t)
        durs.append(len(x) / SR)
        if first is not None:
            firsts.append(first)
        rows.append((u.spk, u.uid, u.text, hyp))

    out = CACHE / "out" / f"{a.model}__{a.cond}.tsv"
    out.parent.mkdir(exist_ok=True)
    out.write_text("".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")

    def score(f):
        e, n = zip(*(cer_counts(f(r[2]), f(r[3])) for r in rows))
        e, n = np.array(e), np.array(n)
        lo, hi = bootstrap_ci(e, n)
        return {"cer": round(e.sum() / n.sum() * 100, 2), "ci95": [round(lo * 100, 2), round(hi * 100, 2)]}

    p, d = np.array(proc), np.array(durs)
    res = {
        "model": a.model, "kind": kind, "cond": a.cond, "threads": a.threads, "n": len(rows),
        "audio_s": round(float(d.sum()), 1),
        "cer_surface": score(norm_text), "cer_kana": score(to_kana),
        "rtf": round(float(p.sum() / d.sum()), 4),
        "latency_p50_s": round(float(np.percentile(p, 50)), 3), "latency_p95_s": round(float(np.percentile(p, 95)), 3),
        "load_s": round(load_s, 2), "disk_mb": round(dir_mb(a.model), 1),
        "rss_base_mb": round(baseline, 1), "rss_after_load_mb": round(after_load, 1), "rss_peak_mb": round(rss_mb(), 1),
        "first_partial_s_mean": round(float(np.mean(firsts)), 2) if firsts else None,
    }
    write_json(RESULTS / "asr" / f"{a.model}__{a.cond}__t{a.threads}{a.tag}.json", res)
    print(res, flush=True)


if __name__ == "__main__":
    main()
