"""Local capture review and direct-to-S3 transfer for the packaged controller."""

from datetime import datetime, timezone
from pathlib import Path
from threading import Lock, RLock
from urllib.parse import urlparse
import hashlib
import json
import logging
import os
import subprocess
import uuid

import httpx


class LocalMediaLibrary:
    def __init__(self, root: Path, mission_id: str | None, device_id: str | None,
                 mission_code: str | None = None) -> None:
        self.root = root
        self.mission_id = mission_id
        self.mission_code = mission_code
        self.device_id = device_id
        self.lock = RLock()
        self.transfer_locks: dict[str, Lock] = {}
        self.root.mkdir(parents=True, exist_ok=True)
        self.manifest = self.root / "media-index.json"
        self.items = self._load()
        # No worker survives a controller process restart. Keep the capture for
        # backend reconciliation instead of displaying a permanently running PUT.
        for item in self.items.values():
            if item.get("status") == "UPLOADING":
                item["status"] = "UPLOAD_FAILED"
        self._prepare_pending_videos()

    def bind(self, mission_id: str | None, device_id: str | None,
             mission_code: str | None = None) -> None:
        """Retain the manifest owner and in-flight workers across session refreshes."""
        with self.lock:
            self.mission_id = mission_id
            self.device_id = device_id
            self.mission_code = mission_code

    def _load(self) -> dict[str, dict]:
        if not self.manifest.exists():
            return {}
        with self.manifest.open("r", encoding="utf-8") as source:
            data = json.load(source)
        return {item["localMediaId"]: item for item in data if Path(item["localPath"]).is_file()}

    def _persist(self) -> None:
        temp = self.manifest.with_suffix(".tmp")
        with temp.open("w", encoding="utf-8") as output:
            json.dump(list(self.items.values()), output, indent=2)
        os.replace(temp, self.manifest)

    def capture_image(self, jpeg: bytes, source_type: str = "DRONE_CAMERA") -> dict:
        self._require_capture_session()
        if source_type not in {"DRONE_CAMERA", "SATELLITE_SNAPSHOT", "MANUAL_UPLOAD"}:
            raise ValueError("Unsupported media source type")
        if not jpeg.startswith(b"\xff\xd8\xff"):
            raise ValueError("Camera did not return a JPEG")
        local_id = str(uuid.uuid4())
        path = self.root / f"{local_id}.jpg"
        path.write_bytes(jpeg)
        return self._register(local_id, path, "IMAGE", "image/jpeg", source_type=source_type)

    def register_video(self, path: Path) -> dict:
        self._require_capture_session()
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Recording is empty")
        browser_path = self._browser_compatible_video(path)
        return self._register(
            str(uuid.uuid4()), browser_path, "VIDEO", "video/mp4",
            original_path=path if browser_path != path else None,
            source_type="DRONE_CAMERA",
        )

    def _require_capture_session(self) -> None:
        if not self.mission_id or not self.device_id:
            raise ValueError("Bind an assigned mission before capturing media")

    def _prepare_pending_videos(self) -> None:
        changed = False
        for item in self.items.values():
            if (item.get("mediaType") != "VIDEO"
                    or item.get("status") != "REVIEW_PENDING"
                    or item.get("backendMediaId")):
                continue
            source = Path(item["localPath"])
            try:
                browser_path = self._browser_compatible_video(source)
            except RuntimeError as exc:
                logging.warning("Local video preview conversion failed: %s", exc)
                item["previewError"] = str(exc)
                changed = True
                continue
            if browser_path == source:
                continue
            item.pop("previewError", None)
            item["originalPath"] = str(source.resolve())
            item["localPath"] = str(browser_path.resolve())
            item["fileName"] = browser_path.name
            item["fileSize"] = browser_path.stat().st_size
            item["checksumSha256"] = self._sha256(browser_path)
            changed = True
        if changed:
            self._persist()

    @staticmethod
    def _browser_compatible_video(path: Path) -> Path:
        try:
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "v:0",
                 "-show_entries", "stream=codec_name", "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
                check=True, capture_output=True, text=True, timeout=20,
            )
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise RuntimeError(f"Cannot inspect recorded video: {path}") from exc
        if probe.stdout.strip() == "h264":
            return path

        output = path.with_name(f"{path.stem}-web.mp4")
        if output.is_file() and output.stat().st_size > 0 and output.stat().st_mtime >= path.stat().st_mtime:
            return output
        temporary = output.with_name(f"{output.stem}-{uuid.uuid4().hex}.part.mp4")
        try:
            subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                 "-i", str(path), "-an", "-c:v", "libx264", "-preset", "veryfast",
                 "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(temporary)],
                check=True, capture_output=True, text=True, timeout=600,
            )
            if not temporary.is_file() or temporary.stat().st_size == 0:
                raise RuntimeError("Video conversion produced an empty file")
            os.replace(temporary, output)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise RuntimeError(f"Cannot create browser-compatible video: {path}") from exc
        finally:
            temporary.unlink(missing_ok=True)
        return output

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def _register(self, local_id: str, path: Path, media_type: str, content_type: str,
                  original_path: Path | None = None, source_type: str | None = None) -> dict:
        item = {
            "localMediaId": local_id, "missionId": self.mission_id,
            "deviceId": self.device_id, "mediaType": media_type,
            "fileName": path.name, "localPath": str(path.resolve()),
            "contentType": content_type, "fileSize": path.stat().st_size,
            "checksumSha256": self._sha256(path),
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "status": "REVIEW_PENDING",
            "sourceType": source_type,
        }
        if self.mission_code:
            item["missionCode"] = self.mission_code
        if original_path is not None:
            item["originalPath"] = str(original_path.resolve())
        with self.lock:
            self.items[local_id] = item
            self._persist()
        return item.copy()

    def list_items(self, mission_keys: set[str] | None = None) -> list[dict]:
        with self.lock:
            items = [item.copy() for item in self.items.values()]
        if not mission_keys:
            return items
        return [
            item for item in items
            if item.get("missionId") in mission_keys or item.get("missionCode") in mission_keys
        ]

    def get(self, local_id: str) -> dict:
        with self.lock:
            if local_id not in self.items:
                raise KeyError(local_id)
            return self.items[local_id].copy()

    def discard(self, local_id: str) -> None:
        with self.lock:
            transfer_lock = self.transfer_locks.setdefault(local_id, Lock())
        if not transfer_lock.acquire(blocking=False):
            raise ValueError("Media cannot be discarded during upload")
        try:
            self._discard(local_id)
        finally:
            transfer_lock.release()

    def _discard(self, local_id: str) -> None:
        with self.lock:
            item = self.get(local_id)
            if item["status"] not in {"REVIEW_PENDING", "UPLOAD_FAILED", "VALIDATING"}:
                raise ValueError("Media cannot be discarded during upload")
            Path(item["localPath"]).unlink(missing_ok=True)
            if item.get("originalPath"):
                Path(item["originalPath"]).unlink(missing_ok=True)
            del self.items[local_id]
            self._persist()

    def transfer(self, local_id: str, plan: dict) -> dict:
        """Transfer using backend-signed URLs; the caller completes/acknowledges backend state."""
        with self.lock:
            transfer_lock = self.transfer_locks.setdefault(local_id, Lock())
        # Two browser tabs can send the same plan. Only one worker owns a capture.
        with transfer_lock:
            item = self.get(local_id)
            attempt_id = plan.get("attemptId")
            if (attempt_id and item.get("transferredAttemptId") == attempt_id
                    and item.get("backendMediaId") == plan.get("mediaId")):
                return item["transferResult"]
            return self._transfer(local_id, plan)

    def _transfer(self, local_id: str, plan: dict) -> dict:
        item = self.get(local_id)
        path = Path(item["localPath"])
        if not path.is_file() or path.stat().st_size != item["fileSize"]:
            raise ValueError("Local capture is missing or changed")
        method = plan.get("uploadMethod")
        with self.lock:
            self.items[local_id]["status"] = "UPLOADING"
            self.items[local_id]["backendMediaId"] = plan["mediaId"]
            self._persist()
        try:
            if method == "PUT":
                self._put(path, plan["uploadUrl"], plan.get("uploadHeaders", {}))
                result = {"parts": []}
            elif method == "MULTIPART":
                result = self._multipart(path, plan)
            else:
                raise ValueError("Unsupported upload method")
        except Exception:
            with self.lock:
                self.items[local_id]["status"] = "UPLOAD_FAILED"
                self._persist()
            raise
        with self.lock:
            self.items[local_id]["status"] = "VALIDATING"
            self.items[local_id]["transferredAttemptId"] = plan.get("attemptId")
            self.items[local_id]["transferResult"] = result
            self._persist()
        return result

    def _multipart(self, path: Path, plan: dict) -> dict:
        part_urls = plan["partUrls"]
        if len(part_urls) != plan["partCount"]:
            raise ValueError("Incomplete multipart plan")
        parts = []
        with path.open("rb") as source:
            for index, entry in enumerate(part_urls, start=1):
                chunk = source.read(plan["partSizeBytes"])
                if not chunk or entry["partNumber"] != index:
                    raise ValueError("Invalid multipart plan")
                response = self._put_bytes(chunk, entry["uploadUrl"], entry.get("uploadHeaders", {}))
                etag = response.headers.get("ETag")
                if not etag:
                    raise ValueError("S3 did not return an ETag")
                parts.append({"partNumber": index, "eTag": etag})
        return {"parts": parts}

    def _put(self, path: Path, url: str, headers: dict) -> httpx.Response:
        return self._put_bytes(path.read_bytes(), url, headers)

    def _put_bytes(self, body, url: str, headers: dict) -> httpx.Response:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname or not parsed.hostname.endswith(".amazonaws.com"):
            raise ValueError("Upload URL must be a presigned AWS HTTPS endpoint")
        flat_headers = {key: values[0] for key, values in headers.items() if values}
        with httpx.Client(timeout=120.0, follow_redirects=False) as client:
            response = client.put(url, content=body, headers=flat_headers)
            response.raise_for_status()
            return response
