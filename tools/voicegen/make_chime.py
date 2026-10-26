"""Synthesize the optional pre-announcement chime: a soft two-note bell ("ding-dong",
E5 then C5) made from scratch with numpy, so there is no third-party sample to license.

    python make_chime.py            # -> app/src/main/assets/chime.ogg (+ out/chime.wav preview)

Loudness: 4 LU under the voice clips (-18 LUFS integrated), true peak <= -1 dBTP,
same Opus encoding as the voice.
"""

from __future__ import annotations

import numpy as np
import soundfile as sf
from scipy.signal import butter, fftconvolve, sosfilt

from common import ASSETS, OUT, SAMPLE_RATE, TARGET_LUFS
from postprocess import encode_opus, fade, normalize

SR = SAMPLE_RATE
CHIME_LUFS = TARGET_LUFS - 4.0
NOTES = ((659.26, 0.0), (523.25, 0.42))          # E5, then C5 a beat later (Hz, start s)
LENGTH_S = 2.1
# Bell-like partials (frequency ratio, amplitude, decay time constant s). Mostly the
# fundamental and its octave, with a few faint inharmonic overtones for the "bell"
# colour; higher partials die away fast, which keeps it soft rather than clangy.
PARTIALS = ((1.0, 1.00, 0.95), (2.0, 0.28, 0.45), (2.76, 0.08, 0.22),
            (4.07, 0.035, 0.14), (5.40, 0.015, 0.09))


def bell(freq: float, n: int, rng: np.random.Generator) -> np.ndarray:
    t = np.arange(n) / SR
    y = np.zeros(n)
    for ratio, amp, tau in PARTIALS:
        phase = rng.uniform(0, 2 * np.pi)
        y += amp * np.exp(-t / tau) * np.sin(2 * np.pi * freq * ratio * t + phase)
    # A slightly detuned copy of the fundamental gives a slow, gentle shimmer.
    y += 0.25 * np.exp(-t / 1.1) * np.sin(2 * np.pi * (freq + 0.9) * t)
    attack = int(0.008 * SR)                       # soft mallet, no click
    y[:attack] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, attack))
    return y


def room(n: int, rng: np.random.Generator) -> np.ndarray:
    """Small synthetic room: decaying, low-passed noise impulse response (RT60 ~0.5 s)."""
    t = np.arange(n) / SR
    ir = rng.standard_normal(n) * np.exp(-6.9 * t / 0.5)
    ir = sosfilt(butter(2, 4000, fs=SR, output="sos"), ir)
    ir /= np.sqrt(np.sum(ir ** 2))
    return ir


def main() -> None:
    rng = np.random.default_rng(20261026)
    n = int(LENGTH_S * SR)
    dry = np.zeros(n)
    for freq, start in NOTES:
        s = int(start * SR)
        dry[s:] += bell(freq, n - s, rng)
    wet = fftconvolve(dry, room(int(0.6 * SR), rng))[:n]
    y = 0.85 * dry + 0.35 * wet / (np.max(np.abs(wet)) / np.max(np.abs(dry)))
    tail = int(0.35 * SR)                          # let the ring fade out smoothly
    y[-tail:] *= np.linspace(1, 0, tail) ** 2
    y = normalize(fade(y), target_lufs=CHIME_LUFS)

    OUT.mkdir(exist_ok=True)
    sf.write(OUT / "chime.wav", y.astype(np.float32), SR, subtype="FLOAT")
    dst = ASSETS / "chime.ogg"
    encode_opus(y, dst)
    print(f"wrote {dst} ({dst.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
