// Dragon Age Origins Recomp Android Duo - GPU completion learned off the command
// processor's thread (PLAN 9.24).
// Copyright (C) 2026 the Dragon Age Origins Recomp Android Duo contributors.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Xenia learns which submissions the GPU has finished by asking each pending fence's
// status (vkGetFenceStatus) at every submission it begins - a question that must return at
// once. Mesa's Turnip on Qualcomm's kernel driver (KGSL) before 2026-10-08 (Mesa commit
// e984ef29, "tu/kgsl: Prevent indefinite wait times for 0 timeout waits") asked the kernel
// to wait on the fence's timestamp with a timeout of 0, which KGSL takes as no timeout:
// each status check waited for the GPU to finish the oldest submission, the command
// processor stood still with it, and the GPU ran dry between them - 40-50% of the frame
// rate lost (docs/DRIVERS.md: T30, v26.0.0 R8, Balemuni Apex v2; run 875's profile, two
// thirds of the command processor's time in wait_timestamp_safe).
//
// Here one thread per completion timeline waits on the submitted fences in order, with no
// timeout - the wait every driver does right - and publishes the last finished
// submission. The timeline (tools/assemble_runtime.py's edits to
// vulkan_gpu_completion_timeline.*) reads that number instead of asking the driver, and
// waits on this thread instead of on a fence, so a fence is reset and used again only
// after this thread is done with it. files/fence-watch "off" keeps Xenia's own checks
// (for comparing).

#include <pthread.h>
#include <vulkan/vulkan.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include "xenia/base/logging.h"

namespace {

struct FenceWatch {
  VkDevice device = VK_NULL_HANDLE;
  PFN_vkWaitForFences wait_for_fences = nullptr;
  std::mutex mutex;
  std::condition_variable work;
  std::condition_variable done;
  // <submission, fence> pushed in submission order; the front is the one being waited on.
  std::deque<std::pair<uint64_t, VkFence>> pending;
  std::atomic<uint64_t> completed{0};
  std::atomic<bool> lost{false};
  bool stop = false;
  std::thread thread;
};

void Run(FenceWatch* watch) {
  pthread_setname_np(pthread_self(), "GPU fences");
  std::unique_lock<std::mutex> lock(watch->mutex);
  for (;;) {
    watch->work.wait(lock, [watch] { return watch->stop || !watch->pending.empty(); });
    if (watch->pending.empty()) {
      return;  // Stopping, and every fence pushed has been waited for.
    }
    const std::pair<uint64_t, VkFence> front = watch->pending.front();
    lock.unlock();
    VkResult result = VK_SUCCESS;
    if (!watch->lost.load(std::memory_order_relaxed)) {
      result = watch->wait_for_fences(watch->device, 1, &front.second, VK_TRUE, UINT64_MAX);
    }
    lock.lock();
    watch->pending.pop_front();
    if (result != VK_SUCCESS) {
      // The device is lost (or the wait failed): nothing will finish any more; the
      // waiters are let go and the timeline reports the loss.
      if (!watch->lost.exchange(true)) {
        XELOGE("GPU fences: a fence wait failed ({}), the device taken as lost",
               int(result));
      }
    }
    watch->completed.store(front.first, std::memory_order_release);
    watch->done.notify_all();
  }
}

}  // namespace

// Whether the timelines use the watch: on unless files/fence-watch says "off".
extern "C" bool warden_fence_watch_wanted() {
  static const bool wanted = [] {
    bool value = true;
    if (const char* dir = std::getenv("WARDEN_FILES_DIR")) {
      if (FILE* file = std::fopen((std::string(dir) + "/fence-watch").c_str(), "r")) {
        char text[16] = {};
        if (std::fscanf(file, "%15s", text) == 1 && std::strcmp(text, "off") == 0) {
          value = false;
        }
        std::fclose(file);
      }
    }
    XELOGI("GPU fences: {}", value ? "watched on their own thread (PLAN 9.24)"
                                   : "checked by Xenia itself (files/fence-watch off)");
    return value;
  }();
  return wanted;
}

extern "C" void* warden_fence_watch_create(VkDevice device,
                                           PFN_vkWaitForFences wait_for_fences) {
  if (device == VK_NULL_HANDLE || !wait_for_fences) {
    return nullptr;
  }
  auto* watch = new FenceWatch;
  watch->device = device;
  watch->wait_for_fences = wait_for_fences;
  watch->thread = std::thread(Run, watch);
  return watch;
}

extern "C" void warden_fence_watch_push(void* handle, uint64_t submission, VkFence fence) {
  auto* watch = static_cast<FenceWatch*>(handle);
  {
    std::lock_guard<std::mutex> lock(watch->mutex);
    watch->pending.emplace_back(submission, fence);
  }
  watch->work.notify_one();
}

// The last submission whose fence - and every one before it - the GPU has finished.
extern "C" uint64_t warden_fence_watch_completed(void* handle, int* lost_out) {
  auto* watch = static_cast<FenceWatch*>(handle);
  *lost_out = watch->lost.load(std::memory_order_acquire) ? 1 : 0;
  return watch->completed.load(std::memory_order_acquire);
}

// Waits until the submission is finished; 0, or 1 if the device was lost.
extern "C" int warden_fence_watch_await(void* handle, uint64_t submission) {
  auto* watch = static_cast<FenceWatch*>(handle);
  std::unique_lock<std::mutex> lock(watch->mutex);
  watch->done.wait(lock, [watch, submission] {
    return watch->completed.load(std::memory_order_acquire) >= submission ||
           watch->lost.load(std::memory_order_acquire);
  });
  return watch->lost.load(std::memory_order_acquire) ? 1 : 0;
}

// Waits for every fence pushed, then ends the thread.
extern "C" void warden_fence_watch_destroy(void* handle) {
  auto* watch = static_cast<FenceWatch*>(handle);
  {
    std::lock_guard<std::mutex> lock(watch->mutex);
    watch->stop = true;
  }
  watch->work.notify_one();
  if (watch->thread.joinable()) {
    watch->thread.join();
  }
  delete watch;
}
