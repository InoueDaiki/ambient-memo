#!/usr/bin/env python3
"""JVM の検証に渡す 16 kHz の WAV を .cache/jvm-in/ に作る。公開データの変換で、コミットはしない。"""
import sys
from pathlib import Path

import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from common import CACHE, SR, eval_set, load_16k  # noqa: E402

out = CACHE / "jvm-in"
out.mkdir(exist_ok=True)
items = eval_set()
items = items[:: len(items) // 60][:60]
lines = []
for u in items:
    p = out / f"{u.spk}_{u.uid}.wav"
    sf.write(p, load_16k(u.wav), SR, subtype="PCM_16")
    lines.append(f"{u.spk}_{u.uid}\t{p}\n")
(out / "list.tsv").write_text("".join(lines))

# VAD から通す用に、発話と間をつないだ 1 本の音声を作る
import numpy as np  # noqa: E402

gap = np.zeros(SR * 2, dtype=np.float32)
scene = np.concatenate([x for u in items[:40] for x in (load_16k(u.wav), gap)])
p = out / "scene.wav"
sf.write(p, scene, SR, subtype="PCM_16")
(out / "scene.tsv").write_text(f"scene\t{p}\n")
print(len(lines), len(scene) / SR)
