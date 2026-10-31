#!/usr/bin/env python3
"""検証に使うモデルを、固定したリビジョンから取得して SHA-256 を確かめる。

取得元の条件: モデルの提供元(Google、Liquid AI、Qwen)が公開しているか、LiteRT-LM の公式
README が案内する系列のリポジトリであること。ログインと利用規約への同意は使わない。
個人による変換物は使わない。理由は docs/検証-LLM.md を参照。
"""
import hashlib
import json
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"

# (リポジトリ, リビジョン, ファイル名)
FILES = [
    ("litert-community/gemma-4-E2B-it-litert-lm", "1caa527c1e88b55f046fcd8d2b4265606680663d", "gemma-4-E2B-it.litertlm"),
    ("litert-community/gemma-4-E4B-it-litert-lm", "e6a3892a8c22cf87ff99583996149cf3cb32452b", "gemma-4-E4B-it.litertlm"),
    ("LiquidAI/LFM2.5-1.2B-JP-202606-GGUF", "448ba3f7d408c2f5c32cec8038612f7c1ed9f054", "LFM2.5-1.2B-JP-202606-Q8_0.gguf"),
    ("Qwen/Qwen3-4B-GGUF", "bc640142c66e1fdd12af0bd68f40445458f3869b", "Qwen3-4B-Q4_K_M.gguf"),
    ("google/gemma-4-E2B-it-qat-q4_0-gguf", "675cff42a74c774d6cb76f76d8eacb49b48c9b93", "gemma-4-E2B_q4_0-it.gguf"),
    ("litert-community/embeddinggemma-2-text-270m-litert-lm", "9be6e8b90982095dc05c2bd162e4b954ee4dbac7", "embeddinggemma-2-text-270m.litertlm"),
]


def expected(repo: str, rev: str, name: str) -> tuple[str, int]:
    url = f"https://huggingface.co/api/models/{repo}/revision/{rev}?blobs=true"
    meta = json.load(urllib.request.urlopen(url, timeout=60))
    if meta.get("gated"):
        sys.exit(f"{repo} はゲート付きなので取得しない")
    for s in meta["siblings"]:
        if s["rfilename"] == name:
            return s["lfs"]["sha256"], s["size"]
    sys.exit(f"{name} が {repo}@{rev} にない")


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 24):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    MODELS.mkdir(exist_ok=True)
    manifest = []
    for repo, rev, name in FILES:
        want, size = expected(repo, rev, name)
        dest = MODELS / name
        if not (dest.exists() and dest.stat().st_size == size):
            url = f"https://huggingface.co/{repo}/resolve/{rev}/{name}"
            print("取得", url, flush=True)
            part = dest.with_suffix(dest.suffix + ".part")
            with urllib.request.urlopen(url, timeout=120) as r, part.open("wb") as f:
                while chunk := r.read(1 << 22):
                    f.write(chunk)
            part.rename(dest)
        got = sha256(dest)
        if got != want:
            sys.exit(f"SHA-256 不一致: {name}\n  期待 {want}\n  実際 {got}")
        print("OK", name, size, got, flush=True)
        manifest.append({"repo": repo, "revision": rev, "file": name, "bytes": size, "sha256": got})
    (ROOT / "results" / "models.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
