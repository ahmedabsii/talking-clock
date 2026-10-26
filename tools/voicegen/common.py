"""Shared paths and helpers for the voicegen tools.

Importing this module also points the Hugging Face / torch caches at
tools/voicegen/.cache so model downloads stay inside the (git-ignored) tool folder.
"""

from __future__ import annotations

import csv
import os
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
CACHE = HERE / ".cache"
OUT = HERE / "out"
WAV_DIR = OUT / "wav"
AUDITION_DIR = OUT / "audition"
PHRASES_CSV = HERE / "phrases.csv"
QA_REPORT = HERE / "qa_report.csv"
ASSETS = REPO / "app" / "src" / "main" / "assets"
VOICE_ASSETS = ASSETS / "voice" / "en"

FORMATS = ("h12", "h24")

# Final clip loudness contract (shared by postprocess.py, verify_asr.py, make_chime.py).
TARGET_LUFS = -14.0
TRUE_PEAK_DBTP = -1.0
SAMPLE_RATE = 48_000
OPUS_BITRATE = "40k"

# The same six phrases are used for every audition voice: both formats, an "oh five"
# in each, noon, midnight, an "hundred" and a two-word minute.
AUDITION_KEYS = (
    ("h12", "0905"),
    ("h12", "1547"),
    ("h12", "1200"),
    ("h24", "0000"),
    ("h24", "0905"),
    ("h24", "1500"),
)

os.environ.setdefault("HF_HOME", str(CACHE / "huggingface"))
os.environ.setdefault("TORCH_HOME", str(CACHE / "torch"))
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")


@dataclass(frozen=True)
class Phrase:
    format: str
    hhmm: str
    text: str

    @property
    def key(self) -> str:
        return f"{self.format}/{self.hhmm}"


def all_hhmm() -> list[str]:
    return [f"{h:02d}{m:02d}" for h in range(24) for m in range(60)]


def load_phrases(path: Path = PHRASES_CSV) -> list[Phrase]:
    with path.open(newline="", encoding="utf-8") as f:
        rows = [Phrase(r["format"], r["hhmm"], r["text"]) for r in csv.DictReader(f)]
    expected = {(fmt, k) for fmt in FORMATS for k in all_hhmm()}
    got = {(p.format, p.hhmm) for p in rows}
    if got != expected or len(rows) != len(expected):
        raise SystemExit(f"{path} must have exactly one row per format x minute "
                         f"({len(expected)} rows); found {len(rows)}")
    return rows


def select(phrases: list[Phrase], only: str, keys: list[str] | None = None) -> list[Phrase]:
    """Pick the phrases for --only audition|all, optionally narrowed to 'h12/0905'-style keys."""
    if only == "audition":
        wanted = {f"{f}/{k}" for f, k in AUDITION_KEYS}
        phrases = [p for p in phrases if p.key in wanted]
    if keys:
        wanted = set(keys)
        phrases = [p for p in phrases if p.key in wanted]
    return phrases
