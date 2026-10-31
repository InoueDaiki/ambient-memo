#!/bin/bash
# 品質の評価を流す。Mac の Metal GPU を使う。
# 最初は CPU 4 スレッドで始めたが、他の処理と同居した状態では1件に 200 秒近くかかり、
# 終わらないので切り替えた。CPU との差は gemma-e2b の CPU 版で確かめる。
# 速度とメモリは run_speed.sh で別に測る。
# 使い方(tools/spike-llm で): mise exec -- bash scripts/run_all.sh <ログの置き場所>
set -u
log=${1:-/tmp}
py=.venv/bin/python

run() {
  m=$1; shift
  "$py" scripts/run_eval.py --model "$m" --split test --backend gpu "$@" >"$log/llm-$m.log" 2>&1
  echo "done $m rc=$?"
}

# 思考なし: 全タスク
for m in lfm-jp gemma-e2b; do run "$m" & done
wait
for m in qwen3-4b gemma-e4b; do run "$m" & done
wait
run gemma-e2b-gguf

# 思考あり: 時間を抑えるため、自由形式の JSON を省く
t=summary,todo_schema,speakers_schema,qa
for m in gemma-e2b-think gemma-e4b-think; do run "$m" --tasks "$t" & done
wait
run qwen3-4b-think --tasks "$t"
