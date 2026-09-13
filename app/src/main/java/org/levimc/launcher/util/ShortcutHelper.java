package org.levimc.launcher.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import androidx.core.content.ContextCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.minecraft.MinecraftLoadingActivity;
import org.levimc.launcher.core.minecraft.ShortcutLaunchActivity;
import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * 桌面快捷方式工具：为某个版本号在桌面创建/更新/删除快捷方式。
 *
 * 快捷方式直接指向 MinecraftLoadingActivity，携带完整版本信息（GameVersion + 存储路径），
 * 点击后跳过主页直接进入对应版本加载流程，正版/盗版统一走启动器（享有 LeviLauncher 全部能力）。
 */
public final class ShortcutHelper {
    private static final int ICON_SIZE = 192;

    // 快捷方式 intent 只能携带基本类型（PersistableBundle 无法序列化自定义 Parcelable）
    public static final String EXTRA_SHORTCUT_IS_INSTALLED = "org.levimc.launcher.extra.SHORTCUT_IS_INSTALLED";
    public static final String EXTRA_SHORTCUT_PROFILE_ID = "org.levimc.launcher.extra.SHORTCUT_PROFILE_ID";
    public static final String EXTRA_SHORTCUT_VERSION_DIR = "org.levimc.launcher.extra.SHORTCUT_VERSION_DIR";

    private ShortcutHelper() {
    }

    /** 快捷方式唯一 ID（正版固定，盗版按 profileId）。 */
    public static String getShortcutId(GameVersion version) {
        if (version == null) return null;
        return version.isInstalled ? "instance_installed" : "instance_" + version.getStorageProfileId();
    }

    /** 默认快捷方式名称：直接使用版本号。 */
    public static String getDefaultLabel(GameVersion version) {
        if (version == null) return "Minecraft";
        if (version.versionCode != null && !version.versionCode.isEmpty()) return version.versionCode;
        if (version.directoryName != null && !version.directoryName.isEmpty()) return version.directoryName;
        return "Minecraft";
    }

    /** 构造给 ShortcutInfo 用的 intent：只带基本类型，指向 ShortcutLaunchActivity。 */
    public static Intent buildShortcutIntent(Context context, GameVersion version) {
        Intent intent = new Intent(context, ShortcutLaunchActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_SHORTCUT_IS_INSTALLED, version.isInstalled);
        intent.putExtra(EXTRA_SHORTCUT_PROFILE_ID, version.getStorageProfileId());
        if (version.versionDir != null) {
            intent.putExtra(EXTRA_SHORTCUT_VERSION_DIR, version.versionDir.getAbsolutePath());
        }
        return intent;
    }

    /** 构造指向 MinecraftLoadingActivity 的完整启动 Intent（进程内使用，可携带 GameVersion）。 */
    public static Intent buildLaunchIntent(Context context, GameVersion version) {
        Intent launchIntent = new Intent(context, MinecraftLoadingActivity.class);
        // ShortcutInfo 要求 intent 必须带 action，否则 build() 抛 "intent's action must be set"
        launchIntent.setAction(Intent.ACTION_VIEW);
        String profileId = MinecraftLauncher.getStorageProfileId(version);
        boolean isolation = version.versionIsolation;

        File filesDir = LauncherStorage.getStorageFilesRoot(context, profileId, isolation, false);
        File externalFilesDir = LauncherStorage.getStorageFilesRoot(context, profileId, isolation, true);
        File dataDir = LauncherStorage.getStorageDataRoot(context, profileId, isolation);
        File cacheDir = LauncherStorage.getStorageCacheRoot(context, profileId, isolation);

        launchIntent.putExtra("MC_PATH", version.versionDir == null ? "" : version.versionDir.getAbsolutePath());
        launchIntent.putExtra("IS_INSTALLED", version.isInstalled);
        launchIntent.putExtra("VERSION_ISOLATION", isolation);
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID, profileId);
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR, filesDir.getAbsolutePath());
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR, externalFilesDir.getAbsolutePath());
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR, dataDir.getAbsolutePath());
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR, cacheDir.getAbsolutePath());
        launchIntent.putExtra(MinecraftLauncher.EXTRA_GAME_VERSION, version);
        launchIntent.putExtra("MODS_ENABLED", false);
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode);
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName);
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically);
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation);
        return launchIntent;
    }

    /** 是否已经创建过该版本的桌面快捷方式。 */
    public static boolean isPinned(Context context, GameVersion version) {
        ShortcutManager sm = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null) return false;
        String id = getShortcutId(version);
        try {
            for (ShortcutInfo info : sm.getPinnedShortcuts()) {
                if (id != null && id.equals(info.getId())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 把版本号添加到桌面（用指定名称）。
     *
     * @return true 表示请求已交给系统（可能弹出确认框）；false 表示当前桌面不支持。
     */
    public static boolean pinShortcut(Context context, GameVersion version, String label) {
        ShortcutManager sm = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null || version == null) return false;

        ShortcutInfo info = buildShortcutInfo(context, version, label);

        if (sm.isRequestPinShortcutSupported()) {
            try {
                return sm.requestPinShortcut(info, null);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** 更新已创建快捷方式的名称（改名）。 */
    public static boolean renameShortcut(Context context, GameVersion version, String newLabel) {
        ShortcutManager sm = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null || version == null) return false;
        try {
            ShortcutInfo info = buildShortcutInfo(context, version, newLabel);
            return sm.updateShortcuts(Collections.singletonList(info));
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除该版本的桌面快捷方式（disable 使其失效，用户可在桌面长按彻底移除）。 */
    public static void removeShortcut(Context context, GameVersion version) {
        ShortcutManager sm = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null || version == null) return;
        String id = getShortcutId(version);
        if (id == null) return;
        try {
            sm.disableShortcuts(Collections.singletonList(id));
        } catch (Exception ignored) {
        }
    }

    /** 旧系统兜底：通过 INSTALL_SHORTCUT 广播添加桌面快捷方式。 */
    @SuppressWarnings("deprecation")
    public static boolean pinShortcutLegacy(Context context, GameVersion version, String label) {
        try {
            Intent launchIntent = buildShortcutIntent(context, version);
            Intent shortcutIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent);
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(context, R.drawable.ic_minecraft_cube));
            shortcutIntent.putExtra("duplicate", false);
            context.sendBroadcast(shortcutIntent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static ShortcutInfo buildShortcutInfo(Context context, GameVersion version, String label) {
        String safeLabel = (label == null || label.trim().isEmpty())
                ? getDefaultLabel(version)
                : label.trim();
        return new ShortcutInfo.Builder(context, getShortcutId(version))
                .setShortLabel(safeLabel)
                .setIcon(buildIcon(context, version))
                .setIntent(buildShortcutIntent(context, version))
                .build();
    }

    /** 图标：直接用 MC 图标（正版系统图标 / 盗版 MC 方块），不叠加角标。 */
    private static Icon buildIcon(Context context, GameVersion version) {
        Drawable base = getMcIcon(context, version);
        if (base == null) {
            return Icon.createWithResource(context, R.drawable.ic_minecraft_cube);
        }
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        base.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
        base.draw(canvas);
        return Icon.createWithBitmap(bitmap);
    }

    private static Drawable getMcIcon(Context context, GameVersion version) {
        if (version != null && version.isInstalled) {
            try {
                return context.getPackageManager().getApplicationIcon(MinecraftLauncher.MC_PACKAGE_NAME);
            } catch (Exception ignored) {
            }
        }
        try {
            return ContextCompat.getDrawable(context, R.drawable.ic_minecraft_cube);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 兼容用：返回当前所有已 pin 的快捷方式 id（调试/展示用）。 */
    public static List<ShortcutInfo> getPinned(Context context) {
        ShortcutManager sm = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null) return Collections.emptyList();
        try {
            return sm.getPinnedShortcuts();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
