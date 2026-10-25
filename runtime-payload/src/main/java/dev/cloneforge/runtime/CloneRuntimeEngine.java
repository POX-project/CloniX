package dev.cloneforge.runtime;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.cloneforge.runtime.engine.ApkAssets;
import dev.cloneforge.runtime.engine.CertStore;
import dev.cloneforge.runtime.engine.CfConfig;
import dev.cloneforge.runtime.hooks.DeviceIdentityHook;
import dev.cloneforge.runtime.hooks.IntentRouterHook;
import dev.cloneforge.runtime.hooks.PackageNameRouter;
import dev.cloneforge.runtime.hooks.ProviderUriHook;
import dev.cloneforge.runtime.hooks.SignatureSpoofHook;

/**
 * The CloneForge in-process runtime engine ("the brain").
 *
 * Init sequence (called from {@link CFComponentFactory#instantiateClassLoader} or, as a
 * fallback for non-factory launches, from {@link CFBootstrapApplication}):
 * <ol>
 *   <li>Parse manifest meta-data + assets/cloneforge/config.json (Context-free, straight
 *       from the clone's own APK via ZipFile).</li>
 *   <li>Extract the byte-exact ORIGINAL base APK (assets/cloneforge/origin_apk.bin) to
 *       {@code files/.cf_origin_base.apk} with 0400-style permissions.</li>
 *   <li>Isolate multi-process WebView data ({@code WebView.setDataDirectorySuffix}).</li>
 *   <li>Load libcloneforge_core.so and call {@link #nativeInitHooks} (ShadowHook
 *       openat/faccessat/fstatat64 redirection + /proc/self/maps filtering).</li>
 *   <li>Install Java hooks: SignatureSpoofHook + IntentRouterHook (pure reflection, always),
 *       then PackageNameRouter / ProviderUriHook / DeviceIdentityHook (LSPlant-gated,
 *       translation-table + persisted-identity fallback).</li>
 * </ol>
 *
 * Every hook installation is wrapped in its own try/catch: the engine NEVER throws an
 * uncaught exception and the clone never dies because of us.
 */
public final class CloneRuntimeEngine {

    public static final String TAG = "CloneForgeRuntime";

    // ---------------------------------------------------------------------------------------------
    // LSPlant availability
    // ---------------------------------------------------------------------------------------------
    /**
     * LSPlant Java-side integration flag.
     *
     * VERIFIED 2026-10-25 against upstream https://github.com/LSPosed/LSPlant (master):
     * the repository ships a C++-only API (lsplant/src/main/jni/include/lsplant.hpp,
     * lsplant::Init / lsplant::Hook driven from your own native code). There is NO upstream
     * org.lsposed.lsplant.LSPlant Java wrapper class to mirror (its Java test classes
     * Hooker.java / Replacement.java are merely hooker objects passed INTO the C++ Hook()).
     *
     * Therefore, per the integration specification: hasLsplant = false and all LSPlant-gated
     * hooks degrade to their documented reflection/table fallbacks. If a future integrator
     * adds an LSPlant C++ bridge (e.g. inside native-bridge), flip this flag and wire
     * PackageNameRouter/ProviderUriHook/DeviceIdentityHook accordingly.
     */
    public static final boolean HAS_LSPLANT = false;

    private static final String ASSET_DIR_SUFFIX = "/files";
    private static final String ORIGIN_APK_FILENAME = ".cf_origin_base.apk";

    // ---------------------------------------------------------------------------------------------
    // Config state (singletons; process-wide, immutable after loading)
    // ---------------------------------------------------------------------------------------------
    private static final AtomicBoolean sConfigLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean sNativeInitialized = new AtomicBoolean(false);

    private static volatile CfConfig sConfig = new CfConfig();
    private static volatile String sOrigApplicationClass = null;
    private static volatile String sOrigFactoryClass = null;
    private static volatile String sApkPath = null;     // clone's installed base.apk path
    private static volatile String sFilesDir = null;    // /data/user/0/<clone_pkg>/files
    private static volatile String sOriginApkPath = null; // extracted clean original APK
    private static volatile byte[] sOriginCertDer = null;

    // ---------------------------------------------------------------------------------------------
    // Native bridge. System.loadLibrary("cloneforge_core") happens in ensureNativeLoaded().
    // ---------------------------------------------------------------------------------------------
    private static native void nativeInitHooks(String origPkg, String clonePkg,
                                               String cleanOriginApkPath, String installedBaseApkPath);

    private CloneRuntimeEngine() {
    }

    // ---------------------------------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------------------------------

    /**
     * Stage 1: parse meta-data + assets from the clone's ApplicationInfo. Context-free, safe
     * to call from AppComponentFactory.instantiateClassLoader. Idempotent.
     */
    public static void preloadFromApplicationInfo(ApplicationInfo aInfo) {
        if (aInfo == null || !sConfigLoaded.compareAndSet(false, true)) {
            return;
        }
        try {
            readMetaData(aInfo);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read manifest meta-data", t);
        }
        try {
            sApkPath = (aInfo.sourceDir != null && !aInfo.sourceDir.isEmpty()) ? aInfo.sourceDir : null;
        } catch (Throwable t) {
            Log.e(TAG, "ApplicationInfo.sourceDir unavailable", t);
        }
        try {
            resolveFilesDir(aInfo);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to resolve files dir", t);
        }
        try {
            loadConfigFromAssets();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load assets/cloneforge/config.json", t);
        }
        try {
            sOriginCertDer = ApkAssets.readAsset(sApkPath, ApkAssets.ASSET_ORIG_CERT);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read origin_cert.der", t);
        }
        if (sConfig.clonePkg != null && !sConfig.clonePkg.isEmpty()
                && sConfig.origPkg != null && !sConfig.origPkg.isEmpty()) {
            Log.i(TAG, "CloneForge config loaded: " + sConfig.origPkg + " -> " + sConfig.clonePkg
                    + " (suffix=" + sConfig.cloneSuffix + ", engine=" + sConfig.engineVersion + ")");
        }
    }

    /**
     * Stage 2: full runtime initialization. Idempotent (guard CAS), never throws.
     * Called once per process at the earliest lifecycle moment.
     */
    public static void ensureRuntimeInitialized(ClassLoader cl) {
        if (!sConfigLoaded.get()) {
            // Without config we cannot meaningfully hook; Log a hint for integrators.
            Log.w(TAG, "ensureRuntimeInitialized called before preloadFromApplicationInfo");
            return;
        }
        if (!sNativeInitialized.compareAndSet(false, true)) {
            return; // already initialized
        }

        installHooks(cl);
        Log.i(TAG, "CloneForge runtime engine ready (hasLsplant=" + HAS_LSPLANT + ")");
    }

    /**
     * Spec-compat alias used by the reference §4.2 implementation.
     */
    public static void installEarlyBinderHooks(ClassLoader cl) {
        ensureRuntimeInitialized(cl);
    }

    // ---------------------------------------------------------------------------------------------
    // Accessors used across the payload
    // ---------------------------------------------------------------------------------------------
    public static String getOriginalApplicationClassName() {
        return sOrigApplicationClass;
    }

    public static String getOriginalFactoryClassName() {
        return sOrigFactoryClass;
    }

    public static String getOriginalPackage() {
        return sConfig.origPkg;
    }

    public static String getClonePackage() {
        return sConfig.clonePkg;
    }

    public static String getCloneSuffix() {
        return sConfig.cloneSuffix;
    }

    public static String getEngineVersion() {
        return sConfig.engineVersion;
    }

    public static Map<String, String> getAuthorityMap() {
        return Collections.unmodifiableMap(sConfig.authorityMap);
    }

    public static Map<String, String> getPermissionMap() {
        return Collections.unmodifiableMap(sConfig.permissionMap);
    }

    public static String getApkPath() {
        return sApkPath;
    }

    public static String getFilesDir() {
        return sFilesDir;
    }

    public static byte[] getOriginCertDer() {
        return sOriginCertDer;
    }

    public static boolean isReady() {
        return sNativeInitialized.get();
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /** Reads cf.* manifest meta-data keys from the clone ApplicationInfo bundle. */
    private static void readMetaData(ApplicationInfo aInfo) {
        Bundle md = aInfo.metaData;
        if (md == null) {
            // Notes: some launch paths deliver ApplicationInfo without the GET_META_DATA bundle;
            // config.json still carries the authoritative data below.
            Log.w(TAG, "ApplicationInfo.metaData is null; falling back to config.json only");
            return;
        }
        sOrigApplicationClass = md.getString("cf.orig_application");
        sOrigFactoryClass = md.getString("cf.orig_factory");
        String mdOrigPkg = md.getString("cf.orig_package");
        String mdClonePkg = md.getString("cf.clone_package");
        String mdCloneSuffix = md.getString("cf.clone_suffix");
        // Meta-data wins only when config.json has not filled them yet (it is more current).
        CfConfig cfg = sConfig;
        if (cfg.origPkg == null || cfg.origPkg.isEmpty()) {
            cfg.origPkg = mdOrigPkg != null ? mdOrigPkg : "";
        }
        if (cfg.clonePkg == null || cfg.clonePkg.isEmpty()) {
            cfg.clonePkg = mdClonePkg != null ? mdClonePkg : "";
        }
        if (cfg.cloneSuffix == null || cfg.cloneSuffix.isEmpty()) {
            cfg.cloneSuffix = mdCloneSuffix != null ? mdCloneSuffix : "";
        }
        sConfig = cfg;
    }

    /** Resolves the private files dir from aInfo.dataDir with a /data/user/0/<clone>/files fallback. */
    private static void resolveFilesDir(ApplicationInfo aInfo) {
        if (sFilesDir != null && !sFilesDir.isEmpty()) {
            return;
        }
        String dataDir = aInfo.dataDir;
        String base;
        if (dataDir != null && !dataDir.isEmpty()) {
            base = dataDir.endsWith(ASSET_DIR_SUFFIX) ? dataDir : dataDir + ASSET_DIR_SUFFIX;
        } else if (sConfig.clonePkg != null && !sConfig.clonePkg.isEmpty()) {
            // Conservative fallback for cold factory paths (primary user only).
            base = "/data/user/0/" + sConfig.clonePkg + ASSET_DIR_SUFFIX;
        } else {
            base = null;
        }
        if (base != null) {
            File dir = new File(base);
            try {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
            } catch (Throwable ignored) {
            }
            sFilesDir = base;
        }
    }

    /** Parses assets/cloneforge/config.json straight out of the clone APK. */
    private static void loadConfigFromAssets() {
        byte[] json = ApkAssets.readAsset(sApkPath, ApkAssets.ASSET_CONFIG);
        if (json == null || json.length == 0) {
            Log.w(TAG, "config.json missing from clone APK; relying on meta-data only");
            return;
        }
        CfConfig parsed = CfConfig.parse(new java.io.ByteArrayInputStream(json));
        if (parsed.origPkg != null && !parsed.origPkg.isEmpty()) {
            CfConfig merged = new CfConfig();
            merged.engineVersion = parsed.engineVersion;
            merged.origPkg = parsed.origPkg;
            merged.clonePkg = parsed.clonePkg;
            merged.cloneSuffix = parsed.cloneSuffix;
            merged.authorityMap = new HashMap<>(parsed.authorityMap);
            merged.permissionMap = new HashMap<>(parsed.permissionMap);
            // meta-data may have arrived first and is authoritative for the class names only.
            sConfig = merged;
        }
    }

    private static void installHooks(ClassLoader cl) {
        // 1. WebView multi-process data isolation (throws if two different suffixes were set
        //    by conflicting init paths — silently tolerated per spec §8 edge-case table).
        try {
            String suffix = "cf_" + (sConfig.cloneSuffix != null ? sConfig.cloneSuffix : "");
            WebView.setDataDirectorySuffix(suffix);
        } catch (IllegalStateException dup) {
            Log.i(TAG, "WebView data suffix already set; ignoring duplicate call");
        } catch (Throwable t) {
            Log.w(TAG, "WebView.setDataDirectorySuffix failed", t);
        }

        // 2. Extract the clean ORIGINAL base APK (byte-exact, 0400 owner-read) for native
        //    open()/fstatat64() redirection (spec §6.2 rule 1).
        sOriginApkPath = null;
        if (sFilesDir != null && sApkPath != null) {
            File dest = new File(sFilesDir, ORIGIN_APK_FILENAME);
            boolean ok = ApkAssets.extractAssetTo(sApkPath, ApkAssets.ASSET_ORIGIN_APK, dest,
                    ApkAssets.ReusePolicy.REUSE_IF_EXISTS);
            sOriginApkPath = ok ? dest.getAbsolutePath() : null;
        } else {
            Log.w(TAG, "filesDir/apkPath unavailable; native origin-APK redirection disabled "
                    + "(filesDir=" + sFilesDir + ", apkPath=" + sApkPath + ")");
        }

        // 3. Native bridge: ShadowHook openat/faccessat/fstatat64 + /proc/self/maps filtering.
        ensureNativeLoaded();

        // 4. Signature & installer provenance spoofing (pure reflection dynamic proxy — always).
        try {
            new SignatureSpoofHook(sConfig.origPkg, sConfig.clonePkg,
                    CertStore.signatureFromDer(sOriginCertDer)).install();
            Log.i(TAG, "SignatureSpoofHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "SignatureSpoofHook install failed (degraded)", t);
        }

        // 5. Intent/Binder routing (pure reflection proxies on IActivityManager /
        //    IActivityTaskManager — always; covers hard-coded ComponentName/setPackage rewrites
        //    AND the API 34+ RECEIVER_EXPORTED enforcement, spec §8).
        try {
            IntentRouterHook.install(sConfig.origPkg, sConfig.clonePkg);
            Log.i(TAG, "IntentRouterHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "IntentRouterHook install failed (degraded)", t);
        }

        // 6. Stack-aware getPackageName() router via LSPlant — currently DISABLED
        //    (HAS_LSPLANT=false; see class javadoc).
        try {
            PackageNameRouter.install(sConfig.origPkg, sConfig.clonePkg);
        } catch (Throwable t) {
            Log.w(TAG, "PackageNameRouter install failed (degraded)", t);
        }

        // 7. ContentProvider/FileProvider authority translation table (always active) + LSPlant
        //    ContentResolver hooks (gated internally).
        try {
            ProviderUriHook.install(sConfig.origPkg, sConfig.cloneSuffix, sConfig.authorityMap);
            Log.i(TAG, "ProviderUriHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "ProviderUriHook install failed (degraded)", t);
        }

        // 8. Per-clone synthetic device identity (persisted profile + cheap reflection hooks).
        try {
            DeviceIdentityHook.init(sFilesDir, sConfig.clonePkg, sConfig.origPkg);
            Log.i(TAG, "DeviceIdentityHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "DeviceIdentityHook install failed (degraded)", t);
        }
    }

    /** Loads libcloneforge_core.so and invokes the native init, sweeping all failures. */
    private static void ensureNativeLoaded() {
        try {
            System.loadLibrary("cloneforge_core");
            String orig = sConfig.origPkg != null ? sConfig.origPkg : "";
            String clone = sConfig.clonePkg != null ? sConfig.clonePkg : "";
            String originApk = sOriginApkPath != null ? sOriginApkPath : "";
            String installedApk = sApkPath != null ? sApkPath : "";
            nativeInitHooks(orig, clone, originApk, installedApk);
            Log.i(TAG, "nativeInitHooks completed (origin=" + originApk + ")");
        } catch (UnsatisfiedLinkError nle) {
            // Payload may be detached from native-bridge (unit tests / future A/B).
            Log.e(TAG, "libcloneforge_core.so not found — native I/O redirection DISABLED", nle);
        } catch (Throwable t) {
            Log.e(TAG, "nativeInitHooks failed — native I/O redirection may be off", t);
        }
    }
}
