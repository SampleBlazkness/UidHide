package com.blazkness.uidhide;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal reader for /data/system/packages.list: "package uid debuggable ..." one per line.
 * system_server is allowed to read it; this avoids calling back into PackageManagerService from
 * inside a PackageManagerService hook.
 */
final class PkgList {

    private static Map<Integer, List<String>> byUid = Collections.emptyMap();
    private static long lastModified;

    private PkgList() {}

    private static synchronized void refresh() {
        File f = new File("/data/system/packages.list");
        long mtime = f.lastModified();
        if (mtime == lastModified && !byUid.isEmpty()) return;

        Map<Integer, List<String>> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 2) continue;
                try {
                    int uid = Integer.parseInt(parts[1]);
                    List<String> list = map.get(uid);
                    if (list == null) {
                        list = new ArrayList<>(2);
                        map.put(uid, list);
                    }
                    list.add(parts[0]);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        byUid = map;
        lastModified = mtime;
    }

    static Map<Integer, List<String>> all() {
        refresh();
        return byUid;
    }

    static List<String> packages(int uid) {
        refresh();
        List<String> list = byUid.get(uid);
        return list == null ? Collections.emptyList() : list;
    }

    static String firstPackage(int uid) {
        List<String> list = packages(uid);
        return list.isEmpty() ? null : list.get(0);
    }
}
