"""Local Vietnamese voice for the order consultant."""
import io
import os
import secrets
import threading
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

app = FastAPI()
lock = threading.Lock()
model = None

class Speech(BaseModel):
    text: str = Field(min_length=1, max_length=1500)

@app.get('/health')
def health():
    return {'status': 'ready', 'modelLoaded': model is not None}

@app.post('/speech')
def speech(body: Speech, x_internal_token: str = Header(default='')):
    global model
    token = os.getenv('DESCRIPTION_AI_TOKEN', '')
    if not token or not secrets.compare_digest(token, x_internal_token):
        raise HTTPException(401)
    if not lock.acquire(timeout=2):
        raise HTTPException(429, 'TTS_BUSY')
    try:
        if model is None:
            from vieneu import Vieneu
            model = Vieneu(mode='v3turbo', backend='onnx')
        import soundfile as sf
        audio = model.infer(body.text, voice='Mai Anh')
        output = io.BytesIO()
        sf.write(output, audio, 48000, format='WAV', subtype='PCM_16')
        return Response(output.getvalue(), media_type='audio/wav')
    finally:
        lock.release()
