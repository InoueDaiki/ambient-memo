#!/usr/bin/env bash
# engine/.cache/models のモデルを、接続した端末の Debug ビルドの内部ストレージへ入れる。
# 先に mise run models:fetch と、Debug ビルドのインストールを済ませる。
# run-as は debuggable なアプリにしか使えないので、Release には入れられない。
# 取得は開発機で行い、アプリには取得のコードも通信も持たせない。端末では最初の開始のときに SHA-256 を確かめる
set -euo pipefail

package=${1:-dev.ambientmemo.app.dev}
adb=${ANDROID_HOME:?ANDROID_HOME がありません}/platform-tools/adb
root=engine/.cache/models
temporary=/data/local/tmp/ambient-memo-models

"$adb" shell rm -rf "$temporary"
"$adb" shell mkdir -p "$temporary"
for directory in "$root"/*; do
  id=$(basename "$directory")
  "$adb" shell mkdir -p "$temporary/$id"
  for file in "$directory"/*; do
    "$adb" push "$file" "$temporary/$id/" > /dev/null
  done
  "$adb" shell run-as "$package" mkdir -p "files/models/$id"
  "$adb" shell run-as "$package" cp -r "$temporary/$id/*" "files/models/$id/"
done
"$adb" shell run-as "$package" chmod -R go-rwx files/models
"$adb" shell rm -rf "$temporary"
