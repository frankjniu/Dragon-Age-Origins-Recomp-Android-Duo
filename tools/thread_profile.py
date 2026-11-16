#!/usr/bin/env python3
"""
thread_profile.py - where the game's threads spend their time at the King's Camp, on a given
Vulkan driver (PLAN 9.24: how BUG-122 was found).

  python tools/thread_profile.py <run> <driver-id|system> [name=value ...]

Sets the driver matrix's shared settings (Bilinear, no frame generation; tools/driver_matrix.py)
and the option files given (name=value; value "-" removes the file), boots the ostagar route
(bash tools/route.sh ostagar first) to the camp, waits 15 s, then:
  - each thread's share of one core over 10 s, from /proc, busiest first
    (build/perf<run>/threads.txt): a thread near 100% is the limit; none near it means
    the threads wait on each other or on the GPU;
  - simpleperf for 8 s with off-CPU time (--trace-offcpu) and frame-pointer call graphs (the
    drivers' libraries unwind that way; DWARF stops at their frames), reported for the
    command processor ("GPU Commands") and the title's render thread as caller graphs
    (build/perf<run>/offcpu-gpu-commands.txt, offcpu-render.txt): where each one blocks.
The log goes to build/run<run>.log; the swaps per second are printed.
"""
import os
import re
import subprocess
import sys
import time

PKG = "dev.daorecompandroid.duo"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.environ["MSYS2_ARG_CONV_EXCL"] = "*"


def sh(cmd, timeout=120):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True,
                          timeout=timeout).stdout


def snapshot(pid):
    out = sh(f'for t in /proc/{pid}/task/*; do echo "$(basename $t) $(cat $t/stat)"; done')
    threads = {}
    for line in out.splitlines():
        try:
            tid, rest = line.split(" ", 1)
            name = rest[rest.index("(") + 1:rest.rindex(")")]
            fields = rest[rest.rindex(")") + 2:].split()
            threads[tid] = (name, int(fields[11]) + int(fields[12]), fields[36])
        except (ValueError, IndexError):
            pass
    return threads


def main():
    run, driver = sys.argv[1], sys.argv[2]
    extra = sys.argv[3:]
    cmds = ["echo 120 > files/frame-rate", "echo 1280x720 > files/resolution",
            "echo on > files/shadows", "echo on > files/vignette",
            "echo exact > files/shader-math", "echo 16 > files/anisotropic",
            "echo off > files/fsr3", "echo bilinear > files/upscaling",
            "echo off > files/frame-generation",
            "rm -f files/vulkan-driver-trying files/vulkan-driver-status"]
    cmds.append("rm -f files/vulkan-driver" if driver == "system"
                else "echo %s > files/vulkan-driver" % driver)
    for kv in extra:
        name, value = kv.split("=", 1)
        cmds.append(("rm -f files/%s" % name) if value == "-" else
                    "echo '%s' > files/%s" % (value, name))
    sh("run-as %s sh -c \"%s\"" % (PKG, "; ".join(cmds)))
    sh("input keyevent KEYCODE_WAKEUP")
    subprocess.run(["adb", "logcat", "-G", "16M"])
    subprocess.run(["adb", "logcat", "-c"])
    sh("am force-stop " + PKG)
    sh("am start -n %s/.GameActivity --ez boot true" % PKG)
    for _ in range(400):
        log = subprocess.run(["adb", "logcat", "-d", "-s", "warden:V"], capture_output=True,
                             text=True, errors="replace").stdout
        if log.count("game: mode is now 0") >= 2:
            break
        time.sleep(1)
    print("at the camp", time.strftime("%H:%M:%S"), flush=True)
    time.sleep(15)
    pid = sh("pidof " + PKG).strip()
    a = snapshot(pid)
    t0 = time.time()
    time.sleep(10)
    b = snapshot(pid)
    dt = time.time() - t0
    rows = []
    for tid, (name, ticks, cpu) in b.items():
        if tid in a:
            rows.append(((ticks - a[tid][1]) / 100.0 / dt * 100, tid, name, cpu))
    rows.sort(reverse=True)
    out_dir = os.path.join(ROOT, "build", "perf" + run)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "threads.txt"), "w") as f:
        for pct, tid, name, cpu in rows[:25]:
            line = "%6.1f%%  %-7s %-20s last on cpu %s" % (pct, tid, name, cpu)
            print(line)
            f.write(line + "\n")
        total = sum(r[0] for r in rows)
        print("process %.1f%% of one core" % total)
        f.write("process %.1f%% of one core\n" % total)
    names = {name: tid for pct, tid, name, cpu in rows}
    gpu_tid = next((tid for pct, tid, name, cpu in rows if name == "GPU Commands"), "")
    rend_tid = next((tid for pct, tid, name, cpu in rows if name.startswith("DragonAge::Rend")), "")
    sp = "/data/local/tmp/simpleperf"
    sh("rm -f /data/local/tmp/perf.data")
    rec = sh("%s record --app %s --call-graph fp --duration 8 -e cpu-clock -f 1000 --trace-offcpu "
             "-o /data/local/tmp/perf.data" % (sp, PKG), timeout=90)
    with open(os.path.join(out_dir, "record-offcpu.txt"), "w") as f:
        f.write(rec + "\nGPU Commands %s, DragonAge::Render %s\n" % (gpu_tid, rend_tid))
    for tid, name in ((gpu_tid, "gpu-commands"), (rend_tid, "render")):
        if not tid:
            continue
        rep = sh("%s report -i /data/local/tmp/perf.data --tids %s -g caller --sort symbol "
                 "--percent-limit 3" % (sp, tid), timeout=240)
        with open(os.path.join(out_dir, "offcpu-%s.txt" % name), "w") as f:
            f.write(rep)
    log = subprocess.run(["adb", "logcat", "-d"], capture_output=True, text=True,
                         errors="replace").stdout
    with open(os.path.join(ROOT, "build", "run%s.log" % run), "w", encoding="utf-8") as f:
        f.write(log)
    sh("am force-stop " + PKG)
    swaps = re.findall(r"(\d\d:\d\d:\d\d\.\d+).*GPU swaps (\d+)", log)
    if len(swaps) >= 2:
        def secs(s):
            h, m, x = s.split(":")
            return int(h) * 3600 + int(m) * 60 + float(x)
        (t1, s1), (t2, s2) = swaps[-5] if len(swaps) >= 5 else swaps[0], swaps[-1]
        print("swaps/s %.1f" % ((int(s2) - int(s1)) / (secs(t2) - secs(t1))))


if __name__ == "__main__":
    main()
