import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from io import BytesIO
import json
import logging
import math
import os
import queue
import select
import shlex
import socket
import subprocess
import termios
import threading
import time
import tty
import sys
import uuid
import grpc
import httpx
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from mavsdk import System
from mavsdk.action import ActionError
from mavsdk.offboard import OffboardError, VelocityNedYaw
from PIL import Image as PilImage
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from dotenv import load_dotenv
from video.video_recorder import RecordingResult, VideoRecorder
from battery_simulator import BatterySimulator, battery_state, preflight_battery_check
from media_uploader import BackendUrlResolver
from media_review import LocalMediaLibrary
from devicecheck_media_probe import verify_media_storage_probe
from thermal_camera_gateway import ThermalCameraGateway

def resolve_project_root() -> Path:
    configured = os.getenv("PROJECT_PATH")
    if configured:
        return Path(configured)

    source_candidate = Path(__file__).resolve().parent.parent
    if (source_candidate / "Forest3D").exists() and (source_candidate / "ondemandmonitoring").exists():
        return source_candidate

    return Path.cwd()


PROJECT_ROOT = resolve_project_root()
RUNTIME_SESSION_ID = uuid.uuid4().hex


def build_media_probe_jpeg() -> bytes:
    buffer = BytesIO()
    PilImage.new("RGB", (1, 1), (18, 139, 84)).save(buffer, format="JPEG")
    return buffer.getvalue()


MEDIA_PROBE_JPEG = build_media_probe_jpeg()

DRONE_DIR = PROJECT_ROOT / "drone"

if "/usr/lib/python3/dist-packages" not in sys.path:
    sys.path.append("/usr/lib/python3/dist-packages")

if str(DRONE_DIR) not in sys.path:
    sys.path.insert(0, str(DRONE_DIR))

ENV_FILE = PROJECT_ROOT / "ondemandmonitoring" / ".env"

load_dotenv(ENV_FILE, override=False)

print(
    f"[ENV] Loaded: {ENV_FILE}",
    flush=True,
)

print(
    "[ENV] Simple obstacle-stop mission mode",
    flush=True,
)
EARLY_FAST_DEMO_MODE = (
    os.getenv("FAST_DEMO_MODE", "false").strip().lower()
    in {"1", "true", "yes", "on"}
)
if EARLY_FAST_DEMO_MODE:
    LidarGateway = None
    AvoidanceController = None
    OBSTACLE_DISTANCE_M = 5.0
    WARNING_DISTANCE_M = 10.0
    front_obstacle_reading = None
    LIDAR_IMPORT_ERROR = RuntimeError("Fast demo mode disables LiDAR/obstacle modules")
else:
    try:
        from obstacle_avoidance.lidar_gateway import LidarGateway
        from obstacle_avoidance.avoidance_controller import AvoidanceController
        from obstacle_avoidance.sensor_reader import (
            OBSTACLE_DISTANCE_M,
            WARNING_DISTANCE_M,
            front_obstacle_reading,
        )
    except ImportError as exc:
        LidarGateway = None
        AvoidanceController = None
        OBSTACLE_DISTANCE_M = 5.0
        WARNING_DISTANCE_M = 10.0
        front_obstacle_reading = None
        LIDAR_IMPORT_ERROR = exc
    else:
        LIDAR_IMPORT_ERROR = None
class MotionOwner(Enum):
    MANUAL = "MANUAL"
    AUTO_PLAN = "AUTO_PLAN"
    EMERGENCY = "EMERGENCY"


@dataclass(frozen=True)
class SavedMotion:
    forward_m_s: float
    right_m_s: float
    down_m_s: float
    north_m_s: float
    east_m_s: float
    yaw_deg: float

try:
    from geofence_monitor import px4_ned_to_sim_xy
except ImportError:
    def px4_ned_to_sim_xy(north_m: float, east_m: float) -> tuple[float, float]:
        return east_m, north_m

try:
    from gz.msgs10.image_pb2 import Image as GzImage, PixelFormatType
    from gz.transport13 import Node
except ImportError:
    GzImage = None
    PixelFormatType = None
    Node = None


PX4_CONTROL_SYSTEM_ADDRESS = os.getenv(
    "PX4_CONTROL_SYSTEM_ADDRESS",
    "udpin://0.0.0.0:14030",
)

MAVSDK_CONTROL_GRPC_PORT = int(
    os.getenv("MAVSDK_CONTROL_GRPC_PORT", "50052")
)
MAVSDK_CONTROL_SYSID = int(
    os.getenv("MAVSDK_CONTROL_SYSID", "245")
)
MAVSDK_CONTROL_COMPID = int(
    os.getenv("MAVSDK_CONTROL_COMPID", "191")
)
FLIGHT_CONTROL_API_PORT = int(os.getenv("FLIGHT_CONTROL_API_PORT", "8090"))
FLIGHT_CONTROL_API_BIND = os.getenv("FLIGHT_CONTROL_API_BIND", "0.0.0.0")
MAVSDK_DISCONNECT_GRACE_S = float(
    os.getenv("MAVSDK_DISCONNECT_GRACE_S", "8.0")
)
MAVSDK_RECONNECT_CONFIRM_S = float(
    os.getenv("MAVSDK_RECONNECT_CONFIRM_S", "1.5")
)
MAVSDK_COMMAND_RECOVERY_WAIT_S = float(
    os.getenv("MAVSDK_COMMAND_RECOVERY_WAIT_S", "6.0")
)
MAVSDK_HEALTH_LOG_INTERVAL_S = float(
    os.getenv("MAVSDK_HEALTH_LOG_INTERVAL_S", "15.0")
)
MAVSDK_CLIENT_RECONNECT_COOLDOWN_S = float(
    os.getenv("MAVSDK_CLIENT_RECONNECT_COOLDOWN_S", "3.0")
)
OFFBOARD_SETPOINT_RATE_HZ = float(
    os.getenv("OFFBOARD_SETPOINT_RATE_HZ", "15.0")
)
OFFBOARD_SETPOINT_STATS_ENABLED = (
    os.getenv("OFFBOARD_SETPOINT_STATS_ENABLED", "false").strip().lower()
    in {"1", "true", "yes", "on"}
)
FAST_DEMO_MODE = (
    os.getenv("FAST_DEMO_MODE", "false").strip().lower()
    in {"1", "true", "yes", "on"}
)
GPS_TARGET_REAL_FLIGHT = (
    os.getenv("GPS_TARGET_REAL_FLIGHT", "true").strip().lower()
    in {"1", "true", "yes", "on"}
)

MOVE_SPEED_M_S = float(
    os.getenv("CONTROL_MOVE_SPEED_M_S", "220.0")
)
VERTICAL_SPEED_M_S = float(
    os.getenv("CONTROL_VERTICAL_SPEED_M_S", "80.0")
)
YAW_STEP_DEG = float(
    os.getenv("CONTROL_YAW_STEP_DEG", "5.0")
)
AUTO_PLAN_REACHED_RADIUS_M = float(os.getenv("AUTO_PLAN_REACHED_RADIUS_M", "18.0" if FAST_DEMO_MODE else "5.0"))
AUTO_PLAN_ALTITUDE_TOLERANCE_M = float(os.getenv("AUTO_PLAN_ALTITUDE_TOLERANCE_M", "8.0" if FAST_DEMO_MODE else "4.0"))
AUTO_PLAN_MAX_SPEED_M_S = float(os.getenv("AUTO_PLAN_MAX_SPEED_M_S", "220.0" if FAST_DEMO_MODE else "8.0"))
AUTO_PLAN_MIN_SPEED_M_S = float(os.getenv("AUTO_PLAN_MIN_SPEED_M_S", "80.0" if FAST_DEMO_MODE else "1.2"))
AUTO_PLAN_SLOWDOWN_RADIUS_M = float(os.getenv("AUTO_PLAN_SLOWDOWN_RADIUS_M", "18.0" if FAST_DEMO_MODE else "35.0"))
AUTO_PLAN_VERTICAL_MAX_SPEED_M_S = float(os.getenv("AUTO_PLAN_VERTICAL_MAX_SPEED_M_S", "80.0" if FAST_DEMO_MODE else "0.5"))
AUTO_PLAN_VERTICAL_GAIN = float(os.getenv("AUTO_PLAN_VERTICAL_GAIN", "2.0" if FAST_DEMO_MODE else "0.08"))
AUTO_PLAN_SETPOINT_SMOOTHING = float(os.getenv("AUTO_PLAN_SETPOINT_SMOOTHING", "1.0" if FAST_DEMO_MODE else "0.35"))
AUTO_PLAN_ALTITUDE_RAMP_M = float(os.getenv("AUTO_PLAN_ALTITUDE_RAMP_M", "80.0" if FAST_DEMO_MODE else "1.0"))
GPS_ROUTE_EXPECTED_SPEED_M_S = float(os.getenv("GPS_ROUTE_EXPECTED_SPEED_M_S", "220.0" if FAST_DEMO_MODE else "6.0"))

# Realistic flight dynamics for the real Gazebo/PX4 flight. The demo knobs above (220 m/s,
# 80 m/s climb, 220 m/s^2 accel) made PX4 "teleport" on takeoff and jerk between setpoints.
# These caps are applied to PX4 itself, so every command (takeoff, manual, GPS route) flies
# like a real quadcopter: gentle takeoff, smooth acceleration, steady cruise.
REALISTIC_FLIGHT = os.getenv("REALISTIC_FLIGHT", "true").strip().lower() in {"1", "true", "yes", "on"}
REAL_TAKEOFF_SPEED_M_S = float(os.getenv("REAL_TAKEOFF_SPEED_M_S", "2.0"))
REAL_CLIMB_SPEED_M_S = float(os.getenv("REAL_CLIMB_SPEED_M_S", "2.5"))
REAL_DESCENT_SPEED_M_S = float(os.getenv("REAL_DESCENT_SPEED_M_S", "1.5"))
REAL_MAX_XY_SPEED_M_S = float(os.getenv("REAL_MAX_XY_SPEED_M_S", "12.0"))
REAL_ACC_HOR_M_S2 = float(os.getenv("REAL_ACC_HOR_M_S2", "2.5"))
REAL_ACC_VERT_M_S2 = float(os.getenv("REAL_ACC_VERT_M_S2", "2.0"))
GPS_FLIGHT_CRUISE_SPEED_M_S = float(os.getenv("GPS_FLIGHT_CRUISE_SPEED_M_S", "8.0"))
GPS_FLIGHT_ACCEL_M_S2 = float(os.getenv("GPS_FLIGHT_ACCEL_M_S2", "1.5"))

SPEED_ADJUST_STEP_M_S = float(
    os.getenv("CONTROL_SPEED_ADJUST_STEP_M_S", "200.0")
)
PX4_SPEED_LIMIT_M_S = float(
    os.getenv(
        "PX4_SPEED_LIMIT_M_S",
        str(max(MOVE_SPEED_M_S, VERTICAL_SPEED_M_S)),
    )
)
TAKEOFF_CONFIRM_TIMEOUT_S = float(os.getenv("TAKEOFF_CONFIRM_TIMEOUT_S", "30.0"))
TAKEOFF_CONFIRM_ALTITUDE_M = float(os.getenv("TAKEOFF_CONFIRM_ALTITUDE_M", "1.0"))
MEDIA_UPLOAD_SHUTDOWN_WAIT_S = float(os.getenv("MEDIA_UPLOAD_SHUTDOWN_WAIT_S", "15.0"))

SAFETY_POLL_INTERVAL_S = float(
    os.getenv("SAFETY_POLL_INTERVAL_S", "0.5")
)

MISSION_CRUISE_SPEED_M_S = float(
    os.getenv("MISSION_CRUISE_SPEED_M_S", "2.0")
)

MISSION_WARNING_DISTANCE_M = float(
    os.getenv("MISSION_WARNING_DISTANCE_M", "80.0")
)

MISSION_WARNING_MIN_SPEED_M_S = float(
    os.getenv("MISSION_WARNING_MIN_SPEED_M_S", "1.0")
)

MISSION_WARNING_MAX_SPEED_M_S = float(
    os.getenv("MISSION_WARNING_MAX_SPEED_M_S", "5.0")
)

MISSION_GOAL_SLOWDOWN_DISTANCE_M = float(
    os.getenv("MISSION_GOAL_SLOWDOWN_DISTANCE_M", "15.0")
)

MISSION_GOAL_MIN_SPEED_M_S = float(
    os.getenv("MISSION_GOAL_MIN_SPEED_M_S", "1.0")
)

MISSION_CLIMB_FIRST_TIMEOUT_S = float(
    os.getenv("MISSION_CLIMB_FIRST_TIMEOUT_S", "60.0")
)

MISSION_CLIMB_MAX_OVERSHOOT_M = float(
    os.getenv("MISSION_CLIMB_MAX_OVERSHOOT_M", "2.0")
)

MISSION_ALTITUDE_TOLERANCE_M = float(
    os.getenv("MISSION_ALTITUDE_TOLERANCE_M", "1.0")
)

MISSION_ALTITUDE_HOLD_DEADBAND_M = float(
    os.getenv("MISSION_ALTITUDE_HOLD_DEADBAND_M", "0.35")
)

MISSION_ALTITUDE_HOLD_MAX_VERTICAL_SPEED_M_S = float(
    os.getenv("MISSION_ALTITUDE_HOLD_MAX_VERTICAL_SPEED_M_S", "1.0")
)

MISSION_FORWARD_START_SPEED_M_S = float(
    os.getenv("MISSION_FORWARD_START_SPEED_M_S", "3.0")
)

MISSION_FORWARD_RAMP_SECONDS = float(
    os.getenv("MISSION_FORWARD_RAMP_SECONDS", "8.0")
)

MISSION_LAUNCH_PAD_CLEAR_RADIUS_M = float(
    os.getenv("MISSION_LAUNCH_PAD_CLEAR_RADIUS_M", "14.0")
)

MISSION_LAUNCH_PAD_MAX_SPEED_M_S = float(
    os.getenv("MISSION_LAUNCH_PAD_MAX_SPEED_M_S", "3.0")
)

MISSION_POLL_INTERVAL_S = float(
    os.getenv("MISSION_POLL_INTERVAL_S", "5.0")
)

MISSION_ARRIVAL_RADIUS_M = float(
    os.getenv("MISSION_ARRIVAL_RADIUS_M", "3.0")
)

BACKEND_BASE_URL = os.getenv(
    "BACKEND_BASE_URL",
    "http://localhost:8080",
).rstrip("/")

SIM_WORLD = os.getenv(
    "SIM_WORLD",
    "legacy",
)

DEFAULT_GAZEBO_WORLD = (
    "forest_monitoring_compact"
    if SIM_WORLD == "compact"
    else "default" if SIM_WORLD == "light" else "forest_monitoring"
)

CAMERA_TOPIC = os.getenv(
    "GAZEBO_CAMERA_TOPIC",
    f"/world/{DEFAULT_GAZEBO_WORLD}/model/x500_mono_cam_down_0/link/camera_link/sensor/camera_down/image",
)
CAMERA_DOWN_TOPIC = os.getenv(
    "GAZEBO_CAMERA_DOWN_TOPIC",
    f"/world/{DEFAULT_GAZEBO_WORLD}/model/x500_mono_cam_down_0/link/camera_link/sensor/camera_down/image",
)
CAMERA_FRONT_TOPIC = os.getenv(
    "GAZEBO_CAMERA_FRONT_TOPIC",
    f"/world/{DEFAULT_GAZEBO_WORLD}/model/x500_mono_cam_down_0/link/camera_link/sensor/camera_front/image",
)
GZ_MODEL_NAME = os.getenv("GZ_MODEL_NAME", "x500_0" if SIM_WORLD == "light" else "x500_mono_cam_down_0")
CAMERA_DEFAULT_VIEW = os.getenv("CAMERA_DEFAULT_VIEW", "FRONT").strip().upper()
CAMERA_TOGGLE_DEBOUNCE_S = float(os.getenv("CAMERA_TOGGLE_DEBOUNCE_S", "0.35"))
GAZEBO_CAMERA_PITCH_TOPIC = os.getenv(
    "GAZEBO_CAMERA_PITCH_TOPIC",
    f"/model/{GZ_MODEL_NAME}/joint/CameraJoint/0/cmd_pos",
)
GAZEBO_CAMERA_JOINT_TOPIC = f"/model/{GZ_MODEL_NAME}/joint/CameraJoint/0/cmd_pos"
CAMERA_PITCH_STEP_DEG = float(os.getenv("CAMERA_PITCH_STEP_DEG", "30"))
CAMERA_DOWN_JOINT_POSITION_RAD = float(os.getenv("CAMERA_DOWN_JOINT_POSITION_RAD", "-1.57079632679"))
CAMERA_FRONT_JOINT_POSITION_RAD = float(
    os.getenv("CAMERA_FRONT_JOINT_POSITION_RAD", "0.0")
)
CAMERA_VIEW_STATE_FILE = Path(
    os.getenv("CAMERA_VIEW_STATE_FILE", "/tmp/forest3d_camera_view_state.json")
)
VIDEO_RECORDING_DIR = Path(
    os.getenv("VIDEO_RECORDING_DIR", "/tmp/forest3d_drone_videos")
)
VIDEO_RECORDING_FPS = float(os.getenv("VIDEO_RECORDING_FPS", "15.0"))
VIDEO_RECORDING_QUEUE_SIZE = int(os.getenv("VIDEO_RECORDING_QUEUE_SIZE", "4"))
CAMERA_STREAM_FPS = float(os.getenv("CAMERA_STREAM_FPS", "15.0"))
CAMERA_STREAM_MAX_WIDTH = int(os.getenv("CAMERA_STREAM_MAX_WIDTH", "0"))
CAMERA_LEGACY_DOWN_SENSOR_ENABLED = (
    os.getenv("CAMERA_LEGACY_DOWN_SENSOR_ENABLED", "false").strip().lower()
    in {"1", "true", "yes", "on"}
)
CAMERA_STREAM_JPEG_QUALITY = int(os.getenv("CAMERA_STREAM_JPEG_QUALITY", "88"))
CAMERA_CAPTURE_JPEG_QUALITY = int(os.getenv("CAMERA_CAPTURE_JPEG_QUALITY", "88"))
GAZEBO_THERMAL_CAMERA_TOPIC = os.getenv(
    "GAZEBO_THERMAL_CAMERA_TOPIC",
    "/thermal_camera",
)
GAZEBO_LIGHT_SERVICE = f"/world/{DEFAULT_GAZEBO_WORLD}/light_config"
GAZEBO_WIND_TOPIC = f"/world/{DEFAULT_GAZEBO_WORLD}/wind"

WEATHER_KEY_PRESETS = {
    "u": "CLEAR_DAY",
    "y": "SUNSET",
    "i": "NIGHT",
    "g": "CLOUDY",
    "j": "FOGGY",
    "m": "WINDY",
    "b": "LIGHT_RAIN",
    "z": "HEAVY_RAIN",
}

WEATHER_PRESETS = {
    "CLEAR_DAY": {
        "label": "Clear Day",
        "intensity": "1.2",
        "direction": "x: -0.5 y: 0.5 z: -0.8",
        "diffuse": "r: 0.95 g: 0.93 b: 0.88 a: 1",
        "specular": "r: 0.3 g: 0.3 b: 0.25 a: 1",
        "wind": None,
    },
    "SUNSET": {
        "label": "Sunset",
        "intensity": "0.75",
        "direction": "x: -0.9 y: 0.15 z: -0.25",
        "diffuse": "r: 1.0 g: 0.48 b: 0.22 a: 1",
        "specular": "r: 0.55 g: 0.25 b: 0.12 a: 1",
        "wind": None,
    },
    "NIGHT": {
        "label": "Night",
        "intensity": "0.12",
        "direction": "x: -0.25 y: 0.35 z: -0.9",
        "diffuse": "r: 0.08 g: 0.1 b: 0.18 a: 1",
        "specular": "r: 0.02 g: 0.03 b: 0.06 a: 1",
        "wind": None,
    },
    "CLOUDY": {
        "label": "Cloudy",
        "intensity": "0.45",
        "direction": "x: -0.35 y: 0.4 z: -0.85",
        "diffuse": "r: 0.45 g: 0.5 b: 0.58 a: 1",
        "specular": "r: 0.12 g: 0.13 b: 0.15 a: 1",
        "wind": None,
    },
    "FOGGY": {
        "label": "Foggy",
        "intensity": "0.35",
        "direction": "x: -0.25 y: 0.25 z: -0.9",
        "diffuse": "r: 0.55 g: 0.58 b: 0.6 a: 1",
        "specular": "r: 0.08 g: 0.08 b: 0.08 a: 1",
        "wind": None,
    },
    "WINDY": {
        "label": "Windy",
        "intensity": "0.9",
        "direction": "x: -0.5 y: 0.5 z: -0.8",
        "diffuse": "r: 0.8 g: 0.82 b: 0.78 a: 1",
        "specular": "r: 0.2 g: 0.22 b: 0.2 a: 1",
        "wind": "x: 12 y: 4 z: 0",
    },
    "LIGHT_RAIN": {
        "label": "Light Rain",
        "intensity": "0.35",
        "direction": "x: -0.35 y: 0.4 z: -0.85",
        "diffuse": "r: 0.32 g: 0.36 b: 0.42 a: 1",
        "specular": "r: 0.08 g: 0.08 b: 0.1 a: 1",
        "wind": "x: 5 y: 2 z: 0",
    },
    "HEAVY_RAIN": {
        "label": "Heavy Rain",
        "intensity": "0.22",
        "direction": "x: -0.35 y: 0.4 z: -0.85",
        "diffuse": "r: 0.22 g: 0.25 b: 0.3 a: 1",
        "specular": "r: 0.04 g: 0.04 b: 0.05 a: 1",
        "wind": "x: 14 y: 5 z: 0",
    },
}
CURRENT_WEATHER_PRESET = "CLEAR_DAY"
CURRENT_WEATHER_LOCK = threading.Lock()


WEATHER_METRICS = {
    "CLEAR_DAY": {
        "status": "PASS",
        "windSpeedMps": 3.2,
        "windGustMps": 4.5,
        "precipitationMmH": 0.0,
        "visibilityKm": 14.0,
        "temperatureC": 30.0,
        "humidityPercent": 62,
        "advisory": "Trời quang, điều kiện bay mô phỏng ổn định.",
    },
    "SUNSET": {
        "status": "PASS",
        "windSpeedMps": 3.8,
        "windGustMps": 5.2,
        "precipitationMmH": 0.0,
        "visibilityKm": 10.5,
        "temperatureC": 28.0,
        "humidityPercent": 68,
        "advisory": "Ánh sáng thấp dần, vẫn đủ điều kiện bay mô phỏng.",
    },
    "NIGHT": {
        "status": "WARN",
        "windSpeedMps": 3.0,
        "windGustMps": 4.0,
        "precipitationMmH": 0.0,
        "visibilityKm": 4.5,
        "temperatureC": 25.0,
        "humidityPercent": 72,
        "advisory": "Bay đêm làm giảm tầm nhìn, cần bật đèn và giữ độ cao an toàn.",
    },
    "CLOUDY": {
        "status": "PASS",
        "windSpeedMps": 5.8,
        "windGustMps": 7.9,
        "precipitationMmH": 0.6,
        "visibilityKm": 12.6,
        "temperatureC": 29.0,
        "humidityPercent": 76,
        "advisory": "Nhiều mây nhẹ, đủ điều kiện bay mô phỏng.",
    },
    "FOGGY": {
        "status": "WARN",
        "windSpeedMps": 2.4,
        "windGustMps": 3.5,
        "precipitationMmH": 0.2,
        "visibilityKm": 2.8,
        "temperatureC": 26.0,
        "humidityPercent": 88,
        "advisory": "Sương mù làm giảm tầm nhìn, cần cân nhắc hoãn bay.",
    },
    "WINDY": {
        "status": "WARN",
        "windSpeedMps": 12.6,
        "windGustMps": 15.2,
        "precipitationMmH": 0.0,
        "visibilityKm": 9.0,
        "temperatureC": 28.0,
        "humidityPercent": 70,
        "advisory": "Gió mạnh, cần giảm trần bay hoặc hoãn nhiệm vụ.",
    },
    "LIGHT_RAIN": {
        "status": "WARN",
        "windSpeedMps": 6.1,
        "windGustMps": 8.4,
        "precipitationMmH": 1.8,
        "visibilityKm": 6.8,
        "temperatureC": 27.0,
        "humidityPercent": 84,
        "advisory": "Mưa nhẹ, kiểm tra camera/payload trước khi bay.",
    },
    "HEAVY_RAIN": {
        "status": "FAIL",
        "windSpeedMps": 14.8,
        "windGustMps": 19.5,
        "precipitationMmH": 8.6,
        "visibilityKm": 2.0,
        "temperatureC": 25.0,
        "humidityPercent": 94,
        "advisory": "Mưa lớn và gió mạnh, không đủ điều kiện cất cánh.",
    },
}


def build_weather_preflight_status() -> dict:
    with CURRENT_WEATHER_LOCK:
        preset_name = CURRENT_WEATHER_PRESET
    preset = WEATHER_PRESETS.get(preset_name, WEATHER_PRESETS["CLEAR_DAY"])
    metrics = WEATHER_METRICS.get(preset_name, WEATHER_METRICS["CLEAR_DAY"])
    status = metrics["status"]
    safe_to_fly = status == "PASS"
    summary = (
        "Thời tiết phù hợp để cất cánh."
        if safe_to_fly
        else "Thời tiết trong mô phỏng cần chú ý trước khi cất cánh."
    )
    return {
        "source": "flight-controller-simulation",
        "weatherPreset": preset_name,
        "weatherLabel": preset["label"],
        "status": status,
        "safeToFly": safe_to_fly,
        "summary": summary,
        "windSpeedMps": metrics["windSpeedMps"],
        "windGustMps": metrics["windGustMps"],
        "precipitationMmH": metrics["precipitationMmH"],
        "visibilityKm": metrics["visibilityKm"],
        "temperatureC": metrics["temperatureC"],
        "humidityPercent": metrics["humidityPercent"],
        "advisories": [metrics["advisory"]],
        "checkedAt": datetime.now(timezone.utc).isoformat(),
    }

class MavsdkAckNoiseFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        return "Received ack for not-existing command: 512" not in message


def configure_mavsdk_logging() -> None:
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except AttributeError:
        pass

    logging.basicConfig(level=logging.WARNING, format="%(message)s")
    logging.getLogger("mavsdk_server").addFilter(MavsdkAckNoiseFilter())


def env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def read_key() -> str:
    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        return sys.stdin.read(1).lower()
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)


def read_key_timeout(timeout_s: float) -> str | None:
    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        readable, _, _ = select.select([sys.stdin], [], [], timeout_s)
        if not readable:
            return None
        return sys.stdin.read(1).lower()
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)


def gz_service_exists(service_name: str) -> bool:
    try:
        result = subprocess.run(
            ["gz", "service", "-l"],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    if result.returncode != 0:
        return False
    return service_name in {line.strip() for line in result.stdout.splitlines()}


def gz_topic_exists(topic_name: str) -> bool:
    try:
        result = subprocess.run(
            ["gz", "topic", "-l"],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    if result.returncode != 0:
        return False
    return topic_name in {line.strip() for line in result.stdout.splitlines()}


def set_gazebo_light(preset: dict[str, str | None]) -> bool:
    if not gz_service_exists(GAZEBO_LIGHT_SERVICE):
        print(f"[WEATHER][WARN] Light service not ready: {GAZEBO_LIGHT_SERVICE}", flush=True)
        return False

    request = (
        f"name: \"sunUTC\" type: DIRECTIONAL cast_shadows: true "
        f"intensity: {preset['intensity']} "
        f"direction {{ {preset['direction']} }} "
        f"diffuse {{ {preset['diffuse']} }} "
        f"specular {{ {preset['specular']} }}"
    )
    try:
        result = subprocess.run(
            [
                "gz",
                "service",
                "-s",
                GAZEBO_LIGHT_SERVICE,
                "--reqtype",
                "gz.msgs.Light",
                "--reptype",
                "gz.msgs.Boolean",
                "--timeout",
                "10000",
                "--req",
                request,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=3,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def set_gazebo_wind(linear_velocity: str | None) -> bool:
    if not gz_topic_exists(GAZEBO_WIND_TOPIC):
        return False

    if linear_velocity is None:
        payload = "enable_wind: false linear_velocity { x: 0 y: 0 z: 0 }"
    else:
        payload = f"enable_wind: true linear_velocity {{ {linear_velocity} }}"

    try:
        result = subprocess.run(
            [
                "gz",
                "topic",
                "-t",
                GAZEBO_WIND_TOPIC,
                "-m",
                "gz.msgs.Wind",
                "-d",
                "0.2",
                "-p",
                payload,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def apply_weather_key(key: str) -> bool:
    preset_name = WEATHER_KEY_PRESETS[key]
    preset = WEATHER_PRESETS[preset_name]
    print(f"[WEATHER] {key} -> {preset['label']}", flush=True)

    light_ok = set_gazebo_light(preset)
    wind_ok = set_gazebo_wind(preset["wind"])

    if not light_ok:
        print("[WEATHER][ERROR] Gazebo light service was not ready.", flush=True)
        return False
    if preset["wind"] is not None and not wind_ok:
        print(f"[WEATHER] Applied {preset['label']} (lighting only)", flush=True)
    else:
        print(f"[WEATHER] Applied {preset['label']}", flush=True)
    with CURRENT_WEATHER_LOCK:
        global CURRENT_WEATHER_PRESET
        CURRENT_WEATHER_PRESET = preset_name
    return True


class CameraGateway:
    def __init__(
        self,
        video_recorder: VideoRecorder | None = None,
        media_library: LocalMediaLibrary | None = None,
    ) -> None:
        self.latest_frames: dict[str, GzImage] = {}
        self.latest_frame_time_s: dict[str, float] = {}
        self.latest_frame_versions: dict[str, int] = {}
        self.latest_jpegs: dict[tuple[str, str], tuple[int, bytes]] = {}
        self.current_mode = CAMERA_DEFAULT_VIEW if CAMERA_DEFAULT_VIEW in {"DOWN", "FRONT"} else "FRONT"
        self.lock = threading.Lock()
        self.node = None
        self.video_recorder = video_recorder
        self.media_library = media_library

    def start(self) -> None:
        if Node is None or GzImage is None:
            print("[CAMERA] Gazebo Python bindings not found")
            print("[CAMERA] Run with PYTHONPATH=/usr/lib/python3/dist-packages")
            return

        self.node = Node()
        topics = {"FRONT": CAMERA_FRONT_TOPIC, "DOWN": CAMERA_DOWN_TOPIC}
        for mode, topic in topics.items():
            self.node.subscribe(GzImage, topic, self._make_frame_handler(mode))
            print(f"[CAMERA] Listening to {mode.lower()} sensor: {topic}")
        if CAMERA_LEGACY_DOWN_SENSOR_ENABLED and CAMERA_TOPIC not in topics.values():
            self.node.subscribe(GzImage, CAMERA_TOPIC, self._make_frame_handler(self.current_mode))
            print(f"[CAMERA] Listening to fallback sensor: {CAMERA_TOPIC}")

    def _make_frame_handler(self, mode: str):
        def _on_frame(msg: GzImage, *_args) -> None:
            with self.lock:
                self.latest_frames[mode] = msg
                self.latest_frame_time_s[mode] = time.monotonic()
                self.latest_frame_versions[mode] = self.latest_frame_versions.get(mode, 0) + 1
                is_active_mode = mode == self.current_mode
            if is_active_mode and self.video_recorder is not None and self.video_recorder.is_recording():
                self.video_recorder.submit_frame(msg)
        return _on_frame

    def latest_frame_age_s(self, mode: str | None = None) -> float | None:
        target_mode = (mode or self.current_mode).strip().upper()
        with self.lock:
            timestamp = self.latest_frame_time_s.get(target_mode)
            if timestamp is None:
                timestamps = list(self.latest_frame_time_s.values())
                timestamp = max(timestamps) if timestamps else None
        if timestamp is None:
            return None
        return time.monotonic() - timestamp

    def set_view_mode(self, mode: str) -> None:
        normalized = mode.strip().upper()
        if normalized not in {"DOWN", "FRONT"}:
            return
        with self.lock:
            self.current_mode = normalized

    def _latest_jpeg(self, *, preview: bool = False) -> bytes | None:
        with self.lock:
            mode = self.current_mode
            frame = self.latest_frames.get(mode)
            if frame is None:
                mode = "DOWN" if "DOWN" in self.latest_frames else "FRONT"
                frame = self.latest_frames.get(mode)
            version = self.latest_frame_versions.get(mode, 0)
            cache_key = (mode, "preview" if preview else "capture")
            cached = self.latest_jpegs.get(cache_key)
            if cached is not None and cached[0] == version:
                return cached[1]

        if frame is None:
            return None

        width = int(frame.width)
        height = int(frame.height)
        raw = bytes(frame.data)
        expected_rgb = width * height * 3
        expected_rgba = width * height * 4

        if len(raw) == expected_rgba:
            image = PilImage.frombytes("RGBA", (width, height), raw).convert("RGB")
        elif len(raw) == expected_rgb:
            image = PilImage.frombytes("RGB", (width, height), raw)
        else:
            print(f"[CAMERA] Unsupported frame size: {len(raw)} bytes for {width}x{height}")
            return None

        quality = CAMERA_CAPTURE_JPEG_QUALITY
        if preview:
            quality = CAMERA_STREAM_JPEG_QUALITY
            if CAMERA_STREAM_MAX_WIDTH > 0 and width > CAMERA_STREAM_MAX_WIDTH:
                preview_height = max(1, round(height * (CAMERA_STREAM_MAX_WIDTH / width)))
                resample = getattr(getattr(PilImage, "Resampling", PilImage), "BILINEAR")
                image = image.resize((CAMERA_STREAM_MAX_WIDTH, preview_height), resample)

        output = BytesIO()
        image.save(output, format="JPEG", quality=quality, optimize=False)
        jpeg = output.getvalue()
        with self.lock:
            if self.latest_frame_versions.get(mode) == version:
                self.latest_jpegs[cache_key] = (version, jpeg)
        return jpeg

    async def capture_for_review(self) -> None:
        print("[CAMERA] Drone camera capture requested")
        jpeg = await asyncio.to_thread(self._latest_jpeg)
        if jpeg is None:
            print("[CAMERA] No camera frame available")
            return

        if self.media_library is None:
            print("[CAMERA] Local media library unavailable")
            return
        item = await asyncio.to_thread(self.media_library.capture_image, jpeg)
        print(f"[CAMERA] Captured for operator review id={item['localMediaId']}", flush=True)

    async def retain_recorded_video(self, recording: RecordingResult) -> None:
        if self.media_library is None:
            print("[VIDEO] Local media library unavailable", flush=True)
            return
        item = await asyncio.to_thread(self.media_library.register_video, recording.path)
        print(f"[VIDEO] Captured for operator review id={item['localMediaId']}", flush=True)


class CameraOrientationController:
    def __init__(self, on_mode_change=None) -> None:
        self.current_mode = CAMERA_DEFAULT_VIEW if CAMERA_DEFAULT_VIEW in {"DOWN", "FRONT"} else "FRONT"
        self.current_pitch_deg = -90.0 if self.current_mode == "DOWN" else 0.0
        self._pitch_direction = 1.0 if self.current_pitch_deg <= -90.0 else -1.0
        self._last_toggle_s = 0.0
        self.on_mode_change = on_mode_change

    def toggle(self) -> None:
        now = time.monotonic()
        if now - self._last_toggle_s < CAMERA_TOGGLE_DEBOUNCE_S:
            return
        self._last_toggle_s = now

        next_mode = "FRONT" if self.current_mode == "DOWN" else "DOWN"
        self.set_mode(next_mode)

    def set_mode(self, mode: str) -> None:
        normalized = mode.strip().upper()
        if normalized not in {"DOWN", "FRONT"}:
            return
        self.set_pitch(-90.0 if normalized == "DOWN" else 0.0)

    def set_pitch(self, pitch_deg: float) -> None:
        clamped_pitch = max(-90.0, min(0.0, pitch_deg))
        if not self.request_pitch(clamped_pitch):
            return
        normalized = "DOWN" if clamped_pitch <= -89.5 else "FRONT"
        if self.on_mode_change is not None:
            self.on_mode_change(normalized)
        self.current_mode = normalized
        self.current_pitch_deg = clamped_pitch
        self._write_state(normalized, clamped_pitch)
        print(f"[CAMERA] Pitch -> {clamped_pitch:.0f} deg (press c for next 30 deg step)", flush=True)

    def _write_state(self, mode: str, pitch_deg: float) -> None:
        payload = {
            "mode": mode,
            "pitchDeg": pitch_deg,
            "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        try:
            CAMERA_VIEW_STATE_FILE.write_text(json.dumps(payload), encoding="utf-8")
        except OSError as exc:
            print(f"[CAMERA] State write failed: {exc}", flush=True)

    def request_pitch(self, pitch_deg: float) -> bool:
        # UI pitch is expressed as 0..-90 degrees. Use the configured Gazebo
        # joint positions so the sign matches the actual simulation model.
        down_ratio = abs(max(-90.0, min(0.0, pitch_deg))) / 90.0
        joint_position = (
            CAMERA_FRONT_JOINT_POSITION_RAD
            + (CAMERA_DOWN_JOINT_POSITION_RAD - CAMERA_FRONT_JOINT_POSITION_RAD)
            * down_ratio
        )
        topics = tuple(dict.fromkeys((GAZEBO_CAMERA_PITCH_TOPIC, GAZEBO_CAMERA_JOINT_TOPIC)))
        sent = False
        errors: list[str] = []
        for topic in topics:
            command = [
                "gz",
                "topic",
                "-t",
                topic,
                "-m",
                "gz.msgs.Double",
                "-p",
                f"data: {joint_position:.12f}",
            ]
            try:
                result = subprocess.run(
                    command,
                    capture_output=True,
                    text=True,
                    timeout=1.5,
                    check=False,
                )
            except (OSError, subprocess.TimeoutExpired) as exc:
                errors.append(f"{topic}: {exc}")
                continue

            if result.returncode == 0:
                sent = True
            else:
                message = (result.stderr or result.stdout or "camera command failed").strip()
                errors.append(f"{topic}: {message}")

        if sent:
            return True

        print(f"[CAMERA] Pitch command unavailable: {'; '.join(errors)}", flush=True)
        return False


async def connect_px4(drone: System) -> bool:
    print("[PX4] Waiting for control connection...")

    try:
        await drone.connect()

        async with asyncio.timeout(20):
            async for state in drone.core.connection_state():
                if state.is_connected:
                    print("[PX4] Control connected")
                    break
    except (asyncio.TimeoutError, grpc.aio.AioRpcError) as exc:
        print_mavsdk_unavailable("PX4 init", exc)
        print("[WARN] Continuing in degraded mode")
        print("[WARN] Arm/takeoff will perform their own readiness checks")
        return False

    await configure_px4_speed_limits(drone)

    print("[PX4] Waiting for local position (up to 60s)...")
    try:
        async with asyncio.timeout(60):
            async for health in drone.telemetry.health():
                status = (
                    f"  gps={'OK' if health.is_global_position_ok else 'wait'}"
                    f"  local={'OK' if health.is_local_position_ok else 'wait'}"
                    f"  accel={'OK' if health.is_accelerometer_calibration_ok else 'wait'}"
                    f"  home={'OK' if health.is_home_position_ok else 'wait'}"
                )
                print(f"[PX4] Health: {status}", flush=True)
                if health.is_local_position_ok and health.is_global_position_ok:
                    print("\n[PX4] Local position OK - Ready to fly!")
                    print("[CHECK] Flight Controller: READY", flush=True)
                    return True
    except (asyncio.TimeoutError, grpc.aio.AioRpcError):
        print("\n[WARN] PX4 telemetry health stream unavailable")
        print("[WARN] Continuing in degraded mode")
        print("[WARN] Arm/takeoff will perform their own readiness checks")
        return False


async def configure_px4_speed_limits(
        drone: System,
        horizontal_speed_m_s: float | None = None,
        vertical_speed_m_s: float | None = None,
) -> None:
    horizontal = MOVE_SPEED_M_S if horizontal_speed_m_s is None else horizontal_speed_m_s
    vertical = VERTICAL_SPEED_M_S if vertical_speed_m_s is None else vertical_speed_m_s
    limit = max(PX4_SPEED_LIMIT_M_S, horizontal, vertical)
    if REALISTIC_FLIGHT:
        xy = min(horizontal, REAL_MAX_XY_SPEED_M_S)
        params = {
            "MPC_XY_CRUISE": min(xy, GPS_FLIGHT_CRUISE_SPEED_M_S),
            "MPC_XY_VEL_MAX": xy,
            "MPC_XY_VEL_ALL": xy,
            "MPC_Z_VEL_MAX_UP": min(vertical, REAL_CLIMB_SPEED_M_S),
            "MPC_Z_VEL_MAX_DN": min(vertical, REAL_DESCENT_SPEED_M_S),
            "MPC_TKO_SPEED": REAL_TAKEOFF_SPEED_M_S,
            "MPC_ACC_HOR_MAX": REAL_ACC_HOR_M_S2,
            "MPC_ACC_HOR": REAL_ACC_HOR_M_S2,
            "MPC_ACC_UP_MAX": REAL_ACC_VERT_M_S2,
            "MPC_ACC_DOWN_MAX": REAL_ACC_VERT_M_S2,
        }
    else:
        params = {
            "MPC_XY_CRUISE": horizontal,
            "MPC_XY_VEL_MAX": horizontal,
            "MPC_XY_VEL_ALL": horizontal,
            "MPC_Z_VEL_MAX_UP": vertical,
            "MPC_Z_VEL_MAX_DN": vertical,
            "MPC_TKO_SPEED": vertical,
            "MPC_ACC_HOR_MAX": limit,
            "MPC_ACC_UP_MAX": limit,
            "MPC_ACC_DOWN_MAX": limit,
        }

    applied = []
    skipped = []
    for name, value in params.items():
        try:
            await drone.param.set_param_float(name, float(value))
            applied.append(f"{name}={float(value):.1f}")
        except Exception:
            skipped.append(name)

    if applied:
        print(f"[PX4] Speed params synced: {', '.join(applied)}", flush=True)
    if skipped:
        print(f"[PX4] Speed params skipped: {', '.join(skipped)}", flush=True)


def print_command_denied(command: str, exc: Exception) -> None:
    print(f"[WARN] {command} failed: {exc}")
    print("[HINT] Check PX4 terminal shows 'Ready for takeoff!' then press t again.")


def print_mavsdk_unavailable(command: str, exc: Exception) -> None:
    code = exc.code().name if isinstance(exc, grpc.aio.AioRpcError) else type(exc).__name__
    details = exc.details() if isinstance(exc, grpc.aio.AioRpcError) else str(exc)
    print(f"[WARN] {command} failed: MAVSDK connection unavailable ({code}: {details})")
    print("[HINT] PX4/Gazebo may still be running. Restart only the Flight Control tab, or rerun start-drone-stack.cmd.")


def is_monitor_running(process_pattern: str) -> bool:
    command = (
        f"pgrep -af {shlex.quote(process_pattern)} "
        "| grep -v 'pgrep -af' "
        "| grep -v 'bash -lc' "
        "| grep -v 'flight_controller.py' "
        ">/dev/null 2>&1"
    )
    result = subprocess.run(["bash", "-lc", command], check=False)
    return result.returncode == 0


def stop_monitor_window(name: str, process_pattern: str) -> None:
    command = (
        f"for pid in $(pgrep -f {shlex.quote(process_pattern)} 2>/dev/null || true); do "
        "cmd=$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null || true); "
        "case \"$cmd\" in "
        "*flight_controller.py*|*pgrep -f*|*bash -lc*) continue ;; "
        "esac; "
        "kill \"$pid\" 2>/dev/null || true; "
        "done"
    )
    subprocess.run(["bash", "-lc", command], check=False)
    print(f"[MONITOR] Closed {name}", flush=True)


def open_monitor_window(name: str, script_name: str) -> None:
    script = PROJECT_ROOT / "scripts" / script_name
    if not script.exists():
        print(f"[MONITOR] {name} script not found: {script}", flush=True)
        return

    command = f"SIM_WORLD=compact exec {script.as_posix()}"
    try:
        subprocess.Popen(
            [
                "wt.exe",
                "new-tab",
                "--title",
                name,
                "wsl.exe",
                "-d",
                "Ubuntu-24.04",
                "--",
                "bash",
                "-lc",
                command,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        print(f"[MONITOR] Failed to open {name}: {exc}", flush=True)
        return

    print(f"[MONITOR] Opening {name}...", flush=True)


def toggle_monitor_window(name: str, script_name: str, process_pattern: str) -> None:
    if is_monitor_running(process_pattern):
        stop_monitor_window(name, process_pattern)
        return
    open_monitor_window(name, script_name)


def open_monitor_window_if_needed(name: str, script_name: str, process_pattern: str) -> None:
    if is_monitor_running(process_pattern):
        print(f"[MONITOR] {name} already open", flush=True)
        return
    open_monitor_window(name, script_name)


CONTROL_COMMAND_KEYS = {
    "takeoff": "t",
    "forward": "w",
    "back": "s",
    "backward": "s",
    "left": "a",
    "right": "d",
    "up": "f",
    "down": "v",
    "yaw_left": "q",
    "yaw-right": "e",
    "yaw_right": "e",
    "stop": "k",
    "hover": "k",
    "safety_toggle": "o",
    "speed_up": "1",
    "speed_down": "2",
    "camera_switch": "c",
    "camera_front": "camera_front",
    "camera_fpv": "camera_front",
    "camera_reset": "camera_front",
    "camera_down": "camera_down",
    "camera_monitor_toggle": "3",
    "lidar_monitor_toggle": "4",
    "telemetry_monitor_toggle": "5",
    "thermal_toggle": "6",
    "thermal_viewer_toggle": "7",
    "thermal_palette_next": "thermal_palette_next",
    "thermal_isotherm_toggle": "thermal_isotherm_toggle",
    "thermal_debug_toggle": "thermal_debug_toggle",
    "thermal_range_toggle": "thermal_range_toggle",
    "photo": "p",
    "video_toggle": "r",
    "land": "l",
    "return_to_base": "l",
    "emergency_stop": "l",
}


def normalize_auto_plan_waypoints(payload: dict) -> list[dict]:
    raw_points = payload.get("waypoints")
    if not isinstance(raw_points, list):
        return []
    normalized = []
    for index, point in enumerate(raw_points):
        if not isinstance(point, dict):
            continue
        try:
            sim_x = float(point["simX"])
            sim_y = float(point["simY"])
            altitude_m = float(point.get("altitudeM", 0.0))
        except (KeyError, TypeError, ValueError):
            continue
        if not all(math.isfinite(value) for value in (sim_x, sim_y, altitude_m)):
            continue
        speed = point.get("speedMps")
        try:
            speed_mps = float(speed) if speed is not None else AUTO_PLAN_MAX_SPEED_M_S
        except (TypeError, ValueError):
            speed_mps = AUTO_PLAN_MAX_SPEED_M_S
        if not math.isfinite(speed_mps) or speed_mps <= 0.0:
            speed_mps = AUTO_PLAN_MAX_SPEED_M_S
        normalized.append(
            {
                "sequence": int(point.get("sequence", index)),
                "simX": sim_x,
                "simY": sim_y,
                "altitudeM": max(0.0, altitude_m),
                "speedMps": min(speed_mps, AUTO_PLAN_MAX_SPEED_M_S),
                "reason": str(point.get("reason", "CRUISE")),
            }
        )
    return sorted(normalized, key=lambda item: item["sequence"])


def gps_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius_m = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2) ** 2
    return 2 * radius_m * math.asin(min(1.0, math.sqrt(a)))


def gps_bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_lambda = math.radians(lon2 - lon1)
    y = math.sin(delta_lambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(delta_lambda)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def gps_offset_m(origin_lat: float, origin_lon: float, latitude: float, longitude: float) -> tuple[float, float]:
    radius_m = 6371000.0
    north_m = math.radians(latitude - origin_lat) * radius_m
    east_m = math.radians(longitude - origin_lon) * radius_m * math.cos(math.radians(origin_lat))
    return north_m, east_m


def normalize_gps_target(payload: dict, current_position: dict | None, in_air: bool) -> dict:
    if current_position is None:
        raise ValueError("Drone needs fresh PX4 GPS before GPS navigation")
    try:
        latitude = float(payload["latitude"])
        longitude = float(payload["longitude"])
        relative_altitude_m = float(payload.get("relativeAltitudeM", 20.0))
        requested_speed_m_s = float(payload.get("speedMps", 6.0))
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("GPS target requires latitude, longitude and numeric altitude") from exc
    if not all(math.isfinite(value) for value in (latitude, longitude, relative_altitude_m)) \
            or abs(latitude) > 90 or abs(longitude) > 180:
        raise ValueError("GPS target must contain valid WGS84 coordinates")
    if not 5.0 <= relative_altitude_m <= 50.0:
        raise ValueError("GPS target altitude must be between 5 and 50 metres")
    if not math.isfinite(requested_speed_m_s) or requested_speed_m_s <= 0.0:
        requested_speed_m_s = 6.0
    requested_speed_m_s = min(45.0, max(3.0, requested_speed_m_s))
    distance_m = gps_distance_m(current_position["latitude"], current_position["longitude"], latitude, longitude)
    if distance_m > 120000.0:
        raise ValueError("GPS target is outside the configured Ho Chi Minh City flight range")
    route_waypoints = []
    raw_route = payload.get("routeWaypoints")
    if isinstance(raw_route, list):
        previous = current_position
        for index, waypoint in enumerate(raw_route[:8]):
            if not isinstance(waypoint, dict):
                continue
            try:
                waypoint_latitude = float(waypoint["latitude"])
                waypoint_longitude = float(waypoint["longitude"])
                waypoint_altitude_m = float(waypoint.get("relativeAltitudeM", relative_altitude_m))
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError("GPS route waypoint requires latitude, longitude and numeric altitude") from exc
            if not all(math.isfinite(value) for value in (waypoint_latitude, waypoint_longitude, waypoint_altitude_m)) \
                    or abs(waypoint_latitude) > 90 or abs(waypoint_longitude) > 180:
                raise ValueError("GPS route waypoint must contain valid WGS84 coordinates")
            if not 5.0 <= waypoint_altitude_m <= 50.0:
                raise ValueError("GPS route waypoint altitude must be between 5 and 50 metres")
            leg_distance_m = gps_distance_m(
                previous["latitude"],
                previous["longitude"],
                waypoint_latitude,
                waypoint_longitude,
            )
            route_waypoints.append(
                {
                    "sequence": int(waypoint.get("sequence", index)),
                    "latitude": waypoint_latitude,
                    "longitude": waypoint_longitude,
                    "relativeAltitudeM": waypoint_altitude_m,
                    "distanceM": leg_distance_m,
                }
            )
            previous = route_waypoints[-1]
    if not route_waypoints:
        route_waypoints = [
            {
                "sequence": 0,
                "latitude": latitude,
                "longitude": longitude,
                "relativeAltitudeM": relative_altitude_m,
                "distanceM": distance_m,
            }
        ]
    return {
        "latitude": latitude,
        "longitude": longitude,
        "relativeAltitudeM": relative_altitude_m,
        "distanceM": distance_m,
        "speedMps": requested_speed_m_s,
        "routeWaypoints": sorted(route_waypoints, key=lambda item: item["sequence"]),
    }


class PreflightPersistenceBridge:
    def __init__(
        self,
        backend_urls: BackendUrlResolver,
        mission_id: str,
        access_token: str | None = None,
    ) -> None:
        self.backend_urls = backend_urls
        self.mission_id = mission_id
        self.access_token = access_token
        self.run_id: str | None = None
        self.base_url: str | None = None
        self.sent_items: dict[str, tuple[str, str]] = {}
        self.warned_unavailable = False

    def start(self) -> str | None:
        self.run_id = None
        self.base_url = None
        self.sent_items.clear()

        for base_url in self.backend_urls.candidates():
            try:
                response = httpx.post(
                    f"{base_url}/api/missions/{self.mission_id}/pre-device-checks",
                    headers=self._headers(),
                    timeout=1.5,
                )
                if response.status_code >= 400:
                    continue

                payload = response.json()
                data = payload.get("data") if isinstance(payload, dict) else None
                run_id = data.get("id") if isinstance(data, dict) else None
                if not run_id:
                    continue

                self.run_id = str(run_id)
                self.base_url = base_url
                self.warned_unavailable = False
                print(
                    f"[PRE-DEVICE-DB] Created run={self.run_id} mission={self.mission_id}",
                    flush=True,
                )
                return self.run_id
            except (httpx.HTTPError, ValueError):
                continue

        if not self.warned_unavailable:
            print(
                f"[PRE-DEVICE-DB] Not saved. Backend unavailable or mission missing: {self.mission_id}",
                flush=True,
            )
            self.warned_unavailable = True
        return None

    def sync(self, payload: dict) -> None:
        if not self.run_id or not self.base_url:
            return

        for item in payload.get("checks", []):
            check_type = str(item.get("key", "")).strip()
            status = self._to_backend_status(str(item.get("status", "")).strip().upper())
            message = str(item.get("message", "")).strip()

            if not check_type or status is None:
                continue

            state = (status, message)
            if self.sent_items.get(check_type) == state:
                continue

            try:
                response = httpx.patch(
                    f"{self.base_url}/api/pre-device-checks/{self.run_id}/items/{check_type}",
                    headers=self._headers(),
                    json={"status": status, "message": message},
                    timeout=1.0,
                )
                if response.status_code < 400:
                    self.sent_items[check_type] = state
            except httpx.HTTPError:
                return

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.access_token}"} if self.access_token else {}

    def verify_media_upload_cycle(self, jpeg: bytes) -> tuple[bool, str]:
        if not self.base_url:
            return False, "Backend run was not created, cannot verify media upload"
        if not self.run_id:
            return False, "Pre-device check run is missing, cannot verify media upload"
        return verify_media_storage_probe(
            self.base_url,
            self.run_id,
            self.access_token,
            jpeg,
        )

    @staticmethod
    def _to_backend_status(status: str) -> str | None:
        if status == "PASS":
            return "PASSED"
        if status in {"FAIL", "WARN"}:
            return "FAILED"
        if status in {"PENDING", "CHECKING"}:
            return status
        return None


class FlightControlApi:
    def __init__(
        self,
        camera: CameraGateway,
        commands: "queue.Queue[str]",
        status_provider=None,
        preflight_provider=None,
        preflight_persistence=None,
        thermal: ThermalCameraGateway | None = None,
        media_library: LocalMediaLibrary | None = None,
        session_binder=None,
        session_releaser=None,
    ) -> None:
        self.camera = camera
        self.commands = commands
        self.status_provider = status_provider
        self.preflight_provider = preflight_provider
        self.preflight_persistence = preflight_persistence
        self.thermal = thermal
        self.media_library = media_library
        self.session_binder = session_binder
        self.session_releaser = session_releaser
        self.preflight_check_id: str | None = None
        self.preflight_started_at_s: float | None = None
        self.server: ThreadingHTTPServer | None = None
        self.thread: threading.Thread | None = None

    def start(self) -> None:
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format: str, *_args) -> None:
                return

            def _cors(self) -> None:
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Media-Source-Type")

            def _write_json(self, status_code: int, payload: dict) -> bool:
                try:
                    self.send_response(status_code)
                    self._cors()
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps(payload).encode("utf-8"))
                    return True
                except (BrokenPipeError, ConnectionResetError, OSError):
                    return False

            def do_OPTIONS(self) -> None:
                self.send_response(204)
                self._cors()
                self.end_headers()

            def do_GET(self) -> None:
                parsed_path = urlparse(self.path)
                if parsed_path.path == "/api/media/local":
                    query = parse_qs(parsed_path.query)
                    mission_keys = set(
                        key.strip()
                        for key in [*query.get("missionId", []), *query.get("missionAlias", [])]
                        if key.strip()
                    )
                    self._write_json(200, {"media": owner.media_library.list_items(mission_keys or None) if owner.media_library else []})
                    return
                if parsed_path.path.startswith("/api/media/local/") and parsed_path.path.endswith("/preview"):
                    local_id = parsed_path.path.split("/")[4]
                    try:
                        item = owner.media_library.get(local_id)
                        source = Path(item["localPath"])
                        size = source.stat().st_size
                        start, end = 0, size - 1
                        range_header = self.headers.get("Range")
                        if range_header:
                            if not range_header.startswith("bytes=") or "," in range_header:
                                self._write_json(416, {"error": "Invalid range"})
                                return
                            first, _, last = range_header[6:].partition("-")
                            if not first:
                                self._write_json(416, {"error": "Invalid range"})
                                return
                            start = int(first)
                            end = int(last) if last else size - 1
                            if start < 0 or end < start or end >= size:
                                self._write_json(416, {"error": "Range outside media"})
                                return
                        self.send_response(206 if range_header else 200)
                        self._cors()
                        self.send_header("Content-Type", item["contentType"])
                        self.send_header("Content-Length", str(end - start + 1))
                        self.send_header("Accept-Ranges", "bytes")
                        if range_header:
                            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        with source.open("rb") as media_file:
                            media_file.seek(start)
                            remaining = end - start + 1
                            while remaining and (chunk := media_file.read(min(1024 * 1024, remaining))):
                                self.wfile.write(chunk)
                                remaining -= len(chunk)
                    except (KeyError, FileNotFoundError):
                        self._write_json(404, {"error": "Media not found"})
                    except ValueError:
                        self._write_json(416, {"error": "Invalid range"})
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                    return
                if self.path.startswith("/api/control/status"):
                    status = {"online": True}
                    if owner.status_provider is not None:
                        try:
                            status.update(owner.status_provider())
                        except Exception as exc:
                            status["statusError"] = str(exc)
                    self._write_json(200, status)
                    return

                if self.path.startswith("/api/weather/preflight-check"):
                    self._write_json(200, build_weather_preflight_status())
                    return

                if self.path.startswith("/api/preflight/"):
                    check_id = self.path.rstrip("/").rsplit("/", 1)[-1]
                    if owner.preflight_provider is None or check_id != owner.preflight_check_id:
                        self.send_response(404)
                        self._cors()
                        self.end_headers()
                        return
                    payload = owner.preflight_provider(check_id, owner.preflight_started_at_s)
                    self._write_json(200, payload)
                    return

                stream_source = "rgb"
                if self.path.startswith("/thermal-stream.mjpg"):
                    stream_source = "thermal"
                elif not self.path.startswith("/stream.mjpg"):
                    self.send_response(404)
                    self._cors()
                    self.end_headers()
                    return

                self.send_response(200)
                self._cors()
                self.send_header("Age", "0")
                self.send_header("Cache-Control", "no-cache, private")
                self.send_header("Pragma", "no-cache")
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.end_headers()

                while True:
                    try:
                        if stream_source == "thermal":
                            frame = owner.thermal.latest_jpeg() if owner.thermal is not None else None
                        else:
                            frame = owner.camera._latest_jpeg(preview=True)
                        if frame is None:
                            time.sleep(0.15)
                            continue
                        self.wfile.write(b"--frame\r\n")
                        self.wfile.write(b"Content-Type: image/jpeg\r\n")
                        self.wfile.write(f"Content-Length: {len(frame)}\r\n\r\n".encode("ascii"))
                        self.wfile.write(frame)
                        self.wfile.write(b"\r\n")
                        time.sleep(1 / max(1.0, CAMERA_STREAM_FPS))
                    except (BrokenPipeError, ConnectionResetError, OSError):
                        return

            def do_POST(self) -> None:
                if self.path == "/api/control/session":
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                        if length <= 0 or length > 16_384:
                            self._write_json(400, {"error": "Invalid session payload"})
                            return
                        payload = json.loads(self.rfile.read(length))
                        if owner.session_binder is None:
                            self._write_json(503, {"error": "Session binding unavailable"})
                            return
                        result = owner.session_binder(payload)
                        self._write_json(200, {"ok": True, **result})
                    except (ValueError, json.JSONDecodeError) as exc:
                        self._write_json(400, {"error": str(exc)[:500]})
                    return

                if self.path == "/api/control/session/release":
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                        payload = json.loads(self.rfile.read(length) or b"{}") if length > 0 else {}
                        if owner.session_releaser is None:
                            self._write_json(503, {"error": "Session release unavailable"})
                            return
                        result = owner.session_releaser(payload)
                        self._write_json(200, {"ok": True, **result})
                    except (ValueError, json.JSONDecodeError) as exc:
                        self._write_json(400, {"error": str(exc)[:500]})
                    return

                if self.path == "/api/media/local/capture":
                    try:
                        if owner.media_library is None or not owner.media_library.mission_id:
                            self._write_json(409, {"ok": False, "error": "Bind an assigned mission before capturing media"})
                            return
                        length = int(self.headers.get("Content-Length", "0"))
                        if length <= 0 or length > 15_000_000:
                            self._write_json(413, {"ok": False, "error": "Snapshot must be a JPEG under 15 MB"})
                            return
                        # Raw browser capture is not implicitly a drone camera frame.
                        source_type = self.headers.get("X-Media-Source-Type")
                        if source_type is None:
                            raise ValueError("Media source type is required for browser capture")
                        item = owner.media_library.capture_image(self.rfile.read(length), source_type)
                        print(f"[CAMERA] Browser snapshot captured for operator review id={item['localMediaId']}", flush=True)
                        self._write_json(201, {"ok": True, "media": item})
                    except (ValueError, OSError) as exc:
                        self._write_json(400, {"ok": False, "error": str(exc)[:500]})
                    return

                if self.path.startswith("/api/media/local/"):
                    local_id = self.path.split("/")[4]
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                        if length > 200000:
                            self._write_json(413, {"error": "Upload plan too large"})
                            return
                        payload = json.loads(self.rfile.read(length) or b"{}")
                        if self.path.endswith("/discard"):
                            owner.media_library.discard(local_id)
                            self._write_json(200, {"ok": True})
                        elif self.path.endswith("/transfer"):
                            result = owner.media_library.transfer(local_id, payload)
                            self._write_json(200, {"ok": True, **result})
                        else:
                            self._write_json(404, {"error": "Unknown media action"})
                    except KeyError:
                        self._write_json(404, {"error": "Media not found"})
                    except (ValueError, httpx.HTTPError) as exc:
                        self._write_json(400, {"error": str(exc)[:500]})
                    return

                if self.path.startswith("/api/weather/preflight-check"):
                    self._write_json(200, build_weather_preflight_status())
                    return
                if self.path.startswith("/api/preflight/check"):
                    persisted_check_id = None
                    if owner.preflight_persistence is not None:
                        persisted_check_id = owner.preflight_persistence.start()

                    owner.preflight_check_id = persisted_check_id or f"PF-{int(time.time() * 1000)}"
                    owner.preflight_started_at_s = time.monotonic()
                    payload = {
                        "checkId": owner.preflight_check_id,
                        "status": "CHECKING",
                    }
                    self._write_json(202, payload)
                    return

                if not self.path.startswith("/api/control/command"):
                    self.send_response(404)
                    self._cors()
                    self.end_headers()
                    return

                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
                    payload = json.loads(body or "{}")
                except (ValueError, json.JSONDecodeError):
                    payload = {}

                command = str(payload.get("command", "")).strip().lower()
                if command == "weather_set":
                    requested_preset = str(payload.get("preset", "")).strip().upper()
                    requested_key = str(payload.get("weatherKey", "")).strip().lower()
                    weather_key = requested_key or next(
                        (
                            key
                            for key, preset_name in WEATHER_KEY_PRESETS.items()
                            if preset_name == requested_preset
                        ),
                        "",
                    )
                    if weather_key not in WEATHER_KEY_PRESETS:
                        self._write_json(400, {"ok": False, "error": "unknown weather preset"})
                        return
                    ok = apply_weather_key(weather_key)
                    status = build_weather_preflight_status()
                    self._write_json(
                        202,
                        {
                            "ok": ok,
                            "command": command,
                            "weather": status,
                            "message": "Weather preset stored"
                            if ok
                            else "Weather preset stored, but Gazebo visual update failed",
                        },
                    )
                    return

                if command in {"photo", "video_toggle"} and (
                    owner.media_library is None or not owner.media_library.mission_id
                ):
                    self._write_json(409, {"ok": False, "error": "Bind an assigned mission before capturing media"})
                    return
                if command == "auto_plan_start":
                    waypoints = normalize_auto_plan_waypoints(payload)
                    if len(waypoints) < 1:
                        self._write_json(400, {"ok": False, "error": "missing waypoints"})
                        return
                    owner.commands.put({"type": "auto_plan_start", "waypoints": waypoints})
                    self._write_json(202, {"ok": True, "command": command, "waypoints": len(waypoints)})
                    return
                if command == "gps_target_start":
                    status = owner.status_provider() if owner.status_provider is not None else {}
                    try:
                        target = normalize_gps_target(
                            payload,
                            status.get("positionGps"),
                            bool(status.get("inAir")),
                        )
                    except ValueError as exc:
                        self._write_json(400, {"ok": False, "error": str(exc)})
                        return
                    owner.commands.put({"type": "gps_target_start", "target": target})
                    self._write_json(
                        202,
                        {
                            "ok": True,
                            "command": command,
                            "distanceM": round(target["distanceM"], 1),
                        },
                    )
                    return

                key = CONTROL_COMMAND_KEYS.get(command)
                if key is None:
                    self._write_json(400, {"ok": False, "error": "unknown command"})
                    return

                owner.commands.put({"type": "key", "key": key})
                self._write_json(202, {"ok": True, "command": command})

        try:
            self.server = ThreadingHTTPServer((FLIGHT_CONTROL_API_BIND, FLIGHT_CONTROL_API_PORT), Handler)
        except OSError as exc:
            print(f"[API] Flight control API unavailable: {exc}", flush=True)
            return

        self.thread = threading.Thread(target=self.server.serve_forever, name="flight-control-api", daemon=True)
        self.thread.start()
        print(
            f"[API] Live stream: http://localhost:{FLIGHT_CONTROL_API_PORT}/stream.mjpg",
            flush=True,
        )
        print(
            f"[API] Thermal stream: http://localhost:{FLIGHT_CONTROL_API_PORT}/thermal-stream.mjpg",
            flush=True,
        )
        print(
            f"[API] Controls: POST http://localhost:{FLIGHT_CONTROL_API_PORT}/api/control/command",
            flush=True,
        )

    def stop(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()


def is_grpc_unavailable(exc: Exception) -> bool:
    if not isinstance(exc, grpc.aio.AioRpcError):
        return False

    if exc.code() == grpc.StatusCode.UNAVAILABLE:
        return True

    details = (exc.details() or "").lower()
    return any(
        message in details
        for message in (
            "stream removed",
            "socket closed",
            "connection reset",
            "connection refused",
        )
    )


async def wait_for_grpc_port(timeout_seconds: float = 12.0) -> bool:
    deadline = asyncio.get_running_loop().time() + timeout_seconds
    while asyncio.get_running_loop().time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", MAVSDK_CONTROL_GRPC_PORT), timeout=0.5):
                return True
        except OSError:
            await asyncio.sleep(0.5)
    return False


class MavsdkConnectionManager:
    def __init__(self) -> None:
        self.drone: System | None = None
        self.lock = asyncio.Lock()
        self.generation = 0
        self.last_reconnect_attempt_s = 0.0
        self.last_unavailable_log_s = 0.0
        self.px4_connected = False
        self.grpc_connected = False
        self.last_px4_connected_s = 0.0
        self.last_connection_update_s = 0.0
        self.last_health_log_s = 0.0
        self.px4_disconnect_count = 0
        self._connection_monitor_task: asyncio.Task | None = None
        self._degraded_since_s: float | None = None
        self._degraded_logged = False
        self._offboard_sender_task: asyncio.Task | None = None
        self._desired_velocity = VelocityNedYaw(0.0, 0.0, 0.0, 0.0)
        self._desired_velocity_dirty = False
        self._setpoint_intervals: list[float] = []
        self._last_setpoint_sent_s: float | None = None
        self._last_setpoint_stats_s = 0.0

    def _new_drone(self) -> System:
        return System(
            mavsdk_server_address="localhost",
            port=MAVSDK_CONTROL_GRPC_PORT,
            sysid=MAVSDK_CONTROL_SYSID,
            compid=MAVSDK_CONTROL_COMPID,
        )

    async def connect(self) -> System:
        async with self.lock:
            drone = self._new_drone()
            px4_ready = await connect_px4(drone)
            self.drone = drone
            self.generation += 1
            now_s = time.monotonic()
            self.grpc_connected = True
            self.px4_connected = px4_ready
            if px4_ready:
                self.last_px4_connected_s = now_s
            self.last_connection_update_s = now_s
            self._start_connection_monitor(drone, self.generation)
            return drone

    async def reconnect(self) -> System | None:
        async with self.lock:
            now = asyncio.get_running_loop().time()
            wait_s = MAVSDK_CLIENT_RECONNECT_COOLDOWN_S - (now - self.last_reconnect_attempt_s)
            if wait_s > 0:
                await asyncio.sleep(wait_s)

            self.last_reconnect_attempt_s = asyncio.get_running_loop().time()

            if self.last_reconnect_attempt_s - self.last_unavailable_log_s >= 3.0:
                print("[MAVSDK-CLIENT] gRPC connection lost")
                print("[MAVSDK-CLIENT] Waiting for server...")
                self.last_unavailable_log_s = self.last_reconnect_attempt_s

            if not await wait_for_grpc_port(20.0):
                if asyncio.get_running_loop().time() - self.last_unavailable_log_s >= 3.0:
                    print("[ERR] MAVSDK control bridge unavailable")
                    self.last_unavailable_log_s = asyncio.get_running_loop().time()
                return None

            print("[MAVSDK-CLIENT] Creating new System connection")
            drone = self._new_drone()

            try:
                await drone.connect()
                connected_since = 0.0
                async with asyncio.timeout(20):
                    async for state in drone.core.connection_state():
                        now_s = asyncio.get_running_loop().time()
                        if state.is_connected:
                            if connected_since <= 0.0:
                                connected_since = now_s
                            if now_s - connected_since >= MAVSDK_RECONNECT_CONFIRM_S:
                                self.drone = drone
                                self.generation += 1
                                self.grpc_connected = True
                                self.px4_connected = True
                                self.last_px4_connected_s = now_s
                                self.last_connection_update_s = now_s
                                await configure_px4_speed_limits(drone)
                                self._start_connection_monitor(drone, self.generation)
                                print(
                                    f"[MAVSDK-CONN] Recreating System generation={self.generation}",
                                    flush=True,
                                )
                                print("[MAVSDK-CLIENT] PX4 reconnected")
                                return drone
                        else:
                            connected_since = 0.0
            except (asyncio.TimeoutError, grpc.aio.AioRpcError) as exc:
                print_mavsdk_unavailable("MAVSDK client reconnect", exc)

            print("[ERR] MAVSDK control bridge unavailable")
            return None

    async def get_drone(self) -> System | None:
        return self.drone

    def update_desired_motion(
            self,
            north_m_s: float,
            east_m_s: float,
            down_m_s: float,
            yaw_deg: float,
    ) -> None:
        self._desired_velocity = VelocityNedYaw(
            north_m_s,
            east_m_s,
            down_m_s,
            yaw_deg,
        )
        self._desired_velocity_dirty = True

    def stop_desired_motion(self, yaw_deg: float = 0.0) -> None:
        self.update_desired_motion(0.0, 0.0, 0.0, yaw_deg)

    def ensure_offboard_sender(self) -> None:
        if self._offboard_sender_task is not None and not self._offboard_sender_task.done():
            return
        self._offboard_sender_task = asyncio.create_task(
            self._offboard_sender_loop(),
            name="offboard-setpoint-sender",
        )

    async def activate_desired_motion(self, drone: System) -> None:
        await ensure_offboard_started(drone)
        await drone.offboard.set_velocity_ned(self._desired_velocity)
        self.ensure_offboard_sender()

    async def stop_offboard_sender(self) -> None:
        self.stop_desired_motion()
        task = self._offboard_sender_task
        self._offboard_sender_task = None
        if task is not None and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            print("[OFFBOARD] Sender stopped", flush=True)

    async def stop_connection_monitor(self) -> None:
        task = self._connection_monitor_task
        self._connection_monitor_task = None
        if task is not None and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def _offboard_sender_loop(self) -> None:
        interval_s = 1.0 / max(OFFBOARD_SETPOINT_RATE_HZ, 1.0)
        offboard_generation = -1
        print(
            f"[OFFBOARD] Sender started target={OFFBOARD_SETPOINT_RATE_HZ:.1f}Hz",
            flush=True,
        )
        while True:
            await asyncio.sleep(interval_s)
            drone = self.drone
            if drone is None:
                continue

            try:
                if offboard_generation != self.generation:
                    await ensure_offboard_started(drone)
                    offboard_generation = self.generation
                    self._offboard_generation = self.generation
                    print(
                        f"[OFFBOARD] Started generation={self.generation}",
                        flush=True,
                    )

                now_s = time.monotonic()
                if self._last_setpoint_sent_s is not None:
                    gap_s = now_s - self._last_setpoint_sent_s
                    self._setpoint_intervals.append(gap_s)
                    if len(self._setpoint_intervals) > 300:
                        self._setpoint_intervals = self._setpoint_intervals[-300:]
                self._last_setpoint_sent_s = now_s

                await drone.offboard.set_velocity_ned(self._desired_velocity)

                if (
                    OFFBOARD_SETPOINT_STATS_ENABLED
                    and now_s - self._last_setpoint_stats_s >= 5.0
                    and self._setpoint_intervals
                ):
                    intervals = self._setpoint_intervals
                    avg_s = sum(intervals) / len(intervals)
                    hz = 1.0 / avg_s if avg_s > 0 else 0.0
                    print(
                        f"[OFFBOARD] setpoint stats count={len(intervals)} "
                        f"avg_hz={hz:.1f} max_gap={max(intervals):.3f}s",
                        flush=True,
                    )
                    self._last_setpoint_stats_s = now_s
            except asyncio.CancelledError:
                raise
            except (OffboardError, grpc.aio.AioRpcError) as exc:
                print_command_denied("offboard setpoint", exc)
                await asyncio.sleep(0.5)

    def _start_connection_monitor(self, drone: System, generation: int) -> None:
        if self._connection_monitor_task is not None:
            self._connection_monitor_task.cancel()
        self._connection_monitor_task = asyncio.create_task(
            self._monitor_connection_state(drone, generation),
            name=f"mavsdk-connection-{generation}",
        )

    async def _monitor_connection_state(self, drone: System, generation: int) -> None:
        print(f"[MAVSDK-CONN] monitor started generation={generation}", flush=True)
        try:
            async for state in drone.core.connection_state():
                if generation != self.generation:
                    return

                now_s = asyncio.get_running_loop().time()
                self.grpc_connected = True
                self.last_connection_update_s = now_s

                if state.is_connected:
                    if not self.px4_connected and self._degraded_since_s is not None:
                        print(
                            f"[MAVSDK-CONN] Recovered after {now_s - self._degraded_since_s:.1f}s",
                            flush=True,
                        )
                    self.px4_connected = True
                    self.last_px4_connected_s = now_s
                    self._degraded_since_s = None
                    self._degraded_logged = False
                else:
                    if self.px4_connected:
                        self.px4_disconnect_count += 1
                        self._degraded_since_s = now_s
                        self._degraded_logged = False
                    self.px4_connected = False
                    if not self._degraded_logged:
                        print(
                            "[MAVSDK-CONN] DEGRADED - PX4 heartbeat temporarily missing",
                            flush=True,
                        )
                        self._degraded_logged = True

                if now_s - self.last_health_log_s >= MAVSDK_HEALTH_LOG_INTERVAL_S:
                    age = now_s - self.last_px4_connected_s if self.last_px4_connected_s else -1.0
                    print(
                        f"[MAVSDK-HEALTH] gen={self.generation} "
                        f"grpc={'OK' if self.grpc_connected else 'LOST'} "
                        f"px4={'OK' if self.px4_connected else 'DEGRADED'} "
                        f"last_px4_seen_age={age:.1f}s "
                        f"px4_disconnects={self.px4_disconnect_count}",
                        flush=True,
                    )
                    self.last_health_log_s = now_s
        except asyncio.CancelledError:
            raise
        except grpc.aio.AioRpcError as exc:
            if generation == self.generation:
                self.grpc_connected = False
                print(
                    f"[MAVSDK-CONN] connection monitor ended: {exc.code().name}",
                    flush=True,
                )
        except Exception as exc:
            if generation == self.generation:
                self.grpc_connected = False
                print(f"[MAVSDK-CONN] connection monitor ended: {exc}", flush=True)

    def connection_age_s(self) -> float | None:
        if not self.last_px4_connected_s:
            return None
        return time.monotonic() - self.last_px4_connected_s

    def recently_connected(self) -> bool:
        age = self.connection_age_s()
        return age is not None and age <= MAVSDK_DISCONNECT_GRACE_S

    async def wait_until_ready(self, timeout_s: float) -> bool:
        deadline = asyncio.get_running_loop().time() + timeout_s
        while asyncio.get_running_loop().time() < deadline:
            if self.px4_connected or self.recently_connected():
                return True
            await asyncio.sleep(0.1)
        return self.px4_connected or self.recently_connected()


async def ensure_offboard_started(drone: System) -> None:
    await drone.offboard.set_velocity_ned(VelocityNedYaw(0.0, 0.0, 0.0, 0.0))
    try:
        await drone.offboard.start()
    except OffboardError as exc:
        if exc._result.result_str != "BUSY":
            raise


def is_climb_only_command(
        north_m_s: float,
        east_m_s: float,
        down_m_s: float,
) -> bool:
    return (
            math.hypot(north_m_s, east_m_s) <= 1e-6
            and down_m_s < -1e-6
    )


async def check_readiness(manager: MavsdkConnectionManager) -> bool:
    if manager.px4_connected:
        return True

    age = manager.connection_age_s()
    if manager.recently_connected():
        print(
            f"[MAVSDK-CONN] DEGRADED - using recent PX4 state age={age:.1f}s",
            flush=True,
        )
        return True

    print("[MAVSDK-CONN] Waiting briefly for PX4 recovery...", flush=True)
    if await manager.wait_until_ready(MAVSDK_COMMAND_RECOVERY_WAIT_S):
        return True

    age_text = "--" if age is None else f"{age:.1f}s"
    print(
        f"[MAVSDK-CONN] PX4 disconnected >{MAVSDK_DISCONNECT_GRACE_S:.1f}s "
        f"(last_seen={age_text})",
        flush=True,
    )
    return False


async def safe_arm(manager: MavsdkConnectionManager) -> System | None:
    """Arm with retry and clear error reporting."""
    drone = await manager.get_drone()
    if drone is None:
        drone = await manager.reconnect()
        if drone is None:
            print("[ERR] Arm cancelled")
            return None

    reconnect_attempts = 0

    for attempt in range(3):
        try:
            if not await check_readiness(manager):
                refreshed = await manager.reconnect()
                if refreshed is None:
                    print("[ERR] MAVSDK control bridge unavailable")
                    print("[ERR] Arm cancelled")
                    return None
                drone = refreshed

            print(f"[CMD] Arming... (attempt {attempt+1}/3)")
            await drone.action.arm()
            print("[CMD] Armed successfully!")
            return drone
        except ActionError as e:
            print(f"[WARN] Arm failed: {e}")
            if attempt < 2:
                print("[HINT] Retrying in 2s...")
                await asyncio.sleep(2)
        except grpc.aio.AioRpcError as exc:
            print_mavsdk_unavailable("arm", exc)
            if is_grpc_unavailable(exc) and reconnect_attempts < 2:
                reconnect_attempts += 1
                refreshed = await manager.reconnect()
                if refreshed is None:
                    print("[ERR] Arm cancelled")
                    return None
                drone = refreshed
                print("[HINT] Retrying arm with new MAVSDK connection...")
                continue
            print("[ERR] MAVSDK control bridge unavailable")
            print("[ERR] Arm cancelled")
            return None

    print("[ERR] Could not arm after 3 attempts.")
    print("[ERR] Ensure PX4 terminal shows: 'Ready for takeoff!'")
    print("[ERR] Check: sensors OK, no safety switch active.")
    return None


async def wait_for_takeoff_confirm(drone: System) -> bool:
    print("[CMD] Takeoff command accepted - waiting for climb confirmation...", flush=True)
    deadline = asyncio.get_running_loop().time() + TAKEOFF_CONFIRM_TIMEOUT_S
    in_air_seen = False
    altitude_seen = False

    async def watch_in_air() -> None:
        nonlocal in_air_seen
        async for in_air in drone.telemetry.in_air():
            if in_air:
                in_air_seen = True
                return

    async def watch_altitude() -> None:
        nonlocal altitude_seen
        async for position in drone.telemetry.position():
            altitude = float(getattr(position, "relative_altitude_m", 0.0) or 0.0)
            if altitude >= TAKEOFF_CONFIRM_ALTITUDE_M:
                altitude_seen = True
                return

    tasks = [
        asyncio.create_task(watch_in_air(), name="takeoff-in-air"),
        asyncio.create_task(watch_altitude(), name="takeoff-altitude"),
    ]
    try:
        while asyncio.get_running_loop().time() < deadline:
            if in_air_seen and altitude_seen:
                print("[CMD] Takeoff confirmed. Use wasdqefv to start offboard flight, k to hover.", flush=True)
                return True
            if in_air_seen:
                print("[CMD] Climbing...", flush=True)
                break
            await asyncio.sleep(0.2)

        remaining = max(0.0, deadline - asyncio.get_running_loop().time())
        if remaining > 0:
            await asyncio.wait(tasks, timeout=remaining, return_when=asyncio.ALL_COMPLETED)

        if in_air_seen and altitude_seen:
            print("[CMD] Takeoff confirmed. Use wasdqefv to start offboard flight, k to hover.", flush=True)
            return True

        print("[WARN] Takeoff timeout - command may still be in progress", flush=True)
        return False
    except (grpc.aio.AioRpcError, asyncio.TimeoutError) as exc:
        print_mavsdk_unavailable("takeoff confirmation", exc)
        return False
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def set_motion(
        manager: MavsdkConnectionManager,
        north_m_s: float,
        east_m_s: float,
        down_m_s: float,
        yaw_deg: float,
) -> System | None:
    drone = await manager.get_drone()

    if drone is None:
        drone = await manager.reconnect()
        if drone is None:
            return None
    elif not await check_readiness(manager):
        drone = await manager.reconnect()
        if drone is None:
            return None

    try:
        manager.update_desired_motion(north_m_s, east_m_s, down_m_s, yaw_deg)
        # Start offboard and deliver the first setpoint before reporting the
        # command as accepted. Previously this only scheduled a background
        # task, so the UI could say "forward sent" while PX4 never entered
        # offboard mode.
        await manager.activate_desired_motion(drone)
        return drone

    except grpc.aio.AioRpcError as exc:
        if not is_grpc_unavailable(exc):
            raise

        print_mavsdk_unavailable("movement", exc)

        if await manager.wait_until_ready(MAVSDK_COMMAND_RECOVERY_WAIT_S):
            drone = await manager.get_drone()
            if drone is None:
                return None
        else:
            drone = await manager.reconnect()

        if drone is None:
            return None

        try:
            manager.update_desired_motion(north_m_s, east_m_s, down_m_s, yaw_deg)
            await manager.activate_desired_motion(drone)

            return drone

        except grpc.aio.AioRpcError as retry_exc:
            if not is_grpc_unavailable(retry_exc):
                raise

            print(
                "[WARN] movement retry skipped: MAVSDK still unavailable",
                flush=True,
            )
            return None
async def track_local_position(
        manager: MavsdkConnectionManager,
        update_position,
        update_velocity=None,
) -> None:
    active_generation = -1

    while True:
        drone = await manager.get_drone()

        if drone is None:
            await asyncio.sleep(0.5)
            continue

        generation = manager.generation

        # MAVSDK System đã đổi sau reconnect -> bỏ stream cũ.
        if generation != active_generation:
            active_generation = generation
            print(
                f"[POSITION] Telemetry generation={generation}",
                flush=True,
            )

        try:
            stream = drone.telemetry.position_velocity_ned()

            while generation == manager.generation:
                try:
                    sample = await asyncio.wait_for(
                        anext(stream),
                        timeout=2.0,
                    )
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    # Stream cũ/stale -> mở lại stream từ System hiện tại.
                    break

                position = sample.position
                velocity = getattr(sample, "velocity", None)

                update_position(
                    float(position.north_m),
                    float(position.east_m),
                    float(position.down_m),
                )
                if update_velocity is not None and velocity is not None:
                    update_velocity(
                        float(velocity.north_m_s),
                        float(velocity.east_m_s),
                        float(velocity.down_m_s),
                    )

        except asyncio.CancelledError:
            raise

        except (AttributeError, grpc.aio.AioRpcError):
            await asyncio.sleep(0.5)


async def track_global_position(manager: MavsdkConnectionManager, update_position) -> None:
    while True:
        drone = await manager.get_drone()
        if drone is None:
            await asyncio.sleep(0.5)
            continue
        generation = manager.generation
        try:
            stream = drone.telemetry.position()
            while generation == manager.generation:
                try:
                    position = await asyncio.wait_for(anext(stream), timeout=2.0)
                except (StopAsyncIteration, asyncio.TimeoutError):
                    break
                update_position(
                    float(position.latitude_deg),
                    float(position.longitude_deg),
                    float(position.absolute_altitude_m),
                    float(position.relative_altitude_m),
                )
        except asyncio.CancelledError:
            raise
        except (AttributeError, grpc.aio.AioRpcError):
            await asyncio.sleep(0.5)


def normalize_battery_percent(value: float | None) -> float | None:
    if value is None or not math.isfinite(value):
        return None
    percent = value * 100.0 if 0.0 <= value <= 1.0 else value
    return max(0.0, min(100.0, percent))


async def track_battery(
        manager: MavsdkConnectionManager,
        update_battery,
) -> None:
    active_generation = -1

    while True:
        drone = await manager.get_drone()

        if drone is None:
            await asyncio.sleep(0.5)
            continue

        generation = manager.generation

        if generation != active_generation:
            active_generation = generation
            print(
                f"[BATTERY] Telemetry generation={generation}",
                flush=True,
            )

        try:
            stream = drone.telemetry.battery()

            while generation == manager.generation:
                try:
                    battery = await asyncio.wait_for(
                        anext(stream),
                        timeout=3.0,
                    )
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    break

                update_battery(
                    normalize_battery_percent(
                        float(battery.remaining_percent)
                        if battery.remaining_percent is not None
                        else None
                    )
                )

        except asyncio.CancelledError:
            raise


async def track_in_air(
        manager: MavsdkConnectionManager,
        update_in_air,
) -> None:
    active_generation = -1

    while True:
        drone = await manager.get_drone()

        if drone is None:
            update_in_air(False)
            await asyncio.sleep(0.5)
            continue

        generation = manager.generation

        if generation != active_generation:
            active_generation = generation
            print(f"[AIR] Telemetry generation={generation}", flush=True)

        try:
            stream = drone.telemetry.in_air()

            while generation == manager.generation:
                try:
                    in_air = await asyncio.wait_for(
                        anext(stream),
                        timeout=3.0,
                    )
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    break

                update_in_air(bool(in_air))

        except asyncio.CancelledError:
            raise
        except (AttributeError, grpc.aio.AioRpcError):
            update_in_air(False)
            await asyncio.sleep(0.5)


async def track_health(
        manager: MavsdkConnectionManager,
        update_health,
) -> None:
    active_generation = -1

    while True:
        drone = await manager.get_drone()

        if drone is None:
            await asyncio.sleep(0.5)
            continue

        generation = manager.generation

        if generation != active_generation:
            active_generation = generation
            print(f"[HEALTH] Telemetry generation={generation}", flush=True)

        try:
            stream = drone.telemetry.health()

            while generation == manager.generation:
                try:
                    health = await asyncio.wait_for(
                        anext(stream),
                        timeout=3.0,
                    )
                except StopAsyncIteration:
                    break
                except asyncio.TimeoutError:
                    break

                update_health(health)

        except asyncio.CancelledError:
            raise
        except (AttributeError, grpc.aio.AioRpcError):
            await asyncio.sleep(0.5)

        except (AttributeError, grpc.aio.AioRpcError):
            await asyncio.sleep(0.5)


async def event_loop_watchdog() -> None:
    expected_interval_s = 0.5
    warn_after_s = 1.5
    loop = asyncio.get_running_loop()
    next_wake_s = loop.time() + expected_interval_s

    while True:
        await asyncio.sleep(expected_interval_s)
        now_s = loop.time()
        lag_s = now_s - next_wake_s
        if lag_s > warn_after_s:
            print(f"[PERF] asyncio event-loop lag={lag_s:.1f}s", flush=True)
        next_wake_s = now_s + expected_interval_s
def body_velocity(
        forward: float,
        right: float,
        yaw_deg: float,
) -> tuple[float, float]:
    yaw_rad = math.radians(yaw_deg)

    north = (
            forward * math.cos(yaw_rad)
            - right * math.sin(yaw_rad)
    )

    east = (
            forward * math.sin(yaw_rad)
            + right * math.cos(yaw_rad)
    )

    return north, east


def is_forward_blocked(state) -> bool:
    return state.front <= OBSTACLE_DISTANCE_M


def is_front_obstacle_direction(direction: str) -> bool:
    return direction == "FRONT"


def should_handle_front_obstacle(state, saved_motion: SavedMotion | None) -> bool:
    return (
        saved_motion is not None
        and saved_motion.forward_m_s > 1e-6
        and is_forward_blocked(state)
    )


def warning_speed_scale(front_distance_m: float) -> float:
    if front_distance_m <= OBSTACLE_DISTANCE_M:
        return 0.0

    warning_span = max(WARNING_DISTANCE_M - OBSTACLE_DISTANCE_M, 0.1)
    ratio = (front_distance_m - OBSTACLE_DISTANCE_M) / warning_span
    ratio = max(0.0, min(1.0, ratio))

    cruise_speed = max(MISSION_CRUISE_SPEED_M_S, 0.1)
    min_scale = max(0.0, min(1.0, MISSION_WARNING_MIN_SPEED_M_S / cruise_speed))
    return min_scale + ratio * (1.0 - min_scale)


def scale_horizontal_motion(saved_motion: SavedMotion, scale: float) -> SavedMotion:
    return SavedMotion(
        forward_m_s=saved_motion.forward_m_s * scale,
        right_m_s=saved_motion.right_m_s * scale,
        down_m_s=saved_motion.down_m_s,
        north_m_s=saved_motion.north_m_s * scale,
        east_m_s=saved_motion.east_m_s * scale,
        yaw_deg=saved_motion.yaw_deg,
    )


async def obstacle_safety_loop(
        avoidance,
        lidar,
        manager: MavsdkConnectionManager,
        get_yaw,
        get_saved_motion,
        is_safety_sensor_enabled,
        get_motion_owner,
        set_motion_owner,
        stop_manual_motion,
        set_safety_speed_scale,
        is_launch_pad_clear,
):
    last_status = "CLEAR"
    unavailable_reported = False
    ready_reported = False
    mavsdk_unavailable_reported = False
    last_warning_log_s = 0.0

    while True:
        if not is_safety_sensor_enabled():
            last_status = "CLEAR"
            set_safety_speed_scale(1.0)
            await asyncio.sleep(SAFETY_POLL_INTERVAL_S)
            continue

        if not is_launch_pad_clear():
            last_status = "CLEAR"
            set_safety_speed_scale(1.0)
            await asyncio.sleep(SAFETY_POLL_INTERVAL_S)
            continue

        state, status, direction = lidar.snapshot()

        if state is None:
            if not getattr(lidar, "available", False) and not unavailable_reported:
                print("[SAFETY] 2D LiDAR unavailable")
                unavailable_reported = True

            await asyncio.sleep(1.0)
            continue

        if not ready_reported:
            print("[SAFETY] 2D LiDAR emergency guard ready")
            ready_reported = True

        saved_motion = get_saved_motion()
        front_blocked = should_handle_front_obstacle(state, saved_motion)

        if front_blocked and get_motion_owner() != MotionOwner.EMERGENCY:
            set_safety_speed_scale(0.0)
            set_motion_owner(MotionOwner.EMERGENCY)
            avoidance.set_yaw(get_yaw())
            stop_manual_motion(clear_saved=False)

            try:
                new_drone = await set_motion(manager, 0.0, 0.0, 0.0, get_yaw())
                if new_drone is not None:
                    avoidance.set_drone(new_drone)
                    await avoidance.hover(log=True)

                if mavsdk_unavailable_reported:
                    print("[SAFETY] MAVSDK control restored")
                    mavsdk_unavailable_reported = False

                print(
                    f"[SAFETY] OBSTACLE_STOP status={status} direction={direction} "
                    f"front={state.front:.2f}m -> HOVER",
                    flush=True,
                )

            except grpc.aio.AioRpcError as exc:
                if is_grpc_unavailable(exc):
                    if not mavsdk_unavailable_reported:
                        print("[SAFETY] MAVSDK unavailable - cannot send hover command")
                        mavsdk_unavailable_reported = True

                    new_drone = await manager.reconnect()
                    if new_drone is not None:
                        avoidance.set_drone(new_drone)
                else:
                    print(f"[SAFETY] Hover failed: {exc}")
            except Exception as exc:
                print(f"[SAFETY] Hover failed: {exc}")

        elif status == "WARNING" and direction == "FRONT":
            scale = warning_speed_scale(state.front)
            set_safety_speed_scale(scale)
            saved_motion = get_saved_motion()

            if saved_motion is not None and get_motion_owner() == MotionOwner.MANUAL:
                slowed = scale_horizontal_motion(saved_motion, scale)
                try:
                    new_drone = await set_motion(
                        manager,
                        slowed.north_m_s,
                        slowed.east_m_s,
                        slowed.down_m_s,
                        get_yaw(),
                    )
                    if new_drone is not None:
                        avoidance.set_drone(new_drone)
                except grpc.aio.AioRpcError as exc:
                    if is_grpc_unavailable(exc):
                        if not mavsdk_unavailable_reported:
                            print("[SAFETY] MAVSDK unavailable - cannot slow down")
                            mavsdk_unavailable_reported = True
                        await manager.reconnect()
                    else:
                        print(f"[SAFETY] Slowdown failed: {exc}")
                except Exception as exc:
                    print(f"[SAFETY] Slowdown failed: {exc}")

            now_s = asyncio.get_running_loop().time()
            if now_s - last_warning_log_s >= 1.0:
                print(
                    f"[SAFETY] WARNING front={state.front:.2f}m "
                    f"-> smooth slowdown scale={scale:.2f}",
                    flush=True,
                )
                last_warning_log_s = now_s

        if status == "CLEAR":
            set_safety_speed_scale(1.0)
            if get_motion_owner() == MotionOwner.EMERGENCY:
                set_motion_owner(MotionOwner.MANUAL)
            if last_status != "CLEAR":
                print("[SAFETY] Path clear", flush=True)

        last_status = status
        await asyncio.sleep(SAFETY_POLL_INTERVAL_S)


async def main() -> None:
    print("========================================")
    print(" On-Demand Monitoring Flight Controller")
    print("========================================")
    print(f"PX4 control: {PX4_CONTROL_SYSTEM_ADDRESS}")
    print(f"MAVSDK gRPC: localhost:{MAVSDK_CONTROL_GRPC_PORT}")
    if FAST_DEMO_MODE:
        print("[FAST] Demo mode: light world, sensors/media probes disabled, GPS route speed boosted")
    print()
    print("Keys: t takeoff | w forward | s back | a left | d right")
    print("      f up | v down | q yaw left | e yaw right | k stop")
    print("      o toggle safety sensor")
    print("      1 speed up | 2 speed down")
    print("      c switch camera down/front")
    print("      3 camera monitor on/off | 4 LiDAR monitor on/off | 5 telemetry on/off")
    print("      6 thermal camera on/off")
    print("      weather: u clear | y sunset | i night | g cloudy | j foggy | m windy | b light rain | z heavy rain")
    print("      p photo | r video start/stop | l land | x exit")
    print()
    print("Press one move key once to keep moving. Press k to stop/hover.")
    print()

    connection_manager = MavsdkConnectionManager()
    drone = await connection_manager.connect()
    backend_urls = BackendUrlResolver(BACKEND_BASE_URL)
    active_mission_id = None
    active_mission_code = None
    active_device_id = None
    media_root = Path(os.getenv("LOCAL_MEDIA_DIR", "/tmp/forest3d_drone_media"))
    media_library = LocalMediaLibrary(
        media_root, active_mission_id, active_device_id)

    video_recorder = VideoRecorder(
        VIDEO_RECORDING_DIR,
        fps=VIDEO_RECORDING_FPS,
        queue_size=VIDEO_RECORDING_QUEUE_SIZE,
    )
    camera = CameraGateway(video_recorder, media_library)
    camera.start()
    thermal = ThermalCameraGateway(backend_urls.candidates())
    thermal_node = None
    thermal_subscribed_topics: set[str] = set()
    if Node is not None and GzImage is not None:
        thermal_node = Node()

        def on_native_thermal_frame(message: GzImage, *_args) -> None:
            try:
                pixel_format = PixelFormatType.Name(message.pixel_format_type) if PixelFormatType is not None else str(message.pixel_format_type)
                thermal.ingest_native_frame(
                    int(message.width),
                    int(message.height),
                    bytes(message.data),
                    pixel_format,
                )
            except (TypeError, ValueError) as exc:
                print(f"[THERMAL] Native frame rejected: {exc}", flush=True)

        thermal_topics = tuple(dict.fromkeys((GAZEBO_THERMAL_CAMERA_TOPIC, "/thermal_camera")))

        def set_native_thermal_subscription(enabled: bool) -> None:
            if enabled:
                for thermal_topic in thermal_topics:
                    if thermal_topic in thermal_subscribed_topics:
                        continue
                    thermal_node.subscribe(GzImage, thermal_topic, on_native_thermal_frame)
                    thermal_subscribed_topics.add(thermal_topic)
                    print(f"[THERMAL] Sensor subscribed: {thermal_topic}", flush=True)
                return

            for thermal_topic in tuple(thermal_subscribed_topics):
                thermal_node.unsubscribe(thermal_topic)
                thermal_subscribed_topics.discard(thermal_topic)
                print(f"[THERMAL] Sensor released: {thermal_topic}", flush=True)

        print("[THERMAL] Sensor idle; press Thermal ON to subscribe", flush=True)
    else:
        def set_native_thermal_subscription(_enabled: bool) -> None:
            return

        print("[THERMAL] Gazebo Transport unavailable; synthetic fallback enabled", flush=True)
    camera_orientation = CameraOrientationController(camera.set_view_mode)
    camera_orientation.set_mode(camera_orientation.current_mode)
    api_commands: queue.Queue[str] = queue.Queue()

    current_yaw_deg = 0.0
    media_tasks: set[asyncio.Task] = set()
    gps_target_task: asyncio.Task | None = None

    def set_current_yaw(yaw_deg: float) -> None:
        nonlocal current_yaw_deg
        current_yaw_deg = yaw_deg % 360.0

    # ================================================================
    # CURRENT / PREVIOUS MANUAL FLIGHT COMMAND
    # ================================================================

    current_forward_m_s = 0.0
    current_right_m_s = 0.0
    current_north_m_s = 0.0
    current_east_m_s = 0.0
    current_down_m_s = 0.0
    control_speed_m_s = MOVE_SPEED_M_S
    control_vertical_speed_m_s = VERTICAL_SPEED_M_S

    safety_task = None
    position_task = None
    global_position_task = None
    battery_task = None
    health_task = None
    in_air_task = None
    simulated_battery_task = None
    watchdog_task = asyncio.create_task(
        event_loop_watchdog(),
        name="event-loop-watchdog",
    )
    avoidance = None
    lidar = None
    lidar_ui_enabled = False
    safety_sensor_enabled = (
    os.getenv("SAFETY_SENSOR_ENABLED", "false").strip().lower()
        in {"1", "true", "yes", "on"}
    )

    motion_owner = MotionOwner.MANUAL
    current_local_north_m = 0.0
    current_local_east_m = 0.0
    current_local_down_m = 0.0
    current_global_position = None
    global_position_update_s = None
    current_velocity_north_m_s = 0.0
    current_velocity_east_m_s = 0.0
    current_velocity_down_m_s = 0.0
    current_px4_battery_percent = None
    current_px4_battery_update_s = None
    media_probe_check_id = None
    media_probe_status = "PENDING"
    media_probe_message = "Media upload probe has not run yet"
    media_probe_lock = threading.Lock()
    current_armed = False
    current_in_air = False
    current_health = None
    current_health_update_s: float | None = None
    local_position_update_s: float | None = None
    local_position_ready = False
    auto_plan_points: list[dict] = []
    auto_plan_index = 0
    auto_plan_active = False
    auto_plan_status = "IDLE"
    auto_plan_desired_altitude_m: float | None = None
    auto_plan_land_on_complete = False
    demo_gps_route_points: list[dict] = []
    demo_gps_route_schedule: list[float] = []
    demo_gps_started_s: float | None = None
    safety_speed_scale = 1.0
    simulated_battery = BatterySimulator(
        float(os.getenv("SIM_BATTERY_INITIAL_PERCENT", "100.0")),
        capacity_mAh=float(os.getenv("SIM_BATTERY_CAPACITY_MAH", "5000")),
    )

    def fresh(age_s: float | None, max_age_s: float) -> bool:
        return age_s is not None and math.isfinite(age_s) and age_s <= max_age_s

    def check_item(key: str, name: str, status: str, message: str, critical: bool = True) -> dict:
        return {
            "key": key,
            "name": name,
            "status": status,
            "message": message,
            "critical": critical,
        }

    def build_preflight_status(check_id: str, started_at_s: float | None) -> dict:
        nonlocal media_probe_check_id, media_probe_status, media_probe_message
        now_s = time.monotonic()
        health_age_s = now_s - current_health_update_s if current_health_update_s is not None else None
        px4_battery_age_s = now_s - current_px4_battery_update_s if current_px4_battery_update_s is not None else None
        camera_age_s = None if FAST_DEMO_MODE else camera.latest_frame_age_s("DOWN")
        lidar_age_s = lidar.latest_scan_age_s() if lidar is not None and hasattr(lidar, "latest_scan_age_s") else None
        connection_age_s = connection_manager.connection_age_s()
        px4_ready = connection_manager.px4_connected or connection_manager.recently_connected()
        grpc_ready = connection_manager.grpc_connected
        health_local_ok = bool(getattr(current_health, "is_local_position_ok", False))
        health_sensor_ok = all(
            bool(getattr(current_health, field, False))
            for field in (
                "is_accelerometer_calibration_ok",
                "is_gyrometer_calibration_ok",
            )
        ) if current_health is not None else False
        backend_ok, backend_target = backend_urls.reachable()
        with media_probe_lock:
            if media_probe_check_id != check_id:
                media_probe_check_id = check_id
                if FAST_DEMO_MODE:
                    media_probe_status = "PASS"
                    media_probe_message = "Fast demo mode: media probe disabled"
                elif backend_ok and control_api.preflight_persistence is not None:
                    media_probe_status = "CHECKING"
                    media_probe_message = "Running isolated media storage round-trip probe"
                    media_ok, media_message = control_api.preflight_persistence.verify_media_upload_cycle(
                        MEDIA_PROBE_JPEG,
                    )
                    media_probe_status = "PASS" if media_ok else "FAIL"
                    media_probe_message = media_message
                else:
                    media_probe_status = "FAIL"
                    media_probe_message = "Media probe needs a reachable backend"
            media_probe_status_snapshot = media_probe_status
            media_probe_message_snapshot = media_probe_message

        if current_px4_battery_percent is not None and fresh(px4_battery_age_s, 10.0):
            battery_ready_status, battery_ready_message = preflight_battery_check(current_px4_battery_percent)
            battery_ready_message = f"{current_px4_battery_percent:.1f}% PX4 battery telemetry - {battery_ready_message}"
        else:
            battery_ready_status = "FAIL"
            battery_ready_message = "No fresh PX4 battery telemetry received"

        checks = [
            check_item(
                "PX4",
                "PX4 Flight Controller",
                "PASS" if px4_ready else "FAIL",
                "Ready for takeoff" if px4_ready else "PX4 heartbeat not available",
                True,
            ),
            check_item(
                "MAVSDK",
                "MAVSDK Connection",
                "PASS" if grpc_ready else "FAIL",
                "PX4 discovered" if grpc_ready else "MAVSDK gRPC bridge unavailable",
                True,
            ),
            check_item(
                "PX4_CONTROL",
                "PX4 Control",
                "PASS" if px4_ready and grpc_ready else "FAIL",
                "Flight controller ready" if px4_ready and grpc_ready else "Flight controller not connected",
                True,
            ),
            check_item(
                "MAVSDK_HEALTH",
                "MAVSDK Health",
                "PASS" if fresh(health_age_s, 5.0) and health_local_ok and health_sensor_ok else "FAIL",
                "PX4 health ready" if fresh(health_age_s, 5.0) and health_local_ok and health_sensor_ok else "PX4 health not ready",
                True,
            ),
            check_item(
                "BATTERY",
                "Battery",
                battery_ready_status,
                battery_ready_message,
                True,
            ),
            check_item(
                "LIDAR",
                "LiDAR",
                "PASS" if FAST_DEMO_MODE or fresh(lidar_age_s, 5.0) else "WARN",
                "Fast demo mode: LiDAR disabled" if FAST_DEMO_MODE
                    else "Fresh scan received" if fresh(lidar_age_s, 5.0) else "No fresh scan received",
                False,
            ),
            check_item(
                "CAMERA",
                "Downward Camera",
                "PASS" if FAST_DEMO_MODE or fresh(camera_age_s, 5.0) else "WARN",
                "Fast demo mode: camera disabled" if FAST_DEMO_MODE
                    else "Camera frames received" if fresh(camera_age_s, 5.0) else "No recent downward camera frame",
                False,
            ),
            check_item(
                "BACKEND",
                "Backend Connection",
                "PASS" if backend_ok else "WARN",
                backend_target if backend_ok else f"{backend_target} not reachable",
                False,
            ),
            check_item(
                "MEDIA",
                "Media Upload",
                media_probe_status_snapshot,
                media_probe_message_snapshot,
                False,
            ),
        ]
        completed = sum(1 for item in checks if item["status"] in {"PASS", "WARN", "FAIL"})
        critical_failures = [item for item in checks if item["critical"] and item["status"] == "FAIL"]
        overall = "FAILED" if critical_failures else "READY"
        payload = {
            "checkId": check_id,
            "status": overall,
            "progress": round((completed / len(checks)) * 100),
            "startedAt": datetime.fromtimestamp(time.time() - (now_s - started_at_s), timezone.utc).isoformat().replace("+00:00", "Z") if started_at_s is not None else None,
            "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "checks": checks,
        }
        if control_api.preflight_persistence is not None:
            control_api.preflight_persistence.sync(payload)
        return payload

    def api_status() -> dict:
        nonlocal demo_gps_route_points, demo_gps_route_schedule, demo_gps_started_s
        now_s = time.monotonic()
        battery_snapshot = simulated_battery.snapshot()
        px4_battery_age_s = now_s - current_px4_battery_update_s if current_px4_battery_update_s is not None else None
        display_battery_percent = battery_snapshot.battery_percent
        display_battery_state = battery_snapshot.battery_state
        horizontal_speed = math.hypot(
            current_velocity_north_m_s,
            current_velocity_east_m_s,
        )
        connection_age_s = connection_manager.connection_age_s()
        sim_x_m, sim_y_m = px4_ned_to_sim_xy(
            current_local_north_m,
            current_local_east_m,
        )
        thermal.update_pose(sim_x_m, sim_y_m, max(0.0, -current_local_down_m))
        demo_gps_position = None
        if FAST_DEMO_MODE and demo_gps_route_points and demo_gps_route_schedule and demo_gps_started_s is not None:
            elapsed_s = max(0.0, time.monotonic() - demo_gps_started_s)
            route_index = 0
            while route_index < len(demo_gps_route_schedule) - 2 and elapsed_s >= demo_gps_route_schedule[route_index + 1]:
                route_index += 1
            if route_index >= len(demo_gps_route_points) - 1:
                demo_gps_position = demo_gps_route_points[-1]
            else:
                leg_start_s = demo_gps_route_schedule[route_index]
                leg_end_s = demo_gps_route_schedule[route_index + 1]
                leg_progress = min(1.0, max(0.0, (elapsed_s - leg_start_s) / max(0.1, leg_end_s - leg_start_s)))
                start = demo_gps_route_points[route_index]
                end = demo_gps_route_points[route_index + 1]
                demo_gps_position = {
                    "latitude": start["latitude"] + (end["latitude"] - start["latitude"]) * leg_progress,
                    "longitude": start["longitude"] + (end["longitude"] - start["longitude"]) * leg_progress,
                    "absoluteAltitudeM": start.get("absoluteAltitudeM", 20.0)
                        + (end.get("absoluteAltitudeM", 20.0) - start.get("absoluteAltitudeM", 20.0)) * leg_progress,
                    "relativeAltitudeM": start.get("relativeAltitudeM", 20.0)
                        + (end.get("relativeAltitudeM", 20.0) - start.get("relativeAltitudeM", 20.0)) * leg_progress,
                }
            if elapsed_s > demo_gps_route_schedule[-1] + 2.0:
                demo_gps_route_points = [demo_gps_route_points[-1]]
                demo_gps_route_schedule = [0.0]
                demo_gps_started_s = time.monotonic()

        live_gps_position = demo_gps_position or (
            current_global_position if global_position_update_s is not None
            and time.monotonic() - global_position_update_s < 5.0 else None
        )
        demo_active = demo_gps_position is not None and len(demo_gps_route_points) > 1
        status = {
            "runtimeSessionId": RUNTIME_SESSION_ID,
            "missionId": active_mission_id,
            "missionCode": active_mission_code,
            "deviceId": active_device_id,
            "positionReady": local_position_ready,
            "positionNed": {
                "northM": current_local_north_m,
                "eastM": current_local_east_m,
                "downM": current_local_down_m,
            },
            "positionGazebo": {
                "x": sim_x_m,
                "y": sim_y_m,
            },
            "positionGps": live_gps_position,
            "yawDeg": current_yaw_deg,
            "altitudeM": max(0.0, -current_local_down_m),
            "inAir": current_in_air or demo_active,
            "speedMps": GPS_ROUTE_EXPECTED_SPEED_M_S if demo_active else horizontal_speed,
            "batteryPercent": round(display_battery_percent, 1),
            "batteryState": display_battery_state,
            "batteryDrainMode": battery_snapshot.battery_drain_mode,
            "batteryCurrentA": round(battery_snapshot.current_draw_a, 2),
            "batteryCapacityMah": round(battery_snapshot.capacity_mAh, 1),
            "batteryRemainingMah": round(battery_snapshot.remaining_mAh, 1),
            "batteryConsumedMah": round(battery_snapshot.consumed_mAh, 1),
            "rawPx4BatteryPercent": current_px4_battery_percent,
            "connection": {
                "grpcConnected": connection_manager.grpc_connected,
                "px4Connected": connection_manager.px4_connected,
                "lastPx4SeenAgeS": connection_age_s,
            },
            "freshness": {
                "localPositionAgeS": now_s - local_position_update_s if local_position_update_s is not None else None,
                "healthAgeS": now_s - current_health_update_s if current_health_update_s is not None else None,
                "px4BatteryAgeS": px4_battery_age_s,
                "cameraFrameAgeS": camera.latest_frame_age_s("DOWN"),
                "lidarScanAgeS": lidar.latest_scan_age_s() if lidar is not None and hasattr(lidar, "latest_scan_age_s") else None,
            },
            "cameraMode": camera_orientation.current_mode,
            "cameraPitchDeg": camera_orientation.current_pitch_deg,
            "velocityNed": {
                "northMps": current_velocity_north_m_s,
                "eastMps": current_velocity_east_m_s,
                "downMps": current_velocity_down_m_s,
            },
            "activeCommand": {
                "forwardMps": current_forward_m_s,
                "rightMps": current_right_m_s,
                "downMps": current_down_m_s,
            },
            "autoPlan": {
                "active": auto_plan_active,
                "status": auto_plan_status,
                "currentIndex": auto_plan_index,
                "total": len(auto_plan_points),
            },
            "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        if lidar is not None and hasattr(lidar, "snapshot"):
            lidar_state, lidar_status, lidar_direction = lidar.snapshot()
            if lidar_state is not None:
                status["lidar"] = {
                    "enabled": lidar_ui_enabled or safety_sensor_enabled,
                    "available": bool(getattr(lidar, "available", False)),
                    "status": lidar_status,
                    "direction": lidar_direction,
                    "rangeMaxM": float(os.getenv("LIDAR_MAX_RANGE_M", "500.0")),
                    "frontM": lidar_state.front,
                    "frontLeftM": lidar_state.front_left,
                    "frontRightM": lidar_state.front_right,
                    "leftM": lidar_state.left,
                    "rightM": lidar_state.right,
                    "backM": lidar_state.back,
                    "nearestM": lidar_state.nearest_distance,
                    "nearestAngleDeg": lidar_state.nearest_angle,
                    "nearestDirection": lidar_state.nearest_direction,
                    "scanAgeS": lidar.latest_scan_age_s() if hasattr(lidar, "latest_scan_age_s") else None,
                }
        elif lidar_ui_enabled:
            status["lidar"] = {
                "enabled": True,
                "available": False,
                "status": "STARTING",
                "direction": "NONE",
                "rangeMaxM": float(os.getenv("LIDAR_MAX_RANGE_M", "500.0")),
                "scanAgeS": None,
            }
        status.update(thermal.status())
        return status

    def bind_control_session(payload: dict) -> dict:
        nonlocal active_mission_id, active_mission_code, active_device_id, media_library
        mission_id = str(payload.get("missionId", "")).strip()
        device_id = str(payload.get("deviceId", "")).strip()
        access_token = str(payload.get("accessToken", "")).strip()
        if not mission_id or len(mission_id) > 255:
            raise ValueError("missionId is required")
        if not device_id or len(device_id) > 80:
            raise ValueError("deviceId is required")
        if not access_token or len(access_token) > 4096 or any(char.isspace() for char in access_token):
            raise ValueError("A valid operator access token is required")
        # Switching from one mission to ANOTHER mid-air is still blocked. But a drone that is
        # airborne with no session yet (e.g. operator pressed Take off before Fly to point)
        # may be claimed, otherwise "Fly to point" could never start after takeoff.
        if (video_recorder.is_recording() or current_in_air) and active_mission_id is not None:
            if mission_id != active_mission_id or device_id != active_device_id:
                raise ValueError("Cannot switch control session while recording or in flight")

        assigned_mission = None
        backend_reachable = False
        token_rejected = False
        for base_url in backend_urls.candidates():
            try:
                response = httpx.get(
                    f"{base_url}/api/missions/mine",
                    headers={"Authorization": f"Bearer {access_token}"},
                    timeout=2.0,
                )
                if response.status_code in (401, 403):
                    token_rejected = True
                    continue
                if response.status_code != 200:
                    continue
                backend_reachable = True
                body = response.json()
                missions = body.get("data", []) if isinstance(body, dict) else []
                assigned_mission = next(
                    (item for item in missions if str(item.get("id", "")) == mission_id),
                    None,
                )
                if assigned_mission is not None:
                    break
            except (httpx.HTTPError, ValueError):
                continue
        if assigned_mission is None:
            if not backend_reachable:
                if token_rejected:
                    raise ValueError("Backend rejected the operator access token; sign in again")
                raise ValueError("Flight Controller cannot reach the backend mission API")
            raise ValueError("Mission is not assigned to the authenticated operator")
        assigned_device = str(assigned_mission.get("deviceId") or "").strip()
        if not assigned_device:
            raise ValueError("Mission has no assigned deviceId in the backend")
        if assigned_device != device_id:
            raise ValueError("Device does not match the mission assignment")

        active_mission_id = mission_id
        active_mission_code = str(assigned_mission.get("missionCode") or "").strip() or None
        active_device_id = assigned_device
        media_library.bind(mission_id, assigned_device, active_mission_code)
        camera.media_library = media_library
        control_api.media_library = media_library
        control_api.preflight_persistence = PreflightPersistenceBridge(
            backend_urls, mission_id, access_token)
        control_api.preflight_check_id = None
        control_api.preflight_started_at_s = None
        print(f"[SESSION] Bound mission={active_mission_code or mission_id} id={mission_id} device={assigned_device}", flush=True)
        return {
            "missionId": mission_id,
            "missionCode": active_mission_code,
            "deviceId": assigned_device,
        }

    def release_control_session(payload: dict) -> dict:
        nonlocal active_mission_id, active_mission_code, active_device_id, media_library, current_in_air
        requested_mission_id = str(payload.get("missionId", "")).strip()
        if requested_mission_id and active_mission_id and requested_mission_id != active_mission_id:
            return {
                "released": False,
                "missionId": active_mission_id,
                "message": "Active control session belongs to another mission",
            }
        retained_video = False
        if video_recorder.is_recording():
            result = stop_video_recording(retain=True)
            retained_video = result is not None
        stop_auto_plan("session release")
        active_mission_id = None
        active_mission_code = None
        active_device_id = None
        current_in_air = False
        media_library.bind(None, None)
        camera.media_library = media_library
        control_api.media_library = media_library
        control_api.preflight_persistence = None
        control_api.preflight_check_id = None
        control_api.preflight_started_at_s = None
        print("[SESSION] Released active control session", flush=True)
        return {"released": True, "retainedVideo": retained_video}

    control_api = FlightControlApi(
        camera,
        api_commands,
        api_status,
        build_preflight_status,
        None,
        thermal,
        media_library,
        bind_control_session,
        release_control_session,
    )
    control_api.start()
    print(
        f"[SAFETY] Sensor default -> "
        f"{'ON' if safety_sensor_enabled else 'OFF'} "
        f"(press o to toggle)",
        flush=True,
    )

    def has_manual_motion() -> bool:
        return (
                current_forward_m_s != 0.0
                or current_right_m_s != 0.0
                or current_down_m_s != 0.0
        )

    def set_motion_owner(owner) -> None:
        nonlocal motion_owner
        if owner != motion_owner:
            print(f"[OWNER] {motion_owner.value} -> {owner.value}", flush=True)
        motion_owner = owner

    def get_motion_owner():
        return motion_owner

    def set_safety_speed_scale(scale: float) -> None:
        nonlocal safety_speed_scale
        safety_speed_scale = max(0.0, min(1.0, scale))

    def start_obstacle_safety_if_needed() -> bool:
        nonlocal avoidance, lidar, safety_task
        if safety_task is not None and not safety_task.done():
            return True
        if LidarGateway is None or AvoidanceController is None:
            print(f"[LIDAR] Disabled: {LIDAR_IMPORT_ERROR}")
            print("[LIDAR] Flight control continues without obstacle avoidance.")
            return False

        if lidar is None:
            lidar = LidarGateway()
            lidar.start()
        if avoidance is None:
            avoidance = AvoidanceController(drone)

        safety_task = asyncio.create_task(
            obstacle_safety_loop(
                avoidance,
                lidar,
                connection_manager,
                lambda: current_yaw_deg,
                current_saved_motion,
                lambda: safety_sensor_enabled,
                get_motion_owner,
                set_motion_owner,
                stop_manual_motion,
                set_safety_speed_scale,
                launch_pad_clear,
            ),
            name="obstacle-safety",
        )
        return True

    def update_local_position(north_m: float, east_m: float, down_m: float) -> None:
        nonlocal current_local_north_m
        nonlocal current_local_east_m
        nonlocal current_local_down_m
        nonlocal local_position_ready
        nonlocal local_position_update_s
        current_local_north_m = north_m
        current_local_east_m = east_m
        current_local_down_m = down_m
        local_position_ready = True
        local_position_update_s = time.monotonic()

    def update_global_position(latitude: float, longitude: float, absolute_altitude_m: float, relative_altitude_m: float) -> None:
        nonlocal current_global_position
        nonlocal global_position_update_s
        if all(map(math.isfinite, (latitude, longitude, absolute_altitude_m, relative_altitude_m))) \
                and abs(latitude) <= 90 and abs(longitude) <= 180:
            current_global_position = {
                "latitude": latitude,
                "longitude": longitude,
                "absoluteAltitudeM": absolute_altitude_m,
                "relativeAltitudeM": relative_altitude_m,
            }
            global_position_update_s = time.monotonic()

    def update_velocity(north_m_s: float, east_m_s: float, down_m_s: float) -> None:
        nonlocal current_velocity_north_m_s
        nonlocal current_velocity_east_m_s
        nonlocal current_velocity_down_m_s
        current_velocity_north_m_s = north_m_s
        current_velocity_east_m_s = east_m_s
        current_velocity_down_m_s = down_m_s

    def update_battery(percent: float | None) -> None:
        nonlocal current_px4_battery_percent
        nonlocal current_px4_battery_update_s
        if percent is not None:
            current_px4_battery_percent = percent
            current_px4_battery_update_s = time.monotonic()

    def update_in_air(in_air: bool) -> None:
        nonlocal current_in_air
        nonlocal current_armed
        if current_in_air and not in_air:
            current_armed = False
        current_in_air = bool(in_air)

    def update_health(health) -> None:
        nonlocal current_health
        nonlocal current_health_update_s
        current_health = health
        current_health_update_s = time.monotonic()

    async def update_simulated_battery_loop() -> None:
        while True:
            simulated_battery.update(
                armed=current_armed or current_in_air,
                in_air=current_in_air,
                velocity_north_m_s=current_velocity_north_m_s,
                velocity_east_m_s=current_velocity_east_m_s,
                velocity_down_m_s=current_velocity_down_m_s,
            )
            await asyncio.sleep(0.5)

    def launch_pad_clear() -> bool:
        if not local_position_ready:
            return True
        launch_distance = math.hypot(
            current_local_north_m,
            current_local_east_m,
        )
        return launch_distance >= MISSION_LAUNCH_PAD_CLEAR_RADIUS_M

    def force_manual_control() -> None:
        set_motion_owner(MotionOwner.MANUAL)

    def build_auto_plan_route(points: list[dict]) -> tuple[list[dict], bool]:
        route = list(points)
        if not route:
            return route, False

        home = next((point for point in route if str(point.get("reason", "")).upper() == "START"), route[0])
        last = route[-1]
        already_home = (
            math.hypot(last["simX"] - home["simX"], last["simY"] - home["simY"])
            <= AUTO_PLAN_REACHED_RADIUS_M
        )
        if not already_home:
            route.append(
                {
                    **home,
                    "sequence": max(point["sequence"] for point in route) + 1,
                    "reason": "RETURN",
                }
            )
        return route, True

    async def land_current_drone(reason: str) -> None:
        print(f"[AUTO-PLAN] {reason}: landing", flush=True)
        stop_video_recording(retain=True)
        await connection_manager.stop_offboard_sender()
        active_drone = await connection_manager.get_drone()
        if active_drone is None:
            active_drone = await connection_manager.reconnect()
        if active_drone is None:
            print("[ERR] MAVSDK control bridge unavailable")
            return
        try:
            await active_drone.offboard.stop()
        except OffboardError:
            pass
        try:
            await active_drone.action.land()
        except ActionError as exc:
            print_command_denied("land", exc)
        except grpc.aio.AioRpcError as exc:
            print_mavsdk_unavailable("land", exc)
            if is_grpc_unavailable(exc):
                new_drone = await connection_manager.reconnect()
                if new_drone is not None and avoidance is not None:
                    avoidance.set_drone(new_drone)
                if new_drone is not None:
                    try:
                        await new_drone.action.land()
                    except ActionError as retry_exc:
                        print_command_denied("land", retry_exc)
                    except grpc.aio.AioRpcError as retry_exc:
                        print_mavsdk_unavailable("land retry", retry_exc)

    def stop_auto_plan(reason: str = "stopped") -> None:
        nonlocal auto_plan_active, auto_plan_points, auto_plan_index, auto_plan_status
        nonlocal auto_plan_desired_altitude_m, auto_plan_land_on_complete
        if auto_plan_active:
            print(f"[AUTO-PLAN] {reason}", flush=True)
        auto_plan_active = False
        auto_plan_points = []
        auto_plan_index = 0
        auto_plan_status = reason
        auto_plan_desired_altitude_m = None
        auto_plan_land_on_complete = False

    def start_auto_plan(points: list[dict]) -> None:
        nonlocal auto_plan_active, auto_plan_points, auto_plan_index, auto_plan_status
        nonlocal auto_plan_desired_altitude_m, auto_plan_land_on_complete
        auto_plan_points, auto_plan_land_on_complete = build_auto_plan_route(points)
        auto_plan_index = 0
        auto_plan_desired_altitude_m = max(0.0, -current_local_down_m)
        if local_position_ready and auto_plan_points:
            sim_x_m, sim_y_m = px4_ned_to_sim_xy(current_local_north_m, current_local_east_m)
            nearest_index = min(
                range(len(auto_plan_points)),
                key=lambda index: math.hypot(
                    auto_plan_points[index]["simX"] - sim_x_m,
                    auto_plan_points[index]["simY"] - sim_y_m,
                ),
            )
            nearest = auto_plan_points[nearest_index]
            nearest_distance = math.hypot(nearest["simX"] - sim_x_m, nearest["simY"] - sim_y_m)
            auto_plan_index = (
                min(nearest_index + 1, len(auto_plan_points) - 1)
                if nearest_distance <= AUTO_PLAN_REACHED_RADIUS_M
                else nearest_index
            )
        auto_plan_active = True
        auto_plan_status = "RUNNING"
        set_motion_owner(MotionOwner.AUTO_PLAN)
        print(
            f"[AUTO-PLAN] Started with {len(auto_plan_points)} waypoint(s), "
            f"current target index={auto_plan_index}",
            flush=True,
        )

    async def update_auto_plan() -> None:
        nonlocal auto_plan_index, auto_plan_status
        nonlocal current_forward_m_s, current_right_m_s
        nonlocal current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg
        nonlocal auto_plan_desired_altitude_m

        if not auto_plan_active:
            return
        if not local_position_ready:
            print("[AUTO-PLAN] Waiting for local position", flush=True)
            return
        if auto_plan_index >= len(auto_plan_points):
            should_land = auto_plan_land_on_complete
            auto_plan_status = "LANDING" if should_land else "COMPLETE"
            stop_auto_plan("complete")
            active_drone = await set_motion(connection_manager, 0.0, 0.0, 0.0, current_yaw_deg)
            if active_drone is not None and avoidance is not None:
                avoidance.set_drone(active_drone)
            set_motion_owner(MotionOwner.MANUAL)
            if should_land:
                auto_plan_status = "LANDING"
                await land_current_drone("return complete")
            return

        sim_x_m, sim_y_m = px4_ned_to_sim_xy(current_local_north_m, current_local_east_m)
        altitude_m = max(0.0, -current_local_down_m)
        target = auto_plan_points[auto_plan_index]
        auto_plan_status = "RETURNING" if str(target.get("reason", "")).upper() == "RETURN" else "RUNNING"
        dx = target["simX"] - sim_x_m
        dy = target["simY"] - sim_y_m
        horizontal_distance = math.hypot(dx, dy)
        if auto_plan_desired_altitude_m is None:
            auto_plan_desired_altitude_m = altitude_m
        altitude_target_delta = target["altitudeM"] - auto_plan_desired_altitude_m
        if abs(altitude_target_delta) <= AUTO_PLAN_ALTITUDE_RAMP_M:
            auto_plan_desired_altitude_m = target["altitudeM"]
        else:
            auto_plan_desired_altitude_m += math.copysign(AUTO_PLAN_ALTITUDE_RAMP_M, altitude_target_delta)
        altitude_error = auto_plan_desired_altitude_m - altitude_m

        if (
                horizontal_distance <= AUTO_PLAN_REACHED_RADIUS_M
                and abs(altitude_error) <= AUTO_PLAN_ALTITUDE_TOLERANCE_M
        ):
            print(
                f"[AUTO-PLAN] Reached waypoint {target['sequence']} "
                f"dist={horizontal_distance:.1f}m alt_err={altitude_error:.1f}m",
                flush=True,
            )
            auto_plan_index += 1
            return

        plan_speed = min(float(target["speedMps"]), control_speed_m_s, AUTO_PLAN_MAX_SPEED_M_S)
        if horizontal_distance <= AUTO_PLAN_REACHED_RADIUS_M:
            speed = 0.0
        else:
            slowdown_span = max(1.0, AUTO_PLAN_SLOWDOWN_RADIUS_M - AUTO_PLAN_REACHED_RADIUS_M)
            slowdown_ratio = min(
                1.0,
                max(0.0, (horizontal_distance - AUTO_PLAN_REACHED_RADIUS_M) / slowdown_span),
            )
            speed = AUTO_PLAN_MIN_SPEED_M_S + (plan_speed - AUTO_PLAN_MIN_SPEED_M_S) * slowdown_ratio
            speed = min(plan_speed, max(AUTO_PLAN_MIN_SPEED_M_S, speed))

        if horizontal_distance <= AUTO_PLAN_REACHED_RADIUS_M:
            north_m_s = 0.0
            east_m_s = 0.0
        else:
            east_m_s = (dx / horizontal_distance) * speed
            north_m_s = (dy / horizontal_distance) * speed

        if abs(altitude_error) <= AUTO_PLAN_ALTITUDE_TOLERANCE_M:
            down_m_s = 0.0
        else:
            vertical_speed = min(
                AUTO_PLAN_VERTICAL_MAX_SPEED_M_S,
                control_vertical_speed_m_s,
                max(0.15, abs(altitude_error) * AUTO_PLAN_VERTICAL_GAIN),
            )
            down_m_s = -vertical_speed if altitude_error > 0.0 else vertical_speed

        smoothing = min(1.0, max(0.0, AUTO_PLAN_SETPOINT_SMOOTHING))
        north_m_s = current_north_m_s + (north_m_s - current_north_m_s) * smoothing
        east_m_s = current_east_m_s + (east_m_s - current_east_m_s) * smoothing
        down_m_s = current_down_m_s + (down_m_s - current_down_m_s) * smoothing
        if abs(down_m_s) < 0.08:
            down_m_s = 0.0

        if horizontal_distance > 0.001:
            current_yaw_deg = (math.degrees(math.atan2(east_m_s, north_m_s)) + 360.0) % 360.0
        current_forward_m_s = math.hypot(north_m_s, east_m_s)
        current_right_m_s = 0.0
        current_north_m_s = north_m_s
        current_east_m_s = east_m_s
        current_down_m_s = down_m_s

        try:
            active_drone = await set_motion(
                connection_manager,
                current_north_m_s,
                current_east_m_s,
                current_down_m_s,
                current_yaw_deg,
            )
            if active_drone is not None and avoidance is not None:
                avoidance.set_drone(active_drone)
        except OffboardError as exc:
            print_command_denied("auto plan", exc)
            stop_auto_plan("offboard rejected")
            set_motion_owner(MotionOwner.MANUAL)
        except grpc.aio.AioRpcError as exc:
            print_mavsdk_unavailable("auto plan", exc)
            stop_auto_plan("mavsdk unavailable")
            set_motion_owner(MotionOwner.MANUAL)

    async def start_gps_target(target: dict) -> None:
        nonlocal current_armed
        nonlocal current_forward_m_s, current_right_m_s
        nonlocal current_north_m_s, current_east_m_s, current_down_m_s
        nonlocal current_yaw_deg
        nonlocal demo_gps_route_points, demo_gps_route_schedule, demo_gps_started_s
        if FAST_DEMO_MODE and not GPS_TARGET_REAL_FLIGHT:
            start_position = current_global_position or {
                "latitude": float(target.get("latitude", 0.0)),
                "longitude": float(target.get("longitude", 0.0)),
                "absoluteAltitudeM": 20.0,
                "relativeAltitudeM": 20.0,
            }
            try:
                normalized = normalize_gps_target(target, start_position, True)
            except ValueError as exc:
                print(f"[GPS-DEMO] Target navigation rejected: {exc}", flush=True)
                return
            demo_gps_route_points = [
                {
                    "latitude": float(start_position["latitude"]),
                    "longitude": float(start_position["longitude"]),
                    "absoluteAltitudeM": float(start_position.get("absoluteAltitudeM", 20.0)),
                    "relativeAltitudeM": float(start_position.get("relativeAltitudeM", 20.0)),
                },
                *[
                    {
                        "latitude": float(waypoint["latitude"]),
                        "longitude": float(waypoint["longitude"]),
                        "absoluteAltitudeM": float(start_position.get("absoluteAltitudeM", 20.0))
                            - float(start_position.get("relativeAltitudeM", 20.0))
                            + float(waypoint["relativeAltitudeM"]),
                        "relativeAltitudeM": float(waypoint["relativeAltitudeM"]),
                    }
                    for waypoint in normalized["routeWaypoints"]
                ],
            ]
            demo_gps_route_schedule = [0.0]
            total_distance_m = 0.0
            for previous, current in zip(demo_gps_route_points, demo_gps_route_points[1:]):
                leg_distance_m = gps_distance_m(
                    previous["latitude"],
                    previous["longitude"],
                    current["latitude"],
                    current["longitude"],
                )
                total_distance_m += leg_distance_m
                leg_duration_s = max(1.2, leg_distance_m / max(1.0, GPS_ROUTE_EXPECTED_SPEED_M_S))
                demo_gps_route_schedule.append(demo_gps_route_schedule[-1] + leg_duration_s)
            if len(demo_gps_route_schedule) > 1:
                total_duration_s = demo_gps_route_schedule[-1]
                scale = 1.0
                if total_duration_s < 8.0:
                    scale = 8.0 / total_duration_s
                elif total_duration_s > 45.0:
                    scale = 45.0 / total_duration_s
                demo_gps_route_schedule = [seconds * scale for seconds in demo_gps_route_schedule]
            demo_gps_started_s = time.monotonic()
            print(
                f"[GPS-DEMO] Fast demo route started with {len(demo_gps_route_points)} points "
                f"toward lat={normalized['latitude']:.7f} lon={normalized['longitude']:.7f} "
                f"distance={total_distance_m:.1f}m duration={demo_gps_route_schedule[-1]:.1f}s",
                flush=True,
            )
            return
        active_drone = await connection_manager.get_drone()
        if active_drone is None:
            active_drone = await connection_manager.reconnect()
        if active_drone is None:
            print("[GPS] MAVSDK control bridge unavailable", flush=True)
            return

        active_drone = await safe_arm(connection_manager)
        if active_drone is None:
            print("[GPS] Target navigation cancelled: cannot arm drone", flush=True)
            return
        current_armed = True
        if avoidance is not None:
            avoidance.set_drone(active_drone)

        gps_deadline_s = time.monotonic() + 10.0
        while (
                (current_global_position is None or global_position_update_s is None
                 or time.monotonic() - global_position_update_s >= 5.0)
                and time.monotonic() < gps_deadline_s
        ):
            await asyncio.sleep(0.2)

        if current_global_position is None or global_position_update_s is None \
                or time.monotonic() - global_position_update_s >= 5.0:
            print("[GPS] Target navigation rejected: PX4 GPS is not fresh", flush=True)
            return
        try:
            normalized = normalize_gps_target(target, current_global_position, current_in_air)
        except ValueError as exc:
            print(f"[GPS] Target navigation rejected: {exc}", flush=True)
            return

        stop_auto_plan("gps target command")
        current_forward_m_s = 0.0
        current_right_m_s = 0.0
        current_north_m_s = 0.0
        current_east_m_s = 0.0
        current_down_m_s = 0.0
        set_motion_owner(MotionOwner.MANUAL)

        try:
            def stream_motion(
                    north_m_s: float,
                    east_m_s: float,
                    down_m_s: float,
                    yaw_deg: float,
            ) -> None:
                connection_manager.update_desired_motion(north_m_s, east_m_s, down_m_s, yaw_deg)

            cruise_speed_m_s = max(
                3.0,
                min(float(normalized.get("speedMps", 6.0)), GPS_ROUTE_EXPECTED_SPEED_M_S, control_speed_m_s, 45.0),
            )
            if REALISTIC_FLIGHT:
                cruise_speed_m_s = max(2.0, min(cruise_speed_m_s, GPS_FLIGHT_CRUISE_SPEED_M_S, REAL_MAX_XY_SPEED_M_S))
            climb_altitude_m = max(8.0, min(50.0, normalized["relativeAltitudeM"]))
            print(
                f"[GPS] Real Gazebo flight: climb to {climb_altitude_m:.1f}m, "
                f"speed={cruise_speed_m_s:.1f}m/s, waypoints={len(normalized['routeWaypoints'])}",
                flush=True,
            )

            climb_started_s = time.monotonic()
            while True:
                current_altitude_m = float((current_global_position or {}).get("relativeAltitudeM", 0.0) or 0.0)
                if current_altitude_m >= climb_altitude_m - 0.8:
                    break
                if time.monotonic() - climb_started_s >= (45.0 if REALISTIC_FLIGHT else 18.0):
                    print("[GPS] Climb timeout; continuing toward target", flush=True)
                    break
                altitude_error_m = climb_altitude_m - current_altitude_m
                climb_speed_m_s = min(REAL_CLIMB_SPEED_M_S if REALISTIC_FLIGHT else 4.0, max(0.8, altitude_error_m * 0.5))
                first_waypoint = normalized["routeWaypoints"][0]
                current_yaw_deg = gps_bearing_deg(
                    float(current_global_position["latitude"]),
                    float(current_global_position["longitude"]),
                    first_waypoint["latitude"],
                    first_waypoint["longitude"],
                )
                current_forward_m_s = 0.0
                current_right_m_s = 0.0
                current_north_m_s = 0.0
                current_east_m_s = 0.0
                current_down_m_s = -climb_speed_m_s
                if time.monotonic() - climb_started_s < 0.25:
                    await set_motion(connection_manager, 0.0, 0.0, current_down_m_s, current_yaw_deg)
                else:
                    stream_motion(0.0, 0.0, current_down_m_s, current_yaw_deg)
                await asyncio.sleep(0.05)

            route_waypoints = normalized["routeWaypoints"]
            route_points = [dict(current_global_position), *route_waypoints]
            origin_lat = float(route_points[0]["latitude"])
            origin_lon = float(route_points[0]["longitude"])
            path_xy: list[tuple[float, float, float]] = [
                (*gps_offset_m(origin_lat, origin_lon, point["latitude"], point["longitude"]),
                 float(point.get("relativeAltitudeM", climb_altitude_m)))
                for point in route_points
            ]
            cumulative_m = [0.0]
            for previous, current in zip(path_xy, path_xy[1:]):
                cumulative_m.append(
                    cumulative_m[-1] + math.hypot(current[0] - previous[0], current[1] - previous[1])
                )
            total_path_m = cumulative_m[-1]
            lookahead_m = max(25.0, min(180.0, cruise_speed_m_s * 4.0)) if REALISTIC_FLIGHT else max(80.0, min(180.0, cruise_speed_m_s * 4.0))
            commanded_speed_m_s = 0.0
            last_tick_s = time.monotonic()
            last_log_s = 0.0
            started_s = time.monotonic()
            max_flight_s = max(90.0, total_path_m / max(1.0, cruise_speed_m_s) * 3.0 + 60.0)

            await set_motion(connection_manager, 0.0, 0.0, 0.0, current_yaw_deg)
            print(
                f"[GPS] Smooth route tracking path={total_path_m:.1f}m "
                f"lookahead={lookahead_m:.1f}m",
                flush=True,
            )

            def point_at_distance(distance_m: float) -> tuple[float, float, float]:
                if distance_m <= 0.0:
                    return path_xy[0]
                if distance_m >= total_path_m:
                    return path_xy[-1]
                for segment_index in range(len(cumulative_m) - 1):
                    start_m = cumulative_m[segment_index]
                    end_m = cumulative_m[segment_index + 1]
                    if distance_m > end_m:
                        continue
                    span_m = max(0.001, end_m - start_m)
                    ratio = (distance_m - start_m) / span_m
                    start = path_xy[segment_index]
                    end = path_xy[segment_index + 1]
                    return (
                        start[0] + (end[0] - start[0]) * ratio,
                        start[1] + (end[1] - start[1]) * ratio,
                        start[2] + (end[2] - start[2]) * ratio,
                    )
                return path_xy[-1]

            while True:
                if current_global_position is None:
                    await asyncio.sleep(0.05)
                    continue

                current_x_m, current_y_m = gps_offset_m(
                    origin_lat,
                    origin_lon,
                    float(current_global_position["latitude"]),
                    float(current_global_position["longitude"]),
                )
                best_progress_m = 0.0
                best_distance_m = float("inf")
                for segment_index in range(len(path_xy) - 1):
                    start = path_xy[segment_index]
                    end = path_xy[segment_index + 1]
                    vx = end[0] - start[0]
                    vy = end[1] - start[1]
                    span_sq = max(0.001, vx * vx + vy * vy)
                    ratio = max(
                        0.0,
                        min(1.0, ((current_x_m - start[0]) * vx + (current_y_m - start[1]) * vy) / span_sq),
                    )
                    projected_x = start[0] + vx * ratio
                    projected_y = start[1] + vy * ratio
                    distance_m = math.hypot(current_x_m - projected_x, current_y_m - projected_y)
                    if distance_m < best_distance_m:
                        best_distance_m = distance_m
                        best_progress_m = cumulative_m[segment_index] + math.sqrt(span_sq) * ratio

                final_x_m, final_y_m, final_altitude_m = path_xy[-1]
                remaining_final_m = math.hypot(final_x_m - current_x_m, final_y_m - current_y_m)
                if remaining_final_m <= 30.0:
                    print(f"[GPS] Smooth target reached ({remaining_final_m:.1f}m)", flush=True)
                    break
                if time.monotonic() - started_s >= max_flight_s:
                    print(f"[GPS] Smooth route timeout ({remaining_final_m:.1f}m left)", flush=True)
                    break

                target_x_m, target_y_m, target_altitude_m = point_at_distance(best_progress_m + lookahead_m)
                dx_m = target_x_m - current_x_m
                dy_m = target_y_m - current_y_m
                vector_distance_m = max(0.001, math.hypot(dx_m, dy_m))
                current_yaw_deg = (math.degrees(math.atan2(dy_m, dx_m)) + 360.0) % 360.0

                if remaining_final_m > 120.0:
                    speed_m_s = cruise_speed_m_s
                else:
                    speed_m_s = min(cruise_speed_m_s, max(2.0, remaining_final_m * 0.35))
                if REALISTIC_FLIGHT:
                    # accelerate/decelerate gradually instead of jumping straight to cruise speed
                    tick_s = time.monotonic()
                    dt_s = max(0.0, min(0.5, tick_s - last_tick_s))
                    last_tick_s = tick_s
                    max_step = GPS_FLIGHT_ACCEL_M_S2 * dt_s
                    commanded_speed_m_s += max(-max_step * 2.0, min(max_step, speed_m_s - commanded_speed_m_s))
                    speed_m_s = max(0.0, commanded_speed_m_s)
                current_north_m_s = (dx_m / vector_distance_m) * speed_m_s
                current_east_m_s = (dy_m / vector_distance_m) * speed_m_s

                altitude_error_m = target_altitude_m - float(
                    current_global_position.get("relativeAltitudeM", climb_altitude_m) or climb_altitude_m
                )
                if abs(altitude_error_m) <= 0.8:
                    current_down_m_s = 0.0
                else:
                    vertical_m_s = min(2.0, max(0.4, abs(altitude_error_m) * 0.35))
                    current_down_m_s = -vertical_m_s if altitude_error_m > 0.0 else vertical_m_s

                current_forward_m_s = math.hypot(current_north_m_s, current_east_m_s)
                current_right_m_s = 0.0
                stream_motion(current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if time.monotonic() - last_log_s >= 4.0:
                    print(
                        f"[GPS] Smooth tracking remaining={remaining_final_m:.1f}m "
                        f"cross_track={best_distance_m:.1f}m speed={speed_m_s:.1f}m/s",
                        flush=True,
                    )
                    last_log_s = time.monotonic()
                await asyncio.sleep(0.05)

            current_forward_m_s = 0.0
            current_right_m_s = 0.0
            current_north_m_s = 0.0
            current_east_m_s = 0.0
            current_down_m_s = 0.0
            stream_motion(0.0, 0.0, 0.0, current_yaw_deg)
            print("[GPS] Target flight complete - hovering", flush=True)
        except (ActionError, OffboardError) as exc:
            print_command_denied("gps target offboard", exc)
        except grpc.aio.AioRpcError as exc:
            print_mavsdk_unavailable("gps target offboard", exc)

    def current_saved_motion():
        if not has_manual_motion():
            return None

        return SavedMotion(
            forward_m_s=current_forward_m_s,
            right_m_s=current_right_m_s,
            down_m_s=current_down_m_s,
            north_m_s=current_north_m_s,
            east_m_s=current_east_m_s,
            yaw_deg=current_yaw_deg,
        )
    def stop_manual_motion(clear_saved: bool = True) -> None:
        nonlocal current_forward_m_s
        nonlocal current_right_m_s
        nonlocal current_north_m_s
        nonlocal current_east_m_s
        nonlocal current_down_m_s

        if not clear_saved:
            return

        current_forward_m_s = 0.0
        current_right_m_s = 0.0
        current_north_m_s = 0.0
        current_east_m_s = 0.0
        current_down_m_s = 0.0

    def track_background_task(task: asyncio.Task, label: str) -> None:
        media_tasks.add(task)

        def _done(done: asyncio.Task) -> None:
            media_tasks.discard(done)
            try:
                exc = done.exception()
            except asyncio.CancelledError:
                return
            if exc is not None:
                print(f"[{label}] Background error: {exc}", flush=True)

        task.add_done_callback(_done)

    async def wait_for_media_tasks(timeout_s: float = MEDIA_UPLOAD_SHUTDOWN_WAIT_S) -> None:
        pending = [task for task in media_tasks if not task.done()]
        if not pending:
            return
        print(f"[VIDEO] Waiting for {len(pending)} pending upload(s)", flush=True)
        done, still_pending = await asyncio.wait(pending, timeout=timeout_s)
        for task in done:
            try:
                task.result()
            except asyncio.CancelledError:
                pass
            except Exception as exc:
                print(f"[MEDIA] Background error: {exc}", flush=True)
        if still_pending:
            print("[VIDEO] Upload wait timeout - local file retained", flush=True)
            for task in still_pending:
                task.cancel()
            await asyncio.gather(*still_pending, return_exceptions=True)

    async def cancel_owned_tasks() -> None:
        owned = [
            ("safety", safety_task),
            ("position", position_task),
            ("global-position", global_position_task),
            ("battery", battery_task),
            ("in-air", in_air_task),
            ("simulated-battery", simulated_battery_task),
            ("health", health_task),
            ("watchdog", watchdog_task),
        ]
        for name, task in owned:
            if task is not None and not task.done():
                task.cancel()
        tasks = [task for _name, task in owned if task is not None]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def cleanup_controller(retain_video: bool = True) -> None:
        print("[SHUTDOWN] Cleaning up...", flush=True)
        control_api.stop()
        stop_video_recording(retain=retain_video)
        await connection_manager.stop_offboard_sender()
        await connection_manager.stop_connection_monitor()
        await cancel_owned_tasks()
        await wait_for_media_tasks()
        print("[SHUTDOWN] Cleanup complete", flush=True)

    def stop_video_recording(retain: bool = True) -> RecordingResult | None:
        if not video_recorder.is_recording():
            return None
        print("[VIDEO] Stopping recording...", flush=True)
        result = video_recorder.stop_recording()
        if result is None:
            return None
        print(
            f"[VIDEO] Recording stopped frames={result.frames_written} "
            f"duration={result.duration_s:.2f}s path={result.path}",
            flush=True,
        )
        print(
            f"[VIDEO] Stats fps={result.configured_fps:.1f} "
            f"size={result.width}x{result.height} dropped={result.dropped_frames}",
            flush=True,
        )
        if retain and result.frames_written > 0:
            task = asyncio.create_task(camera.retain_recorded_video(result))
            track_background_task(task, "VIDEO")
        elif result.frames_written <= 0:
            print("[VIDEO] Local review skipped - no frames were recorded", flush=True)
        return result

    # ================================================================
    # START LIDAR / SAFETY
    # ================================================================

    position_task = asyncio.create_task(
        track_local_position(
            connection_manager,
            update_local_position,
            update_velocity,
        )
    )

    global_position_task = asyncio.create_task(
        track_global_position(connection_manager, update_global_position)
    )

    battery_task = asyncio.create_task(
        track_battery(
            connection_manager,
            update_battery,
        )
    )

    in_air_task = asyncio.create_task(
        track_in_air(
            connection_manager,
            update_in_air,
        )
    )

    simulated_battery_task = asyncio.create_task(
        update_simulated_battery_loop(),
        name="simulated-battery",
    )

    health_task = asyncio.create_task(
        track_health(
            connection_manager,
            update_health,
        )
    )

    if safety_sensor_enabled:
        if not start_obstacle_safety_if_needed():
            safety_sensor_enabled = False

    while True:

        try:
            command_message = api_commands.get_nowait()
        except queue.Empty:
            command_message = await asyncio.to_thread(read_key_timeout, 0.1)
            if command_message is None:
                if auto_plan_active and motion_owner == MotionOwner.AUTO_PLAN:
                    await update_auto_plan()
                continue

        if isinstance(command_message, dict):
            if command_message.get("type") == "auto_plan_start":
                start_auto_plan(command_message.get("waypoints", []))
                continue
            if command_message.get("type") == "gps_target_start":
                if gps_target_task is not None and not gps_target_task.done():
                    gps_target_task.cancel()
                gps_target_task = asyncio.create_task(
                    start_gps_target(command_message.get("target", {})),
                    name="gps-target-flight",
                )
                track_background_task(gps_target_task, "GPS")
                continue
            key = str(command_message.get("key", ""))
        else:
            key = str(command_message)

        if (
                motion_owner != MotionOwner.MANUAL
                and not safety_sensor_enabled
                and motion_owner != MotionOwner.AUTO_PLAN
        ):
            force_manual_control()

        if key in WEATHER_KEY_PRESETS:
            await asyncio.to_thread(apply_weather_key, key)
            continue

        if key in {"w", "a", "s", "d", "f", "v", "q", "e", "k", "h", "l"} and auto_plan_active:
            stop_auto_plan("manual override")
            set_motion_owner(MotionOwner.MANUAL)

        if key in {"w", "a", "s", "d", "f", "v", "q", "e"} and motion_owner != MotionOwner.MANUAL:
            print("[CONTROL] Obstacle stop active - hover until path is clear", flush=True)
            continue

        if key in {"1", "2"}:
            vertical_direction = -1.0 if current_down_m_s < 0.0 else 1.0
            delta = SPEED_ADJUST_STEP_M_S if key == "1" else -SPEED_ADJUST_STEP_M_S
            control_speed_m_s = max(0.0, control_speed_m_s + delta)
            control_vertical_speed_m_s = max(0.0, control_vertical_speed_m_s + delta)
            print(
                f"[SPEED] horizontal={control_speed_m_s:.1f}m/s "
                f"vertical={control_vertical_speed_m_s:.1f}m/s "
                f"step={SPEED_ADJUST_STEP_M_S:.1f}m/s",
                flush=True,
            )

            body_horizontal = math.hypot(current_forward_m_s, current_right_m_s)
            if abs(current_down_m_s) > 1e-6:
                current_down_m_s = vertical_direction * control_vertical_speed_m_s

            active_drone = await connection_manager.get_drone()
            if active_drone is not None:
                await configure_px4_speed_limits(
                    active_drone,
                    control_speed_m_s,
                    control_vertical_speed_m_s,
                )

            if body_horizontal > 1e-6 and motion_owner == MotionOwner.MANUAL:
                scale = control_speed_m_s / body_horizontal
                current_forward_m_s *= scale
                current_right_m_s *= scale
                current_north_m_s, current_east_m_s = body_velocity(
                    current_forward_m_s,
                    current_right_m_s,
                    current_yaw_deg,
                )
                try:
                    active_drone = await set_motion(
                        connection_manager,
                        current_north_m_s,
                        current_east_m_s,
                        current_down_m_s,
                        current_yaw_deg,
                    )
                    if active_drone is not None and avoidance is not None:
                        avoidance.set_drone(active_drone)
                except OffboardError as exc:
                    print_command_denied("speed adjust", exc)
                except grpc.aio.AioRpcError as exc:
                    print_mavsdk_unavailable("speed adjust", exc)
            elif abs(current_down_m_s) > 1e-6 and motion_owner == MotionOwner.MANUAL:
                try:
                    active_drone = await set_motion(
                        connection_manager,
                        current_north_m_s,
                        current_east_m_s,
                        current_down_m_s,
                        current_yaw_deg,
                    )
                    if active_drone is not None and avoidance is not None:
                        avoidance.set_drone(active_drone)
                except OffboardError as exc:
                    print_command_denied("speed adjust", exc)
                except grpc.aio.AioRpcError as exc:
                    print_mavsdk_unavailable("speed adjust", exc)

        elif key == "t":
            print("[CMD] arm + takeoff")
            active_drone = await safe_arm(connection_manager)
            if active_drone is not None:
                current_armed = True
                if avoidance is not None:
                    avoidance.set_drone(active_drone)
                try:
                    await active_drone.action.takeoff()
                    print("[CMD] Takeoff command sent - climbing...")
                    await wait_for_takeoff_confirm(active_drone)
                except (ActionError, OffboardError) as exc:
                    print_command_denied("takeoff/offboard", exc)
                except grpc.aio.AioRpcError as exc:
                    print_mavsdk_unavailable("takeoff", exc)
                    if is_grpc_unavailable(exc):
                        new_drone = await connection_manager.reconnect()
                        if new_drone is not None and avoidance is not None:
                            avoidance.set_drone(new_drone)
                        if new_drone is not None:
                            try:
                                await new_drone.action.takeoff()
                                print("[CMD] Takeoff command sent - climbing...")
                                await wait_for_takeoff_confirm(new_drone)
                            except (ActionError, OffboardError) as retry_exc:
                                print_command_denied("takeoff/offboard", retry_exc)
                            except grpc.aio.AioRpcError as retry_exc:
                                print_mavsdk_unavailable("takeoff retry", retry_exc)
        elif key == "w":
            print("[CMD] forward")
            current_forward_m_s = control_speed_m_s
            current_right_m_s = 0.0
            current_north_m_s, current_east_m_s = body_velocity(
                current_forward_m_s, current_right_m_s, current_yaw_deg
            )
            current_down_m_s = 0.0
            try:
                active_drone = await set_motion(connection_manager, current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("forward", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("forward", exc)
        elif key == "s":
            print("[CMD] backward")
            current_forward_m_s = -control_speed_m_s
            current_right_m_s = 0.0
            current_north_m_s, current_east_m_s = body_velocity(
                current_forward_m_s, current_right_m_s, current_yaw_deg
            )
            current_down_m_s = 0.0
            try:
                active_drone = await set_motion(connection_manager, current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("backward", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("backward", exc)
        elif key == "a":
            print("[CMD] left")
            current_forward_m_s = 0.0
            current_right_m_s = -control_speed_m_s
            current_north_m_s, current_east_m_s = body_velocity(
                current_forward_m_s, current_right_m_s, current_yaw_deg
            )
            current_down_m_s = 0.0
            try:
                active_drone = await set_motion(connection_manager, current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("left", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("left", exc)
        elif key == "d":
            print("[CMD] right")
            current_forward_m_s = 0.0
            current_right_m_s = control_speed_m_s
            current_north_m_s, current_east_m_s = body_velocity(
                current_forward_m_s, current_right_m_s, current_yaw_deg
            )
            current_down_m_s = 0.0
            try:
                active_drone = await set_motion(connection_manager, current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("right", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("right", exc)
        elif key == "f":
            print("[CMD] up")
            current_forward_m_s = 0.0
            current_right_m_s = 0.0
            current_north_m_s = 0.0
            current_east_m_s = 0.0
            current_down_m_s = -control_vertical_speed_m_s
            try:
                if not current_in_air:
                    active_drone = await connection_manager.get_drone() if current_armed else None
                    if active_drone is None:
                        active_drone = await safe_arm(connection_manager)
                    if active_drone is None:
                        print("[CMD] Up cancelled - drone could not arm", flush=True)
                        continue
                    current_armed = True
                    await active_drone.action.takeoff()
                    print("[CMD] Up requested from ground - takeoff initiated", flush=True)
                    await asyncio.sleep(0.5)
                active_drone = await set_motion(connection_manager, current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except (ActionError, OffboardError) as exc:
                print_command_denied("up", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("up", exc)
        elif key == "v":
            print("[CMD] down")
            current_forward_m_s = 0.0
            current_right_m_s = 0.0
            current_north_m_s = 0.0
            current_east_m_s = 0.0
            current_down_m_s = control_vertical_speed_m_s
            try:
                active_drone = await set_motion(connection_manager, current_north_m_s, current_east_m_s, current_down_m_s, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("down", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("down", exc)
        elif key == "q":
            current_yaw_deg = (current_yaw_deg - YAW_STEP_DEG) % 360.0
            print(f"[CMD] yaw left -> {current_yaw_deg:.0f} deg")
            current_north_m_s, current_east_m_s = body_velocity(
                current_forward_m_s, current_right_m_s, current_yaw_deg
            )

            try:
                active_drone = await set_motion(
                    connection_manager,
                    current_north_m_s,
                    current_east_m_s,
                    current_down_m_s,
                    current_yaw_deg
                )
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("yaw left", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("yaw left", exc)

        elif key == "e":
            current_yaw_deg = (current_yaw_deg + YAW_STEP_DEG) % 360.0
            print(f"[CMD] yaw right -> {current_yaw_deg:.0f} deg")
            current_north_m_s, current_east_m_s = body_velocity(
                current_forward_m_s, current_right_m_s, current_yaw_deg
            )

            try:
                active_drone = await set_motion(
                    connection_manager,
                    current_north_m_s,
                    current_east_m_s,
                    current_down_m_s,
                    current_yaw_deg
                )
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("yaw right", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("yaw right", exc)
        elif key in ("k", "h"):
            print("[CMD] stop / hover")
            set_motion_owner(MotionOwner.MANUAL)
            stop_manual_motion()
            try:
                active_drone = await set_motion(connection_manager, 0.0, 0.0, 0.0, current_yaw_deg)
                if active_drone is not None and avoidance is not None:
                    avoidance.set_drone(active_drone)
            except OffboardError as exc:
                print_command_denied("stop/hover", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("stop/hover", exc)
        elif key == "o":
            requested_state = not safety_sensor_enabled
            if requested_state and not start_obstacle_safety_if_needed():
                requested_state = False
            safety_sensor_enabled = requested_state
            if not safety_sensor_enabled and motion_owner == MotionOwner.EMERGENCY:
                force_manual_control()
            state = "ON" if safety_sensor_enabled else "OFF"
            print(f"[SAFETY] Sensor toggle -> {state}", flush=True)
        elif key == "c":
            camera_orientation.toggle()
        elif key == "camera_front":
            camera_orientation.set_mode("FRONT")
        elif key == "camera_down":
            camera_orientation.set_mode("DOWN")
        elif key == "3":
            toggle_monitor_window(
                "Camera monitor",
                "wsl-camera-view.sh",
                "downward_camera_viewer.py|wsl-camera-view.sh",
            )
        elif key == "4":
            lidar_ui_enabled = not lidar_ui_enabled
            if lidar_ui_enabled:
                if LidarGateway is None:
                    lidar_ui_enabled = False
                    print(f"[LIDAR] Disabled: {LIDAR_IMPORT_ERROR}", flush=True)
                elif lidar is None:
                    lidar = LidarGateway()
                    lidar.start()
                    print("[LIDAR] UI monitor -> ON (web dashboard only)", flush=True)
                else:
                    print("[LIDAR] UI monitor -> ON (web dashboard only)", flush=True)
            else:
                print("[LIDAR] UI monitor -> OFF", flush=True)
        elif key == "5":
            toggle_monitor_window(
                "Telemetry monitor",
                "wsl-telemetry.sh",
                "telemetry_sender.py|wsl-telemetry.sh",
            )
        elif key == "6":
            enabled = thermal.toggle()
            set_native_thermal_subscription(enabled)
            print(f"[THERMAL] {'ON' if enabled else 'OFF'}", flush=True)
            stop_monitor_window(
                "Thermal camera",
                "wsl-thermal-view.sh|thermal_debug_viewer.py|sensor_dashboard.*--thermal-view",
            )
        elif key == "7":
            print("[THERMAL] Viewer disabled; use UI thermal stream only", flush=True)
        elif key == "thermal_palette_next":
            print(f"[THERMAL] Palette -> {thermal.cycle_palette()}", flush=True)
        elif key == "thermal_isotherm_toggle":
            print(f"[THERMAL] Isotherm -> {'ON' if thermal.toggle_isotherm() else 'OFF'}", flush=True)
        elif key == "thermal_debug_toggle":
            print(f"[THERMAL] Debug overlay -> {'ON' if thermal.toggle_debug_overlay() else 'OFF'}", flush=True)
        elif key == "thermal_range_toggle":
            print(f"[THERMAL] Display range -> {thermal.toggle_display_range()}", flush=True)
        elif key == "p":
            if not active_mission_id:
                print("[CAMERA] Bind an assigned mission before capturing media", flush=True)
            else:
                task = asyncio.create_task(camera.capture_for_review())
                track_background_task(task, "CAMERA")
        elif key == "r":
            if video_recorder.is_recording():
                stop_video_recording(retain=True)
            elif not active_mission_id:
                print("[VIDEO] Bind an assigned mission before recording", flush=True)
            else:
                try:
                    path = video_recorder.start_recording(active_mission_id)
                except RuntimeError as exc:
                    print(f"[VIDEO] Recording unavailable: {exc}", flush=True)
                else:
                    print(
                        f"[VIDEO] Recording started mission={active_mission_id} path={path}",
                        flush=True,
                    )
        elif key == "l":
            print("[CMD] land")
            stop_video_recording(retain=True)
            await connection_manager.stop_offboard_sender()
            active_drone = await connection_manager.get_drone()
            if active_drone is None:
                active_drone = await connection_manager.reconnect()
            if active_drone is None:
                print("[ERR] MAVSDK control bridge unavailable")
                continue
            try:
                await active_drone.offboard.stop()
            except OffboardError:
                pass
            try:
                await active_drone.action.land()
            except ActionError as exc:
                print_command_denied("land", exc)
            except grpc.aio.AioRpcError as exc:
                print_mavsdk_unavailable("land", exc)
                if is_grpc_unavailable(exc):
                    new_drone = await connection_manager.reconnect()
                    if new_drone is not None and avoidance is not None:
                        avoidance.set_drone(new_drone)
                    if new_drone is not None:
                        try:
                            await new_drone.action.land()
                        except ActionError as retry_exc:
                            print_command_denied("land", retry_exc)
                        except grpc.aio.AioRpcError as retry_exc:
                            print_mavsdk_unavailable("land retry", retry_exc)
            await wait_for_media_tasks()
        elif key == "x":
            print("[SHUTDOWN] Stopped by user")
            await cleanup_controller(retain_video=True)
            return


if __name__ == "__main__":
    configure_mavsdk_logging()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[SHUTDOWN] Stopped by user")
