package dev.cloneforge.runtime;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Android Hidden API restriction bypass (Android 9–15+).
 *
 * Exempts the calling process from non-SDK interface restrictions so reflection and JNI lookups
 * on private platform classes (e.g. DexFile.<init>, ActivityManager.IActivityManagerSingleton)
 * succeed without throwing NoSuchMethodError or IllegalAccessException.
 */
public final class HiddenApiBypass {

    private static final String TAG = "CloneForgeRuntime";
    private static volatile boolean sExemptionsApplied = false;

    private HiddenApiBypass() {
    }

    /**
     * Exempts all APIs starting with given prefixes (default "L" for all classes).
     * Safe to call multiple times; idempotent. Never throws.
     */
    @SuppressLint("PrivateApi")
    public static boolean addHiddenApiExemptions(String... signaturePrefixes) {
        if (sExemptionsApplied) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            sExemptionsApplied = true;
            return true;
        }
        if (signaturePrefixes == null || signaturePrefixes.length == 0) {
            signaturePrefixes = new String[]{"L"};
        }

        // Strategy 1: Double-reflection (bypasses caller-domain checks on Class.getDeclaredMethod)
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", new Class<?>[0]);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class<?>[]{String[].class});

            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{signaturePrefixes});
            sExemptionsApplied = true;
            Log.i(TAG, "HiddenApiBypass: exemptions applied via double reflection");
            return true;
        } catch (Throwable t1) {
            Log.w(TAG, "HiddenApiBypass: double-reflection attempt failed, trying Unsafe fallback: " + t1.getMessage());
        }

        // Strategy 2: sun.misc.Unsafe override
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);

            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);

            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setHiddenApiExemptions.setAccessible(true);
            setHiddenApiExemptions.invoke(vmRuntime, (Object) signaturePrefixes);
            sExemptionsApplied = true;
            Log.i(TAG, "HiddenApiBypass: exemptions applied via Unsafe");
            return true;
        } catch (Throwable t2) {
            Log.e(TAG, "HiddenApiBypass: failed to apply hidden API exemptions", t2);
            return false;
        }
    }
}
