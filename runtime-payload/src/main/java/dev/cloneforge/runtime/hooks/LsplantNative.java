package dev.cloneforge.runtime.hooks;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * The Java face of the native LSPlant bridge (libcloneforge_core.so / lsplant_bridge.cpp).
 *
 * Pure Java, no androidx. Every native call is defensively wrapped so an unavailable or
 * mismatched bridge degrades to "hooks off" instead of crashing the clone. The natives are
 * resolved lazily only when {@link #initOnce()} is called — an UnsatisfiedLinkError there
 * (payload detached from native-bridge) keeps {@link #sInitOk} false permanently.
 *
 * Bridge contract (mirrors LSPlant v6.4 C++ API):
 * <pre>
 *   boolean nativeInit()                                        -> lsplant::Init(env, InitInfo)
 *   Object  nativeHook(target, hookerObject, callbackMethod)     -> lsplant::Hook(...) [backup Method]
 *   boolean nativeUnhook(target)                                -> lsplant::UnHook(target)
 *   boolean nativeDeoptimize(Executable)                        -> lsplant::Deoptimize(...)
 * </pre>
 * Init ordering (enforced by CloneRuntimeEngine.ensureNativeLoaded):
 * System.loadLibrary("cloneforge_core") -> nativeInitHooks (ShadowHook libc hooks) ->
 * initOnce() (LSPlant Init) -> hook installation.
 */
public final class LsplantNative {

    private static final String TAG = "CloneForgeRuntime";

    private static volatile boolean sInitOk = false;
    private static boolean sInitAttempted = false;

    private LsplantNative() {
    }

    // ---------------------------------------------------------------------------------------------
    // Native bridge (registered by lsplant_bridge.cpp; MUST match symbol names exactly)
    // ---------------------------------------------------------------------------------------------
    public static native boolean nativeInit();

    public static native Object nativeHook(Object target, Object hooker, Object callback);

    public static native boolean nativeUnhook(Object target);

    public static native boolean nativeDeoptimize(Object method);

    // ---------------------------------------------------------------------------------------------
    // Public facade
    // ---------------------------------------------------------------------------------------------

    /**
     * One-shot, idempotent LSPlant bootstrap. Returns true only if the native bridge reported
     * success; a failed attempt is never retried (degradation is sticky for this process).
     */
    public static synchronized boolean initOnce() {
        if (sInitOk) {
            return true;
        }
        if (sInitAttempted) {
            return false;
        }
        sInitAttempted = true;
        boolean ok;
        try {
            ok = nativeInit();
        } catch (Throwable t) {
            Log.w(TAG, "LSPlant native init threw (staying degraded)", t);
            ok = false;
        }
        sInitOk = ok;
        Log.i(TAG, "LSPlant bridge init: " + (ok ? "OK" : "FAILED (hooks remain object-level)"));
        return ok;
    }

    /** True iff {@link #initOnce()} succeeded at least once in this process. */
    public static boolean isInit() {
        return sInitOk;
    }

    /**
     * lsplant::Hook. Returns the backup {@code Method} (invoke it to reach the original), or
     * null on any failure — including "target already hooked".
     */
    public static Object hook(Method target, Object hooker, Method callback) {
        if (!isInit() || target == null || hooker == null || callback == null) {
            return null;
        }
        try {
            return nativeHook(target, hooker, callback);
        } catch (Throwable t) {
            Log.w(TAG, "nativeHook threw for " + target.getName(), t);
            return null;
        }
    }

    /** lsplant::UnHook — restores the original entrypoint for {@code target}. */
    public static boolean unhook(Method target) {
        if (!isInit() || target == null) {
            return false;
        }
        try {
            return nativeUnhook(target);
        } catch (Throwable t) {
            Log.w(TAG, "nativeUnhook threw for " + target.getName(), t);
            return false;
        }
    }

    /** lsplant::Deoptimize — forces JIT/AOT-deoptimized (interpreted) execution of {@code m}. */
    public static boolean deoptimize(Method m) {
        if (!isInit() || m == null) {
            return false;
        }
        try {
            return nativeDeoptimize(m);
        } catch (Throwable t) {
            Log.w(TAG, "nativeDeoptimize threw", t);
            return false;
        }
    }
}
