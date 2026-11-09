#!/usr/bin/env bash
set -euo pipefail

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
FOREST3D_PATH="${FOREST3D_PATH:-$PROJECT_PATH/Forest3D}"
PX4_ROOT="$HOME/PX4-Autopilot"
SIM_WORLD="${SIM_WORLD:-compact}"
ENV_FILE="$PROJECT_PATH/.env"

if [ -f "$ENV_FILE" ]; then
    set -a
    # Strip Windows BOM/CRLF endings while keeping the source .env unchanged.
    source <(sed '1s/^\xEF\xBB\xBF//; s/\r$//; /^MAPILLARY_ACCESS_TOKEN=/{s/\\|/|/g;s/|/\\|/g}' "$ENV_FILE")
    set +a
fi

case "$SIM_WORLD" in
    compact)
        WORLD_NAME="forest_monitoring_compact"
        MODEL_PREFIX="x500_mono_cam_down"
        ;;
    legacy)
        WORLD_NAME="forest_monitoring"
        MODEL_PREFIX="x500"
        ;;
    *)
        WORLD_NAME="${GZ_WORLD_NAME:-forest_monitoring_compact}"
        MODEL_PREFIX="${GZ_MODEL_PREFIX:-x500}"
        ;;
esac

export GZ_SIM_RESOURCE_PATH="${FOREST3D_PATH}:${FOREST3D_PATH}/models:$PX4_ROOT/Tools/simulation/gz/models:$PX4_ROOT/Tools/simulation/gz/worlds:${GZ_SIM_RESOURCE_PATH:-}"
export PYTHONPATH="$PROJECT_PATH:${PYTHONPATH:-}"
export SENSOR_VIS_ENABLED="${SENSOR_VIS_ENABLED:-true}"
export SENSOR_VIS_LIDAR_2D_ENABLED="${SENSOR_VIS_LIDAR_2D_ENABLED:-true}"
export SENSOR_VIS_LIDAR_3D_ENABLED="${SENSOR_VIS_LIDAR_3D_ENABLED:-true}"
export SENSOR_VIS_THERMAL_ENABLED="${SENSOR_VIS_THERMAL_ENABLED:-false}"
export LIDAR_TOPIC="${LIDAR_TOPIC:-/lidar}"
export POINTCLOUD_LIDAR_TOPIC="${POINTCLOUD_LIDAR_TOPIC:-/lidar_3d}"
export GAZEBO_CAMERA_TOPIC="${GAZEBO_CAMERA_TOPIC:-/world/${WORLD_NAME}/model/${MODEL_PREFIX}_0/link/camera_link/sensor/camera/image}"
export GAZEBO_THERMAL_CAMERA_TOPIC="${GAZEBO_THERMAL_CAMERA_TOPIC:-/world/${WORLD_NAME}/model/${MODEL_PREFIX}_0/link/thermal_camera_link/sensor/thermal_camera/image}"
export QT_QPA_FONTDIR="${QT_QPA_FONTDIR:-/usr/share/fonts/truetype/dejavu}"
export QT_QPA_PLATFORMTHEME="${QT_QPA_PLATFORMTHEME:-}"

echo "========================================"
echo " Forest3D Sensor Visual Dashboard"
echo "========================================"
echo "World : $WORLD_NAME"
echo "Drone : ${MODEL_PREFIX}_0"
echo "Camera: $GAZEBO_CAMERA_TOPIC"
echo "2D    : $LIDAR_TOPIC"
echo "3D    : $POINTCLOUD_LIDAR_TOPIC"
echo "Therm : $GAZEBO_THERMAL_CAMERA_TOPIC"
echo
echo "This opens the LiDAR 2D, LiDAR 3D, and Thermal Camera realtime windows."
echo "The Downward Camera window is opened by wsl-camera-view.sh."
echo

cd "$PROJECT_PATH"
DRONE_ENV="${DRONE_ENV:-$HOME/drone-env}"
source "$DRONE_ENV/bin/activate"
exec python3 -m drone.visualization.sensor_dashboard
