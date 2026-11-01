package org.levimc.launcher.core.updates;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v711：启动器更新检查（ZL2 同款方案）——版本信息 JSON 托管在
 * GitHub Releases（仓库 lss3409/levi-updates 已公开，/latest/download/ 直链）。
 * v712：解析 files[].uri 直链下载 APK 并跳系统安装器。
 * 自动检查每天一次（限频），设置页手动检查不限频。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    /** v712：版本信息源——GitHub Releases（私有仓库 release 附件匿名不可下，
     *  仓库已公开；/latest/download/ 固定重定向到最新版附件）。
     *  v0.0.1：国内网络慢——update.json 与 APK 下载都走多源回退
     *  （GitHub 直链 → ghproxy 系列镜像，顺序重试）。 */
    private static final String UPDATE_URL =
            "https://github.com/lss3409/levi-updates/releases/latest/download/update.json";
    /** 镜像前缀（前缀模式：镜像域名 + 完整 GitHub URL）。可用性变化快，失败自动跳过。 */
    private static final String[] MIRRORS = {
            "https://ghproxy.net/",
            "https://gh-proxy.org/",
            "https://gh.zwy.one/",
            "https://mirror.ghproxy.com/",
    };
    private static final String PREFS = "levimc_update_check";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 3600 * 1000;
    private static final String CHANNEL_UPDATE = "update_download";
    private static final int NOTIF_DOWNLOAD_ID = 7100;
    private static volatile long lastNotifUpdate;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-check");
        t.setDaemon(true);
        return t;
    });

    /** 更新信息。 */
    public static class Update {
        public long code;
        public String version;
        public String body;
        public String cloudDriveLink; // 网盘分享链接（空则无）
        public final java.util.List<String> apkUrls = new java.util.ArrayList<>(); // APK 直链候选
    }

    /** 检查结果三态（v0.0.5：区分「网络失败」和「已是最新」，不再混为 null）。 */
    public static final int RESULT_FAILED = 0;
    public static final int RESULT_UP_TO_DATE = 1;
    public static final int RESULT_UPDATE = 2;

    public interface Callback {
        void onResult(int status, Update update);
    }

    private UpdateChecker() {
    }

    /** 检查更新。manual=true 忽略限频（设置页手动检查）。 */
    public static void checkAsync(Context ctx, boolean manual, Callback cb) {
        final Context app = ctx.getApplicationContext();
        if (!manual) {
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long last = sp.getLong(KEY_LAST_CHECK, 0);
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) {
                return;
            }
            sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        }
        pool.execute(() -> {
            try {
                Update u = check(app);
                int status = u == null ? RESULT_UP_TO_DATE : RESULT_UPDATE;
                // v0.4.205：红点标志持久化（底栏设置 tab + 基础设置检查更新卡
                // 小红点；检测到新版点亮，检查为最新时熄灭）
                if (status != RESULT_FAILED) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean("update_dot", status == RESULT_UPDATE).apply();
                }
                main.post(() -> cb.onResult(status, u));
            } catch (Throwable t) {
                Log.w(TAG, "检查更新失败: " + t.getClass().getSimpleName());
                main.post(() -> cb.onResult(RESULT_FAILED, null));
            }
        });
    }

    private static Update check(Context app) {
        // v0.0.5：update.json 加分钟级时间戳 query——GitHub latest 重定向
        // 会被 CDN 缓存，旧缓存曾导致「已是最新」误判（v0.0.4 实锤疑似），
        // 每次检查换 query 绕过缓存命中旧内容
        String base = UPDATE_URL + "?t=" + (System.currentTimeMillis() / 60_000);
        // v0.0.24：多源竞速——GitHub 直链被墙时，串行回退要等满
        // connect/read 超时（约 13s/源）才轮到镜像，用户体感「检查更新
        // 很久没反应」。改为所有候选源并发请求，先成功者返回。
        java.util.List<String> candidates = buildCandidates(base);
        java.util.concurrent.ExecutorService race =
                java.util.concurrent.Executors.newFixedThreadPool(candidates.size());
        try {
            java.util.concurrent.ExecutorCompletionService<Update> ecs =
                    new java.util.concurrent.ExecutorCompletionService<>(race);
            int submitted = 0;
            for (String url : candidates) {
                ecs.submit(() -> checkOnce(app, url));
                submitted++;
            }
            int failures = 0;
            Update result = null;
            while (failures < submitted) {
                try {
                    result = ecs.take().get();
                    Log.i(TAG, "竞速成功（" + (submitted - failures) + " 源在跑）");
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    failures++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (failures == submitted) {
                throw new IllegalStateException("all update sources failed");
            }
            return result;
        } finally {
            race.shutdownNow();
        }
    }

    /** 单源检查；网络/解析失败抛异常（区别于「无更新」返回 null）。 */
    private static Update checkOnce(Context app, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(8_000);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new java.io.IOException("HTTP " + code);
        }
        String body = readAll(conn.getInputStream());
        conn.disconnect();
        Log.i(TAG, "源成功: " + url + " (" + body.length() + "B)");
        JSONObject json = new JSONObject(body);
        long remoteCode = json.optLong("code", 0);
        String remoteVersion = json.optString("version", "");

        // v0.0.1：比较基准改为 versionCode（构建时间戳递增），
        // 与显示名 v0.0.1/v0.0.2 解耦，任意版本号规范都能正确判新
        long localCode = 0;
        try {
            android.content.pm.PackageInfo pi = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0);
            if (pi != null) {
                localCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
            }
        } catch (Throwable ignored) {
        }
        if (remoteCode <= localCode) {
            Log.i(TAG, "已是最新: remote=" + remoteCode + " local=" + localCode);
            return null;
        }
        Update u = new Update();
        u.code = remoteCode;
        u.version = remoteVersion;
        u.body = json.optString("body", "");
        JSONArray drives = json.optJSONArray("cloud_drives");
        if (drives != null && drives.length() > 0) {
            JSONObject first = drives.optJSONObject(0);
            if (first != null) {
                u.cloudDriveLink = first.optString("link", "");
            }
        }
        if (u.cloudDriveLink == null || u.cloudDriveLink.isEmpty()) {
            u.cloudDriveLink = json.optString("default_cloud_drive", "");
        }
        JSONArray files = json.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                String uri = files.optJSONObject(i).optString("uri", "");
                if (!uri.isEmpty()) {
                    u.apkUrls.add(uri);
                }
            }
        }
        return u;
    }

    /** 直链 + 镜像前缀候选列表（前缀模式：镜像域名 + 完整 URL）。 */
    private static java.util.List<String> buildCandidates(String base) {
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(base);
        for (String m : MIRRORS) {
            list.add(m + base);
        }
        return list;
    }

    /** 更新弹窗（v0.4.143 readest 风格）：应用图标 + 粗体「发现新版本」+
     *  版本/当前版本描述 + 灰底圆角更新日志卡片（版本号标题+圆点列表）+
     *  下载并安装/稍后。 */
    public static void showUpdateDialog(Activity activity, Update u) {
        if (u == null || u.version.isEmpty()) {
            return;
        }
        // v0.0.12：网络慢导致回调晚到时 Activity 可能已销毁，
        // 弹窗会 BadTokenException 崩溃（vivo 手机检查更新实测）
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int dp = (int) density;
        int accent = new org.levimc.launcher.util.PersonalizationManager(activity)
                .getAccentColor();
        if (accent == 0) {
            accent = activity.getResources().getColor(R.color.primary, activity.getTheme());
        }
        int onSurface = activity.getResources().getColor(R.color.on_surface, activity.getTheme());
        int textSec = activity.getResources().getColor(R.color.text_secondary, activity.getTheme());

        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(20 * dp, 8 * dp, 20 * dp, 0);
        // v0.4.146：更新日志滚动区引用（下载时隐藏切换为进度区）。
        // v0.4.211：改存 logBox——日志区不再包内层 ScrollView（固定
        // 0.36 屏高内层滚动+外层 custom_scroll_view 双层滚动冲突=底部
        // 文字显示不全根因），直接交给外层滚动
        final android.widget.LinearLayout[] logBoxHolder =
                new android.widget.LinearLayout[1];

        // 顶部：应用图标 + 标题/描述（readest 同款结构）
        android.widget.LinearLayout header = new android.widget.LinearLayout(activity);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(org.levimc.launcher.R.mipmap.ic_launcher);
        android.widget.LinearLayout.LayoutParams iconLp = new android.widget.LinearLayout.LayoutParams(
                56 * dp, 56 * dp);
        header.addView(icon, iconLp);

        android.widget.LinearLayout headText = new android.widget.LinearLayout(activity);
        headText.setOrientation(android.widget.LinearLayout.VERTICAL);
        headText.setPadding(14 * dp, 0, 0, 0);

        android.widget.TextView title = new android.widget.TextView(activity);
        title.setText(activity.getString(R.string.update_new_available));
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(onSurface);
        headText.addView(title);

        android.widget.TextView verLine = new android.widget.TextView(activity);
        verLine.setText(activity.getString(R.string.update_version_available,
                u.version, currentVersionName(activity)));
        verLine.setTextSize(13);
        verLine.setTextColor(textSec);
        android.widget.LinearLayout.LayoutParams verLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.topMargin = 2 * dp;
        headText.addView(verLine, verLp);

        android.widget.TextView askLine = new android.widget.TextView(activity);
        askLine.setText(activity.getString(R.string.update_ask_install));
        askLine.setTextSize(13);
        askLine.setTextColor(textSec);
        headText.addView(askLine);

        header.addView(headText, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        // 更新日志区（readest 同款：标题 + 灰底圆角卡片，版本号粗体 + 圆点列表）
        if (u.body != null && !u.body.isEmpty()) {
            android.widget.TextView logTitle = new android.widget.TextView(activity);
            logTitle.setText(activity.getString(R.string.update_changelog_title));
            logTitle.setTextSize(13);
            logTitle.setTextColor(textSec);
            logTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 14 * dp;
            root.addView(logTitle, lp);

            android.widget.LinearLayout logBox = new android.widget.LinearLayout(activity);
            logBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            logBox.setPadding(14 * dp, 10 * dp, 14 * dp, 10 * dp);
            android.graphics.drawable.GradientDrawable cardBg =
                    new android.graphics.drawable.GradientDrawable();
            cardBg.setCornerRadius(12 * dp);
            cardBg.setColor((onSurface & 0x00FFFFFF) | 0x14000000);
            logBox.setBackground(cardBg);

            boolean first = true;
            for (String line : u.body.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (first) {
                    // 首行「v0.4.143 更新日志」→ 版本号粗体标题（accent）。
                    // v0.4.211：只有首个 token 匹配版本号格式才当标题——
                    // 二刺螈文案首行「Ciallo～(∠・ω< )⌒★ 冒险家们久等啦！」
                    // 被 split 切开只显示前半=「Ciallo 后面显示不全」根因
                    String verTitle = t.split("\\s+")[0];
                    if (verTitle.matches("v?\\d[\\d.]*")) {
                        android.widget.TextView ver = new android.widget.TextView(activity);
                        ver.setText(verTitle);
                        ver.setTextSize(15);
                        ver.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        ver.setTextColor(accent);
                        logBox.addView(ver);
                        first = false;
                        continue;
                    }
                }
                first = false;
                if (t.startsWith("•")) {
                    t = t.substring(1).trim();
                }
                android.widget.TextView item = new android.widget.TextView(activity);
                item.setText("•  " + t);
                item.setTextSize(13);
                item.setTextColor(onSurface);
                item.setLineSpacing(2 * dp, 1f);
                android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                ilp.topMargin = 5 * dp;
                logBox.addView(item, ilp);
            }
            // v0.4.211：日志区直接进外层滚动（不再包固定 0.36 屏高的
            // 内层 ScrollView——双层滚动冲突=底部显示不全根因）
            root.addView(logBox, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            logBoxHolder[0] = logBox;
        }

        // v0.4.146：内嵌下载进度（readest 同款——不新开弹窗，点击
        // 「下载并安装」后更新日志区切换为波浪进度条）
        org.levimc.launcher.ui.views.WaveProgressView wave =
                new org.levimc.launcher.ui.views.WaveProgressView(activity);
        wave.setWaveColor(accent);
        wave.setProgress(0f);
        TextView pct = new TextView(activity);
        pct.setTextSize(13);
        pct.setTextColor(textSec);
        pct.setGravity(android.view.Gravity.CENTER);
        pct.setText("0%");
        LinearLayout progressBox = new LinearLayout(activity);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, 10 * dp, 0, 0);
        progressBox.setVisibility(View.GONE);
        progressBox.addView(wave, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 22 * dp));
        progressBox.addView(pct, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout.LayoutParams pbLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.36f));
        pbLp.topMargin = 6 * dp;
        root.addView(progressBox, pbLp);

        org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(activity);
        dialog.setTitleText(activity.getString(R.string.update_dialog_title));
        dialog.setCustomView(root);
        if (!u.apkUrls.isEmpty()) {
            final boolean[] downloadStarted = {false};
            dialog.setPositiveButtonAutoDismiss(false);
            dialog.setPositiveButton(activity.getString(R.string.update_download_btn), d -> {
                if (downloadStarted[0]) {
                    // 下载中：按钮文字已是「隐藏」——关闭弹窗静默下载
                    dialog.dismiss();
                    return;
                }
                downloadStarted[0] = true;
                dialog.setPositiveButtonText(
                        activity.getString(R.string.update_hide_btn));
                if (logBoxHolder[0] != null) {
                    logBoxHolder[0].setVisibility(View.GONE);
                }
                progressBox.setVisibility(View.VISIBLE);
                startDownload(activity, u, dialog, wave, pct);
            });
        } else if (u.cloudDriveLink != null && !u.cloudDriveLink.isEmpty()) {
            dialog.setPositiveButton(activity.getString(R.string.update_browser_btn), d -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(u.cloudDriveLink)));
                } catch (Throwable ignored) {
                }
            });
        } else {
            dialog.setPositiveButton(activity.getString(R.string.update_ok_btn), null);
        }
        dialog.setNegativeButton(activity.getString(R.string.update_later_btn), null);
        dialog.show();
    }

    private static String currentVersionName(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** v712：后台下载 APK → 系统安装器（FileProvider + 安装权限）。
     *  v0.0.1：多源顺序重试——GitHub 直链 → ghproxy 镜像，任一成功即停。
     *  v0.0.4：进度弹窗（可隐藏静默下载）+ 通知栏进度 + 完成后自动安装。
     *  v0.4.146：进度内嵌检查更新弹窗（readest 同款）——不再新开弹窗，
     *  直接更新传入的波浪条与百分比文字；弹窗仍显示时完成自动关闭。 */
    private static void startDownload(Activity activity, Update u,
            org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog,
            org.levimc.launcher.ui.views.WaveProgressView bar,
            TextView pct) {
        final Context app = activity.getApplicationContext();
        final int accent = new org.levimc.launcher.util.PersonalizationManager(app)
                .getAccentColor();
        createChannel(app);
        // v0.4.146：波浪自身规律流动（相位动画）+ 按进度裁剪填充同时进行
        bar.startWaving();

        pool.execute(() -> {
            File dir = new File(app.getFilesDir(), "updates");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File apk = new File(dir, "levi-update.apk");
            // v0.0.26：先竞速探测选最快源（直链被墙时镜像立即顶上，
            // 开 VPN 时直链胜出），失败再串行兜底
            ProgressListener listener =
                    makeProgressListener(dialog, activity, bar, pct, app, accent);
            java.util.List<String> candidates = buildCandidatesForApk(u);
            String fast = pickFastestSource(candidates);
            boolean downloadedOk = false;
            if (fast != null) {
                downloadedOk = tryDownload(fast, apk, listener);
            }
            if (!downloadedOk) {
                for (String url : candidates) {
                    if (url.equals(fast)) {
                        continue;
                    }
                    if (tryDownload(url, apk, listener)) {
                        downloadedOk = true;
                        break;
                    }
                }
            }
            if (downloadedOk) {
                NotificationManagerCompat.from(app).cancel(NOTIF_DOWNLOAD_ID);
                showDoneNotification(app, accent, apk);
                main.post(() -> {
                    if (!activity.isFinishing()) {
                        try {
                            if (dialog.isShowing()) {
                                dialog.dismiss();
                            }
                            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                    activity, activity.getPackageName() + ".fileprovider", apk);
                            Intent install = new Intent(Intent.ACTION_VIEW);
                            install.setDataAndType(uri,
                                    "application/vnd.android.package-archive");
                            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(install);
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(activity,
                                    activity.getString(R.string.update_installer_failed,
                                            t.getClass().getSimpleName()),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                return;
            }
            NotificationManagerCompat.from(app).cancel(NOTIF_DOWNLOAD_ID);
            main.post(() -> {
                if (!activity.isFinishing()) {
                    try {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                    } catch (Throwable ignored) {
                    }
                    android.widget.Toast.makeText(activity,
                            R.string.update_download_all_failed,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** 下载进度回调（弹窗 + 通知栏共用，500ms 通知节流）。 */
    private static ProgressListener makeProgressListener(
            org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog, Activity activity,
            org.levimc.launcher.ui.views.WaveProgressView bar, TextView pct,
            Context app, int accent) {
        return (downloaded, total) -> {
            // 弹窗进度（隐藏后跳过）
            if (dialog.isShowing() && !activity.isFinishing()) {
                main.post(() -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (total > 0) {
                        int p = (int) (downloaded * 100 / total);
                        bar.setProgress(p / 100f);
                        pct.setText(p + "%  " + fmtMB(downloaded)
                                + "/" + fmtMB(total) + " MB");
                    } else {
                        // 未知总量：无限波浪动画（startWaving 已在下载启动时调用）
                        pct.setText(fmtMB(downloaded) + " MB");
                    }
                });
            }
            // 通知进度（500ms 节流）
            long now = System.currentTimeMillis();
            if (now - lastNotifUpdate > 500 || downloaded >= total) {
                lastNotifUpdate = now;
                showDownloadNotification(app, accent, downloaded, total);
            }
        };
    }

    private static String fmtMB(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / 1048576f);
    }

    /** 下载进度通知（低优先级，静默）。 */
    private static void showDownloadNotification(Context app, int accent,
                                                 long downloaded, long total) {
        if (!notificationsAllowed(app)) {
            return;
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_UPDATE)
                .setSmallIcon(R.drawable.ic_notification_leaf)
                .setColor(accent)
                .setContentTitle(app.getString(R.string.update_notif_downloading))
                .setContentText(fmtMB(downloaded) + " MB"
                        + (total > 0 ? " / " + fmtMB(total) + " MB" : ""))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total > 0 ? 100 : 0,
                        total > 0 ? (int) (downloaded * 100 / total) : 0, total <= 0);
        try {
            NotificationManagerCompat.from(app).notify(NOTIF_DOWNLOAD_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    /** 下载完成通知（点击跳安装器）。 */
    private static void showDoneNotification(Context app, int accent, File apk) {
        if (!notificationsAllowed(app)) {
            return;
        }
        PendingIntent pi = null;
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    app, app.getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            pi = PendingIntent.getActivity(app, NOTIF_DOWNLOAD_ID, install,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable ignored) {
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_UPDATE)
                .setSmallIcon(R.drawable.ic_notification_leaf)
                .setColor(accent)
                .setContentTitle(app.getString(R.string.update_notif_done))
                .setContentText(app.getString(R.string.update_notif_tap_install))
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(app).notify(NOTIF_DOWNLOAD_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static boolean notificationsAllowed(Context app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled();
    }

    private static void createChannel(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_UPDATE, app.getString(R.string.update_notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(app.getString(R.string.update_notif_channel_desc));
        manager.createNotificationChannel(ch);
    }

    /** APK 下载候选：update.json 的每条直链 + 各自的镜像前缀展开。 */
    private static java.util.List<String> buildCandidatesForApk(Update u) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String base : u.apkUrls) {
            list.addAll(buildCandidates(base));
        }
        return list;
    }

    /** v0.0.26：下载源竞速探测——直链被墙时串行等满超时才换源
     * （用户反馈「下载一直 0%」；开 VPN 时镜像又不通）。所有候选源
     * 并发探测，最快响应的源胜出，随后用胜出源全量下载。 */
    private static String pickFastestSource(java.util.List<String> urls) {
        if (urls.size() <= 1) {
            return urls.isEmpty() ? null : urls.get(0);
        }
        java.util.concurrent.ExecutorService race =
                java.util.concurrent.Executors.newFixedThreadPool(urls.size());
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> winner =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (String url : urls) {
            race.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(4_000);
                    c.setReadTimeout(4_000);
                    c.setRequestProperty("Range", "bytes=0-0");
                    int code = c.getResponseCode();
                    if ((code == 200 || code == 206)
                            && winner.compareAndSet(null, url)) {
                        latch.countDown();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "探测失败: " + url + " (" + t.getClass().getSimpleName() + ")");
                } finally {
                    if (c != null) {
                        try { c.disconnect(); } catch (Throwable ignored) { }
                    }
                }
            });
        }
        try {
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        race.shutdownNow();
        String w = winner.get();
        Log.i(TAG, "竞速探测胜出: " + w);
        return w;
    }

    /** 下载进度回调。total<=0 表示总大小未知。 */
    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /** 尝试从单个 URL 下载到 apk；成功返回 true（文件完整）。 */
    private static boolean tryDownload(String url, File apk, ProgressListener listener) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            conn.setInstanceFollowRedirects(true);
            int respCode = conn.getResponseCode();
            if (respCode != 200) {
                Log.w(TAG, "下载源 " + url + " HTTP " + respCode);
                return false;
            }
            long total = conn.getContentLengthLong();
            FileOutputStream fos = new FileOutputStream(apk);
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[65536];
            int n;
            long downloaded = 0;
            // v0.0.30：进度汇报阈值节流——此前用 (downloaded & 0x7FFFF)==0
            // 判断「每 512KB」，但累加字节数几乎不会恰好命中 512KB 整数
            // 倍（读块大小不规律），进度条全程卡 0%（用户实测）。
            long lastReport = 0;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (listener != null && downloaded - lastReport >= 256 * 1024) {
                    listener.onProgress(downloaded, total);
                    lastReport = downloaded;
                }
            }
            fos.close();
            is.close();
            if (apk.length() < 1_000_000) { // 少于 1MB 视为下载不完整
                apk.delete();
                Log.w(TAG, "下载源 " + url + " 文件不完整 (" + apk.length() + "B)");
                return false;
            }
            if (listener != null) {
                listener.onProgress(apk.length(), Math.max(total, apk.length()));
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "下载源 " + url + " 失败: " + t.getClass().getSimpleName());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }
}
