#!/usr/bin/env bash
set -euo pipefail
project="${1:?Project path required}"
runtime="$project/.ai-runtime/change-ai"
legacy="$HOME/.local/share/ondemand-change-ai"
if [[ ! -x "$runtime/venv/bin/python" && -x "$legacy/venv/bin/python" && -s "$legacy/changer.pth" && -d "$legacy/open-cd/.git" ]] &&
   "$legacy/venv/bin/python" -c 'import torch, mmcv, mmseg, fastapi, uvicorn' >/dev/null 2>&1; then
  runtime="$legacy"
  echo "Reusing the existing AI environment: $runtime"
fi
mkdir -p "$runtime"
export UV_CACHE_DIR="$runtime/cache"
export UV_PYTHON_INSTALL_DIR="$runtime/python"
if ! command -v curl >/dev/null || ! command -v git >/dev/null; then
  echo 'Installing curl, git and certificate tools for AI Worker.'
  sudo apt-get update
  sudo apt-get install -y curl git ca-certificates
fi
uv="$runtime/bin/uv"
if [[ ! -x "$uv" && -x "$HOME/.local/bin/uv" ]]; then
  uv="$HOME/.local/bin/uv"
elif [[ ! -x "$uv" ]] && command -v uv >/dev/null; then
  uv="$(command -v uv)"
fi
if [[ ! -x "$uv" ]]; then
  curl -fsSL https://astral.sh/uv/install.sh -o "$runtime/install-uv.sh"
  UV_INSTALL_DIR="$runtime/bin" UV_NO_MODIFY_PATH=1 sh "$runtime/install-uv.sh"
fi
if [[ ! -x "$runtime/venv/bin/python" ]]; then
  if command -v python3.11 >/dev/null; then
    "$uv" venv --python "$(command -v python3.11)" "$runtime/venv"
  else
    "$uv" venv --python 3.11 "$runtime/venv"
  fi
fi
python="$runtime/venv/bin/python"
if ! "$python" -c 'import torch, torchvision, mmcv, mmseg, fastapi, uvicorn, cv2, PIL' >/dev/null 2>&1; then
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
echo 'Starting AI worker at http://localhost:8000 (CPU). Stop with Ctrl+C.'
exec "$python" -m uvicorn app:app --host localhost --port 8000 --workers 1
