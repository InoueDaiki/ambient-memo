@file:Suppress("MagicNumber") // ModelCatalog の大きさは配布物の値

package dev.ambientmemo.engine.sherpa

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private const val HASH_BUFFER_BYTES = 1 shl 20
private const val VERIFIED_STAMP = ".verified"

class ModelFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** 1つのディレクトリに置くモデルのファイルの組。ディレクトリの名前は [id]。 */
class ModelPackage(
    val id: String,
    val files: List<ModelFile>,
)

/** 発話の検知のモデル。 */
class VadModel(val modelPackage: ModelPackage, val model: ModelFile)

/**
 * 文字起こしのモデル。種類ごとに JVM と Android のアダプターで設定を組み立てる。
 * 種類を足すときは、両方の `when` がコンパイルエラーになるので、そこに足す。
 */
sealed interface AsrModel {
    val modelPackage: ModelPackage
    val tokens: ModelFile

    class Transducer(
        override val modelPackage: ModelPackage,
        val encoder: ModelFile,
        val decoder: ModelFile,
        val joiner: ModelFile,
        override val tokens: ModelFile,
    ) : AsrModel

    class SenseVoice(
        override val modelPackage: ModelPackage,
        val model: ModelFile,
        override val tokens: ModelFile,
    ) : AsrModel
}

/** 話者の特徴量のモデル。なくても文字起こしは動くので、[SpeechModels] とは別に扱う。 */
class SpeakerModel(val modelPackage: ModelPackage, val model: ModelFile) {
    /** 特徴量に記録する版。モデルを変えると、保存済みの特徴量は比べられなくなる。 */
    val version: String
        get() = modelPackage.id
}

class SpeechModels(val vad: VadModel, val asr: AsrModel) {
    val packages: List<ModelPackage>
        get() = listOf(vad.modelPackage, asr.modelPackage)
}

/** 使えるモデルの一覧。SHA-256 と大きさは、配布物から取り出したファイルの値。 */
object ModelCatalog {
    val SILERO_VAD: VadModel = run {
        val model = ModelFile(
            "silero_vad.onnx",
            643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        )
        VadModel(ModelPackage("silero-vad", listOf(model)), model)
    }

    val ZIPFORMER_REAZON: AsrModel = run {
        val encoder = ModelFile(
            "encoder-epoch-99-avg-1.int8.onnx",
            154_670_139,
            "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d",
        )
        val decoder = ModelFile(
            "decoder-epoch-99-avg-1.int8.onnx",
            2_959_337,
            "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485",
        )
        val joiner = ModelFile(
            "joiner-epoch-99-avg-1.int8.onnx",
            2_696_970,
            "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de",
        )
        val tokens = ModelFile(
            "tokens.txt",
            45_754,
            "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def",
        )
        AsrModel.Transducer(
            ModelPackage(
                "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
                listOf(encoder, decoder, joiner, tokens),
            ),
            encoder,
            decoder,
            joiner,
            tokens,
        )
    }

    /** 次点。ReazonSpeech 由来のモデルを避けるときに使う。ライセンスは FunASR のモデルライセンス。 */
    val SENSE_VOICE_2024: AsrModel = run {
        val model = ModelFile(
            "model.int8.onnx",
            239_233_841,
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        )
        val tokens = ModelFile(
            "tokens.txt",
            315_894,
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
        )
        AsrModel.SenseVoice(
            ModelPackage("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17", listOf(model, tokens)),
            model,
            tokens,
        )
    }

    val CAMPPLUS_ADVANCED: SpeakerModel = run {
        val model = ModelFile(
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            28_281_164,
            "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2",
        )
        SpeakerModel(ModelPackage("3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced", listOf(model)), model)
    }

    /** アプリが使うモデル。差し替えるときはここを変える。`scripts/fetch-models.sh` も合わせる。 */
    val ACTIVE: SpeechModels = SpeechModels(SILERO_VAD, ZIPFORMER_REAZON)

    /** アプリが使う話者のモデル。差し替えるときはここを変える。`scripts/fetch-models.sh` も合わせる。 */
    val ACTIVE_SPEAKER: SpeakerModel = CAMPPLUS_ADVANCED
}

/** [root] の下にモデルを置く。端末では内部ストレージ、JVM ではテスト用のディレクトリ。 */
class ModelStore(private val root: File) {
    fun pathOf(modelPackage: ModelPackage, file: ModelFile): String =
        File(File(root, modelPackage.id), file.name).path

    /** ファイルが揃っているかを大きさだけで見る。中身まで確かめるのは [verify]。 */
    fun isInstalled(models: SpeechModels): Boolean = models.packages.all { modelPackage ->
        modelPackage.files.all { File(pathOf(modelPackage, it)).length() == it.sizeBytes }
    }

    /**
     * SHA-256 を確かめる。合わないファイルは消す。
     *
     * 160 MB の計算に時間がかかるので、一度通ったパッケージは印を残して計算を省く。
     * 印を信じるのは、同じ版のハッシュの組に対してだけ。
     */
    fun verify(models: SpeechModels): Boolean = models.packages.map(::verify).all { it }

    fun isInstalled(model: SpeakerModel): Boolean =
        File(pathOf(model.modelPackage, model.model)).length() == model.model.sizeBytes

    fun verify(model: SpeakerModel): Boolean = verify(model.modelPackage)

    private fun verify(modelPackage: ModelPackage): Boolean {
        val directory = File(root, modelPackage.id)
        val stamp = File(directory, VERIFIED_STAMP)
        val expected = modelPackage.files.joinToString("\n") { "${it.name} ${it.sizeBytes} ${it.sha256}" }
        val hasStamp = stamp.isFile && stamp.readText() == expected
        val installed = modelPackage.files.all { File(directory, it.name).length() == it.sizeBytes }
        if (hasStamp && installed) return true

        stamp.delete()
        val broken = modelPackage.files.filterNot { hasExpectedHash(File(directory, it.name), it) }
        broken.forEach { File(directory, it.name).delete() }
        val isValid = broken.isEmpty()
        if (isValid) stamp.writeText(expected)
        return isValid
    }

    private fun hasExpectedHash(file: File, expected: ModelFile): Boolean =
        file.length() == expected.sizeBytes && sha256(file) == expected.sha256

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
