#!/usr/bin/env python3
"""実機に寄せた条件で話者判定を比べる。数値は仮で、実機の代わりではない。

先に speaker_field_data.py を流す。保存済みの窓の embedding だけを使い、音声には触らない。

判定の手順は engine/domain の SpeakerIdentifier と voiceRuns を移したもの。規則を変えたらここも変える。
`current` は main の規則と値、`Rule()` の既定値は SpeakerConfig の既定値と揃える。

Kotlin との違い:
- トークンの時刻がない。本文のないまとまりを落とす処理と、本文の境界への丸めは再現しない
- 時刻は秒の浮動小数。Kotlin はミリ秒の整数
- 発話は VAD の区間で切る。UtteranceSegmenter の語頭のバッファは PRE の 0.3 秒で代用する(話者区間の検証と同じ)
- 手で直す操作と、長く更新のない話者が席を譲る処理は再現しない
- まとめ直しは consolidate() に移した

例:
    python speaker_field_eval.py self        # 開発用で自分のしきい値を比べる
    python speaker_field_eval.py provisional # 開発用で仮の話者の規則を比べる
    python speaker_field_eval.py enroll      # 開発用で登録の作り方を比べる
    python speaker_field_eval.py adapt       # 開発用で自分の重心の更新を比べる
    python speaker_field_eval.py rival       # 開発用で知っている他人との比較を比べる
    python speaker_field_eval.py report      # 評価用の値を results/speaker_field/ に書く
"""
import argparse
import pickle
import sys
from dataclasses import dataclass, replace
from multiprocessing import Pool

import numpy as np

from common import RESULTS, bootstrap_ci, write_json
from speaker_field_data import FRAME, WORK

DEV, EVAL = range(0, 10), range(10, 30)
CONDS = ["studio", "table", "pocket", "cafe", "mixed"]
OUT = RESULTS / "speaker_field"
MIN_RELIABLE = 2.5
MIN_RELIABLE_RUN = 1.0


@dataclass(frozen=True)
class Rule:
    """SpeakerConfig と同じ意味の値。既定値は SpeakerConfig の既定値と揃える。"""

    self_in: float = 0.50
    self_out: float = 0.45
    floor: float = 0.40
    provisional: float = 0.45
    cap: int = 3
    min_new_run: float = 2.0
    duration_weight: bool = True
    update_forced: bool = False
    compete_margin: float | None = 0.05
    self_override_margin: float = 0.10  # 弱い自分の判定を他人に変えるのに要る差
    rival_self_limit: float = 1.0  # これ以上自分に近い重心は、他人として比べない
    strong_self: float = 0.60
    enroll: str = "close"
    enroll_shape: str = "whole"
    adapt: bool = True
    adapt_score: float = 0.55
    adapt_anchor: float = 0.40
    adapt_other_margin: float = 0.10
    adapt_min_run: float = 2.0
    adapt_base_weight: float = 120.0
    adapt_max_weight: float = 600.0
    adapt_boost: float = 0.05


current = Rule(self_in=0.60, self_out=0.55, floor=0.45, provisional=0.60, cap=10**6, min_new_run=MIN_RELIABLE_RUN,
               duration_weight=False, compete_margin=None, adapt=False)


def unit(v):
    return v / (np.linalg.norm(v) + 1e-9)


def load(cond, i):
    with (WORK / cond / f"{i}.pkl").open("rb") as f:
        return pickle.load(f)


def enrolled(data, rule: Rule):
    e = data["enroll"][rule.enroll]
    if rule.enroll_shape == "whole":
        return unit(e["whole"].mean(0))
    if rule.enroll_shape == "windows":
        return unit(np.concatenate(e["windows"]).mean(0))
    # 外れた発話を落とす。3 発話のうち、残りの平均に最も近い 2 発話を使う
    v = e["whole"]
    keep = np.argsort([-(v[i] @ unit(v.sum(0) - v[i])) for i in range(len(v))])[:2]
    return unit(v[keep].mean(0))


class Speakers:
    """仮の話者の集まり。SpeakerRepository と SpeakerIdentifier.assignOther の代わり。"""

    def __init__(self, rule: Rule):
        self.rule = rule
        self.vecs: list[np.ndarray] = []
        self.weights: list[float] = []
        self.frames: list[list[int]] = []  # 話者ごとの [他人のフレーム, 自分のフレーム]
        self.created = 0

    def nearest(self, v):
        if not self.vecs:
            return -1, -1.0
        sims = np.array(self.vecs) @ v
        i = int(np.argmax(sims))
        return i, float(sims[i])

    def absorb(self, i, v, d):
        w = d if self.rule.duration_weight else 1.0
        self.vecs[i] = unit(self.vecs[i] * self.weights[i] + v * w)
        self.weights[i] += w

    def assign(self, v, reliable, d, can_create):
        i, sim = self.nearest(v)
        if sim >= self.rule.provisional:
            if reliable:
                self.absorb(i, v, d)
            return i
        if reliable and can_create and len(self.vecs) < self.rule.cap:
            self.vecs.append(v)
            self.weights.append(d if self.rule.duration_weight else 1.0)
            self.frames.append([0, 0])
            self.created += 1
            return len(self.vecs) - 1
        if len(self.vecs) >= self.rule.cap:
            if self.rule.update_forced and reliable:
                self.absorb(i, v, d)
            return i
        return None


def identify(data, rule: Rule, sessions=None, spk=None):
    """会話を時刻の順に判定する。聞き取りをまたいで話者と重心を持ち越す。"""
    me = enrolled(data, rule)
    adapted, adapted_w = None, 0.0
    spk = spk or Speakers(rule)
    spk.rule = rule
    hit = other_as_self = self_as_other = unknown = n_self = n_other = 0
    n_runs = 0
    growth = []
    for z in data["sessions"] if sessions is None else sessions:
        t = z["truth"]
        cs, co = np.concatenate([[0], np.cumsum(t == 2)]), np.concatenate([[0], np.cumsum(t == 1)])
        for r in z["segs"]:
            vecs = r["vecs"]
            scores = vecs @ me
            if adapted is not None:
                scores = np.maximum(scores, np.minimum(vecs @ adapted, scores + rule.adapt_boost))
            state, states = False, []
            for x in scores:
                state = bool(x >= (rule.self_out if state else rule.self_in))
                states.append(state)
            centers = [(a + b) / 2 for a, b in r["wins"]]
            edges = [r["s"]] + [(p + q) / 2 for p, q in zip(centers, centers[1:])] + [r["wins"][-1][1]]
            duration = r["e2"] - r["s"]
            i = 0
            while i < len(states):
                j = i
                while j < len(states) and states[j] == states[i]:
                    j += 1
                a, b = edges[i], edges[j]
                score = float(scores[i:j].mean())
                v = unit(vecs[i:j].sum(0))
                d = b - a
                voice = "unknown" if rule.floor <= score < rule.self_in else "self" if states[i] else "other"
                best_i, best = spk.nearest(v)
                if best_i >= 0 and rule.rival_self_limit < 1.0:
                    rivals = [float(c @ v) for c in spk.vecs if float(c @ me) < rule.rival_self_limit]
                    rival = max(rivals, default=-1.0)
                else:
                    rival = best
                if rule.compete_margin is not None and best_i >= 0 and rival > -1.0 and score < rule.strong_self:
                    if voice == "unknown":
                        if score - rival >= rule.compete_margin:
                            voice = "self"
                        elif rival - score >= rule.compete_margin:
                            voice = "other"
                    elif voice == "self" and rival - score >= rule.self_override_margin:
                        voice = "other"
                reliable = duration >= MIN_RELIABLE and d >= MIN_RELIABLE_RUN
                who = None
                if voice == "other":
                    who = spk.assign(v, reliable, d, d >= rule.min_new_run)
                    if who is None:
                        voice = "unknown"
                elif voice == "self" and rule.adapt:
                    anchor = float(v @ me)
                    margin_ok = best_i < 0 or score - best >= rule.adapt_other_margin
                    if score >= rule.adapt_score and anchor >= rule.adapt_anchor and margin_ok and d >= rule.adapt_min_run:
                        w = min(adapted_w, rule.adapt_max_weight)
                        base = adapted if adapted is not None else me
                        w = max(w, rule.adapt_base_weight)
                        adapted = unit(base * w + v * d)
                        adapted_w = min(adapted_w + d, rule.adapt_max_weight)
                x, y = int(a / FRAME), min(len(t), int(b / FRAME))
                if y > x:
                    fs, fo = int(cs[y] - cs[x]), int(co[y] - co[x])
                    n_self += fs
                    n_other += fo
                    if voice == "self":
                        hit += fs
                        other_as_self += fo
                    elif voice == "other":
                        self_as_other += fs
                        spk.frames[who][0] += fo
                        spk.frames[who][1] += fs
                    else:
                        unknown += fs + fo
                n_runs += 1
                i = j
        growth.append(len(spk.vecs))
    leak = sum(1 for o, s in spk.frames if s > o)
    return {"growth": growth, "state": spk, "hit": hit, "fa": other_as_self, "s2o": self_as_other, "unk": unknown, "n_self": n_self, "n_other": n_other,
            "speakers": len(spk.vecs), "self_clusters": leak, "n_true": len(data["spk"]) - 1, "runs": n_runs}


def one(args):
    cond, i, rule = args
    r = identify(load(cond, i), rule)
    r.pop("state")
    return r


def consolidate(spk: Speakers, rule: Rule):
    """SpeakerConsolidation の planConsolidation と同じ。上限まで、または近い組がなくなるまで最も近い 2 つを統合する。"""
    if len(spk.vecs) <= rule.cap:
        return
    vecs, weights, frames = list(spk.vecs), list(spk.weights), list(spk.frames)
    while len(vecs) > 1:
        m = np.array(vecs) @ np.array(vecs).T
        np.fill_diagonal(m, -2)
        a, b = np.unravel_index(int(np.argmax(m)), m.shape)
        a, b = int(min(a, b)), int(max(a, b))
        if len(vecs) <= rule.cap and m[a, b] < rule.provisional:
            break
        vecs[a] = unit(vecs[a] * weights[a] + vecs[b] * weights[b])
        weights[a] += weights[b]
        frames[a] = [frames[a][0] + frames[b][0], frames[a][1] + frames[b][1]]
        del vecs[b], weights[b], frames[b]
    spk.vecs, spk.weights, spk.frames = vecs, weights, frames


def migrated(args):
    """以前の版で 2 回聞き取ってからまとめ直し、新しい規則で残りの 2 回を聞き取る。アプリの更新の想定。"""
    cond, i, keep_blobs = args
    data = load(cond, i)
    first = identify(data, current, data["sessions"][:2])
    spk = first["state"]
    before = len(spk.vecs)
    spk.rule = BASE
    consolidate(spk, BASE)
    after = len(spk.vecs)
    r = identify(data, BASE, data["sessions"][2:], spk)
    r.pop("state")
    r.update(before=before, after=after)
    return r


def summarize(rows):
    t = {k: sum(r[k] for r in rows) for k in ("hit", "fa", "s2o", "unk", "n_self", "n_other")}
    sp = [r["speakers"] for r in rows]
    miss = np.array([r["n_self"] - r["hit"] for r in rows], dtype=float)
    selfs = np.array([r["n_self"] for r in rows], dtype=float)
    fas = np.array([r["fa"] for r in rows], dtype=float)
    others = np.array([r["n_other"] for r in rows], dtype=float)
    rl, rh = bootstrap_ci(miss, selfs)
    fl, fh = bootstrap_ci(fas, others)
    growth = np.mean([r["growth"] for r in rows], axis=0).round(1).tolist() if rows[0]["growth"] else []
    return {"recall_ci": [round((1 - rh) * 100, 1), round((1 - rl) * 100, 1)],
            "fa_ci": [round(fl * 100, 1), round(fh * 100, 1)], "growth": growth, "recall": round(t["hit"] / t["n_self"] * 100, 1), "fa": round(t["fa"] / t["n_other"] * 100, 1),
            "s2o": round(t["s2o"] / t["n_self"] * 100, 1), "unk": round(t["unk"] / (t["n_self"] + t["n_other"]) * 100, 1),
            "speakers_mean": round(float(np.mean(sp)), 1), "speakers_max": int(max(sp)),
            "self_clusters": round(float(np.mean([r["self_clusters"] for r in rows])), 2)}


def run(pool, rules, convs, conds=CONDS):
    keys = [(name, c) for name in rules for c in conds]
    jobs = [(c, i, rules[name]) for name, c in keys for i in convs]
    rows = pool.map(one, jobs, chunksize=4)
    out = {}
    n = len(convs)
    for k, (name, c) in enumerate(keys):
        out.setdefault(name, {})[c] = summarize(rows[k * n : (k + 1) * n])
    return out


def show(res, conds=CONDS):
    print("| 設定 | " + " | ".join(f"{c} 再現/誤受入/不明/話者数" for c in conds) + " |")
    print("|---|" + "---|" * len(conds))
    for name, r in res.items():
        cells = [f"{r[c]['recall']} / {r[c]['fa']} / {r[c]['unk']} / {r[c]['speakers_mean']}" for c in conds]
        print(f"| {name} | " + " | ".join(cells) + " |")
    sys.stdout.flush()


BASE = Rule()


def self_rules():
    out = {"current": current, "base": BASE}
    for s in (0.60, 0.55, 0.50, 0.45, 0.40, 0.35):
        for compete in (None, 0.05):
            for floor_gap in (0.10, 0.15):
                name = f"self{s:.2f} floor-{floor_gap} {'compete' if compete else 'plain'}"
                out[name] = replace(BASE, self_in=s, self_out=s - 0.05, floor=s - floor_gap, compete_margin=compete,
                                    adapt=False)
    return out


def provisional_rules():
    out = {"current": current}
    for th in (0.60, 0.50, 0.45, 0.40, 0.35):
        for cap in (3, 4, 10**6):
            for mn in (1.0, 2.0, 3.0):
                out[f"th{th} cap{cap} new{mn}"] = replace(BASE, provisional=th, cap=cap, min_new_run=mn)
    out["no-duration-weight"] = replace(BASE, duration_weight=False)
    out["update-forced"] = replace(BASE, update_forced=True)
    return out


def enroll_rules():
    return {f"{e} {s}": replace(BASE, enroll=e, enroll_shape=s, adapt=False)
            for e in ("close", "table") for s in ("whole", "windows", "trimmed")}


def adapt_rules():
    out = {"off": replace(BASE, adapt=False)}
    for anchor in (0.40, 0.50, 0.55):
        for base, top in ((30.0, 120.0), (120.0, 600.0)):
            for boost in (1.0, 0.15, 0.10, 0.05):
                out[f"anchor{anchor} w{base:.0f}-{top:.0f} boost{boost}"] = replace(
                    BASE, adapt_anchor=anchor, adapt_base_weight=base, adapt_max_weight=top, adapt_boost=boost)
    return out


def rival_rules():
    out = {"base": BASE, "no-rival": replace(BASE, compete_margin=None)}
    for limit in (1.0, 0.5, 0.4, 0.3):
        for over in (0.05, 0.10, 0.20, 9.0):
            out[f"limit{limit} override{over}"] = replace(BASE, rival_self_limit=limit, self_override_margin=over)
    return out


def report(pool):
    rules = {
        "main の規則": current,
        "main + 仮の話者の上限だけ": replace(current, cap=BASE.cap),
        "緩める(比較なし、更新なし)": replace(BASE, compete_margin=None, adapt=False),
        "緩める + 他人との比較": replace(BASE, adapt=False),
        "推奨(緩める + 比較 + 自分の更新)": BASE,
        "推奨で他人との比較をなくす": replace(BASE, compete_margin=None),
        "推奨で更新の幅の上限なし": replace(BASE, adapt_boost=1.0, adapt_base_weight=30.0, adapt_max_weight=120.0),
        "推奨で仮の話者の重みを長さにしない": replace(BASE, duration_weight=False),
        "推奨で聞き取りと同じ場所で登録": replace(BASE, enroll="table"),
    }
    res = {"dev": run(pool, rules, DEV), "eval": run(pool, rules, EVAL)}
    sens = {f"{s:.2f} / {s - 0.05:.2f} / {s - 0.10:.2f}": replace(BASE, self_in=s, self_out=s - 0.05, floor=s - 0.10)
            for s in (0.60, 0.55, 0.50, 0.45, 0.40, 0.35)}
    res["sensitivity"] = run(pool, sens, EVAL)
    rows = pool.map(migrated, [("mixed", i, True) for i in EVAL], chunksize=2)
    res["migration"] = summarize(rows) | {
        "before": round(float(np.mean([r["before"] for r in rows])), 1),
        "before_max": int(max(r["before"] for r in rows)),
        "after": round(float(np.mean([r["after"] for r in rows])), 1)}
    write_json(OUT / "comparison.json", res)
    for part in ("dev", "eval", "sensitivity"):
        print("#", part)
        show(res[part])
    print("# 詳細(eval)")
    for name, r in res["eval"].items():
        for c in CONDS:
            print(name, c, r[c])
    print("# migration", res["migration"])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["self", "provisional", "enroll", "adapt", "rival", "report"])
    ap.add_argument("--jobs", type=int, default=9)
    ap.add_argument("--conds", default=",".join(CONDS))
    a = ap.parse_args()
    with Pool(a.jobs) as pool:
        if a.stage == "report":
            report(pool)
        else:
            rules = {"self": self_rules, "provisional": provisional_rules, "enroll": enroll_rules,
                     "adapt": adapt_rules, "rival": rival_rules}[a.stage]()
            conds = a.conds.split(",")
            show(run(pool, rules, DEV, conds), conds)


if __name__ == "__main__":
    main()
