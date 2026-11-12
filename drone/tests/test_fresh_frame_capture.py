import ast
import asyncio
import json
import queue
import tempfile
import subprocess
from urllib.parse import urlparse, parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import __future__
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
from threading import Event
import threading
import time
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fresh_frame_capture import FreshFrameCapture


class FreshFrameCaptureTest(unittest.TestCase):
    def test_concurrent_photos_wait_for_different_frame_versions(self):
        gate = FreshFrameCapture(1)
        version = [1]
        saved = []
        first_saved = Event()
        second_read = Event()

        def read():
            if first_saved.is_set():
                second_read.set()
            return "FRONT", version[0], bytes([version[0]])

        def save(jpeg):
            saved.append(jpeg)
            first_saved.set()
            return jpeg

        with ThreadPoolExecutor(max_workers=2) as workers:
            first = workers.submit(gate.capture, read, save)
            self.assertTrue(first_saved.wait(1))
            second = workers.submit(gate.capture, read, save)
            self.assertTrue(second_read.wait(1))
            self.assertEqual(saved, [b"\x01"])
            version[0] = 2
            self.assertEqual(first.result(1), b"\x01")
            self.assertEqual(second.result(1), b"\x02")
        self.assertEqual(saved, [b"\x01", b"\x02"])

    def test_frozen_camera_times_out_without_creating_duplicate(self):
        gate = FreshFrameCapture(0.03)
        save = Mock(return_value="saved")
        read = lambda: ("FRONT", 1, b"frame")
        gate.capture(read, save)
        with self.assertRaisesRegex(TimeoutError, "new frame"):
            gate.capture(read, save)
        save.assert_called_once_with(b"frame")
        self.assertEqual(gate.capture(lambda: ("FRONT", 2, b"next"), save), "saved")

    def test_no_camera_frame_does_not_create_media(self):
        save = Mock()
        with self.assertRaises(TimeoutError):
            FreshFrameCapture(0.02).capture(lambda: None, save)
        save.assert_not_called()

    def test_failed_save_does_not_consume_frame(self):
        gate = FreshFrameCapture()
        read = lambda: ("FRONT", 1, b"frame")
        with self.assertRaises(OSError):
            gate.capture(read, Mock(side_effect=OSError("disk full")))
        self.assertEqual(gate.capture(read, lambda jpeg: jpeg), b"frame")

    def test_camera_modes_have_independent_versions(self):
        gate = FreshFrameCapture(0.02)
        for mode in ["FRONT", "DOWN"]:
            self.assertEqual(gate.capture(lambda: (mode, 1, b"frame"), lambda x: x), b"frame")
        with self.assertRaises(TimeoutError):
            gate.capture(lambda: ("FRONT", 1, b"frame"), lambda x: x)

    def test_new_frames_with_identical_pixels_still_reach_content_policy(self):
        gate = FreshFrameCapture()
        save = Mock()
        for version in [1, 2]:
            gate.capture(lambda: ("FRONT", version, b"same pixels"), save)
        self.assertEqual(save.call_count, 2)

    def test_camera_gateway_uses_versioned_cached_snapshot(self):
        # Load only this real class: importing the controller starts platform-specific
        # Gazebo/MAVSDK setup, which is unnecessary for the camera/cache contract.
        path = Path(__file__).resolve().parents[1] / "flight_controller.py"
        tree = ast.parse(path.read_text(encoding="utf-8"))
        camera = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "CameraGateway")
        namespace = {"FreshFrameCapture": FreshFrameCapture, "CAMERA_DEFAULT_VIEW": "FRONT",
                     "threading": threading, "asyncio": asyncio, "time": time}
        exec(compile(ast.Module(body=[camera], type_ignores=[]), str(path), "exec",
                     flags=__future__.annotations.compiler_flag), namespace)
        library = Mock(mission_id="mission", device_id="device", lock=threading.RLock())
        library.capture_image.return_value = {"localMediaId": "capture"}
        gateway = namespace["CameraGateway"](media_library=library)
        gateway.photo_capture = FreshFrameCapture(0.02)
        gateway.latest_frames["FRONT"] = object()
        gateway.latest_frame_versions["FRONT"] = 1
        gateway.latest_jpegs[("FRONT", "capture")] = (1, b"jpeg")
        self.assertEqual(gateway._latest_jpeg(), b"jpeg")
        asyncio.run(gateway.capture_for_review())
        asyncio.run(gateway.capture_for_review())
        library.capture_image.assert_called_once_with(b"jpeg")
        gateway.latest_frame_versions["FRONT"] = 2
        gateway.latest_jpegs[("FRONT", "capture")] = (2, b"new jpeg")
        asyncio.run(gateway.capture_for_review())
        self.assertEqual(library.capture_image.call_count, 2)
        # A Mission switch during the wait must not save the photo into a sibling.
        def switch_scope_then_save(_read, save):
            library.mission_id = "other-mission"
            return save(b"wrong scope")
        gateway.photo_capture = Mock()
        gateway.photo_capture.capture.side_effect = switch_scope_then_save
        with self.assertRaisesRegex(ValueError, "session changed"):
            gateway.capture_photo()
        self.assertEqual(library.capture_image.call_count, 2)


class PhotoCommandHttpTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / "flight_controller.py"
        tree = ast.parse(path.read_text(encoding="utf-8"))
        api = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "FlightControlApi")
        namespace = {"threading": threading, "json": json, "urlparse": urlparse, "parse_qs": parse_qs, "subprocess": subprocess,
                     "BaseHTTPRequestHandler": BaseHTTPRequestHandler,
                     "ThreadingHTTPServer": ThreadingHTTPServer,
                     "FLIGHT_CONTROL_API_BIND": "127.0.0.1", "FLIGHT_CONTROL_API_PORT": 0}
        exec(compile(ast.Module(body=[api], type_ignores=[]), str(path), "exec",
                     flags=__future__.annotations.compiler_flag), namespace)
        self.camera = Mock()
        self.library = Mock(mission_id="mission")
        self.api = namespace["FlightControlApi"](self.camera, queue.Queue(), media_library=self.library)
        self.api.start()
        self.url = "http://127.0.0.1:%s/api/control/command" % self.api.server.server_address[1]

    def tearDown(self):
        self.api.stop()
        self.api.thread.join(2)

    def command(self):
        request = Request(self.url, data=b'{"command":"photo"}', headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def test_photo_success_reports_saved_capture_without_queuing_second_command(self):
        self.camera.capture_photo.return_value = {"localMediaId": "saved", "localPath": "private"}
        status, body = self.command()
        self.assertEqual(status, 201)
        self.assertEqual(body, {"ok": True, "command": "photo", "localMediaId": "saved"})
        self.assertTrue(self.api.commands.empty())
        self.camera.capture_photo.assert_called_once()

    def test_frozen_camera_returns_actionable_failure(self):
        self.camera.capture_photo.side_effect = TimeoutError()
        status, body = self.command()
        self.assertEqual(status, 409)
        self.assertEqual(body["code"], "CAMERA_FRAME_TIMEOUT")
        self.assertFalse(body["ok"])
        self.assertTrue(self.api.commands.empty())

    def test_disk_failure_never_claims_photo_was_saved(self):
        self.camera.capture_photo.side_effect = OSError("private path")
        status, body = self.command()
        self.assertEqual(status, 503)
        self.assertEqual(body["code"], "CAMERA_CAPTURE_FAILED")
        self.assertNotIn("private path", json.dumps(body))

    def test_unbound_mission_is_still_rejected_before_capture(self):
        self.library.mission_id = None
        status, body = self.command()
        self.assertEqual(status, 409)
        self.assertFalse(body["ok"])
        self.camera.capture_photo.assert_not_called()


if __name__ == "__main__":
    unittest.main()
