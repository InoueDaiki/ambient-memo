package dev.ambientmemo.engine.sherpa.android

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.ambientmemo.engine.sherpa.AsrModel
import dev.ambientmemo.engine.sherpa.ModelFile
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.NativeRecognition
import dev.ambientmemo.engine.sherpa.NativeRecognizer
import dev.ambientmemo.engine.sherpa.NativeSpeakerExtractor
import dev.ambientmemo.engine.sherpa.NativeVad
import dev.ambientmemo.engine.sherpa.SherpaSettings
import dev.ambientmemo.engine.sherpa.SherpaSpeakerEmbedder
import dev.ambientmemo.engine.sherpa.SherpaTranscriber
import dev.ambientmemo.engine.sherpa.SherpaVoiceActivityDetector
import dev.ambientmemo.engine.sherpa.SpeakerModel
import dev.ambientmemo.engine.sherpa.SpeechModels

fun androidVoiceActivityDetector(store: ModelStore, models: SpeechModels): SherpaVoiceActivityDetector =
    SherpaVoiceActivityDetector(AndroidVad(store.pathOf(models.vad.modelPackage, models.vad.model)))

fun androidTranscriber(store: ModelStore, models: SpeechModels): SherpaTranscriber =
    SherpaTranscriber { AndroidRecognizer(store, models.asr) }

fun androidSpeakerEmbedder(store: ModelStore, model: SpeakerModel): SherpaSpeakerEmbedder =
    SherpaSpeakerEmbedder(model.version) { AndroidSpeakerExtractor(store.pathOf(model.modelPackage, model.model)) }

// assets ではなく内部ストレージのファイルを読むので、AssetManager は渡さない
private class AndroidVad(model: String) : NativeVad {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = SherpaSettings.VAD_THRESHOLD,
                minSilenceDuration = SherpaSettings.VAD_MIN_SILENCE_SECONDS,
                minSpeechDuration = SherpaSettings.VAD_MIN_SPEECH_SECONDS,
                windowSize = SherpaSettings.VAD_WINDOW_SAMPLES,
                maxSpeechDuration = SherpaSettings.VAD_MAX_SPEECH_SECONDS,
            ),
            sampleRate = SherpaSettings.SAMPLE_RATE_HZ,
            numThreads = SherpaSettings.VAD_THREADS,
        ),
    )

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

    override fun dropSegments() {
        while (!vad.empty()) vad.pop()
    }

    override fun reset() = vad.reset()

    override fun close() = vad.release()
}

private class AndroidRecognizer(store: ModelStore, model: AsrModel) : NativeRecognizer {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SherpaSettings.SAMPLE_RATE_HZ,
                featureDim = SherpaSettings.ASR_FEATURE_DIM,
            ),
            modelConfig = modelConfig(store, model),
            decodingMethod = SherpaSettings.ASR_DECODING_METHOD,
        ),
    )

    override fun decode(samples: FloatArray, sampleRateHz: Int): NativeRecognition {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return NativeRecognition(result.text, result.tokens.toList(), result.timestamps.toList())
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

private class AndroidSpeakerExtractor(model: String) : NativeSpeakerExtractor {
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = model, numThreads = SherpaSettings.SPEAKER_THREADS),
    )

    override fun compute(samples: FloatArray, sampleRateHz: Int): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}

private fun modelConfig(store: ModelStore, model: AsrModel): OfflineModelConfig {
    val path = { file: ModelFile -> store.pathOf(model.modelPackage, file) }
    val base = OfflineModelConfig(tokens = path(model.tokens), numThreads = SherpaSettings.ASR_THREADS)
    return when (model) {
        is AsrModel.Transducer -> base.copy(
            transducer = OfflineTransducerModelConfig(
                encoder = path(model.encoder),
                decoder = path(model.decoder),
                joiner = path(model.joiner),
            ),
        )
        is AsrModel.SenseVoice -> base.copy(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = path(model.model),
                language = SherpaSettings.ASR_LANGUAGE,
                useInverseTextNormalization = true,
            ),
        )
    }
}
