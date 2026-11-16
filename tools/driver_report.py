#!/usr/bin/env python3
"""
driver_report.py - the driver matrix's tables, from docs/data/vulkan-drivers/matrix.csv
(tools/driver_matrix.py's runs), as Markdown for docs/DRIVERS.md (PLAN 9.23).

Prints, for the runs given (default: every row whose note is "full matrix"), one table a
measure - the title's frames a second, the frames shown a second, the title's 1% low, the GPU
busy - with the combinations down and the drivers across, each cell its run's number
in the CSV; and the smoke runs (note "smoke and pipeline warm-up") as their own table.

Usage:
  python tools/driver_report.py [--note "full matrix"] > snippet.md
"""
import argparse
import csv
import sys
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLE = ROOT / "docs" / "data" / "vulkan-drivers" / "matrix.csv"

COMBO_NAMES = OrderedDict([
    ("bilinear+off", "Bilinear, no frame generation"),
    ("bilinear+lossless", "Bilinear, Lossless 2x"),
    ("fxaa+off", "FXAA, no frame generation"),
    ("fxaa+lossless", "FXAA, Lossless 2x"),
    ("fsr+off", "FSR 1, no frame generation"),
    ("fsr+lossless", "FSR 1, Lossless 2x"),
    ("cas+off", "CAS, no frame generation"),
    ("cas+lossless", "CAS, Lossless 2x"),
    ("fsr3-quality+off", "FSR 3.1 Quality, no frame generation"),
    ("fsr3-quality+lossless", "FSR 3.1 Quality, Lossless 2x"),
    ("fsr3-quality+fsr3", "FSR 3.1 Quality, FSR 3.1 2x"),
    ("fsr3-balanced+off", "FSR 3.1 Balanced, no frame generation"),
    ("fsr3-balanced+lossless", "FSR 3.1 Balanced, Lossless 2x"),
    ("fsr3-balanced+fsr3", "FSR 3.1 Balanced, FSR 3.1 2x"),
    ("fsr3-performance+off", "FSR 3.1 Performance, no frame generation"),
    ("fsr3-performance+lossless", "FSR 3.1 Performance, Lossless 2x"),
    ("fsr3-performance+fsr3", "FSR 3.1 Performance, FSR 3.1 2x"),
])


def short(driver):
    """The driver's column name: its package name without the folder in parentheses."""
    if driver == "system":
        return "System (v676.53)"
    names = {"Turnip Adreno Driver T30": "Turnip T30 (Mr_Purple_666)",
             "Mesa Turnip driver v26.0.0 - R8": "Turnip v26.0.0 R8 (K11MCH1)",
             "Balemuni Apex v2 ULTIMATE": "Turnip Balemuni Apex v2 ULTIMATE",
             "Turnip 27.0.0-R2": "Turnip v27.0.0-R2 (StevenMXZ)"}
    for start, name in names.items():
        if driver.startswith(start):
            return name
    return driver.rsplit(" (", 1)[0]


def cell(row, field):
    value = row.get(field, "")
    if row.get("reached_camp") != "yes":
        return "did not reach the camp"
    if field == "gpu_temp_max_c" and value.isdigit() and int(value) > 1000:
        return "%.1f" % (int(value) / 1000.0)  # the sensor's millidegrees
    if field in ("fsr3_state",) and "unavailable" in value:
        return "FSR 3.1 unavailable"
    return value if value != "" else "-"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--note", default="full matrix")
    a = ap.parse_args()
    rows = list(csv.DictReader(TABLE.open(encoding="utf-8")))
    matrix = [r for r in rows if r["note"] == a.note]
    drivers = list(OrderedDict.fromkeys(r["driver"] for r in matrix))
    by = {(r["driver"], r["combo"]): r for r in matrix}
    out = sys.stdout
    measures = [
        ("title_fps", "The game's frames a second (the title's swaps, the 20 s measured)"),
        ("panel_fps", "Frames shown a second (presents; with frame generation, the generated ones too)"),
        ("title_1pct_low", "The game's 1% low (frames a second)"),
        ("gpu_busy_pct", "GPU busy (%)"),
        ("gpu_mhz", "GPU clock (MHz, the mean of the 20 samples; the Thor's highest is 680)"),
    ]
    # Session 47 (the owner): the size each combination renders at and is upscaled to, as
    # each run's log said it - one line a combination, a driver's own only where it differs.
    out.write("#### What each combination renders at and is upscaled to\n\n")
    out.write("| Combination | Rendered at | Upscaled to | By | Frame generation at |\n")
    out.write("|---|---|---|---|---|\n")
    for combo, name in COMBO_NAMES.items():
        seen = OrderedDict()
        for d in drivers:
            r = by.get((d, combo))
            if not r or not r.get("render_resolution"):
                continue
            key = (r["render_resolution"], r["output_resolution"], r["upscaled_by"],
                   r.get("fg_resolution") or "-")
            seen.setdefault(key, []).append(short(d))
        for key, names in seen.items():
            label = name if len(seen) == 1 else "%s (%s)" % (name, ", ".join(names))
            out.write("| %s | %s | %s | %s | %s |\n" % ((label,) + key))
    out.write("\n")
    for field, title in measures:
        out.write("#### %s\n\n" % title)
        out.write("| Combination | " + " | ".join(short(d) for d in drivers) + " |\n")
        out.write("|---|" + "---|" * len(drivers) + "\n")
        for combo, name in COMBO_NAMES.items():
            cells = []
            for d in drivers:
                r = by.get((d, combo))
                cells.append(cell(r, field) if r else "not run")
            out.write("| %s | %s |\n" % (name, " | ".join(str(c) for c in cells)))
        out.write("\n")
    out.write("#### What each run's FSR 3.1 and frame generation said\n\n")
    out.write("| Run | Driver | Combination | Rendered at | Upscaled to (by) | FSR 3.1 | Frame generation"
              " | Faults | GPU max C |\n")
    out.write("|---|---|---|---|---|---|---|---|---|\n")
    for r in matrix:
        out.write("| %s | %s | %s | %s | %s (%s) | %s | %s | %s | %s |\n" % (
            r["run"], short(r["driver"]), r["combo"], r.get("render_resolution", ""),
            r.get("output_resolution", ""), r.get("upscaled_by", ""),
            r["fsr3_state"][:60].replace("|", "/"),
            r["fg_state"][:70].replace("|", "/"), r["faults"], cell(r, "gpu_temp_max_c")))
    out.write("\n")
    for prefix, title in (("smoke", "Every driver that loads, Bilinear, no frame generation "
                                    "(the smoke runs)"),
                          ("warm", "Bilinear, no frame generation, again after the matrix (the "
                                   "warm checks, each with a screenshot)")):
        smoke = [r for r in rows if r["note"].startswith(prefix)]
        if not smoke:
            continue
        out.write("#### %s\n\n" % title)
        out.write("| Run | Driver | Device | Driver version | Rendered at | Upscaled to | Game fps"
                  " | 1% low | GPU busy | GPU MHz | Shown fps |\n")
        out.write("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for r in smoke:
            out.write("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" % (
                r["run"], short(r["driver"]), r["device"], r["driver_version"],
                r.get("render_resolution", ""), r.get("output_resolution", ""),
                cell(r, "title_fps"), cell(r, "title_1pct_low"), cell(r, "gpu_busy_pct"),
                cell(r, "gpu_mhz"), cell(r, "panel_fps")))
        out.write("\n")


if __name__ == "__main__":
    sys.exit(main())
