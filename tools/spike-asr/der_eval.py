#!/usr/bin/env python3
"""JVS の発話をつないだ合成会話で話者誤り率(DER)を測る。参考値。

2 つの方式を比べる。
- offline: sherpa-onnx の OfflineSpeakerDiarization(pyannote の分割 + embedding + クラスタリング)
- app: アプリで想定する発話区間ごとの処理。VAD の区間ごとに embedding を取り、登録済みの「自分」と比べ、
       それ以外は逐次クラスタリングする

例: python der_eval.py --embed wespeaker_en_voxceleb_CAM++_LM.onnx --cond clean
"""
import argparse
import random
import time

import numpy as np
import sherpa_onnx
from scipy.optimize import linear_sum_assignment

from common import CACHE, RESULTS, SEED, SR, active_rms, load_16k, mix_snr, noise_clip, speakers, utts, write_json
from spk_eval import embed, make_extractor
from vad_eval import lab_segments, make_vad, run

N_CONV = 30
N_DEV = 10
FRAME = 0.01


def conversation(idx: int):
    """会話 1 本。180 秒前後。0 番目の話者を「自分」とする。"""
    rng = random.Random(f"{SEED}-conv-{idx}")
    n_spk = 2 + idx % 3
    # ラベルのない話者は正解が作れないので外す
    usable = [s for s in speakers() if sum(u.lab.exists() for u in utts(s, "nonpara30")) >= 20]
    spk = rng.sample(usable, n_spk)
    pool = {s: [u for u in utts(s, "nonpara30") + utts(s, "parallel100")[5:] if u.lab.exists()] for s in spk}
    for s in spk:
        rng.shuffle(pool[s])
    seen = {s: list(pool[s]) for s in spk}
    x = np.zeros(200 * SR, dtype=np.float32)
    truth = []  # (start, end, spk)
    pos = int(0.5 * SR)
    prev = None
    while pos < 180 * SR:
        s = rng.choice([k for k in spk if k != prev] if rng.random() < 0.8 and prev else spk)
        prev = s
        if not pool[s]:  # ラベルが少ない話者は同じ発話を繰り返して使う
            pool[s] = list(seen[s])
        u = pool[s].pop()
        y = load_16k(u.wav)
        y = y * 0.05 / active_rms(y) * 10 ** (rng.uniform(-3, 3) / 20)
        if pos + len(y) >= len(x):
            break
        x[pos : pos + len(y)] += y
        truth += [(pos / SR + a, pos / SR + b, s) for a, b in lab_segments(u.lab)]
        # 10% は前の発話の終わりに重ねる。残りは短い間を置く
        gap = -0.5 if rng.random() < 0.1 else rng.expovariate(1 / 0.4)
        pos += len(y) + int(gap * SR)
    x = x[: pos + SR]
    return x, truth, spk[0], spk


def der(truth, hyp, dur, collar=0.0):
    """全話者の最適な対応づけで DER を計算する。重なりも数える。"""
    n = int(dur / FRAME)
    rs = sorted({t[2] for t in truth})
    hs = sorted({h[2] for h in hyp})
    R = np.zeros((len(rs), n), dtype=bool)
    H = np.zeros((max(1, len(hs)), n), dtype=bool)
    score = np.ones(n, dtype=bool)
    for s, e, k in truth:
        R[rs.index(k), int(s / FRAME) : int(e / FRAME)] = True
    if collar:
        changes = {t[0] for t in truth} | {t[1] for t in truth}
        for c in changes:
            score[max(0, int((c - collar) / FRAME)) : int((c + collar) / FRAME)] = False
    for s, e, k in hyp:
        H[hs.index(k), int(s / FRAME) : min(n, int(e / FRAME))] = True
    cost = np.array([[-(R[i] & H[j] & score).sum() for j in range(H.shape[0])] for i in range(len(rs))])
    ri, ci = linear_sum_assignment(cost)
    mapped = {int(j): int(i) for i, j in zip(ri, ci)}
    nr, nh = R[:, score].sum(0), H[:, score].sum(0)
    correct = np.zeros(score.sum(), dtype=int)
    for j, i in mapped.items():
        correct += (R[i] & H[j])[score]
    total = nr.sum()
    miss = np.maximum(nr - nh, 0).sum()
    fa = np.maximum(nh - nr, 0).sum()
    conf = (np.minimum(nr, nh) - correct).sum()
    return {"total": int(total), "miss": int(miss), "fa": int(fa), "conf": int(conf)}


def self_other_accuracy(truth, hyp, me, dur):
    """自分かそれ以外かの時間正解率。発話のある時間だけを見る(重なりは自分がいれば自分)。"""
    n = int(dur / FRAME)
    t = np.zeros(n, dtype=int)  # 0: 無音 1: 他人 2: 自分
    for s, e, k in truth:
        a, b = int(s / FRAME), int(e / FRAME)
        t[a:b] = np.maximum(t[a:b], 2 if k == me else 1)
    h = np.zeros(n, dtype=int)
    for s, e, k in hyp:
        h[int(s / FRAME) : min(n, int(e / FRAME))] = 2 if k == "self" else 1
    me_mask, other_mask = t == 2, t == 1
    return {"self_hit": int((h[me_mask] == 2).sum()), "self_total": int(me_mask.sum()),
            "other_as_self": int((h[other_mask] == 2).sum()), "other_total": int(other_mask.sum())}


def app_pipeline(x, ext, vad_model, me_vec, th_self, th_cluster, min_sil, vad_th=0.5):
    vad, win = make_vad(vad_model, vad_th, max_speech=10.0, min_silence=min_sil)
    segs, _ = run(vad, win, x)
    centroids: list[np.ndarray] = []
    counts: list[int] = []
    hyp = []
    for s, e in segs:
        y = x[int(s * SR) : int(e * SR)]
        if len(y) < 0.4 * SR:
            hyp.append((s, e, "other-short"))
            continue
        v = embed(ext, y)
        if float(v @ me_vec) >= th_self:
            hyp.append((s, e, "self"))
            continue
        sims = [float(v @ c) for c in centroids]
        if sims and max(sims) >= th_cluster:
            i = int(np.argmax(sims))
            c = centroids[i] * counts[i] + v
            centroids[i] = c / np.linalg.norm(c)
            counts[i] += 1
        else:
            centroids.append(v)
            counts.append(1)
            i = len(centroids) - 1
        hyp.append((s, e, f"other{i}"))
    return hyp


def make_diar(seg: str, emb: str, th: float, clusters: int = -1):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=seg), num_threads=2),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=emb, num_threads=2),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=clusters, threshold=th),
        min_duration_on=0.3, min_duration_off=0.5,
    )
    return sherpa_onnx.OfflineSpeakerDiarization(cfg)


def agg(rows):
    t = {k: sum(r[k] for r in rows) for k in ("total", "miss", "fa", "conf")}
    return {"der_pct": round((t["miss"] + t["fa"] + t["conf"]) / t["total"] * 100, 2),
            "miss_pct": round(t["miss"] / t["total"] * 100, 2), "fa_pct": round(t["fa"] / t["total"] * 100, 2),
            "conf_pct": round(t["conf"] / t["total"] * 100, 2)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--embed", required=True)
    ap.add_argument("--cond", default="clean")
    ap.add_argument("--vad", default="silero_vad_v5.onnx")
    ap.add_argument("--th-self", type=float, default=0.5)
    ap.add_argument("--n-conv", type=int, default=N_CONV)
    ap.add_argument("--n-dev", type=int, default=N_DEV)
    a = ap.parse_args()
    seg = str(next((CACHE / "segment").glob("*/model.int8.onnx")))
    emb = str(CACHE / "speaker" / a.embed)
    ext = make_extractor(a.embed)

    convs = []
    for i in range(a.n_conv):
        x, truth, me, spk = conversation(i)
        if a.cond != "clean":
            rng = np.random.default_rng(5000 + i)
            n, _ = noise_clip(rng, len(x))
            x = mix_snr(x, n, float(a.cond.removeprefix("snr")))
        vs = [embed(ext, load_16k(u.wav)) for u in utts(me, "parallel100")[:5]]
        c = np.mean(vs, axis=0)
        convs.append((x, truth, me, spk, c / np.linalg.norm(c)))

    res = {"embed": a.embed, "cond": a.cond, "n_conv": a.n_conv, "n_dev": a.n_dev, "offline": {}}
    # offline: クラスタのしきい値を開発用の会話で決める
    best = None
    for th in (0.3, 0.5, 0.7):
        d = make_diar(seg, emb, th)
        rows = []
        for x, truth, *_ in convs[:a.n_dev]:
            r = d.process(x).sort_by_start_time()
            hyp = [(s.start, s.end, s.speaker) for s in r]
            rows.append(der(truth, hyp, len(x) / SR))
        v = agg(rows)["der_pct"]
        if best is None or v < best[0]:
            best = (v, th)
    res["offline"]["cluster_th"] = best[1]
    d = make_diar(seg, emb, best[1])
    rows, rows_c, ph = [], [], [0.0, 0.0]
    for x, truth, *_ in convs[a.n_dev:]:
        t = time.perf_counter()
        r = d.process(x).sort_by_start_time()
        ph[0] += time.perf_counter() - t
        ph[1] += len(x) / SR
        hyp = [(s.start, s.end, s.speaker) for s in r]
        rows.append(der(truth, hyp, len(x) / SR))
        rows_c.append(der(truth, hyp, len(x) / SR, 0.25))
    res["offline"].update(nocollar=agg(rows), collar025=agg(rows_c), rtf=round(ph[0] / ph[1], 4))

    # app: 逐次処理。区切りの無音の長さを変えて比べ、クラスタのしきい値は開発用の会話で決める
    for key, min_sil in (("app", 0.5), ("app_sil02", 0.2)):
        best = None
        for th in (0.3, 0.5, 0.7):
            rows = []
            for x, truth, me, spk, v in convs[: a.n_dev]:
                hyp = app_pipeline(x, ext, a.vad, v, a.th_self, th, min_sil)
                rows.append(der(truth, hyp, len(x) / SR))
            s = agg(rows)["der_pct"]
            if best is None or s < best[0]:
                best = (s, th)
        rows, rows_c, so = [], [], []
        for x, truth, me, spk, v in convs[a.n_dev :]:
            hyp = app_pipeline(x, ext, a.vad, v, a.th_self, best[1], min_sil)
            rows.append(der(truth, hyp, len(x) / SR))
            rows_c.append(der(truth, hyp, len(x) / SR, 0.25))
            so.append(self_other_accuracy(truth, hyp, me, len(x) / SR))
        t = {k: sum(r[k] for r in so) for k in so[0]}
        res[key] = {"cluster_th": best[1], "th_self": a.th_self, "min_silence": min_sil, "nocollar": agg(rows),
                    "collar025": agg(rows_c), "self_recall_pct": round(t["self_hit"] / t["self_total"] * 100, 2),
                    "other_as_self_pct": round(t["other_as_self"] / t["other_total"] * 100, 2)}
    write_json(RESULTS / "der" / f"{a.embed}__{a.cond}.json", res)
    print(res, flush=True)


if __name__ == "__main__":
    main()
