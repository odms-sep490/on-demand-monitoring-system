import hashlib
import io
import logging
import os
import secrets
import warnings
from contextlib import asynccontextmanager
from pathlib import Path

import cv2
import numpy as np
from fastapi import FastAPI, Header, HTTPException, Request
from PIL import Image, ImageOps, UnidentifiedImageError

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)
Image.MAX_IMAGE_PIXELS = 4096 * 4096
MAX_PART = 8 * 1024 * 1024
MAX_BODY = MAX_PART * 2 + 8192


def initialize():
    config = os.getenv("OPENCD_CONFIG")
    checkpoint = os.getenv("OPENCD_CHECKPOINT")
    expected = os.getenv("OPENCD_CHECKPOINT_SHA256", "").lower()
    if not config or not checkpoint or not expected or not os.getenv("OPENCD_TOKEN"):
        return None, None
    try:
        sha256 = hashlib.sha256()
        with Path(checkpoint).open("rb") as file:
            for chunk in iter(lambda: file.read(1024 * 1024), b""):
                sha256.update(chunk)
        digest = sha256.hexdigest()
        if not secrets.compare_digest(digest, expected):
            raise ValueError("Checkpoint SHA-256 mismatch")
        from opencd.apis import OpenCDInferencer

        model = OpenCDInferencer(
            model=config, weights=checkpoint, device=os.getenv("OPENCD_DEVICE", "cpu"),
            classes=["unchanged", "changed"], palette=[[0, 0, 0], [255, 255, 255]],
        )
        return model, digest
    except Exception:
        log.exception("Open-CD initialization failed")
        return None, None


@asynccontextmanager
async def lifespan(app):
    import anyio
    app.state.limiter = anyio.CapacityLimiter(1)
    app.state.model, app.state.digest = initialize()
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "UP"}


@app.get("/ready")
def readiness():
    if app.state.model is None:
        raise HTTPException(503, "NOT_CONFIGURED")
    return {"status": "READY"}


def decode(raw):
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as image:
                if image.format not in {"JPEG", "PNG"} or getattr(image, "n_frames", 1) != 1:
                    raise ValueError("Unsupported image")
                if min(image.size) < 256 or max(image.size) > 4096:
                    raise ValueError("Unsupported dimensions")
                return cv2.cvtColor(np.asarray(ImageOps.exif_transpose(image).convert("RGB")), cv2.COLOR_RGB2BGR)
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError,
            Image.DecompressionBombWarning) as error:
        raise HTTPException(422, "UNSUPPORTED_INPUT") from error


def align(before, after):
    detector = cv2.ORB_create(5000)
    left, left_desc = detector.detectAndCompute(before, None)
    right, right_desc = detector.detectAndCompute(after, None)
    if left_desc is None or right_desc is None:
        raise HTTPException(422, "ALIGNMENT_FAILED")
    matches = [pair[0] for pair in cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(right_desc, left_desc, k=2)
               if len(pair) == 2 and pair[0].distance < 0.7 * pair[1].distance]
    if len(matches) < 35:
        raise HTTPException(422, "ALIGNMENT_FAILED")
    source = np.float32([right[m.queryIdx].pt for m in matches])
    target = np.float32([left[m.trainIdx].pt for m in matches])
    transform, inliers = cv2.estimateAffinePartial2D(source, target, ransacReprojThreshold=3)
    height, width = before.shape[:2]
    if transform is None or inliers is None or inliers.sum() < max(30, 0.55 * len(matches)):
        raise HTTPException(422, "ALIGNMENT_FAILED")
    points = target[inliers.ravel() == 1]
    if cv2.contourArea(cv2.convexHull(points)) < 0.18 * width * height:
        raise HTTPException(422, "ALIGNMENT_FAILED")
    valid = cv2.warpAffine(np.ones(after.shape[:2], np.uint8), transform, (width, height))
    valid = cv2.erode(valid, np.ones((9, 9), np.uint8)).astype(bool)
    if valid.mean() < 0.75:
        raise HTTPException(422, "ALIGNMENT_FAILED")
    return cv2.warpAffine(after, transform, (width, height)), valid


def visual_changes(before, after, valid):
    """Appearance differences, not semantic AI or construction progress.

    Smooth texture and tolerate small registration errors. Remove the median
    colour offset so global exposure changes alone do not mark the whole frame.
    """
    size = before.shape[:2]
    scale = min(1.0, 1024 / max(size))
    target = (max(1, round(size[1] * scale)), max(1, round(size[0] * scale)))
    left, right = [cv2.cvtColor(cv2.GaussianBlur(cv2.resize(img, target), (5, 5), 0),
                              cv2.COLOR_BGR2LAB).astype(np.float32) for img in (before, after)]
    region = cv2.resize(valid.astype(np.uint8), target, interpolation=cv2.INTER_NEAREST).astype(bool)
    offset = np.median((right - left)[region], axis=0)
    right = np.clip(right - offset, 0, 255)
    # Compare against a small neighbourhood to avoid thin outlines from jitter.
    kernel = np.ones((5, 5), np.uint8)
    def distance(a, b):
        low, high = cv2.erode(b, kernel), cv2.dilate(b, kernel)
        delta = np.maximum(low - a, 0) + np.maximum(a - high, 0)
        return np.linalg.norm(delta, axis=2)
    changed = ((distance(left, right) + distance(right, left)) / 2 > 18).astype(np.uint8)
    changed = cv2.morphologyEx(changed, cv2.MORPH_CLOSE, kernel)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(changed, 8)
    cleaned = np.zeros_like(changed)
    for label in range(1, count):
        if stats[label, cv2.CC_STAT_AREA] >= 40:
            cleaned[labels == label] = 1
    return cv2.resize(cleaned, (size[1], size[0]), interpolation=cv2.INTER_NEAREST).astype(bool) & valid


def infer(before_raw, after_raw):
    import base64
    before = decode(before_raw)
    after, valid = align(before, decode(after_raw))
    height, width = valid.shape
    # Preserve scene context and let the model's official test pipeline resize
    # the pair together. MMSeg restores predictions to the original image size.
    predictions = app.state.model([[before, after]], show=False)["predictions"]
    prediction = np.asarray(predictions)
    if prediction.shape == (1, height, width):
        prediction = prediction[0]
    if prediction.shape != (height, width) or not np.isin(prediction, [0, 1]).all():
        raise ValueError("Expected original-size binary prediction")
    ai_mask = (prediction == 1) & valid
    visual_mask = visual_changes(before, after, valid)
    mask = ai_mask | visual_mask
    overlay = np.zeros((height, width, 4), np.uint8)
    overlay[visual_mask] = (0, 200, 255, 255)
    overlay[ai_mask] = (32, 96, 255, 255)

    def encode(image):
        success, data = cv2.imencode(".png", image)
        if not success:
            raise ValueError("PNG encoding failed")
        return base64.b64encode(data).decode("ascii")

    return {
        "before": encode(before), "after": encode(after), "mask": encode(mask.astype(np.uint8) * 255),
        "overlay": encode(overlay), "changedPixelRatio": float(mask.sum() / valid.sum()),
        "validPixelRatio": float(valid.mean()), "alignmentStatus": "AFFINE_VALIDATED",
        "modelName": Path(os.environ["OPENCD_CONFIG"]).stem,
        "modelVersion": "790a1972b538baaaffd4c3022a4d9afaa3f861e5",
        "checkpointIdentifier": app.state.digest,
        "processingVersion": "ai-visual-v3",
        "aiChangedPixelRatio": float(ai_mask.sum() / valid.sum()),
        "visualChangedPixelRatio": float(visual_mask.sum() / valid.sum()),
    }


@app.post("/infer")
async def analyze(request: Request, authorization: str = Header(default="")):
    import anyio
    from starlette.datastructures import UploadFile

    token = os.getenv("OPENCD_TOKEN")
    if not token or not secrets.compare_digest(authorization.encode(), f"Bearer {token}".encode()):
        raise HTTPException(401, "UNAUTHORIZED")
    if app.state.model is None:
        raise HTTPException(503, "NOT_CONFIGURED")
    try:
        size = int(request.headers.get("content-length", "0"))
    except ValueError as error:
        raise HTTPException(400, "UNSUPPORTED_INPUT") from error
    if not 0 < size <= MAX_BODY:
        raise HTTPException(413, "UNSUPPORTED_INPUT")
    try:
        app.state.limiter.acquire_nowait()
    except anyio.WouldBlock as error:
        raise HTTPException(429, "WORKER_BUSY") from error
    try:
        received = 0

        async def bounded_receive():
            nonlocal received
            message = await request.receive()
            received += len(message.get("body", b""))
            if received > MAX_BODY:
                raise HTTPException(413, "UNSUPPORTED_INPUT")
            return message

        bounded = Request(request.scope, bounded_receive)
        async with bounded.form(max_files=2, max_fields=0) as form:
            images = []
            for name in ("before", "after"):
                file = form.get(name)
                if not isinstance(file, UploadFile):
                    raise HTTPException(422, "UNSUPPORTED_INPUT")
                data = await file.read(MAX_PART + 1)
                if len(data) > MAX_PART:
                    raise HTTPException(413, "UNSUPPORTED_INPUT")
                images.append(data)
        return await anyio.to_thread.run_sync(infer, *images)
    except HTTPException:
        raise
    except Exception as error:
        log.exception("Inference failed")
        raise HTTPException(500, "INFERENCE_FAILED") from error
    finally:
        app.state.limiter.release()
