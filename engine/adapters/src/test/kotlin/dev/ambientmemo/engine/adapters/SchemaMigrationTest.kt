package dev.ambientmemo.engine.adapters

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.TodoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * 全バージョンからのマイグレーションを確かめる。
 *
 * スキーマを変えるときの手順は `docs/エンジン設計.md` の「スキーマの変更」を参照。
 */
class SchemaMigrationTest {
    @TempDir
    lateinit var dir: Path

    private val schema = AmbientMemoDatabase.Schema
    private val snapshotDirectory: Path = Path.of(System.getProperty("ambientmemo.schemaDir"))

    private fun snapshot(version: Long): Path = snapshotDirectory.resolve("$version.db")

    private fun seed(version: Long): String? =
        javaClass.getResource("/migration-seeds/$version.sql")?.readText()

    @Test
    fun `すべての版にスナップショットと種データがある`() {
        val versions = 1..schema.version

        versions.forEach { version ->
            assertTrue(Files.exists(snapshot(version))) { "版 $version の .db がありません。generateSchema を実行してください" }
            assertTrue(seed(version) != null) { "版 $version の種データ migration-seeds/$version.sql がありません" }
        }
        val snapshots = Files.list(snapshotDirectory).use { files ->
            files.map { it.name }.filter { it.endsWith(".db") }.toList()
        }
        assertEquals(versions.map { "$it.db" }.toSet(), snapshots.toSet())
    }

    @TestFactory
    fun `すべての版から現在の版へ移行できる`(): List<DynamicTest> =
        (1..schema.version).map { version ->
            dynamicTest("版 $version から") {
                SchemaMigrationChecker(schema, dir).check(snapshot(version), seed(version)!!, version)
            }
        }

    @Test
    fun `開くときに古い版の DB を移行する`() {
        val path = dir.resolve("old.db")
        Files.copy(snapshot(1), path)
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            seedStatements(seed(1)!!).forEach { driver.execute(null, it, 0) }
            driver.execute(null, "PRAGMA user_version = 1", 0)
        }

        openTestDriver(path).use { driver ->
            val rows = AmbientMemoDatabase(driver).utteranceQueries.listOverlapping(Long.MAX_VALUE, 0).executeAsList()

            assertTrue(rows.isNotEmpty())
        }
    }

    @TestFactory
    fun `移行した話者の特徴量は長さを持ち、まとめ直しと統計に使える`(): List<DynamicTest> =
        (3L..<schema.version).map { version ->
            dynamicTest("版 $version から") {
                val path = dir.resolve("speaker-$version.db")
                Files.copy(snapshot(version), path)
                JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                    seedStatements(seed(version)!!).forEach { driver.execute(null, it, 0) }
                    schema.migrate(driver, version, schema.version).value
                    val speakers = SqliteSpeakerRepository(AmbientMemoDatabase(driver), Dispatchers.Unconfined)
                    runTest {
                        val stored = speakers.listEmbeddings("fake-1", 10)
                        val lengths = stored.map { it.sampleCount to it.voicedMillis }.toSet()
                        assertEquals(setOf(3 to 6_000L, 1 to 2_000L), lengths)
                        assertEquals(null, speakers.findSelfAdaptation("fake-1"))
                        assertTrue(speakers.summarize(0).isEmpty())
                    }
                }
            }
        }

    @TestFactory
    fun `移行した ToDo は未完了のまま読めて、完了できる`(): List<DynamicTest> =
        (2L..3L).map { version ->
            dynamicTest("版 $version から") {
                val path = dir.resolve("todo-$version.db")
                Files.copy(snapshot(version), path)
                JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                    seedStatements(seed(version)!!).forEach { driver.execute(null, it, 0) }
                    driver.execute(null, "PRAGMA user_version = $version", 0)
                }

                openTestDriver(path).use { driver ->
                    val repository = SqliteMemoRepository(AmbientMemoDatabase(driver), Dispatchers.Unconfined)
                    runTest {
                        val todos = repository.listTodos(memoId = 1)
                        assertTrue(todos.isNotEmpty())
                        assertTrue(todos.all { it.status != TodoStatus.DONE && it.completedAtMillis == null })

                        repository.completeTodo(todos.first().id, 10_000)

                        val completed = repository.listTodos(memoId = 1).first()
                        assertEquals(TodoStatus.DONE, completed.status)
                        assertEquals(10_000L, completed.completedAtMillis)
                    }
                }
            }
        }

    @Test
    fun `完了の前の状態を、取り消しのために残す`() {
        openTestDriver(dir.resolve("from-status.db")).use { driver ->
            val database = AmbientMemoDatabase(driver)
            database.memoQueries.insert(1, 2, null, null, "complete", "fake-1", 3)
            database.todoQueries.insert(1, "作業", "todo", null, "candidate", 3)

            database.todoQueries.complete(4, 1)

            val row = database.todoQueries.listByMemo(1).executeAsOne()
            assertEquals("done", row.status)
            assertEquals("candidate", row.completed_from_status)
            assertEquals(4L, row.completed_at_ms)
        }
    }

    // ここから下は、チェック自体が壊れていないことを、次の版があるものとして確かめる

    private val addColumn = "ALTER TABLE utterance ADD COLUMN note TEXT"

    private fun checkFromCurrentVersion(next: SqlSchema<QueryResult.Value<Unit>>, seedSql: String = currentSeed()) {
        SchemaMigrationChecker(next, dir).check(snapshot(schema.version), seedSql, schema.version)
    }

    private fun currentSeed() = seed(schema.version)!!

    @Test
    fun `正しい移行は通る`() {
        val next = SchemaWithNextVersion(schema, addColumn) { driver -> driver.execute(null, addColumn, 0) }

        checkFromCurrentVersion(next)
    }

    @Test
    fun `移行が構造を変え忘れると失敗する`() {
        val next = SchemaWithNextVersion(schema, addColumn) { }

        assertThrows(AssertionError::class.java) {
            checkFromCurrentVersion(next)
        }
    }

    @Test
    fun `移行が行を失うと失敗する`() {
        val next = SchemaWithNextVersion(schema, addColumn) { driver ->
            driver.execute(null, addColumn, 0)
            driver.execute(null, "DELETE FROM utterance", 0)
        }

        assertThrows(AssertionError::class.java) {
            checkFromCurrentVersion(next)
        }
    }

    @Test
    fun `種データのないテーブルがあると失敗する`() {
        assertThrows(AssertionError::class.java) {
            checkFromCurrentVersion(schema, seedSql = "")
        }
    }

    /** 現在のスキーマに [change] を足した、次の版のスキーマ。 */
    private class SchemaWithNextVersion(
        private val current: SqlSchema<QueryResult.Value<Unit>>,
        private val change: String,
        private val migration: (SqlDriver) -> Unit,
    ) : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = current.version + 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            current.create(driver).value
            driver.execute(null, change, 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            migration(driver)
            return QueryResult.Unit
        }
    }
}
