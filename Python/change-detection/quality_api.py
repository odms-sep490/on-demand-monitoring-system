import logging
import os
import secrets
import uuid

import anyio
from fastapi import APIRouter, Header, HTTPException, Request
from quality_engine import VERSION, measure

router = APIRouter(prefix='/quality')
log = logging.getLogger(__name__)
LIMIT = int(os.getenv('IMAGE_QUALITY_MAX_IMAGE_SIZE', '8388608'))


@router.get('/ready')
def ready():
    if not os.getenv('IMAGE_QUALITY_INTERNAL_TOKEN'):
        raise HTTPException(503, 'NOT_CONFIGURED')
    return {'status': 'READY', 'metrics': VERSION, 'classifier': 'NOT_CONFIGURED'}


@router.post('/analyze')
async def analyze(request: Request, profile: str = 'PANORAMA',
                  authorization: str = Header(default=''),
                  x_image_mime: str = Header(default=''), x_image_checksum: str = Header(default=''),
                  x_correlation_id: str = Header(default='')):
    token = os.getenv('IMAGE_QUALITY_INTERNAL_TOKEN')
    if not token or not secrets.compare_digest(authorization.encode(), ('Bearer '+token).encode()):
        raise HTTPException(401, 'UNAUTHORIZED')
    chunks = []; size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > LIMIT:
            raise HTTPException(413, 'SIZE_LIMIT')
        chunks.append(chunk)
    correlation = x_correlation_id if len(x_correlation_id) <= 64 and x_correlation_id.isalnum() else uuid.uuid4().hex
    try:
        return await anyio.to_thread.run_sync(measure, b''.join(chunks), profile,
                                            x_image_mime or None, x_image_checksum or None)
    except ValueError:
        raise HTTPException(422, 'INVALID_PROFILE')
    except Exception:
        log.error('Quality analysis failed correlation=%s', correlation)
        raise HTTPException(500, 'QUALITY_ANALYSIS_FAILED')
