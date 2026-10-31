#!/usr/bin/env python3
"""1つのモデルで評価セットを流し、生の出力を results/raw に保存する。

ピークメモリを測るため、推論プロセスを /usr/bin/time -l の下で動かす。
  LiteRT-LM: このスクリプトを --worker で起動し直す
  llama.cpp: llama-server を起動して HTTP で呼ぶ

使い方:
  python scripts/run_eval.py --model gemma-e2b --split test --backend cpu
"""
import argparse
import json
import os
import pathlib
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.request

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import prompts

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = ROOT / "results" / "raw"
THREADS = 4  # 公開されている Android の CPU ベンチマークに揃える
CTX = 4096
THINK_EXTRA = 1200  # 思考に使える分を出力の上限に足す
MAX_OUT = {"summary": 400, "todo": 400, "speakers": 300, "qa": 120}

MODELS = {
    "gemma-e2b": {"runtime": "litert", "file": "gemma-4-E2B-it.litertlm"},
    "gemma-e4b": {"runtime": "litert", "file": "gemma-4-E4B-it.litertlm"},
    "lfm-jp": {"runtime": "llama", "file": "LFM2.5-1.2B-JP-202606-Q8_0.gguf"},
    "qwen3-4b": {"runtime": "llama", "file": "Qwen3-4B-Q4_K_M.gguf", "no_think": True},
    "gemma-e2b-gguf": {"runtime": "llama", "file": "gemma-4-E2B_q4_0-it.gguf"},
    # 思考モードあり。充電中のバッチなら遅くてもよいので比べる
    "gemma-e2b-think": {"runtime": "litert", "file": "gemma-4-E2B-it.litertlm", "think": True},
    "gemma-e4b-think": {"runtime": "litert", "file": "gemma-4-E4B-it.litertlm", "think": True},
    "qwen3-4b-think": {"runtime": "llama", "file": "Qwen3-4B-Q4_K_M.gguf", "think": True},
}


class LiteRt:
    def __init__(self, cfg, backend):
        import litert_lm

        self.lm = litert_lm
        self.think = cfg.get("think", False)
        litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
        be = litert_lm.Backend.CPU(thread_count=THREADS) if backend == "cpu" else litert_lm.Backend.GPU()
        cache = ROOT / "models" / "cache"
        cache.mkdir(exist_ok=True)
        self.engine = litert_lm.Engine(
            str(ROOT / "models" / cfg["file"]), backend=be, max_num_tokens=CTX, cache_dir=str(cache), enable_benchmark=True
        )

    def generate(self, system, user, schema, max_tokens):
        lm = self.lm
        kw = {}
        if schema is not None:
            kw["constrained_decoding_config"] = lm.ConstrainedDecodingConfig(
                provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE
            )
        t0 = time.perf_counter()
        conv = self.engine.create_conversation(
            system_message=system,
            sampler_config=lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0, seed=1),
            max_output_tokens=max_tokens,
            **kw,
        )
        if self.think:
            kw_msg = {"thinking_config": lm.ThinkingConfig(enable_thinking=True)}
        else:
            kw_msg = {}
        try:
            args = {"response_format": lm.ResponseFormat.json(schema)} if schema is not None else {}
            msg = conv.send_message(user, **args, **kw_msg)
            wall = time.perf_counter() - t0
            info = conv.get_benchmark_info()
            text = message_text(msg)
            return {
                "text": text,
                "wall": wall,
                "ttft": info.time_to_first_token_in_second,
                "prompt_tokens": info.last_prefill_token_count,
                "gen_tokens": info.last_decode_token_count,
                "prompt_tps": info.last_prefill_tokens_per_second,
                "gen_tps": info.last_decode_tokens_per_second,
            }
        finally:
            conv.close()


def message_text(msg):
    content = msg["content"] if isinstance(msg, dict) else msg.content
    if isinstance(content, str):
        return content
    out = []
    for c in content:
        if isinstance(c, dict) and c.get("type", "text") != "text":
            continue
        out.append(c.get("text", "") if isinstance(c, dict) else getattr(c, "text", ""))
    return "".join(out)


class Llama:
    def __init__(self, cfg, backend, mem_path):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            self.port = s.getsockname()[1]
        self.cfg = cfg
        cmd = [
            "/usr/bin/time", "-l", "-o", str(mem_path),
            "llama-server", "-m", str(ROOT / "models" / cfg["file"]), "--host", "127.0.0.1",
            "--port", str(self.port), "-c", str(CTX), "-t", str(THREADS), "-tb", str(THREADS),
            "-ngl", "0" if backend == "cpu" else "99", "--jinja", "--no-webui", "--parallel", "1",
            "--no-slots", "--log-disable",
        ]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(240):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{self.port}/health", timeout=2)
                return
            except Exception:
                time.sleep(0.5)
                if self.proc.poll() is not None:
                    break
        raise RuntimeError("llama-server が起動しない")

    def generate(self, system, user, schema, max_tokens):
        if self.cfg.get("no_think"):
            user = user + " /no_think"
        body = {
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.0, "top_k": 1, "top_p": 1.0, "seed": 1,
            "max_tokens": max_tokens, "cache_prompt": False,
        }
        if self.cfg.get("no_think"):
            body["chat_template_kwargs"] = {"enable_thinking": False}
        if schema is not None:
            body["response_format"] = {"type": "json_schema", "json_schema": {"name": "out", "schema": schema}}
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/v1/chat/completions",
            data=json.dumps(body).encode(), headers={"Content-Type": "application/json"},
        )
        t0 = time.perf_counter()
        with urllib.request.urlopen(req, timeout=600) as r:
            d = json.load(r)
        wall = time.perf_counter() - t0
        tm = d["timings"]
        return {
            "text": d["choices"][0]["message"].get("content") or "",
            "finish": d["choices"][0].get("finish_reason"),
            "wall": wall,
            "ttft": tm["prompt_ms"] / 1000,
            "prompt_tokens": tm["prompt_n"],
            "gen_tokens": tm["predicted_n"],
            "prompt_tps": tm["prompt_per_second"],
            "gen_tps": tm["predicted_per_second"],
        }

    def close(self):
        # time の子の llama-server に届ける。time が使用量を書き出してから終わる
        subprocess.run(["pkill", "-TERM", "-P", str(self.proc.pid)], check=False)
        self.proc.wait(timeout=30)


def jobs(items, split):
    """(task, id, qindex, system, user, schema, max_tokens) を返す。"""
    target = [x for x in items if x["split"] == split]
    for it in target:
        yield ("summary", it["id"], None, prompts.SYSTEM, prompts.summary(it), None, MAX_OUT["summary"])
        u = prompts.todo(it)
        yield ("todo_free", it["id"], None, prompts.SYSTEM, u, None, MAX_OUT["todo"])
        yield ("todo_schema", it["id"], None, prompts.SYSTEM, u, prompts.TODO_SCHEMA, MAX_OUT["todo"])
        if it["speakers"]:
            u = prompts.speakers(it)
            yield ("speakers_free", it["id"], None, prompts.SYSTEM, u, None, MAX_OUT["speakers"])
            yield ("speakers_schema", it["id"], None, prompts.SYSTEM, u, prompts.SPEAKER_SCHEMA, MAX_OUT["speakers"])
        for i, q in enumerate(it["qa"]):
            yield ("qa", it["id"], i, prompts.SYSTEM, prompts.qa(it, q["q"], items), None, MAX_OUT["qa"])


def run(args, gen, out):
    items = yaml.safe_load((ROOT / "data" / "eval.yaml").read_text(encoding="utf-8"))
    only = set(args.tasks.split(",")) if args.tasks else None
    todo = [j for j in jobs(items, args.split) if not only or j[0] in only]
    with out.open("w", encoding="utf-8") as f:
        for n, (task, cid, qi, system, user, schema, mt) in enumerate(todo):
            if args.limit and n >= args.limit:
                break
            rec = {"task": task, "id": cid, "q": qi}
            if MODELS[args.model].get("think"):
                mt += THINK_EXTRA
            try:
                rec.update(gen(system, user, schema, mt))
            except Exception as e:  # 1件の失敗で全体を止めず、失敗として数える
                rec.update({"text": "", "error": f"{type(e).__name__}: {e}"[:400]})
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            print(n, task, cid, round(rec.get("wall", -1), 1), rec.get("error", ""), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, choices=MODELS)
    ap.add_argument("--split", default="test")
    ap.add_argument("--backend", default="cpu", choices=["cpu", "gpu"])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--tasks", default="", help="カンマ区切り。空なら全部")
    ap.add_argument("--tag", default="", help="出力ファイル名に足す(速度計測など)")
    ap.add_argument("--worker", action="store_true")
    args = ap.parse_args()
    cfg = MODELS[args.model]
    RAW.mkdir(parents=True, exist_ok=True)
    stem = f"{args.model}.{args.backend}.{args.split}{'.' + args.tag if args.tag else ''}"
    out, mem = RAW / f"{stem}.jsonl", RAW / f"{stem}.mem.txt"

    if cfg["runtime"] == "litert" and not args.worker:
        cmd = ["/usr/bin/time", "-l", "-o", str(mem), sys.executable, *sys.argv, "--worker"]
        sys.exit(subprocess.call(cmd))
    if cfg["runtime"] == "litert":
        t0 = time.perf_counter()
        be = LiteRt(cfg, args.backend)
        print("load", round(time.perf_counter() - t0, 1), flush=True)
        run(args, be.generate, out)
        # 終了処理の不具合でメモリの記録を失わないよう、engine は明示的に閉じない
        sys.stdout.flush()
        os._exit(0)
    be = Llama(cfg, args.backend, mem)
    try:
        run(args, be.generate, out)
    finally:
        be.close()


if __name__ == "__main__":
    main()
