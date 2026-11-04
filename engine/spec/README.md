# エンジンの共通テストのデータ

Android(Kotlin)と iOS(Swift)のエンジンに、同じ入力を渡して同じ出力になるかを確かめるためのデータ。
どちらのテストも、ここにあるファイルを読んで流すだけにする。ケースを足すときはファイルに書き足し、両方の実装に効かせる。

- Kotlin: `engine/domain/src/test/kotlin/dev/ambientmemo/engine/domain/spec/`
- Swift: `engine/ios/Tests/EngineDomainTests/`

## ファイルの形

1 つの領域(関数や型のまとまり)を 1 つのファイルにする。名前は `<領域>.json`。

```json
{
  "area": "quiet-gate",
  "description": "静かな間は VAD を動かさない",
  "cases": [
    {
      "name": "静かなフレームは VAD に渡さない",
      "input": { "...": "..." },
      "expected": { "...": "..." }
    }
  ]
}
```

- `area` はファイル名と同じにする。実行役は `area` で、どの処理に渡すかを選ぶ
- `input` と `expected` の中身は領域ごとに決め、ファイルの `description` の下に `inputSchema` と `expectedSchema` の説明を文章で書く
- 実行役は `input` から処理を呼び、結果を JSON にして `expected` と比べる。比べ方は次の通り
  - オブジェクトはキーの集合と値を比べる。キーの順は問わない
  - 配列は順を含めて比べる
  - 数は差が `1e-6` 以内なら同じとみなす。整数も同じ比べ方にする
  - `null` と、キーがないことは区別する
- 例外を確かめるケースは `expected` を `{ "error": "<種類>" }` にする。種類は領域ごとに決め、文言は比べない
- 例外の後の状態も比べる流れの領域(`capture-pipeline`、`self-enrollment`)は、例外の種類を `failure` の項目に書き、ほかの項目と一緒に比べる

## 書き方の決まり

- 時刻はエポックからのミリ秒の整数で書く
- 音声は書かない。フレームは長さと大きさ(または VAD の判定)だけで表し、実行役が合成する
- 個人の会話や実在の人の名前を書かない。架空の文にする
- 実装の言語に依る挙動(コルーチンの取り消し、スレッド)はここで扱わず、それぞれの言語のテストに残す
- 列挙は Kotlin の名前の文字列で書く(`"PROCESSING_BACKLOG"` など)。とりうる値は各ファイルの `inputSchema` と `expectedSchema` にある
- 話者の特徴量は、数の配列か `{"size": 次元, "values": {"添字": 値}, "normalize": true}` の形で書く。書かない要素は 0 で、`normalize` が true なら長さ 1 にする。結果では 0 でない要素だけを `{"size", "values"}` の形で書く。数でない値は文字列 `"NaN"` にする。値は Float(32 ビット)として読み、計算も Float で行う
- 音声の Fake、ASR、話者の特徴量の Fake は、呼ばれた順の番号で応答を決める。形は `capture-pipeline.json` と `speaker-identifier.json` にある
- 話者と発話の記録は、メモリの Fake(Kotlin の `engine/domain/src/testFixtures/.../testing/Fakes.kt`)と同じ振る舞いにする。id は 1 から作った順に振る

## ファイルの一覧

| ファイル | 対象 |
|---|---|
| `assessed-due.json` | ToDo の期限の確度 `assessDue` |
| `calendar-date.json` | 暦の日付 `CalendarDate` |
| `capture-pipeline.json` | 音声から保存までの流れ `CapturePipeline` |
| `diagnostics.json` | 動作の記録の書式と、記録を足す `TimedLlm`、`RecordingCaptureGaps` |
| `engine-status.json` | エンジンの名前と API の版 |
| `llm-output-parser.json` | LLM の出力から ToDo と要約を読む `parseTodos`、`parseSummary` |
| `llm-title-parser.json` | LLM の出力からタイトルを読む `parseTitle` |
| `model-validation.json` | 発話、欠落、文字起こし、聞き取りの設定の値の検査 |
| `noise-text-filter.json` | 雑音の短い文字を除く `NoiseTextFilter` |
| `organize-job.json` | 整理ジョブ `OrganizeJob` と、今整理できる量 `organizeBacklog`。話題の切れ目の読み取り `parseTopicStarts` もここで確かめる。記録はメモリの Fake、LLM は依頼の種類と本文で応答を決める Fake |
| `organize-policy.json` | 整理を止める理由 `stopReason` |
| `organize-prompts.json` | 整理のプロンプト `OrganizePrompts`。要約と ToDo の文面は検証のスクリプトの出力と一字一句同じ |
| `quiet-gate.json` | 静かな間は VAD を動かさない `QuietGatedVoiceActivityDetector` |
| `segment-backlog.json` | 文字起こし待ちの置き場所 `SegmentBacklog` |
| `self-enrollment.json` | 自分の声の登録 `SelfEnrollment` |
| `speaker-consolidation.json` | 仮の話者のまとめ直し `SpeakerConsolidation` |
| `speaker-corrections.json` | 話者の手の修正 `SpeakerCorrections`、`RecentVoices` |
| `speaker-identifier.json` | 話者の判定 `SpeakerIdentifier` |
| `speaker-long-conversation.json` | 既定の設定での長い聞き取りの話者の増え方と育てた声 |
| `speaker-turns.json` | 聞き取りを通した、区間の中の話者の交代と行の分割 |
| `utterance-chunk.json` | 整理のまとまりと文字起こしの行 `splitIntoChunks`、`toTranscriptLine` |
| `utterance-segmenter.json` | 発話区間の切り出し `UtteranceSegmenter` |

## Kotlin の実行役

`engine/domain/src/test/kotlin/dev/ambientmemo/engine/domain/spec/SpecTest.kt` が、ファイルごとに `@TestFactory` でケースを名前の付いたテストにする。
`engine/domain/src/test/resources/spec` はこのディレクトリへのシンボリックリンクで、JSON をテストのリソースとして読む。
リソースにすると Gradle が JSON を入力として扱うので、JSON だけを直したときにもテストが流れ直す。
ファイル名と `area` が揃っていること、ケースの名前が重ならないこと、どの `area` にも実行役があることも確かめる。

## Kotlin のテストに残したもの

データで表すと言語に依る挙動を確かめられなくなるものと、本物の実装の契約を確かめるものは、Kotlin のテストに残した。Swift では同じ観点のテストを Swift の側に書く。

| テスト | 残した理由 |
|---|---|
| `CapturePipelineTest` の、特徴量と文字起こしの内側のタイムアウト(2 件) | `withTimeout` の `CancellationException` を処理全体のキャンセルと見分けるという、コルーチンに依る挙動 |
| `CapturePipelineTest` の、特徴量の途中のキャンセル、処理中と待ちと開いている発話のキャンセル、キャンセルの途中の欠落の保存の失敗(3 件) | コルーチンの取り消し |
| `CapturePipelineTest` の、追いつかないときに古い発話を捨てる | 文字起こしを止めている間に読み取りが進むという、処理の進み方に依る |
| `CapturePipelineTest` の、処理中も読み取りは止まらない | 仮想の時間で文字起こしを遅らせる、タイミングに依る |
| `CapturePipelineTest` の、WAV の発話を検知して保存する | WAV ファイルの読み取り(`WavAudioSource`)を含む結合テスト |
| `SpeakerTurnPipelineTest` の、窓の途中のキャンセル | コルーチンの取り消し |
| `SpeakerTurnPipelineTest` の、窓の音声が特徴量の後に 0 で塗りつぶされる | 渡した配列の参照を後から見る、メモリの扱いに依る |
| `SelfEnrollmentTest` の、キャンセルで音声を解放し前の登録を残す | コルーチンの取り消し |
| `SegmentBacklogTest` の、発話が届くまで待つ、待っている間に閉じると終わる(2 件) | コルーチンの待ち。待たずに返る場合は `segment-backlog.json` にある |
| `UtteranceSegmenterTest` の、渡したフレームの配列が書き換えられても発話が変わらない | 配列の参照を共有する言語の性質 |
| `SpeakerLongConversationTest` の、長い 2 人の会話で話者が増え続けない | `java.util.Random` の正規乱数に依る統計の確かめで、データにすると 64 次元の特徴量が 2,000 件になる |
| `ModelsTest` の、解放で音声を 0 で塗りつぶす、解放は何度呼んでもよい(2 件) | バッファのメモリの扱い。値の検査は `model-validation.json` にある |
| `FakeContractTests`(各 Fake とメモリの記録の契約テスト)、`testFixtures` の `PortContracts`、`RepositoryContracts` | Fake と本物の実装に同じ契約を流すためのもので、本物の実装(SQLite、sherpa-onnx など)が Kotlin にある |
| `WavAudioSourceTest` | テスト用の WAV の読み取りの実装そのもののテスト |

## Swift の実行役

`engine/ios/Tests/EngineDomainTests/SpecTests.swift` が、全ファイルのケースをパラメータ化した `@Test` の引数にし、`area: ケースの名前` の名前でテストにする。
JSON は Package.swift のリソースにせず、`#filePath` から相対でこのディレクトリを読む。
Foundation の `JSONSerialization` は文字列の中の U+FEFF を消し、`JSONDecoder` は数の書き方を残さないので、`SpecJSON.swift` の小さな読み取りで読み、数は書かれたままの文字列で持つ。
ファイル名と `area` が揃っていること、ケースの名前が重ならないこと、どの `area` にも実行役があることも Kotlin と同じく確かめる。

## 整理ジョブ

`organize-job.json` はメモリの Fake(`InMemoryMemoRepository`)で `OrganizeJob` を流す。
本物の SQLite に依るもの(書き込みの途中の失敗、開き直し、版 1 からの移行、同じまとまりを別のジョブが先に記録したとき)と、キャンセル、LLM の内側のタイムアウトは、`engine/adapters` の `OrganizeJobIntegrationTest` に残す。
