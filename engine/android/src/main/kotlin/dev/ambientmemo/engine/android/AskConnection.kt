package dev.ambientmemo.engine.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "AmbientMemoEngine"
private const val KEY_QUESTION = "question"
private const val KEY_ENDING = "ending"
private const val KEY_TEXT = "text"
private const val KEY_SOURCES = "sources"

internal fun askMessage(question: String) = Message.obtain(null, OrganizeService.MESSAGE_ASK).apply {
    data = Bundle().apply { putString(KEY_QUESTION, question) }
}

internal fun Bundle.question(): String = getString(KEY_QUESTION).orEmpty()

internal fun AskReply.toMessage() = Message.obtain(null, OrganizeService.MESSAGE_ASK_FINISHED).apply {
    data = Bundle().apply {
        putInt(KEY_ENDING, ending.ordinal)
        putString(KEY_TEXT, text)
        putLongArray(KEY_SOURCES, sourceIds.toLongArray())
    }
}

private fun Bundle.toAskReply() = AskReply(
    ending = AskEnding.entries[getInt(KEY_ENDING)],
    text = getString(KEY_TEXT),
    sourceIds = getLongArray(KEY_SOURCES)?.toList().orEmpty(),
)

/**
 * メインのプロセスから、`:organize` で質問を動かす。LLM はこのプロセスに持たない。
 *
 * 質問の間だけ `BIND_AUTO_CREATE` で bind する。利用者が答えを待っているので、優先度は下げない。
 * 同時に1つだけ動かす。
 */
internal class RemoteAsk(context: Context) : AskControl {
    private val context = context.applicationContext
    private val current = AtomicReference<AskConnection?>(null)

    override suspend fun ask(question: String, onStep: (AskStep) -> Unit): AskReply {
        val connection = AskConnection(context, question, onStep)
        if (!current.compareAndSet(null, connection)) return AskReply(AskEnding.BUSY)
        try {
            return connection.run()
        } finally {
            current.set(null)
        }
    }

    override fun cancel() {
        current.get()?.cancel()
    }
}

/** 質問の1回分の bind。相手のプロセスが落ちたら失敗として返す。 */
private class AskConnection(
    private val context: Context,
    private val question: String,
    private val onStep: (AskStep) -> Unit,
) : ServiceConnection {
    private val finished = CompletableDeferred<AskReply>()
    private val client = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                OrganizeService.MESSAGE_ASK_STEP -> if (finished.isActive) onStep(AskStep.entries[message.arg1])
                OrganizeService.MESSAGE_ASK_FINISHED -> finished.complete(message.data.toAskReply())
            }
            true
        },
    )

    @Volatile
    private var service: Messenger? = null

    suspend fun run(): AskReply {
        if (!context.bindService(OrganizeService.bindIntent(context), this, Context.BIND_AUTO_CREATE)) {
            return AskReply(AskEnding.FAILED)
        }
        try {
            return finished.await()
        } catch (e: CancellationException) {
            send(Message.obtain(null, OrganizeService.MESSAGE_ASK_CANCEL))
            throw e
        } finally {
            context.unbindService(this)
        }
    }

    /** 答えを待たずに [AskCancelledException] で終える。`:organize` には止めるよう頼むだけ。 */
    fun cancel() {
        send(Message.obtain(null, OrganizeService.MESSAGE_ASK_CANCEL))
        finished.completeExceptionally(AskCancelledException())
    }

    private fun send(message: Message) {
        message.replyTo = client
        try {
            service?.send(message)
        } catch (_: RemoteException) {
            Log.w(TAG, "整理のプロセスに届きません")
            finished.complete(AskReply(AskEnding.FAILED))
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = Messenger(binder)
        if (finished.isActive) send(askMessage(question))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        finished.complete(AskReply(AskEnding.FAILED))
    }

    override fun onBindingDied(name: ComponentName?) {
        finished.complete(AskReply(AskEnding.FAILED))
    }

    override fun onNullBinding(name: ComponentName?) {
        finished.complete(AskReply(AskEnding.FAILED))
    }
}
