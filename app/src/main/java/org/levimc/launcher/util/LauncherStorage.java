package org.levimc.launcher.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class LauncherStorage {
    private static final String PREFS_NAME = "storage_migration";
    private static final String KEY_COMPLETED = "storage_migration_completed";
    private static final String LEGACY_ROOT_PATH = "games/org.levimc";
    private static final String NO_MEDIA_FILE = ".nomedia";
    private static final String ANDROID_DIR = "Android";
    private static final String ANDROID_DATA_DIR = "data";
    private static final String FILES_DIR = "files";
    private static final String MINECRAFT_DIR = "minecraft";
    private static final String CRASH_LOGS_DIR = "crash_logs";
    private static final String BACKUPS_DIR = "backups";
    private static final String WORLDS_DIR = "worlds";
    private static final Object CACHE_LOCK = new Object();
    private static volatile Boolean cachedUseLegacyRoot;
    private static volatile File cachedTargetAppRoot;

    private LauncherStorage() {
    }

    public static File getAppRoot(Context context) {
        if (shouldUseLegacyRoot(context)) {
            return getLegacyRoot();
        }
        return getTargetAppRoot(context);
    }

    public static File getTargetAppRoot(Context context) {
        File cached = cachedTargetAppRoot;
        if (cached != null) {
            return cached;
        }

        synchronized (CACHE_LOCK) {
            cached = cachedTargetAppRoot;
            if (cached != null) {
                return cached;
            }
            cachedTargetAppRoot = resolveTargetAppRoot(context);
            return cachedTargetAppRoot;
        }
    }

    private static File resolveTargetAppRoot(Context context) {
        File fallback = context.getExternalFilesDir(null);
        if (fallback != null && ensureDir(fallback)) {
            return fallback;
        }

        File internalFallback = context.getFilesDir();
        ensureDir(internalFallback);
        return internalFallback;
    }

    public static String getTargetAppRootDisplayPath(Context context) {
        return ANDROID_DIR + "/" + ANDROID_DATA_DIR + "/" + context.getPackageName() + "/" + FILES_DIR;
    }

    public static File getLegacyRoot() {
        return new File(Environment.getExternalStorageDirectory(), LEGACY_ROOT_PATH);
    }

    public static boolean isMigrationCompleted(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPLETED, false);
    }

    public static boolean shouldUseLegacyRoot(Context context) {
        Boolean cached = cachedUseLegacyRoot;
        if (cached != null) {
            return cached;
        }

        synchronized (CACHE_LOCK) {
            cached = cachedUseLegacyRoot;
            if (cached != null) {
                return cached;
            }
            if (isMigrationCompleted(context)) {
                cachedUseLegacyRoot = false;
                return false;
            }
            if (!getLegacyRoot().exists()) {
                markMigrationCompleted(context);
                cachedUseLegacyRoot = false;
                return false;
            }
            cachedUseLegacyRoot = hasLegacyMarker();
            return cachedUseLegacyRoot;
        }
    }

    public static void markMigrationCompleted(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPLETED, true)
                .apply();
    }

    public static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            cachedUseLegacyRoot = null;
            cachedTargetAppRoot = null;
        }
    }

    public static File getMinecraftRoot(Context context) {
        File root = new File(getAppRoot(context), MINECRAFT_DIR);
        ensureDir(root);
        return root;
    }

    public static File getVersionDir(Context context, String directoryName) {
        File dir = new File(getMinecraftRoot(context), directoryName);
        ensureDir(dir);
        return dir;
    }

    public static File getCrashLogsDir(Context context) {
        File dir = new File(getAppRoot(context), CRASH_LOGS_DIR);
        ensureDir(dir);
        return dir;
    }

    public static File getBackupsRoot(Context context) {
        File dir = new File(getAppRoot(context), BACKUPS_DIR);
        ensureDir(dir);
        return dir;
    }

    public static File getWorldBackupsDir(Context context) {
        File dir = new File(getBackupsRoot(context), WORLDS_DIR);
        ensureDir(dir);
        return dir;
    }

    public static void ensureNoMedia(Context context) {
        try {
            File noMediaFile = new File(getAppRoot(context), NO_MEDIA_FILE);
            File parent = noMediaFile.getParentFile();
            if (parent != null) ensureDir(parent);
            if (!noMediaFile.exists()) noMediaFile.createNewFile();
        } catch (Exception ignored) {
        }
    }

    public static boolean ensureDir(File dir) {
        return dir != null && (dir.exists() ? dir.isDirectory() : dir.mkdirs());
    }

    public static boolean hasLegacyMarker() {
        return new File(getLegacyRoot(), NO_MEDIA_FILE).isFile();
    }
}
