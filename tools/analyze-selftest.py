#!/usr/bin/env python3
"""Parse tools/selftest-results/<tag>/ artifacts and evaluate the voxymap acceptance criteria.

Usage:
    python tools/analyze-selftest.py                       # summary to stdout
    python tools/analyze-selftest.py --json out.json       # also write machine-readable summary
    python tools/analyze-selftest.py --root tools/selftest-results

Reads, per tag:
  report.json     - written by com.voxymap.MapSelfTest inside the game
  tile-trace.log  - voxymap tile trace (DEBUG_TILE_TRACE), lines like
                    "HH:MM:SS.mmm voxymap-color tile=1,0 color_work took=1557.38ms px=73984"

Acceptance (from the task):
  A1  every run result == PASS, no errors, no missing styles
  A2  blocks single-tile color_work median/mean <= current +5%
  A3  feature cold current firstOpenMs <= baseline cold current +3%
  A4  warm reopen firstOpenMs < cold firstOpenMs (cache actually works)
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

COLOR_WORK_RE = re.compile(r"color_work took=([0-9.]+)ms")
STYLE_ORDER = ["current", "terrain", "heightmap", "satellite", "caves", "biomes", "blocks"]


def repair_json(raw: str) -> str:
    """MapSelfTest omits the comma after the scalar "quietTicks" field and after the one-line
    "log" array (Java StringBuilder bug in the harness; it cannot be rebuilt from here).
    Insert the missing separators line-by-line: if a line starts a new "key": value and the
    previous non-empty line does not end with a value separator, add the comma."""
    out: list[str] = []
    prev = ""
    for line in raw.splitlines():
        stripped = line.rstrip()
        if prev and re.match(r'\s*"[^"]+"\s*:', stripped) and not re.search(r"[,{\[]\s*$", prev):
            out[-1] = out[-1].rstrip() + ","
        out.append(line)
        if stripped:
            prev = stripped
    return "\n".join(out)


def load_report(tag_dir: Path) -> dict | None:
    p = tag_dir / "report.json"
    if not p.is_file():
        return None
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            raw = p.read_text(encoding=enc)
        except UnicodeDecodeError:
            continue
        for text in (raw, repair_json(raw)):
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                continue
    return None


def parse_trace(tag_dir: Path) -> dict:
    p = tag_dir / "tile-trace.log"
    out = {"file": str(p) if p.is_file() else None, "color_work": [], "stages": {}}
    if not p.is_file():
        return out
    with p.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = COLOR_WORK_RE.search(line)
            if m:
                out["color_work"].append(float(m.group(1)))
                continue
            stage = re.search(r" voxymap-\w+ tile=[-\d,]+ (\w+) ", line)
            if stage and "took=" not in line:
                out["stages"][stage.group(1)] = out["stages"].get(stage.group(1), 0) + 1
    return out


def stats(values: list[float]) -> dict | None:
    if not values:
        return None
    s = sorted(values)

    def pct(p: float) -> float:
        if len(s) == 1:
            return s[0]
        k = max(0, min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1)))))
        return s[k]

    return {
        "count": len(s),
        "mean": round(statistics.fmean(s), 2),
        "median": round(statistics.median(s), 2),
        "p90": round(pct(90), 2),
        "min": round(s[0], 2),
        "max": round(s[-1], 2),
    }


def get(d: dict | None, key: str, default=None):
    return d.get(key, default) if isinstance(d, dict) else default


def fmt_ms(v) -> str:
    return "-" if v is None else f"{v:g}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent / "selftest-results"))
    ap.add_argument("--json", default="")
    ap.add_argument("--pct", type=float, default=5.0, help="blocks color_work allowance over current, %%")
    ap.add_argument("--first-pct", type=float, default=3.0, help="cold first-open allowance over baseline, %%")
    args = ap.parse_args()

    root = Path(args.root)
    tags = sorted(d.name for d in root.iterdir() if d.is_dir()) if root.is_dir() else []
    print(f"results root: {root}")
    print(f"tags: {', '.join(tags) if tags else '(none)'}")

    reports: dict[str, dict] = {}
    traces: dict[str, dict] = {}
    for tag in tags:
        d = root / tag
        r = load_report(d)
        if r is not None:
            reports[tag] = r
        t = parse_trace(d)
        if t["color_work"] or t["file"]:
            traces[tag] = t

    # ---- per-run table -------------------------------------------------------
    print()
    hdr = (f"{'tag':28} {'result':7} {'cold':5} {'firstOpen':>10} {'fStyle':10} {'worldRdy':>9} "
           f"{'quiet':>7} {'mapOpen':>8} {'styles':>6} {'miss':>4} {'err':>3} {'warn':>4} {'3D':>8}")
    print(hdr)
    print("-" * len(hdr))
    for tag in tags:
        r = reports.get(tag)
        if r is None:
            print(f"{tag:28} {'NO REPORT':7}  (no report.json - harness did not finish)")
            continue
        v3 = get(r, "view3d") or {}
        d3 = f"{v3.get('entered')}/{v3.get('parity')}" if v3.get("tested") else "-"
        print(f"{tag:28} {str(get(r,'result')):7} {str(get(r,'cold')):5} "
              f"{fmt_ms(get(r,'firstOpenMs')):>10} {str(get(r,'firstOpenStyle')):10} "
              f"{fmt_ms(get(r,'worldReadyMs')):>9} {fmt_ms(get(r,'worldQuietMs')):>7} "
              f"{fmt_ms(get(r,'mapOpenMs')):>8} {len(get(r,'styleNames') or []):>6} "
              f"{len(get(r,'missingStyles') or []):>4} {len(get(r,'errors') or []):>3} "
              f"{len(get(r,'warnings') or []):>4} {d3:>8}")

    # ---- per-style settle table ---------------------------------------------
    print()
    print("per-style settle (ms)  [settleMs / colorRequests / colorDone / colorStale / textureBuilds]")
    for tag in tags:
        r = reports.get(tag)
        if not r:
            continue
        styles = get(r, "styles") or []
        if not styles:
            continue
        print(f"  {tag}:")
        for s in styles:
            print(f"    {str(s.get('name')):12} settle={fmt_ms(s.get('settleMs')):>8} "
                  f"req={s.get('colorRequests')} done={s.get('colorDone')} "
                  f"stale={s.get('colorStale')} tex={s.get('textureBuilds')} shot={s.get('screenshot')}")

    # ---- tile trace stats ----------------------------------------------------
    print()
    print("color_work tile stats (TileTrace, DEBUG_TILE_TRACE)")
    trace_stats: dict[str, dict] = {}
    for tag, t in traces.items():
        st = stats(t["color_work"])
        trace_stats[tag] = st or {}
        extra = ", ".join(f"{k}x{v}" for k, v in sorted(t["stages"].items())) or "-"
        if st:
            print(f"  {tag:28} {st}   other={extra}")
        else:
            print(f"  {tag:28} (no color_work lines)   other={extra}")

    # ---- acceptance ----------------------------------------------------------
    print()
    print("ACCEPTANCE")
    checks: list[tuple[str, bool, str]] = []

    # A1) every report PASS, no errors, no missing styles
    bad = []
    for tag, r in reports.items():
        errs = get(r, "errors") or []
        miss = get(r, "missingStyles") or []
        if get(r, "result") != "PASS" or errs or miss:
            bad.append(f"{tag}(result={get(r,'result')} errors={errs} missing={miss})")
    no_report = [t for t in tags if t not in reports]
    if no_report:
        bad.append(f"no report.json: {no_report}")
    checks.append(("A1 all runs PASS, no errors/missing styles", not bad, "; ".join(bad) or "all clean"))

    # A2) blocks vs current color_work (warm runs with clean trace)
    cur_tag, blk_tag = "w1-warm-current", "w1-warm-blocks"
    cs, bs = trace_stats.get(cur_tag, {}), trace_stats.get(blk_tag, {})
    if cs.get("median") is not None and bs.get("median") is not None:
        for key in ("median", "mean"):
            alw = cs[key] * (1.0 + args.pct / 100.0)
            ratio = bs[key] / cs[key] if cs[key] else float("inf")
            ok = bs[key] <= alw
            checks.append((f"A2 color_work {key} blocks <= current+{args.pct:g}%",
                           ok, f"current={cs[key]} blocks={bs[key]} ratio={ratio:.4f} "
                               f"allowed<={alw:.2f}"))
    else:
        checks.append((f"A2 color_work blocks<=current+{args.pct:g}%", False,
                       f"missing trace stats ({cur_tag}={bool(cs)}, {blk_tag}={bool(bs)})"))

    # A3) cold first open: feature vs baseline
    base_tag, feat_tag, blk_cold = "w1-cold-current-baseline", "w1-cold-current-feature", "w1-cold-blocks-feature"
    b = get(reports.get(base_tag), "firstOpenMs")
    f = get(reports.get(feat_tag), "firstOpenMs")
    if b and f:
        alw = b * (1.0 + args.first_pct / 100.0)
        checks.append((f"A3 cold firstOpen feature <= baseline+{args.first_pct:g}%",
                       f <= alw, f"baseline={b} feature={f} ratio={f / b:.4f} allowed<={alw:.1f}"))
        fb = get(reports.get(blk_cold), "firstOpenMs")
        if fb:
            checks.append(("A3b (info) cold firstOpen blocks vs baseline", True,
                           f"baseline={b} blocks={fb} ratio={fb / b:.4f} (+{100 * (fb / b - 1):.2f}%)"))
        # cold feature vs cold baseline vs the also-relevant feature cold blocks
    else:
        checks.append((f"A3 cold firstOpen feature<=baseline+{args.first_pct:g}%", False,
                       f"missing firstOpenMs (baseline={b}, feature={f})"))

    # A4) warm reopen cheaper than cold
    warm = get(reports.get("w1-warm-reopen"), "firstOpenMs")
    cold = f
    if warm and cold:
        checks.append(("A4 warm reopen firstOpen < cold firstOpen (cache works)",
                       warm < cold, f"warm={warm} cold={cold} ({100.0 * warm / cold:.1f}% of cold)"))
    else:
        checks.append(("A4 warm reopen firstOpen < cold firstOpen (cache works)", False,
                       f"missing (warm={warm}, cold={cold})"))

    for name, ok, detail in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}\n         {detail}")

    verdict = all(ok for _, ok, _ in checks) and not no_report
    print()
    print(f"OVERALL: {'PASS' if verdict else 'FAIL'}")

    if args.json:
        out = {
            "resultsRoot": str(root),
            "tags": tags,
            "reports": reports,
            "traces": {t: {"file": v["file"], "stats": trace_stats.get(t, {}),
                           "otherStages": v["stages"]} for t, v in traces.items()},
            "checks": [{"name": n, "pass": ok, "detail": d} for n, ok, d in checks],
            "overall": "PASS" if verdict else "FAIL",
        }
        Path(args.json).write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"wrote {args.json}")

    return 0 if verdict else 1


if __name__ == "__main__":
    sys.exit(main())
