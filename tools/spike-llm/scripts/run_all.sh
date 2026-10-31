#!/bin/bash
# 品質の評価を流す。温度 0 で決定的なので並列でよい。速度は run_speed.sh で別に測る。
# 使い方(tools/spike-llm で): mise exec -- bash scripts/run_all.sh <ログの置き場所>
set -u
log=${1:-/tmp}
py=.venv/bin/python

run() {
  m=$1; shift
  "$py" scripts/run_eval.py --model "$m" --split test "$@" >"$log/llm-$m.log" 2>&1
  echo "done $m rc=$?"
}

# 思考なし: 全タスク
for m in gemma-e2b lfm-jp gemma-e2b-gguf; do run "$m" & done
wait
for m in gemma-e4b qwen3-4b; do run "$m" & done
wait

# 思考あり: 時間を抑えるため、自由形式の JSON を省く
t=summary,todo_schema,speakers_schema,qa
for m in gemma-e2b-think gemma-e4b-think qwen3-4b-think; do run "$m" --tasks "$t" & done
wait
