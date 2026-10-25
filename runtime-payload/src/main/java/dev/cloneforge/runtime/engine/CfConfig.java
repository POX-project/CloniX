package dev.cloneforge.runtime.engine;

import android.util.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parsed form of assets/cloneforge/config.json (written by the CloneForge builder pipeline):
 * <pre>
 * {"engine_version":"2.0.0",
 *  "orig_pkg":"com.target.app",
 *  "clone_pkg":"com.target.app.cf001",
 *  "clone_suffix":"cf001",
 *  "authority_map":{...},
 *  "permission_map":{...}}
 * </pre>
 */
public final class CfConfig {

    public String engineVersion = "";
    public String origPkg = "";
    public String clonePkg = "";
    public String cloneSuffix = "";
    public Map<String, String> authorityMap = new HashMap<>();
    public Map<String, String> permissionMap = new HashMap<>();

    public CfConfig() {
    }

    /** Parses config.json from a stream using android.util.JsonReader. Never throws. */
    public static CfConfig parse(InputStream in) {
        CfConfig cfg = new CfConfig();
        if (in == null) {
            return cfg;
        }
        JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("engine_version".equals(name)) {
                    cfg.engineVersion = reader.nextString();
                } else if ("orig_pkg".equals(name)) {
                    cfg.origPkg = reader.nextString();
                } else if ("clone_pkg".equals(name)) {
                    cfg.clonePkg = reader.nextString();
                } else if ("clone_suffix".equals(name)) {
                    cfg.cloneSuffix = reader.nextString();
                } else if ("authority_map".equals(name)) {
                    cfg.authorityMap = readStringMap(reader);
                } else if ("permission_map".equals(name)) {
                    cfg.permissionMap = readStringMap(reader);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (Exception e) {
            // Malformed config: keep defaults; hooks degrade accordingly.
        }
        return cfg;
    }

    private static Map<String, String> readStringMap(JsonReader reader) throws IOException {
        Map<String, String> map = new HashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            String value = reader.nextString();
            map.put(key, value);
        }
        reader.endObject();
        return map;
    }
}
