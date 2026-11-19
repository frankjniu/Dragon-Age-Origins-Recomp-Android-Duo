// Dragon Age Origins Recomp Android Duo - Eco shading (PLAN 9.26).
// Copyright (C) 2026 the Dragon Age Origins Recomp Android Duo contributors.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Options -> Eco shading (files/eco-shading "2x1" or "2x2", from the next start): the 3D
// scene shaded once a 2x1 or 2x2 block of pixels through VK_KHR_fragment_shading_rate's
// per-draw (dynamic) rate; "off", the default, leaves the device and the pipelines as
// before. An idea of the Dark Souls II: Scholar of the First Sin recomp's Xenia-derived
// renderer (github.com/Lazaer-YZY/ds2-sotfs-recomp, gpu_eco_shading_rate), reimplemented.
// Which draws are shaded coarsely is decided per draw in vulkan_command_processor.cc
// (tools/assemble_runtime.py): depth-tested draws into full-size single-sampled targets
// that cannot discard pixels - the HUD, text, post-processing, shadow maps and alpha-tested
// foliage keep one shader invocation a pixel.

#include <vulkan/vulkan.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "xenia/base/logging.h"

namespace {

std::atomic<bool> g_eco_available{false};
PFN_vkCmdSetFragmentShadingRateKHR g_set_fragment_shading_rate = nullptr;

// The first word of files/<name>, or "" without the file.
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

}  // namespace

// Eco shading's rate as wanted at the start: 0 off, else width | height << 4 (2x1 0x12,
// 2x2 0x22).
extern "C" uint32_t warden_eco_rate() {
  static const uint32_t rate = [] {
    const std::string word = Word("eco-shading");
    const uint32_t value = word == "2x2" ? 0x22u : word == "2x1" ? 0x12u : 0u;
    XELOGI("GPU: eco shading {}", value ? word + " for the 3D scene (files/eco-shading)"
                                        : std::string("off"));
    return value;
  }();
  return rate;
}

// The device made, with or without VK_KHR_fragment_shading_rate's pipeline rate
// (vulkan_device.cc).
extern "C" void warden_eco_device(VkDevice device, PFN_vkGetDeviceProcAddr get_proc,
                                  bool pipeline_rate) {
  g_set_fragment_shading_rate =
      pipeline_rate && get_proc
          ? reinterpret_cast<PFN_vkCmdSetFragmentShadingRateKHR>(
                get_proc(device, "vkCmdSetFragmentShadingRateKHR"))
          : nullptr;
  g_eco_available.store(g_set_fragment_shading_rate != nullptr, std::memory_order_release);
  if (warden_eco_rate()) {
    XELOGI("GPU: eco shading {}", g_set_fragment_shading_rate
                                      ? "available - the device has the pipeline shading rate"
                                      : "not available - no pipeline shading rate on this "
                                        "device; the scene shaded per pixel");
  }
}

// Whether the guest pipelines take the shading rate as dynamic state: Eco shading wanted
// and the device able (vulkan_pipeline_cache.cc, vulkan_command_processor.cc).
extern "C" bool warden_eco_dynamic() {
  return warden_eco_rate() != 0 && g_eco_available.load(std::memory_order_acquire);
}

// A deferred command (CmdWardenCallback): the shading rate, its data width | height << 4.
extern "C" void warden_eco_set_rate(void* data, VkCommandBuffer command_buffer) {
  if (!g_set_fragment_shading_rate) {
    return;
  }
  const uint32_t rate = uint32_t(reinterpret_cast<uintptr_t>(data));
  const VkExtent2D size = {rate & 0xF, rate >> 4};
  const VkFragmentShadingRateCombinerOpKHR combiners[2] = {
      VK_FRAGMENT_SHADING_RATE_COMBINER_OP_KEEP_KHR,
      VK_FRAGMENT_SHADING_RATE_COMBINER_OP_KEEP_KHR};
  g_set_fragment_shading_rate(command_buffer, &size, combiners);
}
