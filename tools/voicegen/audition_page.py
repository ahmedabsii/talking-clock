"""Build out/audition/audition.html: one self-contained page with players for every
audition voice (clips embedded as data: URIs), plus a per-voice reel of all six phrases.

    python audition_page.py
"""

from __future__ import annotations

import base64
import csv
import html
import json
import subprocess

from common import AUDITION_DIR, AUDITION_KEYS, load_phrases

ORDER = ["kokoro-af_heart", "kokoro-af_bella", "kokoro-am_michael", "kokoro-am_fenrir",
         "kokoro-bf_emma", "kokoro-bm_george", "chatterbox-default-ex0.35", "chatterbox-default-ex0.7"]
BLURB = {
    "kokoro-af_heart": "Kokoro · American female (Heart)",
    "kokoro-af_bella": "Kokoro · American female (Bella)",
    "kokoro-am_michael": "Kokoro · American male (Michael)",
    "kokoro-am_fenrir": "Kokoro · American male (Fenrir)",
    "kokoro-bf_emma": "Kokoro · British female (Emma)",
    "kokoro-bm_george": "Kokoro · British male (George)",
    "chatterbox-default-ex0.35": "Chatterbox · default voice, calm (exaggeration 0.35)",
    "chatterbox-default-ex0.7": "Chatterbox · default voice, expressive (exaggeration 0.7)",
}


def data_uri(path) -> str:
    return "data:audio/ogg;base64," + base64.b64encode(path.read_bytes()).decode()


def make_reel(voice_dir, out) -> None:
    """Concatenate the six clips with 0.7 s gaps into one Opus file."""
    inputs, filt = [], []
    for i, (fmt, k) in enumerate(AUDITION_KEYS):
        inputs += ["-i", str(voice_dir / fmt / f"{k}.ogg")]
        filt.append(f"[{i}:a]apad=pad_dur=0.7[a{i}]")
    filt.append("".join(f"[a{i}]" for i in range(len(AUDITION_KEYS)))
                + f"concat=n={len(AUDITION_KEYS)}:v=0:a=1[out]")
    subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *inputs,
                    "-filter_complex", ";".join(filt), "-map", "[out]", "-c:a", "libopus",
                    "-b:a", "40k", "-application", "audio", str(out)], check=True)


def main() -> None:
    text = {(p.format, p.hhmm): p.text for p in load_phrases()}
    voices = [v for v in ORDER if (AUDITION_DIR / v).is_dir()]
    reels = AUDITION_DIR / "reels"
    reels.mkdir(exist_ok=True)

    cards = []
    for v in voices:
        d = AUDITION_DIR / v
        reel = reels / f"{v}.ogg"
        make_reel(d, reel)
        heard = {}
        if (d / "qa.csv").exists():
            with (d / "qa.csv").open() as f:
                heard = {(r["format"], r["hhmm"]): r for r in csv.DictReader(f)}
        rows = []
        for fmt, k in AUDITION_KEYS:
            q = heard.get((fmt, k), {})
            ok = {"pass": "✓", "fail": "✗"}.get(q.get("pass", ""), "")
            rows.append(
                f"<tr><td class=fmt>{'12h' if fmt == 'h12' else '24h'}</td>"
                f"<td>{html.escape(text[fmt, k])}</td>"
                f"<td><audio controls preload=none src='{data_uri(d / fmt / f'{k}.ogg')}'></audio></td>"
                f"<td class=asr title='what Whisper heard'>{ok} {html.escape(q.get('heard', ''))}</td></tr>")
        cards.append(
            f"<section><header><h2>{html.escape(BLURB.get(v, v))}</h2><code>{v}</code></header>"
            f"<div class=reel>All six: <audio controls preload=none src='{data_uri(reel)}'></audio></div>"
            f"<table>{''.join(rows)}</table></section>")

    page = f"""<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>Voice Audition</title>
<style>
:root {{ --bg:#fbfaf7; --fg:#1d1c1a; --muted:#6b6862; --card:#fff; --line:#e6e2da; --accent:#2f6f5e; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#161614; --fg:#ecebe6; --muted:#a19e96; --card:#1f1f1c; --line:#33322e; --accent:#7cc4ad; }} }}
* {{ box-sizing:border-box }}
body {{ margin:0; padding:24px 16px 48px; background:var(--bg); color:var(--fg);
       font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif }}
main {{ max-width:960px; margin:0 auto }}
h1 {{ font-size:22px; margin:0 0 4px }} p.lede {{ color:var(--muted); margin:0 0 20px }}
section {{ background:var(--card); border:1px solid var(--line); border-radius:12px; padding:14px 16px; margin:0 0 14px }}
header {{ display:flex; gap:10px; align-items:baseline; flex-wrap:wrap }}
h2 {{ font-size:16px; margin:0 }} code {{ color:var(--muted); font-size:12px }}
.reel {{ display:flex; gap:10px; align-items:center; margin:10px 0 6px; color:var(--accent); font-weight:600 }}
table {{ width:100%; border-collapse:collapse }} td {{ padding:4px 6px; border-top:1px solid var(--line); vertical-align:middle }}
td.fmt {{ color:var(--muted); width:36px }} td.asr {{ color:var(--muted); font-size:13px }}
audio {{ height:32px; width:220px; max-width:100% }}
@media (max-width:640px) {{ td.asr {{ display:none }} audio {{ width:160px }} }}
</style></head><body><main>
<h1>Talking Clock: voice audition</h1>
<p class=lede>Same six phrases per voice, already trimmed, loudness-normalized (−14 LUFS) and encoded as
in the app (48 kHz Opus, 40 kbps). Grey text is what the Whisper check heard.</p>
{''.join(cards)}
</main></body></html>"""
    out = AUDITION_DIR / "audition.html"
    out.write_text(page, encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size // 1024} KB) and {len(voices)} reels in {reels}")
    print(json.dumps(voices))


if __name__ == "__main__":
    main()
