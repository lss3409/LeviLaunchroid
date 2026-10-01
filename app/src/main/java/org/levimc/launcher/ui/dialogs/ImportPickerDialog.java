package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;
import org.levimc.launcher.util.AccentStyler;
import org.levimc.launcher.util.DialogSizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v606：全局导入选择弹窗——卡片抽屉式（对齐实例备份菜单）：
 * 一级 = 按类型分组（存档/资源包/行为包/结构）的卡片列表；
 * 二级 = 点卡片进入详情（大图标 + 名称/类型/版本/大小/路径）；
 * 三级 = 多包压缩包的子包明细分区；
 * 底部「导入」主按钮（个性化强调色）+「从文件管理器选择」兜底。
 */
public class ImportPickerDialog {

    public interface Listener {
        void onPick(java.io.File file);

        void onPickFromFiles();
    }

    private static List<GlobalImportScanner.Candidate> cachedCandidates;
    private static final int[][] GROUP_ORDER = {
            {GlobalImportScanner.TYPE_WORLD, 0},
            {GlobalImportScanner.TYPE_RESOURCE, 0},
            {GlobalImportScanner.TYPE_BEHAVIOR, 0},
            {GlobalImportScanner.TYPE_STRUCTURE, 0},
    };
    private static final String[] GROUP_LABELS = {"存档", "资源包", "行为包", "结构"};

    public static void show(Context context, Listener listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float density = context.getResources().getDisplayMetrics().density;
        int accent = new org.levimc.launcher.util.PersonalizationManager(context).getAccentColor();
        int textMain = context.getColor(R.color.on_surface);
        int textSub = context.getColor(R.color.text_secondary);
        int cardBg = context.getColor(R.color.surface_high);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_rounded_card);
        root.setPadding((int) (20 * density), (int) (18 * density),
                (int) (20 * density), (int) (16 * density));

        TextView title = new TextView(context);
        title.setText("扫描手机中的可导入内容");
        title.setTextColor(textMain);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView status = new TextView(context);
        status.setText("正在扫描…");
        status.setTextColor(textSub);
        status.setTextSize(12);
        status.setPadding(0, (int) (6 * density), 0, (int) (10 * density));
        root.addView(status);

        // 内容容器：一级列表 / 二级详情共用，切换重建
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView empty = new TextView(context);
        empty.setText("未发现可导入内容\n（存档 .mcworld / 资源包 .mcpack / 行为包 .mcaddon / 结构 .mcstructure，支持 zip 与已解压文件夹）");
        empty.setTextColor(textSub);
        empty.setTextSize(12);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (24 * density), 0, (int) (24 * density));
        content.addView(empty);

        final List<GlobalImportScanner.Candidate>[] results = new List[]{null};

        Runnable showLevel1 = () -> {
            content.removeAllViews();
            List<GlobalImportScanner.Candidate> list = results[0];
            if (list == null || list.isEmpty()) {
                content.addView(empty);
                return;
            }
            for (int g = 0; g < GROUP_ORDER.length; g++) {
                boolean headerAdded = false;
                for (GlobalImportScanner.Candidate c : list) {
                    if (c.type != GROUP_ORDER[g][0]) {
                        continue;
                    }
                    if (!headerAdded) {
                        content.addView(groupHeader(context, GROUP_LABELS[g],
                                textSub, density));
                        headerAdded = true;
                    }
                    content.addView(buildCard(context, c, textMain, textSub, cardBg,
                            density, accent, () -> showLevel2(content, c, context,
                                    textMain, textSub, cardBg, density, accent,
                                    listener, dialog, showLevel1)));
                }
            }
        };

        if (cachedCandidates != null) {
            results[0] = cachedCandidates;
            status.setText("发现 " + cachedCandidates.size() + " 项，点击卡片查看详情");
            showLevel1.run();
        } else {
            GlobalImportScanner.scanAsync(new GlobalImportScanner.Listener() {
                @Override
                public void onProgress(String scanning) {
                    runOnUi(context, () -> status.setText("正在扫描：" + scanning));
                }

                @Override
                public void onDone(List<GlobalImportScanner.Candidate> candidates) {
                    runOnUi(context, () -> {
                        cachedCandidates = candidates;
                        results[0] = candidates;
                        if (candidates == null || candidates.isEmpty()) {
                            status.setText("扫描完成，未发现可导入内容");
                        } else {
                            status.setText("发现 " + candidates.size() + " 项，点击卡片查看详情");
                        }
                        showLevel1.run();
                    });
                }
            });
        }

        // 底部：从文件管理器选择（备份菜单同款——透明底 + 强调色文字）
        Button fromFiles = new Button(context);
        fromFiles.setAllCaps(false);
        fromFiles.setText("📂 从文件管理器选择");
        fromFiles.setTextSize(13);
        fromFiles.setTextColor(accent);
        fromFiles.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * density));
        fbp.topMargin = (int) (6 * density);
        fromFiles.setLayoutParams(fbp);
        fromFiles.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPickFromFiles();
            }
        });
        root.addView(fromFiles);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = DialogSizer.dialogWidth(context, 500);
            final int maxHeight = DialogSizer.dialogMaxHeight(context);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            root.post(() -> {
                if (root.getHeight() > maxHeight) {
                    w.setLayout(width, maxHeight);
                }
            });
        }
        dialog.show();
    }

    // ---------------- 二级详情（卡片抽屉） ----------------

    private static void showLevel2(LinearLayout content, GlobalImportScanner.Candidate c,
                                   Context context, int textMain, int textSub, int cardBg,
                                   float density, int accent, Listener listener,
                                   Dialog dialog, Runnable back) {
        content.removeAllViews();

        // 顶部大图标卡
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(roundBg(context, cardBg));
        head.setPadding((int) (12 * density), (int) (12 * density),
                (int) (12 * density), (int) (12 * density));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = (int) (8 * density);
        head.setLayoutParams(hp);
        content.addView(head);

        ImageView icon = new ImageView(context);
        int iconSize = (int) (64 * density);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        Bitmap bmp = c.icon != null
                ? BitmapFactory.decodeByteArray(c.icon, 0, c.icon.length) : null;
        if (bmp != null) {
            icon.setImageBitmap(roundBitmap(bmp, (int) (12 * density)));
        } else {
            icon.setImageResource(defaultIconRes(c.type));
        }
        head.addView(icon);

        LinearLayout headInfo = new LinearLayout(context);
        headInfo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hip = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hip.leftMargin = (int) (12 * density);
        headInfo.setLayoutParams(hip);
        head.addView(headInfo);

        TextView name = new TextView(context);
        name.setText(c.name);
        name.setTextColor(textMain);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headInfo.addView(name);

        String ver = c.version == null || c.version.isEmpty() ? "" : " · v" + c.version;
        TextView meta = new TextView(context);
        meta.setText(c.typeLabel() + ver + " · " + formatSize(c.size));
        meta.setTextColor(textSub);
        meta.setTextSize(12);
        meta.setPadding(0, (int) (4 * density), 0, 0);
        headInfo.addView(meta);

        // 信息卡（路径）
        LinearLayout infoCard = new LinearLayout(context);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(roundBg(context, cardBg));
        infoCard.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icp.bottomMargin = (int) (8 * density);
        infoCard.setLayoutParams(icp);
        content.addView(infoCard);

        TextView pathLabel = new TextView(context);
        pathLabel.setText("所在位置");
        pathLabel.setTextColor(textSub);
        pathLabel.setTextSize(11);
        infoCard.addView(pathLabel);
        TextView path = new TextView(context);
        path.setText(c.path);
        path.setTextColor(textMain);
        path.setTextSize(12);
        path.setPadding(0, (int) (3 * density), 0, 0);
        infoCard.addView(path);

        // 三级：多包子包明细（抽屉式分区）
        if (c.subItems != null && !c.subItems.isEmpty()) {
            LinearLayout subCard = new LinearLayout(context);
            subCard.setOrientation(LinearLayout.VERTICAL);
            subCard.setBackground(roundBg(context, cardBg));
            subCard.setPadding((int) (12 * density), (int) (10 * density),
                    (int) (12 * density), (int) (10 * density));
            LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scp.bottomMargin = (int) (8 * density);
            subCard.setLayoutParams(scp);
            content.addView(subCard);

            TextView subLabel = new TextView(context);
            subLabel.setText("内含 " + c.subItems.size() + " 个包（压缩包内明细）");
            subLabel.setTextColor(textSub);
            subLabel.setTextSize(11);
            subCard.addView(subLabel);
            for (String s : c.subItems) {
                TextView si = new TextView(context);
                si.setText("· " + s);
                si.setTextColor(textMain);
                si.setTextSize(12);
                si.setPadding((int) (4 * density), (int) (5 * density), 0, 0);
                subCard.addView(si);
            }
        }

        // 底部按钮区：导入（主）+ 返回
        LinearLayout btns = new LinearLayout(context);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btp.topMargin = (int) (2 * density);
        btns.setLayoutParams(btp);
        content.addView(btns);

        Button backBtn = new Button(context);
        backBtn.setAllCaps(false);
        backBtn.setText("← 返回列表");
        backBtn.setTextSize(13);
        backBtn.setTextColor(textSub);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(0,
                (int) (42 * density), 1f);
        btns.addView(backBtn, bbp);
        backBtn.setOnClickListener(v -> back.run());

        Button importBtn = new Button(context);
        importBtn.setAllCaps(false);
        importBtn.setText("导入");
        importBtn.setTextSize(14);
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(0,
                (int) (42 * density), 1f);
        btns.addView(importBtn, ibp);
        AccentStyler.stylePrimary(context, importBtn);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
    }

    // ---------------- 构建组件 ----------------

    private static View groupHeader(Context context, String label, int textSub, float density) {
        TextView t = new TextView(context);
        t.setText("— " + label + " —");
        t.setTextColor(textSub);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
        return t;
    }

    private static View buildCard(Context context, GlobalImportScanner.Candidate c,
                                  int textMain, int textSub, int cardBg, float density,
                                  int accent, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundBg(context, cardBg));
        row.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = (int) (8 * density);
        row.setLayoutParams(rp);

        ImageView icon = new ImageView(context);
        int iconSize = (int) (40 * density);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        Bitmap bmp = c.icon != null
                ? BitmapFactory.decodeByteArray(c.icon, 0, c.icon.length) : null;
        if (bmp != null) {
            icon.setImageBitmap(roundBitmap(bmp, (int) (8 * density)));
        } else {
            icon.setImageResource(defaultIconRes(c.type));
        }
        row.addView(icon);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = (int) (10 * density);
        row.addView(info, ilp);

        TextView name = new TextView(context);
        name.setText(c.name);
        name.setTextColor(textMain);
        name.setTextSize(13);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);

        TextView meta = new TextView(context);
        String ver = c.version == null || c.version.isEmpty() ? "" : " · v" + c.version;
        String subs = c.subItems != null && !c.subItems.isEmpty()
                ? " · 含" + c.subItems.size() + "包" : "";
        meta.setText(c.typeLabel() + ver + " · " + formatSize(c.size) + subs);
        meta.setTextColor(textSub);
        meta.setTextSize(11);
        info.addView(meta);

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextColor(accent);
        arrow.setTextSize(22);
        arrow.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(arrow);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static int defaultIconRes(int type) {
        switch (type) {
            case GlobalImportScanner.TYPE_WORLD:
                return R.drawable.ic_world;
            case GlobalImportScanner.TYPE_RESOURCE:
                return R.drawable.ic_photo;
            case GlobalImportScanner.TYPE_BEHAVIOR:
                return R.drawable.ic_behavior;
            default:
                return R.drawable.ic_modules;
        }
    }

    private static GradientDrawable roundBg(Context context, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(12 * context.getResources().getDisplayMetrics().density);
        return g;
    }

    /** Bitmap 圆角裁剪（贴图对齐启动器卡片风格）。 */
    private static Bitmap roundBitmap(Bitmap src, int radiusPx) {
        if (src == null) {
            return null;
        }
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.Path path = new android.graphics.Path();
            android.graphics.RectF rect = new android.graphics.RectF(0, 0,
                    src.getWidth(), src.getHeight());
            path.addRoundRect(rect, radiusPx, radiusPx, android.graphics.Path.Direction.CW);
            canvas.clipPath(path);
            canvas.drawBitmap(src, 0, 0, paint);
            return out;
        } catch (Throwable ignored) {
            return src;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static void runOnUi(Context context, Runnable r) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(r);
    }
}
