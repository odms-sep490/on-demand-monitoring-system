import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import thermal_camera_gateway as thermal_module  # noqa: E402
from geofence_monitor import px4_ned_to_sim_xy  # noqa: E402
from thermal_camera_gateway import (  # noqa: E402
    ThermalCameraGateway,
    apply_thermal_palette,
    hottest_pixel,
    thermal_statistics,
)


class FakeClock:
    def __init__(self) -> None:
        self.now_s = 100.0

    def __call__(self) -> float:
        return self.now_s

    def advance(self, seconds: float) -> None:
        self.now_s += seconds


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


def source(
    *,
    code: str = "FIRE",
    x: float = 0.0,
    y: float = -280.0,
    radius: float = 30.0,
    temp: float = 120.0,
    active: bool = True,
    world: str = "forest_monitoring_compact",
) -> dict:
    return {
        "id": code,
        "code": code,
        "name": code,
        "centerXM": x,
        "centerYM": y,
        "radiusM": radius,
        "temperatureC": temp,
        "active": active,
        "sourceWorld": world,
        "coordinateSystem": "LOCAL_SIMULATION_METERS_GAZEBO_XY",
    }


class ThermalCameraGatewayTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = FakeClock()
        self.gateway = ThermalCameraGateway("http://backend.test", clock=self.clock)

    def patch_sources(self, sources: list[dict]):
        return patch.object(
            thermal_module,
            "urlopen",
            return_value=FakeResponse({"data": sources}),
        )

    def test_thermal_defaults_off(self) -> None:
        self.assertFalse(self.gateway.status()["thermalEnabled"])

    def test_thermal_toggle_off_to_on(self) -> None:
        self.assertTrue(self.gateway.toggle())
        self.assertTrue(self.gateway.status()["thermalEnabled"])

    def test_thermal_toggle_on_to_off(self) -> None:
        self.gateway.toggle()
        self.assertFalse(self.gateway.toggle())
        self.assertFalse(self.gateway.status()["thermalEnabled"])

    def test_thermal_off_returns_null_measurement(self) -> None:
        with self.patch_sources([source()]):
            status = self.gateway.status()

        self.assertIsNone(status["maxTemperatureC"])
        self.assertFalse(status["hotspotDetected"])

    def test_thermal_off_does_not_create_frame(self) -> None:
        self.assertIsNone(self.gateway.latest_jpeg())

    def test_retired_db_source_below_threshold_is_ignored(self) -> None:
        with self.patch_sources([source(temp=45.0)]):
            self.gateway.update_pose(0.0, -280.0, 20.0)
            self.gateway.toggle()
            status = self.gateway.status()

        self.assertFalse(status["hotspotDetected"])
        self.assertEqual(28.0, status["maxTemperatureC"])

    def test_retired_db_source_above_threshold_is_ignored(self) -> None:
        with self.patch_sources([source(temp=120.0)]):
            self.gateway.update_pose(0.0, -280.0, 20.0)
            self.gateway.toggle()
            status = self.gateway.status()

        self.assertFalse(status["hotspotDetected"])
        self.assertIsNone(status["hotspotTemperatureC"])

    def test_disabled_thermal_source_is_ignored(self) -> None:
        with self.patch_sources([source(temp=200.0, active=False)]):
            self.gateway.update_pose(0.0, -280.0, 20.0)
            self.gateway.toggle()
            status = self.gateway.status()

        self.assertFalse(status["hotspotDetected"])
        self.assertEqual(28.0, status["maxTemperatureC"])

    def test_wrong_world_source_is_ignored(self) -> None:
        with self.patch_sources([source(temp=200.0, world="other_world")]):
            self.gateway.update_pose(0.0, -280.0, 20.0)
            self.gateway.toggle()
            status = self.gateway.status()

        self.assertFalse(status["hotspotDetected"])
        self.assertEqual(28.0, status["maxTemperatureC"])

    def test_source_outside_camera_footprint_is_not_observed(self) -> None:
        with self.patch_sources([source(x=500.0, y=500.0, temp=250.0)]):
            self.gateway.update_pose(0.0, -280.0, 20.0)
            self.gateway.toggle()
            status = self.gateway.status()

        self.assertFalse(status["hotspotDetected"])
        self.assertEqual(28.0, status["maxTemperatureC"])

    def test_unavailable_backend_reports_sensor_offline_and_null_when_off(self) -> None:
        with patch.object(thermal_module, "urlopen", side_effect=OSError("offline")):
            status = self.gateway.status()

        self.assertFalse(status["thermalSensorOnline"])
        self.assertIsNone(status["maxTemperatureC"])

    def test_does_not_poll_backend_thermal_source_api(self) -> None:
        gateway = ThermalCameraGateway(
            ["http://localhost:8080", "http://windows-host:8080"],
            clock=self.clock,
        )

        with patch.object(
            thermal_module,
            "urlopen",
            side_effect=AssertionError("thermal source API must not be called"),
        ) as mocked_open:
            gateway.toggle()
            status = gateway.status()

        self.assertFalse(status["thermalSensorOnline"])
        mocked_open.assert_not_called()

    def test_cached_db_sources_are_not_used_after_api_retirement(self) -> None:
        with self.patch_sources([source()]):
            self.gateway.update_pose(0.0, -280.0, 20.0)
            self.gateway.toggle()
            self.gateway.status()

        self.clock.advance(thermal_module.THERMAL_SOURCE_REFRESH_S + 1)
        with patch.object(thermal_module, "urlopen", side_effect=OSError("connection refused")):
            status = self.gateway.status()

        self.assertFalse(status["thermalSensorOnline"])
        self.assertIsNone(status["thermalSourceError"])
        self.assertEqual(28.0, status["maxTemperatureC"])

    def test_coordinate_conversion_uses_existing_mapping(self) -> None:
        self.assertEqual((20.0, -270.0), px4_ned_to_sim_xy(10.0, 20.0))

    def test_repeated_status_polling_does_not_create_duplicate_processing(self) -> None:
        with self.patch_sources([source()]):
            self.gateway.toggle()
            first_generation = self.gateway.status()["thermalProcessingGeneration"]
            second_generation = self.gateway.status()["thermalProcessingGeneration"]

        self.assertEqual(first_generation, second_generation)

    def test_thermal_toggle_does_not_touch_external_flight_state(self) -> None:
        flight_state = {"offboard": False, "armed": False, "velocity": (0.0, 0.0, 0.0)}

        self.gateway.toggle()
        self.gateway.toggle()

        self.assertEqual({"offboard": False, "armed": False, "velocity": (0.0, 0.0, 0.0)}, flight_state)

    def test_native_l16_frame_reports_radiometric_statistics(self) -> None:
        temperatures = np.array([[28.0, 35.0], [60.0, 247.8]], dtype=np.float32)
        raw = np.rint((temperatures + 273.15) / thermal_module.THERMAL_LINEAR_RESOLUTION_K).astype("<u2")
        self.assertTrue(self.gateway.ingest_native_frame(2, 2, raw.tobytes(), "L_INT16"))
        self.gateway.toggle()

        with patch.object(thermal_module, "urlopen", side_effect=OSError("offline")):
            status = self.gateway.status()

        self.assertEqual("NATIVE_GAZEBO", status["thermalMode"])
        self.assertAlmostEqual(28.0, status["minTemperatureC"], places=1)
        self.assertAlmostEqual(float(temperatures.mean()), status["averageTemperatureC"], places=1)
        self.assertAlmostEqual(247.8, status["maxTemperatureC"], places=1)
        self.assertTrue(status["hotspotDetected"])

    def test_native_frame_is_not_overridden_by_retired_db_source(self) -> None:
        temperatures = np.full((2, 2), 21.9, dtype=np.float32)
        raw = np.rint((temperatures + 273.15) / thermal_module.THERMAL_LINEAR_RESOLUTION_K).astype("<u2")
        self.assertTrue(self.gateway.ingest_native_frame(2, 2, raw.tobytes(), "L_INT16"))

        with self.patch_sources([source(temp=110.0)]):
            self.gateway.update_pose(0.0, -280.0, 30.0)
            self.gateway.toggle()
            status = self.gateway.status()

        self.assertEqual("NATIVE_GAZEBO", status["thermalMode"])
        self.assertAlmostEqual(21.9, status["maxTemperatureC"], places=1)
        self.assertIsNone(status["hotspotTemperatureC"])
        self.assertFalse(status["hotspotDetected"])

    def test_palette_changes_visualization_without_changing_temperatures(self) -> None:
        temperatures = np.array([[20.0, 60.0], [120.0, 250.0]], dtype=np.float32)
        normalized = thermal_module._normalize_temperatures(temperatures, 20.0, 300.0)
        original = temperatures.copy()

        iron = apply_thermal_palette(normalized, "IRON")
        white_hot = apply_thermal_palette(normalized, "WHITE_HOT")

        np.testing.assert_array_equal(temperatures, original)
        self.assertFalse(np.array_equal(iron, white_hot))

    def test_statistics_and_hottest_pixel_use_raw_values(self) -> None:
        temperatures = np.array([[28.1, 35.4, 44.0], [31.2, 247.8, 60.0]], dtype=np.float32)

        minimum, average, maximum = thermal_statistics(temperatures)
        x, y, hottest = hottest_pixel(temperatures)

        self.assertAlmostEqual(28.1, minimum, places=1)
        self.assertAlmostEqual(float(temperatures.mean()), average, places=4)
        self.assertAlmostEqual(247.8, maximum, places=1)
        self.assertEqual((1, 1), (x, y))
        self.assertAlmostEqual(247.8, hottest, places=1)

    def test_stale_native_frame_falls_back_to_synthetic_mode(self) -> None:
        raw = np.full((2, 2), 30115, dtype="<u2")
        self.gateway.ingest_native_frame(2, 2, raw.tobytes(), "L_INT16")
        self.gateway.toggle()
        self.clock.advance(thermal_module.THERMAL_NATIVE_STALE_S + 0.1)

        with self.patch_sources([]):
            status = self.gateway.status()

        self.assertEqual("SYNTHETIC_THERMAL", status["thermalMode"])

    def test_debug_and_isotherm_are_independent_controls(self) -> None:
        self.assertTrue(self.gateway.toggle_debug_overlay())
        self.assertTrue(self.gateway.toggle_isotherm())
        self.assertEqual("WHITE_HOT", self.gateway.cycle_palette())

        status = self.gateway.status()

        self.assertTrue(status["thermalDebugOverlayEnabled"])
        self.assertTrue(status["thermalIsothermEnabled"])
        self.assertEqual("WHITE_HOT", status["thermalPalette"])


if __name__ == "__main__":
    unittest.main()
