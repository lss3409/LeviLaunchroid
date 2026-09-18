package org.levimc.launcher.core.content;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.util.HardcoreBackupManager;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class WorldManager {
    private static final String TAG = "WorldManager";
    private static final int BUFFER_SIZE = 8192;
    
    private final Context context;
    private final ExecutorService executor;
    private File worldsDirectory;
    private List<File> aggregateWorldsDirs;
    
    public interface WorldOperationCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(int progress);
    }

    public WorldManager(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void setCurrentVersion(GameVersion version) {
        if (version != null && version.versionDir != null) {
            this.worldsDirectory = new File(
                    LauncherStorage.getProfileGameDataDir(context, version.getStorageProfileId()),
                    "minecraftWorlds"
            );
            if (!worldsDirectory.exists()) {
                worldsDirectory.mkdirs();
            }
        } else {
            this.worldsDirectory = null;
        }
    }

    public void setWorldsDirectory(File directory) {
        this.worldsDirectory = directory;
        this.aggregateWorldsDirs = null;
        if (worldsDirectory != null && !worldsDirectory.exists()) {
            worldsDirectory.mkdirs();
        }
    }

    public void setAggregateWorldsDirectories(List<File> dirs) {
        this.aggregateWorldsDirs = dirs;
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
            }
        }
    }

    /** 全部世界根目录（v400 静默烘焙扫描用：聚合多个实例目录时含多个）。 */
    public List<File> getWorldsDirectories() {
        List<File> out = new ArrayList<>();
        if (aggregateWorldsDirs != null) {
            out.addAll(aggregateWorldsDirs);
        } else if (worldsDirectory != null) {
            out.add(worldsDirectory);
        }
        return out;
    }

    public List<WorldItem> getWorlds() {
        List<WorldItem> worlds = new ArrayList<>();
        if (aggregateWorldsDirs != null) {
            for (File dir : aggregateWorldsDirs) {
                scanWorldsDirectory(dir, worlds);
            }
        } else {
            scanWorldsDirectory(worldsDirectory, worlds);
        }
        return worlds;
    }

    private void scanWorldsDirectory(File dir, List<WorldItem> worlds) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] worldDirs = dir.listFiles(File::isDirectory);
        if (worldDirs != null) {
            for (File worldDir : worldDirs) {
                try {
                    WorldItem world = new WorldItem(worldDir.getName(), worldDir);
                    if (world.isValid()) {
                        worlds.add(world);
                    }
                } catch (Throwable e) {
                    // 单个世界读取失败（损坏 db/OOM 等）不能中断整个内容管理
                    Log.w(TAG, "Failed to scan world " + worldDir.getName(), e);
                }
            }
        }
    }

    public void importWorld(Uri worldUri, WorldOperationCallback callback) {
        if (executor.isShutdown()) {
            callback.onError("WorldManager has been shut down");
            return;
        }
        executor.execute(() -> {
            try {
                if (worldsDirectory == null) {
                    callback.onError("No version selected");
                    return;
                }

                InputStream inputStream = context.getContentResolver().openInputStream(worldUri);
                if (inputStream == null) {
                    callback.onError("Cannot open world file");
                    return;
                }

                String tempDirName = "temp_world_" + System.currentTimeMillis();
                File tempDir = new File(context.getCacheDir(), tempDirName);
                tempDir.mkdirs();
                
                File tempZip = new File(context.getCacheDir(), tempDirName + "_zip.mcworld");

                try {
                    copyStreamToFile(inputStream, tempZip);
                    extractZip(tempZip, tempDir, callback);

                    File worldDir = findWorldDirectory(tempDir);
                    if (worldDir == null) {
                        callback.onError("Invalid world file - no world data found");
                        return;
                    }

                    String worldName = generateUniqueWorldName(worldDir.getName(), worldsDirectory);
                    File targetDir = new File(worldsDirectory, worldName);

                    copyDirectory(worldDir, targetDir);

                    // 导入后入队静默烘焙（v400：后台慢速渲染可视化缓存，
                    // 不用打开卫星图跑第一遍；启动器开着就持续烘）
                    try {
                        File db = new File(targetDir, "db");
                        if (db.isDirectory()) {
                            org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                    .initCacheDir(context);
                            org.levimc.launcher.core.content.worldmap.SilentBakeManager
                                    .get().enqueue(targetDir);
                        }
                    } catch (Throwable ignored) {
                    }

                    callback.onSuccess("World imported successfully");
                    
                } finally {
                    deleteDirectory(tempDir);
                    tempZip.delete();
                    inputStream.close();
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to import world", e);
                callback.onError("Import failed: " + e.getMessage());
            }
        });
    }

    public void exportWorld(WorldItem world, Uri exportUri, WorldOperationCallback callback) {
        if (executor.isShutdown()) {
            callback.onError("WorldManager has been shut down");
            return;
        }
        executor.execute(() -> {
            try {
                OutputStream outputStream = context.getContentResolver().openOutputStream(exportUri);
                if (outputStream == null) {
                    callback.onError("Cannot create export file");
                    return;
                }

                try {
                    createWorldZip(world.getFile(), outputStream, callback);
                    callback.onSuccess("World exported successfully");
                } finally {
                    outputStream.close();
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to export world", e);
                callback.onError("Export failed: " + e.getMessage());
            }
        });
    }

    public void deleteWorld(WorldItem world, WorldOperationCallback callback) {
        if (executor.isShutdown()) {
            callback.onError("WorldManager has been shut down");
            return;
        }
        executor.execute(() -> {
            try {
                // 直接删除，不再在扫描目录里留备份副本（否则删除后又出现一个 _backup 世界）
                if (deleteDirectory(world.getFile())) {
                    callback.onSuccess(context.getString(R.string.world_deleted_successfully));
                } else {
                    callback.onError(context.getString(R.string.world_delete_failed));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete world", e);
                callback.onError(context.getString(R.string.world_delete_failed) + ": " + e.getMessage());
            }
        });
    }

    public void backupWorld(WorldItem world, WorldOperationCallback callback) {
        if (executor.isShutdown()) {
            callback.onError("WorldManager has been shut down");
            return;
        }
        executor.execute(() -> {
            try {
                String backupPath = createBackup(world);
                callback.onSuccess("Backup created: " + backupPath);

            } catch (Exception e) {
                Log.e(TAG, "Failed to backup world", e);
                callback.onError("Backup failed: " + e.getMessage());
            }
        });
    }

    private void extractZip(File zipFile, File targetDir, WorldOperationCallback callback) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[BUFFER_SIZE];
            
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryFile = new File(targetDir, entry.getName());

                if (!entryFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    continue;
                }
                
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    entryFile.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(entryFile)) {
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private File findWorldDirectory(File searchDir) {
        File levelDat = new File(searchDir, "level.dat");
        if (levelDat.exists()) {
            return searchDir;
        }
        
        File[] files = searchDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File levelDatInSubdir = new File(file, "level.dat");
                    if (levelDatInSubdir.exists()) {
                        return file;
                    }
                    File found = findWorldDirectory(file);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private String generateUniqueWorldName(String baseName, File directory) {
        String worldName = baseName;
        int counter = 1;
        
        while (new File(directory, worldName).exists()) {
            worldName = baseName + "_" + counter;
            counter++;
        }
        
        return worldName;
    }

    private void copyStreamToFile(InputStream input, File output) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = input.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists()) {
                target.mkdirs();
            }
            
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirectory(file, new File(target, file.getName()));
                }
            }
        } else {
            copyFile(source, target);
        }
    }

    private void copyFile(File source, File target) throws IOException {
        target.getParentFile().mkdirs();
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(target)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void createWorldZip(File worldDir, OutputStream outputStream, WorldOperationCallback callback) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(outputStream);
        zipDirectory(worldDir, "", zos);
        zos.close();
    }

    private void zipDirectory(File dir, String basePath, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String entryPath = basePath.isEmpty() ? file.getName() : basePath + "/" + file.getName();
                if (file.isDirectory()) {
                    zipDirectory(file, entryPath, zos);
                } else {
                    ZipEntry entry = new ZipEntry(entryPath);
                    zos.putNextEntry(entry);

                    if (file.getName().equals("level.dat")) {
                        // v412：打包时注入存档唯一标识 leviWorldId
                        // （TAG_String UUID；本体文件不改，游戏兼容零风险）
                        byte[] data = injectWorldId(file);
                        zos.write(data, 0, data.length);
                    } else {
                        try (FileInputStream fis = new FileInputStream(file)) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    /** v412：读 level.dat，无 leviWorldId 时生成 UUID 注入（TAG_String）
     *  并返回注入后的字节；已有标识则原样返回。NBT 解析失败时不阻断
     *  导出（原字节打包）。 */
    private byte[] injectWorldId(File levelDat) {
        try {
            byte[] original = java.nio.file.Files.readAllBytes(levelDat.toPath());
            org.levimc.launcher.core.content.nbt.NbtTag root =
                    new org.levimc.launcher.core.content.nbt.BedrockNbtReader()
                            .readFile(levelDat);
            if (root == null || root.getTag("leviWorldId") != null) {
                return original;
            }
            String uuid = java.util.UUID.randomUUID().toString();
            root.putTag("leviWorldId", new org.levimc.launcher.core.content.nbt.NbtTag(
                    org.levimc.launcher.core.content.nbt.NbtTag.TAG_STRING,
                    "leviWorldId", uuid));
            org.levimc.launcher.core.content.nbt.BedrockNbtWriter writer =
                    new org.levimc.launcher.core.content.nbt.BedrockNbtWriter();
            writer.setHeaderVersion(10);
            byte[] out = writer.writeToBytes(root);
            if (out != null && out.length > 8) {
                Log.i(TAG, "导出注入存档唯一标识: " + uuid);
                return out;
            }
            return original;
        } catch (Throwable e) {
            Log.w(TAG, "注入世界标识失败，原样打包", e);
            try {
                return java.nio.file.Files.readAllBytes(levelDat.toPath());
            } catch (IOException ignored) {
                return new byte[0];
            }
        }
    }

    private String createBackup(WorldItem world) throws IOException {
        // 备份为 .mcworld 压缩包，存到 Download/LeviLauncher/Backups/minecraftWorlds backups/
        // 按 版本号/世界名_seed种子 分类（与极限存档备份结构一致，但不进 Hardcore backups）。
        File worldDir = world.getFile();
        if (worldDir == null || !worldDir.exists()) {
            throw new IOException("无法确定存档文件夹");
        }

        String versionLabel = currentVersionLabel();
        File backupDir = new File(
                new File(LauncherStorage.getWorldBackupsDir(context), versionLabel),
                HardcoreBackupManager.backupDirName(world));
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("无法创建备份目录: " + backupDir.getAbsolutePath());
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String seedStr = world.getSeed() != 0 ? "seed" + world.getSeed() : "noseed";
        File zipFile = new File(backupDir,
                sanitize(world.getWorldName()) + "_" + seedStr + "_" + timestamp + ".mcworld");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zipDirectory(worldDir, "", zos);
        }
        return zipFile.getAbsolutePath();
    }

    /** 当前选中版本的显示名（用于备份分类），无版本时返回 unknown。 */
    private String currentVersionLabel() {
        try {
            org.levimc.launcher.core.versions.VersionManager vm =
                    org.levimc.launcher.core.versions.VersionManager.getIfInitialized();
            org.levimc.launcher.core.versions.GameVersion current = vm != null ? vm.getSelectedVersion() : null;
            String label = current != null ? current.displayName : null;
            if (label != null && !label.isEmpty()) return sanitize(label);
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static String sanitize(String name) {
        if (name == null) return "world";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "world" : s;
    }

    private boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return false;
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }

    public void shutdown() {
        executor.shutdown();
    }

    public void transferWorld(WorldItem world, File targetDirectory, WorldOperationCallback callback) {
        if (executor.isShutdown()) {
            callback.onError("WorldManager has been shut down");
            return;
        }
        executor.execute(() -> {
            try {
                if (targetDirectory == null) {
                    callback.onError("Target directory not available");
                    return;
                }

                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs();
                }

                File sourceDir = world.getFile();
                if (sourceDir == null || !sourceDir.exists()) {
                    callback.onError("Source world not found");
                    return;
                }

                if (sourceDir.getParentFile().equals(targetDirectory)) {
                    callback.onError("Content is already in this location");
                    return;
                }

                String worldName = generateUniqueWorldName(sourceDir.getName(), targetDirectory);
                File targetDir = new File(targetDirectory, worldName);

                copyDirectory(sourceDir, targetDir);
                if (!deleteDirectory(sourceDir)) {
                    callback.onError("World copied, but the original could not be removed");
                    return;
                }

                callback.onSuccess("World transferred successfully");

            } catch (Exception e) {
                Log.e(TAG, "Failed to transfer world", e);
                callback.onError("Transfer failed: " + e.getMessage());
            }
        });
    }
}
