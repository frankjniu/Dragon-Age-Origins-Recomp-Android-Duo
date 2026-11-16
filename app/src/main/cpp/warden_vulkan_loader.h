// PLAN 9.23 (the owner, 2026-11-16; GitHub issue 4): the Vulkan loader the game uses - the
// system's, or, with a driver package imported and chosen (Options -> Visual ->
// Experimental -> Vulkan driver), a copy of it tied to that driver by libadrenotools.
// See warden_vulkan_loader.cc.
#pragma once

#include <string>

#include "xenia/ui/vulkan/vulkan_api.h"

// The loader's dlopen handle, opened at the first call: Xenia's VulkanInstance::Create
// takes it in place of dlopen("libvulkan.so") (assemble_runtime.py).
extern "C" void* warden_vulkan_loader();

// The pipeline cache file's suffix (assemble_runtime.py): "" for the system's driver,
// "-<package>" for an imported one - a driver never reads another's cache.
extern "C" const char* warden_vulkan_cache_suffix();

namespace warden::vulkan_loader {

// Before the Vulkan provider is made (Runtime::Startup): the files directory, where
// files/vulkan-driver names the package chosen and files/drivers/<package>/ holds it.
void Prepare(const std::string& files_dir);

// The Vulkan calls the app makes outside Xenia's tables - FSR 3.1's SDK, lsfg-vk and the
// app's own files call vkGetDeviceProcAddr, vkGetPhysicalDeviceProperties and five others
// by name - bind to warden_vulkan_loader.cc's definitions of those names, which forward to
// this loader: libwarden.so links no libvulkan. On an imported driver the game's instance
// and device belong to the other copy of the loader, and the system's exports asked about
// them answered for the wrong one (BUG-112: FSR 3.1's memory type lookup on Mesa's Turnip).

// After the provider has made its device: whether the package's library is the driver
// loaded - the hook loads the system's in its place when it cannot load it - and if not,
// the package reported as failed (the device report, files/vulkan-driver-status).
void Verify();

// The device report's line: which driver, and why the system's if one was chosen and
// could not be loaded.
std::string Describe();

// The game has shown its frames on the imported driver: the start's guard is cleared
// (files/vulkan-driver-trying, which the app reads at its next start - GameActivity).
void GameReached();

}  // namespace warden::vulkan_loader
