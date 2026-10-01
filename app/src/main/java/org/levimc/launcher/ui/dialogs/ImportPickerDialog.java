package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
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
 * v607：全局导入选择弹窗——内容管理同款 UI（分类卡 + 搜索 + 条目列表）：
 *   顶部：标题 + 搜索框（按名称实时过滤）；
 *   分类区：存档/资源包/附加包/结构文件 四张折叠卡（图标+名称+数量），
 *   点卡展开/收起该分类的条目；
 *   条目区：图标 + 名称 + 类型·版本·大小（多包标记）+「导入」按钮
 *   （个性化强调色）；底部「从文件管理器选择」兜底原 SAF 流程。
 */
public class ImportPickerDialog {

    public interface Listener {
        void onPick(java.io.File file);

        void onPickFromFiles();
    }

    private static List<GlobalImportScanner.Candidate> cachedCandidates;
    private static final boolean[] expanded = new boolean[4];

    private static final int[][] GROUPS = {
            {GlobalImportScanner.TYPE_WORLD, 0},
            {GlobalImportScanner.TYPE_RESOURCE, 0},
            {GlobalImportScanner.TYPE_BEHAVIOR, 0},
            {GlobalImportScanner.TYPE_STRUCTURE, 0},
    };
    private static final String[] LABELS = {"存档", "资源包", "附加包", "结构文件"};
    private static final int[] ICONS = {R.drawable.ic_world, R.drawable.ic_photo,
            R.drawable.ic_behavior, R.drawable.ic_modules};

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

        // 搜索框
        EditText search = new EditText(context);
        search.setHint("搜索名称…");
        search.setHintTextColor(textSub);
        search.setTextColor(textMain);
        search.setTextSize(13);
        search.setSingleLine(true);
        search.setBackground(roundBg(context, cardBg));
        search.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (38 * density));
        slp.topMargin = (int) (10 * density);
        search.setLayoutParams(slp);
        root.addView(search);

        TextView status = new TextView(context);
        status.setText("正在扫描…");
        status.setTextColor(textSub);
        status.setTextSize(12);
        status.setPadding(0, (int) (8 * density), 0, (int) (6 * density));
        root.addView(status);

        // 分类卡 + 条目（共用滚动容器，重绘）
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final List<GlobalImportScanner.Candidate>[] results = new List[]{null};
        final String[] query = {""};
        final Runnable[] redrawRef = new Runnable[1];

        redrawRef[0] = () -> {
            content.removeAllViews();
            List<GlobalImportScanner.Candidate> list = results[0];
            if (list == null) {
                TextView t = new TextView(context);
                t.setText("正在扫描，请稍候…");
                t.setTextColor(textSub);
                t.setTextSize(12);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, (int) (20 * density), 0, 0);
                content.addView(t);
                return;
            }
            // 过滤
            List<GlobalImportScanner.Candidate> filtered = new ArrayList<>();
            for (GlobalImportScanner.Candidate c : list) {
                if (query[0].isEmpty()
                        || c.name.toLowerCase(Locale.US).contains(query[0].toLowerCase(Locale.US))) {
                    filtered.add(c);
                }
            }
            // 分类卡（一行四个）
            LinearLayout cards = new LinearLayout(context);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            content.addView(cards, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int g = 0; g < GROUPS.length; g++) {
                final int type = GROUPS[g][0];
                int count = 0;
                for (GlobalImportScanner.Candidate c : filtered) {
                    if (c.type == type) {
                        count++;
                    }
                }
                // v608：每分类独立展开位，多卡可同时展开
                final int gIdx = g;
                cards.addView(buildCategoryCard(context, g, count, density, accent,
                        textMain, cardBg, expanded[g], v -> {
                            expanded[gIdx] = !expanded[gIdx];
                            redrawRef[0].run();
                        }));
            }
            // 展开分类的条目列表（多个分类可同展，各自带小标题）
            for (int g = 0; g < GROUPS.length; g++) {
                if (!expanded[g]) {
                    continue;
                }
                final int type = GROUPS[g][0];
                boolean any = false;
                for (GlobalImportScanner.Candidate c : filtered) {
                    if (c.type != type) {
                        continue;
                    }
                    any = true;
                    content.addView(buildItemRow(context, c, textMain, textSub, cardBg,
                            density, accent, listener, dialog, redrawRef[0], content));
                }
                if (!any) {
                    TextView t = new TextView(context);
                    t.setText(LABELS[g] + "：暂无匹配内容");
                    t.setTextColor(textSub);
                    t.setTextSize(12);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(0, (int) (10 * density), 0, 0);
                    content.addView(t);
                }
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query[0] = s.toString();
                redrawRef[0].run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        if (cachedCandidates != null) {
            results[0] = cachedCandidates;
            status.setText("发现 " + cachedCandidates.size() + " 项，点分类卡展开");
            redrawRef[0].run();
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
                            status.setText("发现 " + candidates.size() + " 项，点分类卡展开");
                        }
                        redrawRef[0].run();
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
        fbp.topMargin = (int) (4 * density);
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
            int width = DialogSizer.dialogWidth(context, 680);
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


    /** 分类卡：图标 + 名称 + 数量，选中态 accent 描边。 */
    private static View buildCategoryCard(Context context, int group, int count,
                                          float density, int accent, int textMain,
                                          int cardBg, boolean selected,
                                          android.view.View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int pad = (int) (8 * density);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(10 * density);
        if (selected) {
            bg.setStroke((int) (1.5f * density), accent);
        }
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                (int) (72 * density), 1f);
        if (group > 0) {
            cp.leftMargin = (int) (6 * density);
        }
        card.setLayoutParams(cp);
        card.setOnClickListener(onClick);

        ImageView icon = new ImageView(context);
        icon.setImageResource(ICONS[group]);
        icon.setColorFilter(textMain);
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (22 * density), (int) (22 * density)));
        card.addView(icon);

        TextView label = new TextView(context);
        label.setText(LABELS[group] + (count > 0 ? "  " + count : ""));
        label.setTextColor(textMain);
        label.setTextSize(11);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (4 * density);
        card.addView(label, llp);
        return card;
    }

    /** 条目行：小图标 + 名称 + 版本·大小；点条目本体进二级详情，
     * 右侧「导入」MaterialButton（AccentStyler 强调色）。 */
    private static View buildItemRow(Context context, GlobalImportScanner.Candidate c,
                                     int textMain, int textSub, int cardBg, float density,
                                     int accent, Listener listener, Dialog dialog,
                                     Runnable redraw, LinearLayout content) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundBg(context, cardBg));
        row.setPadding((int) (10 * density), (int) (8 * density),
                (int) (10 * density), (int) (8 * density));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = (int) (6 * density);
        row.setLayoutParams(rp);

        // v608：图标套底色圆角容器（裸图突兀）
        LinearLayout iconWrap = new LinearLayout(context);
        iconWrap.setGravity(Gravity.CENTER);
        int iconSize = (int) (34 * density);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        iconWrap.setBackground(roundBg(context, context.getColor(R.color.background)));
        ImageView icon = new ImageView(context);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        Bitmap bmp = c.icon != null
                ? BitmapFactory.decodeByteArray(c.icon, 0, c.icon.length) : null;
        if (bmp != null) {
            icon.setImageBitmap(roundBitmap(bmp, (int) (6 * density)));
        } else {
            icon.setImageResource(ICONS[Math.min(c.type, ICONS.length - 1)]);
            icon.setColorFilter(textSub);
        }
        iconWrap.addView(icon);
        row.addView(iconWrap);

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
        String ver = c.version == null || c.version.isEmpty() ? "" : "v" + c.version + " · ";
        String subs = c.subItems != null && !c.subItems.isEmpty()
                ? "含" + c.subItems.size() + "包 · " : "";
        meta.setText(ver + subs + formatSize(c.size));
        meta.setTextColor(textSub);
        meta.setTextSize(11);
        info.addView(meta);

        // v608：导入按钮 MaterialButton（AccentStyler 才生效）
        com.google.android.material.button.MaterialButton importBtn =
                new com.google.android.material.button.MaterialButton(context);
        importBtn.setAllCaps(false);
        importBtn.setText("导入");
        importBtn.setTextSize(12);
        importBtn.setPadding((int) (14 * density), 0, (int) (14 * density), 0);
        importBtn.setMinWidth(0);
        importBtn.setMinimumWidth(0);
        importBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (34 * density)));
        AccentStyler.stylePrimary(context, importBtn);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
        row.addView(importBtn);

        // 点条目本体（图标/文字区）→ 二级详情
        iconWrap.setOnClickListener(v -> showDetail(content, context, c, textMain,
                textSub, cardBg, density, accent, listener, dialog, redraw));
        info.setOnClickListener(v -> showDetail(content, context, c, textMain,
                textSub, cardBg, density, accent, listener, dialog, redraw));
        return row;
    }

    /** 二级详情：大图标 + 名称/类型/版本/大小 + 路径 + 子包明细 + 导入/返回。 */
    private static void showDetail(LinearLayout content, Context context,
                                   GlobalImportScanner.Candidate c, int textMain,
                                   int textSub, int cardBg, float density, int accent,
                                   Listener listener, Dialog dialog, Runnable back) {
        content.removeAllViews();

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

        LinearLayout iconWrap = new LinearLayout(context);
        iconWrap.setGravity(Gravity.CENTER);
        int iconSize = (int) (64 * density);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        iconWrap.setBackground(roundBg(context, context.getColor(R.color.background)));
        ImageView icon = new ImageView(context);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        Bitmap bmp = c.icon != null
                ? BitmapFactory.decodeByteArray(c.icon, 0, c.icon.length) : null;
        if (bmp != null) {
            icon.setImageBitmap(roundBitmap(bmp, (int) (12 * density)));
        } else {
            icon.setImageResource(ICONS[Math.min(c.type, ICONS.length - 1)]);
            icon.setColorFilter(textSub);
        }
        iconWrap.addView(icon);
        head.addView(iconWrap);

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

        com.google.android.material.button.MaterialButton importBtn =
                new com.google.android.material.button.MaterialButton(context);
        importBtn.setAllCaps(false);
        importBtn.setText("导入");
        importBtn.setTextSize(14);
        importBtn.setMinWidth(0);
        importBtn.setMinimumWidth(0);
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(0,
                (int) (42 * density), 1f);
        ibp.leftMargin = (int) (6 * density);
        btns.addView(importBtn, ibp);
        AccentStyler.stylePrimary(context, importBtn);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
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
