package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.core.content.ResourcePackItem;
import org.levimc.launcher.core.content.ResourcePackManager;
import org.levimc.launcher.core.content.ServerItem;
import org.levimc.launcher.core.content.StructureExtractor;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.core.content.WorldManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityContentListBinding;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.adapter.ResourcePacksAdapter;
import org.levimc.launcher.ui.adapter.StructuresAdapter;
import org.levimc.launcher.ui.adapter.WorldsAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.LauncherStorage;

import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.OutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ContentListActivity extends BaseActivity {

    public static final String EXTRA_CONTENT_TYPE = "content_type";
    public static final String EXTRA_WORLDS_DIRECTORY = "worlds_directory";
    public static final String EXTRA_CURRENT_STORAGE_TYPE = "current_storage_type";
    public static final String EXTRA_SHARED_MODE = "shared_mode";
    public static final int TYPE_WORLDS = 0;
    public static final int TYPE_SKIN_PACKS = 1;
    public static final int TYPE_RESOURCE_PACKS = 2;
    public static final int TYPE_BEHAVIOR_PACKS = 3;
    public static final int TYPE_SCREENSHOTS = 4;
    public static final int TYPE_SERVERS = 5;
    public static final int TYPE_STRUCTURES = 6;
    private static final String STATE_SELECTION_MODE = "selection_mode";
    private static final String STATE_SELECTED_PATHS = "selected_paths";

    private ActivityContentListBinding binding;
    private ContentManager contentManager;
    private VersionManager versionManager;
    private int contentType;
    private File worldsDirectory;
    private FeatureSettings.StorageType currentStorageType;
    private boolean sharedMode;

    private WorldsAdapter worldsAdapter;
    private ResourcePacksAdapter packsAdapter;
    private org.levimc.launcher.ui.adapter.ScreenshotsAdapter screenshotsAdapter;
    private org.levimc.launcher.ui.adapter.ServersAdapter serversAdapter;
    private org.levimc.launcher.ui.adapter.StructureFilesAdapter structuresAdapter;

    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> exportPackLauncher;
    private ActivityResultLauncher<Intent> customFlatWorldLauncher;
    private ActivityResultLauncher<Intent> structureExportLauncher;
    private ActivityResultLauncher<Intent> batchExportFolderLauncher;
    private WorldItem pendingExportWorld;
    private ResourcePackItem pendingExportPack;
    private WorldItem pendingStructureExportWorld;
    private StructureExtractor.StructureInfo pendingStructureInfo;
    private StructureExtractor structureExtractor;
    private List<WorldItem> pendingBatchWorlds = new ArrayList<>();
    private List<ResourcePackItem> pendingBatchPacks = new ArrayList<>();

    private List<WorldItem> allWorlds = new ArrayList<>();
    private List<ResourcePackItem> allPacks = new ArrayList<>();
    private List<ServerItem> allServers = new ArrayList<>();
    private List<org.levimc.launcher.core.content.StructureFileItem> allStructures = new ArrayList<>();

    private org.levimc.launcher.ui.dialogs.LoadingDialog progressDialog;

    private void showProgressDialog(String message) {
        if (progressDialog == null) {
            progressDialog = new org.levimc.launcher.ui.dialogs.LoadingDialog(this);
        }
        progressDialog.show();
        progressDialog.setMessage(message);
    }

    private void hideProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityContentListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DynamicAnim.applyPressScaleRecursively(binding.getRoot());

        contentType = getIntent().getIntExtra(EXTRA_CONTENT_TYPE, TYPE_WORLDS);
        sharedMode = getIntent().getBooleanExtra(EXTRA_SHARED_MODE, false);
        contentManager = ContentManager.getInstance(this);
        versionManager = VersionManager.get(this);

        if (sharedMode) {
            configureSharedDirectories();
        }

        String storageTypeStr = getIntent().getStringExtra(EXTRA_CURRENT_STORAGE_TYPE);
        if (storageTypeStr != null) {
            currentStorageType = parseStorageType(storageTypeStr);
        } else {
            currentStorageType = parseStorageType("EXTERNAL");
        }

        setupActivityResultLaunchers();
        setupUI();
        restoreSelectionState(savedInstanceState);
        setupObservers();
        loadContent();
    }

    private void setupActivityResultLaunchers() {
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingExportWorld != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportWorld(pendingExportWorld, uri);
                    }
                }
                pendingExportWorld = null;
            }
        );

        exportPackLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingExportPack != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportPack(pendingExportPack, uri);
                    }
                }
                pendingExportPack = null;
            }
        );

        customFlatWorldLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadContent();
                }
            }
        );

        structureExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingStructureExportWorld != null && pendingStructureInfo != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportStructureToFile(pendingStructureExportWorld, pendingStructureInfo, uri);
                    }
                }
                pendingStructureExportWorld = null;
                pendingStructureInfo = null;
            }
        );

        batchExportFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        int flags = result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        try {
                            getContentResolver().takePersistableUriPermission(treeUri, flags);
                        } catch (Exception ignored) {
                        }
                        if (!pendingBatchWorlds.isEmpty()) {
                            List<WorldItem> items = new ArrayList<>(pendingBatchWorlds);
                            pendingBatchWorlds.clear();
                            new Thread(() -> exportWorldBatch(treeUri, items, 0, 0, 0, new HashSet<>())).start();
                        } else if (!pendingBatchPacks.isEmpty()) {
                            List<ResourcePackItem> items = new ArrayList<>(pendingBatchPacks);
                            pendingBatchPacks.clear();
                            new Thread(() -> exportPackBatch(treeUri, items, 0, 0, 0, new HashSet<>())).start();
                        }
                    }
                } else {
                    pendingBatchWorlds.clear();
                    pendingBatchPacks.clear();
                }
            }
        );

        structureExtractor = new StructureExtractor(this);
    }

    private void setupUI() {

        String worldsPath = getIntent().getStringExtra(EXTRA_WORLDS_DIRECTORY);
        if (worldsPath != null) {
            worldsDirectory = new File(worldsPath);
        } else if (sharedMode) {
            File sharedGameData = LauncherStorage.getSharedGameDataDir(this, true);
            worldsDirectory = new File(sharedGameData, "minecraftWorlds");
        } else {
            worldsDirectory = getWorldsDirectoryForType(currentStorageType);
        }

        switch (contentType) {
            case TYPE_WORLDS:
                binding.titleText.setText(getString(R.string.worlds_title));
                binding.customFlatButton.setVisibility(View.VISIBLE);
                binding.hardcoreManageButton.setVisibility(View.VISIBLE);
                binding.hardcoreManageButton.setOnClickListener(v -> openHardcoreManager());
                binding.selectButton.setVisibility(View.VISIBLE);
                setupWorldsRecyclerView();
                break;
            case TYPE_SKIN_PACKS:
                binding.titleText.setText(getString(R.string.skin_packs_title));
                binding.selectButton.setVisibility(View.VISIBLE);
                setupPacksRecyclerView();
                break;
            case TYPE_RESOURCE_PACKS:
                binding.titleText.setText(getString(R.string.resource_packs_title));
                binding.selectButton.setVisibility(View.VISIBLE);
                setupPacksRecyclerView();
                break;
            case TYPE_BEHAVIOR_PACKS:
                binding.titleText.setText(getString(R.string.behavior_packs_title));
                binding.selectButton.setVisibility(View.VISIBLE);
                setupPacksRecyclerView();
                break;
            case TYPE_SCREENSHOTS:
                binding.titleText.setText(getString(R.string.screenshots_category));
                binding.searchEditText.setVisibility(View.GONE);
                setupScreenshotsRecyclerView();
                break;
            case TYPE_SERVERS:
                binding.titleText.setText(getString(R.string.servers_category));
                binding.searchEditText.setVisibility(View.VISIBLE);
                binding.customFlatButton.setText(getString(R.string.quick_launch_add_server));
                binding.customFlatButton.setVisibility(View.VISIBLE);
                setupServersRecyclerView();
                break;
            case TYPE_STRUCTURES:
                binding.titleText.setText(getString(R.string.structures_category));
                binding.searchEditText.setVisibility(View.VISIBLE);
                binding.customFlatButton.setVisibility(View.GONE);
                setupStructuresRecyclerView();
                break;
        }

        binding.customFlatButton.setOnClickListener(v -> {
            if (contentType == TYPE_SERVERS) {
                showAddServerDialog();
            } else {
                openCustomFlatWorld();
            }
        });

        binding.selectButton.setOnClickListener(v -> enterSelectionMode());
        binding.selectionCancelButton.setOnClickListener(v -> exitSelectionMode());
        binding.selectAllButton.setOnClickListener(v -> toggleSelectAllVisible());
        binding.batchExportButton.setOnClickListener(v -> startBatchExport());
        binding.batchTransferButton.setOnClickListener(v -> showBatchTransferDialog());
        binding.batchDeleteButton.setOnClickListener(v -> showBatchDeleteDialog());

        setupSearchFilter();
        updateSelectionToolbar();
    }

    private void setupSearchFilter() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterContent(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void filterContent(String query) {
        String rawQuery = query == null ? "" : query.trim();
        String lowerQuery = rawQuery.toLowerCase();

        if (contentType == TYPE_WORLDS && worldsAdapter != null) {
            List<WorldItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allWorlds) : allWorlds.stream()
                .filter(world -> world.getWorldName() != null && world.getWorldName().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
            worldsAdapter.updateWorlds(filtered);
            updateListState(filtered.size(), allWorlds.size(), rawQuery);
        } else if (contentType == TYPE_SERVERS && serversAdapter != null) {
            List<ServerItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allServers) : allServers.stream()
                .filter(server -> (server.name != null && server.name.toLowerCase().contains(lowerQuery)) ||
                                 (server.ip != null && server.ip.toLowerCase().contains(lowerQuery)))
                .collect(Collectors.toList());
            serversAdapter.updateData(filtered);
            updateListState(filtered.size(), allServers.size(), rawQuery);
        } else if (contentType == TYPE_STRUCTURES && structuresAdapter != null) {
            List<org.levimc.launcher.core.content.StructureFileItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allStructures) : allStructures.stream()
                .filter(structure -> structure.getName() != null && structure.getName().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
            structuresAdapter.updateData(filtered);
            updateListState(filtered.size(), allStructures.size(), rawQuery);
        } else if (isPackType() && packsAdapter != null) {
            List<ResourcePackItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allPacks) : allPacks.stream()
                .filter(pack -> pack.getPackName() != null && pack.getPackName().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
            packsAdapter.updateResourcePacks(filtered);
            updateListState(filtered.size(), allPacks.size(), rawQuery);
        }
    }

    private void setupWorldsRecyclerView() {
        worldsAdapter = new WorldsAdapter();
        worldsAdapter.setOnWorldActionListener(new WorldsAdapter.OnWorldActionListener() {
            @Override
            public void onWorldExport(WorldItem world) {
                startWorldExport(world);
            }

            @Override
            public void onWorldDelete(WorldItem world) {
                showDeleteWorldDialog(world);
            }

            @Override
            public void onWorldBackup(WorldItem world) {
                backupWorld(world);
            }

            @Override
            public void onWorldEdit(WorldItem world) {
                openWorldEditor(world);
            }

            @Override
            public void onWorldViewMap(WorldItem world) {
                openNbtViewer(world);
            }

            @Override
            public void onWorldExtractStructures(WorldItem world) {
                showExtractStructuresDialog(world);
            }

            @Override
            public void onWorldTransfer(WorldItem world) {
                showTransferWorldDialog(world);
            }

            @Override
            public void onWorldLocate(WorldItem world) {
                openFileManager(world.getFile());
            }

            @Override
            public void onWorldPlay(WorldItem world) {
                launchWorldDirect(world);
            }

            @Override
            public void onWorldTogglePin(WorldItem world) {
                togglePinWorld(world);
            }
        });
        // v445：置顶集合与顺序快照（SharedPreferences 持久化）
        worldsAdapter.setPinnedPaths(loadPinnedWorlds());
        worldsAdapter.setOrderSnapshot(loadWorldOrder());
        // v445：拖动排序（长按拖动；松手保存顺序快照）
        androidx.recyclerview.widget.ItemTouchHelper touchHelper =
                new androidx.recyclerview.widget.ItemTouchHelper(
                        new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                                androidx.recyclerview.widget.ItemTouchHelper.UP
                                        | androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                                0) {
                            @Override
                            public boolean onMove(@NonNull androidx.recyclerview.widget.RecyclerView rv,
                                                  @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder vh,
                                                  @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
                                int from = vh.getAdapterPosition();
                                int to = target.getAdapterPosition();
                                // v445：跨组禁止——置顶的不能拖到未置顶下面，
                                // 未置顶的不能拖到置顶上面
                                WorldItem fromWorld = worldsAdapter.getWorldAt(from);
                                WorldItem toWorld = worldsAdapter.getWorldAt(to);
                                if (fromWorld != null && toWorld != null
                                        && worldsAdapter.isPinned(fromWorld)
                                        != worldsAdapter.isPinned(toWorld)) {
                                    return false;
                                }
                                worldsAdapter.moveItem(from, to);
                                return true;
                            }

                            @Override
                            public void onSwiped(@NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder vh, int direction) {
                            }

                            @Override
                            public boolean isLongPressDragEnabled() {
                                // 长按拖动与长按多选冲突——仅多选模式关闭时允许拖动
                                return !worldsAdapter.isSelectionMode();
                            }

                            @Override
                            public void clearView(@NonNull androidx.recyclerview.widget.RecyclerView rv,
                                                  @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder vh) {
                                super.clearView(rv, vh);
                                saveWorldOrder(worldsAdapter.currentOrderSnapshot());
                            }
                        });
        touchHelper.attachToRecyclerView(binding.contentRecyclerView);
        worldsAdapter.setOnSelectionChangedListener(count -> updateSelectionToolbar());

        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(worldsAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    /** v460：旧 WorldEditorActivity 已删除——"编辑世界"统一进
     *  NbtViewerActivity 地图页，数据面板「世界设置」Tab 表单化编辑。
     *  v462：EXTRA_OPEN_SETTINGS 直达设置表单（不用先看地图再找入口）。 */
    private void openWorldEditor(WorldItem world) {
        File worldFile = world.getFile();
        if (worldFile == null || !worldFile.exists()) {
            Toast.makeText(this, R.string.world_directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, NbtViewerActivity.class);
        intent.putExtra(NbtViewerActivity.EXTRA_WORLD_DIR, worldFile.getAbsolutePath());
        intent.putExtra(NbtViewerActivity.EXTRA_WORLD_NAME, world.getWorldName());
        intent.putExtra(NbtViewerActivity.EXTRA_OPEN_SETTINGS, true);
        startActivity(intent);
    }

    // ---------------------------------------------------------------- v445 置顶/排序持久化

    private android.content.SharedPreferences worldListPrefs() {
        return getSharedPreferences("world_list_prefs", MODE_PRIVATE);
    }

    private Set<String> loadPinnedWorlds() {
        return new HashSet<>(worldListPrefs().getStringSet("pinned", new HashSet<>()));
    }

    private List<String> loadWorldOrder() {
        String raw = worldListPrefs().getString("order", "");
        List<String> out = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String s : raw.split(",")) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private void saveWorldOrder(List<String> order) {
        // v450：合并旧快照中不在当前列表的路径（搜索过滤时拖动
        // 只覆盖可见项，直接覆盖会把隐藏世界的顺序记录弄丢）
        List<String> merged = new ArrayList<>(order);
        for (String old : loadWorldOrder()) {
            if (!merged.contains(old)) {
                merged.add(old);
            }
        }
        worldListPrefs().edit().putString("order", String.join(",", merged)).apply();
    }

    /** v445：置顶/取消置顶——持久化后重排显示。 */
    private void togglePinWorld(WorldItem world) {
        File f = world != null ? world.getFile() : null;
        if (f == null) {
            return;
        }
        String path;
        try {
            path = f.getCanonicalPath();
        } catch (Exception e) {
            path = f.getAbsolutePath();
        }
        Set<String> pinned = loadPinnedWorlds();
        boolean nowPinned;
        if (pinned.contains(path)) {
            pinned.remove(path);
            nowPinned = false;
        } else {
            pinned.add(path);
            nowPinned = true;
        }
        worldListPrefs().edit().putStringSet("pinned", pinned).apply();
        worldsAdapter.setPinnedPaths(pinned);
        filterContent(binding.searchEditText.getText().toString());
        Toast.makeText(this, nowPinned ? R.string.pin_world : R.string.unpin_world,
                Toast.LENGTH_SHORT).show();
    }

    /** v444：立即游玩——WorldPicker 同款链路（URI 协议直启该存档，
     *  当前实例已选中无需再选版本）。 */
    private void launchWorldDirect(WorldItem world) {
        if (world == null || world.getWorldId() == null) {
            return;
        }
        android.net.Uri uri = org.levimc.launcher.util.MinecraftUriHandler
                .buildConnectLocalWorld(world.getWorldId());
        Intent intent = new Intent(this, IntentHandler.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.world_picker_launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 地图按钮：直接打开世界数据/地图查看（NBT 查看器）。 */
    private void openNbtViewer(WorldItem world) {
        File worldFile = world.getFile();
        if (worldFile == null || !worldFile.exists()) {
            Toast.makeText(this, R.string.world_directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, NbtViewerActivity.class);
        intent.putExtra(NbtViewerActivity.EXTRA_WORLD_DIR, worldFile.getAbsolutePath());
        intent.putExtra(NbtViewerActivity.EXTRA_WORLD_NAME, world.getWorldName());
        startActivity(intent);
    }

    private void openHardcoreManager() {
        Intent intent = new Intent(this, HardcoreBackupActivity.class);
        startActivity(intent);
    }

    private void openFileManager(File dir) {
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, FileManagerActivity.class);
        intent.putExtra(FileManagerActivity.EXTRA_PATH, dir.getAbsolutePath());
        startActivity(intent);
    }

    private void setupPacksRecyclerView() {
        packsAdapter = new ResourcePacksAdapter();
        // 共享文件夹模式下，资源包/行为包显示「预加载」开关
        packsAdapter.setShowPreloadSwitch(sharedMode);
        packsAdapter.setOnResourcePackActionListener(new ResourcePacksAdapter.OnResourcePackActionListener() {
            @Override
            public void onResourcePackDelete(ResourcePackItem pack) {
                showDeletePackDialog(pack);
            }

            @Override
            public void onResourcePackTransfer(ResourcePackItem pack) {
                showTransferPackDialog(pack);
            }

            @Override
            public void onResourcePackExport(ResourcePackItem pack) {
                startPackExport(pack);
            }

            @Override
            public void onResourcePackLocate(ResourcePackItem pack) {
                openFileManager(pack.getFile());
            }

            @Override
            public void onResourcePackPreloadChanged(ResourcePackItem pack, boolean preloaded) {
                // 预加载状态已持久化，启动游戏时会复制到目标版本并写入全局资源
            }
        });
        packsAdapter.setOnSelectionChangedListener(count -> updateSelectionToolbar());

        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(packsAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupScreenshotsRecyclerView() {
        screenshotsAdapter = new org.levimc.launcher.ui.adapter.ScreenshotsAdapter(new ArrayList(), new org.levimc.launcher.ui.adapter.ScreenshotsAdapter.OnScreenshotClickListener() {
            @Override
            public void onDeleteClick(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
                showDeleteScreenshotDialog(screenshot);
            }

            @Override
            public void onSaveClick(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
                saveScreenshotToGallery(screenshot);
            }

            @Override
            public void onLocateClick(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
                openFileManager(screenshot.file);
            }
        });
        binding.contentRecyclerView.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
        binding.contentRecyclerView.setAdapter(screenshotsAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupServersRecyclerView() {
        serversAdapter = new org.levimc.launcher.ui.adapter.ServersAdapter(new ArrayList<>(), server -> showDeleteServerDialog(server));
        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(serversAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupStructuresRecyclerView() {
        structuresAdapter = new org.levimc.launcher.ui.adapter.StructureFilesAdapter(new org.levimc.launcher.ui.adapter.StructureFilesAdapter.OnStructureActionListener() {
            @Override
            public void onDelete(org.levimc.launcher.core.content.StructureFileItem structure) {
                showDeleteStructureDialog(structure);
            }
        });
        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(structuresAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupObservers() {
        switch (contentType) {
            case TYPE_WORLDS:
                contentManager.getWorldsLiveData().observe(this, worlds -> {
                    allWorlds = worlds != null ? worlds : new ArrayList<>();
                    if (worldsAdapter != null) {
                        worldsAdapter.retainSelections(allWorlds);
                        filterContent(binding.searchEditText.getText().toString());
                    }
                    showLoading(false);
                });
                break;
            case TYPE_SKIN_PACKS:
                contentManager.getSkinPacksLiveData().observe(this, packs -> updatePacksFromObserver(packs));
                break;
            case TYPE_RESOURCE_PACKS:
                contentManager.getResourcePacksLiveData().observe(this, packs -> updatePacksFromObserver(packs));
                break;
            case TYPE_BEHAVIOR_PACKS:
                contentManager.getBehaviorPacksLiveData().observe(this, packs -> updatePacksFromObserver(packs));
                break;
            case TYPE_SCREENSHOTS:
                contentManager.getScreenshotsLiveData().observe(this, screenshots -> {
                    List<org.levimc.launcher.core.content.ScreenshotItem> items = screenshots != null ? screenshots : new ArrayList<>();
                    if (screenshotsAdapter != null) {
                        screenshotsAdapter.updateData(items);
                    }
                    updateListState(items.size(), items.size(), "");
                    showLoading(false);
                });
                break;
            case TYPE_SERVERS:
                contentManager.getServersLiveData().observe(this, servers -> {
                    allServers = servers != null ? servers : new ArrayList<>();
                    if (serversAdapter != null) {
                        filterContent(binding.searchEditText.getText().toString());
                    }
                    showLoading(false);
                });
                break;
            case TYPE_STRUCTURES:
                contentManager.getStructuresLiveData().observe(this, structures -> {
                    allStructures = structures != null ? structures : new ArrayList<>();
                    if (structuresAdapter != null) {
                        filterContent(binding.searchEditText.getText().toString());
                    }
                    showLoading(false);
                });
                break;
        }
    }

    private void updatePacksFromObserver(List<ResourcePackItem> packs) {
        allPacks = packs != null ? packs : new ArrayList<>();
        if (packsAdapter != null) {
            packsAdapter.retainSelections(allPacks);
            filterContent(binding.searchEditText.getText().toString());
        }
        showLoading(false);
    }

    private void loadContent() {
        showLoading(true);
        switch (contentType) {
            case TYPE_WORLDS:
                contentManager.refreshWorlds();
                break;
            case TYPE_SKIN_PACKS:
                contentManager.refreshSkinPacks();
                break;
            case TYPE_RESOURCE_PACKS:
                contentManager.refreshResourcePacks();
                break;
            case TYPE_BEHAVIOR_PACKS:
                contentManager.refreshBehaviorPacks();
                break;
            case TYPE_SCREENSHOTS:
                contentManager.refreshScreenshots();
                break;
            case TYPE_SERVERS:
                contentManager.refreshServers();
                break;
            case TYPE_STRUCTURES:
                contentManager.refreshStructures();
                break;
        }
    }

    private void showLoading(boolean show) {
        binding.loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startWorldExport(WorldItem world) {
        pendingExportWorld = world;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 用通用 MIME，避免部分系统把保存文件强制改成 .zip 后缀；
        // 实际后缀由 EXTRA_TITLE（.mcworld）决定
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, world.getName() + ".mcworld");
        exportLauncher.launch(intent);
    }

    private void exportWorld(WorldItem world, Uri uri) {
        showProgressDialog(getString(R.string.exporting_world));
        contentManager.exportWorld(world, uri, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, getString(R.string.export_world_success), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void startPackExport(ResourcePackItem pack) {
        pendingExportPack = pack;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 用通用 MIME，避免部分系统把保存文件强制改成 .zip 后缀；
        // 实际后缀由 EXTRA_TITLE（.mcpack）决定
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, pack.getPackName() + ".mcpack");
        exportPackLauncher.launch(intent);
    }

    private void exportPack(ResourcePackItem pack, Uri uri) {
        showProgressDialog(getString(R.string.exporting_pack));
        contentManager.exportResourcePack(pack, uri, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, getString(R.string.export_pack_success), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void backupWorld(WorldItem world) {
        // 手动备份统一走普通路径（.mcworld 存到 Download/LeviLauncher/Backups/minecraftWorlds backups/）；
        // 极限存档专属 Hardcore backups 文件夹只在定时备份时使用。
        showProgressDialog(getString(R.string.backing_up_world));
        contentManager.backupWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void showDeleteWorldDialog(WorldItem world) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete_world))
            .setMessage(getString(R.string.confirm_delete_world))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteWorld(world))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void deleteWorld(WorldItem world) {
        showProgressDialog(getString(R.string.deleting_world));
        contentManager.deleteWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void showDeletePackDialog(ResourcePackItem pack) {
        int titleResId;
        int messageResId;

        if (contentType == TYPE_BEHAVIOR_PACKS) {
            titleResId = R.string.delete_behavior_pack;
            messageResId = R.string.confirm_delete_behavior_pack;
        } else if (contentType == TYPE_SKIN_PACKS) {
            titleResId = R.string.delete_skin_pack;
            messageResId = R.string.confirm_delete_skin_pack;
        } else {
            titleResId = R.string.delete_resource_pack;
            messageResId = R.string.confirm_delete_resource_pack;
        }

        // 附加包联动：RP/BP 的 manifest dependencies 声明了对端 uuid 时，删除会同时删除对端
        ResourcePackItem linked = null;
        if (contentType == TYPE_RESOURCE_PACKS || contentType == TYPE_BEHAVIOR_PACKS) {
            linked = findLinkedAddonPack(pack);
        }
        final ResourcePackItem linkedPack = linked;
        if (linkedPack != null) {
            titleResId = R.string.addon_delete_linked_title;
        }

        String message = linkedPack != null
                ? getString(R.string.addon_delete_linked_message, pack.getName(), linkedPack.getName())
                : getString(messageResId);

        new CustomAlertDialog(this)
            .setTitleText(getString(titleResId))
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> {
                if (linkedPack != null) deletePack(linkedPack);
                deletePack(pack);
            })
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    /** 附加包对端查找：RP 查 BP / BP 查 RP，依赖声明包含该包 uuid 即视为配对。 */
    private ResourcePackItem findLinkedAddonPack(ResourcePackItem pack) {
        try {
            java.util.List<String> deps = readManifestDependencyUuids(pack);
            if (deps.isEmpty()) return null;
            boolean isResourcePack = contentType == TYPE_RESOURCE_PACKS;
            org.levimc.launcher.core.content.ContentManager cm =
                    org.levimc.launcher.core.content.ContentManager.getInstance(this);
            androidx.lifecycle.LiveData<java.util.List<ResourcePackItem>> otherLive =
                    isResourcePack ? cm.getBehaviorPacksLiveData() : cm.getResourcePacksLiveData();
            java.util.List<ResourcePackItem> others = otherLive.getValue();
            if (others == null) return null;
            for (ResourcePackItem other : others) {
                String uuid = other.getUuid();
                if (uuid != null && deps.contains(uuid)) return other;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 读取包 manifest.json 里 dependencies 声明的 uuid 列表。 */
    private java.util.List<String> readManifestDependencyUuids(ResourcePackItem pack) {
        java.util.List<String> uuids = new java.util.ArrayList<>();
        try {
            java.io.File manifest = new java.io.File(pack.getFile(), "manifest.json");
            if (!manifest.isFile()) return uuids;
            byte[] bytes = new byte[(int) manifest.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(manifest)) {
                int off = 0;
                while (off < bytes.length) {
                    int read = fis.read(bytes, off, bytes.length - off);
                    if (read < 0) break;
                    off += read;
                }
            }
            org.json.JSONObject root = new org.json.JSONObject(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!root.has("dependencies")) return uuids;
            org.json.JSONArray deps = root.getJSONArray("dependencies");
            for (int i = 0; i < deps.length(); i++) {
                org.json.JSONObject dep = deps.optJSONObject(i);
                if (dep != null && dep.has("uuid")) {
                    uuids.add(dep.getString("uuid"));
                }
            }
        } catch (Exception ignored) {
        }
        return uuids;
    }

    private void deletePack(ResourcePackItem pack) {
        showProgressDialog(getString(R.string.deleting_pack));
        contentManager.deleteResourcePack(pack, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void saveScreenshotToGallery(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
        showLoading(true);
        new Thread(() -> {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(screenshot.file.getAbsolutePath());
                if (bitmap != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, screenshot.name + "_" + System.currentTimeMillis() + ".jpg");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);

                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        }
                        runOnUiThread(() -> {
                            showLoading(false);
                            Toast.makeText(ContentListActivity.this, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            runOnUiThread(() -> {
                showLoading(false);
                Toast.makeText(ContentListActivity.this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void showDeleteScreenshotDialog(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete))
            .setMessage(getString(R.string.delete_screenshot_confirm))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteScreenshot(screenshot))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void showAddServerDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);
        EditText serverNameEdit = dialogView.findViewById(R.id.server_name_edit);
        EditText serverIpEdit = dialogView.findViewById(R.id.server_ip_edit);
        EditText serverPortEdit = dialogView.findViewById(R.id.server_port_edit);
        serverPortEdit.setText("19132");

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_add_server))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.add), v -> {
                    String name = serverNameEdit.getText().toString().trim();
                    String ip = serverIpEdit.getText().toString().trim();
                    String portStr = serverPortEdit.getText().toString().trim();

                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(ip)) {
                        Toast.makeText(this, R.string.server_details_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port = 19132;
                    if (!TextUtils.isEmpty(portStr)) {
                        try {
                            port = Integer.parseInt(portStr);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    addServer(new org.levimc.launcher.core.content.ServerItem(name, ip, port));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void addServer(org.levimc.launcher.core.content.ServerItem server) {
        showProgressDialog(getString(R.string.adding_server));
        contentManager.addServer(server, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void deleteScreenshot(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
        showProgressDialog(getString(R.string.deleting_screenshot));
        contentManager.deleteScreenshot(screenshot, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showDeleteServerDialog(org.levimc.launcher.core.content.ServerItem server) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete))
            .setMessage(getString(R.string.delete_server_confirm))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteServer(server))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void showDeleteStructureDialog(org.levimc.launcher.core.content.StructureFileItem structure) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete))
            .setMessage(getString(R.string.delete_confirm, structure.getName()))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteStructure(structure))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void deleteStructure(org.levimc.launcher.core.content.StructureFileItem structure) {
        contentManager.deleteStructure(structure, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void deleteServer(org.levimc.launcher.core.content.ServerItem server) {
        showProgressDialog(getString(R.string.deleting_server));
        contentManager.deleteServer(server, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openCustomFlatWorld() {
        if (worldsDirectory == null || !worldsDirectory.exists()) {
            Toast.makeText(this, R.string.worlds_directory_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, CustomFlatWorldActivity.class);
        intent.putExtra(CustomFlatWorldActivity.EXTRA_WORLDS_DIRECTORY, worldsDirectory.getAbsolutePath());
        customFlatWorldLauncher.launch(intent);
    }

    private void showExtractStructuresDialog(WorldItem world) {
        File worldFile = world.getFile();
        if (worldFile == null || !worldFile.exists()) {
            Toast.makeText(this, R.string.world_directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.loadingOverlay.setVisibility(View.VISIBLE);

        structureExtractor.loadStructures(worldFile, new StructureExtractor.StructureListCallback() {
            @Override
            public void onComplete(List<StructureExtractor.StructureInfo> structures) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    if (structures.isEmpty()) {
                        showNoStructuresFoundDialog();
                    } else {
                        showStructureSelectionDialog(world, structures);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showNoStructuresFoundDialog() {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.no_structures_found_title))
            .setMessage(getString(R.string.no_structures_found_message))
            .setPositiveButton(getString(R.string.dialog_positive_ok), null)
            .show();
    }

    private void showStructureSelectionDialog(WorldItem world, List<StructureExtractor.StructureInfo> structures) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_structure_list, null);

        TextView structureCount = dialogView.findViewById(R.id.structure_count);
        RecyclerView recyclerView = dialogView.findViewById(R.id.structures_recycler_view);

        structureCount.setText(getString(R.string.structures_found_count, structures.size()));

        StructuresAdapter adapter = new StructuresAdapter();
        adapter.setStructures(structures);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.structures_found_title))
                .setCustomView(dialogView)
                .setNegativeButton(getString(R.string.cancel), null);

        adapter.setOnStructureExportListener(structure -> {
            dialog.dismissImmediately();
            startStructureExport(world, structure);
        });

        dialog.show();
    }

    private void startStructureExport(WorldItem world, StructureExtractor.StructureInfo structure) {
        pendingStructureExportWorld = world;
        pendingStructureInfo = structure;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, structure.getFileName());
        structureExportLauncher.launch(intent);
    }

    private void exportStructureToFile(WorldItem world, StructureExtractor.StructureInfo structure, Uri uri) {
        binding.loadingOverlay.setVisibility(View.VISIBLE);

        structureExtractor.exportSingleStructure(structure, uri, new StructureExtractor.ExtractionCallback() {

            @Override
            public void onComplete(int extractedCount, String outputPath) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(ContentListActivity.this,
                            getString(R.string.structure_exported, structure.getName()),
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showTransferWorldDialog(WorldItem world) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_transfer_content, null);
        Spinner targetSpinner = dialogView.findViewById(R.id.target_version_spinner);

        List<GameVersion> allVersions = getAllVersions();
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.transfer_to_shared));
        for (GameVersion v : allVersions) labels.add(v.displayName);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.transfer_content))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.transfer), v -> {
                    int pos = targetSpinner.getSelectedItemPosition();
                    boolean shared = pos <= 0;
                    String profileId = shared ? null : allVersions.get(pos - 1).getStorageProfileId();
                    File targetDir = getWorldsDirectoryForTarget(shared, profileId);
                    transferWorldTo(world, targetDir);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showTransferPackDialog(ResourcePackItem pack) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_transfer_content, null);
        Spinner targetSpinner = dialogView.findViewById(R.id.target_version_spinner);

        List<GameVersion> allVersions = getAllVersions();
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.transfer_to_shared));
        for (GameVersion v : allVersions) labels.add(v.displayName);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.transfer_content))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.transfer), v -> {
                    int pos = targetSpinner.getSelectedItemPosition();
                    boolean shared = pos <= 0;
                    String profileId = shared ? null : allVersions.get(pos - 1).getStorageProfileId();
                    String packType = pack.isBehaviorPack() ? "behavior_packs" :
                            (contentType == TYPE_SKIN_PACKS ? "skin_packs" : "resource_packs");
                    File targetDir = getPackDirectoryForTarget(shared, profileId, packType);
                    transferPackTo(pack, targetDir);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void transferWorldTo(WorldItem world, File targetDir) {
        if (targetDir == null) {
            Toast.makeText(this, getString(R.string.transfer_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);
        contentManager.transferWorld(world, targetDir, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, getString(R.string.transfer_success), Toast.LENGTH_SHORT).show();
                    loadContent();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void transferPackTo(ResourcePackItem pack, File targetDir) {
        if (targetDir == null) {
            Toast.makeText(this, getString(R.string.transfer_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);
        contentManager.transferResourcePack(pack, targetDir, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, getString(R.string.transfer_success), Toast.LENGTH_SHORT).show();
                    loadContent();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private List<GameVersion> getAllVersions() {
        List<GameVersion> all = new ArrayList<>();
        if (versionManager != null) {
            List<GameVersion> installed = versionManager.getInstalledVersions();
            List<GameVersion> custom = versionManager.getCustomVersions();
            if (installed != null) all.addAll(installed);
            if (custom != null) all.addAll(custom);
        }
        return all;
    }

    private File getWorldsDirectoryForTarget(boolean shared, String profileId) {
        File gameDataDir = shared
                ? LauncherStorage.getSharedGameDataDir(this, true)
                : LauncherStorage.getProfileGameDataDir(this, profileId, true);
        return new File(gameDataDir, "minecraftWorlds");
    }

    private File getPackDirectoryForTarget(boolean shared, String profileId, String packType) {
        File gameDataDir = shared
                ? LauncherStorage.getSharedGameDataDir(this, true)
                : LauncherStorage.getProfileGameDataDir(this, profileId, true);
        return new File(gameDataDir, packType);
    }

    private void configureSharedDirectories() {
        // 正版 MC 读 MC 原版目录，否则读启动器共享目录
        File gameDataDir = null;
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion != null && currentVersion.isInstalled) {
            gameDataDir = LauncherStorage.getInstalledMinecraftGameDataDir(this, true);
        }
        if (gameDataDir == null) {
            gameDataDir = LauncherStorage.getSharedGameDataDir(this, true);
        }
        contentManager.setStorageDirectories(
                new File(gameDataDir, "minecraftWorlds"),
                new File(gameDataDir, "resource_packs"),
                new File(gameDataDir, "behavior_packs"),
                new File(gameDataDir, "skin_packs"),
                new File(gameDataDir, "Screenshots"),
                new File(gameDataDir, "minecraftpe"));
        contentManager.setStructuresDirectory(new File(gameDataDir, "structures"));
    }

    private void configureDirectories() {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return;
        File gameDataDir = getGameDataDirForType(currentStorageType);
        if (gameDataDir == null) return;
        contentManager.setStorageDirectories(
                new File(gameDataDir, "minecraftWorlds"),
                new File(gameDataDir, "resource_packs"),
                new File(gameDataDir, "behavior_packs"),
                new File(gameDataDir, "skin_packs"),
                new File(gameDataDir, "Screenshots"),
                new File(gameDataDir, "minecraftpe"));
        contentManager.setStructuresDirectory(new File(gameDataDir, "structures"));
    }

    private File getWorldsDirectoryForType(FeatureSettings.StorageType storageType) {
        File gameDataDir = getGameDataDirForType(storageType);
        return gameDataDir == null ? null : new File(gameDataDir, "minecraftWorlds");
    }

    private File getPackDirectoryForType(FeatureSettings.StorageType storageType, String packType) {
        File gameDataDir = getGameDataDirForType(storageType);
        return gameDataDir == null ? null : new File(gameDataDir, packType);
    }

    private File getGameDataDirForType(FeatureSettings.StorageType storageType) {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return null;
        // 正版/盗版统一走启动器重定向目录（与游戏运行时 getExternalFilesDir 一致），
        // 避免 Android 11+ 无法写入正版 MC 原版 Android/data 目录。
        FeatureSettings.StorageType resolvedType = LauncherStorage.normalizeContentStorageType(
                storageType,
                currentVersion.versionIsolation
        );
        return LauncherStorage.getContentGameDataDir(this, currentVersion.getStorageProfileId(), resolvedType);
    }

    private FeatureSettings.StorageType parseStorageType(String value) {
        try {
            return FeatureSettings.StorageType.valueOf(value);
        } catch (Exception ignored) {
            return FeatureSettings.StorageType.INTERNAL;
        }
    }

    // ------------------------------------------------------------------
    // 选择模式 / 批量操作 / 空状态（同步自上游，含本地「取消全选」切换）
    // ------------------------------------------------------------------

    private boolean isPackType() {
        return contentType == TYPE_SKIN_PACKS || contentType == TYPE_RESOURCE_PACKS || contentType == TYPE_BEHAVIOR_PACKS;
    }

    private void enterSelectionMode() {
        if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.setSelectionMode(true);
        else if (isPackType() && packsAdapter != null) packsAdapter.setSelectionMode(true);
        updateSelectionToolbar();
    }

    private void exitSelectionMode() {
        if (worldsAdapter != null) worldsAdapter.setSelectionMode(false);
        if (packsAdapter != null) packsAdapter.setSelectionMode(false);
        updateSelectionToolbar();
    }

    private boolean isSelectionMode() {
        if (contentType == TYPE_WORLDS) return worldsAdapter != null && worldsAdapter.isSelectionMode();
        if (isPackType()) return packsAdapter != null && packsAdapter.isSelectionMode();
        return false;
    }

    private int getSelectedCount() {
        if (contentType == TYPE_WORLDS) return worldsAdapter != null ? worldsAdapter.getSelectedCount() : 0;
        if (isPackType()) return packsAdapter != null ? packsAdapter.getSelectedCount() : 0;
        return 0;
    }

    private boolean areAllVisibleSelected() {
        if (contentType == TYPE_WORLDS) return worldsAdapter != null && worldsAdapter.areAllVisibleSelected();
        if (isPackType()) return packsAdapter != null && packsAdapter.areAllVisibleSelected();
        return false;
    }

    private void updateSelectionToolbar() {
        boolean active = isSelectionMode();
        int count = getSelectedCount();
        binding.normalToolbar.setVisibility(active ? View.GONE : View.VISIBLE);
        binding.selectionToolbar.setVisibility(active ? View.VISIBLE : View.GONE);
        binding.selectionCountText.setText(getString(R.string.selected_count, count));
        binding.batchExportButton.setEnabled(count > 0);
        binding.batchTransferButton.setEnabled(count > 0);
        binding.batchDeleteButton.setEnabled(count > 0);
        // 全选按钮文字/图标在「全选」与「取消全选」之间切换（同一位置）
        binding.selectAllButton.setText(areAllVisibleSelected() ? R.string.cancel_select_all : R.string.select_all);
    }

    private void toggleSelectAllVisible() {
        if (areAllVisibleSelected()) {
            if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.clearSelection();
            else if (isPackType() && packsAdapter != null) packsAdapter.clearSelection();
        } else {
            if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.selectAllVisible();
            else if (isPackType() && packsAdapter != null) packsAdapter.selectAllVisible();
        }
        updateSelectionToolbar();
    }

    private void handleBackNavigation() {
        if (isSelectionMode()) exitSelectionMode();
        else finish();
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void restoreSelectionState(Bundle state) {
        if (state == null || !state.getBoolean(STATE_SELECTION_MODE, false)) return;
        ArrayList<String> paths = state.getStringArrayList(STATE_SELECTED_PATHS);
        if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.restoreSelection(paths, true);
        else if (isPackType() && packsAdapter != null) packsAdapter.restoreSelection(paths, true);
        updateSelectionToolbar();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SELECTION_MODE, isSelectionMode());
        if (contentType == TYPE_WORLDS && worldsAdapter != null) outState.putStringArrayList(STATE_SELECTED_PATHS, worldsAdapter.getSelectedPaths());
        else if (isPackType() && packsAdapter != null) outState.putStringArrayList(STATE_SELECTED_PATHS, packsAdapter.getSelectedPaths());
    }

    private void updateListState(int visibleCount, int totalCount, String query) {
        binding.itemCountText.setText(String.valueOf(totalCount));
        boolean empty = visibleCount == 0;
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.contentRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) return;

        if (query != null && !query.isEmpty()) {
            binding.emptyStateTitle.setText(getString(R.string.no_search_results, query));
            binding.emptyStateMessage.setText("");
        } else if (contentType == TYPE_WORLDS) {
            binding.emptyStateTitle.setText(R.string.no_worlds_found);
            binding.emptyStateMessage.setText(R.string.no_worlds_message);
        } else if (isPackType()) {
            binding.emptyStateTitle.setText(R.string.no_packs_found);
            binding.emptyStateMessage.setText(R.string.no_packs_message);
        } else if (contentType == TYPE_SERVERS) {
            binding.emptyStateTitle.setText(R.string.no_servers_found);
            binding.emptyStateMessage.setText(R.string.no_servers_message);
        } else if (contentType == TYPE_STRUCTURES) {
            binding.emptyStateTitle.setText(R.string.no_structures_found_list);
            binding.emptyStateMessage.setText(R.string.no_structures_message_list);
        } else {
            binding.emptyStateTitle.setText(R.string.no_screenshots_found);
            binding.emptyStateMessage.setText(R.string.no_screenshots_message);
        }

        if (contentType == TYPE_WORLDS) binding.emptyStateIcon.setImageResource(R.drawable.ic_world);
        else if (contentType == TYPE_BEHAVIOR_PACKS) binding.emptyStateIcon.setImageResource(R.drawable.ic_behavior);
        else if (contentType == TYPE_SKIN_PACKS) binding.emptyStateIcon.setImageResource(R.drawable.ic_tshirt);
        else if (contentType == TYPE_STRUCTURES) binding.emptyStateIcon.setImageResource(R.drawable.ic_structure);
        else binding.emptyStateIcon.setImageResource(R.drawable.ic_photo);
    }

    private List<WorldItem> getSelectedWorlds() {
        return worldsAdapter != null ? worldsAdapter.getSelectedItems(allWorlds) : new ArrayList<>();
    }

    private List<ResourcePackItem> getSelectedPacks() {
        return packsAdapter != null ? packsAdapter.getSelectedItems(allPacks) : new ArrayList<>();
    }

    private void startBatchExport() {
        if (contentType == TYPE_WORLDS) {
            pendingBatchWorlds = getSelectedWorlds();
            pendingBatchPacks.clear();
            if (pendingBatchWorlds.isEmpty()) return;
        } else if (isPackType()) {
            pendingBatchPacks = getSelectedPacks();
            pendingBatchWorlds.clear();
            if (pendingBatchPacks.isEmpty()) return;
        } else {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        batchExportFolderLauncher.launch(intent);
    }

    private Uri createExportDocument(Uri treeUri, String displayName) {
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            return DocumentsContract.createDocument(getContentResolver(), parent, "application/octet-stream", displayName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String uniqueExportName(String name, String extension, Set<String> usedNames) {
        String base = name == null ? "content" : name.trim();
        base = base.replace('\\', '_').replaceAll("[/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) base = "content";
        if (base.length() > 96) base = base.substring(0, 96).trim();
        String candidate = base + extension;
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase())) {
            candidate = base + " (" + suffix++ + ")" + extension;
        }
        return candidate;
    }

    private void exportWorldBatch(Uri treeUri, List<WorldItem> items, int index, int success, int failed, Set<String> usedNames) {
        if (index >= items.size()) {
            finishBatch(success, failed, false);
            return;
        }
        runOnUiThread(() -> showProgressDialog(getString(R.string.batch_exporting, index + 1, items.size())));
        WorldItem world = items.get(index);
        Uri output = createExportDocument(treeUri, uniqueExportName(world.getWorldName(), ".mcworld", usedNames));
        if (output == null) {
            exportWorldBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            return;
        }
        contentManager.exportWorld(world, output, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                exportWorldBatch(treeUri, items, index + 1, success + 1, failed, usedNames);
            }

            @Override
            public void onError(String error) {
                exportWorldBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void exportPackBatch(Uri treeUri, List<ResourcePackItem> items, int index, int success, int failed, Set<String> usedNames) {
        if (index >= items.size()) {
            finishBatch(success, failed, false);
            return;
        }
        runOnUiThread(() -> showProgressDialog(getString(R.string.batch_exporting, index + 1, items.size())));
        ResourcePackItem pack = items.get(index);
        Uri output = createExportDocument(treeUri, uniqueExportName(pack.getPackName(), ".mcpack", usedNames));
        if (output == null) {
            exportPackBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            return;
        }
        contentManager.exportResourcePack(pack, output, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                exportPackBatch(treeUri, items, index + 1, success + 1, failed, usedNames);
            }

            @Override
            public void onError(String error) {
                exportPackBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void showBatchDeleteDialog() {
        int count = getSelectedCount();
        if (count == 0) return;
        boolean worlds = contentType == TYPE_WORLDS;
        new CustomAlertDialog(this)
                .setTitleText(getString(worlds ? R.string.batch_delete_worlds_title : R.string.batch_delete_packs_title))
                .setMessage(getString(worlds ? R.string.batch_delete_worlds_message : R.string.batch_delete_packs_message, count))
                .setPositiveButton(getString(R.string.dialog_positive_delete), v -> {
                    if (worlds) deleteWorldBatch(getSelectedWorlds(), 0, 0, 0);
                    else deletePackBatch(getSelectedPacks(), 0, 0, 0);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void deleteWorldBatch(List<WorldItem> items, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        showProgressDialog(getString(R.string.batch_deleting, index + 1, items.size()));
        contentManager.deleteWorld(items.get(index), false, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> deleteWorldBatch(items, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> deleteWorldBatch(items, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void deletePackBatch(List<ResourcePackItem> items, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        showProgressDialog(getString(R.string.batch_deleting, index + 1, items.size()));
        contentManager.deleteResourcePack(items.get(index), false, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> deletePackBatch(items, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> deletePackBatch(items, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void showBatchTransferDialog() {
        if (getSelectedCount() == 0) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_transfer_content, null);
        Spinner targetSpinner = dialogView.findViewById(R.id.target_version_spinner);

        List<GameVersion> allVersions = getAllVersions();
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.transfer_to_shared));
        for (GameVersion v : allVersions) labels.add(v.displayName);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.transfer_content))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.transfer), v -> {
                    int pos = targetSpinner.getSelectedItemPosition();
                    boolean shared = pos <= 0;
                    String profileId = shared ? null : allVersions.get(pos - 1).getStorageProfileId();
                    if (contentType == TYPE_WORLDS) {
                        File targetDir = getWorldsDirectoryForTarget(shared, profileId);
                        if (targetDir == null) {
                            Toast.makeText(this, getString(R.string.transfer_failed), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        transferWorldBatch(getSelectedWorlds(), targetDir, 0, 0, 0);
                    } else {
                        String packType = contentType == TYPE_BEHAVIOR_PACKS ? "behavior_packs" :
                                (contentType == TYPE_SKIN_PACKS ? "skin_packs" : "resource_packs");
                        File targetDir = getPackDirectoryForTarget(shared, profileId, packType);
                        if (targetDir == null) {
                            Toast.makeText(this, getString(R.string.transfer_failed), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        transferPackBatch(getSelectedPacks(), targetDir, 0, 0, 0);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void transferWorldBatch(List<WorldItem> items, File targetDir, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        showProgressDialog(getString(R.string.batch_transferring, index + 1, items.size()));
        contentManager.transferWorld(items.get(index), targetDir, false, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> transferWorldBatch(items, targetDir, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> transferWorldBatch(items, targetDir, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void transferPackBatch(List<ResourcePackItem> items, File targetDir, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        showProgressDialog(getString(R.string.batch_transferring, index + 1, items.size()));
        contentManager.transferResourcePack(items.get(index), targetDir, false, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> transferPackBatch(items, targetDir, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> transferPackBatch(items, targetDir, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void finishBatch(int success, int failed, boolean refresh) {
        runOnUiThread(() -> {
            hideProgressDialog();
            Toast.makeText(this, getString(R.string.batch_result, success, failed), failed > 0 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            exitSelectionMode();
            if (refresh) loadContent();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 自定义存储路径可能在设置页被切换，重新计算目录避免显示旧路径的包
        if (sharedMode) {
            configureSharedDirectories();
        } else {
            configureDirectories();
        }
        loadContent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (structureExtractor != null) {
            structureExtractor.shutdown();
        }
        if (screenshotsAdapter != null) {
            screenshotsAdapter.shutdown();
        }
    }
}
