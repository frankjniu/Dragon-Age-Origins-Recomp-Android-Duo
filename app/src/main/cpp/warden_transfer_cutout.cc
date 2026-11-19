// Dragon Age Origins Recomp Android Duo - the transfer cut-out (PLAN 9.26).
// Copyright (C) 2026 the Dragon Age Origins Recomp Android Duo contributors.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Xenia keeps the console's EDRAM in host render targets and copies a range from one to
// another when a draw uses it in a different format or layout (an ownership transfer: a
// render pass and a draw of its own). A draw that overwrites a whole rectangle of every
// target it uses - one rectangle or a triangle over the scissor, depth written with ALWAYS
// and the stencil replaced, colours written whole without blending, nothing discarded -
// doesn't need the old contents there: the part of the transfers inside it is left out
// (render_target_cache.cc, tools/assemble_runtime.py). An idea of the Dark Souls II:
// Scholar of the First Sin recomp's renderer (github.com/Lazaer-YZY/ds2-sotfs-recomp,
// gpu_skip_overwritten_transfers), ported.
//
// On by default; files/transfer-cutout "off" turns it off from the next start (to compare).

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "xenia/base/logging.h"

namespace {

std::string Word(const char* name) {
  char text[32] = {};
  if (const char* dir = std::getenv("WARDEN_FILES_DIR")) {
    if (FILE* file = std::fopen((std::string(dir) + "/" + name).c_str(), "r")) {
      if (std::fscanf(file, "%31s", text) != 1) {
        text[0] = 0;
      }
      std::fclose(file);
    }
  }
  return text;
}

// Counted on the GPU commands thread only (RenderTargetCache::Update).
uint64_t g_draws = 0, g_dropped = 0, g_kept = 0;
uint32_t g_detailed = 0;
std::chrono::steady_clock::time_point g_last_log;

}  // namespace

extern "C" bool warden_transfer_cutout_on() {
  static const bool on = [] {
    const bool value = Word("transfer-cutout") != "off";
    XELOGI("GPU: transfer cut-out {}", value ? "on - the transfers a draw overwrites left out"
                                             : "off (files/transfer-cutout)");
    return value;
  }();
  return on;
}

// A draw's cut-out made: its transfers left out entirely (dropped) and those still needed
// around it (kept, drawn outside the rectangle only).
extern "C" void warden_transfer_cutout_note(uint32_t dropped, uint32_t kept, uint32_t x,
                                            uint32_t y, uint32_t width, uint32_t height,
                                            uint32_t targets) {
  ++g_draws;
  g_dropped += dropped;
  g_kept += kept;
  if (g_detailed < 6) {
    ++g_detailed;
    XELOGI("GPU: transfer cut-out {},{} {}x{} (targets {:X}): {} transfers left out, {} "
           "trimmed",
           x, y, width, height, targets, dropped, kept);
  }
  const auto now = std::chrono::steady_clock::now();
  if (now - g_last_log >= std::chrono::seconds(20)) {
    if (g_last_log.time_since_epoch().count()) {
      XELOGI("GPU: transfer cut-out in the last 20 s: {} draws, {} transfers left out, {} "
             "trimmed",
             g_draws, g_dropped, g_kept);
    }
    g_draws = g_dropped = g_kept = 0;
    g_last_log = now;
  }
}
