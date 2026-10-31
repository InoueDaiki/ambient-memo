@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.sherpa.jvm

import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SpeakerEmbedderContract
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import dev.ambientmemo.engine.domain.testing.TranscriberContract
import dev.ambientmemo.engine.domain.testing.VoiceActivityDetectorContract
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.SherpaTranscriber
import dev.ambientmemo.engine.sherpa.SpeechModels
import dev.ambientmemo.engine.sherpa.testing.TestModels
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// モデルの読み込みは重いので、テストの間で使い回す。プロセスの終了まで解放しない
private val transcribers = HashMap<SpeechModels, SherpaTranscriber>()

private fun sharedTranscriber(models: SpeechModels): SherpaTranscriber {
    val store = TestModels.storeFor(models)
    return transcribers.getOrPut(models) { jvmTranscriber(store, models) }
}

private val speakerEmbedder by lazy {
    val model = ModelCatalog.ACTIVE_SPEAKER
    jvmSpeakerEmbedder(TestModels.storeFor(model), model)
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

class JvmSpeakerEmbedderContractTest : SpeakerEmbedderContract() {
    override fun speech(): ShortArray = TestModels.speech()

    override fun embedder(): SpeakerEmbedder {
        TestModels.storeFor(ModelCatalog.ACTIVE_SPEAKER)
        return speakerEmbedder
    }

    private fun segment(samples: ShortArray) =
        SpeechSegment(0, samples.size * 1_000L / TEST_SAMPLE_RATE_HZ, TEST_SAMPLE_RATE_HZ, samples)

    @Test
    fun `モデルの次元の特徴量を返す`() = runTest {
        val embedding = checkNotNull(embedder().embed(segment(speech())))

        assertEquals(EMBEDDING_DIMENSIONS, embedding.vector.size)
    }

    @Test
    fun `短い音声は null を返し、下限の長さなら数の特徴量を返す`() = runTest {
        for (millis in listOf(1, 10, 50, 100, 250, 399)) {
            val samples = speech().copyOf(millis * TEST_SAMPLE_RATE_HZ / 1_000)

            assertEquals(null, embedder().embed(segment(samples))?.vector, "$millis ミリ秒")
        }

        val embedding = embedder().embed(segment(speech().copyOf(4 * TEST_SAMPLE_RATE_HZ / 10)))
        assertTrue(embedding == null || embedding.vector.all { it.isFinite() })
    }

    @Test
    fun `無音でも落ちない`() = runTest {
        embedder().embed(segment(SyntheticAudio.silence(1_000)))
    }

    private companion object {
        const val EMBEDDING_DIMENSIONS = 192
    }
}
