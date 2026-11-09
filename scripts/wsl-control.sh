#!/usr/bin/env bash
set -uo pipefail

PROJECT_PATH="${PROJECT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
REPO_CONTROLLER="$PROJECT_PATH/drone"
ENV_FILE="$PROJECT_PATH/.env"
DRONE_WORKDIR="${DRONE_WORKDIR:-$HOME/drone-controller}"
DRONE_ENV="${DRONE_ENV:-$HOME/drone-env}"

mkdir -p "$DRONE_WORKDIR"
cd "$DRONE_WORKDIR" || exit 1
cp "$REPO_CONTROLLER/flight_controller.py" flight_controller.py
cp "$REPO_CONTROLLER/devicecheck_media_probe.py" devicecheck_media_probe.py
cp "$REPO_CONTROLLER/media_uploader.py" media_uploader.py
cp "$REPO_CONTROLLER/media_review.py" media_review.py
cp "$REPO_CONTROLLER/battery_simulator.py" battery_simulator.py
cp "$REPO_CONTROLLER/geofence_monitor.py" geofence_monitor.py
cp "$REPO_CONTROLLER/thermal_camera_gateway.py" thermal_camera_gateway.py
mkdir -p video
cp "$REPO_CONTROLLER/video/__init__.py" video/__init__.py
cp "$REPO_CONTROLLER/video/video_recorder.py" video/video_recorder.py

requested_sim_world="${SIM_WORLD:-}"
if [ -f "$ENV_FILE" ]; then
    set -a
    # Strip Windows BOM/CRLF endings while keeping the source .env unchanged.
    source <(sed '1s/^\xEF\xBB\xBF//; s/\r$//; /^MAPILLARY_ACCESS_TOKEN=/{s/\\|/|/g;s/|/\\|/g}' "$ENV_FILE")
    set +a
fi
if [ -n "$requested_sim_world" ]; then
    SIM_WORLD="$requested_sim_world"
    export SIM_WORLD
fi
if [ "$SIM_WORLD" = "light" ]; then
    export GAZEBO_CAMERA_TOPIC="/world/default/model/x500_0/link/camera_link/sensor/camera_down/image"
    export GAZEBO_CAMERA_DOWN_TOPIC="$GAZEBO_CAMERA_TOPIC"
    export GAZEBO_CAMERA_FRONT_TOPIC="/world/default/model/x500_0/link/camera_link/sensor/camera_front/image"
fi

source "$DRONE_ENV/bin/activate"

if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
    printf '%s\n' '[VIDEO] FFmpeg is required for browser-compatible previews. Install it with: sudo apt-get install ffmpeg' >&2
    exit 1
fi

if ! python - <<'PY' >/dev/null 2>&1
import cv2
PY
then
    printf '%s\n' '[VIDEO] Installing missing opencv-python dependency...'
    python -m pip install -q opencv-python
fi

MAVSDK_BIN="$(python - <<'PY'
from pathlib import Path
import mavsdk

print(Path(mavsdk.__file__).resolve().parent / "bin" / "mavsdk_server")
PY
)"
MAVSDK_LOG="$PWD/mavsdk_control.log"
MAVSDK_PORT="${MAVSDK_CONTROL_GRPC_PORT:-50052}"
MAVSDK_MAVLINK_ADDRESS="${MAVSDK_MAVLINK_ADDRESS_OVERRIDE:-${PX4_CONTROL_SYSTEM_ADDRESS:-udpin://0.0.0.0:14030}}"
PX4_MAVLINK_RC="${PX4_MAVLINK_RC:-$HOME/PX4-Autopilot/ROMFS/px4fmu_common/init.d-posix/px4-rc.mavlink}"
MAVSDK_SYSID="${MAVSDK_CONTROL_SYSID:-245}"
MAVSDK_COMPID="${MAVSDK_SERVER_COMPID:-190}"
STACK_PROCESS_READY_TIMEOUT_S="${STACK_PROCESS_READY_TIMEOUT_S:-90}"
MAVLINK_ENDPOINT_READY_TIMEOUT_S="${MAVLINK_ENDPOINT_READY_TIMEOUT_S:-30}"
MAVSDK_GRPC_READY_TIMEOUT_S="${MAVSDK_GRPC_READY_TIMEOUT_S:-30}"
MAVSDK_PX4_DISCOVERY_TIMEOUT_S="${MAVSDK_PX4_DISCOVERY_TIMEOUT_S:-30}"
MAVSDK_PID=""
MAVSDK_GENERATION=0
MAVSDK_START_TIME=""
MONITOR_PID=""
RESTART_COUNT=0
MAX_RESTARTS=10
SERVER_RESTART_COOLDOWN_S="${MAVSDK_SERVER_RESTART_COOLDOWN_S:-8}"
MAVSDK_ARGS=(
    -p "$MAVSDK_PORT"
    --sysid "$MAVSDK_SYSID"
    --compid "$MAVSDK_COMPID"
    "$MAVSDK_MAVLINK_ADDRESS"
)

is_port_listening() {
    ss -H -ltn "sport = :${MAVSDK_PORT}" 2>/dev/null | grep -q .
}

is_udp_port_bound() {
    ss -H -lun "sport = :14030" 2>/dev/null | grep -q .
}

simulation_processes_ready() {
    pgrep -u "$USER" -x px4 >/dev/null 2>&1 \
        && { pgrep -u "$USER" -x gz >/dev/null 2>&1 \
            || pgrep -u "$USER" -x ruby >/dev/null 2>&1; }
}

wait_for_simulation_processes() {
    printf '%s\n' '[WAIT] PX4 and Gazebo processes...'
    for _ in $(seq 1 "$STACK_PROCESS_READY_TIMEOUT_S"); do
        if simulation_processes_ready; then
            printf '%s\n' '[CHECK] PX4 process: RUNNING'
            printf '%s\n' '[CHECK] Gazebo process: RUNNING'
            return 0
        fi
        sleep 1
    done

    printf '%s\n' '[CHECK] PX4/Gazebo process readiness: FAILED' >&2
    printf '%s\n' '[DIAG] Expected processes named px4 and gz/ruby did not stay alive.' >&2
    pgrep -a -u "$USER" -x 'px4|gz|ruby' 2>/dev/null || true
    printf '%s\n' '[ACTION] Inspect the Gazebo/PX4 pane for its first ERROR line, then restart the complete stack.' >&2
    return 1
}

px4_mavlink_route_ready() {
    ss -H -lun "sport = :14280" 2>/dev/null | grep -q . \
        && grep -Eq 'udp_onboard_payload_port_remote=.*14030' "$PX4_MAVLINK_RC" \
        && grep -Eq 'mavlink start .*udp_onboard_payload_port_local.*udp_onboard_payload_port_remote' "$PX4_MAVLINK_RC"
}

wait_for_px4_mavlink_route() {
    printf '%s\n' '[WAIT] PX4 MAVLink route 14280 -> 14030...'
    for _ in $(seq 1 "$MAVLINK_ENDPOINT_READY_TIMEOUT_S"); do
        if ! pgrep -u "$USER" -x px4 >/dev/null 2>&1; then
            printf '%s\n' '[CHECK] MAVLink UDP configuration: FAILED (PX4 exited)' >&2
            printf '%s\n' '[ACTION] Inspect the Gazebo/PX4 pane; MAVSDK cannot discover a stopped PX4 process.' >&2
            return 1
        fi
        if px4_mavlink_route_ready; then
            printf '%s\n' '[CHECK] MAVLink UDP configuration: VALID (PX4 source 14280 -> destination 14030)'
            return 0
        fi
        sleep 1
    done

    printf '%s\n' '[CHECK] MAVLink UDP configuration: FAILED' >&2
    printf '%s\n' '[DIAG] PX4 did not expose the expected onboard-payload route 14280 -> 14030.' >&2
    ss -H -uanp 2>/dev/null | grep -E ':14030|:14280' || true
    grep -n -E 'udp_onboard_payload_port_(local|remote)|mavlink start .*udp_onboard_payload' "$PX4_MAVLINK_RC" 2>/dev/null || true
    printf '%s\n' '[ACTION] Check px4-rc.mavlink onboard payload ports and the PX4 startup log.' >&2
    return 1
}

print_server_log_tail() {
    printf '%s\n' '[MAVSDK] Last server log:'
    tail -n 8 "$MAVSDK_LOG" 2>/dev/null || true
}

print_process_diagnostics() {
    printf '[MAVSDK-PROC] generation=%s\n' "${MAVSDK_GENERATION:-0}"
    printf '[MAVSDK-PROC] executable=%s\n' "$MAVSDK_BIN"
    printf '[MAVSDK-PROC] args=%s\n' "${MAVSDK_ARGS[*]}"
    printf '[MAVSDK-PROC] mavlink_address=%s\n' "$MAVSDK_MAVLINK_ADDRESS"
    printf '%s\n' '[MAVSDK-PROC] px4_sitl_note=PX4 onboard payload MAVLink sends to UDP 14030; wsl-sim.sh owns only endpoint/rate; PX4 MAVLink module owns HEARTBEAT'
    printf '[MAVSDK-PROC] cwd=%s\n' "$PWD"
    if [ -n "${MAVSDK_PID:-}" ]; then
        printf '[MAVSDK-PROC] pid=%s\n' "$MAVSDK_PID"
    fi
}

print_port_diagnostics() {
    printf '%s\n' '[MAVSDK-NET] gRPC listeners:'
    ss -ltnp 2>/dev/null | grep ":${MAVSDK_PORT} " || true
    printf '%s\n' '[MAVSDK-NET] MAVLink UDP listeners:'
    ss -lunp 2>/dev/null | grep ":14030 " || true
    printf '%s\n' '[MAVSDK-NET] MAVLink UDP sockets:'
    ss -uanp 2>/dev/null | grep -E ":14030|:14280" || true
}

server_exit_code() {
    local pid="$1"
    local code="unknown"
    if [ -n "$pid" ]; then
        if wait "$pid" 2>/dev/null; then
            code=0
        else
            code=$?
        fi
    fi
    printf '%s' "$code"
}

print_pid_diagnostics() {
    local pid="$1"
    printf '[MAVSDK-PROC] ps pid=%s:\n' "${pid:-unknown}"
    if [ -n "$pid" ]; then
        ps -o pid,ppid,stat,etime,cmd -p "$pid" 2>/dev/null || true
    fi
    print_port_diagnostics
}

print_server_exit() {
    local reason="$1"
    local pid="${MAVSDK_PID:-}"
    local generation="${MAVSDK_GENERATION:-0}"
    local start_time="${MAVSDK_START_TIME:-unknown}"
    local code="unknown"
    print_pid_diagnostics "$pid"
    code="$(server_exit_code "$pid")"
    printf '[MAVSDK-PROC] generation=%s pid=%s started_at=%s exited code=%s reason=%s\n' "$generation" "${pid:-unknown}" "$start_time" "$code" "$reason"
    printf '[MAVSDK-CONN] Server process exited generation=%s reason=%s pid=%s exit_code=%s signal=unknown\n' "$generation" "$reason" "${pid:-unknown}" "$code"
    print_process_diagnostics
    print_server_log_tail
    MAVSDK_PID=""
    MAVSDK_START_TIME=""
}

kill_stale_mavsdk_server() {
    printf '%s\n' '[MAVSDK] Cleaning stale mavsdk_server processes...'

    # Chỉ dọn MAVSDK control server trên port 50052.
    # Telemetry dùng chung gRPC server này nên không chạy server riêng.
    pkill -f "mavsdk_server.*-p ${MAVSDK_PORT}" 2>/dev/null || true

    for _ in $(seq 1 20); do
        grpc_busy=0
        udp_busy=0

        ss -ltn 2>/dev/null | grep -q ":${MAVSDK_PORT} " && grpc_busy=1
        ss -lun 2>/dev/null | grep -q ":14030 " && udp_busy=1

        if [ "$grpc_busy" -eq 0 ] && [ "$udp_busy" -eq 0 ]; then
            printf '%s\n' '[MAVSDK] Stale cleanup complete'
            return 0
        fi

        sleep 0.25
    done

    printf '%s\n' '[MAVSDK] WARNING: control ports still busy after cleanup'
    ss -ltnp 2>/dev/null | grep ":${MAVSDK_PORT} " || true
    ss -lunp 2>/dev/null | grep ":14030 " || true

    return 1
}

start_mavsdk_server() {
    printf '%s\n' '[MAVSDK] Starting server...'
    if [ ! -e "$MAVSDK_BIN" ]; then
        printf '[MAVSDK-PROC] executable missing: %s\n' "$MAVSDK_BIN"
        return 127
    fi
    if [ ! -x "$MAVSDK_BIN" ]; then
        printf '[MAVSDK-PROC] executable is not runnable: %s\n' "$MAVSDK_BIN"
        return 126
    fi
    MAVSDK_GENERATION=$((MAVSDK_GENERATION + 1))
    MAVSDK_START_TIME="$(date -Is)"
    print_process_diagnostics
    "$MAVSDK_BIN" "${MAVSDK_ARGS[@]}" > "$MAVSDK_LOG" 2>&1 &
    MAVSDK_PID=$!
    printf '[MAVSDK-PROC] generation=%s pid=%s started\n' "$MAVSDK_GENERATION" "$MAVSDK_PID"
    printf '[MAVSDK] Started pid=%s port=%s\n' "$MAVSDK_PID" "$MAVSDK_PORT"
}

wait_for_mavsdk_port() {
    for _ in $(seq 1 "$MAVSDK_GRPC_READY_TIMEOUT_S"); do
        if [ -n "${MAVSDK_PID:-}" ] && ! kill -0 "$MAVSDK_PID" 2>/dev/null; then
            print_server_exit "startup-port-wait"
            return 1
        fi

        if is_port_listening; then
            printf '[MAVSDK] Listening on %s\n' "$MAVSDK_PORT"
            return 0
        fi

        sleep 1
    done

    printf '[CHECK] gRPC port %s: FAILED\n' "$MAVSDK_PORT" >&2
    printf '%s\n' '[DIAG] This MAVSDK build binds gRPC only after MAVLink discovery; verify the discovery check above.' >&2
    print_server_log_tail
    print_port_diagnostics
    printf '%s\n' '[ACTION] Confirm PX4 is sending HEARTBEAT packets from UDP 14280 to 14030.' >&2
    return 1
}

wait_for_mavsdk_system() {
    for _ in $(seq 1 "$MAVSDK_PX4_DISCOVERY_TIMEOUT_S"); do
        if [ -n "${MAVSDK_PID:-}" ] && ! kill -0 "$MAVSDK_PID" 2>/dev/null; then
            print_server_exit "px4-discovery-wait"
            return 1
        fi

        if grep -Eiq 'system discovered|discovered system|discovered [0-9]+ component' "$MAVSDK_LOG" 2>/dev/null; then
            printf '%s\n' '[CHECK] PX4 discovery: CONNECTED (MAVLink HEARTBEAT received)'
            return 0
        fi

        # mavsdk_server v3.17.x starts gRPC only after discovering a system.
        # A listening gRPC port is therefore also a positive discovery signal.
        if is_port_listening; then
            printf '%s\n' '[CHECK] PX4 discovery: CONNECTED (gRPC service started)'
            return 0
        fi

        sleep 1
    done

    printf '%s\n' '[CHECK] PX4 discovery: FAILED' >&2
    printf '%s\n' '[DIAG] MAVSDK is running and owns UDP 14030, but no valid PX4 HEARTBEAT was observed.' >&2
    print_server_log_tail
    print_port_diagnostics
    printf '%s\n' '[ACTION] Verify PX4 remains running and its onboard MAVLink instance targets 127.0.0.1:14030.' >&2
    return 1
}

start_and_wait_mavsdk() {
    start_mavsdk_server || return $?
    if ! kill -0 "$MAVSDK_PID" 2>/dev/null; then
        print_server_exit "startup-process-check"
        return 1
    fi
    printf '%s\n' '[CHECK] MAVSDK process: RUNNING'

    for _ in $(seq 1 10); do
        is_udp_port_bound && break
        sleep 0.2
    done
    if ! is_udp_port_bound; then
        printf '%s\n' '[CHECK] MAVSDK UDP endpoint: FAILED (port 14030 is not bound)' >&2
        print_server_log_tail
        return 1
    fi

    # This server version does not bind gRPC until a MAVLink system is found.
    wait_for_mavsdk_system || return 1
    wait_for_mavsdk_port || return 1
    printf '[CHECK] gRPC port %s: LISTENING\n' "$MAVSDK_PORT"
    print_port_diagnostics
    return 0
}

monitor_mavsdk_server() {
    while true; do
        sleep 2

        if [ -n "${MAVSDK_PID:-}" ] && kill -0 "$MAVSDK_PID" 2>/dev/null; then
            continue
        fi

        if [ -n "${MAVSDK_PID:-}" ]; then
            print_server_exit "monitor"
        fi

        if [ "$RESTART_COUNT" -ge "$MAX_RESTARTS" ]; then
            printf '%s\n' '[MAVSDK] Restart limit reached'
            return 0
        fi

        RESTART_COUNT=$((RESTART_COUNT + 1))
        sleep_seconds=$((SERVER_RESTART_COOLDOWN_S + RESTART_COUNT * 2))
        if [ "$sleep_seconds" -gt 10 ]; then
            sleep_seconds=10
        fi

        printf '[MAVSDK] Restart attempt %s/%s in %ss\n' "$RESTART_COUNT" "$MAX_RESTARTS" "$sleep_seconds"
        sleep "$sleep_seconds"
        start_and_wait_mavsdk || true
    done
}

cleanup() {
    printf '%s\n' '[CMD] Cleaning up flight-control mavsdk_server...'
    [ -n "${MONITOR_PID:-}" ] && kill "$MONITOR_PID" 2>/dev/null || true
    [ -n "${MAVSDK_PID:-}" ] && kill "$MAVSDK_PID" 2>/dev/null || true
}

trap cleanup EXIT INT TERM

wait_for_simulation_processes || exit 1
wait_for_px4_mavlink_route || exit 1
kill_stale_mavsdk_server || exit 1
start_and_wait_mavsdk || exit 1
monitor_mavsdk_server &
MONITOR_PID=$!

python flight_controller.py
