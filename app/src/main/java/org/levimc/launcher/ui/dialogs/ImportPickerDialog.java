package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;

import java.util.List;
import java.util.Locale;

/**
 * v602：全局导入选择弹窗——扫描手机常见目录的可导入内容
 * （存档/资源包/行为包/结构，含 zip 与已解压文件夹），列表展示
 * pack_icon/名称/类型/大小/路径，点击即导入；兜底「从文件管理器
 * 选择」走原 SAF 流程。UI 风格对齐实例备份菜单（bg_rounded_card +
 * surface_high 卡片）。
 */
public class ImportPickerDialog {

    public interface Listener {
        void onPick(java.io.File file);

        void onPickFromFiles();
    }

    public static void show(Context context, Listener listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_rounded_card);
        root.setPadding((int) (20 * density), (int) (18 * density),
                (int) (20 * density), (int) (16 * density));

        TextView title = new TextView(context);
        title.setText("扫描手机中的可导入内容");
        title.setTextColor(context.getColor(R.color.on_surface));
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView status = new TextView(context);
        status.setText("正在扫描…");
        status.setTextColor(context.getColor(R.color.text_secondary));
        status.setTextSize(12);
        status.setPadding(0, (int) (6 * density), 0, (int) (10 * density));
        root.addView(status);

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView empty = new TextView(context);
        empty.setText("未发现可导入内容\n（存档 .mcworld / 资源包 .mcpack / 行为包 .mcaddon / 结构 .mcstructure，支持 zip 与已解压文件夹）");
        empty.setTextColor(context.getColor(R.color.text_secondary));
        empty.setTextSize(12);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (24 * density), 0, (int) (24 * density));
        list.addView(empty);

        GlobalImportScanner.scanAsync(new GlobalImportScanner.Listener() {
            @Override
            public void onProgress(String scanning) {
                runOnUi(context, () -> {
                    status.setText("正在扫描：" + scanning);
                });
            }

            @Override
            public void onDone(List<GlobalImportScanner.Candidate> candidates) {
                runOnUi(context, () -> {
                    list.removeAllViews();
                    if (candidates == null || candidates.isEmpty()) {
                        status.setText("扫描完成，未发现可导入内容");
                        list.addView(empty);
                        return;
                    }
                    status.setText("发现 " + candidates.size() + " 项，点击即导入");
                    // v605：按类型分组（存档/资源包/行为包/结构），资源多不乱
                    int[][] groups = {
                            {GlobalImportScanner.TYPE_WORLD, 0},
                            {GlobalImportScanner.TYPE_RESOURCE, 0},
                            {GlobalImportScanner.TYPE_BEHAVIOR, 0},
                            {GlobalImportScanner.TYPE_STRUCTURE, 0},
                    };
                    String[] labels = {"存档", "资源包", "行为包", "结构"};
                    for (int g = 0; g < groups.length; g++) {
                        boolean headerAdded = false;
                        for (GlobalImportScanner.Candidate c : candidates) {
                            if (c.type != groups[g][0]) {
                                continue;
                            }
                            if (!headerAdded) {
                                list.addView(buildGroupHeader(context, labels[g], density));
                                headerAdded = true;
                            }
                            list.addView(buildRow(context, c, listener, dialog, density));
                        }
                    }
                });
            }
        });

        // v605：按钮同实例备份菜单——普通 Button + 透明背景 + 强调色文字
        android.widget.Button fromFiles = new android.widget.Button(context);
        fromFiles.setAllCaps(false);
        fromFiles.setText("📂 从文件管理器选择");
        fromFiles.setTextSize(13);
        fromFiles.setTextColor(new org.levimc.launcher.util.PersonalizationManager(context)
                .getAccentColor());
        fromFiles.setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
            // v604：弹窗比例/限高走 DialogSizer（与备份菜单/详情卡同规范）
            int width = org.levimc.launcher.util.DialogSizer.dialogWidth(context, 500);
            final int maxHeight = org.levimc.launcher.util.DialogSizer.dialogMaxHeight(context);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            root.post(() -> {
                if (root.getHeight() > maxHeight) {
                    w.setLayout(width, maxHeight);
                }
            });
        }
        dialog.show();
    }

    /** v605：分组小标题（存档/资源包/行为包/结构）。 */
    private static View buildGroupHeader(Context context, String label, float density) {
        TextView t = new TextView(context);
        t.setText("— " + label + " —");
        t.setTextColor(context.getColor(R.color.text_secondary));
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
        return t;
    }

    private static View buildRow(Context context, GlobalImportScanner.Candidate c,
                                 Listener listener, Dialog dialog, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundBg(context, context.getColor(R.color.surface_high)));
        row.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (6 * density);
        row.setLayoutParams(rowLp);

        // 图标：pack_icon 字节 → Bitmap；无则按类型默认
        ImageView icon = new ImageView(context);
        int iconSize = (int) (40 * density);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        Bitmap bmp = c.icon != null ? BitmapFactory.decodeByteArray(c.icon, 0, c.icon.length) : null;
        if (bmp != null) {
            // v605：贴图套圆角（启动器卡片风格）
            icon.setImageBitmap(roundBitmap(bmp, (int) (8 * density)));
        } else {
            int res;
            switch (c.type) {
                case GlobalImportScanner.TYPE_WORLD:
                    res = R.drawable.ic_world;
                    break;
                case GlobalImportScanner.TYPE_RESOURCE:
                    res = R.drawable.ic_photo;
                    break;
                case GlobalImportScanner.TYPE_BEHAVIOR:
                    res = R.drawable.ic_behavior;
                    break;
                default:
                    res = R.drawable.ic_modules;
                    break;
            }
            icon.setImageResource(res);
        }
        row.addView(icon);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = (int) (10 * density);
        info.setLayoutParams(infoLp);
        row.addView(info);

        TextView name = new TextView(context);
        name.setText(c.name);
        name.setTextColor(context.getColor(R.color.on_surface));
        name.setTextSize(13);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);

        TextView meta = new TextView(context);
        String ver = c.version == null || c.version.isEmpty() ? "" : " · v" + c.version;
        meta.setText(c.typeLabel() + ver + " · " + formatSize(c.size));
        meta.setTextColor(context.getColor(R.color.text_secondary));
        meta.setTextSize(11);
        info.addView(meta);

        TextView path = new TextView(context);
        path.setText(c.path);
        path.setTextColor(context.getColor(R.color.text_secondary));
        path.setTextSize(10);
        path.setMaxLines(1);
        path.setEllipsize(android.text.TextUtils.TruncateAt.START);
        info.addView(path);

        row.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
        return row;
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

    private static GradientDrawable roundBg(Context context, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(12 * context.getResources().getDisplayMetrics().density);
        return g;
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
