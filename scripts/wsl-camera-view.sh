#!/usr/bin/env bash
set -e

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
FOREST3D_PATH="${FOREST3D_PATH:-$PROJECT_PATH/Forest3D}"
DRONE_PATH="${DRONE_PATH:-$PROJECT_PATH/drone}"
ENV_FILE="$PROJECT_PATH/.env"

if [ -f "$ENV_FILE" ]; then
    set -a
    # Strip Windows BOM/CRLF endings while keeping the source env unchanged.
    source <(sed '1s/^\xEF\xBB\xBF//; s/\r$//; /^MAPILLARY_ACCESS_TOKEN=/{s/\\|/|/g;s/|/\\|/g}' "$ENV_FILE")
    set +a
fi

SIM_WORLD="${SIM_WORLD:-compact}"

if [ "$SIM_WORLD" = "compact" ]; then
    WORLD_NAME="forest_monitoring_compact"
else
    WORLD_NAME="${GZ_WORLD_NAME:-forest_monitoring}"
fi

MODEL_NAME="${GZ_MODEL_NAME:-x500_mono_cam_down_0}"

CAMERA_DOWN_TOPIC="${GAZEBO_CAMERA_DOWN_TOPIC:-/world/${WORLD_NAME}/model/${MODEL_NAME}/link/camera_link/sensor/camera_down/image}"
CAMERA_FRONT_TOPIC="${GAZEBO_CAMERA_FRONT_TOPIC:-/world/${WORLD_NAME}/model/${MODEL_NAME}/link/camera_link/sensor/camera_front/image}"
CAMERA_TOPIC="${GAZEBO_CAMERA_TOPIC:-$CAMERA_DOWN_TOPIC}"

CAMERA_VIEW_CONFIG="$FOREST3D_PATH/gui/downward_camera_view.config"
RUNTIME_CONFIG="/tmp/downward_camera_view_${WORLD_NAME}.config"

echo "========================================"
echo " Downward Camera Viewer"
echo "========================================"
echo "World : $WORLD_NAME"
echo "Model : $MODEL_NAME"
echo "Down  : $CAMERA_DOWN_TOPIC"
echo "Front : $CAMERA_FRONT_TOPIC"
echo

echo "[CAMERA] Waiting for camera stream..."

DRONE_ENV="${DRONE_ENV:-$HOME/drone-env}"
source "$DRONE_ENV/bin/activate"

for _ in $(seq 1 60); do

    if gz topic -l 2>/dev/null | grep -Fxq "$CAMERA_DOWN_TOPIC" \
        && gz topic -l 2>/dev/null | grep -Fxq "$CAMERA_FRONT_TOPIC"; then

        echo "[CAMERA] Topics found."
        echo "[CAMERA] Opening HUD viewer:"
        echo "         DOWN : $CAMERA_DOWN_TOPIC"
        echo "         FRONT: $CAMERA_FRONT_TOPIC"

        sleep 1

        cd "$DRONE_PATH"
        exec python3 downward_camera_viewer.py
    fi

    sleep 1
done

echo
echo "[ERROR] Camera topics were not found:"
echo "DOWN : $CAMERA_DOWN_TOPIC"
echo "FRONT: $CAMERA_FRONT_TOPIC"
echo
echo "Available camera topics:"
gz topic -l 2>/dev/null | grep -Ei "camera|image" || true

exec bash
