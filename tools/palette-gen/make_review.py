#!/usr/bin/env python3
"""Build colour-coded review artifacts for the 200-colour block palette.

Outputs (in ``doc/review/surface-census/``):
  - ``palette200-review.xlsx``  coloured spreadsheet (old vs new colour cells, filters)
  - ``palette200-review.html``  interactive page with editable palette swatches,
                                texture thumbnails and JSON export for manual calibration

Usage: python tools/palette-gen/make_review.py [--jar PATH]
"""
from __future__ import annotations

import argparse
import base64
import csv
import io
import json
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

from PIL import Image
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "doc/review/surface-census"
CSV_BLOCKS = OUT / "full-block-audit.csv"
REVIEW_CSV = OUT / "palette200-review.csv"
CLASSES_CSV = OUT / "palette200-classes.csv"
NEW_JSON = REPO / "stonecutter/src/main/resources/assets/voxymap/surface_classes.json"
DEFAULT_JAR = Path(r"D:\Minecraft\AxolotlLauncher\Data\meta\versions\26.2-0.20.1\26.2-0.20.1.jar")

# the pre-v7 semantic mapping differed from the design CSV only for these blocks
OLD_OVERRIDES = {
    "minecraft:spruce_leaves": "SPRUCE_LEAVES",
    "minecraft:birch_leaves": "BIRCH_LEAVES",
    "minecraft:lily_pad": "LILY_PAD",
    "minecraft:vine": "FOLIAGE",
}
TINT_CLASSES = {"GRASS", "FOLIAGE", "WATER", "DRY_FOLIAGE"}


def hex_to_rgb(h: str) -> tuple[int, int, int]:
    h = h.strip().lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def load_thumb(tex: str, jar: zipfile.ZipFile) -> str:
    names = set(jar.namelist())
    for cand in (f"assets/minecraft/textures/block/{tex}.png", f"assets/minecraft/textures/{tex}.png"):
        if cand not in names:
            continue
        try:
            img = Image.open(io.BytesIO(jar.read(cand))).convert("RGBA")
            img.thumbnail((24, 24), Image.NEAREST)
            buf = io.BytesIO()
            img.save(buf, "PNG")
            return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
        except Exception:
            return ""
    return ""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=str(DEFAULT_JAR))
    args = ap.parse_args()
    jar = zipfile.ZipFile(Path(args.jar))

    new = json.loads(NEW_JSON.read_text(encoding="utf-8"))
    new_classes = {k: v for k, v in new["classes"].items() if k != "FALLBACK"}
    new_classes["FALLBACK"] = new["classes"]["FALLBACK"]
    new_hex = {k: v.get("hex", "#8C8C8C") for k, v in new_classes.items()}
    exact = new["exact"]

    de_new = {}
    for r in csv.DictReader(REVIEW_CSV.open(encoding="utf-8-sig")):
        de_new[r["block"]] = float(r["dE_new"])
    class_blocks = defaultdict(list)
    for block, slot_class in exact.items():
        class_blocks[slot_class].append(block)

    rows = []
    for r in csv.DictReader(CSV_BLOCKS.open(encoding="utf-8-sig")):
        block = r["block"]
        name = block.split(":")[1]
        zh = r.get("zh_name", "")
        tex = (r.get("top_texture") or "").strip()
        design_class = r.get("class", "")
        old_class = OLD_OVERRIDES.get(block, design_class)
        if old_class in ("texture missing", "no texture", "missing", ""):
            old_class = "FALLBACK"
        if old_class not in new_hex:
            old_class = "FALLBACK"
        new_cls = exact.get(block, "FALLBACK")
        rows.append({
            "block": block,
            "name": name,
            "zh": zh,
            "tex": tex if tex and tex not in ("texture missing", "no texture", "all") else name,
            "old_class": old_class,
            "old_hex": new_hex.get(old_class, "#8C8C8C"),
            "new_class": new_cls,
            "new_hex": new_hex.get(new_cls, "#8C8C8C"),
            "de": de_new.get(block),
            "old_tint": old_class in TINT_CLASSES,
            "new_tint": new_cls in TINT_CLASSES,
        })
    rows.sort(key=lambda x: (x["new_class"], x["block"]))
    moved = [r for r in rows if r["old_class"] != r["new_class"]]
    flagged = sorted([r for r in rows if r["de"] is not None and r["de"] > 5.0],
                     key=lambda r: -r["de"])
    print(f"blocks={len(rows)} moved={len(moved)} tint={sum(1 for r in rows if r['new_tint'])} "
          f"dE>5={len(flagged)}")

    # ---------------------------------------------------------------- xlsx
    thin = Side(style="thin", color="D0D0D0")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    head_fill = PatternFill("solid", fgColor="1F2937")
    head_font = Font(color="FFFFFF", bold=True)
    wb = Workbook()

    def style_header(ws, ncols):
        for c in range(1, ncols + 1):
            cell = ws.cell(row=1, column=c)
            cell.fill = head_fill
            cell.font = head_font
            cell.alignment = Alignment(horizontal="center", vertical="center")
            cell.border = border
        ws.freeze_panes = "A2"

    ws = wb.active
    ws.title = "色表200"
    ws.append(["槽位", "类名", "hex", "颜色", "方块数", "示例方块"])
    style_header(ws, 6)
    for idx, cls in enumerate(["FALLBACK"] + [c for c in new_classes if c != "FALLBACK"]):
        hx = new_hex[cls]
        n = len(class_blocks.get(cls, []))
        examples = ", ".join(b.split(":")[1] for b in class_blocks.get(cls, [])[:6])
        ws.append([idx, cls, hx, "", n, examples])
        fill = PatternFill("solid", fgColor="%02X%02X%02X" % hex_to_rgb(hx))
        cell = ws.cell(row=ws.max_row, column=4)
        cell.fill = fill
        cell.border = border
        for c in range(1, 7):
            ws.cell(row=ws.max_row, column=c).border = border
    ws.column_dimensions["A"].width = 6
    ws.column_dimensions["B"].width = 20
    ws.column_dimensions["C"].width = 9
    ws.column_dimensions["D"].width = 10
    ws.column_dimensions["E"].width = 8
    ws.column_dimensions["F"].width = 90
    ws.auto_filter.ref = f"A1:F{ws.max_row}"

    ws2 = wb.create_sheet("方块映射")
    ws2.append(["方块ID", "中文名", "贴图", "旧语义类", "旧颜色", "旧hex",
                "新颜色类", "新颜色", "新hex", "dE(new)", "是否改色", "旧类为群系tint"])
    style_header(ws2, 12)
    for r in rows:
        ws2.append([r["block"], r["zh"], r["tex"], r["old_class"], "", r["old_hex"],
                    r["new_class"], "", r["new_hex"],
                    "" if r["de"] is None else round(r["de"], 2),
                    "是" if r["old_class"] != r["new_class"] else "",
                    "是" if r["old_tint"] else ""])
        row_i = ws2.max_row
        for col, hx in ((5, r["old_hex"]), (8, r["new_hex"])):
            cell = ws2.cell(row=row_i, column=col)
            cell.fill = PatternFill("solid", fgColor="%02X%02X%02X" % hex_to_rgb(hx))
            cell.border = border
        for c in range(1, 13):
            ws2.cell(row=row_i, column=c).border = border
    for col, w in (("A", 38), ("B", 16), ("C", 26), ("D", 18), ("E", 9), ("F", 9),
                   ("G", 16), ("H", 9), ("I", 9), ("J", 9), ("K", 8), ("L", 12)):
        ws2.column_dimensions[col].width = w
    ws2.auto_filter.ref = f"A1:L{ws2.max_row}"

    ws3 = wb.create_sheet("dE大于5")
    ws3.append(["方块ID", "中文名", "贴图", "旧语义类", "旧颜色", "新颜色类", "新颜色", "dE(new)"])
    style_header(ws3, 8)
    for r in flagged:
        ws3.append([r["block"], r["zh"], r["tex"], r["old_class"], "", r["new_class"], "",
                    round(r["de"], 2)])
        row_i = ws3.max_row
        for col, hx in ((5, r["old_hex"]), (7, r["new_hex"])):
            cell = ws3.cell(row=row_i, column=col)
            cell.fill = PatternFill("solid", fgColor="%02X%02X%02X" % hex_to_rgb(hx))
            cell.border = border
        for c in range(1, 9):
            ws3.cell(row=row_i, column=c).border = border
    for col, w in (("A", 38), ("B", 16), ("C", 26), ("D", 18), ("E", 9), ("F", 16), ("G", 9), ("H", 9)):
        ws3.column_dimensions[col].width = w
    ws3.auto_filter.ref = f"A1:H{ws3.max_row}"

    ws4 = wb.create_sheet("说明")
    for line in [
        "方块颜色 200 色调色审阅表",
        "",
        "1. 「色表200」：每个颜色一行，D 列是颜色填充；F 列是该颜色当前映射到的方块示例。",
        "2. 「方块映射」：每个方块一行，E 列旧颜色、H 列新颜色均按真实颜色填充，可直接对比。",
        "3. 「dE大于5」：自动映射误差较大的方块，建议优先人工校正。",
        "4. 修改方法：把想改的『颜色类 → 新 hex』记下来，或用同目录的 HTML 页面调色后导出 JSON。",
        "5. 旧语义类为 v7 之前的映射（设计 CSV 语义类 + v6 的 4 个特例）；群系 tint 类没有固定颜色，",
        "   表里给的是 tint 基色，实际显示会乘群系色。",
        "6. 校正后重新运行 tools/palette-gen/generate_palette.py 或直接改 JSON，并升缓存版本。",
    ]:
        ws4.append([line])
    ws4.column_dimensions["A"].width = 110

    xlsx_path = OUT / "palette200-review.xlsx"
    wb.save(xlsx_path)

    # ---------------------------------------------------------------- html
    def swatch(hx: str) -> str:
        return f'<span class="sw" style="background:{hx}"></span>'

    # thumbnails once per texture
    thumbs = {}
    for r in rows:
        if r["tex"] not in thumbs:
            thumbs[r["tex"]] = load_thumb(r["tex"], jar)

    palette_cards = []
    for idx, cls in enumerate(["FALLBACK"] + [c for c in new_classes if c != "FALLBACK"]):
        hx = new_hex[cls]
        n = len(class_blocks.get(cls, []))
        ex = ", ".join(b.split(":")[1] for b in class_blocks.get(cls, [])[:5])
        palette_cards.append(
            f'<div class="card" data-class="{cls}" data-hex="{hx}">'
            f'<div class="big" style="background:{hx}"></div>'
            f'<div class="cname">{cls}</div>'
            f'<div class="hex"><input type="color" value="{hx}"><input class="hexin" value="{hx}"></div>'
            f'<div class="count">{n} 方块</div>'
            f'<div class="ex">{ex}</div></div>')

    block_rows = []
    for r in rows:
        moved_cls = "moved" if r["old_class"] != r["new_class"] else ""
        de = "" if r["de"] is None else f'{r["de"]:.2f}'
        tint = " tint" if r["new_tint"] else ""
        thumb = thumbs.get(r["tex"], "")
        img = f'<img src="{thumb}" width="24" height="24" class="thumb">' if thumb else '<span class="thumb"></span>'
        block_rows.append(
            f'<tr class="{moved_cls}{tint}" data-name="{r["name"]}" data-zh="{r["zh"]}" '
            f'data-old="{r["old_class"]}" data-new="{r["new_class"]}" data-de="{de}">'
            f'<td>{img}</td><td class="mono">{r["block"]}</td><td>{r["zh"]}</td>'
            f'<td>{swatch(r["old_hex"])}<span class="cls">{r["old_class"]}</span></td>'
            f'<td>{swatch(r["new_hex"])}<span class="cls">{r["new_class"]}</span></td>'
            f'<td class="num">{de}</td></tr>')

    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>方块颜色 200 色 · 调色审阅</title>
<style>
:root {{ color-scheme: dark; }}
body {{ margin:0; background:#111827; color:#e5e7eb; font:13px/1.5 system-ui,"Segoe UI",sans-serif; }}
header {{ padding:14px 20px; background:#0b1220; border-bottom:1px solid #1f2937; position:sticky; top:0; z-index:5; }}
h1 {{ font-size:16px; margin:0 0 4px; }}
.sub {{ color:#9ca3af; font-size:12px; }}
.tabs {{ display:flex; gap:8px; margin-top:10px; }}
.tabs button {{ background:#1f2937; color:#e5e7eb; border:1px solid #374151; border-radius:6px; padding:5px 12px; cursor:pointer; }}
.tabs button.on {{ background:#2563eb; border-color:#2563eb; }}
.panel {{ display:none; padding:16px 20px; }}
.panel.on {{ display:block; }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; }}
.card {{ background:#0b1220; border:1px solid #1f2937; border-radius:8px; padding:8px; }}
.card .big {{ height:44px; border-radius:5px; border:1px solid #374151; }}
.cname {{ font-weight:600; margin-top:6px; word-break:break-all; }}
.hex {{ display:flex; gap:6px; align-items:center; margin:4px 0; }}
.hex input[type=color] {{ width:26px; height:22px; border:none; background:none; padding:0; }}
.hexin {{ width:78px; background:#111827; border:1px solid #374151; color:#e5e7eb; border-radius:4px; padding:2px 4px; font-family:ui-monospace,monospace; }}
.count {{ color:#9ca3af; font-size:11px; }}
.ex {{ color:#6b7280; font-size:10px; margin-top:2px; }}
.toolbar {{ display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin-bottom:10px; }}
.toolbar input[type=search] {{ width:260px; padding:5px 8px; background:#0b1220; color:#e5e7eb; border:1px solid #374151; border-radius:6px; }}
.toolbar label {{ color:#9ca3af; font-size:12px; }}
table {{ width:100%; border-collapse:collapse; }}
th,td {{ padding:4px 8px; border-bottom:1px solid #1f2937; text-align:left; vertical-align:middle; }}
th {{ position:sticky; top:0; background:#0b1220; z-index:2; color:#9ca3af; font-weight:600; }}
tr:hover td {{ background:#111c2e; }}
.sw {{ display:inline-block; width:16px; height:16px; border-radius:3px; border:1px solid #374151; vertical-align:middle; margin-right:5px; }}
.thumb {{ width:24px; height:24px; image-rendering:pixelated; border-radius:3px; background:#1f2937; display:inline-block; }}
.mono {{ font-family:ui-monospace,monospace; font-size:12px; }}
.cls {{ color:#9ca3af; font-size:11px; }}
.num {{ text-align:right; font-variant-numeric:tabular-nums; }}
.tint td {{ opacity:.75; }}
#out {{ width:100%; height:180px; background:#0b1220; color:#e5e7eb; border:1px solid #374151; border-radius:6px; font-family:ui-monospace,monospace; font-size:12px; }}
.btn {{ background:#2563eb; border:none; color:white; border-radius:6px; padding:6px 14px; cursor:pointer; }}
.btn.sec {{ background:#374151; }}
</style></head><body>
<header>
  <h1>方块颜色 200 色 · 调色审阅</h1>
  <div class="sub">色表：FALLBACK + 3 群系 tint + 54 旧色 + 16 羊毛 + 16 混凝土 + 110 自由色 ·
    映射：{len(rows)} 方块 · 改色 {len(moved)} · dE&gt;5 {len(flagged)}</div>
  <div class="tabs">
    <button class="on" data-p="palette">色表（可改色）</button>
    <button data-p="blocks">方块映射</button>
    <button data-p="flag">待校正 dE&gt;5</button>
    <button data-p="export">导出</button>
  </div>
</header>
<div class="panel on" id="palette"><div class="grid">{"".join(palette_cards)}</div></div>
<div class="panel" id="blocks">
  <div class="toolbar">
    <input type="search" id="q" placeholder="搜索方块 ID / 中文名 / 颜色类…">
    <label><input type="checkbox" id="onlyMoved"> 只看改色</label>
    <label><input type="checkbox" id="onlyTint"> 只看群系 tint</label>
    <label><input type="checkbox" id="onlyDe"> 只看 dE&gt;5</label>
    <span class="sub" id="cnt"></span>
  </div>
  <table><thead><tr><th></th><th>方块</th><th>中文名</th><th>旧颜色</th><th>新颜色</th><th>dE</th></tr></thead>
  <tbody id="tbody">{"".join(block_rows)}</tbody></table>
</div>
<div class="panel" id="flag">
  <div class="toolbar"><span class="sub">dE(new) &gt; 5，建议优先人工校正；点「色表」页可改颜色。</span></div>
  <table><thead><tr><th></th><th>方块</th><th>中文名</th><th>旧颜色</th><th>新颜色</th><th>dE</th></tr></thead>
  <tbody id="flagbody"></tbody></table>
</div>
<div class="panel" id="export">
  <p>改过的色表颜色会实时显示在「色表」页；这里导出 JSON（类名 → 新 hex），贴给我即可批量应用。</p>
  <p><button class="btn" id="gen">生成 JSON</button>
     <button class="btn sec" id="dl">下载 .json</button></p>
  <textarea id="out" spellcheck="false"></textarea>
</div>
<script>
const changed = new Map();
document.querySelectorAll('.card').forEach(card => {{
  const cls = card.dataset.class;
  const original = card.dataset.hex.toUpperCase();
  const color = card.querySelector('input[type=color]');
  const hexin = card.querySelector('.hexin');
  const big = card.querySelector('.big');
  function apply(hex, from) {{
    hex = hex.toUpperCase();
    if (!/^#[0-9A-F]{{6}}$/.test(hex)) return;
    big.style.background = hex;
    color.value = hex; hexin.value = hex;
    if (hex === original) changed.delete(cls); else changed.set(cls, hex);
    document.querySelectorAll('tr[data-new="' + cls + '"]').forEach(tr => {{
      const sw = tr.children[4].querySelector('.sw');
      if (sw) sw.style.background = hex;
    }});
  }}
  color.addEventListener('input', e => apply(e.target.value));
  hexin.addEventListener('change', e => apply(e.target.value));
}});
const tbody = document.getElementById('tbody');
const flagbody = document.getElementById('flagbody');
function flagRows() {{
  return [...tbody.querySelectorAll('tr')].filter(tr => parseFloat(tr.dataset.de) > 5)
    .sort((a,b) => parseFloat(b.dataset.de) - parseFloat(a.dataset.de));
}}
flagRows().forEach(tr => flagbody.appendChild(tr.cloneNode(true)));
const q = document.getElementById('q');
const onlyMoved = document.getElementById('onlyMoved');
const onlyTint = document.getElementById('onlyTint');
const onlyDe = document.getElementById('onlyDe');
const cnt = document.getElementById('cnt');
function filter() {{
  const s = q.value.trim().toLowerCase();
  let shown = 0;
  tbody.querySelectorAll('tr').forEach(tr => {{
    let ok = true;
    if (s && !(tr.dataset.name + ' ' + tr.dataset.zh + ' ' + tr.dataset.new).toLowerCase().includes(s)) ok = false;
    if (onlyMoved.checked && !tr.classList.contains('moved')) ok = false;
    if (onlyTint.checked && !tr.classList.contains('tint')) ok = false;
    if (onlyDe.checked && !(parseFloat(tr.dataset.de) > 5)) ok = false;
    tr.style.display = ok ? '' : 'none';
    if (ok) shown++;
  }});
  cnt.textContent = shown + ' 行';
}}
[q, onlyMoved, onlyTint, onlyDe].forEach(el => el.addEventListener('input', filter));
filter();
document.querySelectorAll('.tabs button').forEach(btn => btn.addEventListener('click', () => {{
  document.querySelectorAll('.tabs button').forEach(b => b.classList.toggle('on', b === btn));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('on', p.id === btn.dataset.p));
}}));
document.getElementById('gen').addEventListener('click', () => {{
  const obj = Object.fromEntries([...changed.entries()].sort());
  document.getElementById('out').value = JSON.stringify(obj, null, 2);
}});
document.getElementById('dl').addEventListener('click', () => {{
  const obj = Object.fromEntries([...changed.entries()].sort());
  const blob = new Blob([JSON.stringify(obj, null, 2)], {{type: 'application/json'}});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'palette-overrides.json';
  a.click();
}});
</script>
</body></html>"""
    html_path = OUT / "palette200-review.html"
    html_path.write_text(html, encoding="utf-8")
    print(f"wrote {xlsx_path} ({xlsx_path.stat().st_size} bytes)")
    print(f"wrote {html_path} ({html_path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()

