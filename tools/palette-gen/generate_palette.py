#!/usr/bin/env python3
"""Generate the 200-colour block palette (58 existing colours + 142 new) and the block->colour
mapping for the "blocks" map style.

Pipeline
--------
1. Read the 26.2 block list + top-face texture names from the census CSV (``full-block-audit.csv``).
2. Read the actual PNG textures from the client jar and compute each block's *display colour*:
   - untinted blocks: alpha-weighted average of the top-face texture (all animation frames),
   - fixed-tint blocks (spruce/birch leaves, lily pad): texture average * vanilla tint,
   - biome-tinted blocks are excluded and assigned to GRASS/FOLIAGE/WATER special classes.
3. Keep the existing 54 static palette colours and add 142 colours:
   - 16 dyed wool colours and 16 dyed concrete colours (computed from their textures) as seeds,
   - the rest as weighted k-means clusters in CIELAB over the remaining blocks,
   - near-duplicate new colours are merged, empty clusters are refilled by splitting the worst
     cluster, so the palette ends at exactly 200 colours (including FALLBACK).
4. Assign every visible block to its nearest palette colour ("mapping redesign"), write the new
   ``surface_classes.json`` (v7), a review CSV, a swatch PNG and a report.

Usage
-----
    python tools/palette-gen/generate_palette.py [--jar PATH] [--dry-run]

Requires numpy + Pillow (both are part of the bundled Python runtime).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
CSV_BLOCKS = REPO / "doc/review/surface-census/full-block-audit.csv"
CSV_SURFACE = REPO / "doc/review/surface-census/surface-review.csv"
OLD_JSON = REPO / "stonecutter/src/main/resources/assets/voxymap/surface_classes.json"
BASE_JSON = REPO / "tools/palette-gen/base-palette.json"
MOD_JSON = REPO / "mod/src/main/resources/assets/voxymap/surface_classes.json"
OUT_DIR = REPO / "doc/review/surface-census"
DEFAULT_JAR = Path(r"D:\Minecraft\AxolotlLauncher\Data\meta\versions\26.2-0.20.1\26.2-0.20.1.jar")

PALETTE_SIZE = 200
# 1 FALLBACK + 4 tint classes (GRASS, FOLIAGE, WATER, DRY_FOLIAGE) + static colour clusters
STATIC_TARGET = PALETTE_SIZE - 5
SEED_DYE_NAMES = ["white", "orange", "magenta", "light_blue", "yellow", "lime",
                  "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                  "red", "black"]

# Vanilla BlockColors: blocks that receive a biome tint (see doc/review/mc26.2-src/.../BlockColors.java)
BIOME_TINT_GRASS = {
    "minecraft:grass_block", "minecraft:short_grass", "minecraft:fern", "minecraft:tall_grass",
    "minecraft:large_fern", "minecraft:potted_fern", "minecraft:bush", "minecraft:pink_petals",
    "minecraft:wildflowers", "minecraft:sugar_cane", "minecraft:melon_stem",
    "minecraft:pumpkin_stem", "minecraft:attached_melon_stem", "minecraft:attached_pumpkin_stem",
}
BIOME_TINT_FOLIAGE = {
    "minecraft:oak_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves",
    "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves", "minecraft:vine",
}
# vanilla BlockTintSources.dryFoliage(): leaf litter uses the dry-foliage colormap, not foliage
BIOME_TINT_DRY_FOLIAGE = {"minecraft:leaf_litter"}
BIOME_TINT_WATER = {"minecraft:water", "minecraft:bubble_column", "minecraft:water_cauldron"}
# Vanilla fixed tints (BlockTintSources.constant / water colours)
FIXED_TINT = {
    "minecraft:spruce_leaves": (0x61, 0x99, 0x61),
    "minecraft:birch_leaves": (0x80, 0xA7, 0x55),
    "minecraft:lily_pad": (0x20, 0x80, 0x30),
}
DTYPE = np.float64

# Blocks whose census top_texture is empty or whose vanilla render texture is not named after the
# block.  They are still visible map surfaces (thin/transparent blocks count as a top block), so
# they must get a colour like every other block.
TEXTURE_OVERRIDES = {
    "minecraft:redstone_wire": ("redstone_dust_dot", (0xFF, 0x00, 0x00)),
}


# ---------------------------------------------------------------- colour helpers

def srgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    c = rgb / 255.0
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    t = (m @ c) / np.array([0.95047, 1.0, 1.08883])
    f = np.where(t > 0.008856, np.cbrt(t), 7.787 * t + 16.0 / 116.0)
    return np.array([116.0 * f[1] - 16.0, 500.0 * (f[0] - f[1]), 200.0 * (f[1] - f[2])])


def lab_to_srgb(lab: np.ndarray) -> np.ndarray:
    fy = (lab[0] + 16.0) / 116.0
    fx = fy + lab[1] / 500.0
    fz = fy - lab[2] / 200.0
    xyz = np.array([fx ** 3, fy ** 3, fz ** 3])
    xyz = np.where(xyz > 0.008856, xyz, (xyz - 16.0 / 116.0) / 7.787)
    xyz = xyz * np.array([0.95047, 1.0, 1.08883])
    m_inv = np.array([[3.2404542, -1.5371385, -0.4985314],
                      [-0.9692660, 1.8760108, 0.0415560],
                      [0.0556434, -0.2040259, 1.0572252]])
    c = m_inv @ xyz
    c = np.where(c <= 0.0031308, 12.92 * c, 1.055 * np.power(np.clip(c, 0, None), 1 / 2.4) - 0.055)
    return np.clip(np.rint(c * 255.0), 0, 255).astype(int)


def hex_to_rgb(hexstr: str) -> np.ndarray:
    h = hexstr.strip().lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=DTYPE)


def rgb_to_hex(rgb: np.ndarray) -> str:
    return "#%02X%02X%02X" % tuple(int(v) for v in np.clip(np.rint(rgb), 0, 255))


def de76(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.sqrt(((np.asarray(a) - np.asarray(b)) ** 2).sum()))


def de76_matrix(x: np.ndarray, c: np.ndarray) -> np.ndarray:
    return np.sqrt(((x[:, None, :] - c[None, :, :]) ** 2).sum(axis=2))


# ---------------------------------------------------------------- texture reading

class TextureReader:
    def __init__(self, jar_path: Path):
        self.zip = zipfile.ZipFile(jar_path)
        self.names = set(self.zip.namelist())
        self.cache: dict[str, tuple[np.ndarray, float] | None] = {}

    def average(self, tex: str) -> tuple[np.ndarray, float] | None:
        """Alpha-weighted sRGB average of block/<tex>.png; returns (rgb, alpha_coverage)."""
        if tex in self.cache:
            return self.cache[tex]
        result = None
        for path in (f"assets/minecraft/textures/block/{tex}.png",
                     f"assets/minecraft/textures/{tex}.png"):
            if path not in self.names:
                continue
            try:
                img = Image.open(io.BytesIO(self.zip.read(path))).convert("RGBA")
            except Exception:
                continue
            arr = np.asarray(img, dtype=np.float64)
            if arr.size == 0:
                continue
            a = arr[..., 3] / 255.0
            mask = a > 0
            if not mask.any():
                continue
            px = arr[mask]
            weights = px[:, 3] / 255.0
            if weights.sum() < 0.05:
                weights = np.ones_like(weights)
            rgb = (px[:, :3] * weights[:, None]).sum(axis=0) / weights.sum()
            coverage = float(mask.mean())
            result = (rgb, coverage)
            break
        self.cache[tex] = result
        return result


# ---------------------------------------------------------------- clustering

def init_kmeanspp(x: np.ndarray, w: np.ndarray, k: int, rng: np.random.Generator) -> np.ndarray:
    centers = [x[rng.choice(len(x), p=w)]]
    for _ in range(1, k):
        d = de76_matrix(x, np.array(centers))
        p = w * d.min(axis=1)
        p = p / p.sum()
        centers.append(x[rng.choice(len(x), p=p)])
    return np.array(centers)


def run_kmeans(x, w, fixed, free_count, iterations=100):
    rng = np.random.default_rng(200)
    free = init_kmeanspp(x, w, free_count, rng) if free_count > 0 else np.zeros((0, 3))
    centers = np.vstack([fixed, free]) if free_count > 0 else fixed.copy()
    n_fixed = len(fixed)
    for _ in range(iterations):
        assign = de76_matrix(x, centers).argmin(axis=1)
        new_centers = centers.copy()
        for j in range(n_fixed, len(centers)):
            m = assign == j
            if w[m].sum() > 1e-12:
                new_centers[j] = (x[m] * w[m, None]).sum(axis=0) / w[m].sum()
        if np.allclose(new_centers, centers):
            centers = new_centers
            break
        centers = new_centers
    return centers, n_fixed


def tidy_free_centers(x, w, fixed, centers, n_fixed, target_free):
    """Merge new colours that are within de76<2 of an old/other colour; refill by splitting."""
    centers = centers.copy()
    for _ in range(200):
        # prune near-duplicate free centers (existing colours always win)
        removed = False
        for i in range(n_fixed, len(centers)):
            for j in range(len(centers)):
                if i == j or j == i:
                    continue
                if de76(centers[i], centers[j]) < 2.0:
                    centers = np.delete(centers, i, axis=0)
                    removed = True
                    break
            if removed:
                break
        if removed:
            continue
        free_now = len(centers) - n_fixed
        if free_now == target_free:
            break
        if free_now < target_free:
            # split the free cluster with the largest weighted squared error
            assign = de76_matrix(x, centers).argmin(axis=1)
            err = np.zeros(len(centers))
            for j in range(n_fixed, len(centers)):
                m = assign == j
                if m.any():
                    err[j] = (w[m] * ((x[m] - centers[j]) ** 2).sum(axis=1)).sum()
            j = int(np.argmax(err))
            m = assign == j
            if not m.any():
                break
            # new center at the member farthest from the current center
            idx = np.where(m)[0]
            far = idx[np.argmax(de76_matrix(x[idx], centers[j:j + 1]).ravel())]
            centers = np.vstack([centers, x[far]])
        else:  # free_now > target_free: drop the smallest free cluster center farthest from data
            assign = de76_matrix(x, centers).argmin(axis=1)
            counts = np.array([(assign == j).sum() for j in range(len(centers))])
            drop = None
            for j in range(len(centers) - 1, n_fixed - 1, -1):
                if counts[j] == 0:
                    drop = j
                    break
            if drop is None:
                drop = n_fixed + int(np.argmin(counts[n_fixed:]))
            centers = np.delete(centers, drop, axis=0)
    return centers, n_fixed


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=str(DEFAULT_JAR))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        print(f"client jar not found: {jar}", file=sys.stderr)
        return 1
    tex = TextureReader(jar)

    if BASE_JSON.exists():
        old = json.loads(BASE_JSON.read_text(encoding="utf-8"))
    else:
        old = json.loads(OLD_JSON.read_text(encoding="utf-8"))
    old_classes = list(old["classes"].items())
    old_tint = {name for name, d in old_classes if "tint" in d}
    old_static = [(name, d) for name, d in old_classes
                  if name != "FALLBACK" and name not in old_tint]
    print(f"old palette: {len(old_classes)} classes ({len(old_static)} static, {len(old_tint)} tint)")

    # 26.2 block census ---------------------------------------------------------
    rows = list(csv.DictReader(CSV_BLOCKS.open(encoding="utf-8-sig")))
    surface_weight = {r["block"]: float(r["share_pct"]) / 100.0
                      for r in csv.DictReader(CSV_SURFACE.open(encoding="utf-8-sig"))}

    candidates = []   # (block, rgb, weight, top_texture)
    tint_assign = {}  # block -> GRASS/FOLIAGE/WATER
    no_texture = []
    forced = {}       # block -> class (old LAVA etc.)
    for r in rows:
        block = r["block"]
        block_tex = (r.get("top_texture") or "").strip()
        if block in BIOME_TINT_GRASS:
            tint_assign[block] = "GRASS"
            continue
        if block in BIOME_TINT_FOLIAGE:
            tint_assign[block] = "FOLIAGE"
            continue
        if block in BIOME_TINT_WATER:
            tint_assign[block] = "WATER"
            continue
        if block in BIOME_TINT_DRY_FOLIAGE:
            tint_assign[block] = "DRY_FOLIAGE"
            continue
        avg = None
        tint = None
        override = TEXTURE_OVERRIDES.get(block)
        if override is not None:
            block_tex, tint = override
            avg = tex.average(block_tex)
        elif block_tex and block_tex not in ("texture missing", "no texture", "missing", "all"):
            avg = tex.average(block_tex)
        if avg is None:
            # thin/transparent blocks are often missing from the census texture column: resolve the
            # vanilla texture by convention so they still get a colour
            short = block.split(":")[1]
            for name in (short, f"{short}_top", f"{short}_side", f"{short}_still"):
                avg = tex.average(name)
                if avg is not None:
                    block_tex = name
                    break
        if avg is None:
            no_texture.append(block)
            continue
        rgb = avg[0].copy()
        if tint is not None:
            rgb = rgb * np.array(tint, dtype=DTYPE) / 255.0
        elif block in FIXED_TINT:
            rgb = rgb * np.array(FIXED_TINT[block], dtype=DTYPE) / 255.0
        candidates.append((block, rgb, max(surface_weight.get(block, 0.0), 0.002), block_tex))

    # lava keeps its existing static colour
    for block in ("minecraft:lava", "minecraft:lava_cauldron"):
        forced[block] = "LAVA"
    candidates = [c for c in candidates if c[0] not in forced]
    print(f"blocks: {len(rows)} | tint={len(tint_assign)} | no-texture={len(no_texture)} "
          f"| static candidates={len(candidates)} | forced={len(forced)}")

    x = np.array([srgb_to_lab(c[1]) for c in candidates])
    w = np.array([c[2] for c in candidates], dtype=DTYPE)
    w = w / w.sum()

    # fixed centres: old static colours + wool/concrete seeds
    fixed = [srgb_to_lab(hex_to_rgb(d["hex"])) for _, d in old_static]
    seed_defs = []
    for family, suffix in (("WOOL", "_wool"), ("CONCRETE", "_concrete")):
        for dye in SEED_DYE_NAMES:
            block = f"minecraft:{dye}{suffix}"
            hit = tex.average(f"{dye}{suffix}")
            if hit is None:
                continue
            seed_defs.append((f"{family}_{dye.upper()}", hit[0], block))
    seed_labs = [srgb_to_lab(rgb) for _, rgb, _ in seed_defs]
    seed_count = 0
    kept_seeds = []
    old_labs = list(fixed)
    for (name, rgb, block), lab in zip(seed_defs, seed_labs):
        if any(de76(lab, o) < 2.5 for o in old_labs):
            continue  # colour already present in the old palette
        if any(de76(lab, s[1]) < 2.0 for s in kept_seeds):
            continue
        kept_seeds.append((name, rgb, lab, block))
        seed_count += 1
    free_target = STATIC_TARGET - len(fixed) - seed_count
    print(f"seeds kept: {seed_count} (of {len(seed_defs)}) | free clusters: {free_target}")

    fixed_all = np.array(fixed + [s[2] for s in kept_seeds]) if seed_count else np.array(fixed)
    centers, n_fixed = run_kmeans(x, w, fixed_all, free_target)
    centers, n_fixed = tidy_free_centers(x, w, fixed_all, centers, n_fixed, free_target)
    print(f"final static colours: {len(centers)} (fixed {n_fixed}, free {len(centers)-n_fixed})")

    # name every static colour
    class_defs = []          # (class name, def dict)
    for name, d in old_static:
        class_defs.append((name, dict(d)))
    for name, rgb, lab, block in kept_seeds:
        class_defs.append((name, {"hex": rgb_to_hex(rgb)}))
    for j in range(n_fixed, len(centers)):
        class_defs.append((f"COLOR_{j:03d}", {"hex": rgb_to_hex(lab_to_srgb(centers[j]))}))
    if len(class_defs) != STATIC_TARGET:
        print(f"static colour count mismatch: {len(class_defs)} != {STATIC_TARGET}", file=sys.stderr)
        return 2

    # mapping: nearest static colour (or tint / forced / fallback)
    exact = {}
    resolved = []            # (block, class name, de to that colour, old class label)
    assign = de76_matrix(x, centers).argmin(axis=1)
    dmat = de76_matrix(x, centers)
    for i, (block, rgb, weight, block_tex) in enumerate(candidates):
        cls = class_defs[int(assign[i])][0]
        exact[block] = cls
        resolved.append((block, cls, float(dmat[i, assign[i]])))
    for block, cls in tint_assign.items():
        exact[block] = cls
    for block, cls in forced.items():
        exact[block] = cls

    # review artifacts ----------------------------------------------------------
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    classes_out = {}
    for name, d in old_classes:
        if name == "FALLBACK":
            continue
        classes_out[name] = dict(d)
    for name, d in class_defs:
        if name not in classes_out:
            classes_out[name] = d
    # dry foliage is a tint class: vanilla leaf litter multiplies the texture by the dry-foliage
    # colormap colour (see BlockTintSources.dryFoliage) instead of the normal foliage tint
    classes_out["DRY_FOLIAGE"] = {"hex": "#A37546", "tint": "dry_foliage", "factor": "#ABABAB"}
    # keep FALLBACK last, as the loader skips it when numbering slots
    classes_out["FALLBACK"] = dict(old["classes"]["FALLBACK"])
    new_json = {
        "version": 8,
        "note": "Surface material classes. Land colour = class colour times hillshade; water pixels "
                "use the current palette. v7: 200-colour palette (existing 58 colours kept, 142 added) "
                "and a regenerated block->colour mapping.",
        "fallbackClass": "FALLBACK",
        "classes": classes_out,
        "exact": dict(sorted(exact.items())),
        "snowyOverrides": old.get("snowyOverrides", {}),
        "woodFamilies": old.get("woodFamilies", []),
        "plantKeywords": old.get("plantKeywords", []),
        "structureKeywords": old.get("structureKeywords", []),
    }
    if not args.dry_run:
        OLD_JSON.write_text(json.dumps(new_json, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        MOD_JSON.write_text(json.dumps(new_json, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # report --------------------------------------------------------------------
    class_value = {name: (srgb_to_lab(hex_to_rgb(d["hex"])) if name in classes_out else None)
                   for name, d in classes_out.items()}
    members = defaultdict(list)
    de_list = []
    for block, cls, d in resolved:
        de_list.append(d)
        members[cls].append((block, d))
    de_list = np.array(de_list)
    report = []
    report.append(f"palette v8: {len(classes_out)} classes (+FALLBACK = 200 slots)")
    report.append(f"visible static blocks: {len(resolved)} | tint blocks: {len(tint_assign)} "
                  f"| forced: {len(forced)} | no-texture (FALLBACK): {len(no_texture)}")
    report.append("mapping error (target display colour -> palette colour):")
    report.append(f"  mean dE76={de_list.mean():.2f} median={np.median(de_list):.2f} "
                  f"p90={np.percentile(de_list, 90):.1f} | <3={100*(de_list<3).mean():.1f}% "
                  f"<5={100*(de_list<5).mean():.1f}% <8={100*(de_list<8).mean():.1f}%")
    used_old = sum(1 for name, _ in old_static if members.get(name))
    report.append(f"old static colours used by >=1 block: {used_old}/{len(old_static)} "
                  f"| unused: {[n for n, _ in old_static if not members.get(n)]}")
    report.append(f"new seed colours used: {sum(1 for name, _, _, _ in kept_seeds if members.get(name))}"
                  f"/{seed_count}")
    free_used = sum(1 for j in range(n_fixed, len(centers)) if members.get(f"COLOR_{j:03d}"))
    report.append(f"free colours used: {free_used}/{len(centers)-n_fixed}")
    report.append(f"no-texture (FALLBACK) blocks: {len(no_texture)}: "
                  + ", ".join(b.split(':')[1] for b in no_texture))
    top = sorted(members.items(), key=lambda kv: -len(kv[1]))[:12]
    report.append("largest classes: " + ", ".join(f"{k}({len(v)})" for k, v in top))
    print("\n".join(report))

    if args.dry_run:
        print("dry-run: palette JSON and review artifacts were not written")
        return 0

    with (OUT_DIR / "palette200-review.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        wr = csv.writer(fh)
        wr.writerow(["block", "new_class", "new_hex", "dE_new", "class_blocks", "class_examples"])
        for block, cls, d in sorted(resolved, key=lambda t: (t[1], t[2])):
            cls_def = classes_out.get(cls, {})
            examples = ", ".join(b.split(":")[1] for b, _ in members[cls][:4])
            wr.writerow([block, cls, cls_def.get("hex", ""), f"{d:.2f}", len(members[cls]), examples])
    with (OUT_DIR / "palette200-classes.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        wr = csv.writer(fh)
        wr.writerow(["class", "hex", "blocks", "examples"])
        for name, d in classes_out.items():
            if name == "FALLBACK":
                continue
            examples = ", ".join(b.split(":")[1] for b, _ in members.get(name, [])[:6])
            wr.writerow([name, d.get("hex", ""), len(members.get(name, [])), examples])
    (OUT_DIR / "palette200-report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")

    # swatch image (20 x 10)
    sw = 32
    img = Image.new("RGB", (20 * sw, 10 * sw), (20, 20, 20))
    names = list(classes_out.keys())
    for idx, name in enumerate(names):
        hexstr = classes_out[name].get("hex", "#000000")
        rgb = tuple(int(v) for v in hex_to_rgb(hexstr))
        x0, y0 = (idx % 20) * sw, (idx // 20) * sw
        for yy in range(y0, y0 + sw):
            for xx in range(x0, x0 + sw):
                img.putpixel((xx, yy), rgb)
    img.save(OUT_DIR / "palette200-swatch.png")
    print(f"wrote {OLD_JSON} | review/class/report/swatch in {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


