package dev.ambientmemo.engine.adapters

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.ResultSet

internal typealias Row = Map<String, Any?>

/**
 * ある版の DB に行を入れて現在の版へ移行し、次のことを確かめる。
 * - できた構造が、新規に作った DB と同じ
 * - 移行前の行と列の値がすべて残っている
 *
 * 列を意図して消す移行を足すときは、残す列の扱いをこの確認に足す。
 */
internal class SchemaMigrationChecker(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
    private val workDirectory: Path,
) {
    fun check(snapshot: Path, seedSql: String, fromVersion: Long) {
        val migratedPath = workDirectory.resolve("migrated-$fromVersion.db")
        Files.copy(snapshot, migratedPath, StandardCopyOption.REPLACE_EXISTING)

        JdbcSqliteDriver("jdbc:sqlite:$migratedPath").use { driver ->
            seedStatements(seedSql).forEach { driver.execute(null, it, 0) }
            val before = tables(driver).associateWith { readRows(driver, it) }
            assertTrue(before.isNotEmpty() && before.values.all { it.isNotEmpty() }) {
                "版 $fromVersion の種データがないテーブルがあります: ${before.mapValues { it.value.size }}"
            }

            schema.migrate(driver, fromVersion, schema.version).value

            assertEquals(freshStructure(), structure(driver), "版 $fromVersion から移行した構造が新規の DB と違います")
            before.forEach { (table, rows) ->
                val after = readRows(driver, table).map { row -> row.filterKeys { it in rows.first().keys } }
                assertEquals(rows, after, "版 $fromVersion から移行すると $table の行が変わります")
            }
        }
    }

    private fun freshStructure(): Map<String, String> {
        val path = workDirectory.resolve("fresh.db")
        Files.deleteIfExists(path)
        return JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            schema.create(driver).value
            structure(driver)
        }
    }
}

internal fun seedStatements(sql: String): List<String> = sql
    .lineSequence()
    .filterNot { it.trimStart().startsWith("--") }
    .joinToString("\n")
    .split(";")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/** テーブルとインデックスの定義。空白の違いでは差にしない。 */
internal fun structure(driver: SqlDriver): Map<String, String> =
    query(
        driver,
        "SELECT type || ' ' || name AS object, sql FROM sqlite_master " +
            "WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL",
    ).associate { row -> row["object"] as String to normalize(row["sql"] as String) }

private fun normalize(sql: String) =
    sql.replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").replace(" ,", ",")

internal fun tables(driver: SqlDriver): List<String> =
    query(driver, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .map { it["name"] as String }

private fun readRows(driver: SqlDriver, table: String): List<Row> = query(driver, "SELECT * FROM $table ORDER BY rowid")

/** 確認用の読み取り。列を決めない SELECT なので、ドライバーの接続を直接使う。 */
private fun query(driver: SqlDriver, sql: String): List<Row> {
    val connection = (driver as JdbcSqliteDriver).getConnection()
    try {
        return connection.createStatement().use { it.executeQuery(sql).use(::readAll) }
    } finally {
        driver.closeConnection(connection)
    }
}

private fun readAll(result: ResultSet): List<Row> {
    val columns = (1..result.metaData.columnCount).map { result.metaData.getColumnName(it) }
    val rows = mutableListOf<Row>()
    while (result.next()) {
        // BLOB の配列は == で中身を比べられないので、リストにする
        rows += columns.associateWith { column ->
            result.getObject(column).let { value -> (value as? ByteArray)?.toList() ?: value }
        }
    }
    return rows
}
