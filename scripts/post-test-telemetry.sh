#!/usr/bin/env bash
# Posts a few telemetry samples to the backend so the Mapillary reference capture can be tested
# WITHOUT PX4/Gazebo. TEST TOOL ONLY - not used by the application.
# Usage: scripts/post-test-telemetry.sh <missionId> <deviceId> [lat] [lon] [count]
set -euo pipefail
MISSION_ID="${1:?missionId}"; DEVICE_ID="${2:?deviceId}"
LAT="${3:-10.827581}"; LON="${4:-106.624189}"; COUNT="${5:-1}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRET="$(sed 's/\r$//' "$ROOT/.env" | grep '^DRONE_TELEMETRY_SECRET=' | head -1 | cut -d= -f2-)"
BASE="${BACKEND_BASE_URL:-http://localhost:8080}"
for i in $(seq 1 "$COUNT"); do
  curl -sS -X POST "$BASE/api/internal/v1/drone-telemetry/$DEVICE_ID" \
    -H "Content-Type: application/json" -H "X-Drone-Telemetry-Secret: $SECRET" \
    -d "{\"missionId\":\"$MISSION_ID\",\"connected\":true,\"batteryPercent\":90,\"latitude\":$LAT,\"longitude\":$LON,\"absoluteAltitude\":30,\"relativeAltitude\":20}"
  echo; sleep 1
done
