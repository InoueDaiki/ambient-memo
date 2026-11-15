package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.domain.Tuning
import dev.ambientmemo.engine.domain.TuningKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileTuningStoreTest {
    @TempDir
    lateinit var directory: File

    private val file get() = File(directory, "tuning.txt")

    @Test
    fun `ファイルがなければ既定値`() {
        assertEquals(Tuning.DEFAULT, FileTuningStore(file).load())
    }

    @Test
    fun `保存した値を別の読み手が読める`() {
        val tuning = Tuning.of(mapOf("maxProvisionalSpeakers" to 6.0, "quietGateDbfs" to -60.0))

        FileTuningStore(file).save(tuning)

        val loaded = FileTuningStore(file).load()
        assertEquals(tuning, loaded)
        assertEquals(6, loaded.speakerConfig().maxProvisionalSpeakers)
        assertEquals(-60.0, loaded[TuningKey.QUIET_GATE])
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `既定値に戻すとファイルを消す`() {
        val store = FileTuningStore(file)
        store.save(Tuning.of(mapOf("minTopicLines" to 2.0)))

        store.save(Tuning.DEFAULT)

        assertFalse(file.exists())
        assertEquals(Tuning.DEFAULT, store.load())
    }

    @Test
    fun `壊れたファイルは既定値として読む`() {
        file.writeText("selfThreshold=abc\n\u0000\u0001")

        assertEquals(Tuning.DEFAULT, FileTuningStore(file).load())
    }
}
