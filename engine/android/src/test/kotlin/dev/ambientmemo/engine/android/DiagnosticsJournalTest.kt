package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.CaptureChanged
import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.DiagnosticProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DiagnosticsJournalTest {
    @TempDir
    lateinit var directory: File

    private val clock = object : Clock {
        var now = 1_000L

        override fun nowMillis() = now
    }
    private val event = CaptureChanged(isListening = true, isInterrupted = false)
    private val week = 7L * 24 * 60 * 60 * 1_000

    private fun journal(process: DiagnosticProcess = DiagnosticProcess.MAIN) =
        DiagnosticsJournal(directory, process, clock)

    private fun exported(journal: DiagnosticsJournal): List<String>? =
        journal.export(File(directory, "out"), "HEAD", "FOOT")?.readLines()

    @Test
    fun `始める前は何も残さない`() {
        val journal = journal()

        journal.record(event)

        assertFalse(journal.status().isRecording)
        assertEquals(0, journal.status().sizeBytes)
        assertNull(exported(journal))
    }

    @Test
    fun `別のプロセスの記録も1つのファイルにまとめる`() {
        journal().start()
        journal().record(event)
        journal(DiagnosticProcess.ORGANIZE).record(event)

        val lines = checkNotNull(exported(journal()))

        assertEquals("HEAD", lines.first())
        assertEquals("FOOT", lines.last())
        assertEquals(4, lines.size)
        assertTrue(lines.any { "MAIN" in it })
        assertTrue(lines.any { "ORGANIZE" in it })
    }

    @Test
    fun `止めた後は残さないが書き出せる`() {
        val journal = journal()
        journal.start()
        journal.record(event)
        clock.now += 10

        journal.stop()
        journal.record(event)

        val status = journal.status()
        assertFalse(status.isRecording)
        assertEquals(1_010L, status.endsAtMillis)
        assertEquals(3, exported(journal)?.size)
    }

    @Test
    fun `止め忘れても7日で止まる`() {
        val journal = journal()
        journal.start()
        clock.now += week - 1
        assertTrue(journal.status().isRecording)

        clock.now += 1
        journal.record(event)

        assertFalse(journal.status().isRecording)
        assertEquals(2, exported(journal)?.size)
    }

    @Test
    fun `始め直すと前の記録を消す`() {
        val journal = journal()
        journal.start()
        journal.record(event)

        journal.start()

        assertEquals(0, journal.status().sizeBytes)
    }

    @Test
    fun `消すと書き出せなくなる`() {
        val journal = journal()
        journal.start()
        journal.record(event)

        journal.clear()

        assertNull(journal.status().startedAtMillis)
        assertNull(exported(journal))
    }
}
