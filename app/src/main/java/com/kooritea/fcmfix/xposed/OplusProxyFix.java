package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.WorkSource;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class OplusProxyFix extends XposedModule {

    private static final String OPLUS_APP_STARTUP_MANAGER =
            "com.android.server.am.OplusAppStartupManager";
    private static final String OPLUS_STARTUP_STRATEGY =
            OPLUS_APP_STARTUP_MANAGER + "$OplusStartupStrategy";
    private static final String OPLUS_HANS_DB_CONFIG =
            "com.android.server.hans.OplusHansDBConfig";
    private static final String TYPE_BIND_SERVICE_FROM_GCM = "bsgcm";
    private static final String START_PROCESS_FROM_GCM_BIND_SERVICE = "system[gcm]";

    private static final String[] PROXY_BROADCAST_CLASSES = new String[]{
            "com.android.server.am.OplusProxyBroadcast",
            "com.android.server.am.OplusBroadcastProxy",
            "com.oplus.server.am.OplusProxyBroadcast"
    };

    private static final String[] PROXY_WAKELOCK_CLASSES = new String[]{
            "com.android.server.power.OplusProxyWakeLock",
            "com.android.server.power.oplus.OplusProxyWakeLock",
            "com.oplus.server.power.OplusProxyWakeLock"
    };

    private static volatile Object sOplusProxyWakeLock;
    private static volatile Method sUnfreezeMethod;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        runHook("OplusProxyWakeLock", this::startHookOplusProxyWakeLock);
        runHook("OplusProxyBroadcast", this::startHookOplusProxyBroadcast);
        runHook("registerGmsRestrictObserver", this::startHookRegisterGmsRestrictObserver);
        runHook("updateGmsRestrict", this::startHookUpdateGmsRestrict);
        runHook("isGoogleRestricInfoOn", this::startHookIsGoogleRestricInfoOn);
        runHook("isAppClassifyRestricted", this::startHookAppClassifyRestricted);
        runHook("isAllowStartFromBindService", this::startHookGcmBindService);
        runHook("isAllowStartFromStartService", this::startHookFcmStartService);
        runHook("isSysRestrictionCpn", this::startHookHansGmsRestriction);
    }

    private interface HookAction {
        void run() throws Throwable;
    }

    private void runHook(String name, HookAction action) {
        try {
            action.run();
        } catch (Throwable e) {
            printLog("hook error " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void startHookOplusProxyBroadcast() {
        int hookCount = 0;
        for (String className : PROXY_BROADCAST_CLASSES) {
            Class<?> proxyClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (proxyClass == null) {
                continue;
            }

            for (Method method : proxyClass.getDeclaredMethods()) {
                if (!"shouldProxy".equals(method.getName())) {
                    continue;
                }
                Object noProxyResult = getNoProxyResult(method.getReturnType());
                if (noProxyResult == UnsupportedResult.VALUE) {
                    printLog("unsupported shouldProxy candidate: " + describeMethod(method));
                    continue;
                }

                final Object finalNoProxyResult = noProxyResult;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent intent = findIntentArgument(param.args);
                        if (intent == null || !isFCMAction(intent.getAction())) {
                            return;
                        }

                        String target = getIntentTarget(intent);
                        if (target == null) {
                            target = findAllowedPackageArgument(param.args);
                        }
                        if (target != null && targetIsAllow(target)) {
                            printLog("Oplus shouldProxy bypass: pkg=" + target
                                    + ", action=" + intent.getAction(), true);
                            param.setResult(finalNoProxyResult);
                        }
                    }
                });
                hookCount++;
                printLog("Oplus shouldProxy hook active: " + describeMethod(method));
            }
        }
        if (hookCount == 0) {
            throw new NoSuchMethodError("No compatible Oplus shouldProxy method");
        }
    }

    private void startHookOplusProxyWakeLock() {
        Class<?> wakeLockClass = null;
        for (String className : PROXY_WAKELOCK_CLASSES) {
            wakeLockClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (wakeLockClass != null) {
                break;
            }
        }
        if (wakeLockClass == null) {
            throw new NoClassDefFoundError("OplusProxyWakeLock");
        }

        sUnfreezeMethod = findBestUnfreezeMethod(wakeLockClass);
        if (sUnfreezeMethod == null) {
            throw new NoSuchMethodError(wakeLockClass.getName() + "#unfreezeIfNeed");
        }
        sUnfreezeMethod.setAccessible(true);
        printLog("Oplus unfreeze method selected: " + describeMethod(sUnfreezeMethod));

        if (Modifier.isStatic(sUnfreezeMethod.getModifiers())) {
            return;
        }

        int constructorHooks = 0;
        for (Constructor<?> constructor : wakeLockClass.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sOplusProxyWakeLock = param.thisObject;
                    printLog("OplusProxyWakeLock instance captured");
                }
            });
            constructorHooks++;
        }
        if (constructorHooks == 0) {
            throw new NoSuchMethodError(wakeLockClass.getName() + "#<init>");
        }
    }

    private Method findBestUnfreezeMethod(Class<?> clazz) {
        Method best = null;
        int bestScore = -1;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"unfreezeIfNeed".equals(method.getName())) {
                continue;
            }
            int score = 0;
            for (Class<?> type : method.getParameterTypes()) {
                if (type == int.class || type == Integer.class) score += 4;
                if (WorkSource.class.isAssignableFrom(type)) score += 3;
                if (type == String.class) score += 1;
            }
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }

    private static int getTargetUidFromPackageName(String packageName) {
        if (packageName != null && context != null) {
            try {
                return context.getPackageManager().getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                printLog("error: Package not found: " + packageName);
            }
        }
        return -1;
    }

    public static void unfreeze(String target) {
        Method method = sUnfreezeMethod;
        if (method == null) {
            return;
        }
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : sOplusProxyWakeLock;
        if (!Modifier.isStatic(method.getModifiers()) && receiver == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        Object[] args = createUnfreezeArguments(method.getParameterTypes(), uid);
        if (args == null) {
            printLog("unsupported Oplus unfreeze arguments: " + describeMethod(method));
            return;
        }

        try {
            method.invoke(receiver, args);
            printLog("unfreeze " + target + ", uid=" + uid, true);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            printLog("Oplus unfreeze invocation failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage(), true);
        } catch (Throwable e) {
            printLog("Oplus unfreeze invocation failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), true);
        }
    }

    private static Object[] createUnfreezeArguments(Class<?>[] types, int uid) {
        Object[] args = new Object[types.length];
        boolean uidAssigned = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == int.class || type == Integer.class) {
                args[i] = uidAssigned ? 0 : uid;
                uidAssigned = true;
            } else if (WorkSource.class.isAssignableFrom(type)) {
                args[i] = new WorkSource();
            } else if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
                args[i] = "FCMFix";
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else if (type == float.class || type == Float.class) {
                args[i] = 0F;
            } else if (type == double.class || type == Double.class) {
                args[i] = 0D;
            } else if (!type.isPrimitive()) {
                args[i] = null;
            } else {
                return null;
            }
        }
        return uidAssigned ? args : null;
    }

    private void startHookRegisterGmsRestrictObserver() {
        int hooks = hookAllMethods("com.android.server.hans.scene.OplusBgSceneManager",
                "registerGmsRestrictObserver", null);
        if (hooks == 0) throw new NoSuchMethodError("registerGmsRestrictObserver");
    }

    private void startHookUpdateGmsRestrict() {
        int hooks = hookAllMethods("com.android.server.hans.scene.OplusBgSceneManager",
                "updateGmsRestrict", null);
        if (hooks == 0) throw new NoSuchMethodError("updateGmsRestrict");
    }

    private void startHookIsGoogleRestricInfoOn() {
        int hooks = hookAllMethods(OPLUS_STARTUP_STRATEGY,
                "isGoogleRestricInfoOn", Boolean.FALSE);
        if (hooks == 0) throw new NoSuchMethodError("isGoogleRestricInfoOn");
    }

    /**
     * ColorOS CN keeps per-component classify restriction lists that are skipped on the
     * international build. This check runs before the normal broadcast/service allow-list
     * decision, so only bypass it for an actual FCM intent addressed to an enabled target.
     */
    private void startHookAppClassifyRestricted() {
        Class<?> strategyClass = XposedHelpers.findClassIfExists(OPLUS_STARTUP_STRATEGY, classLoader);
        if (strategyClass == null) throw new NoClassDefFoundError(OPLUS_STARTUP_STRATEGY);

        int hooks = 0;
        for (Method method : strategyClass.getDeclaredMethods()) {
            if (!"isAppClassifyRestricted".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !isFCMIntent(intent)) return;

                    String target = getIntentTarget(intent);
                    if (target == null) target = findCalleePackage(param.args);
                    if (target != null && targetIsAllow(target)) {
                        printLog("Oplus classify restriction bypass: pkg=" + target
                                + ", action=" + intent.getAction(), true);
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus classify hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isAppClassifyRestricted");
    }

    /**
     * On ColorOS 16 the GMS-to-app delivery path is tagged as type "bsgcm" and still
     * passes through isAllowAutoStartByList even when google_restric_info is disabled.
     */
    private void startHookGcmBindService() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(OPLUS_APP_STARTUP_MANAGER, classLoader);
        if (managerClass == null) throw new NoClassDefFoundError(OPLUS_APP_STARTUP_MANAGER);

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            if (!"isAllowStartFromBindService".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!hasStringArgument(param.args, TYPE_BIND_SERVICE_FROM_GCM)
                            && !hasStringArgument(param.args, START_PROCESS_FROM_GCM_BIND_SERVICE)) {
                        return;
                    }
                    String target = findCalleePackage(param.args);
                    if (target != null && targetIsAllow(target)) {
                        printLog("Oplus GCM bind-service bypass: pkg=" + target, true);
                        param.setResult(true);
                    }
                }
            });
            hooks++;
            printLog("Oplus GCM bind hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isAllowStartFromBindService");
    }

    /** Older Firebase delivery variants use startService instead of the GCM bind path. */
    private void startHookFcmStartService() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(OPLUS_APP_STARTUP_MANAGER, classLoader);
        if (managerClass == null) throw new NoClassDefFoundError(OPLUS_APP_STARTUP_MANAGER);

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            if (!"isAllowStartFromStartService".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !isFCMIntent(intent)) return;

                    String target = findCalleePackage(param.args);
                    if (target != null && targetIsAllow(target)) {
                        printLog("Oplus FCM start-service bypass: pkg=" + target
                                + ", action=" + intent.getAction(), true);
                        param.setResult(true);
                    }
                }
            });
            hooks++;
            printLog("Oplus FCM start hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isAllowStartFromStartService");
    }

    /**
     * The CN ELSA policy gives GMS/GSF/Play a broad Hans prevent mask. Returning the
     * framework's NOT_PROXY result here is the runtime equivalent of clearing that mask,
     * without changing the XML or weakening restrictions for unrelated packages.
     */
    private void startHookHansGmsRestriction() {
        Class<?> configClass = XposedHelpers.findClassIfExists(OPLUS_HANS_DB_CONFIG, classLoader);
        if (configClass == null) throw new NoClassDefFoundError(OPLUS_HANS_DB_CONFIG);

        int hooks = 0;
        for (Method method : configClass.getDeclaredMethods()) {
            if (!"isSysRestrictionCpn".equals(method.getName())) continue;
            Object notProxy = findEnumConstant(method.getReturnType(), "NOT_PROXY");
            if (notProxy == null) continue;

            final Object finalNotProxy = notProxy;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String packageName = (String) param.args[0];
                    if (isGoogleCorePackage(packageName)) {
                        printLog("Oplus Hans GMS restriction bypass: pkg=" + packageName, true);
                        param.setResult(finalNotProxy);
                    }
                }
            });
            hooks++;
            printLog("Oplus Hans restriction hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isSysRestrictionCpn");
    }

    private int hookAllMethods(String className, String methodName, Object result) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) return 0;
        int hooks = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (result == null && method.getReturnType() != void.class) {
                continue;
            }
            if (result == Boolean.FALSE && method.getReturnType() != boolean.class
                    && method.getReturnType() != Boolean.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(result);
                }
            });
            hooks++;
            printLog("Oplus restriction hook active: " + describeMethod(method));
        }
        return hooks;
    }

    private String getIntentTarget(Intent intent) {
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        return intent.getPackage();
    }

    private String findAllowedPackageArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && targetIsAllow((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    private String findCalleePackage(Object[] args) {
        Intent intent = findIntentArgument(args);
        if (intent != null) {
            String target = getIntentTarget(intent);
            if (target != null) return target;
        }
        for (Object arg : args) {
            if (arg instanceof ApplicationInfo) {
                return ((ApplicationInfo) arg).packageName;
            }
            if (arg == null) continue;
            try {
                Object appInfo = XposedHelpers.getObjectField(arg, "appInfo");
                if (appInfo instanceof ApplicationInfo) {
                    return ((ApplicationInfo) appInfo).packageName;
                }
            } catch (Throwable ignored) {
            }
        }
        return findAllowedPackageArgument(args);
    }

    private boolean hasStringArgument(Object[] args, String expected) {
        for (Object arg : args) {
            if (expected.equals(arg)) return true;
        }
        return false;
    }

    private boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private boolean isGoogleCorePackage(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.android.vending".equals(packageName);
    }

    private Object findEnumConstant(Class<?> type, String wantedName) {
        if (!type.isEnum()) return null;
        Object[] constants = type.getEnumConstants();
        if (constants == null) return null;
        for (Object constant : constants) {
            if (wantedName.equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    private Intent findIntentArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Object nestedIntent = XposedHelpers.getObjectField(arg, "intent");
                if (nestedIntent instanceof Intent) {
                    return (Intent) nestedIntent;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object getNoProxyResult(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType.isEnum()) {
            Object[] constants = returnType.getEnumConstants();
            if (constants != null) {
                String[] preferred = new String[]{"NOT_INCLUDE", "NOT_PROXY", "ALLOW", "PASS"};
                for (String name : preferred) {
                    for (Object constant : constants) {
                        if (name.equals(((Enum<?>) constant).name())) {
                            return constant;
                        }
                    }
                }
            }
        }
        return UnsupportedResult.VALUE;
    }

    private enum UnsupportedResult { VALUE }

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
