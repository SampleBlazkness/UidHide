package com.blazkness.uidhide;

import android.os.Binder;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hides only the UID -> package mapping for apps that HMA-OSS hides from the caller.
 *
 * Deliberately does not touch package visibility APIs (getPackageInfo / getApplicationInfo /
 * shouldFilterApplication / installed lists): those belong to HMA-OSS, and hooking them from a
 * second module makes the two filters disagree, which detectors see as an inconsistent list.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "UidHide";
    private static final String[] CANDIDATES = {
            "com.android.server.pm.ComputerEngine",
            "com.android.server.pm.IPackageManagerBase",
            "com.android.server.pm.PackageManagerInternalBase",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;

        for (String name : CANDIDATES) {
            try {
                hookClass(Class.forName(name, false, lp.classLoader));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": class missing " + name);
            }
        }
    }

    private void hookClass(Class<?> clazz) {
        String tag = clazz.getSimpleName();

        hook(clazz, "getNameForUid", tag, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 1 || !(param.args[0] instanceof Integer)) return;
                if (isHidden(Binder.getCallingUid(), (Integer) param.args[0])) {
                    param.setResult(null);
                }
            }
        });

        hook(clazz, "getPackagesForUid", tag, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 1 || !(param.args[0] instanceof Integer)) return;
                if (isHidden(Binder.getCallingUid(), (Integer) param.args[0])) {
                    param.setResult(null);
                }
            }
        });

        hook(clazz, "getNamesForUids", tag, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object raw = param.getResult();
                if (!(raw instanceof String[]) || param.args.length < 1) return;
                String[] result = (String[]) raw;
                int[] uids;
                try {
                    uids = (int[]) param.args[0];
                } catch (Throwable t) {
                    return;
                }
                if (uids == null) return;
                int caller = Binder.getCallingUid();
                boolean changed = false;
                for (int i = 0; i < uids.length && i < result.length; i++) {
                    if (isHidden(caller, uids[i])) {
                        result[i] = null;
                        changed = true;
                    }
                }
                if (changed) param.setResult(result);
            }
        });
    }

    private void hook(Class<?> clazz, String method, String tag, XC_MethodHook callback) {
        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(clazz, method, callback);
            int n = set == null ? 0 : set.size();
            XposedBridge.log(TAG + ": " + tag + " " + method + " hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + tag + " " + method + " failed: " + t);
        }
    }

    private boolean isHidden(int callerUid, int targetUid) {
        if (callerUid == targetUid) return false;
        if (callerUid < 10000 || targetUid < 10000) return false;
        try {
            return HiddenUids.isHidden(callerUid, targetUid);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isHidden failed: " + t);
            return false;
        }
    }
}
