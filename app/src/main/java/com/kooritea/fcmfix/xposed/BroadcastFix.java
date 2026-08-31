package com.kooritea.fcmfix.xposed;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.Set;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;

public class BroadcastFix extends XposedModule {

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookBroadcastEntryPoints();
        }catch (Throwable e) {
            printLog("hook error broadcast entry point:" + e.getMessage());
        }
        try{
            this.startHookBroadcastIntentLocked();
        }catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
//        try{
//            this.startHookScheduleResultTo();
//        }catch (Throwable e) {
//            printLog("hook error com.android.server.am.BroadcastQueueModernImpl.scheduleResultTo:" + e.getMessage());
//        }
    }

    /**
     * Android 16 / ColorOS 16 validates and may clone the incoming Intent
     * before entering broadcastIntentLocked. Hook the Binder-facing entry so
     * FLAG_INCLUDE_STOPPED_PACKAGES survives that copy.
     */
    protected void startHookBroadcastEntryPoints(){
        String[] candidateClasses = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        Set<String> hookedSignatures = new HashSet<>();
        int hookCount = 0;

        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntentWithFeature".equals(method.getName())) {
                    continue;
                }
                int intentArgsIndex = findIntentParameterIndex(method);
                if (intentArgsIndex < 0) {
                    continue;
                }
                String signature = describeMethod(method);
                if (!hookedSignatures.add(signature)) {
                    continue;
                }
                try {
                    createBroadcastIntentHooker(intentArgsIndex, -1, method, "entry");
                    hookCount++;
                } catch (Throwable e) {
                    printLog("hook broadcast entry failed: " + signature + ": " + e.getMessage());
                }
            }
        }
        printLog("ColorOS 16 broadcast entry hooks active: " + hookCount);
    }

    protected void startHookBroadcastIntentLocked(){
        String[] candidateClasses = new String[]{
                "com.android.server.am.BroadcastController",
                "com.android.server.am.ActivityManagerService"
        };
        Set<String> hookedSignatures = new HashSet<>();
        int hookCount = 0;

        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                printLog("broadcast hook class missing: " + className);
                continue;
            }

            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntentLocked".equals(method.getName())) {
                    continue;
                }
                int intentArgsIndex = findIntentParameterIndex(method);
                if (intentArgsIndex < 0) {
                    printLog("skip broadcast candidate without Intent: " + describeMethod(method));
                    continue;
                }

                String signature = describeMethod(method);
                if (!hookedSignatures.add(signature)) {
                    continue;
                }

                int appOpArgsIndex = findAppOpParameterIndex(method);
                try {
                    createBroadcastIntentHooker(intentArgsIndex, appOpArgsIndex, method, "locked");
                    hookCount++;
                } catch (Throwable e) {
                    printLog("hook broadcast candidate failed: " + signature + ": " + e.getMessage());
                }
            }
        }

        if (hookCount == 0) {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
        } else {
            printLog("broadcastIntentLocked hooks active: " + hookCount);
        }
    }

    private int findIntentParameterIndex(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (Intent.class.isAssignableFrom(parameterTypes[i])) {
                return i;
            }
        }
        return -1;
    }

    private int findAppOpParameterIndex(Method method) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType() == int.class && "appOp".equals(parameters[i].getName())) {
                return i;
            }
        }

        // Release framework builds often strip parameter names. Keep known AOSP
        // locations only as an optional enhancement; adding the stopped-package
        // flag does not depend on finding appOp.
        int[] candidates;
        if (Build.VERSION.SDK_INT >= 35) {
            candidates = new int[]{13, 12};
        } else if (Build.VERSION.SDK_INT == 34) {
            candidates = new int[]{13, 12};
        } else if (Build.VERSION.SDK_INT == 33) {
            candidates = new int[]{12};
        } else if (Build.VERSION.SDK_INT >= 31) {
            candidates = new int[]{12, 11};
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            candidates = new int[]{10};
        } else {
            candidates = new int[]{9};
        }
        for (int candidate : candidates) {
            if (candidate < parameters.length && parameters[candidate].getType() == int.class) {
                return candidate;
            }
        }
        return -1;
    }

    private String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) result.append(',');
            result.append(types[i].getSimpleName());
        }
        return result.append(')').toString();
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method){
        createBroadcastIntentHooker(intent_args_index, appOp_args_index, method, "locked");
    }

    protected void createBroadcastIntentHooker(int intent_args_index, int appOp_args_index, Method method, String stage){
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target [" + stage + "]: " + describeMethod(method));
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;
        final String finalStage = stage;

        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[finalIntent_args_index] == null){
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                // 介入条件：Intent未包含唤醒停止的pkg 且 Intent是FCM
                if((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && isFCMIntent(intent)){
                    String target;
                    if (intent.getComponent() != null) {
                        target = intent.getComponent().getPackageName();
                    } else {
                        target = intent.getPackage();
                    }
                    boolean targetAllowed = targetIsAllow(target);
                    if ("entry".equals(finalStage)) {
                        printLog("ColorOS16 FCM entry: target=" + target
                                + ", flags=0x" + Integer.toHexString(intent.getFlags())
                                + ", allowed=" + targetAllowed, true);
                    }
                    if(targetAllowed){
                        OplusProxyFix.beginFcmDeliveryWindow(target);
                        if (finalAppOp_args_index >= 0 && finalAppOp_args_index < methodHookParam.args.length) {
                            int i = (Integer) methodHookParam.args[finalAppOp_args_index];
                            if (i == -1) {
                                methodHookParam.args[finalAppOp_args_index] = 11;
                            }
                        }
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        if (getBooleanConfig("includeIceBoxDisableApp",false) && !IceboxUtils.isAppEnabled(context, target)) {
                            printLog("Waiting for IceBox to activate the app: " + target, true);
                            methodHookParam.setResult(false);
                            new Thread(() -> {
                                IceboxUtils.activeApp(context, target);
                                for (int i1 = 0; i1 < 300; i1++) {
                                    if (!IceboxUtils.isAppEnabled(context, target)) {
                                        try {
                                            Thread.sleep(100);
                                        } catch (Throwable e) {
                                            printLog("Send Forced Start Broadcast Error: " + target + " " + e.getMessage(), true);
                                        }
                                    } else {
                                        break;
                                    }
                                }
                                try {
                                    if(IceboxUtils.isAppEnabled(context, target)){
                                        printLog("Send Forced Start Broadcast [" + finalStage + "]: " + target, true);
                                    }else{
                                        printLog("Waiting for IceBox to activate the app timed out: " + target, true);
                                    }
                                    XposedBridge.invokeOriginalMethod(methodHookParam.method, methodHookParam.thisObject, methodHookParam.args);
                                } catch (Throwable e) {
                                    printLog("Send Forced Start Broadcast Error: " + target + " " + e.getMessage(), true);
                                }
                            }).start();
                        }else{
                            printLog("Send Forced Start Broadcast [" + finalStage + "]: " + target, true);
                        }
                        // cos15 unfreeze
                        OplusProxyFix.unfreeze(target);
                    }
                }
            }
        });
    }

    protected void startHookScheduleResultTo(){
        Method method = XposedUtils.findMethod(XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl",classLoader),"scheduleResultTo",1);
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[0] == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultTo") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"intent") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode") == null){
                    return;
                }
                Intent intent = (Intent)XposedHelpers.getObjectField(methodHookParam.args[0],"intent");
                int resultCode = (int) XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode");
                String packageName = intent.getPackage();
                if(resultCode != -1 && getBooleanConfig("noResponseNotification",false) && targetIsAllow(packageName)){
                    try{
                        Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if(notifyIntent!=null){
                            notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                    context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                            createFcmfixChannel(notificationManager);
                            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("FCM Message")
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                            Bitmap icon = getAppIcon(packageName);
                            if(icon != null){
                                notification.setLargeIcon(icon);
                            }
                            notification.setContentIntent(pendingIntent).setAutoCancel(true);
                            notificationManager.notify((int) System.currentTimeMillis(), notification.build());
                        }else{
                            printLog("无法获取目标应用active: " + packageName,false);
                        }
                    }catch (Throwable e){
                        printLog(e.getMessage(),false);
                    }
                }
            }
        });
    }

    private static Bitmap getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable drawable = pm.getApplicationIcon(appInfo);
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            } else {
                Bitmap bitmap = Bitmap.createBitmap(
                        drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new android.graphics.Canvas(bitmap));
                return bitmap;
            }
        } catch (Throwable e) {
            return null;
        }
    }
}
