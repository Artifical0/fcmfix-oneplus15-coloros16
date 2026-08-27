package com.kooritea.fcmfix.xposed;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.List;

/** Restores only the Google entries omitted by the ColorOS CN regional Doze list. */
public class OplusDeviceIdleFix extends XposedModule {

    private static final String OPLUS_DEVICE_IDLE_HELPER =
            "com.android.server.OplusDeviceIdleHelper";
    private static final String[] GOOGLE_DOZE_PACKAGES = new String[]{
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending"
    };

    public OplusDeviceIdleFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHook();
        } catch (Throwable e) {
            printLog("hook error OplusDeviceIdleFix: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void startHook() {
        Class<?> helperClass = XposedHelpers.findClassIfExists(
                OPLUS_DEVICE_IDLE_HELPER, classLoader);
        if (helperClass == null) {
            throw new NoClassDefFoundError(OPLUS_DEVICE_IDLE_HELPER);
        }

        int whitelistHooks = 0;
        int restrictSwitchHooks = 0;
        for (Method method : helperClass.getDeclaredMethods()) {
            if ("getNewWhiteList".equals(method.getName())) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        List<String> whiteList = findListArgument(param.args);
                        if (whiteList == null && param.getResult() instanceof List) {
                            whiteList = (List<String>) param.getResult();
                        }
                        if (whiteList == null) return;

                        for (String packageName : GOOGLE_DOZE_PACKAGES) {
                            if (!whiteList.contains(packageName)) {
                                whiteList.add(packageName);
                                printLog("Oplus Doze whitelist restored: " + packageName, true);
                            }
                        }
                    }
                });
                whitelistHooks++;
                printLog("Oplus Doze whitelist hook active: " + describeMethod(method));
            } else if ("getGoogleRestrictSwitch".equals(method.getName())
                    && (method.getReturnType() == boolean.class
                    || method.getReturnType() == Boolean.class)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                });
                restrictSwitchHooks++;
                printLog("Oplus Doze Google restriction hook active: " + describeMethod(method));
            }
        }
        if (whitelistHooks == 0) throw new NoSuchMethodError("getNewWhiteList");
        if (restrictSwitchHooks == 0) {
            printLog("OplusDeviceIdleHelper#getGoogleRestrictSwitch not found");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> findListArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof List) return (List<String>) arg;
        }
        return null;
    }

    private static String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) result.append(',');
            result.append(types[i].getSimpleName());
        }
        return result.append("): ").append(method.getReturnType().getSimpleName()).toString();
    }
}
