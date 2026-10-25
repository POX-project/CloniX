package dev.cloneforge.runtime.hooks;

import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Subsystem 3 — ContentProvider Authority / FileProvider URI Translator (Spec §5.2).
 *
 * The builder (ManifestAndArscMutator §3.1) renames every <provider> authority
 * {@code A → A + ".cf001"} and records the mapping in authority_map.json. This hook
 * translates URIs resolved at runtime accordingly:
 *
 *   translate(A) = authorityMap[A]                                  if A ∈ authorityMap
 *                = A + "." + cloneSuffix                            if A startsWith ORIG_PKG
 *                  && !A endsWith(cloneSuffix)
 *                = A                                                otherwise
 *
 * Java-side LSPlant hooking of ContentResolver.acquireProvider / acquireExistingProvider /
 * acquireUnstableProvider + androidx FileProvider.getUriForFile is gated on
 * {@link dev.cloneforge.runtime.CloneRuntimeEngine#HAS_LSPLANT}; when the C++ bridge init
 * succeeded (see {@link dev.cloneforge.runtime.hooks.LsplantNative}), the hooks below rewrite
 * the authority argument at the ART level before the original method runs.
 *
 * Pure Java: no androidx / Kotlin / BouncyCastle at runtime.
 */
public final class ProviderUriHook {

    private static final String TAG = "CloneForgeRuntime";

    private final String mOriginalPkg;
    private final String mCloneSuffix;
    private final Map<String, String> mAuthorityMap;

    private static volatile ProviderUriHook sInstance;

    private ProviderUriHook(String originalPkg, String cloneSuffix, Map<String, String> authorityMap) {
        this.mOriginalPkg = originalPkg;
        this.mCloneSuffix = cloneSuffix;
        this.mAuthorityMap = authorityMap == null
                ? java.util.Collections.<String, String>emptyMap()
                : authorityMap;
        sInstance = this;
    }

    /** Install entry. Translates nothing at install time; the table is consumed lazily. */
    public static void install(String originalPkg, String cloneSuffix, Map<String, String> authorityMap) {
        new ProviderUriHook(originalPkg, cloneSuffix, authorityMap);
        // LSPlant path (dormant): hook android.app.ContentResolver methods + FileProvider here.
        if (dev.cloneforge.runtime.CloneRuntimeEngine.HAS_LSPLANT) {
            installLsplantResolverHooks();
        } else {
            android.util.Log.i(TAG, "ProviderUriHook: translation table active; "
                    + "LSPlant resolver hooks unavailable (hasLsplant=false)");
        }
    }

    /** Returns the active hook instance, or null before install(). */
    public static ProviderUriHook getInstance() {
        return sInstance;
    }

    // ---------------------------------------------------------------------------------------------
    // Translation rules (Spec §5.2)
    // ---------------------------------------------------------------------------------------------

    /** Translates a single authority; null-safe and idempotent. */
    public String translateAuthority(String authority) {
        if (authority == null || authority.isEmpty()) {
            return authority;
        }
        if (mCloneSuffix != null && !mCloneSuffix.isEmpty()
                && authority.endsWith("." + mCloneSuffix)) {
            return authority;
        }

        String mapped = mAuthorityMap.get(authority);
        if (mapped != null) {
            return mapped;
        }
        if (mOriginalPkg != null && !mOriginalPkg.isEmpty()
                && authority.startsWith(mOriginalPkg)) {
            return authority + "." + (mCloneSuffix != null ? mCloneSuffix : "");
        }
        return authority;
    }

    /** Translates the authority of a content Uri; non-content Uris pass through untouched. */
    public Uri translateUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme != null && !"content".equals(scheme)) {
            return uri; // file://, android.resource://, etc.
        }
        String auth = uri.getAuthority();
        if (auth == null) {
            return uri;
        }

        String newAuth = translateAuthority(auth);
        if (newAuth.equals(auth)) {
            return uri;
        }
        return uri.buildUpon().authority(newAuth).build();
    }

    /** Reverse translation (clone authority → original authority) for parity/debug tooling. */
    public String reverseAuthority(String authority) {
        for (Map.Entry<String, String> e : mAuthorityMap.entrySet()) {
            if (e.getValue().equals(authority)) {
                return e.getKey();
            }
        }
        return authority;
    }

    // ---------------------------------------------------------------------------------------------
    // LSPlant-gated resolver hooks
    // ---------------------------------------------------------------------------------------------
    /**
     * Hooked targets when the bridge is live (each target individually guarded):
     * <ul>
     *   <li>android.content.ContentResolver#acquireProvider / acquireExistingProvider /
     *       acquireUnstableProvider (1- and 2-arg overloads; authority = LAST parameter) —
     *       rewrite via {@link #translateAuthority}.</li>
     *   <li>androidx.core.content.FileProvider and android.support.v4.content.FileProvider
     *       #getUriForFile (authority = the first String parameter, i.e. always index 1) —
     *       rewrite via {@link #translateAuthority}.</li>
     * </ul>
     * A single shared handler instance is fine because handlers are stateless; rewrites happen
     * on a cloned args array so the caller's array is never mutated.
     */
    private static void installLsplantResolverHooks() {
        final ProviderUriHook inst = getInstance();
        if (inst == null) {
            return;
        }
        int hooked = 0;

        // ContentResolver acquire*Provider family — authority argument (last param) rewrite.
        try {
            Class<?> cr = Class.forName("android.content.ContentResolver");
            for (Method m : cr.getDeclaredMethods()) {
                String name = m.getName();
                if (!"acquireProvider".equals(name) && !"acquireExistingProvider".equals(name)
                        && !"acquireUnstableProvider".equals(name)) {
                    continue;
                }
                int pc = m.getParameterCount();
                Class<?>[] pts = m.getParameterTypes();
                // Overloads: (Context, String) / (String) — the String authority is last.
                if ((pc != 1 && pc != 2) || pts[pc - 1] != String.class) {
                    continue;
                }
                final int authorityIndex = pc - 1;
                HookerHandler rewrite = (hooker, args) -> {
                    Object authority = args[authorityIndex];
                    if (authority instanceof String) {
                        String translated = inst.translateAuthority((String) authority);
                        if (translated != null && !translated.equals(authority)) {
                            Object[] rewritten = args.clone();
                            rewritten[authorityIndex] = translated;
                            return hooker.invokeBackup(rewritten);
                        }
                    }
                    return hooker.invokeBackup(args);
                };
                if (Hooker.hook(m, rewrite) != null) {
                    hooked++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ProviderUriHook: ContentResolver acquire-hooks failed (degraded)", t);
        }

        // FileProvider.getUriForFile — androidx + legacy support library variants.
        final String[] fileProviderClasses = {
                "androidx.core.content.FileProvider",
                "android.support.v4.content.FileProvider",
        };
        for (String fpClass : fileProviderClasses) {
            try {
                Class<?> fp = Class.forName(fpClass);
                for (Method m : fp.getDeclaredMethods()) {
                    if (!"getUriForFile".equals(m.getName())) {
                        continue;
                    }
                    final int pc = m.getParameterCount();
                    final Class<?>[] pts = m.getParameterTypes();
                    int authorityIndex = -1;
                    for (int i = 0; i < pc; i++) {
                        if (pts[i] == String.class) {
                            authorityIndex = i; // first String param = authority
                            break;
                        }
                    }
                    if (authorityIndex < 0) {
                        continue;
                    }
                    final int authIdx = authorityIndex;
                    HookerHandler rewrite = (hooker, args) -> {
                        Object authority = args[authIdx];
                        if (authority instanceof String) {
                            String translated = inst.translateAuthority((String) authority);
                            if (translated != null && !translated.equals(authority)) {
                                Object[] rewritten = args.clone();
                                rewritten[authIdx] = translated;
                                return hooker.invokeBackup(rewritten);
                            }
                        }
                        return hooker.invokeBackup(args);
                    };
                    if (Hooker.hook(m, rewrite) != null) {
                        hooked++;
                    }
                }
            } catch (ClassNotFoundException absent) {
                // support-v4 / androidx may not be on the classpath; fine.
                Log.i(TAG, "ProviderUriHook: " + fpClass + " not present (skipped)");
            } catch (Throwable t) {
                Log.w(TAG, "ProviderUriHook: getUriForFile hook failed for " + fpClass, t);
            }
        }

        Log.i(TAG, "ProviderUriHook: " + hooked + " resolver LSPlant hook(s) installed");
    }
}
