from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import json
import math
import os
import threading
import time
from collections.abc import Iterable
from typing import Any
from urllib.error import URLError
from urllib.request import urlopen

import numpy as np
from PIL import Image as PilImage, ImageDraw


THERMAL_WORLD_NAME = os.getenv("THERMAL_WORLD_NAME", "forest_monitoring_compact")
THERMAL_COORDINATE_SYSTEM = os.getenv(
    "THERMAL_COORDINATE_SYSTEM",
    "LOCAL_SIMULATION_METERS_GAZEBO_XY",
)
THERMAL_SOURCES_PATH = os.getenv("THERMAL_SOURCES_PATH", "/api/thermal-sources")
THERMAL_AMBIENT_TEMP_C = float(os.getenv("THERMAL_AMBIENT_TEMP_C", "28"))
THERMAL_HOTSPOT_THRESHOLD_C = float(os.getenv("THERMAL_HOTSPOT_THRESHOLD_C", "60"))
THERMAL_SOURCE_REFRESH_S = float(os.getenv("THERMAL_SOURCE_REFRESH_S", "10"))
THERMAL_SOURCE_TIMEOUT_S = float(os.getenv("THERMAL_SOURCE_TIMEOUT_S", "0.35"))
THERMAL_FRAME_FPS = float(os.getenv("THERMAL_FRAME_FPS", "6"))
THERMAL_CAMERA_FOV_RAD = float(os.getenv("THERMAL_CAMERA_FOV_RAD", "1.74"))
THERMAL_FRAME_WIDTH = int(os.getenv("THERMAL_FRAME_WIDTH", "640"))
THERMAL_FRAME_HEIGHT = int(os.getenv("THERMAL_FRAME_HEIGHT", "480"))
THERMAL_LINEAR_RESOLUTION_K = float(os.getenv("THERMAL_LINEAR_RESOLUTION_K", "0.01"))
THERMAL_NATIVE_STALE_S = float(os.getenv("THERMAL_NATIVE_STALE_S", "1.5"))
THERMAL_DISPLAY_MIN_C = float(os.getenv("THERMAL_DISPLAY_MIN_C", "20"))
THERMAL_DISPLAY_MAX_C = float(os.getenv("THERMAL_DISPLAY_MAX_C", "300"))
THERMAL_PALETTES = ("IRON", "WHITE_HOT", "BLACK_HOT", "RAINBOW")


def thermal_statistics(temperatures_c: np.ndarray) -> tuple[float, float, float]:
    values = np.asarray(temperatures_c, dtype=np.float32)
    if values.size == 0:
        raise ValueError("thermal frame is empty")
    return float(values.min()), float(values.mean()), float(values.max())


def hottest_pixel(temperatures_c: np.ndarray) -> tuple[int, int, float]:
    values = np.asarray(temperatures_c, dtype=np.float32)
    if values.size == 0:
        raise ValueError("thermal frame is empty")
    y, x = np.unravel_index(int(np.argmax(values)), values.shape)
    return int(x), int(y), float(values[y, x])


def _normalize_temperatures(temperatures_c: np.ndarray, minimum_c: float, maximum_c: float) -> np.ndarray:
    span = max(0.01, maximum_c - minimum_c)
    return np.clip((temperatures_c.astype(np.float32) - minimum_c) / span, 0.0, 1.0)


def apply_thermal_palette(normalized: np.ndarray, palette: str) -> np.ndarray:
    value = np.clip(np.asarray(normalized, dtype=np.float32), 0.0, 1.0)
    name = palette.strip().upper()
    if name == "WHITE_HOT":
        gray = np.rint(value * 255).astype(np.uint8)
        return np.repeat(gray[..., None], 3, axis=2)
    if name == "BLACK_HOT":
        gray = np.rint((1.0 - value) * 255).astype(np.uint8)
        return np.repeat(gray[..., None], 3, axis=2)

    if name == "RAINBOW":
        stops = np.array([
            [0.0, 10, 20, 90],
            [0.25, 0, 180, 255],
            [0.5, 30, 210, 90],
            [0.75, 255, 225, 0],
            [1.0, 220, 20, 10],
        ], dtype=np.float32)
    else:
        stops = np.array([
            [0.0, 0, 0, 4],
            [0.22, 35, 8, 78],
            [0.46, 125, 24, 105],
            [0.66, 215, 48, 38],
            [0.84, 252, 145, 28],
            [1.0, 255, 255, 235],
        ], dtype=np.float32)

    flat = value.ravel()
    channels = [np.interp(flat, stops[:, 0], stops[:, channel]) for channel in (1, 2, 3)]
    return np.stack(channels, axis=1).reshape((*value.shape, 3)).astype(np.uint8)


@dataclass(frozen=True)
class ThermalSource:
    id: str
    code: str
    name: str
    center_x_m: float
    center_y_m: float
    radius_m: float
    temperature_c: float
    active: bool
    source_world: str
    coordinate_system: str


def _finite_float(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _source_from_api(item: dict[str, Any]) -> ThermalSource | None:
    center_x = _finite_float(item.get("centerXM"))
    center_y = _finite_float(item.get("centerYM"))
    radius = _finite_float(item.get("radiusM"))
    temperature = _finite_float(item.get("temperatureC"))
    if center_x is None or center_y is None or radius is None or temperature is None:
        return None
    return ThermalSource(
        id=str(item.get("id") or item.get("code") or ""),
        code=str(item.get("code") or ""),
        name=str(item.get("name") or item.get("code") or "Thermal source"),
        center_x_m=center_x,
        center_y_m=center_y,
        radius_m=max(0.0, radius),
        temperature_c=temperature,
        active=bool(item.get("active", True)),
        source_world=str(item.get("sourceWorld") or ""),
        coordinate_system=str(item.get("coordinateSystem") or ""),
    )


class ThermalCameraGateway:
    """DB-backed thermal adapter for the compact Gazebo simulation.

    Gazebo provides the drone pose and camera flow; the Spring Boot thermal
    source table remains the ground truth for simulated heat. The adapter only
    reports sources inside the downward camera footprint, so a hotspot is not
    visible from the whole map.
    """

    def __init__(
        self,
        backend_base_url: str | Iterable[str],
        *,
        clock=time.monotonic,
    ) -> None:
        configured_urls = [backend_base_url] if isinstance(backend_base_url, str) else list(backend_base_url)
        self.backend_base_urls = tuple(dict.fromkeys(url.rstrip("/") for url in configured_urls if url))
        if not self.backend_base_urls:
            raise ValueError("at least one backend base URL is required")
        self.clock = clock
        self.enabled = False
        self._lock = threading.Lock()
        self._sources: list[ThermalSource] = []
        self._last_source_refresh_s = 0.0
        self._source_online = False
        self._source_error: str | None = None
        self._sim_x_m = 0.0
        self._sim_y_m = 0.0
        self._altitude_m = 0.0
        self._last_measurement_s: float | None = None
        self._last_frame_s = 0.0
        self._last_jpeg: bytes | None = None
        self._processing_generation = 0
        self._min_temp_c: float | None = None
        self._max_temp_c: float | None = None
        self._avg_temp_c: float | None = None
        self._hotspot_temp_c: float | None = None
        self._hotspot_sim_x: float | None = None
        self._hotspot_sim_y: float | None = None
        self._native_temperatures_c: np.ndarray | None = None
        self._native_frame_time_s: float | None = None
        self._native_width = 0
        self._native_height = 0
        self._native_format: str | None = None
        self._palette = "IRON"
        self._isotherm_enabled = False
        self._debug_overlay_enabled = False
        self._display_range_mode = "FIXED"
        self._auto_display_range: tuple[float, float] | None = None
        self._thermal_mode = "SYNTHETIC_THERMAL"

    @property
    def processing_generation(self) -> int:
        with self._lock:
            return self._processing_generation

    def set_enabled(self, enabled: bool) -> None:
        with self._lock:
            if self.enabled == enabled:
                return
            self.enabled = enabled
            self._processing_generation += 1
            if not enabled:
                self._min_temp_c = None
                self._max_temp_c = None
                self._avg_temp_c = None
                self._hotspot_temp_c = None
                self._hotspot_sim_x = None
                self._hotspot_sim_y = None
                self._last_measurement_s = None
                self._last_jpeg = None
                self._thermal_mode = "SYNTHETIC_THERMAL"

    def toggle(self) -> bool:
        with self._lock:
            next_enabled = not self.enabled
        self.set_enabled(next_enabled)
        return next_enabled

    def update_pose(self, sim_x_m: float, sim_y_m: float, altitude_m: float) -> None:
        with self._lock:
            self._sim_x_m = sim_x_m
            self._sim_y_m = sim_y_m
            self._altitude_m = max(0.0, altitude_m)

    def ingest_native_frame(
        self,
        width: int,
        height: int,
        data: bytes,
        pixel_format: str,
        linear_resolution_k: float = THERMAL_LINEAR_RESOLUTION_K,
    ) -> bool:
        if width <= 0 or height <= 0 or len(data) < width * height * 2:
            return False
        format_name = str(pixel_format).upper()
        if format_name not in {"L_INT16", "L16"}:
            return False
        raw = np.frombuffer(data, dtype="<u2", count=width * height).reshape((height, width))
        temperatures_c = raw.astype(np.float32) * linear_resolution_k - 273.15
        with self._lock:
            self._native_temperatures_c = temperatures_c.copy()
            self._native_frame_time_s = self.clock()
            self._native_width = width
            self._native_height = height
            self._native_format = format_name
            self._last_jpeg = None
        return True

    def cycle_palette(self) -> str:
        with self._lock:
            index = (THERMAL_PALETTES.index(self._palette) + 1) % len(THERMAL_PALETTES)
            self._palette = THERMAL_PALETTES[index]
            self._last_jpeg = None
            return self._palette

    def toggle_isotherm(self) -> bool:
        with self._lock:
            self._isotherm_enabled = not self._isotherm_enabled
            self._last_jpeg = None
            return self._isotherm_enabled

    def toggle_debug_overlay(self) -> bool:
        with self._lock:
            self._debug_overlay_enabled = not self._debug_overlay_enabled
            self._last_jpeg = None
            return self._debug_overlay_enabled

    def toggle_display_range(self) -> str:
        with self._lock:
            self._display_range_mode = "AUTO" if self._display_range_mode == "FIXED" else "FIXED"
            self._last_jpeg = None
            return self._display_range_mode

    def _native_frame_fresh_locked(self) -> bool:
        return (
            self._native_temperatures_c is not None
            and self._native_frame_time_s is not None
            and self.clock() - self._native_frame_time_s <= THERMAL_NATIVE_STALE_S
        )

    def _refresh_sources_if_needed(self) -> None:
        now_s = self.clock()
        with self._lock:
            if now_s - self._last_source_refresh_s < THERMAL_SOURCE_REFRESH_S:
                return
            self._last_source_refresh_s = now_s

        sources: list[ThermalSource] | None = None
        last_error: Exception | None = None
        for backend_base_url in self.backend_base_urls:
            url = f"{backend_base_url}{THERMAL_SOURCES_PATH}"
            try:
                with urlopen(url, timeout=THERMAL_SOURCE_TIMEOUT_S) as response:
                    payload = json.loads(response.read().decode("utf-8"))
                data = payload.get("data", payload) if isinstance(payload, dict) else payload
                if not isinstance(data, list):
                    raise ValueError("thermal source API did not return a list")
                sources = [
                    source
                    for item in data
                    if isinstance(item, dict)
                    for source in [_source_from_api(item)]
                    if source is not None
                ]
                break
            except (OSError, URLError, ValueError, json.JSONDecodeError) as exc:
                last_error = exc

        if sources is None:
            with self._lock:
                # Cached source data remains usable during a short backend
                # outage, so don't downgrade a working thermal sensor.
                self._source_online = bool(self._sources)
                self._source_error = None if self._sources else str(last_error or "backend unavailable")
            return

        with self._lock:
            self._sources = sources
            self._source_online = True
            self._source_error = None

    def _active_world_sources(self) -> list[ThermalSource]:
        return [
            source
            for source in self._sources
            if source.active
            and source.source_world == THERMAL_WORLD_NAME
            and source.coordinate_system == THERMAL_COORDINATE_SYSTEM
        ]

    def _footprint_radius_m(self, altitude_m: float) -> float:
        # Downward thermal camera footprint; use a small floor for near-ground
        # SITL moments where altitude may still be stabilizing.
        return max(8.0, altitude_m * math.tan(THERMAL_CAMERA_FOV_RAD / 2.0))

    def _measure_locked(self) -> tuple[float, float, float, float | None, float | None, float | None, bool]:
        sources = self._active_world_sources()
        footprint_radius = self._footprint_radius_m(self._altitude_m)
        visible: list[tuple[ThermalSource, float]] = []
        for source in sources:
            distance = math.hypot(self._sim_x_m - source.center_x_m, self._sim_y_m - source.center_y_m)
            visible_margin = source.radius_m + footprint_radius
            if distance <= visible_margin:
                weight = max(0.05, 1.0 - (distance / max(1.0, visible_margin)))
                visible.append((source, weight))

        if not visible:
            return (
                THERMAL_AMBIENT_TEMP_C,
                THERMAL_AMBIENT_TEMP_C,
                THERMAL_AMBIENT_TEMP_C,
                None,
                None,
                None,
                False,
            )

        weighted_sum = sum(source.temperature_c * weight for source, weight in visible)
        total_weight = sum(weight for _source, weight in visible)
        avg_temp = weighted_sum / max(total_weight, 1e-9)
        hottest = max(visible, key=lambda item: item[0].temperature_c)[0]
        return (
            THERMAL_AMBIENT_TEMP_C,
            avg_temp,
            hottest.temperature_c,
            hottest.temperature_c,
            hottest.center_x_m,
            hottest.center_y_m,
            True,
        )

    def update(self) -> None:
        # Thermal input now comes from the native sensor or the local fallback.
        # Do not poll the retired /api/thermal-sources endpoint.
        with self._lock:
            if not self.enabled:
                return
            min_temp, avg_temp, max_temp, hotspot_temp, hotspot_x, hotspot_y, has_db_source = self._measure_locked()
            if self._native_frame_fresh_locked() and not has_db_source:
                minimum, average, maximum = thermal_statistics(self._native_temperatures_c)
                self._min_temp_c = minimum
                self._avg_temp_c = average
                self._max_temp_c = maximum
                self._hotspot_temp_c = maximum if maximum >= THERMAL_HOTSPOT_THRESHOLD_C else None
                self._hotspot_sim_x = None
                self._hotspot_sim_y = None
                self._last_measurement_s = self._native_frame_time_s
                self._thermal_mode = "NATIVE_GAZEBO"
                return
            self._min_temp_c = min_temp
            self._avg_temp_c = avg_temp
            self._max_temp_c = max_temp
            if max_temp >= THERMAL_HOTSPOT_THRESHOLD_C:
                self._hotspot_temp_c = hotspot_temp
                self._hotspot_sim_x = hotspot_x
                self._hotspot_sim_y = hotspot_y
            else:
                self._hotspot_temp_c = None
                self._hotspot_sim_x = None
                self._hotspot_sim_y = None
            self._last_measurement_s = self.clock()
            self._thermal_mode = "DB_THERMAL_SOURCE" if has_db_source else "SYNTHETIC_THERMAL"

    def status(self) -> dict[str, Any]:
        self.update()
        with self._lock:
            frame_age_ms = None
            active_frame_time = (
                self._native_frame_time_s
                if self._thermal_mode == "NATIVE_GAZEBO"
                else self._last_measurement_s
            )
            if active_frame_time is not None:
                frame_age_ms = round((self.clock() - active_frame_time) * 1000)
            native_active = self._native_frame_fresh_locked()
            minimum = self._min_temp_c if self.enabled else None
            hotspot_detected = (
                self.enabled
                and self._max_temp_c is not None
                and self._max_temp_c >= THERMAL_HOTSPOT_THRESHOLD_C
            )
            return {
                "thermalEnabled": self.enabled,
                "thermalSensorOnline": native_active or self._source_online,
                "thermalFrameAgeMs": frame_age_ms if self.enabled else None,
                "thermalFps": round(THERMAL_FRAME_FPS, 1),
                "thermalFrameWidth": self._native_width or THERMAL_FRAME_WIDTH,
                "thermalFrameHeight": self._native_height or THERMAL_FRAME_HEIGHT,
                "minTemperatureC": round(minimum, 1) if minimum is not None else None,
                "maxTemperatureC": round(self._max_temp_c, 1) if self.enabled and self._max_temp_c is not None else None,
                "averageTemperatureC": round(self._avg_temp_c, 1) if self.enabled and self._avg_temp_c is not None else None,
                "thermalThresholdC": round(THERMAL_HOTSPOT_THRESHOLD_C, 1),
                "hotspotDetected": bool(hotspot_detected),
                "hotspotTemperatureC": round(self._hotspot_temp_c, 1) if hotspot_detected and self._hotspot_temp_c is not None else None,
                "hotspotSimX": round(self._hotspot_sim_x, 2) if hotspot_detected and self._hotspot_sim_x is not None else None,
                "hotspotSimY": round(self._hotspot_sim_y, 2) if hotspot_detected and self._hotspot_sim_y is not None else None,
                "thermalSourceError": self._source_error,
                "thermalProcessingGeneration": self._processing_generation,
                "thermalMode": self._thermal_mode,
                "thermalPixelFormat": self._native_format,
                "thermalPalette": self._palette,
                "thermalIsothermEnabled": self._isotherm_enabled,
                "thermalDebugOverlayEnabled": self._debug_overlay_enabled,
                "thermalDisplayRangeMode": self._display_range_mode,
                "thermalDisplayMinC": round(THERMAL_DISPLAY_MIN_C, 1),
                "thermalDisplayMaxC": round(THERMAL_DISPLAY_MAX_C, 1),
            }

    def latest_jpeg(self) -> bytes | None:
        self.update()
        with self._lock:
            if not self.enabled:
                return None
            now_s = self.clock()
            if self._last_jpeg is not None and now_s - self._last_frame_s < 1.0 / max(1.0, THERMAL_FRAME_FPS):
                return self._last_jpeg
            native_temperatures = (
                self._native_temperatures_c.copy()
                if self._native_frame_fresh_locked() and self._thermal_mode == "NATIVE_GAZEBO"
                else None
            )
            max_temp = self._max_temp_c if self._max_temp_c is not None else THERMAL_AMBIENT_TEMP_C
            avg_temp = self._avg_temp_c if self._avg_temp_c is not None else THERMAL_AMBIENT_TEMP_C
            hotspot = max_temp >= THERMAL_HOTSPOT_THRESHOLD_C
            palette = self._palette
            isotherm_enabled = self._isotherm_enabled
            debug_overlay_enabled = self._debug_overlay_enabled
            display_range_mode = self._display_range_mode

        if native_temperatures is not None:
            image = self._render_temperature_frame(
                native_temperatures,
                palette,
                isotherm_enabled,
                debug_overlay_enabled,
                display_range_mode,
            )
        else:
            image = self._render_synthetic_fallback(max_temp, avg_temp, hotspot, palette)
        output = BytesIO()
        image.save(output, format="JPEG", quality=84, optimize=False)
        jpeg = output.getvalue()
        with self._lock:
            self._last_jpeg = jpeg
            self._last_frame_s = self.clock()
        return jpeg

    def _display_range(self, temperatures_c: np.ndarray, mode: str) -> tuple[float, float]:
        if mode == "FIXED":
            return THERMAL_DISPLAY_MIN_C, THERMAL_DISPLAY_MAX_C
        target = (float(np.percentile(temperatures_c, 2)), float(np.percentile(temperatures_c, 98)))
        if target[1] - target[0] < 5.0:
            target = (target[0] - 2.5, target[1] + 2.5)
        with self._lock:
            previous = self._auto_display_range
            smoothed = target if previous is None else (
                previous[0] * 0.85 + target[0] * 0.15,
                previous[1] * 0.85 + target[1] * 0.15,
            )
            self._auto_display_range = smoothed
        return smoothed

    def _render_temperature_frame(
        self,
        temperatures_c: np.ndarray,
        palette: str,
        isotherm_enabled: bool,
        debug_overlay_enabled: bool,
        display_range_mode: str,
    ) -> PilImage.Image:
        display_min, display_max = self._display_range(temperatures_c, display_range_mode)
        normalized = _normalize_temperatures(temperatures_c, display_min, display_max)
        rgb = apply_thermal_palette(normalized, palette)
        if isotherm_enabled:
            hot_mask = temperatures_c >= THERMAL_HOTSPOT_THRESHOLD_C
            rgb[hot_mask] = np.array([255, 245, 80], dtype=np.uint8)
        image = PilImage.fromarray(rgb, mode="RGB")
        draw = ImageDraw.Draw(image)
        x, y, maximum = hottest_pixel(temperatures_c)
        if maximum >= THERMAL_HOTSPOT_THRESHOLD_C:
            marker_radius = 7
            draw.ellipse((x - marker_radius, y - marker_radius, x + marker_radius, y + marker_radius), outline=(255, 255, 255), width=2)
            draw.line((x - 11, y, x + 11, y), fill=(255, 255, 255), width=1)
            draw.line((x, y - 11, x, y + 11), fill=(255, 255, 255), width=1)
            label_x = min(image.width - 75, x + 12)
            label_y = max(4, y - 18)
            draw.text((label_x, label_y), f"{maximum:.1f} C", fill=(255, 255, 255))
        if debug_overlay_enabled:
            draw.rectangle((1, 1, image.width - 2, image.height - 2), outline=(80, 220, 255), width=1)
            draw.text((8, image.height - 18), f"RANGE {display_min:.1f}..{display_max:.1f} C", fill=(160, 235, 255))
        draw.text((12, 10), "THERMAL", fill=(235, 240, 255))
        return image

    def _render_synthetic_fallback(self, max_temp: float, avg_temp: float, hotspot: bool, palette: str) -> PilImage.Image:
        width = max(160, THERMAL_FRAME_WIDTH)
        height = max(120, THERMAL_FRAME_HEIGHT)
        temperatures = np.full((height, width), THERMAL_AMBIENT_TEMP_C, dtype=np.float32)
        if hotspot:
            yy, xx = np.ogrid[:height, :width]
            cx, cy = width * 0.53, height * 0.48
            irregular = ((xx - cx) / (width * 0.10)) ** 2 + ((yy - cy) / (height * 0.17)) ** 2
            texture = np.sin(xx * 0.13) * np.cos(yy * 0.09) * 0.16
            mask = irregular + texture < 1.0
            temperatures[mask] = max_temp - np.clip(irregular[mask], 0, 1) * max(0.0, max_temp - avg_temp)
        normalized = _normalize_temperatures(temperatures, THERMAL_DISPLAY_MIN_C, THERMAL_DISPLAY_MAX_C)
        image = PilImage.fromarray(apply_thermal_palette(normalized, palette), mode="RGB")
        draw = ImageDraw.Draw(image)
        if hotspot:
            x, y, maximum = hottest_pixel(temperatures)
            draw.ellipse((x - 7, y - 7, x + 7, y + 7), outline=(255, 255, 255), width=2)
            draw.text((x + 12, y - 16), f"{maximum:.1f} C", fill=(255, 255, 255))
        draw.text((12, height - 20), "SYNTHETIC THERMAL", fill=(190, 225, 255))
        draw.text((16, 14), "THERMAL", fill=(230, 240, 255))
        return image
