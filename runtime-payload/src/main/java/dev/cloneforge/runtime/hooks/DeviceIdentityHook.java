package dev.cloneforge.runtime.hooks;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Subsystem 5 — Per-Clone Hardware & Telemetry Identity Isolation (Spec §5.4).
 *
 * On first launch a VirtualDeviceProfile is generated for THIS clone (keyed off clone_pkg via
 * HMAC-SHA256 over a per-clone SecureRandom device seed) and persisted to
 * {@code files/.cf_identity.json} with 0600-style permissions. Values then survive process
 * restarts, guaranteeing that two clones of the same app cannot correlate the same device:
 *
 * <pre>
 *   android_id     : 16 lowercase hex chars
 *   widevine_id    : Base64 of 32 deterministic pseudo-random bytes
 *   advertising_id : random v4 UUID string (per clone)
 *   imei           : Luhn-valid 15-digit, "35" TAC prefix
 *   iccid          : 19 digits, "89" telecom prefix
 *   mac            : 02:XX:XX:XX:XX:XX (locally administered unicast)
 *   bt_name        : "CF-" + 6 hex chars nickname
 * </pre>
 *
 * ART-level spoofing of Settings$NameValueCache / TelephonyManager / MediaDrm /
 * NetworkInterface / WifiInfo requires method hooks (LSPlant). With
 * {@link dev.cloneforge.runtime.CloneRuntimeEngine#HAS_LSPLANT} == false, only the persisted
 * profile ships (exposed via the static getters so engine/overlay code can consume it);
 * hook installation stubs below document the exact targets for when the LSPlant C++ bridge
 * lands in native-bridge.
 */
public final class DeviceIdentityHook {

    private static final String TAG = "CloneForgeRuntime";
    private static final String PROFILE_FILE = ".cf_identity.json";

    private static volatile VirtualDeviceProfile sProfile;
    private static volatile boolean sInitialized = false;

    private DeviceIdentityHook() {
    }

    // ---------------------------------------------------------------------------------------------
    // Init / persistence
    // ---------------------------------------------------------------------------------------------

    /** Bootstraps (or loads) the per-clone identity profile. Never throws. */
    public static void init(String filesDir, String clonePkg, String origPkg) {
        if (sInitialized) {
            return;
        }
        sInitialized = true;
        try {
            File f = new File(filesDir, PROFILE_FILE);
            VirtualDeviceProfile loaded = readProfile(f);
            if (loaded == null) {
                loaded = generateProfile(clonePkg);
                writeProfile(f, loaded);
                Log.i(TAG, "VirtualDeviceProfile generated for " + clonePkg + " -> " + f.getAbsolutePath());
            } else {
                Log.i(TAG, "VirtualDeviceProfile loaded for " + clonePkg);
            }
            sProfile = loaded;
        } catch (Throwable t) {
            Log.e(TAG, "DeviceIdentityHook init failed; identity spoofing inactive", t);
        }

        installLsplantHooks();
    }

    // ---------------------------------------------------------------------------------------------
    // Public accessors (usable by engine/overlay consumers even without LSPlant)
    // ---------------------------------------------------------------------------------------------
    public static String getAndroidId() {
        VirtualDeviceProfile p = sProfile;
        return p == null ? null : p.androidId;
    }

    public static byte[] getWidevineId() {
        VirtualDeviceProfile p = sProfile;
        if (p == null || p.widevineIdB64 == null) {
            return null;
        }
        try {
            return Base64.decode(p.widevineIdB64, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String getAdvertisingId() {
        VirtualDeviceProfile p = sProfile;
        return p == null ? null : p.advertisingId;
    }

    public static String getImei() {
        VirtualDeviceProfile p = sProfile;
        return p == null ? null : p.imei;
    }

    public static String getIccid() {
        VirtualDeviceProfile p = sProfile;
        return p == null ? null : p.iccid;
    }

    public static String getWifiMac() {
        VirtualDeviceProfile p = sProfile;
        return p == null ? null : p.macAddress;
    }

    public static String getBluetoothName() {
        VirtualDeviceProfile p = sProfile;
        return p == null ? null : p.bluetoothName;
    }

    // ---------------------------------------------------------------------------------------------
    // Profile generation (SecureRandom seed + HMAC-SHA256 seeded by clone_pkg)
    // ---------------------------------------------------------------------------------------------
    private static VirtualDeviceProfile generateProfile(String clonePkg) {
        VirtualDeviceProfile p = new VirtualDeviceProfile();
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        String seedB64 = Base64.encodeToString(seed, Base64.NO_WRAP);
        String pkg = clonePkg == null ? "" : clonePkg;

        byte[] aid = hmac(seed, pkg + ":android_id");
        p.androidId = toHex(aid, /*maxLength=*/ 16);

        p.widevineIdB64 = Base64.encodeToString(
                hmacChain(seed, pkg, "widevine", /*bytes=*/ 32), Base64.NO_WRAP);

        p.advertisingId = uuidFrom(hmac(seed, pkg + ":advertising_id")).toString();

        p.imei = buildImei(hmac(seed, pkg + ":imei"));
        p.iccid = buildIccid(hmac(seed, pkg + ":iccid"));
        p.macAddress = buildMac(hmac(seed, pkg + ":mac"));
        p.bluetoothName = "CF-" + toHex(hmac(seed, pkg + ":bt_name"), 6).toUpperCase();

        p.deviceSeedB64 = seedB64;
        return p;
    }

    private static final String HEX = "0123456789abcdef";

    /** HMAC-SHA256 keyed by the per-clone SecureRandom seed; the clone package binds the data side. */
    private static byte[] hmac(byte[] seed, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            byte[] fallback = new byte[32];
            new SecureRandom().nextBytes(fallback);
            return fallback;
        }
    }

    /** Concatenated HMAC blocks to reach n deterministic bytes. */
    private static byte[] hmacChain(byte[] seed, String clonePkg, String purpose, int n) {
        byte[] out = new byte[n];
        int filled = 0;
        int block = 0;
        while (filled < n) {
            byte[] chunk = hmac(seed, clonePkg + ":" + purpose + ":" + block++);
            int len = Math.min(chunk.length, n - filled);
            System.arraycopy(chunk, 0, out, filled, len);
            filled += len;
        }
        return out;
    }

    /** First {@code maxDigits} hex characters of the input bytes. */
    private static String toHex(byte[] bytes, int maxHexChars) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(HEX.charAt(v >>> 4)).append(HEX.charAt(v & 0x0F));
            if (sb.length() >= maxHexChars) {
                break;
            }
        }
        return sb.substring(0, Math.min(sb.length(), maxHexChars));
    }

    /** UUID name-style from 16 bytes (version 4, IETF variant). */
    private static UUID uuidFrom(byte[] bytes) {
        if (bytes.length < 16) {
            byte[] grown = new byte[16];
            System.arraycopy(bytes, 0, grown, 0, bytes.length);
            bytes = grown;
        }
        byte[] bb = new byte[16];
        System.arraycopy(bytes, 0, bb, 0, 16);
        bb[6] = (byte) ((bb[6] & 0x0F) | 0x40);
        bb[8] = (byte) ((bb[8] & 0x3F) | 0x80);
        long msb = 0L;
        long lsb = 0L;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bb[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bb[i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    /** 15-digit "35"-prefixed, Luhn-valid IMEI. */
    private static String buildImei(byte[] stream) {
        StringBuilder sb = new StringBuilder("35");
        int idx = 0;
        while (sb.length() < 14) {
            int d = (stream[idx++ % stream.length] & 0xFF) % 10;
            sb.append(d);
        }
        // 2 + 12 = 14 digits so far; append Luhn check digit.
        sb.append(luhnCheckDigit(sb.toString()));
        return sb.toString();
    }

    /** 19-digit "89"-prefixed ICCID (layered determinism, no checksum enforcement). */
    private static String buildIccid(byte[] stream) {
        StringBuilder sb = new StringBuilder("89");
        int idx = 0;
        while (sb.length() < 19) {
            int d = (stream[idx++ % stream.length] & 0xFF) % 10;
            sb.append(d);
        }
        return sb.toString();
    }

    /** 02:XX:XX:XX:XX:XX local-admin MAC. */
    private static String buildMac(byte[] stream) {
        StringBuilder sb = new StringBuilder("02");
        for (int i = 0; i < 5; i++) {
            int v = stream[i + 1] & 0xFF;
            sb.append(':');
            sb.append(HEX.charAt(v >>> 4)).append(HEX.charAt(v & 0x0F));
        }
        return sb.toString();
    }

    /** Luhn check digit for a numeric string whose rightmost digit would be doubled. */
    private static int luhnCheckDigit(String base) {
        int sum = 0;
        boolean doubleIt = true; // doubling starts at base's rightmost digit
        for (int i = base.length() - 1; i >= 0; i--) {
            int d = base.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - (sum % 10)) % 10;
    }

    // ---------------------------------------------------------------------------------------------
    // (De)serialization — hand-rolled JSON, org.json-free for payload-dex binaries parity
    // ---------------------------------------------------------------------------------------------
    private static final class VirtualDeviceProfile {
        String androidId;
        String widevineIdB64;
        String advertisingId;
        String imei;
        String iccid;
        String macAddress;
        String bluetoothName;
        String deviceSeedB64;
    }

    private static String toJson(VirtualDeviceProfile p) {
        return new StringBuilder()
                .append("{\"android_id\":\"").append(quote(p.androidId)).append('"')
                .append(",\"widevine_id\":\"").append(quote(p.widevineIdB64)).append('"')
                .append(",\"advertising_id\":\"").append(quote(p.advertisingId)).append('"')
                .append(",\"imei\":\"").append(quote(p.imei)).append('"')
                .append(",\"iccid\":\"").append(quote(p.iccid)).append('"')
                .append(",\"mac_address\":\"").append(quote(p.macAddress)).append('"')
                .append(",\"bt_name\":\"").append(quote(p.bluetoothName)).append('"')
                .append(",\"device_seed\":\"").append(quote(p.deviceSeedB64)).append('"')
                .append('}').toString();
    }

    /** All fields are engine-generated (hex/b64/uuid/nickname) — escaping is a pure formality. */
    private static String quote(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static VirtualDeviceProfile readProfile(File f) {
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return fromJson(bos.toString("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "Could not read identity profile; regenerating", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static VirtualDeviceProfile fromJson(String json) {
        if (json == null || !json.contains("android_id")) {
            return null;
        }
        VirtualDeviceProfile p = new VirtualDeviceProfile();
        p.androidId = extract(json, "android_id");
        p.widevineIdB64 = extract(json, "widevine_id");
        p.advertisingId = extract(json, "advertising_id");
        p.imei = extract(json, "imei");
        p.iccid = extract(json, "iccid");
        p.macAddress = extract(json, "mac_address");
        p.bluetoothName = extract(json, "bt_name");
        p.deviceSeedB64 = extract(json, "device_seed");
        if (p.androidId == null || p.widevineIdB64 == null) {
            return null; // generation parity change → regenerate
        }
        return p;
    }

    /** Extracts a top-level string field (without android.util.JsonReader; payload-dex parity). */
    private static String extract(String json, String field) {
        int k = json.indexOf("\"" + field + "\":\"");
        if (k < 0) {
            return null;
        }
        int start = k + field.length() + 4;
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end).replace("\\\\", "\\").replace("\\\"", "\"");
    }

    private static void writeProfile(File f, VirtualDeviceProfile p) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(f, false);
            out.write(toJson(p).getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Lock down to owner read/write only (0600-style; writable for future re-derives).
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to persist identity profile", t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // LSPlant-hook stubs (dormant until a verified Java bridge exists — see HAS_LSPLANT)
    // ---------------------------------------------------------------------------------------------
    /**
     * Hook targets once LSPlant is wired (each target individually guarded; failures logged
     * and skipped so one bad target cannot poison the rest of the identity subsystem):
     * <ul>
     *   <li>android.provider.Settings$NameValueCache#getStringForUser — ANDROID_ID query → profile.
     *       (Static method: must be hooked via ART method replacement, not proxying.)</li>
     *   <li>android.telephony.TelephonyManager#getImei(int) / getDeviceId / getSubscriberId /
     *       getSimSerialNumber — PLT-style instance methods on the binder facade.</li>
     *   <li>android.media.MediaDrm#getPropertyByteArray(name) — when
     *       "deviceUniqueId" → widevine bytes (PropertySet passthrough untouched).</li>
     *   <li>java.net.NetworkInterface#getHardwareAddress +
     *       android.net.wifi.WifiInfo#getMacAddress — MAC from profile.</li>
     *   <li>com.google.android.gms.ads.identifier.AdvertisingIdClient$Info#getId — advertising_id,
     *       attempted only when the GMS class is present on the app classpath (catch-ignore).</li>
     * </ul>
     */
    private static void installLsplantHooks() {
        if (dev.cloneforge.runtime.CloneRuntimeEngine.HAS_LSPLANT) {
            // LSPlant wiring goes here; individually-guarded per target.
        } else {
            Log.i(TAG, "DeviceIdentityHook: profile persisted; ART identity hooks "
                    + "deferred (LSPlant unavailable)");
        }
    }
}
