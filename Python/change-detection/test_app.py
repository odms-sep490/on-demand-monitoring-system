import io
import unittest
from unittest.mock import patch

import numpy as np
from fastapi import HTTPException
from PIL import Image

from app import align, decode, initialize


class WorkerTest(unittest.TestCase):
    def test_missing_checkpoint_is_not_ready(self):
        with patch.dict("os.environ", {}, clear=True):
            self.assertEqual(initialize(), (None, None))

    def test_invalid_image_is_rejected(self):
        with self.assertRaisesRegex(HTTPException, "UNSUPPORTED_INPUT"):
            decode(b"not an image")

    def test_textureless_pair_is_rejected(self):
        with self.assertRaisesRegex(HTTPException, "ALIGNMENT_FAILED"):
            image = np.zeros((512, 512, 3), dtype=np.uint8)
            align(image, image)

    def test_png_decodes_without_rescaling(self):
        raw = io.BytesIO()
        Image.new("RGB", (512, 256), (255, 0, 0)).save(raw, format="PNG")
        decoded = decode(raw.getvalue())
        self.assertEqual(decoded.shape, (256, 512, 3))
        self.assertEqual(decoded[0, 0].tolist(), [0, 0, 255])


class ConfiguredInferenceTest(unittest.TestCase):
    def test_real_checkpoint_on_drone_fixtures(self):
        import base64
        import os
        from pathlib import Path

        from app import app, infer

        before = os.getenv("OPENCD_FIXTURE_BEFORE")
        after = os.getenv("OPENCD_FIXTURE_AFTER")
        if not before or not after:
            self.skipTest("Set both drone fixture paths and configure the checkpoint")
        app.state.model, app.state.digest = initialize()
        self.assertIsNotNone(app.state.model)
        result = infer(Path(before).read_bytes(), Path(after).read_bytes())
        images = {}
        for key in ("before", "after", "mask", "overlay"):
            with Image.open(io.BytesIO(base64.b64decode(result[key]))) as image:
                images[key] = image.copy()
        self.assertTrue(all(image.size == images["before"].size for image in images.values()))
        self.assertTrue(set(np.unique(images["mask"])).issubset({0, 255}))
        self.assertTrue(np.array_equal(np.asarray(images["overlay"])[..., 3], np.asarray(images["mask"])))
        self.assertGreaterEqual(result["changedPixelRatio"], 0)
        self.assertLessEqual(result["changedPixelRatio"], 1)
