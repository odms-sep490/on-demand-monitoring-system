#!/usr/bin/env bash
set -euo pipefail

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
PX4_ROOT="${PX4_ROOT:-$HOME/PX4-Autopilot}"
PX4_COMMIT="${PX4_COMMIT:-cbc6a6f7abbd06fbb2ff0aed3e57e3e95f88232e}"
DRONE_WORKDIR="${DRONE_WORKDIR:-$HOME/drone-controller}"
DRONE_ENV="${DRONE_ENV:-$HOME/drone-env}"
MARKER_DIR="$HOME/.cache/ondemand-drone-stack"
MARKER_FILE="$MARKER_DIR/bootstrap-v1.done"

log() {
    printf '\n==> %s\n' "$1"
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1
}

PX4_BUILD_READY=0
if [ -f "$PX4_ROOT/Tools/setup/ubuntu.sh" ] \
    && [ -f "$PX4_ROOT/build/px4_sitl_default/rootfs/gz_env.sh" ]; then
    PX4_BUILD_READY=1
fi

PYTHON_ENV_READY=0
if [ -x "$DRONE_ENV/bin/python" ] \
    && "$DRONE_ENV/bin/python" - <<'PY' >/dev/null 2>&1
import cv2
import grpc
import httpx
import mavsdk
from mavsdk.action import ActionError
from PIL import Image
import dotenv
from pathlib import Path

server = Path(mavsdk.__file__).resolve().parent / "bin" / "mavsdk_server"
if not server.is_file():
    raise SystemExit("mavsdk_server missing")
PY
then
    PYTHON_ENV_READY=1
fi

if ! need_cmd ffmpeg || ! need_cmd ffprobe; then
    log "Installing FFmpeg for browser-compatible H.264 video review"
    sudo apt-get update
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y ffmpeg
fi

if [ "$PX4_BUILD_READY" -eq 1 ] && [ "$PYTHON_ENV_READY" -eq 1 ]; then
    mkdir -p "$DRONE_WORKDIR/video" "$MARKER_DIR"
    date -Is > "$MARKER_FILE"
    echo "[BOOTSTRAP] Existing drone stack dependencies found"
    exit 0
fi

log "Installing Ubuntu packages"
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ca-certificates \
    curl \
    git \
    lsb-release \
    python3 \
    python3-pip \
    python3-venv \
    rsync \
    unzip \
    wget

if [ ! -d "$PX4_ROOT/.git" ]; then
    log "Downloading PX4 Autopilot"
    git clone --recursive https://github.com/PX4/PX4-Autopilot.git "$PX4_ROOT"
    git -C "$PX4_ROOT" checkout --detach "$PX4_COMMIT"
    git -C "$PX4_ROOT" submodule update --init --recursive
else
    log "Updating PX4 Autopilot submodules"
    git -C "$PX4_ROOT" submodule update --init --recursive
fi

log "Installing PX4 and Gazebo dependencies"
bash "$PX4_ROOT/Tools/setup/ubuntu.sh" --no-nuttx

log "Preparing Python controller environment"
python3 -m venv "$DRONE_ENV"
# shellcheck disable=SC1090
source "$DRONE_ENV/bin/activate"
python -m pip install --upgrade pip
python -m pip uninstall -y em mavsdk mavsdk-grpc >/dev/null 2>&1 || true
python -m pip install \
    "empy==3.3.4" \
    grpcio \
    httpx \
    "mavsdk<4" \
    numpy \
    opencv-python \
    pillow \
    python-dotenv
if [ -f "$PX4_ROOT/Tools/setup/requirements.txt" ]; then
    python -m pip install -r "$PX4_ROOT/Tools/setup/requirements.txt"
fi
python -m pip install --force-reinstall "empy==3.3.4"

log "Preparing controller working folder"
mkdir -p "$DRONE_WORKDIR/video" "$MARKER_DIR"

log "Building PX4 SITL once"
if [ ! -f "$PX4_ROOT/build/px4_sitl_default/rootfs/gz_env.sh" ]; then
    rm -rf "$PX4_ROOT/build/px4_sitl_default"
fi
make -C "$PX4_ROOT" px4_sitl gz_x500_mono_cam_down

date -Is > "$MARKER_FILE"
echo "[BOOTSTRAP] Drone stack dependencies are ready"
