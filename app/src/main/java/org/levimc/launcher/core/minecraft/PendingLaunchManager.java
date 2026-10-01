package org.levimc.launcher.core.minecraft;

import android.content.Context;

import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;

import java.util.List;

/**
 * v587：「自动关闭旧游戏」功能已随双后台回退删除（原 setPendingLaunch/
 * consumePendingLaunch 移除），仅保留快捷方式启动的版本查找。
 */
public final class PendingLaunchManager {
    private PendingLaunchManager() {
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
