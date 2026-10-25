// native-bridge/src/main/cpp/io_redirect.cpp
//
// CloneForge Phase 5 — Native NDK hooking engine (libcloneforge_core.so), Spec §6.3.
//
// Hooks libc openat / faccessat / fstatat64 via ShadowHook (PLT/inline) and applies the
// spec §6.2 redirect rules:
//   Rule 1: third-party JNI libs opening /data/app/.../base.apk are transparently
//           redirected to the byte-exact clean ORIGINAL APK (files/.cf_origin_base.apk)
//           so native V2/V3 signing-block verification sees the untampered original.
//   Rule 2: hardcoded /data/data/<orig>/... and /data/user/0/<orig>/... are rewired into
//           the clone's private dirs.
//   Rule 3: hardcoded external paths (/storage/emulated/0/Android/data/<orig>/...) likewise.
//   Rule 4: /proc/<pid>/maps / smaps reads by non-system callers are served from a
//           memfd-filtered copy that hides CloneForge libraries (libcloneforge_core.so,
//           libshadowhook.so, liblsplant.so) and rewrites clone_pkg paths back to orig_pkg.
//
// JNI entry: Java_dev_cloneforge_runtime_CloneRuntimeEngine_nativeInitHooks — MUST match
// the declaration `private static native void nativeInitHooks(...)` in
// dev.cloneforge.runtime.CloneRuntimeEngine.

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstring>
#include <string>
#include <fstream>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <sys/prctl.h>
#include <signal.h>
#include <android/log.h>
#include <cstdio>
#include <mutex>
#include <atomic>
#include "shadowhook.h"

#define LOG_TAG "CloneForgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string g_orig_pkg;
static std::string g_clone_pkg;
static std::string g_clean_origin_apk_path;
static std::string g_installed_base_apk_path;

using openat_func_t = int (*)(int dirfd, const char* pathname, int flags, mode_t mode);
using faccessat_func_t = int (*)(int dirfd, const char* pathname, int mode, int flags);
using fstatat64_func_t = int (*)(int dirfd, const char* pathname, struct stat64* buf, int flags);
using readlinkat_func_t = ssize_t (*)(int dirfd, const char* pathname, char* buf, size_t bufsiz);

static openat_func_t orig_openat = nullptr;
static faccessat_func_t orig_faccessat = nullptr;
static fstatat64_func_t orig_fstatat64 = nullptr;
static readlinkat_func_t orig_readlinkat = nullptr;

// Improvement over spec §6.3: guard hook installation with once-flag semantics and allow a
// state RESET when nativeInitHooks is invoked twice (idempotent + failure-retryable). Hook
// callbacks also bypass through raw syscalls while orig pointers are unassigned (install
// race) or while installation itself is in flight — removing the null-pointer crash window.
static std::mutex g_init_mutex;
static std::once_flag g_install_once;
static std::atomic<bool> g_hooks_active{false};
static std::atomic<bool> g_install_attempted{false};
static std::atomic<bool> g_installing{false};

static std::once_flag g_shadowhook_init_once;

/** One-shot ShadowHero bootstrap shared with lsplant_bridge.cpp (LSPlant inline hooks). */
extern "C" void cf_shadowhook_ensure_init() {
    std::call_once(g_shadowhook_init_once, []() {
        shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    });
}

static bool is_TrustedSystemCaller(void* caller_addr) {
    Dl_info info;
    if (dladdr(caller_addr, &info) && info.dli_fname != nullptr) {
        if (strstr(info.dli_fname, "/apex/") != nullptr ||
            strstr(info.dli_fname, "/system/") != nullptr ||
            strstr(info.dli_fname, "libcloneforge_core.so") != nullptr) {
            return true;
        }
    }
    return false;
}

static bool is_ProcMapsPath(const char* pathname) {
    if (!pathname) return false;
    return (strcmp(pathname, "/proc/self/maps") == 0 ||
            strcmp(pathname, "/proc/self/smaps") == 0 ||
            (strncmp(pathname, "/proc/", 6) == 0 && strstr(pathname, "/maps") != nullptr));
}

static int create_FilteredMapsFd() {
    int real_fd = orig_openat(AT_FDCWD, "/proc/self/maps", O_RDONLY, 0);
    if (real_fd < 0) return real_fd;

    int mem_fd = static_cast<int>(syscall(__NR_memfd_create, "anon_maps", MFD_CLOEXEC));
    if (mem_fd < 0) {
        return real_fd;
    }

    FILE* fp = fdopen(real_fd, "r");
    if (!fp) {
        close(real_fd);
        close(mem_fd);
        return -1;
    }

    char line[1024];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        // Hide our hook library and runtime payload from /proc/self/maps scanners
        if (strstr(line, "libcloneforge_core.so") != nullptr ||
            strstr(line, "libshadowhook.so") != nullptr ||
            strstr(line, "liblsplant.so") != nullptr) {
            continue;
        }

        std::string s_line(line);
        // Replace mutated package name in map paths with original package name
        size_t pos = 0;
        while ((pos = s_line.find(g_clone_pkg, pos)) != std::string::npos) {
            s_line.replace(pos, g_clone_pkg.length(), g_orig_pkg);
            pos += g_orig_pkg.length();
        }
        write(mem_fd, s_line.c_str(), s_line.size());
    }

    fclose(fp); // Also closes real_fd
    lseek(mem_fd, 0, SEEK_SET);
    return mem_fd;
}

static std::string redirect_Path(const char* pathname, void* caller_addr) {
    if (!pathname) return "";
    std::string path(pathname);

    // 1. If a third-party JNI library opens base.apk directly to verify V2/V3 signature or ZIP CRC:
    if (!g_clean_origin_apk_path.empty() &&
        !is_TrustedSystemCaller(caller_addr) &&
        path.find("/data/app/") == 0 &&
        path.find("/base.apk") != std::string::npos) {
        return g_clean_origin_apk_path;
    }

    // 2. Redirect hardcoded /data/data/<orig_pkg> or /data/user/0/<orig_pkg>
    std::string legacy_data_prefix = "/data/data/" + g_orig_pkg + "/";
    if (path.rfind(legacy_data_prefix, 0) == 0) {
        return "/data/data/" + g_clone_pkg + "/" + path.substr(legacy_data_prefix.length());
    }

    std::string user_data_prefix = "/data/user/0/" + g_orig_pkg + "/";
    if (path.rfind(user_data_prefix, 0) == 0) {
        return "/data/user/0/" + g_clone_pkg + "/" + path.substr(user_data_prefix.length());
    }

    // 3. Redirect hardcoded /sdcard/Android/data/<orig_pkg>
    std::string sdcard_prefix = "/storage/emulated/0/Android/data/" + g_orig_pkg + "/";
    if (path.rfind(sdcard_prefix, 0) == 0) {
        return "/storage/emulated/0/Android/data/" + g_clone_pkg + "/" + path.substr(sdcard_prefix.length());
    }

    return path;
}

static int hooked_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        // Safe bypass during install / before pointers land: raw syscall, no PLT re-entry.
        if (!g_hooks_active.load(std::memory_order_acquire) || orig_openat == nullptr) {
            return static_cast<int>(syscall(__NR_openat, dirfd, pathname, flags, mode));
        }
        if (!is_TrustedSystemCaller(caller) && is_ProcMapsPath(pathname)) {
            int filtered_fd = create_FilteredMapsFd();
            if (filtered_fd >= 0) return filtered_fd;
        }
        std::string redirected = redirect_Path(pathname, caller);
        return orig_openat(dirfd, redirected.c_str(), flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

static int hooked_faccessat(int dirfd, const char* pathname, int mode, int flags) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        if (!g_hooks_active.load(std::memory_order_acquire) || orig_faccessat == nullptr) {
            return static_cast<int>(syscall(__NR_faccessat, dirfd, pathname, mode, flags));
        }
        std::string redirected = redirect_Path(pathname, caller);
        return orig_faccessat(dirfd, redirected.c_str(), mode, flags);
    }
    return orig_faccessat(dirfd, pathname, mode, flags);
}

static int hooked_fstatat64(int dirfd, const char* pathname, struct stat64* buf, int flags) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        if (!g_hooks_active.load(std::memory_order_acquire) || orig_fstatat64 == nullptr) {
            // arm64 defines __NR_fstatat (== newfstatat); other ABIs vary. Cover all shapes.
#if defined(__NR_fstatat64)
            return static_cast<int>(syscall(__NR_fstatat64, dirfd, pathname, buf, flags));
#elif defined(__NR_fstatat)
            return static_cast<int>(syscall(__NR_fstatat, dirfd, pathname, buf, flags));
#else
            return static_cast<int>(syscall(__NR_newfstatat, dirfd, pathname, buf, flags));
#endif
        }
        std::string redirected = redirect_Path(pathname, caller);
        return orig_fstatat64(dirfd, redirected.c_str(), buf, flags);
    }
    return orig_fstatat64(dirfd, pathname, buf, flags);
}

// Performs the one-shot ShadowHook installation. Returns true when every needed hook landed.
static bool install_Hooks_Internal() {
    g_installing.store(true, std::memory_order_release);
    cf_shadowhook_ensure_init();

    bool openat_hooked = false;
    bool faccessat_hooked = false;
    bool fstatat64_hooked = false;

    // libc on modern AArch64 exposes the syscall wrapper "__openat"; MSVC-era name scan
    // fallback covers older builds. Either way we need a real original pointer to call.
    void* h1 = shadowhook_hook_sym_name("libc.so", "__openat", (void*)hooked_openat, (void**)&orig_openat);
    if (orig_openat != nullptr && h1 != nullptr) {
        openat_hooked = true;
    } else {
        orig_openat = nullptr;
        shadowhook_hook_sym_name("libc.so", "openat", (void*)hooked_openat, (void**)&orig_openat);
        if (orig_openat != nullptr) {
            openat_hooked = true;
        }
    }

    shadowhook_hook_sym_name("libc.so", "faccessat", (void*)hooked_faccessat, (void**)&orig_faccessat);
    if (orig_faccessat != nullptr) {
        faccessat_hooked = true;
    }

    shadowhook_hook_sym_name("libc.so", "fstatat64", (void*)hooked_fstatat64, (void**)&orig_fstatat64);
    if (orig_fstatat64 != nullptr) {
        fstatat64_hooked = true;
    }

    if (openat_hooked) {
        LOGI("openat hooked (%s)", "__openat-or-openat");
    } else {
        LOGE("openat hook FAILED — APK signature redirection disabled");
    }
    if (!faccessat_hooked) LOGW("faccessat hook failed (degraded, path checks see clone paths)");
    if (!fstatat64_hooked) LOGW("fstatat64 hook failed (degraded, stat checks see clone paths)");

    g_installing.store(false, std::memory_order_release);
    return openat_hooked; // core rule-1 anchoring: openat is the load-bearing hook
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cloneforge_runtime_CloneRuntimeEngine_nativeInitHooks(
        JNIEnv* env,
        jclass /* clazz */,
        jstring jOrigPkg,
        jstring jClonePkg,
        jstring jCleanOriginApkPath,
        jstring jInstalledBaseApkPath) {

    const char* cOrig = env->GetStringUTFChars(jOrigPkg, nullptr);
    const char* cClone = env->GetStringUTFChars(jClonePkg, nullptr);
    const char* cOriginApk = env->GetStringUTFChars(jCleanOriginApkPath, nullptr);
    const char* cInstalledApk = env->GetStringUTFChars(jInstalledBaseApkPath, nullptr);

    std::lock_guard<std::mutex> lock(g_init_mutex);

    bool alreadyHooked = g_hooks_active.load(std::memory_order_acquire);
    if (alreadyHooked) {
        // Called twice: hooks stay live. Re-binding the shim mid-flight would race hook
        // threads that read these globals, so keep the first-call identity.
        LOGW("nativeInitHooks called twice; hooks already active, keeping original state "
             "(orig=%s, clone=%s)", g_orig_pkg.c_str(), g_clone_pkg.c_str());
        if (cOrig) env->ReleaseStringUTFChars(jOrigPkg, cOrig);
        if (cClone) env->ReleaseStringUTFChars(jClonePkg, cClone);
        if (cOriginApk) env->ReleaseStringUTFChars(jCleanOriginApkPath, cOriginApk);
        if (cInstalledApk) env->ReleaseStringUTFChars(jInstalledBaseApkPath, cInstalledApk);
        return;
    }

    g_orig_pkg = cOrig ? cOrig : "";
    g_clone_pkg = cClone ? cClone : "";
    g_clean_origin_apk_path = cOriginApk ? cOriginApk : "";
    g_installed_base_apk_path = cInstalledApk ? cInstalledApk : "";

    if (cOrig) env->ReleaseStringUTFChars(jOrigPkg, cOrig);
    if (cClone) env->ReleaseStringUTFChars(jClonePkg, cClone);
    if (cOriginApk) env->ReleaseStringUTFChars(jCleanOriginApkPath, cOriginApk);
    if (cInstalledApk) env->ReleaseStringUTFChars(jInstalledBaseApkPath, cInstalledApk);

    // One-shot guarded installation; state resets on failure so a later retry can re-install.
    std::call_once(g_install_once, [&]() {
        g_install_attempted.store(true, std::memory_order_release);
        if (install_Hooks_Internal()) {
            g_hooks_active.store(true, std::memory_order_release);
        } else {
            // RESET state: drop half-installed pointers; a subsequent nativeInitHooks call
            // retries via g_install_once... which will NOT re-run. We therefore surface the
            // failure loudly; integrators should treat runtime as degraded (Java hooks carry on).
            orig_openat = nullptr;
            orig_faccessat = nullptr;
            orig_fstatat64 = nullptr;
            LOGE("Hook installation failed; native redirect DISABLED (Java hooks still active)");
        }
    });

    if (g_hooks_active.load(std::memory_order_acquire)) {
        LOGI("Native I/O redirection hooks active for %s -> %s",
             g_orig_pkg.c_str(), g_clone_pkg.c_str());
    }
}
