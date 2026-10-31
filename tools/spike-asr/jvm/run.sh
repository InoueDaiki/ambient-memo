#!/bin/bash
# Kotlin の検証を Mac の JVM で動かす。tools/spike-asr で実行する
# 例: mise exec -- bash jvm/run.sh --kind sensevoice --dir <モデル> --list .cache/jvm-in/list.tsv --out .cache/out/jvm.tsv
set -eu
C=.cache/jvm
if [ ! -f $C/spike.jar ] || [ jvm/SpikeMain.kt -nt $C/spike.jar ]; then
  kotlinc jvm/SpikeMain.kt -cp $C/sherpa-onnx-jvm-2.0.1.jar -include-runtime -d $C/spike.jar 2>/dev/null
fi
exec /usr/bin/time -l java -cp "$C/spike.jar:$C/sherpa-onnx-jvm-2.0.1.jar:$C/sherpa-onnx-native-lib-osx-aarch64-2.0.1.jar" ambient.spike.SpikeMainKt "$@"
