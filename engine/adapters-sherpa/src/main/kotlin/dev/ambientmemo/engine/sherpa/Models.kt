@file:Suppress("MagicNumber") // ModelCatalog の大きさは配布物の値

package dev.ambientmemo.engine.sherpa

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private const val HASH_BUFFER_BYTES = 1 shl 20
private const val VERIFIED_STAMP = ".verified"
private const val REVISION_CHARS = 8

/** sherpa-onnx の配布物の置き場所。 */
private const val SHERPA_RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

/** Gemma の配布物の置き場所。Apache-2.0 で、ログインも利用規約の同意も要らない(2026-11-01 に確認)。 */
private const val LITERT_COMMUNITY = "https://huggingface.co/litert-community"

/** 話者のモデルの置き場所。配布元のタグの綴りは recongition のまま。 */
private const val SPEAKER_RELEASES =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"

class ModelFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * 1つのディレクトリに置くモデルのファイルの組。ディレクトリの名前は [id]。
 *
 * [source] がないパッケージはアプリから取得できない。[ModelCatalog.DOWNLOADS] のパッケージには必ず持たせる。
 */
class ModelPackage(
    val id: String,
    val files: List<ModelFile>,
    val source: ModelSource? = null,
)

/** モデルの配布物。アプリが通信する先はカタログのこの URL だけ。 */
sealed interface ModelSource {
    val url: String
    val sizeBytes: Long
    val sha256: String

    /** 配布物がモデルのファイルそのもの。Gemma のように数 GB でも、取得と確認だけで済む。 */
    class SingleFile(override val url: String, val file: ModelFile) : ModelSource {
        override val sizeBytes: Long
            get() = file.sizeBytes
        override val sha256: String
            get() = file.sha256
    }

    /** 使わないファイルも含む tar.bz2。[directory] の下から、パッケージのファイルだけを取り出す。 */
    class TarBz2(
        override val url: String,
        override val sizeBytes: Long,
        override val sha256: String,
        val directory: String,
    ) : ModelSource
}

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

/** 整理に使う LLM のモデル。[version] はメモに残す版。 */
class LlmModel(val modelPackage: ModelPackage, val model: ModelFile, val version: String)

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

/** 使えるモデルの一覧。[ModelFile] の SHA-256 と大きさは、配布物から取り出したファイルの値。 */
object ModelCatalog {
    val SILERO_VAD: VadModel = run {
        val model = ModelFile(
            "silero_vad.onnx",
            643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        )
        val source = ModelSource.SingleFile("$SHERPA_RELEASES/silero_vad.onnx", model)
        VadModel(ModelPackage("silero-vad", listOf(model), source), model)
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
        val id = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
        AsrModel.Transducer(
            ModelPackage(
                id,
                listOf(encoder, decoder, joiner, tokens),
                ModelSource.TarBz2(
                    "$SHERPA_RELEASES/$id.tar.bz2",
                    713_097_333,
                    "e0981d0d5d7b446d41010831b59091ebf57d2aa7b79980f67ca37af460b5842d",
                    id,
                ),
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
        val id = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        AsrModel.SenseVoice(
            ModelPackage(
                id,
                listOf(model, tokens),
                ModelSource.TarBz2(
                    "$SHERPA_RELEASES/$id.tar.bz2",
                    163_002_883,
                    "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
                    id,
                ),
            ),
            model,
            tokens,
        )
    }

    /** 検証の第一候補。リビジョンは検証(`tools/spike-llm/results/models.json`)と同じ。 */
    val GEMMA_4_E4B: LlmModel = gemma(
        repository = "gemma-4-E4B-it-litert-lm",
        revision = "e6a3892a8c22cf87ff99583996149cf3cb32452b",
        file = ModelFile(
            "gemma-4-E4B-it.litertlm",
            3_659_530_240,
            "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        ),
    )

    /** 次善。品質は落ちるが、小さく速い。 */
    val GEMMA_4_E2B: LlmModel = gemma(
        repository = "gemma-4-E2B-it-litert-lm",
        revision = "1caa527c1e88b55f046fcd8d2b4265606680663d",
        file = ModelFile(
            "gemma-4-E2B-it.litertlm",
            2_588_147_712,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        ),
    )

    val CAMPPLUS_ADVANCED: SpeakerModel = run {
        val model = ModelFile(
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            28_281_164,
            "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2",
        )
        val source = ModelSource.SingleFile("$SPEAKER_RELEASES/${model.name}", model)
        val id = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced"
        SpeakerModel(ModelPackage(id, listOf(model), source), model)
    }

    /** アプリが使うモデル。差し替えるときはここを変える。`scripts/fetch-models.sh` も合わせる。 */
    val ACTIVE: SpeechModels = SpeechModels(SILERO_VAD, ZIPFORMER_REAZON)

    /** 整理に使うモデル。数 GB あるので [ACTIVE] とは分けて、利用者が別に取得する。 */
    val ACTIVE_LLM: LlmModel = GEMMA_4_E4B

    private fun gemma(repository: String, revision: String, file: ModelFile): LlmModel {
        val id = "${file.name.removeSuffix(".litertlm")}-${revision.take(REVISION_CHARS)}"
        val url = "$LITERT_COMMUNITY/$repository/resolve/$revision/${file.name}"
        return LlmModel(ModelPackage(id, listOf(file), ModelSource.SingleFile(url, file)), file, id)
    }

    /** アプリが使う話者のモデル。差し替えるときはここを変える。`scripts/fetch-models.sh` も合わせる。 */
    val ACTIVE_SPEAKER: SpeakerModel = CAMPPLUS_ADVANCED

    /** アプリが取得するパッケージ。すべてに [ModelPackage.source] が要る。 */
    val DOWNLOADS: List<ModelPackage> = ACTIVE.packages + ACTIVE_SPEAKER.modelPackage

    /** 整理のモデル。数 GB あるので [DOWNLOADS] とは分けて、利用者が別に選ぶ。すべてに [ModelPackage.source] が要る。 */
    val ORGANIZER_DOWNLOADS: List<ModelPackage> = listOf(ACTIVE_LLM.modelPackage)
}

/** [root] の下にモデルを置く。端末では内部ストレージ、JVM ではテスト用のディレクトリ。 */
class ModelStore(private val root: File) {
    fun pathOf(modelPackage: ModelPackage, file: ModelFile): String =
        File(File(root, modelPackage.id), file.name).path

    /** ファイルが揃っているかを大きさだけで見る。中身まで確かめるのは [verify]。 */
    fun isInstalled(models: SpeechModels): Boolean = models.packages.all { isInstalled(it) }

    fun isInstalled(modelPackage: ModelPackage): Boolean =
        modelPackage.files.all { File(pathOf(modelPackage, it)).length() == it.sizeBytes }

    /**
     * SHA-256 を確かめる。合わないファイルは消す。
     *
     * 160 MB の計算に時間がかかるので、一度通ったパッケージは印を残して計算を省く。
     * 印を信じるのは、同じ版のハッシュの組に対してだけ。
     */
    fun verify(models: SpeechModels): Boolean = models.packages.map { verify(it) }.all { it }

    fun isInstalled(model: SpeakerModel): Boolean =
        File(pathOf(model.modelPackage, model.model)).length() == model.model.sizeBytes

    fun verify(model: SpeakerModel): Boolean = verify(model.modelPackage)

    fun verify(modelPackage: ModelPackage): Boolean {
        val directory = File(root, modelPackage.id)
        val stamp = File(directory, VERIFIED_STAMP)
        val expected = stampOf(modelPackage)
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

    /**
     * [staged] の下の、確かめ終えたファイルを移す。
     *
     * 開始の判定は大きさを見るので、書きかけのファイルをここへ置かない。名前の変更で移すので、
     * [staged] は同じファイルシステムに置く。
     */
    internal fun install(modelPackage: ModelPackage, staged: File) {
        val directory = File(root, modelPackage.id)
        check(directory.isDirectory || directory.mkdirs()) { "ディレクトリを作れません" }
        modelPackage.files.forEach { file ->
            check(File(staged, file.name).renameTo(File(directory, file.name))) { "ファイルを移せません" }
        }
        File(directory, VERIFIED_STAMP).writeText(stampOf(modelPackage))
    }

    private fun stampOf(modelPackage: ModelPackage): String =
        modelPackage.files.joinToString("\n") { "${it.name} ${it.sizeBytes} ${it.sha256}" }

    private fun hasExpectedHash(file: File, expected: ModelFile): Boolean =
        file.length() == expected.sizeBytes && sha256Of(file) == expected.sha256
}

/** [onRead] は読んだバイト数の累計を受け取る。中断するときは例外を投げる。 */
internal fun sha256Of(file: File, onRead: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            total += count
            onRead(total)
        }
    }
    return digest.hex()
}

internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
