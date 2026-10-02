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
    /** v647：缓存仅在完整扫描完成后可用（扫描中途关弹窗缓存不完整）。 */
    private static boolean scanComplete;
    /** v647：四张分类卡的数量文本（增量扫描时直接更新，不重建卡片区）。 */
    private static final TextView[] cardCountViews = new TextView[4];
    private static Runnable relimitRef;
    private static View cardsHostView;
    private static int expandedType = -1;
    /** v652：搜索抽屉展开状态（二级菜单返回时按此恢复）。 */
    private static boolean searchOpenState;
    /** v653：条目两列/一列切换（跨弹窗记忆；首开按设备——手机两列、平板一列）。 */
    private static boolean gridMode;
    private static boolean gridModeInit;
    /** v656：批量导入多选状态（长按进入选择模式；勾选集合按 path 记）。 */
    private static boolean selectionMode;
    private static final java.util.Set<String> selectedPaths = new java.util.HashSet<>();
    /** v656：勾选变化回调（更新底部「导入 N 项」按钮）。 */
    private static Runnable onSelectionChangedRef;
    // v662：动画时长常量（照搬 ZalithLauncher2 FmAnimations 思路——
    // 全弹窗动画统一走这些常量，不再散落魔法数字）
    private static final int FADE_IN_MS = 200;
    private static final int FADE_OUT_MS = 180;
    /** v662：网格最小列宽/间距/列数上限（Zalith 自适应列数公式参数）。 */
    private static final int MIN_COLUMN_DP = 170;
    private static final int GRID_GAP_DP = 2;
    private static final int MAX_COLUMNS = 3;

    /** v662：网格列数（照搬 ZalithLauncher2 自适应公式——最小列宽 170dp+
     *  2dp 间距，按弹窗可用宽度自动算列数，上限 3；列表模式恒 1）。 */
    private static int gridColumns(boolean grid, float density, int dialogWidth) {
        if (!grid) {
            return 1;
        }
        float minCol = MIN_COLUMN_DP * density;
        float gap = GRID_GAP_DP * density;
        float avail = Math.max(minCol, dialogWidth - 40 * density);
        int columns = (int) Math.floor((avail - 24 * density + gap) / (minCol + gap));
        return Math.max(1, Math.min(columns, MAX_COLUMNS));
    }

    /** v656：切换勾选并更新卡片样式（选中 = accent 描边 + 半透明底）。 */
    private static void toggleCardSelection(View card, GlobalImportScanner.Candidate c,
                                            int cardBg, int accent, float density) {
        boolean now = !selectedPaths.contains(c.path);
        if (now) {
            selectedPaths.add(c.path);
        } else {
            selectedPaths.remove(c.path);
        }
        applySelectionStyle(card, now, cardBg, accent, density);
    }

    // v646：缩略图 LRU 缓存 + 后台解码线程池——200 条级别条目列表重建时
    // 不再主线程重复解码/裁切位图（点分类卡顿挫的根因）
    private static final int THUMB_CACHE_MAX = 128;
    private static final java.util.Map<String, Bitmap> thumbCache =
            new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> e) {
                    return size() > THUMB_CACHE_MAX;
                }
            };
    private static final java.util.concurrent.ExecutorService thumbPool =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "import-thumb");
                t.setDaemon(true);
                return t;
            });

    // v648：扫描结果持久化缓存——打开弹窗立即显示上次结果，后台重扫刷新
    private static final String CACHE_FILE = "import_scan_cache.json";

    private static void saveScanCache(Context context, List<GlobalImportScanner.Candidate> list) {
        if (list == null) {
            return;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (GlobalImportScanner.Candidate c : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("path", c.path);
                o.put("type", c.type);
                o.put("name", c.name == null ? "" : c.name);
                o.put("size", c.size);
                o.put("version", c.version == null ? "" : c.version);
                o.put("skinPack", c.skinPack);
                o.put("subCount", c.subManifests == null ? 0 : c.subManifests.size());
                o.put("levelName", c.levelInfo != null ? c.levelInfo.levelName : "");
                arr.put(o);
            }
            try (java.io.FileOutputStream fos = context.getApplicationContext()
                    .openFileOutput(CACHE_FILE, Context.MODE_PRIVATE)) {
                fos.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读持久化缓存（icon 不存——由后台线程按路径重新提取）。 */
    private static List<GlobalImportScanner.Candidate> loadScanCache(Context context) {
        try {
            java.io.File f = new java.io.File(
                    context.getApplicationContext().getFilesDir(), CACHE_FILE);
            if (!f.exists()) {
                return null;
            }
            byte[] raw = new byte[(int) f.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < raw.length) {
                    int n = fis.read(raw, off, raw.length - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
            }
            org.json.JSONArray arr = new org.json.JSONArray(
                    new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            List<GlobalImportScanner.Candidate> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String path = o.optString("path", "");
                java.io.File file = new java.io.File(path);
                if (!file.exists()) {
                    continue; // 文件已删/改名，跳过
                }
                GlobalImportScanner.Candidate c = new GlobalImportScanner.Candidate();
                c.type = o.optInt("type");
                c.name = o.optString("name", file.getName());
                c.file = file;
                c.size = o.optLong("size");
                c.path = path;
                c.version = o.optString("version", "");
                c.skinPack = o.optBoolean("skinPack", false);
                out.add(c);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** v648：调试日志（宽度排查用，files/import_debug.log）。 */
    private static void dbg(Context context, String msg) {
        try {
            try (java.io.FileOutputStream fos = context.getApplicationContext()
                    .openFileOutput("import_debug.log", Context.MODE_APPEND)) {
                fos.write((msg + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

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
        // v651：手机专属布局——条目两列网格 + 分类卡收小（平板保持原样）
        final boolean phone = DialogSizer.isPhone(context);
        // v653：两列/一列首开按设备默认，此后跟随用户切换
        if (!gridModeInit) {
            gridMode = phone;
            gridModeInit = true;
        }
        int accent = new org.levimc.launcher.util.PersonalizationManager(context).getAccentColor();
        int textMain = context.getColor(R.color.on_surface);
        int textSub = context.getColor(R.color.text_secondary);
        int cardBg = context.getColor(R.color.surface_high);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_rounded_card);
        root.setPadding((int) (20 * density), (int) (18 * density),
                (int) (20 * density), (int) (16 * density));

        // v652：标题删除（用户：头部占一半空间，弹窗标题信息量低）
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
        slp.topMargin = (int) (4 * density);
        search.setLayoutParams(slp);
        // v652：搜索抽屉式——默认收起，点状态行右侧图标展开
        search.setVisibility(searchOpenState ? View.VISIBLE : View.GONE);
        // v653：放大镜图标进文本框内（右侧 drawableEnd）
        search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0);
        search.setCompoundDrawablePadding((int) (8 * density));
        android.graphics.drawable.Drawable[] cds = search.getCompoundDrawables();
        if (cds[2] != null) {
            cds[2].setTint(textSub);
        }
        root.addView(search);

        TextView status = new TextView(context);
        status.setText("正在扫描…");
        status.setTextColor(textSub);
        status.setTextSize(10); // v652：状态字小一点
        status.setPadding(0, (int) (2 * density), 0, 0);

        // v652：状态行——状态文字 + 布局切换图标 + 搜索抽屉图标
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(status, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // v653：两列/一列自由切换（图标显示当前布局）
        ImageView layoutToggle = new ImageView(context);
        layoutToggle.setImageResource(gridMode ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        layoutToggle.setColorFilter(textSub);
        layoutToggle.setPadding((int) (4 * density), (int) (2 * density),
                (int) (4 * density), (int) (2 * density));
        statusRow.addView(layoutToggle);
        ImageView searchToggle = new ImageView(context);
        searchToggle.setImageResource(R.drawable.ic_search);
        searchToggle.setColorFilter(textSub);
        searchToggle.setPadding((int) (4 * density), (int) (2 * density),
                (int) (2 * density), (int) (2 * density));
        // v653：展开后图标进文本框内（drawableEnd），状态行把手隐藏
        searchToggle.setVisibility(searchOpenState ? View.GONE : View.VISIBLE);
        statusRow.addView(searchToggle);
        root.addView(statusRow);
        // v653：搜索抽屉开关（带滑出/收回动画——启动器补动画）
        final Runnable[] toggleRef = new Runnable[1];
        toggleRef[0] = () -> {
            searchOpenState = !searchOpenState;
            if (searchOpenState) {
                searchToggle.setVisibility(View.GONE);
                search.setVisibility(View.VISIBLE);
                search.setAlpha(0f);
                search.setTranslationY(-(int) (14 * density));
                search.animate().alpha(1f).translationY(0).setDuration(FADE_IN_MS).start();
                search.requestFocus();
            } else {
                search.clearFocus();
                search.animate().alpha(0f).translationY(-(int) (14 * density))
                        .setDuration(150).withEndAction(() -> {
                            search.setVisibility(View.GONE);
                            searchToggle.setVisibility(View.VISIBLE);
                        }).start();
            }
            if (relimitRef != null) {
                relimitRef.run();
            }
        };
        searchToggle.setOnClickListener(v -> toggleRef[0].run());
        // 点文本框内的放大镜（drawableEnd 区域）也收起
        search.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP && searchOpenState) {
                float iconZone = search.getWidth() - search.getPaddingRight() - 44 * density;
                if (ev.getX() > iconZone) {
                    toggleRef[0].run();
                    return true;
                }
            }
            return false;
        });

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
        final int[] widthArr = new int[]{DialogSizer.dialogWidth(context, 560)}; // v662：前移供列数公式用
        final Runnable[] redrawRef = new Runnable[1];
        // v647：增量扫描状态——rendered=已渲染游标（results 索引）、
        // lastFlush=上次刷新时间戳、pendingFlush=是否有排队的刷新、
        // closed=弹窗已关闭（回调静默）
        final int[] rendered = {0};
        final long[] lastFlush = {0L};
        final boolean[] pendingFlush = {false};
        final boolean[] closed = {false};
        // v648：已知路径去重（持久化缓存加载后，重扫发现的新增项跳过已有）
        final java.util.Set<String> knownPaths = new java.util.HashSet<>();
        // v651/v662：网格增量 append 凑行暂存（凑满一列数即渲染一行）
        final List<GlobalImportScanner.Candidate>[] pendingGroup = new List[]{new ArrayList<>()};
        android.util.DisplayMetrics dmd = context.getResources().getDisplayMetrics();
        dbg(context, "show: density=" + dmd.density + " widthPixels=" + dmd.widthPixels
                + " heightPixels=" + dmd.heightPixels
                + " dialogWidth=" + DialogSizer.dialogWidth(context, 560)
                + " isPhone=" + DialogSizer.isPhone(context));

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
            // 分类卡（固定区一行四个，单展开位切换）；
            // v616：卡片区与条目区间距 8dp——修"卡片与条目重叠"
            LinearLayout cards = new LinearLayout(context);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardsLp.bottomMargin = (int) (8 * density);
            cardsHost.addView(cards, cardsLp);
            for (int g = 0; g < GROUPS.length; g++) {
                final int type = GROUPS[g][0];
                int count = 0;
                for (GlobalImportScanner.Candidate c : filtered) {
                    if (c.type == type) {
                        count++;
                    }
                }
                View card = buildCategoryCard(context, g, count, density, accent,
                        textMain, cardBg, type == expandedType, phone, v -> {
                            // v610：单展开位——必须始终有一个分类展开
                            // （点已展开卡不再收起，避免下方出现大空缺）
                            // v653：防御——点已展开卡直接无操作
                            if (expandedType == type) {
                                return;
                            }
                            expandedType = type;
                            redrawRef[0].run();
                        });
                // v647：缓存计数文本引用（增量扫描直接更新）
                View cnt = card.findViewWithTag("count");
                cardCountViews[g] = cnt instanceof TextView ? (TextView) cnt : null;
                cards.addView(card);
            }
            // 条目列表（仅展开分类）+ 卡片与条目的间距
            if (expandedType >= 0) {
                TextView gap = new TextView(context);
                gap.setText(" ");
                gap.setTextSize(4);
                content.addView(gap);
                boolean any = false;
                // v662：网格列数自适应（ZalithLauncher2 公式：最小列宽
                // 170dp+2dp 间距，按弹窗可用宽度自动算列数，上限 3 列；
                // 用户切到列表模式恒 1 列）
                int columns = gridColumns(gridMode, density, widthArr[0]);
                if (columns > 1) {
                    List<GlobalImportScanner.Candidate> group = new ArrayList<>();
                    for (GlobalImportScanner.Candidate c : filtered) {
                        if (c.type != expandedType) {
                            continue;
                        }
                        any = true;
                        group.add(c);
                        if (group.size() == columns) {
                            content.addView(buildGridRow(context, group, columns, textMain,
                                    textSub, cardBg, density, accent, listener, dialog,
                                    redrawRef[0], content, search, status));
                            group = new ArrayList<>();
                        }
                    }
                    if (!group.isEmpty()) {
                        // 末行不足：右端留空补齐
                        content.addView(buildGridRow(context, group, columns, textMain, textSub,
                                cardBg, density, accent, listener, dialog, redrawRef[0],
                                content, search, status));
                    }
                } else {
                    for (GlobalImportScanner.Candidate c : filtered) {
                        if (c.type != expandedType) {
                            continue;
                        }
                        any = true;
                        content.addView(buildItemRow(context, c, textMain, textSub, cardBg,
                                density, accent, listener, dialog, redrawRef[0], content,
                                search, status));
                    }
                }
                if (!any) {
                    TextView t = new TextView(context);
                    t.setText("该分类暂无匹配内容");
                    t.setTag("empty"); // v651：增量 append 前删占位
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
            // v653：重建后条目区淡入（启动器补动画；扫描增量 append 不触发）
            content.setAlpha(0.55f);
            content.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            // v647：全量重建后增量游标对齐（已发现的候选都已渲染）
            rendered[0] = list.size();
            // v651/v662：全量重建后网格凑行暂存清零（重建已含末行项）
            pendingGroup[0].clear();
        };

        // v653：两列/一列切换（redrawRef 就绪后绑定；图标显示当前布局）
        layoutToggle.setOnClickListener(v -> {
            gridMode = !gridMode;
            layoutToggle.setImageResource(gridMode
                    ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
            // v662：布局切换缩放回弹动画（Zalith 动画风格）
            content.setScaleX(0.97f);
            content.setScaleY(0.97f);
            content.animate().scaleX(1f).scaleY(1f).setDuration(FADE_IN_MS).start();
            redrawRef[0].run();
        });

        // v646：搜索防抖 300ms——每敲一个字符不再立即全量重建列表
        final Runnable[] debounceRef = new Runnable[1];
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query[0] = s.toString();
                if (debounceRef[0] != null) {
                    search.removeCallbacks(debounceRef[0]);
                }
                Runnable debounced = () -> redrawRef[0].run();
                debounceRef[0] = debounced;
                search.postDelayed(debounced, 300);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // v647：缓存仅在完整扫描完成后可用；扫描进行中重开弹窗则换绑监听者接管
        if (cachedCandidates != null && scanComplete) {
            results[0] = cachedCandidates;
            if (expandedType < 0) {
                expandedType = firstNonEmptyType(cachedCandidates);
            }
            status.setText("发现 " + cachedCandidates.size() + " 项，点分类卡展开");
            redrawRef[0].run();
        } else {
            results[0] = new ArrayList<>();
            // v647：静态展开态/计数引用是上一弹窗的——新弹窗必须重置，
            // 否则首个 onFound 不触发全量渲染，分类卡区恒空
            expandedType = -1;
            rendered[0] = 0;
            for (int i = 0; i < cardCountViews.length; i++) {
                cardCountViews[i] = null;
            }
            // 初始占位（首个候选到达前的空态，首次 onFound 全量渲染时清掉）
            TextView placeholder = new TextView(context);
            placeholder.setText("正在扫描，请稍候…");
            placeholder.setTextColor(textSub);
            placeholder.setTextSize(12);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setPadding(0, (int) (20 * density), 0, 0);
            content.addView(placeholder);
            int live = GlobalImportScanner.liveCount();
            status.setText(live > 0 ? "正在扫描…（已有 " + live + " 项）" : "正在扫描…");
            // v648：持久化缓存——打开弹窗立即显示上次扫描结果，后台重扫刷新
            List<GlobalImportScanner.Candidate> cached = loadScanCache(context);
            if (cached != null) {
                results[0] = cached;
                for (GlobalImportScanner.Candidate c : cached) {
                    knownPaths.add(c.path);
                }
                expandedType = firstNonEmptyType(cached);
                status.setText("已载入上次结果 " + cached.size() + " 项，正在刷新…");
                redrawRef[0].run();
            } else {
                // v706：无磁盘缓存但首次扫描仍在进行（首次打开没扫完就
                // 退出，二次打开）——直接用扫描线程已发现的结果填充，
                // 否则要等深潜阶段的新 onFound 才显示（浅层资源早已发现，
                // 新候选迟迟不来，弹窗一直挂着「正在扫描」占位）
                List<GlobalImportScanner.Candidate> soFar =
                        GlobalImportScanner.foundSoFar();
                if (soFar != null && !soFar.isEmpty()) {
                    List<GlobalImportScanner.Candidate> copy;
                    try {
                        copy = new ArrayList<>(soFar);
                    } catch (Throwable t) {
                        copy = new ArrayList<>();
                    }
                    if (!copy.isEmpty()) {
                        results[0] = copy;
                        for (GlobalImportScanner.Candidate c : copy) {
                            knownPaths.add(c.path);
                        }
                        expandedType = firstNonEmptyType(copy);
                        status.setText("已发现 " + copy.size() + " 项，仍在扫描…");
                        redrawRef[0].run();
                    }
                }
            }
            // v647：批量刷新——150ms 合并一批增量（只 append 新行+更新计数，
            // 不做全量重建，扫描期间列表平滑增长）
            final Runnable[] flushRef = new Runnable[1];
            flushRef[0] = () -> {
                pendingFlush[0] = false;
                if (closed[0] || !GlobalImportScanner.isScanning()) {
                    return;
                }
                List<GlobalImportScanner.Candidate> list = results[0];
                if (list == null) {
                    return;
                }
                // v651：新增条目前删掉空态占位（「该分类暂无匹配内容」）
                View emptyView = content.findViewWithTag("empty");
                if (emptyView != null) {
                    content.removeView(emptyView);
                }
                for (int i = rendered[0]; i < list.size(); i++) {
                    GlobalImportScanner.Candidate c = list.get(i);
                    if (c.type != expandedType
                            || (!query[0].isEmpty() && !c.name.toLowerCase(Locale.US)
                                    .contains(query[0].toLowerCase(Locale.US)))) {
                        continue;
                    }
                    if (gridMode) { // v653：两列/一列可切换
                        // v662：网格凑行追加（凑满自适应列数即渲染一行）
                        pendingGroup[0].add(c);
                        int cols = gridColumns(true, density, widthArr[0]);
                        if (pendingGroup[0].size() == cols) {
                            content.addView(buildGridRow(context, new ArrayList<>(pendingGroup[0]),
                                    cols, textMain, textSub, cardBg, density, accent, listener,
                                    dialog, redrawRef[0], content, search, status));
                            pendingGroup[0].clear();
                        }
                    } else {
                        content.addView(buildItemRow(context, c, textMain, textSub, cardBg,
                                density, accent, listener, dialog, redrawRef[0], content,
                                search, status));
                    }
                }
                // v706：网格凑行余量直接渲染（不满一行也显示，末尾条目
                // 不再延迟到 onDone 全量重建才出现）
                if (gridMode && !pendingGroup[0].isEmpty()) {
                    int cols = gridColumns(true, density, widthArr[0]);
                    content.addView(buildGridRow(context, new ArrayList<>(pendingGroup[0]),
                            cols, textMain, textSub, cardBg, density, accent, listener,
                            dialog, redrawRef[0], content, search, status));
                    pendingGroup[0].clear();
                }
                rendered[0] = list.size();
                // 分类卡计数增量更新（与全量重建同口径：按过滤后计数）
                for (int g = 0; g < GROUPS.length; g++) {
                    if (cardCountViews[g] == null) {
                        continue;
                    }
                    int type = GROUPS[g][0];
                    int count = 0;
                    for (GlobalImportScanner.Candidate cc : list) {
                        if (cc.type == type
                                && (query[0].isEmpty() || cc.name.toLowerCase(Locale.US)
                                        .contains(query[0].toLowerCase(Locale.US)))) {
                            count++;
                        }
                    }
                    cardCountViews[g].setText(LABELS[g] + (count > 0 ? "  " + count : ""));
                }
                status.setText("已发现 " + list.size() + " 项，仍在扫描…");
                lastFlush[0] = android.os.SystemClock.uptimeMillis();
                if (relimitRef != null) {
                    relimitRef.run();
                }
            };
            GlobalImportScanner.scanAsync(new GlobalImportScanner.Listener() {
                @Override
                public void onProgress(String scanning) {
                    runOnUi(context, () -> {
                        if (closed[0]) {
                            return;
                        }
                        // v656：显示相对路径（去掉 /storage/emulated/0 前缀），
                        // 超长截断保留尾部（正在扫哪个目录一眼可见）
                        String p = scanning == null ? "" : scanning;
                        if (p.startsWith("/storage/emulated/0/")) {
                            p = p.substring("/storage/emulated/0/".length());
                        } else if (p.equals("/storage/emulated/0")) {
                            p = "";
                        }
                        if (p.length() > 42) {
                            p = "…" + p.substring(p.length() - 41);
                        }
                        status.setText("正在扫描：" + p);
                    });
                }

                @Override
                public void onFound(GlobalImportScanner.Candidate c) {
                    runOnUi(context, () -> {
                        if (closed[0]) {
                            return;
                        }
                        // v648：持久化缓存里已显示的条目不重复追加
                        if (!knownPaths.add(c.path)) {
                            return;
                        }
                        List<GlobalImportScanner.Candidate> list = results[0];
                        list.add(c);
                        cachedCandidates = list;
                        if (expandedType < 0) {
                            // 首个候选：定展开分类并首次全量渲染
                            expandedType = firstNonEmptyType(list);
                            redrawRef[0].run();
                            lastFlush[0] = android.os.SystemClock.uptimeMillis();
                            return;
                        }
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastFlush[0] >= 150) {
                            lastFlush[0] = now;
                            flushRef[0].run();
                        } else if (!pendingFlush[0]) {
                            pendingFlush[0] = true;
                            status.postDelayed(flushRef[0], 150);
                        }
                    });
                }

                @Override
                public void onDone(List<GlobalImportScanner.Candidate> candidates) {
                    runOnUi(context, () -> {
                        cachedCandidates = candidates;
                        scanComplete = true;
                        // v648：完整结果写持久化缓存（下次打开立即显示）
                        saveScanCache(context, candidates);
                        if (closed[0]) {
                            return;
                        }
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

        // v656：多选操作条——「取消」（退出多选） + 「导入 N 项」（批量导入）
        LinearLayout batchBar = new LinearLayout(context);
        batchBar.setOrientation(LinearLayout.HORIZONTAL);
        batchBar.setGravity(Gravity.END);
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbp.topMargin = (int) (8 * density);
        batchBar.setLayoutParams(bbp);
        batchBar.setVisibility(View.GONE);

        // v656：多选条与勾选回调联动（按钮文字/可用性/可见性）——
        // 提前声明供「取消」按钮引用
        final Runnable[] updateBottomRef = new Runnable[1];

        // v657/v659：取消按钮 UI 兼容——Material 官方文字按钮构造
        //（borderlessButtonStyle attr = TextButton 扁平样式）。注意：
        // ContextThemeWrapper(TextButton 样式) 对 MaterialButton 无效——
        // 它构造时读 theme 的 materialButtonStyle attr 而非样式本身，
        // 导致文字色仍是主题默认深绿（像素实测 (27,94,32)）。
        MaterialButton batchCancel = new MaterialButton(context, null,
                android.R.attr.borderlessButtonStyle);
        batchCancel.setAllCaps(false);
        batchCancel.setText("取消");
        batchCancel.setTextSize(13);
        batchCancel.setMinWidth(0);
        batchCancel.setMinimumWidth(0);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (42 * density));
        batchCancel.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
        batchCancel.setLayoutParams(cbp);
        AccentStyler.styleSecondary(context, batchCancel);
        batchCancel.setOnClickListener(v -> {
            // 取消 = 退出多选模式并清空勾选
            selectionMode = false;
            selectedPaths.clear();
            redrawRef[0].run();
            updateBottomRef[0].run();
        });
        batchBar.addView(batchCancel, cbp);

        MaterialButton batchImport = new MaterialButton(context);
        batchImport.setAllCaps(false);
        batchImport.setText("导入");
        batchImport.setTextSize(13);
        batchImport.setMinWidth(0);
        batchImport.setMinimumWidth(0);
        LinearLayout.LayoutParams ibp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (42 * density));
        ibp2.leftMargin = (int) (8 * density);
        batchImport.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
        batchImport.setLayoutParams(ibp2);
        AccentStyler.stylePrimary(context, batchImport);
        batchImport.setOnClickListener(v -> {
            // 批量导入：按勾选集合逐个回调
            List<GlobalImportScanner.Candidate> all = results[0];
            List<java.io.File> files = new ArrayList<>();
            if (all != null) {
                for (GlobalImportScanner.Candidate c : all) {
                    if (selectedPaths.contains(c.path)) {
                        files.add(c.file);
                    }
                }
            }
            dialog.dismiss();
            if (listener != null) {
                for (java.io.File f : files) {
                    listener.onPick(f);
                }
            }
        });
        batchBar.addView(batchImport, ibp2);
        root.addView(batchBar);

        // v656：多选条与勾选回调联动（按钮文字/可用性/可见性）
        updateBottomRef[0] = () -> {
            boolean sm = selectionMode;
            fromFiles.setVisibility(sm ? View.GONE : View.VISIBLE);
            batchBar.setVisibility(sm ? View.VISIBLE : View.GONE);
            if (sm) {
                batchImport.setText("导入 " + selectedPaths.size() + " 项");
                batchImport.setEnabled(!selectedPaths.isEmpty());
            }
            if (relimitRef != null) {
                relimitRef.run();
            }
        };
        onSelectionChangedRef = updateBottomRef[0];

        // v613：限高做成可复用回调——内容动态变化（扫描完成/切换
        // 分类/进二级菜单）后重新测量，避免弹窗被撑出屏幕
        final Window[] wRef = new Window[1];
        final int[] maxHArr = new int[]{DialogSizer.dialogMaxHeight(context)};
        final LinearLayout[] rootRef = new LinearLayout[]{root};
        // v655：弹窗高度动态自适应（恢复 v613 语义）——每次内容变化后
        // 主动测量内容高度：超限锁 maxH，未超回 WRAP（内容少弹窗跟着缩）。
        // v648 的「锁死不再回 WRAP」是错的：切到内容少的分类弹窗不收缩，
        // 留下大空白（用户反馈「显示内容很少那UI就变小」失效的根因）。
        // 无两帧跳：目标尺寸一步到位（同值 setLayout 不触发重布局）。
        final boolean[] sizeLogged = {false};
        Runnable relimit = () -> {
            Window ww = wRef[0];
            if (ww == null) {
                return;
            }
            rootRef[0].post(() -> {
                if (!sizeLogged[0]) {
                    sizeLogged[0] = true;
                    dbg(context, "relimit: root=" + rootRef[0].getWidth() + "x"
                            + rootRef[0].getHeight() + " widthArr=" + widthArr[0]
                            + " maxH=" + maxHArr[0]);
                }
                // 固定区高度 = 当前 root 高 - scroll 区高（与内容无关）
                int fixed = Math.max(0, rootRef[0].getHeight() - scroll.getHeight());
                // v660：先按条目数估算高度（免全树 measure——v655 每次
                // 内容变化全量 measure 200 条两列卡片整树，是点击卡顿
                // 回归的主因）。估算明显装得下就直接 WRAP 不 measure。
                List<GlobalImportScanner.Candidate> list = results[0];
                int items = 0;
                if (list != null && expandedType >= 0) {
                    for (GlobalImportScanner.Candidate c : list) {
                        if (c.type == expandedType
                                && (query[0].isEmpty() || c.name.toLowerCase(Locale.US)
                                        .contains(query[0].toLowerCase(Locale.US)))) {
                            items++;
                        }
                    }
                }
                int rowH = (int) (160 * density); // 两列/单行行高估算（偏保守）
                int est = items == 0 ? 0
                        : (int) Math.ceil(items / (gridMode ? 2.0 : 1.0)) * rowH;
                if (est + fixed < maxHArr[0] * 0.92) {
                    ww.setLayout(widthArr[0], ViewGroup.LayoutParams.WRAP_CONTENT);
                    return;
                }
                // 接近/超过上限才真 measure（一次性决策，同值设置不重布局）
                int wSpec = View.MeasureSpec.makeMeasureSpec(
                        Math.max(1, scroll.getWidth()), View.MeasureSpec.EXACTLY);
                content.measure(wSpec, View.MeasureSpec.makeMeasureSpec(
                        0, View.MeasureSpec.UNSPECIFIED));
                int contentH = content.getMeasuredHeight();
                if (contentH + fixed > maxHArr[0]) {
                    ww.setLayout(widthArr[0], maxHArr[0]);
                } else {
                    ww.setLayout(widthArr[0], ViewGroup.LayoutParams.WRAP_CONTENT);
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
        // v647：关闭后扫描回调静默（缓存仍由 onDone 收尾写入）
        // v656：关闭弹窗同时退出多选模式并清空勾选（防跨弹窗残留）
        dialog.setOnDismissListener(d -> {
            closed[0] = true;
            selectionMode = false;
            selectedPaths.clear();
        });
        dialog.show();
        // v662：弹窗打开动画（淡入+上滑，Zalith FmAnimations 风格）
        root.setAlpha(0f);
        root.setTranslationY((int) (24 * density));
        root.animate().alpha(1f).translationY(0).setDuration(FADE_IN_MS).start();
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

    /** 分类卡：图标 + 名称 + 数量，选中态 accent 描边。
     *  v651：手机（phone）收小——高 44dp/图标 14dp/字号 10；
     *  v654：平板也收紧间距——padding 8→5dp、卡间 6→3dp、高 72→64dp、
     *  图标 22→20dp（用户反馈平板分类卡间距过大）。 */
    private static View buildCategoryCard(Context context, int group, int count,
                                          float density, int accent, int textMain,
                                          int cardBg, boolean selected, boolean phone,
                                          View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int pad = (int) (5 * density);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(10 * density);
        if (selected) {
            bg.setStroke((int) (1.5f * density), accent);
        }
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                (int) ((phone ? 44 : 64) * density), 1f);
        if (group > 0) {
            cp.leftMargin = (int) (3 * density);
        }
        card.setLayoutParams(cp);
        card.setOnClickListener(onClick);

        ImageView icon = new ImageView(context);
        icon.setImageResource(ICONS[group]);
        // v615：分类卡图标灰色（对齐内容管理分类图标观感）
        icon.setColorFilter(0xFF8A8A8A);
        int iconDp = phone ? 14 : 20;
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (iconDp * density), (int) (iconDp * density)));
        card.addView(icon);

        TextView label = new TextView(context);
        label.setTag("count"); // v647：增量扫描更新计数用
        label.setText(LABELS[group] + (count > 0 ? "  " + count : ""));
        label.setTextColor(textMain);
        label.setTextSize(phone ? 10 : 11);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (3 * density);
        card.addView(label, llp);
        return card;
    }

    /** v651/v662：网格行——N 个竖版条目卡片并排（列数自适应，
     *  末行不足时右端留空补齐）。 */
    private static View buildGridRow(Context context, List<GlobalImportScanner.Candidate> rowItems,
                                     int columns, int textMain, int textSub, int cardBg,
                                     float density, int accent, Listener listener, Dialog dialog,
                                     Runnable redraw, LinearLayout content, EditText search,
                                     TextView status) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = (int) (6 * density);
        row.setLayoutParams(rp);
        int gapPx = (int) (GRID_GAP_DP * density);
        for (int i = 0; i < columns; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = gapPx;
            }
            if (i < rowItems.size()) {
                row.addView(buildGridItem(context, rowItems.get(i), textMain, textSub, cardBg,
                        density, accent, listener, dialog, redraw, content, search, status), lp);
            } else {
                // 末行不足：留空占位保持对齐
                row.addView(new View(context), lp);
            }
        }
        return row;
    }

    /** v651：两列网格里的竖版条目卡——图标上、名字/版本下、导入按钮底部。 */
    private static View buildGridItem(Context context, GlobalImportScanner.Candidate c,
                                      int textMain, int textSub, int cardBg, float density,
                                      int accent, Listener listener, Dialog dialog,
                                      Runnable redraw, LinearLayout content,
                                      EditText search, TextView status) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        // v656：勾选样式（选中 = accent 描边 + 半透明底）
        applySelectionStyle(card, selectedPaths.contains(c.path), cardBg, accent, density);
        card.setPadding((int) (10 * density), (int) (10 * density),
                (int) (10 * density), (int) (10 * density));

        int iconSize = (int) (44 * density);
        LinearLayout iconWrap = new LinearLayout(context);
        iconWrap.setGravity(Gravity.CENTER);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        iconWrap.setBackground(roundBg(context, context.getColor(R.color.background)));
        ImageView icon = new ImageView(context);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        applyThumb(context, icon, c, iconSize, textSub);
        iconWrap.addView(icon);
        card.addView(iconWrap);

        TextView name = new TextView(context);
        name.setText(org.levimc.launcher.util.McFormatUtils.format(c.name));
        name.setTextColor(textMain);
        name.setTextSize(12);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) (6 * density);
        card.addView(name, nlp);

        TextView meta = new TextView(context);
        String ver = c.version == null || c.version.isEmpty() ? "" : "v" + c.version + " · ";
        String subs = c.subManifests != null && c.subManifests.size() > 1
                ? "含" + c.subManifests.size() + "包 · " : "";
        meta.setText(ver + subs + formatSize(c.size));
        meta.setTextColor(textSub);
        meta.setTextSize(10);
        meta.setMaxLines(1);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = (int) (3 * density);
        card.addView(meta, mlp);

        MaterialButton importBtn = new MaterialButton(context);
        importBtn.setAllCaps(false);
        importBtn.setText("导入");
        importBtn.setTextSize(11);
        importBtn.setPadding(0, 0, 0, 0);
        importBtn.setMinWidth(0);
        importBtn.setMinimumWidth(0);
        importBtn.setMinHeight(0);
        importBtn.setMinimumHeight(0);
        importBtn.setInsetTop(0);
        importBtn.setInsetBottom(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (28 * density));
        blp.topMargin = (int) (8 * density);
        importBtn.setLayoutParams(blp);
        AccentStyler.stylePrimary(context, importBtn);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onPick(c.file);
            }
        });
        // v656：多选模式下隐藏单个导入按钮
        importBtn.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        card.addView(importBtn, blp);

        // 点卡片本体：多选模式 = 切换勾选；否则进二级详情
        // v653：卡片整体也可点（修两列时点到 padding/间隙无响应）
        View.OnClickListener toDetail = v -> {
            if (selectionMode) {
                toggleCardSelection(card, c, cardBg, accent, density);
                if (onSelectionChangedRef != null) {
                    onSelectionChangedRef.run();
                }
            } else {
                showDetail(content, context, c, textMain, textSub, cardBg, density, accent,
                        listener, dialog, redraw, search, status);
            }
        };
        card.setOnClickListener(toDetail);
        // v658：子 View 不挂监听（点击穿透到 card，长按才能稳定触发）
        // v656：长按进入多选模式并勾选
        card.setOnLongClickListener(v -> {
            boolean first = !selectionMode;
            selectionMode = true;
            toggleCardSelection(card, c, cardBg, accent, density);
            if (first) {
                redraw.run(); // 全量重建：全部卡片隐藏导入按钮 + 恢复勾选样式
            }
            if (onSelectionChangedRef != null) {
                onSelectionChangedRef.run();
            }
            return true;
        });
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
        // v656：勾选样式（选中 = accent 描边 + 半透明底）
        applySelectionStyle(row, selectedPaths.contains(c.path), cardBg, accent, density);
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
        name.setText(org.levimc.launcher.util.McFormatUtils.format(c.name));
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
        // v656：多选模式下隐藏单个导入按钮
        importBtn.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        row.addView(importBtn);

        // 点条目本体：多选模式 = 切换勾选；否则进二级详情
        // v658：子 View（iconWrap/info）不再单独挂监听——它们拦截触摸后
        // 长按不向父冒泡（performLongClick 只查触摸目标），导致单行条目
        // 长按无反应；统一由 row 处理点击/长按（子 View 点击穿透）
        View.OnClickListener toDetail = v -> {
            if (selectionMode) {
                toggleCardSelection(row, c, cardBg, accent, density);
                if (onSelectionChangedRef != null) {
                    onSelectionChangedRef.run();
                }
            } else {
                showDetail(content, context, c, textMain, textSub, cardBg, density, accent,
                        listener, dialog, redraw, search, status);
            }
        };
        row.setOnClickListener(toDetail);
        // v656：长按进入多选模式并勾选
        row.setOnLongClickListener(v -> {
            boolean first = !selectionMode;
            selectionMode = true;
            toggleCardSelection(row, c, cardBg, accent, density);
            if (first) {
                redraw.run(); // 全量重建：全部条目隐藏导入按钮 + 恢复勾选样式
            }
            if (onSelectionChangedRef != null) {
                onSelectionChangedRef.run();
            }
            return true;
        });
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
        status.setVisibility(View.GONE);
        if (status.getParent() instanceof View) {
            ((View) status.getParent()).setVisibility(View.GONE);
        }
        if (cardsHostView != null) {
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
        name.setText(org.levimc.launcher.util.McFormatUtils.format(c.name));
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
                si.setText(org.levimc.launcher.util.McFormatUtils.format("· " + sm.name + v + " " + tag));
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
            search.setVisibility(searchOpenState ? View.VISIBLE : View.GONE);
            status.setVisibility(View.VISIBLE);
        if (status.getParent() instanceof View) {
            ((View) status.getParent()).setVisibility(View.VISIBLE);
        }
        if (cardsHostView != null) {
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
        status.setVisibility(View.GONE);
        if (status.getParent() instanceof View) {
            ((View) status.getParent()).setVisibility(View.GONE);
        }
        if (cardsHostView != null) {
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
        name.setText(org.levimc.launcher.util.McFormatUtils.format(sm.name));
        name.setTextColor(textMain);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setPadding(0, (int) (4 * density), 0, 0);
        head.addView(name);

        String ver = sm.version.isEmpty() ? "" : " · v" + sm.version;
        TextView meta = new TextView(context);
        meta.setText(org.levimc.launcher.util.McFormatUtils.format("清单版本" + ver + "  ·  来自 " + c.name));
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
            search.setVisibility(searchOpenState ? View.VISIBLE : View.GONE);
            status.setVisibility(View.VISIBLE);
        if (status.getParent() instanceof View) {
            ((View) status.getParent()).setVisibility(View.VISIBLE);
        }
        if (cardsHostView != null) {
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
            t.setText(org.levimc.launcher.util.McFormatUtils.format(r));
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
            rows.add("游戏模式：" + gameTypeText(info.gameType));
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
     * 无贴图用默认图（皮肤包 ic_tshirt）。
     * v646：缓存命中同步设置；未命中先默认图、后台解码完成后替换
     * （列表重建不再主线程解码位图）。 */
    private static void applyThumb(Context context, ImageView iv,
                                   GlobalImportScanner.Candidate c, int sizePx,
                                   int tintColor) {
        // v646：缓存 key 带尺寸——列表行 34dp 与二级详情 64dp 不串用
        String cacheKey = c.path + "@" + sizePx;
        synchronized (thumbCache) {
            Bitmap cached = thumbCache.get(cacheKey);
            if (cached != null) {
                iv.setImageBitmap(cached);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                return;
            }
        }
        iv.setImageResource(c.skinPack ? R.drawable.ic_tshirt
                : ICONS[Math.min(c.type, ICONS.length - 1)]);
        iv.setColorFilter(tintColor);
        byte[] data = c.icon;
        if (data == null) {
            // v648：持久化缓存恢复的候选无 icon 字节——后台从原文件提取
            final java.io.File srcFile = c.file;
            final int srcType = c.type;
            thumbPool.execute(() -> {
                try {
                    byte[] ext = GlobalImportScanner.extractIcon(srcFile, srcType);
                    if (ext == null) {
                        return;
                    }
                    Bitmap bmp2 = BitmapFactory.decodeByteArray(ext, 0, ext.length);
                    if (bmp2 == null) {
                        return;
                    }
                    float density2 = context.getResources().getDisplayMetrics().density;
                    Bitmap out2 = centerCropRound(bmp2, sizePx, (int) (10 * density2));
                    synchronized (thumbCache) {
                        thumbCache.put(cacheKey, out2);
                    }
                    iv.post(() -> {
                        if (iv.getParent() != null) {
                            iv.setImageBitmap(out2);
                            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            iv.setColorFilter(null);
                        }
                    });
                } catch (Throwable ignored) {
                }
            });
            return;
        }
        thumbPool.execute(() -> {
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) {
                    return;
                }
                float density = context.getResources().getDisplayMetrics().density;
                Bitmap out = centerCropRound(bmp, sizePx, (int) (10 * density));
                synchronized (thumbCache) {
                    thumbCache.put(cacheKey, out);
                }
                // 视图可能已被列表重建替换——仅在仍挂载时更新
                iv.post(() -> {
                    if (iv.getParent() != null) {
                        iv.setImageBitmap(out);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setColorFilter(null);
                    }
                });
            } catch (Throwable ignored) {
            }
        });
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

    /** v656：混色（选中底色 = 卡片底色叠 accent 半透明）。 */
    private static int blend(int base, int accent, float ratio) {
        int r = (int) (((base >> 16) & 0xFF) * (1 - ratio) + ((accent >> 16) & 0xFF) * ratio);
        int g = (int) (((base >> 8) & 0xFF) * (1 - ratio) + ((accent >> 8) & 0xFF) * ratio);
        int b = (int) ((base & 0xFF) * (1 - ratio) + (accent & 0xFF) * ratio);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** v656：勾选样式——选中 = accent 描边 + accent 半透明底（参考内容管理勾选）。 */
    private static void applySelectionStyle(View card, boolean selected, int cardBg,
                                            int accent, float density) {
        GradientDrawable bg = new GradientDrawable();
        if (selected) {
            bg.setColor(blend(cardBg, accent, 0.18f));
            bg.setStroke((int) (2 * density), accent);
        } else {
            bg.setColor(cardBg);
        }
        bg.setCornerRadius(12 * density);
        card.setBackground(bg);
    }

    /** v656：NBT GameType 数字 → 可读文本（0 生存/1 创造/2 冒险/3 旁观）。 */
    private static String gameTypeText(String v) {
        if (v == null) {
            return "";
        }
        switch (v) {
            case "0":
                return "生存模式";
            case "1":
                return "创造模式";
            case "2":
                return "冒险模式";
            case "3":
                return "旁观模式";
            default:
                return v;
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
