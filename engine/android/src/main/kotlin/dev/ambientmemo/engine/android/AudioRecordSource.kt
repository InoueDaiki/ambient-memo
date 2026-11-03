package dev.ambientmemo.engine.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.ambientmemo.engine.domain.AudioSource
import kotlin.math.max

private const val SAMPLE_RATE_HZ = 16_000
/** 1 回に読む長さ。メモに残るまでがこの分だけ遅れる。 */
private const val READ_MILLIS = 1_000L

/** OS 側のバッファ。読んだ音声を処理している間も溢れないように、1 回に読む長さより長くする。 */
private const val BUFFER_MILLIS = 3_000L
private const val BYTES_PER_SAMPLE = 2
private const val MILLIS_PER_SECOND = 1_000L

/** マイクから 16 kHz のモノラル PCM を読む [AudioSource]。音声はメモリ上のバッファにしか読まない。 */
internal fun microphoneAudioSource(): AudioSource = PcmReaderAudioSource(
    sampleRateHz = SAMPLE_RATE_HZ,
    maxLagMillis = BUFFER_MILLIS,
    readMillis = READ_MILLIS,
    openReader = ::openAudioRecord,
)

// RECORD_AUDIO は Engine.startCapture が確かめる
@SuppressLint("MissingPermission")
private fun openAudioRecord(): PcmReader {
    val bufferBytes = (SAMPLE_RATE_HZ * BUFFER_MILLIS / MILLIS_PER_SECOND).toInt() * BYTES_PER_SAMPLE
    val minBufferBytes = AudioRecord.getMinBufferSize(
        SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBufferBytes <= 0) throw AudioCaptureException("AudioRecord の設定を使えません: $minBufferBytes")
    val record = AudioRecord(
        // 音声認識向けの入力。端末の自動音量調整を避ける
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        max(minBufferBytes, bufferBytes),
    )
    if (record.state == AudioRecord.STATE_INITIALIZED) record.startRecording()
    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        record.release()
        throw AudioCaptureException("録音を開始できません")
    }
    return AudioRecordReader(record)
}

private class AudioRecordReader(private val record: AudioRecord) : PcmReader {
    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

    override fun close() {
        record.stop()
        record.release()
    }
}
