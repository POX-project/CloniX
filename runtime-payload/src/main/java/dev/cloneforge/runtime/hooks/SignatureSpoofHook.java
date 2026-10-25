package dev.cloneforge.runtime.hooks;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.ArraySet;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Subsystem 1 — APK Signature & Integrity Spoofing (Spec §5.1).
 *
 * Intercepts ActivityThread.sPackageManager (IPackageManager) with a Java dynamic Proxy and:
 *  1. Redirects getPackageInfo queries for the ORIGINAL package to the CLONE package (so
 *     integrity checks still succeed even after the original is uninstalled), restoring the
 *     requested package name inside the returned PackageInfo.
 *  2. Patches PackageInfo.signatures AND PackageInfo.signingInfo with the ORIGINAL app's
 *     certificate (from assets/cloneforge/origin_cert.der).
 *  3. Forces hasSigningCertificate(...) == true for both packages.
 *  4. Spoofs installer provenance: getInstallerPackageName → com.android.vending, and
 *     getInstallSourceInfo (API 30+ variants) field writes → com.android.vending.
 *
 * Pure reflection; compiles on API 28+ (minSdk), no androidx / Kotlin / BouncyCastle.
 */
public final class SignatureSpoofHook {

    private static final String TAG = "CloneForgeRuntime";

    private final String mOriginalPkg;
    private final String mClonePkg;
    /** Null when origin_cert.der could not be read; then only routing/provenance hooks stay active. */
    private final Signature mFakeSignature;

    public SignatureSpoofHook(String originalPkg, String clonePkg, Signature fakeSignature) {
        this.mOriginalPkg = originalPkg;
        this.mClonePkg = clonePkg;
        this.mFakeSignature = fakeSignature;
    }

    /** Installs the IPackageManager proxy on ActivityThread.sPackageManager. Throws on hard failure. */
    public void install() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThread.setAccessible(true);
        Object currentActivityThreadInstance = currentActivityThread.invoke(null);
        if (currentActivityThreadInstance == null) {
            throw new IllegalStateException("ActivityThread.currentActivityThread() == null");
        }

        Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
        sPackageManagerField.setAccessible(true);
        final Object realIPackageManager = sPackageManagerField.get(currentActivityThreadInstance);
        if (realIPackageManager == null) {
            throw new IllegalStateException("ActivityThread.sPackageManager == null");
        }

        Class<?> iPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
        Object proxyIPm = Proxy.newProxyInstance(
                iPackageManagerClass.getClassLoader(),
                new Class<?>[]{iPackageManagerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        try {
                            return intercept(proxy, method, args, realIPackageManager);
                        } catch (java.lang.reflect.InvocationTargetException ite) {
                            // Propagate the framework's own exception semantics.
                            Throwable cause = ite.getCause();
                            throw (cause != null) ? cause : ite;
                        }
                    }

                    private Object intercept(Object proxy, Method method, Object[] args, Object real) throws Throwable {
                        String methodName = method.getName();

                        // 1. getPackageInfo(String packageName, long|int flags, [int userId])
                        if ("getPackageInfo".equals(methodName) && args != null && args.length >= 1
                                && args[0] instanceof String) {
                            String requestedPkg = (String) args[0];
                            boolean isTarget = mClonePkg.equals(requestedPkg)
                                    || mOriginalPkg.equals(requestedPkg);

                            if (mOriginalPkg.equals(requestedPkg)) {
                                // Redirect to the installed clone package so the query succeeds
                                // even with the original app removed from the device.
                                args[0] = mClonePkg;
                            }

                            Object result = method.invoke(real, args);
                            if (isTarget && result instanceof PackageInfo) {
                                patchPackageInfo((PackageInfo) result,
                                        (mOriginalPkg.equals(requestedPkg) ? requestedPkg : mClonePkg));
                            }
                            return result;
                        }

                        // 2. Installer provenance: spoof Play Store (most common licence gate).
                        if ("getInstallerPackageName".equals(methodName)) {
                            return "com.android.vending";
                        }

                        // 3. getInstallSourceInfo (API 30+, several shapes on 30/33+)
                        if ("getInstallSourceInfo".equals(methodName)) {
                            Object result = method.invoke(real, args);
                            return spoofInstallSourceInfo(result);
                        }

                        // 4. hasSigningCertificate(String pkg, byte[] cert, int type) and
                        //    hasSigningCertificate(int uid, byte[] cert, int type)
                        if ("hasSigningCertificate".equals(methodName) && args != null && args.length >= 2) {
                            if (args[0] instanceof String) {
                                String pkg = (String) args[0];
                                if (mClonePkg.equals(pkg) || mOriginalPkg.equals(pkg)) {
                                    return Boolean.TRUE;
                                }
                            }
                        }

                        return method.invoke(real, args);
                    }
                });

        sPackageManagerField.set(currentActivityThreadInstance, proxyIPm);
    }

    /** Rewrites signatures + signingInfo in-place on the returned PackageInfo. */
    private void patchPackageInfo(PackageInfo pkgInfo, String requestedPkgName) {
        if (pkgInfo == null) {
            return;
        }
        // Restore the id the caller actually asked for (we redirected orig→clone query upstream).
        if (mOriginalPkg.equals(requestedPkgName)) {
            pkgInfo.packageName = mOriginalPkg;
            if (pkgInfo.applicationInfo != null) {
                pkgInfo.applicationInfo.packageName = mOriginalPkg;
            }
        }

        if (mFakeSignature == null) {
            return; // origin cert unavailable; nothing to spoof
        }

        // Legacy signatures array (still honored by many licence SDKs).
        pkgInfo.signatures = new Signature[]{mFakeSignature};

        // Android 9+ signingInfo via internal SigningDetails construction.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                pkgInfo.signingInfo = buildSpoofedSigningInfo(mFakeSignature);
            } catch (Throwable t) {
                Log.w(TAG, "signingInfo spoof failed; signatures[] spoof still in place", t);
            }
        }
    }

    /**
     * Builds a SigningInfo whose backing SigningDetails carries our (original app's) certificate.
     * Framework constructor layouts vary across API 28–35; we probe three known shapes.
     */
    private SigningInfo buildSpoofedSigningInfo(Signature sig) throws Exception {
        final Signature[] sigArray = new Signature[]{sig};
        final int SIGNING_BLOCK_V3 = 3;

        Class<?> signingDetailsClass;
        try {
            signingDetailsClass = Class.forName("android.content.pm.SigningDetails");
        } catch (ClassNotFoundException e) {
            signingDetailsClass = Class.forName("android.content.pm.PackageParser$SigningDetails");
        }

        Object signingDetailsInstance = null;
        for (Constructor<?> ctor : signingDetailsClass.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            Class<?>[] paramTypes = ctor.getParameterTypes();
            if (paramTypes.length == 4
                    && paramTypes[0] == Signature[].class
                    && paramTypes[1] == int.class
                    && paramTypes[2] == ArraySet.class
                    && paramTypes[3] == Signature[].class) {
                signingDetailsInstance =
                        ctor.newInstance(sigArray, SIGNING_BLOCK_V3, new ArraySet<>(), sigArray);
                break;
            } else if (paramTypes.length == 3
                    && paramTypes[0] == Signature[].class
                    && paramTypes[1] == int.class
                    && (paramTypes[2] == android.util.ArraySet.class || paramTypes[2] == java.util.Set.class)) {
                // API 28–29 shape: (Signature[] signatures, int schemeVersion, Set<Signature> pastCerts)
                signingDetailsInstance = ctor.newInstance(sigArray, SIGNING_BLOCK_V3, null);
                break;
            } else if (paramTypes.length == 2
                    && paramTypes[0] == Signature[].class
                    && paramTypes[1] == int.class) {
                signingDetailsInstance = ctor.newInstance(sigArray, SIGNING_BLOCK_V3);
                break;
            } else if (paramTypes.length == 1 && paramTypes[0] == Signature[].class) {
                // Some builds expose (Signature[] pastSigningCertificates) via UNKNOWN static value.
                signingDetailsInstance = ctor.newInstance((Object) sigArray);
                break;
            }
        }

        if (signingDetailsInstance != null) {
            Constructor<SigningInfo> siCtor = SigningInfo.class.getDeclaredConstructor(signingDetailsClass);
            siCtor.setAccessible(true);
            return siCtor.newInstance(signingDetailsInstance);
        }
        return null;
    }

    /**
     * Spoofs InstallSourceInfo fields (mInitiatingPackageName / mInstallingPackageName /
     * mOriginatingPackageName) to com.android.vending. Field-write based — resilient to
     * API-level shape changes (unknown constructor shapes are tolerated, unknown fields skipped).
     */
    private Object spoofInstallSourceInfo(Object originalInfo) {
        if (originalInfo == null) {
            return null;
        }
        try {
            Class<?> cls = originalInfo.getClass();
            Field initiating = cls.getDeclaredField("mInitiatingPackageName");
            Field installing = cls.getDeclaredField("mInstallingPackageName");
            Field originating = cls.getDeclaredField("mOriginatingPackageName");
            initiating.setAccessible(true);
            installing.setAccessible(true);
            originating.setAccessible(true);
            initiating.set(originalInfo, "com.android.vending");
            installing.set(originalInfo, "com.android.vending");
            originating.set(originalInfo, "com.android.vending");
        } catch (Throwable t) {
            Log.w(TAG, "InstallSourceInfo spoof degraded: " + t.getMessage());
        }
        return originalInfo;
    }
}
