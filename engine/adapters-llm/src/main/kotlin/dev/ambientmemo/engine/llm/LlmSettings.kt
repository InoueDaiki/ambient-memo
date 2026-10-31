package dev.ambientmemo.engine.llm

/** 実行基盤の設定。値は検証(`tools/spike-llm`)と同じにする。変えると検証の結果が当てはまらなくなる。 */
object LlmSettings {
    /** 入力と出力の合計の上限。`OrganizeConfig.maxChunkChars` はこれに収まる長さにしてある。 */
    const val MAX_NUM_TOKENS = 4096

    const val CPU_THREADS = 4

    const val TOP_K = 1
    const val TOP_P = 1.0
    const val TEMPERATURE = 0.0
    const val SEED = 1
}
