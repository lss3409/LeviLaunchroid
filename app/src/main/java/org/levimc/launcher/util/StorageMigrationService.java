package org.levimc.launcher.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.activities.MainActivity;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class StorageMigrationService extends Service {
    public static final String ACTION_START = "org.levimc.launcher.action.START_STORAGE_MIGRATION";

    private static final String CHANNEL_ID = "storage_migration";
    private static final int NOTIFICATION_ID = 4207;
    private static final String PREFS_NAME = "storage_migration";
    private static final String KEY_RUNNING = "storage_migration_running";
    private static final String KEY_STATUS = "storage_migration_status";
    private static final String KEY_PERCENT = "storage_migration_percent";
    private static final String KEY_PROCESSED_FILES = "storage_migration_processed_files";
    private static final String KEY_TOTAL_FILES = "storage_migration_total_files";
    private static final String KEY_PROCESSED_BYTES = "storage_migration_processed_bytes";
    private static final String KEY_TOTAL_BYTES = "storage_migration_total_bytes";
    private static final String KEY_CURRENT_FILE = "storage_migration_current_file";
    private static final String KEY_ESTIMATED_REMAINING_MILLIS = "storage_migration_estimated_remaining_millis";
    private static final String KEY_ESTIMATED_COMPLETION_AT = "storage_migration_estimated_completion_at";
    private static final long NOTIFICATION_THROTTLE_MS = 1000L;

    private static volatile MigrationState latestState = MigrationState.idle();

    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<MigrationListener> listeners = new CopyOnWriteArrayList<>();

    private StorageMigrationManager migrationManager;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean migrationStarted;
    private long lastNotificationAt;
    private int lastNotificationPercent = -1;
    private long fileProgressStartedAtElapsed;

    public enum Status {
        IDLE,
        SCANNING,
        RUNNING,
        COMPLETED,
        PARTIAL,
        FAILED
    }

    public interface MigrationListener {
        void onMigrationStateChanged(MigrationState state);
    }

    public class LocalBinder extends Binder {
        public StorageMigrationService getService() {
            return StorageMigrationService.this;
        }
    }

    public static class MigrationState {
        public final Status status;
        public final int percent;
        public final int processedFiles;
        public final int totalFiles;
        public final long processedBytes;
        public final long totalBytes;
        public final String currentFile;
        public final int skippedFiles;
        public final int failedFiles;
        public final String errorMessage;
        public final long estimatedRemainingMillis;
        public final long estimatedCompletionAtMillis;

        private MigrationState(
                Status status,
                int percent,
                int processedFiles,
                int totalFiles,
                long processedBytes,
                long totalBytes,
                String currentFile,
                int skippedFiles,
                int failedFiles,
                String errorMessage,
                long estimatedRemainingMillis,
                long estimatedCompletionAtMillis
        ) {
            this.status = status;
            this.percent = Math.max(0, Math.min(100, percent));
            this.processedFiles = processedFiles;
            this.totalFiles = totalFiles;
            this.processedBytes = processedBytes;
            this.totalBytes = totalBytes;
            this.currentFile = currentFile == null ? "" : currentFile;
            this.skippedFiles = skippedFiles;
            this.failedFiles = failedFiles;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.estimatedRemainingMillis = estimatedRemainingMillis;
            this.estimatedCompletionAtMillis = estimatedCompletionAtMillis;
        }

        public boolean isActive() {
            return status == Status.SCANNING || status == Status.RUNNING;
        }

        public boolean isFinished() {
            return status == Status.COMPLETED || status == Status.PARTIAL || status == Status.FAILED;
        }

        static MigrationState idle() {
            return new MigrationState(Status.IDLE, 0, 0, 0, 0L, 0L, "", 0, 0, "", -1L, -1L);
        }

        static MigrationState scanning() {
            return new MigrationState(Status.SCANNING, 0, 0, 0, 0L, 0L, "", 0, 0, "", -1L, -1L);
        }

        static MigrationState running(
                StorageMigrationManager.MigrationProgress progress,
                long estimatedRemainingMillis,
                long estimatedCompletionAtMillis
        ) {
            return new MigrationState(
                    Status.RUNNING,
                    progress.percent,
                    progress.processedFiles,
                    progress.totalFiles,
                    progress.processedBytes,
                    progress.totalBytes,
                    progress.currentFile,
                    0,
                    0,
                    "",
                    estimatedRemainingMillis,
                    estimatedCompletionAtMillis
            );
        }

        static MigrationState completed(StorageMigrationManager.MigrationResult result) {
            Status status = result.failedFiles == 0 ? Status.COMPLETED : Status.PARTIAL;
            return new MigrationState(
                    status,
                    result.failedFiles == 0 ? 100 : 0,
                    result.totalFiles,
                    result.totalFiles,
                    result.totalBytes,
                    result.totalBytes,
                    "",
                    result.skippedFiles,
                    result.failedFiles,
                    "",
                    0L,
                    System.currentTimeMillis()
            );
        }

        static MigrationState failed(Exception error) {
            String message = error == null ? "" : error.getMessage();
            return new MigrationState(Status.FAILED, 0, 0, 0, 0L, 0L, "", 0, 0, message, -1L, -1L);
        }
    }

    public static boolean isMigrationRunning(Context context) {
        MigrationState state = latestState;
        if (state != null && state.isActive()) return true;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false);
    }

    public static void startMigration(Context context) {
        Context appContext = context.getApplicationContext();
        latestState = MigrationState.scanning();
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .putString(KEY_STATUS, Status.SCANNING.name())
                .putInt(KEY_PERCENT, 0)
                .putLong(KEY_ESTIMATED_REMAINING_MILLIS, -1L)
                .putLong(KEY_ESTIMATED_COMPLETION_AT, -1L)
                .apply();
        Intent intent = new Intent(context, StorageMigrationService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    public static MigrationState getLatestState() {
        return latestState;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        migrationManager = new StorageMigrationManager(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildProgressNotification(latestState));
        startMigrationIfNeeded();
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    public void addListener(MigrationListener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onMigrationStateChanged(latestState);
    }

    public void removeListener(MigrationListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    public MigrationState getCurrentState() {
        return latestState;
    }

    private void startMigrationIfNeeded() {
        if (migrationStarted) return;
        migrationStarted = true;
        fileProgressStartedAtElapsed = 0L;
        acquireWakeLock();
        updateState(MigrationState.scanning());
        persistRunning(true);
        updateForegroundNotification(latestState, true);

        migrationManager.startMigration(new StorageMigrationManager.MigrationCallback() {
            @Override
            public void onScanning() {
                updateState(MigrationState.scanning());
                updateForegroundNotification(latestState, true);
            }

            @Override
            public void onProgress(StorageMigrationManager.MigrationProgress progress) {
                updateState(createRunningState(progress));
                updateForegroundNotification(latestState, false);
            }

            @Override
            public void onCompleted(StorageMigrationManager.MigrationResult result) {
                MigrationState state = MigrationState.completed(result);
                updateState(state);
                persistRunning(false);
                releaseWakeLock();
                showFinalNotification(state);
                stopSelf();
            }

            @Override
            public void onFailed(Exception error) {
                MigrationState state = MigrationState.failed(error);
                updateState(state);
                persistRunning(false);
                releaseWakeLock();
                showFinalNotification(state);
                stopSelf();
            }
        });
    }

    private void updateState(MigrationState state) {
        latestState = state;
        persistState(state);
        for (MigrationListener listener : listeners) {
            listener.onMigrationStateChanged(state);
        }
    }

    private void persistRunning(boolean running) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, running)
                .apply();
    }

    private void persistState(MigrationState state) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, state.status.name())
                .putInt(KEY_PERCENT, state.percent)
                .putInt(KEY_PROCESSED_FILES, state.processedFiles)
                .putInt(KEY_TOTAL_FILES, state.totalFiles)
                .putLong(KEY_PROCESSED_BYTES, state.processedBytes)
                .putLong(KEY_TOTAL_BYTES, state.totalBytes)
                .putString(KEY_CURRENT_FILE, state.currentFile)
                .putLong(KEY_ESTIMATED_REMAINING_MILLIS, state.estimatedRemainingMillis)
                .putLong(KEY_ESTIMATED_COMPLETION_AT, state.estimatedCompletionAtMillis);
        editor.putBoolean(KEY_RUNNING, state.isActive());
        editor.apply();
    }

    private void updateForegroundNotification(MigrationState state, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force
                && state.percent == lastNotificationPercent
                && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) {
            return;
        }
        lastNotificationAt = now;
        lastNotificationPercent = state.percent;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildProgressNotification(state));
        }
    }

    private void showFinalNotification(MigrationState state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildFinalNotification(state));
        }
    }

    private Notification buildProgressNotification(MigrationState state) {
        Notification.Builder builder = newNotificationBuilder()
                .setSmallIcon(R.drawable.ic_leaf_logo_mono)
                .setContentTitle(getString(R.string.storage_migration_progress_title))
                .setContentText(getNotificationText(state))
                .setStyle(new Notification.BigTextStyle().bigText(getNotificationText(state)))
                .setContentIntent(createContentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (state.status == Status.SCANNING || state.status == Status.IDLE) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, state.percent, false);
        }

        return builder.build();
    }

    private Notification buildFinalNotification(MigrationState state) {
        String title;
        String text;
        if (state.status == Status.COMPLETED) {
            title = getString(R.string.storage_migration_completed_title);
            text = getString(
                    R.string.storage_migration_completed_message,
                    state.totalFiles,
                    formatBytes(state.totalBytes),
                    state.skippedFiles
            );
        } else if (state.status == Status.PARTIAL) {
            title = getString(R.string.storage_migration_partial_title);
            text = getString(
                    R.string.storage_migration_partial_message,
                    state.failedFiles,
                    state.totalFiles
            );
        } else {
            title = getString(R.string.storage_migration_failed_title);
            text = getString(R.string.storage_migration_failed_message, state.errorMessage);
        }

        return newNotificationBuilder()
                .setSmallIcon(R.drawable.ic_leaf_logo_mono)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(createContentIntent())
                .setOngoing(false)
                .setAutoCancel(true)
                .build();
    }

    private Notification.Builder newNotificationBuilder() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder;
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private String getNotificationText(MigrationState state) {
        if (state.status == Status.RUNNING) {
            String progress = getString(
                    R.string.storage_migration_progress_detail,
                    state.processedFiles,
                    state.totalFiles,
                    shortenPath(state.currentFile)
            );
            return progress + "\n" + getEtaText(state);
        }
        return getString(R.string.storage_migration_scanning) + "\n" + getString(R.string.storage_migration_eta_pending);
    }

    private MigrationState createRunningState(StorageMigrationManager.MigrationProgress progress) {
        if (fileProgressStartedAtElapsed <= 0L) {
            fileProgressStartedAtElapsed = SystemClock.elapsedRealtime();
        }
        long remainingMillis = estimateRemainingMillis(progress);
        long completionAtMillis = remainingMillis >= 0L
                ? System.currentTimeMillis() + remainingMillis
                : -1L;
        return MigrationState.running(progress, remainingMillis, completionAtMillis);
    }

    private long estimateRemainingMillis(StorageMigrationManager.MigrationProgress progress) {
        if (fileProgressStartedAtElapsed <= 0L) return -1L;
        if (progress.totalFiles <= 0 || progress.processedFiles <= 0) return -1L;
        int remainingFiles = Math.max(0, progress.totalFiles - progress.processedFiles);
        if (remainingFiles == 0) return 0L;
        long elapsed = SystemClock.elapsedRealtime() - fileProgressStartedAtElapsed;
        if (elapsed < 3000L) return -1L;
        double filesPerMillis = progress.processedFiles / (double) elapsed;
        if (filesPerMillis <= 0.0d) return -1L;
        return Math.max(0L, (long) Math.ceil(remainingFiles / filesPerMillis));
    }

    private String getEtaText(MigrationState state) {
        if (state.estimatedRemainingMillis < 0L || state.estimatedCompletionAtMillis <= 0L) {
            return getString(R.string.storage_migration_eta_pending);
        }
        String remaining = formatDuration(state.estimatedRemainingMillis);
        String completionTime = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                .format(new Date(state.estimatedCompletionAtMillis));
        return getString(R.string.storage_migration_eta_detail, remaining, completionTime);
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(1L, Math.round(millis / 1000.0d));
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0L) {
            return getString(R.string.storage_migration_duration_hours_minutes, hours, minutes);
        }
        if (minutes > 0L) {
            return getString(R.string.storage_migration_duration_minutes_seconds, minutes, remainingSeconds);
        }
        return getString(R.string.storage_migration_duration_seconds, remainingSeconds);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.storage_migration_progress_title),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) return;
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":StorageMigration"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        } finally {
            wakeLock = null;
        }
    }

    private String shortenPath(String path) {
        if (path == null || path.isEmpty()) return "";
        final int max = 48;
        return path.length() <= max ? path : "..." + path.substring(path.length() - max);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0);
    }
}
