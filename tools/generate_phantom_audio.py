"""Regenerate the original 10-kill Phantom audio on Forge's offline Kokoro setup.

Requires: kokoro_onnx, numpy, soundfile, trusted local Kokoro model & voices.
Output: a single non-looping Android WAV: three low warning sweeps, then a
British English woman clearly saying 'God Mode Enabled.'
This is a developer build tool, not an app runtime dependency.
"""
import argparse
import hashlib
import math
from pathlib import Path

import numpy as np
import soundfile as sf
from kokoro_onnx import Kokoro

MODEL_HASH = "ae315a79b623f244700e4afb9246c46a26066782e049ba174bf3ba433970ee9c"
VOICES_HASH = "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d"


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def alarm(rate: int) -> np.ndarray:
    """Three ominous descending dual-tone pulses, softly gated."""
    length = int(rate * 2.50)
    t = np.arange(length, dtype=np.float32) / rate
    sound = np.zeros(length, dtype=np.float32)
    for start in (0.04, 0.83, 1.62):
        u = t - start
        active = (u >= 0) & (u < 0.66)
        q = np.clip(u, 0, 0.66)
        env = np.maximum(0, np.minimum(np.minimum(1, q / .075),
                                         np.minimum(1, (.66 - q) / .20))) ** 1.25
        phase = 2 * math.pi * (285 * q - 142 * q ** 2 / (2 * .66))
        sound += np.where(active,
            env * (.35 * np.sin(phase) + .14 * np.sin(phase * 1.51)
                   + .19 * np.sin(phase * .5)), 0)
    edge = np.maximum(0, np.minimum.reduce((
        np.ones(length, dtype=np.float32), t / .04, (2.50 - t) / .23)))
    return np.clip(sound * edge, -.9, .9) * .74


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    model = args.model_dir / "kokoro-v1.0.int8.onnx"
    voices = args.model_dir / "voices-v1.0.bin"
    if sha256(model) != MODEL_HASH or sha256(voices) != VOICES_HASH:
        raise RuntimeError("Unverified voice model or voice pack")
    engine = Kokoro(str(model), str(voices))
    speech, rate = engine.create("God Mode Enabled.",
        voice="bf_emma", speed=.91, lang="en-gb", trim=False)
    if rate != 24000:
        raise RuntimeError(f"Unexpected voice output rate {rate}")
    speech = np.asarray(speech, dtype=np.float32)
    if speech.ndim != 1 or not (rate * .6 < len(speech) < rate * 5):
        raise RuntimeError("Voice synthesis returned invalid duration")
    peak = max(1e-5, float(np.max(np.abs(speech))))
    speech = np.clip(speech * min(1.35, .80 / peak), -.90, .90)
    tail = min(int(rate * .12), len(speech))
    speech[-tail:] *= np.linspace(1, 0, tail, dtype=np.float32)
    sound = np.concatenate((
        alarm(rate),
        np.zeros(int(rate * .20), dtype=np.float32),
        np.zeros(int(rate * .26), dtype=np.float32), # no clipped first syllable
        speech,
        np.zeros(int(rate * .40), dtype=np.float32)
    ))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(args.output), sound, rate, subtype="PCM_16")
    data, got_rate = sf.read(str(args.output), dtype="float32")
    if got_rate != rate or len(data) != len(sound):
        raise RuntimeError("Audio readback mismatch")
    if not np.all(data[-int(rate*.3):] == 0):
        raise RuntimeError("Audio has no silent tail")
    print(f"PHANTOM_AUDIO_READY {args.output.name} duration={len(sound)/rate:.3f}s "
          f"bytes={args.output.stat().st_size} sha256={sha256(args.output)}")
    print("Sequence: three original descending alarm sweeps; British woman: God Mode Enabled.")


if __name__ == "__main__":
    main()
