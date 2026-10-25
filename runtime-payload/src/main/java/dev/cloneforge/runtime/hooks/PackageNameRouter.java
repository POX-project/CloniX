package dev.cloneforge.runtime.hooks;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Subsystem 4 — Stack-Aware Context.getPackageName() Router (Spec §5.3 decision matrix).
 *
 * Decision matrix (fires when ContextImpl.getPackageName() is invoked):
 * <pre>
 *   Immediate caller class starts with:
 *     "android." | "com.android." | "androidx.core.app." | "androidx.work."
 *     | "com.google.android.gms.common.internal."
 *        → CLONE_PKG  (system/Binder identity; UID-bound correctness)
 *     anything else (app code / licence SDKs)
 *        → ORIG_PKG   (hardcoded package-name checks pass)
 * </pre>
 * ContextImpl.getOpPackageName() → ALWAYS CLONE_PKG (Binder/UID-bound op string).
 * Resources.getIdentifier(name, defType, defPackage): defPackage == CLONE_PKG → ORIG_PKG
 * (resources.arsc keeps the original package namespace).
 *
 * All three targets are hooked as ART method replacements through LSPlant
 * ({@link Hooker} + {@link LsplantNative}; installed from the C++ bridge in
 * native-bridge lsplant_bridge.cpp). Each hook is individually guarded: a failure on one
 * target skips it and leaves the remaining matrix rows — and the pure-Java fallback matrix
 * helpers below — intact.
 */
public final class PackageNameRouter {

    private static final String TAG = "CloneForgeRuntime";

    private static final String[] SYSTEM_PREFIXES = {
            "android.",
            "com.android.",
            "androidx.core.app.",
            "androidx.work.",
            "com.google.android.gms.common.internal.",
    };

    /** Frames belonging to us or to the LSPlant-generated stub — never treated as callers. */
    private static final String[] IGNORED_FRAME_PREFIXES = {
            "dev.cloneforge.runtime.",
            "LSPHooker_",          // Info.generated_class_name (must match lsplant_bridge.cpp)
            "java.lang.reflect.",  // backup reflection bridge
    };

    private static String sOrigPkg = null;
    private static String sClonePkg = null;
    private static volatile boolean sInstallAttempted = false;

    private PackageNameRouter() {
    }

    /**
     * Attempts route installation. Returns true only when an ART hook engine (LSPlant) is
     * available. Skips silently otherwise (single best-effort log).
     */
    public static boolean install(String originalPkg, String clonePkg) {
        if (sInstallAttempted) {
            return false;
        }
        sInstallAttempted = true;

        if (dev.cloneforge.runtime.CloneRuntimeEngine.HAS_LSPLANT) {
            return installWithLsplant(originalPkg, clonePkg);
        }
        Log.i(TAG, "PackageNameRouter skipped (LSPlant unavailable; stack-aware routing inactive)");
        return false;
    }

    /**
     * LSPlant path. Hook targets (each guarded at wiring time):
     * <ol>
     *   <li>android.content.ContextImpl#getPackageName() (+ the hidden getPackageName(int)
     *       overload) → routePackageName(stack-aware)</li>
     *   <li>android.content.ContextImpl#getOpPackageName() → always clonePkg</li>
     *   <li>android.content.res.Resources#getIdentifier(String,String,String) →
     *       defPackage: clonePkg → origPkg</li>
     * </ol>
     */
    private static boolean installWithLsplant(String originalPkg, String clonePkg) {
        sOrigPkg = originalPkg;
        sClonePkg = clonePkg;
        if (originalPkg == null || originalPkg.isEmpty()
                || clonePkg == null || clonePkg.isEmpty()) {
            Log.w(TAG, "PackageNameRouter: missing orig/clone package; router off");
            return false;
        }

        int hooked = 0;

        // 1. ContextImpl#getPackageName — stack-aware matrix.
        try {
            Class<?> ctxImpl = Class.forName("android.app.ContextImpl");
            HookerHandler router = (hooker, args) -> decideStackAwarePackageName(hooker, args);
            for (Method m : ctxImpl.getDeclaredMethods()) {
                if ("getPackageName".equals(m.getName()) && m.getReturnType() == String.class
                        && (m.getParameterCount() == 0
                            || (m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == int.class))) {
                    if (Hooker.hook(m, router) != null) {
                        hooked++;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageNameRouter: ContextImpl#getPackageName hook failed (row)", t);
        }

        // 2. ContextImpl#getOpPackageName — always CLONE (Binder op identity).
        try {
            Class<?> ctxImpl = Class.forName("android.app.ContextImpl");
            HookerHandler alwaysClone = (hooker, args) -> sClonePkg;
            for (Method m : ctxImpl.getDeclaredMethods()) {
                if ("getOpPackageName".equals(m.getName()) && m.getReturnType() == String.class
                        && m.getParameterCount() == 0) {
                    if (Hooker.hook(m, alwaysClone) != null) {
                        hooked++;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageNameRouter: ContextImpl#getOpPackageName hook failed (row)", t);
        }

        // 3. Resources.getIdentifier — rewrite defPackage when the clone queries by its own name.
        try {
            Method m = android.content.res.Resources.class
                    .getDeclaredMethod("getIdentifier", String.class, String.class, String.class);
            HookerHandler getId = (hooker, args) -> {
                // Instance target: args[0] = receiver, args[1] = name, [2] = defType, [3] = defPackage.
                if (args.length >= 4 && sClonePkg.equals(args[3])) {
                    Object[] rewritten = args.clone();
                    rewritten[3] = sOrigPkg;
                    return hooker.invokeBackup(rewritten);
                }
                return hooker.invokeBackup(args);
            };
            if (Hooker.hook(m, getId) != null) {
                hooked++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageNameRouter: Resources#getIdentifier hook failed (row)", t);
        }

        Log.i(TAG, "PackageNameRouter: " + hooked + " LSPlant hook(s) installed");
        return hooked > 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Decision helpers (kept stateless & testable)
    // ---------------------------------------------------------------------------------------------

    /**
     * §5.3 stack-aware matrix. The LSPlant callback replaces the target frame entirely, so the
     * first stack frame that is neither ours nor the generated hooker IS the caller of
     * ContextImpl.getPackageName().
     */
    private static String decideStackAwarePackageName(Hooker hooker, Object[] args) {
        String caller = firstRealCaller();
        if (caller != null && isSystemClassName(caller)) {
            return sClonePkg;
        }
        return sOrigPkg;
    }

    /**
     * Walks the current stack skipping stack[0] (getStackTrace itself), our payload frames and
     * the LSPlant generated stub; returns the first remaining frame's class or null.
     */
    private static String firstRealCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 1; i < stack.length; i++) {
                String cls = stack[i].getClassName();
                if (cls == null || isIgnoredFrame(cls)) {
                    continue;
                }
                return cls;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isIgnoredFrame(String className) {
        for (String prefix : IGNORED_FRAME_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True when the caller frame belongs to a system/systemx namespace. */
    public static boolean isSystemClassName(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : SYSTEM_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Matrix rule for Resources.getIdentifier(): clone package queries map back to the arsc namespace. */
    public static String routeIdentifierPackage(String defPackage, String origPkg, String clonePkg) {
        if (clonePkg != null && clonePkg.equals(defPackage)) {
            return origPkg;
        }
        return defPackage;
    }
}
