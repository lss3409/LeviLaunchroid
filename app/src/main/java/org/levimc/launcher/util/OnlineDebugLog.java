package org.levimc.launcher.util;

import android.content.Context;

/**
 * v562：联机调试文件日志——vivo 类设备的 logcat 抓不到应用日志
 * （OriginOS 限制），联机关键路径写应用内部 files 目录，用 run-as 读：
 *   adb shell run-as org.levimc.launcher cat files/levimc_online_debug.log
 */
public final class OnlineDebugLog {

    private static volatile Context appContext;

    private OnlineDebugLog() {
    }

    /** SplashActivity 启动时初始化（filesDir 路径 run-as 可读）。 */
    public static void init(Context ctx) {
        if (appContext == null && ctx != null) {
            appContext = ctx.getApplicationContext();
        }
    }

    /** v0.0.24：日志轮转上限——曾涨到 171MB（v698 教训），超 5MB 截断重写。 */
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024;

    public static void log(String msg) {
        try {
            Context ctx = appContext;
            String path = ctx != null
                    ? ctx.getFilesDir().getAbsolutePath() + "/levimc_online_debug.log"
                    : "/data/data/org.levimc.launcher/files/levimc_online_debug.log";
            java.io.File f = new java.io.File(path);
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            if (f.length() > MAX_LOG_SIZE) {
                java.io.FileWriter trunc = new java.io.FileWriter(f, false);
                trunc.close();
            }
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(System.currentTimeMillis() + " " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {
        }
    }
}
