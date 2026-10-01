package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.util.DialogSizer;
import org.levimc.launcher.util.InstanceBackupManager;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v570：导入备份菜单——点「导入备份」打开，显示备份目录
 * （Download/LeviLauncher/Backups/minecraft item/）下的实例备份列表。
 * 一级：时间戳式列表行（实例名/版本/时间/大小）；点击进入二级详情：
 * manifest.json 信息、备份内资源（行为包/资源包/模组）、玩家登录信息
 * （Xbox 名字/UUID），底部「恢复此备份」+「从文件管理器选择」兜底。
 */
public final class BackupListDialog extends Dialog {

    public interface Listener {
        void onRestoreRequested(File backupFile);

        void onPickFromFilesRequested();
    }

    /** 单个备份的解析结果。 */
    private static final class BackupInfo {
        File file;
        InstanceBackupManager.BackupManifest manifest;
        long size;
        String gamerTag;
        String xuid;
        String clientId;
        int resourcePackCount;
        int behaviorPackCount;
        int modCount;
        boolean bakedCacheIncluded;
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final int accent;
    private final boolean zh;

    private LinearLayout contentContainer;
    private ScrollView scrollView;
    private TextView titleView;
    private ProgressBar loadingBar;
    private View detailView;

    public BackupListDialog(Context context, Listener listener) {
        super(context, R.style.LeviDialogTheme);
        this.context = context;
        this.listener = listener;
        int ac = new PersonalizationManager(context).getAccentColor();
        this.accent = ac != 0 ? ac : context.getColor(R.color.primary);
        this.zh = Locale.getDefault().getLanguage().startsWith("zh");
        buildUi();
    }

    // ---------------- UI 构建 ----------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(14));
        // v570.1：与 CustomAlertDialog 一致的不透明弹窗背景（LeviDialogTheme
        // 窗口透明，之前无背景容器导致菜单全透明）
        root.setBackground(context.getDrawable(R.drawable.bg_rounded_card));

        titleView = new TextView(context);
        titleView.setText(zh ? "导入实例备份" : "Import Instance Backup");
        titleView.setTextColor(context.getColor(R.color.on_surface));
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        root.addView(titleView);

        TextView subtitle = new TextView(context);
        subtitle.setText(zh ? "备份目录：Download/LeviLauncher/Backups/minecraft item/"
                : "Backup folder: Download/LeviLauncher/Backups/minecraft item/");
        subtitle.setTextColor(context.getColor(R.color.text_secondary));
        subtitle.setTextSize(11);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentContainer);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, sp);

        loadingBar = new ProgressBar(context);
        loadingBar.setIndeterminate(true);
        loadingBar.getIndeterminateDrawable().setTint(accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(14);
        root.addView(loadingBar, lp);

        // 底部按钮：从文件管理器选择（一级列表为空时也可见）
        Button pickButton = new Button(context);
        pickButton.setAllCaps(false);
        pickButton.setText(zh ? "从文件管理器选择…" : "Choose from Files…");
        pickButton.setTextColor(accent);
        pickButton.setTextSize(13);
        pickButton.setBackgroundColor(Color.TRANSPARENT);
        pickButton.setOnClickListener(v -> {
            dismiss();
            if (listener != null) {
                listener.onPickFromFilesRequested();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        bp.topMargin = dp(6);
        root.addView(pickButton, bp);

        setContentView(root);
        Window w = getWindow();
        if (w != null) {
            w.setLayout(DialogSizer.dialogWidth(context, 440),
                    DialogSizer.dialogMaxHeight(context));
        }
    }

    /** 每次 show 前重新扫描（备份文件可能新增/覆盖）。 */
    @Override
    public void show() {
        super.show();
        if (detailView != null) {
            contentContainer.removeView(detailView);
            detailView = null;
        }
        contentContainer.removeAllViews();
        loadingBar.setVisibility(View.VISIBLE);
        scanAndFill();
    }

    // ---------------- 扫描与解析 ----------------

    private void scanAndFill() {
        executor.execute(() -> {
            File dir = new File(LauncherStorage.getBackupsRoot(context), "minecraft item");
            if (!dir.isDirectory()) {
                // 兜底：直接拼路径
                dir = new File(android.os.Environment.getExternalStorageDirectory(),
                        "Download/LeviLauncher/Backups/minecraft item");
            }
            File[] files = dir.listFiles();
            List<BackupInfo> infos = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile() || !InstanceBackupManager.isBackupFileName(f.getName())) {
                        continue;
                    }
                    BackupInfo info = new BackupInfo();
                    info.file = f;
                    info.size = f.length();
                    parseManifest(info);
                    parseContents(info);
                    infos.add(info);
                }
            }
            infos.sort((a, b) -> Long.compare(b.manifest != null ? b.manifest.createdAt : b.file.lastModified(),
                    a.manifest != null ? a.manifest.createdAt : a.file.lastModified()));
            getWindow().getDecorView().post(() -> {
                loadingBar.setVisibility(View.GONE);
                fillList(infos);
            });
        });
    }

    /** 只读 manifest.json（单条目随机访问，大文件也快）。 */
    private void parseManifest(BackupInfo info) {
        try (ZipFile zip = new ZipFile(info.file)) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) {
                return;
            }
            byte[] data = new byte[(int) Math.max(1L, Math.min(entry.getSize(), 65536))];
            int off = 0;
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                int len;
                while (off < data.length && (len = in.read(data, off, data.length - off)) > 0) {
                    off += len;
                }
            }
            info.manifest = InstanceBackupManager.parseManifestJson(
                    new String(data, 0, off, "UTF-8"));
        } catch (Exception ignored) {
        }
    }

    /** 详情数据：资源统计 + 玩家登录信息（枚举全部条目，仅在行点击时深读也
     * 可以，但条目数不多，直接一并解析，行点击即可秒开详情）。 */
    private void parseContents(BackupInfo info) {
        try (ZipFile zip = new ZipFile(info.file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                if (n.startsWith("xal/") && n.endsWith("Xal.Production.RETAIL.D.json")) {
                    parseXal(zip, e, info);
                } else if (n.startsWith("profile/") && n.endsWith("/minecraftpe/clientId.txt")) {
                    parseClientId(zip, e, info);
                } else if (n.startsWith("profile/") && n.contains("/resource_packs/")
                        && (n.endsWith(".mcpack") || n.endsWith(".mcaddon"))) {
                    info.resourcePackCount++;
                } else if (n.startsWith("profile/") && n.contains("/behavior_packs/")
                        && (n.endsWith(".mcpack") || n.endsWith(".mcaddon"))) {
                    info.behaviorPackCount++;
                } else if (n.startsWith("profile/") && n.contains("/mods/")
                        && !n.endsWith("/")) {
                    info.modCount++;
                } else if (n.startsWith("baked_cache/")) {
                    info.bakedCacheIncluded = true;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void parseXal(ZipFile zip, ZipEntry e, BackupInfo info) {
        try (java.io.InputStream in = zip.getInputStream(e)) {
            org.json.JSONObject obj = new org.json.JSONObject(
                    new String(readAll(in), "UTF-8"));
            info.gamerTag = obj.optString("gamerTag", null);
            if (info.gamerTag == null || info.gamerTag.isEmpty()) {
                info.gamerTag = obj.optString("gamertag", null);
            }
            info.xuid = obj.optString("xuid", null);
            if (info.xuid == null || info.xuid.isEmpty()) {
                info.xuid = obj.optString("userXuid", null);
            }
        } catch (Exception ignored) {
        }
    }

    private void parseClientId(ZipFile zip, ZipEntry e, BackupInfo info) {
        try (java.io.InputStream in = zip.getInputStream(e)) {
            info.clientId = new String(readAll(in), "UTF-8").trim();
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    // ---------------- 一级列表 ----------------

    private void fillList(List<BackupInfo> infos) {
        if (infos.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(zh ? "暂无备份——先在实例页备份一个实例"
                    : "No backups yet. Back up an instance first.");
            empty.setTextColor(context.getColor(R.color.text_secondary));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));
            contentContainer.addView(empty);
            return;
        }
        for (BackupInfo info : infos) {
            contentContainer.addView(buildListRow(info));
        }
    }

    private View buildListRow(BackupInfo info) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(roundBg(context.getColor(R.color.surface_high)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        String name = info.manifest != null && info.manifest.instanceName != null
                && !info.manifest.instanceName.isEmpty()
                ? info.manifest.instanceName
                : fileNameWithoutExt(info.file.getName());
        TextView nameTv = new TextView(context);
        nameTv.setText(name);
        nameTv.setTextColor(context.getColor(R.color.on_surface));
        nameTv.setTextSize(15);
        nameTv.setTypeface(null, Typeface.BOLD);
        row.addView(nameTv);

        StringBuilder meta = new StringBuilder();
        if (info.manifest != null && info.manifest.versionName != null
                && !info.manifest.versionName.isEmpty()) {
            meta.append(info.manifest.versionName).append(" · ");
        }
        meta.append(formatTime(info.manifest != null ? info.manifest.createdAt
                : info.file.lastModified()));
        meta.append(" · ").append(formatSize(info.size));
        if (info.manifest != null && info.manifest.installed) {
            meta.append(" · ").append(zh ? "已安装" : "installed");
        }
        TextView metaTv = new TextView(context);
        metaTv.setText(meta.toString());
        metaTv.setTextColor(context.getColor(R.color.text_secondary));
        metaTv.setTextSize(11);
        metaTv.setPadding(0, dp(3), 0, 0);
        row.addView(metaTv);

        row.setOnClickListener(v -> showDetail(info));
        return row;
    }

    // ---------------- 二级详情 ----------------

    private void showDetail(BackupInfo info) {
        if (detailView != null) {
            contentContainer.removeView(detailView);
        }
        contentContainer.removeAllViews();
        titleView.setText(info.manifest != null && info.manifest.instanceName != null
                && !info.manifest.instanceName.isEmpty()
                ? info.manifest.instanceName : fileNameWithoutExt(info.file.getName()));

        LinearLayout detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);

        detail.addView(sectionTitle(zh ? "备份信息" : "Backup Info"));
        LinearLayout infoCard = new LinearLayout(context);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        infoCard.setBackground(roundBg(context.getColor(R.color.surface_high)));
        InstanceBackupManager.BackupManifest m = info.manifest;
        if (m == null) {
            infoCard.addView(kvRow(zh ? "状态" : "Status", zh ? "无法解析（文件损坏或非备份）"
                    : "Unreadable (corrupted or not a backup)"));
        } else {
            infoCard.addView(kvRow(zh ? "实例名" : "Name", m.instanceName));
            infoCard.addView(kvRow(zh ? "版本" : "Version", m.versionName));
            infoCard.addView(kvRow(zh ? "目录名" : "Directory", m.directoryName));
            infoCard.addView(kvRow(zh ? "类型" : "Type",
                    m.installed ? (zh ? "已安装实例" : "Installed") : (zh ? "自建实例" : "Custom")));
            infoCard.addView(kvRow(zh ? "创建时间" : "Created", formatTimeFull(m.createdAt)));
            infoCard.addView(kvRow(zh ? "文件大小" : "Size", formatSize(info.size)));
        }
        detail.addView(infoCard);

        detail.addView(sectionTitle(zh ? "备份内容" : "Contents"));
        LinearLayout resCard = new LinearLayout(context);
        resCard.setOrientation(LinearLayout.VERTICAL);
        resCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        resCard.setBackground(roundBg(context.getColor(R.color.surface_high)));
        resCard.addView(kvRow(zh ? "资源包（.mcpack/.mcaddon）" : "Resource packs",
                String.valueOf(info.resourcePackCount)));
        resCard.addView(kvRow(zh ? "行为包" : "Behavior packs",
                String.valueOf(info.behaviorPackCount)));
        resCard.addView(kvRow(zh ? "模组（mods）" : "Mods", String.valueOf(info.modCount)));
        resCard.addView(kvRow(zh ? "烘培地图缓存" : "Baked map cache",
                info.bakedCacheIncluded ? (zh ? "已包含" : "Included") : (zh ? "未包含" : "Not included")));
        detail.addView(resCard);

        detail.addView(sectionTitle(zh ? "玩家登录信息" : "Player Account"));
        LinearLayout playerCard = new LinearLayout(context);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        playerCard.setBackground(roundBg(context.getColor(R.color.surface_high)));
        playerCard.addView(kvRow(zh ? "Xbox 名字" : "Xbox gamertag",
                emptyToDash(info.gamerTag)));
        playerCard.addView(kvRow(zh ? "XUID" : "XUID", emptyToDash(info.xuid)));
        playerCard.addView(kvRow(zh ? "客户端 UUID" : "Client UUID", emptyToDash(info.clientId)));
        detail.addView(playerCard);

        Button restoreBtn = new Button(context);
        restoreBtn.setAllCaps(false);
        restoreBtn.setText(zh ? "恢复此备份" : "Restore This Backup");
        restoreBtn.setTextColor(Color.WHITE);
        restoreBtn.setTextSize(14);
        restoreBtn.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent);
        bg.setCornerRadius(dp(10));
        restoreBtn.setBackground(bg);
        restoreBtn.setOnClickListener(v -> {
            File target = info.file;
            dismiss();
            if (listener != null) {
                listener.onRestoreRequested(target);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        bp.topMargin = dp(14);
        detail.addView(restoreBtn, bp);

        Button backBtn = new Button(context);
        backBtn.setAllCaps(false);
        backBtn.setText(zh ? "返回列表" : "Back to List");
        backBtn.setTextColor(context.getColor(R.color.text_secondary));
        backBtn.setTextSize(13);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setOnClickListener(v -> show());
        detail.addView(backBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        detailView = detail;
        contentContainer.addView(detail);
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    // ---------------- 小工具 ----------------

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(accent);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(2), dp(10), 0, dp(6));
        return tv;
    }

    private View kvRow(String key, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView k = new TextView(context);
        k.setText(key);
        k.setTextColor(context.getColor(R.color.text_secondary));
        k.setTextSize(12);
        row.addView(k, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView v = new TextView(context);
        v.setText(value == null || value.isEmpty() ? "—" : value);
        v.setTextColor(context.getColor(R.color.on_surface));
        v.setTextSize(12);
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private String formatTime(long ts) {
        if (ts <= 0) {
            return "—";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(ts));
    }

    private String formatTimeFull(long ts) {
        return formatTime(ts);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String fileNameWithoutExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String emptyToDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * context.getResources().getDisplayMetrics().density));
    }
}
