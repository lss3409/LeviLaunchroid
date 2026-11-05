package org.levimc.launcher.ui.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.leveldb.WorldDatabase;
import org.levimc.launcher.core.content.nbt.NativeNbt;
import org.levimc.launcher.core.content.nbt.NbtDocument;
import org.levimc.launcher.core.content.nbt.NbtTag;
import org.levimc.launcher.core.content.world.ChunkKey;
import org.levimc.launcher.core.content.world.LbblPalette;
import org.levimc.launcher.core.content.worldmap.BiomeNames;
import org.levimc.launcher.ui.views.ContentActionPopup;
import org.levimc.launcher.core.content.world.MapPalette;
import org.levimc.launcher.core.content.world.MapSettings;
import org.levimc.launcher.core.content.world.WorldChunk;
import org.levimc.launcher.core.content.world.WorldLocator;
import org.levimc.launcher.core.content.world.WorldMapRenderer;
import org.levimc.launcher.core.content.world.WorldOperations;
import org.levimc.launcher.core.content.world.WorldRecords;
import org.levimc.launcher.core.content.world.WorldSession;
import org.levimc.launcher.ui.world.NbtTreePanel;
import org.levimc.launcher.ui.world.WorldMapView;
import org.levimc.launcher.ui.world.WorldUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class WorldEditorActivity extends BaseActivity implements NbtTreePanel.Host {
    public static final String EXTRA_WORLD_PATH = "world_path", EXTRA_WORLD_NAME = "world_name";
    // v0.4.214：图层名 11 语言（官方硬编码 → 资源）
    private static final int[] LAYER_NAMES = {
        R.string.world_layer_satellite,
        R.string.world_layer_cave,
        R.string.world_layer_slime,
        R.string.world_layer_heightmap,
        R.string.world_layer_biomes,
        R.string.world_layer_foliage,
        R.string.world_layer_xray,
        R.string.world_layer_light
    };

    private String layerName(int index) {
        return getString(LAYER_NAMES[index]);
    }



    private String dimensionNameLocalized(int dimension) {
        return getString(
                dimension == 1
                        ? R.string.world_dim_nether
                        : dimension == 2 ? R.string.world_dim_end : R.string.world_dim_overworld);
    }

    private static final class State {
        WorldSession session;
        WorldMapRenderer renderer;
        MapSettings settings = new MapSettings();
        WorldRecords.Document document;
        byte[] key, raw, pendingExport;
        Bitmap pendingImage;
        boolean metadata, standalone, draftDirty;
        int tab;
        int importMode;
        double centerX, centerZ;
        float scale = 5;
        // v0.4.214：每维度图层记忆（-1=无记忆）——主世界卫星切下界
        // （首次默认洞穴）再切回主世界恢复卫星
        int[] dimLayers = {-1, -1, -1};
        // v0.4.214：框选多边形顶点链（block 坐标，旋转恢复）
        int[][] selPoints;
        Integer x1, z1, x2, z2;
        int y1 = -64, y2 = 319;
        List<WorldRecords.Marker> players = new ArrayList<>(), bookmarks = new ArrayList<>();
    }

    private State state;
    private ExecutorService executor;
    private Future<?> task;
    private volatile boolean active;
    private boolean busy, saving, initialized;
    private boolean switchingTab;
    private int job;
    private LinearLayout root, mapPanel, nbtPanel, recordsPanel, worldNbtButton, localPlayerButton;
    private FrameLayout pages, editorPanel, blocker;
    private TextView status, progressLabel, recordSummary;
    private MaterialButton saveButton;
    private TabLayout tabs;
    private WorldMapView map;
    private RecyclerView records;
    private EditText recordSearch;
    private final List<byte[]> recordKeys = new ArrayList<>();
    private final RecordAdapter recordAdapter = new RecordAdapter();
    private byte[] nextPage;
    private NbtTreePanel tree;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchRunnable;
    private ActivityResultLauncher<String> exportFile;
    private ActivityResultLauncher<String[]> importFile;
    // v0.4.214：左侧四抽屉（LBBL：世界信息/图层/标点&结构/设置——
    // 替代官方左上角图层/维度/Y 按钮与加号菜单 Map options）
    private LinearLayout drawerHost, drawerTabs;
    private FrameLayout drawerBody;
    private ScrollView drawerInfoPanel, drawerLayerPanel, drawerBookmarkPanel, drawerSettingsPanel;
    private final List<TextView> drawerLayerRows = new ArrayList<>();
    private final List<TextView> drawerDimRows = new ArrayList<>();
    // v0.4.214：顶部横幅维度 chips（维度切换移入横幅中间）
    private final List<TextView> headerDimChips = new ArrayList<>();
    private final List<ImageView> drawerIcons = new ArrayList<>();
    private TextView drawerYRow;
    private LinearLayout infoPanelCol, bookmarkPanelCol;
    private EditText bookmarkSearch;
    // v0.4.214：框选操作条（✓应用/撤销/×关闭）
    private LinearLayout selBar;
    // v0.4.214：二级菜单气泡（跳结构 tab 时需关闭）
    private PopupWindow selPopup;
    // v0.4.214：放置模式（从选区二级菜单「放置在此」进入结构 tab，
    // 点击结构条目=选中并跳回地图放置到选区中心）
    private boolean placeMode;
    private boolean drawerOpen = true;
    private int drawerTab = 1;
    private String worldPath = "";
    private org.levimc.launcher.core.content.BlueprintDb blueprintDb;
    private final List<org.levimc.launcher.core.content.BlueprintDb.Point> mapPoints =
            new ArrayList<>();
    private final List<org.levimc.launcher.core.content.BlueprintDb.Point> filteredPoints =
            new ArrayList<>();
    private final List<org.levimc.launcher.core.content.worldmap.WorldMapRenderer.StructureMarker>
            structures = new ArrayList<>();
    // v0.4.214：默认关闭结构标记（用户要求；设置抽屉可开）
    private boolean showStructures = false;

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof State) state = (State) retained;
        else {
            String path = getIntent().getStringExtra(EXTRA_WORLD_PATH);
            if (path == null) {
                finish();
                return;
            }
            state = new State();
            worldPath = path;
            state.session = new WorldSession(new File(path));
            state.renderer = new WorldMapRenderer(state.session);
            LbblPalette.init(getApplicationContext()); // v0.4.214：LBBL 颜色表
            loadPreferences();
        }
        executor =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "LeviWorldWorkspace");
                            t.setPriority(Thread.MIN_PRIORITY);
                            return t;
                        });
        exportFile =
                registerForActivityResult(
                        new ActivityResultContracts.CreateDocument("application/octet-stream"),
                        uri -> {
                            if (uri == null) {
                                clearExport();
                                return;
                            }
                            byte[] bytes = state.pendingExport;
                            Bitmap image = state.pendingImage;
                            state.pendingExport = null;
                            state.pendingImage = null;
                            if (bytes == null && image == null) return;
                            work(
                                    getString(R.string.world_w_exporting),
                                    () -> {
                                        try (OutputStream out =
                                                getContentResolver().openOutputStream(uri)) {
                                            if (out == null)
                                                throw new IOException(
                                                        getString(R.string.world_e_cannot_open_dest));
                                            if (image != null) {
                                                if (!image.compress(
                                                        Bitmap.CompressFormat.PNG, 100, out))
                                                    throw new IOException(
                                                            getString(R.string.world_e_cannot_encode));
                                            } else out.write(bytes);
                                            out.flush();
                                        } finally {
                                            if (image != null) image.recycle();
                                        }
                                        return true;
                                    },
                                    v -> {
                                        if (state.standalone) {
                                            state.draftDirty = false;
                                            updatePending();
                                        }
                                        toast(getString(R.string.world_toast_exported));
                                    });
                        });
        importFile =
                registerForActivityResult(
                        new ActivityResultContracts.OpenDocument(),
                        uri -> {
                            if (uri == null) return;
                            int mode = state.importMode;
                            work(
                                    getString(R.string.world_w_reading_file),
                                    () -> {
                                        try (InputStream in =
                                                getContentResolver().openInputStream(uri)) {
                                            if (in == null)
                                                throw new IOException(getString(R.string.world_e_cannot_open_file));
                                            return readBounded(in);
                                        }
                                    },
                                    bytes -> {
                                        if (mode == 1) importStructure(bytes);
                                        else if (mode == 2) openNbtFile(bytes);
                                        else if (mode == 3) replaceValue(bytes);
                                    });
                        });
        buildUi();
    }

    private void buildUi() {
        root = WorldUi.column(this, 0);
        root.setBackgroundColor(WorldUi.color(this, R.color.background));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(8), 0);
        header.addView(
                WorldUi.backButton(this, this::leave),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        String name = getIntent().getStringExtra(EXTRA_WORLD_NAME);
        TextView title = WorldUi.text(this, name == null ? getString(R.string.world_editor_world) : name, 17, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, dp(12), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // v0.4.214：横幅中间加维度切换 chips + 坐标搜索（LBBL 风格——
        // 用户指令：中间大空白里加维度切换跟搜索）
        LinearLayout dimChips = new LinearLayout(this);
        dimChips.setGravity(Gravity.CENTER_VERTICAL);
        int[] dimNames = {
            R.string.world_dim_overworld, R.string.world_dim_nether, R.string.world_dim_end
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView chip = WorldUi.text(this, getString(dimNames[i]), 13, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setBackground(WorldUi.ripple(this, 0, 12));
            chip.setOnClickListener(v -> setDimension(idx));
            headerDimChips.add(chip);
            dimChips.addView(chip);
        }
        header.addView(dimChips);
        EditText headerSearch =
                WorldUi.input(this, getString(R.string.world_search_coords), "", false);
        headerSearch.setSingleLine(true);
        headerSearch.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            || (event != null
                                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                        String value = headerSearch.getText().toString().trim();
                        String[] parts = value.split("[,\\s]+");
                        if (parts.length >= 2) {
                            try {
                                int gx = Integer.parseInt(parts[0]);
                                int gz = Integer.parseInt(parts[1]);
                                map.center(gx, gz);
                                android.view.inputmethod.InputMethodManager imm =
                                        (android.view.inputmethod.InputMethodManager)
                                                getSystemService(INPUT_METHOD_SERVICE);
                                imm.hideSoftInputFromWindow(
                                        headerSearch.getWindowToken(), 0);
                                return true;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        error(getString(R.string.world_e_whole_number));
                        return true;
                    }
                    return false;
                });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(150), dp(38));
        searchLp.setMarginStart(dp(4));
        header.addView(headerSearch, searchLp);
        styleDimChips();
        tabs = new TabLayout(this);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setSelectedTabIndicatorColor(WorldUi.accent(this));
        tabs.setTabTextColors(WorldUi.color(this, R.color.text_secondary), WorldUi.accent(this));
        tabs.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        for (String tab : new String[] {getString(R.string.world_tab_map), getString(R.string.world_tab_nbt), getString(R.string.world_tab_structures)}) {
            TextView label = WorldUi.text(this, tab, 13, true);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(
                    new ColorStateList(
                            new int[][] {new int[] {android.R.attr.state_selected}, new int[] {}},
                            new int[] {
                                WorldUi.accent(this), WorldUi.color(this, R.color.text_secondary)
                            }));
            tabs.addTab(tabs.newTab().setCustomView(label));
        }
        header.addView(tabs, new LinearLayout.LayoutParams(dp(268), -1));
        saveButton = WorldUi.button(this, getString(R.string.world_save), this::save);
        header.addView(saveButton, new LinearLayout.LayoutParams(dp(68), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));
        FrameLayout area = new FrameLayout(this);
        pages = new FrameLayout(this);
        area.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        buildMap();
        buildNbt();
        buildRecords();
        blocker = new FrameLayout(this);
        blocker.setClickable(true);
        blocker.setBackgroundColor(
                WorldUi.translucent(WorldUi.color(this, R.color.background), 175));
        LinearLayout progress = WorldUi.column(this, 12);
        progress.setGravity(Gravity.CENTER);
        progress.addView(new ProgressBar(this));
        progressLabel = WorldUi.text(this, "", 12, true);
        progressLabel.setPadding(0, dp(12), 0, dp(4));
        progress.addView(progressLabel);
        progress.addView(
                WorldUi.button(
                        this,
                        getString(R.string.world_cancel),
                        () -> {
                            if (!saving && task != null) {
                                task.cancel(true);
                                busy = false;
                                job++;
                                blocker.setVisibility(View.GONE);
                                updatePending();
                            }
                        }));
        blocker.addView(progress, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        area.addView(blocker, new FrameLayout.LayoutParams(-1, -1));
        blocker.setVisibility(View.GONE);
        status = WorldUi.text(this, "", 11, true);
        status.setPadding(dp(16), 0, dp(16), 0);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));
        setContentView(root);
        tabs.addOnTabSelectedListener(
                new TabLayout.OnTabSelectedListener() {
                    public void onTabSelected(TabLayout.Tab tab) {
                        handleTab(tab.getPosition());
                    }

                    public void onTabUnselected(TabLayout.Tab tab) {}

                    public void onTabReselected(TabLayout.Tab tab) {}
                });
        state.tab = Math.min(2, state.tab);
        tabs.getTabAt(state.tab).select();
        showTab(state.tab);
    }

    private void buildMap() {
        mapPanel = WorldUi.column(this, 0);
        // v0.4.214：抽屉 overlay 浮在地图上（占位方案挤占地图像素，
        // 用户反馈抽屉底部区域白白浪费）——准星/交互偏移到可视中心
        // 由 WorldMapView.setLeftInset 处理
        FrameLayout mapArea = new FrameLayout(this);
        map = new WorldMapView(this, state.session, state.renderer);
        map.configure(state.settings);
        map.viewport(state.centerX, state.centerZ, state.scale);
        map.setPlayers(state.players);
        map.setBookmarks(state.bookmarks);
        map.setListener(
                new WorldMapView.Listener() {
                    public void inspect(int x, int z, boolean hold) {
                        WorldEditorActivity.this.inspect(x, z, hold);
                    }

                    // v0.4.214：点击结构图标 → 结构信息弹窗
                    public void structure(int x, int z, String type) {
                        WorldEditorActivity.this.structureInfo(x, z, type);
                    }

                    public void moved(double x, double z, float scale) {
                        state.centerX = x;
                        state.centerZ = z;
                        state.scale = scale;
                        if (!busy) mapStatus();
                    }

                    public void error(String message) {
                        if (!busy) status.setText(message);
                    }

                    // v0.4.214：框选变化（粗框完成/拖点/清除）
                    public void selected(int[][] points) {
                        WorldEditorActivity.this.selectionChanged(points);
                    }
                });
        mapArea.addView(map, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.BOTTOM);
        ImageView tools = WorldUi.iconButton(this, R.drawable.ic_add, getString(R.string.world_tools_title), this::showTools);
        tools.setBackground(WorldUi.ripple(this, WorldUi.color(this, R.color.surface), 12));
        tools.setImageTintList(ColorStateList.valueOf(WorldUi.accent(this)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(44), dp(44));
        tp.setMargins(0, 0, dp(8), 0);
        controls.addView(tools, tp);
        LinearLayout zoom = WorldUi.column(this, 0);
        zoom.setBackground(WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        zoom.addView(
                WorldUi.iconButton(
                        this, R.drawable.ic_world_zoom_in, getString(R.string.world_zoom_in), () -> map.zoom(1.5f)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        zoom.addView(
                WorldUi.iconButton(
                        this, R.drawable.ic_world_zoom_out, getString(R.string.world_zoom_out), () -> map.zoom(1 / 1.5f)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        controls.addView(zoom);
        FrameLayout.LayoutParams cp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        cp.setMargins(0, 0, dp(12), dp(12));
        mapArea.addView(controls, cp);
        buildDrawer(mapArea); // v0.4.214：左侧四抽屉（overlay 浮在地图上）
        // v0.4.214：框选操作条——✓应用唤出二级菜单 / 撤销回退拖点 / ×关闭
        selBar = new LinearLayout(this);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setGravity(Gravity.CENTER);
        selBar.setVisibility(View.GONE);
        selBar.setBackground(WorldUi.background(this, WorldUi.color(this, R.color.surface), 16));
        ImageView selApply =
                WorldUi.iconButton(
                        this,
                        R.drawable.ic_check,
                        getString(R.string.world_sel_apply),
                        () -> {
                            if (map.hasSelection()) showSelMenu();
                        });
        selApply.setImageTintList(ColorStateList.valueOf(WorldUi.accent(this)));
        ImageView selUndoBtn =
                WorldUi.iconButton(
                        this,
                        R.drawable.ic_undo,
                        getString(R.string.world_sel_undo),
                        map::undoSelection);
        selUndoBtn.setImageTintList(
                ColorStateList.valueOf(WorldUi.color(this, R.color.text_secondary)));
        ImageView selClose =
                WorldUi.iconButton(
                        this,
                        R.drawable.ic_close,
                        getString(R.string.world_sel_close),
                        () -> {
                            map.clearSelection();
                            map.setSelectMode(false);
                            selBar.setVisibility(View.GONE);
                        });
        selClose.setImageTintList(
                ColorStateList.valueOf(WorldUi.color(this, R.color.text_secondary)));
        for (ImageView b : new ImageView[] {selApply, selUndoBtn, selClose})
            selBar.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        FrameLayout.LayoutParams sbp =
                new FrameLayout.LayoutParams(
                        -2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sbp.setMargins(0, 0, 0, dp(30));
        mapArea.addView(selBar, sbp);
        // v0.4.214：旋转恢复框选
        if (state.selPoints != null) {
            map.setSelectMode(true);
            map.restoreSelection(state.selPoints);
            selBar.setVisibility(View.VISIBLE);
        }
        mapPanel.addView(mapArea, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(mapPanel, new FrameLayout.LayoutParams(-1, -1));
        refreshSelection();
    }

    /** v0.4.214：抽屉面板限高 ScrollView（内容 wrap 不拉到底）。 */
    private static final class MaxScrollView extends ScrollView {
        private final int maxHeight;

        MaxScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            heightMeasureSpec =
                    MeasureSpec.makeMeasureSpec(
                            Math.min(MeasureSpec.getSize(heightMeasureSpec), maxHeight),
                            MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    // v0.4.214：左侧四抽屉（LBBL：世界信息/图层/标点&结构/设置）——
    // 替代官方左上角图层/维度/Y 按钮（冗余设置入口删除）
    private void buildDrawer(FrameLayout mapArea) {
        drawerHost = new LinearLayout(this);
        drawerHost.setOrientation(LinearLayout.HORIZONTAL);
        drawerTabs = WorldUi.column(this, 4);
        drawerTabs.setPadding(0, dp(8), 0, dp(8));
        drawerTabs.setBackground(
                WorldUi.background(this, WorldUi.color(this, R.color.surface_variant), 12));
        // v0.4.214：图标列高度与面板同步（match host）——面板高度变化时
        // 四个 tab 图标固定顶部不随动（用户实测：随动导致点击位置漂移）
        drawerHost.addView(drawerTabs, new LinearLayout.LayoutParams(dp(44), -1));
        int[] icons = {
            R.drawable.ic_world, R.drawable.ic_layers, R.drawable.ic_pin, R.drawable.ic_settings
        };
        String[] labels = {
            getString(R.string.world_drawer_info),
            getString(R.string.world_drawer_layers),
            getString(R.string.world_drawer_bookmarks),
            getString(R.string.world_drawer_settings)
        };
        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            ImageView icon =
                    WorldUi.iconButton(
                            this, icons[i], labels[i], () -> selectDrawerTab(idx));
            drawerIcons.add(icon);
            drawerTabs.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        // v0.4.214：折叠按钮已删（用户要求——再点 tab 即收起，
        // 按钮被弹性推到底部会挡住比例尺）
        drawerBody = new FrameLayout(this);
        drawerBody.setBackground(
                WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        drawerBody.setClipToOutline(true);
        drawerInfoPanel = buildInfoPanel();
        drawerLayerPanel = buildLayerPanel();
        drawerBookmarkPanel = buildBookmarkPanel();
        drawerSettingsPanel = buildSettingsPanel();
        drawerBody.addView(drawerInfoPanel, new FrameLayout.LayoutParams(-1, -2));
        drawerBody.addView(drawerLayerPanel, new FrameLayout.LayoutParams(-1, -2));
        drawerBody.addView(drawerBookmarkPanel, new FrameLayout.LayoutParams(-1, -2));
        drawerBody.addView(drawerSettingsPanel, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(dp(208), -2);
        bodyLp.setMarginStart(dp(4));
        drawerHost.addView(drawerBody, bodyLp);
        mapArea.addView(
                drawerHost,
                // v0.4.214：overlay 顶部对齐——面板高度变化时整体不上下移动
                new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));
        selectDrawerTab(drawerTab);
    }

    private void selectDrawerTab(int tab) {
        // 再点当前 tab = 收起抽屉
        if (tab == drawerTab && drawerOpen) {
            setDrawerOpen(false);
            return;
        }
        drawerTab = tab;
        drawerInfoPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        drawerLayerPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        drawerBookmarkPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        drawerSettingsPanel.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        if (!drawerOpen) setDrawerOpen(true);
        for (int i = 0; i < drawerIcons.size(); i++)
            drawerIcons
                    .get(i)
                    .setImageTintList(
                            ColorStateList.valueOf(
                                    i == drawerTab
                                            ? WorldUi.accent(this)
                                            : WorldUi.color(this, R.color.text_secondary)));
        if (tab == 0) refreshInfoPanel();
        if (tab == 2) refreshBookmarkPanel();
    }

    private void setDrawerOpen(boolean open) {
        drawerOpen = open;
        drawerBody.setVisibility(open ? View.VISIBLE : View.GONE);
        // v0.4.214：overlay 下准星/交互锚定可视中心——tab 列 40dp 常驻；
        // 面板展开 = 40dp tab + 4dp 边距 + 208dp 面板
        map.setLeftInset(dp(open ? 40 + 4 + 208 : 40));
    }

    private TextView sectionTitle(String label) {
        TextView title = WorldUi.text(this, label, 12, true);
        title.setPadding(0, dp(6), 0, dp(4));
        title.setTextColor(WorldUi.accent(this));
        return title;
    }

    private TextView drawerRow(String label, Runnable onClick) {
        TextView row = WorldUi.text(this, label, 13, false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setBackground(WorldUi.ripple(this, 0, 10));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void styleDrawerRow(TextView row, boolean selected) {
        row.setTextColor(
                selected
                        ? WorldUi.accent(this)
                        : WorldUi.color(this, R.color.on_surface));
        // 选中态必须用 Typeface.DEFAULT 重设（getTypeface 还原会失效）
        row.setTypeface(
                android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** 图层抽屉：8 图层单选 + 3 维度单选（地狱默认洞穴）+ Y 范围 + 坐标开关。 */
    private ScrollView buildLayerPanel() {
        ScrollView scroll = new MaxScrollView(this, dp(480));
        LinearLayout col = WorldUi.column(this, 2);
        col.setPadding(dp(10), dp(8), dp(10), dp(16));
        col.addView(sectionTitle(getString(R.string.world_layer_title)));
        for (int i = 0; i < LAYER_NAMES.length; i++) {
            final int idx = i;
            TextView row = drawerRow(layerName(i), () -> setLayer(idx));
            drawerLayerRows.add(row);
            col.addView(row, new LinearLayout.LayoutParams(-1, dp(38)));
        }
        // v0.4.214：维度切换已移到顶部横幅 chips；Y 范围移到设置抽屉；
        // 坐标开关删除（用户指令）——图层抽屉只留 8 图层单选
        refreshDrawerRows();
        scroll.addView(col);
        return scroll;
    }

    /** 设置抽屉：官方 MapSettings 开关直切（替代加号菜单 Map options）。 */
    private ScrollView buildSettingsPanel() {
        ScrollView scroll = new MaxScrollView(this, dp(480));
        LinearLayout col = WorldUi.column(this, 8);
        col.setPadding(dp(10), dp(8), dp(10), dp(16));
        col.addView(sectionTitle(getString(R.string.world_drawer_settings)));
        toggle(
                col,
                getString(R.string.world_opt_shading),
                state.settings.shading,
                v -> {
                    state.settings.shading = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_grid),
                state.settings.grid,
                v -> {
                    state.settings.grid = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_slime),
                state.settings.slimeOverlay,
                v -> {
                    state.settings.slimeOverlay = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_players),
                state.settings.players,
                v -> {
                    state.settings.players = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_entities),
                state.settings.entities,
                v -> {
                    state.settings.entities = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_block_entities),
                state.settings.blockEntities,
                v -> {
                    state.settings.blockEntities = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_leaves),
                state.settings.leaves,
                v -> {
                    state.settings.leaves = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_water),
                state.settings.water,
                v -> {
                    state.settings.water = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_slice),
                state.settings.slice,
                v -> {
                    state.settings.slice = v;
                    configureMap();
                });
        toggle(
                col,
                getString(R.string.world_opt_structures),
                showStructures,
                v -> {
                    showStructures = v;
                    map.setShowStructures(v);
                });
        // v0.4.214：Y 范围从图层抽屉移到设置抽屉（用户指令）
        col.addView(sectionTitle(getString(R.string.world_y_range)));
        drawerYRow = drawerRow("", this::chooseY);
        col.addView(drawerYRow, new LinearLayout.LayoutParams(-1, dp(38)));
        refreshDrawerRows();
        scroll.addView(col);
        return scroll;
    }

    /** 世界信息抽屉：旧版方案（世界名/路径/种子/版本/尺寸/玩家）。 */
    private ScrollView buildInfoPanel() {
        ScrollView scroll = new MaxScrollView(this, dp(480));
        infoPanelCol = WorldUi.column(this, 6);
        infoPanelCol.setPadding(dp(10), dp(8), dp(10), dp(16));
        scroll.addView(infoPanelCol);
        // v0.4.214：不在 onCreate 刷新——work() 会抢占官方 busy 锁，
        // 导致 onStart 的「打开存档世界」初始化被跳过（地图不显示根因）。
        // 点开世界信息 tab 时惰性加载（selectDrawerTab(0)）。
        return scroll;
    }

    private void refreshInfoPanel() {
        if (infoPanelCol == null || !initialized) return;
        infoPanelCol.removeAllViews();
        infoPanelCol.addView(sectionTitle(getString(R.string.world_drawer_info)));
        String displayName =
                getIntent().getStringExtra(EXTRA_WORLD_NAME);
        infoPanelCol.addView(
                WorldUi.text(
                        this,
                        getString(R.string.world_info_name)
                                + "："
                                + (displayName == null || displayName.isEmpty()
                                        ? new java.io.File(worldPath).getName()
                                        : displayName),
                        12,
                        false));
        infoPanelCol.addView(
                WorldUi.text(
                        this, getString(R.string.world_info_path) + "：" + worldPath, 11, false));
        work(
                getString(R.string.world_w_reading_players),
                () -> {
                    long seed = 0;
                    String version = "";
                    try {
                        NbtTag root = state.session.metadata().root;
                        NbtTag seedTag = root.getTag("RandomSeed");
                        if (seedTag != null) seed = seedTag.getLong();
                        NbtTag ver = root.getTag("lastOpenedWithVersion");
                        if (ver != null && ver.getIntArray().length >= 2) {
                            int[] v = ver.getIntArray();
                            version = v[0] + "." + v[1];
                        }
                    } catch (Exception ignored) {
                    }
                    int w = -1, h = -1, minX = 0, minZ = 0;
                    try {
                        org.levimc.launcher.core.content.worldmap.WorldMapRenderer.WorldMap wm =
                                org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                        .buildBoundsOnly(
                                                new java.io.File(worldPath, "db"),
                                                state.settings.dimension);
                        if (wm != null) {
                            w = wm.width;
                            h = wm.height;
                            minX = wm.minBlockX;
                            minZ = wm.minBlockZ;
                        }
                    } catch (Exception ignored) {
                    }
                    List<WorldRecords.Marker> players = WorldRecords.players(state.session);
                    return new Object[] {seed, version, w, h, minX, minZ, players};
                },
                info -> {
                    if (infoPanelCol == null) return;
                    long seed = (Long) info[0];
                    String version = (String) info[1];
                    int w = (Integer) info[2], h = (Integer) info[3];
                    @SuppressWarnings("unchecked")
                    List<WorldRecords.Marker> players =
                            (List<WorldRecords.Marker>) info[6];
                    infoPanelCol.addView(
                            WorldUi.text(
                                    this,
                                    getString(R.string.world_info_seed) + "：" + seed,
                                    12,
                                    false));
                    if (!version.isEmpty())
                        infoPanelCol.addView(
                                WorldUi.text(
                                        this,
                                        getString(R.string.world_info_version) + "：" + version,
                                        12,
                                        false));
                    if (w > 0)
                        infoPanelCol.addView(
                                WorldUi.text(
                                        this,
                                        getString(R.string.world_info_size)
                                                + "："
                                                + w
                                                + " × "
                                                + h
                                                + "（"
                                                + (Integer) info[4]
                                                + ", "
                                                + (Integer) info[5]
                                                + "）",
                                        12,
                                        false));
                    LinearLayout rows = new LinearLayout(this);
                    rows.setOrientation(LinearLayout.VERTICAL);
                    if (players.isEmpty()) {
                        rows.addView(
                                WorldUi.text(
                                        this,
                                        getString(R.string.world_info_no_player),
                                        12,
                                        false));
                    }
                    for (WorldRecords.Marker marker : players)
                        rows.addView(
                                WorldUi.text(
                                        this,
                                        (marker.label == null || marker.label.isEmpty()
                                                        ? getString(R.string.world_info_player)
                                                        : marker.label)
                                                + "："
                                                + (int) marker.x
                                                + ", "
                                                + (int) marker.y
                                                + ", "
                                                + (int) marker.z,
                                        12,
                                        false));
                    infoPanelCol.addView(rows);
                });
    }

    /** 标点&结构抽屉：双列网格 + 贴图 + 搜索（LBBL 方案）。
     *  v0.4.214：单列文字行改为双列卡片（结构条目带原版贴图，
     *  标点条目用彩色圆点）；底部「结构列表」跳转按钮删除。 */
    private ScrollView buildBookmarkPanel() {
        ScrollView scroll = new MaxScrollView(this, dp(480));
        LinearLayout col = WorldUi.column(this, 6);
        col.setPadding(dp(10), dp(8), dp(10), dp(16));
        col.addView(sectionTitle(getString(R.string.world_drawer_bookmarks)));
        bookmarkSearch =
                WorldUi.input(this, getString(R.string.world_search_bookmarks), "", false);
        bookmarkSearch.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                        searchRunnable = WorldEditorActivity.this::refreshBookmarkPanel;
                        handler.postDelayed(searchRunnable, 200);
                    }

                    public void afterTextChanged(Editable s) {}
                });
        col.addView(bookmarkSearch, new LinearLayout.LayoutParams(-1, dp(38)));
        bookmarkPanelCol = new LinearLayout(this);
        bookmarkPanelCol.setOrientation(LinearLayout.VERTICAL);
        col.addView(bookmarkPanelCol, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(col);
        return scroll;
    }

    private void refreshBookmarkPanel() {
        if (bookmarkPanelCol == null) return;
        bookmarkPanelCol.removeAllViews();
        mapPoints.clear();
        if (blueprintDb != null) {
            mapPoints.addAll(blueprintDb.getPoints(blueprintWorldId()));
        }
        String query =
                bookmarkSearch == null
                        ? ""
                        : bookmarkSearch
                                .getText()
                                .toString()
                                .trim()
                                .toLowerCase(java.util.Locale.ROOT);
        List<View> cells = new ArrayList<>();
        for (org.levimc.launcher.core.content.BlueprintDb.Point p : mapPoints) {
            if (!query.isEmpty()
                    && !p.name.toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !(p.x + "," + p.z).contains(query)) continue;
            int color = 0xFF8A8A8A;
            try {
                color = android.graphics.Color.parseColor(p.color);
            } catch (Exception ignored) {
            }
            cells.add(
                    bookmarkCell(
                            p.name,
                            dimNameOf(p.dimension) + " " + p.x + ", " + p.z,
                            color,
                            null,
                            () -> {
                                map.center(p.x, p.z);
                                state.centerX = p.x;
                                state.centerZ = p.z;
                            }));
        }
        for (org.levimc.launcher.core.content.worldmap.WorldMapRenderer.StructureMarker s :
                structures) {
            String label =
                    org.levimc.launcher.ui.views.WorldMapView.structureLabel(this, s.type);
            if (!query.isEmpty()
                    && !label.toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !(s.x + "," + s.z).contains(query)) continue;
            cells.add(
                    bookmarkCell(
                            label,
                            s.x + ", " + s.z,
                            0xFF8A8A8A,
                            org.levimc.launcher.ui.views.WorldMapView.structureIconName(s.type),
                            () -> {
                                map.center(s.x, s.z);
                                state.centerX = s.x;
                                state.centerZ = s.z;
                            }));
        }
        if (cells.isEmpty()) {
            bookmarkPanelCol.addView(
                    WorldUi.text(
                            this,
                            query.isEmpty()
                                    ? getString(R.string.world_info_no_points)
                                    : getString(R.string.world_search_no_result),
                            12,
                            false));
            return;
        }
        // 双列网格：两格一行，奇数个时用占位补满
        for (int i = 0; i < cells.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(cells.get(i), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(
                    i + 1 < cells.size()
                            ? cells.get(i + 1)
                            : new Space(this),
                    new LinearLayout.LayoutParams(0, -2, 1));
            bookmarkPanelCol.addView(row);
        }
    }

    /** 双列网格单格卡片：贴图/彩色圆点 + 名称 + 坐标。 */
    private LinearLayout bookmarkCell(
            String title, String sub, int color, String iconName, Runnable onClick) {
        LinearLayout cell = WorldUi.column(this, 4);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(10), dp(4), dp(10));
        cell.setBackground(WorldUi.ripple(this, 0, 12));
        ImageView iv = new ImageView(this);
        if (iconName != null) {
            Bitmap icon = loadStructureIcon(iconName);
            if (icon != null) iv.setImageBitmap(icon);
        }
        if (iv.getDrawable() == null) iv.setImageBitmap(colorDot(color));
        cell.addView(iv, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = WorldUi.text(this, title, 12, false);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView coord = WorldUi.text(this, sub, 10, true);
        coord.setGravity(Gravity.CENTER);
        coord.setMaxLines(1);
        coord.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(coord, new LinearLayout.LayoutParams(-1, -2));
        cell.setOnClickListener(
                v -> {
                    // v0.4.214：跳转排查日志（用户反馈点击没跳转）
                    android.util.Log.i(
                            "LeviWorldEditor",
                            "bookmark cell click: " + title + " sub=" + sub);
                    onClick.run();
                });
        return cell;
    }

    /** 结构贴图加载：drawable 资源 → assets/structure_icons/ 回退（与
     *  WorldMapView 同套游戏原版方块贴图），带 LRU 缓存。 */
    private final Map<String, Bitmap> structureIconCache =
            new LinkedHashMap<String, Bitmap>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 48;
                }
            };

    private Bitmap loadStructureIcon(String name) {
        Bitmap cached = structureIconCache.get(name);
        if (cached != null) return cached;
        int resId = getResources().getIdentifier(name, "drawable", getPackageName());
        Bitmap b = null;
        if (resId != 0) {
            Bitmap src = android.graphics.BitmapFactory.decodeResource(getResources(), resId);
            if (src != null) {
                b =
                        src.getWidth() == 32
                                ? src
                                : Bitmap.createScaledBitmap(src, 32, 32, true);
            }
        }
        if (b == null) {
            try (java.io.InputStream is =
                    getAssets().open("structure_icons/" + name + ".png")) {
                Bitmap src = android.graphics.BitmapFactory.decodeStream(is);
                if (src != null) {
                    b = Bitmap.createScaledBitmap(src, 32, 32, false);
                    src.recycle();
                }
            } catch (Exception ignored) {
            }
        }
        if (b != null) structureIconCache.put(name, b);
        return b;
    }

    /** 标点彩色圆点（无贴图条目的回退图标）。 */
    private Bitmap colorDot(int color) {
        Bitmap b = Bitmap.createBitmap(dp(14), dp(14), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(b.getWidth() / 2f, b.getHeight() / 2f, b.getWidth() / 2f, p);
        return b;
    }

    private String dimNameOf(String dimension) {
        return switch (dimension == null ? "" : dimension) {
            case "nether" -> getString(R.string.world_dim_nether);
            case "end" -> getString(R.string.world_dim_end);
            default -> getString(R.string.world_dim_overworld);
        };
    }

    private String blueprintWorldId() {
        return new java.io.File(worldPath).getName();
    }

    private String blueprintDimName(int dimension) {
        return dimension == 1 ? "nether" : dimension == 2 ? "end" : "overworld";
    }

    /** 从 BlueprintDb 重载标点 → 地图标记 + 抽屉列表。 */
    private void refreshBlueprintPoints() {
        mapPoints.clear();
        filteredPoints.clear();
        if (blueprintDb != null) {
            mapPoints.addAll(blueprintDb.getPoints(blueprintWorldId()));
        }
        state.bookmarks.clear();
        for (org.levimc.launcher.core.content.BlueprintDb.Point p : mapPoints) {
            int dim = p.dimension.equals("nether") ? 1 : p.dimension.equals("end") ? 2 : 0;
            if (dim != state.settings.dimension) continue;
            filteredPoints.add(p);
            state.bookmarks.add(
                    new WorldRecords.Marker(
                            p.name, p.x, p.y, p.z, dim, WorldRecords.Marker.BOOKMARK, null));
        }
        map.setBookmarks(state.bookmarks);
        refreshBookmarkPanel();
    }

    /** 结构扫描（LBBL 方案——SeedStructureFinder 种子预测）。 */
    private void refreshStructures() {
        work(
                getString(R.string.world_w_searching_chunks),
                () -> {
                    long seed = 0;
                    try {
                        NbtTag seedTag = state.session.metadata().root.getTag("RandomSeed");
                        if (seedTag != null) seed = seedTag.getLong();
                    } catch (Exception ignored) {
                    }
                    List<org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                    .StructureMarker>
                            list = new ArrayList<>();
                    String json =
                            org.levimc.launcher.core.content.worldmap.SeedStructureFinder.scan(
                                    seed,
                                    state.centerX,
                                    state.centerZ,
                                    80,
                                    200,
                                    org.levimc.launcher.core.content.worldmap.SeedStructureFinder
                                            .ALL_OVERWORLD);
                    if (json != null) {
                        org.json.JSONArray arr =
                                new org.json.JSONObject(json).optJSONArray("results");
                        if (arr != null)
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject o = arr.getJSONObject(i);
                                list.add(
                                        new org.levimc.launcher.core.content.worldmap
                                                .WorldMapRenderer.StructureMarker(
                                                o.getInt("x"),
                                                o.getInt("z"),
                                                o.getString("name"),
                                                null,
                                                true));
                            }
                    }
                    return list;
                },
                list -> {
                    structures.clear();
                    structures.addAll(list);
                    map.setStructures(structures);
                    map.setShowStructures(showStructures);
                    refreshBookmarkPanel();
                });
    }

    private void setLayer(int index) {
        state.settings.layer = MapSettings.Layer.values()[index];
        if (index == 2) {
            state.settings.dimension = 0; // 史莱姆区块仅主世界
        }
        refreshDrawerRows();
        configureMap();
    }

    private void setDimension(int index) {
        int old = state.settings.dimension;
        if (old != index) {
            // v0.4.214：每维度记住图层——主世界卫星切下界（首次默认洞穴）
            // 再切回主世界恢复卫星（用户反馈：切回主世界也变成洞穴了）
            if (old >= 0 && old < 3)
                state.dimLayers[old] = state.settings.layer.ordinal();
            if (state.dimLayers[index] >= 0) {
                state.settings.layer =
                        MapSettings.Layer.values()[state.dimLayers[index]];
            } else if (index == 1 && state.settings.layer == MapSettings.Layer.SATELLITE) {
                state.settings.layer = MapSettings.Layer.CAVE;
            }
        }
        state.settings.dimension = index;
        state.settings.lowerY = index == 0 ? -64 : 0;
        state.settings.upperY = index == 0 ? 319 : index == 1 ? 127 : 255;
        state.settings.y =
                Math.max(
                        state.settings.lowerY,
                        Math.min(state.settings.upperY, state.settings.y));
        refreshDrawerRows();
        configureMap();
        refreshSelection();
    }

    private void refreshDrawerRows() {
        for (int i = 0; i < drawerLayerRows.size(); i++) {
            styleDrawerRow(drawerLayerRows.get(i), i == state.settings.layer.ordinal());
        }
        for (int i = 0; i < drawerDimRows.size(); i++) {
            styleDrawerRow(drawerDimRows.get(i), i == state.settings.dimension);
        }
        if (drawerYRow != null) {
            drawerYRow.setText(
                    "Y " + state.settings.y + "（" + state.settings.lowerY + " ~ "
                            + state.settings.upperY + "）");
        }
        styleDimChips();
    }

    /** v0.4.214：顶部横幅维度 chips 选中态（当前维度 accent 高亮）。 */
    private void styleDimChips() {
        for (int i = 0; i < headerDimChips.size(); i++) {
            styleDrawerRow(headerDimChips.get(i), i == state.settings.dimension);
        }
    }

    private void buildRecords() {
        recordsPanel = WorldUi.column(this, 8);
        recordsPanel.setBackground(
                WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        recordSearch = WorldUi.input(this, getString(R.string.world_search_structures), "", false);
        toolbar.addView(recordSearch, new LinearLayout.LayoutParams(0, dp(38), 1));
        toolbar.addView(
                WorldUi.button(
                        this,
                        getString(R.string.world_import),
                        () -> {
                            state.importMode = 1;
                            importFile.launch(new String[] {"*/*"});
                        }));
        recordsPanel.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));
        recordSearch.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                        searchRunnable =
                                () -> {
                                    if (active && !busy && state.tab == 2) loadRecords(true);
                                };
                        handler.postDelayed(searchRunnable, 350);
                    }

                    public void afterTextChanged(Editable e) {}
                });
        records = new RecyclerView(this);
        // v0.4.214：双列网格（平板宽屏单列卡片内容稀疏=「那么窄」观感）
        records.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
        records.setAdapter(recordAdapter);
        records.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView view, int dx, int dy) {
                        if (dy > 0 && !view.canScrollVertically(1) && nextPage != null && !busy)
                            loadRecords(false);
                    }
                });
        recordsPanel.addView(records, new LinearLayout.LayoutParams(-1, 0, 1));
        recordSummary = WorldUi.text(this, "", 12, true);
        recordSummary.setGravity(Gravity.CENTER);
        recordSummary.setPadding(dp(12), dp(8), dp(12), dp(8));
        recordsPanel.addView(recordSummary);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, -1);
        rp.setMargins(dp(8), dp(8), dp(8), dp(8));
        pages.addView(recordsPanel, rp);
    }

    private void buildNbt() {
        nbtPanel = new LinearLayout(this);
        nbtPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout choices = WorldUi.column(this, 0);
        worldNbtButton = nbtChoice(getString(R.string.world_nbt_world_data), "level.dat", () -> openRecord(null, true));
        localPlayerButton =
                nbtChoice(
                        "~local_player",
                        "",
                        () -> openRecord("~local_player".getBytes(StandardCharsets.UTF_8), false));
        choices.addView(worldNbtButton, new LinearLayout.LayoutParams(-1, dp(72)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(64));
        lp.topMargin = dp(8);
        choices.addView(localPlayerButton, lp);
        int width =
                Math.max(148, Math.min(184, getResources().getConfiguration().screenWidthDp / 4));
        nbtPanel.addView(choices, new LinearLayout.LayoutParams(dp(width), -1));
        editorPanel = new FrameLayout(this);
        editorPanel.setBackground(
                WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        editorPanel.setClipToOutline(true);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, -1, 1);
        ep.setMarginStart(dp(8));
        nbtPanel.addView(editorPanel, ep);
        pages.addView(nbtPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout nbtChoice(String title, String subtitle, Runnable action) {
        LinearLayout card = WorldUi.column(this, 14);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = WorldUi.text(this, title, 13, false);
        name.setSingleLine(true);
        card.addView(name);
        if (!subtitle.isEmpty()) {
            TextView detail = WorldUi.text(this, subtitle, 11, true);
            detail.setPadding(0, dp(4), 0, 0);
            card.addView(detail);
        }
        card.setOnClickListener(
                v -> {
                    if (!busy && initialized) action.run();
                });
        card.setFocusable(true);
        return card;
    }

    private void updateNbtChoices() {
        boolean open = state.document != null || state.raw != null;
        boolean local =
                open
                        && !state.standalone
                        && !state.metadata
                        && Arrays.equals(
                                state.key, "~local_player".getBytes(StandardCharsets.UTF_8));
        LinearLayout[] choices = {worldNbtButton, localPlayerButton};
        boolean[] selected = {open && state.metadata, local};
        for (int i = 0; i < choices.length; i++) {
            choices[i].setSelected(selected[i]);
            choices[i].setBackground(
                    WorldUi.ripple(
                            this,
                            selected[i]
                                    ? WorldUi.translucent(WorldUi.accent(this), 25)
                                    : WorldUi.color(this, R.color.surface),
                            12));
            ((TextView) choices[i].getChildAt(0))
                    .setTextColor(
                            selected[i]
                                    ? WorldUi.accent(this)
                                    : WorldUi.color(this, R.color.on_surface));
        }
    }

    private void showTools() {
        if (busy) return;
        // v0.4.214：生物群系/结构/选区图像/删除区块已移入框选二级菜单
        // （用户指令——右下角加号只留独立工具）
        String[] names = {
            getString(R.string.world_tool_selection),
            getString(R.string.world_tool_blocks),
            getString(R.string.world_tool_locate),
            getString(R.string.world_goto),
            // Map options 已移入左侧设置抽屉（v0.4.214 冗余入口删除）；
            // 生物群系/结构/选区图像/删除区块/火把已移入框选二级菜单
            getString(R.string.world_tool_screenshot),
            getString(R.string.world_tool_advanced_nbt),
            getString(R.string.world_tool_html)
        };
        int[] icons = {
            R.drawable.ic_world_selection,
            R.drawable.ic_minecraft_cube,
            R.drawable.ic_search,
            R.drawable.ic_world_location,
            R.drawable.ic_photo,
            R.drawable.ic_edit,
            R.drawable.ic_export
        };
        Runnable[] actions = {
            this::selectionBounds,
            this::blockTools,
            this::locate,
            this::goMenu,
            this::screenshot,
            this::advancedNbt,
            this::exportHtml
        };
        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = WorldUi.column(this, 12);
        scroll.addView(grid);
        AlertDialog dialog =
                WorldUi.show(
                        this,
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(getString(R.string.world_tools_title))
                                .setView(scroll)
                                .setNegativeButton(getString(R.string.world_close), null));
        int columns = getResources().getConfiguration().screenWidthDp >= 760 ? 4 : 3;
        for (int i = 0; i < names.length; i += columns) {
            LinearLayout row = new LinearLayout(this);
            grid.addView(row, new LinearLayout.LayoutParams(-1, dp(82)));
            for (int j = i; j < Math.min(i + columns, names.length); j++) {
                final int index = j;
                LinearLayout tile = WorldUi.column(this, 8);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(
                        WorldUi.ripple(this, WorldUi.color(this, R.color.surface_variant), 12));
                ImageView icon = new ImageView(this);
                icon.setImageResource(icons[j]);
                icon.setImageTintList(ColorStateList.valueOf(WorldUi.accent(this)));
                tile.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
                TextView label = WorldUi.text(this, names[j], 11, false);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(6), 0, 0);
                tile.addView(label);
                tile.setFocusable(true);
                tile.setOnClickListener(
                        v -> {
                            dialog.dismiss();
                            actions[index].run();
                        });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(tile, p);
            }
        }
        if (dialog.getWindow() != null)
            dialog.getWindow()
                    .setLayout(
                            dp(
                                    Math.min(
                                            getResources().getConfiguration().screenWidthDp - 40,
                                            columns * 132 + 40)),
                            -2);
    }

    private void advancedNbt() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_tool_advanced_nbt))
                        .setItems(
                                new String[] {
                                    getString(R.string.world_open_db_key),
                                    getString(R.string.world_anbt_locator),
                                    getString(R.string.world_anbt_open_file),
                                    getString(R.string.world_anbt_create),
                                    getString(R.string.world_anbt_restore),
                                    getString(R.string.world_anbt_discard),
                                    getString(R.string.world_anbt_reload)
                                },
                                (d, i) -> {
                                    if (i == 0) openByKey();
                                    else if (i == 1) locate();
                                    else if (i == 2) {
                                        state.importMode = 2;
                                        importFile.launch(new String[] {"*/*"});
                                    } else if (i == 3) createRecord();
                                    else if (i == 4)
                                        confirm(
                                                getString(R.string.world_confirm_restore),
                                                getString(R.string.world_confirm_restore_msg),
                                                () ->
                                                        work(
                                                                getString(R.string.world_w_reading_history),
                                                                () -> {
                                                                    state.session
                                                                            .restoreLatestSave();
                                                                    return true;
                                                                },
                                                                v -> changedWorkspace()));
                                    else if (i == 5)
                                        confirm(
                                                getString(R.string.world_confirm_discard),
                                                getString(R.string.world_confirm_discard_msg),
                                                () ->
                                                        work(
                                                                getString(R.string.world_w_reloading_world),
                                                                () -> {
                                                                    state.session.discard();
                                                                    return true;
                                                                },
                                                                v -> {
                                                                    clearOpenDocument();
                                                                    changedWorkspace();
                                                                    showTab(state.tab);
                                                                }));
                                    else
                                        work(
                                                getString(R.string.world_w_refreshing_map),
                                                () -> {
                                                    state.renderer.clear();
                                                    return true;
                                                },
                                                v -> {
                                                    map.refresh();
                                                    configureMap();
                                                });
                                }));
    }

    private void handleTab(int position) {
        if (switchingTab) return;
        if (busy) {
            switchingTab = true;
            tabs.getTabAt(state.tab).select();
            switchingTab = false;
            return;
        }
        if (position != state.tab
                && (state.document != null || state.raw != null)
                && state.draftDirty) {
            if (state.standalone) {
                switchingTab = true;
                tabs.getTabAt(state.tab).select();
                switchingTab = false;
                resolveDraft(
                        () -> {
                            clearOpenDocument();
                            tabs.getTabAt(position).select();
                        });
                return;
            }
            work(
                    getString(R.string.world_w_keeping_nbt),
                    () -> {
                        stageDraft();
                        return true;
                    },
                    v -> {
                        clearOpenDocument();
                        showTab(position);
                    });
            return;
        }
        if (position != state.tab && (state.document != null || state.raw != null))
            clearOpenDocument();
        showTab(position);
    }

    private void clearOpenDocument() {
        state.document = null;
        state.raw = null;
        state.key = null;
        state.metadata = false;
        state.standalone = false;
        state.draftDirty = false;
        tree = null;
    }

    private void showTab(int tab) {
        state.tab = tab;
        if (tabs.getSelectedTabPosition() != tab) {
            switchingTab = true;
            tabs.getTabAt(tab).select();
            switchingTab = false;
        }
        mapPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        nbtPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        recordsPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        status.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        if (tab == 0) {
            if (active && initialized) map.start();
            mapStatus();
        } else map.stop();
        if (tab == 1) renderEditor();
        else if (tab == 2 && active && initialized) loadRecords(true);
        refreshSelection();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (state == null) return;
        active = true;
        state.session.resume();
        // v0.4.214：已初始化（跳系统文件管理器/切后台返回）时静默刷新
        // ——不弹「正在打开存档世界」阻挡窗（用户反馈跳 SAF 也弹）
        if (initialized) {
            // v0.4.214：后台切回后渲染 worker 已被 onStop 的 map.stop()
            // 停止——重启渲染（用户反馈切后台回来地图不渲染根因）
            if (state.tab == 0) map.start();
            executor.execute(
                    () -> {
                        try {
                            state.session.initialize();
                            List<WorldRecords.Marker> players =
                                    WorldRecords.players(state.session);
                            runOnUiThread(
                                    () -> {
                                        state.players = players;
                                        map.setPlayers(players);
                                    });
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        work(
                getString(R.string.world_w_opening_world),
                () -> {
                    state.session.initialize();
                    List<WorldRecords.Marker> players = WorldRecords.players(state.session);
                    // v0.4.214：默认中心=玩家位置（用户指定 0,119,0）。
                    // 质心扫描全 db 是「打开加载片刻」的根源（官方秒开=
                    // 只读玩家数据）——仅玩家缺失时才扫描兜底。
                    double cx = Double.NaN, cz = Double.NaN;
                    if (players.isEmpty()) {
                        try {
                            double[] centroid =
                                    org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                            .buildChunkCentroid(
                                                    new java.io.File(worldPath, "db"),
                                                    state.settings.dimension);
                            if (centroid != null) {
                                cx = centroid[0];
                                cz = centroid[1];
                            } else {
                                org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                                .WorldMap
                                        wm =
                                                org.levimc.launcher.core.content.worldmap
                                                        .WorldMapRenderer
                                                        .buildBoundsOnly(
                                                                new java.io.File(
                                                                        worldPath, "db"),
                                                                state.settings.dimension);
                                if (wm != null && wm.width > 0 && wm.height > 0) {
                                    cx = wm.minBlockX + wm.width / 2.0;
                                    cz = wm.minBlockZ + wm.height / 2.0;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    android.util.Log.i(
                            "LeviWorldEditor",
                            "open center bounds=" + cx + "," + cz + " players="
                                    + players.size());
                    return new Object[] {players, cx, cz};
                },
                info -> {
                    initialized = true;
                    @SuppressWarnings("unchecked")
                    List<WorldRecords.Marker> players =
                            (List<WorldRecords.Marker>) info[0];
                    state.players = players;
                    map.setPlayers(players);
                    double cx = (Double) info[1], cz = (Double) info[2];
                    // v0.4.214：打开中心=玩家位置优先（用户指定默认 (0,119,0)
                    // 即玩家所在处）；玩家读不到才用已生成区域质心兜底
                    if (!players.isEmpty()) {
                        map.center(players.get(0).x, players.get(0).z);
                    } else if (!Double.isNaN(cx)) {
                        map.center(cx, cz);
                    } else {
                        try {
                            NbtTag root = state.session.metadata().root;
                            map.center(number(root, "SpawnX", 0), number(root, "SpawnZ", 0));
                        } catch (Exception ignored) {
                        }
                    }
                    loadBlueprintPoints();
                    updatePending();
                    if (state.tab == 0) {
                        map.start();
                        mapStatus();
                    } else if (state.tab == 1) renderEditor();
                    else if (state.tab == 2) loadRecords(true);
                });
    }

    @Override
    protected void onStop() {
        active = false;
        job++;
        if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
        if (state != null) {
            state.centerX = map.centerX();
            state.centerZ = map.centerZ();
            state.scale = map.zoom();
            savePreferences();
            map.stop();
            state.session.suspend();
        }
        if (task != null && !saving) task.cancel(true);
        busy = false;
        if (blocker != null) blocker.setVisibility(View.GONE);
        if (executor != null && !executor.isShutdown())
            executor.execute(() -> state.session.releaseDatabase());
        super.onStop();
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return state;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (executor != null) {
            if (!isChangingConfigurations() && state != null)
                executor.execute(
                        () -> {
                            state.session.close();
                            state.renderer.clear();
                            clearExport();
                        });
            executor.shutdown();
        }
        super.onDestroy();
    }

    private <T> void work(String message, Callable<T> operation, Consumer<T> accept) {
        if (busy || executor == null || executor.isShutdown()) return;
        busy = true;
        int token = ++job;
        if (status != null) status.setText(message);
        if (progressLabel != null) progressLabel.setText(message);
        updatePending();
        if (blocker != null) blocker.setVisibility(View.VISIBLE);
        task =
                executor.submit(
                        () -> {
                            try {
                                T result = operation.call();
                                runOnUiThread(
                                        () -> {
                                            if (token != job || !active || isDestroyed()) return;
                                            busy = false;
                                            saving = false;
                                            blocker.setVisibility(View.GONE);
                                            updatePending();
                                            accept.accept(result);
                                        });
                            } catch (Exception | LinkageError ex) {
                                // v0.4.214：work 异常写日志（此前只弹窗
                                // 无堆栈，系统级异常无法定位）
                                android.util.Log.e(
                                        "LeviWorldEditor", "work failed: " + message, ex);
                                runOnUiThread(
                                        () -> {
                                            if (token != job || !active || isDestroyed()) return;
                                            busy = false;
                                            saving = false;
                                            blocker.setVisibility(View.GONE);
                                            error(
                                                    ex.getMessage() == null
                                                            ? ex.getClass().getSimpleName()
                                                            : ex.getMessage());
                                            updatePending();
                                        });
                            }
                        });
    }

    private void updatePending() {
        if (state == null || saveButton == null) return;
        boolean changed = state.session.pendingCount() > 0 || state.draftDirty;
        saveButton.setEnabled(changed && !busy);
        saveButton.setAlpha(changed ? 1f : .45f);
        saveButton.setText(
                state.standalone && state.draftDirty
                        ? getString(R.string.world_export)
                        : getString(R.string.world_save));
    }

    private void save() {
        if (busy) return;
        if (state.standalone) {
            exportDocument();
            return;
        }
        saving = true;
        work(
                getString(R.string.world_w_saving_world),
                () -> {
                    stageDraft();
                    return state.session.save();
                },
                file -> {
                    changedWorkspace();
                    toast(file == null ? getString(R.string.world_toast_no_changes) : getString(R.string.world_toast_saved));
                });
    }

    private void stageDraft() throws IOException {
        if (!state.draftDirty) return;
        if (state.standalone)
            throw new IOException(getString(R.string.world_e_export_standalone_first));
        byte[] bytes = state.document != null ? state.document.bytes() : state.raw;
        if (state.metadata) {
            NbtDocument doc = NativeNbt.decode(bytes, 0, NativeNbt.LEVEL_DAT);
            state.session.stageMetadata(doc);
        } else state.session.stage(state.key, bytes);
        state.draftDirty = false;
    }

    private void changedWorkspace() {
        map.configure(state.settings);
        if (state.tab == 0 && active) map.start();
        updatePending();
        refreshSelection();
        mapStatus();
    }

    public void changed() {
        state.draftDirty = true;
        updatePending();
    }

    @Override
    public void error(String message) {
        String text = message == null ? getString(R.string.world_e_unable) : translate(message);
        status.setText(text);
        toast(text);
    }

    /** v0.4.214：WorldOperations 硬编码英文错误 key 化（官方未翻译）
     *  ——world_e_* 前缀在显示端统一本地化，支持 key:参数 格式。 */
    private String translate(String message) {
        if (message == null) return getString(R.string.world_e_unable);
        if (message.startsWith("world_e_")) {
            String key = message;
            String suffix = "";
            int colon = message.indexOf(':');
            if (colon > 0) {
                key = message.substring(0, colon);
                suffix = " " + message.substring(colon + 1);
            }
            int id = getResources().getIdentifier(key, "string", getPackageName());
            return id == 0 ? message : getString(id) + suffix;
        }
        // v0.4.214：Java 运行时异常原始消息兜底（NPE 等未翻译异常）——
        // 用户要求：保留系统级异常全文，格式「意外错误：<原始消息>」
        if (message.contains("Attempt to invoke")
                || message.contains("null object reference")
                || message.contains("NullPointerException")
                || message.contains("Exception"))
            return getString(R.string.world_e_unexpected) + "：" + message;
        return message;
    }

    private void toast(String message) {
        String m = message == null ? getString(R.string.world_e_unable) : message;
        // v0.4.214：长异常消息截断（完整内容在 status 栏）
        if (m.length() > 120) m = m.substring(0, 120) + "…";
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void mapStatus() {
        if (status == null || state == null || busy) return;
        status.setText(String.format(Locale.ROOT, "X %.0f  Z %.0f", state.centerX, state.centerZ));
    }

    private void configureMap() {
        map.configure(state.settings);
        map.setPlayers(state.players);
        mapStatus();
        if (state.tab == 0 && active) map.start();
    }

    private void chooseY() {
        fields(
                getString(R.string.world_vertical_range),
                new String[] {getString(R.string.world_layer_y), getString(R.string.world_min_y), getString(R.string.world_max_y)},
                new String[] {
                    "" + state.settings.y, "" + state.settings.lowerY, "" + state.settings.upperY
                },
                values -> {
                    int y = parse(values[0]), min = parse(values[1]), max = parse(values[2]);
                    if (min > max || min < -128 || max > 511 || y < min || y > max)
                        throw new IOException(getString(R.string.world_e_y_range));
                    state.settings.y = y;
                    state.settings.lowerY = min;
                    state.settings.upperY = max;
                    refreshDrawerRows();
                    configureMap();
                });
    }

    private void toggle(
            LinearLayout parent, String label, boolean value, Consumer<Boolean> accept) {
        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setText(label);
        toggle.setTextSize(13);
        toggle.setTextColor(WorldUi.color(this, R.color.on_surface));
        toggle.setChecked(value);
        toggle.setThumbTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {WorldUi.accent(this), WorldUi.color(this, R.color.outline)}));
        toggle.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {
                            WorldUi.translucent(WorldUi.accent(this), 95),
                            WorldUi.translucent(WorldUi.color(this, R.color.outline), 65)
                        }));
        toggle.setOnCheckedChangeListener((b, c) -> accept.accept(c));
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void goMenu() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_goto))
                        .setItems(
                                new String[] {
                                    getString(R.string.world_go_spawn),
                                    getString(R.string.world_go_player),
                                    getString(R.string.world_go_coords),
                                    getString(R.string.world_bookmarks_title),
                                    getString(R.string.world_bookmark_add)
                                },
                                (d, i) -> {
                                    if (i == 0) goSpawn();
                                    else if (i == 1) goPlayer();
                                    else if (i == 2)
                                        fields(
                                                getString(R.string.world_go_coords),
                                                new String[] {"X", "Z"},
                                                new String[] {
                                                    "" + (int) state.centerX,
                                                    "" + (int) state.centerZ
                                                },
                                                v -> map.center(parse(v[0]), parse(v[1])));
                                    else if (i == 3) bookmarks();
                                    else
                                        addBookmark(
                                                (int) state.centerX,
                                                state.settings.y,
                                                (int) state.centerZ);
                                }));
    }

    private void goSpawn() {
        work(
                getString(R.string.world_w_reading_spawn),
                () -> {
                    NbtTag root = state.session.metadata().root;
                    return new int[] {
                        number(root, "SpawnX", 0),
                        number(root, "SpawnY", 64),
                        number(root, "SpawnZ", 0),
                        number(root, "SpawnDimension", 0)
                    };
                },
                p -> {
                    state.settings.dimension = p[3];
                    state.settings.y = p[1];
                    refreshDrawerRows();
                    map.center(p[0], p[2]);
                    configureMap();
                });
    }

    private void goPlayer() {
        work(
                getString(R.string.world_w_reading_players),
                () -> WorldRecords.players(state.session),
                players -> {
                    state.players = players;
                    map.setPlayers(players);
                    if (players.isEmpty()) {
                        error(getString(R.string.world_e_no_players_found));
                        return;
                    }
                    String[] labels =
                            players.stream()
                                    .map(
                                            p ->
                                                    p.label + " · " + (int) p.x + ", " + (int) p.y
                                                            + ", " + (int) p.z)
                                    .toArray(String[]::new);
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.world_players_title))
                                    .setItems(labels, (d, i) -> goMarker(players.get(i))));
                });
    }

    private void goMarker(WorldRecords.Marker marker) {
        state.settings.dimension = marker.dimension;
        state.settings.y = (int) marker.y;
        refreshDrawerRows();
        map.center(marker.x, marker.z);
        configureMap();
        tabs.getTabAt(0).select();
    }

    /** v0.4.214：结构信息弹窗（点击地图结构图标）——结构名 + 坐标 +
     *  Wiki 教学文案（这是什么结构、干什么用的）。 */
    private void structureInfo(int x, int z, String type) {
        String label =
                org.levimc.launcher.ui.views.WorldMapView.structureLabel(this, type);
        int infoRes = structureInfoRes(type);
        String message =
                getString(R.string.world_struct_coords_fmt, x, z)
                        + "\n\n"
                        + (infoRes != 0 ? getString(infoRes) : getString(R.string.world_struct_info_generic));
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(label)
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.world_close), null));
    }

    private int structureInfoRes(String type) {
        switch (type) {
            case "village": return R.string.world_struct_info_village;
            case "desert_pyramid":
            case "desert_temple": return R.string.world_struct_info_desert_pyramid;
            case "jungle_temple":
            case "jungle_pyramid": return R.string.world_struct_info_jungle_temple;
            case "swamp_hut": return R.string.world_struct_info_swamp_hut;
            case "igloo": return R.string.world_struct_info_igloo;
            case "shipwreck": return R.string.world_struct_info_shipwreck;
            case "ocean_ruin": return R.string.world_struct_info_ocean_ruin;
            case "ocean_monument":
            case "monument": return R.string.world_struct_info_ocean_monument;
            case "woodland_mansion":
            case "mansion": return R.string.world_struct_info_woodland_mansion;
            case "pillager_outpost": return R.string.world_struct_info_pillager_outpost;
            case "ruined_portal": return R.string.world_struct_info_ruined_portal;
            case "ancient_city": return R.string.world_struct_info_ancient_city;
            case "buried_treasure":
            case "treasure": return R.string.world_struct_info_buried_treasure;
            case "mineshaft": return R.string.world_struct_info_mineshaft;
            case "trail_ruins": return R.string.world_struct_info_trail_ruins;
            case "trial_chambers": return R.string.world_struct_info_trial_chambers;
            case "fortress": return R.string.world_struct_info_fortress;
            case "end_city": return R.string.world_struct_info_end_city;
            default: return 0;
        }
    }

    private void inspect(int x, int z, boolean hold) {
        if (busy || !initialized) return;
        work(
                getString(R.string.world_w_inspecting),
                () -> {
                    WorldChunk chunk =
                            state.renderer.chunk(
                                    Math.floorDiv(x, 16),
                                    Math.floorDiv(z, 16),
                                    state.settings.dimension);
                    int y =
                            state.settings.slice
                                    ? state.settings.y
                                    : chunk.top(x & 15, z & 15, state.settings.leaves);
                    NbtTag block = chunk.blockAt(x & 15, y, z & 15);
                    return new Object[] {
                        chunk,
                        y,
                        block,
                        NativeNbt.toSnbt(block),
                        WorldRecords.chunkMarkers(state.session, chunk.x, chunk.z, state.settings)
                    };
                },
                info -> {
                    WorldChunk chunk = (WorldChunk) info[0];
                    int y = (int) info[1];
                    NbtTag block = (NbtTag) info[2];
                    String detail =
                            x
                                    + ", "
                                    + y
                                    + ", "
                                    + z
                                    + "\n"
                                    + WorldChunk.name(block)
                                    + "\n"
                                    + MapPalette.biomeName(chunk.biome(x & 15, y, z & 15))
                                    + "\n"
                                    + getString(
                                            R.string.world_chunk_at,
                                            String.valueOf(chunk.x),
                                            String.valueOf(chunk.z));
                    if (!chunk.exists)
                        detail += "\n" + getString(R.string.world_not_generated);
                    String[] actions = {
                        getString(R.string.world_inspect_block_states),
                        getString(R.string.world_inspect_bookmark),
                        getString(R.string.world_inspect_block_entities),
                        getString(R.string.world_inspect_entities),
                        getString(R.string.world_inspect_teleport),
                        getString(R.string.world_inspect_move_spawn),
                        getString(R.string.world_inspect_chunk_records)
                    };
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.world_inspect_title))
                                    .setMessage(detail)
                                    .setPositiveButton(
                                            hold ? getString(R.string.world_inspect_select) : getString(R.string.world_inspect_actions),
                                            (d, w) ->
                                                    WorldUi.show(
                                                            this,
                                                            new MaterialAlertDialogBuilder(this)
                                                                    .setTitle(
                                                                            x + ", " + y + ", " + z)
                                                                    .setItems(
                                                                            actions,
                                                                            (dialog, i) -> {
                                                                                if (i == 0)
                                                                                    editBlock(
                                                                                            x,
                                                                                            y,
                                                                                            z,
                                                                                            (String)
                                                                                                    info[
                                                                                                            3]);
                                                                                else if (i == 1)
                                                                                    addBookmark(
                                                                                            x, y,
                                                                                            z);
                                                                                else if (i == 2)
                                                                                    openRecord(
                                                                                            ChunkKey
                                                                                                    .of(
                                                                                                            chunk.x,
                                                                                                            chunk.z,
                                                                                                            state.settings
                                                                                                                    .dimension,
                                                                                                            0x31),
                                                                                            false);
                                                                                else if (i == 3)
                                                                                    entityRecords(
                                                                                            chunk.x,
                                                                                            chunk.z);
                                                                                else if (i == 4)
                                                                                    teleport(
                                                                                            x,
                                                                                            y + 1,
                                                                                            z);
                                                                                else if (i == 5)
                                                                                    moveSpawn(
                                                                                            x,
                                                                                            y + 1,
                                                                                            z);
                                                                                else
                                                                                    chunkRecords(
                                                                                            chunk.x,
                                                                                            chunk.z);
                                                                            })))
                                    .setNegativeButton(getString(R.string.world_close), null));
                });
    }

    private void editBlock(int x, int y, int z, String snbt) {
        fields(
                getString(R.string.world_block_palette),
                new String[] {getString(R.string.world_block_compound)},
                new String[] {snbt},
                values -> {
                    WorldOperations.Region region =
                            new WorldOperations.Region(x, y, z, x, y, z, state.settings.dimension);
                    work(
                            getString(R.string.world_w_editing_block),
                            () ->
                                    new WorldOperations(state.session)
                                            .blocks(
                                                    region,
                                                    null,
                                                    WorldOperations.parseBlock(values[0]),
                                                    0,
                                                    this::operationProgress),
                            count -> changedWorkspace());
                });
    }

    private void entityRecords(int cx, int cz) {
        work(
                getString(R.string.world_w_reading_entity_records),
                () -> {
                    MapSettings all = state.settings.copy();
                    all.entities = true;
                    all.blockEntities = false;
                    all.entityFilter = "";
                    return WorldRecords.chunkMarkers(state.session, cx, cz, all);
                },
                markers -> {
                    if (markers.isEmpty()) {
                        error(getString(R.string.world_e_no_entities));
                        return;
                    }
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.world_entities_title))
                                    .setItems(
                                            markers.stream()
                                                    .map(
                                                            m ->
                                                                    m.label + " · " + (int) m.x
                                                                            + ", " + (int) m.y
                                                                            + ", " + (int) m.z)
                                                    .toArray(String[]::new),
                                            (d, i) -> openRecord(markers.get(i).key, false)));
                });
    }

    private void chunkRecords(int cx, int cz) {
        work(
                getString(R.string.world_w_reading_chunk_records),
                () -> {
                    List<byte[]> keys = new ArrayList<>();
                    byte[] next = null;
                    do {
                        WorldDatabase.Page page =
                                state.session.scan(ChunkKey.prefix(cx, cz), next, 256, "");
                        for (byte[] key : page.keys) {
                            ChunkKey c = ChunkKey.parse(key);
                            if (c != null && c.dimension == state.settings.dimension) keys.add(key);
                        }
                        next = page.next;
                    } while (next != null);
                    return keys;
                },
                keys -> {
                    if (keys.isEmpty()) {
                        error(getString(R.string.world_e_no_records));
                        return;
                    }
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.world_inspect_chunk_records))
                                    .setItems(
                                            keys.stream()
                                                    .map(WorldRecords::label)
                                                    .toArray(String[]::new),
                                            (d, i) -> openRecord(keys.get(i), false)));
                });
    }

    private void teleport(int x, int y, int z) {
        work(
                getString(R.string.world_w_reading_players),
                () -> WorldRecords.players(state.session),
                players -> {
                    if (players.isEmpty()) {
                        error(getString(R.string.world_e_no_players));
                        return;
                    }
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.world_move_player_title))
                                    .setItems(
                                            players.stream()
                                                    .map(p -> p.label)
                                                    .toArray(String[]::new),
                                            (d, i) ->
                                                    confirm(
                                                            getString(R.string.world_move_player_confirm),
                                                            getString(
                                                                    R.string
                                                                            .world_in_dim_fmt,
                                                                    x,
                                                                    y,
                                                                    z,
                                                                    dimensionNameLocalized(
                                                                            state.settings
                                                                                    .dimension)),
                                                            () ->
                                                                    work(
                                                                            getString(R.string.world_w_updating_player),
                                                                            () -> {
                                                                                WorldRecords.Marker
                                                                                        player =
                                                                                                players
                                                                                                        .get(
                                                                                                                i);
                                                                                WorldRecords
                                                                                                .Document
                                                                                        doc =
                                                                                                WorldRecords
                                                                                                        .decode(
                                                                                                                state
                                                                                                                        .session
                                                                                                                        .get(
                                                                                                                                player.key));
                                                                                NbtTag pos =
                                                                                        doc.root
                                                                                                .getTag(
                                                                                                        "Pos");
                                                                                if (pos == null
                                                                                        || pos.getList()
                                                                                                        .size()
                                                                                                != 3)
                                                                                    throw new IOException(
                                                                                            getString(R.string.world_e_player_pos));
                                                                                for (int j = 0;
                                                                                        j < 3;
                                                                                        j++)
                                                                                    pos.getList()
                                                                                            .get(j)
                                                                                            .setValue(
                                                                                                    (float)
                                                                                                            (j
                                                                                                                            == 0
                                                                                                                    ? x
                                                                                                                            + .5
                                                                                                                    : j
                                                                                                                                    == 1
                                                                                                                            ? y
                                                                                                                            : z
                                                                                                                                    + .5));
                                                                                doc.root.putTag(
                                                                                        "DimensionId",
                                                                                        WorldOperations
                                                                                                .intTag(
                                                                                                        "DimensionId",
                                                                                                        state.settings
                                                                                                                .dimension));
                                                                                NbtTag motion =
                                                                                        doc.root
                                                                                                .getTag(
                                                                                                        "Motion");
                                                                                if (motion != null)
                                                                                    for (NbtTag n :
                                                                                            motion
                                                                                                    .getList())
                                                                                        n.setValue(
                                                                                                0f);
                                                                                state.session.stage(
                                                                                        player.key,
                                                                                        doc
                                                                                                .bytes());
                                                                                return true;
                                                                            },
                                                                            v ->
                                                                                    changedWorkspace()))));
                });
    }

    private void moveSpawn(int x, int y, int z) {
        confirm(
                getString(R.string.world_move_spawn_confirm),
                x + ", " + y + ", " + z,
                () ->
                        work(
                                getString(R.string.world_w_updating_spawn),
                                () -> {
                                    NbtDocument doc = state.session.metadata();
                                    doc.root.putTag("SpawnX", WorldOperations.intTag("SpawnX", x));
                                    doc.root.putTag("SpawnY", WorldOperations.intTag("SpawnY", y));
                                    doc.root.putTag("SpawnZ", WorldOperations.intTag("SpawnZ", z));
                                    doc.root.putTag(
                                            "SpawnDimension",
                                            WorldOperations.intTag(
                                                    "SpawnDimension", state.settings.dimension));
                                    state.session.stageMetadata(doc);
                                    return true;
                                },
                                v -> changedWorkspace()));
    }

    private void loadRecords(boolean reset) {
        if (!initialized || busy || state.tab != 2 || !reset && nextPage == null) return;
        byte[] start = reset ? null : nextPage;
        String query = recordSearch.getText().toString();
        byte[] prefix = WorldRecords.prefix(WorldRecords.Group.STRUCTURES);
        work(
                getString(R.string.world_w_reading_structures),
                () -> {
                    List<byte[]> keys = new ArrayList<>();
                    if (reset)
                        for (byte[] key : state.session.stagedKeys(prefix))
                            if (WorldRecords.matches(key, WorldRecords.Group.STRUCTURES)
                                    && new String(key, StandardCharsets.UTF_8)
                                            .toLowerCase(Locale.ROOT)
                                            .contains(query.toLowerCase(Locale.ROOT)))
                                keys.add(key);
                    WorldDatabase.Page page = state.session.scan(prefix, start, 128, query);
                    keys.addAll(Arrays.asList(page.keys));
                    return new Object[] {keys, page.next};
                },
                result -> {
                    if (reset) recordKeys.clear();
                    recordKeys.addAll((List<byte[]>) result[0]);
                    nextPage = (byte[]) result[1];
                    recordAdapter.notifyDataSetChanged();
                    recordSummary.setText(
                            recordKeys.isEmpty() && nextPage == null ? getString(R.string.world_e_no_structures) : "");
                    recordSummary.setVisibility(
                            recordKeys.isEmpty() && nextPage == null ? View.VISIBLE : View.GONE);
                    records.post(
                            () -> {
                                if (state.tab == 2
                                        && !records.canScrollVertically(1)
                                        && nextPage != null
                                        && !busy) loadRecords(false);
                            });
                });
    }

    private void openRecord(byte[] key, boolean metadata) {
        if (busy || !initialized) return;
        if ((state.document != null || state.raw != null)
                && !state.standalone
                && state.metadata == metadata
                && Arrays.equals(state.key, key)) return;
        if (state.draftDirty) {
            resolveDraft(() -> openRecord(key, metadata));
            return;
        }
        work(
                getString(R.string.world_w_opening_nbt),
                () -> {
                    byte[] bytes =
                            metadata ? state.session.metadata().encode() : state.session.get(key);
                    if (bytes == null)
                        throw new IOException(getString(R.string.world_e_no_record));
                    WorldRecords.Document document = null;
                    try {
                        document = WorldRecords.decode(bytes);
                    } catch (IOException ignored) {
                    }
                    return new Object[] {bytes, document};
                },
                result -> {
                    state.key = key;
                    state.metadata = metadata;
                    state.standalone = false;
                    state.document = (WorldRecords.Document) result[1];
                    state.raw = state.document == null ? (byte[]) result[0] : null;
                    state.draftDirty = false;
                    showTab(1);
                });
    }

    private void renderEditor() {
        editorPanel.removeAllViews();
        updateNbtChoices();
        if (state.document == null && state.raw == null) {
            tree = null;
            TextView empty = WorldUi.text(this, getString(R.string.world_select_nbt), 13, true);
            empty.setGravity(Gravity.CENTER);
            editorPanel.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        LinearLayout body = WorldUi.column(this, 0);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(8), 0);
        TextView title =
                WorldUi.text(
                        this,
                        state.metadata
                                ? getString(R.string.world_nbt_world_data)
                                : state.standalone ? getString(R.string.world_nbt_file) : WorldRecords.label(state.key),
                        14,
                        false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(
                WorldUi.iconButton(this, R.drawable.ic_export, getString(R.string.world_export_nbt), this::exportDocument),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
        if (state.document != null) {
            tree =
                    new NbtTreePanel(
                            this, state.document.root, this, tag -> state.document.root = tag);
            body.addView(tree, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            tree = null;
            TextView hint =
                    WorldUi.text(
                            this,
                            getString(R.string.world_binary_bytes_fmt, state.raw.length),
                            13,
                            true);
            hint.setPadding(dp(16), dp(12), dp(16), dp(12));
            body.addView(hint);
            ScrollView scroll = new ScrollView(this);
            TextView preview =
                    WorldUi.text(
                            this,
                            WorldSession.hex(
                                            Arrays.copyOf(
                                                    state.raw, Math.min(state.raw.length, 8192)))
                                    + (state.raw.length > 8192
                                            ? "\n" + getString(R.string.world_export_to_inspect)
                                            : ""),
                            12,
                            false);
            preview.setTypeface(android.graphics.Typeface.MONOSPACE);
            preview.setTextIsSelectable(true);
            preview.setPadding(dp(16), dp(8), dp(16), dp(8));
            scroll.addView(preview);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout tools = new LinearLayout(this);
            tools.addView(
                    WorldUi.button(
                            this,
                            getString(R.string.world_edit_hex),
                            () -> {
                                if (state.raw.length > 262144) {
                                    error(
                                            getString(R.string.world_e_binary_too_large));
                                    return;
                                }
                                fields(
                                        getString(R.string.world_exact_hex),
                                        new String[] {getString(R.string.world_hex_bytes)},
                                        new String[] {WorldSession.hex(state.raw)},
                                        v -> {
                                            state.raw =
                                                    v[0].replaceAll("\\s", "").isEmpty()
                                                            ? new byte[0]
                                                            : WorldSession.unhex(v[0]);
                                            changed();
                                            renderEditor();
                                        });
                            }));
            tools.addView(
                    WorldUi.button(
                            this,
                            getString(R.string.world_import_value),
                            () -> {
                                state.importMode = 3;
                                importFile.launch(new String[] {"*/*"});
                            }));
            body.addView(tools);
        }
        editorPanel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        updatePending();
    }

    private void closeEditor(boolean ask) {
        if (ask && state.draftDirty) {
            resolveDraft(() -> closeEditor(false));
            return;
        }
        clearOpenDocument();
        updatePending();
        showTab(state.tab);
    }

    private void resolveDraft(Runnable next) {
        if (!state.standalone) {
            work(
                    getString(R.string.world_w_keeping_nbt),
                    () -> {
                        stageDraft();
                        return true;
                    },
                    v -> {
                        updatePending();
                        next.run();
                    });
            return;
        }
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_unsaved_nbt))
                        .setMessage(getString(R.string.world_unsaved_nbt_msg))
                        .setPositiveButton(getString(R.string.world_export), (d, w) -> exportDocument())
                        .setNeutralButton(
                                getString(R.string.world_discard),
                                (d, w) -> {
                                    state.draftDirty = false;
                                    next.run();
                                })
                        .setNegativeButton(getString(R.string.world_cancel), null));
    }

    @Override
    public void snbt(NbtTag tag, Consumer<NbtTag> accept) {
        work(
                getString(R.string.world_w_formatting_snbt),
                () -> NativeNbt.toSnbt(tag),
                text -> {
                    if (text.length() > 1048576) {
                        error(
                                getString(R.string.world_e_tag_too_large));
                        return;
                    }
                    LinearLayout form = WorldUi.column(this, 16);
                    EditText input = WorldUi.input(this, "SNBT", text, true);
                    input.setTypeface(android.graphics.Typeface.MONOSPACE);
                    input.setMinLines(4);
                    input.setMaxLines(12);
                    ScrollView scroll = new ScrollView(this);
                    scroll.addView(input);
                    form.addView(scroll);
                    AlertDialog dialog =
                            WorldUi.show(
                                    this,
                                    new MaterialAlertDialogBuilder(this)
                                            .setTitle(getString(R.string.world_edit_snbt))
                                            .setView(form)
                                            .setPositiveButton(getString(R.string.world_apply), null)
                                            .setNegativeButton(getString(R.string.world_cancel), null));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    v -> {
                                        String value = input.getText().toString();
                                        dialog.dismiss();
                                        work(
                                                getString(R.string.world_w_parsing_snbt),
                                                () -> NativeNbt.fromSnbt(value),
                                                accept);
                                    });
                });
    }

    @Override
    public void search(Runnable operation, Runnable accept) {
        work(
                getString(R.string.world_w_searching_nbt),
                () -> {
                    operation.run();
                    return true;
                },
                value -> accept.run());
    }

    @Override
    public void copy(NbtTag tag, Consumer<NbtTag> accept) {
        work(getString(R.string.world_w_copying_tag), () -> WorldOperations.copy(tag), accept);
    }

    /** v0.4.214：结构记录菜单=内容管理三点同款气泡（锚定笔图标）。 */
    private void recordMenu(byte[] key, View anchor) {
        boolean structure = WorldRecords.matches(key, WorldRecords.Group.STRUCTURES);
        java.util.List<ContentActionPopup.Action> actions = new java.util.ArrayList<>();
        actions.add(
                new ContentActionPopup.Action(
                        R.drawable.ic_edit,
                        structure ? R.string.world_rm_edit_nbt : R.string.world_rm_edit_binary,
                        false,
                        () -> openRecord(key, false)));
        actions.add(
                new ContentActionPopup.Action(
                        R.drawable.ic_export,
                        structure
                                ? R.string.world_rm_export_structure
                                : R.string.world_rm_export_value,
                        false,
                        () ->
                                work(
                                        getString(R.string.world_w_preparing_export),
                                        () -> state.session.get(key),
                                        bytes -> {
                                            if (bytes == null) {
                                                error(
                                                        getString(
                                                                R.string
                                                                        .world_e_record_deleted));
                                                return;
                                            }
                                            exportBytes(
                                                    bytes,
                                                    structure
                                                            ? structureName(key)
                                                                    + ".mcstructure"
                                                            : "record.nbt");
                                        })));
        if (structure)
            actions.add(
                    new ContentActionPopup.Action(
                            R.drawable.ic_content,
                            R.string.world_place_title,
                            false,
                            () -> pasteSaved(key)));
        actions.add(
                new ContentActionPopup.Action(
                        R.drawable.ic_download,
                        R.string.world_import_value,
                        false,
                        () -> {
                            if (state.draftDirty) {
                                resolveDraft(
                                        () -> {
                                            state.key = key;
                                            state.metadata = false;
                                            state.standalone = false;
                                            state.importMode = 3;
                                            importFile.launch(new String[] {"*/*"});
                                        });
                            } else {
                                state.key = key;
                                state.metadata = false;
                                state.standalone = false;
                                state.importMode = 3;
                                importFile.launch(new String[] {"*/*"});
                            }
                        }));
        if (!structure)
            actions.add(
                    new ContentActionPopup.Action(
                            R.drawable.ic_content,
                            R.string.world_rm_copy_hex,
                            false,
                            () -> {
                                ((android.content.ClipboardManager)
                                                getSystemService(CLIPBOARD_SERVICE))
                                        .setPrimaryClip(
                                                android.content.ClipData.newPlainText(
                                                        getString(R.string.world_db_key),
                                                        WorldSession.hex(key)));
                                toast(getString(R.string.world_toast_key_copied));
                            }));
        actions.add(
                new ContentActionPopup.Action(
                        R.drawable.ic_delete,
                        R.string.world_rm_delete,
                        true,
                        () ->
                                confirm(
                                        getString(R.string.world_confirm_delete_record),
                                        WorldRecords.label(key)
                                                + "\n"
                                                + getString(R.string.world_save_to_apply),
                                        () ->
                                                work(
                                                        getString(
                                                                R.string
                                                                        .world_w_deleting_record),
                                                        () -> {
                                                            state.session.stage(key, null);
                                                            return true;
                                                        },
                                                        v -> {
                                                            changedWorkspace();
                                                            loadRecords(true);
                                                        }))));
        ContentActionPopup.show(anchor, WorldRecords.label(key), actions);
    }

    private void openByKey() {
        fields(
                getString(R.string.world_open_db_key),
                new String[] {getString(R.string.world_db_key_hint)},
                new String[] {""},
                v -> {
                    byte[] key =
                            v[0].startsWith("hex:")
                                    ? WorldSession.unhex(v[0].substring(4))
                                    : v[0].getBytes(StandardCharsets.UTF_8);
                    openRecord(key, false);
                });
    }

    private void createRecord() {
        fields(
                getString(R.string.world_create_record),
                new String[] {getString(R.string.world_utf8_key), getString(R.string.world_snbt_value)},
                new String[] {"", "{}"},
                v ->
                        work(
                                getString(R.string.world_w_creating_record),
                                () -> {
                                    if (v[0].isEmpty())
                                        throw new IOException(getString(R.string.world_e_key_empty));
                                    byte[] key = v[0].getBytes(StandardCharsets.UTF_8);
                                    if (state.session.get(key) != null)
                                        throw new IOException(getString(R.string.world_e_key_exists));
                                    byte[] bytes =
                                            NativeNbt.encode(
                                                    NativeNbt.fromSnbt(v[1]),
                                                    0,
                                                    NativeNbt.LITTLE_ENDIAN,
                                                    false);
                                    state.session.stage(key, bytes);
                                    return key;
                                },
                                key -> {
                                    changedWorkspace();
                                    openRecord(key, false);
                                }));
    }

    private void exportDocument() {
        if (state.document == null && state.raw == null) return;
        work(
                getString(R.string.world_w_preparing_nbt_export),
                () -> state.document == null ? state.raw : state.document.bytes(),
                bytes ->
                        exportBytes(
                                bytes,
                                state.metadata
                                        ? "level.dat"
                                        : state.key != null
                                                        && WorldRecords.matches(
                                                                state.key,
                                                                WorldRecords.Group.STRUCTURES)
                                                ? structureName(state.key) + ".mcstructure"
                                                : "edited.nbt"));
    }

    private void exportBytes(byte[] bytes, String filename) {
        state.pendingExport = bytes;
        state.pendingImage = null;
        exportFile.launch(filename.replaceAll("[\\\\/:*?\"<>|]", "_"));
    }

    private void replaceValue(byte[] data) {
        confirm(
                getString(R.string.world_confirm_replace),
                getString(R.string.world_confirm_replace_msg),
                () ->
                        work(
                                getString(R.string.world_w_importing_value),
                                () -> {
                                    if (state.metadata) {
                                        NativeNbt.decode(data, 0, NativeNbt.LEVEL_DAT);
                                        state.session.stageMetadata(
                                                NativeNbt.decode(data, 0, NativeNbt.LEVEL_DAT));
                                    } else {
                                        if (WorldRecords.matches(
                                                state.key, WorldRecords.Group.STRUCTURES))
                                            WorldOperations.validateStructure(data);
                                        state.session.stage(state.key, data);
                                    }
                                    return true;
                                },
                                v -> {
                                    state.draftDirty = false;
                                    state.document = null;
                                    state.raw = null;
                                    openRecord(state.key, state.metadata);
                                    changedWorkspace();
                                }));
    }

    private void importStructure(byte[] bytes) {
        work(
                getString(R.string.world_w_reading_structure),
                () -> {
                    WorldOperations.validateStructure(bytes);
                    return bytes;
                },
                data ->
                        fields(
                                getString(R.string.world_import_structure),
                                new String[] {getString(R.string.world_structure_id)},
                                new String[] {"mystructure:imported"},
                                v ->
                                        work(
                                                getString(R.string.world_w_importing_structure),
                                                () -> {
                                                    new WorldOperations(state.session)
                                                            .saveStructure(v[0], data);
                                                    return true;
                                                },
                                                done -> {
                                                    changedWorkspace();
                                                    state.tab = 2;
                                                    tabs.getTabAt(2).select();
                                                    closeEditor(false);
                                                    loadRecords(true);
                                                })));
    }

    private void openNbtFile(byte[] bytes) {
        if (state.draftDirty) {
            resolveDraft(() -> openNbtFile(bytes));
            return;
        }
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_nbt_format))
                        .setItems(
                                new String[] {
                                    getString(R.string.world_nbt_le),
                                    getString(R.string.world_nbt_leveldat),
                                    getString(R.string.world_nbt_be)
                                },
                                (d, i) ->
                                        work(
                                                getString(R.string.world_w_opening_nbt_file),
                                                () -> {
                                                    NbtDocument doc =
                                                            NativeNbt.decode(
                                                                    bytes,
                                                                    0,
                                                                    i == 1
                                                                            ? NativeNbt.LEVEL_DAT
                                                                            : i == 2
                                                                                    ? NativeNbt
                                                                                            .BIG_ENDIAN
                                                                                    : NativeNbt
                                                                                            .LITTLE_ENDIAN);
                                                    if (!doc.compressed
                                                            && doc.bytesRead != bytes.length)
                                                        throw new IOException(
                                                                getString(R.string.world_e_trailing_data));
                                                    return new WorldRecords.Document(
                                                            doc.root, false, doc);
                                                },
                                                doc -> {
                                                    state.document = doc;
                                                    state.raw = null;
                                                    state.key = null;
                                                    state.metadata = false;
                                                    state.standalone = true;
                                                    state.draftDirty = false;
                                                    showTab(1);
                                                })));
    }

    private void locate() {
        String[] kinds = {
            getString(R.string.world_loc_block), getString(R.string.world_loc_biome), getString(R.string.world_loc_entity), getString(R.string.world_loc_block_entity), getString(R.string.world_loc_key)
        };
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_locator_title))
                        .setItems(
                                kinds,
                                (d, i) ->
                                        fields(
                                                getString(R.string.world_locate_fmt, kinds[i]),
                                                new String[] {
                                                    getString(R.string.world_name_or_id),
                                                    getString(R.string.world_radius),
                                                    getString(R.string.world_min_y),
                                                    getString(R.string.world_max_y)
                                                },
                                                new String[] {
                                                    i == 0 ? "diamond_ore" : i == 1 ? "1" : "",
                                                    "8",
                                                    "" + state.settings.lowerY,
                                                    "" + state.settings.upperY
                                                },
                                                v -> {
                                                    int radius = parse(v[1]),
                                                            min = parse(v[2]),
                                                            max = parse(v[3]);
                                                    if (min > max)
                                                        throw new IOException(
                                                                getString(R.string.world_e_min_exceeds_max));
                                                    work(
                                                            getString(R.string.world_w_searching_chunks),
                                                            () ->
                                                                    new WorldLocator(
                                                                                    state.session,
                                                                                    state.renderer)
                                                                            .search(
                                                                                    WorldLocator
                                                                                            .Kind
                                                                                            .values()[
                                                                                            i],
                                                                                    v[0],
                                                                                    (int)
                                                                                            state.centerX,
                                                                                    (int)
                                                                                            state.centerZ,
                                                                                    radius,
                                                                                    state.settings
                                                                                            .dimension,
                                                                                    min,
                                                                                    max,
                                                                                    this
                                                                                            ::operationProgress),
                                                            results -> {
                                                                if (results.isEmpty()) {
                                                                    error(
                                                                            getString(R.string.world_e_no_matches));
                                                                    return;
                                                                }
                                                                WorldUi.show(
                                                                        this,
                                                                        new MaterialAlertDialogBuilder(
                                                                                        this)
                                                                                .setTitle(
                                                                                        getString(
                                                                                                        R.string
                                                                                                                .world_matches_fmt,
                                                                                                        results
                                                                                                                .size())
                                                                                                + (results
                                                                                                                .size()
                                                                                                        >= 512
                                                                                                        ? getString(
                                                                                                                R.string
                                                                                                                        .world_limit_suffix)
                                                                                                        : ""))
                                                                                .setItems(
                                                                                        results
                                                                                                .stream()
                                                                                                .map(
                                                                                                        Object
                                                                                                                ::toString)
                                                                                                .toArray(
                                                                                                        String
                                                                                                                        []
                                                                                                                ::new),
                                                                                        (dialog,
                                                                                                n) -> {
                                                                                            WorldLocator
                                                                                                            .Result
                                                                                                    result =
                                                                                                            results
                                                                                                                    .get(
                                                                                                                            n);
                                                                                            if (result.key
                                                                                                    != null)
                                                                                                openRecord(
                                                                                                        result.key,
                                                                                                        false);
                                                                                            else {
                                                                                                map
                                                                                                        .center(
                                                                                                                result.x,
                                                                                                                result.z);
                                                                                                state.settings
                                                                                                                .y =
                                                                                                        result.y;
                                                                                                refreshDrawerRows();
                                                                                                configureMap();
                                                                                            }
                                                                                        }));
                                                            });
                                                })));
    }

    private boolean operationProgress(int current, int total) {
        if (Thread.currentThread().isInterrupted() || !active) return false;
        if (current % 8 == 0) {
            int token = job;
            runOnUiThread(
                    () -> {
                        if (active && token == job)
                            status.setText(
                                    total > 0
                                            ? getString(R.string.world_status_processing, current, total)
                                            : getString(R.string.world_status_scanning, current));
                    });
        }
        return true;
    }

    private void selectionBounds() {
        // v0.4.214：「选择」按钮=仅框选（LBBL 方案）——单指滑动粗框选，
        // 四角与区块交接处顶点拖动微调，完成后底部操作条应用/撤销/关闭
        map.setSelectMode(true);
        if (selBar != null) selBar.setVisibility(View.VISIBLE);
        toast(getString(R.string.world_sel_drag_hint));
    }

    /** v0.4.214：框选变化——同步 state 多边形顶点链与官方 bbox
     *  （结构/图像/火把/删除区块等官方操作层用 bbox）。 */
    private void selectionChanged(int[][] points) {
        if (points == null) {
            state.selPoints = null;
            state.x1 = state.z1 = state.x2 = state.z2 = null;
            refreshSelection();
            return;
        }
        state.selPoints = points;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE,
                z1 = Integer.MIN_VALUE;
        for (int[] p : points) {
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            z0 = Math.min(z0, p[1]);
            z1 = Math.max(z1, p[1]);
        }
        state.x1 = x0;
        state.z1 = z0;
        state.x2 = x1;
        state.z2 = z1;
        refreshSelection();
    }

    /** v0.4.214：✓应用 → 二级菜单气泡（选区操作合集）。 */
    private void showSelMenu() {
        LinearLayout menu = WorldUi.column(this, 0);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        menu.setBackground(WorldUi.background(this, WorldUi.color(this, R.color.surface), 16));
        String[] names = {
            getString(R.string.world_tool_biomes),
            getString(R.string.world_sm_export),
            getString(R.string.world_sm_save),
            getString(R.string.world_sm_place),
            getString(R.string.world_sm_import),
            getString(R.string.world_tool_sel_image),
            getString(R.string.world_tool_torch),
            getString(R.string.world_tool_delete_chunks),
            getString(R.string.world_tool_voxel3d)
        };
        int[] icons = {
            R.drawable.ic_world,
            R.drawable.ic_export,
            R.drawable.ic_structure,
            R.drawable.ic_structure,
            R.drawable.ic_structure,
            R.drawable.ic_photo,
            R.drawable.ic_world_light,
            R.drawable.ic_delete,
            R.drawable.ic_minecraft_cube
        };
        Runnable[] acts = {
            this::editBiomePolygon,
            this::selExport,
            this::selSave,
            this::selPlace,
            this::selImport,
            this::selectionImage,
            this::torchShades,
            this::deleteChunks,
            this::voxel3d
        };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(6), dp(12), dp(6));
            row.setBackground(WorldUi.ripple(this, 0, 10));
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(WorldUi.accent(this)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView label = WorldUi.text(this, names[i], 13, false);
            label.setPadding(dp(12), 0, 0, 0);
            row.addView(label);
            // v0.4.214：点击菜单项不关闭气泡——操作取消/完成后仍可
            // 继续点其他项（连续操作），点气泡外区域关闭
            row.setOnClickListener(v -> acts[index].run());
            menu.addView(row, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        PopupWindow popup = new PopupWindow(menu, dp(300), -2, true);
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(10));
        // v0.4.214：气泡动态定位（文件管理器删除气泡同款）——显示在
        // 最后收手位置右侧；超出屏幕边缘则贴边缘
        menu.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = menu.getMeasuredWidth(), h = menu.getMeasuredHeight();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = (int) map.lastUpX() + dp(16);
        if (x + w > dm.widthPixels - dp(8)) x = dm.widthPixels - w - dp(8);
        int y = (int) map.lastUpY() - h / 2;
        y = Math.max(dp(8), Math.min(y, dm.heightPixels - h - dp(8)));
        popup.showAtLocation(map, Gravity.NO_GRAVITY, x, y);
        selPopup = popup;
        popup.setOnDismissListener(() -> selPopup = null);
    }

    /** v0.4.214：多边形选区精确生物群系（覆盖判定逐块）。
     *  UI=可读列表（语言切换同款）：色块 + 显示名 + 代码名，点行应用。 */
    private void editBiomePolygon() {
        int[][] points = state.selPoints;
        if (points == null || points.length < 4) {
            error(getString(R.string.world_e_selection_corners));
            return;
        }
        java.util.List<LbblPalette.BiomeEntry> entries =
                LbblPalette.biomeEntries();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));
        for (LbblPalette.BiomeEntry be : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(7), dp(14), dp(7));
            row.setBackground(WorldUi.ripple(this, 0, 10));
            // 群系色块
            View swatch = new View(this);
            swatch.setBackground(WorldUi.background(this, be.color, dp(4)));
            row.addView(swatch, new LinearLayout.LayoutParams(dp(22), dp(22)));
            // 显示名（中文优先）
            TextView name =
                    WorldUi.text(this, BiomeNames.displayName(be.code), 14, false);
            name.setPadding(dp(12), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            // 代码名
            TextView code = WorldUi.text(this, "minecraft:" + be.code, 11, true);
            row.addView(code);
            row.setOnClickListener(
                    v -> {
                        work(
                                getString(R.string.world_w_editing_biomes),
                                () -> {
                                    WorldOperations.Region region = selection();
                                    return new WorldOperations(state.session)
                                            .biomePolygon(
                                                    region,
                                                    points,
                                                    be.id,
                                                    this::operationProgress);
                                },
                                count -> {
                                    changedWorkspace();
                                    toast(count + " blocks updated");
                                });
                    });
            list.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_change_biome))
                        .setView(scroll)
                        .setNegativeButton(getString(R.string.world_close), null));
    }

    /** v0.4.214：选区导出为 mcstructure 文件（bbox=格式限制）。 */
    private void selExport() {
        try {
            WorldOperations.Region region = selection();
            work(
                    getString(R.string.world_w_extracting_structure),
                    () ->
                            new WorldOperations(state.session)
                                    .exportStructure(region, this::operationProgress),
                    bytes -> exportBytes(bytes, "selection.mcstructure"));
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    /** v0.4.214：选区保存到此世界（结构 id）。 */
    private void selSave() {
        try {
            WorldOperations.Region region = selection();
            fields(
                    getString(R.string.world_save_structure),
                    new String[] {getString(R.string.world_structure_id)},
                    new String[] {"mystructure:selection"},
                    v ->
                            work(
                                    getString(R.string.world_w_saving_selection),
                                    () -> {
                                        byte[] bytes =
                                                new WorldOperations(state.session)
                                                        .exportStructure(
                                                                region,
                                                                this::operationProgress);
                                        new WorldOperations(state.session)
                                                .saveStructure(v[0], bytes);
                                        return true;
                                    },
                                    result -> changedWorkspace()));
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    /** v0.4.214：放置已保存结构（跳结构 tab 选结构）——先关二级菜单
     *  气泡（用户反馈跳结构选项时气泡还悬浮着）；进入放置模式：点击
     *  结构条目即选中并跳回地图放置到选区中心。 */
    private void selPlace() {
        if (selPopup != null) {
            selPopup.dismiss();
            selPopup = null;
        }
        placeMode = true;
        state.tab = 2;
        tabs.getTabAt(2).select();
        closeEditor(false);
        loadRecords(true);
        toast(getString(R.string.world_record_place_hint));
    }

    /** v0.4.214：放置模式下点击结构条目——回地图放置到选区中心，
     *  选区小于结构时提示确认，放置后 Snackbar 可撤销。 */
    private void placeSelected(byte[] key) {
        placeMode = false;
        int[][] pts = state.selPoints;
        if (pts == null || pts.length < 4) {
            error(getString(R.string.world_e_selection_corners));
            return;
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE,
                z1 = Integer.MIN_VALUE;
        for (int[] p : pts) {
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            z0 = Math.min(z0, p[1]);
            z1 = Math.max(z1, p[1]);
        }
        final int fCx = (x0 + x1) / 2, fCz = (z0 + z1) / 2,
                fW = x1 - x0 + 1, fD = z1 - z0 + 1, fDim = state.settings.dimension;
        work(
                getString(R.string.world_w_placing_structure),
                () -> {
                    byte[] data = state.session.get(key);
                    if (data == null) throw new IOException("world_e_record_deleted");
                    // 结构尺寸（NBT size）
                    NbtDocument doc = NativeNbt.decode(data, 0, NativeNbt.LITTLE_ENDIAN);
                    NbtTag sizeTag = doc.root.getTag("size");
                    if (sizeTag == null || sizeTag.getList().size() != 3)
                        throw new IOException("world_e_bad_structure_dims");
                    int sw = sizeTag.getList().get(0).getInt(),
                            sy = sizeTag.getList().get(1).getInt(),
                            sd = sizeTag.getList().get(2).getInt();
                    int px = fCx - sw / 2, pz = fCz - sd / 2, py = state.y1;
                    // 放置前快照（撤销用）：受影响 chunk 的原始数据
                    Map<byte[], byte[]> before = new LinkedHashMap<>();
                    for (int cx = Math.floorDiv(px, 16);
                            cx <= Math.floorDiv(px + sw - 1, 16);
                            cx++)
                        for (int cz = Math.floorDiv(pz, 16);
                                cz <= Math.floorDiv(pz + sd - 1, 16);
                                cz++) {
                            WorldChunk chunk = WorldChunk.load(state.session, cx, cz, fDim);
                            for (WorldChunk.Section s : chunk.sections.values())
                                before.put(
                                        ChunkKey.of(cx, cz, fDim, 0x2f, s.y),
                                        WorldChunk.encodeSection(s));
                            if (chunk.data3d != null)
                                before.put(ChunkKey.of(cx, cz, fDim, 0x2b), chunk.data3d.clone());
                            if (chunk.data2d != null)
                                before.put(ChunkKey.of(cx, cz, fDim, 0x2d), chunk.data2d.clone());
                        }
                    new WorldOperations(state.session)
                            .pasteStructure(
                                    data, px, py, pz, fDim, this::operationProgress);
                    return new Object[] {before, fW < sw || fD < sd};
                },
                info -> {
                    @SuppressWarnings("unchecked")
                    Map<byte[], byte[]> before =
                            (Map<byte[], byte[]>) ((Object[]) info)[0];
                    boolean small = (Boolean) ((Object[]) info)[1];
                    changedWorkspace();
                    // v0.4.214：放置后立刻重渲染地图（tile 缓存失效刷新）
                    map.refresh();
                    showSelSnackbar(
                            getString(R.string.world_record_placed)
                                    + (small
                                            ? "（"
                                                    + getString(
                                                            R.string.world_record_place_small_hint)
                                                    + "）"
                                            : ""),
                            getString(R.string.world_record_undo),
                            () -> undoPlace(before));
                });
    }

    /** v0.4.214：主题化 Snackbar——surface 底 + on_surface 文字 +
     *  accent 操作钮（用户要求：撤销 UI 适配启动器，不用默认深色）。 */
    private void showSelSnackbar(String text, String actionLabel, Runnable action) {
        Snackbar bar = Snackbar.make(map, text, Snackbar.LENGTH_LONG);
        View sbView = bar.getView();
        sbView.setBackgroundTintList(
                ColorStateList.valueOf(WorldUi.color(this, R.color.surface)));
        TextView tv =
                sbView.findViewById(com.google.android.material.R.id.snackbar_text);
        if (tv != null) tv.setTextColor(WorldUi.color(this, R.color.on_surface));
        bar.setActionTextColor(WorldUi.accent(this));
        if (action != null && actionLabel != null)
            bar.setAction(actionLabel, v -> action.run());
        bar.show();
    }

    /** v0.4.214：撤销结构放置（stage 回滚到放置前快照，未保存时有效）。 */
    private void undoPlace(Map<byte[], byte[]> before) {
        work(
                getString(R.string.world_w_undoing),
                () -> {
                    state.session.stageBatch(before);
                    return true;
                },
                v -> {
                    map.refresh();
                    toast(getString(R.string.world_record_undone));
                });
    }

    /** v0.4.214：导入结构文件。 */
    private void selImport() {
        state.importMode = 1;
        importFile.launch(new String[] {"*/*"});
    }

    /** v0.4.214：选区 3D 体素视图——WebView + three.js（WebGL）方案，
     *  参考 schematic-manager 的 Web 渲染思路：Android 端提取表面方块
     *  +贴图 base64 → JS 端 InstancedMesh 渲染 + OrbitControls。 */
    private void voxel3d() {
        voxelLogReset(); // v0.4.214：打开即重置诊断日志（早于数据生成）
        int[][] points = state.selPoints;
        if (points == null || points.length < 4) {
            error(getString(R.string.world_e_selection_corners));
            return;
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE,
                z1 = Integer.MIN_VALUE;
        for (int[] p : points) {
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            z0 = Math.min(z0, p[1]);
            z1 = Math.max(z1, p[1]);
        }
        final int fCenterX = (x0 + x1) / 2, fCenterZ = (z0 + z1) / 2,
                fSize = Math.min(Math.max(x1 - x0 + 1, z1 - z0 + 1), 128),
                fDim = state.settings.dimension;
        final java.io.File dbDir = new java.io.File(worldPath, "db");
        work(
                getString(R.string.world_w_rendering_image),
                () -> {
                    org.levimc.launcher.core.content.worldmap.WorldMapRenderer.VoxelColumn[][]
                            data =
                                    org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                            .renderVoxelRegion(
                                                    dbDir, fCenterX, fCenterZ, fDim, fSize, 128);
                    if (data == null) throw new IOException("world_e_ungenerated");
                    return buildVoxelJson(data, fSize);
                },
                json -> showVoxelWebDialog((String) json, fCenterX, fCenterZ));
    }

    /** WebView 3D 弹窗（数据经 VoxelBridge 注入 JS）。 */
    private void showVoxelWebDialog(String json, int cx, int cz) {
        android.app.Dialog dialog =
                new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(WorldUi.color(this, R.color.background));
        TextView title =
                WorldUi.text(
                        this,
                        getString(R.string.world_tool_voxel3d) + "  ·  " + cx + ", " + cz,
                        14,
                        false);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(WorldUi.accent(this));
        title.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(title);
        android.webkit.WebView web = new android.webkit.WebView(this);
        android.webkit.WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF12141A);
        android.webkit.WebView.setWebContentsDebuggingEnabled(true); // 调试截图用
        web.addJavascriptInterface(new VoxelBridge(json), "VoxelBridge");
        // v0.4.214：JS console 转发到 logcat + 文件（平板 logcat 被
        // EasyTier poll 刷爆，关键日志必须落盘）
        web.setWebChromeClient(
                new android.webkit.WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                        voxelLog(
                                "JS[" + cm.messageLevel() + "] " + cm.message()
                                        + " @" + cm.sourceId() + ":" + cm.lineNumber());
                        return true;
                    }
                });
        web.setWebViewClient(
                new android.webkit.WebViewClient() {
                    @Override
                    public void onPageFinished(android.webkit.WebView view, String url) {
                        voxelLog("page finished: " + url);
                    }

                    @Override
                    public void onReceivedError(
                            android.webkit.WebView view,
                            android.webkit.WebResourceRequest req,
                            android.webkit.WebResourceError err) {
                        voxelLog(
                                "web error: " + err.getErrorCode() + " "
                                        + err.getDescription() + " "
                                        + (req.getUrl() == null ? "" : req.getUrl()));
                    }
                });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.setPadding(0, 0, 0, dp(16));
        String[] labels = {
            getString(R.string.world_voxel_export), getString(R.string.world_close)
        };
        android.view.View.OnClickListener[] clicks = {
            v2 -> web.evaluateJavascript("window.exportVoxelPng && window.exportVoxelPng()", null),
            v2 -> dialog.dismiss()
        };
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView btn = WorldUi.text(this, labels[i], 13, false);
            btn.setGravity(Gravity.CENTER);
            btn.setBackground(WorldUi.ripple(this, WorldUi.accent(this), 18));
            btn.setTextColor(WorldUi.color(this, R.color.on_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(10), 0, dp(10), 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(clicks[index]);
            btns.addView(btn);
        }
        root.addView(btns);
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> web.destroy());
        dialog.show();
        web.loadUrl("file:///android_asset/voxel3d/index.html");
    }

    /** v0.4.214：3D 视图诊断日志（logcat + files/voxel3d.log 落盘，
     *  512KB 截断重写——平板 logcat 被 EasyTier 刷爆的兜底）。 */
    private void voxelLog(String msg) {
        android.util.Log.i("LeviVoxel3d", msg);
        try {
            java.io.File f = new java.io.File(getFilesDir(), "voxel3d.log");
            if (f.length() > 512 * 1024) f.delete();
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, true);
            os.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
            os.close();
        } catch (Exception ignored) {
        }
    }

    /** 每次打开 3D 视图时重置日志（避免新旧版本日志混杂误读）。 */
    private void voxelLogReset() {
        try {
            new java.io.File(getFilesDir(), "voxel3d.log").delete();
        } catch (Exception ignored) {
        }
    }

    /** JS 桥：JSON 数据注入 + 导出 PNG 接收。 */
    private final class VoxelBridge {
        private final String json;

        VoxelBridge(String json) {
            this.json = json;
        }

        @android.webkit.JavascriptInterface
        public String getData() {
            return json;
        }

        /** 调试：静默保存 WebGL 画面到 files/voxel_frame.png（不走 SAF）。 */
        @android.webkit.JavascriptInterface
        public void debugSave(String b64) {
            runOnUiThread(
                    () -> {
                        try {
                            byte[] png =
                                    android.util.Base64.decode(
                                            b64, android.util.Base64.DEFAULT);
                            java.io.FileOutputStream fos =
                                    new java.io.FileOutputStream(
                                            new java.io.File(
                                                    getFilesDir(), "voxel_frame.png"));
                            fos.write(png);
                            fos.close();
                        } catch (Exception ex) {
                            error(ex.getMessage());
                        }
                    });
        }

        @android.webkit.JavascriptInterface
        public void exported(String b64) {
            runOnUiThread(
                    () -> {
                        try {
                            byte[] png =
                                    android.util.Base64.decode(
                                            b64, android.util.Base64.DEFAULT);
                            state.pendingImage =
                                    BitmapFactory.decodeByteArray(png, 0, png.length);
                            state.pendingExport = null;
                            exportFile.launch("voxel-3d.png");
                        } catch (Exception ex) {
                            error(ex.getMessage());
                        }
                    });
        }
    }

    /** 表面方块提取 + 贴图 base64 打包成 JS 数据 JSON。 */
    private String buildVoxelJson(
            org.levimc.launcher.core.content.worldmap.WorldMapRenderer.VoxelColumn[][] data,
            int size)
            throws Exception {
        // ① 地表高度=「各列有名块底部 y 的众数」——地面是大面积平坦层
        // （大量列共享同一底部 y），树根也落在这个高度。剔除 y<=地面
        // 的方块=地面板与树根一层消失，包围盒=纯结构（居中+绕结构转）
        java.util.Map<Integer, Integer> bottomHist = new java.util.HashMap<>();
        for (int dz = 0; dz < size; dz++)
            for (int dx = 0; dx < size; dx++) {
                org.levimc.launcher.core.content.worldmap.WorldMapRenderer.VoxelColumn col =
                        data[dz][dx];
                int bottom = Integer.MAX_VALUE;
                for (int i = 0; i < col.ys.length; i++)
                    if (col.names[i] != null && col.ys[i] < bottom) bottom = col.ys[i];
                if (bottom != Integer.MAX_VALUE)
                    bottomHist.merge(bottom, 1, Integer::sum);
            }
        int groundY = Integer.MIN_VALUE, groundCount = 0;
        for (java.util.Map.Entry<Integer, Integer> e : bottomHist.entrySet())
            if (e.getValue() > groundCount) {
                groundCount = e.getValue();
                groundY = e.getKey();
            }
        voxelLog("voxel groundY=" + groundY + " columns=" + groundCount + "/" + size * size);
        // ② 表面提取：六邻居任一为空气/越界即暴露；null 名剔除（灰板
        // 真身）；y<=groundY 剔除（地面板）。v0.4.214 删「每列顶部 12
        // 层」限制——高结构（塔/长条）底部被误砍变短（用户实测）
        List<int[]> surf = new ArrayList<>();
        java.util.Map<String, Integer> nameIdx = new java.util.HashMap<>();
        List<String> names = new ArrayList<>();
        for (int dz = 0; dz < size; dz++)
            for (int dx = 0; dx < size; dx++) {
                org.levimc.launcher.core.content.worldmap.WorldMapRenderer.VoxelColumn col =
                        data[dz][dx];
                for (int i = 0; i < col.ys.length; i++) {
                    int y = col.ys[i];
                    if (col.names[i] == null) continue;
                    if (y <= groundY) continue;
                    boolean exposed =
                            !solidAt(data, size, dx, dz, y + 1)
                                    || !solidAt(data, size, dx, dz, y - 1)
                                    || !solidAt(data, size, dx - 1, dz, y)
                                    || !solidAt(data, size, dx + 1, dz, y)
                                    || !solidAt(data, size, dx, dz - 1, y)
                                    || !solidAt(data, size, dx, dz + 1, y);
                    if (!exposed) continue;
                    String name = col.names[i];
                    // v0.4.214：(方块名, 群系) 复合键——同方块不同群系
                    // 草色不同（colormap 群系着色）
                    String key = (name == null ? "null" : name) + "@" + col.biome;
                    Integer idx = nameIdx.get(key);
                    if (idx == null) {
                        idx = names.size();
                        nameIdx.put(key, idx);
                        names.add(key);
                    }
                    surf.add(new int[] {dx, y, dz, idx});
                }
            }
        // v0.4.214：过滤后无方块（选区纯地面/无结构）——友好提示而非
        // 空图集崩溃（"width and height must be > 0" 根因）
        if (surf.isEmpty())
            throw new IOException("world_e_no_structure");
        // 过滤后包围盒（居中诊断）
        int bx0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE,
                by0 = Integer.MAX_VALUE, by1 = Integer.MIN_VALUE,
                bz0 = Integer.MAX_VALUE, bz1 = Integer.MIN_VALUE;
        for (int[] b : surf) {
            bx0 = Math.min(bx0, b[0]);
            bx1 = Math.max(bx1, b[0]);
            by0 = Math.min(by0, b[1]);
            by1 = Math.max(by1, b[1]);
            bz0 = Math.min(bz0, b[2]);
            bz1 = Math.max(bz1, b[2]);
        }
        voxelLog(
                "voxel surf bbox=(" + bx0 + "," + by0 + "," + bz0 + ")-("
                        + bx1 + "," + by1 + "," + bz1 + ") size=" + size);
        // ② 贴图集：top/side 双面变体收集 → 合并图集（atlas，16px 网格）
        // ——BoxGeometry 六面单贴图是「草方块全草/木头全年轮」根因；
        // tint 按方块（樱花粉/树叶绿/草绿）；缺失→纯色/名称标签
        java.util.LinkedHashMap<String, Bitmap> atlasBits = new java.util.LinkedHashMap<>();
        String[] topKeys = new String[names.size()], sideKeys = new String[names.size()],
                bottomKeys = new String[names.size()];
        StringBuilder missNames = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String composite = names.get(i);
            String name = composite;
            int at = composite.lastIndexOf('@');
            if (at > 0) name = composite.substring(0, at);
            // v0.4.214：固定色表（照搬 schematic-manager tint.rs——
            // 原版默认群系色；樱花树叶不染色=贴图本身粉色）
            int tint = tintColorFor(name);
            // top 变体
            Bitmap top = org.levimc.launcher.util.BlockTextures.loadVariant(this, name, "top");
            String topKey = null;
            if (top != null) {
                if (tint >= 0) top = tintBitmap(top, tint);
                topKey = "t:" + org.levimc.launcher.util.BlockTextures.fileName(name) + ":top";
                atlasBits.putIfAbsent(topKey, top);
            }
            // side 变体（×0.72 面明暗——照搬 schematic-manager 预乘明暗）
            Bitmap side = org.levimc.launcher.util.BlockTextures.loadVariant(this, name, "side");
            String sideKey = null, bottomKey = null;
            if (side != null) {
                if (tint >= 0 && isTintedSide(name)) side = tintBitmap(side, tint);
                side = tintBitmap(side, 0xFFB8B8B8); // ×0.72 侧面明暗
                sideKey = "t:" + org.levimc.launcher.util.BlockTextures.fileName(name) + ":side";
                atlasBits.putIfAbsent(sideKey, side);
                // bottom 变体（×0.55 底面更暗）
                Bitmap bottom = tintBitmap(side, 0xFF8C8C8C); // 0.72×0.76≈0.55
                bottomKey = "t:" + org.levimc.launcher.util.BlockTextures.fileName(name) + ":bottom";
                atlasBits.putIfAbsent(bottomKey, bottom);
            }
            if (top == null && side == null) {
                // v0.4.214：无贴图方块全部名称标签（用户：纯色块不知道
                // 是什么）——名称贴图顶/侧同用
                Bitmap fb = nameLabelBitmap(name);
                topKey = "f:" + i;
                sideKey = topKey; // 顶/侧同用（null 数组 NPE 根因）
                bottomKey = topKey;
                atlasBits.put(topKey, fb);
                if (missNames.length() > 0) missNames.append(",");
                missNames.append(name);
            } else if (top == null) {
                topKey = sideKey; // 无 top 变体→用 side
            } else if (side == null) {
                sideKey = topKey;
                bottomKey = topKey;
            }
            topKeys[i] = topKey;
            sideKeys[i] = sideKey;
            bottomKeys[i] = bottomKey != null ? bottomKey : sideKey;
        }
        if (missNames.length() > 0)
            voxelLog("voxel missing textures: " + missNames);
        // atlas 拼图（16px 网格）
        int grid = (int) Math.ceil(Math.sqrt(atlasBits.size()));
        int atlasPx = grid * 16;
        Bitmap atlas = Bitmap.createBitmap(atlasPx, atlasPx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas ac = new android.graphics.Canvas(atlas);
        java.util.Map<String, int[]> atlasUv = new java.util.HashMap<>();
        int bi = 0;
        for (java.util.Map.Entry<String, Bitmap> e : atlasBits.entrySet()) {
            int u = (bi % grid) * 16, v = (bi / grid) * 16;
            ac.drawBitmap(e.getValue(), u, v, null);
            atlasUv.put(e.getKey(), new int[] {u, v});
            bi++;
        }
        // 每方块 UV 条目（t=top UV，s=side UV，b=bottom UV）
        org.json.JSONArray texArr = new org.json.JSONArray();
        for (int i = 0; i < names.size(); i++) {
            org.json.JSONObject t = new org.json.JSONObject();
            int[] tu = atlasUv.get(topKeys[i]);
            int[] su = atlasUv.get(sideKeys[i]);
            int[] bu = atlasUv.get(bottomKeys[i]);
            t.put("t", new org.json.JSONArray().put(tu[0]).put(tu[1]));
            t.put("s", new org.json.JSONArray().put(su[0]).put(su[1]));
            t.put("b", new org.json.JSONArray().put(bu[0]).put(bu[1]));
            texArr.put(t);
        }
        // ③ blocks（紧凑数组）——y 减去全局最小 y（从 0 起），场景
        // 居中于原点（相机 lookAt 0,0,0，绝对 y 会导致场景悬在上方
        // =「灰板」根因）
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] b : surf) {
            minY = Math.min(minY, b[1]);
            maxY = Math.max(maxY, b[1]);
        }
        org.json.JSONArray blockArr = new org.json.JSONArray();
        for (int[] b : surf) {
            org.json.JSONArray e = new org.json.JSONArray();
            e.put(b[0]);
            e.put(b[1] - minY);
            e.put(b[2]);
            e.put(b[3]);
            blockArr.put(e);
        }
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("size", size);
        root.put("ySize", maxY - minY + 1);
        root.put("atlasGrid", grid);
        root.put(
                "atlas",
                android.util.Base64.encodeToString(
                        pngBytes(atlas), android.util.Base64.NO_WRAP));
        root.put("textures", texArr);
        root.put("blocks", blockArr);
        voxelLog(
                "voxel json: blocks=" + surf.size() + " names=" + names.size()
                        + " atlas=" + atlasPx + "px cells=" + atlasBits.size());
        return root.toString();
    }

    private static boolean solidAt(
            org.levimc.launcher.core.content.worldmap.WorldMapRenderer.VoxelColumn[][] data,
            int size,
            int dx,
            int dz,
            int y) {
        if (dx < 0 || dx >= size || dz < 0 || dz >= size) return false;
        org.levimc.launcher.core.content.worldmap.WorldMapRenderer.VoxelColumn col = data[dz][dx];
        for (int yy : col.ys) if (yy == y) return true;
        return false;
    }

    /** v0.4.214：MC 本体 colormap 群系着色——基岩版草/树叶随群系变色
     *  的色板贴图（从 MC APK 提取：textures/colormap/grass.png、
     *  foliage.png），按群系温湿度采样（官方公式 x=(1-h)·255、
     *  y=(1-t)·255）。非灰模板类返回 -1 不 tint。 */
    private static final Map<Integer, float[]> BIOME_CLIMATE = new java.util.HashMap<>();

    static {
        // 温湿度表（MC 官方群系数据，基岩版 biome id 见 wiki Biome/ID）
        int[][] base = {
            {0, 50, 50}, {1, 80, 40}, {2, 200, 0}, {3, 20, 30}, {4, 70, 80},
            {5, 25, 80}, {6, 80, 50}, {7, 50, 50}, {8, 200, 0}, {9, 50, 50},
            {10, 0, 50}, {11, 0, 50}, {12, 0, 50}, {13, 0, 50}, {14, 90, 100},
            {15, 90, 100}, {16, 80, 40}, {17, 200, 0}, {18, 70, 80}, {19, 25, 80},
            {20, 20, 30}, {21, 95, 90}, {22, 95, 90}, {23, 95, 80}, {24, 50, 50},
            {25, 20, 30}, {26, 5, 30}, {27, 60, 60}, {28, 60, 60}, {29, 70, 80},
            {30, -50, 40}, {31, -50, 40}, {32, 30, 80}, {33, 30, 80}, {34, 20, 30},
            {35, 120, 0}, {36, 100, 0}, {37, 200, 0}, {38, 200, 0}, {39, 200, 0},
            {40, 80, 50}, {41, 80, 50}, {42, 50, 50}, {43, 50, 50}, {44, 50, 50},
            {45, 50, 50}, {46, 0, 50}, {47, 0, 50}, {48, 95, 90}, {49, 95, 90},
            {129, 80, 40}, {130, 200, 0}, {131, 20, 30}, {132, 70, 80}, {133, 25, 80},
            {134, 80, 50}, {140, 0, 50}, {149, 95, 90}, {151, 95, 80}, {155, 60, 60},
            {156, 60, 60}, {157, 70, 80}, {158, -50, 40}, {160, 30, 80}, {161, 30, 80},
            {162, 20, 30}, {163, 110, 0}, {164, 100, 0}, {165, 200, 0}, {166, 200, 0},
            {167, 200, 0}, {178, 200, 0}, {179, 200, 0}, {180, 200, 0}, {181, 200, 0},
            {182, -70, 90}, {183, -70, 90}, {184, -30, 90}, {185, -20, 80},
            {186, 30, 80}, {187, 50, 50}, {188, 20, 0}, {189, 100, 30},
            {190, 80, 40}, {191, 80, 90}, {192, 50, 80}, {193, 70, 80},
            {194, 100, 0}, {195, 70, 80},
        };
        for (int[] e : base) BIOME_CLIMATE.put(e[0], new float[] {e[1] / 100f, e[2] / 100f});
    }

    private final Map<String, Bitmap> colormapCache = new java.util.HashMap<>();

    private boolean colormapLogged;

    private Bitmap colormap(String name) {
        Bitmap b = colormapCache.get(name);
        if (b == null) {
            try {
                // ① 外部（用户可替换）——权限不足时 decodeFile 返回 null
                File f =
                        new File(
                                org.levimc.launcher.util.LauncherStorage.getTexturesDir(this),
                                "colormap/" + name + ".png");
                if (f.isFile()) b = BitmapFactory.decodeFile(f.getAbsolutePath());
                // ② assets 内置兜底（MC 本体官方色板，随 APK 分发）
                if (b == null) {
                    try (InputStream is = getAssets().open("colormap/" + name + ".png")) {
                        b = BitmapFactory.decodeStream(is);
                    } catch (Exception ignored) {
                    }
                }
                if (!colormapLogged) {
                    colormapLogged = true;
                    voxelLog(
                            "colormap " + name + ": external=" + f.isFile()
                                    + " bmp=" + (b != null ? b.getWidth() + "x" + b.getHeight() : "null"));
                }
            } catch (Exception e) {
                if (!colormapLogged) {
                    colormapLogged = true;
                    voxelLog("colormap " + name + " load failed: " + e);
                }
            }
            colormapCache.put(name, b);
        }
        return b;
    }

    /** 灰度模板类（草方块/树叶）→ colormap 群系采样色。 */
    /** v0.4.214：方块染色固定色表（照搬 schematic-manager tint.rs——
     *  原版默认群系色；樱花树叶**不染色**=MCJE 贴图本身就是粉色；
     *  云杉/白桦树叶固定色；水贴图自带蓝色不染。 */
    private static int tintColorFor(String name) {
        String f = org.levimc.launcher.util.BlockTextures.fileName(name);
        if (f == null) return -1;
        if (f.contains("cherry_leaves")) return -1; // 不染色
        if (f.contains("spruce_leaves")) return 0xFF619961;
        if (f.contains("birch_leaves")) return 0xFF80A755;
        if (f.contains("leaves") || f.contains("vine")) return 0xFF77AB2F;
        if (f.contains("lily_pad")) return 0xFF208030;
        if (f.contains("grass_block") || f.endsWith("grass_top") || f.contains("fern"))
            return 0xFF91BD59;
        return -1;
    }

    private int colormapTint(String name, int biomeId) {
        String f = org.levimc.launcher.util.BlockTextures.fileName(name);
        if (f == null) return -1;
        boolean isGrass = f.contains("grass_block") || f.endsWith("grass_top");
        boolean isLeaves = f.contains("leaves");
        if (!isGrass && !isLeaves) return -1;
        Bitmap cm = colormap(isLeaves ? "foliage" : "grass");
        if (cm == null) return -1;
        float[] c = BIOME_CLIMATE.get(biomeId);
        if (c == null) c = new float[] {0.5f, 0.5f};
        float t = Math.max(0f, Math.min(1f, c[0]));
        float h = Math.max(0f, Math.min(1f, c[1]));
        int x = (int) ((1f - h) * (cm.getWidth() - 1));
        int y = (int) ((1f - t) * (cm.getHeight() - 1));
        return cm.getPixel(x, y) | 0xFF000000;
    }

    /** side 变体是否也要 tint（草方块侧面 grass_block_side 同为灰度模板）。 */
    private static boolean isTintedSide(String name) {
        String f = org.levimc.launcher.util.BlockTextures.fileName(name);
        return f != null && f.contains("grass_block");
    }

    private static boolean isEntityName(String name) {
        if (name == null) return false;
        return name.contains("frame")
                || name.contains("painting")
                || name.contains("armor_stand")
                || name.contains("skull")
                || name.contains("banner")
                || name.contains("sign")
                || name.endsWith("_head");
    }

    private static String shortName(String name) {
        // v0.4.214：旧渲染器 names 数组可能含 null——兜底
        String s = name == null ? "?" : name;
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(colon + 1);
        return s.length() > 9 ? s.substring(0, 9) : s;
    }

    /** 贴图乘色（草顶/树叶灰白模板 × 固定绿）。 */
    private static Bitmap tintBitmap(Bitmap src, int tint) {
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
        int r = (tint >> 16) & 255, g = (tint >> 8) & 255, bl = tint & 255;
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            int nr = ((px[i] >> 16) & 255) * r / 255,
                    ng = ((px[i] >> 8) & 255) * g / 255,
                    nb = (px[i] & 255) * bl / 255;
            px[i] = (a << 24) | (nr << 16) | (ng << 8) | nb;
        }
        b.setPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return b;
    }

    /** 纯色方块贴图（无贴图方块回退）。 */
    private static Bitmap colorBitmap(int color) {
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        c.drawColor(color);
        return b;
    }

    /** 实体名称标签贴图（展示框/画/告示牌等——用户指定显示名称）。 */
    private static Bitmap nameLabelBitmap(String name) {
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFECE5D8);
        c.drawRect(0, 0, 16, 16, p);
        p.setColor(0xFF3A2F1D);
        p.setTextSize(6f);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText(shortName(name), 8, 11, p);
        return b;
    }

    private static byte[] pngBytes(Bitmap bmp) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /** v0.4.214：导出交互式 HTML 地图（LBBL 旧渲染器+bounds+烘焙缓存
     *  链路，放右下角加号——单文件 Leaflet 地图双击可开）。 */
    private void exportHtml() {
        work(
                getString(R.string.world_w_rendering_image),
                () -> {
                    java.io.File db = new java.io.File(worldPath, "db");
                    int dim = state.settings.dimension;
                    org.levimc.launcher.core.content.worldmap.WorldMapRenderer.WorldMap wm =
                            org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                    .buildBoundsOnly(db, dim);
                    if (wm == null || wm.width <= 0 || wm.height <= 0)
                        throw new IOException("world_e_ungenerated");
                    // 烘焙补全 chunk 颜色（旧缓存，缺啥补啥；缓存完整时秒回）
                    final java.util.concurrent.CountDownLatch done =
                            new java.util.concurrent.CountDownLatch(1);
                    Thread bt =
                            org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                                    .bakeWorldCache(db, dim, wm, null, done::countDown);
                    if (!done.await(10, java.util.concurrent.TimeUnit.MINUTES))
                        throw new IOException("world_e_timeout");
                    long seed = 0;
                    String version = "";
                    try {
                        NbtTag root = state.session.metadata().root;
                        NbtTag seedTag = root.getTag("RandomSeed");
                        if (seedTag != null) seed = seedTag.getLong();
                        NbtTag ver = root.getTag("lastOpenedWithVersion");
                        if (ver != null && ver.getIntArray().length >= 2) {
                            int[] v = ver.getIntArray();
                            version = v[0] + "." + v[1];
                        }
                    } catch (Exception ignored) {
                    }
                    java.io.File outDir =
                            org.levimc.launcher.util.LauncherStorage.getMapsExportDir(this);
                    String name = getIntent().getStringExtra(EXTRA_WORLD_NAME);
                    if (name == null || name.isEmpty())
                        name = new java.io.File(worldPath).getName();
                    String dimName = dim == 1 ? "nether" : dim == 2 ? "end" : "overworld";
                    int px = 0, pz = 0;
                    if (!state.players.isEmpty()) {
                        px = (int) state.players.get(0).x;
                        pz = (int) state.players.get(0).z;
                    }
                    return org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                            .exportWorldHtml(
                                    wm,
                                    outDir,
                                    "world-" + dimName + ".html",
                                    name,
                                    seed,
                                    version,
                                    px,
                                    pz,
                                    0,
                                    0,
                                    new ArrayList<>());
                },
                file -> {
                    toast(
                            getString(R.string.world_html_saved)
                                    + " "
                                    + ((java.io.File) file).getAbsolutePath());
                });
    }

    private WorldOperations.Region selection() throws IOException {
        if (state.x1 == null || state.z1 == null || state.x2 == null || state.z2 == null)
            throw new IOException(
                    getString(R.string.world_e_selection_corners));
        WorldOperations.Region region =
                new WorldOperations.Region(
                        state.x1,
                        state.y1,
                        state.z1,
                        state.x2,
                        state.y2,
                        state.z2,
                        state.settings.dimension);
        region.validateBounds();
        return region;
    }

    private void refreshSelection() {
        if (map != null) map.selection(state.x1, state.z1, state.x2, state.z2);
    }

    private void blockTools() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_block_ops))
                        .setItems(
                                new String[] {
                                    getString(R.string.world_fill_fg),
                                    getString(R.string.world_replace_fg),
                                    getString(R.string.world_fill_bg),
                                    getString(R.string.world_replace_bg)
                                },
                                (d, i) ->
                                        fields(
                                                getString(R.string.world_block_edit),
                                                i % 2 == 0
                                                        ? new String[] {
                                                            getString(R.string.world_replacement_name)
                                                        }
                                                        : new String[] {
                                                            getString(R.string.world_find_names),
                                                            getString(R.string.world_replacement_name)
                                                        },
                                                i % 2 == 0
                                                        ? new String[] {"minecraft:stone"}
                                                        : new String[] {"stone", "minecraft:glass"},
                                                v -> {
                                                    WorldOperations.Region region = selection();
                                                    String find = i % 2 == 0 ? null : v[0],
                                                            block = v[v.length - 1];
                                                    confirm(
                                                            getString(R.string.world_confirm_block_edit),
                                                            getString(
                                                                    R.string
                                                                            .world_blocks_in_area_fmt,
                                                                    region.volume()),
                                                            () ->
                                                                    work(
                                                                            getString(R.string.world_w_editing_blocks),
                                                                            () ->
                                                                                    new WorldOperations(
                                                                                                    state.session)
                                                                                            .blocks(
                                                                                                    region,
                                                                                                    find,
                                                                                                    WorldOperations
                                                                                                            .parseBlock(
                                                                                                                    block),
                                                                                                    i
                                                                                                            / 2,
                                                                                                    this
                                                                                                            ::operationProgress),
                                                                            count -> {
                                                                                changedWorkspace();
                                                                                toast(
                                                                                        getString(
                                                                                                R.string
                                                                                                        .world_blocks_changed_fmt,
                                                                                                count));
                                                                            }));
                                                })));
    }

    private void editBiome() {
        fields(
                getString(R.string.world_change_biome),
                new String[] {getString(R.string.world_biome_id)},
                new String[] {"1"},
                v -> {
                    WorldOperations.Region region = selection();
                    int biome = parse(v[0]);
                    work(
                            getString(R.string.world_w_editing_biomes),
                            () ->
                                    new WorldOperations(state.session)
                                            .biome(region, biome, this::operationProgress),
                            count -> {
                                changedWorkspace();
                                toast(count + " chunks updated");
                            });
                });
    }

    private void structureTools() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_tab_structures))
                        .setItems(
                                new String[] {
                                    getString(R.string.world_sm_export),
                                    getString(R.string.world_sm_save),
                                    getString(R.string.world_sm_place),
                                    getString(R.string.world_sm_import)
                                },
                                (d, i) -> {
                                    if (i == 2) {
                                        state.tab = 2;
                                        tabs.getTabAt(2).select();
                                        closeEditor(false);
                                        loadRecords(true);
                                        return;
                                    }
                                    if (i == 3) {
                                        state.importMode = 1;
                                        importFile.launch(new String[] {"*/*"});
                                        return;
                                    }
                                    try {
                                        WorldOperations.Region region = selection();
                                        if (i == 0)
                                            work(
                                                    getString(R.string.world_w_extracting_structure),
                                                    () ->
                                                            new WorldOperations(state.session)
                                                                    .exportStructure(
                                                                            region,
                                                                            this
                                                                                    ::operationProgress),
                                                    bytes ->
                                                            exportBytes(
                                                                    bytes,
                                                                    "selection.mcstructure"));
                                        else
                                            fields(
                                                    getString(R.string.world_save_structure),
                                                    new String[] {getString(R.string.world_structure_id)},
                                                    new String[] {"mystructure:selection"},
                                                    v ->
                                                            work(
                                                                    getString(R.string.world_w_saving_selection),
                                                                    () -> {
                                                                        byte[] bytes =
                                                                                new WorldOperations(
                                                                                                state.session)
                                                                                        .exportStructure(
                                                                                                region,
                                                                                                this
                                                                                                        ::operationProgress);
                                                                        new WorldOperations(
                                                                                        state.session)
                                                                                .saveStructure(
                                                                                        v[0],
                                                                                        bytes);
                                                                        return true;
                                                                    },
                                                                    result -> changedWorkspace()));
                                    } catch (IOException ex) {
                                        error(ex.getMessage());
                                    }
                                }));
    }

    private void pasteSaved(byte[] key) {
        fields(
                getString(R.string.world_place_title),
                new String[] {getString(R.string.world_origin_x), getString(R.string.world_origin_y), getString(R.string.world_origin_z)},
                new String[] {
                    "" + (int) state.centerX, "" + state.settings.y, "" + (int) state.centerZ
                },
                v -> {
                    int x = parse(v[0]), y = parse(v[1]), z = parse(v[2]);
                    confirm(
                            getString(R.string.world_confirm_place),
                            getString(R.string.world_confirm_place_msg),
                            () ->
                                    work(
                                            getString(R.string.world_w_placing_structure),
                                            () ->
                                                    new WorldOperations(state.session)
                                                            .pasteStructure(
                                                                    state.session.get(key),
                                                                    x,
                                                                    y,
                                                                    z,
                                                                    state.settings.dimension,
                                                                    this::operationProgress),
                                            count -> {
                                                changedWorkspace();
                                                toast(count + " block layers placed");
                                            }));
                });
    }

    private void torchShades() {
        try {
            WorldOperations.Region region = selection();
            confirm(
                    getString(R.string.world_confirm_shades),
                    getString(R.string.world_confirm_shades_msg),
                    () ->
                            work(
                                    getString(R.string.world_w_adding_shades),
                                    () ->
                                            new WorldOperations(state.session)
                                                    .shadeTorches(region, this::operationProgress),
                                    count -> {
                                        changedWorkspace();
                                        toast(count + " shades added");
                                    }));
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void deleteChunks() {
        try {
            WorldOperations.Region region = selection();
            int x1 = Math.floorDiv(region.x1, 16) * 16,
                    z1 = Math.floorDiv(region.z1, 16) * 16,
                    x2 = Math.floorDiv(region.x2, 16) * 16 + 15,
                    z2 = Math.floorDiv(region.z2, 16) * 16 + 15;
            confirm(
                    getString(R.string.world_confirm_delete_chunks),
                    getString(R.string.world_delete_chunks_msg_fmt, x1, z1, x2, z2),
                    () ->
                            work(
                                    getString(R.string.world_w_deleting_chunks),
                                    () ->
                                            new WorldOperations(state.session)
                                                    .deleteChunks(region, this::operationProgress),
                                    count -> {
                                        changedWorkspace();
                                        toast(count + " chunks marked for deletion");
                                    }));
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void selectionImage() {
        try {
            WorldOperations.Region region = selection();
            MapSettings settings = state.settings.copy();
            work(
                    getString(R.string.world_w_rendering_image),
                    () -> state.renderer.regionImage(region, settings),
                    image -> {
                        state.pendingImage = image;
                        state.pendingExport = null;
                        exportFile.launch("world-selection.png");
                    });
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void screenshot() {
        if (state.tab != 0) tabs.getTabAt(0).select();
        state.pendingImage = map.screenshot();
        state.pendingExport = null;
        exportFile.launch("world-map.png");
    }

    private void addBookmark(int x, int y, int z) {
        // v0.4.214：标点用我们的体系（BlueprintDb SQLite）
        fields(
                getString(R.string.world_bookmark_add),
                new String[] {getString(R.string.world_info_name)},
                new String[] {x + ", " + z},
                v -> {
                    org.levimc.launcher.core.content.BlueprintDb.Point p =
                            new org.levimc.launcher.core.content.BlueprintDb.Point();
                    p.worldId = blueprintWorldId();
                    p.name = v[0].isEmpty() ? x + ", " + z : v[0];
                    p.x = x;
                    p.y = y;
                    p.z = z;
                    p.dimension = blueprintDimName(state.settings.dimension);
                    p.category = org.levimc.launcher.core.content.BlueprintDb.CAT_CUSTOM;
                    p.color = "#C27EB8";
                    if (blueprintDb != null) {
                        blueprintDb.updatePoint(p);
                    }
                    refreshBlueprintPoints();
                });
    }

    private void bookmarks() {
        if (state.bookmarks.isEmpty()) {
            toast(getString(R.string.world_e_no_bookmarks));
            return;
        }
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.world_bookmarks_title))
                        .setItems(
                                state.bookmarks.stream()
                                        .map(m -> m.label + " · " + (int) m.x + ", " + (int) m.z)
                                        .toArray(String[]::new),
                                (d, i) -> {
                                    WorldRecords.Marker marker = state.bookmarks.get(i);
                                    WorldUi.show(
                                            this,
                                            new MaterialAlertDialogBuilder(this)
                                                    .setTitle(marker.label)
                                                    .setItems(
                                                            new String[] {
                                                                getString(R.string.world_goto), getString(R.string.world_delete_bookmark)
                                                            },
                                                            (dialog, n) -> {
                                                                if (n == 0) goMarker(marker);
                                                                else {
                                                                    // v0.4.214：删除写
                                                                    // BlueprintDb
                                                                    if (blueprintDb != null
                                                                            && i < filteredPoints
                                                                                    .size()) {
                                                                        blueprintDb.deletePoint(
                                                                                filteredPoints
                                                                                        .get(i)
                                                                                        .id);
                                                                    }
                                                                    refreshBlueprintPoints();
                                                                }
                                                            }));
                                }));
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(
                "world_workspace_"
                        + Integer.toHexString(state.session.world.getAbsolutePath().hashCode()),
                MODE_PRIVATE);
    }

    private void loadPreferences() {
        SharedPreferences p = preferences();
        state.centerX = Double.longBitsToDouble(p.getLong("x", 0));
        state.centerZ = Double.longBitsToDouble(p.getLong("z", 0));
        state.scale = p.getFloat("zoom", 5);
        state.settings.dimension = p.getInt("dimension", 0);
        state.settings.y = p.getInt("y", 64);
        state.settings.grid = p.getBoolean("grid", false);
        state.settings.layer =
                MapSettings.Layer.values()[Math.max(0, Math.min(7, p.getInt("layer", 0)))];
        // v0.4.214：下界打开默认洞穴图层（用户指令——直观展现地下结构熔岩湖）
        if (state.settings.dimension == 1
                && state.settings.layer == MapSettings.Layer.SATELLITE) {
            state.settings.layer = MapSettings.Layer.CAVE;
        }
        state.settings.lowerY = state.settings.dimension == 0 ? -64 : 0;
        state.settings.upperY =
                state.settings.dimension == 0 ? 319 : state.settings.dimension == 1 ? 127 : 255;
        state.settings.lowerY = p.getInt("lowerY", state.settings.lowerY);
        state.settings.upperY = p.getInt("upperY", state.settings.upperY);
        state.settings.coordinates = p.getBoolean("coordinates", true);
        state.settings.players = p.getBoolean("players", true);
        state.settings.entities = p.getBoolean("entities", false);
        state.settings.blockEntities = p.getBoolean("blockEntities", false);
        state.settings.leaves = p.getBoolean("leaves", true);
        state.settings.water = p.getBoolean("water", true);
        state.settings.shading = p.getBoolean("shading", true);
        state.settings.slice = p.getBoolean("slice", false);
        state.settings.slimeOverlay = p.getBoolean("slimeOverlay", false);
        state.settings.entityFilter = p.getString("entityFilter", "");
        state.settings.blockEntityFilter = p.getString("blockEntityFilter", "");
        // v0.4.214：prefs 历史数据可能存过 null（putString(key,null) 真存
        // null）——X 射线渲染 toLowerCase NPE 根因，读入时兜底
        state.settings.xray = p.getString("xray", state.settings.xray);
        if (state.settings.xray == null)
            state.settings.xray =
                    "diamond_ore,ancient_debris,emerald_ore,gold_ore,iron_ore,redstone_ore,lapis_ore,coal_ore,copper_ore";
        state.settings.brightness = p.getFloat("brightness", 1f);
    }

    private void savePreferences() {
        preferences()
                .edit()
                .putLong("x", Double.doubleToLongBits(state.centerX))
                .putLong("z", Double.doubleToLongBits(state.centerZ))
                .putFloat("zoom", state.scale)
                .putInt("dimension", state.settings.dimension)
                .putInt("y", state.settings.y)
                .putInt("layer", state.settings.layer.ordinal())
                .putBoolean("grid", state.settings.grid)
                .putInt("lowerY", state.settings.lowerY)
                .putInt("upperY", state.settings.upperY)
                .putBoolean("coordinates", state.settings.coordinates)
                .putBoolean("players", state.settings.players)
                .putBoolean("entities", state.settings.entities)
                .putBoolean("blockEntities", state.settings.blockEntities)
                .putBoolean("leaves", state.settings.leaves)
                .putBoolean("water", state.settings.water)
                .putBoolean("shading", state.settings.shading)
                .putBoolean("slice", state.settings.slice)
                .putBoolean("slimeOverlay", state.settings.slimeOverlay)
                .putString("entityFilter", state.settings.entityFilter)
                .putString("blockEntityFilter", state.settings.blockEntityFilter)
                .putString("xray", state.settings.xray)
                .putFloat("brightness", state.settings.brightness)
                .apply();
    }

    /** v0.4.214：标点从 BlueprintDb（我们的体系）加载 + 结构扫描。 */
    private void loadBlueprintPoints() {
        blueprintDb = new org.levimc.launcher.core.content.BlueprintDb(getApplicationContext());
        refreshBlueprintPoints();
        refreshStructures();
    }

    private interface Values {
        void accept(String[] values) throws Exception;
    }

    private void fields(String title, String[] labels, String[] initial, Values accept) {
        LinearLayout form = WorldUi.column(this, 16);
        List<EditText> inputs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            EditText input = labelInput(form, labels[i], initial[i]);
            inputs.add(input);
        }
        TextView error = WorldUi.text(this, "", 11, true);
        form.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog =
                WorldUi.show(
                        this,
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(title)
                                .setView(scroll)
                                .setPositiveButton(getString(R.string.world_apply), null)
                                .setNegativeButton(getString(R.string.world_cancel), null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                String[] values = new String[inputs.size()];
                                for (int i = 0; i < values.length; i++)
                                    values[i] = inputs.get(i).getText().toString();
                                accept.accept(values);
                                dialog.dismiss();
                            } catch (Exception ex) {
                                error.setText(
                                        ex.getMessage() == null
                                                ? getString(R.string.world_e_invalid)
                                                : ex.getMessage());
                            }
                        });
    }

    private EditText labelInput(LinearLayout parent, String label, String value) {
        TextView text = WorldUi.text(this, label, 11, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(6), 0, dp(4));
        parent.addView(text, tp);
        EditText input =
                WorldUi.input(this, label, value, label.contains("SNBT") || label.contains("Hex"));
        input.setMaxLines(label.contains("SNBT") ? 6 : 3);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void confirm(String title, String message, Runnable action) {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.world_continue), (d, w) -> action.run())
                        .setNegativeButton(getString(R.string.world_cancel), null));
    }

    private int parse(String value) throws IOException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IOException(getString(R.string.world_e_whole_number));
        }
    }

    private static int number(NbtTag tag, String key, int fallback) {
        NbtTag value = tag.getTag(key);
        return value == null ? fallback : value.getInt();
    }

    private int dp(float value) {
        return WorldUi.dp(this, value);
    }

    private byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if ((long) out.size() + n > NativeNbt.MAX_BYTES)
                throw new IOException(getString(R.string.world_e_file_too_large));
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String structureName(byte[] key) {
        String value = new String(key, StandardCharsets.UTF_8);
        value = value.startsWith("structuretemplate_") ? value.substring(18) : value;
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void clearExport() {
        state.pendingExport = null;
        if (state.pendingImage != null) {
            state.pendingImage.recycle();
            state.pendingImage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            if (!saving && task != null) task.cancel(true);
            return;
        }
        if (state.tab == 1 && (state.document != null || state.raw != null)) {
            if (tree != null && tree.up()) return;
            closeEditor(true);
        } else leave();
    }

    private void leave() {
        if (busy) return;
        if (state.draftDirty || state.session.pendingCount() > 0) {
            WorldUi.show(
                    this,
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.world_unsaved_edits))
                            .setMessage(getString(R.string.world_unsaved_edits_msg))
                            .setPositiveButton(
                                    getString(R.string.world_save),
                                    (d, w) -> {
                                        if (state.standalone) {
                                            exportDocument();
                                            return;
                                        }
                                        saving = true;
                                        work(
                                                getString(R.string.world_w_saving_edits),
                                                () -> {
                                                    stageDraft();
                                                    state.session.save();
                                                    return true;
                                                },
                                                v -> finish());
                                    })
                            .setNeutralButton(getString(R.string.world_discard), (d, w) -> finish())
                            .setNegativeButton(getString(R.string.world_cancel), null));
        } else finish();
    }

    private final class RecordAdapter extends RecyclerView.Adapter<RecordHolder> {
        public RecordHolder onCreateViewHolder(ViewGroup parent, int type) {
            // v0.4.214：结构卡片化（参考内容管理）——surface 圆角卡+
            // elevation 阴影 + 实例缩略图 + 标题 + 右侧笔图标菜单
            LinearLayout card = WorldUi.column(WorldEditorActivity.this, 0);
            card.setPadding(dp(12), dp(10), dp(6), dp(10));
            card.setBackground(
                    WorldUi.background(
                            WorldEditorActivity.this,
                            WorldUi.color(WorldEditorActivity.this, R.color.surface),
                            14));
            card.setElevation(dp(2));
            LinearLayout row = new LinearLayout(WorldEditorActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView thumb = new ImageView(WorldEditorActivity.this);
            thumb.setImageResource(R.drawable.ic_structure);
            thumb.setImageTintList(
                    ColorStateList.valueOf(
                            WorldUi.color(WorldEditorActivity.this, R.color.text_secondary)));
            row.addView(thumb, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout col = WorldUi.column(WorldEditorActivity.this, 4);
            col.setPadding(dp(10), 0, 0, 0);
            TextView title = WorldUi.text(WorldEditorActivity.this, "", 13, false);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(title);
            TextView sub = WorldUi.text(WorldEditorActivity.this, "", 11, true);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView menu =
                    WorldUi.iconButton(
                            WorldEditorActivity.this,
                            R.drawable.ic_edit,
                            getString(R.string.world_record_menu),
                            () -> {});
            menu.setImageTintList(
                    ColorStateList.valueOf(
                            WorldUi.color(WorldEditorActivity.this, R.color.text_secondary)));
            row.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(36)));
            card.addView(row);
            return new RecordHolder(card, title, sub, thumb, menu);
        }

        public void onBindViewHolder(RecordHolder holder, int position) {
            byte[] key = recordKeys.get(position);
            String label = new String(key, StandardCharsets.UTF_8);
            boolean structure = WorldRecords.matches(key, WorldRecords.Group.STRUCTURES);
            String name = label.startsWith("structuretemplate_") ? label.substring(18) : label;
            holder.title.setText(name);
            holder.menu.setVisibility(structure ? View.VISIBLE : View.GONE);
            // v0.4.214：菜单只在右侧笔图标触发（用户指定——条目本体
            // 点击仅放置模式下选中放置）
            holder.menu.setOnClickListener(v -> recordMenu(key, holder.menu));
            // 缩略图（结构实例俯视快照）——同步查缓存避免滚动复用闪烁
            holder.thumb.setImageResource(R.drawable.ic_structure);
            holder.thumb.setImageTintList(
                    ColorStateList.valueOf(
                            WorldUi.color(
                                    WorldEditorActivity.this, R.color.text_secondary)));
            holder.sub.setText("");
            if (structure) {
                Bitmap cached = structureThumbCache.get(name);
                if (cached != null) {
                    holder.thumb.setImageBitmap(cached);
                    holder.thumb.setImageTintList(null);
                } else {
                    executor.execute(
                            () -> {
                                try {
                                    byte[] data = state.session.get(key);
                                    if (data == null) return;
                                    Bitmap b = structureThumb(name, data);
                                    if (b == null) return;
                                    runOnUiThread(
                                            () -> {
                                                if (holder.getAdapterPosition() != position
                                                        && !holder.title
                                                                .getText()
                                                                .equals(name)) return;
                                                holder.thumb.setImageBitmap(b);
                                                holder.thumb.setImageTintList(null);
                                            });
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
            holder.itemView.setOnClickListener(
                    v -> {
                        // v0.4.214：放置模式（从选区二级菜单进入）——点击
                        // 结构=选中并跳回地图放置到选区中心；非放置模式
                        // 点击本体无操作（菜单在笔图标）
                        if (placeMode && structure) placeSelected(key);
                    });
            holder.itemView.setOnLongClickListener(null);
        }

        @Override
        public int getItemCount() {
            return recordKeys.size();
        }
    }

    private static final class RecordHolder extends RecyclerView.ViewHolder {
        final TextView title, sub;
        final ImageView thumb, menu;

        RecordHolder(View view, TextView title, TextView sub, ImageView thumb, ImageView menu) {
            super(view);
            this.title = title;
            this.sub = sub;
            this.thumb = thumb;
            this.menu = menu;
        }
    }

    /** v0.4.214：结构实例俯视缩略图（palette 顶层方块色，48px 采样）。 */
    private final Map<String, Bitmap> structureThumbCache =
            new LinkedHashMap<String, Bitmap>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 64;
                }
            };

    private Bitmap structureThumb(String cacheKey, byte[] data) {
        try {
            Bitmap cached = structureThumbCache.get(cacheKey);
            if (cached != null) return cached;
            NbtDocument doc = NativeNbt.decode(data, 0, NativeNbt.LITTLE_ENDIAN);
            NbtTag sizeTag = doc.root.getTag("size");
            NbtTag struct = doc.root.getTag("structure");
            if (sizeTag == null || struct == null || sizeTag.getList().size() != 3) return null;
            int sx = sizeTag.getList().get(0).getInt(),
                    sy = sizeTag.getList().get(1).getInt(),
                    sz = sizeTag.getList().get(2).getInt();
            NbtTag bi = struct.getTag("block_indices");
            NbtTag pal =
                    struct.getTag("palette") == null
                            ? null
                            : struct.getTag("palette").getTag("default") == null
                                    ? null
                                    : struct.getTag("palette")
                                            .getTag("default")
                                            .getTag("block_palette");
            if (bi == null || pal == null || bi.getList().isEmpty()) return null;
            List<NbtTag> fg = bi.getList().get(0).getList();
            List<NbtTag> palette = pal.getList();
            if (fg.size() < sx * sy * sz) return null;
            int target = 48;
            int step = Math.max(1, Math.max(sx, sz) / target);
            int tw = Math.max(1, (sx + step - 1) / step),
                    th = Math.max(1, (sz + step - 1) / step);
            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            int[] pix = new int[tw * th];
            for (int ty = 0; ty < th; ty++)
                for (int tx = 0; tx < tw; tx++) {
                    int x = Math.min(sx - 1, tx * step), z = Math.min(sz - 1, ty * step);
                    int color = 0;
                    for (int y = sy - 1; y >= 0; y--) {
                        int idx = fg.get(x * sy * sz + y * sz + z).getInt();
                        if (idx < 0 || idx >= palette.size()) continue;
                        color = MapPalette.block(WorldChunk.name(palette.get(idx)), 0);
                        break;
                    }
                    pix[ty * tw + tx] = color;
                }
            bmp.setPixels(pix, 0, tw, 0, 0, tw, th);
            structureThumbCache.put(cacheKey, bmp);
            return bmp;
        } catch (Exception ignored) {
            return null;
        }
    }
}
