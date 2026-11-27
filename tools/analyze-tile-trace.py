#!/usr/bin/env python3
"""Summarise a voxymap tile trace (logs/voxymap-tile-*.log) for pipeline investigations.

Usage:
  python tools/analyze-tile-trace.py <tile-trace.log> [more logs...]

Reports:
  * per-stage event counts / timing percentiles (took=NNms lines)
  * dropped-work ratios (submitted work that never produced a callback)
  * per-tile repeat counts for texture_build / color_done / cache_submit / src_request
  * a per-second timeline of the key stages, so rescan/drop storms are visible
"""
import re
import sys
from collections import Counter, defaultdict

LINE = re.compile(
    r"^(?P<time>\d\d:\d\d:\d\d\.\d\d\d) (?P<thread>.+?) tile=(?P<tx>-?\d+),(?P<tz>-?\d+) "
    r"(?P<stage>\S+)(?: took=(?P<took>[\d.]+)ms)?(?: (?P<detail>.*))?$")
SECOND = re.compile(r"^(\d\d):(\d\d):(\d\d)")


def pct(values, p):
    if not values:
        return 0.0
    values = sorted(values)
    idx = min(len(values) - 1, max(0, int(round((len(values) - 1) * p))))
    return values[idx]


def fmt_ms(v):
    return f"{v:8.2f}"


def analyse(path):
    print("=" * 100)
    print(f"TRACE {path}")
    print("=" * 100)
    counts = Counter()
    durations = defaultdict(list)
    per_tile = defaultdict(Counter)
    per_second = defaultdict(Counter)
    second_start = None
    dropped = 0
    split_samples = []

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.startswith("# dropped"):
                dropped += int(line.split()[2])
                continue
            m = LINE.match(line)
            if not m:
                continue
            stage = m.group("stage")
            counts[stage] += 1
            tx, tz = int(m.group("tx")), int(m.group("tz"))
            if tx >= 0 and tz >= 0 or True:
                pass
            per_tile[(tx, tz)][stage] += 1
            sec = m.group("time")[:8]
            per_second[sec][stage] += 1
            if second_start is None:
                second_start = sec
            took = m.group("took")
            if took is not None and took != "":
                durations[stage].append(float(took))
            if stage == "index_scan_start":
                per_second[sec]["!rescan"] += 1
            if stage == "index_invalidate":
                per_second[sec]["!invalidate"] += 1

    hh, mm, ss = (int(x) for x in second_start.split(":"))
    start_sec = hh * 3600 + mm * 60 + ss

    print("\n-- stage counts --")
    for stage, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        d = durations.get(stage, [])
        if d:
            print(f"{stage:22s} {n:7d}  mean={fmt_ms(sum(d)/len(d))}  "
                  f"p50={fmt_ms(pct(d,0.5))}  p95={fmt_ms(pct(d,0.95))}  max={fmt_ms(max(d))}  (n={len(d)})")
        else:
            print(f"{stage:22s} {n:7d}")

    if dropped:
        print(f"\n!! trace queue dropped {dropped} lines (trace is lossy)")

    print("\n-- dropped / redundant work --")
    submits = counts["cache_submit"]
    resolved = counts["cache_hit"] + counts["cache_miss"] + counts["cache_no_file"] + \
        counts["cache_bad_header"] + counts["cache_geom_mismatch"] + counts["cache_bad_payload"] + \
        counts["cache_read_error"]
    src_submit = counts["src_submit"] + counts["src_request"] + counts["src_fill_request"]
    src_done = counts["src_hit"] + counts["src_empty"] + counts["src_fill_hit"] + counts["src_fill_empty"]
    color_req = counts["color_request"]
    color_done = counts["color_done"] + counts["color_stale"] + counts["color_cancelled"] + \
        counts["color_empty"] + counts["color_error"]
    print(f"cache_submit={submits}  resolved={resolved}  unresolved={submits-resolved}")
    print(f"src(requests)={src_submit}  src(done)={src_done}  unresolved={src_submit-src_done}")
    print(f"color_request={color_req}  color_results={color_done}  unresolved={color_req-color_done}")
    print(f"texture_build={counts['texture_build']}  texture_done={counts['texture_done']}")
    print(f"index_scan_start={counts['index_scan_start']}  index_invalidate={counts['index_invalidate']}  "
          f"index_cache_hit={counts['index_cache_hit']}  index_cache_stale={counts['index_cache_stale']}  "
          f"index_cache_save={counts['index_cache_save']}")

    print("\n-- repeated work per tile (top 12 tiles) --")
    def repeat_ranking(stage, limit=12):
        rows = [(tile, c[stage]) for tile, c in per_tile.items() if c[stage] > 1]
        rows.sort(key=lambda kv: -kv[1])
        total = sum(n for _, n in rows)
        many = sum(1 for _, n in rows if n >= 5)
        print(f"{stage:16s} tiles_with_repeats={len(rows):5d}  events={total:6d}  tiles>=5x={many:5d}")
        for (tx, tz), n in rows[:limit]:
            print(f"    tile=({tx},{tz}) {stage}={n}")
    for st in ("texture_build", "color_done", "color_request", "color_stale",
               "cache_submit", "cache_hit", "src_request", "src_fill_hit", "evict"):
        repeat_ranking(st)

    print("\n-- per-second timeline (only seconds with activity) --")
    keys = sorted(per_second)
    print("   t(s) | rescan inval scanms | cache sub/hit/miss | src req/hit | color req/done/stale/cancel | tex | evict")
    for sec in keys:
        c = per_second[sec]
        hh2, mm2, ss2 = (int(x) for x in sec.split(":"))
        t = hh2 * 3600 + mm2 * 60 + ss2 - start_sec
        scan = durations.get("index_scan_done", [])
        print(f"  {t:5d} | {c['!rescan']:6d} {c['!invalidate']:5d}        | "
              f"{c['cache_submit']:5d} {c['cache_hit']:3d} {c['cache_miss']:5d} | "
              f"{c['src_request'] + c['src_fill_request']:3d} {c['src_hit'] + c['src_fill_hit']:3d} | "
              f"{c['color_request']:5d} {c['color_done']:4d} {c['color_stale']:5d} {c['color_cancelled']:6d} | "
              f"{c['texture_build']:3d} | {c['evict']:4d}")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    for p in sys.argv[1:]:
        analyse(p)
