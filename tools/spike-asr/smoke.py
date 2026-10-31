#!/usr/bin/env python3
"""全モデルが作れて日本語の見本を読めるかだけを確かめる。"""
import sys
import time

import numpy as np

from asr_eval import decode_offline, decode_online
from common import SR, load_16k
from models import MODELS, model_dir

x = load_16k(next(model_dir("nemotron35-160ms").glob("test_wavs/ja.wav"))).astype(np.float32)
names = sys.argv[1:] or list(MODELS)
for n in names:
    kind, build = MODELS[n]
    t = time.time()
    try:
        rec = build(4)
        lt = time.time() - t
        t = time.time()
        d = decode_online if kind == "online" else decode_offline
        hyp, _ = d(rec, x, "ja" if n.startswith("nemotron") else "")
        print(f"OK {n} load={lt:.1f}s dec={time.time()-t:.1f}s {hyp[:60]}", flush=True)
    except Exception as e:
        print(f"NG {n} {type(e).__name__} {str(e)[:200]}", flush=True)
    del rec
