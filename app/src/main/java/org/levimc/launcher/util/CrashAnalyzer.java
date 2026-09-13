package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import org.levimc.launcher.core.minecraft.LaunchTrace;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃自动分析（参照 PCL 的思路）：
 * 游戏启动时记录会话与启用的模组快照；如果游戏没有走正常退出流程就消失
 * （进程被杀/原生崩溃），启动器重启后生成中文分析报告，提示可能的原因。
 */
public final class CrashAnalyzer {
    private static final String TAG = "CrashAnalyzer";
    private static final String PREFS = "crash_analyzer";
    private static final String KEY_SESSION_STARTED = "session_started";
    private static final String KEY_NORMAL_EXIT = "normal_exit";
    private static final String KEY_MODS = "session_mods";
    private static final String KEY_VERSION = "session_version";
    private static final String KEY_STARTED_AT = "session_started_at";

    /** 崩溃分类：模组加载期 / 启动期（未进游戏）/ 游玩中。 */
    public static final int CATEGORY_MOD_LOADING = 0;
    public static final int CATEGORY_LAUNCH_PHASE = 1;
    public static final int CATEGORY_IN_GAME = 2;

    public static class CrashReport {
        public String lastStage;      // 崩溃前最后一条启动里程碑（阶段名）
        public String lastLine;       // 最后一条日志原文
        public String modNames;       // 启用模组名（顿号分隔）
        public String versionLabel;   // 版本号显示名
        public boolean nativeDump;    // 检测到原生崩溃转储
        public int category;          // CATEGORY_*
        public File logFile;          // 启动日志文件
    }

    private CrashAnalyzer() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 游戏启动时调用：记录会话开始与启用模组快照。 */
    public static void onGameLaunchStarted(Context context, List<String> enabledModNames, String versionLabel) {
        StringBuilder sb = new StringBuilder();
        if (enabledModNames != null) {
            for (String name : enabledModNames) {
                if (sb.length() > 0) sb.append('、');
                sb.append(name);
            }
        }
        prefs(context).edit()
                .putBoolean(KEY_SESSION_STARTED, true)
                .putBoolean(KEY_NORMAL_EXIT, false)
                .putString(KEY_MODS, sb.toString())
                .putString(KEY_VERSION, versionLabel == null ? "" : versionLabel)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .apply();
    }

    /** 游戏走正常退出流程时调用。 */
    public static void onGameExitNormal(Context context) {
        prefs(context).edit().putBoolean(KEY_NORMAL_EXIT, true).apply();
    }

    /**
     * 启动器重启后调用（严格模式）：仅当检测到游戏原生崩溃转储（.dmp）时才返回报告；
     * 后台被杀、进程被回收等没有转储的异常消失不弹窗。返回后消费标记。
     */
    @Nullable
    public static CrashReport consumeCrashReport(Context context) {
        SharedPreferences p = prefs(context);
        boolean started = p.getBoolean(KEY_SESSION_STARTED, false);
        boolean normalExit = p.getBoolean(KEY_NORMAL_EXIT, true);
        if (!started) return null;
        long startedAt = p.getLong(KEY_STARTED_AT, 0L);

        CrashReport report = new CrashReport();
        report.modNames = p.getString(KEY_MODS, "");
        report.versionLabel = p.getString(KEY_VERSION, "");

        report.logFile = LaunchTrace.getLatestLogFile(context);
        report.lastLine = "";
        report.lastStage = "";
        String logContent = "";
        if (report.logFile != null) {
            logContent = readLogContent(report.logFile);
            String last = lastLineOf(logContent);
            if (last != null) {
                report.lastLine = last;
                report.lastStage = extractStage(last);
            }
        }
        report.nativeDump = findRecentNativeDump(context, startedAt);
        report.category = categorize(logContent);

        // 消费标记，避免重复弹窗
        p.edit().putBoolean(KEY_SESSION_STARTED, false).apply();

        // 严格模式：没有原生崩溃转储说明只是后台被杀/被回收，不算崩溃，不弹窗
        if (!report.nativeDump) {
            Log.i(TAG, "Session ended abnormally without native dump; skipping dialog (strict mode)");
            return null;
        }
        return report;
    }

    /** 按日志里程碑对崩溃分类：模组加载期 / 启动期 / 游玩中。 */
    private static int categorize(String logContent) {
        boolean enteredGame = logContent.contains("Mojang MainActivity super.onCreate finished");
        if (enteredGame) return CATEGORY_IN_GAME;
        boolean modStarted = logContent.contains("Native mod enable started");
        boolean modFinished = logContent.contains("Native mod enable finished");
        if (modStarted && !modFinished) return CATEGORY_MOD_LOADING;
        return CATEGORY_LAUNCH_PHASE;
    }

    /** 读取日志全文（上限 256KB，避免异常大文件）。 */
    private static String readLogContent(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
                if (sb.length() > 256 * 1024) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read launch log", e);
        }
        return sb.toString();
    }

    private static String lastLineOf(String content) {
        if (content == null) return null;
        String[] lines = content.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) return lines[i];
        }
        return null;
    }

    /** 从日志行提取阶段名：去掉时间戳与 [session] +Nms 前缀。 */
    private static String extractStage(String line) {
        if (line == null) return "";
        int idx = line.indexOf("] ");
        if (idx < 0) return line;
        String rest = line.substring(idx + 2);
        int msIdx = rest.indexOf("ms ");
        if (msIdx > 0) {
            rest = rest.substring(msIdx + 3);
        }
        return rest;
    }

    /** 查找会话开始后产生的原生崩溃转储（.dmp）。 */
    private static boolean findRecentNativeDump(Context context, long after) {
        long threshold = after - 5000L; // 允许 5 秒误差
        List<File> dirs = new ArrayList<>();
        try {
            dirs.add(LauncherStorage.getCrashLogsDir(context));
            File minecraftRoot = LauncherStorage.getMinecraftRoot(context);
            File[] profiles = minecraftRoot.listFiles(File::isDirectory);
            if (profiles != null) {
                for (File profile : profiles) {
                    File crash = new File(profile, "crash");
                    if (crash.isDirectory()) dirs.add(crash);
                }
            }
        } catch (Exception ignored) {
        }
        for (File dir : dirs) {
            File[] dumps = dir.listFiles((d, name) -> name.endsWith(".dmp") || name.endsWith(".dmp.tmp"));
            if (dumps != null) {
                for (File dump : dumps) {
                    if (dump.lastModified() >= threshold) return true;
                }
            }
        }
        return false;
    }
}
