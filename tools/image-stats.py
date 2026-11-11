#!/usr/bin/env python3
"""Quantitative checks on the MapSelfTest screenshots.

For every PNG in tools/selftest-results/*/:
  * mean / stdev RGB and brightness (a black or single-colour shot is a failure),
  * percentage of near-black, near-white and exact-FALLBACK-grey pixels,
  * a coarse 8x8 luminance+chroma fingerprint so two styles of the same world can be compared
    (identical fingerprints = the style switch had no effect).

The FALLBACK grey of voxymap is (0.5,0.5,0.5) linear-ish; in the 8-bit screenshot it lands around
128,128,128. Exact (128,128,128) and near-grey (|r-g|,|g-b| <= 2) coverage is reported so residual
slot-0 / FALLBACK tiles are visible numerically even when they are too small to notice by eye.
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

from PIL import Image

GREY = (128, 128, 128)


def stats(path: Path) -> dict:
    with Image.open(path) as im:
        im = im.convert("RGBA")
        # the map is drawn in a window smaller than 1280x720; ignore fully transparent pixels
        px = list(im.getdata())
    total = len(px)
    opaque = [p for p in px if p[3] > 0]
    n = max(1, len(opaque))
    sums = [0, 0, 0]
    sq = [0, 0, 0]
    black = white = exact_grey = near_grey = 0
    for r, g, b, _a in opaque:
        sums[0] += r; sums[1] += g; sums[2] += b
        sq[0] += r * r; sq[1] += g * g; sq[2] += b * b
        lum = (r * 299 + g * 587 + b * 114) // 1000
        if lum < 8:
            black += 1
        if lum > 250:
            white += 1
        if abs(r - GREY[0]) <= 1 and abs(g - GREY[1]) <= 1 and abs(b - GREY[2]) <= 1:
            exact_grey += 1
        elif abs(r - g) <= 2 and abs(g - b) <= 2 and abs(r - b) <= 2 and 60 <= lum <= 200:
            near_grey += 1
    mean = [round(s / n, 2) for s in sums]
    var = [max(0.0, sq[i] / n - (sums[i] / n) ** 2) for i in range(3)]
    std = [round(v ** 0.5, 2) for v in var]
    return {
        "file": path.name,
        "size": list(im.size),
        "opaquePct": round(100.0 * len(opaque) / total, 1),
        "meanRGB": mean,
        "stdRGB": std,
        "blackPct": round(100.0 * black / n, 2),
        "whitePct": round(100.0 * white / n, 2),
        "exactGreyPct": round(100.0 * exact_grey / n, 2),
        "nearGreyPct": round(100.0 * near_grey / n, 2),
        "nonBlack": std[0] > 1 or std[1] > 1 or std[2] > 1,
    }


def fingerprint(path: Path, grid: int = 8) -> list[float]:
    with Image.open(path) as im:
        im = im.convert("RGB").resize((grid, grid), Image.BILINEAR)
        out = []
        for r, g, b in im.getdata():
            out.extend([round(r / 255.0, 3), round(g / 255.0, 3), round(b / 255.0, 3)])
    return out


def fp_dist(a: list[float], b: list[float]) -> float:
    return round(sum((x - y) ** 2 for x, y in zip(a, b)) / max(1, len(a)) ** 0.5, 4)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent / "selftest-results"))
    ap.add_argument("--json", default="")
    args = ap.parse_args()
    root = Path(args.root)

    pngs = sorted(root.rglob("*.png"))
    if not pngs:
        print(f"no screenshots under {root}")
        return 1

    rows = []
    by_tag: dict[str, list[Path]] = {}
    for p in pngs:
        tag = p.parent.name
        by_tag.setdefault(tag, []).append(p)
        s = stats(p)
        s["tag"] = tag
        rows.append(s)

    print(f"{'file':52} {'meanRGB':22} {'stdRGB':20} {'black%':>6} {'white%':>6} {'grey%':>6} {'ngrey%':>7}")
    print("-" * 138)
    for s in rows:
        print(f"{s['tag'] + '/' + s['file']:52} {str(s['meanRGB']):22} {str(s['stdRGB']):20} "
              f"{s['blackPct']:>6} {s['whitePct']:>6} {s['exactGreyPct']:>6} {s['nearGreyPct']:>7}")

    # per-world style comparison: current-2d vs blocks-2d etc.
    print()
    print("style-difference (8x8 fingerprint distance; 0 = identical rendering)")
    pairs = []
    for tag, files in sorted(by_tag.items()):
        d = {f.name: f for f in files}
        names = sorted(d)
        for a, b in itertools.combinations(names, 2):
            try:
                dist = fp_dist(fingerprint(d[a]), fingerprint(d[b]))
            except Exception as exc:  # pragma: no cover
                print(f"  {tag}: {a} vs {b}: ERROR {exc}")
                continue
            pairs.append({"tag": tag, "a": a, "b": b, "distance": dist})
    for p in pairs:
        flag = "" if p["distance"] > 0.002 else "  <-- IDENTICAL"
        print(f"  {p['tag']:24} {p['a']:26} vs {p['b']:26} d={p['distance']}{flag}")

    if args.json:
        Path(args.json).write_text(
            json.dumps({"stats": rows, "pairs": pairs}, indent=2), encoding="utf-8")
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
