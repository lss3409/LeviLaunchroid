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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

    public interface Callback {
        /** update 非空 = 发现新版本。 */
        void onResult(Update update);
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
            Update u = check(app);
            main.post(() -> cb.onResult(u));
        });
    }

    private static Update check(Context app) {
        // v0.0.1：update.json 多源回退（GitHub 直链 → 镜像），拿到即止
        for (String url : buildCandidates(UPDATE_URL)) {
            try {
                return checkOnce(app, url);
            } catch (Throwable t) {
                Log.w(TAG, "更新源失败换下一个: " + url + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        return null;
    }

    /** 单源检查；网络/解析失败抛异常（区别于「无更新」返回 null）。 */
    private static Update checkOnce(Context app, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new java.io.IOException("HTTP " + code);
        }
        String body = readAll(conn.getInputStream());
        conn.disconnect();
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

    /** 新版弹窗（Levi 风格）：版本 + 更新日志 + 下载更新（直链下载安装/
     *  网盘跳转）+ 稍后。 */
    public static void showUpdateDialog(Activity activity, Update u) {
        if (u == null || u.version.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本 ").append(u.version).append('\n');
        if (u.body != null && !u.body.isEmpty()) {
            msg.append('\n').append(u.body);
        }
        if (!u.apkUrls.isEmpty()) {
            msg.append("\n\n点击「下载更新」直接下载安装包");
        } else if (u.cloudDriveLink != null && !u.cloudDriveLink.isEmpty()) {
            msg.append("\n\n点击「去下载」打开网盘下载页面");
        }
        android.widget.TextView tv = new android.widget.TextView(activity);
        tv.setText(msg.toString());
        tv.setTextSize(14);
        tv.setTextColor(activity.getResources().getColor(
                R.color.on_surface, activity.getTheme()));
        tv.setPadding(0, (int) (10 * activity.getResources().getDisplayMetrics().density),
                0, 0);
        org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(activity);
        dialog.setTitleText("发现新版本");
        dialog.setCustomView(tv);
        if (!u.apkUrls.isEmpty()) {
            dialog.setPositiveButton("下载更新", d -> downloadAndInstall(activity, u));
        } else if (u.cloudDriveLink != null && !u.cloudDriveLink.isEmpty()) {
            dialog.setPositiveButton("去下载", d -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(u.cloudDriveLink)));
                } catch (Throwable ignored) {
                }
            });
        } else {
            dialog.setPositiveButton("知道了", null);
        }
        dialog.setNegativeButton("稍后", null);
        dialog.show();
    }

    /** v712：后台下载 APK → 系统安装器（FileProvider + 安装权限）。
     *  v0.0.1：多源顺序重试——GitHub 直链 → ghproxy 镜像，任一成功即停。
     *  v0.0.4：进度弹窗（可隐藏静默下载）+ 通知栏进度 + 完成后自动安装。 */
    private static void downloadAndInstall(Activity activity, Update u) {
        final Context app = activity.getApplicationContext();
        final int accent = new org.levimc.launcher.util.PersonalizationManager(app)
                .getAccentColor();
        createChannel(app);

        // 进度弹窗（「隐藏」= 关闭弹窗，后台静默下载，通知栏可见进度）
        ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setMax(100);
        TextView pct = new TextView(activity);
        pct.setTextSize(13);
        pct.setTextColor(activity.getResources().getColor(
                R.color.on_surface, activity.getTheme()));
        pct.setText("0%");
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(0, pad, 0, 0);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(pct, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(activity);
        dialog.setTitleText("正在下载 " + u.version);
        dialog.setCustomView(box);
        dialog.setNegativeButton("隐藏", null);
        dialog.show();
        final java.util.concurrent.atomic.AtomicBoolean dialogShown =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        dialog.setOnDismissListener(d -> dialogShown.set(false));

        pool.execute(() -> {
            File dir = new File(app.getFilesDir(), "updates");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File apk = new File(dir, "levi-update.apk");
            for (String url : buildCandidatesForApk(u)) {
                if (tryDownload(url, apk, (downloaded, total) -> {
                    // 弹窗进度（隐藏后跳过）
                    if (dialogShown.get() && !activity.isFinishing()) {
                        main.post(() -> {
                            if (!dialogShown.get() || activity.isFinishing()) {
                                return;
                            }
                            if (total > 0) {
                                int p = (int) (downloaded * 100 / total);
                                bar.setProgress(p);
                                pct.setText(p + "%  " + fmtMB(downloaded)
                                        + "/" + fmtMB(total) + " MB");
                            } else {
                                bar.setIndeterminate(true);
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
                })) {
                    NotificationManagerCompat.from(app).cancel(NOTIF_DOWNLOAD_ID);
                    showDoneNotification(app, accent, apk);
                    main.post(() -> {
                        if (dialogShown.get() && !activity.isFinishing()) {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                        }
                        if (!activity.isFinishing()) {
                            try {
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
                                        "无法打开安装器：" + t.getClass().getSimpleName(),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                    return;
                }
            }
            NotificationManagerCompat.from(app).cancel(NOTIF_DOWNLOAD_ID);
            main.post(() -> {
                if (dialogShown.get() && !activity.isFinishing()) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
                if (!activity.isFinishing()) {
                    android.widget.Toast.makeText(activity,
                            "所有下载源均失败，请稍后重试",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
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
                .setContentTitle("正在下载更新")
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
                .setContentTitle("更新下载完成")
                .setContentText("点击安装")
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
                CHANNEL_UPDATE, "更新下载", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("更新包下载进度");
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
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (listener != null && (downloaded & 0x7FFFF) == 0) { // 每 512KB 汇报
                    listener.onProgress(downloaded, total);
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
