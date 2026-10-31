package dev.ambientmemo.engine.adapters

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import java.nio.file.Path
import java.util.Properties

/** 本物の SQLite のファイル DB を開く。本番と同じくスキーマの版で作成とマイグレーションを行う。 */
internal fun openTestDriver(path: Path): JdbcSqliteDriver =
    JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), AmbientMemoDatabase.Schema)
