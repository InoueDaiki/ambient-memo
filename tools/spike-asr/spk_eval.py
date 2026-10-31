#!/usr/bin/env python3
"""話者 embedding で「自分かそれ以外か」を見分ける精度を JVS で測る。

- 登録: 各話者の parallel100 の先頭 5 発話(静かな音声)
- 試験: 同じ話者の nonpara30 の先頭 10 発話を長さと雑音を変えて使う。登録と文が重ならない
- しきい値は前半 50 話者で「他人を自分と誤る率 1%」に決め、後半 50 話者で評価する
例: python spk_eval.py --model wespeaker_en_voxceleb_CAM++_LM.onnx
"""
import argparse
import random
import resource
import time

import numpy as np
import sherpa_onnx

from common import CACHE, RESULTS, SEED, SR, degrade, genders, load_16k, speakers, utts, write_json

DURS = {"full": None, "3s": 3.0, "1.5s": 1.5}
# 全組み合わせは時間がかかるので、長さは clean で見て、雑音と残響は 3 秒で見る
RUNS = [("clean", "full"), ("clean", "3s"), ("clean", "1.5s"), ("snr10", "3s"), ("far", "3s")]
N_TEST = 10


def make_extractor(model: str, threads: int = 1):
    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(CACHE / "speaker" / model), num_threads=threads)
    return sherpa_onnx.SpeakerEmbeddingExtractor(cfg)


def embed(ext, x: np.ndarray) -> np.ndarray:
    st = ext.create_stream()
    st.accept_waveform(sample_rate=SR, waveform=x)
    st.input_finished()
    v = np.array(ext.compute(st), dtype=np.float32)
    return v / (np.linalg.norm(v) + 1e-9)


def crop(x: np.ndarray, sec: float | None, rng: random.Random) -> np.ndarray:
    if sec is None or len(x) <= sec * SR:
        return x
    n = int(sec * SR)
    # 発話の前後の無音を避けて、声の入っている部分から切る
    lo, hi = len(x) // 8, max(len(x) // 8 + 1, len(x) * 7 // 8 - n)
    s = rng.randrange(lo, hi)
    return x[s : s + n]


def eer(tar: np.ndarray, non: np.ndarray) -> tuple[float, float]:
    th = np.sort(np.concatenate([tar, non]))
    fr = np.searchsorted(np.sort(tar), th) / len(tar)
    fa = 1 - np.searchsorted(np.sort(non), th) / len(non)
    i = int(np.argmin(np.abs(fr - fa)))
    return float((fr[i] + fa[i]) / 2), float(th[i])


def trial_scores(enroll, tests, spk_list, gend, rng):
    """登録の重心と試験発話のコサイン。戻り値は (本人スコア, 他人スコア, 同性の他人スコア)。"""
    tar, non, same = [], [], []
    for s in spk_list:
        tar += list(tests[s] @ enroll[s])
        for o in rng.sample([k for k in spk_list if k != s], min(20, len(spk_list) - 1)):
            v = tests[o] @ enroll[s]
            non += list(v)
            if gend.get(o) == gend.get(s):
                same += list(v)
    return np.array(tar), np.array(non), np.array(same)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    a = ap.parse_args()
    t0 = time.perf_counter()
    ext = make_extractor(a.model)
    load_s = time.perf_counter() - t0
    spks = speakers()
    gend = genders()
    rng = random.Random(SEED)

    enroll = {}
    proc = audio = 0.0
    for s in spks:
        vs = []
        for u in utts(s, "parallel100")[:5]:
            x = load_16k(u.wav)
            t = time.perf_counter()
            vs.append(embed(ext, x))
            proc += time.perf_counter() - t
            audio += len(x) / SR
        c = np.mean(vs, axis=0)
        enroll[s] = c / np.linalg.norm(c)

    res = {"model": a.model, "dim": ext.dim, "load_s": round(load_s, 2), "by": {}}
    dev, ev = spks[:50], spks[50:]
    for cond, dn in RUNS:
        ds = DURS[dn]
        tests = {}
        for s in spks:
            vs = []
            for i, u in enumerate(utts(s, "nonpara30")[:N_TEST]):
                x = degrade(load_16k(u.wav), cond, hash_key(s, i))
                x = crop(x, ds, rng).astype(np.float32)
                t = time.perf_counter()
                vs.append(embed(ext, x))
                proc += time.perf_counter() - t
                audio += len(x) / SR
            tests[s] = np.stack(vs)
        d_t, d_n, _ = trial_scores(enroll, tests, dev, gend, random.Random(1))
        e_t, e_n, e_s = trial_scores(enroll, tests, ev, gend, random.Random(2))
        th = float(np.quantile(d_n, 0.99))
        e_all, _ = eer(np.concatenate([d_t, e_t]), np.concatenate([d_n, e_n]))
        res["by"][f"{cond}/{dn}"] = {
            "eer_pct": round(e_all * 100, 2), "eer_samegender_eval_pct": round(eer(e_t, e_s)[0] * 100, 2),
            "th_far1": round(th, 3),
            "eval_far_pct": round(float((e_n >= th).mean() * 100), 2),
            "eval_frr_pct": round(float((e_t < th).mean() * 100), 2),
            "trials": len(d_t) + len(d_n) + len(e_t) + len(e_n),
        }
        print(a.model, cond, dn, res["by"][f"{cond}/{dn}"], flush=True)
    res["rtf"] = round(proc / audio, 4)
    res["size_mb"] = round((CACHE / "speaker" / a.model).stat().st_size / 1e6, 1)
    res["rss_peak_mb"] = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6, 1)
    write_json(RESULTS / "speaker" / f"{a.model}.json", res)


def hash_key(s: str, i: int) -> int:
    return int(s[3:]) * 100 + i


if __name__ == "__main__":
    main()
