package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.VoiceActivityDetector

/**
 * 発話の検知と文字起こしの実装の組。
 *
 * 実装を決める場所は、ビルド種別ごとの `speechComponents()` だけにする。
 * - `src/debug`: 開発用の Fake
 * - `src/release`: まだ実装がないので null。Fake のコードは Release に入らない
 *
 * sherpa-onnx を足すときは `src/main` の共通の関数に移し、両方の `speechComponents()` と Fake を消す。
 */
class SpeechComponents(
    val voiceActivityDetector: VoiceActivityDetector,
    val transcriber: Transcriber,
    /** 本物の認識ではないとき true。画面と通知で開発用だと知らせる。 */
    val isDevelopment: Boolean,
)
