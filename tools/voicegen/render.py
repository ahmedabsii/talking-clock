"""Render phrases.csv to WAV with a local neural TTS engine (GPU).

    python render.py --engine kokoro --voice af_heart --only audition
    python render.py --engine kokoro --voice af_heart --only all
    python render.py --engine chatterbox --voice default --exaggeration 0.5 --only all
    python render.py --retry --keys h12/0905 h24/1500     # re-render with the next seed

Output: <out>/<format>/<HHMM>.wav (24 kHz mono float). Default <out> is out/wav for
--only all and out/audition/<label>/wav for --only audition.

* Resumable: existing WAVs are skipped (use --force to redo them).
* Deterministic: every clip gets a fixed seed derived from --seed, its key and its
  attempt number; settings and per-clip seeds are kept in <out>/render_state.json.
* --retry bumps the attempt number of the given keys (new seed; for Kokoro, which
  is deterministic, a slightly different speed) and re-renders them using the
  settings saved in render_state.json.
* Failures are logged to <out>/render_failures.log and do not stop the run.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import traceback
import zlib
from pathlib import Path

import common  # noqa: F401  (sets model cache env vars before torch/HF imports)
from common import AUDITION_DIR, WAV_DIR, Phrase, load_phrases, select

import numpy as np
import soundfile as sf
import torch

# Per-engine respellings applied to the CSV text right before synthesis, for tokens
# the engine's front end would otherwise mispronounce. phrases.csv keeps the plain
# written form (it is also the ASR reference text).
RESPELL = {
    "kokoro": {},
    "chatterbox": {},
}

# Kokoro has no sampling, so a new seed alone would give an identical clip; retries
# nudge the speaking rate instead.
KOKORO_RETRY_SPEED = (1.0, 0.95, 1.05, 0.92, 1.08)

MIN_SECONDS, MAX_SECONDS = 0.4, 6.0


def respell(engine: str, text: str) -> str:
    for src, dst in RESPELL[engine].items():
        text = text.replace(src, dst)
    return text


def clip_seed(base: int, key: str, attempt: int) -> int:
    return (base + zlib.crc32(key.encode()) + attempt * 7919) % (2**31 - 1)


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


class KokoroEngine:
    sr = 24_000

    def __init__(self, voice: str, speed: float):
        from kokoro import KModel, KPipeline

        self.voice = voice
        self.speed = speed
        model = KModel(repo_id="hexgrad/Kokoro-82M").to("cuda").eval()
        # American voices start with a*, British with b*.
        lang = {"a": "a", "b": "b"}[voice[0]]
        self.pipeline = KPipeline(lang_code=lang, repo_id="hexgrad/Kokoro-82M", model=model)

    def synth(self, text: str, attempt: int) -> tuple[np.ndarray, dict]:
        speed = self.speed * KOKORO_RETRY_SPEED[attempt % len(KOKORO_RETRY_SPEED)]
        chunks, phonemes = [], []
        with torch.inference_mode():
            for result in self.pipeline(text, voice=self.voice, speed=speed, split_pattern=None):
                chunks.append(result.audio.detach().cpu().numpy())
                phonemes.append(result.phonemes)
        return np.concatenate(chunks), {"speed": round(speed, 4), "phonemes": " ".join(phonemes)}


class ChatterboxEngine:
    def __init__(self, voice: str, exaggeration: float, cfg_weight: float, temperature: float):
        from chatterbox.tts import ChatterboxTTS

        self.model = ChatterboxTTS.from_pretrained(device="cuda")
        self.sr = self.model.sr
        self.prompt = None if voice == "default" else voice
        self.kw = dict(exaggeration=exaggeration, cfg_weight=cfg_weight, temperature=temperature)

    def synth(self, text: str, attempt: int) -> tuple[np.ndarray, dict]:
        with torch.inference_mode():
            wav = self.model.generate(text, audio_prompt_path=self.prompt, **self.kw)
        return wav.squeeze(0).detach().cpu().numpy(), {}


def default_out(args) -> Path:
    if args.only == "audition":
        return AUDITION_DIR / label(args) / "wav"
    return WAV_DIR


def label(args) -> str:
    if args.engine == "chatterbox":
        return f"chatterbox-{Path(args.voice).stem}-ex{args.exaggeration:g}"
    return f"kokoro-{args.voice}"


def load_state(out: Path) -> dict:
    path = out / "render_state.json"
    if path.exists():
        return json.loads(path.read_text())
    return {"settings": None, "clips": {}}


def save_state(out: Path, state: dict) -> None:
    tmp = out / "render_state.json.tmp"
    tmp.write_text(json.dumps(state, indent=1, sort_keys=True))
    tmp.replace(out / "render_state.json")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--engine", choices=["kokoro", "chatterbox"])
    ap.add_argument("--voice", help="Kokoro voice id (af_heart, bm_george, ...) or, for "
                    "Chatterbox, 'default' or a path to a reference WAV")
    ap.add_argument("--only", choices=["audition", "all"], default="all")
    ap.add_argument("--keys", nargs="+", metavar="FMT/HHMM", help="limit to these clips, e.g. h12/0905")
    ap.add_argument("--out", type=Path, help="output folder (default depends on --only)")
    ap.add_argument("--seed", type=int, default=20261026)
    ap.add_argument("--speed", type=float, default=1.0, help="Kokoro speaking rate")
    ap.add_argument("--exaggeration", type=float, default=0.5, help="Chatterbox expressiveness")
    ap.add_argument("--cfg-weight", type=float, default=0.5, help="Chatterbox CFG weight (pace)")
    ap.add_argument("--temperature", type=float, default=0.8, help="Chatterbox sampling temperature")
    ap.add_argument("--force", action="store_true", help="re-render clips that already exist")
    ap.add_argument("--retry", action="store_true", help="bump the attempt of --keys and re-render "
                    "them with the settings saved in render_state.json")
    args = ap.parse_args()

    out = args.out or (WAV_DIR if args.retry else None)
    if out is None:
        if not (args.engine and args.voice):
            ap.error("--engine and --voice are required")
        out = default_out(args)
    out.mkdir(parents=True, exist_ok=True)
    state = load_state(out)

    if args.retry:
        if not args.keys or not state["settings"]:
            ap.error("--retry needs --keys and an existing render_state.json in the output folder")
        for k, v in state["settings"].items():
            setattr(args, k, v)
    settings = {k: getattr(args, k) for k in
                ("engine", "voice", "seed", "speed", "exaggeration", "cfg_weight", "temperature")}
    if state["settings"] and state["settings"] != settings:
        print(f"note: settings differ from the ones saved in {out}/render_state.json; "
              f"existing clips are kept unless --force", file=sys.stderr)
    state["settings"] = settings

    phrases: list[Phrase] = select(load_phrases(), args.only, args.keys)
    todo = []
    for p in phrases:
        path = out / p.format / f"{p.hhmm}.wav"
        if args.retry:
            state["clips"].setdefault(p.key, {"attempt": 0})["attempt"] += 1
        elif path.exists() and not args.force:
            continue
        todo.append((p, path))
    print(f"{len(phrases)} selected, {len(todo)} to render -> {out}")
    if not todo:
        return 0

    if args.engine == "kokoro":
        engine = KokoroEngine(args.voice, args.speed)
    else:
        engine = ChatterboxEngine(args.voice, args.exaggeration, args.cfg_weight, args.temperature)

    failures = 0
    log = (out / "render_failures.log").open("a", encoding="utf-8")
    t0 = time.time()
    for i, (p, path) in enumerate(todo, 1):
        clip = state["clips"].setdefault(p.key, {"attempt": 0})
        attempt = clip["attempt"]
        seed = clip_seed(args.seed, p.key, attempt)
        text = respell(args.engine, p.text)
        try:
            seed_everything(seed)
            audio, extra = engine.synth(text, attempt)
            audio = np.asarray(audio, dtype=np.float32)
            dur = len(audio) / engine.sr
            if not np.isfinite(audio).all() or not (MIN_SECONDS <= dur <= MAX_SECONDS):
                raise ValueError(f"implausible output: {dur:.2f}s, finite={np.isfinite(audio).all()}")
            path.parent.mkdir(parents=True, exist_ok=True)
            sf.write(path, audio, engine.sr, subtype="FLOAT")
            clip.update(seed=seed, text=text, seconds=round(dur, 3), **extra)
        except Exception as e:  # keep going; the failure is logged and retried later
            failures += 1
            msg = f"{time.strftime('%F %T')} {p.key} attempt={attempt} seed={seed} {type(e).__name__}: {e}"
            print("FAIL", msg, file=sys.stderr)
            log.write(msg + "\n" + traceback.format_exc() + "\n")
            log.flush()
        if i % 50 == 0 or i == len(todo):
            save_state(out, state)
            rate = i / (time.time() - t0)
            print(f"  {i}/{len(todo)}  {rate:.1f} clips/s  eta {(len(todo) - i) / rate / 60:.1f} min", flush=True)
    save_state(out, state)
    print(f"done: {len(todo) - failures} rendered, {failures} failed (see {out}/render_failures.log)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
