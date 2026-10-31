#!/bin/bash
# 速度を1モデルずつ順番に測る。品質の評価が終わり、マシンが空いているときに流す。
# CPU は 4 スレッド。GPU は Mac の Metal。Pixel の GPU や NPU の代わりにはならない。
# 使い方(tools/spike-llm で): mise exec -- bash scripts/run_speed.sh
set -u
py=.venv/bin/python
for b in cpu gpu; do
  for m in gemma-e2b gemma-e4b lfm-jp qwen3-4b gemma-e2b-gguf; do
    "$py" scripts/run_eval.py --model "$m" --split test --backend "$b" --tasks summary,todo_schema --limit 16 --tag speed 2>&1 | tail -1
    echo "done $m $b"
  done
done
