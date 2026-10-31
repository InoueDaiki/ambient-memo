@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.llm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmErrorsTest {
    @Test
    fun `実行基盤の文脈長の超過を見分ける`() {
        // LiteRT-LM 0.16.1 の実際の文言
        val message = "Failed to call nativeSendMessage: INVALID_ARGUMENT: Input token ids are too long. " +
            "Exceeding the maximum number of tokens allowed: 12024 >= 4096"

        assertTrue(isInputTooLong(message))
    }

    @Test
    fun `それ以外の失敗は超過として扱わない`() {
        assertFalse(isInputTooLong("Failed to call nativeSendMessage: CANCELLED: Task cancelled"))
        assertFalse(isInputTooLong(null))
    }
}
