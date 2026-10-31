# 検証: 日本語の VAD、ASR、話者識別

sherpa-onnx で使えるモデルを Mac 上で測り、第一候補を選んだ記録。実機(Pixel)では測っていない。再現の手順は末尾にある。

## 推薦

| 領域 | 第一候補 | 設定 | 代わり |
|---|---|---|---|
| VAD | `silero_vad.onnx`(644 KB、MIT) | しきい値 0.5、区切りの無音 0.5〜0.8 秒、最大 20 秒 | `silero_vad_v5.onnx` |
| ASR | `sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01` の int8(160 MB、Apache-2.0)を発話区間ごとに使う | 区間の前後に実音声と無音を 0.3 秒ずつ足す | `sensevoice-2024`、`parakeet-ja` |
| 話者 | `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced`(28 MB、Apache-2.0) | コサイン類似度 0.6 から始め、端末で調整する | `3dspeaker_speech_eres2netv2`(71 MB) |
| 実行基盤 | sherpa-onnx 2.0.1 | Mac の JVM で動作を確認 | なし |

理由は次のとおり。

- ASR は、この候補が CER、速度、容量、メモリの釣り合いで最もよい。`parakeet-ja` より CER は少し高いが 95% 区間は重なり、メモリは約 3 分の 1、実時間比は約 4 分の 1 で済む。`moonshine` は軽いが雑音に弱く、ライセンスの制約もある
- 日本語のストリーミングモデルは存在するが、CER が第一候補のほぼ 2 倍で、メモリも増える。発話区間ごとの処理でよいので採らない
- VAD は取りこぼしがなく、語頭の遅れが小さい
- 話者は 28 MB でも 3 秒の発話で EER 0.40% だった。Mac の 1 スレッドで実時間比 0.005

**判断が要る点が 1 つある。** 第一候補の ASR は ReazonSpeech で学習している。モデルのライセンスは Apache-2.0 で、ログインなしで取得できる。一方、ReazonSpeech のデータセットには著作権法第 30 条の 4 の目的に限る条件があり、`docs/設計メモ.md` はこのデータを後回しにしている。データセットのページに学習済みモデルの扱いの記載はなかった。ReazonSpeech 由来のモデルを避ける場合は `sensevoice-2024`(FunASR のモデルライセンス)が次の候補になる。その場合、clean の CER は 7.4%、VAD から通した CER は 7.7% で、メモリは約 1 GB になる。

## 比較表

CER は「表記 / 読み」の順で、句読点を除いて計算した。読みは unidic-lite でカタカナに揃えた値で、漢字とかなの表記ゆれの影響が小さい。実時間比は Mac の 1 スレッド、ピーク RSS は 2 スレッドの Python プロセス全体(土台は約 75 MB)。`VAD→ASR` は区間の前後を足した場合。

| モデル | 種別 | clean | SNR 5 dB | 残響 + 10 dB | VAD→ASR clean | 実時間比 | 容量 MB | ピーク RSS MB | ライセンス |
|---|---|---|---|---|---|---|---|---|---|
| **zipformer-reazon** | 区間 | 6.4 / 2.7 | 7.2 / 3.4 | 7.3 / 3.6 | 8.7 / 3.6 | 0.012 | 160 | 723 | Apache-2.0(ReazonSpeech で学習) |
| zipformer-reazon-ja-en | 区間 | 7.0 / 3.0 | 7.6 / 3.5 | 8.1 / 4.2 | 8.7 / 3.7 | 0.009 | 210 | 526 | 確認できず(元のページが 401) |
| parakeet-ja | 区間 | 5.9 / 2.2 | 6.2 / 2.5 | 7.0 / 3.1 | 7.4 / 2.6 | 0.052 | 656 | 2068 | CC-BY-4.0(ReazonSpeech で学習) |
| sensevoice-2024 | 区間 | 7.4 / 3.4 | 9.6 / 5.6 | 9.8 / 5.5 | 7.7 / 3.3 | 0.027 | 240 | 1048 | FunASR モデルライセンス 1.1 |
| moonshine-base-ja | 区間 | 7.7 / 2.4 | 10.2 / 4.8 | 12.3 / 6.5 | 8.0 / 2.9 | 0.018 | 141 | 626 | Moonshine Community(商用は登録が必要) |
| moonshine-tiny-ja | 区間 | 8.7 / 3.2 | 14.3 / 7.6 | 19.3 / 11.8 | 8.6 / 3.6 | 0.011 | 72 | 409 | 同上 |
| whisper-turbo(60 発話) | 区間 | 7.0 / 2.5 | - | - | - | 0.404 | 1037 | 3096 | MIT |
| cohere-transcribe(60 発話) | 区間 | 7.0 / 1.6 | - | - | - | 0.114 | 2888 | 5036 | Apache-2.0 |
| qwen3-asr-0.6b | 区間 | 11.8 / 5.7 | - | - | - | 0.155 | 987 | 2990 | Apache-2.0 |
| whisper-small | 区間 | 13.3 / 6.2 | - | - | - | 0.238 | 376 | 1540 | Apache-2.0 |
| nemotron35-1120ms | ストリーミング | 12.1 / 7.0 | 16.8 / 11.1 | 18.9 / 13.2 | 12.8 / 6.6 | 0.098 | 682 | 2100 | OpenMDW-1.1 |
| zipformer-multi-streaming | ストリーミング | 12.8 / 6.9 | 15.6 / 10.2 | 21.1 / 14.6 | 13.2 / 6.3 | 0.050 | 339 | 853 | 確認できず |

`sensevoice-2025`(2025-09-09)は JVS で CER 81.5% だった。言語を `ja` に指定しても広東語(`yue`)と判定されるので、候補から外した。詳しい値は末尾の表にある。

## 分かったこと

### ASR

- **区間の前後に音が要る。** VAD の区間をそのまま渡すと、zipformer-reazon の CER は 25.3% まで悪化した。前後に実音声と無音を 0.3 秒ずつ足すと 8.7% に戻った。無音だけを 0.5 秒足した場合は 11.0%。実音声を足すには、VAD の直前の音声をメモリのリングバッファに持つ必要がある。音声をディスクに書かない方針とは矛盾しない
- **区切りは長めがよい。** 区切りの無音を 0.2 秒にすると CER は 10.7%、0.8 秒なら 7.9% だった
- **雑音への強さに差がある。** ReazonSpeech で学習したモデルは SNR 5 dB でも CER の悪化が小さい。moonshine-tiny は残響で CER が 2 倍以上になった
- **雑音だけの区間でも文字が出る。** VAD が誤検知した 225 区間のうち、zipformer-reazon はすべてで文字を出した。平均 1.8 文字と短いが、DB に書く前の除去が要る。moonshine は平均 9〜10 文字、nemotron は 0 文字だった
- ストリーミングの最初の部分結果は、ファイルの先頭から平均 1.2〜1.4 秒の位置で出た。JVS の発話の前には無音が入っているので、発話の開始からの遅れではない

### VAD

- `silero_vad.onnx` はしきい値 0.5 で、clean から 0 dB まで発話の取りこぼしが 0 件だった。発話は各条件 100 件余りなので、0 件でも 0% を保証する数ではない
- `silero_vad.onnx` と `silero_vad_v4.onnx` は全指標が同じだった。配布物の版の表記は確認していない
- v5 は ESC-50 の突発音での誤検知がほぼ 0 件と優秀だが、10 dB での語頭の遅れが p95 で 0.66 秒ある(`silero_vad.onnx` は 0.11 秒)。VAD から ASR まで通した CER は、v5 の 8.9 / 9.1% に対し `silero_vad.onnx` は 8.7 / 7.9%
- TEN-VAD はしきい値 0.5 の 0 dB で 8.2% を取りこぼした。リポジトリのライセンスも GitHub の判定では不明(NOASSERTION)だった
- 環境音だけでの誤検知は平均 79 回/時だった。ほとんどは会議室(OMEETING)とバス(TBUS)で出ている。会議室の録音には本物の話し声が入っているので、誤検知と言い切れない。バスの音の中身は確認していない。残りの 10 環境は 0〜36 回/時だった
- ESC-50 の突発音は、クリップごとに音量を揃えて連続で流した。`silero_vad.onnx` で 82 回/時。音の密度が実生活より高いので、実際の頻度ではない

### 話者

- 自分かそれ以外かを見分ける EER は、CAM++ advanced で全長 0.22%、3 秒 0.40%、1.5 秒 1.40%、SNR 10 dB の 3 秒 1.78%、残響 + 10 dB の 3 秒 3.60%
- **しきい値は持ち越しにくい。** 前半 50 話者で他人の誤受入率が 1% になるしきい値(類似度 0.605)を決めても、後半 50 話者では 4.03% になった。ERes2NetV2 でも 1.70% だった。端末で自分の声を使って調整する前提にする
- sherpa-onnx の配布物の `wespeaker_en_voxceleb_CAM++_LM.onnx` は EER 44.7% で、見分けられなかった。原因は調べていない。同じ系列の resnet34 も雑音に弱い(SNR 10 dB の 3 秒で EER 21.3%)。WeSpeaker の英語モデルは採らない
- DER(合成会話、参考値)は、VAD の区切りを 0.2 秒にした発話区間ごとの処理で clean 12.4%(collar 0.25 秒で 3.8%)だった。sherpa-onnx の `OfflineSpeakerDiarization` は 27.3%(14.7%)で、発話の交代を区切れる場合は発話区間ごとの処理のほうがよかった。ただし、この合成会話は発話の間が 0.2 秒より長いことが多い
- 区切りが 0.5 秒だと 29.6% に悪化する。1 つの区間に 2 人が入るため。SNR 15 dB では 0.2 秒でも 44.6% と大きく悪化した。雑音の中の短い区間の扱いは課題として残る
- ASR は区切りが長いほうがよく、話者は短いほうがよい。そのため、ASR の区間と話者の区間を分ける必要がある。たとえば ASR の区間の中で窓をずらして embedding を取る方法だが、これは測っていない

## Mac の JVM からの利用

動く。`tools/spike-asr/jvm/SpikeMain.kt` で、VAD、ASR、embedding を Kotlin から通した。

- `sherpa-onnx-jvm-2.0.1.jar` と `sherpa-onnx-native-lib-osx-aarch64-2.0.1.jar` をクラスパスに置くだけで、ネイティブライブラリは `LibraryUtils.load()` が読み込む。jar のクラスは Java 8 互換で、ルートの JDK 17 で動く
- 60 発話で Python と比べると 59 発話の出力が同一で、CER は JVM 6.70%、Python 6.76%。差の原因は入力の WAV を 16 ビットに丸めたことと考えられるが、確かめてはいない
- JVM で VAD、ASR、embedding を通した実時間比は 0.014(2 スレッド)、プロセスのピーク RSS は 931 MB

`domain` とは次の形でつなげる。

- `SpikeMain.kt` の `SpeechDetector`、`Transcriber`、`SpeakerEmbedder` は sherpa-onnx の型を含まないので、そのまま `domain` の境界にできる。設計メモの `Transcriber` と同じ考え方
- `tools/eval` は、`domain` と JVM 用の実装を依存に持つ Kotlin/JVM のモジュールにする。WAV と正解から CER を計算する部分は `tools/spike-asr` の Python から移す
- **注意: JVM の jar と Android の AAR は API が違う。** どちらもパッケージは `com.k2fsa.sherpa.onnx` だが、jar は Java の Builder、AAR は `AssetManager` を受ける Kotlin の data class。実装の共有はできないので、`adapters` は JVM 用と Android 用の薄い実装に分け、同じ契約テストを流す。2 つを同じクラスパスに置かないようにする
- AAR の arm64 の `.so` はすべて 16 KB 境界に揃っていた。JNI 込みの配布物の大きさは arm64 で約 31 MB

## 計測条件

| 項目 | 内容 |
|---|---|
| 計測機 | Apple M5(性能コア 4 + 高効率コア 6)、メモリ 32 GB、macOS 26.6.2 |
| 実行環境 | sherpa-onnx 2.0.1(CPU)、Python 3.12.16、JDK 17.0.14、Kotlin 2.4.21 |
| ASR の評価データ | JVS の 100 話者から 300 発話(29.5 分)。乱数は固定。nonpara30 が 200、parallel100 が 100 |
| 雑音 | DEMAND の 12 環境を重ねて SNR 10 / 5 dB |
| 残響 | 指数減衰の雑音で作った合成の残響(RT60 0.5 秒)に SNR 10 dB の雑音を足したもの。実測の部屋の応答ではない |
| VAD の評価データ | 各条件 20 分の合成シーン(約 107 発話、音量を ±6 dB で変化)。雑音のみは DEMAND 1 時間と ESC-50 2.8 時間 |
| VAD の正解 | JVS の音素ラベル(sil, pau 以外)。ラベルのない話者は除いた |
| 話者の評価 | 各話者 5 発話で登録し、別の 10 発話を試験。21,000 試行 |
| DER | 180 秒前後の合成会話 30 本(2〜4 人、10% は発話が重なる)。10 本でしきい値を決め、残り 20 本で評価 |
| VAD→ASR | 80 発話を 1〜4 秒の間で並べた音声を、VAD で区切って ASR に渡す。出力を連結して正解と比べた |

速度の注意点。

- 速度とメモリは 60 発話を 1 モデルずつ流した値。この間のロードアベレージは 1〜4 で、完全な無負荷ではない
- CER の計測中は他の検証と重なり、ロードアベレージが 100 を超えた時間もある。CER は負荷の影響を受けないが、`results/asr/*__t2.json` の `rtf` は使わない
- 雑音の条件のピーク RSS は雑音データの読み込みを含むので使わない
- whisper-turbo、cohere-transcribe、omnilingual-300m は時間がかかるので 300 発話を止め、60 発話の値を載せた

## データとライセンス

| データ | 判断 | 内容 |
|---|---|---|
| Common Voice の日本語 | **使わず** | 2025 年 10 月から Mozilla Data Collective のみで配布。ダウンロード API が `401 Authentication required` を返す。データ自体は CC0 だが、ログインが要る |
| JVS | 使用 | 著者のページの公開リンクから、ログインと同意なしで取得。音声は個人利用と非商用の研究に使える。再配布は不可。文は JSUT 由来 |
| DEMAND | 使用 | Zenodo、CC BY 4.0 |
| ESC-50 | 使用 | GitHub、CC BY-NC 3.0。音は個人の検証にだけ使う |
| SPREDS-D2(NICT) | **使えず** | 台本のない対話で、CC BY 4.0 と検索結果にあった。代わりの候補として探したが、NICT のサイトがリニューアル工事中で、取得元の条件を確認できなかった。再開の時期は書かれていない |

Common Voice の代わりは JVS の音声と文で測った。JVS は読み上げなので、Common Voice や実際の会話より CER はよく出る。

JVS の Drive リンクには、大きいファイルで出るウイルススキャン不可の確認画面がある。規約への同意やログインではないので、`fetch.py` はこの確認だけを通す。

モデルについて。

- 取得元はすべて sherpa-onnx の GitHub Releases。中のスクリプトは実行していない
- `cohere-transcribe` と pyannote の分割モデルは、Hugging Face の元のページでは連絡先の登録が要る。sherpa-onnx の配布物はログインなしで取得でき、ライセンスは Apache-2.0 と MIT
- 第一候補の話者モデルと VAD の配布元のライセンスは、ModelScope と GitHub で確認した
- `zipformer-reazon-ja-en` と `zipformer-multi-streaming`(PengChengStarling)はライセンスを確認できなかった
- Moonshine の日本語モデルは MIT ではなく Moonshine Community License。個人利用は無償で、商用は登録が要る。配布時の表示義務もある

## Mac と Pixel で差が出そうな点

数値は測っていないので、見積もりも書かない。

| 項目 | 差が出る理由 | 確かめ方 |
|---|---|---|
| 実時間比 | M5 は Pixel の CPU より速いと考えられる。Mac の値をそのまま Pixel の値として使わない | 同じモデルを Pixel で測る |
| スレッド数 | Mac では zipformer-reazon は 1 スレッドで十分だった。コア構成が違うので Pixel では別に決める | 1、2、4 スレッドで測る |
| 発熱と電池 | Mac では測れない。VAD は常時動き、誤検知の回数が ASR の電池消費に効く | 1 日の実測 |
| メモリ | `ru_maxrss` は Mac の計測値で、Android の PSS とは定義が違う。LLM と同時に載せるかどうかも効く | Pixel で PSS を測る |
| マイク | JVS はスタジオ収録。端末のマイク、ポケットの中、離れた話者では音が違う。`AudioRecord` の音源の選び方で AGC とノイズ抑制も変わる | 自分の録音で確かめる |
| モデルの配置 | Mac はファイルパス、Android は assets か内部ストレージ。160 MB を APK に入れるかどうかも決める必要がある | 実装時に確かめる |
| Android の API | AAR は JVM の jar と API が違い、この検証では AAR のコードを動かしていない | 実機で通す |
| NPU | sherpa-onnx の QNN は Qualcomm 向けで、Tensor の Pixel には使えないと考えられる。CPU 前提で評価した | 必要になったら調べる |

## 未検証

- Pixel での速度、メモリ、電池、発熱(依頼の範囲外)
- 実際の会話、独り言、早口、複数人の重なりでの CER。JVS は読み上げ
- Common Voice での CER。ログインが要るので使っていない
- SPREDS-D2 での CER。条件を確認できなかった
- 端末のマイクで録った音声での話者の識別。JVS の登録と試験は同じ収録環境
- 話者の窓ずらし、自分の声のしきい値の調整、雑音区間の文字の除去。どれも案で、測っていない
- whisper-turbo、cohere-transcribe の雑音下の CER。qwen3、whisper-small も同じ
- ストリーミングの部分結果の安定性と、発話開始からの遅れ
- 長時間動かしたときのメモリリーク
- `tools/eval` と `domain` の実際の結合。別のエージェントの骨組みを待つ

## 再現の手順

`tools/spike-asr/` で実行する。データとモデルは `.cache/` に入り、コミットされない。

```bash
mise trust mise.toml && mise install
mise exec -- uv sync
mise exec -- uv run python fetch.py            # 約 33 GB
mise exec -- uv run python run_all.py asr-accuracy 3
mise exec -- uv run python run_all.py asr-speed x
mise exec -- uv run python run_all.py vad
mise exec -- uv run python run_all.py speaker
mise exec -- uv run python run_all.py der
mise exec -- uv run python run_all.py e2e
mise exec -- uv run python summarize.py
mise exec -- uv run python jvm/prepare_inputs.py
mise exec -- bash jvm/run.sh --kind transducer --dir .cache/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01 \
  --list .cache/jvm-in/list.tsv --out .cache/out/jvm-list.tsv --threads 2
```

`asr-accuracy` は全モデルの全条件を流すので、この記録より範囲が広く、時間もかかる。集計の元は `tools/spike-asr/results/` にある。取得したファイルの SHA-256 は `results/fetch-lock.json` に記録した。

## 詳しい表

`summarize.py` の出力。

### 精度(CER %、表記 / 読み)

| モデル | 種別 | n | clean | snr10 | snr5 | far | clean の 95% 区間(表記) |
|---|---|---|---|---|---|---|---|
| parakeet-ja | offline | 300 | 5.9 / 2.2 | 5.8 / 2.2 | 6.2 / 2.5 | 7.0 / 3.1 | 5.0〜6.8 |
| zipformer-reazon | offline | 300 | 6.4 / 2.7 | 6.7 / 3.0 | 7.2 / 3.4 | 7.3 / 3.6 | 5.5〜7.4 |
| whisper-turbo | offline | 60 | 7.0 / 2.5 | - | - | - | 4.7〜9.8 |
| zipformer-reazon-ja-en | offline | 300 | 7.0 / 3.0 | 7.2 / 3.2 | 7.6 / 3.5 | 8.1 / 4.2 | 5.9〜8.0 |
| cohere-transcribe | offline | 60 | 7.0 / 1.6 | - | - | - | 4.6〜9.8 |
| sensevoice-2024 | offline | 300 | 7.4 / 3.4 | 8.2 / 4.1 | 9.6 / 5.6 | 9.8 / 5.5 | 6.3〜8.6 |
| moonshine-base-ja | offline | 300 | 7.7 / 2.4 | 8.4 / 3.1 | 10.2 / 4.8 | 12.3 / 6.5 | 6.5〜8.7 |
| moonshine-tiny-ja | offline | 300 | 8.7 / 3.2 | 10.1 / 4.3 | 14.3 / 7.6 | 19.3 / 11.8 | 7.5〜9.9 |
| qwen3-asr-0.6b | offline | 300 | 11.8 / 5.7 | - | - | - | 10.5〜13.0 |
| nemotron35-1120ms | online | 300 | 12.1 / 7.0 | 14.1 / 9.1 | 16.8 / 11.1 | 18.9 / 13.2 | 10.9〜13.4 |
| nemotron35-560ms | online | 300 | 12.2 / 6.9 | - | - | - | 11.1〜13.5 |
| nemotron35-160ms | online | 300 | 12.4 / 7.0 | - | - | - | 11.2〜13.7 |
| zipformer-multi-streaming | online | 300 | 12.8 / 6.9 | 14.7 / 9.5 | 15.6 / 10.2 | 21.1 / 14.6 | 11.3〜14.3 |
| whisper-small | offline | 300 | 13.3 / 6.2 | - | - | - | 11.6〜15.1 |
| whisper-base | offline | 300 | 20.3 / 10.6 | - | - | - | 18.6〜22.1 |
| omnilingual-300m | offline | 60 | 23.3 / 15.2 | - | - | - | 20.4〜26.3 |
| whisper-tiny | offline | 300 | 29.2 / 15.7 | - | - | - | 27.2〜31.3 |
| sensevoice-2025 | offline | 300 | 81.5 / 71.7 | - | - | - | 80.2〜82.6 |

### 速度とメモリ(60 発話、JVS clean)

| モデル | 容量 MB | 実時間比 1 / 2 / 4 スレッド | 遅延 p95 秒(2 スレッド) | 読み込み秒 | ピーク RSS MB(2 スレッド) |
|---|---|---|---|---|---|
| parakeet-ja | 656 | 0.052 / 0.037 / 0.034 | 0.37 | 0.7 | 2068 |
| zipformer-reazon | 160 | 0.012 / 0.012 / 0.014 | 0.13 | 0.5 | 723 |
| whisper-turbo | 1037 | 0.404 / 0.339 / 0.330 | 3.15 | 0.8 | 3096 |
| zipformer-reazon-ja-en | 210 | 0.009 / 0.009 / 0.011 | 0.09 | 0.3 | 526 |
| cohere-transcribe | 2888 | 0.114 / 0.099 / 0.104 | 1.00 | 2.3 | 5036 |
| sensevoice-2024 | 240 | 0.027 / 0.018 / 0.016 | 0.18 | 0.3 | 1048 |
| moonshine-base-ja | 141 | 0.018 / 0.017 / 0.020 | 0.20 | 0.2 | 626 |
| moonshine-tiny-ja | 72 | 0.011 / 0.011 / 0.013 | 0.12 | 0.1 | 409 |
| qwen3-asr-0.6b | 987 | 0.155 / 0.123 / 0.121 | 1.27 | 1.1 | 2990 |
| nemotron35-1120ms | 682 | 0.098 / 0.075 / 0.074 | 0.74 | 0.6 | 2100 |
| nemotron35-560ms | 682 | 0.153 / 0.118 / 0.117 | 1.18 | 0.6 | 2065 |
| nemotron35-160ms | 682 | 0.355 / 0.284 / 0.301 | 2.77 | 0.6 | 2066 |
| zipformer-multi-streaming | 339 | 0.050 / 0.058 / 0.072 | 0.57 | 1.2 | 853 |
| whisper-small | 376 | 0.238 / 0.226 / 0.231 | 2.49 | 0.5 | 1540 |
| whisper-base | 161 | 0.084 / 0.081 / 0.085 | 0.88 | 0.2 | 935 |
| omnilingual-300m | 366 | 0.148 / 0.092 / 0.068 | 0.95 | 0.3 | 1515 |
| whisper-tiny | 104 | 0.049 / 0.048 / 0.052 | 0.51 | 0.1 | 639 |

### VAD

| モデル | しきい値 | 発話の取りこぼし % clean/20/10/5/0 dB | フレーム取りこぼし % 10 dB | 誤検知 回/時(非音声区間 10 dB) | 環境音のみ 回/時 | 突発音 回/時 | 稼働率 % 環境音 | 語頭の遅れ p95 秒 10 dB | KB |
|---|---|---|---|---|---|---|---|---|---|
| silero_vad.int8 | 0.5 | 0.0/0.0/0.0/0.0/0.0 | 1.5 | 0 | 46 | 85 | 3.2 | 0.14 | 213 |
| silero_vad | 0.3 | 0.0/0.0/0.0/0.0/0.0 | 0.6 | 0 | 72 | 152 | 9.3 | 0.09 | 644 |
| silero_vad | 0.5 | 0.0/0.0/0.0/0.0/0.0 | 1.2 | 0 | 79 | 82 | 5.4 | 0.11 | 644 |
| silero_vad | 0.7 | 0.0/0.0/0.0/0.0/0.9 | 2.2 | 0 | 57 | 37 | 3.2 | 0.25 | 644 |
| silero_vad_v4 | 0.5 | 0.0/0.0/0.0/0.0/0.0 | 1.2 | 0 | 79 | 82 | 5.4 | 0.11 | 1808 |
| silero_vad_v5 | 0.3 | 0.0/0.0/0.0/0.0/0.9 | 2.0 | 0 | 78 | 1 | 11.8 | 0.17 | 2313 |
| silero_vad_v5 | 0.5 | 0.9/0.0/0.0/0.0/0.9 | 2.9 | 0 | 82 | 0 | 8.1 | 0.66 | 2313 |
| silero_vad_v5 | 0.7 | 0.9/0.0/0.0/0.0/2.7 | 4.1 | 0 | 75 | 0 | 5.1 | 0.79 | 2313 |
| ten-vad.int8 | 0.5 | 0.0/1.9/0.9/0.9/8.2 | 3.3 | 0 | 37 | 129 | 3.2 | 0.38 | 130 |
| ten-vad | 0.3 | 0.0/0.0/0.0/0.0/0.0 | 0.4 | 0 | 57 | 293 | 22.7 | 0.06 | 332 |
| ten-vad | 0.5 | 0.0/1.9/0.9/0.9/8.2 | 3.3 | 0 | 39 | 132 | 3.3 | 0.38 | 332 |
| ten-vad | 0.7 | 4.7/5.6/4.7/14.2/28.2 | 14.9 | 0 | 13 | 18 | 0.5 | 1.52 | 332 |

### VAD と embedding の速度(1 スレッド、雑音データなしの別プロセス)

| モデル | 実時間比 | ピーク RSS MB |
|---|---|---|
| 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced | 0.0053 | 207 |
| 3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common | 0.0274 | 583 |
| nemo_en_titanet_small | 0.0036 | 382 |
| silero_vad.int8 | 0.0034 | 120 |
| silero_vad | 0.0023 | 120 |
| silero_vad_v4 | 0.0026 | 127 |
| silero_vad_v5 | 0.0024 | 133 |
| ten-vad.int8 | 0.0025 | 122 |
| ten-vad | 0.0023 | 121 |

### 話者 embedding

| モデル | MB | EER % clean 全長 | 3 秒 | 1.5 秒 | 10 dB 3 秒 | 残響+10 dB 3 秒 | 後半話者の FRR %(FAR 目標 1%、clean 3 秒) | 同 FAR 実測 |
|---|---|---|---|---|---|---|---|---|
| 3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common | 71 | 0.10 | 0.20 | 1.00 | 1.20 | 2.61 | 0.20 | 1.70 |
| 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced | 28 | 0.22 | 0.40 | 1.40 | 1.78 | 3.60 | 0.20 | 4.03 |
| nemo_en_titanet_large | 101 | 0.30 | 0.50 | 2.89 | 1.70 | 6.60 | 0.00 | 3.42 |
| nemo_en_titanet_small | 40 | 0.50 | 0.59 | 3.60 | 2.51 | 6.30 | 0.00 | 4.39 |
| wespeaker_zh_cnceleb_resnet34_LM | 26 | 1.40 | 1.80 | 4.10 | 8.90 | 13.90 | 2.60 | 1.50 |
| wespeaker_en_voxceleb_resnet34_LM | 26 | 3.30 | 3.82 | 8.40 | 21.30 | 41.78 | 15.20 | 1.08 |
| wespeaker_en_voxceleb_CAM++_LM | 29 | 44.71 | 46.78 | 49.90 | 48.41 | 48.70 | 89.60 | 5.51 |

### DER(合成会話、参考値)

| embedding | 条件 | offline DER %(collar 0 / 0.25) | 実時間比 | app DER %(collar 0 / 0.25) | 自分の再現率 % | 他人を自分と誤る率 % |
|---|---|---|---|---|---|---|
| 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced | clean | 27.3 / 14.7 | 0.086 | 無音 0.5 秒: 29.6 / 16.1 | 94.9 | 7.9 |
| 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced | clean | 27.3 / 14.7 | 0.086 | 無音 0.2 秒: 12.4 / 3.8 | 93.4 | 0.7 |
| 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced | snr15 | 32.8 / 19.1 | 0.086 | 無音 0.5 秒: 51.5 / 32.4 | 89.0 | 13.0 |
| 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced | snr15 | 32.8 / 19.1 | 0.086 | 無音 0.2 秒: 44.6 / 27.4 | 89.3 | 9.7 |
| nemo_en_titanet_small | clean | 28.4 / 15.5 | 0.043 | 無音 0.5 秒: 31.4 / 17.7 | 88.9 | 6.6 |
| nemo_en_titanet_small | clean | 28.4 / 15.5 | 0.043 | 無音 0.2 秒: 16.4 / 7.3 | 84.0 | 1.0 |
| wespeaker_en_voxceleb_CAM++_LM | clean | 69.7 / 56.6 | 0.047 | 無音 0.5 秒: 73.8 / 61.4 | 44.7 | 32.6 |
| wespeaker_en_voxceleb_CAM++_LM | clean | 69.7 / 56.6 | 0.047 | 無音 0.2 秒: 65.9 / 59.5 | 17.5 | 15.9 |

### VAD から ASR まで(80 発話、CER %、表記 / 読み)

| モデル | 区間の前後 | VAD | 区切りの無音 | clean | SNR 10 dB | 区間数 clean / 10 dB | 雑音区間のうち文字が出た % | 平均文字数 |
|---|---|---|---|---|---|---|---|---|
| moonshine-base-ja | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 8.0 / 2.9 | 7.9 / 2.9 | 134 / 88 | 100 | 10.3 |
| moonshine-base-ja | 音声 0 + 無音 0 | silero_vad | 0.5 | 23.8 / 16.5 | 17.8 / 12.1 | 134 / 88 | 100 | 2.8 |
| moonshine-tiny-ja | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 8.6 / 3.6 | 8.3 / 2.8 | 134 / 88 | 98 | 8.8 |
| moonshine-tiny-ja | 音声 0 + 無音 0 | silero_vad | 0.5 | 17.2 / 11.0 | 17.1 / 12.8 | 134 / 88 | 97 | 3.4 |
| nemotron35-1120ms | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 12.8 / 6.6 | 13.3 / 7.3 | 134 / 88 | 0 | 0.0 |
| nemotron35-1120ms | 音声 0.0 + 無音 0.0 | silero_vad | 0.5 | 39.4 / 35.6 | 35.6 / 32.5 | 134 / 88 | 0 | 0.0 |
| parakeet-ja | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 7.4 / 2.6 | 6.5 / 2.1 | 134 / 88 | 66 | 1.7 |
| parakeet-ja | 音声 0 + 無音 0 | silero_vad | 0.5 | 11.4 / 4.5 | 7.5 / 2.9 | 134 / 88 | 64 | 1.6 |
| sensevoice-2024 | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 7.7 / 3.3 | 7.5 / 3.3 | 134 / 88 | 59 | 1.0 |
| sensevoice-2024 | 音声 0 + 無音 0 | silero_vad | 0.5 | 9.6 / 5.7 | 8.6 / 4.5 | 134 / 88 | 80 | 1.5 |
| zipformer-multi-streaming | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 13.2 / 6.3 | 11.2 / 5.6 | 134 / 88 | 71 | 1.3 |
| zipformer-multi-streaming | 音声 0 + 無音 0 | silero_vad | 0.5 | 30.1 / 26.1 | 21.9 / 17.8 | 134 / 88 | 68 | 1.0 |
| zipformer-reazon | 音声 0.3 + 無音 0.3 | silero_vad | 0.2 | 10.7 / 5.2 | 9.2 / 4.8 | 173 / 119 | 100 | 1.8 |
| zipformer-reazon | 音声 0.3 + 無音 0.3 | silero_vad | 0.8 | 7.9 / 3.4 | 7.7 / 3.7 | 89 / 82 | 100 | 1.8 |
| zipformer-reazon | 音声 0.3 + 無音 0.3 | silero_vad_v5 | 0.5 | 8.9 / 3.9 | 9.1 / 3.9 | 126 / 124 | 100 | 1.9 |
| zipformer-reazon | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 8.7 / 3.6 | 7.9 / 3.5 | 134 / 88 | 100 | 1.8 |
| zipformer-reazon-ja-en | 音声 0.3 + 無音 0.3 | silero_vad | 0.5 | 8.7 / 3.7 | 7.3 / 3.2 | 134 / 88 | 100 | 1.9 |
| zipformer-reazon | 音声 0.0 + 無音 0.5 | silero_vad | 0.5 | 11.0 / 4.0 | 8.2 / 3.7 | 134 / 88 | 100 | 2.0 |
| zipformer-reazon | 音声 0 + 無音 0 | silero_vad | 0.5 | 25.3 / 22.0 | 19.1 / 15.8 | 134 / 88 | 100 | 1.7 |
