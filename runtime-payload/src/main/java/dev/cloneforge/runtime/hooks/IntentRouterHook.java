package dev.cloneforge.runtime.hooks;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Subsystem 2 — Binder Intent Router (Spec §8: "Hardcoded Intent ComponentName" and
 * "Android 14+ TargetSDK 34 Receiver Export Enforcement").
 *
 * Installs pure-reflection dynamic proxies on:
 *  - android.app.IActivityManager  (obtained from ActivityManagerNative.getDefault() /
 *    the ActivityManager.IActivityManagerSingleton Singleton field)
 *  - android.app.IActivityTaskManager (ActivityThread.sActivityTaskManager on API 31+,
 *    with legacy "sATM" fallback on API 30)
 *
 * Behaviors:
 *  1. Any Binder call carrying an Intent whose explicit ComponentName package OR setPackage()
 *     equals ORIG_PKG is rewritten in-place to CLONE_PKG, so internal navigation (activities,
 *     services, binders, broadcasts) stays strictly inside the clone instance and never
 *     launches the original app.
 *  2. On API 34+, IActivityManager registerReceiver / registerReceiverWithFeature calls whose
 *     flags argument omits an export flag (== 0) get {@link Context#RECEIVER_NOT_EXPORTED}
 *     injected to satisfy targetSdk 34 runtime enforcement.
 *
 * Pure reflection; no LSPlant required; never throws beyond install().
 */
public final class IntentRouterHook {

    private static final String TAG = "CloneForgeRuntime";

    private static volatile boolean sInstalled = false;

    private IntentRouterHook() {
    }

    /** Installs both Binder proxies. Throws on catastrophic failure; individual stages self-heal. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static void install(String originalPkg, String clonePkg) throws Exception {
        if (sInstalled) {
            return;
        }
        if (originalPkg == null || originalPkg.isEmpty()
                || clonePkg == null || clonePkg.isEmpty()
                || originalPkg.equals(clonePkg)) {
            return; // nothing to route
        }

        sInstalled = true;
        boolean anyInstalled = false;

        // ---- 1. IActivityManager ----------------------------------------------------------
        try {
            Object singleton = getActivityManagerSingleton();
            if (singleton != null) {
                Object real = extractSingletonValue(singleton);
                if (real != null) {
                    Class<?> iface = Class.forName("android.app.IActivityManager");
                    Object proxy = Proxy.newProxyInstance(iface.getClassLoader(),
                            new Class<?>[]{iface},
                            new IntentRewriteHandler(originalPkg, clonePkg, real));
                    writeSingletonValue(singleton, proxy);
                    anyInstalled = true;
                    Log.i(TAG, "IActivityManager proxy installed (Intent routing active)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "IActivityManager proxy not installed: " + t);
        }

        // ---- 2. IActivityTaskManager (API 30/31+) ----------------------------------------
        try {
            if (hookActivityTaskManager(originalPkg, clonePkg)) {
                anyInstalled = true;
                Log.i(TAG, "IActivityTaskManager proxy installed (activity starts routed)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "IActivityTaskManager proxy not installed: " + t);
        }

        if (!anyInstalled) {
            Log.w(TAG, "IntentRouterHook found no Binder entry points; intent routing inactive");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // IActivityManager acquisition (multi-shape across API 28–36)
    // ---------------------------------------------------------------------------------------------
    @SuppressLint("DiscouragedPrivateApi")
    private static Object getActivityManagerSingleton() throws Exception {
        // Shape 1: android.app.ActivityManagerNative.gDefault (Singleton<IActivityManager>) — API 28–30.
        try {
            Class<?> amn = Class.forName("android.app.ActivityManagerNative");
            Field f = findField(amn, "gDefault");
            if (f != null) {
                return f.get(null);
            }
        } catch (ClassNotFoundException ignored) {
            // ActivityManagerNative removed on newer builds.
        }

        // Shape 2: android.app.ActivityManager.IActivityManagerSingleton — API 26–36.
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            Field f = findField(am, "IActivityManagerSingleton");
            if (f != null) {
                return f.get(null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Looking up IActivityManagerSingleton failed: " + t);
        }

        Log.w(TAG, "No IActivityManager singleton handle found; IAM proxy skipped");
        return null;
    }

    @SuppressLint("PrivateApi")
    private static Object extractSingletonValue(Object singleton) throws Exception {
        if (singleton == null) {
            return null;
        }
        try {
            Method getValue = singleton.getClass().getMethod("getValue");
            getValue.setAccessible(true);
            return getValue.invoke(singleton);
        } catch (NoSuchMethodException e) {
            // android.util.Singleton private field
            Field f = findField(singleton.getClass(), "mInstance");
            return f != null ? f.get(singleton) : null;
        }
    }

    private static void writeSingletonValue(Object singleton, Object proxy) throws Exception {
        Field f = findField(singleton.getClass(), "mInstance");
        if (f == null) {
            throw new IllegalStateException("Singleton.mInstance not found");
        }
        f.set(singleton, proxy);
    }

    // ---------------------------------------------------------------------------------------------
    // IActivityTaskManager acquisition
    // ---------------------------------------------------------------------------------------------
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static boolean hookActivityTaskManager(String originalPkg, String clonePkg) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            return false;
        }
        String[] fieldNames = Build.VERSION.SDK_INT >= 31
                ? new String[]{"sActivityTaskManager", "sATM"}
                : new String[]{"sATM", "sActivityTaskManager"};
        for (String name : fieldNames) {
            try {
                Field f = activityThread.getDeclaredField(name);
                f.setAccessible(true);
                Object real = f.get(thread);
                if (real == null) {
                    continue;
                }
                Class<?>[] ifaces = pickInterfaces(real);
                if (ifaces == null || ifaces.length == 0) {
                    return false;
                }
                Object proxy = Proxy.newProxyInstance(ifaces[0].getClassLoader(),
                        ifaces,
                        new IntentRewriteHandler(originalPkg, clonePkg, real));
                f.set(thread, proxy);
                return true;
            } catch (NoSuchFieldException ignored) {
                // try next name
            }
        }
        return false;
    }

    /** Chooses the binder proxy interface deterministically (BinderProxy exposes none). */
    private static Class<?>[] pickInterfaces(Object real) {
        try {
            return new Class<?>[]{Class.forName("android.app.IActivityTaskManager")};
        } catch (Throwable ignored) {
        }
        try {
            return new Class<?>[]{Class.forName("android.app.IActivityManager")};
        } catch (Throwable ignored) {
        }
        return null; // caller treats as fatal for this stage only
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared invocation handler
    // ---------------------------------------------------------------------------------------------
    private static final class IntentRewriteHandler implements InvocationHandler {
        private final String mOrigPkg;
        private final String mClonePkg;
        private final Object mReal;

        IntentRewriteHandler(String origPkg, String clonePkg, Object real) {
            mOrigPkg = origPkg;
            mClonePkg = clonePkg;
            mReal = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if (args != null) {
                    rewriteIntentArgs(method, args);
                    rewriteReceiverFlags(method, args);
                }
                return method.invoke(mReal, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                throw (cause != null) ? cause : ite;
            }
        }

        /** Rewrites every Intent argument: ComponentName package + setPackage + ComponentInfo hinting. */
        private void rewriteIntentArgs(Method method, Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Intent) {
                    Intent intent = (Intent) arg;
                    try {
                        rewriteIntent(intent);
                    } catch (Throwable t) {
                        Log.w(TAG, "Intent rewrite failed for " + method.getName(), t);
                    }
                }
            }
        }

        private void rewriteIntent(Intent intent) {
            if (intent == null) {
                return;
            }
            // Explicit ComponentName targeting the original app — retarget inside the clone.
            android.content.ComponentName cn = intent.getComponent();
            if (cn != null && mOrigPkg.equals(cn.getPackageName())) {
                intent.setClassName(mClonePkg, cn.getClassName());
            }
            // Implicit setPackage targeting the original app.
            String pkg = intent.getPackage();
            if (pkg != null && mOrigPkg.equals(pkg)) {
                intent.setPackage(mClonePkg);
            }
        }

        /**
         * API 34+ (UPSIDE_DOWN_CAKE) enforcement: registerReceiver/registerReceiverWithFeature
         * on IActivityManager end with an int flags parameter. When the caller omitted both
         * RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED (flags == 0) we inject RECEIVER_NOT_EXPORTED.
         * Best-effort: exact flags parameter position is appended-last across observed builds.
         */
        private void rewriteReceiverFlags(Method method, Object[] args) {
            if (Build.VERSION.SDK_INT < 34) {
                return;
            }
            String name = method.getName();
            if (!"registerReceiverWithFeature".equals(name) && !"registerReceiver".equals(name)) {
                return;
            }
            int last = args.length - 1;
            for (int i = last; i >= 0; i--) {
                if (args[i] instanceof Integer) {
                    int flags = (Integer) args[i];
                    if (flags == 0) {
                        args[i] = Context.RECEIVER_NOT_EXPORTED;
                    }
                    break; // only the trailing int (flags) — userId/bitmap ids precede it
                }
            }
        }
    }
}
