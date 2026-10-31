#!/usr/bin/env python3
"""results/ の JSON を Markdown の表にして標準出力に出す。"""
import json
from pathlib import Path

from common import RESULTS
from models import MODELS


def load(sub):
    return [json.loads(p.read_text()) | {"_f": p.stem} for p in sorted((RESULTS / sub).glob("*.json"))]


def asr():
    rows = load("asr")
    acc = {(r["model"], r["cond"]): r for r in rows if "-speed" not in r["_f"]}
    spd = {(r["model"], r["threads"]): r for r in rows if "-speed" in r["_f"]}
    conds = ["clean", "snr10", "snr5", "far"]
    # 全 300 発話を流せなかった重いモデルは、速度計測の 60 発話の CER を使う
    for (m, t), r in spd.items():
        if t == 2:
            acc.setdefault((m, "clean"), r)
    print("### 精度(CER %、表記 / 読み)\n")
    print("| モデル | 種別 | n | " + " | ".join(conds) + " | clean の 95% 区間(表記) |")
    print("|---|---|---|" + "---|" * (len(conds) + 1))
    order = sorted(MODELS, key=lambda m: acc.get((m, "clean"), {}).get("cer_surface", {}).get("cer", 999))
    for m in order:
        if (m, "clean") not in acc:
            continue
        cells = []
        for c in conds:
            r = acc.get((m, c))
            cells.append(f"{r['cer_surface']['cer']:.1f} / {r['cer_kana']['cer']:.1f}" if r else "-")
        ci = acc[(m, "clean")]["cer_surface"]["ci95"]
        print(f"| {m} | {MODELS[m][0]} | {acc[(m, 'clean')]['n']} | " + " | ".join(cells) + f" | {ci[0]:.1f}〜{ci[1]:.1f} |")
    print("\n### 速度とメモリ(60 発話、JVS clean)\n")
    print("| モデル | 容量 MB | 実時間比 1 / 2 / 4 スレッド | 遅延 p95 秒(2 スレッド) | 読み込み秒 | ピーク RSS MB(2 スレッド) |")
    print("|---|---|---|---|---|---|")
    for m in order:
        if (m, 2) not in spd:
            continue
        rr = [spd.get((m, t)) for t in (1, 2, 4)]
        r = rr[1]
        rt = " / ".join(f"{x['rtf']:.3f}" if x else "-" for x in rr)
        print(f"| {m} | {r['disk_mb']:.0f} | {rt} | {r['latency_p95_s']:.2f} | {r['load_s']:.1f} | {r['rss_peak_mb']:.0f} |")


def vad():
    rows = load("vad")
    print("\n### VAD\n")
    print("| モデル | しきい値 | 発話の取りこぼし % clean/20/10/5/0 dB | フレーム取りこぼし % 10 dB | 誤検知 回/時(非音声区間 10 dB) | 環境音のみ 回/時 | 突発音 回/時 | 稼働率 % 環境音 | 語頭の遅れ p95 秒 10 dB | KB |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        s = r["speech"]
        miss = "/".join(f"{s[c]['utt_miss_pct']:.1f}" for c in ("clean", "snr20", "snr10", "snr5", "snr0"))
        env = {k: v for k, v in r["noise_only"].items() if not k.startswith("esc50")}
        esc = {k: v for k, v in r["noise_only"].items() if k.startswith("esc50")}
        def rate(d):
            h = sum(v["hours"] for v in d.values())
            return sum(v["false_per_h"] * v["hours"] for v in d.values()) / h
        duty = sum(v["duty_pct"] * v["hours"] for v in env.values()) / sum(v["hours"] for v in env.values())
        print(f"| {r['model'].removesuffix('.onnx')} | {r['threshold']} | {miss} | {s['snr10']['frame_miss_pct']:.1f} | {s['snr10']['false_per_nonspeech_h']:.0f} | {rate(env):.0f} | {rate(esc):.0f} | {duty:.1f} | {s['snr10']['head_late_p95_s']:.2f} | {r['size_kb']} |")


def speaker():
    rows = load("speaker")
    print("\n### 話者 embedding\n")
    print("| モデル | MB | EER % clean 全長 | 3 秒 | 1.5 秒 | 10 dB 3 秒 | 残響+10 dB 3 秒 | 後半話者の FRR %(FAR 目標 1%、clean 3 秒) | 同 FAR 実測 |")
    print("|---|---|---|---|---|---|---|---|---|")
    rows.sort(key=lambda r: r["by"]["clean/3s"]["eer_pct"])
    for r in rows:
        b = r["by"]
        print(f"| {r['model'].removesuffix('.onnx')} | {r['size_mb']:.0f} | {b['clean/full']['eer_pct']:.2f} | {b['clean/3s']['eer_pct']:.2f} | {b['clean/1.5s']['eer_pct']:.2f} | {b['snr10/3s']['eer_pct']:.2f} | {b['far/3s']['eer_pct']:.2f} | {b['clean/3s']['eval_frr_pct']:.2f} | {b['clean/3s']['eval_far_pct']:.2f} |")


def der():
    rows = load("der")
    if not rows:
        return
    print("\n### DER(合成会話、参考値)\n")
    print("| embedding | 条件 | offline DER %(collar 0 / 0.25) | 実時間比 | app DER %(collar 0 / 0.25) | 自分の再現率 % | 他人を自分と誤る率 % |")
    print("|---|---|---|---|---|---|---|")
    for r in rows:
        o = r["offline"]
        for key in ("app", "app_sil02"):
            a = r[key]
            print(f"| {r['embed'].removesuffix('.onnx')} | {r['cond']} | {o['nocollar']['der_pct']:.1f} / {o['collar025']['der_pct']:.1f} | {o['rtf']:.3f} | 無音 {a['min_silence']} 秒: {a['nocollar']['der_pct']:.1f} / {a['collar025']['der_pct']:.1f} | {a['self_recall_pct']:.1f} | {a['other_as_self_pct']:.1f} |")


def e2e():
    rows = load("e2e")
    if not rows:
        return
    print("\n### VAD から ASR まで(80 発話、CER %、表記 / 読み)\n")
    print("| モデル | 区間の前後 | VAD | 区切りの無音 | clean | SNR 10 dB | 区間数 clean / 10 dB | 雑音区間のうち文字が出た % | 平均文字数 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        c, n = r["e2e"]["clean"], r["e2e"]["snr10"]
        pad = f"音声 {r.get('ctx', 0)} + 無音 {r.get('zero', 0)}"
        print(f"| {r['model']} | {pad} | {r['vad'].removesuffix('.onnx')} | {r.get('min_silence', 0.5)} | {c['cer_surface']:.1f} / {c['cer_kana']:.1f} | {n['cer_surface']:.1f} / {n['cer_kana']:.1f} | {c['segments']} / {n['segments']} | {r['noise_only']['with_text_pct']:.0f} | {r['noise_only']['mean_chars']:.1f} |")


def misc():
    rows = load("speed")
    if not rows:
        return
    print("\n### VAD と embedding の速度(1 スレッド、雑音データなしの別プロセス)\n")
    print("| モデル | 実時間比 | ピーク RSS MB |")
    print("|---|---|---|")
    for r in rows:
        print(f"| {r['model'].removesuffix('.onnx')} | {r['rtf_1thread']:.4f} | {r['rss_peak_mb']:.0f} |")


if __name__ == "__main__":
    asr()
    vad()
    misc()
    speaker()
    der()
    e2e()
