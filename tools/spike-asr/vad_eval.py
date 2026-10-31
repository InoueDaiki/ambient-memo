#!/usr/bin/env python3
"""VAD の取りこぼしと誤検知を、JVS と雑音の合成データで測る。

- 音声あり: 発話と 1〜6 秒の間を交互に置き、雑音を重ねる。正解は JVS の音素ラベル(sil, pau 以外)
- 音声なし: DEMAND の環境音 1 時間ぶんと、ESC-50 の突発音。検知されたものはすべて誤検知
例: python vad_eval.py --model silero_vad_v5.onnx --threshold 0.5
"""
import argparse
import csv
import random
import resource
import time

import numpy as np
import sherpa_onnx

from common import CACHE, RESULTS, SEED, SR, active_rms, load_16k, noise_files, rms, speakers, utts, write_json

CONDS = {"snr20": 20, "snr10": 10, "snr5": 5, "snr0": 0}
SCENE_S = 20 * 60
ENVS_ALL = None


def lab_segments(path) -> list[tuple[float, float]]:
    """音素ラベルから発話中の区間を返す。"""
    seg = []
    for line in path.read_text().splitlines():
        c = line.split()
        if len(c) < 3:
            continue
        s, e, ph = float(c[0]), float(c[1]), c[2]
        if ph in ("sil", "pau"):
            continue
        if seg and s - seg[-1][1] < 0.01:
            seg[-1] = (seg[-1][0], e)
        else:
            seg.append((s, e))
    return seg


def scene(cond: str):
    """固定の乱数で合成シーンを作る。ディスクには書かない。"""
    rng = random.Random(f"{SEED}-{cond}")
    nrng = np.random.default_rng(abs(hash_str(cond)) % 2**31)
    pool = [u for s in speakers() for u in utts(s, "parallel100")[:12] if u.lab.exists()]
    rng.shuffle(pool)
    x = np.zeros(SCENE_S * SR, dtype=np.float32)
    truth = []  # (start, end, [(s, e)...])
    pos = int(2.0 * SR)
    target = 0.05
    for u in pool:
        y = load_16k(u.wav)
        y = y * target / active_rms(y) * 10 ** (rng.uniform(-6, 6) / 20)
        if pos + len(y) >= len(x) - SR:
            break
        x[pos : pos + len(y)] += y
        segs = [(pos / SR + s, pos / SR + e) for s, e in lab_segments(u.lab)]
        if segs:
            truth.append(segs)
        pos += len(y) + int(rng.uniform(1, 6) * SR)
    files = noise_files()
    n = np.zeros(0, dtype=np.float32)
    for env in sorted(files):
        f = files[env][int(nrng.integers(len(files[env])))]
        c = load_16k(f)
        n = np.concatenate([n, c[: SCENE_S * SR // len(files) + 1]])
    n = n[: len(x)]
    if len(n) < len(x):
        n = np.resize(n, len(x))
    if cond != "clean":
        n = n * target / rms(n) / 10 ** (CONDS[cond] / 20)
        x = x + n
    return x, truth


def hash_str(s: str) -> int:
    return sum((i + 1) * ord(c) for i, c in enumerate(s)) * 2654435761


def make_vad(model: str, th: float, max_speech: float = 20.0, min_silence: float = 0.5):
    cfg = sherpa_onnx.VadModelConfig()
    p = str(CACHE / "vad" / model)
    if model.startswith("ten"):
        cfg.ten_vad.model = p
        cfg.ten_vad.threshold = th
        cfg.ten_vad.min_silence_duration = min_silence
        cfg.ten_vad.min_speech_duration = 0.25
        cfg.ten_vad.max_speech_duration = max_speech
        cfg.ten_vad.window_size = 256
        win = 256
    else:
        cfg.silero_vad.model = p
        cfg.silero_vad.threshold = th
        cfg.silero_vad.min_silence_duration = min_silence
        cfg.silero_vad.min_speech_duration = 0.25
        cfg.silero_vad.max_speech_duration = max_speech
        cfg.silero_vad.window_size = 512
        win = 512
    cfg.sample_rate = SR
    cfg.num_threads = 1
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60), win


def run(vad, win, x):
    segs = []
    t = time.perf_counter()
    for i in range(0, len(x) - win, win):
        vad.accept_waveform(x[i : i + win])
        while not vad.empty():
            s = vad.front
            segs.append((s.start / SR, (s.start + len(s.samples)) / SR))
            vad.pop()
    vad.flush()
    while not vad.empty():
        s = vad.front
        segs.append((s.start / SR, (s.start + len(s.samples)) / SR))
        vad.pop()
    return segs, time.perf_counter() - t


def mask(segs, n):
    m = np.zeros(n, dtype=bool)
    for s, e in segs:
        m[int(s * 100) : int(e * 100)] = True
    return m


def score_scene(segs, truth, dur):
    n = int(dur * 100)
    t = mask([s for u in truth for s in u], n)
    d = mask(segs, n)
    # 発話全体(前後の無音を含む範囲)。正解の発話にかすらない検知は誤検知と数える
    ut = mask([(u[0][0] - 0.3, u[-1][1] + 0.3) for u in truth], n)
    miss = fa = 0
    head = []
    for u in truth:
        m = mask(u, n)
        cov = (m & d).sum() / max(1, m.sum())
        miss += cov < 0.5
        hit = [s for s, e in segs if e > u[0][0] and s < u[-1][1]]
        if hit:
            head.append(max(0.0, min(hit) - u[0][0]))
    false = [(s, e) for s, e in segs if not ut[int(s * 100) : int(e * 100) + 1].any()]
    non_h = (~ut).sum() / 360000
    return {
        "utts": len(truth), "utt_miss_pct": round(miss / len(truth) * 100, 2),
        "frame_miss_pct": round(float((t & ~d).sum() / t.sum() * 100), 2),
        "frame_fa_pct": round(float((~t & ~ut & d).sum() / (~ut).sum() * 100), 2),
        "false_segments": len(false), "false_per_nonspeech_h": round(len(false) / non_h, 1),
        "head_late_p95_s": round(float(np.percentile(head, 95)), 3) if head else None,
        "duty_pct": round(float(d.mean() * 100), 1), "truth_duty_pct": round(float(ut.mean() * 100), 1),
    }


def esc50_stream():
    base = CACHE / "noise" / "ESC-50-master"
    rows = list(csv.DictReader((base / "meta" / "esc50.csv").open()))
    human = {"crying_baby", "sneezing", "clapping", "breathing", "coughing", "footsteps", "laughing",
             "brushing_teeth", "snoring", "drinking_sipping"}
    groups = {"human_nonspeech": [], "other": []}
    for r in rows:
        groups["human_nonspeech" if r["category"] in human else "other"].append(r["filename"])
    for k, names in groups.items():
        ys = []
        for f in names:
            y = load_16k(base / "audio" / f)
            r = rms(y)
            ys.append(y / r * 0.05 if r > 1e-5 else y)
        yield k, np.concatenate(ys)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--threshold", type=float, default=0.5)
    a = ap.parse_args()
    res = {"model": a.model, "threshold": a.threshold, "speech": {}, "noise_only": {}}
    proc = audio = 0.0
    for cond in ["clean", *CONDS]:
        x, truth = scene(cond)
        vad, win = make_vad(a.model, a.threshold)
        segs, t = run(vad, win, x)
        proc, audio = proc + t, audio + len(x) / SR
        res["speech"][cond] = score_scene(segs, truth, len(x) / SR)
    for env, fs in noise_files().items():
        x = load_16k(fs[0])
        vad, win = make_vad(a.model, a.threshold)
        segs, t = run(vad, win, x)
        proc, audio = proc + t, audio + len(x) / SR
        h = len(x) / SR / 3600
        res["noise_only"][env] = {"hours": round(h, 3), "false_per_h": round(len(segs) / h, 1),
                                  "duty_pct": round(sum(e - s for s, e in segs) / (len(x) / SR) * 100, 2)}
    for k, x in esc50_stream():
        vad, win = make_vad(a.model, a.threshold)
        segs, t = run(vad, win, x)
        proc, audio = proc + t, audio + len(x) / SR
        h = len(x) / SR / 3600
        res["noise_only"]["esc50_" + k] = {"hours": round(h, 3), "false_per_h": round(len(segs) / h, 1),
                                           "duty_pct": round(sum(e - s for s, e in segs) / (len(x) / SR) * 100, 2)}
    res["rtf"] = round(proc / audio, 5)
    res["size_kb"] = round((CACHE / "vad" / a.model).stat().st_size / 1000)
    res["rss_peak_mb"] = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6, 1)
    write_json(RESULTS / "vad" / f"{a.model}__th{a.threshold}.json", res)
    print(a.model, a.threshold, res["rtf"], flush=True)


if __name__ == "__main__":
    main()
