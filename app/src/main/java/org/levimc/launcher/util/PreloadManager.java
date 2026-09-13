package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.levimc.launcher.core.content.ResourcePackItem;

import java.util.HashSet;
import java.util.Set;

/**
 * 预加载管理：在共享文件夹的资源包/行为包上标记「预加载」，
 * 打开后该包会在每次启动游戏时复制进目标版本目录，并写入 global_resource_packs.json，
 * 使玩家创建世界时无需手动添加即可生效（全局资源）。
 *
 * 用包 UUID 作为标识存储到 SharedPreferences。
 */
public final class PreloadManager {
    private static final String PREFS_NAME = "preload_packs";
    private static final String KEY_UUIDS = "preload_uuids";

    private final Context context;

    public PreloadManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Set<String> getPreloadUuids() {
        return new HashSet<>(prefs(context).getStringSet(KEY_UUIDS, new HashSet<>()));
    }

    public boolean isPreloaded(ResourcePackItem pack) {
        String uuid = pack.getUuid();
        if (uuid == null || uuid.isEmpty()) return false;
        return getPreloadUuids().contains(uuid.toLowerCase());
    }

    public boolean isPreloaded(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;
        return getPreloadUuids().contains(uuid.toLowerCase());
    }

    public void setPreloaded(String uuid, boolean preloaded) {
        if (uuid == null || uuid.isEmpty()) return;
        Set<String> uuids = getPreloadUuids();
        if (preloaded) {
            uuids.add(uuid.toLowerCase());
        } else {
            uuids.remove(uuid.toLowerCase());
        }
        prefs(context).edit().putStringSet(KEY_UUIDS, uuids).apply();
    }
}
