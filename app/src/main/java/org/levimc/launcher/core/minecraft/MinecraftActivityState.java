package org.levimc.launcher.core.minecraft;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.lang.ref.WeakReference;

public final class MinecraftActivityState {
    private static volatile boolean running = false;
    private static volatile boolean resumed = false;
    private static WeakReference<Activity> currentActivityRef;

    private MinecraftActivityState() {}

    public static void onCreated(Activity activity) {
        running = true;
        currentActivityRef = new WeakReference<>(activity);
    }

    public static void onResumed() {
        resumed = true;
    }

    public static void onResumed(Activity activity) {
        resumed = true;
    }

    public static void onPaused() {
        resumed = false;
    }

    public static void onPaused(Activity activity) {
        resumed = false;
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
    }

    public static boolean isRunning() {
        return running;
    }

    /** 当前游戏 Activity（弱引用，可能为 null；v524 联机悬浮窗用）。 */
    public static Activity getActivity() {
        WeakReference<Activity> ref = currentActivityRef;
        return ref == null ? null : ref.get();
    }

    public static boolean isRunning(Context context) {
        return running;
    }

    public static boolean isResumed() {
        return resumed;
    }

    public static boolean isResumed(Context context) {
        return resumed;
    }

    public static Activity getCurrentActivity() {
        return currentActivityRef != null ? currentActivityRef.get() : null;
    }

    /** 当前运行中游戏的版本目录名（MINECRAFT_VERSION_DIR），未知返回 null。 */
    public static String getRunningVersionDir() {
        Activity activity = getCurrentActivity();
        if (activity == null) return null;
        Intent intent = activity.getIntent();
        if (intent == null) return null;
        return intent.getStringExtra("MINECRAFT_VERSION_DIR");
    }

    /**
     * 把正在运行的游戏任务栈带回前台（相当于「回到游戏」）。
     * 不携带 data/action，MinecraftActivity.onNewIntent 会忽略这类请求，
     * 不会覆盖启动参数（存储路径等 extras）。
     *
     * @return 游戏 Activity 存在并已发起请求返回 true；游戏已不在则返回 false。
     */
    public static boolean bringGameToFront(Context context) {
        Activity game = getCurrentActivity();
        if (game == null || game.isFinishing()) return false;
        Intent forward = new Intent(game, MinecraftActivity.class);
        forward.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        game.startActivity(forward);
        return true;
    }
}
