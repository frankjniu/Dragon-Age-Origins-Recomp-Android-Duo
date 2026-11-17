#!/usr/bin/env python3
"""
driver_matrix.py - the King's Camp measured on each Vulkan driver, with each upscaling and
frame generation (PLAN 9.23).

For each driver and each combination, one boot with tools/measure_ostagar.sh (the ostagar
route must be in place: bash tools/route.sh ostagar), the option files set before it. Every
run is written to the repository, not only the terminal:

  docs/data/vulkan-drivers/matrix.csv        one row a run (appended)
  docs/data/vulkan-drivers/run<N>.txt        the run's lines that matter: the driver, the
                                             device, FSR 3.1's and frame generation's own
                                             lines, the frames: lines measured, the GPU
                                             sampler, faults

Only extracted lines are kept: the whole log (build/run<N>.log) stays on this computer.

The settings every run shares (the rest are the device's as they are): Frame rate 120 (the
game uncapped - the GPU decides: TESTING.md "Measuring uncapped"), Rendering resolution
1280x720, Dynamic shadows on, Corner vignette on, Shader math exact, Texture filtering 16x.
The combinations: FSR 3.1 off with each of the presenter's four upscalers, frame generation
off and Lossless 2x; FSR 3.1 Quality, Balanced and Performance, frame generation off,
Lossless 2x and FSR 3.1 2x (FSR 3.1's needs FSR 3.1 on).

Drivers: `system`, or a package's .zip, which is put on the device as the app's import does
(its meta.json and libraries into files/drivers/<package>/; VulkanDrivers.java).

Usage:
  python tools/driver_matrix.py --run 744 --drivers system,<zip>,... [--combos all|smoke|NAMES]
      [--note TEXT] [--interleave] [--shots]
  python tools/driver_matrix.py --reparse

--shots also saves the main screen at the camp (build/shot<N>.png; measure_ostagar.sh's SHOT).
"""
import argparse
import csv
import json
import os
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "data" / "vulkan-drivers"
ADB = os.environ.get("ADB") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" /
                                   "platform-tools" / "adb.exe")
PKG = "dev.daorecompandroid.duo"

SHARED = {"frame-rate": "120", "resolution": "1280x720", "shadows": "on", "vignette": "on",
          "shader-math": "exact", "anisotropic": "16"}

# name: (fsr3, upscaling, frame-generation)
COMBOS = {}
for up in ("bilinear", "fxaa", "fsr", "cas"):
    for fg in ("off", "lossless"):
        COMBOS["%s+%s" % (up, fg)] = ("off", up, fg)
for profile in ("quality", "balanced", "performance"):
    for fg in ("off", "lossless", "fsr3"):
        COMBOS["fsr3-%s+%s" % (profile, fg)] = (profile, "bilinear", fg)
SMOKE = ["bilinear+off"]

FIELDS = ["run", "date", "driver", "driver_loaded", "device", "driver_version", "combo", "fsr3",
          "upscaling", "frame_generation", "render_resolution", "output_resolution",
          "upscaled_by", "fg_resolution", "reached_camp", "swaps_per_s", "title_fps",
          "title_1pct_low", "title_p99_ms", "panel_fps", "panel_p99_ms", "gpu_busy_pct",
          "gpu_mhz", "gpu_temp_max_c", "fsr3_state", "fg_state", "faults", "note"]


def adb(*args, check=False, input_bytes=None):
    env = dict(os.environ, MSYS2_ARG_CONV_EXCL="*")
    return subprocess.run([ADB, *args], capture_output=True, env=env, input=input_bytes,
                          check=check)


def shell_as(command):
    return adb("shell", "run-as %s sh -c '%s'" % (PKG, command)).stdout.decode(errors="replace")


def set_files(values):
    parts = []
    for name, value in values.items():
        parts.append("rm -f files/%s" % name if value is None else
                     "echo %s > files/%s" % (value, name))
    shell_as("; ".join(parts))


def package_id(name):
    """VulkanDrivers.idFor."""
    ident = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    if len(ident) > 48:
        ident = ident[:48].rstrip("-")
    return "driver" if ident in ("", "system") else ident


def install_package(zip_path):
    """The package's meta.json and libraries into files/drivers/<id>/, as the import does."""
    with zipfile.ZipFile(zip_path) as z:
        meta_name = next(n for n in z.namelist() if n.endswith("meta.json"))
        meta = json.loads(z.read(meta_name).decode("utf-8"))
        ident = package_id(meta["name"])
        wanted = [n for n in z.namelist() if n == meta_name or n.endswith(".so")]
        listed = shell_as("ls files/drivers/%s 2>/dev/null" % ident).split()
        if meta["libraryName"] in listed:
            return ident, meta
        shell_as("rm -rf files/drivers/%s; mkdir -p files/drivers/%s" % (ident, ident))
        for name in wanted:
            data = z.read(name)
            target = "files/drivers/%s/%s" % (ident, name.split("/")[-1])
            # exec-in: raw bytes (adb shell's stdin mangles a binary).
            adb("exec-in", "run-as", PKG, "sh", "-c", "cat > %s" % target, input_bytes=data)
            # adb can return before the device's cat has written the last of it.
            for _ in range(40):
                size = shell_as("stat -c %%s %s" % target).strip()
                if size == str(len(data)):
                    break
                time.sleep(0.25)
            if size != str(len(data)):
                raise SystemExit("%s: %s bytes on the device, %d here" % (target, size, len(data)))
        return ident, meta


PRESENTER = {"bilinear": "Bilinear", "fxaa": "FXAA", "fsr": "FSR 1", "cas": "CAS"}


def upscaler_name(sizes, upscaling):
    """FSR 3.1, or the presenter's own upscaler as the row's Upscaling chose it."""
    if sizes["upscaled_by"] == "presenter":
        sizes["upscaled_by"] = PRESENTER.get(upscaling, upscaling) + " (presenter)"
    return sizes


def resolutions(log):
    """The size the game rendered at, the size it was upscaled to, by what, and the size frame
    generation worked at - from the run's own log lines, at the camp (the last said)."""
    made = re.findall(r"FSR 3\.1: the upscaler is made - (\d+x\d+) to (\d+x\d+)", log)
    swapchain = re.findall(r"VulkanPresenter: Created (\d+x\d+) swapchain", log)
    output = swapchain[-1] if swapchain else ""
    if made:
        render, target = made[-1]
        by = "FSR 3.1"
    else:
        # The presenter's guest output: the game's own frame, which its upscaler scales.
        frames = re.findall(r"presenter: guest frame \d+ refreshed \((\d+x\d+)", log)
        render = frames[-1] if frames else ""
        target = output
        by = "presenter"
    fg = re.findall(r"the generator is up in \d+ ms - (\d+x\d+)|"
                    r"Frame generation: FSR 3\.1's frames put together at (\d+x\d+)", log)
    fg_size = ""
    if fg:
        fg_size = next(x for x in fg[-1] if x)
    return {"render_resolution": render, "output_resolution": target, "upscaled_by": by,
            "fg_resolution": fg_size}


def last(pattern, text, group=1, default=""):
    found = re.findall(pattern, text)
    if not found:
        return default
    item = found[-1]
    return item if isinstance(item, str) else item[group - 1]


# Only this app's lines, and a crash's, are kept: the repository is public, and other apps'
# lines say what else is on the device (session 47).
OUR_TAGS = re.compile(r" [VDIWEF] (warden|Warden|hook_impl|libadrenotools|libc|DEBUG|vulkan|"
                      r"AndroidRuntime) *:")


def parse(run, stdout):
    log_path = ROOT / "build" / ("run%d.log" % run)
    log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
    lines = log.splitlines()
    keep = []
    for line in lines:
        if re.search(r"Vulkan driver|vulkan driver|Vulkan device ready|Debugging Mode: GPU|"
                     r"VulkanPresenter: Created|the upscaler is made|generator is up|"
                     r"frames put together at|GPU: rendering at|"
                     r"FSR 3\.1:|Frame generation:|Frame generation passes|frame generation|"
                     r"GPU fences:|fault on guest|Warden:F|"
                     r"FATAL|SIGSEGV|libadrenotools|hook_android|adrenotools|game: mode is now|"
                     r"VkPipelineCache|unavailable|not made|frames: |GPU: frame rate|"
                     r"startup\(\) =", line) and OUR_TAGS.search(line):
            keep.append(line)
    frames = [l for l in lines if " frames: present:" in l]
    window = frames[-4:]

    def stats(which):
        fps, p99, low = [], [], []
        for line in window:
            part = line.split("title:")[0] if which == "present" else line.split("title:")[-1]
            n = re.search(r"(\d+) frames, interval ms mean ([\d.]+)", part)
            q = re.search(r"p99 ([\d.]+)", part)
            lo = re.search(r"1% low ([\d.]+) fps", part)
            if n:
                fps.append(int(n.group(1)) / 5.0)
            if q:
                p99.append(float(q.group(1)))
            if lo:
                low.append(float(lo.group(1)))
        avg = lambda v: round(sum(v) / len(v), 1) if v else ""
        return avg(fps), avg(p99), (round(min(low), 1) if low else "")

    panel_fps, panel_p99, _ = stats("present")
    title_fps, title_p99, title_low = stats("title")
    busy = [int(m) for m in re.findall(r"gpu busy (\d+) ?%?", stdout)]
    clock = [int(m) for m in re.findall(r"clock (\d+)", stdout)]
    temps = [int(m) for m in re.findall(r"temp (\d+)", stdout)]
    swaps = last(r"Ostagar: ([\d.]+) swaps/s", stdout)
    faults = len(re.findall(r"fault on guest thread|Fatal signal|SIGSEGV|SIGABRT", log))
    fsr_state = last(r"FSR 3\.1: (on \([a-z]+\)[^\n]{0,60}|unavailable[^\n]{0,80}|off[^\n]{0,60})",
                     log)
    fg_state = last(r"Frame generation: ([^\n]{0,110})", log)
    device = last(r"Vulkan device ready: ([^\n]+)", log).strip()
    driver_line = last(r"Vulkan driver: ([^\n]+)", log).strip()
    version = last(r"driver version\s+(0x[0-9A-Fa-f]+)", log)
    if not version:
        version = last(r"driver ([0-9A-F]{8}) \(vendor", log)
    reached = log.count("game: mode is now 0") >= 2
    sizes = resolutions(log)
    mhz = round(sum(clock) / len(clock) / 1e6) if clock else ""
    return {
        "device": device, "driver_loaded": driver_line[:120], "driver_version": version,
        "reached_camp": "yes" if reached else "no", "swaps_per_s": swaps,
        "title_fps": title_fps, "title_1pct_low": title_low, "title_p99_ms": title_p99,
        "panel_fps": panel_fps, "panel_p99_ms": panel_p99,
        "gpu_busy_pct": round(sum(busy) / len(busy)) if busy else "",
        "gpu_mhz": mhz, "gpu_temp_max_c": max(temps) if temps else "",
        "fsr3_state": fsr_state.strip()[:90], "fg_state": fg_state.strip()[:110],
        "faults": faults, **sizes,
    }, keep


def reparse():
    """matrix.csv again, each row's measures read from its run's log as this version reads
    them and from measure_ostagar.sh's output kept in run<N>.txt; only the rows' own fields
    (the first ten columns - run, date, driver, combination - alike in every version, and the
    note) kept. The resolutions are also written at the top of each run<N>.txt."""
    table = OUT / "matrix.csv"
    rows = list(csv.DictReader(table.open(encoding="utf-8")))
    for row in rows:
        run = int(row["run"])
        # A row written with more columns than the file's header (a newer version appending
        # under an older one's header, session 47) has its extra values under None: its last
        # is its note.
        extra = row.pop(None, None)
        if extra:
            row["note"] = extra[-1]
        log_path = ROOT / "build" / ("run%d.log" % run)
        if not log_path.is_file():
            print("run %d: no log here - kept as it was" % run)
            continue
        log = log_path.read_text(encoding="utf-8", errors="replace")
        text_path = OUT / ("run%d.txt" % run)
        text = text_path.read_text(encoding="utf-8") if text_path.is_file() else ""
        stdout = text.split("measure_ostagar.sh:\n", 1)[-1].split("\nlog lines:", 1)[0]
        measured, _ = parse(run, stdout if text else "")
        row.update(measured)
        sizes = upscaler_name(resolutions(log), row["upscaling"])
        row.update(sizes)
        if text:
            line = ("resolutions: rendered at %s, upscaled to %s by %s; frame generation at %s"
                    % (sizes["render_resolution"] or "?", sizes["output_resolution"] or "?",
                       sizes["upscaled_by"], sizes["fg_resolution"] or "(off)"))
            lines = text.split("\n")
            if len(lines) > 1 and lines[1].startswith("resolutions:"):
                lines[1] = line
            else:
                lines.insert(1, line)
            text = "\n".join(lines)
            text_path.write_text(text, encoding="utf-8")
    with table.open("w", newline="", encoding="utf-8") as out:
        writer = csv.DictWriter(out, fieldnames=FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in FIELDS})
    print("%d rows made again" % len(rows))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", type=int, help="the first run number")
    ap.add_argument("--drivers", help="system and/or package .zip paths")
    ap.add_argument("--combos", default="all")
    ap.add_argument("--note", default="")
    ap.add_argument("--interleave", action="store_true")
    ap.add_argument("--shots", action="store_true",
                    help="the main screen at the camp saved as build/shot<N>.png")
    ap.add_argument("--reparse", action="store_true",
                    help="no runs: matrix.csv's every row made again from build/run<N>.log, "
                         "with the columns this version has (the resolutions, session 47)")
    a = ap.parse_args()
    if a.reparse:
        return reparse()
    combos = (list(COMBOS) if a.combos == "all" else SMOKE if a.combos == "smoke"
              else a.combos.split(","))
    OUT.mkdir(parents=True, exist_ok=True)
    table = OUT / "matrix.csv"
    new = not table.is_file()
    if not new:
        with table.open(encoding="utf-8") as existing:
            header = existing.readline().strip().split(",")
        if header != FIELDS:
            # Rows appended under another version's header are read out of line (session 47).
            sys.exit("matrix.csv's columns are an older version's: "
                     "python tools/driver_matrix.py --reparse first")
    run = a.run
    drivers = []
    for driver in a.drivers.split(","):
        drivers.append(("system", {"name": "system"}) if driver == "system"
                       else install_package(driver))
    # --interleave: every driver for one combination before the next, so the handheld's
    # warming over hours of runs falls on each driver alike.
    pairs = ([(d, c) for c in combos for d in drivers] if a.interleave
             else [(d, c) for d in drivers for c in combos])
    if pairs:
        for (ident, meta), combo in pairs:
            fsr3, upscaling, fg = COMBOS[combo]
            values = dict(SHARED, **{"vulkan-driver": ident, "fsr3": fsr3,
                                     "upscaling": upscaling, "frame-generation": fg})
            set_files(values)
            # A guard left by an earlier run would choose the system's driver again.
            shell_as("rm -f files/vulkan-driver-trying files/vulkan-driver-status")
            print("run %d: %s, %s" % (run, ident, combo), flush=True)
            began = time.strftime("%Y-%m-%d %H:%M")
            env = dict(os.environ)
            if a.shots:
                env["SHOT"] = str(ROOT / "build" / ("shot%d.png" % run))
            result = subprocess.run(["bash", "tools/measure_ostagar.sh", str(run)], cwd=ROOT,
                                    capture_output=True, text=True, env=env)
            stdout = result.stdout
            row, keep = parse(run, stdout)
            upscaler_name(row, upscaling)
            row.update({"run": run, "date": began, "driver": ident if ident == "system"
                        else "%s (%s)" % (meta["name"], ident), "combo": combo, "fsr3": fsr3,
                        "upscaling": upscaling if fsr3 == "off" else "(FSR 3.1)",
                        "frame_generation": fg, "note": a.note})
            with (OUT / ("run%d.txt" % run)).open("w", encoding="utf-8") as out:
                out.write("run %d - driver %s, %s (fsr3 %s, upscaling %s, frame generation %s)\n"
                          % (run, row["driver"], combo, fsr3, upscaling, fg))
                out.write("settings: %s\n\n" % json.dumps(values))
                out.write("measure_ostagar.sh:\n" + stdout + "\n")
                out.write("log lines:\n")
                # Each "frames:" line before the camp is not the measure; keep the last 6.
                frames = [l for l in keep if " frames: " in l][-6:]
                for line in keep:
                    if " frames: " in line and line not in frames:
                        continue
                    out.write(line[:600] + "\n")
            with table.open("a", newline="", encoding="utf-8") as out:
                writer = csv.DictWriter(out, fieldnames=FIELDS)
                if new:
                    writer.writeheader()
                    new = False
                writer.writerow({k: row.get(k, "") for k in FIELDS})
            print("  title %s fps (1%% low %s), panel %s fps, swaps %s/s, GPU %s%%, %s; %s"
                  % (row["title_fps"], row["title_1pct_low"], row["panel_fps"],
                     row["swaps_per_s"], row["gpu_busy_pct"], row["fsr3_state"][:40],
                     row["fg_state"][:50]), flush=True)
            run += 1
    print("next run: %d" % run)


if __name__ == "__main__":
    sys.exit(main())
