package dev.cloneforge.patcher

import java.util.Locale

/**
 * Shared constants for the CloneForge patcher module.
 */
object CfConstants {

    /** Engine version written into assets/cloneforge/config.json. */
    const val ENGINE_VERSION = "2.0.0"

    /** Broadcast action used for the PackageInstaller commit status receiver. */
    const val INSTALL_COMMIT_ACTION = "dev.cloneforge.ACTION_INSTALL_COMMIT"

    /** Extra keys delivered with [INSTALL_COMMIT_ACTION]. */
    const val EXTRA_MUTATED_PACKAGE = "mutated_pkg"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_STATUS = "status"
    const val EXTRA_STATUS_MESSAGE = "statusMessage"

    /** App-asset root that CI packs into the host app. */
    const val ASSET_ROOT = "cloneforge"

    /** Asset paths inside the *host* app APK (read via context.assets). */
    const val ASSET_RUNTIME_DEX = "$ASSET_ROOT/runtime-payload.dex"
    const val ASSET_NATIVE_ROOT = "$ASSET_ROOT/native"

    /** ZIP entry paths inside the produced clone APK. */
    const val APK_ASSETS_DIR = "assets/$ASSET_ROOT"
    const val APK_ASSET_CONFIG = "$APK_ASSETS_DIR/config.json"
    const val APK_ASSET_CERT = "$APK_ASSETS_DIR/origin_cert.der"
    const val APK_ASSET_ORIGIN_APK = "$APK_ASSETS_DIR/origin_apk.bin"

    /** Bootstrap classes living inside runtime-payload.dex. */
    const val BOOTSTRAP_APPLICATION = "dev.cloneforge.runtime.CFBootstrapApplication"
    const val BOOTSTRAP_FACTORY = "dev.cloneforge.runtime.CFComponentFactory"

    /** Manifest &lt;meta-data&gt; keys injected into the clone. */
    const val META_ORIG_PACKAGE = "cf.orig_package"
    const val META_CLONE_PACKAGE = "cf.clone_package"
    const val META_CLONE_SUFFIX = "cf.clone_suffix"
    const val META_ORIG_APPLICATION = "cf.orig_application"
    const val META_ORIG_FACTORY = "cf.orig_factory"

    /** Persistent BKS keystore file name. */
    const val KEYSTORE_FILE = "cloneforge_master.bks"

    /** Keystore password for the internal CloneForge master keystore. */
    const val KEYSTORE_PASSWORD = "cf_internal_keystore_pw"

    /** Default auto hue shift for clone number 1. */
    const val DEFAULT_HUE_DEG = 120f

    /** ABI preference order used when picking native libraries to inject. */
    val PREFERRED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /** Data offset alignment boundary for uncompressed .so entries (16 KB pages). */
    const val ALIGN_SO = 16384

    /** Data offset alignment boundary for uncompressed resources.arsc entries. */
    const val ALIGN_RESC = 4

    /** "cf" + zero-padded index, e.g. 1 -> "cf001". */
    fun cloneSuffix(index: Int): String =
        String.format(Locale.US, "cf%03d", index.coerceAtLeast(0))
}
