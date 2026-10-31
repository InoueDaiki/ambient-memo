#!/usr/bin/env python3
"""VAD と話者 embedding の速度とメモリを、雑音のキャッシュを持たない別プロセスで測る。

例: python speed_misc.py vad silero_vad.onnx / python speed_misc.py speaker nemo_en_titanet_small.onnx
"""
import resource
import sys
import time

import numpy as np

from common import RESULTS, SR, load_16k, speakers, utts, write_json
from spk_eval import embed, make_extractor
from vad_eval import make_vad, run


def rss() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6


kind, model = sys.argv[1], sys.argv[2]
us = [u for s in speakers()[:10] for u in utts(s, "nonpara30")[:6]]
audio = [load_16k(u.wav).astype(np.float32) for u in us]
base = rss()
if kind == "vad":
    x = np.concatenate(audio)
    v, w = make_vad(model, 0.5)
    t = time.perf_counter()
    _, proc = run(v, w, x)
    dur = len(x) / SR
else:
    ext = make_extractor(model)
    t = time.perf_counter()
    for y in audio:
        embed(ext, y)
    proc = time.perf_counter() - t
    dur = sum(len(y) for y in audio) / SR
res = {"kind": kind, "model": model, "audio_s": round(dur, 1), "rtf_1thread": round(proc / dur, 5),
       "rss_base_mb": round(base, 1), "rss_peak_mb": round(rss(), 1), "rss_added_mb": round(rss() - base, 1)}
write_json(RESULTS / "speed" / f"{model}.json", res)
print(res, flush=True)
