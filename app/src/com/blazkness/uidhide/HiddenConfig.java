package com.blazkness.uidhide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Reads HMA-OSS configuration and answers "which packages does HMA hide from this caller".
 *
 * The hidden set must match HMA's own view exactly; this module never invents one.
 */
final class HiddenConfig {

    private static final String TAG = "UidHide";

    private static final Map<String, List<String>> cache = new HashMap<>();
    private static long lastModified = -1;

    private HiddenConfig() {}

    static synchronized List<String> hiddenFor(String callerPkg) {
        File config = findConfig();
        if (config == null) return null;

        long mtime = config.lastModified();
        if (mtime != lastModified) {
            cache.clear();
            lastModified = mtime;
        }

        List<String> cached = cache.get(callerPkg);
        if (cached != null) return cached;

        List<String> result = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(config)) {
            byte[] buf = new byte[(int) config.length()];
            int n = in.read(buf);
            JSONObject root = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));

            JSONObject scope = root.optJSONObject("scope");
            JSONObject cfg = scope == null ? null : scope.optJSONObject(callerPkg);
            if (cfg == null) {
                cache.put(callerPkg, result);
                return result;
            }
            if (cfg.optBoolean("useWhitelist", false)) {
                XposedBridge.log(TAG + ": " + callerPkg + " is a whitelist caller, skipped");
                cache.put(callerPkg, result);
                return result;
            }

            Set<String> set = new HashSet<>();
            JSONObject templates = root.optJSONObject("templates");
            JSONArray apply = cfg.optJSONArray("applyTemplates");
            if (apply != null && templates != null) {
                for (int i = 0; i < apply.length(); i++) {
                    JSONObject tpl = templates.optJSONObject(apply.optString(i));
                    if (tpl == null || tpl.optBoolean("isWhitelist", false)) continue;
                    JSONArray apps = tpl.optJSONArray("appList");
                    if (apps == null) continue;
                    for (int j = 0; j < apps.length(); j++) set.add(apps.optString(j));
                }
            }

            JSONArray extra = cfg.optJSONArray("extraAppList");
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) set.add(extra.optString(i));
            }
            JSONArray opposite = cfg.optJSONArray("extraOppositeAppList");
            if (opposite != null) {
                for (int i = 0; i < opposite.length(); i++) set.remove(opposite.optString(i));
            }

            result.addAll(set);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": config read failed: " + t);
        }
        cache.put(callerPkg, result);
        return result;
    }

    private static File findConfig() {
        File misc = new File("/data/misc");
        File[] dirs = misc.listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            if (d.isDirectory() && d.getName().startsWith("hide_my_applist_")) {
                File f = new File(d, "config.json");
                if (f.isFile()) return f;
            }
        }
        return null;
    }
}
