"""検証スクリプトの共通部品。音声は常にメモリ上で扱い、加工後の音声はディスクに書かない。"""
import json
import random
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
import soxr

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / ".cache"
RESULTS = ROOT / "results"
JVS = CACHE / "data" / "jvs_ver1"
SR = 16000
SEED = 20261031


@dataclass(frozen=True)
class Utt:
    spk: str
    subset: str
    uid: str
    text: str
    wav: Path
    lab: Path


def load_16k(path: Path) -> np.ndarray:
    x, sr = sf.read(str(path), dtype="float32", always_2d=False)
    if x.ndim > 1:
        x = x[:, 0]
    return soxr.resample(x, sr, SR, quality="HQ") if sr != SR else x


def speakers() -> list[str]:
    return sorted(p.name for p in JVS.glob("jvs[0-9]*") if p.is_dir())


def genders() -> dict[str, str]:
    out = {}
    for line in (JVS / "gender_f0range.txt").read_text().splitlines()[1:]:
        c = line.split()
        if len(c) >= 2:
            out[c[0]] = c[1]
    return out


def utts(spk: str, subset: str) -> list[Utt]:
    base = JVS / spk / subset
    res = []
    for line in (base / "transcripts_utf8.txt").read_text(encoding="utf-8").splitlines():
        if ":" not in line:
            continue
        uid, text = line.split(":", 1)
        wav = base / "wav24kHz16bit" / f"{uid}.wav"
        if wav.exists():
            res.append(Utt(spk, subset, uid, text.strip(), wav, base / "lab" / "mon" / f"{uid}.lab"))
    return res


def eval_set(per_spk: int = 3) -> list[Utt]:
    """全100話者から固定の乱数で選ぶ ASR 評価セット。話者ごとに nonpara30 を2、parallel100 を1。"""
    rng = random.Random(SEED)
    res = []
    for s in speakers():
        n = utts(s, "nonpara30")
        p = utts(s, "parallel100")
        rng.shuffle(n)
        rng.shuffle(p)
        res += n[: per_spk - 1] + p[:1]
    return res


def noise_files(envs=None) -> dict[str, list[Path]]:
    out = {}
    for d in sorted((CACHE / "noise").iterdir()):
        chs = sorted(d.glob("ch*.wav"))
        if chs and (envs is None or d.name in envs):
            out[d.name] = chs
    return out


_noise_cache: dict[Path, np.ndarray] = {}


def noise_clip(rng: np.random.Generator, n: int, envs=None) -> tuple[np.ndarray, str]:
    files = noise_files(envs)
    env = sorted(files)[rng.integers(len(files))]
    f = files[env][rng.integers(len(files[env]))]
    if f not in _noise_cache:
        _noise_cache[f] = load_16k(f)
    x = _noise_cache[f]
    start = rng.integers(0, max(1, len(x) - n))
    y = x[start : start + n]
    if len(y) < n:
        y = np.resize(y, n)
    return y.copy(), env


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x)) + 1e-12))


def active_rms(x: np.ndarray, frame: int = 480) -> float:
    """無音を除いた音声レベル。上位 50% のフレームの RMS。"""
    f = x[: len(x) // frame * frame].reshape(-1, frame)
    e = np.mean(f**2, axis=1)
    return float(np.sqrt(np.mean(np.sort(e)[len(e) // 2 :]) + 1e-12))


def mix_snr(speech: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    gain = active_rms(speech) / (rms(noise) * 10 ** (snr_db / 20))
    m = speech + noise * gain
    peak = np.max(np.abs(m))
    return m / peak * 0.95 if peak > 0.95 else m


def synth_rir(rng: np.random.Generator, rt60: float = 0.5, drr_db: float = 0.0) -> np.ndarray:
    """指数減衰の雑音で作る合成の残響。実測の部屋の応答ではない。"""
    n = int(rt60 * SR)
    t = np.arange(n) / SR
    h = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    h[: int(0.0025 * SR)] = 0
    h /= np.sqrt(np.sum(h**2)) + 1e-12
    direct = 10 ** (drr_db / 20)
    h[0] += direct
    return h / np.sqrt(np.sum(h**2))


def degrade(x: np.ndarray, cond: str, seed: int) -> np.ndarray:
    """clean / snr10 / snr5 / far(残響+SNR10)。seed は発話ごとに固定する。"""
    if cond == "clean":
        return x
    rng = np.random.default_rng(seed)
    if cond == "far":
        from scipy.signal import fftconvolve

        y = fftconvolve(x, synth_rir(rng, 0.5, 0.0))[: len(x)].astype(np.float32)
        y *= rms(x) / (rms(y) + 1e-9)
        return mix_snr(y, noise_clip(rng, len(y))[0], 10.0)
    snr = float(cond.removeprefix("snr"))
    return mix_snr(x, noise_clip(rng, len(x))[0], snr)


_PUNCT = re.compile(r"[\s、。,.!?!?・「」『』()()\[\]…‥ー―\-~〜:;\"'’“”]+")
_tagger = None


def norm_text(s: str) -> str:
    """表記の比較用。NFKC、小文字化、句読点と長音記号以外の記号を除く。"""
    s = unicodedata.normalize("NFKC", s).lower()
    s = re.sub(r"<\|[^|]*\|>|<[a-z-]+>", "", s)
    keep = s.replace("ー", "")
    keep = _PUNCT.sub("", keep)
    return keep.replace("", "ー")


def to_kana(s: str) -> str:
    """読み(カタカナ)に揃える。漢字とかな、算用数字の表記ゆれの影響を減らす指標。"""
    global _tagger
    if _tagger is None:
        import fugashi

        _tagger = fugashi.Tagger()
    out = []
    for w in _tagger(norm_text(s)):
        k = getattr(w.feature, "kana", None) or w.surface
        out.append(k)
    return "".join(out)


def cer_counts(ref: str, hyp: str) -> tuple[int, int]:
    from rapidfuzz.distance import Levenshtein

    return Levenshtein.distance(ref, hyp), len(ref)


def bootstrap_ci(errs: np.ndarray, lens: np.ndarray, n: int = 1000) -> tuple[float, float]:
    rng = np.random.default_rng(SEED)
    idx = rng.integers(0, len(errs), (n, len(errs)))
    v = errs[idx].sum(1) / lens[idx].sum(1)
    return float(np.percentile(v, 2.5)), float(np.percentile(v, 97.5))


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
