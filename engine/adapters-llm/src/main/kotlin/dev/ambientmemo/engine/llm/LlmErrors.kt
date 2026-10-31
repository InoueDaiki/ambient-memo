package dev.ambientmemo.engine.llm

/** LiteRT-LM 0.16.1 が文脈長を超えた入力に返す文言の一部。型では見分けられないので、版を上げたら確かめ直す。 */
private const val INPUT_TOO_LONG = "Input token ids are too long"

/** 実行基盤の例外の文言から、入力が文脈長を超えたことを見分ける。 */
fun isInputTooLong(message: String?): Boolean = message?.contains(INPUT_TOO_LONG) == true
