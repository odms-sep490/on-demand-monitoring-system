#!/usr/bin/env bash
set -euo pipefail

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SENSOR_DIR="$PROJECT_PATH/drone/obstacle_avoidance"
ENV_FILE="$PROJECT_PATH/.env"
LIDAR_TOPIC="${LIDAR_TOPIC:-/lidar}"

if [ -f "$ENV_FILE" ]; then
    set -a
    # Strip Windows BOM/CRLF endings while keeping the source .env unchanged.
    source <(sed '1s/^\xEF\xBB\xBF//; s/\r$//; /^MAPILLARY_ACCESS_TOKEN=/{s/\\|/|/g;s/|/\\|/g}' "$ENV_FILE")
    set +a
fi

echo '========================================'
echo ' LiDAR Sensor Monitor'
echo '========================================'
echo "Topic: $LIDAR_TOPIC"
echo "Thresholds: warning=${LIDAR_WARNING_DISTANCE_M:-25.0} obstacle=${LIDAR_OBSTACLE_DISTANCE_M:-15.0} emergency=${LIDAR_EMERGENCY_DISTANCE_M:-7.0}"
echo
echo '[LIDAR] Waiting for Gazebo sensor topic...'

for _ in $(seq 1 90); do
    if gz topic -l 2>/dev/null | grep -Fxq "$LIDAR_TOPIC"; then
        echo "[LIDAR] Topic found: $LIDAR_TOPIC"
        cd "$SENSOR_DIR"
        exec python3 lidar_listener.py
    fi
    sleep 1
done

echo "[LIDAR][WARN] Topic not found: $LIDAR_TOPIC"
echo '[LIDAR] Available sensor topics:'
gz topic -l 2>/dev/null | grep -Ei 'lidar|laser|scan|camera|image|imu|sensor' || true
echo
echo '[LIDAR] This screen will stay open for inspection.'
exec bash
