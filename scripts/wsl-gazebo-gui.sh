#!/usr/bin/env bash
set -e

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SIM_WORLD="${1:-${SIM_WORLD:-compact}}"

case "$SIM_WORLD" in
    light) WORLD_NAME="default" ;;
    compact) WORLD_NAME="forest_monitoring_compact" ;;
    legacy) WORLD_NAME="forest_monitoring" ;;
    *) WORLD_NAME="$SIM_WORLD" ;;
esac

if [ "$SIM_WORLD" = "compact" ] || [ "$SIM_WORLD" = "legacy" ]; then
    FOREST3D_PATH="${FOREST3D_PATH:-$PROJECT_PATH/Forest3D}"
    PX4_ROOT="${PX4_ROOT:-$HOME/PX4-Autopilot}"
    FOREST3D_GZ_GUI_CONFIG="${FOREST3D_GZ_GUI_CONFIG:-$FOREST3D_PATH/gui/forest_monitoring_gui.config}"
    export GZ_SIM_RESOURCE_PATH="${FOREST3D_PATH}:${FOREST3D_PATH}/models:${PX4_ROOT}/Tools/simulation/gz/models:${PX4_ROOT}/Tools/simulation/gz/worlds:${GZ_SIM_RESOURCE_PATH:-}"
fi

echo "[GAZEBO] Waiting for Gazebo server: $WORLD_NAME"
for _ in $(seq 1 90); do
    if pgrep -f "gz sim .* -s .*worlds/${WORLD_NAME}\.sdf" >/dev/null 2>&1 \
        || pgrep -f "gz sim .* -s" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

if [ -d "/run/user/$(id -u)" ]; then
    chmod 700 "/run/user/$(id -u)" 2>/dev/null || true
fi

echo "[GAZEBO] Opening Gazebo GUI..."
if [ -n "${FOREST3D_GZ_GUI_CONFIG:-}" ] && [ -f "$FOREST3D_GZ_GUI_CONFIG" ]; then
    exec gz sim --gui-config "$FOREST3D_GZ_GUI_CONFIG" -g
fi
exec gz sim -g
