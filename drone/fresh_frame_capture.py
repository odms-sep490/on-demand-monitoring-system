"""Serialize photo capture without storing the same camera frame twice."""

from threading import Lock
import time


class FreshFrameCapture:
    def __init__(self, timeout_seconds: float = 2.0) -> None:
        self.timeout_seconds = timeout_seconds
        self._lock = Lock()
        self._versions: dict[str, int] = {}

    def capture(self, read_snapshot, save):
        # This lock is separate from the camera lock: sensor callbacks and preview
        # must keep running while a photo waits for the next frame.
        deadline = time.monotonic() + self.timeout_seconds
        if not self._lock.acquire(timeout=self.timeout_seconds):
            raise TimeoutError("Camera is busy capturing another photo. Please try again.")
        try:
            while time.monotonic() < deadline:
                snapshot = read_snapshot()
                if snapshot is not None:
                    mode, version, jpeg = snapshot
                    if version > self._versions.get(mode, -1):
                        result = save(jpeg)
                        self._versions[mode] = version
                        return result
                time.sleep(min(0.02, max(0, deadline - time.monotonic())))
            raise TimeoutError("Camera has not provided a new frame. Please try again.")
        finally:
            self._lock.release()
