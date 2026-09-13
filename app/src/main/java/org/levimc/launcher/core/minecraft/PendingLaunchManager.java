package org.levimc.launcher.core.minecraft;

import android.content.Context;
import android.content.SharedPreferences;

import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;

import java.util.List;

/**
 * 「关闭旧游戏后自动启动新版本」的跨重启记忆。
 *
 * 当开启「启动新版本时关闭旧游戏」开关后，点击新版本会：
 * 1. 记录待启动版本（持久化到 SharedPreferences，杀进程重启后仍在）；
 * 2. finish 当前游戏触发退出流程（杀进程重启启动器）；
 * 3. 启动器重启后 consumePendingLaunch 查出该版本并自动启动。
 */
public final class PendingLaunchManager {
    private static final String PREFS = "pending_launch";
    private static final String KEY_IS_INSTALLED = "is_installed";
    private static final String KEY_PROFILE_ID = "profile_id";
    private static final String KEY_VERSION_DIR = "version_dir";

    private PendingLaunchManager() {
    }

    public static void setPendingLaunch(Context context, GameVersion version) {
        if (version == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IS_INSTALLED, version.isInstalled)
                .putString(KEY_PROFILE_ID, version.getStorageProfileId())
                .putString(KEY_VERSION_DIR, version.versionDir != null ? version.versionDir.getAbsolutePath() : null)
                .apply();
    }

    /** 读取并清除待启动版本（不存在返回 null）。 */
    public static GameVersion consumePendingLaunch(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean isInstalled = p.getBoolean(KEY_IS_INSTALLED, false);
        String profileId = p.getString(KEY_PROFILE_ID, null);
        String versionDir = p.getString(KEY_VERSION_DIR, null);
        p.edit().clear().apply();

        if (profileId == null && versionDir == null) return null;
        return findVersion(context, isInstalled, profileId, versionDir);
    }

    /** 通过版本标识查找完整 GameVersion。 */
    public static GameVersion findVersion(Context context, boolean isInstalled, String profileId, String versionDir) {
        try {
            VersionManager vm = VersionManager.get(context);
            if (isInstalled) {
                List<GameVersion> list = vm.getInstalledVersions();
                return list.isEmpty() ? null : list.get(0);
            }
            for (GameVersion v : vm.getCustomVersions()) {
                if ((profileId != null && profileId.equals(v.getStorageProfileId()))
                        || (versionDir != null && v.versionDir != null && versionDir.equals(v.versionDir.getAbsolutePath()))) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
