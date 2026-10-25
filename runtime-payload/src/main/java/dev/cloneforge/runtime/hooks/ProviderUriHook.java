package dev.cloneforge.runtime.hooks;

import android.net.Uri;

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
 * {@link dev.cloneforge.runtime.CloneRuntimeEngine#HAS_LSPLANT}. With the flag (verified) false,
 * the table still ships here and is consumed by PackageNameRouter's resource-identifier
 * rewrites and by any future ContentResolver bridge — nothing crashes in the meantime.
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
    // LSPlant-gated resolver hooks (dormant; see CloneRuntimeEngine.HAS_LSPLANT)
    // ---------------------------------------------------------------------------------------------
    /**
     * When LSPlant becomes available, hook these targets here (each in its own guarded block):
     * <ul>
     *   <li>android.content.ContentResolver#acquireProvider(Context, String)/acquireExistingProvider/
     *       acquireUnstableProvider — rewrite the authority argument via {@link #translateAuthority}.</li>
     *   <li>android.content.ContentResolver#registerContentObserver / notifyChange — rewrite Uri args.</li>
     *   <li>androidx.core.content.FileProvider (and android.support.v4.content.FileProvider)
     *       #getUriForFile — rewrite the authority argument (spec §8 FileProvider row).</li>
     * </ul>
     * Body intentionally empty today: the API could not be verified upstream (no Java facade),
     * and a guessed signature is a crash risk.
     */
    private static void installLsplantResolverHooks() {
        // no-op by design (LSPlant unavailable upstream; see CloneRuntimeEngine javadoc)
    }
}
