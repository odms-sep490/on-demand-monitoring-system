"""Local Vietnamese speech worker for the order consultant."""

from collections import OrderedDict
import io
import os
import secrets
import threading
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

os.environ.setdefault("HF_HUB_DISABLE_XET", "1")

ZERO_TTS_REPO = "zeroweight-ai/ZeroTTS"
ZERO_TTS_REVISION = "18d6681518a4becaed3c125ceb3c7db59905fe86"
SUPPORTED_PROVIDERS = {"zerotts", "vieneu"}
CACHE_MAX_ITEMS = 64

app = FastAPI()
lock = threading.Lock()
cache_lock = threading.Lock()
wav_cache: OrderedDict[tuple[str, str, str], bytes] = OrderedDict()
models: dict[str, object | None] = {"zerotts": None, "vieneu": None}


class Speech(BaseModel):
    text: str = Field(min_length=1, max_length=1500)


def active_profile() -> tuple[str, str]:
    provider = os.getenv("TTS_PROVIDER", "zerotts").strip().lower()
    if provider not in SUPPORTED_PROVIDERS:
        raise ValueError("TTS_PROVIDER must be zerotts or vieneu")
    if provider == "zerotts":
        voice = os.getenv("TTS_VOICE", "maichi").strip() or "maichi"
    else:
        voice = (
            os.getenv("TTS_VOICE")
            or os.getenv("TTS_DEFAULT_VOICE")
            or "Mai Anh"
        ).strip()
    return provider, voice


def load_model(provider: str):
    if models[provider] is not None:
        return models[provider]

    if provider == "zerotts":
        from zerotts import ZeroTTS

        revision = os.getenv("TTS_MODEL_REVISION", ZERO_TTS_REVISION)
        cpu_count = os.cpu_count() or 4
        intra_threads = int(os.getenv("TTS_INTRA_OP_THREADS", str(min(cpu_count, 8))))
        codec_threads = int(os.getenv("TTS_CODEC_INTRA_OP_THREADS", str(min(cpu_count, 4))))
        candidates = []
        configured_path = os.getenv("TTS_MODEL_PATH")
        if configured_path:
            candidates.append(Path(configured_path))
        candidates.extend(
            (
                Path(__file__).resolve().parents[2] / ".ai-runtime" / "zerotts-model",
                Path.home()
                / ".cache"
                / "huggingface"
                / "hub"
                / "models--zeroweight-ai--ZeroTTS"
                / "snapshots"
                / revision,
            )
        )
        required_files = (
            "config.json",
            "tokenizer.json",
            "null_voice_emb.npy",
            "onnx/text_encoder.onnx",
            "onnx/prefix_step.onnx",
            "onnx/local_frame_decode.onnx",
            "onnx/codec/codec_browser_onnx_meta.json",
            "onnx/codec/moss_audio_tokenizer_decode_full.onnx",
            "onnx/codec/moss_audio_tokenizer_decode_shared.data",
            "onnx/codec/moss_audio_tokenizer_decode_step.onnx",
            f"voices/{os.getenv('TTS_VOICE', 'maichi').strip() or 'maichi'}/voice.npz",
        )
        local_model_dir = next(
            (
                candidate
                for candidate in candidates
                if all((candidate / name).is_file() for name in required_files)
            ),
            None,
        )
        if local_model_dir:
            model = ZeroTTS(
                local_model_dir,
                providers=["CPUExecutionProvider"],
                intra_op_num_threads=intra_threads,
                codec_intra_op_num_threads=codec_threads,
                warmup=False,
            )
        else:
            model = ZeroTTS.from_pretrained(
                os.getenv("TTS_MODEL_ID", ZERO_TTS_REPO),
                revision=revision,
                providers=["CPUExecutionProvider"],
                intra_op_num_threads=intra_threads,
                codec_intra_op_num_threads=codec_threads,
                warmup=False,
            )
    else:
        from vieneu import Vieneu

        model = Vieneu(mode="v3turbo", backend="onnx")

    models[provider] = model
    return model


def _preload_model_background() -> None:
    try:
        provider, _ = active_profile()
        with lock:
            load_model(provider)
    except Exception:
        # Keep worker responsive; errors surface on explicit /speech requests.
        pass


@app.on_event("startup")
def on_startup():
    threading.Thread(target=_preload_model_background, daemon=True).start()


@app.get("/health")
def health():
    try:
        provider, voice = active_profile()
    except ValueError as error:
        return {
            "status": "misconfigured",
            "provider": os.getenv("TTS_PROVIDER", "zerotts"),
            "error": str(error),
            "fallbackProvider": "vieneu",
        }
    return {
        "status": "ready",
        "provider": provider,
        "voice": voice,
        "modelLoaded": models[provider] is not None,
        "cacheSize": len(wav_cache),
        "fallbackProvider": "vieneu" if provider == "zerotts" else "zerotts",
        "modelRevision": ZERO_TTS_REVISION if provider == "zerotts" else "v3turbo",
        "sampleRate": 48_000,
    }


@app.post("/speech")
def speech(body: Speech, x_internal_token: str = Header(default="")):
    token = os.getenv("DESCRIPTION_AI_TOKEN", "")
    if not token or not secrets.compare_digest(token, x_internal_token):
        raise HTTPException(401)

    text = " ".join(body.text.strip().split())
    if not text:
        raise HTTPException(422, "EMPTY_TEXT")

    try:
        provider, voice = active_profile()
    except ValueError as error:
        raise HTTPException(503, "TTS_MISCONFIGURED") from error

    cache_key = (provider, voice, text)
    with cache_lock:
        cached = wav_cache.get(cache_key)
        if cached is not None:
            wav_cache.move_to_end(cache_key)
            return Response(cached, media_type="audio/wav")

    lock_timeout = float(os.getenv("TTS_LOCK_TIMEOUT_SECONDS", "180"))
    if not lock.acquire(timeout=lock_timeout):
        raise HTTPException(429, "TTS_BUSY")

    try:
        # Re-check cache in case another request synthesized the same text while waiting
        with cache_lock:
            cached = wav_cache.get(cache_key)
            if cached is not None:
                wav_cache.move_to_end(cache_key)
                return Response(cached, media_type="audio/wav")

        model = load_model(provider)
        if provider == "zerotts":
            import numpy as np
            import soundfile as sf

            audio = np.asarray(
                model.synthesize(
                    text,
                    voice=voice,
                    cfg_scale=float(os.getenv("TTS_ZERO_CFG_SCALE", "1.0")),
                    text_temperature=float(os.getenv("TTS_ZERO_TEXT_TEMPERATURE", "1.0")),
                    audio_temperature=float(os.getenv("TTS_ZERO_AUDIO_TEMPERATURE", "0.8")),
                    audio_topk=int(os.getenv("TTS_ZERO_AUDIO_TOPK", "25")),
                    audio_topp=float(os.getenv("TTS_ZERO_AUDIO_TOPP", "0.95")),
                    audio_repetition_penalty=float(
                        os.getenv("TTS_ZERO_AUDIO_REPETITION_PENALTY", "1.2")
                    ),
                )
            ).squeeze()
            sample_rate = int(model.sample_rate)
        else:
            import numpy as np
            import soundfile as sf

            audio = np.asarray(model.infer(text, voice=voice)).squeeze()
            sample_rate = 48_000

        if audio.ndim != 1 or audio.size == 0 or not np.isfinite(audio).all():
            raise ValueError("The speech model returned an invalid audio buffer")

        peak = float(np.max(np.abs(audio)))
        if peak > 0.98:
            audio = audio * (0.98 / peak)

        output = io.BytesIO()
        sf.write(output, audio, sample_rate, format="WAV", subtype="PCM_16")
        wav_bytes = output.getvalue()

        with cache_lock:
            wav_cache[cache_key] = wav_bytes
            wav_cache.move_to_end(cache_key)
            while len(wav_cache) > CACHE_MAX_ITEMS:
                wav_cache.popitem(last=False)

        return Response(wav_bytes, media_type="audio/wav")
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(503, "TTS_INFERENCE_FAILED") from error
    finally:
        lock.release()
