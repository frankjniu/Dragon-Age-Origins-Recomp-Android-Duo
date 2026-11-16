// PLAN 9.23 (the owner, 2026-11-16; GitHub issue 4): the Vulkan loader the game uses.
//
// With no package chosen it is the system's libvulkan.so, as it always was. With one
// (files/vulkan-driver holding its name, files/drivers/<name>/ its meta.json and libraries,
// imported by the app - VulkanDrivers.java), libadrenotools opens the system's loader again
// in a linker namespace of its own whose driver is the package's library, and that copy is
// the loader the game's instance and device come from. Its two hooks (libhook_impl.so,
// libmain_hook.so) are the app's, in its library folder.
//
// Everything the app does with Vulkan starts from this handle: Xenia's instance takes it
// (assemble_runtime.py), and the app's own calls get their entry points here
// (GetInstanceProcAddr, GetDeviceProcAddr) - the app no longer links libvulkan, so a call
// to the system loader's exports cannot be written by mistake. D16 (2026-11-03) found FSR
// 3.1 failing on Mesa's Turnip at its memory type lookup (BUG-112): that was the system
// loader's vkGetPhysicalDeviceMemoryProperties asked about the other copy's device.
//
// A package that cannot be loaded leaves the system's driver in its place, said in the
// device report and in files/vulkan-driver-status for the app's row; a start with one that
// never reaches the game (a crash in the driver) is found by the app at its next start
// from files/vulkan-driver-trying, which GameReached removes.
#include "warden_vulkan_loader.h"

#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>

#include <adrenotools/driver.h>

#include "xenia/base/logging.h"

namespace {

std::string g_files_dir;
std::once_flag g_open_once;
void* g_handle = nullptr;
// The package loaded, or empty for the system's driver.
std::string g_package;
std::string g_suffix;
std::string g_describe = "system's (no package chosen)";
PFN_vkGetInstanceProcAddr g_get_instance_proc_addr = nullptr;
PFN_vkGetDeviceProcAddr g_get_device_proc_addr = nullptr;
std::once_flag g_reached_once;

std::string ReadFile(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    return {};
  }
  std::ostringstream text;
  text << in.rdbuf();
  return text.str();
}

std::string Trim(std::string text) {
  const char* space = " \t\r\n";
  text.erase(0, text.find_first_not_of(space));
  const size_t end = text.find_last_not_of(space);
  text.erase(end == std::string::npos ? 0 : end + 1);
  return text;
}

// A string member of meta.json, read plainly (the app checked the file as JSON as it
// imported the package; the names it holds have no escapes worth reading here).
std::string MetaString(const std::string& json, const char* key) {
  const std::string quoted = std::string("\"") + key + "\"";
  size_t at = json.find(quoted);
  if (at == std::string::npos) {
    return {};
  }
  at = json.find(':', at + quoted.size());
  if (at == std::string::npos) {
    return {};
  }
  at = json.find('"', at);
  if (at == std::string::npos) {
    return {};
  }
  const size_t end = json.find('"', at + 1);
  return end == std::string::npos ? std::string() : json.substr(at + 1, end - at - 1);
}

bool PlainName(const std::string& name) {
  return !name.empty() && name != "." && name != ".." &&
         name.find('/') == std::string::npos && name.find('\\') == std::string::npos;
}

void WriteStatus(const std::string& status, const std::string& detail) {
  if (g_files_dir.empty()) {
    return;
  }
  if (FILE* file = fopen((g_files_dir + "/vulkan-driver-status").c_str(), "w")) {
    fprintf(file, "%s\n%s\n", status.c_str(), detail.c_str());
    fclose(file);
  }
}

// The app's library folder, where the hooks are: this library's own.
std::string LibraryFolder() {
  Dl_info info = {};
  if (!dladdr(reinterpret_cast<void*>(&warden_vulkan_loader), &info) || !info.dli_fname) {
    return {};
  }
  std::string path = info.dli_fname;
  const size_t slash = path.rfind('/');
  return slash == std::string::npos ? std::string() : path.substr(0, slash + 1);
}

void* OpenSystem() {
  return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
}

// The package chosen, opened; nullptr (and why) when it cannot be.
void* OpenPackage(const std::string& package, std::string& why, std::string& describe) {
  if (!PlainName(package)) {
    why = "the chosen package's name is not a folder name";
    return nullptr;
  }
  const std::string folder = g_files_dir + "/drivers/" + package + "/";
  const std::string meta = ReadFile(folder + "meta.json");
  if (meta.empty()) {
    why = "no meta.json in files/drivers/" + package;
    return nullptr;
  }
  const std::string library = MetaString(meta, "libraryName");
  if (!PlainName(library)) {
    why = "meta.json names no library";
    return nullptr;
  }
  struct stat st = {};
  if (stat((folder + library).c_str(), &st) != 0) {
    why = library + " is not in the package";
    return nullptr;
  }
  const std::string hooks = LibraryFolder();
  if (hooks.empty() || stat((hooks + "libmain_hook.so").c_str(), &st) != 0) {
    why = "the app's driver hooks are not in its library folder (" + hooks + ")";
    return nullptr;
  }
  describe = MetaString(meta, "name");
  const std::string version = MetaString(meta, "driverVersion");
  const std::string vendor = MetaString(meta, "vendor");
  if (!vendor.empty() || !version.empty()) {
    describe += " (" + vendor + (vendor.empty() || version.empty() ? "" : ", ") + version + ")";
  }
  XELOGI("Vulkan driver: opening the package {} - {}, {} from {}, the hooks from {}", package,
         describe, library, folder, hooks);
  void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                            hooks.c_str(), folder.c_str(), library.c_str(),
                                            nullptr, nullptr);
  if (!handle) {
    why = "libadrenotools could not open it";
  }
  return handle;
}

void Open() {
  const std::string package =
      g_files_dir.empty() ? std::string() : Trim(ReadFile(g_files_dir + "/vulkan-driver"));
  if (!package.empty() && package != "system") {
    std::string why;
    std::string describe;
    g_handle = OpenPackage(package, why, describe);
    if (g_handle) {
      g_package = package;
      g_suffix = "-" + package;
      g_describe = "imported: " + describe + " (files/drivers/" + package + ")";
      WriteStatus("loaded " + package, describe);
    } else {
      XELOGE("Vulkan driver: the package {} cannot be loaded ({}) - the system's driver "
             "instead", package, why);
      g_describe = "system's - the chosen package " + package + " could not be loaded: " + why;
      WriteStatus("failed " + package, why);
    }
  } else {
    WriteStatus("system", "");
  }
  if (!g_handle) {
    g_handle = OpenSystem();
    if (!g_handle) {
      XELOGE("Vulkan driver: libvulkan.so cannot be opened: {}", dlerror());
      return;
    }
  }
  g_get_instance_proc_addr =
      reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(g_handle, "vkGetInstanceProcAddr"));
  g_get_device_proc_addr =
      reinterpret_cast<PFN_vkGetDeviceProcAddr>(dlsym(g_handle, "vkGetDeviceProcAddr"));
  XELOGI("Vulkan driver: {}", g_describe);
}

void EnsureOpen() { std::call_once(g_open_once, Open); }

}  // namespace

extern "C" void* warden_vulkan_loader() {
  EnsureOpen();
  return g_handle;
}

extern "C" const char* warden_vulkan_cache_suffix() {
  EnsureOpen();
  return g_suffix.c_str();
}

// The loader's exports the app's code calls by name (warden_vulkan_loader.h): FSR 3.1's
// SDK (vkCreateBuffer and the physical device's queries), lsfg-vk's and the app's own
// vkGetInstanceProcAddr and vkGetDeviceProcAddr - all that `llvm-nm -D --undefined-only
// libwarden.so` listed from libvulkan before it was unlinked. Hidden: the app's own, never
// another library's. A new one is a link error, to be added here.
namespace {
template <typename T>
T Export(const char* name) {
  EnsureOpen();
  T function = g_handle ? reinterpret_cast<T>(dlsym(g_handle, name)) : nullptr;
  if (!function) {
    XELOGE("Vulkan driver: the loader has no {}", name);
  }
  return function;
}
}  // namespace

#define WARDEN_VK_EXPORT extern "C" __attribute__((visibility("hidden"))) VKAPI_ATTR

WARDEN_VK_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance,
                                                                     const char* name) {
  EnsureOpen();
  return g_get_instance_proc_addr ? g_get_instance_proc_addr(instance, name) : nullptr;
}

WARDEN_VK_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device,
                                                                   const char* name) {
  EnsureOpen();
  return g_get_device_proc_addr ? g_get_device_proc_addr(device, name) : nullptr;
}

WARDEN_VK_EXPORT VkResult VKAPI_CALL vkCreateBuffer(VkDevice device,
                                                    const VkBufferCreateInfo* info,
                                                    const VkAllocationCallbacks* allocator,
                                                    VkBuffer* buffer) {
  static const auto real = Export<PFN_vkCreateBuffer>("vkCreateBuffer");
  return real(device, info, allocator, buffer);
}

WARDEN_VK_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures(
    VkPhysicalDevice physical_device, VkPhysicalDeviceFeatures* features) {
  static const auto real = Export<PFN_vkGetPhysicalDeviceFeatures>("vkGetPhysicalDeviceFeatures");
  real(physical_device, features);
}

WARDEN_VK_EXPORT void VKAPI_CALL vkGetPhysicalDeviceFeatures2(
    VkPhysicalDevice physical_device, VkPhysicalDeviceFeatures2* features) {
  static const auto real =
      Export<PFN_vkGetPhysicalDeviceFeatures2>("vkGetPhysicalDeviceFeatures2");
  real(physical_device, features);
}

WARDEN_VK_EXPORT void VKAPI_CALL vkGetPhysicalDeviceProperties(
    VkPhysicalDevice physical_device, VkPhysicalDeviceProperties* properties) {
  static const auto real =
      Export<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties");
  real(physical_device, properties);
}

WARDEN_VK_EXPORT void VKAPI_CALL vkGetPhysicalDeviceProperties2(
    VkPhysicalDevice physical_device, VkPhysicalDeviceProperties2* properties) {
  static const auto real =
      Export<PFN_vkGetPhysicalDeviceProperties2>("vkGetPhysicalDeviceProperties2");
  real(physical_device, properties);
}

WARDEN_VK_EXPORT void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties(
    VkPhysicalDevice physical_device, VkPhysicalDeviceMemoryProperties* properties) {
  static const auto real =
      Export<PFN_vkGetPhysicalDeviceMemoryProperties>("vkGetPhysicalDeviceMemoryProperties");
  real(physical_device, properties);
}

namespace warden::vulkan_loader {

void Prepare(const std::string& files_dir) { g_files_dir = files_dir; }

std::string Describe() {
  EnsureOpen();
  return g_describe;
}

void Verify() {
  if (g_package.empty()) {
    return;
  }
  // The hook loads the system's driver in the package's place when the package's library
  // cannot be loaded (run 748: Turnip T29 needs pthread_getaffinity_np, which Android 13's
  // libc does not have; run 755: Qualcomm 849 a newer Android's allocator library) - and
  // says so only in its own log. The package's folder in the process's maps is the proof
  // (the path as mapped may be /data/data/... where files_dir says /data/user/0/...).
  std::ifstream maps("/proc/self/maps");
  std::string line;
  bool mapped = false;
  const std::string tail = "/drivers/" + g_package + "/";
  while (std::getline(maps, line)) {
    if (line.find(tail) != std::string::npos) {
      mapped = true;
      break;
    }
  }
  if (mapped) {
    return;
  }
  XELOGE("Vulkan driver: the package {}'s library is not loaded - the system's driver ran in "
         "its place (hook_impl's lines in the log say why)", g_package);
  g_describe = "system's - the chosen package " + g_package +
               " could not be loaded on this device (its library did not load: hook_impl's "
               "lines in the log say why)";
  WriteStatus("failed " + g_package, "its library could not be loaded on this device, so the "
                                     "system's driver ran in its place");
  g_package.clear();
  g_suffix.clear();
}

void GameReached() {
  if (g_package.empty() || g_files_dir.empty()) {
    return;
  }
  std::call_once(g_reached_once, [] {
    if (unlink((g_files_dir + "/vulkan-driver-trying").c_str()) == 0) {
      XELOGI("Vulkan driver: the game is running on {} - the start's guard cleared",
             g_package);
    }
  });
}

}  // namespace warden::vulkan_loader
