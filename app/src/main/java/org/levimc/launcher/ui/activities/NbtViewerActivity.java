package org.levimc.launcher.ui.activities;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.CpuScheduler;
import org.levimc.launcher.core.content.BlueprintDb;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.core.content.leveldb.LevelDBEntry;
import org.levimc.launcher.core.content.leveldb.LevelDBReader;
import org.levimc.launcher.core.content.leveldb.NativeLevelDb;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
import org.levimc.launcher.core.content.nbt.NbtTag;
import org.levimc.launcher.core.content.worldmap.WorldMapRenderer;
import org.levimc.launcher.databinding.ActivityNbtViewerBinding;
import org.levimc.launcher.databinding.ItemNbtDbEntryBinding;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.views.WorldMapView;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只读存档数据查看器：
 * a) level.dat 的 NBT 树；b) db（LevelDB）条目列表，点击条目查看其 NBT 树。
 */
public class NbtViewerActivity extends BaseActivity {

    private static final String TAG = "NbtViewer";

    public static final String EXTRA_WORLD_DIR = "world_dir";
    public static final String EXTRA_WORLD_NAME = "world_name";

    private static final int TAB_LEVEL = 0;
    private static final int TAB_DB = 1;

    /** 大 NBT 限制：最大展开深度 */
    private static final int MAX_DEPTH = 6;
    /** 大 NBT 限制：每层条目上限 */
    private static final int MAX_CHILDREN = 200;
    /** 值超过该大小不尝试解析为 NBT（防止大块数据解析导致卡顿） */
    private static final int MAX_PARSE_BYTES = 8 * 1024 * 1024;
    /** 字符串显示长度上限 */
    private static final int MAX_STRING_DISPLAY = 512;

    private ActivityNbtViewerBinding binding;
    private ExecutorService executor;
    /** 视口按需渲染线程池（多 chunk 并行渲染；LevelDBReader 每次新建实例，线程安全）。
     *  6 线程：前几个并发任务绑大核，其余由系统调度到其余核——大小核全部用上
     *  （游戏式全核调度，类似终末地 Job System 的做法）。 */
    private final ExecutorService renderPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "chunk-render");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    /** 渲染任务序号：< 大核数的任务绑大核，其余自由调度（小核也参与）。 */
    private final java.util.concurrent.atomic.AtomicInteger renderTaskSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 渲染完成 chunk 的批量重绘缓冲：80ms 窗口合并，一次局部重绘处理多个 chunk。 */
    private final java.util.Set<Long> renderedChunkBuffer = new java.util.HashSet<>();
    private final android.os.Handler flushHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private boolean flushScheduled = false;
    private final Runnable flushRenderedChunks = () -> {
        flushScheduled = false;
        java.util.Set<Long> batch;
        synchronized (renderedChunkBuffer) {
            if (renderedChunkBuffer.isEmpty()) {
                return;
            }
            batch = new java.util.HashSet<>(renderedChunkBuffer);
            renderedChunkBuffer.clear();
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        binding.worldMapImage.onChunksRendered(batch);
    };

    /**
     * 启动后台任务：取消上一个未完成的加载再新建线程池。
     * 防止快速切换维度时多个全量 db 读取并行叠加导致 OOM（崩溃日志实测）。
     * 每次启动递增 generation——迟到（被取消）加载的回调必须丢弃，
     * 否则主世界加载会覆盖切换维度后的末地/下界地图。
     */
    private int loadGeneration = 0;

    private void startBackgroundTask() {
        if (executor != null) {
            executor.shutdownNow();
        }
        loadGeneration++;
        executor = Executors.newSingleThreadExecutor();
    }

    /** 当前加载是否仍是最新（回调前校验，防过期结果覆盖新地图）。 */
    private boolean isCurrentLoad(int generation) {
        return generation == loadGeneration;
    }

    private int currentTab = TAB_LEVEL;
    private int accentColor = 0;

    private NbtTag levelDatRoot;
    private WorldMapRenderer.WorldMap currentMap;
    private File currentWorldDir;
    private final List<LevelDBEntry> dbEntries = new ArrayList<>();
    private DbEntryAdapter dbAdapter;
    private boolean dbParseRunning = false;

    // ---- 标点 / 连线 / 蓝图码（PRD 缝合功能） ----
    private BlueprintDb blueprintDb;
    private String blueprintWorldId = "";
    private final List<BlueprintDb.Point> mapPoints = new ArrayList<>();
    private final List<BlueprintDb.Link> mapLinks = new ArrayList<>();
    private String mapDimension = "overworld";
    private boolean toolMenuOpen = false;
    /** 连线模式：null=关，否则为起点标点 id */
    private Long linkModeFrom = null;
    private boolean linkModeActive = false;
    /** 测距模式：起点标点 id */
    private Long rulerFrom = null;
    private boolean rulerModeActive = false;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNbtViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DynamicAnim.applyPressScaleRecursively(binding.getRoot());

        String worldDirPath = getIntent().getStringExtra(EXTRA_WORLD_DIR);
        if (worldDirPath == null) {
            Toast.makeText(this, R.string.invalid_world_path, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final File worldDir = new File(worldDirPath);
        if (!worldDir.isDirectory()) {
            Toast.makeText(this, R.string.invalid_world_path, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final String worldName = getIntent().getStringExtra(EXTRA_WORLD_NAME);

        PersonalizationManager pm = new PersonalizationManager(this);
        accentColor = pm.getAccentColor();
        if (accentColor != 0) {
            binding.nbtTitle.setTextColor(accentColor);
        }

        binding.nbtBack.setOnClickListener(v -> finish());
        DynamicAnim.applyPressScale(binding.nbtBack);

        binding.nbtEditLeveldatButton.setOnClickListener(v -> showEditLevelDatDialog());
        DynamicAnim.applyPressScale(binding.nbtEditLeveldatButton);

        binding.nbtTabLevel.setOnClickListener(v -> selectTab(TAB_LEVEL));
        binding.nbtTabDb.setOnClickListener(v -> selectTab(TAB_DB));
        DynamicAnim.applyPressScale(binding.nbtTabLevel);
        DynamicAnim.applyPressScale(binding.nbtTabDb);

        binding.nbtDbBack.setOnClickListener(v -> showDbEntryList());
        DynamicAnim.applyPressScale(binding.nbtDbBack);

        dbAdapter = new DbEntryAdapter(this::onDbEntryClick);
        binding.nbtDbRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.nbtDbRecycler.setAdapter(dbAdapter);

        WorldMapRenderer.init(getApplicationContext());
        CpuScheduler.init();
        binding.worldMapPlaceholder.setText(R.string.world_map_loading);

        blueprintDb = new BlueprintDb(this);
        blueprintWorldId = worldDir.getName();
        loadBlueprintData();
        setupMapTools(worldDir, worldName);
        setupPrdOverlays();

        // BTR 式视口按需渲染：滑动到未渲染区域时后台逐 chunk 渲染。
        // 中心优先（玩家/出生点附近的 chunk 先渲染）+ 多线程并行 +
        // 每批完成即重绘（渐进显示，不用等整个视口渲染完）。
        binding.worldMapImage.setOnChunksNeededListener(chunkKeys -> {
            if (chunkKeys.isEmpty() || currentWorldDir == null || currentMap == null) {
                return;
            }
            final File dbDir = new File(currentWorldDir, "db");
            final int dim = "nether".equals(mapDimension) ? 1 : "end".equals(mapDimension) ? 2 : 0;
            final WorldMapRenderer.WorldMap fMap = currentMap;
            // 中心优先排序：按 chunk 与目标中心（玩家/出生点）距离升序
            java.util.List<Long> keys = new java.util.ArrayList<>(chunkKeys);
            int centerCx = fMap.playerBlockX >= 0 ? Math.floorDiv(fMap.playerBlockX, 16)
                    : fMap.spawnBlockX >= 0 ? Math.floorDiv(fMap.spawnBlockX, 16)
                    : fMap.minBlockX / 16 + fMap.width / 32;
            int centerCz = fMap.playerBlockZ >= 0 ? Math.floorDiv(fMap.playerBlockZ, 16)
                    : fMap.spawnBlockZ >= 0 ? Math.floorDiv(fMap.spawnBlockZ, 16)
                    : fMap.minBlockZ / 16 + fMap.height / 32;
            final int cCx = centerCx;
            final int cCz = centerCz;
            keys.sort((a, b) -> {
                long dxa = ((a >> 32) - cCx);
                long dza = ((int) (long) a - cCz);
                long dxb = ((b >> 32) - cCx);
                long dzb = ((int) (long) b - cCz);
                return Long.compare(dxa * dxa + dza * dza, dxb * dxb + dzb * dzb);
            });
            final java.util.concurrent.atomic.AtomicInteger remaining =
                    new java.util.concurrent.atomic.AtomicInteger(keys.size());
            final java.util.Set<Long> inFlight = java.util.Collections.synchronizedSet(
                    new java.util.HashSet<>());
            for (Long key : keys) {
                // 已完成或正在渲染的跳过（onDraw 每帧重报缺失，防重复提交）
                if (fMap.chunkColors.containsKey(key) || !inFlight.add(key)) {
                    remaining.decrementAndGet();
                    continue;
                }
                renderPool.execute(() -> {
                    // 全核调度：前几个并发任务绑大核，其余自由调度到其它核
                    // （大小核全部参与渲染——发热不严重说明核心没跑满）
                    if (renderTaskSeq.getAndIncrement() < CpuScheduler.bigCoreCount) {
                        CpuScheduler.pinCurrentThreadToBigCores();
                    }
                    try {
                        int cx = (int) (key >> 32);
                        int cz = (int) (long) key;
                        int[] colors = WorldMapRenderer.renderChunkOnDemand(dbDir, cx, cz, dim);
                        // 未生成 chunk 也放 EMPTY 占位，防重复请求
                        fMap.chunkColors.put(key, colors != null ? colors : EMPTY_CHUNK_COLORS);
                    } catch (Throwable ignored) {
                    } finally {
                        inFlight.remove(key);
                        remaining.decrementAndGet();
                        // 批量节流重绘：80ms 窗口内的完成 chunk 合并成一次
                        // 局部重绘（同一 chunk 行的重叠行区间只采样一次）
                        runOnUiThread(() -> {
                            synchronized (renderedChunkBuffer) {
                                renderedChunkBuffer.add(key);
                                if (flushScheduled) {
                                    return;
                                }
                                flushScheduled = true;
                            }
                            flushHandler.postDelayed(flushRenderedChunks, 80);
                        });
                    }
                });
            }
        });

        selectTab(TAB_LEVEL);
        loadData(worldDir, worldName);
    }

    /** 空 chunk 占位（未生成区域，全透明；避免反复按需读取无数据 chunk）。 */
    private static final int[] EMPTY_CHUNK_COLORS = new int[256];

    /** PRD 悬浮层交互：顶栏展开、左栏抽屉 Tab、图层开关、数据面板、坐标 HUD。 */
    private void setupPrdOverlays() {
        // 顶栏：点击窄条展开/收起
        binding.topbarStrip.setOnClickListener(v -> {
            boolean expanded = binding.topbarFull.getVisibility() == View.VISIBLE;
            binding.topbarFull.setVisibility(expanded ? View.GONE : View.VISIBLE);
            binding.topbarChev.setText(expanded ? "▼" : "▲");
        });
        // 顶栏全局搜索 → 联动左栏标点 Tab 搜索
        binding.mapSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.pointSearchInput.setText(s);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        // 数据面板（level.dat 树 / db 条目）
        binding.btnDataPanel.setOnClickListener(v -> {
            binding.dataPanel.setVisibility(View.VISIBLE);
            binding.topbarFull.setVisibility(View.GONE);
        });
        binding.dataPanelClose.setOnClickListener(v ->
                binding.dataPanel.setVisibility(View.GONE));
        DynamicAnim.applyPressScale(binding.btnDataPanel);
        DynamicAnim.applyPressScale(binding.dataPanelClose);

        // 左栏：图标条点击切换 Tab（展开抽屉）
        View.OnClickListener lbClick = v -> {
            binding.leftbarBody.setVisibility(View.VISIBLE);
            binding.tabInfo.setVisibility(v == binding.lbInfo ? View.VISIBLE : View.GONE);
            binding.tabLayers.setVisibility(v == binding.lbLayers ? View.VISIBLE : View.GONE);
            binding.tabPoints.setVisibility(v == binding.lbPoints ? View.VISIBLE : View.GONE);
            binding.lbInfo.setTextColor(ContextCompat.getColor(this,
                    v == binding.lbInfo ? R.color.primary : R.color.text_secondary));
            binding.lbLayers.setTextColor(ContextCompat.getColor(this,
                    v == binding.lbLayers ? R.color.primary : R.color.text_secondary));
            binding.lbPoints.setTextColor(ContextCompat.getColor(this,
                    v == binding.lbPoints ? R.color.primary : R.color.text_secondary));
        };
        binding.lbInfo.setOnClickListener(lbClick);
        binding.lbLayers.setOnClickListener(lbClick);
        binding.lbPoints.setOnClickListener(lbClick);

        // 图层控制（复选框状态与视图双向同步，初始状态也要下发）
        binding.layerGrid.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowGrid(checked));
        binding.worldMapImage.setShowGrid(binding.layerGrid.isChecked());
        binding.layerBiome.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowBiomeLayer(checked));
        binding.worldMapImage.setShowBiomeLayer(binding.layerBiome.isChecked());
        binding.layerEntity.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowEntities(checked));
        binding.worldMapImage.setShowEntities(binding.layerEntity.isChecked());
        binding.layerStructure.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowStructures(checked));
        binding.worldMapImage.setShowStructures(binding.layerStructure.isChecked());
        binding.layerSlime.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowSlimeChunks(checked));
        binding.worldMapImage.setShowSlimeChunks(binding.layerSlime.isChecked());
        // 内存优化（BTR 式离屏卸载）：滑到哪渲染到哪，视口外的 chunk 直接回收
        binding.layerMemory.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setMemoryOptimized(checked));
        binding.worldMapImage.setMemoryOptimized(binding.layerMemory.isChecked());

        // 左栏标点搜索 → 列表过滤
        binding.pointSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderLeftPointList(s.toString().trim());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    /** 左栏 Tab3 标点列表渲染（搜索过滤 + 点击跳转视角）。 */
    private void renderLeftPointList(String query) {
        binding.pointListContainer.removeAllViews();
        String q = query != null ? query.toLowerCase().trim() : "";
        boolean any = false;
        for (BlueprintDb.Point p : mapPoints) {
            if (!p.dimension.equals(mapDimension)) {
                continue;
            }
            if (!q.isEmpty()
                    && !p.name.toLowerCase().contains(q)
                    && !(p.x + "," + p.z).contains(q)) {
                continue;
            }
            any = true;
            TextView item = new TextView(this);
            item.setText("● " + p.name + "  (" + p.x + "," + p.z + ")");
            item.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
            item.setTextSize(12);
            item.setPadding(0, (int) (7 * getResources().getDisplayMetrics().density),
                    0, (int) (7 * getResources().getDisplayMetrics().density));
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                if (!p.dimension.equals(mapDimension)) {
                    mapDimension = p.dimension;
                    loadMapForDimension(p.dimension);
                }
                binding.worldMapImage.animateTo(p.x, p.z);
            });
            binding.pointListContainer.addView(item);
        }
        if (!any) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_points);
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            empty.setTextSize(12);
            binding.pointListContainer.addView(empty);
        }
    }

    // ---------------------------------------------------------------- 标点 / 连线 / 蓝图码

    /** 初始化地图工具（FAB 菜单、维度切换、地图交互回调）。 */
    private void setupMapTools(File worldDir, String worldName) {
        // FAB 展开/收起
        binding.mapFab.setOnClickListener(v -> {
            toolMenuOpen = !toolMenuOpen;
            binding.mapToolMenu.setVisibility(toolMenuOpen ? View.VISIBLE : View.GONE);
            if (toolMenuOpen) {
                DynamicAnim.applyPressScale(binding.mapFab);
            }
        });
        DynamicAnim.applyPressScale(binding.mapFab);

        // 添加标点：提示长按地图
        binding.toolAddPoint.setOnClickListener(v -> {
            closeToolMenu();
            Toast.makeText(this, R.string.world_map_hint, Toast.LENGTH_SHORT).show();
        });
        // 连线模式
        binding.toolLinkMode.setOnClickListener(v -> {
            closeToolMenu();
            if (mapPoints.size() < 2) {
                Toast.makeText(this, R.string.no_points, Toast.LENGTH_SHORT).show();
                return;
            }
            linkModeActive = true;
            linkModeFrom = null;
            rulerModeActive = false;
            rulerFrom = null;
            Toast.makeText(this, R.string.link_pick_first, Toast.LENGTH_SHORT).show();
        });
        // 测距
        binding.toolRuler.setOnClickListener(v -> {
            closeToolMenu();
            if (mapPoints.size() < 2) {
                Toast.makeText(this, R.string.no_points, Toast.LENGTH_SHORT).show();
                return;
            }
            rulerModeActive = true;
            rulerFrom = null;
            linkModeActive = false;
            linkModeFrom = null;
            Toast.makeText(this, R.string.ruler_pick_first, Toast.LENGTH_SHORT).show();
        });
        // 标点列表
        binding.toolPointList.setOnClickListener(v -> {
            closeToolMenu();
            binding.leftbarBody.setVisibility(View.VISIBLE);
            binding.tabInfo.setVisibility(View.GONE);
            binding.tabLayers.setVisibility(View.GONE);
            binding.tabPoints.setVisibility(View.VISIBLE);
        });
        // 蓝图码
        binding.toolBlueprint.setOnClickListener(v -> {
            closeToolMenu();
            showBlueprintDialog();
        });

        // 维度切换
        setupDimensionSwitch();

        // 坐标 HUD：缩放/平移时更新中心坐标
        binding.worldMapImage.setOnViewChangedListener((cx, cz) ->
                binding.mapHud.setText("X: " + cx + "  Z: " + cz
                        + "  ·  " + dimName(mapDimension)));

        // 地图交互：长按添加标点、点击标点弹详情、单击空地显示坐标
        binding.worldMapImage.setOnMapInteractListener(new WorldMapView.OnMapInteractListener() {
            @Override
            public void onLongPress(int blockX, int blockZ) {
                showPointEditor(null, blockX, blockZ);
            }

            @Override
            public void onPointClick(BlueprintDb.Point point) {
                // 连线 / 测距模式优先
                if (linkModeActive) {
                    handleLinkPick(point);
                    return;
                }
                if (rulerModeActive) {
                    handleRulerPick(point);
                    return;
                }
                showPointDetail(point);
            }

            @Override
            public void onMapTap(int blockX, int blockZ) {
                // 单击显示该处坐标（十字标记在地图上，HUD 同步显示）
                binding.mapHud.setText("X: " + blockX + "  Z: " + blockZ
                        + "  ·  " + dimName(mapDimension));
            }
        });
    }

    private void closeToolMenu() {
        toolMenuOpen = false;
        binding.mapToolMenu.setVisibility(View.GONE);
    }

    /** 维度切换：高亮当前维度、刷新地图数据与标点渲染。 */
    private void setupDimensionSwitch() {
        View.OnClickListener dimClick = v -> {
            String dim;
            if (v == binding.worldDimOverworld) dim = "overworld";
            else if (v == binding.worldDimNether) dim = "nether";
            else dim = "end";
            mapDimension = dim;
            int active = getResources().getColor(R.color.primary, getTheme());
            int inactive = getResources().getColor(R.color.text_secondary, getTheme());
            binding.worldDimOverworld.setTextColor("overworld".equals(dim) ? active : inactive);
            binding.worldDimNether.setTextColor("nether".equals(dim) ? active : inactive);
            binding.worldDimEnd.setTextColor("end".equals(dim) ? active : inactive);
            binding.worldMapImage.setDimension(dim);
            loadMapForDimension(dim);
        };
        binding.worldDimOverworld.setOnClickListener(dimClick);
        binding.worldDimNether.setOnClickListener(dimClick);
        binding.worldDimEnd.setOnClickListener(dimClick);
        DynamicAnim.applyPressScale(binding.worldDimOverworld);
        DynamicAnim.applyPressScale(binding.worldDimNether);
        DynamicAnim.applyPressScale(binding.worldDimEnd);
    }

    /** 切换维度后重新渲染地图（下界/末地无数据时提示）+ 解析实体/结构图层数据。 */
    /** db 目录总大小（字节）——大世界（>20MB）走流式渲染路径。 */
    private static long dbSizeBytes(File dbDir) {
        long total = 0;
        File[] files = dbDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    total += f.length();
                }
            }
        }
        return total;
    }

    private void loadMapForDimension(String dim) {
        loadMapForDimension(dim, false);
    }

    /** keepView=true 时重载后保留当前视角（y 轴偏移等原地刷新场景）。 */
    private void loadMapForDimension(String dim, boolean keepView) {
        binding.nbtLoading.setVisibility(View.VISIBLE);
        // 维度隔绝：切换时立刻清掉旧维度地图与图层——否则新图渲染完成前
        // 旧图一直显示（"切下界先看到主世界，过一会才跳过去"的根因）
        currentMap = null;
        binding.worldMapImage.setWorldMap(null);
        binding.worldMapImage.setEntityData(new ArrayList<>());
        binding.worldMapImage.setStructureMarkers(new ArrayList<>());
        binding.worldMapPlaceholder.setVisibility(View.VISIBLE);
        startBackgroundTask();
        final int gen = loadGeneration;
        final boolean fKeepView = keepView;
        executor.execute(() -> {
            File dbDir = new File(currentWorldDir, "db");
            WorldMapRenderer.WorldMap worldMap = null;
            List<WorldMapRenderer.EntityPos> entities = null;
            List<WorldMapRenderer.StructureMarker> structures = null;
            if (dbDir.isDirectory()) {
                int dimId = "nether".equals(dim) ? 1 : "end".equals(dim) ? 2 : 0;
                long dbSize = dbSizeBytes(dbDir);
                if (dbSize > 20 * 1024 * 1024) {
                    // 大世界（155MB 级）：流式渲染，全量 readAllEntries 会 OOM
                    Log.i(TAG, "大世界流式渲染 dbSize=" + dbSize);
                    worldMap = WorldMapRenderer.loadChunkCache(dbDir, dimId);
                    if (worldMap == null) {
                        worldMap = WorldMapRenderer.buildSatelliteMapStreaming(dbDir, dimId);
                        if (worldMap != null) {
                            WorldMapRenderer.saveChunkCache(worldMap, dbDir, dimId);
                        }
                    }
                    entities = WorldMapRenderer.parseEntitiesStreaming(dbDir, dimId);
                    structures = WorldMapRenderer.parseStructureMarkersStreaming(dbDir, dimId);
                    if (structures == null) {
                        structures = new java.util.ArrayList<>();
                    }
                    // 流式渲染第三遍顺带检测的海底神殿/末地城标记
                    if (worldMap != null && worldMap.detectedStructures != null) {
                        structures.addAll(worldMap.detectedStructures);
                    }
                    // 降采样地图：实体/结构标记坐标 ÷blockScale（与归一化 chunk 对齐）
                    if (worldMap != null && worldMap.blockScale > 1) {
                        int sc = worldMap.blockScale;
                        java.util.List<WorldMapRenderer.EntityPos> ne =
                                new java.util.ArrayList<>();
                        for (WorldMapRenderer.EntityPos ep : entities) {
                            ne.add(new WorldMapRenderer.EntityPos(
                                    Math.floorDiv((int) ep.x, sc), ep.y,
                                    Math.floorDiv((int) ep.z, sc), ep.name));
                        }
                        entities = ne;
                        java.util.List<WorldMapRenderer.StructureMarker> ns =
                                new java.util.ArrayList<>();
                        for (WorldMapRenderer.StructureMarker sm : structures) {
                            ns.add(new WorldMapRenderer.StructureMarker(
                                    Math.floorDiv(sm.x, sc), Math.floorDiv(sm.z, sc), sm.type));
                        }
                        structures = ns;
                    }
                } else {
                    List<LevelDBEntry> entries = null;
                    try {
                        entries = NativeLevelDb.readAllEntries(dbDir);
                    } catch (Throwable ignored) {
                    }
                    if (entries == null) {
                        try {
                            LevelDBReader reader = new LevelDBReader(dbDir);
                            entries = reader.readAllEntries();
                            reader.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (entries != null) {
                        worldMap = WorldMapRenderer.buildSatelliteMap(entries, dimId);
                        // 原生库可能漏读 13/14B key（下界/末地），失败时回退纯 Java 重读
                        if (worldMap == null) {
                            try {
                                LevelDBReader reader = new LevelDBReader(dbDir);
                                List<LevelDBEntry> javaEntries = reader.readAllEntries();
                                reader.close();
                                if (javaEntries.size() > entries.size()) {
                                    entries = javaEntries;
                                    worldMap = WorldMapRenderer.buildSatelliteMap(entries, dimId);
                                    Log.i(TAG, "维度切换原生库漏读回退纯 Java: " + entries.size()
                                            + " 条目, 地图=" + (worldMap != null
                                            ? worldMap.width + "x" + worldMap.height : "仍失败"));
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        entities = WorldMapRenderer.parseEntities(entries, dimId);
                        structures = WorldMapRenderer.parseStructureMarkers(entries, dimId);
                    }
                }
            }
            final WorldMapRenderer.WorldMap fMap = worldMap;
            final List<WorldMapRenderer.EntityPos> fEntities = entities;
            final List<WorldMapRenderer.StructureMarker> fStructures = structures;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !isCurrentLoad(gen)) return;
                binding.nbtLoading.setVisibility(View.GONE);
                if (fMap != null) {
                    WorldMapRenderer.debugExport(fMap); // 调试导出 map_debug.png
                    binding.worldMapImage.setWorldMap(fMap, fKeepView);
                    binding.worldMapImage.setEntityData(fEntities);
                    binding.worldMapImage.setStructureMarkers(fStructures);
                    binding.worldMapPlaceholder.setVisibility(View.GONE);
                    refreshMapBlueprintData();
                } else {
                    // 该维度无数据：清空旧地图（避免上一维度地图残留误导）
                    binding.worldMapImage.setWorldMap(null);
                    binding.worldMapImage.setEntityData(null);
                    binding.worldMapImage.setStructureMarkers(null);
                    binding.worldMapPlaceholder.setText(R.string.world_map_unavailable);
                    binding.worldMapPlaceholder.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /** 从数据库读取标点/连线并同步到地图视图。 */
    private void loadBlueprintData() {
        mapPoints.clear();
        mapLinks.clear();
        mapPoints.addAll(blueprintDb.getPoints(blueprintWorldId));
        mapLinks.addAll(blueprintDb.getLinks(blueprintWorldId));
        refreshMapBlueprintData();
        renderLeftPointList(null);
    }

    /**
     * 数据面板权限卡 + 结构卡（HTML 原型 renderDataPanelExtras 落地）：
     * 成员列表 = 本地玩家（~local_player DisplayName）+ 其它 player_* XUID，
     * 头像 = 名字 hash 8×8 像素块；结构列表 = 结构检测标记（类型 + 坐标）。
     */
    private void refreshDataPanelExtras(List<WorldMapRenderer.StructureMarker> structures,
                                        List<LevelDBEntry> entries) {
        if (binding.dpPermsList == null) {
            return; // 旧布局无卡片
        }
        // 添加权限（HTML 原型 perm-add 占位：正式版写入 level.dat 多人权限）
        binding.dpPermAdd.setOnClickListener(v ->
                Toast.makeText(this, R.string.dp_perm_add_toast, Toast.LENGTH_SHORT).show());
        binding.dpPermsList.removeAllViews();
        binding.dpStructsList.removeAllViews();
        float d = getResources().getDisplayMetrics().density;

        // 成员：本地玩家 + player_* XUID（Bedrock 权限成员无本地完整存储，
        // level.dat 仅全局 permissionsLevel；按可用数据显示）
        java.util.List<String[]> members = new java.util.ArrayList<>();
        if (entries != null) {
            for (LevelDBEntry e : entries) {
                byte[] rawKey = e.getKey().getRawKey();
                if (rawKey == null || rawKey.length < 8) {
                    continue;
                }
                String keyStr;
                boolean printable = true;
                for (byte b : rawKey) {
                    if (b < 32 || b > 126) {
                        printable = false;
                        break;
                    }
                }
                if (!printable) {
                    continue;
                }
                keyStr = new String(rawKey, java.nio.charset.StandardCharsets.US_ASCII);
                if (keyStr.equals("~local_player")) {
                    String name = "本地玩家";
                    try {
                        NbtTag root = new BedrockNbtReader().readFromBytes(e.getValue());
                        if (root != null && root.getType() == NbtTag.TAG_COMPOUND) {
                            NbtTag dn = root.getTag("DisplayName");
                            if (dn != null) {
                                name = dn.getString();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    members.add(new String[]{name, "local", "owner"});
                } else if (keyStr.startsWith("player_") && keyStr.length() > 7) {
                    String xuid = keyStr.substring(7, Math.min(15, keyStr.length()));
                    members.add(new String[]{"XUID " + xuid + "…", keyStr.substring(7), "member"});
                }
            }
        }
        if (members.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.dp_member_empty);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(12f);
            binding.dpPermsList.addView(empty);
        } else {
            for (String[] m : members) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, (int) (4 * d), 0, 0);
                ImageView av = new ImageView(this);
                av.setImageBitmap(pixelAvatar(m[0], 34));
                int sz = (int) (34 * d);
                row.addView(av, new LinearLayout.LayoutParams(sz, sz));
                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView n1 = new TextView(this);
                n1.setText(m[0]);
                n1.setTextColor(getColor(R.color.on_surface));
                n1.setTextSize(12f);
                n1.setTypeface(null, android.graphics.Typeface.BOLD);
                info.addView(n1);
                TextView n2 = new TextView(this);
                n2.setText(m[1].equals("local") ? getString(R.string.dp_perm_local)
                        : "XUID " + m[1]);
                n2.setTextColor(getColor(R.color.text_secondary));
                n2.setTextSize(10f);
                info.addView(n2);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ilp.leftMargin = (int) (6 * d);
                row.addView(info, ilp);
                TextView badge = new TextView(this);
                badge.setText(m[2].equals("owner") ? getString(R.string.dp_perm_owner)
                        : getString(R.string.dp_perm_member));
                badge.setTextColor(getColor(R.color.primary));
                badge.setTextSize(10f);
                badge.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(badge);
                binding.dpPermsList.addView(row);
            }
        }

        // 结构卡
        if (structures == null || structures.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.dp_struct_empty);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(12f);
            binding.dpStructsList.addView(empty);
        } else {
            int shown = 0;
            for (WorldMapRenderer.StructureMarker m : structures) {
                if (shown++ >= 8) {
                    break;
                }
                TextView row = new TextView(this);
                row.setText(m.type + "：(" + m.x + ", " + m.z + ")");
                row.setTextColor(getColor(R.color.on_surface));
                row.setTextSize(11f);
                row.setPadding(0, (int) (3 * d), 0, 0);
                binding.dpStructsList.addView(row);
            }
        }
    }

    /** 名字 hash 生成 8×8 像素头像（HTML 原型 makeAvatar 同款思路）。 */
    private android.graphics.Bitmap pixelAvatar(String name, int px) {
        int seed = 0;
        for (char c : name.toCharArray()) {
            seed = seed * 31 + c;
        }
        int[] palette = {0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFB74D,
                0xFFBA68C8, 0xFF4DB6AC, 0xFFA1887F, 0xFF90A4AE};
        int base = palette[(seed >>> 4) & 7];
        int dark = android.graphics.Color.argb(255,
                (int) (((base >> 16) & 0xFF) * 0.7f),
                (int) (((base >> 8) & 0xFF) * 0.7f),
                (int) ((base & 0xFF) * 0.7f));
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(8, 8,
                android.graphics.Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                seed = seed * 1103515245 + 12345;
                boolean on = ((seed >>> 24) & 1) != 0;
                bmp.setPixel(x, y, on ? base : dark);
            }
        }
        return android.graphics.Bitmap.createScaledBitmap(bmp, px, px, false);
    }

    private void refreshMapBlueprintData() {
        binding.worldMapImage.setBlueprintData(mapPoints, mapLinks);
        binding.worldMapImage.setDimension(mapDimension);
    }

    private void loadData(File worldDir, String worldName) {
        currentWorldDir = worldDir;
        binding.nbtLoading.setVisibility(View.VISIBLE);
        startBackgroundTask();
        final int gen = loadGeneration;
        executor.execute(() -> {
            Log.i(TAG, "开始加载世界数据: world_dir=" + worldDir.getAbsolutePath()
                    + ", world_name=" + (worldName != null ? worldName : worldDir.getName()));
            WorldItem worldItem = null;
            NbtTag root = null;
            List<LevelDBEntry> entries = new ArrayList<>();
            boolean levelDatMissing = false;
            boolean dbMissing = false;

            try {
                worldItem = new WorldItem(worldName != null ? worldName : worldDir.getName(), worldDir);
            } catch (Exception ignored) {
            }

            File levelDat = new File(worldDir, "level.dat");
            if (levelDat.isFile()) {
                try {
                    root = new BedrockNbtReader().readFile(levelDat);
                } catch (Exception ignored) {
                }
            } else {
                levelDatMissing = true;
            }

            File dbDir = new File(worldDir, "db");
            Log.i(TAG, "db 目录: " + dbDir.getAbsolutePath()
                    + ", 存在=" + dbDir.isDirectory());
            WorldMapRenderer.WorldMap worldMap = null;
            List<WorldMapRenderer.EntityPos> entities = null;
            List<WorldMapRenderer.StructureMarker> structures = null;
            boolean largeWorld = false;
            if (dbDir.isDirectory() && dbSizeBytes(dbDir) > 20 * 1024 * 1024) {
                // 大世界（155MB 级）：BTR 式视口按需渲染——先快速进入
                // （磁盘缓存 / 只扫范围），滑动到新区域才渲染新 chunk
                largeWorld = true;
                Log.i(TAG, "大世界按需渲染 dbSize=" + dbSizeBytes(dbDir));
                worldMap = WorldMapRenderer.loadChunkCache(dbDir, 0);
                if (worldMap == null) {
                    worldMap = WorldMapRenderer.buildBoundsOnly(dbDir, 0);
                }
                if (worldMap != null) {
                    worldMap.chunkSourceDir = dbDir;
                    worldMap.chunkSourceDim = 0;
                }
                // 实体/结构/玩家位置全部延迟到首屏显示之后（各自要全量读一遍
                // 183MB db，同步执行会把首屏拖慢 20-30 秒——"更慢"的根因）
                entities = new ArrayList<>();
                structures = new ArrayList<>();
                final WorldMapRenderer.WorldMap fMap0 = worldMap;
                // 实体与结构解析并行（原来串行 4+5 秒 → 并行 ~5 秒，
                // 大核空闲时 IO 等待互相重叠）
                executor.execute(() -> {
                    final java.util.concurrent.atomic.AtomicInteger done =
                            new java.util.concurrent.atomic.AtomicInteger(0);
                    final List<WorldMapRenderer.EntityPos>[] ents =
                            new List[1];
                    final List<WorldMapRenderer.StructureMarker>[] strs =
                            new List[1];
                    final Runnable deliver = () -> {
                        if (done.incrementAndGet() != 2) {
                            return;
                        }
                        final List<WorldMapRenderer.EntityPos> fEnts =
                                ents[0] != null ? ents[0] : new ArrayList<>();
                        List<WorldMapRenderer.StructureMarker> fStrs =
                                strs[0] != null ? strs[0] : new ArrayList<>();
                        if (fMap0 != null && fMap0.detectedStructures != null) {
                            fStrs.addAll(fMap0.detectedStructures);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || !isCurrentLoad(gen)) {
                                return;
                            }
                            binding.worldMapImage.setEntityData(fEnts);
                            binding.worldMapImage.setStructureMarkers(fStrs);
                            refreshDataPanelExtras(fStrs, null);
                        });
                    };
                    // 延迟 6 秒：让首屏视口渲染先用满 4 个渲染线程，
                    // 之后实体/结构解析再并行抢线程（此时视口基本填充完）
                    flushHandler.postDelayed(() -> {
                        renderPool.execute(() -> {
                            CpuScheduler.pinCurrentThreadToBigCores();
                            ents[0] = WorldMapRenderer.parseEntitiesStreaming(dbDir, 0);
                            deliver.run();
                        });
                        renderPool.execute(() -> {
                            CpuScheduler.pinCurrentThreadToBigCores();
                            strs[0] = WorldMapRenderer.parseStructureMarkersStreaming(dbDir, 0);
                            deliver.run();
                        });
                    }, 6000);
                });
            } else if (dbDir.isDirectory()) {
                // 优先 BTR 同款原生库（自带全部 MCPE 压缩格式），失败回退纯 Java
                try {
                    entries = NativeLevelDb.readAllEntries(dbDir);
                } catch (Throwable ignored) {
                    entries = null;
                }
                if (entries == null) {
                    try {
                        LevelDBReader reader = new LevelDBReader(dbDir);
                        entries = reader.readAllEntries();
                        reader.close();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                dbMissing = true;
            }
            Log.i(TAG, "db 读取完成: 条目数=" + (entries != null ? entries.size() : -1));

            if (!largeWorld) {
                // 世界地图：BTR 卫星模式（方块颜色 + 坡度阴影），后台解码，缩放时按比例重采样
                try {
                    worldMap = WorldMapRenderer.buildSatelliteMap(entries);
                    Log.i(TAG, "卫星地图完成: " + (worldMap != null
                            ? worldMap.width + "x" + worldMap.height
                            : "失败(null)"));
                } catch (Exception e) {
                    Log.i(TAG, "卫星地图渲染异常", e);
                }

                // 原生库可能漏读 13/14B key（下界/末地），失败时回退纯 Java 重读
                if (worldMap == null && dbDir.isDirectory()) {
                    try {
                        LevelDBReader reader = new LevelDBReader(dbDir);
                        List<LevelDBEntry> javaEntries = reader.readAllEntries();
                        reader.close();
                        if (javaEntries.size() > entries.size()) {
                            entries = javaEntries;
                            worldMap = WorldMapRenderer.buildSatelliteMap(entries);
                            Log.i(TAG, "原生库漏读回退纯 Java: " + entries.size() + " 条目, 地图="
                                    + (worldMap != null ? worldMap.width + "x" + worldMap.height : "仍失败"));
                        }
                    } catch (Exception ignored) {
                    }
                }

                // 实体 / 结构图层数据（actorprefix 实体 + 方块实体结构检测）
                if (worldMap != null) {
                    entities = WorldMapRenderer.parseEntities(entries, 0);
                    structures = WorldMapRenderer.parseStructureMarkers(entries, 0);
                }
            }

            // 玩家位置（db 玩家数据的 Pos）与出生点（level.dat SpawnX/Z）
            if (worldMap != null) {
                for (LevelDBEntry entry : entries) {
                    String name = entry.getKey().getDisplayName();
                    byte[] rawKey = entry.getKey().getRawKey();
                    boolean isPlayerKey = false;
                    if (name != null && (name.contains("local_player") || name.startsWith("player"))) {
                        isPlayerKey = true;
                    } else if (rawKey != null && (rawKey.length == 9 || rawKey.length == 10)
                            && !entry.getKey().isChunkKey()) {
                        // 1.19+ actor 二进制 key：非 chunk 的 9/10 字节 key 按内容判定
                        isPlayerKey = true;
                    }
                    if (!isPlayerKey) {
                        continue;
                    }
                    try {
                        NbtTag playerRoot = new BedrockNbtReader().readFromBytes(entry.getValue());
                        if (playerRoot == null) {
                            continue;
                        }
                        // 优先 Pos（生存中玩家位置）；死亡存档无 Pos，回退 DeathPosition（死亡点）
                        NbtTag posTag = playerRoot.getTag("Pos");
                        if (posTag != null && posTag.getType() == NbtTag.TAG_LIST
                                && posTag.getList().size() >= 3) {
                            float px = posTag.getList().get(0).getFloat();
                            float pz = posTag.getList().get(2).getFloat();
                            // 合理世界范围（±3000 万方块）内才认定是玩家位置
                            if (Math.abs(px) < 3e7f && Math.abs(pz) < 3e7f) {
                                worldMap.playerBlockX = (int) Math.floor(px);
                                worldMap.playerBlockZ = (int) Math.floor(pz);
                                // 降采样地图：玩家标记坐标 ÷blockScale
                                if (worldMap.blockScale > 1) {
                                    worldMap.playerBlockX = Math.floorDiv(worldMap.playerBlockX, worldMap.blockScale);
                                    worldMap.playerBlockZ = Math.floorDiv(worldMap.playerBlockZ, worldMap.blockScale);
                                }
                                Log.i(TAG, "玩家位置: " + worldMap.playerBlockX + "," + worldMap.playerBlockZ);
                                break;
                            }
                        }
                        NbtTag deathX = playerRoot.getTag("DeathPositionX");
                        NbtTag deathZ = playerRoot.getTag("DeathPositionZ");
                        if (deathX != null && deathZ != null) {
                            worldMap.playerBlockX = deathX.getInt();
                            worldMap.playerBlockZ = deathZ.getInt();
                            Log.i(TAG, "玩家位置(死亡点): " + worldMap.playerBlockX + "," + worldMap.playerBlockZ);
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (root != null) {
                    NbtTag sx = root.getTag("SpawnX");
                    NbtTag sz = root.getTag("SpawnZ");
                    if (sx != null && sz != null) {
                        worldMap.spawnBlockX = sx.getInt();
                        worldMap.spawnBlockZ = sz.getInt();
                        Log.i(TAG, "出生点: " + worldMap.spawnBlockX + "," + worldMap.spawnBlockZ);
                    }
                }
            }

            final WorldItem fWorld = worldItem;
            final NbtTag fRoot = root;
            final List<LevelDBEntry> fEntries = entries;
            final boolean fLevelMissing = levelDatMissing;
            final boolean fDbMissing = dbMissing;
            final WorldMapRenderer.WorldMap fWorldMap = worldMap;
            final List<WorldMapRenderer.EntityPos> fEntities = entities;
            final List<WorldMapRenderer.StructureMarker> fStructures = structures;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !isCurrentLoad(gen)) return;
                onDataLoaded(fWorld, fRoot, fEntries, fLevelMissing, fDbMissing, fWorldMap);
                binding.worldMapImage.setEntityData(fEntities);
                binding.worldMapImage.setStructureMarkers(fStructures);
                refreshDataPanelExtras(fStructures, fEntries);
            });
        });
    }

    private void onDataLoaded(WorldItem worldItem, NbtTag root, List<LevelDBEntry> entries,
                              boolean levelDatMissing, boolean dbMissing,
                              WorldMapRenderer.WorldMap worldMap) {
        currentMap = worldMap;
        binding.nbtLoading.setVisibility(View.GONE);

        // 世界地图：占满全屏（PRD 布局），缩放/平移时按比例重采样方块颜色
        if (worldMap != null) {
            WorldMapRenderer.debugExport(worldMap); // 调试导出 map_debug.png
            binding.worldMapImage.setWorldMap(worldMap);
            binding.worldMapPlaceholder.setVisibility(View.GONE);
        } else {
            binding.worldMapPlaceholder.setText(R.string.world_map_unavailable);
        }
        binding.nbtLoading.setVisibility(View.GONE);

        // 左栏 Tab1 世界信息（PRD 左栏）
        if (worldItem != null) {
            binding.infoSeed.setText(getString(R.string.nbt_summary_seed, worldItem.getSeed()));
            binding.infoGamemode.setText(getString(R.string.nbt_summary_gamemode, worldItem.getGameMode()));
            binding.infoHardcore.setText(getString(R.string.nbt_summary_hardcore,
                    yesNo(worldItem.isHardcore())));
            binding.infoDead.setText(getString(R.string.nbt_summary_dead,
                    yesNo(worldItem.isPlayerDead())));
            StringBuilder playerInfo = new StringBuilder();
            playerInfo.append(getString(R.string.nbt_summary_health));
            playerInfo.append(worldItem.getPlayerHealth() >= 0f
                    ? String.format(Locale.getDefault(), "%.1f", worldItem.getPlayerHealth()) : "?");
            binding.infoPlayer.setText(playerInfo.toString());
        }

        // 数据面板内世界信息摘要卡
        if (worldItem != null) {
            binding.nbtSummaryCard.setVisibility(View.VISIBLE);
            binding.nbtSummaryName.setText(worldItem.getWorldName());
            StringBuilder info = new StringBuilder();
            info.append(getString(R.string.nbt_summary_seed, worldItem.getSeed())).append('\n');
            info.append(getString(R.string.nbt_summary_gamemode, worldItem.getGameMode())).append('\n');
            info.append(getString(R.string.nbt_summary_hardcore,
                    yesNo(worldItem.isHardcore()))).append('\n');
            info.append(getString(R.string.nbt_summary_dead,
                    yesNo(worldItem.isPlayerDead())));
            if (worldItem.getPlayerHealth() >= 0f) {
                info.append('\n').append(getString(R.string.nbt_summary_health,
                        worldItem.getPlayerHealth()));
            }
            binding.nbtSummaryInfo.setText(info.toString());
        }

        // a) level.dat 树
        levelDatRoot = root;
        binding.nbtLevelTree.removeAllViews();
        if (root == null) {
            binding.nbtLevelEmpty.setVisibility(View.VISIBLE);
            if (!levelDatMissing) {
                binding.nbtLevelEmpty.setText(R.string.nbt_no_data);
            }
        } else {
            binding.nbtLevelEmpty.setVisibility(View.GONE);
            addTreeRoot(binding.nbtLevelTree, root, getString(R.string.nbt_level_dat));
        }

        // b) db 条目
        dbEntries.clear();
        dbEntries.addAll(entries);
        dbAdapter.notifyDataSetChanged();
        binding.nbtTabDb.setText(getString(R.string.nbt_db_entries) + " (" + entries.size() + ")");
        if (entries.isEmpty()) {
            binding.nbtDbEmpty.setVisibility(View.VISIBLE);
            if (!dbMissing) {
                binding.nbtDbEmpty.setText(R.string.nbt_no_data);
            }
        } else {
            binding.nbtDbEmpty.setVisibility(View.GONE);
        }
    }

    private String yesNo(boolean value) {
        return getString(value ? R.string.nbt_yes : R.string.nbt_no);
    }

    // ---------------------------------------------------------------- level.dat 编辑

    /** 编辑 level.dat 常用字段（世界名/模式/难度/硬核/种子）。启动器卡片风格 UI。 */
    private void showEditLevelDatDialog() {
        if (levelDatRoot == null || currentWorldDir == null) {
            Toast.makeText(this, R.string.nbt_no_data, Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, NbtTag> root = levelDatRoot.getCompound();
        if (root.isEmpty()) {
            Toast.makeText(this, R.string.nbt_no_data, Toast.LENGTH_SHORT).show();
            return;
        }

        View panel = getLayoutInflater().inflate(R.layout.dialog_edit_leveldat, null);
        EditText nameEdit = panel.findViewById(R.id.edit_world_name);
        EditText seedEdit = panel.findViewById(R.id.edit_seed);
        TextView gamemodeValue = panel.findViewById(R.id.tv_gamemode_value);
        TextView difficultyValue = panel.findViewById(R.id.tv_difficulty_value);
        SwitchMaterial hardcoreSwitch = panel.findViewById(R.id.switch_hardcore);

        // 初始值
        String curName = "";
        NbtTag nameTag = root.get("LevelName");
        if (nameTag != null && nameTag.getType() == NbtTag.TAG_STRING) {
            curName = nameTag.getString();
        }
        nameEdit.setText(curName);

        NbtTag seedTag = root.get("RandomSeed");
        if (seedTag != null && seedTag.getType() == NbtTag.TAG_LONG) {
            seedEdit.setText(String.valueOf(seedTag.getLong()));
        }

        NbtTag hcTag = root.get("IsHardcore");
        hardcoreSwitch.setChecked(hcTag != null && hcTag.getByte() != 0);

        final int[] curGamemode = {0};
        NbtTag gmTag = root.get("GameType");
        if (gmTag != null && gmTag.getType() == NbtTag.TAG_INT) {
            curGamemode[0] = Math.max(0, Math.min(2, gmTag.getInt()));
        }
        gamemodeValue.setText(gamemodeName(curGamemode[0]));

        final int[] curDifficulty = {1};
        NbtTag diffTag = root.get("Difficulty");
        if (diffTag != null && diffTag.getType() == NbtTag.TAG_INT) {
            curDifficulty[0] = Math.max(0, Math.min(3, diffTag.getInt()));
        }
        difficultyValue.setText(difficultyName(curDifficulty[0]));

        // 游戏模式：点击弹出启动器列表选择
        String[] modes = {
                getString(R.string.nbt_gamemode_survival),
                getString(R.string.nbt_gamemode_creative),
                getString(R.string.nbt_gamemode_adventure)
        };
        panel.findViewById(R.id.row_gamemode).setOnClickListener(v -> {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.nbt_edit_gamemode))
                    .setItems(modes, (dialog, which) -> {
                        curGamemode[0] = which;
                        gamemodeValue.setText(gamemodeName(which));
                    })
                    .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                    .show();
        });

        // 难度：点击弹出启动器列表选择
        String[] difficulties = {
                getString(R.string.nbt_difficulty_peaceful),
                getString(R.string.nbt_difficulty_easy),
                getString(R.string.nbt_difficulty_normal),
                getString(R.string.nbt_difficulty_hard)
        };
        panel.findViewById(R.id.row_difficulty).setOnClickListener(v -> {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.nbt_edit_difficulty))
                    .setItems(difficulties, (dialog, which) -> {
                        curDifficulty[0] = which;
                        difficultyValue.setText(difficultyName(which));
                    })
                    .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                    .show();
        });

        // 使用启动器统一弹窗 UI
        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.nbt_edit_leveldat))
                .setCustomView(panel)
                .setPositiveButton(getString(R.string.nbt_edit_save), v -> {
                    String newName = nameEdit.getText().toString().trim();
                    String seedText = seedEdit.getText().toString().trim();
                    long newSeed;
                    try {
                        newSeed = Long.parseLong(seedText);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.nbt_edit_seed_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyLevelDatEdits(root, newName, curGamemode[0],
                            curDifficulty[0], hardcoreSwitch.isChecked(), newSeed);
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null);
        dialog.show();
    }

    private String difficultyName(int difficulty) {
        return switch (difficulty) {
            case 0 -> getString(R.string.nbt_difficulty_peaceful);
            case 1 -> getString(R.string.nbt_difficulty_easy);
            case 2 -> getString(R.string.nbt_difficulty_normal);
            case 3 -> getString(R.string.nbt_difficulty_hard);
            default -> getString(R.string.nbt_unknown);
        };
    }

    /** 修改 NBT 树并写回 level.dat（写前备份为 level.dat.bak）。 */
    private void applyLevelDatEdits(Map<String, NbtTag> root, String newName, int gameType,
                                    int difficulty, boolean hardcore, long seed) {
        File levelDat = new File(currentWorldDir, "level.dat");
        try {
            // 备份当前文件（游戏只保留 level.dat_old，这里额外留一份启动器备份）
            File backup = new File(currentWorldDir, "level.dat.bak");
            copyFile(levelDat, backup);

            if (!newName.isEmpty()) {
                root.put("LevelName", new NbtTag(NbtTag.TAG_STRING, "LevelName", newName));
            }
            root.put("GameType", new NbtTag(NbtTag.TAG_INT, "GameType", gameType));
            root.put("Difficulty", new NbtTag(NbtTag.TAG_INT, "Difficulty", difficulty));
            root.put("IsHardcore", new NbtTag(NbtTag.TAG_BYTE, "IsHardcore",
                    (byte) (hardcore ? 1 : 0)));
            root.put("RandomSeed", new NbtTag(NbtTag.TAG_LONG, "RandomSeed", seed));

            BedrockNbtWriter writer = new BedrockNbtWriter();
            writer.setHeaderVersion(10);
            writer.writeFile(levelDat, levelDatRoot);
            Toast.makeText(this, R.string.nbt_edit_saved, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "level.dat 已写回: " + levelDat.getAbsolutePath());

            // 刷新摘要与 NBT 树
            binding.nbtSummaryName.setText(newName.isEmpty()
                    ? getString(R.string.nbt_unknown) : newName);
            StringBuilder info = new StringBuilder();
            info.append(getString(R.string.nbt_summary_seed, seed)).append('\n');
            info.append(getString(R.string.nbt_summary_gamemode, gamemodeName(gameType))).append('\n');
            info.append(getString(R.string.nbt_summary_hardcore, yesNo(hardcore))).append('\n');
            info.append(getString(R.string.nbt_summary_dead, yesNo(levelDatRoot != null
                    && levelDatRoot.getTag("PlayerHasDied") != null
                    && levelDatRoot.getTag("PlayerHasDied").getByte() != 0)));
            binding.nbtSummaryInfo.setText(info.toString());
            binding.nbtLevelTree.removeAllViews();
            addTreeRoot(binding.nbtLevelTree, levelDatRoot, getString(R.string.nbt_level_dat));
        } catch (IOException e) {
            Log.e(TAG, "写回 level.dat 失败", e);
            Toast.makeText(this, getString(R.string.nbt_edit_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String gamemodeName(int gameType) {
        return switch (gameType) {
            case 0 -> getString(R.string.nbt_gamemode_survival);
            case 1 -> getString(R.string.nbt_gamemode_creative);
            case 2 -> getString(R.string.nbt_gamemode_adventure);
            default -> getString(R.string.nbt_unknown);
        };
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    // ---------------------------------------------------------------- Tabs

    private void selectTab(int tab) {
        currentTab = tab;
        binding.nbtLevelPane.setVisibility(tab == TAB_LEVEL ? View.VISIBLE : View.GONE);
        binding.nbtDbPane.setVisibility(tab == TAB_DB ? View.VISIBLE : View.GONE);

        boolean levelSelected = tab == TAB_LEVEL;
        styleTab(binding.nbtTabLevel, levelSelected);
        styleTab(binding.nbtTabDb, !levelSelected);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        if (selected && accentColor != 0) {
            tab.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        } else {
            tab.setBackgroundTintList(null);
        }
        tab.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.on_primary : R.color.text_secondary));
        tab.setTypeface(tab.getTypeface(), selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ---------------------------------------------------------------- db 列表

    private void onDbEntryClick(LevelDBEntry entry) {
        if (dbParseRunning) return;
        byte[] value = entry.getValue();
        if (value == null) return;

        dbParseRunning = true;
        executor.execute(() -> {
            NbtTag root = null;
            String error = null;
            if (value.length > MAX_PARSE_BYTES) {
                error = getString(R.string.nbt_value_too_large);
            } else {
                try {
                    root = new BedrockNbtReader().readFromBytes(value);
                    if (root == null || root.getType() == NbtTag.TAG_END) {
                        root = null;
                        error = getString(R.string.nbt_not_nbt);
                    }
                } catch (Exception e) {
                    root = null;
                    error = getString(R.string.nbt_not_nbt) + ": " + e.getMessage();
                }
            }
            final NbtTag fRoot = root;
            final String fError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showDbEntryDetail(entry, fRoot, fError);
            });
        });
    }

    private void showDbEntryDetail(LevelDBEntry entry, NbtTag root, String error) {
        dbParseRunning = false;
        binding.nbtDbDetailHeader.setVisibility(View.VISIBLE);
        binding.nbtDbKeyName.setText(entry.getKey().getDisplayName());
        binding.nbtDbRecycler.setVisibility(View.GONE);
        binding.nbtDbEmpty.setVisibility(View.GONE);
        binding.nbtDbScroll.setVisibility(View.VISIBLE);

        binding.nbtDbTree.removeAllViews();
        if (root != null) {
            addTreeRoot(binding.nbtDbTree, root, entry.getKey().getDisplayName());
        } else {
            TextView message = new TextView(this);
            message.setText(error != null ? error : getString(R.string.nbt_not_nbt));
            message.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            message.setTextSize(13);
            message.setPadding(dp(8), dp(12), dp(8), dp(4));
            message.setTypeface(misans(), Typeface.NORMAL);
            binding.nbtDbTree.addView(message);
            binding.nbtDbTree.addView(buildHexPreview(entry.getValue()));
        }
    }

    private void showDbEntryList() {
        binding.nbtDbDetailHeader.setVisibility(View.GONE);
        binding.nbtDbScroll.setVisibility(View.GONE);
        binding.nbtDbTree.removeAllViews();
        binding.nbtDbRecycler.setVisibility(View.VISIBLE);
        if (dbEntries.isEmpty()) {
            binding.nbtDbEmpty.setVisibility(View.VISIBLE);
        }
    }

    /** 非 NBT 数据：展示前 64 字节十六进制预览 */
    private TextView buildHexPreview(byte[] value) {
        int shown = Math.min(value != null ? value.length : 0, 64);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            hex.append(String.format(Locale.US, "%02X ", value[i] & 0xFF));
        }
        if (shown < (value != null ? value.length : 0)) {
            hex.append("…");
        }
        TextView preview = new TextView(this);
        preview.setText(hex.toString());
        preview.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        preview.setTextSize(11);
        preview.setPadding(dp(8), 0, dp(8), dp(12));
        preview.setTypeface(Typeface.MONOSPACE);
        return preview;
    }

    // ---------------------------------------------------------------- NBT 树渲染

    private void addTreeRoot(LinearLayout container, NbtTag root, String rootTitle) {
        if (rootTitle != null && !rootTitle.isEmpty()) {
            TextView title = new TextView(this);
            title.setText(rootTitle);
            title.setTextColor(ContextCompat.getColor(this, R.color.primary));
            title.setTextSize(14);
            title.setTypeface(misans(), Typeface.BOLD);
            title.setPadding(0, dp(4), 0, dp(4));
            container.addView(title);
        }
        if (root.getType() == NbtTag.TAG_COMPOUND || root.getType() == NbtTag.TAG_LIST) {
            buildChildren(container, root, 0);
        } else {
            addTagNode(container, root, -1, 0);
        }
    }

    /** 递归渲染节点：COMPOUND/LIST 为可折叠节点，基本类型为叶行 */
    private void addTagNode(LinearLayout parent, NbtTag tag, int index, int depth) {
        boolean containerType = tag.getType() == NbtTag.TAG_COMPOUND
                || tag.getType() == NbtTag.TAG_LIST;
        boolean expandable = containerType && depth < MAX_DEPTH;

        if (expandable) {
            View header = createExpandableHeader(tag, index, depth);
            LinearLayout children = new LinearLayout(this);
            children.setOrientation(LinearLayout.VERTICAL);
            children.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            children.setPadding(dp(16), 0, 0, 0);
            children.setVisibility(View.GONE);
            parent.addView(header);
            parent.addView(children);

            NodeHolder holder = new NodeHolder(tag, children, depth, header);
            header.setTag(holder);
            header.setOnClickListener(v -> toggleNode((NodeHolder) v.getTag()));
        } else if (containerType) {
            // 深度限制：仅显示摘要行
            parent.addView(createLeafRow(formatContainerSummary(tag, index), depth, Typeface.NORMAL,
                    ContextCompat.getColor(this, R.color.text_secondary)));
        } else {
            parent.addView(createLeafRow(formatPrimitive(tag, index), depth, Typeface.NORMAL,
                    ContextCompat.getColor(this, R.color.on_surface)));
        }
    }

    private void toggleNode(NodeHolder holder) {
        if (!holder.built) {
            holder.built = true;
            buildChildren(holder.children, holder.tag, holder.depth + 1);
        }
        boolean nowVisible = holder.children.getVisibility() != View.VISIBLE;
        holder.children.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
        holder.arrow.setText(nowVisible ? "▾" : "▸");
    }

    private void buildChildren(LinearLayout container, NbtTag tag, int depth) {
        if (tag.getType() == NbtTag.TAG_COMPOUND) {
            Map<String, NbtTag> map = tag.getCompound();
            int shown = 0;
            for (Map.Entry<String, NbtTag> entry : map.entrySet()) {
                if (shown >= MAX_CHILDREN) break;
                addTagNode(container, entry.getValue(), -1, depth);
                shown++;
            }
            if (map.size() > MAX_CHILDREN) {
                addHiddenRow(container, map.size() - MAX_CHILDREN);
            }
        } else if (tag.getType() == NbtTag.TAG_LIST) {
            List<NbtTag> list = tag.getList();
            int shown = 0;
            for (int i = 0; i < list.size() && shown < MAX_CHILDREN; i++) {
                addTagNode(container, list.get(i), i, depth);
                shown++;
            }
            if (list.size() > MAX_CHILDREN) {
                addHiddenRow(container, list.size() - MAX_CHILDREN);
            }
        }
    }

    private void addHiddenRow(LinearLayout container, int hiddenCount) {
        TextView row = new TextView(this);
        row.setText(getString(R.string.nbt_items_hidden, hiddenCount));
        row.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        row.setTextSize(12);
        row.setTypeface(misans(), Typeface.ITALIC);
        row.setPadding(0, dp(3), 0, dp(3));
        container.addView(row);
    }

    private View createExpandableHeader(NbtTag tag, int index, int depth) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        TextView arrow = new TextView(this);
        arrow.setText("▸");
        arrow.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        arrow.setTextSize(10);
        arrow.setGravity(Gravity.CENTER);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.addView(arrow);

        TextView label = new TextView(this);
        label.setText(formatContainerSummary(tag, index));
        label.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
        label.setTextSize(13);
        label.setTypeface(misans(), Typeface.BOLD);
        label.setPadding(0, 0, dp(8), 0);
        row.addView(label);

        return row;
    }

    private TextView createLeafRow(String text, int depth, int style, int color) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(color);
        row.setTextSize(13);
        row.setTypeface(misans(), style);
        row.setPadding(0, dp(3), dp(8), dp(3));
        return row;
    }

    private String formatContainerSummary(NbtTag tag, int index) {
        String name = displayName(tag, index);
        if (tag.getType() == NbtTag.TAG_COMPOUND) {
            String count = getString(R.string.nbt_items, tag.getCompound().size());
            if (tag.getName() == null || tag.getName().isEmpty()) {
                return name + " · " + count;
            }
            return "{" + name + "} · " + count;
        }
        return name + " [" + tag.getList().size() + "]";
    }

    private String formatPrimitive(NbtTag tag, int index) {
        String name = displayName(tag, index);
        switch (tag.getType()) {
            case NbtTag.TAG_BYTE: {
                byte v = tag.getByte();
                if (v == 0) return name + " = 0 (false)";
                if (v == 1) return name + " = 1 (true)";
                return name + " = " + v;
            }
            case NbtTag.TAG_SHORT:
                return name + " = " + tag.getShort();
            case NbtTag.TAG_INT:
                return name + " = " + tag.getInt();
            case NbtTag.TAG_LONG: {
                long v = tag.getLong();
                String suffix = longTimeSuffix(name, v);
                return name + " = " + v + (suffix != null ? suffix : "");
            }
            case NbtTag.TAG_FLOAT:
                return name + " = " + String.format(Locale.US, "%.1f", tag.getFloat());
            case NbtTag.TAG_DOUBLE:
                return name + " = " + String.format(Locale.US, "%.2f", tag.getDouble());
            case NbtTag.TAG_STRING: {
                String s = tag.getString();
                if (s.length() > MAX_STRING_DISPLAY) {
                    s = s.substring(0, MAX_STRING_DISPLAY) + "…";
                }
                return name + " = \"" + s + "\"";
            }
            case NbtTag.TAG_BYTE_ARRAY: {
                byte[] arr = tag.getByteArray();
                StringBuilder sb = new StringBuilder("byte[")
                        .append(arr.length).append("]");
                int n = Math.min(arr.length, 8);
                if (n > 0) {
                    sb.append(": ");
                    for (int i = 0; i < n; i++) {
                        sb.append(String.format(Locale.US, "%02X ", arr[i] & 0xFF));
                    }
                    if (arr.length > n) sb.append("…");
                }
                return name + " = " + sb;
            }
            case NbtTag.TAG_INT_ARRAY:
                return name + " = int[" + tag.getIntArray().length + "]";
            case NbtTag.TAG_LONG_ARRAY:
                return name + " = long[" + tag.getLongArray().length + "]";
            case NbtTag.TAG_END:
                return name + " = END";
            default:
                return name + " = " + tag.getValue();
        }
    }

    /** Long 时间换算：key 含 Time/Played 时附加上下文 */
    private String longTimeSuffix(String name, long value) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.contains("time") && !lower.contains("played")) {
            return null;
        }
        if (value >= 100_000_000L && value <= 1_000_000_000_000L) {
            // 秒级时间戳（如 LastPlayed）
            return " (" + dateFormat.format(new Date(value * 1000L)) + ")";
        }
        if (value >= 0) {
            // 游戏刻：24000 刻/天，1000 刻/时
            long days = value / 24000L;
            long hours = (value % 24000L) / 1000L;
            long minutes = (value % 1000L) * 60L / 1000L;
            return " (" + getString(R.string.nbt_ticks_time, days, hours, minutes) + ")";
        }
        return null;
    }

    private String displayName(NbtTag tag, int index) {
        String name = tag.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return index >= 0 ? "[" + index + "]" : "(root)";
    }

    private Typeface misans() {
        return ResourcesCompat.getFont(this, R.font.misans);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String humanSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    // ---------------------------------------------------------------- 节点状态

    private static class NodeHolder {
        final NbtTag tag;
        final LinearLayout children;
        final TextView arrow;
        final int depth;
        boolean built;

        NodeHolder(NbtTag tag, LinearLayout children, int depth, View header) {
            this.tag = tag;
            this.children = children;
            this.depth = depth;
            this.arrow = header instanceof LinearLayout
                    ? (TextView) ((LinearLayout) header).getChildAt(0)
                    : null;
        }
    }

    // ---------------------------------------------------------------- db 条目适配器

    private class DbEntryAdapter extends RecyclerView.Adapter<DbEntryAdapter.VH> {

        interface Listener {
            void onEntryClick(LevelDBEntry entry);
        }

        private final Listener listener;

        DbEntryAdapter(Listener listener) {
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemNbtDbEntryBinding itemBinding = ItemNbtDbEntryBinding.inflate(
                    getLayoutInflater(), parent, false);
            return new VH(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            LevelDBEntry entry = dbEntries.get(position);
            byte[] value = entry.getValue();
            holder.binding.nbtEntryName.setText(entry.getKey().getDisplayName());
            holder.binding.nbtEntryInfo.setText(humanSize(value != null ? value.length : 0));
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onEntryClick(entry);
            });
        }

        @Override
        public int getItemCount() {
            return dbEntries.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemNbtDbEntryBinding binding;

            VH(ItemNbtDbEntryBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 大世界按需渲染：把本次会话渲染过的 chunk 增量写入磁盘缓存
        if (currentMap != null && currentMap.chunkSourceDir != null
                && !currentMap.chunkColors.isEmpty()) {
            WorldMapRenderer.saveChunkCache(currentMap, currentMap.chunkSourceDir,
                    currentMap.chunkSourceDim);
        }
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
        if (blueprintDb != null) {
            blueprintDb.close();
        }
    }

    // ---------------------------------------------------------------- 标点编辑 / 详情

    /** 标点编辑弹窗（point=null 时新建，坐标为 blockX/blockZ）。 */
    private void showPointEditor(BlueprintDb.Point point, int blockX, int blockZ) {
        EditText nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        nameEdit.setHint(R.string.point_name);
        if (point != null) {
            nameEdit.setText(point.name);
        }

        // 分类：点击弹出启动器列表选择
        final String[] catValues = {BlueprintDb.CAT_BASE, BlueprintDb.CAT_PORTAL,
                BlueprintDb.CAT_FARM, BlueprintDb.CAT_VILLAGE,
                BlueprintDb.CAT_STRUCTURE, BlueprintDb.CAT_CUSTOM};
        String[] catNames = {getString(R.string.cat_base), getString(R.string.cat_portal),
                getString(R.string.cat_farm), getString(R.string.cat_village),
                getString(R.string.cat_structure), getString(R.string.cat_custom)};
        final String[] selCategory = {point != null ? point.category : BlueprintDb.CAT_CUSTOM};

        EditText detailEdit = new EditText(this);
        detailEdit.setSingleLine(false);
        detailEdit.setHint(R.string.point_detail);
        if (point != null && point.detail != null) {
            detailEdit.setText(point.detail);
        }

        final int px = point != null ? point.x : blockX;
        final int pz = point != null ? point.z : blockZ;
        final int py = point != null ? point.y : 64;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        panel.addView(nameEdit);
        panel.addView(detailEdit);
        TextView coordText = new TextView(this);
        coordText.setText(getString(R.string.point_coord) + ": X:" + px + " Y:" + py + " Z:" + pz
                + " (" + dimName(mapDimension) + ")");
        coordText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        coordText.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        coordText.setLayoutParams(lp);
        panel.addView(coordText);
        TextView catLabel = new TextView(this);
        catLabel.setText(getString(R.string.point_category) + ": " + catName(selCategory[0]));
        catLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        catLabel.setTextSize(12);
        catLabel.setLayoutParams(lp);
        catLabel.setClickable(true);
        catLabel.setFocusable(true);
        catLabel.setOnClickListener(v -> new CustomAlertDialog(this)
                .setTitleText(getString(R.string.point_category))
                .setItems(catNames, (dialog, which) -> {
                    selCategory[0] = catValues[which];
                    catLabel.setText(getString(R.string.point_category) + ": " + catNames[which]);
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show());
        panel.addView(catLabel);

        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(point != null ? R.string.point_edit_title : R.string.point_add_title))
                .setCustomView(panel)
                .setPositiveButton(getString(R.string.nbt_edit_save), v -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "未命名标点";
                    }
                    String detail = detailEdit.getText().toString().trim();
                    if (point != null) {
                        point.name = name;
                        point.category = selCategory[0];
                        point.detail = detail;
                        blueprintDb.updatePoint(point);
                    } else {
                        BlueprintDb.Point np = new BlueprintDb.Point();
                        np.worldId = blueprintWorldId;
                        np.name = name;
                        np.x = px;
                        np.y = py;
                        np.z = pz;
                        np.dimension = mapDimension;
                        np.category = selCategory[0];
                        np.detail = detail;
                        np.color = categoryColor(selCategory[0]);
                        np.id = blueprintDb.addPoint(np);
                        mapPoints.add(np);
                    }
                    Toast.makeText(this, R.string.point_saved, Toast.LENGTH_SHORT).show();
                    loadBlueprintData();
                });
        if (point != null) {
            dialog.setNegativeButton(getString(R.string.point_delete), v2 -> {
                blueprintDb.deletePoint(point.id);
                Toast.makeText(this, R.string.point_deleted, Toast.LENGTH_SHORT).show();
                loadBlueprintData();
            });
        } else {
            dialog.setNegativeButton(getString(R.string.nbt_edit_cancel), null);
        }
        dialog.show();
    }

    /** 标点详情弹窗：详情 + 编辑 + 删除。 */
    private void showPointDetail(BlueprintDb.Point point) {
        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.point_coord)).append(": X:").append(point.x)
                .append(" Y:").append(point.y).append(" Z:").append(point.z).append('\n');
        info.append(getString(R.string.point_category)).append(": ").append(catName(point.category)).append('\n');
        if (point.detail != null && !point.detail.isEmpty()) {
            info.append(getString(R.string.point_detail)).append(": ").append(point.detail).append('\n');
        }
        new CustomAlertDialog(this)
                .setTitleText(point.name)
                .setMessage(info.toString())
                .setPositiveButton(getString(R.string.point_edit_title), v -> {
                    showPointEditor(point, point.x, point.z);
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show();
    }

    /** 连线模式：两次点击标点建线。 */
    private void handleLinkPick(BlueprintDb.Point p) {
        if (linkModeFrom == null) {
            linkModeFrom = p.id;
            binding.worldMapImage.setHighlightPoint(p.id);
            Toast.makeText(this, R.string.link_pick_second, Toast.LENGTH_SHORT).show();
            return;
        }
        BlueprintDb.Point from = findPoint(linkModeFrom);
        BlueprintDb.Link l = new BlueprintDb.Link();
        l.worldId = blueprintWorldId;
        l.fromId = linkModeFrom;
        l.toId = p.id;
        l.type = BlueprintDb.LINK_LOGISTICS;
        l.color = "#64b5f6";
        l.id = blueprintDb.addLink(l);
        mapLinks.add(l);
        linkModeActive = false;
        linkModeFrom = null;
        binding.worldMapImage.setHighlightPoint(-1);
        Toast.makeText(this, getString(R.string.link_created,
                from != null ? from.name : "", p.name), Toast.LENGTH_SHORT).show();
        loadBlueprintData();
    }

    /** 测距：两次点击标点显示距离（跨维度 1:8）。 */
    private void handleRulerPick(BlueprintDb.Point p) {
        if (rulerFrom == null) {
            rulerFrom = p.id;
            binding.worldMapImage.setHighlightPoint(p.id);
            Toast.makeText(this, R.string.ruler_pick_first, Toast.LENGTH_SHORT).show();
            return;
        }
        BlueprintDb.Point a = findPoint(rulerFrom);
        if (a == null) {
            rulerFrom = null;
            return;
        }
        double ax = "nether".equals(a.dimension) ? a.x * 8.0 : a.x;
        double az = "nether".equals(a.dimension) ? a.z * 8.0 : a.z;
        double bx = "nether".equals(p.dimension) ? p.x * 8.0 : p.x;
        double bz = "nether".equals(p.dimension) ? p.z * 8.0 : p.z;
        double ow = Math.hypot(bx - ax, bz - az);
        String result = a.dimension.equals(p.dimension)
                ? Math.round(ow) + "m"
                : getString(R.string.ruler_result,
                        Math.round(ow) + "m") + "（主世界）/ " + Math.round(ow / 8) + "m（下界）";
        Toast.makeText(this, getString(R.string.ruler_result, result), Toast.LENGTH_LONG).show();
        rulerFrom = null;
        rulerModeActive = false;
        binding.worldMapImage.setHighlightPoint(-1);
    }

    private BlueprintDb.Point findPoint(long id) {
        for (BlueprintDb.Point p : mapPoints) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    private String catName(String category) {
        switch (category) {
            case BlueprintDb.CAT_BASE: return getString(R.string.cat_base);
            case BlueprintDb.CAT_PORTAL: return getString(R.string.cat_portal);
            case BlueprintDb.CAT_FARM: return getString(R.string.cat_farm);
            case BlueprintDb.CAT_VILLAGE: return getString(R.string.cat_village);
            case BlueprintDb.CAT_STRUCTURE: return getString(R.string.cat_structure);
            default: return getString(R.string.cat_custom);
        }
    }

    private String categoryColor(String category) {
        switch (category) {
            case BlueprintDb.CAT_BASE: return "#ffd54f";
            case BlueprintDb.CAT_PORTAL: return "#ab47bc";
            case BlueprintDb.CAT_FARM: return "#ef5350";
            case BlueprintDb.CAT_VILLAGE: return "#4ade80";
            case BlueprintDb.CAT_STRUCTURE: return "#f5a623";
            default: return "#ffd54f";
        }
    }

    private String dimName(String dim) {
        if ("nether".equals(dim)) return getString(R.string.dim_nether);
        if ("end".equals(dim)) return getString(R.string.dim_end);
        return getString(R.string.dim_overworld);
    }

    // ---------------------------------------------------------------- 标点列表

    /** 标点列表弹窗（名称/拼音首字母/坐标搜索 + 点击跳到标点）。 */
    private void showPointListDialog() {
        if (mapPoints.isEmpty()) {
            Toast.makeText(this, R.string.no_points, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.point_search_hint);

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        Runnable rebuild = () -> {
            listContainer.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase();
            boolean any = false;
            for (BlueprintDb.Point p : mapPoints) {
                if (!p.dimension.equals(mapDimension)) {
                    continue;
                }
                if (!q.isEmpty()
                        && !p.name.toLowerCase().contains(q)
                        && !(p.x + "," + p.z).contains(q)) {
                    continue;
                }
                any = true;
                TextView item = new TextView(this);
                item.setText("● " + p.name + "  (" + p.x + "," + p.z + ")  " + catName(p.category));
                item.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
                item.setTextSize(13);
                item.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density),
                        0, (int) (8 * getResources().getDisplayMetrics().density));
                item.setClickable(true);
                item.setFocusable(true);
                item.setOnClickListener(v -> {
                    // 跳到标点：维度不一致先切换，然后平滑飞向标点
                    if (!p.dimension.equals(mapDimension)) {
                        mapDimension = p.dimension;
                        loadMapForDimension(p.dimension);
                    }
                    binding.worldMapImage.animateTo(p.x, p.z);
                });
                listContainer.addView(item);
            }
            if (!any) {
                TextView empty = new TextView(this);
                empty.setText(R.string.no_points);
                empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                empty.setTextSize(12);
                listContainer.addView(empty);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild.run(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(listContainer);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(search);
        panel.addView(scroll);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.point_list_title))
                .setCustomView(panel)
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show();
        rebuild.run();
    }

    // ---------------------------------------------------------------- 蓝图码

    /** 蓝图码弹窗：生成/复制/分享/导入（冲突处理三选项）。 */
    private void showBlueprintDialog() {
        EditText codeView = new EditText(this);
        codeView.setSingleLine(false);
        codeView.setHorizontallyScrolling(true);
        codeView.setText(generateBlueprintCode());
        codeView.setTextIsSelectable(true);
        codeView.setKeyListener(null); // 只读

        EditText importInput = new EditText(this);
        importInput.setSingleLine(false);
        importInput.setHint(R.string.blueprint_import_hint);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // 伪二维码（HTML 原型 drawFakeQr：蓝图码 hash 视觉化，非真实扫码）
        ImageView qrView = new ImageView(this);
        int qrPx = (int) (96 * getResources().getDisplayMetrics().density);
        qrView.setImageBitmap(fakeQrBitmap(codeView.getText().toString(), qrPx));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrPx, qrPx);
        qrLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        qrView.setLayoutParams(qrLp);
        panel.addView(qrView);
        panel.addView(codeView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        importInput.setLayoutParams(lp);
        panel.addView(importInput);

        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.blueprint_title))
                .setCustomView(panel)
                .setNeutralButton(getString(R.string.blueprint_copy), v -> {
                    copyToClipboard(codeView.getText().toString());
                    Toast.makeText(this, R.string.blueprint_copied, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(getString(R.string.blueprint_import), v -> {
                    String code = importInput.getText().toString().trim();
                    if (code.isEmpty()) {
                        code = codeView.getText().toString();
                    }
                    importBlueprint(code);
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null);
        dialog.show();
    }

    /** 蓝图码 hash → 24×24 伪二维码位图（HTML 原型 drawFakeQr 同款）。 */
    private android.graphics.Bitmap fakeQrBitmap(String seedStr, int px) {
        int seed = 0;
        for (char c : seedStr.toCharArray()) {
            seed = seed * 31 + c;
        }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(24, 24,
                android.graphics.Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                seed = seed * 1103515245 + 12345;
                boolean on = ((seed >>> 24) & 1) != 0;
                bmp.setPixel(x, y, on ? 0xFF111111 : 0xFFFFFFFF);
            }
        }
        return android.graphics.Bitmap.createScaledBitmap(bmp, px, px, false);
    }

    /** JSON → GZIP → Base64 → LeviBP:v1: 前缀（PRD 07.3 管线）。 */
    private String generateBlueprintCode() {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("v", 1);
            java.util.Map<String, Object> world = new java.util.HashMap<>();
            world.put("name", currentWorldDir != null ? currentWorldDir.getName() : "");
            world.put("seed", getWorldSeed());
            payload.put("world", world);
            java.util.List<Object> pts = new java.util.ArrayList<>();
            for (BlueprintDb.Point p : mapPoints) {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", p.id);
                m.put("name", p.name);
                m.put("x", p.x);
                m.put("y", p.y);
                m.put("z", p.z);
                m.put("dimension", p.dimension);
                m.put("category", p.category);
                m.put("color", p.color);
                m.put("detail", p.detail != null ? p.detail : "");
                pts.add(m);
            }
            payload.put("points", pts);
            java.util.List<Object> lks = new java.util.ArrayList<>();
            for (BlueprintDb.Link l : mapLinks) {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("from", l.fromId);
                m.put("to", l.toId);
                m.put("type", l.type);
                m.put("color", l.color);
                lks.add(m);
            }
            payload.put("links", lks);
            byte[] gz = gzipBytes(gson.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "LeviBP:v1:" + android.util.Base64.encodeToString(gz, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "生成蓝图码失败", e);
            return "LeviBP:v1:";
        }
    }

    private long getWorldSeed() {
        if (levelDatRoot != null) {
            NbtTag seedTag = levelDatRoot.getTag("RandomSeed");
            if (seedTag != null) {
                return seedTag.getLong();
            }
        }
        return 0;
    }

    /** 解析并导入蓝图码（解码 → 种子校验 → 冲突预览 → 三选项处理）。 */
    private void importBlueprint(String code) {
        if (!code.startsWith("LeviBP:v1:")) {
            Toast.makeText(this, R.string.blueprint_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] raw = android.util.Base64.decode(
                    code.substring("LeviBP:v1:".length()), android.util.Base64.DEFAULT);
            byte[] jsonBytes = gunzipBytes(raw);
            com.google.gson.Gson gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> obj = gson.fromJson(
                    new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8),
                    java.util.Map.class);
            java.util.List<java.util.Map<String, Object>> pts =
                    (java.util.List<java.util.Map<String, Object>>) obj.get("points");
            java.util.List<java.util.Map<String, Object>> lks =
                    (java.util.List<java.util.Map<String, Object>>) obj.get("links");
            if (pts == null) {
                Toast.makeText(this, R.string.blueprint_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            // 种子校验：不一致仅警告
            java.util.Map<String, Object> worldMeta = (java.util.Map<String, Object>) obj.get("world");
            if (worldMeta != null && worldMeta.get("seed") instanceof Double
                    && ((Double) worldMeta.get("seed")).longValue() != getWorldSeed()) {
                Toast.makeText(this, R.string.blueprint_seed_warn, Toast.LENGTH_LONG).show();
            }
            // 冲突检测：同名标点
            java.util.List<String> conflictNames = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> m : pts) {
                String name = (String) m.get("name");
                for (BlueprintDb.Point existing : mapPoints) {
                    if (existing.name.equals(name)) {
                        conflictNames.add(name);
                        break;
                    }
                }
            }
            if (!conflictNames.isEmpty()) {
                showImportConflictDialog(obj, pts, lks, conflictNames);
            } else {
                applyBlueprintImport(pts, lks, new java.util.HashMap<>());
                Toast.makeText(this, getString(R.string.blueprint_import_ok,
                        pts.size(), lks != null ? lks.size() : 0), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "导入蓝图码失败", e);
            Toast.makeText(this, R.string.blueprint_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    /** 冲突处理弹窗：每个冲突标点可选 覆盖/重命名/保留。 */
    private void showImportConflictDialog(java.util.Map<String, Object> obj,
                                          java.util.List<java.util.Map<String, Object>> pts,
                                          java.util.List<java.util.Map<String, Object>> lks,
                                          java.util.List<String> conflictNames) {
        final java.util.Map<String, String> ops = new java.util.HashMap<>();
        for (String n : conflictNames) {
            ops.put(n, "keep");
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        for (String name : conflictNames) {
            TextView label = new TextView(this);
            label.setText("● " + name);
            label.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
            label.setTextSize(13);
            panel.addView(label);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            String[] opNames = {getString(R.string.blueprint_op_overwrite),
                    getString(R.string.blueprint_op_rename), getString(R.string.blueprint_op_keep)};
            String[] opValues = {"overwrite", "rename", "keep"};
            final TextView[] opViews = new TextView[3];
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                TextView op = new TextView(this);
                op.setText(opNames[i]);
                op.setPadding((int) (10 * getResources().getDisplayMetrics().density),
                        (int) (6 * getResources().getDisplayMetrics().density),
                        (int) (10 * getResources().getDisplayMetrics().density),
                        (int) (6 * getResources().getDisplayMetrics().density));
                op.setTextSize(12);
                op.setTextColor(ContextCompat.getColor(this,
                        i == 2 ? R.color.primary : R.color.text_secondary));
                op.setClickable(true);
                op.setFocusable(true);
                op.setOnClickListener(v -> {
                    ops.put(name, opValues[idx]);
                    for (TextView tv : opViews) {
                        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    }
                    op.setTextColor(ContextCompat.getColor(this, R.color.primary));
                });
                opViews[i] = op;
                row.addView(op);
            }
            panel.addView(row);
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.blueprint_conflict_title))
                .setCustomView(panel)
                .setPositiveButton(getString(R.string.blueprint_import), v -> {
                    applyBlueprintImport(pts, lks, ops);
                    Toast.makeText(this, getString(R.string.blueprint_import_ok,
                            pts.size(), lks != null ? lks.size() : 0), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show();
    }

    /** 按冲突选项写入数据库：覆盖替换、重命名追加 (2)、保留跳过。 */
    private void applyBlueprintImport(java.util.List<java.util.Map<String, Object>> pts,
                                      java.util.List<java.util.Map<String, Object>> lks,
                                      java.util.Map<String, String> ops) {
        java.util.Map<String, Long> idMap = new java.util.HashMap<>(); // 旧 id → 新 id
        for (java.util.Map<String, Object> m : pts) {
            String name = (String) m.get("name");
            String op = ops.getOrDefault(name, "keep");
            if ("keep".equals(op)) {
                continue;
            }
            double x = ((Number) m.get("x")).doubleValue();
            double y = ((Number) m.get("y")).doubleValue();
            double z = ((Number) m.get("z")).doubleValue();
            String dimension = (String) m.get("dimension");
            String category = (String) m.get("category");
            String color = (String) m.get("color");
            String detail = (String) m.get("detail");
            if (dimension == null) dimension = "overworld";
            if (category == null) category = BlueprintDb.CAT_CUSTOM;
            if (color == null) color = "#ffd54f";
            double oldId = ((Number) m.get("id")).doubleValue();
            if ("overwrite".equals(op)) {
                BlueprintDb.Point existing = null;
                for (BlueprintDb.Point p : mapPoints) {
                    if (p.name.equals(name)) {
                        existing = p;
                        break;
                    }
                }
                if (existing != null) {
                    existing.x = (int) x;
                    existing.y = (int) y;
                    existing.z = (int) z;
                    existing.dimension = dimension;
                    existing.category = category;
                    existing.color = color;
                    existing.detail = detail != null ? detail : "";
                    blueprintDb.updatePoint(existing);
                    idMap.put(String.valueOf((long) oldId), existing.id);
                }
            } else { // rename
                String newName = name;
                int n = 2;
                while (nameExists(newName)) {
                    newName = name + " (" + n++ + ")";
                }
                BlueprintDb.Point np = new BlueprintDb.Point();
                np.worldId = blueprintWorldId;
                np.name = newName;
                np.x = (int) x;
                np.y = (int) y;
                np.z = (int) z;
                np.dimension = dimension;
                np.category = category;
                np.color = color;
                np.detail = detail != null ? detail : "";
                np.id = blueprintDb.addPoint(np);
                mapPoints.add(np);
                idMap.put(String.valueOf((long) oldId), np.id);
            }
        }
        // 连线：旧 id 映射到新 id（保留的标点沿用旧 id）
        if (lks != null) {
            for (java.util.Map<String, Object> m : lks) {
                long from = ((Number) m.get("from")).longValue();
                long to = ((Number) m.get("to")).longValue();
                String type = (String) m.get("type");
                String color = (String) m.get("color");
                long nf = idMap.containsKey(String.valueOf(from)) ? idMap.get(String.valueOf(from)) : from;
                long nt = idMap.containsKey(String.valueOf(to)) ? idMap.get(String.valueOf(to)) : to;
                if (nf <= 0 || nt <= 0) {
                    continue;
                }
                BlueprintDb.Link l = new BlueprintDb.Link();
                l.worldId = blueprintWorldId;
                l.fromId = nf;
                l.toId = nt;
                l.type = type != null ? type : BlueprintDb.LINK_LOGISTICS;
                l.color = color != null ? color : "#64b5f6";
                blueprintDb.addLink(l);
            }
        }
        loadBlueprintData();
    }

    private boolean nameExists(String name) {
        for (BlueprintDb.Point p : mapPoints) {
            if (p.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 导入时标点 id 映射（idMap 更新后保留标点沿用旧 id 已在 applyBlueprintImport 处理）。 */
    private static byte[] gzipBytes(byte[] input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(input);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzipBytes(byte[] input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(input))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("blueprint", text));
        }
    }
}
