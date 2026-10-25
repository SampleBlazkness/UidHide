package com.blazkness.uidhide;

import android.os.SystemClock;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Precomputed caller -> hidden target-uids map.
 *
 * The detectors call getPackagesForUid tens of thousands of times; doing a config parse or a
 * file stat per call makes the scan visibly slow. Snapshots are rebuilt at most every 30 s and
 * every lookup is a hash hit.
 */
final class HiddenUids {

    private static final long TTL_MS = 30_000L;

    private static volatile Snapshot snapshot;

    private HiddenUids() {}

    static boolean isHidden(int callerUid, int targetUid) {
        Snapshot s = snapshot;
        long now = SystemClock.elapsedRealtime();
        if (s == null || now - s.builtAt > TTL_MS) {
            s = rebuild();
            snapshot = s;
        }
        Set<Integer> hidden = s.byCaller.get(PkgList.firstPackage(callerUid));
        return hidden != null && hidden.contains(targetUid);
    }

    private static Snapshot rebuild() {
        Snapshot s = new Snapshot(SystemClock.elapsedRealtime());
        try {
            Map<Integer, List<String>> byUid = PkgList.all();
            for (Map.Entry<Integer, List<String>> e : byUid.entrySet()) {
                for (String pkg : e.getValue()) {
                    List<String> hiddenPkgs = HiddenConfig.hiddenFor(pkg);
                    if (hiddenPkgs == null || hiddenPkgs.isEmpty()) continue;
                    Set<Integer> hiddenUids = s.byCaller.get(pkg);
                    if (hiddenUids == null) {
                        hiddenUids = new HashSet<>();
                        s.byCaller.put(pkg, hiddenUids);
                    }
                    hiddenUids.addAll(hiddenUidsFor(hiddenPkgs, byUid));
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static Set<Integer> hiddenUidsFor(List<String> hiddenPkgs, Map<Integer, List<String>> byUid) {
        Set<Integer> out = new HashSet<>();
        Set<String> wanted = new HashSet<>(hiddenPkgs);
        for (Map.Entry<Integer, List<String>> e : byUid.entrySet()) {
            for (String pkg : e.getValue()) {
                if (wanted.contains(pkg)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    private static final class Snapshot {
        final long builtAt;
        final Map<String, Set<Integer>> byCaller = new HashMap<>();

        Snapshot(long builtAt) {
            this.builtAt = builtAt;
        }
    }
}
