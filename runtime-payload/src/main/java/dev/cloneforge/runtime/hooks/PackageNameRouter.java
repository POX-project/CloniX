package dev.cloneforge.runtime.hooks;

import android.util.Log;

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
 * ContextImpl.getOpPackageName() / AttributionSource.getPackageName() → ALWAYS CLONE_PKG.
 * Resources.getIdentifier(name, defType, defPackage): defPackage == CLONE_PKG → ORIG_PKG
 * (resources.arsc keeps the original package namespace).
 *
 * IMPLEMENTATION STATUS: the ART-level interception of ContextImpl's package getters requires
 * an ART method hook (LSPlant). Upstream LSPlant ships a C++-only API (see
 * {@link dev.cloneforge.runtime.CloneRuntimeEngine#HAS_LSPLANT}); with hasLsplant=false this
 * router is inert and {@link #install} skips silently, exactly per the degradation contract.
 * The matrix helpers below are pure and shared for future re-wiring; attach-time system calls
 * are meanwhile covered by CFBootstrapApplication$SystemSafeContextWrapper, and Binder
 * identity strings by IntentRouterHook rewrites.
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

    private static volatile boolean sInstallAttempted = false;

    private PackageNameRouter() {
    }

    /**
     * Attempts route installation. Returns true only when an ART hook engine (LSPlant) is
     * available. Skips silently otherwise (single best-effort log at debug level).
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

    /** Dormant — wired when CloneRuntimeEngine.HAS_LSPLANT becomes true. */
    private static boolean installWithLsplant(String originalPkg, String clonePkg) {
        // Hook targets (each guarded at wiring time):
        //  1. android.content.ContextImpl#getPackageName(int)  → routePackageName(stack-aware)
        //  2. android.content.ContextImpl#getOpPackageName()   → always clonePkg
        //  3. android.content.res.Resources#getIdentifier(...) → defPackage: clonePkg → origPkg
        // Implementation deferred: requires the C++ LSPlant bridge (lsplant::Init/Hook).
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Pure decision helpers (kept stateless & testable)
    // ---------------------------------------------------------------------------------------------

    /** True when the immediate non-self caller frame belongs to a system/systemx namespace. */
    public static boolean callerIsSystemSelfByName(Thread currentThread) {
        try {
            StackTraceElement[] stack = currentThread.getStackTrace();
            // stack[0]=getStackTrace, [1]=this dispatch, [2]=the hook dispatch, next is caller-ish.
            for (int i = 2; i < Math.min(stack.length, 6); i++) {
                String cls = stack[i].getClassName();
                if (cls != null && isSystemClassName(cls)) {
                    return true;
                }
                // Stop scanning as soon as app code appears below the system layer.
                if (!cls.startsWith("dev.cloneforge.runtime.")) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

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
