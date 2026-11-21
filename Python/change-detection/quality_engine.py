"""CPU image measurements. Provisional profiles are advisory, not certified quality."""
import hashlib
import io
from datetime import datetime, timezone

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

VERSION = 'quality-metrics-1.0'
PROFILES = {
    'PANORAMA': (35, 1280, 720), 'DETAIL': (80, 1920, 1080),
    'INFRASTRUCTURE': (90, 1920, 1080), 'MAPPING': (100, 3000, 2000),
    'CHANGE_COMPARISON': (50, 1024, 768),
}


class PaddleQualityClassifier:
    """Contract for a task-trained, licensed multi-label PaddleClas export.

    A directory or ImageNet weights alone must never enable quality inference.
    Activation requires a separately validated adapter with preprocessing and
    per-label calibration matching the training export.
    """
    labels = ('ACCEPTABLE', 'BLURRY', 'TOO_DARK', 'OVEREXPOSED', 'OTHER_QUALITY_ISSUE')

    def predict(self, image):
        return {'status': 'NOT_CONFIGURED', 'labels': [],
                'reason': 'Chưa có mô hình phân loại chất lượng ảnh đã được xác minh.'}


CLASSIFIER = PaddleQualityClassifier()


def measure(raw, profile='PANORAMA', expected_mime=None, expected_checksum=None):
    if profile not in PROFILES:
        raise ValueError('INVALID_PROFILE')
    result = dict(profile=profile, profileVersion='1-provisional', algorithmVersion=VERSION,
                  analyzedAt=datetime.now(timezone.utc).isoformat(), metrics={}, criteria=[],
                  classifier={'status': 'NOT_CONFIGURED', 'labels': []},
                  warnings=['Bộ tiêu chí tham khảo, chưa hiệu chỉnh theo camera và điều kiện chụp.'],
                  unchecked=['Ý nghĩa nội dung ảnh', 'Chất lượng theo hợp đồng cụ thể',
                             'Phân loại AI bằng PaddleClas'])
    criteria = result['criteria']
    checksum = hashlib.sha256(raw).hexdigest()
    if not expected_checksum:
        result['unchecked'].append('Đối chiếu checksum gốc chưa được lưu')
    if not expected_mime:
        result['unchecked'].append('Đối chiếu MIME đã khai báo')
    try:
        with Image.open(io.BytesIO(raw)) as image:
            if image.format not in ('PNG', 'JPEG') or getattr(image, 'n_frames', 1) != 1:
                raise ValueError('INVALID_IMAGE')
            width, height = image.size
            if width * height > 24_000_000 or max(width, height) > 10000:
                raise ValueError('PIXEL_LIMIT')
            actual_mime = Image.MIME[image.format]
            image.load()
            rgb = np.asarray(ImageOps.exif_transpose(image).convert('RGB'))
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        result.update(decision='FAIL', metrics={'checksumSha256': checksum},
                      criteria=[dict(code='INTEGRITY', status='FAIL', message='Không đọc được ảnh JPEG/PNG hợp lệ trong giới hạn cho phép.')])
        return result
    integrity_ok = (not expected_mime or expected_mime.lower() == actual_mime) and (
        not expected_checksum or expected_checksum.lower() == checksum)
    criteria.append(dict(code='INTEGRITY', status='PASS' if integrity_ok else 'REVIEW_REQUIRED',
                         message='Đọc được ảnh.' if integrity_ok else 'Thông tin tệp không khớp MIME hoặc checksum đã lưu.'))
    height, width = rgb.shape[:2]
    scale = min(1, 1024 / max(height, width))
    small = cv2.resize(rgb, (max(1, round(width*scale)), max(1, round(height*scale))), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(small, cv2.COLOR_RGB2GRAY)
    denoised = cv2.GaussianBlur(gray, (3, 3), 0)
    sharpness = float(cv2.Laplacian(denoised, cv2.CV_64F).var())
    threshold, minimum_long, minimum_short = PROFILES[profile]
    dark = float((gray <= 10).mean()); bright = float((gray >= 245).mean())
    mean = float(gray.mean())
    suspect_exposure = dark > .4 or bright > .4 or mean < 35 or mean > 225
    small_hash = cv2.resize(gray, (9, 8), interpolation=cv2.INTER_AREA)
    bits = (small_hash[:, 1:] > small_hash[:, :-1]).flatten()
    dhash = f'{sum(int(bit) << i for i, bit in enumerate(bits)):016x}'
    resolution_ok = max(width, height) >= minimum_long and min(width, height) >= minimum_short
    criteria.extend([
        dict(code='BLUR', status='PASS' if sharpness >= threshold else 'REVIEW_REQUIRED',
             message='Mức chi tiết phù hợp ngưỡng tham khảo.' if sharpness >= threshold else 'Ảnh ít chi tiết hoặc có dấu hiệu mờ; cần xem lại.'),
        dict(code='EXPOSURE', status='REVIEW_REQUIRED' if suspect_exposure else 'PASS',
             message='Có vùng rất tối hoặc rất sáng; hãy đối chiếu điều kiện chụp.' if suspect_exposure else 'Độ sáng nằm trong khoảng tham khảo.'),
        dict(code='RESOLUTION', status='PASS' if resolution_ok else 'REVIEW_REQUIRED',
             message=f'Ảnh {width} × {height}; tham khảo tối thiểu cạnh dài {minimum_long}, cạnh ngắn {minimum_short} pixel.'),
        dict(code='DELIVERABLE_REQUIREMENTS', status='INDETERMINATE',
             message='Chưa đối chiếu yêu cầu độ phân giải riêng trong hợp đồng.'),
    ])
    result['metrics'] = dict(width=width, height=height, bytes=len(raw), actualMime=actual_mime,
                             checksumSha256=checksum, sharpness=round(sharpness, 3),
                             brightness=round(mean, 3), darkRatio=dark, brightRatio=bright,
                             perceptualHash=dhash, normalizedLongEdge=max(gray.shape),
                             blurReviewThreshold=threshold)
    result['classifier'] = CLASSIFIER.predict(rgb)
    result['decision'] = 'REVIEW_REQUIRED' if any(c['status'] == 'REVIEW_REQUIRED' for c in criteria) else 'PASS'
    result['warnings'].append('PASS chỉ áp dụng các phép đo đã kiểm tra, không xác nhận ảnh đạt mọi yêu cầu bàn giao.')
    return result


def hash_distance(left, right):
    return (int(left, 16) ^ int(right, 16)).bit_count()
