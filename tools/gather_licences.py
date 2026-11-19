#!/usr/bin/env python3
"""
gather_licences.py - the licences of the code built into the app, for its About page.

PLAN 11.5. The app compiles in code from many projects; their licences (MIT, BSD and the
like) ask that their notices go with every copy, and lsfg-vk's (GPL-3.0) and FFmpeg's
and libmspack's (LGPL-2.1) more. This copies each one's licence text, from the pinned
checkouts in third_party/, into app/src/main/assets/licences/ - one file each, headed by
what it is, what the app uses it for, its version and where its source is - and
index.txt, which the app's About page lists (AboutPage.java). Run it after a pin moves;
--check says whether the files are up to date. The texts are the projects' own; nothing
of the game is in them.

Usage:
  python tools/gather_licences.py
  python tools/gather_licences.py --check
"""
import argparse
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TP = ROOT / "third_party"
XTP = TP / "xenia-canary" / "third_party"
OUT = ROOT / "app" / "src" / "main" / "assets" / "licences"

# The DS2 recomp's SDK licence (sdk/LICENSE at commit 08196a2e), for the code PLAN 9.26
# ported from it.
DS2_SDK_LICENCE = """Copyright (c) 2026, Tom Clay <tomc@tctechstuff.com>

Portions of this software are derived from the Xenia project:
Copyright (c) 2022, Ben Vanik and Xenia project contributors
https://xenia.jp

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""

# (file name, name, licence, used for, version, source, licence text: path or
# ("header", path) for a licence in a source file's opening comment, or None and a
# note in the last field)
COMPONENTS = [
    ("this-app", "Dragon Age Origins Recomp Android Duo (this app)", "GPL-3.0-or-later",
     "everything below put together: the Android app, its launcher and the runtime's"
     " glue", "as About this app says", "as About this app says", ROOT / "LICENSE"),
    ("xenia", "Xenia (xenia-canary)", "BSD 3-Clause",
     "the Xbox 360 GPU emulation (its Vulkan backend), the kernel and file system parts",
     "commit 1c81b867", "https://github.com/xenia-canary/xenia-canary",
     TP / "xenia-canary" / "LICENSE"),
    ("daorecomp", "DAORecomp", "MIT",
     "the recompilation of Dragon Age: Origins, its runtime, its game fixes and mod converter",
     "commit eb2360a4", "https://github.com/96dot/Dragon-Age-Origins-Recomp",
     TP / "DAORecomp" / "LICENSE"),
    ("xenonrecomp", "XenonRecomp (XenonUtils)", "MIT",
     "the PowerPC-to-C++ recompiler and the runtime headers its output uses",
     "commit ddd128bc", "https://github.com/hedge-dev/XenonRecomp",
     TP / "XenonRecomp" / "LICENSE.md"),
    ("simde", "SIMD Everywhere", "MIT",
     "the Xbox 360's vector instructions on ARM", "as XenonRecomp pins it",
     "https://github.com/simd-everywhere/simde",
     TP / "XenonRecomp" / "thirdparty" / "simde" / "COPYING"),
    ("lsfg-vk", "lsfg-vk", "GPL-3.0",
     "frame generation with Lossless Scaling's shaders (read from the player's own"
     " Lossless.dll, which the app does not include)",
     "v2.0.0-dev, commit 0213be9f", "https://github.com/PancakeTAS/lsfg-vk",
     TP / "lsfg-vk" / "LICENSE.md"),
    ("fidelityfx-sdk", "AMD FidelityFX SDK", "MIT",
     "FSR 3.1 upscaling and frame generation", "v1.1.4",
     "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK",
     TP / "FidelityFX-SDK" / "LICENSE.txt"),
    ("fidelityfx-cas", "AMD FidelityFX CAS", "MIT",
     "the presenter's sharpening filter", "as xenia-canary pins it",
     "https://github.com/GPUOpen-Effects/FidelityFX-CAS", XTP / "FidelityFX-CAS" / "LICENSE.txt"),
    ("fidelityfx-fsr", "AMD FidelityFX FSR 1", "MIT",
     "the presenter's upscaling filter", "as xenia-canary pins it",
     "https://github.com/GPUOpen-Effects/FidelityFX-FSR", XTP / "FidelityFX-FSR" / "license.txt"),
    ("ffmpeg", "FFmpeg", "LGPL-2.1-or-later",
     "decoding the game's XMA audio (libavcodec, libavutil)",
     "has207's fork, branch xmaframes, commit d980192e", "https://github.com/has207/FFmpeg",
     XTP / "FFmpeg" / "COPYING.LGPLv2.1"),
    ("libmspack", "libmspack", "LGPL-2.1",
     "unpacking the title update's compressed patch (LZX)", "as XenonRecomp pins it",
     "https://github.com/kyz/libmspack",
     TP / "XenonRecomp" / "thirdparty" / "libmspack" / "libmspack" / "COPYING.LIB"),
    ("glslang", "glslang", "BSD 3-Clause, MIT and Apache-2.0 (per file)",
     "building the shaders' SPIR-V", "as xenia-canary pins it",
     "https://github.com/KhronosGroup/glslang", XTP / "glslang" / "LICENSE.txt"),
    ("vulkan-headers", "Vulkan-Headers", "Apache-2.0 OR MIT",
     "the Vulkan API's definitions", "as xenia-canary pins it",
     "https://github.com/KhronosGroup/Vulkan-Headers", XTP / "Vulkan-Headers" / "LICENSE.md"),
    ("vulkan-memory-allocator", "Vulkan Memory Allocator", "MIT",
     "GPU memory allocation", "as xenia-canary pins it",
     "https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator",
     XTP / "VulkanMemoryAllocator" / "LICENSE.txt"),
    ("fmt", "{fmt}", "MIT", "text formatting", "as xenia-canary pins it",
     "https://github.com/fmtlib/fmt", XTP / "fmt" / "LICENSE"),
    ("snappy", "Snappy", "BSD 3-Clause", "compression", "as xenia-canary pins it",
     "https://github.com/google/snappy", XTP / "snappy" / "COPYING"),
    ("xxhash", "xxHash", "BSD 2-Clause", "hashing", "as xenia-canary pins it",
     "https://github.com/Cyan4973/xxHash", XTP / "xxhash" / "LICENSE"),
    ("disruptorplus", "disruptorplus", "MIT", "the log's queue", "as xenia-canary pins it",
     "https://github.com/xenia-canary/disruptorplus", XTP / "disruptorplus" / "LICENSE.txt"),
    ("date", "date", "MIT", "clocks and calendars", "as xenia-canary pins it",
     "https://github.com/HowardHinnant/date", XTP / "date" / "LICENSE.txt"),
    ("utfcpp", "UTF8-CPP", "BSL-1.0", "text encodings", "as xenia-canary pins it",
     "https://github.com/nemtrif/utfcpp", XTP / "utfcpp" / "LICENSE"),
    ("cxxopts", "cxxopts", "MIT", "settings", "as xenia-canary pins it",
     "https://github.com/jarro2783/cxxopts", XTP / "cxxopts" / "LICENSE"),
    ("tomlplusplus", "toml++", "MIT", "settings files", "as xenia-canary pins it",
     "https://github.com/marzer/tomlplusplus", XTP / "tomlplusplus" / "LICENSE"),
    ("stb", "stb_image", "MIT or public domain (the author's choice of either)",
     "reading and writing images", "as xenia-canary pins it", "https://github.com/nothings/stb",
     ("header", XTP / "stb" / "stb_image.h")),
    ("tiny-aes-c", "tiny-AES-c", "The Unlicense (public domain)",
     "decrypting the game's executable", "as XenonRecomp pins it",
     "https://github.com/kokke/tiny-AES-c",
     TP / "XenonRecomp" / "thirdparty" / "tiny-AES-c" / "unlicense.txt"),
    ("tinysha1", "TinySHA1", "ISC-style (in its source)", "checking files' SHA-1",
     "as XenonRecomp pins it", "https://github.com/mohaps/TinySHA1",
     ("header", next((TP / "XenonRecomp" / "thirdparty" / "TinySHA1").glob("*.hpp"), None))),
    ("7zip", "7-Zip's C decoder (LZMA SDK)", "public domain",
     "unpacking .7z mods", "tag 26.05", "https://github.com/ip7z/7zip",
     None, "The files built (7z*, Bra*, Bcj2, Delta, Lzma*, Ppmd7*, CpuArch) say, each:\n"
           "\"Igor Pavlov : Public domain\"."),
    ("libarchive", "libarchive", "BSD 2-Clause", "unpacking .rar mods (RAR 4 and RAR 5)",
     "v3.8.11", "https://github.com/libarchive/libarchive", TP / "libarchive" / "COPYING"),
    # PLAN 9.23: an imported Vulkan driver package, loaded in a linker namespace of its own.
    ("libadrenotools", "libadrenotools (with liblinkernsbypass)", "BSD 2-Clause",
     "loading a Vulkan driver package you import (Options -> Visual -> Experimental)",
     "commit 8fae8ce2", "https://github.com/bylaws/libadrenotools",
     TP / "libadrenotools" / "LICENSE"),
    # PLAN 9.26: the transfer cut-out's rectangle estimate, ported from the DS2 recomp's SDK
    # (not fetched: its licence text is kept here).
    ("ds2-sotfs-recomp", "ReXGlue SDK (the Dark Souls II: Scholar of the First Sin recomp)",
     "BSD 3-Clause",
     "the rectangle a draw overwrites, for leaving out render target copies "
     "(DrawExtentEstimator::EstimateRectangleCoverage)",
     "commit 08196a2e", "https://github.com/Lazaer-YZY/ds2-sotfs-recomp",
     None, DS2_SDK_LICENCE),
    # PLAN 12.6: what the app makes the game's code with on the device.
    ("llvm", "LLVM (clang, lld, compiler-rt's builtins, libunwind)",
     "Apache-2.0 WITH LLVM-exception",
     "the compiler the app makes the game's code with on this device, from your disc",
     "llvmorg-19.1.7", "https://github.com/llvm/llvm-project",
     TP / "llvm-project" / "llvm" / "LICENSE.TXT"),
    ("android-ndk", "Android NDK (bionic's headers and link stubs, libc++'s headers)",
     "Apache-2.0, BSD and others, as its NOTICE says",
     "the C and C++ headers and libraries the game's code is compiled against on this device",
     "r28c (28.2.13676358)", "https://developer.android.com/ndk",
     Path(os.environ.get("ANDROID_NDK_HOME", Path(os.environ.get("LOCALAPPDATA", "")) /
                         "Android" / "Sdk" / "ndk" / "28.2.13676358")) / "NOTICE"),
]


def header_licence(path):
    """The licence in a source file: stb's at its end, the others in the opening comment."""
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    if "ALTERNATIVE A" in text:
        start = text.rfind("This software is available under 2 licenses")
        end = text.find("*/", start)
        return text[start:end].strip() if start >= 0 else ""
    match = re.match(r"\s*/\*(.*?)\*/", text, re.S)
    return re.sub(r"^\s*\* ?", "", match.group(1), flags=re.M).strip() if match else ""


def build():
    files = {}
    index = []
    for entry in COMPONENTS:
        key, name, licence, used, version, source, text_source = entry[:7]
        note = entry[7] if len(entry) > 7 else ""
        if text_source is None:
            text = note
        elif isinstance(text_source, tuple):
            if text_source[1] is None:
                raise SystemExit("no source file for %s" % name)
            text = header_licence(text_source[1])
        else:
            if not Path(text_source).is_file():
                raise SystemExit("missing %s (python tools/bootstrap.py fetches it)" % text_source)
            text = Path(text_source).read_text(encoding="utf-8", errors="replace").strip()
            # A project whose LICENSE names texts kept beside it (Vulkan-Headers'
            # LICENSES/): those texts too.
            texts_dir = Path(text_source).parent / "LICENSES"
            if texts_dir.is_dir():
                for extra in sorted(texts_dir.glob("*.txt")):
                    text += "\n\n%s\n%s\n\n%s" % (extra.name, "-" * 60, extra.read_text(
                        encoding="utf-8", errors="replace").strip())
        body =("%s\n\nLicence: %s\nIn this app: %s\nVersion: %s\nSource: %s\n\n%s\n"
                % (name, licence, used, version, source, "-" * 60)) + text + "\n"
        files[key + ".txt"] = body
        index.append("%s\t%s\t%s\t%s" % (key + ".txt", name, licence, used))
    files["index.txt"] = "\n".join(index) + "\n"
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    files = build()
    stale = [n for n, t in files.items()
             if not (OUT / n).is_file() or (OUT / n).read_text(encoding="utf-8") != t]
    if args.check:
        print("licences: %d files, %s" % (len(files), "up to date" if not stale
                                           else "stale: " + ", ".join(stale)))
        return 1 if stale else 0
    OUT.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        (OUT / name).write_text(text, encoding="utf-8", newline="\n")
    for old in OUT.glob("*.txt"):
        if old.name not in files:
            old.unlink()
    print("licences: %d files written to %s" % (len(files), OUT.relative_to(ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
