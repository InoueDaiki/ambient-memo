package dev.ambientmemo.engine.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewTodo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.SystemClock
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

private const val TAG = "AmbientMemoDebug"
private const val SEED_MODEL_VERSION = "debug-seed"
private const val SEED_UTTERANCE_MILLIS = 1_000L

/**
 * 整理ジョブの代わりに、未処理の発話を1つのメモにまとめる。整理ジョブがまだ動かない間の確認用で、
 * Release には入らない。発話がなければ見本の発話を1つ足す。
 *
 * `adb shell dumpsys` と同じ DUMP 権限を要るようにして、adb からだけ呼べるようにしている。
 *
 *     adb shell am broadcast -n dev.ambientmemo.app.dev/dev.ambientmemo.engine.android.DebugMemoReceiver
 */
class DebugMemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val memo = seed(Engine.get(context))
                Log.i(TAG, if (memo == null) "投入できませんでした" else "メモを投入しました: ${memo.id}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(engine: Engine) = engine.run {
        val now = SystemClock.nowMillis()
        val utterances = memoRepository.listUnorganizedUtterances().ifEmpty {
            listOf(
                utteranceRepository.insert(
                    NewUtterance(now - SEED_UTTERANCE_MILLIS, now, "開発用の見本の発話です。", null, now),
                ),
            )
        }
        memoRepository.recordMemo(
            NewMemo(
                startedAtMillis = utterances.minOf { it.startedAtMillis },
                endedAtMillis = utterances.maxOf { it.endedAtMillis },
                title = "開発用のメモ",
                summary = "- 整理ジョブの代わりに adb から投入した",
                todoExtraction = TodoExtraction.COMPLETE,
                modelVersion = SEED_MODEL_VERSION,
                createdAtMillis = now,
                utteranceIds = utterances.map { it.id },
                todos = listOf(
                    NewTodo("開発用の確定した ToDo", TodoKind.REMINDER, tomorrow(), TodoStatus.OPEN),
                    NewTodo("開発用の候補の ToDo", TodoKind.TODO, tomorrow(), TodoStatus.CANDIDATE),
                ),
            ),
        )
    }

    private fun tomorrow(): CalendarDate {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        return CalendarDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
