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
        boolean bakedCacheIncluded;
        /** 三级明细：类型 0=资源包(mcpack) 1=行为包(mcaddon) 2=模组 3=世界存档。 */
        final List<ResItem> resources = new ArrayList<>();
        final List<ResItem> worlds = new ArrayList<>();
    }

    /** 资源明细项（三级菜单数据）。 */
    private static final class ResItem {
        int type;
        String name;
        byte[] icon;
        long size;
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
        // v570.4：内容自适应高度（不再 weight 撑满屏高 78%——平板上
        // 列表短时弹窗仍被拉成竖长条），超屏时由下方测量逻辑限高
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
            // v570.2：横向放宽（详情键值两列不挤）；v570.4：高度自适应内容，
            // 超屏（78% 屏高）才压缩——平板/横屏内容短时不再被拉成竖长条，
            // 手机显示不受影响（此前就是内容撑满 78%）
            int width = DialogSizer.dialogWidth(context, 500);
            final int maxHeight = DialogSizer.dialogMaxHeight(context);
            w.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            root.post(() -> {
                if (root.getHeight() > maxHeight) {
                    w.setLayout(width, maxHeight);
                }
            });
        }
    }

    /** 每次 show 前重新扫描（备份文件可能新增/覆盖）。 */
    @Override
    public void show() {
        super.show();
        titleView.setText(zh ? "导入实例备份" : "Import Instance Backup");
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

    /** 详情数据：资源/模组/存档/玩家登录信息（枚举全部条目，行点击即可
     * 秒开详情）。v570.2：分类修正——mcpack=资源包、mcaddon=行为包；
     * 模组按 mods/ 直接子项计数（不再统计 mod 内部全部文件）；收集
     * 世界存档名与 pack 图标（三级明细用）。 */
    private void parseContents(BackupInfo info) {
        java.util.Set<String> modNames = new java.util.HashSet<>();
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
                        && n.endsWith(".mcpack")) {
                    ResItem item = parsePack(zip, e, 0);
                    if (item != null) {
                        info.resources.add(item);
                    }
                } else if (n.startsWith("profile/") && n.contains("/behavior_packs/")
                        && n.endsWith(".mcaddon")) {
                    ResItem item = parsePack(zip, e, 1);
                    if (item != null) {
                        info.resources.add(item);
                    }
                } else if (n.startsWith("profile/") && n.contains("/mods/")) {
                    String rel = n.substring(n.indexOf("/mods/") + "/mods/".length());
                    int slash = rel.indexOf('/');
                    if (slash > 0) {
                        modNames.add(rel.substring(0, slash));
                    } else if (rel.endsWith(".zip")) {
                        modNames.add(rel.substring(0, rel.length() - 4));
                    }
                } else if (n.startsWith("profile/") && n.contains("/minecraftWorlds/")) {
                    String rel = n.substring(n.indexOf("/minecraftWorlds/") + "/minecraftWorlds/".length());
                    int slash = rel.indexOf('/');
                    if (slash > 0) {
                        String world = rel.substring(0, slash);
                        ResItem existing = null;
                        for (ResItem w : info.worlds) {
                            if (w.name.equals(world)) {
                                existing = w;
                                break;
                            }
                        }
                        if (existing == null) {
                            existing = new ResItem();
                            existing.type = 3;
                            existing.name = world;
                            info.worlds.add(existing);
                        }
                        // v570.3：world_icon（启动器同款三文件名）
                        String inner = rel.substring(slash + 1);
                        if (existing.icon == null
                                && (inner.equals("world_icon.jpeg")
                                || inner.equals("world_icon.jpg")
                                || inner.equals("world_icon.png"))) {
                            try (java.io.InputStream in = zip.getInputStream(e)) {
                                byte[] data = readAll(in);
                                if (data.length < 512 * 1024) {
                                    existing.icon = data;
                                }
                            }
                        }
                    }
                } else if (n.startsWith("baked_cache/")) {
                    info.bakedCacheIncluded = true;
                }
            }
        } catch (Exception ignored) {
        }
        for (String mod : modNames) {
            ResItem item = new ResItem();
            item.type = 2;
            item.name = mod;
            info.resources.add(item);
        }
    }

    /** 解析 .mcpack/.mcaddon（内嵌 zip）：manifest 的 name/description/pack_icon。 */
    private ResItem parsePack(ZipFile zip, ZipEntry e, int type) {
        try {
            ResItem item = new ResItem();
            item.type = type;
            item.size = e.getSize();
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    zip.getInputStream(e))) {
                java.util.zip.ZipEntry inner;
                while ((inner = zin.getNextEntry()) != null) {
                    String name = inner.getName();
                    if (name.equals("manifest.json")) {
                        byte[] data = readAll(zin);
                        org.json.JSONObject obj = new org.json.JSONObject(
                                new String(data, "UTF-8"));
                        org.json.JSONObject header = obj.optJSONObject("header");
                        if (header != null) {
                            item.name = header.optString("name", null);
                            if (item.name == null || item.name.isEmpty()) {
                                item.name = e.getName().substring(
                                        e.getName().lastIndexOf('/') + 1);
                            }
                        }
                    } else if (item.icon == null && name.endsWith(".png")) {
                        // pack_icon.png（manifest 里 pack_icon 字段指向的文件）
                        byte[] data = readAll(zin);
                        if (data.length < 256 * 1024) {
                            item.icon = data;
                        }
                    }
                    zin.closeEntry();
                }
            }
            if (item.name == null || item.name.isEmpty()) {
                item.name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
            }
            return item;
        } catch (Exception ignored) {
            return null;
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
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        row.setBackground(roundBg(context.getColor(R.color.surface_high)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        String name = info.manifest != null && info.manifest.instanceName != null
                && !info.manifest.instanceName.isEmpty()
                ? info.manifest.instanceName
                : fileNameWithoutExt(info.file.getName());
        TextView nameTv = new TextView(context);
        nameTv.setText(name);
        nameTv.setTextColor(context.getColor(R.color.on_surface));
        nameTv.setTextSize(15);
        nameTv.setTypeface(null, Typeface.BOLD);
        texts.addView(nameTv);

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
        texts.addView(metaTv);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // v570.4：一级行直接恢复按钮（无需进二级菜单）
        Button restore = new Button(context);
        restore.setAllCaps(false);
        restore.setText(zh ? "恢复" : "Restore");
        restore.setTextColor(Color.WHITE);
        restore.setTextSize(12);
        restore.setTypeface(null, Typeface.BOLD);
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(accent);
        rb.setCornerRadius(dp(8));
        restore.setBackground(rb);
        restore.setMinWidth(0);
        restore.setMinimumWidth(0);
        restore.setPadding(dp(14), 0, dp(14), 0);
        restore.setOnClickListener(v -> {
            File target = info.file;
            dismiss();
            if (listener != null) {
                listener.onRestoreRequested(target);
            }
        });
        row.addView(restore, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));

        row.setOnClickListener(v -> showDetail(info));
        return row;
    }

    // ---------------- 二级详情（抽屉式卡片） ----------------

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
        InstanceBackupManager.BackupManifest m = info.manifest;

        // 1. 备份信息卡（默认展开）
        StringBuilder infoSummary = new StringBuilder();
        if (m != null && m.versionName != null && !m.versionName.isEmpty()) {
            infoSummary.append(m.versionName).append("  ");
        }
        infoSummary.append(formatTime(m != null ? m.createdAt : info.file.lastModified()));
        View[] infoPair = drawerCard(zh ? "备份信息" : "Backup Info", infoSummary.toString(), true);
        LinearLayout infoBody = (LinearLayout) infoPair[1];
        if (m == null) {
            infoBody.addView(kvRow(zh ? "状态" : "Status", zh ? "无法解析（文件损坏或非备份）"
                    : "Unreadable (corrupted or not a backup)"));
        } else {
            // v570.3：manifest.versionName 实际存的是 versionCode——文案用「版本号」
            infoBody.addView(kvRow(zh ? "版本号" : "Version code", m.versionName));
            infoBody.addView(kvRow(zh ? "类型" : "Type",
                    m.installed ? (zh ? "已安装实例" : "Installed") : (zh ? "自建实例" : "Custom")));
            infoBody.addView(kvRow(zh ? "创建时间" : "Created", formatTimeFull(m.createdAt)));
            infoBody.addView(kvRow(zh ? "文件大小" : "Size", formatSize(info.size)));
            infoBody.addView(kvRow(zh ? "烘培缓存" : "Baked cache",
                    info.bakedCacheIncluded ? (zh ? "已包含" : "Included")
                            : (zh ? "未包含" : "Not included")));
        }
        detail.addView(infoPair[0]);

        // 2. 世界存档卡
        View[] worldPair = drawerCard(zh ? "世界存档" : "Worlds",
                String.valueOf(info.worlds.size()), false);
        LinearLayout worldBody = (LinearLayout) worldPair[1];
        if (info.worlds.isEmpty()) {
            worldBody.addView(smallText(zh ? "备份中无存档" : "No worlds in backup"));
        } else {
            for (ResItem w : info.worlds) {
                worldBody.addView(resItemRow(w));
            }
        }
        detail.addView(worldPair[0]);

        // 3. 资源卡（三级：分类行 → 点击展开明细）
        int packCount = 0;
        int behCount = 0;
        int modCount = 0;
        for (ResItem r : info.resources) {
            if (r.type == 0) {
                packCount++;
            } else if (r.type == 1) {
                behCount++;
            } else {
                modCount++;
            }
        }
        View[] resPair = drawerCard(zh ? "资源与模组" : "Resources & Mods",
                packCount + " / " + behCount + " / " + modCount, false);
        LinearLayout resBody = (LinearLayout) resPair[1];
        resBody.addView(resCategoryRow(zh ? "资源包（.mcpack）" : "Resource packs (.mcpack)",
                packCount, 0, info.resources));
        resBody.addView(resCategoryRow(zh ? "行为包（.mcaddon）" : "Behavior packs (.mcaddon)",
                behCount, 1, info.resources));
        resBody.addView(resCategoryRow(zh ? "模组" : "Mods", modCount, 2, info.resources));
        detail.addView(resPair[0]);

        // 4. 玩家信息卡
        boolean hasPlayer = (info.gamerTag != null && !info.gamerTag.isEmpty())
                || (info.xuid != null && !info.xuid.isEmpty())
                || (info.clientId != null && !info.clientId.isEmpty());
        View[] playerPair = drawerCard(zh ? "玩家登录信息" : "Player Account",
                hasPlayer ? emptyToDash(info.gamerTag) : (zh ? "无" : "None"), false);
        LinearLayout playerBody = (LinearLayout) playerPair[1];
        if (hasPlayer) {
            playerBody.addView(kvRow(zh ? "Xbox 名字" : "Xbox gamertag",
                    emptyToDash(info.gamerTag)));
            playerBody.addView(kvRow(zh ? "XUID" : "XUID", emptyToDash(info.xuid)));
            playerBody.addView(kvRow(zh ? "客户端 UUID" : "Client UUID",
                    emptyToDash(info.clientId)));
        } else {
            playerBody.addView(smallText(zh ? "备份中无登录信息" : "No account in backup"));
        }
        detail.addView(playerPair[0]);

        // 操作按钮
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
        bp.topMargin = dp(12);
        detail.addView(restoreBtn, bp);

        Button backBtn = new Button(context);
        backBtn.setAllCaps(false);
        backBtn.setText(zh ? "返回列表" : "Back to List");
        backBtn.setTextColor(context.getColor(R.color.text_secondary));
        backBtn.setTextSize(13);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setOnClickListener(v -> show());
        detail.addView(backBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        detailView = detail;
        contentContainer.addView(detail);
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    /** 抽屉式分区卡：返回 [卡片根视图, 内容容器]。 */
    private View[] drawerCard(String title, String summary, boolean expanded) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(roundBg(context.getColor(R.color.surface_high)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = dp(8);
        card.setLayoutParams(cp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextColor(accent);
        titleTv.setTextSize(13);
        titleTv.setTypeface(null, Typeface.BOLD);
        header.addView(titleTv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView summaryTv = new TextView(context);
        summaryTv.setText(summary);
        summaryTv.setTextColor(context.getColor(R.color.text_secondary));
        summaryTv.setTextSize(11);
        summaryTv.setPadding(dp(8), 0, 0, 0);
        header.addView(summaryTv);

        TextView arrow = new TextView(context);
        arrow.setText(expanded ? "▾" : "▸");
        arrow.setTextColor(context.getColor(R.color.text_secondary));
        arrow.setTextSize(13);
        arrow.setPadding(dp(8), 0, 0, 0);
        header.addView(arrow);

        card.addView(header);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, 0);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        card.addView(body);

        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.setText(show ? "▾" : "▸");
        });
        return new View[]{card, body};
    }

    /** 三级分类行：点开在下方展开该类别明细。 */
    private View resCategoryRow(String title, int count, int type, List<ResItem> all) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(5), 0, dp(5));
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextColor(context.getColor(R.color.on_surface));
        t.setTextSize(12);
        header.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView c = new TextView(context);
        c.setText(String.valueOf(count));
        c.setTextColor(context.getColor(R.color.text_secondary));
        c.setTextSize(11);
        header.addView(c);
        row.addView(header);

        LinearLayout items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setVisibility(View.GONE);
        if (count == 0) {
            TextView none = smallText(zh ? "（无）" : "(none)");
            none.setPadding(0, dp(2), 0, dp(4));
            items.addView(none);
        } else {
            for (ResItem item : all) {
                if (item.type != type) {
                    continue;
                }
                items.addView(resItemRow(item));
            }
        }
        row.addView(items);
        header.setOnClickListener(v -> {
            boolean show = items.getVisibility() != View.VISIBLE;
            items.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        return row;
    }

    /** 三级明细行：图标 + 名字 + 大小。 */
    private View resItemRow(ResItem item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(4), dp(4));

        android.widget.ImageView icon = new android.widget.ImageView(context);
        if (item.icon != null && item.icon.length > 0) {
            try {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(
                        item.icon, 0, item.icon.length);
                if (bmp != null) {
                    icon.setImageBitmap(bmp);
                }
            } catch (Throwable ignored) {
            }
        }
        if (icon.getDrawable() == null) {
            // v570.3：无贴图用启动器内容管理同款默认图标
            int fallback;
            switch (item.type) {
                case 1:
                    fallback = R.drawable.ic_behavior;
                    break;
                case 2:
                    fallback = R.drawable.ic_modules;
                    break;
                case 3:
                    fallback = R.drawable.ic_world;
                    break;
                default:
                    fallback = R.drawable.ic_photo;
                    break;
            }
            icon.setImageResource(fallback);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView name = new TextView(context);
        name.setText(item.name);
        name.setTextColor(context.getColor(R.color.on_surface));
        name.setTextSize(12);
        name.setPadding(dp(8), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (item.size > 0) {
            TextView size = new TextView(context);
            size.setText(formatSize(item.size));
            size.setTextColor(context.getColor(R.color.text_secondary));
            size.setTextSize(10);
            row.addView(size);
        }
        return row;
    }

    private TextView bulletRow(String text) {
        TextView tv = new TextView(context);
        tv.setText("• " + text);
        tv.setTextColor(context.getColor(R.color.on_surface));
        tv.setTextSize(12);
        tv.setPadding(0, dp(3), 0, dp(3));
        return tv;
    }

    private TextView smallText(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(context.getColor(R.color.text_secondary));
        tv.setTextSize(11);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
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
        row.setPadding(0, dp(3), 0, dp(3));
        TextView k = new TextView(context);
        k.setText(key);
        k.setTextColor(context.getColor(R.color.text_secondary));
        k.setTextSize(12);
        row.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(context);
        v.setText(value == null || value.isEmpty() ? "—" : value);
        v.setTextColor(context.getColor(R.color.on_surface));
        v.setTextSize(12);
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
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
