#!/usr/bin/env python3
"""ASR の区間の内側で話者を判定する案を、JVS の合成会話で比べる。数値は参考値。

先に speaker_seg_data.py を流す。ここでは保存済みの embedding だけを使い、音声には触らない。

- dev: 開発用の会話(0〜9)で、案ごとにしきい値を決める
- eval: 評価用(10〜29)と確認用(30〜59)で、決めた設定を測る
- 結果は results/speaker_seg/ に書く

例: python speaker_seg_eval.py sweep
"""
import argparse
import itertools
import pickle
import time
from collections import Counter
from dataclasses import dataclass

import numpy as np

from common import RESULTS, write_json
from der_eval import agg, der
from speaker_seg_data import MIN_EMBED, N_DEV, WORK, key

FRAME = 0.01
DEV, EVAL, CONFIRM = range(0, N_DEV), range(N_DEV, 30), range(30, 60)
MIN_RECALL = {"clean": 90.0, "snr15": 80.0}
MIN_RELIABLE_WHOLE = 2.5  # アプリと同じ。区間は無音を含む
MIN_RELIABLE_PART = 1.0  # 無音を含まない部分
TH_GRID = [round(x, 2) for x in np.arange(0.30, 0.86, 0.05)]
OUT = RESULTS / "speaker_seg"


# ---- データ ----


class Conv:
    def __init__(self, cond, i):
        with (WORK / cond / f"{i}.pkl").open("rb") as f:
            d = pickle.load(f)
        self.__dict__.update(d)
        n = int(self.dur / FRAME) + 2
        t = np.zeros(n, dtype=np.int8)
        for s, e, k in self.truth:
            a, b = int(s / FRAME), int(e / FRAME)
            t[a:b] = np.maximum(t[a:b], 2 if k == self.me else 1)
        self.t = t
        self.cs = np.concatenate([[0], np.cumsum(t == 2)])
        self.co = np.concatenate([[0], np.cumsum(t == 1)])
        self.sim_cache = {}

    def vec(self, a, b):
        return self.embs.get(key(a, b))

    def counts(self, a, b):
        """[a, b) の自分と他人のフレーム数。"""
        i, j = int(a / FRAME), min(len(self.t), int(b / FRAME))
        if j <= i:
            return 0, 0
        return int(self.cs[j] - self.cs[i]), int(self.co[j] - self.co[i])

    def tokens(self):
        with (WORK / self.cond / f"{self.idx}.asr.pkl").open("rb") as f:
            return pickle.load(f)["segs"]


_conv = {}


def conv(cond, i) -> Conv:
    k = (cond, i)
    if k not in _conv:
        _conv[k] = Conv(cond, i)
    return _conv[k]


# ---- 判定の単位 ----


@dataclass
class Group:
    a: float
    b: float
    vec: np.ndarray | None
    sim: float
    state: str | None = None  # 先に決まっている場合だけ(窓の平滑化)


def unit(v):
    return v / (np.linalg.norm(v) + 1e-9)


def weighted(vs, ds):
    return unit(sum(v * d for v, d in zip(vs, ds)))


def whole_groups(c, tail=True):
    out = []
    for r in c.segs:
        v = c.vec(r["s"], r["e2"] if tail else r["e"])
        if v is None:
            v = c.vec(r["s"], r["e2"])
        out.append([Group(r["s"], r["e"], v, float(v @ c.me_vec) if v is not None else -1.0)])
    return out


def fill(items):
    """(a, b, vec) を、区間全体を覆うように中点で隙間を埋める。短い部分は隣へ吸収する。"""
    items = [list(x) for x in items]
    # 短い部分(embedding なし)は、長いほうの隣に吸収する
    i = 0
    while len(items) > 1 and i < len(items):
        if items[i][2] is None:
            left = items[i - 1] if i > 0 else None
            right = items[i + 1] if i + 1 < len(items) else None
            if left is None or (right is not None and right[1] - right[0] > left[1] - left[0]):
                right[0] = items[i][0]
            else:
                left[1] = items[i][1]
            items.pop(i)
        else:
            i += 1
    for p, q in zip(items, items[1:]):
        m = (p[1] + q[0]) / 2
        p[1], q[0] = m, m
    return items


def merge_adjacent(items, th):
    """隣り合う部分が似ていれば、長さで重みづけした平均にまとめる。embedding を増やさない。"""
    if th is None:
        return items
    out = [list(items[0]) + [items[0][1] - items[0][0]]]
    for a, b, v in items[1:]:
        d = b - a
        p = out[-1]
        if p[2] is not None and v is not None and float(p[2] @ v) >= th:
            p[2] = weighted([p[2], v], [p[3], d])
            p[1] = b
            p[3] += d
        else:
            out.append([a, b, v, d])
    return [x[:3] for x in out]


def part_groups(c, name, merge=None):
    out = []
    for r in c.segs:
        items = [(a, b, c.vec(a, b)) for a, b in r["parts"][name]]
        items = merge_adjacent(fill(items), merge)
        # 全部が短いときは後ろの無音込みの全体に頼る
        gs = []
        for a, b, v in items:
            if v is None:
                v = c.vec(r["s"], r["e2"])
            gs.append(Group(a, b, v, float(v @ c.me_vec) if v is not None else -1.0))
        out.append(gs)
    return out


def win_series(c, r, name):
    ws = r["win"][name]
    vs = [c.vec(a, b) for a, b in ws]
    keep = [i for i, v in enumerate(vs) if v is not None]
    if not keep:
        return [], [], []
    ws = [ws[i] for i in keep]
    vs = np.array([vs[i] for i in keep])
    return ws, vs, vs @ c.me_vec


def win_edges(ws, s, e):
    """窓の中央の中点を、判定の境界にする。"""
    cs = [(a + b) / 2 for a, b in ws]
    return [s] + [(p + q) / 2 for p, q in zip(cs, cs[1:])] + [e]


def runs(states):
    i = 0
    while i < len(states):
        j = i
        while j < len(states) and states[j] == states[i]:
            j += 1
        yield i, j, states[i]
        i = j


def smooth_groups(c, name, kind, p, th, lo=None):
    """窓ごとの判定を平滑化する。kind は median(p は窓の数)か hyst(th が入る側、lo が留まる側)。"""
    out = []
    for r in c.segs:
        ws, vs, sims = win_series(c, r, name)
        if not ws:
            v = c.vec(r["s"], r["e2"])
            out.append([Group(r["s"], r["e"], v, float(v @ c.me_vec), None)])
            continue
        if kind == "median":
            n = len(sims)
            b = sims >= th
            sm = [b[max(0, i - p // 2) : min(n, i + p // 2 + 1)].mean() > 0.5 for i in range(n)]
        else:
            st, sm = False, []
            for x in sims:
                st = (x >= lo) if st else (x >= th)
                sm.append(bool(st))
        ed = win_edges(ws, r["s"], r["e"])
        gs = []
        for i, j, st in runs(sm):
            v = weighted(list(vs[i:j]), [1.0] * (j - i))
            gs.append(Group(ed[i], ed[j], v, float(sims[i:j].mean()), "self" if st else "other"))
        out.append(gs)
    return out


def change_points(vs, ws, hop_w, th):
    """左右の隣り合う窓の距離の山を変化点にする。"""
    n = len(vs)
    d = np.zeros(n)
    for i in range(hop_w, n):
        d[i] = 1 - float(vs[i - hop_w] @ vs[i])
    out = []
    for i in range(hop_w, n):
        if d[i] >= th and d[i] >= d[max(hop_w, i - hop_w) : min(n, i + hop_w + 1)].max():
            if not out or i - out[-1] >= hop_w:
                out.append(i)
    return out


def cd_groups(c, name, th, base=None, min_len=2.0):
    """変化点で分ける。base を渡すと、静かな区切りの後の、min_len 秒以上の部分の内側だけを見る。"""
    w, hop_w = (1.0, 2) if name == "w10h05" else (1.5, 3)
    out = []
    for r in c.segs:
        if base is None:
            spans = [(r["s"], r["e"], None)]
        else:
            spans = fill([(a, b, c.vec(a, b)) for a, b in r["parts"][base]])
        gs = []
        for a, b, v0 in spans:
            if base is not None and b - a < min_len:
                v = v0 if v0 is not None else c.vec(r["s"], r["e2"])
                gs.append(Group(a, b, v, float(v @ c.me_vec)))
                continue
            ws, vs, _ = win_series(c, r, name)
            sel = [i for i, (p, q) in enumerate(ws) if p >= a - 0.25 and q <= b + 0.25]
            if len(sel) <= hop_w:
                v = v0 if v0 is not None else c.vec(r["s"], r["e2"])
                gs.append(Group(a, b, v, float(v @ c.me_vec)))
                continue
            ws2 = [ws[i] for i in sel]
            vs2 = vs[sel]
            cps = change_points(vs2, ws2, hop_w, th)
            if not cps and v0 is not None:
                gs.append(Group(a, b, v0, float(v0 @ c.me_vec)))
                continue
            # 変化点 i は窓 i - hop_w と窓 i の境目。窓 i の始まりにあたる
            cut = [ws2[i][0] for i in cps]
            edges = [a, *cut, b]
            mid = np.array([(p + q) / 2 for p, q in ws2])
            for k in range(len(edges) - 1):
                # 境界をまたがない窓の平均。embedding を増やさない
                inside = [i for i, (p, q) in enumerate(ws2) if p >= edges[k] - 0.05 and q <= edges[k + 1] + 0.05]
                if not inside:
                    inside = [int(np.argmin(np.abs(mid - (edges[k] + edges[k + 1]) / 2)))]
                v = unit(vs2[inside].mean(0))
                gs.append(Group(edges[k], edges[k + 1], v, float(v @ c.me_vec)))
        out.append(gs)
    return out


# ---- 判定と集計 ----


def classify(g, hi, lo, delta=0.0, band=None):
    if g.state is not None:
        # 窓の平滑化の結果に、まとまりの平均類似度で不明の帯を重ねる
        if band and band[0] <= g.sim < band[1]:
            return "unknown"
        return g.state
    sim = g.sim + (delta if g.b - g.a < 1.5 else 0.0)
    if sim >= hi:
        return "self"
    return "other" if sim < lo else "unknown"


def decide(c, groups, hi, lo, mode, f=0.5, delta=0.0, band=None):
    """区間ごとの判定の列を返す。各要素は [(a, b, 状態, Group の列)]。

    mode: parts は部分ごと(文字起こしを時刻で分ける)、major は 1 区間 1 話者(多数決)。
    major の f: 自分の時間の割合が f 以上で自分、1 - f 以下で他人、その間は不明。
    """
    out = []
    for gs in groups:
        st = [classify(g, hi, lo, delta, band) for g in gs]
        if mode == "parts":
            for g, s in zip(gs, st):
                out.append((g.a, g.b, s, [g]))
            continue
        d = Counter()
        for g, s in zip(gs, st):
            d[s] += g.b - g.a
        total = sum(d.values()) or 1.0
        frac = d["self"] / total
        if frac >= f:
            s = "self"
        elif frac <= 1 - f and d["other"] >= d["unknown"]:
            s = "other"
        else:
            s = "unknown"
        out.append((gs[0].a, gs[-1].b, s, [g for g, x in zip(gs, st) if x == s] or gs))
    return out


def tally(c, dec):
    """時間で数える。重なりは自分がいれば自分。"""
    t = Counter()
    n_s, n_o = int(c.cs[-1]), int(c.co[-1])
    t["self_total"], t["other_total"] = n_s, n_o
    for a, b, st, _ in dec:
        fs, fo = c.counts(a, b)
        t[f"s_{st}"] += fs
        t[f"o_{st}"] += fo
    return t


def pct(t):
    st, ot = t["self_total"], t["other_total"]
    return {
        "self_recall": 100 * t["s_self"] / st,
        "other_as_self": 100 * t["o_self"] / ot,
        "self_as_other": 100 * t["s_other"] / st,
        "unknown": 100 * (t["s_unknown"] + t["o_unknown"]) / (st + ot),
        "self_unknown": 100 * t["s_unknown"] / st,
        "other_unknown": 100 * t["o_unknown"] / ot,
        "self_precision": 100 * t["s_self"] / max(1, t["s_self"] + t["o_self"]),
    }


class Clusters:
    """仮の話者の逐次クラスタリング。アプリの照合と同じ規則。"""

    def __init__(self, th):
        self.th, self.c, self.n = th, [], []

    def assign(self, v, reliable):
        sims = [float(v @ x) for x in self.c]
        if sims and max(sims) >= self.th:
            i = int(np.argmax(sims))
            if reliable:
                x = self.c[i] * self.n[i] + v
                self.c[i] = unit(x)
                self.n[i] += 1
            return f"other{i}"
        if reliable:
            self.c.append(v)
            self.n.append(1)
            return f"other{len(self.c) - 1}"
        return None


def hyp_of(c, dec, th_cluster, whole):
    cl = Clusters(th_cluster)
    hyp = []
    for a, b, st, gs in dec:
        if st == "self":
            hyp.append((a, b, "self"))
        elif st == "other":
            d = sum(g.b - g.a for g in gs)
            v = weighted([g.vec for g in gs], [g.b - g.a for g in gs])
            rel = (b - a + 1.1 >= MIN_RELIABLE_WHOLE) if whole else d >= MIN_RELIABLE_PART
            k = cl.assign(v, rel)
            if k is not None:
                hyp.append((a, b, k))
    return hyp, len(cl.c)


def speech_mask(c, hyp):
    """DER 用。無音まで話者を付けて誤検出になる分を、案の比較から除く。全案で同じ VAD の 0.2 秒の部分に絞る。"""
    spans = sorted((a, b) for r in c.segs for a, b in r["parts"]["s20"])
    out = []
    for a, b, k in hyp:
        for p, q in spans:
            x, y = max(a, p), min(b, q)
            if y > x:
                out.append((x, y, k))
    return out


def char_tally(c, dec_per_seg):
    """ASR のトークンごとの話者を、音素の正解と比べる。文字数で重みづけする。"""
    t = Counter()
    n = len(c.t)
    tok = c.tokens()
    for r, seg_t, dec in zip(c.segs, tok, dec_per_seg):
        for w, tm in zip(seg_t["tokens"], seg_t["times"]):
            if not w.strip():
                continue
            tm2 = min(max(tm, r["s"]), r["e"] - 0.01)
            i = min(n - 1, int(tm / FRAME))
            lo, hi = max(0, i - 15), min(n, i + 16)
            v = int(c.t[lo:hi].max()) if c.t[i] == 0 else int(c.t[i])
            if v == 0:
                t["no_truth"] += len(w)
                continue
            st = next((x[2] for x in dec if x[0] <= tm2 < x[1]), dec[-1][2])
            who = "s" if v == 2 else "o"
            t[f"{who}_{st}"] += len(w)
            t["self_total" if v == 2 else "other_total"] += len(w)
    return t


def seg_dec(c, groups, **kw):
    return [decide(c, [gs], **kw) for gs in groups]


# ---- 案の一覧 ----


@dataclass(frozen=True)
class Plan:
    name: str
    family: str
    args: tuple = ()
    hi: float = 0.6
    lo: float | None = None
    mode: str = "parts"
    f: float = 0.5
    delta: float = 0.0
    band: tuple | None = None

    def groups(self, c):
        key_ = (self.family, self.args, self.hi if self.family in ("median", "hyst") else None,
                self.lo if self.family == "hyst" else None)
        cache = c.sim_cache
        if key_ not in cache:
            fam, a = self.family, self.args
            if fam == "whole":
                g = whole_groups(c, tail=a[0])
            elif fam == "parts":
                g = part_groups(c, a[0], a[1])
            elif fam == "median":
                g = smooth_groups(c, a[0], "median", a[1], self.hi)
            elif fam == "hyst":
                g = smooth_groups(c, a[0], "hyst", 0, self.hi, self.lo)
            elif fam == "cd":
                g = cd_groups(c, a[0], a[1])
            elif fam == "cdparts":
                g = cd_groups(c, a[0], a[1], base=a[2])
            else:
                raise ValueError(fam)
            cache[key_] = g
        return cache[key_]

    def dec(self, c):
        lo = self.hi if self.lo is None else self.lo
        return decide(c, self.groups(c), self.hi, lo, self.mode, self.f, self.delta, self.band)

    def seg_decs(self, c):
        lo = self.hi if self.lo is None else self.lo
        return seg_dec(c, self.groups(c), hi=self.hi, lo=lo, mode=self.mode, f=self.f, delta=self.delta, band=self.band)


def grid():
    """案ごとの設定の候補。family ごとに、しきい値を開発用の会話で選ぶ。"""
    ths, plans = TH_GRID, {}

    def add(fam_label, p):
        plans.setdefault(fam_label, []).append(p)

    for th in ths:
        add("A 区間全体", Plan("whole", "whole", (True,), th))
        add("A' 区間全体(後ろの無音なし)", Plan("whole-notail", "whole", (False,), th))
    for name in ("s10", "s15", "s20", "s30", "h10", "h20", "d6", "d10"):
        for merge in (None, 0.4, 0.5, 0.6, 0.7):
            for th, delta in itertools.product(ths, (0.0, 0.05, 0.1)):
                add(f"B1 部分/時刻 {name}", Plan(f"parts-{name}", "parts", (name, merge), th, delta=delta))
                for f in (0.5, 0.6, 0.7, 0.8):
                    add(f"B2 部分/多数決 {name}",
                        Plan(f"major-{name}", "parts", (name, merge), th, mode="major", f=f, delta=delta))
    for win in ("w10h05", "w15h05", "w15h075"):
        for th in ths:
            for k in (3, 5):
                add(f"D1 窓/多数決平滑 {win}", Plan(f"median-{win}", "median", (win, k), th))
            for d in (0.05, 0.1, 0.15, 0.2):
                add(f"D2 窓/ヒステリシス {win}", Plan(f"hyst-{win}", "hyst", (win,), th, round(th - d, 2)))
    for win in ("w10h05", "w15h05"):
        for cdth in (0.3, 0.4, 0.5, 0.6, 0.7):
            for th in ths:
                add(f"C1 変化点 {win}", Plan(f"cd-{win}", "cd", (win, cdth), th))
                for base in ("s20", "s30"):
                    add(f"C2 区切り+変化点 {base} {win}", Plan(f"cdparts-{base}-{win}", "cdparts", (win, cdth, base), th))
    return plans


def two_tier(p: Plan):
    """不明の帯の候補。下へ w、上へ u 広げる。上へ広げると、自分と判定する時間も減る。"""
    out = []
    for w, u in itertools.product((0.05, 0.1, 0.15, 0.2), (0.0, 0.05, 0.1, 0.15)):
        if p.family in ("median", "hyst"):
            out.append(Plan(p.name + "+不明", p.family, p.args, p.hi, p.lo, p.mode, p.f, p.delta,
                            (round(p.hi - w, 2), round(p.hi + u, 2))))
        else:
            out.append(Plan(p.name + "+不明", p.family, p.args, round(p.hi + u, 2), round(p.hi - w, 2), p.mode, p.f,
                            p.delta))
    return out


def score(plan: Plan, cond, ids):
    t = Counter()
    for i in ids:
        c = conv(cond, i)
        t.update(tally(c, plan.dec(c)))
    return pct(t)


def select(plans, dev=DEV):
    """clean と SNR 15 dB の再現率の下限を満たす中で、2 条件の平均の誤受入が最も小さい設定を選ぶ。"""
    best = None
    fallback = None
    for p in plans:
        r = {cond: score(p, cond, dev) for cond in ("clean", "snr15")}
        fa = (r["clean"]["other_as_self"] + r["snr15"]["other_as_self"]) / 2
        ok = all(r[k]["self_recall"] >= v for k, v in MIN_RECALL.items())
        rec = (r["clean"]["self_recall"] + r["snr15"]["self_recall"]) / 2
        if ok and (best is None or fa < best[0]):
            best = (fa, p, r)
        if fallback is None or rec > fallback[0]:
            fallback = (rec, p, r)
    if best is None:
        return None, fallback[1], fallback[2]
    return True, best[1], best[2]


def cost_chunks(plan: Plan, c):
    """この案が embedding を取る音声の長さの一覧(秒)。"""
    fam, a = plan.family, plan.args
    out = []
    for r in c.segs:
        if fam == "whole":
            out.append((r["e2"] if a[0] else r["e"]) - r["s"])
        elif fam == "parts":
            out += [q - p for p, q in r["parts"][a[0]] if q - p >= MIN_EMBED]
        elif fam in ("median", "hyst"):
            out += [q - p for p, q in r["win"][a[0]] if q - p >= MIN_EMBED]
        elif fam == "cd":
            out += [q - p for p, q in r["win"][a[0]] if q - p >= MIN_EMBED]
        elif fam == "cdparts":
            ps = r["parts"][a[2]]
            out += [q - p for p, q in ps if q - p >= MIN_EMBED]
            for p, q in ps:
                if q - p >= 2.0:
                    out += [y - x for x, y in r["win"][a[0]] if x >= p - 0.25 and y <= q + 0.25]
    return out


def measure(plan: Plan, cond, ids, c0, c1, th_cluster):
    t, ct = Counter(), Counter()
    d0, d25, raw0, per, ncl, nspk, cpu, audio, nemb, sec = [], [], [], [], [], [], 0.0, 0.0, 0, 0.0
    whole = plan.family == "whole"
    for i in ids:
        c = conv(cond, i)
        dec = plan.dec(c)
        tt = tally(c, dec)
        t.update(tt)
        per.append(tt)
        hyp, n = hyp_of(c, dec, th_cluster, whole)
        raw0.append(der(c.truth, hyp, c.dur))
        hyp = speech_mask(c, hyp)
        ncl.append(n)
        nspk.append(len(c.spk) - 1)
        d0.append(der(c.truth, hyp, c.dur))
        d25.append(der(c.truth, hyp, c.dur, 0.25))
        ct.update(char_tally(c, plan.seg_decs(c)))
        ch = cost_chunks(plan, c)
        nemb += len(ch)
        sec += sum(ch)
        cpu += c0 * len(ch) + c1 * sum(ch)
        audio += c.dur
    res = {k: round(v, 2) for k, v in pct(t).items()}
    res |= {"der_collar0": agg(d0)["der_pct"], "der_collar025": agg(d25)["der_pct"],
            "der_detail": agg(d0), "der_nomask_collar0": agg(raw0)["der_pct"], "clusters_mean": round(float(np.mean(ncl)), 2),
            "other_speakers_mean": round(float(np.mean(nspk)), 2),
            "embeds_per_min": round(nemb / audio * 60, 1), "embedded_sec_per_audio_sec": round(sec / audio, 3),
            "embed_cpu_rtf": round(cpu / audio, 5)}
    res["chars"] = {k: round(v, 2) for k, v in pct(ct).items()}
    res["chars"]["n"] = ct["self_total"] + ct["other_total"]
    rng = np.random.default_rng(1)
    idx = rng.integers(0, len(per), (2000, len(per)))
    fa, rc = [], []
    so = np.array([p["o_self"] for p in per])
    ot = np.array([p["other_total"] for p in per])
    sh = np.array([p["s_self"] for p in per])
    st = np.array([p["self_total"] for p in per])
    fa = so[idx].sum(1) / ot[idx].sum(1) * 100
    rc = sh[idx].sum(1) / st[idx].sum(1) * 100
    res["per_conv_self_recall_min"] = round(float((sh / st * 100).min()), 1)
    res["per_conv_other_as_self_max"] = round(float((so / ot * 100).max()), 1)
    res["other_as_self_ci95"] = [round(float(np.percentile(fa, q)), 1) for q in (2.5, 97.5)]
    res["self_recall_ci95"] = [round(float(np.percentile(rc, q)), 1) for q in (2.5, 97.5)]
    return res


def cluster_th(plan):
    """DER の仮の話者のしきい値を開発用の会話で決める。"""
    best = None
    for th in (0.4, 0.5, 0.6, 0.7):
        rows = []
        for cond in ("clean", "snr15"):
            for i in DEV:
                c = conv(cond, i)
                hyp, _ = hyp_of(c, plan.dec(c), th, plan.family == "whole")
                rows.append(der(c.truth, speech_mask(c, hyp), c.dur))
        v = agg(rows)["der_pct"]
        if best is None or v < best[0]:
            best = (v, th)
    return best[1]


def seg_stats():
    """区間の中身の集計。2 人以上が 0.3 秒以上入る割合と、話者交代の無音の長さ。"""
    res = {}
    for cond in ("clean", "snr15", "tclean", "tsnr15"):
        ids = list(range(30))
        tot = Counter()
        fine = Counter()
        gaps = []
        for i in ids:
            c = conv(cond, i)
            tr = sorted(c.truth)
            gaps += [b[0] - a[1] for a, b in zip(tr, tr[1:]) if a[2] != b[2]]

            def spk_in(a, b):
                o = Counter()
                for s, e, k in c.truth:
                    d = min(b, e) - max(a, s)
                    if d > 0:
                        o[k] += d
                return o

            for r in c.segs:
                o = spk_in(r["s"] + 0.3, r["e"])
                if o:
                    tot["n"] += 1
                    tot["mixed"] += sum(v > 0.3 for v in o.values()) > 1
                for name, ps in r["parts"].items():
                    for a, b in ps:
                        o = spk_in(a, b)
                        if o:
                            fine[name, "n"] += 1
                            fine[name, "mixed"] += sum(v > 0.3 for v in o.values()) > 1
                            fine[name, "sec"] += b - a
        g = np.array(gaps)
        res[cond] = {
            "segments": tot["n"], "mixed_pct": round(tot["mixed"] / tot["n"] * 100, 1),
            "turn_gap_median_s": round(float(np.median(g)), 2),
            "turn_gap_lt_0_2_pct": round(float((g < 0.2).mean() * 100), 1),
            "turn_gap_lt_0_8_pct": round(float((g < 0.8).mean() * 100), 1),
            "parts": {n: {"count": fine[n, "n"], "mixed_pct": round(fine[n, "mixed"] / max(1, fine[n, "n"]) * 100, 1),
                          "mean_sec": round(fine[n, "sec"] / max(1, fine[n, "n"]), 2)}
                      for n in sorted({k[0] for k in fine})},
        }
    return res


def plan_dict(p: Plan):
    return {"family": p.family, "args": list(p.args), "hi": p.hi, "lo": p.lo, "mode": p.mode, "f": p.f,
            "delta": p.delta, "band": p.band}


def best_two_tier(p: Plan, max_unknown=10.0):
    """開発用の 4 条件で、不明が max_unknown % 以内で、誤受入 x 3 + 自分を他人とする率 の平均が最小の帯を選ぶ。

    3 倍は「自分の取り違えのほうが害が大きい」を表す仮の重み。
    """
    best = None
    for q in two_tier(p):
        r = [score(q, cond, DEV) for cond in ("clean", "snr15", "tclean", "tsnr15")]
        if max(x["unknown"] for x in r) > max_unknown:
            continue
        v = sum(x["other_as_self"] * 3 + x["self_as_other"] for x in r)
        if best is None or v < best[0]:
            best = (v, q)
    return best[1] if best else None


TIER_FOR = ("B1 部分/時刻 s20", "B1 部分/時刻 h10", "B1 部分/時刻 d10", "C1 変化点 w15h05", "C2 区切り+変化点 s30 w15h05",
            "D2 窓/ヒステリシス w15h05", "D2 窓/ヒステリシス w15h075")


def report(c0, c1, labels=None, out="comparison.json"):
    with (WORK / "chosen.pkl").open("rb") as f:
        chosen = pickle.load(f)
    # 現状の 0.6 は開発用で選ばれなくても必ず載せる
    rows = {"現状 区間全体 0.6": Plan("whole", "whole", (True,), 0.6)}
    for label, (ok, p) in chosen.items():
        if labels and label not in labels:
            continue
        rows[label] = p
        # 多数決は再現率の下限のためにしきい値が下がる。部分ごとと同じ設定の多数決も載せて、割り当てだけの差を見る
        if label.startswith("B1"):
            rows[label.replace("B1 部分/時刻", "B2' 同設定の多数決")] = Plan(
                p.name.replace("parts", "major"), p.family, p.args, p.hi, p.lo, "major", 0.5, p.delta)
        if label == "D2 窓/ヒステリシス w15h075":
            rows["D2' 同設定の多数決 w15h075"] = Plan("major-hyst", p.family, p.args, p.hi, p.lo, "major", 0.5, p.delta)
    res = {}
    for label, p in list(rows.items()):
        if label not in TIER_FOR:
            continue
        tt = best_two_tier(p)
        if tt is not None:
            rows[label + " +不明"] = tt
    for label, p in rows.items():
        th = cluster_th(p)
        res[label] = {"plan": plan_dict(p), "cluster_th": th, "dev": {}, "eval": {}, "confirm": {}, "tight_eval": {},
                      "tight_confirm": {}}
        for cond in ("clean", "snr15"):
            res[label]["dev"][cond] = measure(p, cond, DEV, c0, c1, th)
            res[label]["eval"][cond] = measure(p, cond, EVAL, c0, c1, th)
            res[label]["confirm"][cond] = measure(p, cond, CONFIRM, c0, c1, th)
        for cond in ("tclean", "tsnr15"):
            res[label]["tight_eval"][cond] = measure(p, cond, EVAL, c0, c1, th)
            res[label]["tight_confirm"][cond] = measure(p, cond, CONFIRM, c0, c1, th)
        e = res[label]["eval"]
        print(f"{label[:34]:34} ", end="")
        for s, cond in (("eval", "clean"), ("eval", "snr15"), ("tight_eval", "tclean"), ("tight_eval", "tsnr15")):
            m = res[label][s][cond]
            print(f"{m['self_recall']:5.1f}/{m['other_as_self']:4.1f}/{m['unknown']:4.1f}/{m['der_collar0']:5.1f}", end="  ")
        print(f"{e['clean']['embed_cpu_rtf']:.4f} {e['clean']['chars']['other_as_self']:.1f} sao {e['clean']['self_as_other']:.1f}", flush=True)
    write_json(OUT / out, res)
    if "D2 窓/ヒステリシス w15h075" in rows:
        sensitivity(rows["D2 窓/ヒステリシス w15h075"], c0, c1)


def sensitivity(p: Plan, c0, c1):
    """推薦の設定のしきい値をずらした値と、雑音の環境ごとの値。"""
    res = {"thresholds": {}, "envs": {}}
    for d in (-0.1, -0.05, 0.05, 0.1):
        q = Plan(p.name, p.family, p.args, round(p.hi + d, 2), round(p.lo + d, 2), p.mode, p.f, p.delta)
        res["thresholds"][f"{q.hi}/{q.lo}"] = {
            cond: {k: round(v, 1) for k, v in score(q, cond, EVAL).items()} for cond in ("clean", "snr15", "tclean", "tsnr15")}
    t = {}
    for i in list(EVAL) + list(CONFIRM):
        c = conv("snr15", i)
        t.setdefault(c.env, Counter()).update(tally(c, p.dec(c)))
    res["envs"] = {k: {"self_recall": round(pct(v)["self_recall"], 1), "other_as_self": round(pct(v)["other_as_self"], 1),
                       "n_frames": v["self_total"] + v["other_total"]} for k, v in sorted(t.items())}
    write_json(OUT / "sensitivity.json", res)


def table(path="comparison.json"):
    """比較表を Markdown で出す。"""
    import json

    d = json.loads((OUT / path).read_text(encoding="utf-8"))
    print("| 案 | 設定 | 条件 | 再現率 | 誤受入 | 自分→他人 | 不明 | DER 0 / 0.25 | 文字の誤受入 | 文字の再現率 | CPU 実時間比 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for label, r in d.items():
        p = r["plan"]
        cfg = f"{p['hi']}/{p['lo']}" + (f" 帯{p['band']}" if p["band"] else "") + f" {p['args']}"
        for sec, cond in (("eval", "clean"), ("eval", "snr15"), ("tight_eval", "tclean"), ("tight_eval", "tsnr15"),
                          ("confirm", "clean"), ("confirm", "snr15"), ("tight_confirm", "tclean"), ("tight_confirm", "tsnr15")):
            m = r[sec][cond]
            print(f"| {label} | {cfg} | {sec} {cond} | {m['self_recall']} {m['self_recall_ci95']} | {m['other_as_self']} "
                  f"{m['other_as_self_ci95']} | {m['self_as_other']} | {m['unknown']} | {m['der_collar0']} / {m['der_collar025']} "
                  f"| {m['chars']['other_as_self']} | {m['chars']['self_recall']} | {m['embed_cpu_rtf']} |")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["stats", "sweep", "baseline", "report", "table"])
    ap.add_argument("--c0", type=float, default=1e-3, help="embedding 1 回の固定費 CPU 秒")
    ap.add_argument("--c1", type=float, default=5e-3, help="音声 1 秒あたりの CPU 秒")
    a = ap.parse_args()
    if a.cmd == "stats":
        write_json(OUT / "segments.json", seg_stats())
        print(open(OUT / "segments.json").read())
    if a.cmd == "report":
        report(a.c0, a.c1)
    if a.cmd == "table":
        table()
    if a.cmd == "baseline":
        for cond in ("clean", "snr15"):
            r = score(Plan("whole", "whole", (True,), 0.6), cond, range(30))
            print(cond, {k: round(v, 1) for k, v in r.items()})
    if a.cmd == "sweep":
        t0 = time.time()
        plans = grid()
        chosen = {}
        for label, ps in plans.items():
            ok, p, r = select(ps)
            chosen[label] = (ok, p)
            print(f"{time.time() - t0:6.0f}s {label} ok={ok} hi={p.hi} lo={p.lo} args={p.args} f={p.f} d={p.delta} "
                  f"clean {r['clean']['self_recall']:.1f}/{r['clean']['other_as_self']:.1f} "
                  f"snr15 {r['snr15']['self_recall']:.1f}/{r['snr15']['other_as_self']:.1f}", flush=True)
        with (WORK / "chosen.pkl").open("wb") as f:
            pickle.dump(chosen, f)


if __name__ == "__main__":
    main()
