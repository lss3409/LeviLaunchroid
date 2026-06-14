package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class StorageMigrationManager {
    private static final String PREFS_NAME = "storage_migration";
    private static final String KEY_COMPLETED = "storage_migration_completed";
    private static final String KEY_SOURCE_PATH = "storage_migration_source_path";
    private static final String KEY_COMPLETED_AT = "storage_migration_completed_at";
    private static final String KEY_TOTAL_FILES = "storage_migration_total_files";
    private static final String KEY_TOTAL_BYTES = "storage_migration_total_bytes";
    private static final int BUFFER_SIZE = 262144;
    private static final long UI_THROTTLE_MS = 100L;

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelled;
    private volatile boolean paused;

    public interface MigrationCallback {
        void onScanning();

        void onProgress(MigrationProgress progress);

        void onCompleted(MigrationResult result);

        void onFailed(Exception error);
    }

    public static class MigrationProgress {
        public final int percent;
        public final int processedFiles;
        public final int totalFiles;
        public final long processedBytes;
        public final long totalBytes;
        public final String currentFile;

        MigrationProgress(
                int percent,
                int processedFiles,
                int totalFiles,
                long processedBytes,
                long totalBytes,
                String currentFile
        ) {
            this.percent = percent;
            this.processedFiles = processedFiles;
            this.totalFiles = totalFiles;
            this.processedBytes = processedBytes;
            this.totalBytes = totalBytes;
            this.currentFile = currentFile;
        }
    }

    public static class MigrationResult {
        public final int totalFiles;
        public final long totalBytes;
        public final int skippedFiles;
        public final int failedFiles;

        MigrationResult(int totalFiles, long totalBytes, int skippedFiles, int failedFiles) {
            this.totalFiles = totalFiles;
            this.totalBytes = totalBytes;
            this.skippedFiles = skippedFiles;
            this.failedFiles = failedFiles;
        }
    }

    private static class MigrationFile {
        final File source;
        final File target;
        final String relativePath;
        final long size;
        final long lastModified;

        MigrationFile(File source, File target, String relativePath) {
            this.source = source;
            this.target = target;
            this.relativePath = relativePath;
            this.size = Math.max(0L, source.length());
            this.lastModified = source.lastModified();
        }
    }

    public StorageMigrationManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean shouldOfferMigration() {
        if (isMigrationCompleted() || running.get()) return false;
        File legacyRoot = LauncherStorage.getLegacyRoot();
        if (!LauncherStorage.shouldUseLegacyRoot(context)) return false;
        File targetRoot = LauncherStorage.getTargetAppRoot(context);
        if (samePath(legacyRoot, targetRoot)) return false;
        return true;
    }

    public boolean isMigrationCompleted() {
        return prefs.getBoolean(KEY_COMPLETED, false);
    }

    public boolean canReadLegacyRoot() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            return false;
        }
        File legacyRoot = LauncherStorage.getLegacyRoot();
        return legacyRoot.exists() && legacyRoot.canRead();
    }

    public void startMigration(MigrationCallback callback) {
        if (!running.compareAndSet(false, true)) return;
        cancelled = false;
        paused = false;
        executor.execute(() -> {
            try {
                if (!canReadLegacyRoot()) {
                    throw new IOException("Legacy storage permission is not available");
                }
                if (callback != null) callback.onScanning();

                File sourceRoot = LauncherStorage.getLegacyRoot();
                File targetRoot = LauncherStorage.getTargetAppRoot(context);
                if (!LauncherStorage.ensureDir(targetRoot)) {
                    throw new IOException("Cannot create target directory: " + targetRoot.getAbsolutePath());
                }
                if (!targetRoot.canWrite()) {
                    throw new IOException("Target directory is not writable: " + targetRoot.getAbsolutePath());
                }

                List<MigrationFile> files = scanFiles(sourceRoot, targetRoot);
                long scannedBytes = 0L;
                for (MigrationFile file : files) scannedBytes += file.size;
                if (files.isEmpty()) {
                    MigrationResult result = new MigrationResult(0, 0L, 0, 0);
                    markCompleted(sourceRoot, result);
                    if (callback != null) callback.onCompleted(result);
                    return;
                }
                final long totalBytes = scannedBytes;

                int[] failedFiles = {0};
                int skippedFiles = 0;
                int[] processedFiles = {0};
                long[] processedBytes = {0L};
                long[] lastUiAt = {0L};
                int[] lastPercent = {-1};
                byte[] buffer = new byte[BUFFER_SIZE];

                for (MigrationFile file : files) {
                    throwIfStopped();

                    if (isAlreadyMigrated(file)) {
                        skippedFiles++;
                        processedFiles[0]++;
                        processedBytes[0] += file.size;
                        ProgressState state = new ProgressState(processedFiles[0], files.size(), processedBytes[0], totalBytes, file.relativePath);
                        maybePostProgress(callback, state, lastUiAt, lastPercent);
                        continue;
                    }

                    try {
                        final int processedFilesBeforeCopy = processedFiles[0];
                        final long processedBytesBeforeCopy = processedBytes[0];
                        copyFile(file, buffer, bytesCopied -> {
                            ProgressState state = new ProgressState(
                                    processedFilesBeforeCopy,
                                    files.size(),
                                    processedBytesBeforeCopy + bytesCopied,
                                    totalBytes,
                                    file.relativePath
                            );
                            maybePostProgress(callback, state, lastUiAt, lastPercent);
                        });
                    } catch (MigrationPausedException error) {
                        throw error;
                    } catch (IOException error) {
                        failedFiles[0]++;
                    }

                    processedFiles[0]++;
                    processedBytes[0] += file.size;
                    ProgressState state = new ProgressState(processedFiles[0], files.size(), processedBytes[0], totalBytes, file.relativePath);
                    maybePostProgress(callback, state, lastUiAt, lastPercent);
                }

                MigrationResult result = new MigrationResult(files.size(), totalBytes, skippedFiles, failedFiles[0]);
                if (failedFiles[0] == 0) {
                    markCompleted(sourceRoot, result);
                }
                if (callback != null) callback.onCompleted(result);
            } catch (Exception error) {
                if (callback != null) callback.onFailed(error);
            } finally {
                running.set(false);
            }
        });
    }

    public void cancel() {
        cancelled = true;
    }

    public void pause() {
        paused = true;
    }

    private List<MigrationFile> scanFiles(File sourceRoot, File targetRoot) throws IOException {
        List<MigrationFile> result = new ArrayList<>();
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(sourceRoot);
        String sourceBase = sourceRoot.getCanonicalPath();

        while (!stack.isEmpty()) {
            throwIfStopped();
            File current = stack.pop();
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                throwIfStopped();
                if (child.isDirectory()) {
                    stack.push(child);
                    continue;
                }
                if (!child.isFile()) continue;

                String childPath = child.getCanonicalPath();
                if (!childPath.startsWith(sourceBase)) continue;
                String relative = childPath.substring(sourceBase.length());
                while (relative.startsWith(File.separator)) {
                    relative = relative.substring(1);
                }
                if (relative.isEmpty()) continue;
                File target = new File(targetRoot, relative);
                result.add(new MigrationFile(child, target, relative.replace(File.separatorChar, '/')));
            }
        }

        return result;
    }

    private boolean isAlreadyMigrated(MigrationFile file) {
        return file.target.exists()
                && file.target.isFile()
                && file.target.length() == file.size;
    }

    private void copyFile(MigrationFile file, byte[] buffer, CopyProgress progress) throws IOException {
        File parent = file.target.getParentFile();
        if (parent != null && !LauncherStorage.ensureDir(parent)) {
            throw new IOException("Cannot create directory: " + parent.getAbsolutePath());
        }

        File temp = new File(file.target.getAbsolutePath() + ".tmp");
        if (temp.exists() && !temp.delete()) {
            throw new IOException("Cannot reset temp file: " + temp.getAbsolutePath());
        }

        long copied = 0L;
        boolean moved = false;
        try {
            try (FileInputStream in = new FileInputStream(file.source);
                 FileOutputStream out = new FileOutputStream(temp)) {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    throwIfStopped();
                    out.write(buffer, 0, len);
                    copied += len;
                    if (progress != null) progress.onCopied(copied);
                }
                out.getFD().sync();
            }

            if (temp.length() != file.size) {
                throw new IOException(String.format(Locale.US, "Size mismatch for %s", file.relativePath));
            }

            if (file.target.exists() && !file.target.delete()) {
                throw new IOException("Cannot replace target file: " + file.target.getAbsolutePath());
            }
            if (!temp.renameTo(file.target)) {
                throw new IOException("Cannot move temp file into place: " + file.target.getAbsolutePath());
            }
            moved = true;
            if (file.lastModified > 0L) {
                file.target.setLastModified(file.lastModified);
            }
        } finally {
            if (!moved && temp.exists()) {
                temp.delete();
            }
        }
    }

    private void throwIfStopped() throws IOException {
        if (paused) throw new MigrationPausedException();
        if (cancelled) throw new IOException("Migration cancelled");
    }

    public static class MigrationPausedException extends IOException {
        MigrationPausedException() {
            super("Migration paused");
        }
    }

    private void maybePostProgress(
            MigrationCallback callback,
            ProgressState state,
            long[] lastUiAt,
            int[] lastPercent
    ) {
        long now = SystemClock.uptimeMillis();
        int percent = state.percent();
        if (now - lastUiAt[0] >= UI_THROTTLE_MS || percent != lastPercent[0] || percent >= 100) {
            postProgress(callback, state);
            lastUiAt[0] = now;
            lastPercent[0] = percent;
        }
    }

    private void postProgress(MigrationCallback callback, ProgressState state) {
        if (callback == null) return;
        callback.onProgress(new MigrationProgress(
                state.percent(),
                state.processedFiles,
                state.totalFiles,
                state.processedBytes,
                state.totalBytes,
                state.currentFile
        ));
    }

    private void markCompleted(File sourceRoot, MigrationResult result) {
        prefs.edit()
                .putString(KEY_SOURCE_PATH, sourceRoot.getAbsolutePath())
                .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
                .putInt(KEY_TOTAL_FILES, result.totalFiles)
                .putLong(KEY_TOTAL_BYTES, result.totalBytes)
                .apply();
        LauncherStorage.markMigrationCompleted(context);
        LauncherStorage.invalidateCache();
    }

    private static boolean samePath(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private interface CopyProgress {
        void onCopied(long bytesCopied);
    }

    private static class ProgressState {
        final int processedFiles;
        final int totalFiles;
        final long processedBytes;
        final long totalBytes;
        final String currentFile;

        ProgressState(int processedFiles, int totalFiles, long processedBytes, long totalBytes, String currentFile) {
            this.processedFiles = processedFiles;
            this.totalFiles = totalFiles;
            this.processedBytes = processedBytes;
            this.totalBytes = totalBytes;
            this.currentFile = currentFile;
        }

        int percent() {
            if (totalFiles <= 0) return 100;
            int filePercent = (int) ((processedFiles * 100L) / totalFiles);
            if (totalBytes <= 0L) return Math.max(0, Math.min(100, filePercent));
            int bytePercent = (int) ((Math.max(0L, Math.min(processedBytes, totalBytes)) * 100L) / totalBytes);
            return Math.max(0, Math.min(100, (filePercent + bytePercent) / 2));
        }
    }
}
