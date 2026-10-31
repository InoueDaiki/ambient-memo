package dev.ambientmemo.engine.adapters

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import java.io.File
import java.io.RandomAccessFile

private const val DATABASE_NAME = "ambient-memo.db"
private const val OPEN_LOCK_SUFFIX = ".open-lock"

/** 同じプロセスの中の順番。ファイルのロックはプロセスの間にしか効かず、重ねて取ると例外になる。 */
private val openLock = Any()

/**
 * 端末の DB を開く。版が古ければここでマイグレーションする。
 *
 * メインと `:organize` の 2 つのプロセスが開く。SQLiteOpenHelper は版を読んでからトランザクションを始めるので、
 * 同時に開くと、両方が古い版を見て、後の方が同じマイグレーションを当てて失敗する。
 * 開いて移行するところまでをファイルのロックで順番にする。
 */
fun openAndroidDatabase(context: Context): AmbientMemoDatabase = synchronized(openLock) {
    val path = context.getDatabasePath(DATABASE_NAME)
    path.parentFile?.mkdirs()
    val driver = AndroidSqliteDriver(AmbientMemoDatabase.Schema, context, DATABASE_NAME)
    RandomAccessFile(File(path.path + OPEN_LOCK_SUFFIX), "rw").use { file ->
        file.channel.lock().use {
            // ドライバーは最初の問い合わせで開く
            driver.executeQuery(null, "SELECT 1", { QueryResult.Unit }, 0)
        }
    }
    AmbientMemoDatabase(driver)
}
