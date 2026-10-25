package dev.cloneforge.runtime;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * CloneForge bootstrap Application (manifest attribute android:name replacement).
 *
 * Fallback entry point for launches that bypass the {@link CFComponentFactory} path
 * (providers-first instantiation on API 28, or a carrier/launcher path that rebinds the
 * process without AppComponentFactory). It:
 *  - reads manifest meta-data,
 *  - loads assets/cloneforge/config.json,
 *  - extracts the clean origin APK and initializes the native + Java hooks,
 *  - and, when meta-data {@code cf.orig_application} is present, lazily instantiates the
 *    ORIGINAL Application class and drives its lifecycle (attachBaseContext + onCreate)
 *    on top of a Context whose getPackageName() reports the CLONE package (safe for all
 *    system / Binder callers).
 *
 * Every step is guarded with try/catch: any failure degrades to plain android.app.Application.
 */
public class CFBootstrapApplication extends Application {

    private static final String TAG = "CloneForgeRuntime";

    /** Lazily created original Application, driven by this bootstrap. */
    private static volatile Application sOriginalApplication;

    /** True once the original Application's onCreate has been driven (exactly once). */
    private static volatile boolean sOriginalOnCreateDriven;

    /** The real (clone) base context; kept to source the app classloader. */
    private Context mBaseContext;

    /**
     * Returns the original Application instance when this bootstrap created it, or null when
     * the factory path already instantiated the original application itself.
     */
    public static Application getOriginalApplication() {
        return sOriginalApplication;
    }

    @Override
    protected void attachBaseContext(Context base) {
        mBaseContext = base;

        // Engine init first — but never let it break attachBaseContext.
        try {
            CloneRuntimeEngine.preloadFromApplicationInfo(base.getApplicationInfo());
        } catch (Throwable t) {
            Log.w(TAG, "preloadFromApplicationInfo failed in bootstrap attach", t);
        }
        try {
            ClassLoader cl = CFBootstrapApplication.class.getClassLoader();
            CloneRuntimeEngine.ensureRuntimeInitialized(cl);
        } catch (Throwable t) {
            Log.w(TAG, "ensureRuntimeInitialized failed in bootstrap attach", t);
        }

        // System-safe wrapper: every Binder-bound identity query reports the CLONE package.
        super.attachBaseContext(new SystemSafeContextWrapper(base));

        // Bringing up the original Application subclass (meta-data cf.orig_application).
        try {
            inflateOriginalApplication(base);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to inflate original Application; degrading to plain Application", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            driveOriginalOnCreate();
        } catch (Throwable t) {
            Log.e(TAG, "Original Application.onCreate failed; continuing", t);
        }
    }

    /**
     * Instantiates the original Application and calls its attachBaseContext with a wrapped
     * Context whose getPackageName() returns the CLONE package. The original class is resolved
     * on the app classloader — the payload dex is appended as classesN.dex in the same APK, so
     * both share one PathClassLoader.
     */
    private void inflateOriginalApplication(Context realBase) throws Exception {
        if (sOriginalApplication != null) {
            return; // already inflated (factory-path double attach, multi-process bounce)
        }
        String origAppClass = CloneRuntimeEngine.getOriginalApplicationClassName();
        if (origAppClass == null || origAppClass.isEmpty()
                || CFBootstrapApplication.class.getName().equals(origAppClass)) {
            return; // original manifest had no Application subclass
        }

        ClassLoader cl = (mBaseContext != null) ? mBaseContext.getClassLoader() : null;
        if (cl == null) {
            cl = CFBootstrapApplication.class.getClassLoader();
        }
        if (cl == null) {
            return;
        }

        Class<?> clazz = cl.loadClass(origAppClass);
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Application original = (Application) ctor.newInstance();

        Method attach = findDeclaredMethodInHierarchy(clazz, "attachBaseContext", Context.class);
        if (attach != null) {
            attach.setAccessible(true);
            attach.invoke(original, new SystemSafeContextWrapper(realBase));
        } else {
            Log.w(TAG, "No attachBaseContext(Context) found on " + origAppClass);
            return;
        }

        sOriginalApplication = original;
        Log.i(TAG, "Original Application " + origAppClass + " inflated (onCreate deferred)");
    }

    private static Method findDeclaredMethodInHierarchy(Class<?> start, String name, Class<?>... params) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** Drives the original Application's onCreate exactly once, after OUR super.onCreate(). */
    private void driveOriginalOnCreate() throws Exception {
        Application original = sOriginalApplication;
        if (original == null || sOriginalOnCreateDriven) {
            return;
        }
        sOriginalOnCreateDriven = true;
        try {
            Method onCreate = findDeclaredMethodInHierarchy(original.getClass(), "onCreate");
            if (onCreate != null) {
                onCreate.setAccessible(true);
                onCreate.invoke(original);
            } else {
                original.onCreate();
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException("Original Application.onCreate failed", cause);
        }
    }

    /**
     * Context wrapper that reports the CLONE package for system/Binder-sensitive identity
     * queries (getPackageName()). NOTE: Context.getOpPackageName() is a hidden API and cannot
     * be compiled/overridden against the public android.jar; the stack-aware PackageNameRouter
     * (Spec §5.3) — currently gated on LSPlant — covers those cases. Here we hard-override the
     * public identity entry point which covers attachBaseContext-time system calls.
     */
    private static final class SystemSafeContextWrapper extends ContextWrapper {
        SystemSafeContextWrapper(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            String clone = CloneRuntimeEngine.getClonePackage();
            return (clone != null && !clone.isEmpty())
                    ? clone
                    : super.getPackageName();
        }
    }
}
