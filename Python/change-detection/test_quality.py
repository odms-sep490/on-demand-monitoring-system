import io
import unittest
import cv2
import numpy as np
from PIL import Image
from quality_engine import measure, hash_distance, PROFILES


def encode(image):
    output = io.BytesIO(); Image.fromarray(image).save(output, format='PNG'); return output.getvalue()


class QualityTest(unittest.TestCase):
    def setUp(self):
        self.image = np.random.default_rng(16).integers(0, 256, (1080, 1920, 3), dtype=np.uint8)

    def test_blur_reduces_measured_detail(self):
        sharp = measure(encode(self.image))
        blurred = measure(encode(cv2.GaussianBlur(self.image, (35, 35), 8)))
        self.assertGreater(sharp['metrics']['sharpness'], blurred['metrics']['sharpness'])
        self.assertEqual(blurred['decision'], 'REVIEW_REQUIRED')

    def test_dark_and_bright_are_review_not_definitive_failure(self):
        for level in (0, 255):
            result = measure(encode(np.full((720, 1280, 3), level, np.uint8)))
            self.assertEqual(result['decision'], 'REVIEW_REQUIRED')
            self.assertEqual(next(c for c in result['criteria'] if c['code']=='EXPOSURE')['status'], 'REVIEW_REQUIRED')

    def test_low_resolution(self):
        result = measure(encode(self.image[:100, :100]), 'MAPPING')
        self.assertEqual(next(c for c in result['criteria'] if c['code']=='RESOLUTION')['status'], 'REVIEW_REQUIRED')

    def test_corrupt_file(self):
        result = measure(b'not an image')
        self.assertEqual(result['decision'], 'FAIL')
        self.assertEqual(result['criteria'][0]['code'], 'INTEGRITY')

    def test_mime_and_checksum_mismatch(self):
        result = measure(encode(self.image), expected_mime='image/jpeg', expected_checksum='0'*64)
        self.assertEqual(result['criteria'][0]['status'], 'REVIEW_REQUIRED')

    def test_duplicate_hash(self):
        first = measure(encode(self.image))['metrics']['perceptualHash']
        second = measure(encode(self.image))['metrics']['perceptualHash']
        self.assertEqual(hash_distance(first, second), 0)

    def test_profiles_and_honest_classifier(self):
        for profile in PROFILES:
            result = measure(encode(self.image), profile)
            self.assertEqual(result['classifier']['status'], 'NOT_CONFIGURED')
            self.assertNotIn('confidence', result['classifier'])
            self.assertIn('algorithmVersion', result)
            self.assertIn('unchecked', result)

    def test_unknown_profile(self):
        with self.assertRaises(ValueError): measure(b'', 'FAKE')

if __name__ == '__main__': unittest.main()
