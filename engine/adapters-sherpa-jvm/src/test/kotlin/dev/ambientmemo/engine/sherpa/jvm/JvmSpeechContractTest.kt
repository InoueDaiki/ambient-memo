@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.sherpa.jvm

import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import dev.ambientmemo.engine.domain.testing.TranscriberContract
import dev.ambientmemo.engine.domain.testing.VoiceActivityDetectorContract
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.SherpaTranscriber
import dev.ambientmemo.engine.sherpa.SpeechModels
import dev.ambientmemo.engine.sherpa.testing.TestModels
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// モデルの読み込みは重いので、テストの間で使い回す。プロセスの終了まで解放しない
private val transcribers = HashMap<SpeechModels, SherpaTranscriber>()

private fun sharedTranscriber(models: SpeechModels): SherpaTranscriber {
    val store = TestModels.storeFor(models)
    return transcribers.getOrPut(models) { jvmTranscriber(store, models) }
}

class JvmVoiceActivityDetectorContractTest : VoiceActivityDetectorContract() {
    override fun speech(): ShortArray = TestModels.speech()

    override fun detector(speechMillis: LongRange): VoiceActivityDetector {
        val models = ModelCatalog.ACTIVE
        return jvmVoiceActivityDetector(TestModels.storeFor(models), models)
    }
}

class JvmZipformerTranscriberContractTest : TranscriberContract() {
    override fun speech(): ShortArray = TestModels.speech()

    override fun transcriber(): Transcriber = sharedTranscriber(ModelCatalog.ACTIVE)

    @Test
    fun `日本語の発話を文字にする`() = runTest {
        val samples = TestModels.speech()
        val segment = SpeechSegment(0, 1_000, TEST_SAMPLE_RATE_HZ, samples)

        val text = transcriber().transcribe(segment).text

        assertTrue(text.length > 5, text)
        assertTrue(text.any { it in 'ぁ'..'ん' }, text)
    }
}

/** 次点のモデルに差し替えられることを確かめる。CI にはモデルがないので手元でだけ動く。 */
class JvmSenseVoiceTranscriberContractTest : TranscriberContract() {
    private val models = SpeechModels(ModelCatalog.SILERO_VAD, ModelCatalog.SENSE_VOICE_2024)

    override fun speech(): ShortArray = TestModels.speech()

    override fun transcriber(): Transcriber {
        // CI の必須のモデルには含めないので、storeFor を通さず飛ばす
        assumeTrue(TestModels.root?.let { ModelStore(it).isInstalled(models) } == true)
        return sharedTranscriber(models)
    }
}
