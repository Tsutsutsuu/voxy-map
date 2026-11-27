#!/usr/bin/env python3
"""Count knowingly-grey (FALLBACK) map pixels in voxymap screenshots.

Grey alone is NOT evidence: the palette's stone classes are grey too (STONE #7E7E7E,
ANDESITE #888889, GRAVEL #847F7F, DIORITE #BDBCBD) and hillshade/contours shift every colour.
So this script prefers the two unambiguous modes:

  1. magenta  - run with -DebugFallback: FALLBACK (slot 0) pixels are painted 255,0,255.
  2. exact    - run with -NoShading (hillshade+contours off): FALLBACK is exactly #8C8C8C,
                which no palette class uses.

The old soft-grey heuristic is still reported last but is only an upper bound.

Usage:
  python tools/analyze-grey.py <png> [more pngs...] [-m]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

FALLBACK = np.array([0x8C, 0x8C, 0x8C], dtype=np.int16)
MAGENTA = np.array([0xFF, 0x00, 0xFF], dtype=np.int16)


def analyse(path: Path, write_mask: bool) -> None:
    img = Image.open(path).convert("RGB")
    a = np.asarray(img, dtype=np.int16)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]

    magenta = np.all(a == MAGENTA, axis=2)
    exact = np.all(a == FALLBACK, axis=2)
    cert = magenta | exact

    mx = a.max(axis=2)
    mn = a.min(axis=2)
    soft = ((mx - mn) <= 8) & (((r + g + b) // 3) >= 85) & (((r + g + b) // 3) <= 190)

    def pct(mask):
        n = int(mask.sum())
        return n, 100.0 * n / mask.size

    mc, mp = pct(magenta)
    ec, ep = pct(exact)
    cc, cp = pct(cert)
    sc, sp = pct(soft)
    ys, xs = np.nonzero(cert)
    bbox = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())) if cc else None
    print(f"{path.name}: magenta={mc} ({mp:.3f}%) exactFallback={ec} ({ep:.3f}%) "
          f"certain={cc} ({cp:.3f}%) bbox={bbox} | softGrey(upperBound)={sc} ({sp:.2f}%)")

    if write_mask and cc:
        out = np.zeros_like(a, dtype=np.uint8)
        out[:, :, 0] = np.where(cert, 255, 0).astype(np.uint8)
        Image.fromarray(out, "RGB").save(path.with_name(path.stem + "-fallback-mask.png"))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "-m"]
    mask = "-m" in sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    for p in args:
        analyse(Path(p), mask)
