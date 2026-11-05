package org.levimc.launcher.core.content.leveldb;

import org.levimc.launcher.core.content.nbt.NativeNbt;
import org.levimc.launcher.core.content.world.WorldSession;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LevelDBManager {
    public interface LoadCallback {
        void onProgress(int current, int total);

        void onComplete(List<LevelDBEntry> entries);

        void onError(String error);
    }

    public interface StructureExportCallback {
        void onProgress(int current, int total, String structureName);

        void onComplete(int exportedCount, String outputPath);

        void onError(String error);
    }

    private final File dbDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<LevelDBEntry> entries = new ArrayList<>();

    public LevelDBManager(File world) {
        dbDir = new File(world, "db");
    }

    public void loadDatabase(LoadCallback callback) {
<<<<<<< HEAD
        executor.execute(() -> {
            try {
                if (!dbDir.exists() || !dbDir.isDirectory()) {
                    callback.onError("Database directory not found: " + dbDir.getAbsolutePath());
                    return;
                }

                LevelDBReader reader = new LevelDBReader(dbDir);
                // 结构提取只读结构条目：此前 readAllEntries 全量读 183MB
                // 大世界（56.9 万条目）→ 512MB 堆 OOM 崩溃（实测 TK 纯净
                // 生电提取结构必崩）。v0.4.202：改用前缀定位模式——
                // readEntries 带过滤虽然内存降到 KB 级，但仍是全量扫所有
                // sst（每 sst 逐 block 遍历），大世界转圈良久才报「未找到
                // 结构」；readEntriesByPrefixAll 二分定位前缀起点、读到
                // 前缀结束即停（chunk 读同款路径），只碰 1-2 个 data block
                byte[] prefix = "structuretemplate_".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                entries = reader.readEntriesByPrefixAll(prefix);
                reader.close();

                Log.d(TAG, "Loaded " + entries.size() + " entries from database");

                callback.onProgress(50, 100);

                categorizeEntries();

                Log.d(TAG, "Found " + structureEntries.size() + " structures");

                callback.onProgress(100, 100);
                callback.onComplete(entries);

            } catch (Exception e) {
                Log.e(TAG, "Failed to load database", e);
                callback.onError("Failed to load database: " + e.getMessage());
            }
        });
    }

    private void categorizeEntries() {
        structureEntries.clear();

        for (LevelDBEntry entry : entries) {
            LevelDBKey key = entry.getKey();
            if (key.isStructureKey()) {
                structureEntries.add(entry);
                String structureId = key.getStructureId();
                byte[] value = entry.getValue();
                Log.d(TAG, "Found structure: " + structureId +
                      " (value size: " + (value != null ? value.length : 0) + " bytes)");
            }
        }

        Log.d(TAG, "Categorization complete: " + structureEntries.size() + " structures found");
=======
        executor.execute(
                () -> {
                    try (LevelDBReader reader = new LevelDBReader(dbDir)) {
                        entries =
                                reader.readEntries(
                                        "structuretemplate_".getBytes(StandardCharsets.UTF_8),
                                        64L * 1024 * 1024);
                        callback.onProgress(100, 100);
                        callback.onComplete(new ArrayList<>(entries));
                    } catch (Exception | LinkageError ex) {
                        callback.onError(ex.getMessage());
                    }
                });
>>>>>>> v1.6.0
    }

    public List<LevelDBEntry> getStructureEntries() {
        return new ArrayList<>(entries);
    }

    public void exportAllStructures(File output, StructureExportCallback callback) {
        executor.execute(
                () -> {
                    try {
                        int done = 0;
                        for (LevelDBEntry entry : entries) {
                            if (Thread.currentThread().isInterrupted())
                                throw new java.io.InterruptedIOException();
                            String id = entry.getKey().getStructureId();
                            String[] parts = id.split(":", 2);
                            String namespace = parts.length == 2 ? parts[0] : "mystructure",
                                    name = parts.length == 2 ? parts[1] : parts[0];
                            File folder = new File(output, "structures/" + safe(namespace));
                            if (!folder.isDirectory() && !folder.mkdirs())
                                throw new java.io.IOException("Cannot create export folder");
                            byte[] value = entry.getValue();
                            NativeNbt.decode(value, 0, NativeNbt.LITTLE_ENDIAN);
                            WorldSession.atomic(
                                    new File(folder, safe(name) + ".mcstructure"), value);
                            callback.onProgress(++done, entries.size(), id);
                        }
                        callback.onComplete(done, new File(output, "structures").getAbsolutePath());
                    } catch (Exception | LinkageError ex) {
                        callback.onError(ex.getMessage());
                    }
                });
    }

    private static String safe(String value) {
        String name = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isBlank() || name.equals(".") || name.equals("..") ? "structure" : name;
    }

    public void shutdown() {
        executor.shutdownNow();
        entries = new ArrayList<>();
    }
}
