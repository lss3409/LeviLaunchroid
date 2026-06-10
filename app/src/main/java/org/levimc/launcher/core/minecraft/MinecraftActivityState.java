package org.levimc.launcher.core.minecraft;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.List;

public final class MinecraftActivityState {
    private static final String PREFS_NAME = "minecraft_activity_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_RESUMED = "resumed";
    private static final String KEY_PID = "pid";
    private static final String KEY_UPDATED_AT = "updated_at";

    private static volatile boolean running = false;
    private static volatile boolean resumed = false;
    private static WeakReference<Activity> currentActivityRef;

    private MinecraftActivityState() {}

    public static void onCreated(Activity activity) {
        running = true;
        currentActivityRef = new WeakReference<>(activity);
        writePersistentState(activity, true, false);
    }

    public static void onResumed() {
        resumed = true;
    }

    public static void onResumed(Activity activity) {
        resumed = true;
        writePersistentState(activity, true, true);
    }

    public static void onPaused() {
        resumed = false;
    }

    public static void onPaused(Activity activity) {
        resumed = false;
        writePersistentState(activity, true, false);
    }

    public static void onDestroyed() {
        running = false;
        resumed = false;
        currentActivityRef = null;
    }

    public static void onDestroyed(Activity activity) {
        running = false;
        resumed = false;
        currentActivityRef = null;
        writePersistentState(activity, false, false);
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isRunning(Context context) {
        if (running) return true;
        if (context == null) return false;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            return false;
        }

        int pid = prefs.getInt(KEY_PID, -1);
        boolean alive = pid > 0 && isMinecraftProcessAlive(context, pid);
        if (!alive) {
            writePersistentState(context, false, false);
        }
        return alive;
    }

    public static boolean isResumed() {
        return resumed;
    }

    public static boolean isResumed(Context context) {
        if (resumed) return true;
        if (!isRunning(context)) return false;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_RESUMED, false);
    }

    public static Activity getCurrentActivity() {
        return currentActivityRef != null ? currentActivityRef.get() : null;
    }

    private static void writePersistentState(Context context, boolean isRunning, boolean isResumed) {
        if (context == null) return;

        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, isRunning)
                .putBoolean(KEY_RESUMED, isResumed)
                .putInt(KEY_PID, isRunning ? Process.myPid() : -1)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();
    }

    private static boolean isMinecraftProcessAlive(Context context, int pid) {
        String expectedProcessName = context.getPackageName() + ":minecraft";
        try {
            ActivityManager activityManager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                List<ActivityManager.RunningAppProcessInfo> processes =
                        activityManager.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo process : processes) {
                        if (process.pid == pid && expectedProcessName.equals(process.processName)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return expectedProcessName.equals(readProcessName(pid));
    }

    private static String readProcessName(int pid) {
        byte[] buffer = new byte[256];
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/cmdline")) {
            int length = input.read(buffer);
            if (length <= 0) return "";
            int end = 0;
            while (end < length && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }
}
