#!/usr/bin/env bash
set -euo pipefail

sleep 22

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
REPO_CONTROLLER="$PROJECT_PATH/drone"
ENV_FILE="$PROJECT_PATH/.env"
DRONE_WORKDIR="${DRONE_WORKDIR:-$HOME/drone-controller}"
DRONE_ENV="${DRONE_ENV:-$HOME/drone-env}"

mkdir -p "$DRONE_WORKDIR"
cd "$DRONE_WORKDIR"
cp "$REPO_CONTROLLER/telemetry_sender.py" telemetry_sender.py
cp "$REPO_CONTROLLER/sitl_battery_sim.py" sitl_battery_sim.py

if [ -f "$ENV_FILE" ]; then
    set -a
    # Strip Windows BOM/CRLF endings while keeping the source .env unchanged.
    source <(sed '1s/^\xEF\xBB\xBF//; s/\r$//; /^MAPILLARY_ACCESS_TOKEN=/{s/\\|/|/g;s/|/\\|/g}' "$ENV_FILE")
    set +a
fi

if [ -z "${DRONE_TELEMETRY_SECRET:-}" ]; then
    echo "[MAVSDK-TEL] ERROR: DRONE_TELEMETRY_SECRET is missing in $ENV_FILE"
    echo "[MAVSDK-TEL] Backend preflight requires authenticated live telemetry."
    exit 1
fi

source "$DRONE_ENV/bin/activate"

MAVSDK_PORT="${MAVSDK_TELEMETRY_GRPC_PORT:-50052}"

echo "[MAVSDK-TEL] Using shared MAVSDK gRPC server on ${MAVSDK_PORT}"

for _ in $(seq 1 60); do
    if ss -ltn 2>/dev/null | grep -q ":${MAVSDK_PORT} "; then
        echo "[MAVSDK-TEL] Shared server ready on ${MAVSDK_PORT}"
        exec python telemetry_sender.py
    fi

    sleep 1
done

echo "[MAVSDK-TEL] ERROR: shared MAVSDK server ${MAVSDK_PORT} not ready"
echo "[MAVSDK-TEL] Start/restart Flight Control; it owns mavsdk_server."
exit 1
