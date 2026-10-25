// native-bridge/src/main/cpp/lsplant_bridge.cpp
//
// CloneForge — LSPlant C++↔Java bridge (libcloneforge_core.so).
//
// Upstream LSPlant v6.4 (org.lsposed.lsplant:lsplant:6.4) exposes a C++-only API, so this
// translation unit owns the Java-facing surface and registers natives for
// dev.cloneforge.runtime.hooks.LsplantNative:
//
//   nativeInit()                                    -> lsplant::Init(env, InitInfo)
//   nativeHook(target, hookerObj, callbackMethod)   -> lsplant::Hook(...)         [backup Method]
//   nativeUnhook(target)                            -> lsplant::UnHook(target)
//   nativeDeoptimize(method)                        -> lsplant::Deoptimize(method)
//
// Mirrored v6.4 API (lsplant/src/main/jni/include/lsplant.hpp, tag v6.4):
//   bool   Init(JNIEnv*, const InitInfo&);
//   jobject Hook(JNIEnv*, jobject target_method, jobject hooker_object, jobject callback_method);
//   bool   UnHook(JNIEnv*, jobject target_method);
//   bool   IsHooked(JNIEnv*, jobject target_method, bool thermal_throttled = false);
//   bool   Deoptimize(JNIEnv*, jobject);
//   struct InitInfo { inline_hooker; inline_unhooker; art_symbol_resolver;
//                     art_symbol_prefix_resolver(optional); generated_class_name = "LSPHooker_"; ... }
//
// inline_hooker / inline_unhooker are backed by ShadowHook:
//   void* shadowhook_hook_func_addr(void* func_addr, void* new_addr, void** orig_addr); // -> stub
//   int   shadowhook_unhook(void* stub);
// The stub returned by shadowhook_hook_func_addr IS the "backup function" LSPlant expects
// (calling the stub executes the original — the same pattern LSPosed uses with Dobby).
//
// art_symbol_resolver must resolve symbols from BOTH .dynsym and .symtab of libart.so
// (hidden ART internals are not exported; dlsym is not enough). We ship a compact in-process
// ELF reader (ELF32 + ELF64, linear scan, file-backed) instead of pulling a full library.
//
// Ordering contract (enforced by CloneRuntimeEngine.ensureNativeLoaded):
//   System.loadLibrary("cloneforge_core")          [JNI_OnLoad caches the JavaVM]
//     -> nativeInitHooks()                         [ShadowHook init + openat/faccessat/fstatat64]
//     -> LsplantNative.initOnce() -> nativeInit()  [shadowhook already live; LSPlant Init]
//     -> LSPlant hooks (PackageNameRouter / ProviderUriHook / DeviceIdentityHook)

#include <jni.h>
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include <lsplant.hpp>
#include "shadowhook.h"

#define LOG_TAG "CloneForgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" void cf_shadowhook_ensure_init();  // implemented in io_redirect.cpp

namespace {

// ---------------------------------------------------------------------------------------------
// libart.so symbol resolution (ELF32 + ELF64, .symtab + .dynsym, linear scan)
// ---------------------------------------------------------------------------------------------

struct MapLine {
    uintptr_t start;
    uintptr_t end;
    unsigned long long file_offset;
    std::string path;
};

static bool parseSelfMaps(std::vector<MapLine>* out) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (fp == nullptr) {
        return false;
    }
    char line[1024];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        uintptr_t start = 0;
        uintptr_t end = 0;
        unsigned long long off = 0;
        char perms[8] = {0};
        unsigned long long inode = 0;
        // addr perms offset dev inode pathname
        int consumed = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s %llx %*x:%*x %llu %n",
                   &start, &end, perms, &off, &inode, &consumed) < 5) {
            continue;
        }
        MapLine m{};
        m.start = start;
        m.end = end;
        m.file_offset = off;
        const char* p = line + consumed;
        while (*p == ' ' || *p == '\t') {
            p++;
        }
        if (*p == '\n' || *p == '\0') {
            p = nullptr;
        }
        if (p != nullptr) {
            m.path.assign(p);
            while (!m.path.empty() && (m.path.back() == '\n' || m.path.back() == ' ')) {
                m.path.pop_back();
            }
        }
        out->push_back(std::move(m));
    }
    fclose(fp);
    return true;
}

/** Base (load bias) of the mapping whose file offset is 0 — the image start of libart.so. */
static bool findLibartBase(std::string* path, uintptr_t* base) {
    std::vector<MapLine> maps;
    if (!parseSelfMaps(&maps)) {
        return false;
    }
    const MapLine* best = nullptr;
    for (const auto& m : maps) {
        if (m.path.empty()) {
            continue;
        }
        // Match by suffix so /apex/com.android.art/lib64/libart.so also matches "libart.so".
        const char* base_name = "libart.so";
        size_t len = strlen(base_name);
        if (m.path.size() < len || m.path.compare(m.path.size() - len, len, base_name) != 0) {
            continue;
        }
        if (m.file_offset != 0) {
            continue;  // image start maps offset 0
        }
        if (best == nullptr || m.start < best->start) {
            best = &m;
        }
    }
    if (best == nullptr) {
        return false;
    }
    if (path != nullptr) {
        *path = best->path;
    }
    if (base != nullptr) {
        *base = best->start;
    }
    return true;
}

/**
 * File-backed ELF reader for the ART image. mmaps the .so and walks section headers;
 * linear-scans SHT_SYMTAB and SHT_DYNSYM for an exact (or optional partial) name match.
 * Runtime address = image base + st_value (Android shared objects use base-relative
 * virtual addresses).
 */
class ArtElfResolver {
public:
    ArtElfResolver() = default;
    ~ArtElfResolver() {
        Close();
    }

    ArtElfResolver(const ArtElfResolver&) = delete;
    ArtElfResolver& operator=(const ArtElfResolver&) = delete;

    bool Open(const char* path) {
        if (path == nullptr) {
            return false;
        }
        fd_ = open(path, O_RDONLY);
        if (fd_ < 0) {
            LOGW("lsplant: cannot open %s (%s)", path, strerror(errno));
            return false;
        }
        struct stat st {};
        if (fstat(fd_, &st) != 0 || st.st_size <= 0) {
            LOGW("lsplant: fstat failed on %s", path);
            return false;
        }
        size_ = static_cast<size_t>(st.st_size);
        map_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (map_ == MAP_FAILED) {
            LOGW("lsplant: mmap failed on %s (%s)", path, strerror(errno));
            map_ = nullptr;
            return false;
        }

        unsigned char* base = static_cast<unsigned char*>(map_);
        // Validate magic + class.
        if (size_ < EI_NIDENT || memcmp(base, ELFMAG, SELFMAG) != 0) {
            LOGW("lsplant: %s is not an ELF image", path);
            return false;
        }
        if (base[EI_CLASS] == ELFCLASS64) {
            is64_ = true;
        } else if (base[EI_CLASS] == ELFCLASS32) {
            is64_ = false;
        } else {
            LOGW("lsplant: unsupported ELF class in %s", path);
            return false;
        }
        return true;
    }

    void Close() {
        if (map_ != nullptr) {
            munmap(map_, size_);
            map_ = nullptr;
            size_ = 0;
        }
        if (fd_ >= 0) {
            close(fd_);
            fd_ = -1;
        }
    }

    /** Linear scan of all symbol sections; first match wins. */
    void* Find(const char* name) const {
        if (map_ == nullptr || name == nullptr) {
            return nullptr;
        }
        void* direct = is64_ ? FindTyped<Elf64_Ehdr, Elf64_Shdr, Elf64_Sym>(name) :
                       FindTyped<Elf32_Ehdr, Elf32_Shdr, Elf32_Sym>(name);
        return direct;
    }

private:
    template <typename EhdrT, typename ShdrT, typename SymT>
    void* FindTyped(const char* name) const {
        auto base = static_cast<const unsigned char*>(map_);
        auto ehdr = reinterpret_cast<const EhdrT*>(base);
        size_t shnum = ehdr->e_shnum;
        size_t shentsize = ehdr->e_shentsize;
        size_t shoff = ehdr->e_shoff;
        if (shnum == 0 || shentsize == 0) {
            return nullptr;
        }
        if (shoff + shnum * shentsize > size_) {
            return nullptr;  // truncated table
        }
        auto shdrs = reinterpret_cast<const ShdrT*>(base + shoff);
        size_t name_len = strlen(name);
        for (size_t i = 0; i < shnum; i++) {
            const auto& sh = shdrs[i];
            if (sh.sh_type != SHT_SYMTAB && sh.sh_type != SHT_DYNSYM) {
                continue;
            }
            if (sh.sh_link >= shnum || sh.sh_entsize == 0) {
                continue;
            }
            const auto& str_sh = shdrs[sh.sh_link];
            auto strtab = base + str_sh.sh_offset;
            size_t strtab_size = str_sh.sh_size;
            if (sh.sh_offset + sh.sh_size > size_) {
                continue;
            }
            auto syms = reinterpret_cast<const SymT*>(base + sh.sh_offset);
            size_t count = sh.sh_size / sh.sh_entsize;
            for (size_t s = 0; s < count; s++) {
                const SymT& sym = syms[s];
                if (sym.st_name >= strtab_size) {
                    continue;
                }
                const char* sym_name = reinterpret_cast<const char*>(strtab + sym.st_name);
                if (strncmp(sym_name, name, name_len) == 0 && sym_name[name_len] == '\0'
                        && sym.st_value != 0) {
                    if (found_first_ == nullptr) {
                        found_first_ = sym_name;
                    }
                    return reinterpret_cast<void*>(sym.st_value);
                }
            }
        }
        return nullptr;
    }

    void* map_ = nullptr;
    size_t size_ = 0;
    int fd_ = -1;
    bool is64_ = false;
    // Reserved for future diagnostics (first matched exact symbol name).
    const char* found_first_ = nullptr;
};

static std::once_flag g_art_once;
static ArtElfResolver* g_art_elf = nullptr;
static uintptr_t g_art_base = 0;
static bool g_art_ready = false;

/** Collects the raw symbol address (base-relative st_value); resolver-ready check flags. */
static void* resolveArtSymbol(std::string_view symbol) {
    std::call_once(g_art_once, []() {
        std::string path;
        uintptr_t base = 0;
        if (!findLibartBase(&path, &base)) {
            LOGE("lsplant: libart.so not found in /proc/self/maps");
            return;
        }
        auto* elf = new (std::nothrow) ArtElfResolver();
        if (elf == nullptr || !elf->Open(path.c_str())) {
            delete elf;
            return;
        }
        g_art_elf = elf;
        g_art_base = base;
        g_art_ready = true;
        LOGI("lsplant: libart.so resolved (base=0x%" PRIxPTR ", path=%s)", base, path.c_str());
    });
    if (!g_art_ready || g_art_elf == nullptr) {
        return nullptr;
    }
    void* rel = g_art_elf->Find(std::string(symbol).c_str());
    if (rel == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<void*>(g_art_base + reinterpret_cast<uintptr_t>(rel));
}

// ---------------------------------------------------------------------------------------------
// LSPlant bootstrap
// ---------------------------------------------------------------------------------------------

static JavaVM* g_vm = nullptr;
static std::once_flag g_lsplant_init_once;
static bool g_lsplant_ok = false;

static bool lsplantInitOnce(JNIEnv* env) {
    std::call_once(g_lsplant_init_once, [&]() {
        if (env == nullptr) {
            return;
        }
        // Engine order already called nativeInitHooks() -> shadowhook is live. Guard anyway
        // so a stray direct call cannot reach shadowhook_hook_func_addr on an
        // uninitialized shadowhook.
        cf_shadowhook_ensure_init();

        lsplant::InitInfo info{};
        info.inline_hooker = [](void* target, void* hooker) -> void* {
            if (target == nullptr || hooker == nullptr) {
                return nullptr;
            }
            void* orig = nullptr;
            // The returned stub executes the original and doubles as LSPlant's backup fn.
            void* stub = shadowhook_hook_func_addr(target, hooker, &orig);
            if (stub == nullptr) {
                LOGW("lsplant: shadowhook_hook_func_addr failed for target %p", target);
                return nullptr;
            }
            return stub;
        };
        info.inline_unhooker = [](void* stub) -> bool {
            return stub != nullptr && shadowhook_unhook(stub) == 0;
        };
        info.art_symbol_resolver = [](std::string_view name) -> void* {
            return resolveArtSymbol(name);
        };
        info.art_symbol_prefix_resolver = nullptr;  // optional in v6.x (unused by lsplant.cc)
        // Default names are kept (generated_class_name = "LSPHooker_"). PackageNameRouter's
        // stack filter relies on this literal — change it only in both places together.
        info.generated_class_name = "LSPHooker_";
        info.generated_source_name = "LSP";
        info.generated_field_name = "hooker";

        g_lsplant_ok = lsplant::Init(env, info);
        LOGI("lsplant::Init -> %s", g_lsplant_ok ? "true" : "FALSE");
    });
    return g_lsplant_ok;
}

}  // namespace

// ---------------------------------------------------------------------------------------------
// JNI bridge for dev.cloneforge.runtime.hooks.LsplantNative
// ---------------------------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_vm = vm;
    LOGI("lsplant_bridge: JNI_OnLoad (javaVM cached)");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cloneforge_runtime_hooks_LsplantNative_nativeInit(JNIEnv* env, jclass /*clazz*/) {
    if (g_vm == nullptr) {
        LOGE("lsplant_bridge: JNI_OnLoad did not run; cannot init LSPlant");
        return JNI_FALSE;
    }
    return lsplantInitOnce(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cloneforge_runtime_hooks_LsplantNative_nativeHook(
        JNIEnv* env, jclass /*clazz*/,
        jobject target, jobject hooker_object, jobject callback_method) {
    if (!g_lsplant_ok || target == nullptr || hooker_object == nullptr
            || callback_method == nullptr) {
        return nullptr;
    }
    try {
        // Returns a global-ref'd java.lang.reflect.Method backed-up entrypoint, or nullptr.
        return lsplant::Hook(env, target, hooker_object, callback_method);
    } catch (...) {
        LOGE("lsplant::Hook threw a C++ exception; returning null (degraded)");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cloneforge_runtime_hooks_LsplantNative_nativeUnhook(
        JNIEnv* env, jclass /*clazz*/, jobject target) {
    if (!g_lsplant_ok || target == nullptr) {
        return JNI_FALSE;
    }
    try {
        return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cloneforge_runtime_hooks_LsplantNative_nativeDeoptimize(
        JNIEnv* env, jclass /*clazz*/, jobject target) {
    if (!g_lsplant_ok || target == nullptr) {
        return JNI_FALSE;
    }
    try {
        return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
