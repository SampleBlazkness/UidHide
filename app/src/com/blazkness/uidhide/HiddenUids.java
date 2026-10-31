package com.blazkness.uidhide;

import android.os.SystemClock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * caller package -> hidden target uids, rebuilt lazily.
 *
 * Lookups stay hash hits; a full rebuild only happens when the snapshot expires or
 * packages.list was reloaded.
 *
 * Two failure modes this guards against:
 *
 *  - One bad config entry must not drop every other caller (under-hide). The rebuild is
 *    per-entry, a failure is logged and skipped, and a caller missing from the snapshot is
 *    built on demand instead of leaking its hidden uids until the next rebuild.
 *  - A snapshot built from an outdated packages.list must not outlive a uid change
 *    (over-hide, i.e. hiding an app HMA never hid). The snapshot carries PkgList's generation
 *    and is thrown away as soon as packages.list is reloaded.
 */
final class HiddenUids {

    private static final String TAG = "UidHide";

    /** Short enough to pick up a uid reassignment quickly, long enough to stay cheap. */
    private static final long TTL_MS = 5_000L;

    private static Snapshot snapshot;

    private HiddenUids() {}

    static boolean isHidden(int callerUid, int targetUid) {
        String caller = PkgList.firstPackage(callerUid);
        if (caller == null) return false;
        Entry entry = entryFor(caller);
        return entry != null && entry.uids.contains(targetUid);
    }

    private static synchronized Entry entryFor(String caller) {
        long now = SystemClock.elapsedRealtime();
        if (snapshot == null
                || now - snapshot.builtAt > TTL_MS
                || snapshot.generation != PkgList.generation()) {
            snapshot = rebuild();
        }
        Entry entry = snapshot.entries.get(caller);
        if (entry == null) {
            // In HMA's scope but never built (bad entry, or the app appeared after the
            // snapshot): build it now instead of leaking its hidden uids until the TTL.
            entry = buildEntry(caller, PkgList.all());
            if (entry != null) snapshot.entries.put(caller, entry);
        }
        return entry;
    }

    /** Full rebuild. One broken caller is logged and skipped; the others still get built. */
    private static Snapshot rebuild() {
        Map<Integer, List<String>> byUid = PkgList.all();
        Snapshot s = new Snapshot(SystemClock.elapsedRealtime(), PkgList.generation());
        int failures = 0;
        for (Map.Entry<Integer, List<String>> e : byUid.entrySet()) {
            for (String pkg : e.getValue()) {
                if (s.entries.containsKey(pkg)) break;
                try {
                    Entry entry = buildEntry(pkg, byUid);
                    if (entry != null) s.entries.put(pkg, entry);
                } catch (Throwable t) {
                    if (++failures <= 3) {
                        XposedBridge.log(TAG + ": rebuild skipped " + pkg + ": " + t);
                    }
                }
                break;
            }
        }
        return s;
    }

    private static Entry buildEntry(String caller, Map<Integer, List<String>> byUid) {
        List<String> hidden = HiddenConfig.hiddenFor(caller);
        if (hidden == null || hidden.isEmpty()) return null;

        Set<String> pkgs = new HashSet<>(hidden);
        Set<Integer> uids = new HashSet<>();
        for (Map.Entry<Integer, List<String>> e : byUid.entrySet()) {
            for (String pkg : e.getValue()) {
                if (pkgs.contains(pkg)) {
                    uids.add(e.getKey());
                    break;
                }
            }
        }
        if (uids.isEmpty()) return null;
        return new Entry(pkgs, uids);
    }

    private static final class Entry {
        final Set<String> pkgs;
        final Set<Integer> uids;

        Entry(Set<String> pkgs, Set<Integer> uids) {
            this.pkgs = pkgs;
            this.uids = uids;
        }
    }

    private static final class Snapshot {
        final long builtAt;
        final int generation;
        final Map<String, Entry> entries = new HashMap<>();

        Snapshot(long builtAt, int generation) {
            this.builtAt = builtAt;
            this.generation = generation;
        }
    }
}
