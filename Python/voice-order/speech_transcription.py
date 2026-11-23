"""Optional real Qwen3-ASR. No download or synthetic transcript on an HTTP request."""
import os
import subprocess
import tempfile
import threading
import json
import sys
import wave
import urllib.request
import array
from pathlib import Path
from fastapi import HTTPException

_slot = threading.Lock()

def _infer(command):
    process=subprocess.Popen(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        code=process.wait(timeout=180)
        if code:raise subprocess.CalledProcessError(code,command)
    except subprocess.TimeoutExpired:
        if os.name=='nt':
            subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
        else:process.kill()
        process.wait(timeout=10)
        raise

def configured():
    model = Path(os.getenv('ORDER_ASR_MODEL_PATH', '__not_configured__'))
    python = Path(os.getenv('ORDER_ASR_PYTHON', sys.executable))
    return model.is_dir() and (model/'model.safetensors').is_file() and python.is_file()

def transcribe(data: bytes, content_type: str):
    model_path = Path(os.getenv('ORDER_ASR_MODEL_PATH', '__not_configured__'))
    if not model_path.is_dir():
        raise HTTPException(503, 'QWEN_ASR_MODEL_NOT_CONFIGURED')
    if not _slot.acquire(blocking=False):
        raise HTTPException(429, 'ASR_BUSY')
    try:
        extension={'audio/webm':'.webm','audio/wav':'.wav','audio/ogg':'.ogg','audio/mp4':'.mp4'}.get(content_type.split(';')[0])
        if not extension:raise HTTPException(415,'ASR_UNSUPPORTED_AUDIO')
        if not data:raise HTTPException(422,'ASR_EMPTY_AUDIO')
        with tempfile.TemporaryDirectory(prefix='order-asr-') as folder:
            source=Path(folder)/('input'+extension);source.write_bytes(data)
            wav=Path(folder)/'audio.wav'
            subprocess.run([os.getenv('ORDER_ASR_FFMPEG','ffmpeg'),'-nostdin','-v','error','-i',str(source),'-t','61','-ar','16000','-ac','1',str(wav)],
                           check=True,timeout=20,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            with wave.open(str(wav)) as audio:
                duration=audio.getnframes()/audio.getframerate()
                samples=array.array('h',audio.readframes(audio.getnframes()))
            if duration>60:raise HTTPException(422,'ASR_AUDIO_TOO_LONG')
            if duration<0.1:raise HTTPException(422,'ASR_EMPTY_AUDIO')
            if max((abs(sample) for sample in samples),default=0)<32:
                raise HTTPException(422,'ASR_EMPTY_AUDIO')
            output=Path(folder)/'transcript.json'
            # Release the local chat model's VRAM before loading speech weights.
            # This does not delete its downloaded files or conversation history.
            for model in {os.getenv('ORDER_AGENT_MODEL','qwen2.5:3b'),os.getenv('DESCRIPTION_LLM_MODEL','qwen3:1.7b')}:
                try:
                    payload=json.dumps({'model':model,'keep_alive':0,'stream':False}).encode()
                    request=urllib.request.Request('http://localhost:11434/api/generate',payload,{'Content-Type':'application/json'})
                    with urllib.request.urlopen(request,timeout=5):pass
                except OSError:pass # CPU-only deployments need not run Ollama here.
            # A short-lived process releases ASR weights before conversational
            # inference. The local laptop cannot keep both models in RAM.
            _infer([os.getenv('ORDER_ASR_PYTHON',sys.executable),
                            str(Path(__file__).with_name('speech_inference.py')),
                            str(model_path),str(wav),str(output)])
            text=json.loads(output.read_text(encoding='utf-8'))['text'].strip()
        if not text:raise HTTPException(422,'ASR_EMPTY')
        return {'text':text[:2000]}
    except HTTPException:raise
    except subprocess.TimeoutExpired:raise HTTPException(504,'ASR_TIMEOUT')
    except subprocess.CalledProcessError as error:
        if Path(str(error.cmd[0])).name.lower().startswith('ffmpeg'):
            raise HTTPException(422,'ASR_INVALID_AUDIO')
        raise HTTPException(503,'QWEN_ASR_UNAVAILABLE')
    except Exception:raise HTTPException(503,'QWEN_ASR_UNAVAILABLE')
    finally:_slot.release()
