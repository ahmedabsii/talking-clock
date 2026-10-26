"""Turn rendered WAVs into the app's Ogg Opus voice assets.

    python postprocess.py                      # out/wav -> app/src/main/assets/voice/en
    python postprocess.py --in out/audition/kokoro-af_heart/wav --out out/audition/kokoro-af_heart
    python postprocess.py --keys h12/0905 --force

For each <in>/<format>/<HHMM>.wav writes <out>/<format>/<HHMM>.ogg:
  1. resample to 48 kHz mono
  2. trim leading/trailing silence, 10 ms raised-cosine fades
  3. normalize to -14 LUFS integrated (pyloudnorm, BS.1770) with a look-ahead limiter
     holding the 4x-oversampled true peak under -1 dBTP (minus a codec margin)
  4. encode Ogg Opus 40 kbps (libopus, -application audio), metadata stripped
Clips whose .ogg is newer than the .wav are skipped unless --force.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pyloudnorm as pyln
import soundfile as sf
from scipy.ndimage import minimum_filter1d, uniform_filter1d
from scipy.signal import resample_poly

from common import (FORMATS, OPUS_BITRATE, SAMPLE_RATE, TARGET_LUFS, TRUE_PEAK_DBTP,
                    VOICE_ASSETS, WAV_DIR)

FADE_S = 0.010
# Silence = 5 ms frames more than 45 dB below the clip's loudest frame (anything under
# -60 dBFS always counts). Keep a little air so soft onsets and final consonants survive.
TRIM_REL_DB, TRIM_FLOOR_DB = -45.0, -60.0
PAD_HEAD_S, PAD_TAIL_S = 0.015, 0.050
# Opus can overshoot the input peak slightly; limit a bit under the contract.
CODEC_MARGIN_DB = 0.6
LIMITER_LOOKAHEAD_S = 0.004


def db(x: float) -> float:
    return 20 * np.log10(max(x, 1e-12))


def true_peak(x: np.ndarray) -> float:
    """Linear true peak via 4x oversampling (ITU-R BS.1770 style)."""
    return float(np.max(np.abs(resample_poly(x, 4, 1)))) if len(x) else 0.0


def to_48k(x: np.ndarray, sr: int) -> np.ndarray:
    if sr == SAMPLE_RATE:
        return x
    g = np.gcd(sr, SAMPLE_RATE)
    return resample_poly(x, SAMPLE_RATE // g, sr // g)


def trim(x: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    hop = int(sr * 0.005)
    n = len(x) // hop
    if n == 0:
        return x
    rms = np.sqrt(np.mean(x[: n * hop].reshape(n, hop) ** 2, axis=1) + 1e-20)
    lvl = 20 * np.log10(rms)
    thr = max(lvl.max() + TRIM_REL_DB, TRIM_FLOOR_DB)
    loud = np.flatnonzero(lvl > thr)
    start = max(0, loud[0] * hop - int(PAD_HEAD_S * sr))
    end = min(len(x), (loud[-1] + 1) * hop + int(PAD_TAIL_S * sr))
    return x[start:end]


def fade(x: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    n = min(int(FADE_S * sr), len(x) // 2)
    ramp = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, n))
    x = x.copy()
    x[:n] *= ramp
    x[len(x) - n:] *= ramp[::-1]
    return x


def limit(x: np.ndarray, ceiling: float, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Look-ahead brickwall limiter on the oversampled peak envelope."""
    env = np.abs(resample_poly(x, 4, 1)).reshape(-1, 4).max(axis=1)[: len(x)]
    if len(env) < len(x):
        env = np.pad(env, (0, len(x) - len(env)))
    gain = np.minimum(1.0, ceiling / np.maximum(env, 1e-12))
    if gain.min() >= 1.0:
        return x
    w = max(1, int(LIMITER_LOOKAHEAD_S * sr))
    # Min over 2w then a w-wide average keeps the smoothed gain under the raw gain at
    # every peak while ramping into it over ~w samples (no clicks).
    gain = uniform_filter1d(minimum_filter1d(gain, 2 * w + 1), w + 1)
    return x * gain


def normalize(x: np.ndarray, target_lufs: float = TARGET_LUFS,
              tp_dbtp: float = TRUE_PEAK_DBTP, margin_db: float = CODEC_MARGIN_DB) -> np.ndarray:
    meter = pyln.Meter(SAMPLE_RATE)
    ceiling = 10 ** ((tp_dbtp - margin_db) / 20)
    y = x
    for _ in range(6):
        lufs = meter.integrated_loudness(y)
        y = limit(y * 10 ** ((target_lufs - lufs) / 20), ceiling)
        if abs(meter.integrated_loudness(y) - target_lufs) < 0.05:
            break
    # The limiter is followed by a smooth envelope, so a tiny residual overshoot is
    # possible; a final scale keeps the peak strictly at or under the ceiling.
    tp = true_peak(y)
    return y * (ceiling / tp) if tp > ceiling else y


def encode_opus(x: np.ndarray, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".ogg.tmp")
    cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
           "-f", "f32le", "-ar", str(SAMPLE_RATE), "-ac", "1", "-i", "pipe:0",
           "-c:a", "libopus", "-b:a", OPUS_BITRATE, "-vbr", "on", "-application", "audio",
           "-frame_duration", "20", "-map_metadata", "-1", "-f", "ogg", str(tmp)]
    subprocess.run(cmd, input=x.astype("<f4").tobytes(), check=True)
    tmp.replace(path)


def measure_encoded(path: Path) -> tuple[float, float]:
    """(integrated LUFS, true peak dBTP) of the decoded Opus file."""
    y, sr = sf.read(path, dtype="float64")
    return pyln.Meter(sr).integrated_loudness(y), db(true_peak(y))


def process_file(src: Path, dst: Path, margin_db: float = CODEC_MARGIN_DB) -> str:
    audio, sr = sf.read(src, dtype="float64", always_2d=True)
    x = fade(trim(to_48k(audio.mean(axis=1), sr)))
    # Opus changes loudness a little and peaks erratically (up to ~2 dB on a heavily
    # limited clip), so check the decoded result and step the headroom / gain until
    # the encoded clip itself meets the contract.
    target = TARGET_LUFS
    for _ in range(8):
        encode_opus(normalize(x, target_lufs=target, margin_db=margin_db), dst)
        lufs, tp = measure_encoded(dst)
        tp_ok = tp <= TRUE_PEAK_DBTP - 0.05
        if tp_ok and abs(lufs - TARGET_LUFS) <= 0.3:
            break
        if not tp_ok:
            margin_db += 0.4
        target = min(TARGET_LUFS + 1.5, target + TARGET_LUFS - lufs)
    else:
        raise RuntimeError(f"could not meet loudness contract: {lufs:.2f} LUFS, {tp:.2f} dBTP")
    return str(dst)


def _job(a):
    src, dst, margin = a
    try:
        return process_file(src, dst, margin), None
    except Exception as e:
        return str(dst), f"{type(e).__name__}: {e}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="src", type=Path, default=WAV_DIR)
    ap.add_argument("--out", type=Path, default=VOICE_ASSETS)
    ap.add_argument("--keys", nargs="+", metavar="FMT/HHMM")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--margin-db", type=float, default=CODEC_MARGIN_DB,
                    help="extra true-peak headroom for the codec (dB)")
    ap.add_argument("--jobs", type=int, default=6)
    args = ap.parse_args()

    jobs = []
    for fmt in FORMATS:
        for src in sorted((args.src / fmt).glob("*.wav")):
            key = f"{fmt}/{src.stem}"
            if args.keys and key not in args.keys:
                continue
            dst = args.out / fmt / f"{src.stem}.ogg"
            if not args.force and dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime:
                continue
            jobs.append((src, dst, args.margin_db))
    print(f"{len(jobs)} clips to process -> {args.out}")
    errors = 0
    with ProcessPoolExecutor(args.jobs) as pool:
        for i, (dst, err) in enumerate(pool.map(_job, jobs, chunksize=8), 1):
            if err:
                errors += 1
                print(f"FAIL {dst}: {err}", file=sys.stderr)
            if i % 250 == 0:
                print(f"  {i}/{len(jobs)}", flush=True)
    print(f"done: {len(jobs) - errors} written, {errors} failed")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
