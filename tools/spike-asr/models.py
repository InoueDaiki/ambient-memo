"""評価する ASR モデルの一覧と、sherpa-onnx の認識器の作り方。"""
from pathlib import Path

import sherpa_onnx

from common import CACHE

A = CACHE / "asr"


def _g(d: Path, *pats: str) -> str:
    """最初に見つかったパターンのファイルを返す。int8 を先に書く。"""
    for p in pats:
        hit = sorted(d.glob(p))
        if hit:
            return str(hit[0])
    raise FileNotFoundError(f"{d}: {pats}")


def _trans(d: Path, cls, threads: int, **kw):
    return cls.from_transducer(
        encoder=_g(d, "encoder*int8*.onnx", "encoder*.onnx"),
        decoder=_g(d, "decoder*.onnx"),
        joiner=_g(d, "joiner*int8*.onnx", "joiner*.onnx"),
        tokens=_g(d, "tokens.txt"),
        num_threads=threads,
        **kw,
    )


def _whisper(name: str, threads: int):
    d = A / f"sherpa-onnx-whisper-{name}"
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=_g(d, "*encoder.int8.onnx"), decoder=_g(d, "*decoder.int8.onnx"),
        tokens=_g(d, "*tokens.txt"), language="ja", task="transcribe", num_threads=threads,
    )


def _moonshine(d: Path, threads: int):
    return sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
        encoder=_g(d, "encoder*.ort", "encoder*.onnx"),
        decoder=_g(d, "decoder*.ort", "decoder*.onnx"),
        tokens=_g(d, "tokens.txt"), num_threads=threads,
    )


def _sense(date: str, threads: int):
    d = A / f"sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-{date}"
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=_g(d, "model.int8.onnx"), tokens=_g(d, "tokens.txt"), language="ja", use_itn=True,
        num_threads=threads,
    )


def _nemotron(ms: int, threads: int):
    d = A / f"sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-{ms}ms-int8-2026-06-11"
    return _nemo_on(d, threads)


def _nemo_on(d: Path, threads: int):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        encoder=_g(d, "encoder*int8*.onnx", "encoder*.onnx"), decoder=_g(d, "decoder*.onnx"),
        joiner=_g(d, "joiner*int8*.onnx", "joiner*.onnx"), tokens=_g(d, "tokens.txt"),
        num_threads=threads, model_type="nemo_transducer", sample_rate=16000,
    )


MODELS = {
    # 名前: (種別, 作り方, 表示に使うディレクトリ)
    "zipformer-multi-streaming": ("online", lambda t: _trans(
        A / "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
        sherpa_onnx.OnlineRecognizer, t)),
    "nemotron35-160ms": ("online", lambda t: _nemotron(160, t)),
    "nemotron35-560ms": ("online", lambda t: _nemotron(560, t)),
    "nemotron35-1120ms": ("online", lambda t: _nemotron(1120, t)),
    "sensevoice-2024": ("offline", lambda t: _sense("2024-07-17", t)),
    "sensevoice-2025": ("offline", lambda t: _sense("2025-09-09", t)),
    "zipformer-reazon": ("offline", lambda t: _trans(
        A / "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01", sherpa_onnx.OfflineRecognizer, t)),
    "zipformer-reazon-ja-en": ("offline", lambda t: _trans(
        A / "sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17", sherpa_onnx.OfflineRecognizer, t)),
    "parakeet-ja": ("offline", lambda t: sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=_g(A / "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8", "model*.onnx"),
        tokens=_g(A / "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8", "tokens.txt"),
        num_threads=t)),
    "moonshine-tiny-ja": ("offline", lambda t: _moonshine(A / "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27", t)),
    "moonshine-base-ja": ("offline", lambda t: _moonshine(A / "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27", t)),
    "whisper-tiny": ("offline", lambda t: _whisper("tiny", t)),
    "whisper-base": ("offline", lambda t: _whisper("base", t)),
    "whisper-small": ("offline", lambda t: _whisper("small", t)),
    "whisper-turbo": ("offline", lambda t: _whisper("turbo", t)),
    "qwen3-asr-0.6b": ("offline", lambda t: _qwen(t)),
    "cohere-transcribe": ("offline", lambda t: _cohere(t)),
    "omnilingual-300m": ("offline", lambda t: _omni(t)),
}


def _qwen(t):
    d = A / "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    tok = sorted(p.parent for p in d.rglob("vocab.json"))[0]
    return sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=_g(d, "conv*int8*.onnx", "conv*.onnx"), encoder=_g(d, "encoder*int8*.onnx", "encoder*.onnx"),
        decoder=_g(d, "decoder*int8*.onnx", "decoder*.onnx"), tokenizer=str(tok), num_threads=t,
    )


def _cohere(t):
    d = A / "sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01"
    return sherpa_onnx.OfflineRecognizer.from_cohere_transcribe(
        encoder=_g(d, "encoder*int8*.onnx", "encoder*.onnx"), decoder=_g(d, "decoder*int8*.onnx", "decoder*.onnx"),
        tokens=_g(d, "tokens.txt"), language="ja", num_threads=t,
    )


def _omni(t):
    d = A / "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05"
    return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=_g(d, "model*.onnx"), tokens=_g(d, "tokens.txt"), num_threads=t,
    )


def model_dir(name: str) -> Path | None:
    table = {
        "zipformer-multi-streaming": "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
        "nemotron35-160ms": "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11",
        "nemotron35-560ms": "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        "nemotron35-1120ms": "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11",
        "sensevoice-2024": "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        "sensevoice-2025": "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        "zipformer-reazon": "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
        "zipformer-reazon-ja-en": "sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17",
        "parakeet-ja": "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
        "moonshine-tiny-ja": "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27",
        "moonshine-base-ja": "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27",
        "whisper-tiny": "sherpa-onnx-whisper-tiny",
        "whisper-base": "sherpa-onnx-whisper-base",
        "whisper-small": "sherpa-onnx-whisper-small",
        "whisper-turbo": "sherpa-onnx-whisper-turbo",
        "qwen3-asr-0.6b": "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        "cohere-transcribe": "sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01",
        "omnilingual-300m": "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05",
    }
    return A / table[name] if name in table else None
