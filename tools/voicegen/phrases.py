"""Generate phrases.csv: one spoken sentence per minute of the day, for each clock format.

    python phrases.py            # writes phrases.csv
    python phrases.py --sample   # also prints a sample of rows

Columns: format (h12|h24), hhmm (24-hour key 0000-2359, the asset file name), text.
The text is the natural written form of what the voice says; render.py may respell
a few tokens per engine (e.g. "P.M.") so the engine pronounces them correctly.
"""

from __future__ import annotations

import argparse
import csv

from common import FORMATS, PHRASES_CSV, all_hhmm

ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"]
TENS = {20: "twenty", 30: "thirty", 40: "forty", 50: "fifty"}


def number_words(n: int) -> str:
    """0-59 as words, hyphenated like 'forty-seven'."""
    if n < 20:
        return ONES[n]
    tens, ones = divmod(n, 10)
    return TENS[tens * 10] + (f"-{ONES[ones]}" if ones else "")


def minute_words(m: int) -> str:
    """Minutes 1-59 as read on a clock: 5 -> 'oh five', 30 -> 'thirty'."""
    return f"oh {ONES[m]}" if m < 10 else number_words(m)


def phrase_h12(h: int, m: int) -> str:
    if (h, m) == (0, 0):
        return "It's midnight."
    if (h, m) == (12, 0):
        return "It's twelve noon."
    hour = number_words(h % 12 or 12)
    suffix = "A.M." if h < 12 else "P.M."
    if m == 0:
        return f"It's {hour} o'clock {suffix}"
    return f"It's {hour} {minute_words(m)} {suffix}"


def phrase_h24(h: int, m: int) -> str:
    if (h, m) == (0, 0):
        return "It's midnight."
    if h == 0:
        hour = "zero"
    elif h < 10:
        hour = f"oh {ONES[h]}"
    else:
        hour = number_words(h)
    if m == 0:
        return f"It's {hour} hundred."
    return f"It's {hour} {minute_words(m)}."


def build_rows() -> list[dict[str, str]]:
    makers = {"h12": phrase_h12, "h24": phrase_h24}
    rows = []
    for fmt in FORMATS:
        for hhmm in all_hhmm():
            h, m = int(hhmm[:2]), int(hhmm[2:])
            rows.append({"format": fmt, "hhmm": hhmm, "text": makers[fmt](h, m)})
    return rows


SAMPLE_KEYS = ["0000", "0001", "0005", "0030", "0100", "0905", "0910", "0959", "1159",
               "1200", "1201", "1215", "1300", "1515", "1547", "2000", "2347", "2359"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", action="store_true", help="print sample rows after writing")
    args = ap.parse_args()

    rows = build_rows()
    with PHRASES_CSV.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["format", "hhmm", "text"], lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print(f"wrote {len(rows)} rows to {PHRASES_CSV.name}")

    if args.sample:
        by_key = {(r["format"], r["hhmm"]): r["text"] for r in rows}
        print(f"{'time':<6} {'12-hour':<34} 24-hour")
        for k in SAMPLE_KEYS:
            print(f"{k[:2]}:{k[2:]}  {by_key['h12', k]:<34} {by_key['h24', k]}")


if __name__ == "__main__":
    main()
