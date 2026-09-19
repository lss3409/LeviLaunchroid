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
import org.levimc.launcher.ui.views.VoxelView;
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
     *  （游戏式全核调度，类似终末地 Job System 的做法）。
     *  切维度时整池换新（shutdownNow 旧池清队列）——否则旧维度任务排队，
     *  新维度 chunk 全部等旧队列跑完（下界/末地切换 60 秒的根因）。 */
    /** 延迟解析线程池（实体/结构解析，切维度换新清队列）。 */
    private volatile ExecutorService renderPool = newRenderPool();
    /** 渲染代际：切维度 +1；任务执行时比对，代际不符直接放弃（旧维度残留任务）。 */
    private final java.util.concurrent.atomic.AtomicInteger renderGen =
            new java.util.concurrent.atomic.AtomicInteger();

    private static ExecutorService newRenderPool() {
        // v392 视口渲染已删：此池只跑延迟实体/结构解析（大核绑定）
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "parse-pool");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
    }
    /** 结构标点详情弹窗：坐标 / NBT 数据 / 附近实体。 */
    private void showStructureDetail(WorldMapRenderer.StructureMarker m) {
        StringBuilder sb = new StringBuilder();
        sb.append("X: ").append(m.x).append("  Z: ").append(m.z);
        // 附近实体（半径 128 方块内，最多列 10 个）
        java.util.List<String> near = new java.util.ArrayList<>();
        for (WorldMapRenderer.EntityPos e : binding.worldMapImage.getEntities()) {
            double d = Math.hypot(e.x - m.x, e.z - m.z);
            if (d < 128 && near.size() < 10) {
                near.add(WorldMapView.entityLabel(e.name) + "(" + Math.round(d) + "m)");
            }
        }
        if (!near.isEmpty()) {
            sb.append("\n\n附近实体:\n· ").append(String.join("\n· ", near));
        }
        if (m.nbtDetail != null && !m.nbtDetail.isEmpty()) {
            sb.append("\n\nNBT 数据:\n").append(m.nbtDetail);
        } else {
            sb.append("\n\nNBT 数据: 无（palette 方块特征检测）");
        }
        new CustomAlertDialog(this)
                .setTitleText(structureLabelZh(m.type))
                .setMessage(sb.toString())
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show();
    }

    /** v413：矿石中文名（标点详情弹窗标题）。 */
    private String oreLabelZh(String name) {
        String base = name != null && name.startsWith("minecraft:")
                ? name.substring(10) : name != null ? name : "";
        switch (base) {
            case "diamond_ore":
            case "deepslate_diamond_ore": return "钻石矿石";
            case "emerald_ore":
            case "deepslate_emerald_ore": return "绿宝石矿石";
            case "gold_ore":
            case "deepslate_gold_ore": return "金矿石";
            case "nether_gold_ore": return "下界金矿石";
            case "iron_ore":
            case "deepslate_iron_ore": return "铁矿石";
            case "coal_ore":
            case "deepslate_coal_ore": return "煤矿石";
            case "copper_ore":
            case "deepslate_copper_ore": return "铜矿石";
            case "lapis_ore":
            case "deepslate_lapis_ore": return "青金石矿石";
            case "redstone_ore":
            case "deepslate_redstone_ore": return "红石矿石";
            case "nether_quartz_ore": return "下界石英矿石";
            case "ancient_debris": return "远古残骸";
            default: return base;
        }
    }

    private String structureLabelZh(String type) {
        switch (type != null ? type : "") {
            case "village": return "村庄";
            case "spawner": return "刷怪笼";
            case "trial_spawner": return "试炼刷怪笼";
            case "end_portal": return "末地传送门";
            case "fortress": return "下界要塞";
            case "swamp_hut": return "女巫小屋";
            case "ocean_monument": return "海底神殿";
            case "end_city": return "末地城";
            case "desert_temple": return "沙漠神殿";
            case "outpost": return "掠夺者前哨站";
            default: return type != null ? type : "结构";
        }
    }

    /** 合并视口按需渲染检测到的结构标记（去重后追加进结构图层）。 */
    private void mergeOnDemandStructures() {
        java.util.List<WorldMapRenderer.StructureMarker> ods =
                WorldMapRenderer.takeOnDemandStructures();
        for (WorldMapRenderer.StructureMarker m : ods) {
            boolean dup = false;
            for (WorldMapRenderer.StructureMarker cur : currentStructures) {
                if (cur.type.equals(m.type)
                        && Math.abs(cur.x - m.x) < 48 && Math.abs(cur.z - m.z) < 48) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                currentStructures.add(m);
            }
        }
    }

    /** 当前结构标记列表（按需渲染检测到的结构标记动态合并进来）。
     * 需在 flushRenderedChunks 字段之前声明（初始化块前向引用限制）。 */
    private final List<WorldMapRenderer.StructureMarker> currentStructures = new ArrayList<>();

    private final android.os.Handler flushHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());

    /**
     * 启动后台任务：取消上一个未完成的加载再新建线程池。
     * 防止快速切换维度时多个全量 db 读取并行叠加导致 OOM（崩溃日志实测）。
     * 每次启动递增 generation——迟到（被取消）加载的回调必须丢弃，
     * 否则主世界加载会覆盖切换维度后的末地/下界地图。
     */
    private int loadGeneration = 0;

    private void startBackgroundTask() {
        if (executor != null) {
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
    /** 当前视口中心（渲染排序用，Atomic 供渲染线程读）。 */
    private final java.util.concurrent.atomic.AtomicInteger viewCenterX =
            new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
    private final java.util.concurrent.atomic.AtomicInteger viewCenterZ =
            new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

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
    /** 左栏当前展开的 Tab（再次点击收回抽屉）。 */
    private View currentLbTab = null;
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
        // v400：进入卫星图——其它存档的静默烘焙停止（卫星图内
        // 烘焙由本 Activity 全速 ACTIVE 模式负责），退出后恢复
        org.levimc.launcher.core.content.worldmap.SilentBakeManager.get().pause();
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
            // 个性化主题色贯通：FAB/维度高亮/抽屉图标高亮不再用默认主题深绿色
            binding.mapFab.setBackgroundTintList(ColorStateList.valueOf(accentColor));
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

        // v392：视口按需渲染已删除（弹窗确认"删除+烘焙视口优先"）——
        // 缺失区域由烘焙统一补全，视口变化通过 OnViewChangedListener
        // 更新烘焙优先队列（拖动时烘焙跟着渲染屏幕区域）
        binding.worldMapImage.setOnViewChangedListener((cx, cz) -> {
            viewCenterX.set(cx);
            viewCenterZ.set(cz);
            WorldMapRenderer.bumpBakeViewport(cx, cz);
        });

        selectTab(TAB_LEVEL);
        loadData(worldDir, worldName);
    }

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
        binding.dataPanelClose.setOnClickListener(v ->
                binding.dataPanel.setVisibility(View.GONE));
        DynamicAnim.applyPressScale(binding.dataPanelClose);

        // 左栏：图标条点击切换 Tab（展开抽屉）；再点当前 Tab 收回抽屉。
        // ImageView 高亮用 colorFilter（setTextColor 是 TextView API）
        int activeColor = accentColor != 0 ? accentColor
                : ContextCompat.getColor(this, R.color.primary);
        int inactiveColor = ContextCompat.getColor(this, R.color.text_secondary);
        View.OnClickListener lbClick = v -> {
            boolean same = currentLbTab == v
                    && binding.leftbarBody.getVisibility() == View.VISIBLE;
            if (same) {
                // 收回：隐藏抽屉主体并复位图标高亮
                binding.leftbarBody.setVisibility(View.GONE);
                currentLbTab = null;
                resetLbTints(inactiveColor);
                return;
            }
            currentLbTab = v;
            binding.leftbarBody.setVisibility(View.VISIBLE);
            binding.tabInfo.setVisibility(v == binding.lbInfo ? View.VISIBLE : View.GONE);
            binding.tabLayers.setVisibility(v == binding.lbLayers ? View.VISIBLE : View.GONE);
            binding.tabPoints.setVisibility(v == binding.lbPoints ? View.VISIBLE : View.GONE);
            binding.tabSettings.setVisibility(v == binding.lbSettings ? View.VISIBLE : View.GONE);
            resetLbTints(inactiveColor);
            ((android.widget.ImageView) v).setColorFilter(activeColor);
        };
        binding.lbInfo.setOnClickListener(lbClick);
        binding.lbLayers.setOnClickListener(lbClick);
        binding.lbPoints.setOnClickListener(lbClick);
        binding.lbSettings.setOnClickListener(lbClick);

        // 图层控制（复选框状态与视图双向同步，初始状态也要下发）
        binding.layerGrid.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowGrid(checked));
        binding.worldMapImage.setShowGrid(binding.layerGrid.isChecked());
        binding.layerBiome.setOnCheckedChangeListener((b, checked) -> {
            binding.worldMapImage.setShowBiomeLayer(checked);
            // v418：biome 数据延迟读（v20 拆分文件）——开启图层且
            // 内存无数据时后台读 biome 缓存
            if (checked && currentMap != null && currentWorldDir != null
                    && currentMap.chunkBiomeColors == null
                    && currentMap.cacheHasBiome) {
                final WorldMapRenderer.WorldMap fBio = currentMap;
                final File bioDb = new File(currentWorldDir, "db");
                final int bioDim = "nether".equals(mapDimension) ? 1
                        : "end".equals(mapDimension) ? 2 : 0;
                final String bioSuffix = fBio.chunkCacheSuffix != null
                        ? fBio.chunkCacheSuffix
                        : WorldMapRenderer.cacheSuffixFor(bioDim);
                executor.execute(() -> {
                    java.util.Map<Long, int[]> bm =
                            WorldMapRenderer.loadChunkBiomeCache(
                                    bioDb, bioDim, bioSuffix);
                    if (bm == null) {
                        return;
                    }
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()
                                && currentMap == fBio) {
                            fBio.chunkBiomeColors = bm;
                            binding.worldMapImage.clearChunkData();
                            binding.worldMapImage.invalidate();
                        }
                    });
                });
            }
        });
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
        // v396：忽略光源方块开关已删除（有 bug）——行为固定开启：
        // 火把/灯笼等非固体光源俯视渲染视为空气穿透（黄色杂点问题）
        WorldMapRenderer.ignoreLightBlocks = true;

        // v397：坡度阴影开关（渲染管线参数——v413 起阴影进缓存后缀
        // _ns，切换 = 换缓存直接读，不再重烘焙）
        // v420：先同步 UI 到全局静态状态——此前 XML 默认 true 而
        // 静态变量被上个存档切过 false，"开关没效果/跨存档没阴影"
        // 的根因（setChecked 若值变化会触发 reload，此时 currentWorldDir
        // 尚未设置，reload 内部守卫直接返回，无害）
        binding.layerShading.setChecked(WorldMapRenderer.enableShading);
        binding.layerShading.setOnCheckedChangeListener((b, checked) -> {
            WorldMapRenderer.enableShading = checked;
            // 重新加载当前维度（读新后缀缓存秒生效；miss 则烘焙）
            reloadMapForCurrentDimension();
        });

        // v397：结构特征检测开关（palette 猜结构可能误报）
        binding.layerStructDetect.setOnCheckedChangeListener((b, checked) -> {
            WorldMapRenderer.enableStructureDetection = checked;
            if (!checked) {
                // 清掉 palette 特征检测类结构标记（沙漠神殿/前哨站）
                synchronized (currentStructures) {
                    currentStructures.removeIf(m -> "desert_temple".equals(m.type)
                            || "outpost".equals(m.type));
                }
                binding.worldMapImage.setStructureMarkers(currentStructures);
            }
        });

        // v397：清空世界缓存（确认弹窗 → 删缓存 → 重烘焙）
        binding.btnClearCache.setOnClickListener(v -> {
            if (currentWorldDir == null) {
                return;
            }
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.btn_clear_cache))
                    .setMessage("将删除当前世界的全部渲染缓存（三个维度）并重新烘焙。存档数据不受影响。")
                    .setPositiveButton("清空", v2 -> {
                        File db = new File(currentWorldDir, "db");
                        WorldMapRenderer.deleteDimCacheFiles(db, 0);
                        WorldMapRenderer.deleteDimCacheFiles(db, 1);
                        WorldMapRenderer.deleteDimCacheFiles(db, 2);
                        invalidateRenderCacheAndBake();
                        Toast.makeText(this, "缓存已清空，正在重新烘焙",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                    .show();
        });

        // v413：矿石标点图层（烘焙/渲染 chunk 时收集矿石标记，
        // 独立落盘 ore.bin；开启显示色块标点，点击看详情）
        binding.layerOre.setOnCheckedChangeListener((b, checked) ->
                binding.worldMapImage.setShowOreLayer(checked));
        // v413：矿石标点点击 → 详情弹窗（矿石名/坐标/数量）
        binding.worldMapImage.setOnOreClickListener(m -> {
            String label = oreLabelZh(m.name);
            new CustomAlertDialog(this)
                    .setTitleText(label)
                    .setMessage("方块: " + m.name
                            + "\n坐标: X " + m.blockX
                            + (m.blockY >= 0 ? "  Y " + m.blockY : "")
                            + "  Z " + m.blockZ
                            + "（区块 " + m.chunkX + "," + m.chunkZ + "）"
                            + (m.count > 0 ? "\n该区块数量: " + m.count : "")
                            + "\n\n提示: 坐标为该矿种在区块内的首个"
                            + "位置，矿石分布在地下")
                    .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                    .show();
        });
        // v413/v417：实体点击 → 附近实体列表（密堆时弹列表；
        // 单个直接详情）
        binding.worldMapImage.setOnEntityClickListener(near -> {
            if (near.size() == 1) {
                WorldMapRenderer.EntityPos ep = near.get(0);
                new CustomAlertDialog(this)
                        .setTitleText(WorldMapView.entityLabel(ep.name))
                        .setMessage("实体: " + ep.name
                                + "\n坐标: X " + Math.round(ep.x)
                                + "  Y " + Math.round(ep.y)
                                + "  Z " + Math.round(ep.z))
                        .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                        .show();
                return;
            }
            // 密堆：列出附近实体（名称 + 坐标）
            StringBuilder sb = new StringBuilder();
            for (WorldMapRenderer.EntityPos ep : near) {
                sb.append("· ").append(WorldMapView.entityLabel(ep.name))
                        .append("（X ").append(Math.round(ep.x))
                        .append(", Z ").append(Math.round(ep.z))
                        .append("）\n");
            }
            new CustomAlertDialog(this)
                    .setTitleText("附近实体（" + near.size() + "）")
                    .setMessage(sb.toString().trim())
                    .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                    .show();
        });

        // 下界渲染层（y 轴范围）：全部/上部/中部/下部——下界 sub 0-7 每层
        // 都有方块，全量解码是下界渲染慢的主因；选窄范围大幅提速
        setupNetherYSegment();
        // 下界剔除方块黑名单
        setupNetherExclude();

        // 左栏标点搜索 → 列表过滤
        binding.pointSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderLeftPointList(s.toString().trim());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    /** 下界剔除方块黑名单（方块名多选，色块图标来自 bedrockmap 色表）。 */
    private void setupNetherExclude() {
        java.util.Set<String> saved = getSharedPreferences("nbt_viewer", MODE_PRIVATE)
                .getStringSet("nether_exclude", null);
        WorldMapRenderer.netherExcludeBlocks = saved != null
                ? new java.util.HashSet<>(saved) : null;
        binding.netherExcludeBtn.setOnClickListener(v -> showNetherExcludeDialog());
        DynamicAnim.applyPressScale(binding.netherExcludeBtn);
        refreshNetherExcludeLabel();
    }

    /** 常用下界方块（中文名 / 完整名）。 */
    private static final String[][] NETHER_BLOCKS = {
            {"基岩", "minecraft:bedrock"},
            {"下界岩", "minecraft:netherrack"},
            {"灵魂沙", "minecraft:soul_sand"},
            {"灵魂土", "minecraft:soul_soil"},
            {"玄武岩", "minecraft:basalt"},
            {"黑石", "minecraft:blackstone"},
            {"绯红菌岩", "minecraft:crimson_nylium"},
            {"诡异菌岩", "minecraft:warped_nylium"},
            {"沙砾", "minecraft:gravel"},
            {"岩浆块", "minecraft:magma"},
    };

    private void refreshNetherExcludeLabel() {
        // 精简：只显示"黑名单"字样，详情进弹窗
        binding.netherExcludeBtn.setText(getString(R.string.nether_exclude_title));
    }

    private void showNetherExcludeDialog() {
        final java.util.Set<String> sel = new java.util.HashSet<>();
        if (WorldMapRenderer.netherExcludeBlocks != null) {
            sel.addAll(WorldMapRenderer.netherExcludeBlocks);
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        panel.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        for (String[] b : NETHER_BLOCKS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (5 * d), 0, (int) (5 * d));
            // MC 原版方块贴图（assets/nether_textures/，minecraft.wiki 爬取；
            // 加载失败回退色表色块）
            ImageView icon = new ImageView(this);
            String texName = b[1].substring("minecraft:".length());
            try (java.io.InputStream in = getAssets()
                    .open("nether_textures/" + texName + ".png")) {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    icon.setImageBitmap(bmp);
                } else {
                    throw new java.io.IOException("decode fail");
                }
            } catch (Exception e) {
                icon.setImageResource(R.drawable.bg_circle);
                icon.setColorFilter(WorldMapRenderer.blockColor(b[1]));
            }
            int sp = (int) (26 * d);
            row.addView(icon, new LinearLayout.LayoutParams(sp, sp));
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(b[0]);
            cb.setTextSize(13);
            cb.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
            cb.setChecked(sel.contains(b[1]));
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    sel.add(b[1]);
                } else {
                    sel.remove(b[1]);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = (int) (10 * d);
            row.addView(cb, lp);
            panel.addView(row);
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.nether_exclude_dialog_title))
                .setCustomView(panel)
                .setPositiveButton(getString(R.string.nbt_edit_save), v2 -> {
                    // null = 从未设置（默认剔除基岩+下界岩）；
                    // 保存后按选择生效（空集 = 全不剔除）
                    WorldMapRenderer.netherExcludeBlocks = new java.util.HashSet<>(sel);
                    getSharedPreferences("nbt_viewer", MODE_PRIVATE).edit()
                            .putStringSet("nether_exclude", new java.util.HashSet<>(sel)).apply();
                    refreshNetherExcludeLabel();
                    // 当前在下界：重载（缓存带黑名单后缀，独立缓存）
                    if ("nether".equals(mapDimension) && currentWorldDir != null) {
                        loadMapForDimension("nether");
                    }
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show();
    }

    /** 后台烘焙线程（v386：无缓存时逐 chunk 补全缓存，低优先级）。 */
    private volatile Thread bakeThread;

    // v403 烘焙进度 HUD：坐标 HUD 上方显示"烘焙中 N/M 区块"
    private final java.util.concurrent.atomic.AtomicInteger bakeDoneCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile int bakeTotalCount = -1;

    /** 烘焙开始：记录总数并显示进度 HUD。 */
    private void showBakeProgress(int total) {
        if (total <= 0) {
            return;
        }
        bakeTotalCount = total;
        bakeDoneCount.set(0);
        updateBakeProgressHud();
    }

    /** 烘焙批次完成：累计进度。 */
    private void onBakeBatch(java.util.Set<Long> batch) {
        if (bakeTotalCount > 0 && batch != null && !batch.isEmpty()) {
            bakeDoneCount.addAndGet(batch.size());
            updateBakeProgressHud();
        }
    }

    /** 烘焙结束：隐藏进度 HUD。 */
    private void hideBakeProgress() {
        bakeTotalCount = -1;
        if (!isFinishing() && !isDestroyed()) {
            binding.bakeProgress.setVisibility(View.GONE);
        }
    }

    private final Runnable bakeHudCheck = () -> {
        // v420：心跳检查——烘焙线程已死但 onDone 未到（中断/异常）
        // 时进度 HUD 卡住不消失的兜底
        Thread bt = bakeThread;
        if (bt == null || !bt.isAlive()) {
            hideBakeProgress();
        }
    };

    private void updateBakeProgressHud() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        int done = bakeDoneCount.get();
        int total = bakeTotalCount;
        if (total <= 0) {
            return;
        }
        binding.bakeProgress.setVisibility(View.VISIBLE);
        binding.bakeProgress.setText("烘焙中 " + done + "/" + total + " 区块"
                + (done >= total ? " · 落盘中…" : ""));
        // 心跳：1.5 秒后检查烘焙线程存活（有 batch 时会重置）
        binding.bakeProgress.removeCallbacks(bakeHudCheck);
        binding.bakeProgress.postDelayed(bakeHudCheck, 1500);
    }

    /** v403：构造烘焙进度回调——onStart 显示进度 HUD，onBatch 累计
     *  并通知地图渐进渲染（notifyRender=false 时只计数不刷新视图）。 */
    private WorldMapRenderer.BakeProgress bakeProgressFor(
            final WorldMapRenderer.WorldMap target, final boolean notifyRender) {
        return new WorldMapRenderer.BakeProgress() {
            @Override
            public void onStart(int total) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && currentMap == target) {
                        showBakeProgress(total);
                    }
                });
            }

            @Override
            public void onBatch(java.util.Set<Long> chunkKeys) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || currentMap != target) {
                        return;
                    }
                    onBakeBatch(chunkKeys);
                    if (notifyRender) {
                        binding.worldMapImage.onChunksRendered(chunkKeys);
                    }
                });
            }
        };
    }

    /** v397：渲染参数变化（阴影开关等）——中断烘焙 + 强制重烘焙当前
     *  维度（渲染结果变了缓存作废）。
     *  v398 修复"每次打开都重新渲染"：不再清空内存缓存——旧渲染继续
     *  显示，烘焙完成后新渲染逐 chunk 覆盖。此前清空内存 → 用户没等
     *  烘焙跑完就退出 → saveChunkCache 落盘部分缓存覆盖完整缓存文件
     *  → 下次打开缓存不足 60% 又触发补缺烘焙 = 死循环。 */
    private void invalidateRenderCacheAndBake() {
        renderGen.incrementAndGet();
        if (bakeThread != null) {
            bakeThread.interrupt();
            bakeThread = null;
        }
        binding.worldMapImage.clearChunkData();
        if (currentMap != null && currentMap.chunkSourceDir != null
                && currentWorldDir != null) {
            final WorldMapRenderer.WorldMap fBake = currentMap;
            final int bakeDim = "nether".equals(mapDimension) ? 1
                    : "end".equals(mapDimension) ? 2 : 0;
            bakeThread = WorldMapRenderer.bakeWorldCache(
                    currentMap.chunkSourceDir, bakeDim, fBake,
                    bakeProgressFor(fBake, true),
                    () -> runOnUiThread(() -> {
                        hideBakeProgress();
                        if (!isFinishing() && !isDestroyed() && currentMap == fBake) {
                            binding.worldMapImage.onChunksRendered(
                                    java.util.Collections.emptySet());
                        }
                    }),
                    true);
        }
    }

    /**
     * 增量更新（v384 用户新思路"专门存地图数据的地方"）：缓存命中秒开后，
     * 后台对比 db 文件指纹（sst 不可变，存档更新 = 新增文件/追加 log），
     * 只重渲染变化文件覆盖的 chunk，完成后合并保存——用户玩过存档后
     * 打开地图不用全量重渲染。
     */
    private void startIncrementalUpdate(File dbDir, WorldMapRenderer.WorldMap map,
                                        int dim) {
        if (map == null || map.chunkColors == null || dbDir == null) {
            return;
        }
        executor.execute(() -> {
            java.util.Set<String> changed = WorldMapRenderer.diffDbFiles(dbDir, map);
            if (changed.isEmpty()) {
                Log.i(TAG, "增量更新: db 无变化 (dim=" + dim + ")");
                return;
            }
            java.util.Set<Long> chunks =
                    WorldMapRenderer.collectChangedChunks(dbDir, changed, dim);
            if (chunks.isEmpty()) {
                return;
            }
            Log.i(TAG, "增量更新: " + changed.size() + " 个新文件, "
                    + chunks.size() + " 个变化 chunk (dim=" + dim + ")");
            try {
                LevelDBReader reader = new LevelDBReader(dbDir);
                int done = WorldMapRenderer.refreshChangedChunks(
                        reader, dbDir, map, chunks, dim);
                reader.close();
                if (done > 0) {
                    map.chunkCacheSuffix = WorldMapRenderer.cacheSuffixFor(dim);
                    // 增量更新完成后缓存仍完整
                    map.cacheComplete = true;
                    WorldMapRenderer.saveChunkCache(map, dbDir, dim, true);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && currentMap == map) {
                            binding.worldMapImage.onChunksRendered(
                                    java.util.Collections.emptySet());
                        }
                    });
                }
                Log.i(TAG, "增量更新完成: " + done + " chunk (dim=" + dim + ")");
            } catch (Exception e) {
                Log.w(TAG, "增量更新失败", e);
            }
        });
    }

    /** 下界渲染层分段选择：全部/上部(y64-127)/中部(y32-95)/下部(y0-63)。 */
    private void setupNetherYSegment() {
        final String PREFS = "nether_render_y";
        int saved = getSharedPreferences("nbt_viewer", MODE_PRIVATE).getInt(PREFS, 0);
        applyNetherY(saved);
        View.OnClickListener segClick = v -> {
            int mode;
            if (v == binding.netherYAll) mode = 0;
            else if (v == binding.netherYTop) mode = 1;
            else if (v == binding.netherYMid) mode = 2;
            else mode = 3;
            applyNetherY(mode);
            getSharedPreferences("nbt_viewer", MODE_PRIVATE).edit().putInt(PREFS, mode).apply();
            // 当前在下界：重载（缓存文件名带 y 参数后缀，各设置独立缓存，
            // 切换回来命中旧缓存不用重渲染）
            if ("nether".equals(mapDimension) && currentWorldDir != null) {
                loadMapForDimension("nether");
            }
        };
        binding.netherYAll.setOnClickListener(segClick);
        binding.netherYTop.setOnClickListener(segClick);
        binding.netherYMid.setOnClickListener(segClick);
        binding.netherYBottom.setOnClickListener(segClick);
        for (TextView t : new TextView[]{binding.netherYAll, binding.netherYTop,
                binding.netherYMid, binding.netherYBottom}) {
            DynamicAnim.applyPressScale(t);
        }
    }

    /** 应用下界 y 范围并刷新分段高亮（个性化主题色贯通）。 */
    private void applyNetherY(int mode) {
        switch (mode) {
            case 1: WorldMapRenderer.netherYMin = 64; WorldMapRenderer.netherYMax = 127; break;
            case 2: WorldMapRenderer.netherYMin = 32; WorldMapRenderer.netherYMax = 95; break;
            case 3: WorldMapRenderer.netherYMin = 0; WorldMapRenderer.netherYMax = 63; break;
            default: WorldMapRenderer.netherYMin = -1; WorldMapRenderer.netherYMax = -1; break;
        }
        int active = accentColor != 0 ? accentColor
                : ContextCompat.getColor(this, R.color.primary);
        int inactive = ContextCompat.getColor(this, R.color.text_secondary);
        TextView[] segs = {binding.netherYAll, binding.netherYTop,
                binding.netherYMid, binding.netherYBottom};
        for (int i = 0; i < segs.length; i++) {
            boolean sel = i == mode;
            segs[i].setBackgroundResource(sel ? R.drawable.bg_tab_selected
                    : R.drawable.bg_tab_unselected);
            segs[i].setTextColor(sel ? active : inactive);
        }
    }

    /** 复位左栏图标条高亮（ViewBinding 对旧 id 推断为 View，运行时实为 ImageView）。 */
    private void resetLbTints(int inactiveColor) {
        ((android.widget.ImageView) binding.lbInfo).setColorFilter(inactiveColor);
        ((android.widget.ImageView) binding.lbLayers).setColorFilter(inactiveColor);
        ((android.widget.ImageView) binding.lbPoints).setColorFilter(inactiveColor);
        binding.lbSettings.setColorFilter(inactiveColor);
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
            // 加号 → × 旋转动画（打开转 45°，收起转回）
            binding.mapFab.animate().rotation(toolMenuOpen ? 45f : 0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
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
            if (mapPoints.isEmpty()) {
                Toast.makeText(this, R.string.no_points, Toast.LENGTH_SHORT).show();
                return;
            }
            if (mapPoints.size() < 2) {
                // 有标点但不足 2 个：与"暂无标点"区分提示，避免误读成数据丢失
                Toast.makeText(this, R.string.point_need_two, Toast.LENGTH_SHORT).show();
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
            if (mapPoints.isEmpty()) {
                Toast.makeText(this, R.string.no_points, Toast.LENGTH_SHORT).show();
                return;
            }
            if (mapPoints.size() < 2) {
                Toast.makeText(this, R.string.point_need_two, Toast.LENGTH_SHORT).show();
                return;
            }
            rulerModeActive = true;
            rulerFrom = null;
            linkModeActive = false;
            linkModeFrom = null;
            Toast.makeText(this, R.string.ruler_pick_first, Toast.LENGTH_SHORT).show();
        });
        // 标点列表（强制刷新渲染，避免抽屉停留旧列表）
        binding.toolPointList.setOnClickListener(v -> {
            closeToolMenu();
            currentLbTab = binding.lbPoints;
            binding.leftbarBody.setVisibility(View.VISIBLE);
            binding.tabInfo.setVisibility(View.GONE);
            binding.tabLayers.setVisibility(View.GONE);
            binding.tabPoints.setVisibility(View.VISIBLE);
            binding.tabSettings.setVisibility(View.GONE);
            renderLeftPointList(null);
        });
        // 导出世界为 HTML（PRD 7.4：Leaflet 交互式地图，单文件）
        binding.toolBlueprint.setOnClickListener(v -> {
            closeToolMenu();
            exportWorldHtmlAsync();
        });
        // 3D 体素视图（BedrockMap voxel 同款交互的 Canvas 最小实现）
        binding.toolVoxel.setOnClickListener(v -> {
            closeToolMenu();
            showVoxelDialog();
        });

        // 维度切换
        setupDimensionSwitch();

        // 坐标 HUD：缩放/平移时更新中心坐标 + 缩放倍率
        binding.worldMapImage.setOnViewChangedListener((cx, cz) -> {
            viewCenterX.set(cx);
            viewCenterZ.set(cz);
            binding.mapHud.setText("X: " + cx + "  Z: " + cz
                    + "  ·  " + dimName(mapDimension)
                    + "  ·  ×" + String.format(java.util.Locale.getDefault(),
                    "%.2f", binding.worldMapImage.getPixelsPerBlock()));
        });

        // 结构标记点击 → 详情弹窗（NBT 数据/附近实体/坐标）
        binding.worldMapImage.setOnStructureClickListener(this::showStructureDetail);

        // 3D 区域拖选（BedrockMap 右键拖选同款）：松手回调区域 → 渲染 3D
        binding.worldMapImage.setOnRegionSelectListener((minX, minZ, maxX, maxZ) -> {
            int w = maxX - minX + 1;
            int h = maxZ - minZ + 1;
            int side = Math.max(2, Math.min(32, Math.max(w, h)));
            int cx = (minX + maxX) / 2;
            int cz = (minZ + maxZ) / 2;
            startVoxelRender(cx, cz, side);
        });

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
                // 单击显示该处坐标（十字标记在地图上，HUD 同步显示；
                // v403 恢复缩放倍率显示）
                binding.mapHud.setText("X: " + blockX + "  Z: " + blockZ
                        + "  ·  " + dimName(mapDimension)
                        + "  ·  ×" + String.format(java.util.Locale.getDefault(),
                        "%.2f", binding.worldMapImage.getPixelsPerBlock()));
            }
        });
    }

    private void closeToolMenu() {
        toolMenuOpen = false;
        binding.mapToolMenu.setVisibility(View.GONE);
        // 收起时 × 转回加号（若动画中途打断，直接复位）
        binding.mapFab.animate().rotation(0f).setDuration(180).start();
    }

    /** 维度切换：高亮当前维度、刷新地图数据与标点渲染。 */
    private void setupDimensionSwitch() {
        View.OnClickListener dimClick = v -> {
            String dim;
            if (v == binding.worldDimOverworld) dim = "overworld";
            else if (v == binding.worldDimNether) dim = "nether";
            else dim = "end";
            switchToDimension(dim);
        };
        binding.worldDimOverworld.setOnClickListener(dimClick);
        binding.worldDimNether.setOnClickListener(dimClick);
        binding.worldDimEnd.setOnClickListener(dimClick);
        DynamicAnim.applyPressScale(binding.worldDimOverworld);
        DynamicAnim.applyPressScale(binding.worldDimNether);
        DynamicAnim.applyPressScale(binding.worldDimEnd);
        // 标题 = 维度切换：点击循环切换（主世界→下界→末地）
        binding.nbtTitle.setOnClickListener(v -> {
            String next;
            if ("overworld".equals(mapDimension)) next = "nether";
            else if ("nether".equals(mapDimension)) next = "end";
            else next = "overworld";
            switchToDimension(next);
        });
        DynamicAnim.applyPressScale(binding.nbtTitle);
    }

    /** 统一维度切换：按钮高亮 + 标题显示当前维度名 + 重载地图。 */
    private void switchToDimension(String dim) {
        mapDimension = dim;
        int active = accentColor != 0 ? accentColor
                : getResources().getColor(R.color.primary, getTheme());
        int inactive = getResources().getColor(R.color.text_secondary, getTheme());
        binding.worldDimOverworld.setTextColor("overworld".equals(dim) ? active : inactive);
        binding.worldDimNether.setTextColor("nether".equals(dim) ? active : inactive);
        binding.worldDimEnd.setTextColor("end".equals(dim) ? active : inactive);
        String name = "overworld".equals(dim) ? getString(R.string.dim_overworld)
                : "nether".equals(dim) ? getString(R.string.dim_nether)
                : getString(R.string.dim_end);
        binding.nbtTitle.setText(name);
        // 下界专属设置（渲染层 y 范围 + 黑名单）只在切到下界时显示
        if (binding.netherSettingsGroup != null) {
            binding.netherSettingsGroup.setVisibility(
                    "nether".equals(dim) ? View.VISIBLE : View.GONE);
        }
        binding.worldMapImage.setDimension(dim);
        loadMapForDimension(dim);
    }

    /** v413：阴影开关等渲染参数变化后重载当前维度（缓存后缀变了
     *  → 换缓存直接读秒生效；miss 则烘焙。比 force 重烘焙快得多，
     *  且旧阴影渲染立即消失）。 */
    private void reloadMapForCurrentDimension() {
        if (currentWorldDir == null || mapDimension == null) {
            return;
        }
        // 保留当前视图（缩放/位置不跳）
        loadMapForDimension(mapDimension, true);
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
        // 切维度前保存当前维度渲染缓存——否则刚渲染的 chunk 只存在内存，
        // 切回来缓存是旧的，又要重新渲染（下界切换慢的帮凶）。
        // 必须异步：大世界缓存可达几十 MB，UI 线程同步写 2-5 秒会让旧图
        // 一直留在屏幕上（"切下界渲染的却是主世界图"的根因）
        WorldMapRenderer.WorldMap oldMap = currentMap;
        if (oldMap != null && oldMap.chunkColors != null && !oldMap.chunkColors.isEmpty()
                && currentWorldDir != null) {
            final WorldMapRenderer.WorldMap toSave = oldMap;
            final File saveDb = new File(currentWorldDir, "db");
            // 维度必须取 toSave 自己的 chunkSourceDim——switchToDimension 已把
            // mapDimension 改成新维度，用它算会把主世界图存进下界缓存文件
            // （"切下界显示主世界图、颜色错乱"的根因）
            final int saveDim = toSave.chunkSourceDim >= 0 ? toSave.chunkSourceDim : 0;
            // 保持读入时的完整性标志（完整缓存切维度后仍是完整）
            final boolean saveComplete = toSave.cacheComplete;
            new Thread(() -> WorldMapRenderer.saveChunkCache(
                    toSave, saveDb, saveDim, saveComplete),
                    "cache-save").start();
        }
        // 维度隔绝：切换时立刻清掉旧维度地图与图层——否则新图渲染完成前
        // 旧图一直显示（"切下界先看到主世界，过一会才跳过去"的根因）
        currentMap = null;
        binding.worldMapImage.setWorldMap(null);
        binding.worldMapImage.setEntityData(new ArrayList<>());
        binding.worldMapImage.setStructureMarkers(new ArrayList<>());
        binding.worldMapPlaceholder.setVisibility(View.VISIBLE);
        // v403：旧维度烘焙进度 HUD 重置
        hideBakeProgress();
        // 渲染代际 + 线程池换新：旧维度延迟实体/结构解析任务作废
        renderGen.incrementAndGet();
        // 烘焙线程换维度时中断（新维度有自己的烘焙）
        if (bakeThread != null) {
            bakeThread.interrupt();
            bakeThread = null;
        }
        ExecutorService oldPool = renderPool;
        renderPool = newRenderPool();
        oldPool.shutdownNow();
        startBackgroundTask();
        final int gen = loadGeneration;
        final boolean fKeepView = keepView;
        executor.execute(() -> {
            File dbDir = new File(currentWorldDir, "db");
            // 缓存统一走应用私有目录（v7 起不再写世界目录——旧版写世界
            // 目录每次编辑膨胀 100+MB），打开时清掉世界目录遗留旧缓存
            WorldMapRenderer.initCacheDir(getApplicationContext());
            if (dbDir.getParentFile() != null) {
                WorldMapRenderer.cleanupLegacyWorldCache(dbDir);
                WorldMapRenderer.migrateLegacyCache(dbDir);
            }
            WorldMapRenderer.WorldMap worldMap = null;
            List<WorldMapRenderer.EntityPos> entities = null;
            List<WorldMapRenderer.StructureMarker> structures = null;
            // v394：下界切段 fallback——新段缓存 miss 时先显示"全部"段
            // 的缓存图（立即有图），新段后台烘焙完成后自动切换。
            // 声明在 if 块外（完成回调 runOnUiThread 在块外引用）
            final WorldMapRenderer.WorldMap[] fallbackMap = {null};
            if (dbDir.isDirectory()) {
                int dimId = "nether".equals(dim) ? 1 : "end".equals(dim) ? 2 : 0;
                long dbSize = dbSizeBytes(dbDir);
                if (dbSize > 20 * 1024 * 1024) {
                    // 大世界：与首次打开同款的快速按需路径——磁盘缓存/bounds
                    // 扫描秒进地图，视口按需渲染。此前此处是全量流式渲染
                    // 30-60 秒（"渲染完全部区块才显示"的根因）
                    Log.i(TAG, "大世界按需渲染(切维度) dbSize=" + dbSize);
                    worldMap = WorldMapRenderer.loadChunkCache(dbDir, dimId);
                    if (worldMap == null && dimId == 1) {
                        fallbackMap[0] = WorldMapRenderer.loadChunkCacheWithSuffix(
                                dbDir, dimId,
                                WorldMapRenderer.netherYallCacheSuffix());
                        if (fallbackMap[0] != null) {
                            worldMap = fallbackMap[0];
                            Log.i(TAG, "下界切段 fallback: 先显示全部段缓存图");
                        }
                    }
                    if (worldMap == null) {
                        worldMap = WorldMapRenderer.buildBoundsOnly(dbDir, dimId);
                    }
                    if (worldMap != null) {
                        worldMap.chunkSourceDir = dbDir;
                        worldMap.chunkSourceDim = dimId;
                        // 渲染参数后缀定格在 map 创建时——保存缓存用它
                        // （切段时全局参数已改成新段）
                        worldMap.chunkCacheSuffix =
                                WorldMapRenderer.cacheSuffixFor(dimId);
                    }
                    entities = new java.util.ArrayList<>();
                    structures = new java.util.ArrayList<>();
                    // 实体/结构解析延迟 6 秒并行（与首屏视口渲染错峰，
                    // 否则同步全量读 183MB db 拖慢切维度 20-30 秒）
                    final WorldMapRenderer.WorldMap fMapL = worldMap;
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
                        if (fMapL != null && fMapL.detectedStructures != null) {
                            fStrs.addAll(fMapL.detectedStructures);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || !isCurrentLoad(gen)) {
                                return;
                            }
                            binding.worldMapImage.setEntityData(fEnts);
                            synchronized (currentStructures) {
                                currentStructures.clear();
                                currentStructures.addAll(fStrs);
                                mergeOnDemandStructures();
                            }
                            binding.worldMapImage.setStructureMarkers(currentStructures);
                            refreshDataPanelExtras(fStrs, null);
                        });
                    };
                    flushHandler.postDelayed(() -> {
                        renderPool.execute(() -> {
                            CpuScheduler.pinCurrentThreadToBigCores();
                            ents[0] = WorldMapRenderer.parseEntitiesStreaming(dbDir, dimId);
                            deliver.run();
                        });
                        renderPool.execute(() -> {
                            CpuScheduler.pinCurrentThreadToBigCores();
                            strs[0] = WorldMapRenderer.parseStructureMarkersStreaming(dbDir, dimId);
                            deliver.run();
                        });
                    }, 6000);
                } else {
                    // 小世界切维度：优先读缓存（v390），miss 才全量渲染+存缓存
                    try {
                        worldMap = WorldMapRenderer.loadSmallMapCache(dbDir, dimId);
                    } catch (Exception ignored) {
                        worldMap = null;
                    }
                    if (worldMap == null) {
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
                            if (worldMap != null) {
                                WorldMapRenderer.saveSmallMapCache(worldMap, dbDir, dimId);
                            }
                            entities = WorldMapRenderer.parseEntities(entries, dimId);
                            structures = WorldMapRenderer.parseStructureMarkers(entries, dimId);
                        }
                    } else {
                        entities = new java.util.ArrayList<>();
                        structures = new java.util.ArrayList<>();
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
                    // 恢复 currentMap（loadMapForDimension 开头置 null 清旧图，
                    // 完成回调此前只 setWorldMap 不恢复——onChunksNeededListener
                    // 和 startPrerender 检查 currentMap==null 直接 return，
                    // 切维度后视口按需渲染/预渲染全不工作的根因）
                    currentMap = fMap;
                    // v403：主世界打开 fit 全图（"大的缩放比例"），
                    // 下界/末地保持放大起步（v380 教训：fit 进 LOD 黑屏）
                    binding.worldMapImage.initialFitAll =
                            "overworld".equals(mapDimension);
                    binding.worldMapImage.setWorldMap(fMap, fKeepView);
                    // 不 fitToView：fit 后下界 ×0.44 缩放太小（视口 chunk
                    // >4096 进 LOD、网格不画），进图一片黑像没渲染（v380
                    // fit 改动被用户要求回退——与主世界一致走 initialView
                    // 默认放大 20px/block，渲染从玩家/出生点周边渐进）
                    binding.worldMapImage.setEntityData(fEntities);
                    binding.worldMapImage.setStructureMarkers(fStructures);
                    binding.worldMapPlaceholder.setVisibility(View.GONE);
                    refreshMapBlueprintData();
                    // 下界/末地自动启动流式全量渲染（v373 切维度行为）：
                    // 后台渐进合并（onChunkData 节流通知 → LOD 增量更新），
                    // 完成后落盘缓存，下次切维度直接读缓存秒开。
                    // 缓存 chunk 数不足 bounds 应有数 60% 时也补渲染
                    // （末地 122/1260 的坏缓存命中后外岛永远缺失的根因）。
                    // 主世界不自动渲染：18 万条目 ~300MB 叠加视口渲染+
                    // 实体解析并行，512MB heap 必 OOM（v382 实测崩溃，
                    // tombstone OutOfMemoryError）——主世界走视口按需+LOD
                    if (!"overworld".equals(mapDimension)) {
                        // 下界/末地切维度：缓存不足时自动烘焙（v388 起与
                        // 主世界统一机制——替代已删除的流式预渲染；共享
                        // fMap 圆形铺开 + 落盘缓存）
                        // v19：完整性用缓存头部标志（bounds 面积×60% 对
                        // 稀疏世界永远"不足"——TK 实际 24844 chunk 只占
                        // 外包矩形 2.5%，误判会空转烘焙）
                        boolean cacheInsufficient = !fMap.cacheComplete;
                        final int bakeDim = "nether".equals(mapDimension) ? 1 : 2;
                        if (fMap.chunkColors != null && fMap.chunkSourceDir != null
                                && (fMap.chunkColors.isEmpty() || cacheInsufficient)) {
                            if (fallbackMap[0] != null) {
                                // 切段 fallback 场景：显示"全部"段旧图，
                                // 烘焙用独立新 map（新段渲染窗口不同），
                                // 第一批 chunk 完成后切换显示到新 map
                                final WorldMapRenderer.WorldMap fFallback =
                                        fallbackMap[0];
                                WorldMapRenderer.WorldMap bakeMap =
                                        WorldMapRenderer.buildBoundsOnly(
                                                fMap.chunkSourceDir, bakeDim);
                                if (bakeMap != null) {
                                    bakeMap.chunkSourceDir = fMap.chunkSourceDir;
                                    bakeMap.chunkSourceDim = bakeDim;
                                    bakeMap.chunkCacheSuffix =
                                            WorldMapRenderer.cacheSuffixFor(bakeDim);
                                    final WorldMapRenderer.WorldMap fBakeMap = bakeMap;
                                    final boolean[] switched = {false};
                                    bakeThread = WorldMapRenderer.bakeWorldCache(
                                            fMap.chunkSourceDir, bakeDim, fBakeMap,
                                            new WorldMapRenderer.BakeProgress() {
                                                @Override
                                                public void onStart(int total) {
                                                    runOnUiThread(() -> {
                                                        if (isFinishing() || isDestroyed()
                                                                || renderGen.get() != gen) {
                                                            return;
                                                        }
                                                        showBakeProgress(total);
                                                    });
                                                }

                                                @Override
                                                public void onBatch(java.util.Set<Long> batch) {
                                                    runOnUiThread(() -> {
                                                        if (isFinishing() || isDestroyed()
                                                                || renderGen.get() != gen) {
                                                            return;
                                                        }
                                                        onBakeBatch(batch);
                                                        if (!switched[0]) {
                                                            // 第一批数据就绪：切到新段视图
                                                            switched[0] = true;
                                                            currentMap = fBakeMap;
                                                            binding.worldMapImage.setWorldMap(fBakeMap);
                                                        }
                                                        binding.worldMapImage.onChunksRendered(batch);
                                                    });
                                                }
                                            },
                                            () -> runOnUiThread(() -> {
                                                hideBakeProgress();
                                                if (isFinishing() || isDestroyed()
                                                        || renderGen.get() != gen) {
                                                    return;
                                                }
                                                if (switched[0]) {
                                                    binding.worldMapImage.onChunksRendered(
                                                            java.util.Collections.emptySet());
                                                }
                                            }));
                                }
                            } else {
                                final WorldMapRenderer.WorldMap fBake = fMap;
                                bakeThread = WorldMapRenderer.bakeWorldCache(
                                        fMap.chunkSourceDir, bakeDim, fBake,
                                        bakeProgressFor(fBake, true),
                                        () -> runOnUiThread(() -> {
                                            hideBakeProgress();
                                            if (!isFinishing() && !isDestroyed()
                                                    && currentMap == fBake) {
                                                binding.worldMapImage.onChunksRendered(
                                                        java.util.Collections.emptySet());
                                            }
                                        }));
                            }
                        } else if (fMap.chunkColors != null && !fMap.chunkColors.isEmpty()
                                && fMap.chunkSourceDir != null) {
                            // 缓存命中：增量更新（存档玩过后只重渲染变化 chunk）
                            startIncrementalUpdate(fMap.chunkSourceDir, fMap, bakeDim);
                        }
                    }
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
        skeletonShown = false;
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

            // v418：WorldItem 构造并行（读 level.dat + db 玩家状态
            // ~200-300ms——不阻塞地图首屏；onDataLoaded 前 join）
            final WorldItem[] wiRef = new WorldItem[1];
            Thread wiThread = new Thread(() -> {
                try {
                    wiRef[0] = new WorldItem(worldName != null ? worldName
                            : worldDir.getName(), worldDir);
                } catch (Throwable ignored) {
                }
            }, "world-item-load");
            wiThread.start();

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
            // 缓存统一走应用私有目录（v7），清掉世界目录遗留旧缓存
            WorldMapRenderer.initCacheDir(getApplicationContext());
            WorldMapRenderer.cleanupLegacyWorldCache(dbDir);
            // v395：旧扁平缓存迁移到新目录结构（<世界>/<维度>/）
            WorldMapRenderer.migrateLegacyCache(dbDir);
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
            } else if (dbDir.isDirectory()) {
                // v411：面积巨大但 db 小的稀疏世界（玩家跑图跑得很远）
                // ——小世界路径 assembleMap 全图数组按面积分配：6686×
                // 6686 方块要 178MB，进程 536MB 上限直接 OOM（实测
                // 用户导入 17 存档崩溃）。面积 > 1000 万方块也走大
                // 世界 chunk 路径（bounds 缓存 16 字节快读）
                WorldMapRenderer.WorldMap probe =
                        WorldMapRenderer.buildBoundsOnly(dbDir, 0);
                if (probe != null) {
                    long area = (long) probe.width * probe.height;
                    if (area > 10_000_000L) {
                        largeWorld = true;
                        Log.i(TAG, "面积巨大走大世界路径: " + probe.width
                                + "x" + probe.height + " dbSize="
                                + dbSizeBytes(dbDir));
                    }
                }
            }
            if (dbDir.isDirectory() && largeWorld) {
                // 大世界：v418 两阶段打开——阶段 1 先显 bounds 骨架
                // （16 字节缓存秒读 + 关 loading，用户要求"进去 0.8 秒
                // 内要显示"）；阶段 2 完整缓存解码后替换（保留视图）
                WorldMapRenderer.WorldMap skeleton =
                        WorldMapRenderer.buildBoundsOnly(dbDir, 0);
                if (skeleton != null) {
                    // v420：骨架带出生点（level.dat 顶层）——打开
                    // 初始视图直接定位出生点，不再停在空洞/未生成
                    // 区块（"TK 打开默认到没渲染的区块"的根因）
                    if (root != null) {
                        NbtTag sx = root.getTag("SpawnX");
                        NbtTag sz = root.getTag("SpawnZ");
                        if (sx != null && sz != null) {
                            skeleton.spawnBlockX = sx.getInt();
                            skeleton.spawnBlockZ = sz.getInt();
                        }
                    }
                    final WorldMapRenderer.WorldMap fSkeleton = skeleton;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()
                                || !isCurrentLoad(gen)) {
                            return;
                        }
                        skeletonShown = true;
                        binding.nbtLoading.setVisibility(View.GONE);
                        binding.worldMapPlaceholder.setVisibility(View.GONE);
                        binding.worldMapImage.initialFitAll = false;
                        binding.worldMapImage.setWorldMap(fSkeleton);
                    });
                }
                // 阶段 2：完整缓存解码（1-2 秒，完成后 onDataLoaded 替换）
                worldMap = WorldMapRenderer.loadChunkCache(dbDir, 0);
                if (worldMap == null) {
                    worldMap = skeleton;
                }
                if (worldMap != null) {
                    worldMap.chunkSourceDir = dbDir;
                    worldMap.chunkSourceDim = 0;
                    worldMap.chunkCacheSuffix = WorldMapRenderer.cacheSuffixFor(0);
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
                            synchronized (currentStructures) {
                                currentStructures.clear();
                                currentStructures.addAll(fStrs);
                                // 合并视口按需渲染检测到的结构标记（沙漠神殿/前哨站
                                // 等无 key 结构靠 palette 特征检测）
                                mergeOnDemandStructures();
                            }
                            binding.worldMapImage.setStructureMarkers(currentStructures);
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
                // 小世界优先读磁盘缓存（v390：第二次打开秒开，不再每次
                // 全量渲染 8-20 秒）。db 变化（玩家玩过一局）时先显示
                // 旧图（v393），后台重渲染后替换——不再每次都卡
                // "地图渲染中"
                try {
                    worldMap = WorldMapRenderer.loadSmallMapCache(dbDir, 0, true);
                } catch (Exception ignored) {
                    worldMap = null;
                }
                if (worldMap == null) {
                    // 世界地图：BTR 卫星模式（方块颜色 + 坡度阴影），同步全量渲染
                    // （v380 秒进路径视口按需渲染首屏无渲染，回滚 v372 全量渲染代码）
                    try {
                        worldMap = WorldMapRenderer.buildSatelliteMap(entries);
                        Log.i(TAG, "卫星地图完成: " + (worldMap != null
                                ? worldMap.width + "x" + worldMap.height
                                : "失败(null)"));
                    } catch (Exception e) {
                        Log.i(TAG, "卫星地图渲染异常", e);
                    }
                    if (worldMap != null) {
                        WorldMapRenderer.saveSmallMapCache(worldMap, dbDir, 0);
                    }
                } else if (worldMap.cacheStale) {
                    // 旧图已加载（立即显示）：后台全量重渲染 + 保存 + 替换
                    final File fDbDir = dbDir;
                    final List<LevelDBEntry> fEntries = entries;
                    final WorldMapRenderer.WorldMap fOld = worldMap;
                    executor.execute(() -> {
                        try {
                            WorldMapRenderer.WorldMap fresh =
                                    WorldMapRenderer.buildSatelliteMap(fEntries);
                            if (fresh != null) {
                                WorldMapRenderer.saveSmallMapCache(fresh, fDbDir, 0);
                                runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed()
                                            && currentMap == fOld) {
                                        currentMap = fresh;
                                        // v421：keepView——后台刷新替换
                                        // 保留当前视图（此前不带 keepView
                                        // → initialView 重置跳回出生点，
                                        // "缩放滑到别处一松手就跳屏"的根因）
                                        binding.worldMapImage.setWorldMap(fresh, true);
                                        Log.i(TAG, "小世界旧图已刷新: "
                                                + fresh.width + "x" + fresh.height);
                                    }
                                });
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "小世界后台重渲染失败", e);
                        }
                    });
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
                            if (worldMap != null) {
                                WorldMapRenderer.saveSmallMapCache(worldMap, dbDir, 0);
                            }
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
            // （大世界 entries 为空列表，此处只处理小世界；大世界玩家位置
            // 用 level.dat 出生点）
            if (worldMap != null && entries != null) {
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
                            float py = posTag.getList().get(1).getFloat();
                            float pz = posTag.getList().get(2).getFloat();
                            // 合理世界范围（±3000 万方块）内才认定是玩家位置
                            if (Math.abs(px) < 3e7f && Math.abs(pz) < 3e7f) {
                                worldMap.playerBlockX = (int) Math.floor(px);
                                worldMap.playerBlockY = (int) Math.floor(py);
                                worldMap.playerBlockZ = (int) Math.floor(pz);
                                // 降采样地图：玩家标记坐标 ÷blockScale
                                if (worldMap.blockScale > 1) {
                                    worldMap.playerBlockX = Math.floorDiv(worldMap.playerBlockX, worldMap.blockScale);
                                    worldMap.playerBlockZ = Math.floorDiv(worldMap.playerBlockZ, worldMap.blockScale);
                                }
                                NbtTag uid = playerRoot.getTag("UniqueID");
                                if (uid != null) {
                                    worldMap.playerUniqueId = uid.getLong();
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

            // v418：等并行构造的 WorldItem（此时地图已加载完，最多再等 800ms）
            try {
                wiThread.join(800);
            } catch (InterruptedException ignored) {
            }
            worldItem = wiRef[0];
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
            // v418：骨架已显示则保留视图（不跳不闪）
            binding.worldMapImage.initialFitAll = false;
            binding.worldMapImage.setWorldMap(worldMap, skeletonShown);
            binding.worldMapPlaceholder.setVisibility(View.GONE);
            // 主世界打开不自动预渲染：流式渲染 18 万条目（subchunk value
            // ~300MB）叠加视口按需渲染+实体解析，536MB heap 直接 OOM
            // （v382 实测"预渲染失败 OutOfMemoryError"）。主世界视口按需
            // 渲染 + LOD 已覆盖；全图预渲染只用于切维度自动（下界/末地
            // 数据量小）
            // 缓存完整命中：后台增量更新——存档玩过之后只重渲染变化文件
            // 覆盖的 chunk。v19：完整性用缓存头部标志（bounds 面积×60%
            // 对稀疏世界永远"不足"——TK 实际 24844 chunk 只占外包
            // 矩形 2.5%，误判导致每次打开都补缺烘焙"重新渲染"）
            boolean cacheLacking = !worldMap.cacheComplete;
            if (worldMap.chunkColors != null && !worldMap.chunkColors.isEmpty()
                    && !cacheLacking && worldMap.chunkSourceDir != null) {
                startIncrementalUpdate(worldMap.chunkSourceDir, worldMap, 0);
            } else if (worldMap.chunkColors != null && worldMap.chunkSourceDir != null) {
                // 无缓存或缓存不足（烘焙曾中断/只烘了一半）：后台
                // 多线程烘焙补全。共享屏幕 map——烘焙的 chunk 直接进屏幕，
                // 圆形铺开效果（距离排序从原点向外 + 每批 50 chunk 通知 UI）
                final WorldMapRenderer.WorldMap fBake = worldMap;
                bakeThread = WorldMapRenderer.bakeWorldCache(
                        worldMap.chunkSourceDir, 0, fBake,
                        bakeProgressFor(fBake, true),
                        () -> runOnUiThread(() -> {
                            hideBakeProgress();
                            if (!isFinishing() && !isDestroyed() && currentMap == fBake) {
                                binding.worldMapImage.onChunksRendered(
                                        java.util.Collections.emptySet());
                            }
                        }));
            }
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
            // 玩家坐标（db ~local_player Pos，xyz）
            if (worldMap != null && worldMap.playerBlockX >= 0) {
                binding.infoPos.setText(getString(R.string.nbt_summary_pos,
                        worldMap.playerBlockX,
                        worldMap.playerBlockY >= 0 ? worldMap.playerBlockY : 0,
                        worldMap.playerBlockZ));
            } else {
                binding.infoPos.setText(getString(R.string.nbt_summary_pos, 0, 0, 0));
            }
            // UUID = local_player UniqueID（Bedrock 存档无 Xbox XUID 字段；
            // 部分存档高位 0xFF 填充，负数时取低 32 位无符号有效值）
            long uidVal = worldMap != null ? worldMap.playerUniqueId : -1;
            if (uidVal < 0 && uidVal != -1) {
                uidVal = uidVal & 0xFFFFFFFFL;
            }
            binding.infoUuid.setText(getString(R.string.nbt_summary_uuid, uidVal));
            // 游戏版本（level.dat LastOpenedWithVersion——直接用 root 参数：
            // levelDatRoot 字段在本方法后面才赋值，读字段会拿到上一次的值/null）
            binding.infoVersion.setText(getString(R.string.nbt_summary_version,
                    readVersionFromRoot(root)));
            // 存档大小：后台递归计算（大世界 db 文件多，UI 线程会卡）
            binding.infoSize.setText(getString(R.string.nbt_summary_size, "…"));
            final File sizeDir = currentWorldDir;
            executor.execute(() -> {
                String sizeStr = formatBytes(dirSizeRecursive(sizeDir));
                runOnUiThread(() -> {
                    if (currentWorldDir == sizeDir && !isFinishing()) {
                        binding.infoSize.setText(getString(R.string.nbt_summary_size, sizeStr));
                    }
                });
            });
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

        // b) db 条目（v374 小世界秒进：entries 延迟后台解析，此处可能为 null）
        dbEntries.clear();
        if (entries != null) {
            dbEntries.addAll(entries);
        }
        dbAdapter.notifyDataSetChanged();
        int entryCount = entries != null ? entries.size() : 0;
        binding.nbtTabDb.setText(getString(R.string.nbt_db_entries) + " (" + entryCount + ")");
        if (entryCount == 0) {
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
        // （v378 小世界全量回填后 currentMap 是 colors 数组路径 map，
        // 其 chunkColors 为 null——判空保护，否则 onDestroy NPE 崩溃）。
        // 所有维度缓存都持久保存（v384：撤销 v382 的退出删除——用户
        // 新思路"专门存地图数据的地方，打开瞬间读，存档更新时更新"）
        if (currentMap != null && currentMap.chunkSourceDir != null
                && currentMap.chunkColors != null
                && !currentMap.chunkColors.isEmpty()) {
            // v19：保持 map 的完整性标志（完整缓存退出后再存仍是完整）
            WorldMapRenderer.saveChunkCache(currentMap, currentMap.chunkSourceDir,
                    currentMap.chunkSourceDim, currentMap.cacheComplete);
            // v413：矿石标点一起落盘（烘焙未跑完时保留已收集部分）
            WorldMapRenderer.saveOreMarkers(currentMap,
                    currentMap.chunkSourceDir, currentMap.chunkSourceDim);
        }
        if (bakeThread != null) {
            bakeThread.interrupt();
        }
        // v400：退出卫星图——恢复其它存档的后台静默烘焙
        org.levimc.launcher.core.content.worldmap.SilentBakeManager.get().resume();
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
        // 标点颜色：预设 8 色循环选择（黄/红/蓝/绿/紫/橙/青/白）
        final String[] pointColors = {"#ffd54f", "#ef5350", "#42a5f5", "#66bb6a",
                "#ab47bc", "#ffa726", "#26c6da", "#eceff1"};
        final String[] selColor = {point != null && point.color != null
                ? point.color : categoryColor(selCategory[0])};
        final ImageView[] colorSwatchRef = new ImageView[1]; // 分类切换 lambda 里回写色块

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
                    selColor[0] = categoryColor(selCategory[0]);
                    if (colorSwatchRef[0] != null) {
                        colorSwatchRef[0].setColorFilter(parseColorSafe(selColor[0]));
                    }
                })
                .setNegativeButton(getString(R.string.nbt_edit_cancel), null)
                .show());
        panel.addView(catLabel);
        // 颜色行：色块 + 点击换色
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        colorRow.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        TextView colorLabel = new TextView(this);
        colorLabel.setText(getString(R.string.point_color) + ": ");
        colorLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        colorLabel.setTextSize(12);
        colorRow.addView(colorLabel);
        ImageView colorSwatch = new ImageView(this);
        colorSwatch.setImageResource(R.drawable.bg_circle);
        colorSwatch.setColorFilter(parseColorSafe(selColor[0]));
        colorSwatchRef[0] = colorSwatch;
        int swatchPx = (int) (22 * getResources().getDisplayMetrics().density);
        colorRow.addView(colorSwatch, new LinearLayout.LayoutParams(swatchPx, swatchPx));
        colorRow.setClickable(true);
        colorRow.setFocusable(true);
        final int[] colorIdx = {0};
        colorRow.setOnClickListener(v -> {
            colorIdx[0] = (colorIdx[0] + 1) % pointColors.length;
            selColor[0] = pointColors[colorIdx[0]];
            colorSwatch.setColorFilter(parseColorSafe(selColor[0]));
        });
        panel.addView(colorRow);

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
                        point.color = selColor[0];
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
                        np.color = selColor[0];
                        np.id = blueprintDb.addPoint(np);
                        // 插入失败（id<=0）时提示，不静默丢失
                        if (np.id <= 0) {
                            Toast.makeText(this, R.string.point_save_failed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
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
        float d = getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        // 颜色行：色块 + hex（未命名标点/分类默认色都要可见）
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String colorHex = point.color != null && !point.color.isEmpty()
                ? point.color : categoryColor(point.category);
        ImageView swatch = new ImageView(this);
        swatch.setImageResource(R.drawable.bg_circle);
        swatch.setColorFilter(parseColorSafe(colorHex));
        int sp = (int) (20 * d);
        colorRow.addView(swatch, new LinearLayout.LayoutParams(sp, sp));
        TextView colorText = new TextView(this);
        colorText.setText(getString(R.string.point_color) + ": " + colorHex);
        colorText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        colorText.setTextSize(12);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = (int) (8 * d);
        colorRow.addView(colorText, clp);
        panel.addView(colorRow);
        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.point_coord)).append(": X:").append(point.x)
                .append(" Y:").append(point.y).append(" Z:").append(point.z).append('\n');
        info.append(getString(R.string.point_category)).append(": ").append(catName(point.category)).append('\n');
        if (point.detail != null && !point.detail.isEmpty()) {
            info.append(getString(R.string.point_detail)).append(": ").append(point.detail).append('\n');
        }
        TextView infoText = new TextView(this);
        infoText.setText(info.toString());
        infoText.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
        infoText.setTextSize(13);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = (int) (8 * d);
        panel.addView(infoText, ilp);
        new CustomAlertDialog(this)
                .setTitleText(point.name)
                .setCustomView(panel)
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
        // 连线颜色跟随起点标点颜色（无起点色回退默认蓝）
        l.color = from != null && from.color != null && !from.color.isEmpty()
                ? from.color : "#64b5f6";
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

    /** 安全解析颜色字符串（非法值回退黄色）。 */
    private static int parseColorSafe(String hex) {
        try {
            return android.graphics.Color.parseColor(hex);
        } catch (Exception e) {
            return 0xFFFFD54F;
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
    /** 后台导出交互式 HTML 地图（Leaflet 单文件，含卫星图/标点/连线/结构/玩家出生点）。 */
    /** 导出 HTML 通知（通知栏实时进度百分比；-1 失败 100 完成）。 */
    private void showExportNotification(int percent, String detail) {
        try {
            android.app.NotificationManager nm =
                    getSystemService(android.app.NotificationManager.class);
            if (nm == null) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) {
                return;
            }
            String channelId = "map_export";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        channelId, "地图导出", android.app.NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                b = new android.app.Notification.Builder(this, channelId);
            } else {
                b = new android.app.Notification.Builder(this);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("导出世界 HTML")
                    .setOngoing(percent < 100);
            if (percent < 0) {
                b.setContentText("导出失败: " + detail);
            } else if (percent < 100) {
                b.setContentText("正在导出… " + percent + "%")
                        .setProgress(100, percent, false);
            } else {
                b.setContentText("导出完成: " + detail);
            }
            nm.notify(0x5E97E, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "导出通知失败", t);
        }
    }

    /** v416：导出防重入——导出中重复点击直接忽略（此前用户多点
     *  几下后积压的完成弹窗一股脑弹出）。 */
    private volatile boolean htmlExporting = false;
    /** v418：两阶段打开——bounds 骨架已显示（onDataLoaded 替换
     *  完整图时保留视图不跳）。 */
    private volatile boolean skeletonShown = false;

    private void exportWorldHtmlAsync() {
        if (currentMap == null || currentWorldDir == null) {
            Toast.makeText(this, "地图尚未加载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (htmlExporting) {
            Toast.makeText(this, "正在导出中，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        htmlExporting = true;
        final WorldMapRenderer.WorldMap fMap = currentMap;
        final File worldDir = currentWorldDir;
        // 导出当前显示维度（切到哪个维度点导出就导出哪个维度的图）
        final int exportDim = "nether".equals(mapDimension) ? 1
                : "end".equals(mapDimension) ? 2 : 0;
        final String dimName = exportDim == 1 ? "nether" : exportDim == 2 ? "end" : "overworld";
        // v416：实体列表为空时现场解析（大世界实体延迟 6 秒解析——
        // 打开地图马上导出会拿到空列表，"刷铁机区域没实体"的根因）
        java.util.List<WorldMapRenderer.EntityPos> ents =
                binding.worldMapImage.getEntities();
        if ((ents == null || ents.isEmpty()) && currentWorldDir != null) {
            try {
                ents = WorldMapRenderer.parseEntitiesStreaming(
                        new File(currentWorldDir, "db"), exportDim);
            } catch (Throwable ignored) {
                ents = new java.util.ArrayList<>();
            }
        }
        final java.util.List<WorldMapRenderer.EntityPos> fEntities = ents;
        final String fVersion = readLevelVersion();
        final long fSeed = getWorldSeed();
        binding.nbtLoading.setVisibility(View.VISIBLE);
        showExportNotification(0, "");
        executor.execute(() -> {
            try {
                // v390：导出改用缓存数据——缓存已有 chunk 直接读，缺失的
                // 烘焙补全（缺啥补啥），不再每次全量流式渲染 30-60 秒
                WorldMapRenderer.WorldMap exportMap = fMap;
                final File exportDb = new File(worldDir, "db");
                if (exportMap.chunkColors != null) {
                    showExportNotification(5, "");
                    // 同步等待缺失补全完成（烘焙只烘缺失 chunk，
                    // 缓存完整时几乎瞬间返回）
                    final java.util.concurrent.CountDownLatch bakeDone =
                            new java.util.concurrent.CountDownLatch(1);
                    Thread bt = WorldMapRenderer.bakeWorldCache(
                            exportDb, exportDim, exportMap, null, bakeDone::countDown);
                    try {
                        bakeDone.await(10, java.util.concurrent.TimeUnit.MINUTES);
                    } catch (InterruptedException ignored) {
                    }
                    showExportNotification(70, "");
                }
                java.util.List<String[]> pts = new java.util.ArrayList<>();
                synchronized (mapPoints) {
                    for (BlueprintDb.Point p : mapPoints) {
                        if (!p.dimension.equals(mapDimension)) {
                            continue;
                        }
                        pts.add(new String[]{p.name, String.valueOf(p.x),
                                String.valueOf(p.z), categoryColor(p.category)});
                    }
                }
                java.util.List<String[]> lks = new java.util.ArrayList<>();
                synchronized (mapLinks) {
                    for (BlueprintDb.Link l : mapLinks) {
                        BlueprintDb.Point a = findPoint(l.fromId);
                        BlueprintDb.Point b = findPoint(l.toId);
                        if (a != null && b != null) {
                            lks.add(new String[]{String.valueOf(a.x), String.valueOf(a.z),
                                    String.valueOf(b.x), String.valueOf(b.z), "#64b5f6"});
                        }
                    }
                }
                int px = fMap.playerBlockX;
                int pz = fMap.playerBlockZ;
                int sx = fMap.spawnBlockX;
                int sz = fMap.spawnBlockZ;
                java.util.List<WorldMapRenderer.StructureMarker> sts;
                synchronized (currentStructures) {
                    sts = new java.util.ArrayList<>(currentStructures);
                }
                File dir = new File("/sdcard/Download/LeviLauncher");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                showExportNotification(80, "");
                // 文件名带维度后缀——不同维度的导出互不覆盖
                File out = WorldMapRenderer.exportWorldHtml(exportMap, dir,
                        worldDir.getName() + "_map_" + dimName + ".html",
                        worldDir.getName() + " (" + dimName + ")", fSeed, fVersion,
                        px, pz, sx, sz, pts, lks, sts, fEntities);
                final File fOut = out;
                runOnUiThread(() -> {
                    htmlExporting = false;
                    binding.nbtLoading.setVisibility(View.GONE);
                    showExportNotification(100, fOut.getName());
                    Toast.makeText(this, "已导出: " + fOut.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                Log.w(TAG, "导出 HTML 失败", e);
                runOnUiThread(() -> {
                    htmlExporting = false;
                    binding.nbtLoading.setVisibility(View.GONE);
                    showExportNotification(-1, String.valueOf(e.getMessage()));
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 目录递归大小（字节）。 */
    private static long dirSizeRecursive(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                total += dirSizeRecursive(f);
            } else {
                total += f.length();
            }
        }
        return total;
    }

    /** 字节数 → 可读字符串（B/KB/MB/GB）。 */
    private static String formatBytes(long bytes) {
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

    private String readLevelVersion() {
        return readVersionFromRoot(levelDatRoot);
    }

    /** 从 level.dat root 读游戏版本（LastOpenedWithVersion / MinimumCompatibleClientVersion）。 */
    private static String readVersionFromRoot(NbtTag root) {
        if (root == null) {
            return "?";
        }
        NbtTag v = root.getTag("LastOpenedWithVersion");
        if (v == null) {
            v = root.getTag("MinimumCompatibleClientVersion");
        }
        if (v == null) {
            return "?";
        }
        java.util.List<NbtTag> l = v.getList();
        if (l.isEmpty()) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (NbtTag t : l) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(t.getInt());
        }
        return sb.toString();
    }

    /** 3D 体素视图：进入地图拖选模式（BedrockMap 右键拖选同款），松手生成。 */
    private void showVoxelDialog() {
        if (currentWorldDir == null || currentMap == null) {
            Toast.makeText(this, "地图尚未加载", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.worldMapImage.setVoxelSelectMode(true);
        Toast.makeText(this, R.string.voxel_select_hint, Toast.LENGTH_LONG).show();
    }

    /** 3D 选区（结构导出用）：左上角 + 边长。 */
    private int voxelMinX;
    private int voxelMinZ;
    private int voxelSize;

    /** 导出选中区域为 .mcstructure 结构文件。 */
    private void exportVoxelStructure(int minX, int minZ, int size) {
        if (currentWorldDir == null) {
            return;
        }
        File dir = new File("/sdcard/Download/LeviLauncher/structures");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String name = "structure_" + minX + "_" + minZ + "_" + size + ".mcstructure";
        File out = new File(dir, name);
        binding.nbtLoading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            int dim = "nether".equals(mapDimension) ? 1 : "end".equals(mapDimension) ? 2 : 0;
            boolean ok = WorldMapRenderer.exportStructureRegion(
                    new File(currentWorldDir, "db"), minX, minZ, size, dim, out);
            runOnUiThread(() -> {
                binding.nbtLoading.setVisibility(View.GONE);
                Toast.makeText(this, ok ? "结构已导出: " + out.getAbsolutePath()
                        : "结构导出失败（区域无方块数据）", Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 后台渲染 3D 区域数据（立即弹对话框显示加载状态，完成后回填）。 */
    private void startVoxelRender(int centerX, int centerZ, int size) {
        final File dbDir = new File(currentWorldDir, "db");
        final int dim = "nether".equals(mapDimension) ? 1 : "end".equals(mapDimension) ? 2 : 0;
        voxelMinX = centerX - size / 2;
        voxelMinZ = centerZ - size / 2;
        voxelSize = size;
        final int fCenterX = centerX;
        final int fCenterZ = centerZ;
        // 先弹对话框（VoxelView 空数据显示"加载中…"），大世界读 chunk 要数秒
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
        float d = getResources().getDisplayMetrics().density;
        TextView title = new TextView(this);
        title.setText(getString(R.string.tool_voxel) + "  ·  "
                + centerX + ", " + centerZ + "  ·  " + dimName(mapDimension));
        title.setTextColor(ContextCompat.getColor(this, R.color.primary));
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));
        root.addView(title);
        VoxelView voxel = new VoxelView(this);
        root.addView(voxel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.CENTER);
        btns.setPadding(0, 0, 0, (int) (16 * d));
        String[] labels = {"⟲ 旋转", "放大", "导出结构", "关闭"};
        final int[] selMinX = new int[1];
        final int[] selMinZ = new int[1];
        final int[] selSize = new int[1];
        selMinX[0] = voxelMinX;
        selMinZ[0] = voxelMinZ;
        selSize[0] = voxelSize;
        android.view.View.OnClickListener[] clicks = {
                v2 -> voxel.rotateClockwise(),
                v2 -> voxel.toggleZoom(),
                v2 -> exportVoxelStructure(selMinX[0], selMinZ[0], selSize[0]),
                v2 -> dialog.dismiss()
        };
        for (int i = 0; i < labels.length; i++) {
            TextView btn = new TextView(this);
            btn.setText(labels[i]);
            btn.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
            btn.setTextSize(13);
            btn.setBackgroundResource(R.drawable.bg_rounded_card);
            btn.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = (int) (12 * d);
            }
            btn.setLayoutParams(lp);
            btn.setOnClickListener(clicks[i]);
            btns.addView(btn);
        }
        root.addView(btns);
        dialog.setContentView(root);
        dialog.show();
        executor.execute(() -> {
            WorldMapRenderer.VoxelColumn[][] data = WorldMapRenderer.renderVoxelRegion(
                    dbDir, fCenterX, fCenterZ, dim, size, 8);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (data == null) {
                    Toast.makeText(this, "该区域无数据", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    return;
                }
                voxel.setVoxelData(data, size);
            });
        });
    }

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
