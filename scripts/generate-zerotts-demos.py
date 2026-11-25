"""Generate matched local ZeroTTS voice comparisons for the order assistant."""

from pathlib import Path
from time import perf_counter

import numpy as np
import soundfile as sf
from zerotts import ZeroTTS


DEMO_TEXT = (
    "Dạ, em chào anh chị ạ! Em là bé AI của OnDemand Monitor nè. "
    "Hôm nay em sẽ giúp anh chị tìm dịch vụ drone phù hợp nha! "
    "Anh chị đang cần kiểm tra nhà xưởng, giám sát khu vực, giám sát rừng "
    "hay theo dõi công trình ạ?"
)
MODEL_ID = "zeroweight-ai/ZeroTTS"
MODEL_REVISION = "18d6681518a4becaed3c125ceb3c7db59905fe86"
TARGET_RMS = 0.12

PROFILES = (
    (
        "zerotts-cute-light-maichi.wav",
        "maichi",
        {"text_temperature": 0.9, "audio_temperature": 0.7},
    ),
    (
        "zerotts-cute-high-hamy.wav",
        "hamy",
        {"text_temperature": 1.0, "audio_temperature": 0.8},
    ),
    (
        "zerotts-cartoon-expressive-hamy.wav",
        "hamy",
        {"text_temperature": 1.0, "audio_temperature": 0.92, "audio_topk": 30},
    ),
)


def main() -> None:
    output_dir = Path(__file__).resolve().parents[1] / "docs" / "audio"
    output_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = (
        Path.home()
        / ".cache"
        / "huggingface"
        / "hub"
        / "models--zeroweight-ai--ZeroTTS"
        / "snapshots"
        / MODEL_REVISION
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
        "voices/hamy/voice.npz",
        "voices/maichi/voice.npz",
    )
    local_model_dir = cache_dir if all((cache_dir / name).is_file() for name in required_files) else None
    source = str(local_model_dir) if local_model_dir else MODEL_ID
    print(f"Loading {source} at {MODEL_REVISION} on CPU…", flush=True)
    started = perf_counter()
    if local_model_dir:
        model = ZeroTTS(
            local_model_dir,
            providers=["CPUExecutionProvider"],
            intra_op_num_threads=1,
            codec_intra_op_num_threads=1,
            warmup=False,
        )
    else:
        model = ZeroTTS.from_pretrained(
            MODEL_ID,
            revision=MODEL_REVISION,
            providers=["CPUExecutionProvider"],
            intra_op_num_threads=1,
            codec_intra_op_num_threads=1,
            warmup=False,
        )
    print(f"Model loaded in {perf_counter() - started:.1f}s.", flush=True)

    for filename, voice, overrides in PROFILES:
        options = {
            "voice": voice,
            "cfg_scale": 1.0,
            "text_temperature": 1.0,
            "audio_temperature": 0.8,
            "audio_topk": 25,
            "audio_topp": 0.95,
            "audio_repetition_penalty": 1.2,
            **overrides,
        }
        started = perf_counter()
        samples = np.asarray(model.synthesize(DEMO_TEXT, **options)).squeeze()
        if samples.ndim != 1 or samples.size == 0 or not np.isfinite(samples).all():
            raise RuntimeError(f"{filename}: model returned an invalid audio buffer")

        rms = float(np.sqrt(np.mean(np.square(samples))))
        peak = float(np.max(np.abs(samples)))
        gain = TARGET_RMS / max(rms, 1e-8)
        if peak * gain > 0.98:
            gain = 0.98 / peak
        samples = (samples * gain).astype(np.float32)

        path = output_dir / filename
        sf.write(path, samples, model.sample_rate, format="WAV", subtype="PCM_16")
        info = sf.info(path)
        print(
            f"Wrote {path.name}: voice={voice}, {info.duration:.2f}s, "
            f"{info.samplerate}Hz mono, peak={np.max(np.abs(samples)):.3f}, "
            f"rms={np.sqrt(np.mean(np.square(samples))):.3f}, "
            f"inference={perf_counter() - started:.1f}s",
            flush=True,
        )


if __name__ == "__main__":
    main()
