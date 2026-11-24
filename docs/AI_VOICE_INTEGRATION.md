# OnDemand Monitor AI voice

## Provider selection

The local speech worker exposes `POST /speech` and keeps its internal token check. `TTS_PROVIDER=zerotts` is the default and uses the pinned ZeroTTS revision with the `maichi` voice. `TTS_PROVIDER=vieneu` selects the existing VieNeu-TTS provider; its default voice remains `Mai Anh`. This is a selectable fallback provider, not automatic failover. Restart the worker after changing either setting.

Run `scripts/install-zerotts-tts.ps1` to install the package and only the model files and voice packs needed by this integration. Run `scripts/start-vieneu-tts.ps1` to start the worker on port 8001. The worker looks for a complete local model in `TTS_MODEL_PATH`, the repository's `.ai-runtime/zerotts-model`, or the pinned Hugging Face cache before attempting a download.

## Voice comparisons

All three WAV files use the same Vietnamese text and the actual ZeroTTS model output. A uniform scalar gain was applied to match RMS loudness for listening; there was no pitch shifting, time stretching, denoising, or artificial emotion processing.

| Demo | Voice | Model-supported settings | Duration |
| --- | --- | --- | ---: |
| `docs/audio/zerotts-cute-light-maichi.wav` | Mai Chi (`maichi`) | text temperature 0.9, audio temperature 0.7 | 11.76 s |
| `docs/audio/zerotts-cute-high-hamy.wav` | Hà My (`hamy`) | ZeroTTS sampling defaults | 13.12 s |
| `docs/audio/zerotts-cartoon-expressive-hamy.wav` | Hà My (`hamy`) | audio temperature 0.92, audio top-k 30 | 14.32 s |

ZeroTTS exposes sampling controls but no direct speed, pitch, or emotion control. The “cute” labels describe the listening intent, not guaranteed model modes. Lower sampling settings are used for the calmer option; the more varied Hamy sample is a comparison, not a reliably selectable emotion. The generated files are 48 kHz mono PCM, RMS-normalized to 0.12, and have no clipped samples. Mai Chi is the default because the produced sample is the shortest and least sampling-variant of these three, which is the safer starting point for dates, amounts, and operational guidance. The Hà My options remain available in the demo files; the provider can select them by setting `TTS_VOICE=hamy`.

## App playback and animation

The order conversation requests audio through the existing authenticated customer conversation endpoint. The browser plays the returned WAV as an `HTMLAudioElement`; the voice toggle aborts generation or pauses playback and clears the audio analyser. `SPEAKING` begins only on the audio `playing` event and ends on completion or stop. Microphone capture drives `LISTENING`; transcription and API work drive `THINKING`.

The analyser follows the actual audio envelope to gently vary the robot's glow. The mascot art is a single flattened image, so this implementation does not move the mouth, eyes, or hands independently and does not claim phoneme lip sync. Those movements require a layered or rigged mascot asset. Text chat and the existing order/Qwen-Agent flows remain unchanged.
