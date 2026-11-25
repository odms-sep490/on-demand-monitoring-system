import asyncio
import logging
import math
import os
import socket
import subprocess
from pathlib import Path
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

import grpc
import httpx
from dotenv import load_dotenv
from mavsdk import System

from geofence_monitor import px4_ned_to_sim_xy

from sitl_battery_sim import (
    BatteryInputs,
    SitlBatterySimulator,
    battery_level,
    normalize_real_battery_percent,
)


def resolve_project_root() -> Path:
    configured = os.getenv("PROJECT_PATH")
    if configured:
        return Path(configured)

    source_candidate = Path(__file__).resolve().parent.parent
    if (source_candidate / "Forest3D").exists() and (source_candidate / "ondemandmonitoring").exists():
        return source_candidate

    return Path.cwd()


PROJECT_ROOT = resolve_project_root()
ENV_FILE = PROJECT_ROOT / "ondemandmonitoring" / ".env"
load_dotenv(ENV_FILE, override=True)

PX4_SYSTEM_ADDRESS = os.getenv("PX4_SYSTEM_ADDRESS", "udp://:14540")
MAVSDK_TELEMETRY_GRPC_PORT = int(os.getenv("MAVSDK_TELEMETRY_GRPC_PORT", "50052"))
MAVSDK_TELEMETRY_SYSID = int(os.getenv("MAVSDK_TELEMETRY_SYSID", "245"))
MAVSDK_TELEMETRY_COMPID = int(os.getenv("MAVSDK_TELEMETRY_COMPID", "191"))
BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://localhost:8080").rstrip("/")
DRONE_TELEMETRY_SECRET = os.getenv("DRONE_TELEMETRY_SECRET", "").strip()
FLIGHT_CONTROL_API_PORT = int(os.getenv("FLIGHT_CONTROL_API_PORT", "8090"))
FLIGHT_CONTROL_STATUS_URL = f"http://localhost:{FLIGHT_CONTROL_API_PORT}/api/control/status"
TELEMETRY_INTERVAL_SECONDS = float(os.getenv("TELEMETRY_INTERVAL_SECONDS", "5"))
MAVSDK_GRPC_READY_TIMEOUT_S = float(os.getenv("MAVSDK_GRPC_READY_TIMEOUT_S", "10.0"))
MAVSDK_PX4_DISCOVERY_TIMEOUT_S = float(os.getenv("MAVSDK_PX4_DISCOVERY_TIMEOUT_S", "20.0"))
MAVSDK_TELEMETRY_PROFILE = os.getenv("MAVSDK_TELEMETRY_PROFILE", "lean").strip().lower()
LOGGER = logging.getLogger("telemetry_sender")
battery_sim = SitlBatterySimulator()


@dataclass
class TelemetryState:
    battery_percent: float | None = None
    battery_level: str | None = None

    latitude: float | None = None
    longitude: float | None = None
    absolute_altitude: float | None = None
    relative_altitude: float | None = None
    local_north: float | None = None
    local_east: float | None = None
    local_down: float | None = None

    gps_fix_type: str | None = None
    gps_satellite_count: int | None = None

    gyrometer_ok: bool | None = None
    accelerometer_ok: bool | None = None
    magnetometer_ok: bool | None = None
    local_position_ok: bool | None = None
    global_position_ok: bool | None = None
    home_position_ok: bool | None = None
    armable: bool | None = None

    heading_degree: float | None = None

    velocity_north: float | None = None
    velocity_east: float | None = None
    velocity_down: float | None = None
    ground_speed: float | None = None

    armed: bool | None = None
    flight_mode: str | None = None

    home_latitude: float | None = None
    home_longitude: float | None = None
    home_absolute_altitude: float | None = None
    home_relative_altitude: float | None = None

    roll_degree: float | None = None
    pitch_degree: float | None = None
    yaw_degree: float | None = None

    connected: bool | None = None
    in_air: bool | None = None

    # MAVSDK Python exposes geofence upload/clear APIs, but no telemetry stream
    # for configured/passed state in this installed version. Keep unavailable
    # values explicit instead of faking a preflight result.
    geofence_configured: bool | None = False
    geofence_passed: bool | None = None

    lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def update(self, **values: Any) -> None:
        async with self.lock:
            for key, value in values.items():
                setattr(self, key, value)

    async def battery_inputs(self) -> BatteryInputs:
        async with self.lock:
            return BatteryInputs(
                armed=self.armed,
                in_air=self.in_air,
                velocity_north_m_s=self.velocity_north,
                velocity_east_m_s=self.velocity_east,
                velocity_down_m_s=self.velocity_down,
            )

    async def snapshot(self) -> dict[str, Any]:
        async with self.lock:
            sim_x = None
            sim_y = None
            if self.local_north is not None and self.local_east is not None:
                sim_x, sim_y = px4_ned_to_sim_xy(self.local_north, self.local_east)

            return {
                "batteryPercent": self.battery_percent,
                "batteryLevel": self.battery_level,
                "latitude": self.latitude,
                "longitude": self.longitude,
                "absoluteAltitude": self.absolute_altitude,
                "relativeAltitude": self.relative_altitude,
                "altitude": self.relative_altitude,
                "simX": sim_x,
                "simY": sim_y,
                "gpsFixType": self.gps_fix_type,
                "gpsSatelliteCount": self.gps_satellite_count,
                "gyrometerOk": self.gyrometer_ok,
                "accelerometerOk": self.accelerometer_ok,
                "magnetometerOk": self.magnetometer_ok,
                "localPositionOk": self.local_position_ok,
                "globalPositionOk": self.global_position_ok,
                "homePositionOk": self.home_position_ok,
                "armable": self.armable,
                "headingDegree": self.heading_degree,
                "velocityNorth": self.velocity_north,
                "velocityEast": self.velocity_east,
                "velocityDown": self.velocity_down,
                "groundSpeed": self.ground_speed,
                "speed": self.ground_speed,
                "armed": self.armed,
                "flightMode": self.flight_mode,
                "homeLatitude": self.home_latitude,
                "homeLongitude": self.home_longitude,
                "homeAbsoluteAltitude": self.home_absolute_altitude,
                "homeRelativeAltitude": self.home_relative_altitude,
                "rollDegree": self.roll_degree,
                "pitchDegree": self.pitch_degree,
                "yawDegree": self.yaw_degree,
                "connected": self.connected,
                "inAir": self.in_air,
                "geofenceConfigured": self.geofence_configured,
                "geofencePassed": self.geofence_passed,
            }

    async def has_backend_required_fields(self) -> bool:
        async with self.lock:
            return all(
                value is not None
                for value in (
                    self.latitude,
                    self.longitude,
                    self.relative_altitude,
                    self.armed,
                    self.battery_percent,
                )
            ) and self.connected is True


state = TelemetryState()

backend_telemetry_enabled = False
_backend_telemetry_task: asyncio.Task[None] | None = None


def bound_device_id(control_status: Any) -> str | None:
    """Use only a device identity bound to a backend-verified mission."""
    if not isinstance(control_status, dict) or not control_status.get("missionId"):
        return None
    device_id = control_status.get("deviceId")
    if not isinstance(device_id, str):
        return None
    device_id = device_id.strip()
    return device_id if 0 < len(device_id) <= 50 else None


class MavsdkAckNoiseFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        return "Received ack for not-existing command: 512" not in message


def configure_logging() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("mavsdk_server").addFilter(MavsdkAckNoiseFilter())


def clean_enum_name(value: Any) -> str | None:
    if value is None:
        return None
    name = getattr(value, "name", None)
    if name:
        return str(name)
    text = str(value)
    return text.rsplit(".", maxsplit=1)[-1]


def number_or_none(value: Any) -> float | None:
    if value is None:
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(result):
        return None
    return result


def first_attr(source: Any, *names: str) -> Any:
    for name in names:
        if hasattr(source, name):
            return getattr(source, name)
    return None


class TelemetryConnectionLost(Exception):
    pass


def is_grpc_unavailable(exc: Exception) -> bool:
    if not isinstance(exc, grpc.aio.AioRpcError):
        return False

    if exc.code() == grpc.StatusCode.UNAVAILABLE:
        return True

    details = (exc.details() or "").lower()
    return any(
        text in details
        for text in (
            "stream removed",
            "socket closed",
            "connection reset",
            "connection refused",
            "failed to connect",
        )
    )


async def wait_for_grpc_port(timeout_seconds: float = 60.0) -> bool:
    deadline = asyncio.get_running_loop().time() + timeout_seconds
    while asyncio.get_running_loop().time() < deadline:
        try:
            with socket.create_connection(("localhost", MAVSDK_TELEMETRY_GRPC_PORT), timeout=0.5):
                return True
        except OSError:
            await asyncio.sleep(0.5)
    return False


def new_mavsdk_system() -> System:
    return System(
        mavsdk_server_address="localhost",
        port=MAVSDK_TELEMETRY_GRPC_PORT,
        sysid=MAVSDK_TELEMETRY_SYSID,
        compid=MAVSDK_TELEMETRY_COMPID,
    )


async def run_stream(name: str, stream: Callable[[], Any], handler: Callable[[Any], Awaitable[None]]) -> None:
    try:
        async for item in stream():
            await handler(item)
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        if is_grpc_unavailable(exc):
            raise TelemetryConnectionLost(name) from exc
        LOGGER.warning("%s stream failed; restarting telemetry generation", name)
        raise TelemetryConnectionLost(name) from exc


async def connect_px4(drone: System, generation: int) -> None:
    LOGGER.info("[MAVSDK-TELEMETRY] waiting for server localhost:%s", MAVSDK_TELEMETRY_GRPC_PORT)
    while not await wait_for_grpc_port(MAVSDK_GRPC_READY_TIMEOUT_S):
        LOGGER.warning("[MAVSDK-TELEMETRY] server unavailable on localhost:%s", MAVSDK_TELEMETRY_GRPC_PORT)

    LOGGER.info("[MAVSDK-TELEMETRY] server available")
    await drone.connect()

    async with asyncio.timeout(MAVSDK_PX4_DISCOVERY_TIMEOUT_S):
        async for connection_state in drone.core.connection_state():
            await state.update(connected=connection_state.is_connected)
            if connection_state.is_connected:
                LOGGER.info("[MAVSDK-TELEMETRY] PX4 reconnected generation=%s", generation)
                return


async def configure_telemetry_rates(drone: System) -> None:
    rate_calls = (
        ("position", drone.telemetry.set_rate_position, 5.0),
        ("battery", drone.telemetry.set_rate_battery, 1.0),
        ("attitude", drone.telemetry.set_rate_attitude_euler, 5.0),
        ("velocity", drone.telemetry.set_rate_velocity_ned, 5.0),
    )

    for name, setter, rate_hz in rate_calls:
        try:
            await setter(rate_hz)
        except Exception:
            LOGGER.debug("Could not set %s telemetry rate", name, exc_info=True)


async def watch_connection(drone: System) -> None:
    async def handle(connection_state: Any) -> None:
        await state.update(connected=connection_state.is_connected)

    await run_stream("connection", drone.core.connection_state, handle)


async def watch_battery(drone: System) -> None:
    async def handle(battery: Any) -> None:
        real_percent = normalize_real_battery_percent(number_or_none(battery.remaining_percent))
        if not battery_sim.enabled:
            await state.update(
                battery_percent=round(real_percent, 2) if real_percent is not None else None,
                battery_level=battery_level(real_percent),
            )

    await run_stream("battery", drone.telemetry.battery, handle)


async def watch_sitl_battery_sim() -> None:
    while True:
        snapshot = battery_sim.update(await state.battery_inputs())
        if snapshot is not None:
            await state.update(
                battery_percent=snapshot.percent,
                battery_level=snapshot.level,
            )
        await asyncio.sleep(0.5)


async def watch_position(drone: System) -> None:
    async def handle(position: Any) -> None:
        await state.update(
            latitude=number_or_none(position.latitude_deg),
            longitude=number_or_none(position.longitude_deg),
            absolute_altitude=number_or_none(position.absolute_altitude_m),
            relative_altitude=number_or_none(position.relative_altitude_m),
        )

    await run_stream("position", drone.telemetry.position, handle)


async def watch_gps_info(drone: System) -> None:
    async def handle(gps_info: Any) -> None:
        await state.update(
            gps_fix_type=clean_enum_name(gps_info.fix_type),
            gps_satellite_count=gps_info.num_satellites,
        )

    await run_stream("gps_info", drone.telemetry.gps_info, handle)


async def watch_health(drone: System) -> None:
    async def handle(health: Any) -> None:
        await state.update(
            gyrometer_ok=first_attr(health, "is_gyrometer_calibration_ok"),
            accelerometer_ok=first_attr(health, "is_accelerometer_calibration_ok"),
            magnetometer_ok=first_attr(health, "is_magnetometer_calibration_ok"),
            local_position_ok=first_attr(health, "is_local_position_ok"),
            global_position_ok=first_attr(health, "is_global_position_ok"),
            home_position_ok=first_attr(health, "is_home_position_ok"),
            armable=first_attr(health, "is_armable"),
        )

    await run_stream("health", drone.telemetry.health, handle)


async def watch_heading(drone: System) -> None:
    async def handle(heading: Any) -> None:
        await state.update(heading_degree=number_or_none(heading.heading_deg))

    await run_stream("heading", drone.telemetry.heading, handle)


async def watch_velocity(drone: System) -> None:
    async def handle(velocity: Any) -> None:
        north = number_or_none(velocity.north_m_s)
        east = number_or_none(velocity.east_m_s)
        down = number_or_none(velocity.down_m_s)
        ground_speed = None
        if north is not None and east is not None:
            ground_speed = math.sqrt(north**2 + east**2)

        await state.update(
            velocity_north=north,
            velocity_east=east,
            velocity_down=down,
            ground_speed=round(ground_speed, 3) if ground_speed is not None else None,
        )

    await run_stream("velocity_ned", drone.telemetry.velocity_ned, handle)


async def watch_local_position_velocity(drone: System) -> None:
    async def handle(sample: Any) -> None:
        position = sample.position
        velocity = sample.velocity
        north = number_or_none(position.north_m)
        east = number_or_none(position.east_m)
        down = number_or_none(position.down_m)
        velocity_north = number_or_none(velocity.north_m_s)
        velocity_east = number_or_none(velocity.east_m_s)
        velocity_down = number_or_none(velocity.down_m_s)
        ground_speed = None
        if velocity_north is not None and velocity_east is not None:
            ground_speed = math.sqrt(velocity_north**2 + velocity_east**2)

        await state.update(
            local_north=north,
            local_east=east,
            local_down=down,
            velocity_north=velocity_north,
            velocity_east=velocity_east,
            velocity_down=velocity_down,
            ground_speed=round(ground_speed, 3) if ground_speed is not None else None,
        )

    await run_stream("position_velocity_ned", drone.telemetry.position_velocity_ned, handle)


async def watch_armed(drone: System) -> None:
    async def handle(armed: bool) -> None:
        await state.update(armed=armed)

    await run_stream("armed", drone.telemetry.armed, handle)


async def watch_flight_mode(drone: System) -> None:
    async def handle(flight_mode: Any) -> None:
        await state.update(flight_mode=clean_enum_name(flight_mode))

    await run_stream("flight_mode", drone.telemetry.flight_mode, handle)


async def watch_home(drone: System) -> None:
    async def handle(home: Any) -> None:
        await state.update(
            home_latitude=number_or_none(home.latitude_deg),
            home_longitude=number_or_none(home.longitude_deg),
            home_absolute_altitude=number_or_none(home.absolute_altitude_m),
            home_relative_altitude=number_or_none(first_attr(home, "relative_altitude_m")),
        )

    await run_stream("home", drone.telemetry.home, handle)


async def watch_attitude(drone: System) -> None:
    async def handle(attitude: Any) -> None:
        await state.update(
            roll_degree=number_or_none(attitude.roll_deg),
            pitch_degree=number_or_none(attitude.pitch_deg),
            yaw_degree=number_or_none(attitude.yaw_deg),
        )

    await run_stream("attitude_euler", drone.telemetry.attitude_euler, handle)


async def watch_in_air(drone: System) -> None:
    async def handle(in_air: bool) -> None:
        await state.update(in_air=in_air)

    await run_stream("in_air", drone.telemetry.in_air, handle)


async def send_telemetry() -> None:
    if not backend_telemetry_enabled:
        return

    candidates = [BACKEND_BASE_URL]
    if BACKEND_BASE_URL in {"http://localhost:8080", "http://127.0.0.1:8080"}:
        try:
            route = subprocess.run(
                ["ip", "route", "show", "default"],
                capture_output=True, text=True, timeout=1.0, check=True,
            ).stdout.split()
            if "via" in route:
                candidates.append(f"http://{route[route.index('via') + 1]}:8080")
        except (OSError, subprocess.SubprocessError, IndexError):
            pass
    timeout = httpx.Timeout(5.0)
    sent_count = 0

    LOGGER.info("Waiting for a backend-verified mission binding before publishing telemetry")
    async with httpx.AsyncClient(timeout=timeout) as client:
        last_device_id = None
        while backend_telemetry_enabled:
            try:
                status_response = await client.get(FLIGHT_CONTROL_STATUS_URL)
                status_response.raise_for_status()
                control_status = status_response.json()
                device_id = bound_device_id(control_status)
                mission_id = str(control_status.get("missionId") or "") if device_id else ""
            except (httpx.HTTPError, ValueError, TypeError, AttributeError):
                device_id = None

            if not device_id:
                if last_device_id is not None:
                    LOGGER.warning("Mission binding unavailable; pausing backend telemetry")
                    last_device_id = None
                await asyncio.sleep(TELEMETRY_INTERVAL_SECONDS)
                continue
            if device_id != last_device_id:
                LOGGER.info("Publishing telemetry for mission %s, device %s", mission_id, device_id)
                last_device_id = device_id
            if not await state.has_backend_required_fields():
                await asyncio.sleep(TELEMETRY_INTERVAL_SECONDS)
                continue

            payload = await state.snapshot()
            payload["missionId"] = mission_id
            response = None
            for base_url in candidates:
                try:
                    response = await client.post(
                        f"{base_url}/api/internal/v1/drone-telemetry/{device_id}",
                        json=payload,
                        headers={"X-Drone-Telemetry-Secret": DRONE_TELEMETRY_SECRET},
                    )
                    if 200 <= response.status_code < 300:
                        candidates.remove(base_url)
                        candidates.insert(0, base_url)
                    break
                except httpx.HTTPError:
                    continue
            if response is None:
                LOGGER.warning("Backend unavailable; retrying telemetry on next interval")
            else:
                if 200 <= response.status_code < 300:
                    sent_count += 1
                    if sent_count == 1 or sent_count % 30 == 0:
                        LOGGER.info("Telemetry updated successfully, HTTP %s", response.status_code)
                else:
                    LOGGER.warning("Backend rejected telemetry, HTTP %s: %s", response.status_code, response.text[:500])

            await asyncio.sleep(TELEMETRY_INTERVAL_SECONDS)


def start_backend_telemetry() -> asyncio.Task[None]:
    """Publish authenticated live telemetry to Backend."""
    global _backend_telemetry_task, backend_telemetry_enabled
    backend_telemetry_enabled = True
    if _backend_telemetry_task is None or _backend_telemetry_task.done():
        _backend_telemetry_task = asyncio.create_task(send_telemetry(), name="backend-telemetry")
    return _backend_telemetry_task


async def stop_backend_telemetry() -> None:
    """Stop Backend telemetry without affecting MAVSDK flight telemetry."""
    global _backend_telemetry_task, backend_telemetry_enabled
    backend_telemetry_enabled = False
    if _backend_telemetry_task is not None:
        _backend_telemetry_task.cancel()
        await asyncio.gather(_backend_telemetry_task, return_exceptions=True)
        _backend_telemetry_task = None


async def run_telemetry_stream_generation(drone: System, generation: int) -> None:
    await configure_telemetry_rates(drone)
    LOGGER.info("[TELEMETRY] streams restarted generation=%s profile=%s", generation, MAVSDK_TELEMETRY_PROFILE)

    tasks = [
        asyncio.create_task(watch_connection(drone), name=f"connection-{generation}"),
        asyncio.create_task(watch_battery(drone), name=f"battery-{generation}"),
        asyncio.create_task(watch_position(drone), name=f"position-{generation}"),
        asyncio.create_task(watch_gps_info(drone), name=f"gps_info-{generation}"),
        asyncio.create_task(watch_health(drone), name=f"health-{generation}"),
        asyncio.create_task(watch_local_position_velocity(drone), name=f"position_velocity_ned-{generation}"),
        asyncio.create_task(watch_armed(drone), name=f"armed-{generation}"),
        asyncio.create_task(watch_in_air(drone), name=f"in_air-{generation}"),
    ]
    if MAVSDK_TELEMETRY_PROFILE == "full":
        tasks.extend(
            [
                asyncio.create_task(watch_heading(drone), name=f"heading-{generation}"),
                asyncio.create_task(watch_flight_mode(drone), name=f"flight_mode-{generation}"),
                asyncio.create_task(watch_home(drone), name=f"home-{generation}"),
                asyncio.create_task(watch_attitude(drone), name=f"attitude_euler-{generation}"),
            ]
        )

    try:
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_EXCEPTION)
        for task in done:
            exc = task.exception()
            if exc is not None:
                raise exc
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def telemetry_supervisor() -> None:
    generation = 0
    backoff_s = 1.0

    while True:
        generation += 1
        drone = new_mavsdk_system()

        try:
            await connect_px4(drone, generation)
            backoff_s = 1.0
            await run_telemetry_stream_generation(drone, generation)
        except asyncio.CancelledError:
            raise
        except TelemetryConnectionLost as exc:
            await state.update(connected=False)
            LOGGER.warning("[MAVSDK-TELEMETRY] connection lost (%s)", exc)
        except grpc.aio.AioRpcError as exc:
            await state.update(connected=False)
            if is_grpc_unavailable(exc):
                LOGGER.warning("[MAVSDK-TELEMETRY] connection lost")
            else:
                LOGGER.warning("[MAVSDK-TELEMETRY] gRPC error; reconnecting")
        except Exception:
            await state.update(connected=False)
            LOGGER.warning("[MAVSDK-TELEMETRY] telemetry generation failed; reconnecting", exc_info=True)

        LOGGER.info("[MAVSDK-TELEMETRY] waiting %.1fs before reconnect", backoff_s)
        await asyncio.sleep(backoff_s)
        backoff_s = min(backoff_s * 1.5, 10.0)


async def main() -> None:
    LOGGER.info("========================================")
    LOGGER.info(" On-Demand Monitoring Telemetry Sender")
    LOGGER.info("========================================")
    LOGGER.info("Drone identity: mission assignment via Flight Controller %s", FLIGHT_CONTROL_STATUS_URL)
    LOGGER.info("PX4: %s", PX4_SYSTEM_ADDRESS)
    LOGGER.info("MAVSDK gRPC shared server: localhost:%s", MAVSDK_TELEMETRY_GRPC_PORT)
    LOGGER.info("Backend: %s", BACKEND_BASE_URL)
    if DRONE_TELEMETRY_SECRET:
        LOGGER.info("[TELEMETRY-BE] Publishing authenticated telemetry")
        start_backend_telemetry()
    else:
        LOGGER.warning("[TELEMETRY-BE] Disabled: DRONE_TELEMETRY_SECRET is not configured")
    if battery_sim.enabled:
        LOGGER.info(
            "SITL battery simulation enabled: start %.1f%%, min %.1f%%, state %s",
            battery_sim.start_percent,
            battery_sim.min_percent,
            battery_sim.state_path,
        )

    await asyncio.gather(
        telemetry_supervisor(),
        watch_sitl_battery_sim(),
    )


if __name__ == "__main__":
    configure_logging()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        LOGGER.info("Stopped by user")
