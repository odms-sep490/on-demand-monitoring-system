$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$python = Join-Path $repo '.ai-runtime/vieneu-py311/Scripts/python.exe'
if (!(Test-Path -LiteralPath $python)) {
    throw 'The local Python 3.11 TTS environment is missing. Restore it before installing ZeroTTS.'
}

$env:HF_HUB_DISABLE_XET = '1'
$env:PYTHONUTF8 = '1'
$ErrorActionPreference = 'Continue'
@'
import importlib.util
import sys
sys.exit(0 if importlib.util.find_spec('zerotts') else 1)
'@ | & $python - 2>$null
$needsInstall = $LASTEXITCODE -ne 0
$ErrorActionPreference = 'Stop'
if ($needsInstall) {
    Write-Host '[Setup] ZeroTTS is missing; installing...'
    & $python -m pip install -r (Join-Path $repo 'Python/voice-order/requirements-zerotts.txt')
    if ($LASTEXITCODE -ne 0) { throw 'ZeroTTS package installation failed.' }
} else {
    Write-Host '[Setup] ZeroTTS is already installed; skipping package installation.'
}

$env:ZEROTTS_MODEL_REVISION = '18d6681518a4becaed3c125ceb3c7db59905fe86'
@'
import os
from huggingface_hub import hf_hub_download, try_to_load_from_cache
from pathlib import Path

repo_id = "zeroweight-ai/ZeroTTS"
revision = os.environ["ZEROTTS_MODEL_REVISION"]
files = (
    "config.json",
    "tokenizer.json",
    "null_voice_emb.npy",
    "onnx/text_encoder.onnx",
    "onnx/prefix_step.onnx",
    "onnx/local_frame_decode.onnx",
    "onnx/codec/codec_browser_onnx_meta.json",
    "onnx/codec/moss_audio_tokenizer_decode_full.onnx",
    "onnx/codec/moss_audio_tokenizer_decode_shared.data",
    "onnx/codec/moss_audio_tokenizer_decode_step.onnx",
    "voices/hamy/voice.npz",
    "voices/maichi/voice.npz",
)
for filename in files:
    cached = try_to_load_from_cache(repo_id=repo_id, filename=filename, revision=revision)
    if isinstance(cached, str) and Path(cached).is_file() and Path(cached).stat().st_size > 0:
        print(f"[Reuse] {filename}", flush=True)
        continue
    print(f"[Download] Missing ZeroTTS file: {filename}", flush=True)
    path = hf_hub_download(repo_id=repo_id, filename=filename, revision=revision)
    print(f"Cached {filename} at {path}")
'@ | & $python -
if ($LASTEXITCODE -ne 0) { throw 'ZeroTTS model download failed. Check the network and run this installer again.' }

Write-Host 'ZeroTTS and the Hà My / Mai Chi voice packs are installed locally.'
