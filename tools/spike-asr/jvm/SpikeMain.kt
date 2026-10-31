// Mac の JVM から sherpa-onnx を動かす検証。
// domain 側のインターフェースを想定した形にしてあり、tools/eval の土台にできるかを見る。
// sherpa-onnx の型は Sherpa* の実装の中だけに閉じる。音声はメモリ上だけで扱う。
package ambient.spike

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

const val SAMPLE_RATE = 16000

// ここから下の 3 つが domain に置く想定の境界。Android にも sherpa-onnx にも依存しない
data class SpeechSpan(val startSec: Double, val pcm: FloatArray)

interface SpeechDetector {
    fun feed(pcm: FloatArray): List<SpeechSpan>
    fun finish(): List<SpeechSpan>
}

interface Transcriber {
    fun transcribe(pcm: FloatArray, sampleRate: Int): String
}

interface SpeakerEmbedder {
    fun embed(pcm: FloatArray, sampleRate: Int): FloatArray
}

// ここから下は adapters に置く想定の実装
class SherpaVad(model: String, threshold: Float = 0.5f) : SpeechDetector {
    private val vad = Vad(
        VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder().setModel(model).setThreshold(threshold)
                    .setMinSilenceDuration(0.5f).setMinSpeechDuration(0.25f).setMaxSpeechDuration(20f)
                    .setWindowSize(512).build(),
            )
            .setSampleRate(SAMPLE_RATE).setNumThreads(1).build(),
    )

    private fun drain(): List<SpeechSpan> = buildList {
        while (!vad.empty()) {
            val s = vad.front()
            add(SpeechSpan(s.start.toDouble() / SAMPLE_RATE, s.samples))
            vad.pop()
        }
    }

    override fun feed(pcm: FloatArray): List<SpeechSpan> {
        vad.acceptWaveform(pcm)
        return drain()
    }

    override fun finish(): List<SpeechSpan> {
        vad.flush()
        return drain()
    }
}

class SherpaOfflineTranscriber(kind: String, dir: String, threads: Int) : Transcriber {
    private val rec: OfflineRecognizer

    init {
        val b = OfflineModelConfig.builder().setTokens("$dir/tokens.txt").setNumThreads(threads)
        when (kind) {
            "sensevoice" -> b.setSenseVoice(
                OfflineSenseVoiceModelConfig.builder().setModel("$dir/model.int8.onnx").setLanguage("ja")
                    .setInverseTextNormalization(true).build(),
            )
            "nemo-ctc" -> b.setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(find(dir, "model")).build())
            "transducer" -> b.setTransducer(
                OfflineTransducerModelConfig.builder().setEncoder(find(dir, "encoder")).setDecoder(find(dir, "decoder"))
                    .setJoiner(find(dir, "joiner")).build(),
            )
            else -> error("未対応: $kind")
        }
        rec = OfflineRecognizer(
            OfflineRecognizerConfig.builder().setOfflineModelConfig(b.build())
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(80).build())
                .setDecodingMethod("greedy_search").build(),
        )
    }

    override fun transcribe(pcm: FloatArray, sampleRate: Int): String {
        val st = rec.createStream()
        st.acceptWaveform(pcm, sampleRate)
        rec.decode(st)
        val text = rec.getResult(st).text
        st.release()
        return text
    }
}

class SherpaStreamingTranscriber(dir: String, threads: Int, private val language: String) : Transcriber {
    private val rec = OnlineRecognizer(
        OnlineRecognizerConfig.builder()
            .setOnlineModelConfig(
                OnlineModelConfig.builder()
                    .setTransducer(
                        OnlineTransducerModelConfig.builder().setEncoder(find(dir, "encoder"))
                            .setDecoder(find(dir, "decoder")).setJoiner(find(dir, "joiner")).build(),
                    )
                    .setTokens("$dir/tokens.txt").setNumThreads(threads).setModelType("nemo_transducer").build(),
            )
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(80).build())
            .setDecodingMethod("greedy_search").build(),
    )

    override fun transcribe(pcm: FloatArray, sampleRate: Int): String {
        val st = rec.createStream()
        if (language.isNotEmpty()) st.setOption("language", language)
        val step = sampleRate / 10
        var i = 0
        while (i < pcm.size) {
            st.acceptWaveform(pcm.copyOfRange(i, minOf(pcm.size, i + step)), sampleRate)
            while (rec.isReady(st)) rec.decode(st)
            i += step
        }
        st.acceptWaveform(FloatArray((0.8 * sampleRate).toInt()), sampleRate)
        st.inputFinished()
        while (rec.isReady(st)) rec.decode(st)
        val text = rec.getResult(st).text
        st.release()
        return text
    }
}

class SherpaEmbedder(model: String) : SpeakerEmbedder {
    private val ext = SpeakerEmbeddingExtractor(
        SpeakerEmbeddingExtractorConfig.builder().setModel(model).setNumThreads(1).build(),
    )

    override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray {
        val st = ext.createStream()
        st.acceptWaveform(pcm, sampleRate)
        st.inputFinished()
        val v = ext.compute(st)
        st.release()
        return v
    }
}

fun find(dir: String, role: String): String {
    val files = File(dir).listFiles()!!.filter { it.name.startsWith(role) && it.name.endsWith(".onnx") }
    return (files.firstOrNull { "int8" in it.name } ?: files.first()).path
}

/** VAD の区間の前後に実音声と無音を足す。Zipformer は前後に音がないと語頭と語尾を落とす。 */
fun withContext(all: FloatArray, s: SpeechSpan, sec: Double = 0.3): FloatArray {
    val n = (sec * SAMPLE_RATE).toInt()
    val start = (s.startSec * SAMPLE_RATE).toInt()
    val silence = FloatArray(n)
    return silence + all.copyOfRange(maxOf(0, start - n), minOf(all.size, start + s.pcm.size + n)) + silence
}

fun main(args: Array<String>) {
    LibraryUtils.load()
    val opt = args.toList().chunked(2).associate { it[0].removePrefix("--") to it[1] }
    val threads = opt["threads"]?.toInt() ?: 2
    val t0 = System.nanoTime()
    val asr: Transcriber = if (opt["kind"] == "streaming") {
        SherpaStreamingTranscriber(opt.getValue("dir"), threads, opt["lang"] ?: "")
    } else {
        SherpaOfflineTranscriber(opt.getValue("kind"), opt.getValue("dir"), threads)
    }
    val spk = opt["embed"]?.let { SherpaEmbedder(it) }
    val vad = opt["vad"]?.let { SherpaVad(it) }
    println("load_s=%.2f".format((System.nanoTime() - t0) / 1e9))

    val out = File(opt.getValue("out")).bufferedWriter()
    var audioSec = 0.0
    var asrSec = 0.0
    var spans = 0
    val paths = File(opt.getValue("list")).readLines().filter { it.isNotBlank() }
    for (line in paths) {
        val (id, path) = line.split("\t")
        val w = WaveReader(path)
        audioSec += w.samples.size.toDouble() / w.sampleRate
        val t = System.nanoTime()
        if (vad == null) {
            out.write("$id\t${asr.transcribe(w.samples, w.sampleRate)}\n")
        } else {
            // VAD -> ASR + embedding の流れを通す
            check(w.sampleRate == SAMPLE_RATE) { "VAD には 16 kHz を渡す" }
            val spansHere = ArrayList<SpeechSpan>()
            var i = 0
            while (i + 512 <= w.samples.size) {
                spansHere += vad.feed(w.samples.copyOfRange(i, i + 512))
                i += 512
            }
            spansHere += vad.finish()
            for (s in spansHere) {
                val text = asr.transcribe(withContext(w.samples, s), SAMPLE_RATE)
                val dim = spk?.embed(s.pcm, SAMPLE_RATE)?.size ?: 0
                out.write("$id\t%.2f\t$dim\t$text\n".format(s.startSec))
                spans++
            }
        }
        asrSec += (System.nanoTime() - t) / 1e9
    }
    out.close()
    println("files=${paths.size} spans=$spans audio_s=%.1f proc_s=%.1f rtf=%.4f".format(audioSec, asrSec, asrSec / audioSec))
}
