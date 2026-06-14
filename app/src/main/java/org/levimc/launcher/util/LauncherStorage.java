package org.levimc.launcher.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class LauncherStorage {
    private static final String PREFS_NAME = "storage_migration";
    private static final String KEY_COMPLETED = "storage_migration_completed";
    private static final String LEGACY_ROOT_PATH = "games/org.levimc";
    private static final String ANDROID_DIR = "Android";
    private static final String ANDROID_DATA_DIR = "data";
    private static final String ANDROID_MEDIA_DIR = "media";
    private static final String MINECRAFT_DIR = "minecraft";
    private static final String CRASH_LOGS_DIR = "crash_logs";
    private static final String BACKUPS_DIR = "backups";
    private static final String WORLDS_DIR = "worlds";

    private LauncherStorage() {
    }

    public static File getAppRoot(Context context) {
        if (shouldUseLegacyRoot(context)) {
            return getLegacyRoot();
        }
        return getTargetAppRoot(context);
    }

    public static File getTargetAppRoot(Context context) {
        File[] filesDirs = context.getExternalFilesDirs(null);
        if (filesDirs != null) {
            for (File filesDir : filesDirs) {
                File appRoot = resolveAndroidMediaRoot(context, filesDir);
                if (appRoot != null && ensureDir(appRoot)) {
                    return appRoot;
                }
            }
        }

        File fallback = context.getExternalFilesDir(null);
        if (fallback != null && ensureDir(fallback)) {
            return fallback;
        }

        File internalFallback = new File(context.getFilesDir(), "media");
        ensureDir(internalFallback);
        return internalFallback;
    }

    public static String getTargetAppRootDisplayPath(Context context) {
        return ANDROID_DIR + "/" + ANDROID_MEDIA_DIR + "/" + context.getPackageName();
    }

    private static File resolveAndroidMediaRoot(Context context, File externalFilesDir) {
        if (externalFilesDir == null) return null;
        File packageDir = externalFilesDir.getParentFile();
        if (packageDir == null) return null;
        File dataDir = packageDir.getParentFile();
        if (dataDir == null || !ANDROID_DATA_DIR.equals(dataDir.getName())) return null;
        File androidDir = dataDir.getParentFile();
        if (androidDir == null || !ANDROID_DIR.equals(androidDir.getName())) return null;
        return new File(new File(androidDir, ANDROID_MEDIA_DIR), context.getPackageName());
    }

    public static File getLegacyRoot() {
        return new File(Environment.getExternalStorageDirectory(), LEGACY_ROOT_PATH);
    }

    public static boolean isMigrationCompleted(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPLETED, false);
    }

    public static boolean shouldUseLegacyRoot(Context context) {
        File legacyRoot = getLegacyRoot();
        return !isMigrationCompleted(context) && hasAnyChild(legacyRoot);
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
            File noMediaFile = new File(getAppRoot(context), ".nomedia");
            File parent = noMediaFile.getParentFile();
            if (parent != null) ensureDir(parent);
            if (!noMediaFile.exists()) noMediaFile.createNewFile();
        } catch (Exception ignored) {
        }
    }

    public static boolean ensureDir(File dir) {
        return dir != null && (dir.exists() ? dir.isDirectory() : dir.mkdirs());
    }

    public static boolean hasAnyChild(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File[] children = dir.listFiles();
        return children != null && children.length > 0;
    }
}
