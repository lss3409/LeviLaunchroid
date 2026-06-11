package org.levimc.launcher.core.minecraft;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;

public final class LaunchTrace {
    public static final String EXTRA_SESSION_ID = "org.levimc.launcher.extra.LAUNCH_SESSION_ID";
    public static final String EXTRA_STARTED_ELAPSED_MS = "org.levimc.launcher.extra.LAUNCH_STARTED_ELAPSED_MS";

    private static final String TAG = "MinecraftLaunchTrace";

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
