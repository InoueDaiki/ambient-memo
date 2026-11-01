#!/usr/bin/env python3
"""検証に使うモデルとデータを公式の配布元から取得する。

- 取得先は下の表に書いたものだけ。アカウントや規約への同意が要るものは入れない
- アーカイブは .cache/<種別>/<名前>/ に展開するだけで、中のスクリプトは実行しない
- 取得した本体の SHA-256 を results/fetch-lock.json に記録する

使い方: python fetch.py [--only 名前の一部 ...] [--group asr vad speaker noise jvm data]
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / ".cache"
LOCK = ROOT / "results" / "fetch-lock.json"
SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

ASR = [
    # ストリーミング
    "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
    "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11",
    "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
    "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11",
    # 非ストリーミング
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
    "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
    "sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17",
    "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
    "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27",
    "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27",
    "sherpa-onnx-whisper-tiny",
    "sherpa-onnx-whisper-base",
    "sherpa-onnx-whisper-small",
    "sherpa-onnx-whisper-turbo",
    "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
    "sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01",
    "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05",
]
VAD = ["silero_vad.onnx", "silero_vad.int8.onnx", "silero_vad_v5.onnx", "silero_vad_v4.onnx",
       "ten-vad.onnx", "ten-vad.int8.onnx"]
SPEAKER = [
    "wespeaker_en_voxceleb_CAM++_LM.onnx",
    "wespeaker_en_voxceleb_resnet34_LM.onnx",
    "wespeaker_en_voxceleb_resnet152_LM.onnx",
    "wespeaker_zh_cnceleb_resnet34_LM.onnx",
    "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
    "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx",
    "3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx",
    "nemo_en_titanet_small.onnx",
    "nemo_en_titanet_large.onnx",
]
SEGMENT = ["sherpa-onnx-pyannote-segmentation-3-0"]
NOISE = ["DKITCHEN", "DLIVING", "DWASHING", "OOFFICE", "OMEETING", "PCAFETER", "PSTATION",
         "PRESTO", "SPSQUARE", "STRAFFIC", "TBUS", "TCAR"]
# 突発音の誤検知を見る。全体は CC BY-NC 3.0
ESC50 = "https://github.com/karoldvl/ESC-50/archive/master.zip"
JVM = ["sherpa-onnx-jvm-2.0.1.jar", "sherpa-onnx-native-lib-osx-aarch64-2.0.1.jar", "sherpa-onnx-2.0.1.aar"]
ZENODO = "https://zenodo.org/api/records/1227121/files"

# JVS を使う前に、著者のページの利用規約を読むこと。
# JVS は著者のページ
# https://sites.google.com/site/shinnosuketakamichi/research-topics/jvs_corpus
# の公開リンク。ログインと規約への同意は不要(大きいファイルに出るウイルススキャン不可の確認だけ通す)
JVS_DRIVE_ID = "19oAw8wWn3Y7z6CKChRdAyGOB9yupL_Xt"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def curl(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    subprocess.run(["curl", "-fL", "--retry", "5", "-C", "-", "-sS", "-o", str(tmp), url], check=True)
    tmp.rename(dest)


def safe_extract_tar(archive: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tf:
        tf.extractall(out, filter="data")


def safe_extract_zip(archive: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    base = out.resolve()
    with zipfile.ZipFile(archive) as zf:
        for m in zf.infolist():
            if not (base / m.filename).resolve().is_relative_to(base):
                raise RuntimeError(f"不正なパス: {m.filename}")
        zf.extractall(out)


def jvs_url() -> str:
    """Drive の確認ページのフォームから、実際の取得 URL を組み立てる。"""
    url = f"https://drive.usercontent.google.com/download?id={JVS_DRIVE_ID}&export=download"
    with urllib.request.urlopen(url, timeout=60) as r:
        html = r.read(8000).decode("utf-8", "replace")
    if "Virus scan warning" not in html:
        raise RuntimeError("想定と違う応答。ログインや同意を求められていないか確認する")
    q = "&".join(f"{k}={v}" for k, v in re.findall(r'name="(\w+)" value="([^"]*)"', html))
    return f"https://drive.usercontent.google.com/download?{q}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", nargs="*", default=["asr", "vad", "speaker", "noise", "jvm", "data"])
    ap.add_argument("--only", nargs="*", default=[])
    a = ap.parse_args()
    lock = json.loads(LOCK.read_text()) if LOCK.exists() else {}

    jobs: list[tuple[str, str, Path, str]] = []
    if "asr" in a.group:
        for n in ASR:
            jobs.append((n, f"{SHERPA}/asr-models/{n}.tar.bz2", CACHE / "dl" / f"{n}.tar.bz2", "tar:asr"))
    if "vad" in a.group:
        for n in VAD:
            jobs.append((n, f"{SHERPA}/asr-models/{n}", CACHE / "vad" / n, "raw"))
    if "speaker" in a.group:
        for n in SPEAKER:
            jobs.append((n, f"{SHERPA}/speaker-recongition-models/{n}", CACHE / "speaker" / n, "raw"))
        for n in SEGMENT:
            jobs.append((n, f"{SHERPA}/speaker-segmentation-models/{n}.tar.bz2",
                         CACHE / "dl" / f"{n}.tar.bz2", "tar:segment"))
    if "noise" in a.group:
        for n in NOISE:
            jobs.append((n, f"{ZENODO}/{n}_16k.zip/content", CACHE / "dl" / f"{n}_16k.zip", "zip:noise"))
    if "jvm" in a.group:
        for n in JVM:
            jobs.append((n, f"{SHERPA}/v2.0.1/{n}", CACHE / "jvm" / n, "raw"))
    if "noise" in a.group:
        jobs.append(("ESC-50", ESC50, CACHE / "dl" / "ESC-50.zip", "zip:noise"))
    if "data" in a.group:
        jobs.append(("jvs_ver1", "", CACHE / "dl" / "jvs_ver1.zip", "zip:data"))

    for name, url, dest, kind in jobs:
        if a.only and not any(o in name for o in a.only):
            continue
        if name in lock and dest.exists():
            print("済", name, flush=True)
            continue
        print("取得", name, flush=True)
        if not dest.exists():
            curl(url or jvs_url(), dest)
        if kind.startswith("tar:"):
            safe_extract_tar(dest, CACHE / kind[4:])
        elif kind.startswith("zip:"):
            safe_extract_zip(dest, CACHE / kind[4:])
        lock[name] = {"url": url or "(JVS の Drive 公開リンク)", "bytes": dest.stat().st_size, "sha256": sha256(dest)}
        LOCK.parent.mkdir(exist_ok=True)
        LOCK.write_text(json.dumps(lock, indent=1, ensure_ascii=False) + "\n")
    print("完了", flush=True)


if __name__ == "__main__":
    sys.exit(main())
