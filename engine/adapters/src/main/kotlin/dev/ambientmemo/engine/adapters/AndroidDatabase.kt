package dev.ambientmemo.engine.adapters

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase

private const val DATABASE_NAME = "ambient-memo.db"

/** 端末の DB を開く。版が古ければ開くときにマイグレーションする。 */
fun openAndroidDatabase(context: Context): AmbientMemoDatabase =
    AmbientMemoDatabase(AndroidSqliteDriver(AmbientMemoDatabase.Schema, context, DATABASE_NAME))
