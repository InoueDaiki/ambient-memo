#!/usr/bin/env python3
"""話者区間の検証の下準備。合成会話ごとに VAD、窓、embedding、ASR のトークン時刻を計算し、作業用に保存する。

保存先は .cache/speaker_seg/(コミットしない)。音声は保存しない。

会話の番号:
- 0〜9: 開発用(しきい値を決める)
- 10〜29: 評価用。以前の計測と同じ会話
- 30〜59: 確認用。開発用の話者を含まない

条件:
- clean / snr15: 以前の計測と同じ会話
- tclean / tsnr15: 発話の前後の無音を切り詰め、間を平均 0.15 秒にした会話(tight)

例: python speaker_seg_data.py --conds clean,snr15 --start 0 --end 60 --jobs 6
"""
import argparse
import pickle
import random
import time
from multiprocessing import Pool

import numpy as np

import der_eval
from common import CACHE, SEED, SR, active_rms, load_16k, mix_snr, noise_clip, speakers, utts
from der_eval import conversation
from spk_eval import embed, make_extractor
from vad_eval import lab_segments, make_vad, run

WORK = CACHE / "speaker_seg"
MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
VAD = "silero_vad.onnx"
ASR = "zipformer-reazon"
PRE, TAIL = 0.3, 0.8
MIN_EMBED = 0.4
N_DEV = 10
# 細かい区切り: 名前 -> (VAD のしきい値, 区切りの無音)
FINE = {
    "s10": (0.5, 0.10), "s15": (0.5, 0.15), "s20": (0.5, 0.20), "s30": (0.5, 0.30),
    "h10": (0.7, 0.10), "h20": (0.7, 0.20),
}
WINDOWS = {"w10h05": (1.0, 0.5), "w15h05": (1.5, 0.5), "w15h075": (1.5, 0.75)}
ALL_SPEAKERS = None


def dev_speakers() -> set[str]:
    return {s for i in range(N_DEV) for s in conversation(i)[3]}


def conv_for(i: int):
    """番号 30 以降は開発用の話者を除いた話者から作る。"""
    if i < 30:
        return conversation(i)
    keep = [s for s in ALL_SPEAKERS if s not in DEV]
    orig = der_eval.speakers
    der_eval.speakers = lambda: keep
    try:
        return conversation(i)
    finally:
        der_eval.speakers = orig


def conversation_tight(idx: int):
    """発話の前後の無音を切り詰めた会話。実際の会話の間に近づけるための負荷試験で、実測の分布ではない。"""
    rng = random.Random(f"{SEED}-tight-{idx}")
    n_spk = 2 + idx % 3
    pop = ALL_SPEAKERS if idx < 30 else [s for s in ALL_SPEAKERS if s not in DEV]
    usable = [s for s in pop if sum(u.lab.exists() for u in utts(s, "nonpara30")) >= 20]
    spk = rng.sample(usable, n_spk)
    pool = {s: [u for u in utts(s, "nonpara30") + utts(s, "parallel100")[5:] if u.lab.exists()] for s in spk}
    for s in spk:
        rng.shuffle(pool[s])
    seen = {s: list(pool[s]) for s in spk}
    x = np.zeros(200 * SR, dtype=np.float32)
    truth = []
    pos = int(0.5 * SR)
    prev = None
    while pos < 180 * SR:
        s = rng.choice([k for k in spk if k != prev] if rng.random() < 0.8 and prev else spk)
        prev = s
        if not pool[s]:
            pool[s] = list(seen[s])
        u = pool[s].pop()
        y = load_16k(u.wav)
        y = y * 0.05 / active_rms(y) * 10 ** (rng.uniform(-3, 3) / 20)
        lab = lab_segments(u.lab)
        a = max(0, int((lab[0][0] - 0.05) * SR))
        b = min(len(y), int((lab[-1][1] + 0.05) * SR))
        y = y[a:b]
        if pos + len(y) >= len(x):
            break
        x[pos : pos + len(y)] += y
        truth += [(pos / SR + p - a / SR, pos / SR + q - a / SR, s) for p, q in lab]
        gap = -0.5 if rng.random() < 0.1 else rng.expovariate(1 / 0.15)
        pos += len(y) + int(gap * SR)
    return x[: pos + SR], truth, spk[0], spk


def load_conv(cond: str, i: int):
    x, truth, me, spk = conversation_tight(i) if cond.startswith("t") else conv_for(i)
    env = None
    base = cond.removeprefix("t") if cond.startswith("t") else cond
    if base != "clean":
        n_, env = noise_clip(np.random.default_rng(5000 + i), len(x))
        x = mix_snr(x, n_, float(base.removeprefix("snr")))
    return x, truth, me, spk, env


def segments(x, th, sil, max_speech=20.0):
    vad, win = make_vad(VAD, th, max_speech=max_speech, min_silence=sil)
    return run(vad, win, x)[0]


def energy_dips(x, s, e, floor_db=6.0, min_dip=0.12):
    """区間の内側で、エネルギーが下位 10% + floor_db を下回る谷の中央を返す。雑音の中で VAD が切らない場所を探す。"""
    hop = int(0.02 * SR)
    y = x[int(s * SR) : int(e * SR)]
    n = len(y) // hop
    if n < 10:
        return []
    db = 10 * np.log10(np.mean(y[: n * hop].reshape(n, hop) ** 2, axis=1) + 1e-10)
    low = db < np.percentile(db, 10) + floor_db
    out, i = [], 0
    while i < n:
        if low[i]:
            j = i
            while j < n and low[j]:
                j += 1
            if (j - i) * 0.02 >= min_dip and i > 0 and j < n:
                out.append(s + (i + j) / 2 * 0.02)
            i = j
        else:
            i += 1
    return out


def windows(s, e, w, hop):
    if e - s <= w:
        return [(s, e)]
    t = np.arange(s, e - w + 1e-6, hop)
    r = [(float(a), float(a + w)) for a in t]
    if r[-1][1] < e - 1e-6:
        r.append((e - w, e))
    return r


def key(a, b):
    return (int(round(a * 1000)), int(round(b * 1000)))


def prepare(args):
    cond, i = args
    path = WORK / cond / f"{i}.pkl"
    if path.exists():
        return path
    t0 = time.perf_counter()
    x, truth, me, spk, env = load_conv(cond, i)
    x = np.asarray(x, dtype=np.float32)
    dur = len(x) / SR
    ext = make_extractor(MODEL)
    # 3 発話の平均。アプリの登録と同じ数。登録は静かな場所を想定して clean のまま
    vs = [embed(ext, load_16k(u.wav)) for u in utts(me, "parallel100")[:3]]
    c = np.mean(vs, axis=0)
    me_vec = c / np.linalg.norm(c)

    raw = segments(x, 0.5, 0.8)
    fine_all = {k: segments(x, th, sil) for k, (th, sil) in FINE.items()}
    embs: dict[tuple[int, int], np.ndarray] = {}

    def em(a, b):
        k = key(a, b)
        if k not in embs:
            y = x[int(a * SR) : int(b * SR)]
            if len(y) < MIN_EMBED * SR:
                return None
            embs[k] = embed(ext, y)
        return embs[k]

    segs = []
    for s0, e0 in raw:
        s = max(0.0, s0 - PRE)
        e2 = min(dur, e0 + TAIL)
        if e2 - s < MIN_EMBED:
            continue
        rec = {"s": s, "e": e0, "e2": e2, "parts": {}, "win": {}}
        em(s, e2)  # 現状の区間全体
        em(s, e0)  # 後ろの無音なし
        for name, fine in fine_all.items():
            ps = [(max(a, s), min(b, e0)) for a, b in fine if min(b, e0) - max(a, s) > 0.02]
            if not ps:
                ps = [(s, e0)]
            ps[0] = (s, ps[0][1])
            ps[-1] = (ps[-1][0], e0)
            rec["parts"][name] = ps
            for a, b in ps:
                em(a, b)
        for floor in (6.0, 10.0):
            cuts = energy_dips(x, s, e0, floor)
            edges = [s, *cuts, e0]
            ps = [(a, b) for a, b in zip(edges, edges[1:])]
            rec["parts"][f"d{int(floor)}"] = ps
            for a, b in ps:
                em(a, b)
        for name, (w, hop) in WINDOWS.items():
            ws = windows(s, e0, w, hop)
            rec["win"][name] = ws
            for a, b in ws:
                em(a, b)
        segs.append(rec)
    out = {"cond": cond, "idx": i, "truth": truth, "me": me, "spk": spk, "env": env, "dur": dur,
           "me_vec": me_vec, "segs": segs, "embs": embs, "fine_counts": {k: len(v) for k, v in fine_all.items()}}
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        pickle.dump(out, f)
    print(cond, i, round(time.perf_counter() - t0, 1), len(segs), len(embs), flush=True)
    return path


def asr_tokens(args):
    """区間ごとに ASR を流し、トークンを会話の時刻に直して保存する。区間の前後の足し方は検証-ASR.md の推薦どおり。"""
    cond, i, recs = args
    from models import MODELS

    path = WORK / cond / f"{i}.asr.pkl"
    if path.exists():
        return path
    x, *_ = load_conv(cond, i)
    x = np.asarray(x, dtype=np.float32)
    rec = MODELS[ASR][1](1)
    zero = np.zeros(int(0.3 * SR), dtype=np.float32)
    res = []
    t_cpu = time.process_time()
    for s, e in recs:
        a = int(max(0, s) * SR)  # s は既に語頭 0.3 秒を含む
        b = int(min(len(x) / SR, e + PRE) * SR)
        st = rec.create_stream()
        st.accept_waveform(SR, np.concatenate([zero, x[a:b], zero]))
        rec.decode_stream(st)
        r = st.result
        res.append({"text": r.text, "tokens": list(r.tokens),
                    "times": [float(t) - 0.3 + a / SR for t in r.timestamps]})
    with path.open("wb") as f:
        pickle.dump({"segs": res, "cpu": time.process_time() - t_cpu}, f)
    print("asr", cond, i, flush=True)
    return path


def timing():
    """embedding の CPU 時間を、音声の長さの一次式 c0 + c1 * 秒 で近似する。1 スレッド、Mac の値。"""
    from common import write_json, RESULTS

    init()
    x, *_ = load_conv("clean", 0)
    x = np.asarray(x, dtype=np.float32)
    ext = make_extractor(MODEL)
    embed(ext, x[: SR])
    rng = np.random.default_rng(0)
    rows = []
    for sec in [0.4, 0.5, 0.8, 1.0, 1.5, 2.0, 3.0, 5.0, 8.0, 12.0, 20.0] * 20:
        a = int(rng.integers(0, len(x) - int(20 * SR)))
        y = x[a : a + int(sec * SR)]
        t = time.process_time()
        embed(ext, y)
        rows.append((sec, time.process_time() - t))
    r = np.array(rows)
    c1, c0 = np.polyfit(r[:, 0], r[:, 1], 1)
    vad_rtf = None
    from vad_eval import make_vad as mk

    v, win = mk(VAD, 0.5, 20.0, 0.8)
    t = time.process_time()
    run(v, win, x)
    vad_rtf = (time.process_time() - t) / (len(x) / SR)
    out = {"c0": float(c0), "c1": float(c1), "vad_rtf": vad_rtf, "n": len(rows),
           "by_sec": {str(s): float(r[r[:, 0] == s, 1].mean()) for s in sorted(set(r[:, 0]))}}
    write_json(RESULTS / "speaker_seg" / "timing.json", out)
    print(out)


def init():
    global ALL_SPEAKERS, DEV
    ALL_SPEAKERS = speakers()
    DEV = dev_speakers()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--conds", default="clean,snr15")
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--end", type=int, default=60)
    ap.add_argument("--jobs", type=int, default=6)
    ap.add_argument("--asr", action="store_true")
    ap.add_argument("--timing", action="store_true")
    a = ap.parse_args()
    if a.timing:
        timing()
        return
    conds = a.conds.split(",")
    jobs = [(c, i) for c in conds for i in range(a.start, a.end)]
    with Pool(a.jobs, initializer=init, maxtasksperchild=8) as p:
        if a.asr:
            args = []
            for c, i in jobs:
                with (WORK / c / f"{i}.pkl").open("rb") as f:
                    d = pickle.load(f)
                args.append((c, i, [(r["s"], r["e"]) for r in d["segs"]]))
            list(p.imap_unordered(asr_tokens, args))
        else:
            list(p.imap_unordered(prepare, jobs))


if __name__ == "__main__":
    main()
