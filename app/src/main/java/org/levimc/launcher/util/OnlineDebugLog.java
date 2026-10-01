package org.levimc.launcher.util;

/**
 * v562：联机调试文件日志——vivo 类设备的 logcat 抓不到应用日志
 * （OriginOS 限制），联机关键路径写文件，pull 出来排查异地问题：
 *   adb pull /sdcard/Download/levimc_online_debug.log
 */
public final class OnlineDebugLog {

    private static final String PATH = "/sdcard/Download/levimc_online_debug.log";

    private OnlineDebugLog() {
    }

    public static void log(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(PATH, true);
            fw.write(System.currentTimeMillis() + " " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {
        }
    }
}
