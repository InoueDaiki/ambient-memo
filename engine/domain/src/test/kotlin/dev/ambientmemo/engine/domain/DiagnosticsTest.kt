package dev.ambientmemo.engine.domain

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiagnosticsTest {
    private val request = LlmRequest("秘密のシステム", "秘密の文字起こし", 48)

    private class ScriptedLlm(private val reply: () -> String) : Llm {
        override val modelVersion = "fake"

        override suspend fun generate(request: LlmRequest) = reply()
    }

    @Test
    fun `1行のJSONに本文を含めない`() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        TimedLlm(ScriptedLlm { "秘密の出力" }, events::add).generate(request)

        val line = encodeDiagnostic(1_000, DiagnosticProcess.ORGANIZE, events.single())

        assertTrue(line.startsWith("""{"at":1000,"process":"ORGANIZE","event":"llm_generate","""))
        assertTrue(""""inputChars":15""" in line)
        assertTrue(""""outputChars":5""" in line)
        assertTrue(""""maxOutputTokens":48""" in line)
        assertFalse("秘密" in line)
    }

    @Test
    fun `生成に失敗しても時間を記録して例外をそのまま投げる`() = runTest {
        val events = mutableListOf<DiagnosticEvent>()

        assertThrows<IllegalStateException> {
            TimedLlm(ScriptedLlm { error("失敗") }, events::add).generate(request)
        }

        assertTrue((events.single() as LlmGenerated).isFailed)
    }

    @Test
    fun `欠落を保存してから長さを記録する`() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val saved = mutableListOf<CaptureGap>()
        val delegate = object : CaptureGapRepository {
            override suspend fun insert(gap: CaptureGap) {
                saved += gap
            }

            override suspend fun listOverlapping(fromMillis: Long, toMillis: Long) = saved.toList()
        }

        RecordingCaptureGaps(delegate, events::add)
            .insert(CaptureGap(100, 350, CaptureGapReason.PROCESSING_BACKLOG))

        assertEquals(1, saved.size)
        assertEquals(CaptureGapRecorded(CaptureGapReason.PROCESSING_BACKLOG, 250), events.single())
    }
}
