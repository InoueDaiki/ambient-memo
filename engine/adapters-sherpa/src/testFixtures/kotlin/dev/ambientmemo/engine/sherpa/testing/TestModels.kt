package dev.ambientmemo.engine.sherpa.testing

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import dev.ambientmemo.engine.sherpa.AsrModel
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.SpeechModels
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.FileInputStream

/**
 * 本物のモデルを使うテストの置き場所。
 *
 * モデルはコミットしない。`mise run models:fetch` で取得する。モデルがなければテストを飛ばすが、
 * 本物のモデルで動かす CI では `ambientmemo.requireModels` を立てて、飛ばさず失敗させる。
 */
object TestModels {
    /** テストの実行環境が決める場所。JVM はシステムプロパティ、端末は androidTest が指定する。 */
    var root: File? = System.getProperty("ambientmemo.modelsDir")?.let(::File)

    private val requireModels = System.getProperty("ambientmemo.requireModels").toBoolean()

    /** [models] がなければテストを飛ばす。あれば、そのストアを返す。 */
    fun storeFor(models: SpeechModels): ModelStore {
        val store = root?.let(::ModelStore)
        val isInstalled = store?.isInstalled(models) == true
        check(isInstalled || !requireModels) { "モデルがありません: ${models.asr.modelPackage.id}" }
        assumeTrue(isInstalled, "モデルがありません。mise run models:fetch で取得します")
        return checkNotNull(store)
    }

    /**
     * モデルの配布物に含まれる発話の音声。リポジトリには入れない。
     * [AsrModel] の検証用の音声はどのモデルでも同じものを使う。
     */
    fun speech(): ShortArray {
        val models = ModelCatalog.ACTIVE
        storeFor(models)
        val path = File(File(checkNotNull(root), models.asr.modelPackage.id), "test_wavs/3.wav")
        return readWav(path)
    }

    fun readWav(path: File): ShortArray = runBlocking {
        val samples = ArrayList<Short>()
        WavAudioSource({ FileInputStream(path) }, FakeClock())
            .frames()
            .onEach { frame: AudioFrame -> samples += frame.samples.asList() }
            .collect()
        samples.toShortArray()
    }
}
