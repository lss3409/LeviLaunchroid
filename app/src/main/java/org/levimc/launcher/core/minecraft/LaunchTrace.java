package org.levimc.launcher.core.minecraft;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class LaunchTrace {
    public static final String EXTRA_SESSION_ID = "org.levimc.launcher.extra.LAUNCH_SESSION_ID";
    public static final String EXTRA_STARTED_ELAPSED_MS = "org.levimc.launcher.extra.LAUNCH_STARTED_ELAPSED_MS";

    private static final String TAG = "MinecraftLaunchTrace";
    private static final String LOG_DIR_NAME = "launch_logs";
    private static final int MAX_LOG_FILES = 30;

    private static volatile Context appContext;

    /** 在 Application 启动时调用，用于把启动日志写入文件（供分享与崩溃分析）。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** 启动日志目录（filesDir/launch_logs）。 */
    public static File getLogDir(Context context) {
        return new File(context.getFilesDir(), LOG_DIR_NAME);
    }

    /** 最近一次启动的日志文件（按修改时间），无则返回 null。 */
    public static File getLatestLogFile(Context context) {
        File dir = getLogDir(context);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    /** 指定会话的日志文件（可能尚未写入），返回 File 对象（不保证存在）。 */
    public static File getLogFileFor(Context context, String sessionId) {
        return new File(getLogDir(context), "launch_" + sessionId + ".log");
    }

    private final String sessionId;
    private final long startedElapsedMs;

    private LaunchTrace(String sessionId, long startedElapsedMs) {
        this.sessionId = sessionId;
        this.startedElapsedMs = startedElapsedMs;
    }

    public static LaunchTrace create(Intent intent) {
        long now = SystemClock.elapsedRealtime();
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        if (intent != null) {
            intent.putExtra(EXTRA_SESSION_ID, sessionId);
            intent.putExtra(EXTRA_STARTED_ELAPSED_MS, now);
        }
        return new LaunchTrace(sessionId, now);
    }

    public static LaunchTrace ensure(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_SESSION_ID) && intent.hasExtra(EXTRA_STARTED_ELAPSED_MS)) {
            return fromIntent(intent);
        }
        return create(intent);
    }

    public static LaunchTrace fromIntent(Intent intent) {
        long now = SystemClock.elapsedRealtime();
        if (intent == null) {
            return new LaunchTrace("adhoc-" + now, now);
        }

        String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        long startedElapsedMs = intent.getLongExtra(EXTRA_STARTED_ELAPSED_MS, now);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = "adhoc-" + now;
            intent.putExtra(EXTRA_SESSION_ID, sessionId);
            intent.putExtra(EXTRA_STARTED_ELAPSED_MS, startedElapsedMs);
        }
        return new LaunchTrace(sessionId, startedElapsedMs);
    }

    public void mark(String stage) {
        mark(stage, null);
    }

    public synchronized void mark(String stage, String detail) {
        record(stage, detail, Log.DEBUG, false);
    }

    public void milestone(String stage) {
        milestone(stage, null);
    }

    public synchronized void milestone(String stage, String detail) {
        record(stage, detail, Log.INFO, true);
    }

    public synchronized void warning(String stage, String detail) {
        record(stage, detail, Log.WARN, true);
    }

    public void warning(String stage) {
        warning(stage, null);
    }

    public synchronized void error(String stage, String detail) {
        record(stage, detail, Log.ERROR, true);
    }

    public void error(String stage) {
        error(stage, null);
    }

    private void record(String stage, String detail, int level, boolean emit) {
        long now = SystemClock.elapsedRealtime();
        long totalMs = now - startedElapsedMs;

        if (!emit) {
            return;
        }

        String message = String.format(Locale.US, "[%s] +%dms %s",
                sessionId, totalMs, stage);
        if (detail != null && !detail.isEmpty()) {
            message += " - " + detail;
        }
        Log.println(level, TAG, message);
        appendToLogFile(message);
    }

    private void appendToLogFile(String message) {
        Context context = appContext;
        if (context == null) return;
        try {
            File dir = getLogDir(context);
            if (!dir.exists() && !dir.mkdirs()) return;
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            String line = fmt.format(new Date()) + " " + message + "\n";
            try (FileWriter writer = new FileWriter(
                    new File(dir, "launch_" + sessionId + ".log"), true)) {
                writer.append(line);
            }
            pruneOldLogs(dir);
        } catch (IOException | SecurityException ignored) {
        }
    }

    private static void pruneOldLogs(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null || files.length <= MAX_LOG_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_LOG_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    public String formatForUi(String message) {
        return message;
    }

    public long elapsedMs() {
        return SystemClock.elapsedRealtime() - startedElapsedMs;
    }

    public String getSessionId() {
        return sessionId;
    }
}
