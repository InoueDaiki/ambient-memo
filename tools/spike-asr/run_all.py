#!/usr/bin/env python3
"""評価を順に流す。結果があるジョブは飛ばす。

精度(CER など)はジョブを並列に流す。速度は他の負荷を避けて1本ずつ流す。
使い方: python run_all.py asr-accuracy | asr-speed | vad | speaker | der | e2e
"""
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from common import RESULTS
from models import MODELS

PY = sys.executable
CONDS = ["clean", "snr10", "snr5", "far"]
VAD_RUNS = [("silero_vad_v5.onnx", 0.5), ("ten-vad.onnx", 0.5), ("silero_vad_v4.onnx", 0.5), ("ten-vad.int8.onnx", 0.5),
            ("silero_vad_v5.onnx", 0.3), ("silero_vad_v5.onnx", 0.7), ("ten-vad.onnx", 0.3), ("ten-vad.onnx", 0.7),
            ("silero_vad.onnx", 0.5), ("silero_vad.int8.onnx", 0.5), ("silero_vad.onnx", 0.3), ("silero_vad.onnx", 0.7)]
SPK = ["3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", "nemo_en_titanet_small.onnx",
       "wespeaker_zh_cnceleb_resnet34_LM.onnx", "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx",
       "wespeaker_en_voxceleb_resnet34_LM.onnx", "wespeaker_en_voxceleb_CAM++_LM.onnx",
       "nemo_en_titanet_large.onnx"]


def run(job):
    out, cmd = job
    if Path(out).exists():
        return
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(("OK " if r.returncode == 0 else "NG ") + " ".join(cmd[1:]), flush=True)
    if r.returncode:
        print(r.stderr[-800:], flush=True)


def pool(jobs, n):
    with ThreadPoolExecutor(n) as ex:
        list(ex.map(run, jobs))


def main():
    what = sys.argv[1]
    if what == "asr-accuracy":
        # 軽いモデルから流す
        jobs = [(RESULTS / "asr" / f"{m}__{c}__t2.json",
                 [PY, "asr_eval.py", "--model", m, "--cond", c, "--threads", "2"])
                for c in (sys.argv[3:] or CONDS) for m in MODELS]
        pool(jobs, int(sys.argv[2]) if len(sys.argv) > 2 else 4)
    elif what == "asr-speed":
        names = sys.argv[3:] or list(MODELS)
        for t in (1, 2, 4):
            for m in names:
                run((RESULTS / "asr" / f"{m}__clean__t{t}-speed.json",
                     [PY, "asr_eval.py", "--model", m, "--cond", "clean", "--threads", str(t), "--limit", "60", "--tag=-speed"]))
    elif what == "vad":
        pool([(RESULTS / "vad" / f"{m}__th{th}.json", [PY, "vad_eval.py", "--model", m, "--threshold", str(th)])
              for m, th in VAD_RUNS], 3)
    elif what == "e2e":
        for m in ("zipformer-reazon", "zipformer-reazon-ja-en", "parakeet-ja", "sensevoice-2024", "moonshine-base-ja",
                  "moonshine-tiny-ja", "zipformer-multi-streaming", "nemotron35-1120ms"):
            run((RESULTS / "e2e" / f"{m}.json", [PY, "e2e_eval.py", "--model", m, "--vad", "silero_vad.onnx"]))
            run((RESULTS / "e2e" / f"{m}-ctx03.json", [PY, "e2e_eval.py", "--model", m, "--vad", "silero_vad.onnx",
                                                       "--ctx", "0.3", "--zero", "0.3", "--tag=-ctx03"]))
    elif what == "der":
        e = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        for m, c, th in ((e, "clean", "0.6"), (e, "snr15", "0.57"), ("nemo_en_titanet_small.onnx", "clean", "0.6"),
                         ("wespeaker_en_voxceleb_CAM++_LM.onnx", "clean", "0.6")):
            run((RESULTS / "der" / f"{m}__{c}.json",
                 [PY, "der_eval.py", "--embed", m, "--vad", "silero_vad.onnx", "--th-self", th, "--cond", c]))
    elif what == "speaker":
        pool([(RESULTS / "speaker" / f"{m}.json", [PY, "spk_eval.py", "--model", m]) for m in SPK], 3)


if __name__ == "__main__":
    main()
