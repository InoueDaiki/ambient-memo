package dev.ambientmemo.engine.android

import android.content.Context
import dev.ambientmemo.engine.domain.AudioSource

/** Release は常にマイクを使う。 */
@Suppress("FunctionOnlyReturningConstant", "UnusedParameter") // Debug と同じ呼び出しにするため
internal fun developmentAudioSource(context: Context): AudioSource? = null
