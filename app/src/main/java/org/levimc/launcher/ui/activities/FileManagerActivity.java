package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.filemanager.logic.entry.FmEntry;
import org.levimc.launcher.filemanager.logic.task.TaskProgress;
import org.levimc.launcher.filemanager.logic.task.TaskState;
import org.levimc.launcher.filemanager.logic.trash.TrashItem;
import org.levimc.launcher.filemanager.ui.FmDialogs;
import org.levimc.launcher.filemanager.ui.FmEntryAdapter;
import org.levimc.launcher.filemanager.ui.FmFlowBridgeKt;
import org.levimc.launcher.filemanager.ui.FmTrashAdapter;
import org.levimc.launcher.filemanager.viewmodel.DialogIntent;
import org.levimc.launcher.filemanager.viewmodel.FileManagerUiState;
import org.levimc.launcher.filemanager.viewmodel.FileManagerViewModel;
import org.levimc.launcher.filemanager.viewmodel.FmInitState;
import org.levimc.launcher.filemanager.viewmodel.SearchUiState;
import org.levimc.launcher.filemanager.viewmodel.TrashItemView;
import org.levimc.launcher.filemanager.viewmodel.TrashListView;
import org.levimc.launcher.filemanager.viewmodel.TrashViewState;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件管理器（v666：Zalith Launcher 2 文件管理器 View 体系重写）。
 * 数据层为 org.levimc.launcher.filemanager（Zalith logic 层移植，v664）。
 * 竖屏：列表 + 底部栏；横屏：自适应列数网格 + 左侧导航栏。
 */
public class FileManagerActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";

    private static final int SAF_NONE = 0;
    private static final int SAF_COMPRESS = 1;
    private static final int SAF_EXTRACT = 2;
    private static final int SAF_IMPORT_FILES = 3;
    private static final int SAF_IMPORT_DIR = 4;

    private FileManagerViewModel vm;
    private FileManagerUiState lastState;
    private SearchUiState lastSearchUi;
    private String lastDialogKey = "";
    private String lastSnackbar = "";
    private int pendingSaf = SAF_NONE;

    private RecyclerView recycler;
    private RecyclerView trashRecycler;
    private TextView txtPath;
    private TextView emptyView;
    private TextView txtClip;
    private TextView taskText;
    private ProgressBar taskProgress;
    private View clipBar;
    private View taskBanner;
    private View navRail;
    private View bottomBar;
    private View selectionBar;

    private FmEntryAdapter entryAdapter;
    private FmTrashAdapter trashAdapter;

    private boolean showingTrash;
    private boolean landscape;
    private boolean multiSelect;
    private int accent;
    private int onSurface;
    private int textSecondary;

    private final ActivityResultLauncher<Intent> safLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                int saf = pendingSaf;
                pendingSaf = SAF_NONE;
                if (data == null || data.getData() == null) {
                    switch (saf) {
                        case SAF_COMPRESS: vm.onCompressOutputPickedCancelled(); break;
                        case SAF_EXTRACT: vm.onExtractOutputPickedCancelled(); break;
                        case SAF_IMPORT_FILES:
                        case SAF_IMPORT_DIR: vm.onImportCancelled(); break;
                        default: break;
                    }
                    return;
                }
                switch (saf) {
                    case SAF_COMPRESS: vm.onCompressOutputPicked(data.getData()); break;
                    case SAF_EXTRACT: vm.onExtractOutputPicked(data.getData()); break;
                    case SAF_IMPORT_DIR: vm.onImportDir(data.getData()); break;
                    case SAF_IMPORT_FILES: {
                        List<Uri> uris = new ArrayList<>();
                        if (data.getClipData() != null) {
                            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                                uris.add(data.getClipData().getItemAt(i).getUri());
                            }
                        } else {
                            uris.add(data.getData());
                        }
                        vm.onImportFiles(uris);
                        break;
                    }
                    default: break;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        bindViews();
        landscape = getResources().getConfiguration().smallestScreenWidthDp >= 600
                || getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        accent = new PersonalizationManager(this).getAccentColor();
        onSurface = getResources().getColor(R.color.on_surface, getTheme());
        textSecondary = getResources().getColor(R.color.text_secondary, getTheme());

        String rootPath = getIntent().getStringExtra(EXTRA_PATH);
        if (rootPath == null || rootPath.isEmpty()) {
            rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        }

        SavedStateHandle handle = new SavedStateHandle();
        handle.set(FileManagerViewModel.KEY_ROOT_PATH, rootPath);
        vm = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                return (T) new FileManagerViewModel(getApplicationContext(), handle);
            }
        }).get(FileManagerViewModel.class);

        setupAdapters();
        setupListeners();
        applyLayoutMode();

        vm.initialize();
        FmFlowBridgeKt.collectFlow(this, vm.getInitState(), init -> {
            if (init instanceof FmInitState.Failed) {
                FmDialogs.dismissLast();
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                        .setTitleText(getString(R.string.fm_ui_init_failed))
                        .setMessage(((FmInitState.Failed) init).getMessage())
                        .setPositiveButton(getString(R.string.confirm), v -> finish())
                        .show();
            }
        });
        FmFlowBridgeKt.collectFlow(this, vm.getState(), this::onState);
        FmFlowBridgeKt.collectFlow(this, vm.getSearchUi(), this::onSearchUi);
        FmFlowBridgeKt.collectFlow(this, vm.getErrorEvents(), msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void bindViews() {
        recycler = findViewById(R.id.fm_recycler);
        trashRecycler = findViewById(R.id.fm_trash_recycler);
        txtPath = findViewById(R.id.fm_txt_path);
        emptyView = findViewById(R.id.fm_empty);
        txtClip = findViewById(R.id.fm_txt_clip);
        taskText = findViewById(R.id.fm_task_text);
        taskProgress = findViewById(R.id.fm_task_progress);
        clipBar = findViewById(R.id.fm_clip_bar);
        taskBanner = findViewById(R.id.fm_task_banner);
        navRail = findViewById(R.id.fm_nav_rail);
        bottomBar = findViewById(R.id.fm_bottom_bar);
        selectionBar = findViewById(R.id.fm_selection_bar);
    }

    private FmEntryAdapter.Listener entryListener() {
        return new FmEntryAdapter.Listener() {
            @Override
            public void onEntryClick(FmEntry entry) {
                if (entry.isDirectory()) {
                    vm.enterDirectory(entry);
                } else {
                    FmDialogs.openFile(FileManagerActivity.this, entry);
                }
            }

            @Override
            public void onEntryLongClick(FmEntry entry) {
                vm.toggleSelection(entry);
            }

            @Override
            public void onMoreClick(FmEntry entry) {
                FmDialogs.showEntryMenu(FileManagerActivity.this, vm, entry, () -> {});
            }
        };
    }

    private void setupAdapters() {
        entryAdapter = new FmEntryAdapter(entryListener(), java.util.Collections.emptySet(),
                false, accent, onSurface, textSecondary);
        recycler.setAdapter(entryAdapter);

        trashAdapter = new FmTrashAdapter(new FmTrashAdapter.Listener() {
            @Override
            public void onItemClick(TrashItemView item) {
                TrashItem full = findTrashItem(item.getUuid());
                if (full != null) {
                    FmDialogs.showTrashItemMenu(FileManagerActivity.this, vm, full);
                }
            }

            @Override
            public void onItemLongClick(TrashItemView item) {
                vm.toggleTrashSelection(item.getUuid());
            }
        }, java.util.Collections.emptySet(), false, accent, onSurface, textSecondary);
        trashRecycler.setAdapter(trashAdapter);
        trashRecycler.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupListeners() {
        findViewById(R.id.fm_btn_back).setOnClickListener(v -> {
            if (showingTrash) {
                showFiles();
            } else if (!vm.consumeBack()) {
                finish();
            }
        });
        txtPath.setOnClickListener(v -> FmDialogs.showJumpDialog(this, vm,
                lastState != null && lastState.getCurrentDir() != null
                        ? lastState.getCurrentDir().toString() : ""));
        findViewById(R.id.fm_btn_search).setOnClickListener(v -> vm.showSearchDialog());
        findViewById(R.id.fm_btn_sort).setOnClickListener(v -> FmDialogs.showSortDialog(this, vm,
                lastState != null ? lastState.getSortConfig()
                        : new org.levimc.launcher.filemanager.viewmodel.SortConfig()));
        findViewById(R.id.fm_btn_multi).setOnClickListener(v -> {
            if (showingTrash) {
                if (multiSelect) vm.clearTrashSelection();
                else vm.selectAllTrash();
            } else {
                if (multiSelect) vm.clearSelection();
                else vm.selectAll();
            }
        });
        findViewById(R.id.fm_btn_more).setOnClickListener(v -> showMoreMenu());

        findViewById(R.id.fm_btn_clip_paste).setOnClickListener(v -> vm.requestPaste());
        findViewById(R.id.fm_btn_clip_cancel).setOnClickListener(v -> vm.clearClipboard());
        findViewById(R.id.fm_btn_task_cancel).setOnClickListener(v -> vm.cancelCurrentTask());

        findViewById(R.id.fm_bottom_home).setOnClickListener(v -> showFiles());
        findViewById(R.id.fm_bottom_trash).setOnClickListener(v -> showTrash());
        findViewById(R.id.fm_rail_home).setOnClickListener(v -> showFiles());
        findViewById(R.id.fm_rail_trash).setOnClickListener(v -> showTrash());

        findViewById(R.id.fm_btn_sel_all).setOnClickListener(v -> {
            if (showingTrash) vm.selectAllTrash();
            else vm.selectAll();
        });
        findViewById(R.id.fm_btn_sel_copy).setOnClickListener(v -> {
            if (!showingTrash) vm.bulkCopy();
        });
        findViewById(R.id.fm_btn_sel_cut).setOnClickListener(v -> {
            if (!showingTrash) vm.bulkCut();
        });
        findViewById(R.id.fm_btn_sel_compress).setOnClickListener(v -> {
            if (showingTrash) {
                List<TrashItem> items = vm.selectedTrashItems();
                if (!items.isEmpty()) vm.beginTrashRestore(items);
            } else {
                vm.bulkCompress();
            }
        });
        findViewById(R.id.fm_btn_sel_delete).setOnClickListener(v -> {
            if (showingTrash) {
                List<TrashItem> items = vm.selectedTrashItems();
                if (!items.isEmpty()) confirmTrashPurge(items);
            } else {
                int count = lastState != null ? lastState.getSelection().size() : 0;
                if (count > 0) FmDialogs.showBatchDeleteConfirm(this, vm, count);
            }
        });
        findViewById(R.id.fm_btn_sel_exit).setOnClickListener(v -> {
            if (showingTrash) vm.clearTrashSelection();
            else vm.clearSelection();
        });
    }

    private void showMoreMenu() {
        final boolean hasClip = lastState != null && lastState.getClipboard() != null;
        final List<String> items = new ArrayList<>();
        items.add(getString(R.string.fm_ui_new_folder));
        items.add(getString(R.string.fm_ui_new_file));
        if (hasClip) items.add(getString(R.string.fm_ui_paste));
        items.add(getString(R.string.fm_ui_jump));
        items.add(getString(R.string.fm_ui_sort));
        items.add(getString(R.string.fm_ui_hidden) + (lastState != null && lastState.getShowHidden() ? " ✓" : ""));
        items.add(getString(R.string.fm_ui_clear_trash));
        new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.fm_ui_more))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String picked = items.get(which);
                    if (picked.equals(getString(R.string.fm_ui_new_folder))) {
                        FmDialogs.showCreateDialog(this, vm, true);
                    } else if (picked.equals(getString(R.string.fm_ui_new_file))) {
                        FmDialogs.showCreateDialog(this, vm, false);
                    } else if (picked.equals(getString(R.string.fm_ui_paste))) {
                        vm.requestPaste();
                    } else if (picked.equals(getString(R.string.fm_ui_jump))) {
                        FmDialogs.showJumpDialog(this, vm,
                                lastState != null && lastState.getCurrentDir() != null
                                        ? lastState.getCurrentDir().toString() : "");
                    } else if (picked.equals(getString(R.string.fm_ui_sort))) {
                        FmDialogs.showSortDialog(this, vm,
                                lastState != null ? lastState.getSortConfig()
                                        : new org.levimc.launcher.filemanager.viewmodel.SortConfig());
                    } else if (picked.startsWith(getString(R.string.fm_ui_hidden))) {
                        vm.toggleHidden();
                    } else if (picked.equals(getString(R.string.fm_ui_clear_trash))) {
                        confirmTrashClear();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void confirmTrashPurge(List<TrashItem> items) {
        new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.fm_ui_delete_confirm))
                .setMessage(getString(R.string.fm_ui_delete_batch_msg, items.size()))
                .setPositiveButton(getString(R.string.fm_ui_purge), v -> vm.trashPurge(items))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void confirmTrashClear() {
        int total = 0;
        if (lastState != null && lastState.getTrashView() instanceof TrashViewState.Opened) {
            total = ((TrashViewState.Opened) lastState.getTrashView()).getTrashListView().getTotal();
        }
        if (total <= 0) {
            Toast.makeText(this, R.string.fm_ui_trash_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.fm_ui_clear_trash))
                .setMessage(getString(R.string.fm_ui_confirm_clear_trash, total))
                .setPositiveButton(getString(R.string.fm_ui_clear_trash), v -> vm.trashClear())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showFiles() {
        showingTrash = false;
        vm.closeTrash();
        trashRecycler.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        renderEmptyState();
    }

    private void showTrash() {
        showingTrash = true;
        vm.loadTrashList();
        recycler.setVisibility(View.GONE);
        trashRecycler.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }

    // ---------------- 状态渲染 ----------------

    private void onState(FileManagerUiState s) {
        lastState = s;

        if (s.getCurrentDir() != null) {
            txtPath.setText(s.getCurrentDir().toString());
        }
        // 热路径防护：任务进度等高频更新不重建适配器，只按引用比较增量刷新
        if (entryAdapter == null || entryAdapter.getBoundList() != s.getVisibleEntries()) {
            entryAdapter = new FmEntryAdapter(entryListener(), s.getSelection(), s.getMultiSelect(),
                    accent, onSurface, textSecondary);
            entryAdapter.setViewType(landscape ? FmEntryAdapter.VIEW_CARD : FmEntryAdapter.VIEW_LIST);
            recycler.setAdapter(entryAdapter);
            entryAdapter.submit(s.getVisibleEntries());
        } else {
            entryAdapter.updateSelection(s.getSelection(), s.getMultiSelect());
        }

        multiSelect = s.getMultiSelect();
        renderEmptyState();
        renderSelectionBar();
        renderClipboard(s);
        renderTask(s);
        renderTrash(s);
        renderSnackbar(s);
        renderDialog(s);
    }

    private TrashItem findTrashItem(String uuid) {
        if (lastState == null || !(lastState.getTrashView() instanceof TrashViewState.Opened)) {
            return null;
        }
        for (TrashItem item : ((TrashViewState.Opened) lastState.getTrashView()).getRawItems()) {
            if (item.getUuid().equals(uuid)) return item;
        }
        return null;
    }

    private void renderEmptyState() {
        if (showingTrash) return;
        boolean empty = lastState == null || lastState.getVisibleEntries().isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyView.setText(R.string.fm_ui_empty_dir);
    }

    private void renderSelectionBar() {
        selectionBar.setVisibility(multiSelect ? View.VISIBLE : View.GONE);
        if (landscape) {
            navRail.setVisibility(multiSelect ? View.GONE : View.VISIBLE);
        } else {
            bottomBar.setVisibility(multiSelect ? View.GONE : View.VISIBLE);
        }
        findViewById(R.id.fm_btn_sel_copy).setVisibility(showingTrash ? View.GONE : View.VISIBLE);
        findViewById(R.id.fm_btn_sel_cut).setVisibility(showingTrash ? View.GONE : View.VISIBLE);
    }

    private void renderClipboard(FileManagerUiState s) {
        if (s.getClipboard() != null) {
            clipBar.setVisibility(View.VISIBLE);
            int n = s.getClipboard().getSources().size();
            txtClip.setText(getString(s.getClipboard().isCut() ? R.string.fm_ui_clip_cut : R.string.fm_ui_clip_copy, n));
        } else {
            clipBar.setVisibility(View.GONE);
        }
    }

    private void renderTask(FileManagerUiState s) {
        TaskState ts = s.getTaskState();
        TaskProgress tp = s.getTaskProgress();
        if (ts instanceof TaskState.Busy) {
            taskBanner.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder(getString(R.string.fm_ui_task));
            if (tp != null) {
                if (tp.getCurrentName() != null) {
                    sb.append(" · ").append(tp.getCurrentName());
                }
                if (tp.getTotal() > 0) {
                    sb.append(" · ").append(tp.getCompleted()).append('/').append(tp.getTotal());
                }
            }
            taskText.setText(sb.toString());
            if (tp != null && tp.getTotal() > 0) {
                taskProgress.setIndeterminate(false);
                taskProgress.setProgress((int) (tp.getRatio() * 1000));
            } else {
                taskProgress.setIndeterminate(true);
            }
        } else {
            taskBanner.setVisibility(View.GONE);
        }
    }

    private void renderTrash(FileManagerUiState s) {
        TrashViewState tv = s.getTrashView();
        if (tv instanceof TrashViewState.Opened) {
            TrashListView list = ((TrashViewState.Opened) tv).getTrashListView();
            trashAdapter.submit(list.getItems());
            if (showingTrash) {
                if (list.getItems().isEmpty() && !list.getLoading()) {
                    emptyView.setText(R.string.fm_ui_trash_empty);
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    emptyView.setVisibility(View.GONE);
                }
            }
        } else if (showingTrash) {
            trashAdapter.submit(new ArrayList<>());
            emptyView.setText(R.string.fm_ui_loading);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    private void renderSnackbar(FileManagerUiState s) {
        if (s.getSnackbar() == null) {
            lastSnackbar = "";
            return;
        }
        String text = s.getSnackbar().getText();
        if (!text.equals(lastSnackbar)) {
            lastSnackbar = text;
            Toast.makeText(this, text, s.getSnackbar().getLong() ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    private void onSearchUi(SearchUiState ui) {
        lastSearchUi = ui;
    }

    // ---------------- 对话框分发 ----------------

    private String dialogKey(DialogIntent intent) {
        if (intent == null) return "null";
        StringBuilder key = new StringBuilder(intent.getClass().getName());
        if (intent instanceof DialogIntent.PasteConflict) {
            key.append('#').append(((DialogIntent.PasteConflict) intent).getCurrentIndex());
        } else if (intent instanceof DialogIntent.TrashRestoreConflict) {
            key.append('#').append(((DialogIntent.TrashRestoreConflict) intent).getPendingIndex());
        }
        return key.toString();
    }

    private void renderDialog(FileManagerUiState s) {
        DialogIntent intent = s.getDialogIntent();
        String key = dialogKey(intent);
        if (key.equals(lastDialogKey)) return;
        lastDialogKey = key;
        FmDialogs.dismissLast();
        if (intent == null) return;

        if (intent instanceof DialogIntent.Search) {
            FmDialogs.showSearchSetup(this, vm);
        } else if (intent instanceof DialogIntent.SearchTask) {
            new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                    .setTitleText(getString(R.string.fm_ui_search))
                    .setMessage(getString(R.string.fm_ui_loading))
                    .show();
        } else if (intent instanceof DialogIntent.SearchResult) {
            FmDialogs.showSearchResults(this, vm, lastSearchUi);
        } else if (intent instanceof DialogIntent.CompressSetup) {
            FmDialogs.showCompressSetup(this, vm, (DialogIntent.CompressSetup) intent);
        } else if (intent instanceof DialogIntent.CompressOutputChoice) {
            FmDialogs.showOutputChoice(this, vm, false);
        } else if (intent instanceof DialogIntent.CompressOutputPick) {
            pendingSaf = SAF_COMPRESS;
            launchSaf(false, false);
        } else if (intent instanceof DialogIntent.CompressConflict) {
            FmDialogs.showConflict(this, vm, ((DialogIntent.CompressConflict) intent).getFileName(), false);
        } else if (intent instanceof DialogIntent.ExtractSetup) {
            FmDialogs.showExtractSetup(this, vm, (DialogIntent.ExtractSetup) intent);
        } else if (intent instanceof DialogIntent.ExtractOutputChoice) {
            FmDialogs.showOutputChoice(this, vm, true);
        } else if (intent instanceof DialogIntent.ExtractOutputPick) {
            pendingSaf = SAF_EXTRACT;
            launchSaf(false, false);
        } else if (intent instanceof DialogIntent.ExtractConflict) {
            FmDialogs.showConflict(this, vm, ((DialogIntent.ExtractConflict) intent).getName(), true);
        } else if (intent instanceof DialogIntent.ExtractPassword) {
            FmDialogs.showExtractPassword(this, vm, ((DialogIntent.ExtractPassword) intent).getErrorText());
        } else if (intent instanceof DialogIntent.ImportFiles) {
            pendingSaf = SAF_IMPORT_FILES;
            launchSaf(true, true);
        } else if (intent instanceof DialogIntent.ImportDir) {
            pendingSaf = SAF_IMPORT_DIR;
            launchSaf(false, false);
        } else if (intent instanceof DialogIntent.PasteConflict) {
            FmDialogs.showPasteConflict(this, vm, (DialogIntent.PasteConflict) intent);
        } else if (intent instanceof DialogIntent.TrashRestoreConflict) {
            FmDialogs.showTrashRestoreConflict(this, vm, (DialogIntent.TrashRestoreConflict) intent);
        }
    }

    private void launchSaf(boolean pickFiles, boolean allowMultiple) {
        Intent i = pickFiles
                ? new Intent(Intent.ACTION_OPEN_DOCUMENT)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (pickFiles) {
            i.setType("*/*");
            if (allowMultiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            safLauncher.launch(i);
        } catch (Exception e) {
            pendingSaf = SAF_NONE;
            Toast.makeText(this, R.string.fm_ui_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 布局模式 ----------------

    private void applyLayoutMode() {
        if (landscape) {
            navRail.setVisibility(multiSelect ? View.GONE : View.VISIBLE);
            bottomBar.setVisibility(View.GONE);
            if (entryAdapter != null) entryAdapter.setViewType(FmEntryAdapter.VIEW_CARD);
            recycler.setLayoutManager(new GridLayoutManager(this, gridColumns()));
        } else {
            navRail.setVisibility(View.GONE);
            bottomBar.setVisibility(multiSelect ? View.GONE : View.VISIBLE);
            if (entryAdapter != null) entryAdapter.setViewType(FmEntryAdapter.VIEW_LIST);
            recycler.setLayoutManager(new LinearLayoutManager(this));
        }
    }

    /** Zalith 网格列数公式：columns = floor((maxWidth - 24dp + gap) / (280dp + gap)) */
    private int gridColumns() {
        float density = getResources().getDisplayMetrics().density;
        float availDp = getResources().getDisplayMetrics().widthPixels / density - 76f;
        float gap = 8f;
        int columns = (int) Math.floor((availDp - 24f + gap) / (280f + gap));
        return Math.max(1, columns);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        applyLayoutMode();
    }

    @Override
    public void onBackPressed() {
        if (showingTrash) {
            showFiles();
            return;
        }
        if (vm != null && !vm.consumeBack()) {
            super.onBackPressed();
        }
    }
}
