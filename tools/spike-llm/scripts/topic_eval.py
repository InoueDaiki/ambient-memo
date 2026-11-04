#!/usr/bin/env python3
"""話題の切れ目のプロンプトを評価データで流し、答えと採点を results/topics に残す。

プロンプトと答えの読み取りは、アプリ(engine/domain の OrganizePrompts.topics と parseTopicStarts)と同じにする。
始める前に、engine/spec のケースとプロンプトが一字一句同じことを確かめる。

答えはデータの本文を含みうるので、番号として読めた答えだけを残す。読めなかった答えは長さだけを残す。

プロンプトの案は VARIANTS に置く。"app" はアプリの今の文面で、engine/spec のケースと比べる。
案は調整用のデータ(--data dev)だけで比べ、決めた案だけを評価用で流す。

使い方:
  .venv/bin/python scripts/topic_data.py
  .venv/bin/python scripts/topic_data.py --dev
  .venv/bin/python scripts/topic_eval.py --model gemma-e4b --backend gpu --data dev --variant app
"""
import argparse
import json
import os
import pathlib
import re
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import prompts  # noqa: E402
import run_eval  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = ROOT.parent.parent
DATA = ROOT / "data" / "cache" / "topics"
OUT = ROOT / "results" / "topics"
SPEC = REPO / "engine" / "spec" / "organize-prompts.json"
SPEC_CASE = "話題の切れ目のプロンプトは行番号を付けて渡す"
MAX_OUTPUT_TOKENS = 64

# 最初にアプリへ入れた文面。調整の案はこれに足して作った
V1_HEAD = (
    "次の文字起こしを話題ごとに分けてください。行の先頭の数字は行番号です。\n"
    "\n"
    "- 新しい話題が始まる行の番号を、小さい順に半角のカンマ区切りで出力する\n"
    "- 最初の番号は 1 にする。話題が1つだけなら 1 だけを出力する\n"
    "- 相づちや言いよどみだけの行では話題を変えない\n"
    "- 番号だけを1行で出力する。説明は付けない\n"
    "\n"
    "例: 1〜4 行目が昼食の店の相談、5〜9 行目が週末の予定、10 行目から仕事の締め切りの話なら「1, 5, 10」\n"
    "\n"
    "文字起こし:\n"
)


SAME_TOPIC_RULE = (
    "- 話している対象(用事、予定、出来事、もの)が別のものに変わったときだけ、新しい話題にする\n"
    "- 同じ対象についての質問、詳しい話、感想、言い換えは、同じ話題に含める\n"
)
# 余分な切れ目を減らす案。調整用のデータで比べ、v4 をアプリに入れた(docs/検証-話題の切れ目.md)
VARIANTS = {
    "v1": V1_HEAD,
    # 何を話題の変化とするかを書く
    "v2": V1_HEAD.replace(
        "- 相づちや言いよどみだけの行では話題を変えない\n",
        SAME_TOPIC_RULE + "- 相づちや言いよどみだけの行では話題を変えない\n",
    ),
    # v2 に、迷ったら分けないことを足す
    "v3": V1_HEAD.replace(
        "- 相づちや言いよどみだけの行では話題を変えない\n",
        SAME_TOPIC_RULE + "- 相づちや言いよどみだけの行では話題を変えない\n- 迷ったら分けない\n",
    ),
    # v3 に、分けない例を足す
    "v4": V1_HEAD.replace(
        "- 相づちや言いよどみだけの行では話題を変えない\n",
        SAME_TOPIC_RULE + "- 相づちや言いよどみだけの行では話題を変えない\n- 迷ったら分けない\n",
    ).replace(
        "の話なら「1, 5, 10」\n",
        "の話なら「1, 5, 10」\n"
        "例: 1〜8 行目がずっと週末の予定の話で、途中で店や時間の細かい話になっても「1」\n",
    ),
}


# アプリの今の文面。engine/spec のケースと比べる
TOPICS_HEAD = VARIANTS["v4"]
VARIANTS["app"] = TOPICS_HEAD


def topics_prompt(lines: list[str], head: str = TOPICS_HEAD) -> str:
    return head + "\n".join(f"{i + 1} {line}" for i, line in enumerate(lines))


THINK_BLOCK = re.compile(r"<think>.*?(</think>|\Z)", re.S)
CODE_FENCE_LINE = re.compile(r"^\s*```.*$", re.M)
TOPIC_STARTS = re.compile(r"[0-9 \t\n\x0b\f\r,、，]+")
NUMBER = re.compile(r"[0-9]+")
TOPIC_WRAPPERS = "「」\"*# 　"
INT_MAX = 2**31 - 1


def parse_topic_starts(text: str, line_count: int) -> list[int] | None:
    """アプリの parseTopicStarts と同じ。`\\d` と `\\s` は Java と同じく ASCII だけにする。"""
    cleaned = CODE_FENCE_LINE.sub("", THINK_BLOCK.sub("", text)).strip()
    line = next((x.strip().strip(TOPIC_WRAPPERS) for x in cleaned.splitlines() if x.strip().strip(TOPIC_WRAPPERS)), None)
    if line is None or not TOPIC_STARTS.fullmatch(line):
        return None
    starts = [int(m) for m in NUMBER.findall(line) if int(m) <= INT_MAX and 1 <= int(m) <= line_count]
    return sorted(set(starts + [1]))


def check_against_spec() -> None:
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    case = next(c for c in spec["cases"] if c["name"] == SPEC_CASE)
    want = case["expected"]
    if topics_prompt(case["input"]["lines"]) != want["user"] or prompts.SYSTEM != want["system"]:
        sys.exit("プロンプトが engine/spec のケースと違う")
    if want["maxOutputTokens"] != MAX_OUTPUT_TOKENS:
        sys.exit("出力の上限が engine/spec のケースと違う")
    # organize-job.json の読み取りのケースと同じ結果になるか
    for text, count, expected in [
        ("1, 3, 5", 6, [1, 3, 5]),
        ("「3, 3, 0, 99」", 4, [1, 3]),
        ("話題は 2 つです。1, 3", 4, None),
        ("1", 4, [1]),
    ]:
        if parse_topic_starts(text, count) != expected:
            sys.exit(f"読み取りがアプリと違う: {text!r}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemma-e4b", choices=[m for m, c in run_eval.MODELS.items() if c["runtime"] == "litert"])
    ap.add_argument("--backend", default="gpu", choices=["cpu", "gpu"])
    ap.add_argument("--data", default="eval", choices=["eval", "dev"])
    ap.add_argument("--variant", default="app", choices=VARIANTS)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    check_against_spec()
    head = VARIANTS[args.variant]
    examples = [json.loads(x) for x in (DATA / f"{args.data}.jsonl").read_text(encoding="utf-8").splitlines()]
    if args.limit:
        examples = examples[: args.limit]
    out_dir = OUT / args.data
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{args.model}.{args.backend}.{args.variant}.jsonl"
    t0 = time.perf_counter()
    lm = run_eval.LiteRt(run_eval.MODELS[args.model], args.backend)
    print("load", round(time.perf_counter() - t0, 1), flush=True)
    with out.open("w", encoding="utf-8") as f:
        for n, e in enumerate(examples):
            rec = {"id": e["id"], "set": e["set"], "lines": len(e["lines"]), "gold": e["starts"]}
            try:
                r = lm.generate(prompts.SYSTEM, topics_prompt(e["lines"], head), None, MAX_OUTPUT_TOKENS)
                pred = parse_topic_starts(r["text"], len(e["lines"]))
                rec.update({"pred": pred, "wall": round(r["wall"], 2), "prompt_tokens": r["prompt_tokens"]})
                text = r["text"].strip()
                # 番号と区切りだけの答えだけを残す。ほかの文字はデータの本文を含みうる
                rec["output"] = text if TOPIC_STARTS.fullmatch(text) else f"<残さない答え {len(text)} 字>"
            except Exception as ex:  # 1件の失敗で全体を止めず、失敗として数える
                rec.update({"pred": None, "error": type(ex).__name__})
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            print(n, e["id"], rec.get("pred"), rec["gold"], rec.get("wall"), flush=True)
    sys.stdout.flush()
    # run_eval と同じく、終了処理の不具合を避けるため engine は明示的に閉じない
    os._exit(0)


if __name__ == "__main__":
    main()
