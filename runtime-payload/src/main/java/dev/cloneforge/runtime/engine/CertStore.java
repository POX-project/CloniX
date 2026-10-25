package dev.cloneforge.runtime.engine;

import android.content.pm.Signature;

import java.security.MessageDigest;

/**
 * Bridges the raw X.509 DER of the ORIGINAL app (assets/cloneforge/origin_cert.der) into the
 * framework {@link Signature} used to spoof PackageManager results.
 *
 * NOTE: {@code new Signature(byte[])} is public SDK API — no BouncyCastle is required at
 * runtime. No androidx, no Kotlin.
 */
public final class CertStore {

    private CertStore() {
    }

    /** Creates a Signature from raw X.509 DER bytes; returns null on invalid input. */
    public static Signature signatureFromDer(byte[] der) {
        if (der == null || der.length == 0) {
            return null;
        }
        try {
            return new Signature(der);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lowercase hex SHA-256 of the DER blob (for debugging / builder parity checks). */
    public static String sha256Hex(byte[] der) {
        if (der == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(der);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            final String HEX = "0123456789abcdef";
            for (byte b : digest) {
                int v = b & 0xFF;
                sb.append(HEX.charAt(v >>> 4)).append(HEX.charAt(v & 0x0F));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
