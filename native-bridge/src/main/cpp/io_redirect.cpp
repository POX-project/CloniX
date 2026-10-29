// native-bridge/src/main/cpp/io_redirect.cpp
//
// CloneForge Phase 5 — Native NDK hooking engine (libcloneforge_core.so), Spec §6.3.
//
// Hooks libc open/openat / access/faccessat / stat/fstatat64 / readlink/readlinkat / ptrace via
// ShadowHook (PLT/inline) and applies full stealth & redirect rules:
//   Rule 1: third-party JNI libs opening base.apk are transparently redirected to the byte-exact
//           clean ORIGINAL APK (files/.cf_origin_base.apk) so native V2/V3 signing-block
//           verification sees the untampered original.
//   Rule 2: hardcoded /data/data/<orig>/..., /data/user/0/<orig>/..., /data/user_de/0/<orig>/...
//           are rewired into the clone's private dirs.
//   Rule 3: hardcoded external paths (/storage/emulated/0/Android/{data,media,obb}/<orig>/...)
//           and /sdcard/Android/... are likewise rewired.
//   Rule 4: /proc/<pid>/maps & smaps reads by non-system callers are served from an anonymous
//           memfd that hides CloneForge libraries (libcloneforge_core.so, libshadowhook.so,
//           liblsplant.so, runtime-payload.dex, .cf_ files, and shadowhook anon memory) and
//           rewrites clone_pkg paths back to orig_pkg.
//   Rule 5: /proc/<pid>/cmdline reads are served from an anonymous memfd returning orig_pkg\0.
//   Rule 6: /proc/<pid>/status reads are sanitized to report TracerPid: 0 and orig_pkg.
//   Rule 7: ptrace(PTRACE_TRACEME) anti-debugging probes are neutralized returning 0.

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstring>
#include <string>
#include <fstream>
#include <sstream>
#include <cstdarg>
#include <algorithm>
#include <dirent.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/system_properties.h>
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
using open_func_t = int (*)(const char* pathname, int flags, mode_t mode);
using faccessat_func_t = int (*)(int dirfd, const char* pathname, int mode, int flags);
using access_func_t = int (*)(const char* pathname, int mode);
using fstatat64_func_t = int (*)(int dirfd, const char* pathname, struct stat64* buf, int flags);
using stat_func_t = int (*)(const char* pathname, struct stat* buf);
using readlinkat_func_t = ssize_t (*)(int dirfd, const char* pathname, char* buf, size_t bufsiz);
using readlink_func_t = ssize_t (*)(const char* pathname, char* buf, size_t bufsiz);
using ptrace_func_t = long (*)(int request, pid_t pid, void* addr, void* data);

using readdir_func_t = struct dirent* (*)(DIR* dirp);
using mkdirat_func_t = int (*)(int dirfd, const char* pathname, mode_t mode);
using mkdir_func_t = int (*)(const char* pathname, mode_t mode);
using unlinkat_func_t = int (*)(int dirfd, const char* pathname, int flags);
using unlink_func_t = int (*)(const char* pathname);
using rmdir_func_t = int (*)(const char* pathname);
using renameat2_func_t = int (*)(int olddirfd, const char* oldpath, int newdirfd, const char* newpath, unsigned int flags);
using renameat_func_t = int (*)(int olddirfd, const char* oldpath, int newdirfd, const char* newpath);
using rename_func_t = int (*)(const char* oldpath, const char* newpath);
using statfs64_func_t = int (*)(const char* path, struct statfs64* buf);
using statfs_func_t = int (*)(const char* path, struct statfs* buf);
using statvfs64_func_t = int (*)(const char* path, struct statvfs64* buf);
using statvfs_func_t = int (*)(const char* path, struct statvfs* buf);
using sys_prop_get_func_t = int (*)(const char* name, char* value);

static openat_func_t orig_openat = nullptr;
static open_func_t orig_open = nullptr;
static faccessat_func_t orig_faccessat = nullptr;
static access_func_t orig_access = nullptr;
static fstatat64_func_t orig_fstatat64 = nullptr;
static stat_func_t orig_stat = nullptr;
static stat_func_t orig_lstat = nullptr;
static readlinkat_func_t orig_readlinkat = nullptr;
static readlink_func_t orig_readlink = nullptr;
static ptrace_func_t orig_ptrace = nullptr;

static readdir_func_t orig_readdir = nullptr;
static readdir_func_t orig_readdir64 = nullptr;
static mkdirat_func_t orig_mkdirat = nullptr;
static mkdir_func_t orig_mkdir = nullptr;
static unlinkat_func_t orig_unlinkat = nullptr;
static unlink_func_t orig_unlink = nullptr;
static rmdir_func_t orig_rmdir = nullptr;
static renameat2_func_t orig_renameat2 = nullptr;
static renameat_func_t orig_renameat = nullptr;
static rename_func_t orig_rename = nullptr;
static statfs64_func_t orig_statfs64 = nullptr;
static statfs_func_t orig_statfs = nullptr;
static statvfs64_func_t orig_statvfs64 = nullptr;
static statvfs_func_t orig_statvfs = nullptr;
static sys_prop_get_func_t orig_system_property_get = nullptr;

static std::mutex g_init_mutex;
static std::once_flag g_install_once;
static std::atomic<bool> g_hooks_active{false};
static std::atomic<bool> g_install_attempted{false};
static std::atomic<bool> g_installing{false};

static std::once_flag g_shadowhook_init_once;

/** One-shot ShadowHook bootstrap shared with lsplant_bridge.cpp (LSPlant inline hooks). */
extern "C" void cf_shadowhook_ensure_init() {
    std::call_once(g_shadowhook_init_once, []() {
        int r = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        if (r != 0) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_init failed: %d, errno %d (%s)", r, err, shadowhook_to_errmsg(err));
        } else {
            LOGI("shadowhook_init succeeded (mode: UNIQUE)");
        }
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
            (strncmp(pathname, "/proc/", 6) == 0 &&
             (strstr(pathname, "/maps") != nullptr || strstr(pathname, "/smaps") != nullptr)));
}

static bool is_ProcCmdlinePath(const char* pathname) {
    if (!pathname) return false;
    return (strcmp(pathname, "/proc/self/cmdline") == 0 ||
            (strncmp(pathname, "/proc/", 6) == 0 && strstr(pathname, "/cmdline") != nullptr));
}

static bool is_ProcStatusPath(const char* pathname) {
    if (!pathname) return false;
    return (strcmp(pathname, "/proc/self/status") == 0 ||
            (strncmp(pathname, "/proc/", 6) == 0 && strstr(pathname, "/status") != nullptr));
}

static bool is_ProcStatPath(const char* pathname) {
    if (!pathname) return false;
    if (strcmp(pathname, "/proc/self/stat") == 0) return true;
    if (strncmp(pathname, "/proc/", 6) == 0 && strstr(pathname, "/stat") != nullptr) {
        // Exclude /status
        if (strstr(pathname, "/status") == nullptr) {
            return true;
        }
    }
    return false;
}

static bool is_ProcMountsPath(const char* pathname) {
    if (!pathname) return false;
    return (strcmp(pathname, "/proc/self/mounts") == 0 ||
            strcmp(pathname, "/proc/mounts") == 0 ||
            strcmp(pathname, "/proc/self/mountinfo") == 0 ||
            (strncmp(pathname, "/proc/", 6) == 0 &&
             (strstr(pathname, "/mounts") != nullptr || strstr(pathname, "/mountinfo") != nullptr)));
}

static int create_FilteredMapsFd(const char* target_path = nullptr) {
    const char* p = (target_path && target_path[0] != '\0') ? target_path : "/proc/self/maps";
    int real_fd = orig_openat ? orig_openat(AT_FDCWD, p, O_RDONLY, 0)
                              : static_cast<int>(syscall(__NR_openat, AT_FDCWD, p, O_RDONLY, 0));
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
        // Scrub all CloneForge, hooking engines, runtime dex, and internal artifacts:
        if (strstr(line, "libcloneforge_core.so") != nullptr ||
            strstr(line, "libshadowhook.so") != nullptr ||
            strstr(line, "liblsplant.so") != nullptr ||
            strstr(line, "runtime-payload.dex") != nullptr ||
            strstr(line, ".cf_") != nullptr ||
            strstr(line, "[anon:shadowhook") != nullptr ||
            strstr(line, "[anon:lsplant") != nullptr) {
            continue;
        }

        std::string s_line(line);
        // Replace mutated clone package name in paths with original package name
        if (!g_clone_pkg.empty() && !g_orig_pkg.empty()) {
            size_t pos = 0;
            while ((pos = s_line.find(g_clone_pkg, pos)) != std::string::npos) {
                s_line.replace(pos, g_clone_pkg.length(), g_orig_pkg);
                pos += g_orig_pkg.length();
            }
        }
        write(mem_fd, s_line.c_str(), s_line.size());
    }

    fclose(fp); // Closes real_fd
    lseek(mem_fd, 0, SEEK_SET);
    return mem_fd;
}

static int create_FilteredCmdlineFd() {
    int mem_fd = static_cast<int>(syscall(__NR_memfd_create, "anon_cmdline", MFD_CLOEXEC));
    if (mem_fd < 0) return -1;
    if (!g_orig_pkg.empty()) {
        write(mem_fd, g_orig_pkg.c_str(), g_orig_pkg.size() + 1);
    }
    lseek(mem_fd, 0, SEEK_SET);
    return mem_fd;
}

static int create_FilteredStatusFd(const char* target_path = nullptr) {
    const char* p = (target_path && target_path[0] != '\0') ? target_path : "/proc/self/status";
    int real_fd = orig_openat ? orig_openat(AT_FDCWD, p, O_RDONLY, 0)
                              : static_cast<int>(syscall(__NR_openat, AT_FDCWD, p, O_RDONLY, 0));
    if (real_fd < 0) return real_fd;

    int mem_fd = static_cast<int>(syscall(__NR_memfd_create, "anon_status", MFD_CLOEXEC));
    if (mem_fd < 0) {
        return real_fd;
    }

    FILE* fp = fdopen(real_fd, "r");
    if (!fp) {
        close(real_fd);
        close(mem_fd);
        return -1;
    }

    char line[512];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        std::string s_line(line);
        // Anti-debug spoofing: If TracerPid is non-zero, report 0
        if (s_line.rfind("TracerPid:", 0) == 0) {
            s_line = "TracerPid:\t0\n";
        } else if (s_line.rfind("State:", 0) == 0 &&
                   (s_line.find("(tracing stop)") != std::string::npos ||
                    s_line.find("\tt") != std::string::npos ||
                    s_line.find("\tT") != std::string::npos)) {
            s_line = "State:\tS (sleeping)\n";
        } else if (!g_clone_pkg.empty() && !g_orig_pkg.empty()) {
            size_t pos = 0;
            while ((pos = s_line.find(g_clone_pkg, pos)) != std::string::npos) {
                s_line.replace(pos, g_clone_pkg.length(), g_orig_pkg);
                pos += g_orig_pkg.length();
            }
        }
        write(mem_fd, s_line.c_str(), s_line.size());
    }

    fclose(fp);
    lseek(mem_fd, 0, SEEK_SET);
    return mem_fd;
}

static int create_FilteredStatFd(const char* target_path = nullptr) {
    const char* p = (target_path && target_path[0] != '\0') ? target_path : "/proc/self/stat";
    int real_fd = orig_openat ? orig_openat(AT_FDCWD, p, O_RDONLY, 0)
                              : static_cast<int>(syscall(__NR_openat, AT_FDCWD, p, O_RDONLY, 0));
    if (real_fd < 0) return real_fd;

    int mem_fd = static_cast<int>(syscall(__NR_memfd_create, "anon_stat", MFD_CLOEXEC));
    if (mem_fd < 0) {
        return real_fd;
    }

    char buf[2048];
    ssize_t n = read(real_fd, buf, sizeof(buf) - 1);
    close(real_fd);
    if (n > 0) {
        buf[n] = '\0';
        std::string s_buf(buf, n);
        if (!g_clone_pkg.empty() && !g_orig_pkg.empty()) {
            size_t pos = 0;
            while ((pos = s_buf.find(g_clone_pkg, pos)) != std::string::npos) {
                s_buf.replace(pos, g_clone_pkg.length(), g_orig_pkg);
                pos += g_orig_pkg.length();
            }
        }
        write(mem_fd, s_buf.c_str(), s_buf.size());
    }
    lseek(mem_fd, 0, SEEK_SET);
    return mem_fd;
}

static int create_FilteredMountsFd(const char* target_path = nullptr) {
    const char* p = (target_path && target_path[0] != '\0') ? target_path : "/proc/self/mounts";
    int real_fd = orig_openat ? orig_openat(AT_FDCWD, p, O_RDONLY, 0)
                              : static_cast<int>(syscall(__NR_openat, AT_FDCWD, p, O_RDONLY, 0));
    if (real_fd < 0) return real_fd;

    int mem_fd = static_cast<int>(syscall(__NR_memfd_create, "anon_mounts", MFD_CLOEXEC));
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
        if (strstr(line, "cloneforge") != nullptr ||
            strstr(line, "shadowhook") != nullptr ||
            strstr(line, "lsplant") != nullptr ||
            strstr(line, "magisk") != nullptr ||
            strstr(line, "kernelsu") != nullptr ||
            strstr(line, "ksu") != nullptr ||
            strstr(line, "apatch") != nullptr ||
            strstr(line, ".cf_") != nullptr) {
            continue;
        }

        std::string s_line(line);
        if (!g_clone_pkg.empty() && !g_orig_pkg.empty()) {
            size_t pos = 0;
            while ((pos = s_line.find(g_clone_pkg, pos)) != std::string::npos) {
                s_line.replace(pos, g_clone_pkg.length(), g_orig_pkg);
                pos += g_orig_pkg.length();
            }
        }
        write(mem_fd, s_line.c_str(), s_line.size());
    }

    fclose(fp);
    lseek(mem_fd, 0, SEEK_SET);
    return mem_fd;
}

static std::string redirect_Path(const char* pathname, void* caller_addr) {
    if (!pathname) return "";
    std::string path(pathname);

    // 1. Third-party JNI library opening base.apk directly to verify V2/V3 signature:
    if (!g_clean_origin_apk_path.empty() && !is_TrustedSystemCaller(caller_addr)) {
        if ((path.find("/data/app/") == 0 || path.find("/data/user/") == 0) &&
            path.find(".apk") != std::string::npos) {
            if (path.find(g_clone_pkg) != std::string::npos ||
                (!g_orig_pkg.empty() && path.find(g_orig_pkg) != std::string::npos) ||
                (!g_installed_base_apk_path.empty() && path == g_installed_base_apk_path)) {
                return g_clean_origin_apk_path;
            }
        }
    }

    if (g_orig_pkg.empty() || g_clone_pkg.empty()) {
        return path;
    }

    // 2. Redirect /data/data/<orig_pkg>
    std::string legacy_data_prefix = "/data/data/" + g_orig_pkg;
    if (path == legacy_data_prefix) {
        return "/data/data/" + g_clone_pkg;
    }
    if (path.rfind(legacy_data_prefix + "/", 0) == 0) {
        return "/data/data/" + g_clone_pkg + "/" + path.substr(legacy_data_prefix.length() + 1);
    }

    // 3. Redirect /data/user/0/<orig_pkg>
    std::string user_data_prefix = "/data/user/0/" + g_orig_pkg;
    if (path == user_data_prefix) {
        return "/data/user/0/" + g_clone_pkg;
    }
    if (path.rfind(user_data_prefix + "/", 0) == 0) {
        return "/data/user/0/" + g_clone_pkg + "/" + path.substr(user_data_prefix.length() + 1);
    }

    // 4. Redirect /data/user_de/0/<orig_pkg> (direct-boot storage)
    std::string user_de_prefix = "/data/user_de/0/" + g_orig_pkg;
    if (path == user_de_prefix) {
        return "/data/user_de/0/" + g_clone_pkg;
    }
    if (path.rfind(user_de_prefix + "/", 0) == 0) {
        return "/data/user_de/0/" + g_clone_pkg + "/" + path.substr(user_de_prefix.length() + 1);
    }

    // 5. Redirect external storage paths (/storage/emulated/0 and /sdcard)
    static const char* const ext_prefixes[] = {
        "/storage/emulated/0/Android/data/",
        "/storage/emulated/0/Android/media/",
        "/storage/emulated/0/Android/obb/",
        "/sdcard/Android/data/",
        "/sdcard/Android/media/",
        "/sdcard/Android/obb/"
    };
    for (const char* prefix : ext_prefixes) {
        std::string p = prefix + g_orig_pkg;
        if (path == p) {
            return prefix + g_clone_pkg;
        }
        std::string p_slash = p + "/";
        if (path.rfind(p_slash, 0) == 0) {
            return prefix + g_clone_pkg + "/" + path.substr(p_slash.length());
        }
    }

    return path;
}

static int hooked_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        if (!g_hooks_active.load(std::memory_order_acquire) || orig_openat == nullptr) {
            return static_cast<int>(syscall(__NR_openat, dirfd, pathname, flags, mode));
        }
        if (!is_TrustedSystemCaller(caller)) {
            if (is_ProcMapsPath(pathname)) {
                int filtered_fd = create_FilteredMapsFd(pathname);
                if (filtered_fd >= 0) return filtered_fd;
            } else if (is_ProcCmdlinePath(pathname)) {
                int filtered_fd = create_FilteredCmdlineFd();
                if (filtered_fd >= 0) return filtered_fd;
            } else if (is_ProcStatusPath(pathname)) {
                int filtered_fd = create_FilteredStatusFd(pathname);
                if (filtered_fd >= 0) return filtered_fd;
            } else if (is_ProcStatPath(pathname)) {
                int filtered_fd = create_FilteredStatFd(pathname);
                if (filtered_fd >= 0) return filtered_fd;
            } else if (is_ProcMountsPath(pathname)) {
                int filtered_fd = create_FilteredMountsFd(pathname);
                if (filtered_fd >= 0) return filtered_fd;
            }
        }
        std::string redirected = redirect_Path(pathname, caller);
        return orig_openat(dirfd, redirected.c_str(), flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

static int hooked_open(const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    return hooked_openat(AT_FDCWD, pathname, flags, mode);
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

static int hooked_access(const char* pathname, int mode) {
    return hooked_faccessat(AT_FDCWD, pathname, mode, 0);
}

static int hooked_fstatat64(int dirfd, const char* pathname, struct stat64* buf, int flags) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        if (!g_hooks_active.load(std::memory_order_acquire) || orig_fstatat64 == nullptr) {
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

static int hooked_stat(const char* pathname, struct stat* buf) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        std::string redirected = redirect_Path(pathname, caller);
        if (orig_stat != nullptr) {
            return orig_stat(redirected.c_str(), buf);
        }
#if defined(__NR_fstatat)
        return static_cast<int>(syscall(__NR_fstatat, AT_FDCWD, redirected.c_str(), buf, 0));
#elif defined(__NR_newfstatat)
        return static_cast<int>(syscall(__NR_newfstatat, AT_FDCWD, redirected.c_str(), buf, 0));
#else
        return -1;
#endif
    }
    return orig_stat ? orig_stat(pathname, buf) : -1;
}

static int hooked_lstat(const char* pathname, struct stat* buf) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        std::string redirected = redirect_Path(pathname, caller);
        if (orig_lstat != nullptr) {
            return orig_lstat(redirected.c_str(), buf);
        }
#if defined(__NR_fstatat)
        return static_cast<int>(syscall(__NR_fstatat, AT_FDCWD, redirected.c_str(), buf, AT_SYMLINK_NOFOLLOW));
#elif defined(__NR_newfstatat)
        return static_cast<int>(syscall(__NR_newfstatat, AT_FDCWD, redirected.c_str(), buf, AT_SYMLINK_NOFOLLOW));
#else
        return -1;
#endif
    }
    return orig_lstat ? orig_lstat(pathname, buf) : -1;
}

static ssize_t hooked_readlinkat(int dirfd, const char* pathname, char* buf, size_t bufsiz) {
    void* caller = __builtin_return_address(0);
    const char* target = pathname;
    std::string redirected;
    if (pathname != nullptr) {
        redirected = redirect_Path(pathname, caller);
        target = redirected.c_str();
    }
    ssize_t res = orig_readlinkat ? orig_readlinkat(dirfd, target, buf, bufsiz)
                                  : syscall(__NR_readlinkat, dirfd, target, buf, bufsiz);
    if (res > 0 && buf != nullptr) {
        std::string s_buf(buf, res);
        bool modified = false;

        // 1. Memfd de-cloaking: if reading /proc/self/fd/<fd> of an anonymous memfd,
        // rewrite the fake memfd link back to the expected procfs path
        if (s_buf.find("anon_maps") != std::string::npos) {
            s_buf = "/proc/self/maps";
            modified = true;
        } else if (s_buf.find("anon_cmdline") != std::string::npos) {
            s_buf = "/proc/self/cmdline";
            modified = true;
        } else if (s_buf.find("anon_status") != std::string::npos) {
            s_buf = "/proc/self/status";
            modified = true;
        } else if (s_buf.find("anon_stat") != std::string::npos) {
            s_buf = "/proc/self/stat";
            modified = true;
        } else if (s_buf.find("anon_mounts") != std::string::npos) {
            s_buf = "/proc/self/mounts";
            modified = true;
        }
        // 2. Origin APK de-cloaking: if fd points to our clean origin APK artifact,
        // report the installed base APK path instead
        else if (!g_clean_origin_apk_path.empty() &&
                 (s_buf.find(".cf_origin_base.apk") != std::string::npos ||
                  s_buf == g_clean_origin_apk_path)) {
            if (!g_installed_base_apk_path.empty()) {
                s_buf = g_installed_base_apk_path;
            }
            modified = true;
        }

        // 3. Package name stealth: replace clone_pkg with orig_pkg
        if (!g_clone_pkg.empty() && !g_orig_pkg.empty()) {
            size_t pos = 0;
            while ((pos = s_buf.find(g_clone_pkg, pos)) != std::string::npos) {
                s_buf.replace(pos, g_clone_pkg.length(), g_orig_pkg);
                pos += g_orig_pkg.length();
                modified = true;
            }
        }

        if (modified) {
            size_t copy_len = std::min(s_buf.size(), bufsiz);
            memcpy(buf, s_buf.data(), copy_len);
            res = static_cast<ssize_t>(copy_len);
        }
    }
    return res;
}

static ssize_t hooked_readlink(const char* pathname, char* buf, size_t bufsiz) {
    return hooked_readlinkat(AT_FDCWD, pathname, buf, bufsiz);
}

static struct dirent* filter_Dirent(DIR* dirp, readdir_func_t orig_fn) {
    if (!orig_fn) return nullptr;
    struct dirent* entry = nullptr;
    while ((entry = orig_fn(dirp)) != nullptr) {
        if (entry->d_name[0] != '\0') {
            // Stealth filtering: hide CloneForge artifacts & hooking engines
            if (strncmp(entry->d_name, ".cf_", 4) == 0 ||
                strstr(entry->d_name, "cloneforge") != nullptr ||
                strstr(entry->d_name, "shadowhook") != nullptr ||
                strstr(entry->d_name, "lsplant") != nullptr) {
                continue; // Skip stealth artifact, advance to next directory entry
            }
        }
        return entry;
    }
    return nullptr;
}

static struct dirent* hooked_readdir(DIR* dirp) {
    if (!g_hooks_active.load(std::memory_order_acquire) || orig_readdir == nullptr) {
        return orig_readdir ? orig_readdir(dirp) : nullptr;
    }
    return filter_Dirent(dirp, orig_readdir);
}

static struct dirent* hooked_readdir64(DIR* dirp) {
    if (!g_hooks_active.load(std::memory_order_acquire) || orig_readdir64 == nullptr) {
        return orig_readdir64 ? orig_readdir64(dirp) : nullptr;
    }
    return filter_Dirent(dirp, orig_readdir64);
}

static int hooked_mkdirat(int dirfd, const char* pathname, mode_t mode) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        std::string redirected = redirect_Path(pathname, caller);
        if (orig_mkdirat != nullptr) {
            return orig_mkdirat(dirfd, redirected.c_str(), mode);
        }
        return static_cast<int>(syscall(__NR_mkdirat, dirfd, redirected.c_str(), mode));
    }
    return orig_mkdirat ? orig_mkdirat(dirfd, pathname, mode) : -1;
}

static int hooked_mkdir(const char* pathname, mode_t mode) {
    return hooked_mkdirat(AT_FDCWD, pathname, mode);
}

static int hooked_unlinkat(int dirfd, const char* pathname, int flags) {
    void* caller = __builtin_return_address(0);
    if (pathname != nullptr) {
        std::string redirected = redirect_Path(pathname, caller);
        if (orig_unlinkat != nullptr) {
            return orig_unlinkat(dirfd, redirected.c_str(), flags);
        }
        return static_cast<int>(syscall(__NR_unlinkat, dirfd, redirected.c_str(), flags));
    }
    return orig_unlinkat ? orig_unlinkat(dirfd, pathname, flags) : -1;
}

static int hooked_unlink(const char* pathname) {
    return hooked_unlinkat(AT_FDCWD, pathname, 0);
}

static int hooked_rmdir(const char* pathname) {
    return hooked_unlinkat(AT_FDCWD, pathname, AT_REMOVEDIR);
}

static int hooked_renameat2(int olddirfd, const char* oldpath, int newdirfd, const char* newpath, unsigned int flags) {
    void* caller = __builtin_return_address(0);
    std::string redir_old = oldpath ? redirect_Path(oldpath, caller) : "";
    std::string redir_new = newpath ? redirect_Path(newpath, caller) : "";
    const char* o = oldpath ? redir_old.c_str() : nullptr;
    const char* n = newpath ? redir_new.c_str() : nullptr;
    if (orig_renameat2 != nullptr) {
        return orig_renameat2(olddirfd, o, newdirfd, n, flags);
    }
#if defined(__NR_renameat2)
    return static_cast<int>(syscall(__NR_renameat2, olddirfd, o, newdirfd, n, flags));
#else
    return -1;
#endif
}

static int hooked_renameat(int olddirfd, const char* oldpath, int newdirfd, const char* newpath) {
    void* caller = __builtin_return_address(0);
    std::string redir_old = oldpath ? redirect_Path(oldpath, caller) : "";
    std::string redir_new = newpath ? redirect_Path(newpath, caller) : "";
    const char* o = oldpath ? redir_old.c_str() : nullptr;
    const char* n = newpath ? redir_new.c_str() : nullptr;
    if (orig_renameat != nullptr) {
        return orig_renameat(olddirfd, o, newdirfd, n);
    }
    return hooked_renameat2(olddirfd, oldpath, newdirfd, newpath, 0);
}

static int hooked_rename(const char* oldpath, const char* newpath) {
    return hooked_renameat(AT_FDCWD, oldpath, AT_FDCWD, newpath);
}

static int hooked_statfs64(const char* path, struct statfs64* buf) {
    void* caller = __builtin_return_address(0);
    if (path != nullptr) {
        std::string redirected = redirect_Path(path, caller);
        if (orig_statfs64 != nullptr) {
            return orig_statfs64(redirected.c_str(), buf);
        }
#if defined(__NR_statfs64)
        return static_cast<int>(syscall(__NR_statfs64, redirected.c_str(), sizeof(struct statfs64), buf));
#else
        return -1;
#endif
    }
    return orig_statfs64 ? orig_statfs64(path, buf) : -1;
}

static int hooked_statfs(const char* path, struct statfs* buf) {
    void* caller = __builtin_return_address(0);
    if (path != nullptr) {
        std::string redirected = redirect_Path(path, caller);
        if (orig_statfs != nullptr) {
            return orig_statfs(redirected.c_str(), buf);
        }
        return hooked_statfs64(redirected.c_str(), reinterpret_cast<struct statfs64*>(buf));
    }
    return orig_statfs ? orig_statfs(path, buf) : -1;
}

static int hooked_statvfs64(const char* path, struct statvfs64* buf) {
    void* caller = __builtin_return_address(0);
    if (path != nullptr) {
        std::string redirected = redirect_Path(path, caller);
        if (orig_statvfs64 != nullptr) {
            return orig_statvfs64(redirected.c_str(), buf);
        }
    }
    return orig_statvfs64 ? orig_statvfs64(path, buf) : -1;
}

static int hooked_statvfs(const char* path, struct statvfs* buf) {
    void* caller = __builtin_return_address(0);
    if (path != nullptr) {
        std::string redirected = redirect_Path(path, caller);
        if (orig_statvfs != nullptr) {
            return orig_statvfs(redirected.c_str(), buf);
        }
        return hooked_statvfs64(redirected.c_str(), reinterpret_cast<struct statvfs64*>(buf));
    }
    return orig_statvfs ? orig_statvfs(path, buf) : -1;
}

static int hooked_system_property_get(const char* name, char* value) {
    if (!name || !value) return 0;
    void* caller = __builtin_return_address(0);
    if (!is_TrustedSystemCaller(caller)) {
        if (strcmp(name, "ro.debuggable") == 0) {
            strcpy(value, "0");
            return 1;
        }
        if (strcmp(name, "ro.secure") == 0) {
            strcpy(value, "1");
            return 1;
        }
        if (strcmp(name, "ro.build.type") == 0) {
            strcpy(value, "user");
            return 4;
        }
        if (strcmp(name, "ro.build.tags") == 0) {
            strcpy(value, "release-keys");
            return 12;
        }
        if (strcmp(name, "ro.boot.flash.locked") == 0) {
            strcpy(value, "1");
            return 1;
        }
        if (strcmp(name, "ro.boot.verifiedbootstate") == 0) {
            strcpy(value, "green");
            return 5;
        }
    }
    if (orig_system_property_get != nullptr) {
        return orig_system_property_get(name, value);
    }
    return __system_property_get(name, value);
}

static long hooked_ptrace(int request, pid_t pid, void* addr, void* data) {
    // PTRACE_TRACEME = 0
    if (request == 0) {
        LOGI("ptrace(PTRACE_TRACEME) intercepted: anti-debug neutralized");
        return 0;
    }
    // PTRACE_ATTACH = 16 (anti-debugging watchdog / self-trace)
    if (request == 16 && (pid == 0 || pid == getpid())) {
        LOGI("ptrace(PTRACE_ATTACH, self) intercepted: anti-debug neutralized");
        return 0;
    }
    if (orig_ptrace != nullptr) {
        return orig_ptrace(request, pid, addr, data);
    }
    return syscall(__NR_ptrace, request, pid, addr, data);
}

static bool install_Hooks_Internal() {
    g_installing.store(true, std::memory_order_release);
    cf_shadowhook_ensure_init();

    bool openat_hooked = false;

    // 1. openat (__openat, openat, openat64)
    void* h1 = shadowhook_hook_sym_name("libc.so", "__openat", (void*)hooked_openat, (void**)&orig_openat);
    if (!h1 || !orig_openat) {
        h1 = shadowhook_hook_sym_name("libc.so", "openat", (void*)hooked_openat, (void**)&orig_openat);
    }
    if (!h1 || !orig_openat) {
        h1 = shadowhook_hook_sym_name("libc.so", "openat64", (void*)hooked_openat, (void**)&orig_openat);
    }
    if (h1 && orig_openat) {
        openat_hooked = true;
        LOGI("openat hooked successfully");
    }

    // Fortified open & openat (__open_2, __openat_2)
    shadowhook_hook_sym_name("libc.so", "__open_2", (void*)hooked_open, nullptr);
    shadowhook_hook_sym_name("libc.so", "__openat_2", (void*)hooked_openat, nullptr);

    // 2. open (__open_2, open, open64)
    shadowhook_hook_sym_name("libc.so", "open", (void*)hooked_open, (void**)&orig_open);
    shadowhook_hook_sym_name("libc.so", "open64", (void*)hooked_open, (void**)&orig_open);

    // 3. faccessat (faccessat, faccessat2, __faccessat, __faccessat_2, access)
    shadowhook_hook_sym_name("libc.so", "faccessat", (void*)hooked_faccessat, (void**)&orig_faccessat);
    shadowhook_hook_sym_name("libc.so", "faccessat2", (void*)hooked_faccessat, nullptr);
    shadowhook_hook_sym_name("libc.so", "__faccessat_2", (void*)hooked_faccessat, nullptr);
    shadowhook_hook_sym_name("libc.so", "access", (void*)hooked_access, (void**)&orig_access);

    // 4. fstatat64 / fstatat / newfstatat
    void* fs = shadowhook_hook_sym_name("libc.so", "fstatat64", (void*)hooked_fstatat64, (void**)&orig_fstatat64);
    if (!fs || !orig_fstatat64) {
        fs = shadowhook_hook_sym_name("libc.so", "fstatat", (void*)hooked_fstatat64, (void**)&orig_fstatat64);
    }
    if (!fs || !orig_fstatat64) {
        shadowhook_hook_sym_name("libc.so", "newfstatat", (void*)hooked_fstatat64, (void**)&orig_fstatat64);
    }

    // 5. stat / lstat
    shadowhook_hook_sym_name("libc.so", "stat", (void*)hooked_stat, (void**)&orig_stat);
    shadowhook_hook_sym_name("libc.so", "lstat", (void*)hooked_lstat, (void**)&orig_lstat);

    // 6. readlinkat / readlink
    shadowhook_hook_sym_name("libc.so", "readlinkat", (void*)hooked_readlinkat, (void**)&orig_readlinkat);
    shadowhook_hook_sym_name("libc.so", "readlink", (void*)hooked_readlink, (void**)&orig_readlink);

    // 7. readdir / readdir64
    shadowhook_hook_sym_name("libc.so", "readdir", (void*)hooked_readdir, (void**)&orig_readdir);
    shadowhook_hook_sym_name("libc.so", "readdir64", (void*)hooked_readdir64, (void**)&orig_readdir64);

    // 8. mkdir / mkdirat
    shadowhook_hook_sym_name("libc.so", "mkdirat", (void*)hooked_mkdirat, (void**)&orig_mkdirat);
    shadowhook_hook_sym_name("libc.so", "mkdir", (void*)hooked_mkdir, (void**)&orig_mkdir);

    // 9. unlink / unlinkat / rmdir
    shadowhook_hook_sym_name("libc.so", "unlinkat", (void*)hooked_unlinkat, (void**)&orig_unlinkat);
    shadowhook_hook_sym_name("libc.so", "unlink", (void*)hooked_unlink, (void**)&orig_unlink);
    shadowhook_hook_sym_name("libc.so", "rmdir", (void*)hooked_rmdir, (void**)&orig_rmdir);

    // 10. rename / renameat / renameat2
    shadowhook_hook_sym_name("libc.so", "renameat2", (void*)hooked_renameat2, (void**)&orig_renameat2);
    shadowhook_hook_sym_name("libc.so", "renameat", (void*)hooked_renameat, (void**)&orig_renameat);
    shadowhook_hook_sym_name("libc.so", "rename", (void*)hooked_rename, (void**)&orig_rename);

    // 11. statfs / statfs64 / statvfs / statvfs64
    shadowhook_hook_sym_name("libc.so", "statfs64", (void*)hooked_statfs64, (void**)&orig_statfs64);
    shadowhook_hook_sym_name("libc.so", "statfs", (void*)hooked_statfs, (void**)&orig_statfs);
    shadowhook_hook_sym_name("libc.so", "statvfs64", (void*)hooked_statvfs64, (void**)&orig_statvfs64);
    shadowhook_hook_sym_name("libc.so", "statvfs", (void*)hooked_statvfs, (void**)&orig_statvfs);

    // 12. __system_property_get
    shadowhook_hook_sym_name("libc.so", "__system_property_get", (void*)hooked_system_property_get, (void**)&orig_system_property_get);

    // 13. ptrace
    shadowhook_hook_sym_name("libc.so", "ptrace", (void*)hooked_ptrace, (void**)&orig_ptrace);

    g_installing.store(false, std::memory_order_release);
    return openat_hooked;
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
        LOGW("nativeInitHooks called twice; keeping original state (orig=%s, clone=%s)",
             g_orig_pkg.c_str(), g_clone_pkg.c_str());
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

    std::call_once(g_install_once, [&]() {
        g_install_attempted.store(true, std::memory_order_release);
        if (install_Hooks_Internal()) {
            g_hooks_active.store(true, std::memory_order_release);
        } else {
            orig_openat = nullptr;
            orig_faccessat = nullptr;
            orig_fstatat64 = nullptr;
            LOGE("Hook installation failed; native redirect DISABLED");
        }
    });

    if (g_hooks_active.load(std::memory_order_acquire)) {
        LOGI("Native I/O redirection hooks active for %s -> %s (originApk=%s)",
             g_orig_pkg.c_str(), g_clone_pkg.c_str(), g_clean_origin_apk_path.c_str());
    }
}

