#!/usr/bin/env python3
"""VAD から ASR までをつないだ流れと、雑音だけを渡したときの幻聴を、最終候補について測る。

例: python e2e_eval.py --model zipformer-reazon --vad silero_vad_v5.onnx
"""
import argparse
import random

import numpy as np

from asr_eval import decode_offline, decode_online
from common import CACHE, RESULTS, SEED, SR, active_rms, cer_counts, load_16k, mix_snr, noise_clip, norm_text, rms, speakers, to_kana, utts, write_json
from models import MODELS
from vad_eval import make_vad, run


def scene(cond: str):
    """80 発話を間を空けて並べ、正解は発話順に連結した本文にする。"""
    rng = random.Random(f"{SEED}-e2e")
    us = [u for s in speakers() for u in utts(s, "nonpara30")[10:12]]
    rng.shuffle(us)
    us = us[:80]
    parts, refs = [], []
    for u in us:
        y = load_16k(u.wav)
        y = y * 0.05 / active_rms(y) * 10 ** (rng.uniform(-6, 6) / 20)
        parts += [y, np.zeros(int(rng.uniform(1, 4) * SR), dtype=np.float32)]
        refs.append(u.text)
    x = np.concatenate(parts).astype(np.float32)
    if cond != "clean":
        nrng = np.random.default_rng(77)
        # 環境を切り替えながら雑音を重ねる
        n = np.concatenate([noise_clip(nrng, 30 * SR)[0] for _ in range(len(x) // (30 * SR) + 1)])[: len(x)]
        x = mix_snr(x, n, float(cond.removeprefix("snr"))).astype(np.float32)
    return x, refs


def noise_segments(model_vad: str) -> list[np.ndarray]:
    """VAD が音声と判定してしまった雑音の区間を集める。ASR に渡るのはこの音だけ。"""
    rng = np.random.default_rng(99)
    segs = []
    base = CACHE / "noise" / "ESC-50-master" / "audio"
    files = sorted(base.glob("*.wav"))
    rng.shuffle(files)
    for f in files[:1000]:
        y = load_16k(f)
        r = rms(y)
        if r < 1e-5:
            continue
        y = (y / r * 0.05).astype(np.float32)
        vad, win = make_vad(model_vad, 0.5)
        ss, _ = run(vad, win, y)
        segs += [y[int(a * SR) : int(b * SR)] for a, b in ss]
    return segs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--vad", default="silero_vad_v5.onnx")
    ap.add_argument("--ctx", type=float, default=0.0, help="区間の前後に足す実音声の秒数")
    ap.add_argument("--zero", type=float, default=0.0, help="区間の前後に足す無音の秒数")
    ap.add_argument("--min-silence", type=float, default=0.5)
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    kind, build = MODELS[a.model]
    rec = build(2)
    dec = decode_online if kind == "online" else decode_offline
    lang = "ja" if a.model.startswith("nemotron") else ""
    res = {"model": a.model, "vad": a.vad, "ctx": a.ctx, "zero": a.zero, "min_silence": a.min_silence, "e2e": {}}
    for cond in ("clean", "snr10"):
        x, refs = scene(cond)
        vad, win = make_vad(a.vad, 0.5, min_silence=a.min_silence)
        segs, _ = run(vad, win, x)
        z = np.zeros(int(a.zero * SR), dtype=np.float32)
        hyp = ""
        for s, e in segs:
            y = x[max(0, int((s - a.ctx) * SR)) : int((e + a.ctx) * SR)]
            hyp += dec(rec, np.concatenate([z, y, z]), lang)[0]
        ref = "".join(refs)
        e, n = cer_counts(norm_text(ref), norm_text(hyp))
        ek, nk = cer_counts(to_kana(ref), to_kana(hyp))
        res["e2e"][cond] = {"segments": len(segs), "utts": len(refs), "cer_surface": round(e / n * 100, 2), "cer_kana": round(ek / nk * 100, 2)}
    ns = noise_segments(a.vad)
    z = np.zeros(int(a.zero * SR), dtype=np.float32)
    outs = [dec(rec, np.concatenate([z, y, z]), lang)[0] for y in ns]
    chars = [len(norm_text(t)) for t in outs]
    res["noise_only"] = {"segments": len(ns), "with_text_pct": round(float(np.mean([c > 0 for c in chars]) * 100), 1),
                         "mean_chars": round(float(np.mean(chars)), 1)}
    write_json(RESULTS / "e2e" / f"{a.model}{a.tag}.json", res)
    print(res, flush=True)


if __name__ == "__main__":
    main()
