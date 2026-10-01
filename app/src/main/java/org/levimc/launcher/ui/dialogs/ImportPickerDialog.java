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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;
import org.levimc.launcher.util.AccentStyler;
import org.levimc.launcher.util.DialogSizer;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v609：全局导入选择弹窗——内容管理同款 UI：
 *   顶部：标题 + 搜索框（二级菜单时隐藏）+ 扫描状态（二级时隐藏）；
 *   分类区：存档/资源包/附加包/结构文件 四张卡，单展开位（点新卡
 *   切换收起旧卡，再点同一卡收起）；
 *   条目区：方形 pack_icon（原样显示，不裁圆）+ 名称 + 版本·大小 +
 *   「导入」MaterialButton；
 *   二级（点条目本体）：清单文件信息——包的名称/版本/描述、addon
 *   的 bp/rp 子包列表（点子包切换详情）、依赖 uuid；存档显示
 *   level.dat 解析的版本/种子/模式/最后游玩时间/世界名。
 *   底部「从文件管理器选择」与导入同款强调色按钮。
 */
public class ImportPickerDialog {

    public interface Listener {
        void onPick(java.io.File file);

        void onPickFromFiles();
    }

    private static List<GlobalImportScanner.Candidate> cachedCandidates;
    private static Runnable relimitRef;
    private static View cardsHostView;
    private static int expandedType = -1;

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

        // v610：分类卡固定区（吸顶，条目滚动时始终显示）
        LinearLayout cardsHost = new LinearLayout(context);
        cardsHostView = cardsHost;
        cardsHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(cardsHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
            cardsHost.removeAllViews();
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
            // 分类卡（固定区一行四个，单展开位切换）
            LinearLayout cards = new LinearLayout(context);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            cardsHost.addView(cards, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int g = 0; g < GROUPS.length; g++) {
                final int type = GROUPS[g][0];
                int count = 0;
                for (GlobalImportScanner.Candidate c : filtered) {
                    if (c.type == type) {
                        count++;
                    }
                }
                cards.addView(buildCategoryCard(context, g, count, density, accent,
                        textMain, cardBg, type == expandedType, v -> {
                            // v610：单展开位——必须始终有一个分类展开
                            // （点已展开卡不再收起，避免下方出现大空缺）
                            expandedType = type;
                            redrawRef[0].run();
                        }));
            }
            // 条目列表（仅展开分类）+ 卡片与条目的间距
            if (expandedType >= 0) {
                TextView gap = new TextView(context);
                gap.setText(" ");
                gap.setTextSize(4);
                content.addView(gap);
                boolean any = false;
                for (GlobalImportScanner.Candidate c : filtered) {
                    if (c.type != expandedType) {
                        continue;
                    }
                    any = true;
                    content.addView(buildItemRow(context, c, textMain, textSub, cardBg,
                            density, accent, listener, dialog, redrawRef[0], content,
                            search, status));
                }
                if (!any) {
                    TextView t = new TextView(context);
                    t.setText("该分类暂无匹配内容");
                    t.setTextColor(textSub);
                    t.setTextSize(12);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(0, (int) (10 * density), 0, 0);
                    content.addView(t);
                }
            }
            // v613：内容变化后重新测量弹窗高度（限高自适应）
            if (relimitRef != null) {
                relimitRef.run();
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
            if (expandedType < 0) {
                expandedType = firstNonEmptyType(cachedCandidates);
            }
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
                            if (expandedType < 0) {
                                expandedType = firstNonEmptyType(candidates);
                            }
                            status.setText("发现 " + candidates.size() + " 项，点分类卡展开");
                        }
                        redrawRef[0].run();
                    });
                }
            });
        }

        // 底部：从文件管理器选择——与导入同款强调色按钮
        MaterialButton fromFiles = new MaterialButton(context);
        fromFiles.setAllCaps(false);
        fromFiles.setText("从文件管理器选择");
        fromFiles.setTextSize(13);
        // v614：文件夹贴图（用户提供 SVG），白图标配强调色底
        fromFiles.setIconResource(R.drawable.ic_import_folder);
        fromFiles.setIconTint(android.content.res.ColorStateList.valueOf(0xFF8A8A8A));
        fromFiles.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        fromFiles.setIconPadding((int) (6 * density));
        fromFiles.setMinWidth(0);
        fromFiles.setMinimumWidth(0);
        LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * density));
        fbp.topMargin = (int) (8 * density);
        fromFiles.setLayoutParams(fbp);
        AccentStyler.stylePrimary(context, fromFiles);
        fromFiles.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPickFromFiles();
            }
        });
        root.addView(fromFiles);

        // v613：限高做成可复用回调——内容动态变化（扫描完成/切换
        // 分类/进二级菜单）后重新测量，避免弹窗被撑出屏幕
        final Window[] wRef = new Window[1];
        final int[] widthArr = new int[]{DialogSizer.dialogWidth(context, 560)};
        final int[] maxHArr = new int[]{DialogSizer.dialogMaxHeight(context)};
        final LinearLayout[] rootRef = new LinearLayout[]{root};
        Runnable relimit = () -> {
            Window ww = wRef[0];
            if (ww == null) {
                return;
            }
            ww.setLayout(widthArr[0], ViewGroup.LayoutParams.WRAP_CONTENT);
            rootRef[0].post(() -> {
                if (rootRef[0].getHeight() > maxHArr[0]) {
                    ww.setLayout(widthArr[0], maxHArr[0]);
                }
            });
        };
        relimitRef = relimit;

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        wRef[0] = w;
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(widthArr[0], ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        relimit.run();
    }

    private static int firstNonEmptyType(List<GlobalImportScanner.Candidate> list) {
        for (int g = 0; g < GROUPS.length; g++) {
            for (GlobalImportScanner.Candidate c : list) {
                if (c.type == GROUPS[g][0]) {
                    return GROUPS[g][0];
                }
            }
        }
        return GROUPS[0][0];
    }

    /** 分类卡：图标 + 名称 + 数量，选中态 accent 描边。 */
    private static View buildCategoryCard(Context context, int group, int count,
                                          float density, int accent, int textMain,
                                          int cardBg, boolean selected,
                                          View.OnClickListener onClick) {
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
        // v615：分类卡图标灰色（对齐内容管理分类图标观感）
        icon.setColorFilter(0xFF8A8A8A);
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

    /** 条目行：方形 pack_icon + 名称 + 版本·大小；点本体进二级详情。 */
    private static View buildItemRow(Context context, GlobalImportScanner.Candidate c,
                                     int textMain, int textSub, int cardBg, float density,
                                     int accent, Listener listener, Dialog dialog,
                                     Runnable redraw, LinearLayout content,
                                     EditText search, TextView status) {
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

        // v609：pack_icon 方形原样显示（内容管理同款，不裁圆）
        LinearLayout iconWrap = new LinearLayout(context);
        iconWrap.setGravity(Gravity.CENTER);
        int iconSize = (int) (34 * density);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        iconWrap.setBackground(roundBg(context, context.getColor(R.color.background)));
        ImageView icon = new ImageView(context);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        applyThumb(context, icon, c, iconSize, textSub);
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
        String subs = c.subManifests != null && c.subManifests.size() > 1
                ? "含" + c.subManifests.size() + "包 · " : "";
        meta.setText(ver + subs + formatSize(c.size));
        meta.setTextColor(textSub);
        meta.setTextSize(11);
        info.addView(meta);

        MaterialButton importBtn = new MaterialButton(context);
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
        View.OnClickListener toDetail = v -> showDetail(content, context, c, textMain,
                textSub, cardBg, density, accent, listener, dialog, redraw, search, status);
        iconWrap.setOnClickListener(toDetail);
        info.setOnClickListener(toDetail);
        return row;
    }

    /** 二级详情：清单文件信息 / 存档 NBT 信息 / 子包跳转。 */
    private static void showDetail(LinearLayout content, Context context,
                                   GlobalImportScanner.Candidate c, int textMain,
                                   int textSub, int cardBg, float density, int accent,
                                   Listener listener, Dialog dialog, Runnable back,
                                   EditText search, TextView status) {
        // v609：二级菜单里隐藏搜索框和"发现 N 项"提示
        search.setVisibility(View.GONE);
        status.setVisibility(View.GONE);        if (cardsHostView != null) {
            cardsHostView.setVisibility(View.GONE);
        }
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
        applyThumb(context, icon, c, iconSize, textSub);
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

        // ---- 存档：level.dat 解析信息 ----
        if (c.type == GlobalImportScanner.TYPE_WORLD && c.levelInfo != null) {
            content.addView(buildInfoCard(context, "存档信息", buildWorldInfoRows(context,
                    c.levelInfo, textMain, textSub, density), textMain, textSub, cardBg, density));
        }

        // ---- 包：清单文件信息（主清单：单包=mainManifest，多包=第一个） ----
        if (c.type != GlobalImportScanner.TYPE_WORLD
                && c.type != GlobalImportScanner.TYPE_STRUCTURE) {
            GlobalImportScanner.SubManifest main = c.mainManifest;
            if (main == null && c.subManifests != null && !c.subManifests.isEmpty()) {
                main = c.subManifests.get(0);
            }
            if (main != null) {
                List<String> rows = new ArrayList<>();
                rows.add("名称：" + main.name);
                if (!main.version.isEmpty()) {
                    rows.add("版本：v" + main.version);
                }
                rows.add("类型：" + (main.isBehavior() ? "行为包（BP）" : "资源包（RP）"));
                if (!main.description.isEmpty()) {
                    rows.add("描述：" + main.description);
                }
                content.addView(buildInfoCard(context, "清单文件（manifest.json）",
                        rows.toArray(new String[0]), textMain, textSub, cardBg, density));
            }
        }

        // ---- 包：多包子包列表（仅 mcaddon） ----
        if (c.subManifests != null && !c.subManifests.isEmpty()) {
            // 子包分区：bp/rp 列表可点击切换详情
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
            subLabel.setText("内含 " + c.subManifests.size() + " 个子包（点击查看依赖）");
            subLabel.setTextColor(textSub);
            subLabel.setTextSize(11);
            subCard.addView(subLabel);

            for (GlobalImportScanner.SubManifest sm : c.subManifests) {
                TextView si = new TextView(context);
                String tag = sm.isBehavior() ? "〔行为包〕" : "〔资源包〕";
                String v = sm.version.isEmpty() ? "" : "  v" + sm.version;
                si.setText("· " + sm.name + v + " " + tag);
                si.setTextColor(accent);
                si.setTextSize(12);
                si.setPadding((int) (4 * density), (int) (6 * density), 0, 0);
                // 点子包切换该子包的清单详情
                si.setOnClickListener(v2 -> showSubDetail(content, context, sm, c, textMain,
                        textSub, cardBg, density, accent, listener, dialog, back, search, status));
                subCard.addView(si);
            }
        }

        // 所在位置
        content.addView(buildInfoCard(context, "所在位置",
                new String[]{c.path}, textMain, textSub, cardBg, density));

        // ---- 底部按钮：返回列表 + 导入（同款强调色） ----
        // v613：按钮区按 M3 规范——右对齐、内容宽度、按钮间 8dp 间距
        LinearLayout btns = new LinearLayout(context);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btp.topMargin = (int) (2 * density);
        btns.setLayoutParams(btp);
        content.addView(btns);

        MaterialButton backBtn = new MaterialButton(context);
        backBtn.setAllCaps(false);
        backBtn.setText("← 返回列表");
        backBtn.setTextSize(13);
        backBtn.setMinWidth(0);
        backBtn.setMinimumWidth(0);
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (42 * density));
        backBtn.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
        btns.addView(backBtn, bbp);
        AccentStyler.stylePrimary(context, backBtn);
        backBtn.setOnClickListener(v -> {
            search.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);        if (cardsHostView != null) {
            cardsHostView.setVisibility(View.VISIBLE);
        }
            back.run();
        });

        MaterialButton importBtn = new MaterialButton(context);
        importBtn.setAllCaps(false);
        importBtn.setText("导入");
        importBtn.setTextSize(14);
        importBtn.setMinWidth(0);
        importBtn.setMinimumWidth(0);
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (42 * density));
        ibp.leftMargin = (int) (8 * density);
        importBtn.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
        btns.addView(importBtn, ibp);
        AccentStyler.stylePrimary(context, importBtn);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
        // v613：二级菜单内容变化后重新测量弹窗高度
        if (relimitRef != null) {
            relimitRef.run();
        }
    }

    /** 子包清单详情（addon 的 bp/rp 各自 manifest 信息 + 依赖）。 */
    private static void showSubDetail(LinearLayout content, Context context,
                                      GlobalImportScanner.SubManifest sm,
                                      GlobalImportScanner.Candidate c, int textMain,
                                      int textSub, int cardBg, float density, int accent,
                                      Listener listener, Dialog dialog, Runnable back,
                                      EditText search, TextView status) {
        search.setVisibility(View.GONE);
        status.setVisibility(View.GONE);        if (cardsHostView != null) {
            cardsHostView.setVisibility(View.GONE);
        }
        content.removeAllViews();

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(roundBg(context, cardBg));
        head.setPadding((int) (12 * density), (int) (12 * density),
                (int) (12 * density), (int) (12 * density));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = (int) (8 * density);
        head.setLayoutParams(hp);
        content.addView(head);

        TextView tag = new TextView(context);
        tag.setText(sm.isBehavior() ? "行为包（BP）" : "资源包（RP）");
        tag.setTextColor(accent);
        tag.setTextSize(11);
        tag.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(tag);

        TextView name = new TextView(context);
        name.setText(sm.name);
        name.setTextColor(textMain);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setPadding(0, (int) (4 * density), 0, 0);
        head.addView(name);

        String ver = sm.version.isEmpty() ? "" : " · v" + sm.version;
        TextView meta = new TextView(context);
        meta.setText("清单版本" + ver + "  ·  来自 " + c.name);
        meta.setTextColor(textSub);
        meta.setTextSize(11);
        meta.setPadding(0, (int) (3 * density), 0, 0);
        head.addView(meta);

        if (!sm.description.isEmpty()) {
            content.addView(buildInfoCard(context, "描述", new String[]{sm.description},
                    textMain, textSub, cardBg, density));
        }
        if (sm.dependencies != null && !sm.dependencies.isEmpty()) {
            String[] deps = sm.dependencies.toArray(new String[0]);
            content.addView(buildInfoCard(context, "依赖的包（" + deps.length + "）",
                    deps, textMain, textSub, cardBg, density));
        }

        // v613：按钮区按 M3 规范——右对齐、内容宽度、按钮间 8dp 间距
        LinearLayout btns = new LinearLayout(context);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btp.topMargin = (int) (2 * density);
        btns.setLayoutParams(btp);
        content.addView(btns);

        MaterialButton backBtn = new MaterialButton(context);
        backBtn.setAllCaps(false);
        backBtn.setText("← 返回列表");
        backBtn.setTextSize(13);
        backBtn.setMinWidth(0);
        backBtn.setMinimumWidth(0);
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (42 * density));
        backBtn.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
        btns.addView(backBtn, bbp);
        AccentStyler.stylePrimary(context, backBtn);
        backBtn.setOnClickListener(v -> {
            search.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);        if (cardsHostView != null) {
            cardsHostView.setVisibility(View.VISIBLE);
        }
            back.run();
        });

        MaterialButton importBtn = new MaterialButton(context);
        importBtn.setAllCaps(false);
        importBtn.setText("导入");
        importBtn.setTextSize(14);
        importBtn.setMinWidth(0);
        importBtn.setMinimumWidth(0);
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (42 * density));
        ibp.leftMargin = (int) (8 * density);
        importBtn.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
        btns.addView(importBtn, ibp);
        AccentStyler.stylePrimary(context, importBtn);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
        // v613：子包详情内容变化后重新测量弹窗高度
        if (relimitRef != null) {
            relimitRef.run();
        }
    }

    /** 通用信息卡：标题 + 多行内容。 */
    private static View buildInfoCard(Context context, String title, String[] rows,
                                      int textMain, int textSub, int cardBg, float density) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(context, cardBg));
        card.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = (int) (8 * density);
        card.setLayoutParams(cp);

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextColor(textSub);
        label.setTextSize(11);
        card.addView(label);
        for (String r : rows) {
            TextView t = new TextView(context);
            t.setText(r);
            t.setTextColor(textMain);
            t.setTextSize(12);
            t.setPadding(0, (int) (4 * density), 0, 0);
            card.addView(t);
        }
        return card;
    }

    /** 存档 level.dat 信息行。 */
    private static String[] buildWorldInfoRows(Context context,
                                               GlobalImportScanner.LevelInfo info,
                                               int textMain, int textSub, float density) {
        List<String> rows = new ArrayList<>();
        if (!info.version.isEmpty()) {
            rows.add("游戏版本：" + info.version);
        }
        if (!info.levelName.isEmpty()) {
            rows.add("世界名：" + info.levelName);
        }
        if (!info.seed.isEmpty()) {
            rows.add("种子：" + info.seed);
        }
        if (!info.gameType.isEmpty()) {
            rows.add("游戏模式：" + info.gameType);
        }
        if (info.lastPlayed > 0) {
            rows.add("最后游玩：" + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                    .format(new Date(info.lastPlayed)));
        }
        if (rows.isEmpty()) {
            rows.add("（level.dat 无有效信息）");
        }
        return rows.toArray(new String[0]);
    }

    /** v611：图标照搬启动器内容管理——centerCrop 方形裁切 + 10dp 圆角；
     * 无贴图用默认图（皮肤包 ic_tshirt）。 */
    private static void applyThumb(Context context, ImageView iv,
                                   GlobalImportScanner.Candidate c, int sizePx,
                                   int tintColor) {
        Bitmap bmp = c.icon != null
                ? BitmapFactory.decodeByteArray(c.icon, 0, c.icon.length) : null;
        if (bmp != null) {
            float density = context.getResources().getDisplayMetrics().density;
            iv.setImageBitmap(centerCropRound(bmp, sizePx, (int) (10 * density)));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            iv.setImageResource(c.skinPack ? R.drawable.ic_tshirt
                    : ICONS[Math.min(c.type, ICONS.length - 1)]);
            iv.setColorFilter(tintColor);
        }
    }

    /** centerCrop 到 size×size 再圆角（启动器 LeviContentThumbnailShape 同款）。 */
    private static Bitmap centerCropRound(Bitmap src, int size, int radius) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int side = Math.min(w, h);
            int sx = (w - side) / 2;
            int sy = (h - side) / 2;
            Bitmap cropped = Bitmap.createBitmap(src, sx, sy, side, side);
            if (side != size) {
                cropped = Bitmap.createScaledBitmap(cropped, size, size, true);
            }
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.Path path = new android.graphics.Path();
            path.addRoundRect(new android.graphics.RectF(0, 0, size, size),
                    radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(path);
            canvas.drawBitmap(cropped, 0, 0, paint);
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
