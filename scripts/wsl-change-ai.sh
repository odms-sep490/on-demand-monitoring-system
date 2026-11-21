#!/usr/bin/env bash
set -euo pipefail
project="${1:?Project path required}"
runtime="$HOME/.local/share/ondemand-change-ai"
mkdir -p "$runtime"
if [[ ! -x "$HOME/.local/bin/uv" ]]; then
  curl -fsSL https://astral.sh/uv/install.sh -o "$runtime/install-uv.sh"
  sh "$runtime/install-uv.sh"
fi
uv="$HOME/.local/bin/uv"
if [[ ! -x "$runtime/venv/bin/python" ]]; then
  "$uv" venv --python 3.11 "$runtime/venv"
fi
python="$runtime/venv/bin/python"
if [[ ! -f "$runtime/dependencies-v2.ready" ]]; then
  "$uv" pip install --python "$python" torch==2.0.0 torchvision==0.15.1 --index-url https://download.pytorch.org/whl/cpu
  "$uv" pip install --python "$python" -r "$project/Python/change-detection/requirements.txt" gdown --find-links https://download.openmmlab.com/mmcv/dist/cpu/torch2.0/index.html --only-binary mmcv
  touch "$runtime/dependencies-v2.ready"
fi
if [[ ! -d "$runtime/open-cd/.git" ]]; then
  git clone https://github.com/likyoo/open-cd.git "$runtime/open-cd"
  git -C "$runtime/open-cd" checkout 790a1972b538baaaffd4c3022a4d9afaa3f861e5
fi
if [[ ! -s "$runtime/changer.pth" ]]; then
  "$runtime/venv/bin/gdown" '1SZ8DBgBryJ63X9AU0AQvaJ2ZH3oQHQHe' -O "$runtime/changer.pth.download"
  mv "$runtime/changer.pth.download" "$runtime/changer.pth"
  sha256sum "$runtime/changer.pth" > "$runtime/changer.pth.sha256"
fi
if [[ -f "$runtime/changer.pth.sha256" ]]; then
  sha256sum --check "$runtime/changer.pth.sha256"
else
  sha256sum "$runtime/changer.pth" > "$runtime/changer.pth.sha256"
fi
# Read only the worker token; never source the project's shell-incompatible .env.
export OPENCD_TOKEN
OPENCD_TOKEN=$(sed -n 's/^OPENCD_TOKEN=//p' "$project/.env" | tail -1 | tr -d '\r')
[[ -n "$OPENCD_TOKEN" ]] || { echo 'Missing OPENCD_TOKEN in project .env'; exit 1; }
export OPENCD_CONFIG="$runtime/open-cd/configs/changer/changer_ex_r18_512x512_40k_levircd.py"
export OPENCD_CHECKPOINT="$runtime/changer.pth"
export OPENCD_CHECKPOINT_SHA256
OPENCD_CHECKPOINT_SHA256=$(sha256sum "$OPENCD_CHECKPOINT" | cut -d' ' -f1)
export OPENCD_DEVICE=cpu PYTHONPATH="$runtime/open-cd"
export IMAGE_QUALITY_INTERNAL_TOKEN
IMAGE_QUALITY_INTERNAL_TOKEN=$(sed -n 's/^IMAGE_QUALITY_INTERNAL_TOKEN=//p' "$project/.env" | tail -1 | tr -d '\r')
export IMAGE_QUALITY_MAX_IMAGE_SIZE=8388608
cd "$project/Python/change-detection"
echo 'Starting AI worker on 127.0.0.1:8000 (CPU). Stop with Ctrl+C.'
exec "$python" -m uvicorn app:app --host 127.0.0.1 --port 8000 --workers 1
