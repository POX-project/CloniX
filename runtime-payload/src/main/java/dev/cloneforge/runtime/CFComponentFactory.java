package dev.cloneforge.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CloneForge bootstrap AppComponentFactory (API 28+, manifest attribute android:appComponentFactory).
 *
 * Runs before Application.attachBaseContext() and before any ContentProvider.onCreate() in the
 * clone process. It delegates every instantiate* call to the ORIGINAL AppComponentFactory
 * recorded in manifest meta-data {@code cf.orig_factory} (or falls back to the base
 * {@link AppComponentFactory} behavior if the delegate cannot be created).
 *
 * Spec reference: CloneForge spec §4.2 / engine diagram §1.2 (tail).
 */
public class CFComponentFactory extends AppComponentFactory {

    private static final String TAG = "CloneForgeRuntime";

    /** Guard so the engine is initialized exactly once per process. */
    private static final AtomicBoolean sRuntimeInstalled = new AtomicBoolean(false);

    /** Delegate created lazily from meta-data {@code cf.orig_factory}. */
    private AppComponentFactory mDelegateFactory;

    /** Pre-built fallback so a broken delegate can never take down the process. */
    private final AppComponentFactory mDefaultFactory = new AppComponentFactory();

    /**
     * Installs the CloneForge in-process runtime at the earliest lifecycle moment.
     * Kept public/static so {@link CFBootstrapApplication} and external tooling can reuse it.
     * Never throws.
     */
    public static void ensureRuntimeInitialized(ClassLoader cl) {
        if (sRuntimeInstalled.compareAndSet(false, true)) {
            try {
                CloneRuntimeEngine.ensureRuntimeInitialized(cl);
            } catch (Throwable t) {
                Log.e(TAG, "CloneForge early runtime initialization failed; degrading gracefully", t);
            }
        } else {
            // Already installed (or being installed on another thread); just make sure any
            // deferred second-phase work (e.g. origin-APK extraction) gets done.
            try {
                CloneRuntimeEngine.ensureRuntimeInitialized(cl);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Returns the original AppComponentFactory from meta-data {@code cf.orig_factory}, lazily
     * instantiated on {@code cl}. On any failure falls back to the platform default behavior.
     */
    private synchronized AppComponentFactory getDelegate(ClassLoader cl) {
        if (mDelegateFactory != null && mDelegateFactory != mDefaultFactory) {
            return mDelegateFactory;
        }
        try {
            String origFactoryName = CloneRuntimeEngine.getOriginalFactoryClassName();
            if (origFactoryName != null && !origFactoryName.isEmpty()
                    && !CFComponentFactory.class.getName().equals(origFactoryName)) {
                try {
                    Class<?> clazz = cl.loadClass(origFactoryName);
                    mDelegateFactory = (AppComponentFactory) clazz.getDeclaredConstructor().newInstance();
                    Log.i(TAG, "Instantiated delegate AppComponentFactory: " + origFactoryName);
                    return mDelegateFactory;
                } catch (Throwable t) {
                    Log.w(TAG, "Could not instantiate delegate AppComponentFactory: " + origFactoryName, t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to resolve cf.orig_factory meta-data", t);
        }
        if (mDelegateFactory == null) {
            mDelegateFactory = mDefaultFactory;
        }
        return mDelegateFactory;
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        try {
            // Step 0: Lift hidden API restrictions so reflection/JNI into ART internals succeeds.
            HiddenApiBypass.addHiddenApiExemptions("L");
            // Must run first: parses config.json + meta-data and warms static state.
            CloneRuntimeEngine.preloadFromApplicationInfo(aInfo);
            ensureRuntimeInitialized(cl);
        } catch (Throwable t) {
            Log.e(TAG, "instantiateClassLoader preload/init failed; continuing with plain delegation", t);
        }
        try {
            return getDelegate(cl).instantiateClassLoader(cl, aInfo);
        } catch (Throwable t) {
            Log.e(TAG, "Delegate instantiateClassLoader failed; returning original cl", t);
            return cl;
        }
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            ensureRuntimeInitialized(cl);
        } catch (Throwable ignored) {
        }
        try {
            String origAppClass = CloneRuntimeEngine.getOriginalApplicationClassName();
            // If the original manifest had no Application subclass, keep whatever the
            // framework pass'ed in (normally CFBootstrapApplication itself). Only fall back
            // to plain android.app.Application when we injected a bootstrap but lost the name.
            String targetClass = (origAppClass != null && !origAppClass.isEmpty()) ? origAppClass : className;
            return getDelegate(cl).instantiateApplication(cl, targetClass);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            Log.e(TAG, "Delegate instantiateApplication failed; falling back to platform Application", t);
            return super.instantiateApplication(cl, className);
        }
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            ensureRuntimeInitialized(cl);
        } catch (Throwable ignored) {
        }
        try {
            return getDelegate(cl).instantiateActivity(cl, className, intent);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            Log.e(TAG, "Delegate instantiateActivity failed; falling back to platform default", t);
            return super.instantiateActivity(cl, className, intent);
        }
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            ensureRuntimeInitialized(cl);
        } catch (Throwable ignored) {
        }
        try {
            return getDelegate(cl).instantiateProvider(cl, className);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            Log.e(TAG, "Delegate instantiateProvider failed; falling back to platform default", t);
            return super.instantiateProvider(cl, className);
        }
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            ensureRuntimeInitialized(cl);
        } catch (Throwable ignored) {
        }
        try {
            return getDelegate(cl).instantiateReceiver(cl, className, intent);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            Log.e(TAG, "Delegate instantiateReceiver failed; falling back to platform default", t);
            return super.instantiateReceiver(cl, className, intent);
        }
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            ensureRuntimeInitialized(cl);
        } catch (Throwable ignored) {
        }
        try {
            return getDelegate(cl).instantiateService(cl, className, intent);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            Log.e(TAG, "Delegate instantiateService failed; falling back to platform default", t);
            return super.instantiateService(cl, className, intent);
        }
    }
}
