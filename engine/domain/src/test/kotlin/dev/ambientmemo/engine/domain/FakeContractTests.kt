package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.AudioSourceContract
import dev.ambientmemo.engine.domain.testing.CaptureGapRepositoryContract
import dev.ambientmemo.engine.domain.testing.ClockContract
import dev.ambientmemo.engine.domain.testing.FakeAudioSource
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.FakeTranscriber
import dev.ambientmemo.engine.domain.testing.InMemoryCaptureGapRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SpeakerEmbedderContract
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TranscriberContract
import dev.ambientmemo.engine.domain.testing.UtteranceRepositoryContract
import dev.ambientmemo.engine.domain.testing.VoiceActivityDetectorContract
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import java.io.ByteArrayInputStream

class FakeAudioSourceContractTest : AudioSourceContract() {
    override fun source(samples: ShortArray) = FakeAudioSource(samples)
}

class WavAudioSourceContractTest : AudioSourceContract() {
    override fun source(samples: ShortArray) =
        WavAudioSource({ ByteArrayInputStream(SyntheticAudio.wav(samples)) }, FakeClock(1_000))
}

class FakeClockContractTest : ClockContract() {
    override fun clock() = FakeClock()
}

class SystemClockContractTest : ClockContract() {
    override fun clock() = SystemClock
}

class ScriptedVoiceActivityDetectorContractTest : VoiceActivityDetectorContract() {
    override fun detector(speechMillis: LongRange) = ScriptedVoiceActivityDetector(listOf(speechMillis))
}

class FakeTranscriberContractTest : TranscriberContract() {
    override fun transcriber() = FakeTranscriber()
}

class FakeSpeakerEmbedderContractTest : SpeakerEmbedderContract() {
    override fun embedder() = FakeSpeakerEmbedder()
}

class InMemoryUtteranceRepositoryContractTest : UtteranceRepositoryContract() {
    override fun repository() = InMemoryUtteranceRepository()
}

class InMemoryCaptureGapRepositoryContractTest : CaptureGapRepositoryContract() {
    override fun repository() = InMemoryCaptureGapRepository()
}
