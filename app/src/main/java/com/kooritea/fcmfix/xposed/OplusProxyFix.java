package com.kooritea.fcmfix.xposed;

import android.content.Intent;
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
        int hooks = hookAllMethods("com.android.server.am.OplusAppStartupManager$OplusStartupStrategy",
                "isGoogleRestricInfoOn", Boolean.FALSE);
        if (hooks == 0) throw new NoSuchMethodError("isGoogleRestricInfoOn");
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
