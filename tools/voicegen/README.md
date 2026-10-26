# voicegen: Talking Clock voice clips

Talking Clock never synthesizes speech on the phone. This tool renders every
announcement (1,440 minutes × 2 formats) on a PC as one natural sentence with a
free, open-source neural voice, then post-processes and checks them. The results
are bundled as app assets.

```
app/src/main/assets/voice/en/h12/HHMM.ogg    "It's three fifteen P.M."
app/src/main/assets/voice/en/h24/HHMM.ogg    "It's fifteen fifteen."
app/src/main/assets/chime.ogg                optional two-note chime
```

`HHMM` is the 24-hour key `0000`–`2359` in **both** folders. The app looks clips up
by this key, so the path contract is fixed.

## Chosen voice

**Kokoro-82M, voice `bf_emma` (British English female)**, picked by ear from an
audition of eight voices (6 Kokoro voices, plus Chatterbox at two expressiveness levels).

```bash
python render.py --engine kokoro --voice bf_emma --only all   # speed 1.0, seed 20261026
```

Kokoro has no sampling, so the render is deterministic. `--seed` only matters for
Chatterbox. The exact settings and per-clip phonemes are kept in
`out/wav/render_state.json`.

## Setup

Requirements: Linux, Python 3.12, an NVIDIA GPU with a CUDA 12 driver (built on an
RTX 2070 8 GB), `ffmpeg` with `libopus`, and [uv](https://docs.astral.sh/uv/) (plain
`python -m venv` + `pip` also works).

```bash
cd tools/voicegen
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -r requirements.txt
```

`requirements.txt` pins the direct dependencies. `requirements-lock.txt` is the full
resolved set the clips were built with. Models download on first use into
`tools/voicegen/.cache/`, which is git-ignored: Kokoro-82M ≈ 0.3 GB, Chatterbox ≈ 3 GB,
Whisper large-v3 ≈ 3 GB. Renders and audition files go to `out/`, also git-ignored.

## Pipeline

All commands run from `tools/voicegen/` with `.venv/bin/python` (or an activated venv).

| Step | Command | Output |
|---|---|---|
| 1. Phrases | `python phrases.py --sample` | `phrases.csv` (`format,hhmm,text`, 2,880 rows) |
| 2. Audition | `python render.py --engine kokoro --voice af_heart --only audition`<br>`python postprocess.py --in out/audition/kokoro-af_heart/wav --out out/audition/kokoro-af_heart`<br>`python audition_page.py` | `out/audition/audition.html` with players, plus `out/audition/reels/*.ogg` |
| 3. Render | `python render.py --engine kokoro --voice <id> --only all` | `out/wav/<format>/<HHMM>.wav` (24 kHz) |
| 4. Post-process | `python postprocess.py` | `app/src/main/assets/voice/en/<format>/<HHMM>.ogg` |
| 5. QA | `python verify_asr.py --fix` | `qa_report.csv` |
| 6. Chime | `python make_chime.py` | `app/src/main/assets/chime.ogg` |

### Phrase rules (`phrases.py`)

| Time | 12-hour | 24-hour |
|---|---|---|
| 15:15 | It's three fifteen P.M. | It's fifteen fifteen. |
| 09:05 | It's nine oh five A.M. | It's oh nine oh five. |
| 15:00 | It's three o'clock P.M. | It's fifteen hundred. |
| 12:00 | It's twelve noon. | It's twelve hundred. |
| 00:00 | It's midnight. | It's midnight. |
| 00:30 | It's twelve thirty A.M. | It's zero thirty. |

`phrases.csv` holds the plain written sentence. It is also the reference text for the
ASR check. If an engine mispronounces a token, add a respelling to `RESPELL` in
`render.py` rather than editing the CSV.

### `render.py`

```
render.py --engine kokoro|chatterbox --voice <id> --only audition|all
          [--keys h12/0905 ...] [--seed N] [--speed 1.0]
          [--exaggeration 0.5 --cfg-weight 0.5 --temperature 0.8]   # Chatterbox
          [--force] [--retry]
```

- **Resumable:** existing WAVs are skipped, so an interrupted run can simply be restarted.
- **Deterministic:** each clip's seed comes from `--seed`, its key and its attempt
  number. Settings and per-clip seeds, speeds and phonemes are stored in
  `out/wav/render_state.json`.
- **`--retry --keys …`:** re-renders those clips with their next attempt number, using the
  saved settings. Chatterbox gets a new seed. Kokoro is deterministic, so it gets a
  slightly different speaking rate instead (×0.95, ×1.05, …).
- **Failures:** logged to `out/wav/render_failures.log`. A failure never stops the run.

### `postprocess.py`

For each clip, in order:

1. Resample to 48 kHz mono.
2. Trim leading and trailing silence: 5 ms frames 45 dB below the loudest frame,
   keeping 15 ms of lead and 50 ms of tail.
3. Add 10 ms raised-cosine fades.
4. Normalize to **−14 LUFS integrated** (pyloudnorm, ITU-R BS.1770), with a look-ahead
   limiter on the 4×-oversampled peak. It holds the true peak under **−1 dBTP** with
   extra headroom for the codec.
5. Encode **Ogg Opus, 40 kbps VBR, `-application audio`**, with metadata stripped.
6. Decode the `.ogg` and measure it. Opus shifts loudness a little and peaks erratically
   (up to about 2 dB on a heavily limited clip). Until the encoded file itself is within
   ±0.3 LU of −14 LUFS and ≤ −1.05 dBTP, the step adds 0.4 dB of headroom and/or
   corrects the gain, then re-encodes (up to 8 tries).

Clips whose `.ogg` is newer than their `.wav` are skipped; `--force` redoes them.

### `verify_asr.py`

faster-whisper `large-v3` (float16, GPU) transcribes every final `.ogg`. Before
comparing, the transcript is normalized: case and punctuation, `a.m.`/`AM`/`am`, and
digits become clock words, so `9.05`, `0905` and `15:00` read as "nine oh five",
"oh nine oh five" and "fifteen hundred". The result is compared with `phrases.csv`.

A clip passes when all three hold:

- the text matches;
- loudness is within ±1 LU of −14 LUFS and the true peak is ≤ −1 dBTP;
- its duration per syllable is within 0.6–1.6× of the set's median, which catches
  babble or dropped words.

`--fix` handles failures automatically. A text or duration failure is re-rendered
with the next seed and a loudness failure is re-encoded with more headroom, for up
to 3 rounds. After the last round, any clip still failing is listed for a person to
listen to. Add a clip you've listened to and accepted to `qa_approved.txt` (one
`h12/0905` per line). It then shows as `approved` in `qa_report.csv`.

`qa_report.csv` columns: `format, hhmm, expected, heard, duration_s, lufs, pass,
tp_dbtp, attempts, note`.

### `make_chime.py`

A soft two-note bell ("ding-dong", E5 then C5), synthesized from scratch in numpy:
a few bell-like partials, a gentle attack, and a small synthetic room. It is
normalized 4 LU under the voice (−18 LUFS, ≤ −1 dBTP) and uses the same Opus
settings. No third-party samples are involved.

## Licenses

Both engines and all of the code here allow redistributing the generated audio in a
commercial Play Store app. List these in the app's About / open-source notices
screen:

- **Kokoro-82M** (`hexgrad/Kokoro-82M`) model and voices: **Apache-2.0**. The
  `kokoro` and `misaki` packages are Apache-2.0. misaki's English G2P can fall back to
  espeak-ng (GPL-3.0), but espeak-ng only runs on this PC at render time and is never
  shipped in the app. The generated audio carries no license restriction.
- **Chatterbox** (`ResembleAI/chatterbox`): **MIT**. Every Chatterbox output carries
  Resemble AI's imperceptible **Perth neural watermark**, which their detector can
  identify as AI-generated. That is harmless for a clock app, but worth knowing and
  worth a line in the notices.
- **faster-whisper / Whisper**: MIT. Used only for QA; nothing from it ships.
- **Chime**: original work generated by `make_chime.py`. No third-party material.
