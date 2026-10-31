package dev.ambientmemo.engine.adapters

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapReason
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.testing.CaptureGapRepositoryContract
import dev.ambientmemo.engine.domain.testing.MemoRepositoryContract
import dev.ambientmemo.engine.domain.testing.SpeakerRepositoryContract
import dev.ambientmemo.engine.domain.testing.UtteranceRepositoryContract
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteUtteranceRepositoryContractTest : UtteranceRepositoryContract() {
    @TempDir
    lateinit var dir: Path

    private lateinit var driver: JdbcSqliteDriver

    override fun repository(): SqliteUtteranceRepository {
        driver = openTestDriver(dir.resolve("test.db"))
        return SqliteUtteranceRepository(AmbientMemoDatabase(driver))
    }

    @AfterEach
    fun closeDriver() = driver.close()
}

class SqliteCaptureGapRepositoryContractTest : CaptureGapRepositoryContract() {
    @TempDir
    lateinit var dir: Path

    private lateinit var driver: JdbcSqliteDriver

    override fun repository(): SqliteCaptureGapRepository {
        driver = openTestDriver(dir.resolve("test.db"))
        return SqliteCaptureGapRepository(AmbientMemoDatabase(driver))
    }

    @AfterEach
    fun closeDriver() = driver.close()
}

class SqliteSpeakerRepositoryContractTest : SpeakerRepositoryContract() {
    @TempDir
    lateinit var dir: Path

    private lateinit var driver: JdbcSqliteDriver

    override fun repository(): SqliteSpeakerRepository {
        driver = openTestDriver(dir.resolve("test.db"))
        return SqliteSpeakerRepository(AmbientMemoDatabase(driver))
    }

    @AfterEach
    fun closeDriver() = driver.close()
}

class SqliteMemoRepositoryContractTest : MemoRepositoryContract() {
    @TempDir
    lateinit var dir: Path

    private lateinit var driver: JdbcSqliteDriver

    override fun stores(): Stores {
        driver = openTestDriver(dir.resolve("test.db"))
        val database = AmbientMemoDatabase(driver)
        return Stores(SqliteUtteranceRepository(database), SqliteMemoRepository(database))
    }

    @AfterEach
    fun closeDriver() = driver.close()
}

class SqliteRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val path: Path
        get() = dir.resolve("test.db")

    @Test
    fun `閉じて開き直しても残っている`() = runTest {
        val utterance = NewUtterance(0, 100, "残す", null, 100)
        val gap = CaptureGap(100, 200, CaptureGapReason.PROCESSING_BACKLOG)
        openTestDriver(path).use { driver ->
            val database = AmbientMemoDatabase(driver)
            SqliteUtteranceRepository(database).insert(utterance)
            SqliteCaptureGapRepository(database).insert(gap)
        }

        openTestDriver(path).use { driver ->
            val database = AmbientMemoDatabase(driver)
            assertEquals("残す", SqliteUtteranceRepository(database).listOverlapping(0, 100).single().text)
            assertEquals(listOf(gap), SqliteCaptureGapRepository(database).listOverlapping(100, 200))
        }
    }

    @Test
    fun `自分は DB でも1人だけ`() {
        openTestDriver(path).use { driver ->
            val queries = AmbientMemoDatabase(driver).speakerQueries
            queries.insert("自分", "self", 1, 0)

            assertThrows(Exception::class.java) { queries.insert("もう1人", "self", 1, 0) }
        }
    }

    @Test
    fun `時刻が逆転した行は DB でも拒む`() {
        openTestDriver(path).use { driver ->
            val queries = AmbientMemoDatabase(driver).utteranceQueries

            assertThrows(Exception::class.java) { queries.insert(10, 9, "本文", null, 10, null, null) }
        }
    }

    @Test
    fun `未知の理由は黙って読み替えない`() = runTest {
        openTestDriver(path).use { driver ->
            val database = AmbientMemoDatabase(driver)
            database.captureGapQueries.insert(0, 100, "unknown")

            assertThrows(IllegalStateException::class.java) {
                runTest { SqliteCaptureGapRepository(database).listOverlapping(0, 100) }
            }
        }
    }
}
