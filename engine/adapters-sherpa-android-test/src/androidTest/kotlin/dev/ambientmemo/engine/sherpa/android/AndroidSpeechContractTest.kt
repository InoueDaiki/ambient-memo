package dev.ambientmemo.engine.sherpa.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SpeakerEmbedderContract
import dev.ambientmemo.engine.domain.testing.TranscriberContract
import dev.ambientmemo.engine.domain.testing.VoiceActivityDetectorContract
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.testing.TestModels
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException

private const val TAG = "AndroidSpeechContract"

private class AndroidVadContract : VoiceActivityDetectorContract() {
    public override fun speech(): ShortArray = TestModels.speech()

    public override fun detector(speechMillis: LongRange): VoiceActivityDetector {
        val models = ModelCatalog.ACTIVE
        return androidVoiceActivityDetector(TestModels.storeFor(models), models)
    }
}

private class AndroidTranscriberContract : TranscriberContract() {
    private val models = ModelCatalog.ACTIVE
    private val transcriber by lazy { androidTranscriber(TestModels.storeFor(models), models) }

    public override fun speech(): ShortArray = TestModels.speech()

    public override fun transcriber(): Transcriber = transcriber
}

private class AndroidSpeakerEmbedderContract : SpeakerEmbedderContract() {
    private val model = ModelCatalog.ACTIVE_SPEAKER
    private val embedder by lazy { androidSpeakerEmbedder(TestModels.storeFor(model), model) }

    public override fun speech(): ShortArray = TestModels.speech()

    public override fun embedder(): SpeakerEmbedder = embedder
}

/**
 * 同じ契約テストを AAR の実装に流す。
 *
 * 契約テストは JUnit 5 で書いてあり、端末の JUnit 4 のランナーでは動かないので、`@Test` の付いたメソッドを探して呼ぶ。
 * モデルは `scripts/push-models.sh dev.ambientmemo.engine.sherpa.android.test.test` で内部ストレージに置く。
 * 手で動かすテストなので、モデルがなければ飛ばさず失敗させる。飛ばすと、通っていないのに OK と表示される。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSpeechContractTest {
    @Before
    fun locateModels() {
        System.setProperty("ambientmemo.requireModels", "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TestModels.root = context.filesDir.resolve("models")
    }

    private fun runContract(contract: Any) {
        val tests = generateSequence<Class<*>>(contract.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }
            .toList()
        check(tests.isNotEmpty()) { "契約テストが見つかりません" }
        tests.forEach { test ->
            test.isAccessible = true
            try {
                test.invoke(contract)
                Log.i(TAG, "通過: ${test.name}")
            } catch (e: InvocationTargetException) {
                throw AssertionError("${test.name} が失敗しました", e.cause)
            }
        }
    }

    @Test
    fun voiceActivityDetector() = runContract(AndroidVadContract())

    @Test
    fun transcriber() = runContract(AndroidTranscriberContract())

    @Test
    fun speakerEmbedder() = runContract(AndroidSpeakerEmbedderContract())
}
