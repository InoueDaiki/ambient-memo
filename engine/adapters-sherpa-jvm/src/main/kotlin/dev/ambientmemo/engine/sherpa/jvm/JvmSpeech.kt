package dev.ambientmemo.engine.sherpa.jvm

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.ambientmemo.engine.sherpa.AsrModel
import dev.ambientmemo.engine.sherpa.ModelFile
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.NativeRecognizer
import dev.ambientmemo.engine.sherpa.NativeVad
import dev.ambientmemo.engine.sherpa.SherpaSettings
import dev.ambientmemo.engine.sherpa.SherpaTranscriber
import dev.ambientmemo.engine.sherpa.SherpaVoiceActivityDetector
import dev.ambientmemo.engine.sherpa.SpeechModels

private val nativeLibrary by lazy { LibraryUtils.load() }

fun jvmVoiceActivityDetector(store: ModelStore, models: SpeechModels): SherpaVoiceActivityDetector =
    SherpaVoiceActivityDetector(JvmVad(store.pathOf(models.vad.modelPackage, models.vad.model)))

fun jvmTranscriber(store: ModelStore, models: SpeechModels): SherpaTranscriber =
    SherpaTranscriber { JvmRecognizer(store, models.asr) }

private class JvmVad(model: String) : NativeVad {
    private val vad: Vad

    init {
        nativeLibrary
        vad = Vad(
            VadModelConfig.builder()
                .setSileroVadModelConfig(
                    SileroVadModelConfig.builder()
                        .setModel(model)
                        .setThreshold(SherpaSettings.VAD_THRESHOLD)
                        .setMinSilenceDuration(SherpaSettings.VAD_MIN_SILENCE_SECONDS)
                        .setMinSpeechDuration(SherpaSettings.VAD_MIN_SPEECH_SECONDS)
                        .setMaxSpeechDuration(SherpaSettings.VAD_MAX_SPEECH_SECONDS)
                        .setWindowSize(SherpaSettings.VAD_WINDOW_SAMPLES)
                        .build(),
                )
                .setSampleRate(SherpaSettings.SAMPLE_RATE_HZ)
                .setNumThreads(SherpaSettings.VAD_THREADS)
                .build(),
        )
    }

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected

    override fun dropSegments() {
        while (!vad.empty()) vad.pop()
    }

    override fun reset() = vad.reset()

    override fun close() = vad.release()
}

private class JvmRecognizer(store: ModelStore, model: AsrModel) : NativeRecognizer {
    private val recognizer: OfflineRecognizer

    init {
        nativeLibrary
        recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig(store, model))
                .setFeatureConfig(
                    FeatureConfig.builder()
                        .setSampleRate(SherpaSettings.SAMPLE_RATE_HZ)
                        .setFeatureDim(SherpaSettings.ASR_FEATURE_DIM)
                        .build(),
                )
                .setDecodingMethod(SherpaSettings.ASR_DECODING_METHOD)
                .build(),
        )
    }

    override fun decode(samples: FloatArray, sampleRateHz: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

private fun modelConfig(store: ModelStore, model: AsrModel): OfflineModelConfig {
    val path = { file: ModelFile -> store.pathOf(model.modelPackage, file) }
    val builder = OfflineModelConfig.builder()
        .setTokens(path(model.tokens))
        .setNumThreads(SherpaSettings.ASR_THREADS)
    when (model) {
        is AsrModel.Transducer -> builder.setTransducer(
            OfflineTransducerModelConfig.builder()
                .setEncoder(path(model.encoder))
                .setDecoder(path(model.decoder))
                .setJoiner(path(model.joiner))
                .build(),
        )
        is AsrModel.SenseVoice -> builder.setSenseVoice(
            OfflineSenseVoiceModelConfig.builder()
                .setModel(path(model.model))
                .setLanguage(SherpaSettings.ASR_LANGUAGE)
                .setInverseTextNormalization(true)
                .build(),
        )
    }
    return builder.build()
}
