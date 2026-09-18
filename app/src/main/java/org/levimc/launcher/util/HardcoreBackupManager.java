package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.core.content.WorldManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.settings.FeatureSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 极限存档（Hardcore）定时备份管理。
 *
 * 检测世界的极限模式（level.dat IsHardcore），按用户设定的间隔（分钟/天）对极限世界自动备份。
 * 仅当世界「最近被游玩过」（目录 lastModified 相对上次备份发生变化）时才备份，避免重复备份相同内容。
 *
 * 备份到：.../minecraftWorlds backups/Hardcore backups/世界名称/
 * 文件名含详细时间戳（年-月-日 时-分-秒）与种子。
 */
public final class HardcoreBackupManager {
    private static final String TAG = "HardcoreBackupManager";
    private static final String PREFS_NAME = "hardcore_backup";
    private static final int BUFFER_SIZE = 131072;

    // 备份时机：三选一
    public static final String TRIGGER_INGAME = "ingame";   // 局内备份（游玩中定时）
    public static final String TRIGGER_PAUSE = "pause";     // 局内暂停时备份
    public static final String TRIGGER_EXIT = "exit";       // 退出后备份

    public interface Callback {
        void onBackedUp(WorldItem world, String backupPath);
        void onSkipped(WorldItem world, String reason);
        void onFinished(int backedUp, int skipped);

        /** 备份进度（0~100），后台线程回调。 */
        default void onProgress(WorldItem world, int percent) {
        }
    }

    public static class BackupRecord {
        public final File file;
        public final String worldName;
        public final long seed;
        public final long timestamp;

        BackupRecord(File file, String worldName, long seed, long timestamp) {
            this.file = file;
            this.worldName = worldName;
            this.seed = seed;
            this.timestamp = timestamp;
        }
    }

    private final Context context;

    public HardcoreBackupManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 扫描当前选中版本世界目录，返回所有世界（含非极限）。 */
    public static List<WorldItem> scanWorlds(Context context) {
        List<File> dirs = new ArrayList<>();
        VersionManager vm = VersionManager.getIfInitialized();
        GameVersion current = vm != null ? vm.getSelectedVersion() : null;
        if (current == null) {
            dirs.add(new File(LauncherStorage.getSharedGameDataDir(context, true), "minecraftWorlds"));
        } else {
            // 正版/盗版统一扫描启动器重定向目录（与内容管理、游戏运行时一致）
            FeatureSettings.StorageType resolved = LauncherStorage.normalizeContentStorageType(
                    FeatureSettings.StorageType.EXTERNAL, current.versionIsolation);
            File gameData = LauncherStorage.getContentGameDataDir(context, current.getStorageProfileId(), resolved);
            if (gameData != null) dirs.add(new File(gameData, "minecraftWorlds"));
        }

        WorldManager wm = new WorldManager(context);
        if (dirs.size() == 1) {
            wm.setWorldsDirectory(dirs.get(0));
        } else if (!dirs.isEmpty()) {
            wm.setAggregateWorldsDirectories(dirs);
        }
        return wm.getWorlds();
    }

    /** 扫描极限世界列表。 */
    public static List<WorldItem> scanHardcoreWorlds(Context context) {
        List<WorldItem> result = new ArrayList<>();
        for (WorldItem w : scanWorlds(context)) {
            if (w.isHardcore()) result.add(w);
        }
        return result;
    }

    /** 直接扫描指定世界目录下的极限世界（供游戏进程使用，不依赖 VersionManager）。 */
    public static List<WorldItem> scanHardcoreWorldsIn(File worldsDir) {
        List<WorldItem> result = new ArrayList<>();
        if (worldsDir == null || !worldsDir.isDirectory()) return result;
        File[] worldDirs = worldsDir.listFiles(File::isDirectory);
        if (worldDirs == null) return result;
        for (File worldDir : worldDirs) {
            WorldItem world = new WorldItem(worldDir.getName(), worldDir);
            if (world.isValid() && world.isHardcore()) {
                result.add(world);
            }
        }
        return result;
    }

    // ---- 备份间隔设置 ----

    public long getIntervalMs() {
        SharedPreferences p = prefs(context);
        String unit = p.getString("interval_unit", "minutes");
        long value = p.getLong("interval_value", 30);
        if ("days".equals(unit)) {
            return value * 24L * 60L * 60L * 1000L;
        }
        return value * 60L * 1000L;
    }

    public long getIntervalValue() {
        SharedPreferences p = prefs(context);
        return p.getLong("interval_value", 30);
    }

    public String getIntervalUnit() {
        return prefs(context).getString("interval_unit", "minutes");
    }

    public void setInterval(long value, String unit) {
        prefs(context).edit()
                .putLong("interval_value", value)
                .putString("interval_unit", unit)
                .apply();
    }

    public boolean isEnabled() {
        return prefs(context).getBoolean("enabled", false);
    }

    public void setEnabled(boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
    }

    /** 当前备份时机，默认「局内备份」。 */
    public String getBackupTrigger() {
        return prefs(context).getString("backup_trigger", TRIGGER_INGAME);
    }

    public void setBackupTrigger(String trigger) {
        prefs(context).edit().putString("backup_trigger", trigger).apply();
    }

    // ---- 判断是否需要备份 ----

    /** 返回 null 表示需要备份；否则返回跳过原因。 */
    private String shouldBackup(WorldItem world, long intervalMs) {
        long lastModified = world.getFile() != null ? world.getFile().lastModified() : 0L;

        SharedPreferences p = prefs(context);
        long lastBackupAt = p.getLong("last_backup_at_" + world.getWorldId(), 0L);
        long lastBackupModified = p.getLong("last_backup_modified_" + world.getWorldId(), 0L);

        if (lastBackupAt == 0L) {
            return null; // 从未备份过，直接备份
        }

        // 间隔未到 → 跳过
        if (System.currentTimeMillis() - lastBackupAt < intervalMs) {
            return "未到设定的备份间隔";
        }

        // 最近没有游玩（目录未变化）→ 跳过，避免重复备份
        if (lastModified == lastBackupModified) {
            return "最近未游玩，无需重复备份";
        }

        return null;
    }

    /** 检查所有极限世界，按间隔备份；备份过程通过通知栏显示进度与结果。 */
    public void checkAndBackup(List<WorldItem> worlds, Callback callback) {
        new Thread(() -> {
            if (!isEnabled()) {
                post(callback, () -> callback.onFinished(0, 0));
                return;
            }
            long intervalMs = getIntervalMs();
            int backedUp = 0;
            int skipped = 0;
            for (WorldItem world : worlds) {
                if (!world.isHardcore()) continue;
                String skipReason = shouldBackup(world, intervalMs);
                if (skipReason != null) {
                    skipped++;
                    post(callback, () -> callback.onSkipped(world, skipReason));
                    continue;
                }
                try {
                    // 备份开始：通知栏显示进度条
                    showBackupNotification(world, 0, null);
                    String path = backupWorld(world, percent -> {
                        post(callback, () -> callback.onProgress(world, percent));
                        showBackupNotification(world, percent, null);
                    });
                    backedUp++;
                    // 备份完成：通知栏显示结果与位置
                    showBackupNotification(world, 100, path);
                    post(callback, () -> callback.onBackedUp(world, path));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to backup hardcore world " + world.getWorldName(), e);
                    skipped++;
                    showBackupNotification(world, -1, e.getMessage());
                    post(callback, () -> callback.onSkipped(world, "备份失败：" + e.getMessage()));
                }
            }
            final int b = backedUp;
            final int s = skipped;
            post(callback, () -> callback.onFinished(b, s));
        }, "hardcore-backup").start();
    }

    /** 立即备份一个极限世界（手动触发），带进度回调（后台线程）。 */
    public String backupWorld(WorldItem world) throws IOException {
        return backupWorld(world, null);
    }

    /** 立即备份一个极限世界（手动触发），zip 过程中按百分比回调进度。 */
    public String backupWorld(WorldItem world, IntConsumer progress) throws IOException {
        File worldFile = world.getFile();
        if (worldFile == null || !worldFile.exists()) {
            throw new IOException("世界目录不存在");
        }

        File worldBackupDir = getWorldBackupDir(world);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String seedStr = world.getSeed() != 0 ? "seed" + world.getSeed() : "noseed";
        String backupName = world.getWorldName() + "_" + seedStr + "_" + timestamp + ".mcworld";
        File backupFile = new File(worldBackupDir, backupName);

        long totalBytes = countBytes(worldFile);
        final long[] written = {0};
        final int[] lastPercent = {-1};
        try (OutputStream fos = new FileOutputStream(backupFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zipDirectory(worldFile, "", zos, totalBytes, written, progress, lastPercent);
        }

        // 记录上次备份状态
        prefs(context).edit()
                .putLong("last_backup_at_" + world.getWorldId(), System.currentTimeMillis())
                .putLong("last_backup_modified_" + world.getWorldId(), worldFile.lastModified())
                .apply();

        return backupFile.getAbsolutePath();
    }

    /** 进度回调（0~100 整数）。 */
    public interface IntConsumer {
        void accept(int percent);
    }

    // ---- 备份通知（通知栏弹窗：进度 + 结果路径） ----

    private static final String NOTIF_CHANNEL_ID = "hardcore_backup";

    private void ensureNotifChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    context.getString(R.string.hardcore_backup_notif_channel),
                    android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.hardcore_backup_notif_channel));
            android.app.NotificationManager nm =
                    context.getSystemService(android.app.NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 通知栏显示备份状态。
     * @param percent 0~100 进行中（进度条）；100 完成；-1 失败
     * @param detail  完成时 = 备份文件路径；失败时 = 错误信息
     */
    private void showBackupNotification(WorldItem world, int percent, String detail) {
        try {
            android.app.NotificationManager nm =
                    context.getSystemService(android.app.NotificationManager.class);
            if (nm == null) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && nm.areNotificationsEnabled() == false) {
                return;
            }
            ensureNotifChannel();
            android.app.Notification.Builder builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                builder = new android.app.Notification.Builder(context, NOTIF_CHANNEL_ID);
            } else {
                builder = new android.app.Notification.Builder(context);
            }
            builder.setSmallIcon(org.levimc.launcher.R.drawable.ic_leaf_logo_mono)
                    .setContentTitle(context.getString(R.string.hardcore_backup_notif_title,
                            world.getWorldName()));

            if (percent >= 0 && percent < 100) {
                builder.setProgress(100, percent, false)
                        .setContentText(percent + "%")
                        .setOngoing(true);
            } else if (percent == 100) {
                builder.setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setContentText(context.getString(R.string.hardcore_backup_notif_done,
                                detail != null ? detail : ""));
            } else {
                builder.setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setContentText(context.getString(R.string.hardcore_backup_notif_failed,
                                detail != null ? detail : ""));
            }
            String worldId = world.getWorldId();
            int notifId = worldId != null && !worldId.isEmpty() ? worldId.hashCode() : world.getWorldName().hashCode();
            nm.notify(notifId, builder.build());
        } catch (Throwable t) {
            Log.w(TAG, "备份通知失败", t);
        }
    }

    /** 若世界自上次备份后无变化（未游玩），返回 null 表示跳过，避免无意义重复备份。 */
    public String backupWorldIfChanged(WorldItem world) throws IOException {
        File worldFile = world.getFile();
        long lastModified = worldFile != null ? worldFile.lastModified() : 0L;
        SharedPreferences p = prefs(context);
        long lastBackupAt = p.getLong("last_backup_at_" + world.getWorldId(), 0L);
        long lastBackupModified = p.getLong("last_backup_modified_" + world.getWorldId(), 0L);
        if (lastBackupAt != 0L && lastModified == lastBackupModified) {
            return null; // 未游玩，无需重复备份
        }
        return backupWorld(world);
    }

    /** 最近一次备份时间戳（毫秒），从未备份返回 0。 */
    public long getLastBackupTime(WorldItem world) {
        return prefs(context).getLong("last_backup_at_" + world.getWorldId(), 0L);
    }

    private void zipDirectory(File dir, String basePath, ZipOutputStream zos,
                              long totalBytes, long[] written, IntConsumer progress, int[] lastPercent)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String entryPath = basePath.isEmpty() ? file.getName() : basePath + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, entryPath, zos, totalBytes, written, progress, lastPercent);
            } else {
                ZipEntry entry = new ZipEntry(entryPath);
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                        written[0] += len;
                        if (progress != null && totalBytes > 0) {
                            int percent = (int) Math.min(99, written[0] * 100 / totalBytes);
                            if (percent != lastPercent[0]) {
                                lastPercent[0] = percent;
                                progress.accept(percent);
                            }
                        }
                    }
                }
                zos.closeEntry();
            }
        }
    }

    /** 递归统计目录总字节数（用于备份进度百分比）。 */
    private long countBytes(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                total += countBytes(file);
            } else {
                total += file.length();
            }
        }
        return total;
    }

    private static String sanitize(String name) {
        if (name == null) return "world";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "world" : s;
    }

    /** 当前选中游戏版本的显示名称（用于备份分类），无版本时返回 unknown。 */
    private String getVersionLabel() {
        VersionManager vm = VersionManager.getIfInitialized();
        GameVersion current = vm != null ? vm.getSelectedVersion() : null;
        String label = current != null ? current.displayName : null;
        if (label == null || label.isEmpty()) return "unknown";
        return sanitize(label);
    }

    /** 列出所有极限世界备份记录（按时间倒序，最新在前）。 */
    public List<BackupRecord> listBackups(WorldItem world) {
        List<BackupRecord> records = new ArrayList<>();
        File worldBackupDir = new File(new File(LauncherStorage.getHardcoreBackupsDir(context), getVersionLabel()), backupDirName(world));
        if (!worldBackupDir.exists()) return records;
        File[] files = worldBackupDir.listFiles();
        if (files == null) return records;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".mcworld")) continue;
            records.add(new BackupRecord(file, world.getWorldName(), world.getSeed(), parseBackupTime(file)));
        }
        records.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return records;
    }

    /** 从备份文件名（..._yyyyMMdd_HHmmss.mcworld）解析备份时间；解析失败时退回文件修改时间。 */
    public static long parseBackupTime(File file) {
        if (file == null) return 0L;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("_(\\d{8}_\\d{6})\\.mcworld$")
                .matcher(file.getName());
        if (m.find()) {
            try {
                long t = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .parse(m.group(1)).getTime();
                if (t > 0) return t;
            } catch (Exception ignored) {
            }
        }
        return file.lastModified();
    }

    /** 删除一个备份文件。 */
    public boolean deleteBackup(File backupFile) {
        return backupFile != null && backupFile.exists() && backupFile.delete();
    }

    /** 该世界极限备份目录（按 版本号/世界名_seed种子 分类，同名不同种子的世界互不混淆）。 */
    public File getWorldBackupDir(WorldItem world) {
        File dir = new File(new File(LauncherStorage.getHardcoreBackupsDir(context), getVersionLabel()), backupDirName(world));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 备份目录名：v413 起优先存档唯一标识（leviWorldId），
     *  无标识（老存档）回退 世界名_seed种子。 */
    public static String backupDirName(org.levimc.launcher.core.content.WorldItem world) {
        String id = world != null ? world.getLeviWorldId() : null;
        if (id != null && !id.isEmpty()) {
            return "id_" + sanitize(id);
        }
        return sanitize(world != null ? world.getWorldName() : "world")
                + "_seed" + (world != null ? world.getSeed() : 0L);
    }

    /** 备份目录名：世界名_seed种子。 */
    public static String backupDirName(String worldName, long seed) {
        return sanitize(worldName) + "_seed" + seed;
    }

    /**
     * 确保备份目录里存在一个信息文件（info.json），记录存档详细信息。
     * MT 管理器无法直接打开文件夹，但可以打开这个文件，从而间接定位到该备份目录。
     */
    public File ensureInfoFile(WorldItem world) {
        File dir = getWorldBackupDir(world);
        File infoFile = new File(dir, "存档信息.json");
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("世界名称", world.getWorldName());
            root.put("版本号", getVersionLabel());
            root.put("世界ID", world.getWorldId());
            root.put("种子", world.getSeed());
            root.put("模式", world.isHardcore() ? "极限模式" : "普通生存");
            root.put("最近备份", getLastBackupTime(world) > 0
                    ? fmt.format(new Date(getLastBackupTime(world))) : "尚未备份");
            root.put("备份目录", dir.getAbsolutePath());

            java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(infoFile), java.nio.charset.StandardCharsets.UTF_8);
            writer.write(root.toString(2));
            writer.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write info file: " + e.getMessage());
        }
        return infoFile;
    }

    private void post(Callback callback, Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    /** 回档：删除当前世界，用指定备份原地恢复（替换成备份内容，目录名不变）。 */
    public boolean restoreWorldFromBackup(WorldItem world, BackupRecord backup) throws IOException {
        if (backup == null || backup.file == null || !backup.file.exists()) return false;

        File targetDir = world.getFile();
        if (targetDir == null || !targetDir.exists()) {
            // 世界目录已被删除：用新目录名解压生成
            File worldsDir = resolveWorldsDir();
            String dirName = "levi" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            targetDir = new File(worldsDir, dirName);
            if (!targetDir.exists()) targetDir.mkdirs();
        } else {
            // 世界还在（已死亡变旁观）：删除后原地恢复
            deleteDirectory(targetDir);
            if (!targetDir.exists() && !targetDir.mkdirs()) return false;
        }
        return extractWorldTo(backup.file, targetDir);
    }

    /** 回档：删除当前世界，用最新备份原地恢复（替换成备份内容，目录名不变）。 */
    public boolean restoreWorldFromLatestBackup(WorldItem world) throws IOException {
        List<BackupRecord> records = listBackups(world);
        if (records.isEmpty()) return false;
        return restoreWorldFromBackup(world, records.get(0));
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private File resolveWorldsDir() {
        File worldsDir = null;
        VersionManager vm = VersionManager.getIfInitialized();
        GameVersion current = vm != null ? vm.getSelectedVersion() : null;
        if (current != null) {
            FeatureSettings.StorageType resolved = LauncherStorage.normalizeContentStorageType(
                    FeatureSettings.StorageType.EXTERNAL, current.versionIsolation);
            File gameData = LauncherStorage.getContentGameDataDir(context, current.getStorageProfileId(), resolved);
            if (gameData != null) worldsDir = new File(gameData, "minecraftWorlds");
        }
        if (worldsDir == null) {
            worldsDir = new File(LauncherStorage.getSharedGameDataDir(context, true), "minecraftWorlds");
        }
        if (!worldsDir.exists()) worldsDir.mkdirs();
        return worldsDir;
    }

    /** 解压 .mcworld 备份到目标世界目录。 */
    private boolean extractWorldTo(File mcworldFile, File destDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(mcworldFile))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.contains("..")) continue; // 防目录穿越
                File target = new File(destDir, entryName);
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        return true;
    }
}
