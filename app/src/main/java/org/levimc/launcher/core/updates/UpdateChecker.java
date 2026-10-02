package org.levimc.launcher.core.updates;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
     *  仓库已公开；/latest/download/ 固定重定向到最新版附件）。 */
    private static final String UPDATE_URL =
            "https://github.com/lss3409/levi-updates/releases/latest/download/update.json";
    private static final String PREFS = "levimc_update_check";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 3600 * 1000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-check");
        t.setDaemon(true);
        return t;
    });

    /** 更新信息。 */
    public static class Update {
        public int code;
        public String version;
        public String body;
        public String cloudDriveLink; // 网盘分享链接（空则无）
        public String apkUrl;          // APK 直链（GitHub Releases，空则无）
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
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            String body = readAll(conn.getInputStream());
            conn.disconnect();
            JSONObject json = new JSONObject(body);
            int remoteCode = json.optInt("code", 0);
            String remoteVersion = json.optString("version", "");

            // 本机版本号（versionName = git tag，如 v710）
            int localCode = 0;
            try {
                android.content.pm.PackageInfo pi = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0);
                String vn = pi != null ? pi.versionName : "";
                if (vn.startsWith("v")) {
                    localCode = Integer.parseInt(vn.substring(1));
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
            if (files != null && files.length() > 0) {
                u.apkUrl = files.optJSONObject(0).optString("uri", "");
            }
            return u;
        } catch (Throwable t) {
            Log.w(TAG, "检查更新失败: " + t.getClass().getSimpleName());
            return null;
        }
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
        if (u.apkUrl != null && !u.apkUrl.isEmpty()) {
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
        if (u.apkUrl != null && !u.apkUrl.isEmpty()) {
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

    /** v712：后台下载 APK → 系统安装器（FileProvider + 安装权限）。 */
    private static void downloadAndInstall(Activity activity, Update u) {
        android.widget.Toast.makeText(activity, "开始下载 " + u.version + "…",
                android.widget.Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(u.apkUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(300_000);
                conn.setInstanceFollowRedirects(true);
                int respCode = conn.getResponseCode();
                if (respCode != 200) {
                    conn.disconnect();
                    main.post(() -> android.widget.Toast.makeText(activity,
                            "下载失败（HTTP " + respCode + "）",
                            android.widget.Toast.LENGTH_SHORT).show());
                    return;
                }
                File dir = new File(activity.getFilesDir(), "updates");
                if (!dir.isDirectory()) {
                    dir.mkdirs();
                }
                File apk = new File(dir, "levi-update.apk");
                FileOutputStream fos = new FileOutputStream(apk);
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                is.close();
                conn.disconnect();
                if (apk.length() < 1_000_000) { // 少于 1MB 视为下载不完整
                    apk.delete();
                    main.post(() -> android.widget.Toast.makeText(activity,
                            "下载不完整，请重试", android.widget.Toast.LENGTH_SHORT).show());
                    return;
                }
                main.post(() -> {
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
                });
            } catch (Throwable t) {
                main.post(() -> android.widget.Toast.makeText(activity,
                        "下载失败：" + t.getClass().getSimpleName(),
                        android.widget.Toast.LENGTH_SHORT).show());
            }
        });
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
