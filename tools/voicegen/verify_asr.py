"""Check every final clip with faster-whisper (GPU) and write qa_report.csv.

    python verify_asr.py                       # check app/src/main/assets/voice/en
    python verify_asr.py --fix                 # re-render failures (new seed), up to 3 rounds
    python verify_asr.py --assets out/audition/kokoro-af_heart --only audition --report out/audition/qa.csv

A clip passes when
  * the transcript, normalized (digits -> clock words, a.m./p.m., punctuation, case),
    equals the phrases.csv text,
  * its integrated loudness is within +/-1 LU of -14 LUFS and its true peak <= -1 dBTP,
  * its duration is plausible for its syllable count (catches babble / dropped words
    the recognizer papered over).
Keys listed in qa_approved.txt (one 'h12/0905' per line) were listened to and accepted
by a person; they are reported as 'approved' even if the checks above disagree.
"""

from __future__ import annotations

import argparse
import csv
import gc
import itertools
import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

import common  # noqa: F401  (cache env vars)
from common import (CACHE, HERE, QA_REPORT, TARGET_LUFS, TRUE_PEAK_DBTP, VOICE_ASSETS, WAV_DIR,
                    load_phrases, select)

import numpy as np
import pyloudnorm as pyln
import soundfile as sf
from scipy.signal import resample_poly

APPROVED = HERE / "qa_approved.txt"
LUFS_TOL = 1.0
DURATION_TOL_S = 0.4
MAX_ROUNDS = 3

ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"]
TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]


# ---------------------------------------------------------------- text normalization
def cardinal(n: int) -> str:
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + (f" {ONES[n % 10]}" if n % 10 else "")
    raise ValueError(n)


def hour_readings(s: str) -> list[str]:
    n = int(s)
    if s in ("0", "00"):
        return ["zero", "oh", "zero zero", "oh oh"]
    if s.startswith("0"):                       # "09" was spoken with a leading zero
        return [f"oh {ONES[n]}", f"zero {ONES[n]}"]
    return [cardinal(n)]


def minute_readings(s: str) -> list[str]:
    n = int(s)
    if n == 0:
        return ["hundred", "oclock", "", "oh oh", "zero zero", "hundred hours"]
    if n < 10:
        return [f"oh {ONES[n]}", f"zero {ONES[n]}"]
    return [cardinal(n)]


def number_readings(tok: str) -> list[str]:
    """Spoken readings of a digit token the recognizer wrote: '3:15', '09.05', '1500', '7'."""
    m = re.fullmatch(r"(\d{1,2})[:.](\d{2})", tok)
    if m is None and re.fullmatch(r"\d{3,4}", tok):
        m = re.fullmatch(r"(\d{1,2})(\d{2})", tok)
    if m:
        return [f"{h} {mm}".strip() for h in hour_readings(m[1]) for mm in minute_readings(m[2])]
    if tok == "0":                              # "0 0 5" is how Whisper writes "zero oh five"
        return ["zero", "oh"]
    if re.fullmatch(r"\d{1,2}", tok):
        return hour_readings(tok) if len(tok) == 2 and tok.startswith("0") else [cardinal(int(tok))]
    return [tok]


def normalize(text: str) -> list[str]:
    """All normalized word strings a transcript may stand for (digits are ambiguous)."""
    s = text.lower().replace("’", "'")
    s = re.sub(r"(?<=\d),(?=\d{3}\b)", "", s)               # 1,500 -> 1500
    s = re.sub(r"(\d)([a-z])", r"\1 \2", s)                 # 9am -> 9 am
    s = re.sub(r"\b([ap])\s*\.?\s*m\b\.?", r" \1m ", s)     # a.m. / a. m. / am -> am
    s = re.sub(r"\bo\s*'?\s*clock\b", " oclock ", s)
    s = re.sub(r"\bit\s*'?\s*s\b|\bit is\b", " its ", s)
    s = s.replace("-", " ")
    toks = re.findall(r"\d{1,2}[:.]\d{2}|\d+|[a-z]+", s)
    toks = ["oh" if t == "o" else t for t in toks]           # "nine o five"
    options = [number_readings(t) if t[0].isdigit() else [t] for t in toks]
    out = []
    for combo in itertools.product(*options):
        out.append(" ".join(w for w in " ".join(combo).split()))
        if len(out) > 512:
            break
    return out


def text_matches(expected: str, heard: str) -> bool:
    return normalize(expected)[0] in set(normalize(heard))


def syllables(text: str) -> int:
    words = normalize(text)[0].split()
    n = 0
    for w in words:
        w = {"pm": "pee em", "am": "ay em", "oclock": "oclock"}.get(w, w)
        n += sum(max(1, len(re.findall(r"[aeiouy]+", x.rstrip("e") or x))) for x in w.split())
    return n


# ---------------------------------------------------------------- audio measurements
def measure(path: Path) -> tuple[np.ndarray, float, float, float]:
    """(16 kHz audio for ASR, duration s, integrated LUFS, true peak dBTP)."""
    x, sr = sf.read(path, dtype="float64", always_2d=True)
    x = x.mean(axis=1)
    lufs = pyln.Meter(sr).integrated_loudness(x)
    tp = 20 * np.log10(max(np.max(np.abs(resample_poly(x, 4, 1))), 1e-12))
    g = np.gcd(sr, 16000)
    x16 = resample_poly(x, 16000 // g, sr // g).astype(np.float32)
    return x16, len(x) / sr, lufs, tp


def load_whisper(name: str):
    import torch  # noqa: F401  (loads the CUDA/cuDNN libraries ctranslate2 links against)
    from faster_whisper import WhisperModel

    return WhisperModel(name, device="cuda", compute_type="float16",
                        download_root=str(CACHE / "whisper"))


def transcribe(model, audio: np.ndarray) -> str:
    segments, _ = model.transcribe(audio, language="en", beam_size=5, temperature=0.0,
                                   condition_on_previous_text=False, without_timestamps=True,
                                   vad_filter=False)
    return " ".join(s.text.strip() for s in segments).strip()


def check(model, assets: Path, phrases) -> dict[str, dict]:
    rows = {}
    for i, p in enumerate(phrases, 1):
        path = assets / p.format / f"{p.hhmm}.ogg"
        row = {"format": p.format, "hhmm": p.hhmm, "expected": p.text, "heard": "",
               "duration_s": "", "lufs": "", "tp_dbtp": "", "pass": "fail", "note": ""}
        if not path.exists():
            row["note"] = "missing"
        else:
            audio, dur, lufs, tp = measure(path)
            heard = transcribe(model, audio)
            row.update(heard=heard, duration_s=f"{dur:.3f}", lufs=f"{lufs:.2f}", tp_dbtp=f"{tp:.2f}")
            notes = []
            if not text_matches(p.text, heard):
                notes.append("text")
            if abs(lufs - TARGET_LUFS) > LUFS_TOL or tp > TRUE_PEAK_DBTP:
                notes.append("loudness")
            row["note"] = " ".join(notes)
        rows[p.key] = row
        if i % 100 == 0:
            print(f"  checked {i}/{len(phrases)}", flush=True)
    return rows


def flag_durations(rows: dict[str, dict]) -> None:
    """Mark clips whose duration is far from a linear fit on syllable count.

    Clip length is a fixed overhead plus a per-syllable time, so a fit (rather than a
    seconds-per-syllable ratio, which is unfair to short phrases) exposes babble,
    trailing noise or a dropped word as a large residual.
    """
    keys = [k for k, r in rows.items() if r["duration_s"]]
    if len(keys) < 20:
        return
    syl = np.array([syllables(rows[k]["expected"]) for k in keys], dtype=float)
    dur = np.array([float(rows[k]["duration_s"]) for k in keys])
    slope, icept = np.polyfit(syl, dur, 1)
    res = dur - (icept + slope * syl)
    limit = max(DURATION_TOL_S, 6 * statistics.median(np.abs(res - np.median(res))))
    for k, r in zip(keys, res):
        note = rows[k]["note"].replace("duration", "").strip()
        rows[k]["note"] = (note + " duration").strip() if abs(r) > limit else note


def finalize(rows: dict[str, dict], approved: set[str]) -> None:
    for k, r in rows.items():
        r["pass"] = "approved" if k in approved and r["note"] != "missing" else (
            "pass" if not r["note"] else "fail")


def run(cmd: list[str]) -> None:
    print("$", " ".join(cmd), flush=True)
    subprocess.run([sys.executable, *cmd], cwd=HERE, check=False)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--assets", type=Path, default=VOICE_ASSETS)
    ap.add_argument("--wav", type=Path, default=WAV_DIR, help="render folder used by --fix")
    ap.add_argument("--only", choices=["audition", "all"], default="all")
    ap.add_argument("--keys", nargs="+", metavar="FMT/HHMM")
    ap.add_argument("--report", type=Path, default=QA_REPORT)
    ap.add_argument("--model", default="large-v3")
    ap.add_argument("--fix", action="store_true", help=f"re-render failing clips, up to {MAX_ROUNDS} rounds")
    args = ap.parse_args()

    phrases = select(load_phrases(), args.only, args.keys)
    by_key = {p.key: p for p in phrases}
    approved = set(APPROVED.read_text().split()) if APPROVED.exists() else set()

    model = load_whisper(args.model)
    rows = check(model, args.assets, phrases)
    flag_durations(rows)

    for rnd in range(1, MAX_ROUNDS + 1 if args.fix else 1):
        bad = [k for k, r in rows.items() if r["note"] and k not in approved]
        rerender = [k for k in bad if set(rows[k]["note"].split()) & {"text", "duration", "missing"}]
        reencode = [k for k in bad if k not in rerender]
        if not bad:
            break
        print(f"round {rnd}: re-rendering {len(rerender)}, re-encoding {len(reencode)}")
        del model
        gc.collect()
        if rerender:
            run(["render.py", "--retry", "--out", str(args.wav), "--keys", *rerender])
            run(["postprocess.py", "--in", str(args.wav), "--out", str(args.assets),
                 "--force", "--keys", *rerender])
        if reencode:
            run(["postprocess.py", "--in", str(args.wav), "--out", str(args.assets),
                 "--force", "--margin-db", str(0.6 + 0.4 * rnd), "--keys", *reencode])
        model = load_whisper(args.model)
        rows.update(check(model, args.assets, [by_key[k] for k in bad]))
        flag_durations(rows)

    finalize(rows, approved)
    state = args.wav / "render_state.json"
    clips = json.loads(state.read_text())["clips"] if state.exists() else {}
    fields = ["format", "hhmm", "expected", "heard", "duration_s", "lufs", "pass",
              "tp_dbtp", "attempts", "note"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        for k in sorted(rows):
            w.writerow({**rows[k], "attempts": clips.get(k, {}).get("attempt", 0)})

    vals = [float(r["lufs"]) for r in rows.values() if r["lufs"]]
    fails = [r for r in rows.values() if r["pass"] == "fail"]
    n_ok = sum(r["pass"] != "fail" for r in rows.values())
    print(f"{n_ok}/{len(rows)} pass ({sum(r['pass'] == 'approved' for r in rows.values())} approved); "
          f"LUFS {min(vals):.2f}..{max(vals):.2f}" if vals else "no clips measured")
    for r in fails:
        print(f"  FAIL {r['format']}/{r['hhmm']}  [{r['note']}]  expected {r['expected']!r}  heard {r['heard']!r}")
    print(f"report: {args.report}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
