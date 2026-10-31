#!/usr/bin/env python3
"""results/raw の出力を採点し、results/scores.json と results/errors.md を作る。"""
import collections
import datetime
import json
import pathlib
import re
import sys
import unicodedata

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = ROOT / "results" / "raw"
ITEMS = {x["id"]: x for x in yaml.safe_load((ROOT / "data" / "eval.yaml").read_text(encoding="utf-8"))}

UNKNOWN = re.compile(r"不明|記載(が|は)?(なく|ない|ありません|されていません)|書かれていません|分かりません|わかりません")


def norm(s) -> str:
    s = unicodedata.normalize("NFKC", str(s))
    return re.sub(r"\s+", "", s).lower()


def groups_hit(text: str, groups) -> bool:
    t = norm(text)
    return all(any(norm(alt) in t for alt in g) for g in groups)


def strip_think(t: str) -> str:
    return re.sub(r"<think>.*?</think>", "", t, flags=re.S).strip()


def parse(text: str):
    """(解析結果, 状態)。状態は strict / fenced / fail。"""
    t = strip_think(text)
    try:
        return json.loads(t), "strict"
    except json.JSONDecodeError:
        pass
    m = re.search(r"```(?:json)?\s*(.*?)```", t, re.S)
    if m:
        try:
            return json.loads(m.group(1)), "fenced"
        except json.JSONDecodeError:
            pass
    return None, "fail"


def valid_todo(o) -> bool:
    if not isinstance(o, dict) or not isinstance(o.get("items"), list):
        return False
    for x in o["items"]:
        if not isinstance(x, dict) or not isinstance(x.get("task"), str) or x.get("kind") not in ("todo", "reminder"):
            return False
        d = x.get("due", "MISSING")
        if d is not None and not (isinstance(d, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", d)):
            return False
    return True


def valid_speakers(o) -> bool:
    if not isinstance(o, dict) or not isinstance(o.get("speakers"), list):
        return False
    return all(isinstance(x, dict) and isinstance(x.get("label"), str) and "name" in x for x in o["speakers"])


def due_ok(pred, gold) -> bool:
    golds = gold if isinstance(gold, list) else [gold]
    golds = [g.isoformat() if hasattr(g, "isoformat") else g for g in golds]
    return (pred or None) in golds


def score_todos(item, obj):
    """gold と予測を1対1で対応付ける。optional に当たった予測は TP にも FP にも数えない。"""
    gold = item["todos"]
    used, tp, fp, due_hit, kind_hit, opt, fps = set(), 0, 0, 0, 0, 0, []
    for p in obj["items"]:
        hit = None
        for i, g in enumerate(gold):
            if i not in used and groups_hit(p["task"], g["match"]):
                hit = i
                break
        if hit is None:
            # 同じ gold への重複は誤りとして数える
            fp += 1
            fps.append(p["task"])
            continue
        used.add(hit)
        g = gold[hit]
        if g.get("optional"):
            opt += 1
            continue
        tp += 1
        due_hit += due_ok(p.get("due"), g["due"])
        kind_hit += p["kind"] == g["kind"]
    req = sum(not g.get("optional") for g in gold)
    miss = [" / ".join(map(str, g["match"][0])) for i, g in enumerate(gold) if i not in used and not g.get("optional")]
    return dict(tp=tp, fp=fp, gold=req, due=due_hit, kind=kind_hit, opt=opt), fps, miss


def clean_name(n) -> str:
    return re.sub(r"(さん|くん|君|ちゃん|様|先生)$", "", norm(n))


def speaker_null(n) -> bool:
    return n is None or norm(n) in ("", "null", "不明", "none", "unknown")


def score_speakers(item, obj):
    pred = {x["label"]: x.get("name") for x in obj["speakers"]}
    c = collections.Counter()
    for label, gold in item["speakers"].items():
        p = pred.get(label)
        if gold is None:
            c["null_gold"] += 1
            if speaker_null(p):
                c["null_ok"] += 1
        else:
            c["named_gold"] += 1
            if not speaker_null(p):
                pn = clean_name(p)
                if any(norm(a) in pn or (pn and pn in norm(a)) for a in gold):
                    c["named_ok"] += 1
                else:
                    c["named_wrong"] += 1
    return c


def main():
    out, errors = {}, []
    for path in sorted(RAW.glob("*.jsonl")):
        recs = [json.loads(l) for l in path.open(encoding="utf-8")]
        stem = path.stem
        r = {"calls": len(recs), "errors": sum("error" in x for x in recs)}
        by = collections.defaultdict(list)
        for x in recs:
            by[x["task"]].append(x)
        errors.append(f"\n## {stem}\n")

        for mode in ("todo_free", "todo_schema"):
            if not by[mode]:
                continue
            tot, st = collections.Counter(), collections.Counter()
            trap_fp = 0
            for x in by[mode]:
                obj, s = parse(x["text"])
                ok = obj is not None and valid_todo(obj)
                st["ok_strict" if ok and s == "strict" else "ok_fenced" if ok else "fail"] += 1
                item = ITEMS[x["id"]]
                if not ok:
                    tot["gold"] += sum(not g.get("optional") for g in item["todos"])
                    continue
                m, fps, miss = score_todos(item, obj)
                tot.update(m)
                if not item["todos"]:
                    trap_fp += len(obj["items"])
                if fps or miss:
                    errors.append(f"- {mode} {x['id']} 誤検出 {fps} / 見逃し {miss}")
            n = len(by[mode])
            p = tot["tp"] / (tot["tp"] + tot["fp"]) if tot["tp"] + tot["fp"] else 0
            rc = tot["tp"] / tot["gold"] if tot["gold"] else 0
            r[mode] = {
                "n": n, "parse_strict": st["ok_strict"] / n, "parse_ok": (st["ok_strict"] + st["ok_fenced"]) / n,
                "precision": p, "recall": rc, "f1": 2 * p * rc / (p + rc) if p + rc else 0,
                "tp": tot["tp"], "fp": tot["fp"], "gold": tot["gold"],
                "due_acc": tot["due"] / tot["tp"] if tot["tp"] else 0,
                "kind_acc": tot["kind"] / tot["tp"] if tot["tp"] else 0,
                "fp_on_no_todo": trap_fp,
            }

        for mode in ("speakers_free", "speakers_schema"):
            if not by[mode]:
                continue
            c, ok_n, fenced = collections.Counter(), 0, 0
            for x in by[mode]:
                obj, s = parse(x["text"])
                item = ITEMS[x["id"]]
                if obj is None or not valid_speakers(obj):
                    c["named_gold"] += sum(v is not None for v in item["speakers"].values())
                    c["null_gold"] += sum(v is None for v in item["speakers"].values())
                    continue
                ok_n += 1
                fenced += s == "fenced"
                c.update(score_speakers(item, obj))
            n = len(by[mode])
            r[mode] = {
                "n": n, "parse_ok": ok_n / n, "parse_strict": (ok_n - fenced) / n,
                "named_recall": c["named_ok"] / c["named_gold"] if c["named_gold"] else 0,
                "named_wrong": c["named_wrong"], "named_gold": c["named_gold"],
                "null_kept": c["null_ok"] / c["null_gold"] if c["null_gold"] else 0,
                "null_gold": c["null_gold"],
                "overall": (c["named_ok"] + c["null_ok"]) / (c["named_gold"] + c["null_gold"]),
            }

        if by["summary"]:
            fact_hit = fact_n = forb = over = bad = 0
            for x in by["summary"]:
                item = ITEMS[x["id"]]
                t = strip_think(x["text"])
                facts = item["summary_facts"]
                fact_n += len(facts)
                fact_hit += sum(groups_hit(t, f) for f in facts)
                forb += sum(norm(w) in norm(t) for w in item["forbidden"])
                over += len(t) > len(item["transcript"])
                bad += not t
            r["summary"] = {"n": len(by["summary"]), "fact_recall": fact_hit / fact_n, "forbidden_hits": forb,
                            "longer_than_source": over, "empty": bad,
                            "avg_chars": sum(len(strip_think(x["text"])) for x in by["summary"]) / len(by["summary"])}

        if by["qa"]:
            c = collections.Counter()
            for x in by["qa"]:
                q = ITEMS[x["id"]]["qa"][x["q"]]
                t = strip_think(x["text"])
                if q.get("answerable") is False:
                    c["un_n"] += 1
                    c["un_ok"] += bool(UNKNOWN.search(t))
                else:
                    c["an_n"] += 1
                    hit = groups_hit(t, q["a"]) and not UNKNOWN.search(t)
                    c["an_ok"] += hit
                    if not hit:
                        errors.append(f"- {x['id']} 「{q['q']}」→ {t[:80]!r}")
            r["qa"] = {"answerable_acc": c["an_ok"] / c["an_n"] if c["an_n"] else 0, "n": c["an_n"],
                       "unanswerable_acc": c["un_ok"] / c["un_n"] if c["un_n"] else 0, "un_n": c["un_n"]}

        good = [x for x in recs if x.get("gen_tokens")]
        gt = sum(x["gen_tokens"] for x in good)
        r["speed"] = {
            "gen_tps": gt / sum(x["gen_tokens"] / x["gen_tps"] for x in good if x.get("gen_tps")) if good else 0,
            "prompt_tps": sum(x["prompt_tokens"] for x in good)
            / max(sum(x["prompt_tokens"] / x["prompt_tps"] for x in good if x.get("prompt_tps")), 1e-9) if good else 0,
            "avg_wall": sum(x["wall"] for x in good) / len(good) if good else 0,
            "max_prompt_tokens": max((x["prompt_tokens"] for x in good), default=0),
        }

        mem = path.with_suffix("").with_suffix("") if False else RAW / f"{stem}.mem.txt"
        if mem.exists():
            t = mem.read_text()
            m1 = re.search(r"(\d+)\s+maximum resident set size", t)
            m2 = re.search(r"(\d+)\s+peak memory footprint", t)
            r["memory"] = {"peak_rss_gb": int(m1[1]) / 1e9 if m1 else None,
                           "peak_footprint_gb": int(m2[1]) / 1e9 if m2 else None}
        out[stem] = r
    (ROOT / "results" / "scores.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    (ROOT / "results" / "errors.md").write_text("# 誤りの一覧(自動生成)\n" + "\n".join(errors) + "\n", encoding="utf-8")
    for k, v in out.items():
        print(k, json.dumps({a: b for a, b in v.items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
