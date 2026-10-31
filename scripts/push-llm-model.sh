#!/usr/bin/env bash
# LLM のモデルを、接続した端末の Debug ビルドの内部ストレージへ入れる。開発用。
#
# 使い方: scripts/push-llm-model.sh <モデルのファイル> [パッケージ]
# モデルは engine/.cache に置かない。mise run models:push がすべて端末へ送るので、聞き取りの確認のたびに数 GB を送ってしまう。
# 置き場所は ModelCatalog.ACTIVE_LLM に合わせる。端末では最初の整理のときに SHA-256 を確かめる
set -euo pipefail

file=${1:?モデルのファイルを指定してください}
package=${2:-dev.ambientmemo.app.dev}
id=$(basename "$file" .litertlm)-e6a3892a
adb=${ANDROID_HOME:?ANDROID_HOME がありません}/platform-tools/adb
temporary=/data/local/tmp/ambient-memo-llm

"$adb" shell rm -rf "$temporary"
"$adb" shell mkdir -p "$temporary"
"$adb" push "$file" "$temporary/" > /dev/null
"$adb" shell run-as "$package" mkdir -p "files/models/$id"
"$adb" shell run-as "$package" cp "$temporary/$(basename "$file")" "files/models/$id/"
"$adb" shell run-as "$package" chmod -R go-rwx files/models
"$adb" shell rm -rf "$temporary"
