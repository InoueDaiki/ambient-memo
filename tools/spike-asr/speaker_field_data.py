#!/usr/bin/env python3
"""実機に寄せた話者判定の評価の下準備。登録と試験で条件を変えた、長い会話の窓の embedding を保存する。

実機の代わりではない。端末のマイク、残響、距離はすべて合成で、値は仮。
保存先は .cache/speaker_field/(コミットしない)。音声は保存しない。

会話: 既定で 20 分。5 分の聞き取りを 4 回。聞き取りごとに部屋、距離、雑音を選び直す。
- 0〜9: 開発用(しきい値を決める)。話者は開発用の 30 人から
- 10〜29: 評価用。話者は残りの 70 人から
- 番号を 3 で割って 2 余る会話だけ 3 人、残りは 2 人。自分は 0 番目の話者

発話: 実際の会話に寄せて短く切る。相づち(0.4〜0.9 秒)が 3 割、短い発話(1〜2.5 秒)が 4 割、残りは 1 文のまま。

条件:
- studio: JVS のまま。発話を切るだけ
- table: 端末は机の上。自分 0.5 m 程度、相手はもう少し遠い。静かな部屋
- pocket: ポケットや鞄の中。高域がこもり、音量が下がる
- cafe: 人の声や食器の音が重なる。端末の AGC のような音量の揺れを足す
- mixed: 上の 3 つを聞き取りごとに順に使う。長く使ったときの想定

登録は全条件で同じ: 端末を口元に持ち、静かな場所で読み上げる(close)。比較用に table の登録も持つ。

例: python speaker_field_data.py --conds studio,table,pocket,cafe,mixed --start 0 --end 30 --jobs 9
"""
import argparse
import pickle
import random
import time
from dataclasses import dataclass
from multiprocessing import Pool

import numpy as np
from scipy.signal import butter, fftconvolve, sosfilt

from common import CACHE, SEED, SR, active_rms, load_16k, noise_clip, rms, speakers, synth_rir, utts
from spk_eval import embed, make_extractor
from vad_eval import make_vad, run

WORK = CACHE / "speaker_field"
MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
VAD = "silero_vad.onnx"
SESSION_S = 300
N_SESSIONS = 4
PRE = 0.3
MIN_EMBED = 0.4
WINDOW, HOP = 1.5, 0.75
N_DEV_SPEAKERS = 30
FRAME = 0.01
QUIET = ["DLIVING", "OOFFICE", "OMEETING"]
BUSY = ["PCAFETER", "PRESTO", "DKITCHEN", "PSTATION"]
OUTSIDE = ["SPSQUARE", "STRAFFIC", "TBUS", "TCAR", "PSTATION"]


@dataclass(frozen=True)
class Channel:
    rt60: float
    drr_me: float  # 自分の直接音と残響の比 dB
    drr_other: float
    gain_other_db: float  # 自分に対する相手の音量
    lowpass: float  # 端末の前の遮りで削られる帯域 Hz
    snr: float
    envs: tuple[str, ...]
    agc: bool = False


CHANNELS = {
    "table": Channel(0.35, 3.0, -2.0, -4.0, 7000, 20, tuple(QUIET)),
    "pocket": Channel(0.25, 4.0, 0.0, -5.0, 2500, 12, tuple(QUIET + OUTSIDE)),
    "cafe": Channel(0.55, 2.0, -3.0, -3.0, 7000, 8, tuple(BUSY), agc=True),
}
ENROLL = {"close": Channel(0.2, 12.0, 12.0, 0.0, 7000, 30, tuple(QUIET)), "table": CHANNELS["table"]}


def pools():
    order = speakers()
    random.Random(f"{SEED}-field").shuffle(order)
    return order[:N_DEV_SPEAKERS], order[N_DEV_SPEAKERS:]


def voiced(path) -> list[tuple[float, float]]:
    """音素のラベルから、無音でない音素の区間を返す。"""
    out = []
    for line in path.read_text().splitlines():
        c = line.split()
        if len(c) >= 3 and c[2] not in ("sil", "pau"):
            out.append((float(c[0]), float(c[1])))
    return out


def fragment(rng: random.Random, x: np.ndarray, phones, kind: str):
    """発話を切る。音素の境界で切り、語の途中で切れた短い発話の代わりにする。"""
    if kind == "full":
        return x, phones, 0.0
    lo, hi = (0.4, 0.9) if kind == "backchannel" else (1.0, 2.5)
    want = rng.uniform(lo, hi)
    if len(phones) < 3:
        return x, phones, 0.0
    first = rng.randrange(len(phones))
    last = first
    while last + 1 < len(phones) and phones[last][1] - phones[first][0] < want:
        last += 1
    a, b = phones[first][0], phones[last][1]
    i, j = int(a * SR), int(b * SR)
    y = x[i:j].copy()
    fade = min(int(0.01 * SR), len(y) // 2)
    if fade:
        ramp = np.linspace(0, 1, fade, dtype=np.float32)
        y[:fade] *= ramp
        y[-fade:] *= ramp[::-1]
    return y, [(p - a, q - a) for p, q in phones[first : last + 1]], a


def device_eq(x: np.ndarray, lowpass: float) -> np.ndarray:
    """端末のマイクの帯域の代わり。低域を切り、高域を落とす。"""
    sos = butter(2, 120, "hp", fs=SR, output="sos")
    y = sosfilt(sos, x)
    sos = butter(4, lowpass, "lp", fs=SR, output="sos")
    return sosfilt(sos, y).astype(np.float32)


def room(x: np.ndarray, rt60: float, drr: float, rng: np.random.Generator) -> np.ndarray:
    return fftconvolve(x, synth_rir(rng, rt60, drr))[: len(x)].astype(np.float32)


def agc(x: np.ndarray) -> np.ndarray:
    """0.5 秒の時定数で音量を揃える簡単な AGC。端末の実装の再現ではなく、音量が揺れる条件の代わり。"""
    hop = int(0.05 * SR)
    n = len(x) // hop
    level = np.sqrt(np.mean(x[: n * hop].reshape(n, hop) ** 2, axis=1) + 1e-8)
    gain, smooth = np.ones(n), 0.05
    for i in range(n):
        smooth = 0.9 * smooth + 0.1 * level[i]
        gain[i] = np.clip(0.05 / smooth, 0.25, 8.0)
    g = np.interp(np.arange(len(x)), (np.arange(n) + 0.5) * hop, gain)
    return np.clip(x * g, -1, 1).astype(np.float32)


def add_noise(x: np.ndarray, snr: float, envs, rng: np.random.Generator) -> np.ndarray:
    n, _ = noise_clip(rng, len(x), set(envs))
    gain = active_rms(x) / (rms(n) * 10 ** (snr / 20))
    return x + n.astype(np.float32) * gain


def session(rng: random.Random, spk, pool, cond: str, seed: int):
    """5 分の聞き取りを 1 回作る。戻り値は混ぜた音声と話者ごとの有声の区間。"""
    tracks = {s: np.zeros((SESSION_S + 20) * SR, dtype=np.float32) for s in spk}
    truth = []
    pos = int(0.5 * SR)
    prev = None
    while pos < SESSION_S * SR:
        s = rng.choice([k for k in spk if k != prev] if rng.random() < 0.75 and prev else spk)
        prev = s
        if not pool[s][1]:
            pool[s] = (pool[s][0], list(pool[s][0]))
            rng.shuffle(pool[s][1])
        u = pool[s][1].pop()
        y = load_16k(u.wav)
        y = y * 0.05 / active_rms(y) * 10 ** (rng.uniform(-3, 3) / 20)
        r = rng.random()
        kind = "backchannel" if r < 0.3 else "short" if r < 0.7 else "full"
        y, phones, _ = fragment(rng, y, voiced(u.lab), kind)
        if pos + len(y) >= len(tracks[s]):
            break
        tracks[s][pos : pos + len(y)] += y
        truth += [(pos / SR + a, pos / SR + b, s) for a, b in phones]
        gap = -0.2 if rng.random() < 0.1 else rng.expovariate(1 / 0.5)
        if rng.random() < 0.15:
            gap = rng.uniform(2.0, 10.0)
        pos += len(y) + int(gap * SR)
    end = min(pos + SR, len(tracks[spk[0]]))
    nrng = np.random.default_rng(seed)
    me = spk[0]
    if cond == "studio":
        return sum(t[:end] for t in tracks.values()), truth, "-"
    c = CHANNELS[cond]
    mixed = np.zeros(end, dtype=np.float32)
    for s, t in tracks.items():
        is_me = s == me
        y = room(t[:end], c.rt60, c.drr_me if is_me else c.drr_other, nrng)
        mixed += y if is_me else y * 10 ** (c.gain_other_db / 20)
    mixed = add_noise(mixed, c.snr, c.envs, nrng)
    y = device_eq(mixed, c.lowpass)
    if c.agc:
        y = agc(y)
    peak = np.max(np.abs(y))
    return (y / peak * 0.95 if peak > 0.95 else y), truth, cond


def enroll_clip(u, c: Channel, rng: np.random.Generator) -> np.ndarray:
    """登録の 1 発話。アプリと同じく最長 6 秒で切る。JVS の前後の無音が区間の無音の代わりになる。"""
    y = load_16k(u.wav)
    y = y * 0.05 / active_rms(y)
    y = y[: 6 * SR]
    y = add_noise(room(y, c.rt60, c.drr_me, rng), c.snr, c.envs, rng)
    return device_eq(y, c.lowpass)


def windows(s, e):
    if e - s <= WINDOW:
        return [(s, e)]
    r = [(float(a), float(a + WINDOW)) for a in np.arange(s, e - WINDOW + 1e-6, HOP)]
    if r[-1][1] < e - 1e-6:
        r.append((e - WINDOW, e))
    return r


def frames(truth, me, dur):
    """10 ms ごとの正解。0: 無音 1: 他人 2: 自分。重なりは自分がいれば自分。"""
    t = np.zeros(int(dur / FRAME) + 2, dtype=np.int8)
    for s, e, k in truth:
        a, b = int(s / FRAME), int(e / FRAME)
        t[a:b] = np.maximum(t[a:b], 2 if k == me else 1)
    return t


def prepare(args):
    cond, i = args
    path = WORK / cond / f"{i}.pkl"
    if path.exists():
        return path
    t0 = time.perf_counter()
    rng = random.Random(f"{SEED}-field-{i}")
    dev, rest = pools()
    n_spk = 3 if i % 3 == 2 else 2
    spk = rng.sample(dev if i < 10 else rest, n_spk)
    pool = {}
    for s in spk:
        items = [u for u in utts(s, "nonpara30") + utts(s, "parallel100")[5:] if u.lab.exists()]
        rng.shuffle(items)
        pool[s] = (items, list(items))
    ext = make_extractor(MODEL)
    enroll = {}
    nrng = np.random.default_rng(9000 + i)
    for name, c in ENROLL.items():
        clips = [enroll_clip(u, c, nrng) for u in utts(spk[0], "parallel100")[:3]]
        whole = np.array([embed(ext, y) for y in clips])
        wins = []
        for y in clips:
            d = len(y) / SR
            wins.append(np.array([embed(ext, y[int(a * SR) : int(b * SR)]) for a, b in windows(0.0, d - 0.8)]))
        enroll[name] = {"whole": whole, "windows": wins}
    sessions = []
    conds = ["table", "pocket", "cafe", "table"] if cond == "mixed" else [cond] * N_SESSIONS
    for n, cs in enumerate(conds):
        x, truth, label = session(rng, spk, pool, cs, 7000 + i * 10 + n)
        x = np.asarray(x, dtype=np.float32)
        vad, win = make_vad(VAD, 0.5, max_speech=20.0, min_silence=0.8)
        raw = run(vad, win, x)[0]
        dur = len(x) / SR
        segs = []
        for s0, e0 in raw:
            s = max(0.0, s0 - PRE)
            ws = [w for w in windows(s, e0) if w[1] - w[0] >= MIN_EMBED]
            if not ws:
                continue
            vs = np.array([embed(ext, x[int(a * SR) : int(b * SR)]) for a, b in ws], dtype=np.float32)
            segs.append({"s": s, "e": e0, "e2": min(dur, e0 + 0.8), "wins": ws, "vecs": vs})
        sessions.append({"cond": label, "dur": dur, "truth": frames(truth, spk[0], dur), "segs": segs})
    out = {"cond": cond, "idx": i, "spk": spk, "enroll": enroll, "sessions": sessions}
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        pickle.dump(out, f)
    n_win = sum(len(r["wins"]) for z in sessions for r in z["segs"])
    print(cond, i, round(time.perf_counter() - t0, 1), n_win, flush=True)
    return path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--conds", default="studio,table,pocket,cafe,mixed")
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--end", type=int, default=30)
    ap.add_argument("--jobs", type=int, default=8)
    a = ap.parse_args()
    jobs = [(c, i) for c in a.conds.split(",") for i in range(a.start, a.end)]
    with Pool(a.jobs, maxtasksperchild=8) as p:
        list(p.imap_unordered(prepare, jobs))


if __name__ == "__main__":
    main()
