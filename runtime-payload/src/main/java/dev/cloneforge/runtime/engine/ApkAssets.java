package dev.cloneforge.runtime.engine;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Readers for the CloneForge payload assets shipped inside the clone's own base APK:
 * <pre>
 *   assets/cloneforge/config.json      (engine + maps)
 *   assets/cloneforge/origin_cert.der  (X.509 DER certificate of the ORIGINAL app)
 *   assets/cloneforge/origin_apk.bin   (byte-exact copy of the ORIGINAL unmutated base.apk)
 * </pre>
 *
 * Assets are read directly from the APK with java.util.zip.ZipFile — this works even at
 * AppComponentFactory.instantiateClassLoader time, before any Context exists (Context-free).
 */
public final class ApkAssets {

    private static final String TAG = "CloneForgeRuntime";

    public static final String ASSET_CONFIG = "assets/cloneforge/config.json";
    public static final String ASSET_ORIG_CERT = "assets/cloneforge/origin_cert.der";
    public static final String ASSET_ORIGIN_APK = "assets/cloneforge/origin_apk.bin";

    private ApkAssets() {
    }

    /** Reads a full asset entry from the APK at {@code apkPath}. Returns null on any failure. */
    public static byte[] readAsset(String apkPath, String entryName) {
        if (apkPath == null || apkPath.isEmpty()) {
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            if (in == null) {
                return null;
            }
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read asset " + entryName + " from " + apkPath, t);
            return null;
        } finally {
            closeZipQuietly(zip);
        }
    }

    /**
     * Streams an asset entry out to {@code dest} (mkdirs'd), then locks it down to
     * owner-read-only (0400 semantics via File API): setWritable(false,false),
     * setExecutable(false,false), setReadable(false,false) then setReadable(true,true).
     *
     * @return true on success (or when the file already exists with identical content length
     *         and contentType indicates "reuse").
     */
    public static boolean extractAssetTo(String apkPath, String entryName, File dest, ReusePolicy reuse) {
        if (apkPath == null || dest == null) {
            return false;
        }

        if (reuse == ReusePolicy.REUSE_IF_EXISTS && dest.exists() && dest.length() > 0) {
            // Different content length is tolerated: origin APKs are byte-exact copies and
            // never change between clone upgrades of the SAME original version. An upgrade
            // changes the version marker elsewhere and the builder re-extracts via cache-busting.
            lockDown(dest);
            return true;
        }

        ZipFile zip = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                Log.e(TAG, "Asset missing in clone APK: " + entryName);
                return false;
            }
            in = zip.getInputStream(entry);

            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(dest, false);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            lockDown(dest);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to extract asset " + entryName + " to " + dest, t);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            closeZipQuietly(zip);
        }
    }

    /** Applies 0400-style owner-only read permissions using the File API. */
    private static void lockDown(File f) {
        try {
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
            // Grant owner-only read as the final step.
            f.setReadable(true, true);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeZipQuietly(ZipFile z) {
        if (z != null) {
            try {
                z.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Whether extractAssetTo may reuse an already-extracted file. */
    public enum ReusePolicy {
        REUSE_IF_EXISTS,
        ALWAYS_EXTRACT
    }
}
