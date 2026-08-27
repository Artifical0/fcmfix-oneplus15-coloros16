package com.kooritea.fcmfix.xposed;

import android.content.pm.PackageManager;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * ColorOS Battery's GoogleRestrictionController applies POLICY_REJECT_ALL when its
 * Google connectivity probe fails. Keep the hook inside com.oplus.battery so manual
 * per-app network controls from Settings/TrafficMonitor remain untouched.
 */
public class OplusBatteryNetworkFix extends XposedModule {

    private static final String NETWORK_CONTROL_MANAGER =
            "android.net.OplusNetworkingControlManager";
    private static final int POLICY_REJECT_ALL = 4;
    private static final int POLICY_NONE = 0;
    private static final String[] GOOGLE_NETWORK_PACKAGES = new String[]{
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.configupdater"
    };

    public OplusBatteryNetworkFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHookGoogleNetworkPolicy();
        } catch (Throwable e) {
            printLog("hook error Oplus Battery GMS network policy: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void startHookGoogleNetworkPolicy() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                NETWORK_CONTROL_MANAGER, classLoader);
        if (managerClass == null) {
            throw new NoClassDefFoundError(NETWORK_CONTROL_MANAGER);
        }

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"setUidPolicy".equals(method.getName())
                    || parameters.length != 2
                    || parameters[0] != int.class
                    || parameters[1] != int.class) {
                continue;
            }

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[0];
                    int policy = (Integer) param.args[1];
                    if (policy != POLICY_REJECT_ALL || !isGoogleNetworkUid(uid)) {
                        return;
                    }

                    // Let the original method clear any stale reject-all state in netd.
                    param.args[1] = POLICY_NONE;
                    printLog("Oplus Battery GMS network reject bypass: uid=" + uid, true);
                }
            });
            hooks++;
            printLog("Oplus Battery network hook active: " + method);
        }

        if (hooks == 0) {
            throw new NoSuchMethodError(NETWORK_CONTROL_MANAGER + "#setUidPolicy(int,int)");
        }
    }

    private boolean isGoogleNetworkUid(int uid) {
        if (context == null) {
            printLog("Oplus Battery network hook skipped before context initialization");
            return false;
        }

        PackageManager packageManager = context.getPackageManager();
        for (String packageName : GOOGLE_NETWORK_PACKAGES) {
            try {
                if (packageManager.getPackageUid(packageName, 0) == uid) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }
}
