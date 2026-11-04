#!/usr/bin/env python3
"""話題の切れ目の評価データを、公開の対話コーパスから作る。

データ本体はコミットしない。取得元を固定し、SHA-256 を確かめてから data/cache/topics に作る。

- concat: RealPersonaChat(CC BY-SA 4.0)の別々の雑談から一部ずつ取り、1〜4 個つなぐ。
  つないだ所が話題の切れ目。挨拶と締めの発話は切れ目の手がかりになるので除く
- natural: JMultiWOZ(CC BY-SA 4.0)の1つの対話。ホテルから飲食店などへ、話の対象が移った所を切れ目にする。
  対象は各ターンの対話状態の active_domain から決める。対象が1つの対話は、切れ目のない例として使う

行はアプリと同じ `[HH:mm] 話者: 本文` にし、話者は「自分」と「話者1」の2つだけにする。
つないだ所で話者のラベルが変わると、話者の違いだけで切れ目が分かってしまうため。
1 つの例は、アプリのまとまりの上限(1,800 字、行ごとに改行の 1 字を足す)に収める。

評価用(eval.jsonl)と、プロンプトの調整用(dev.jsonl)を作る。調整用は別の乱数の種で作り、
評価用に使った対話を除く。調整は調整用だけで行い、評価用は決めた後に確かめるためだけに流す。

使い方: .venv/bin/python scripts/topic_data.py [--dev]
"""
import argparse
import hashlib
import json
import pathlib
import random
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "data" / "cache" / "topics"
OUT = CACHE / "eval.jsonl"
DEV_OUT = CACHE / "dev.jsonl"
SEED = 20261104
DEV_SEED = 20261105
MAX_CHUNK_CHARS = 1800

SOURCES = {
    "rpc": (
        "https://github.com/nu-dialogue/real-persona-chat/archive/refs/tags/v1.0.0.zip",
        "9b831fe9d117f8ab5b1ce23c122393a039e468eeeaf4e7fd0404d8873ac87140",
    ),
    "jmw": (
        "https://github.com/nu-dialogue/jmultiwoz/raw/a78dd334b907e12318b76bb23d0a9ed1498b8d15/dataset/JMultiWOZ_1.0.zip",
        "283dea36f5abeea8f016fea2bd4df4dfba67a40f89c4600e47ed701c57ea2bc2",
    ),
}

# 例の数。concat は話題の数ごと、natural は切れ目の有無ごと
CONCAT_PER_K = {1: 25, 2: 35, 3: 35, 4: 25}
NATURAL_MULTI = 90
NATURAL_SINGLE = 30
# concat の1つの話題の発話の数
SEGMENT_MIN, SEGMENT_MAX = 4, 10
# 挨拶と締めを除くため、対話の頭と終わりから飛ばす発話の数
SKIP_HEAD, SKIP_TAIL = 4, 4


def fetch(name: str) -> pathlib.Path:
    url, want = SOURCES[name]
    dest = CACHE / f"{name}.zip"
    if not dest.exists():
        print("取得", url, flush=True)
        part = dest.with_suffix(".part")
        with urllib.request.urlopen(url, timeout=120) as r, part.open("wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        part.rename(dest)
    got = hashlib.sha256(dest.read_bytes()).hexdigest()
    if got != want:
        sys.exit(f"SHA-256 不一致: {dest}\n  期待 {want}\n  実際 {got}")
    return dest


def line(minutes: int, speaker: str, text: str) -> str:
    # ASR の文字起こしには改行がないので、発話の中の改行は空白にする。改行が残ると行番号がずれる
    return f"[{minutes // 60 % 24:02d}:{minutes % 60:02d}] {speaker}: {' '.join(text.split())}"


def chars(lines: list[str]) -> int:
    """アプリの splitIntoChunks と同じ数え方。Python の len は UTF-16 ではないが、評価の文字はほぼ BMP に収まる。"""
    return sum(len(x) + 1 for x in lines)


def timed(rng: random.Random, turns: list[tuple[str, str]]) -> list[str]:
    """発話に 18 時台からの時刻を振る。間は 10〜40 秒で、アプリのまとまりの無音(5 分)には届かない。"""
    seconds = 18 * 3600 + rng.randrange(0, 1800)
    out = []
    for speaker, text in turns:
        out.append(line(seconds // 60, speaker, text))
        seconds += rng.randrange(10, 41)
    return out


def concat_examples(rng: random.Random, excluded: set) -> list[dict]:
    with zipfile.ZipFile(fetch("rpc")) as z:
        names = sorted(n for n in z.namelist() if "/dialogues/" in n and n.endswith(".json"))
        dialogues = [json.loads(z.read(n)) for n in names]
    # 本文が空の発話はアプリでは行にならないので、最初から除く
    usable = []
    for d in dialogues:
        utterances = [u for u in d["utterances"] if u["text"].strip()]
        if len(utterances) >= SKIP_HEAD + SKIP_TAIL + SEGMENT_MAX and ("rpc", d["dialogue_id"]) not in excluded:
            usable.append((d["dialogue_id"], utterances))
    rng.shuffle(usable)
    pool = iter(usable)
    examples = []
    for k, count in CONCAT_PER_K.items():
        made = 0
        while made < count:
            segments = []
            for _ in range(k):
                dialogue_id, utterances = next(pool)
                length = rng.randint(SEGMENT_MIN, SEGMENT_MAX)
                start = rng.randrange(SKIP_HEAD, len(utterances) - SKIP_TAIL - length + 1)
                window = utterances[start:start + length]
                # 窓の最初の話者を「自分」にする。どの話題にも自分がいる、端末の持ち主の会話にする
                me = window[0]["interlocutor_id"]
                turns = [("自分" if u["interlocutor_id"] == me else "話者1", u["text"]) for u in window]
                segments.append((dialogue_id, start, turns))
            turns = [t for _, _, seg in segments for t in seg]
            lines = timed(rng, turns)
            if chars(lines) > MAX_CHUNK_CHARS:
                continue
            starts, n = [], 1
            for _, _, seg in segments:
                starts.append(n)
                n += len(seg)
            examples.append({
                "id": f"concat-{k}-{made:02d}",
                "set": "concat",
                "topics": k,
                "source": [{"dialogue_id": d, "from": s, "count": len(seg)} for d, s, seg in segments],
                "lines": lines,
                "starts": starts,
            })
            made += 1
    return examples


def domains_of(turns: list[dict]) -> list[str | None]:
    """各ターンの話の対象。利用者の発話は、その後の係の発話の対話状態で決まる。"""
    out: list[str | None] = [None] * len(turns)
    for i, t in enumerate(turns):
        if t["speaker"] == "SYSTEM":
            domain = (t.get("dialogue_state") or {}).get("belief_state", {}).get("general", {}).get("active_domain")
            out[i] = domain
            if i > 0 and turns[i - 1]["speaker"] == "USER":
                out[i - 1] = domain
    # 挨拶など対象のない所は、前の対象(なければ次の対象)と同じ話題にする
    last = None
    for i, d in enumerate(out):
        if d is None:
            out[i] = last
        else:
            last = d
    first = next((d for d in out if d is not None), None)
    return [d if d is not None else first for d in out]


def natural_examples(rng: random.Random, excluded: set) -> list[dict]:
    with zipfile.ZipFile(fetch("jmw")) as z:
        dialogues = json.loads(z.read("JMultiWOZ_1.0/dialogues.json"))
    multi, single = [], []
    for key in sorted(dialogues):
        d = dialogues[key]
        if ("jmw", d["dialogue_id"]) in excluded:
            continue
        turns = [t for t in d["turns"] if t["utterance"].strip()]
        domains = domains_of(turns)
        if domains[0] is None:
            continue
        # 話者は利用者を「自分」、係を「話者1」にする
        spoken = [("自分" if t["speaker"] == "USER" else "話者1", t["utterance"]) for t in turns]
        # 切れ目は、対象が変わった最初の利用者の発話。係の発話で変わったときはその行
        starts = [1] + [i + 1 for i in range(1, len(domains)) if domains[i] != domains[i - 1]]
        entry = {"dialogue_id": d["dialogue_id"], "spoken": spoken, "starts": starts, "domains": domains}
        (multi if len(starts) > 1 else single).append(entry)
    rng.shuffle(multi)
    rng.shuffle(single)
    examples = []
    for group, count in (("multi", NATURAL_MULTI), ("single", NATURAL_SINGLE)):
        made = 0
        for entry in multi if group == "multi" else single:
            if made == count:
                break
            lines = timed(rng, entry["spoken"])
            if chars(lines) > MAX_CHUNK_CHARS:
                continue
            examples.append({
                "id": f"natural-{group}-{made:02d}",
                "set": "natural",
                "topics": len(entry["starts"]),
                "source": [{"dialogue_id": entry["dialogue_id"]}],
                "domains": [d for i, d in enumerate(entry["domains"]) if i + 1 in entry["starts"]],
                "lines": lines,
                "starts": entry["starts"],
            })
            made += 1
    return examples


def used_dialogues(path: pathlib.Path) -> set:
    used = set()
    for row in path.read_text(encoding="utf-8").splitlines():
        e = json.loads(row)
        for s in e["source"]:
            used.add(("rpc" if e["set"] == "concat" else "jmw", s["dialogue_id"]))
    return used


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dev", action="store_true", help="調整用を作る。先に評価用を作っておく")
    args = ap.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    if args.dev:
        if not OUT.exists():
            sys.exit("先に評価用を作る")
        rng, excluded, out = random.Random(DEV_SEED), used_dialogues(OUT), DEV_OUT
    else:
        rng, excluded, out = random.Random(SEED), set(), OUT
    examples = concat_examples(rng, excluded) + natural_examples(rng, excluded)
    with out.open("w", encoding="utf-8") as f:
        for e in examples:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    by_set: dict[str, list[dict]] = {}
    for e in examples:
        by_set.setdefault(e["set"], []).append(e)
    for name, items in by_set.items():
        lines = sum(len(e["lines"]) for e in items)
        boundaries = sum(len(e["starts"]) - 1 for e in items)
        print(name, len(items), "例", lines, "行", boundaries, "切れ目",
              "平均", round(sum(chars(e["lines"]) for e in items) / len(items)), "字")
    print("書き出し", out)


if __name__ == "__main__":
    main()
