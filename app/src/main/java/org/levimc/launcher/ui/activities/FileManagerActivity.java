package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
import org.levimc.launcher.filemanager.editor.EditorState;
import org.levimc.launcher.filemanager.ui.FmHighlighter;
import org.levimc.launcher.filemanager.viewmodel.DialogIntent;
import org.levimc.launcher.filemanager.viewmodel.EditorUiState;
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
    /** v0.4.203：当前编辑器内容的来源窗（保存/关闭作用于它）。 */
    private FileManagerViewModel editorVm;
    private FileManagerUiState lastState;
    private SearchUiState lastSearchUi;
    private String lastDialogKey = "";
    private String lastSnackbar = "";
    private int pendingSaf = SAF_NONE;

    // v0.4.203：双窗模式（MT 管理器同款）——右窗独立 VM/列表/路径栏
    private FileManagerViewModel vmRight;
    private FileManagerUiState lastStateRight;
    private RecyclerView recyclerRight;
    private android.widget.LinearLayout pathBarRight;
    private View paneRight;
    private View paneLeft;
    private View paneDivider;
    private FmEntryAdapter entryAdapterRight;
    private boolean dualMode = false;
    private int activePane = 0; // 0=左 1=右
    /** v0.4.207：直发/粘贴新增文件高亮（名字染蓝）——左右窗各自集合。 */
    private final java.util.Set<String> leftHighlighted = new java.util.HashSet<>();
    private final java.util.Set<String> rightHighlighted = new java.util.HashSet<>();
    private java.util.Set<String> peerBeforePaths;
    /** v0.4.208：diff 归属窗——集合只对直发目标窗有意义（另一窗的条目
     *  几乎必然不在集合里→added 恒真→互相 refreshCurrentDir 死循环=频闪根因）。 */
    private boolean peerBeforeTargetIsRight;
    /** v0.4.209：空白长按计时（RecyclerView 空白处自实现长按检测）。 */
    private final java.util.Map<View, Runnable> pendingBlankLong = new java.util.HashMap<>();
    private final java.util.Map<View, float[]> blankDownXY = new java.util.HashMap<>();

    /** v0.4.209：启动空白长按计时——DOWN 位置若无子 View，超时弹路径气泡。 */
    private void armBlankLongPress(View v, FileManagerViewModel vmm, float x, float y) {
        cancelBlankLongPress(v);
        blankDownXY.put(v, new float[]{x, y});
        Runnable r = () -> {
            pendingBlankLong.remove(v);
            blankDownXY.remove(v);
            android.view.View child = v instanceof RecyclerView
                    ? ((RecyclerView) v).findChildViewUnder(x, y) : null;
            if (child == null) {
                org.levimc.launcher.util.OnlineDebugLog.log("fm-bubble 空白长按 view=" + v.getClass().getSimpleName());
                FmDialogs.showPathBubble(FileManagerActivity.this, vmm, v, x, y);
            }
        };
        pendingBlankLong.put(v, r);
        v.postDelayed(r, android.view.ViewConfiguration.getLongPressTimeout());
    }

    private void cancelBlankLongPress(View v) {
        Runnable r = pendingBlankLong.remove(v);
        blankDownXY.remove(v);
        if (r != null) {
            v.removeCallbacks(r);
        }
    }

    /** v0.4.209：MOVE 位移是否超过 touch slop（超了取消长按，防滚动误触）。 */
    private boolean movedBeyondSlop(View v, float x, float y, int slop) {
        float[] down = blankDownXY.get(v);
        if (down == null) return false;
        return Math.abs(x - down[0]) > slop || Math.abs(y - down[1]) > slop;
    }
    private String lastLeftDir = "";
    private String lastRightDir = "";

    private RecyclerView recycler;
    private RecyclerView trashRecycler;
    private android.widget.LinearLayout pathBar;
    private String fmRootPath;
    private TextView emptyView;

    private TextView taskText;
    private ProgressBar taskProgress;

    private View taskBanner;
    private View navRail;
    private View bottomBar;
    private View selectionBar;
    private View topBar;
    private View contentFrame;

    // 内置编辑器（v668）
    private View editorRoot;
    private TextView editorName;
    private TextView editorDirty;
    private TextView editorSave;
    private EditText editorText;
    private boolean applyingHighlight;
    private boolean editorUserEdited;
    private boolean exitConfirmShown;
    private String lastEditorPath;
    /** v0.4.142：大文件截断只读预览模式（隐藏保存/只读开关，跳过高亮）。 */
    private boolean editorPreviewOnly;
    private ProgressBar editorLoading;
    private TextView editorNotice;
    /** 只读模式（v670）：打开即只读（睁眼），点眼睛切换编辑（笔）；文件不可写时恒只读。 */
    private boolean editorReadonly = true;
    private boolean editorFileWritable = true;
    private ImageView editorReadonlyToggle;
    private android.text.method.KeyListener editorDefaultKeyListener;
    private org.levimc.launcher.filemanager.ui.FmEditorScrollBar editorScrollBar;
    private final Handler highlightHandler = new Handler(Looper.getMainLooper());
    private final Runnable highlightTask = this::runHighlight;

    // 音频播放（v669 恢复旧版）
    private MediaPlayer mediaPlayer;
    private View audioPanel;
    private ImageView audioPlayPause;
    private TextView audioName;
    private TextView audioCurrent;
    private TextView audioDuration;
    private SeekBar audioSeek;
    private final Handler audioHandler = new Handler(Looper.getMainLooper());
    private String audioSourcePath;
    /** v0.4.89：根目录双击退出计时。 */
    private long lastBackClick;

    private FmEntryAdapter entryAdapter;
    private FmTrashAdapter trashAdapter;

    private boolean showingTrash;
    private boolean landscape;
    private boolean multiSelect;
    /** v0.4.203：左右窗各自的多选状态（操作条跟随激活窗）。 */
    private boolean leftMultiSelect;
    /** v0.4.208：任务横幅可见性切换诊断（频闪排查）。 */
    private boolean lastTaskVisible;
    private int taskToggleCount;
    private long lastTaskLog;
    private boolean rightMultiSelect;
    private int accent;
    private int onSurface;
    private int textSecondary;
    private int pageBg;

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
        pageBg = getResources().getColor(R.color.background, getTheme());

        String rootPath = getIntent().getStringExtra(EXTRA_PATH);
        if (rootPath == null || rootPath.isEmpty()) {
            rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        }
        fmRootPath = rootPath;

        SavedStateHandle handle = new SavedStateHandle();
        handle.set(FileManagerViewModel.KEY_ROOT_PATH, rootPath);
        vm = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                return (T) new FileManagerViewModel(getApplicationContext(), handle);
            }
        }).get(FileManagerViewModel.class);
        // v0.4.203：右窗独立 VM（双窗模式；独立路径状态/历史栈）
        SavedStateHandle handleRight = new SavedStateHandle();
        handleRight.set(FileManagerViewModel.KEY_ROOT_PATH, rootPath);
        vmRight = new FileManagerViewModel(getApplicationContext(), handleRight);
        editorVm = vm; // v0.4.203：编辑器来源窗默认左窗

        setupAdapters();
        setupListeners();
        setupAudioPanel();
        applyLayoutMode();

        vm.initialize();
        vmRight.initialize();
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
        FmFlowBridgeKt.collectFlow(this, vmRight.getState(), this::onStateRight); // v0.4.203
        FmFlowBridgeKt.collectFlow(this, vmRight.getErrorEvents(), msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        FmFlowBridgeKt.collectFlow(this, vmRight.getEditorUi(), this::onEditorUi);
        FmFlowBridgeKt.collectFlow(this, vmRight.getSearchUi(), this::onSearchUiRight);
        FmFlowBridgeKt.collectFlow(this, vm.getSearchUi(), this::onSearchUi);
        FmFlowBridgeKt.collectFlow(this, vm.getEditorUi(), this::onEditorUi);
        FmFlowBridgeKt.collectFlow(this, vm.getErrorEvents(), msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        setupEditor();
    }

    private void bindViews() {
        recycler = findViewById(R.id.fm_recycler);
        trashRecycler = findViewById(R.id.fm_trash_recycler);
        pathBar = findViewById(R.id.fm_path_bar);
        emptyView = findViewById(R.id.fm_empty);

        taskText = findViewById(R.id.fm_task_text);
        taskProgress = findViewById(R.id.fm_task_progress);

        taskBanner = findViewById(R.id.fm_task_banner);
        navRail = findViewById(R.id.fm_nav_rail);
        bottomBar = findViewById(R.id.fm_bottom_bar);
        selectionBar = findViewById(R.id.fm_selection_bar);
        topBar = findViewById(R.id.fm_top_bar);
        contentFrame = findViewById(R.id.fm_content_frame);
        editorRoot = findViewById(R.id.fm_editor_root);
        editorName = findViewById(R.id.fm_editor_name);
        editorDirty = findViewById(R.id.fm_editor_dirty);
        editorSave = findViewById(R.id.fm_editor_save);
        editorText = findViewById(R.id.fm_editor_text);
        editorReadonlyToggle = findViewById(R.id.fm_editor_readonly);
        editorScrollBar = findViewById(R.id.fm_editor_scrollbar);
        editorLoading = findViewById(R.id.fm_editor_loading);
        editorNotice = findViewById(R.id.fm_editor_notice);
        audioPanel = findViewById(R.id.fm_audio_panel);
        audioPlayPause = findViewById(R.id.fm_audio_play_pause);
        audioName = findViewById(R.id.fm_audio_name);
        audioCurrent = findViewById(R.id.fm_audio_current);
        audioDuration = findViewById(R.id.fm_audio_duration);
        audioSeek = findViewById(R.id.fm_audio_seek);
        // v0.4.203：双窗视图
        paneRight = findViewById(R.id.fm_pane_right);
        paneLeft = findViewById(R.id.fm_pane_left);
        paneDivider = findViewById(R.id.fm_pane_divider);
        recyclerRight = findViewById(R.id.fm_recycler_right);
        pathBarRight = findViewById(R.id.fm_path_bar_right);
    }

    /** v0.4.203：listener 参数化（左/右窗各用自己的 VM；交互自动聚焦本窗；
     *  长按=气泡菜单（MT 同款），多选模式长按=勾选）。 */
    private FmEntryAdapter.Listener entryListener(FileManagerViewModel vmm, boolean fromRight) {
        return new FmEntryAdapter.Listener() {
            @Override
            public void onEntryClick(FmEntry entry) {
                if (dualMode && activePane != (fromRight ? 1 : 0)) {
                    activatePane(fromRight ? 1 : 0);
                }
                openFileEntry(entry, fromRight);
            }

            @Override
            public void onEntryLongClick(FmEntry entry) {
                // 多选模式勾选；自动聚焦当前窗
                if (dualMode && activePane != (fromRight ? 1 : 0)) {
                    activatePane(fromRight ? 1 : 0);
                }
                vmm.toggleSelection(entry);
            }

            @Override
            public void onEntryLongMenu(FmEntry entry, View anchor) {
                // v0.4.207：长按气泡菜单——双窗时复制/移动带方向箭头直发
                // 对面窗（不弹提示，目标窗闪烁动画反馈）
                if (dualMode && activePane != (fromRight ? 1 : 0)) {
                    activatePane(fromRight ? 1 : 0);
                }
                // 记录直发前对面窗条目集合（粘贴完成后 diff 新增文件染蓝）
                if (dualMode) {
                    FileManagerUiState peerState = fromRight ? lastState : lastStateRight;
                    if (peerState != null) {
                        peerBeforePaths = new java.util.HashSet<>();
                        for (FmEntry pe : peerState.getVisibleEntries()) {
                            peerBeforePaths.add(pe.getPath().toString());
                        }
                        peerBeforeTargetIsRight = !fromRight; // 直发目标=对面窗（fromRight 条目→左窗）
                    }
                }
                FmDialogs.showEntryBubble(FileManagerActivity.this, vmm, entry, anchor,
                        dualMode ? (fromRight ? vm : vmRight) : null, !fromRight,
                        () -> runOnUiThread(() -> {
                            // 直发完成动画：目标窗 alpha 脉冲（内容一闪表示
                            // 文件已到达对面窗）
                            View target = fromRight ? paneLeft : paneRight;
                            if (target == null || target.getVisibility() != View.VISIBLE) {
                                return;
                            }
                            target.animate().alpha(0.4f).setDuration(140)
                                    .withEndAction(() -> target.animate().alpha(1f)
                                            .setDuration(300).start())
                                    .start();
                        }));
            }
        };
    }

    private void setupAdapters() {
        entryAdapter = new FmEntryAdapter(entryListener(vm, false), java.util.Collections.emptySet(),
                false, accent, onSurface, textSecondary, pageBg);
        recycler.setAdapter(entryAdapter);

        // v0.4.203：右窗独立列表（双窗模式）
        entryAdapterRight = new FmEntryAdapter(entryListener(vmRight, true),
                java.util.Collections.emptySet(), false, accent, onSurface, textSecondary, pageBg);
        recyclerRight.setAdapter(entryAdapterRight);
        recyclerRight.setLayoutManager(new LinearLayoutManager(this));

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
        }, java.util.Collections.emptySet(), false, accent, onSurface, textSecondary, pageBg);
        trashRecycler.setAdapter(trashAdapter);
        trashRecycler.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupListeners() {
        findViewById(R.id.fm_btn_back).setOnClickListener(v -> {
            if (showingTrash) {
                showFiles();
            } else if (!activeVm().consumeBack()) {
                // v0.4.89：已到根目录——双击退出（防误触）
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastBackClick < 1500) {
                    finish();
                } else {
                    lastBackClick = now;
                    Toast.makeText(this, R.string.fm_double_back_exit,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        // v0.4.203：跳转路径按钮已删（用户要求）
        findViewById(R.id.fm_btn_search).setOnClickListener(v -> activeVm().showSearchDialog());
        findViewById(R.id.fm_btn_sort).setOnClickListener(v -> {
            FileManagerUiState st = (dualMode && activePane == 1) ? lastStateRight : lastState;
            FmDialogs.showSortDialog(this, activeVm(),
                    st != null ? st.getSortConfig()
                            : new org.levimc.launcher.filemanager.viewmodel.SortConfig());
        });
        findViewById(R.id.fm_btn_multi).setOnClickListener(v -> {
            if (showingTrash) {
                if (multiSelect) vm.clearTrashSelection();
                else vm.selectAllTrash();
            } else {
                // v0.4.209：左下角全选按钮——未多选点击=全选；已多选点击=
                // 全选（操作条单选后补全选入口）；已全选点击=清空退出多选。
                // 按激活窗状态判断（此前用共用 multiSelect 判断会误清另一窗）
                boolean m = (dualMode && activePane == 1) ? rightMultiSelect : leftMultiSelect;
                if (!m) {
                    activeVm().selectAll();
                } else {
                    FileManagerUiState st = (activeVm() == vmRight) ? lastStateRight : lastState;
                    int total = st != null ? st.getVisibleEntries().size() : 0;
                    int sel = st != null ? st.getSelection().size() : 0;
                    if (total > 0 && sel >= total) {
                        activeVm().clearSelection(); // 已全选：再点=取消全选
                    } else {
                        activeVm().selectAll(); // 单选/部分选：点=全选
                    }
                }
            }
        });
        findViewById(R.id.fm_btn_more).setOnClickListener(v -> showMoreMenu());

        // v0.4.208：剪贴板横幅已删（粘贴入口=长按气泡/更多菜单）
        findViewById(R.id.fm_btn_task_cancel).setOnClickListener(v -> activeVm().cancelCurrentTask());

        findViewById(R.id.fm_bottom_home).setOnClickListener(v -> showFiles());
        findViewById(R.id.fm_bottom_trash).setOnClickListener(v -> showTrash());
        findViewById(R.id.fm_rail_home).setOnClickListener(v -> showFiles());
        findViewById(R.id.fm_rail_trash).setOnClickListener(v -> showTrash());

        // v0.4.203：双窗操作（MT 管理器同款）——单/双窗切换、上一级、窗口同步、新建。
        // 横屏底栏隐藏（applyLayoutMode），按钮两份：底栏（竖屏）+ rail（横屏）
        bindDualActions(R.id.fm_bottom_dual, R.id.fm_bottom_up, R.id.fm_bottom_sync,
                R.id.fm_bottom_new, R.id.fm_bottom_dual_icon, R.id.fm_bottom_dual_text,
                R.id.fm_bottom_up_text, R.id.fm_bottom_sync_text, R.id.fm_bottom_new_text);
        bindDualActions(R.id.fm_rail_dual, R.id.fm_rail_up, R.id.fm_rail_sync,
                R.id.fm_rail_new, R.id.fm_rail_dual_icon, R.id.fm_rail_dual_text,
                R.id.fm_rail_up_text, R.id.fm_rail_sync_text, R.id.fm_rail_new_text);
        updateDualButtons();

        // v0.4.203：点击/滑动/空白区域聚焦——右窗点击激活右窗，左窗同理。
        // v0.4.209：RecyclerView 空白长按自实现——RecyclerView 覆写
        // onTouchEvent 不走 View 长按检测，setOnLongClickListener 空白处
        // 不触发（用户实测点空白无气泡的根因）。touch 监听里 DOWN 启动
        // 长按计时，MOVE 超 touchSlop 取消，触发时条目下方无子 View 才弹
        // 路径气泡；条目上的长按由条目自己消费，不冲突
        final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        android.view.View.OnTouchListener focusRight = (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    activatePane(1);
                    armBlankLongPress(v, vmRight, e.getX(), e.getY());
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (movedBeyondSlop(v, e.getX(), e.getY(), touchSlop)) {
                        cancelBlankLongPress(v);
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    cancelBlankLongPress(v);
                    break;
            }
            return false;
        };
        android.view.View.OnTouchListener focusLeft = (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    activatePane(0);
                    armBlankLongPress(v, vm, e.getX(), e.getY());
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (movedBeyondSlop(v, e.getX(), e.getY(), touchSlop)) {
                        cancelBlankLongPress(v);
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    cancelBlankLongPress(v);
                    break;
            }
            return false;
        };
        recyclerRight.setOnTouchListener(focusRight);
        findViewById(R.id.fm_empty_right).setOnTouchListener(focusRight);
        findViewById(R.id.fm_path_bar_right).setOnTouchListener(focusRight);
        recycler.setOnTouchListener(focusLeft);
        emptyView.setOnTouchListener(focusLeft);
        pathBar.setOnTouchListener(focusLeft);
        // v0.4.208：路径栏/空态占位长按气泡（复制/剪切当前目录/粘贴；
        // 横幅已删，长按气泡恢复为粘贴入口）。列表空白处走 touch 自实现
        // 长按（上方 focusLeft/focusRight）——RecyclerView 的
        // setOnLongClickListener 空白处不触发（v0.4.209 实测根因）
        android.view.View.OnLongClickListener pathLongLeft = v -> {
            activatePane(0);
            FmDialogs.showPathBubble(FileManagerActivity.this, vm, v, -1, -1);
            return true;
        };
        android.view.View.OnLongClickListener pathLongRight = v -> {
            activatePane(1);
            FmDialogs.showPathBubble(FileManagerActivity.this, vmRight, v, -1, -1);
            return true;
        };
        pathBar.setOnLongClickListener(pathLongLeft);
        emptyView.setOnLongClickListener(pathLongLeft);
        findViewById(R.id.fm_path_bar_right).setOnLongClickListener(pathLongRight);
        findViewById(R.id.fm_empty_right).setOnLongClickListener(pathLongRight);

        findViewById(R.id.fm_btn_sel_all).setOnClickListener(v -> {
            // v0.4.208：操作条「单选」——清空选择只选第一个文件
            // （顶栏全选按钮仍为全选语义；左滑单选已删）
            if (showingTrash) {
                selVm().clearSelection();
                return;
            }
            FileManagerUiState st = (selVm() == vmRight) ? lastStateRight : lastState;
            if (st == null || st.getVisibleEntries().isEmpty()) {
                selVm().clearSelection();
                return;
            }
            selVm().clearSelection();
            selVm().toggleSelection(st.getVisibleEntries().get(0));
        });
        findViewById(R.id.fm_btn_sel_copy).setOnClickListener(v -> {
            if (!showingTrash) selVm().bulkCopy();
        });
        findViewById(R.id.fm_btn_sel_cut).setOnClickListener(v -> {
            if (!showingTrash) selVm().bulkCut();
        });
        findViewById(R.id.fm_btn_sel_compress).setOnClickListener(v -> {
            if (showingTrash) {
            } else {
                selVm().bulkCompress();
            }
        });
        findViewById(R.id.fm_btn_sel_delete).setOnClickListener(v -> {
            if (showingTrash) {
            } else {
                FileManagerUiState st = (activePane == 1 && dualMode) ? lastStateRight : lastState;
                int count = st != null ? st.getSelection().size() : 0;
                if (count > 0) FmDialogs.showBatchDeleteConfirm(this, selVm(), count);
            }
        });
        findViewById(R.id.fm_btn_sel_exit).setOnClickListener(v -> {
            if (showingTrash) vm.clearTrashSelection();
            else selVm().clearSelection();
        });
        // v0.4.204：多选底栏「属性」——选中单个时查看属性
        findViewById(R.id.fm_btn_sel_props).setOnClickListener(v -> {
            if (showingTrash) {
                return;
            }
            FileManagerUiState st = (selVm() == vmRight) ? lastStateRight : lastState;
            if (st != null && st.getSelection().size() == 1) {
                String key = st.getSelection().iterator().next();
                for (FmEntry e : st.getVisibleEntries()) {
                    if (e.getPath().toString().equals(key)) {
                        FmDialogs.showPropertiesDialog(this, e);
                        return;
                    }
                }
            }
            Toast.makeText(this, R.string.fm_ui_props_single_only,
                    Toast.LENGTH_SHORT).show();
        });
    }

    /** v0.4.203：当前激活窗 VM（双窗且激活右窗时用右窗）。 */
    private FileManagerViewModel activeVm() {
        return (dualMode && activePane == 1) ? vmRight : vm;
    }

    /** v0.4.203：多选操作目标——激活窗有选中优先，否则取有选中的另一窗
     *  （左右窗共用一个底栏：任何窗口有选中都显示操作条）。 */
    private FileManagerViewModel selVm() {
        if (!dualMode) {
            return vm;
        }
        if (activePane == 1 && rightMultiSelect) {
            return vmRight;
        }
        if (activePane == 0 && leftMultiSelect) {
            return vm;
        }
        return rightMultiSelect ? vmRight : vm;
    }

    /** v0.4.204：路径栏/空白长按气泡（MT 同款）——有剪贴板时首项「粘贴」
     *  （先复制/剪切后到任意路径长按空白即粘贴），另有复制/移动当前目录；
     *  动态定位防超出屏幕。 */

    private void showMoreMenu() {
        final boolean hasClip = lastState != null && lastState.getClipboard() != null;
        final List<String> items = new ArrayList<>();
        // v0.4.203：新建合并为 MT 式单项；跳转项已删（用户要求）
        items.add(getString(R.string.fm_ui_new));
        if (hasClip) items.add(getString(R.string.fm_ui_paste));
        items.add(getString(R.string.fm_ui_sort));
        items.add(getString(R.string.fm_ui_hidden) + (lastState != null && lastState.getShowHidden() ? " ✓" : ""));
        items.add(getString(R.string.fm_ui_clear_trash));
        org.levimc.launcher.ui.dialogs.CustomAlertDialog moreDialog = new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.fm_ui_more))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String picked = items.get(which);
                    if (picked.equals(getString(R.string.fm_ui_new))) {
                        FmDialogs.showCreateDialogMt(this, activeVm()); // v0.4.204 激活窗
                    } else if (picked.equals(getString(R.string.fm_ui_paste))) {
                        // v0.4.203：双窗粘贴走激活窗
                        if (dualMode && activePane == 1) {
                            vmRight.requestPaste();
                        } else {
                            vm.requestPaste();
                        }
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
                .setNegativeButton(getString(R.string.cancel), null);
        moreDialog.setCanceledOnTouchOutside(true);
        moreDialog.show();
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

    // ---------------- 文件打开分发（v669：图片预览/音频播放/文本编辑/系统打开） ----------------

    /** v0.4.203：参数化打开入口（右窗条目用 vmRight——此前硬编码 vm，
     *  右窗点文件夹实际改了左窗目录=「右窗点不动」根因）。 */
    private void openFileEntry(FmEntry entry, boolean fromRight) {
        FileManagerViewModel vmm = fromRight ? vmRight : vm;
        String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
        // 打开非音频内容时收起音频面板（防残留）
        if (!isAudio(name)) {
            releaseAudio();
        }
        if (entry.isDirectory()) {
            vmm.enterDirectory(entry);
            return;
        }
        // v0.4.203：压缩包单击=静默拆包查看（MT 同款二级浏览）
        if (entry.getArchiveType() != null) {
            FmDialogs.showArchiveBrowse(this, new java.io.File(entry.getPath().toString()));
            return;
        }
        java.io.File file = new java.io.File(entry.getPath().toString());
        if (isImage(name)) {
            FmDialogs.showImagePreview(this, file);
        } else if (isAudio(name)) {
            openAudio(file);
        } else {
            boolean text = false;
            for (String suffix : FmDialogs.TEXT_SUFFIX) {
                if (name.endsWith(suffix)) {
                    text = true;
                    break;
                }
            }
            if (text) {
                editorVm = vmm; // v0.4.203：记录编辑器来源窗（保存/关闭作用于它）
                vmm.editorOpen(entry.getPath());
            } else {
                openWithSystem(file, name);
            }
        }
    }

    private static boolean isImage(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    private static boolean isAudio(String name) {
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg")
                || name.endsWith(".m4a") || name.endsWith(".flac") || name.endsWith(".aac");
    }

    /** 其它类型走系统打开（FileProvider 授权，避免 file:// 崩溃）。 */
    private void openWithSystem(java.io.File file, String name) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, FmDialogs.guessMime(name));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, null));
        } catch (Exception e) {
            Toast.makeText(this, R.string.fm_ui_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 音频播放（v669 恢复旧版） ----------------

    private void openAudio(java.io.File file) {
        try {
            releaseAudio();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> runOnUiThread(() -> {
                audioSeek.setProgress(audioSeek.getMax());
                audioCurrent.setText(audioDuration.getText());
                releaseAudio();
            }));
            mediaPlayer.start();
            audioSourcePath = file.getAbsolutePath();
            audioPanel.setVisibility(View.VISIBLE);
            audioName.setText(file.getName());
            int duration = mediaPlayer.getDuration();
            if (duration <= 0) duration = 1000;
            audioSeek.setMax(duration);
            audioSeek.setProgress(0);
            audioDuration.setText(formatTime(duration));
            audioCurrent.setText("0:00");
            audioPlayPause.setImageResource(R.drawable.ic_pause);
            startAudioProgress();
        } catch (Exception e) {
            Toast.makeText(this, R.string.audio_play_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupAudioPanel() {
        int tint = accent != 0 ? accent : onSurface;
        audioPlayPause.setOnClickListener(v -> togglePlayPause());
        audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    audioCurrent.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        // 进度条 tint 用强调色（个性化兼容，不硬编码 primary）
        if (audioSeek.getProgressDrawable() != null) {
            audioSeek.getProgressDrawable().setTint(tint);
        }
        if (audioSeek.getThumb() != null) {
            audioSeek.getThumb().setTint(tint);
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            audioPlayPause.setImageResource(R.drawable.ic_play);
        } else {
            mediaPlayer.start();
            audioPlayPause.setImageResource(R.drawable.ic_pause);
        }
    }

    private void startAudioProgress() {
        audioHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null) {
                    audioSeek.setProgress(mediaPlayer.getCurrentPosition());
                    audioCurrent.setText(formatTime(mediaPlayer.getCurrentPosition()));
                    audioHandler.postDelayed(this, 250);
                }
            }
        }, 500);
    }

    private void releaseAudio() {
        audioHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        audioPanel.setVisibility(View.GONE);
        audioSourcePath = null;
    }

    private static String formatTime(int ms) {
        int s = ms / 1000;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    private void showTrash() {
        // v0.4.203：回收站为全局单窗视图，进入时退出双窗
        if (dualMode) {
            toggleDualMode();
        }
        showingTrash = true;
        vm.loadTrashList();
        recycler.setVisibility(View.GONE);
        trashRecycler.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }


    /** v0.4.90：面包屑路径栏（系统文件管理器同款）——根名 + 相对段，
     *  每段点击直接跳到该路径；「⋯」按钮打开跳转菜单显示全部路径。 */
    private void rebuildPathBar(String currentPath) {
        rebuildPathBarFor(pathBar, currentPath, vm);
    }

    /** v0.4.203：右窗路径栏。 */
    private void rebuildPathBarRight(String currentPath) {
        rebuildPathBarFor(pathBarRight, currentPath, vmRight);
    }

    /** v0.4.203：参数化路径栏（左/右窗共用）。 */
    private void rebuildPathBarFor(android.widget.LinearLayout bar, String currentPath,
                                   FileManagerViewModel vmm) {
        if (bar == null) {
            return;
        }
        bar.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        java.io.File root = new java.io.File(fmRootPath);
        String rel = "";
        if (currentPath != null && !currentPath.equals(fmRootPath)
                && currentPath.startsWith(fmRootPath + "/")) {
            rel = currentPath.substring(fmRootPath.length() + 1);
        }
        // 根名（包文件名）段：点击回根
        bar.addView(pathSegment(root.getName(), fmRootPath, false, vmm));
        // v0.4.147：每段传各自对应的实际路径且只有末段是叶子——此前
        // 所有段都传 currentPath+isLeaf=true（不可点），只有根段能点
        // （「路径只能点父级，assets/minecraft/textures 点不了」根因）
        String[] segs = rel.split("/");
        String cur = fmRootPath;
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            if (seg.isEmpty()) {
                continue;
            }
            cur += "/" + seg;
            boolean leaf = (i == segs.length - 1);
            // 分隔符
            TextView slash = new TextView(this);
            slash.setText("/");
            slash.setTextColor(textSecondary);
            slash.setTextSize(14);
            slash.setPadding((int) (2 * d), 0, (int) (2 * d), 0);
            bar.addView(slash);
            bar.addView(pathSegment(seg, cur, leaf, vmm));
        }
    }

    private TextView pathSegment(String label, String path, boolean isLeaf,
                                 FileManagerViewModel vmm) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF3B82F6);
        tv.setTextSize(14);
        tv.setMaxLines(1);
        if (isLeaf) {
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        // v0.4.110：删除圆角描边背景——纯路径文本显示（系统文件管理器同款）
        float d = getResources().getDisplayMetrics().density;
        tv.setPadding((int) (2 * d), 0, (int) (2 * d), 0);
        // 叶子段（当前目录）不跳自己；其它段点击跳转
        if (!isLeaf) {
            tv.setOnClickListener(v -> {
                if (!vmm.submitJump(path)) {
                    Toast.makeText(this, R.string.fm_ui_jump_invalid, Toast.LENGTH_SHORT).show();
                }
            });
        }
        return tv;
    }

    // ---------------- v0.4.203 双窗模式 ----------------

    /** v0.4.203：跨窗剪贴板同步记录（内容比较防回环）。 */
    private java.util.List<java.nio.file.Path> lastClipSources;
    private boolean lastClipCut;
    private boolean hasLastClip;

    /** v0.4.203：剪贴板变化 → 同步给另一窗（跨窗复制/剪切/粘贴）+
     *  复制/剪切自动切双窗（原窗在左）。内容比较防 setClipboardExternal
     *  回环。 */
    private void syncClipboard(
            org.levimc.launcher.filemanager.viewmodel.FmClipboard cb, boolean fromRight) {
        boolean changed = (cb == null) ? hasLastClip
                : (!hasLastClip || !cb.getSources().equals(lastClipSources)
                        || cb.isCut() != lastClipCut);
        if (!changed) {
            return;
        }
        hasLastClip = cb != null;
        lastClipSources = cb != null ? cb.getSources() : null;
        lastClipCut = cb != null && cb.isCut();
        FileManagerViewModel target = fromRight ? vm : vmRight;
        if (cb != null) {
            target.setClipboardExternal(cb.getSources(), cb.isCut());
            if (!dualMode) {
                toggleDualMode(); // 复制/剪切自动进双窗
            }
        } else {
            target.clearClipboard();
        }
    }

    /** v0.4.203：双窗四键绑定（底栏与 rail 共用同一逻辑）。 */
    private void bindDualActions(int dualId, int upId, int syncId, int newId,
                                 int dualIconId, int dualTextId, int upTextId,
                                 int syncTextId, int newTextId) {
        findViewById(dualId).setOnClickListener(v -> toggleDualMode());
        findViewById(upId).setOnClickListener(v -> {
            if (dualMode && activePane == 1) {
                vmRight.goParent();
            } else {
                vm.goParent();
            }
        });
        findViewById(syncId).setOnClickListener(v -> {
            // v0.4.207：一次性同步——右窗跳到左窗当前目录（联动模式已
            // 按用户要求撤销：双窗的意义就是独立导航）
            if (lastState != null && lastState.getCurrentDir() != null) {
                vmRight.submitJump(lastState.getCurrentDir().toString());
            }
        });
        findViewById(newId).setOnClickListener(v -> {
            // v0.4.203：MT 式新建——先输名称（支持后缀）再选文件夹/文件
            FileManagerViewModel target = (dualMode && activePane == 1) ? vmRight : vm;
            FmDialogs.showCreateDialogMt(this, target);
        });
        ((TextView) findViewById(dualTextId)).setText(R.string.fm_ui_dual);
        ((TextView) findViewById(upTextId)).setText(R.string.fm_ui_up);
        ((TextView) findViewById(syncTextId)).setText(R.string.fm_ui_sync);
        ((TextView) findViewById(newTextId)).setText(R.string.fm_ui_new);
    }

    /** 单/双窗切换。 */
    private void toggleDualMode() {
        dualMode = !dualMode;
        paneRight.setVisibility(dualMode ? View.VISIBLE : View.GONE);
        paneDivider.setVisibility(dualMode ? View.VISIBLE : View.GONE);
        if (dualMode && lastStateRight == null) {
            // 首次进入双窗：右窗同步到左窗当前目录（MT 同款初始行为）
            if (lastState != null && lastState.getCurrentDir() != null) {
                vmRight.submitJump(lastState.getCurrentDir().toString());
            }
        }
        if (!dualMode) {
            activePane = 0;
        }
        updateDualButtons();
        applyPaneFocus();
    }

    /** v0.4.203：书页式聚焦（MT 同款）——聚焦窗细描边+阴影浮起（锐利），
     *  非聚焦窗变暗（6% 黑罩）表示「在下方」；单窗无任何装饰。 */
    private void applyPaneFocus() {
        if (!dualMode) {
            paneLeft.setBackground(null);
            paneLeft.setElevation(0);
            paneLeft.setForeground(null);
            paneRight.setBackground(null);
            paneRight.setElevation(0);
            paneRight.setForeground(null);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        int activeColor = accent;
        android.graphics.drawable.GradientDrawable gdA = new android.graphics.drawable.GradientDrawable();
        gdA.setStroke((int) d, activeColor); // 1dp 细描边
        gdA.setColor(pageBg);
        android.graphics.drawable.GradientDrawable gdI = new android.graphics.drawable.GradientDrawable();
        gdI.setColor(pageBg);
        android.graphics.drawable.ColorDrawable dim = new android.graphics.drawable.ColorDrawable(
                0x0F000000); // 6% 黑罩
        View focused = activePane == 1 ? paneRight : paneLeft;
        View unfocused = activePane == 1 ? paneLeft : paneRight;
        focused.setBackground(gdA);
        focused.setElevation(6 * d);
        focused.setForeground(null);
        unfocused.setBackground(gdI);
        unfocused.setElevation(0);
        unfocused.setForeground(dim);
    }

    /** 激活窗格（底栏「上一级/新建」与主路径栏跟随激活窗）。 */
    private void activatePane(int pane) {
        if (activePane == pane) {
            return;
        }
        activePane = pane;
        // v0.4.203：多选操作条跟随激活窗（左窗多选中切到右窗 → 操作条收起）
        multiSelect = leftMultiSelect || rightMultiSelect;
        updateDualButtons();
        applyPaneFocus();
        // v0.4.212：路径栏重建延后到触摸链完成——DOWN 时同步重建
        // （removeAllViews+addView）会替换按下处的视图导致 click 事件流
        // 断裂=点击无响应；重建开销阻塞主线程=延迟反馈（用户实测）
        pathBar.removeCallbacks(mainPathBarRefresh);
        pathBar.post(mainPathBarRefresh = () -> refreshMainPathBar());
        renderSelectionBar();
    }

    /** v0.4.212：延后的主路径栏刷新（防 DOWN 时视图替换断 click 流）。 */
    private Runnable mainPathBarRefresh;

    /** v0.4.203：主路径栏跟随激活窗（段点击作用于激活窗 VM）。 */
    private void refreshMainPathBar() {
        if (dualMode && activePane == 1 && lastStateRight != null
                && lastStateRight.getCurrentDir() != null) {
            rebuildPathBarFor(pathBar, lastStateRight.getCurrentDir().toString(), vmRight);
        } else if (lastState != null && lastState.getCurrentDir() != null) {
            rebuildPathBarFor(pathBar, lastState.getCurrentDir().toString(), vm);
        }
    }

    /** 底栏/rail 按钮文字与高亮（双份按钮同步状态）。 */
    private void updateDualButtons() {
        int[] dualIcons = {R.id.fm_bottom_dual_icon, R.id.fm_rail_dual_icon};
        int[] upTexts = {R.id.fm_bottom_up_text, R.id.fm_rail_up_text};
        for (int id : dualIcons) {
            TextView t = findViewById(id);
            if (t != null) {
                t.setText(dualMode ? "☰" : "▥");
                t.setTextColor(dualMode ? accent : onSurface);
            }
        }
        for (int id : upTexts) {
            TextView t = findViewById(id);
            if (t != null) {
                t.setTextColor(dualMode && activePane == 1 ? accent : textSecondary);
            }
        }
    }

    /** v0.4.203：右窗状态渲染（轻量——列表/空态/路径栏；与左窗同款
     *  引用比较+增量更新，热路径不重建适配器）。 */
    private void onStateRight(FileManagerUiState s) {
        lastStateRight = s;
        if (s == null) {
            return;
        }
        syncClipboard(s.getClipboard(), true); // 右窗复制/剪切 → 同步左窗
        renderDialog(s, vmRight); // v0.4.204：右窗对话框（搜索/压缩/冲突等）
        String dir = s.getCurrentDir() != null ? s.getCurrentDir().toString() : fmRootPath;
        // v0.4.207：目录切换清空新增高亮与 before 集合；直发完成后
        // diff 新增文件染蓝（before 集合持续保留——粘贴任务完成前的
        // 状态刷新会先触发 diff 空结果，一次性消费会导致真正的新文件
        // 出现时已无集合可 diff=蓝字不显示的根因）
        if (!dir.equals(lastRightDir)) {
            lastRightDir = dir;
            rightHighlighted.clear();
            peerBeforePaths = null;
        }
        if (peerBeforePaths != null && peerBeforeTargetIsRight) {
            boolean added = false;
            for (FmEntry pe : s.getVisibleEntries()) {
                if (!peerBeforePaths.contains(pe.getPath().toString())) {
                    rightHighlighted.add(pe.getPath().toString());
                    peerBeforePaths.add(pe.getPath().toString()); // v0.4.208：并入集合防重复发现
                    added = true;
                }
            }
            if (added) {
                // v0.4.207：粘贴完成——源窗（左）同步刷新：移动后源文件
                // 应立即消失（此前要退出重进才刷新=用户实测根因）
                vm.refreshCurrentDir();
            }
        }
        if (entryAdapterRight != null && !rightHighlighted.isEmpty()) {
            entryAdapterRight.setHighlightedPaths(rightHighlighted);
        }
        rebuildPathBarRight(dir);
        if (entryAdapterRight == null
                || entryAdapterRight.getBoundList() != s.getVisibleEntries()) {
            entryAdapterRight = new FmEntryAdapter(entryListener(vmRight, true),
                    s.getSelection(), s.getMultiSelect(), accent, onSurface, textSecondary, pageBg);
            entryAdapterRight.setViewType(landscape
                    ? FmEntryAdapter.VIEW_CARD : FmEntryAdapter.VIEW_LIST);
            entryAdapterRight.setHighlightedPaths(rightHighlighted); // v0.4.207 新增染蓝
            recyclerRight.setAdapter(entryAdapterRight);
            entryAdapterRight.submit(s.getVisibleEntries());
        } else {
            entryAdapterRight.updateSelection(s.getSelection(), s.getMultiSelect());
        }
        boolean empty = s.getVisibleEntries() == null || s.getVisibleEntries().isEmpty();
        recyclerRight.setVisibility(empty ? View.GONE : View.VISIBLE);
        TextView emptyRight = findViewById(R.id.fm_empty_right);
        emptyRight.setVisibility(empty ? View.VISIBLE : View.GONE);
        // v0.4.203：右窗多选状态驱动操作条（此前只认左窗=右窗选中无操作条的根因）
        rightMultiSelect = s.getMultiSelect();
        if (dualMode && activePane == 1) {
            multiSelect = rightMultiSelect;
            renderSelectionBar();
            refreshMainPathBar();
        }
    }

    // ---------------- 状态渲染 ----------------

    private void onState(FileManagerUiState s) {
        lastState = s;
        syncClipboard(s.getClipboard(), false); // 左窗复制/剪切 → 同步右窗+自动双窗
        // v0.4.207：目录切换清空高亮与 before 集合；右窗直发到左窗后
        // diff 新增染蓝（before 集合持续保留，粘贴完成前的状态刷新
        // 会先触发空 diff——一次性消费=蓝字不显示的根因）
        if (s.getCurrentDir() != null) {
            String dir = s.getCurrentDir().toString();
            if (!dir.equals(lastLeftDir)) {
                lastLeftDir = dir;
                leftHighlighted.clear();
                peerBeforePaths = null;
            }
        }
        if (peerBeforePaths != null && !peerBeforeTargetIsRight) {
            boolean added = false;
            for (FmEntry pe : s.getVisibleEntries()) {
                if (!peerBeforePaths.contains(pe.getPath().toString())) {
                    leftHighlighted.add(pe.getPath().toString());
                    peerBeforePaths.add(pe.getPath().toString()); // v0.4.208：并入集合防重复发现
                    added = true;
                }
            }
            if (entryAdapter != null && !leftHighlighted.isEmpty()) {
                entryAdapter.setHighlightedPaths(leftHighlighted);
            }
            if (added) {
                // v0.4.207：粘贴完成——源窗（右）同步刷新（移动后源文件消失）
                vmRight.refreshCurrentDir();
            }
        }

        if (s.getCurrentDir() != null) {
            // v0.4.203：激活窗=右窗时主路径栏归右窗管，左窗状态不覆盖
            if (activePane == 0 || !dualMode) {
                rebuildPathBar(s.getCurrentDir().toString());
            }
        }
        // 热路径防护：任务进度等高频更新不重建适配器，只按引用比较增量刷新
        if (entryAdapter == null || entryAdapter.getBoundList() != s.getVisibleEntries()) {
            entryAdapter = new FmEntryAdapter(entryListener(vm, false), s.getSelection(), s.getMultiSelect(),
                    accent, onSurface, textSecondary, pageBg);
            entryAdapter.setViewType(landscape ? FmEntryAdapter.VIEW_CARD : FmEntryAdapter.VIEW_LIST);
            entryAdapter.setHighlightedPaths(leftHighlighted); // v0.4.207 新增染蓝
            recycler.setAdapter(entryAdapter);
            entryAdapter.submit(s.getVisibleEntries());
        } else {
            entryAdapter.updateSelection(s.getSelection(), s.getMultiSelect());
        }

        // v0.4.203：多选状态分窗保存；操作条跟随激活窗
        leftMultiSelect = s.getMultiSelect();
        multiSelect = leftMultiSelect || rightMultiSelect; // v0.4.203：共用底栏——任一窗有选中即显示操作条
        renderEmptyState();
        renderSelectionBar();

        renderTask(s);
        renderTrash(s);
        renderSnackbar(s);
        renderDialog(s, vm);
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
        findViewById(R.id.fm_btn_sel_props).setVisibility(showingTrash ? View.GONE : View.VISIBLE);
    }

    private void renderTask(FileManagerUiState s) {
        TaskState ts = s.getTaskState();
        TaskProgress tp = s.getTaskProgress();
        boolean visible = ts instanceof TaskState.Busy;
        // v0.4.208：任务横幅频闪诊断日志（可见性切换 3 秒一条，有界）
        if (visible != lastTaskVisible) {
            lastTaskVisible = visible;
            taskToggleCount++;
            long now = System.currentTimeMillis();
            if (now - lastTaskLog > 3000) {
                lastTaskLog = now;
                org.levimc.launcher.util.OnlineDebugLog.log("fm-task 切换 #"
                        + taskToggleCount + " -> " + (visible ? "BUSY" : "IDLE")
                        + " task=" + (ts == null ? "null" : ts.getClass().getSimpleName()));
            }
        }
        if (visible) {
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
                emptyView.setVisibility(View.VISIBLE);
            } else {
                emptyView.setVisibility(View.GONE);
            }
        } else if (showingTrash) {
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

    /** v0.4.203：右窗搜索状态（搜索对话框由 vmRight dialog 流驱动渲染）。 */
    private void onSearchUiRight(SearchUiState ui) {
    }

    // ---------------- 内置编辑器（v668） ----------------

    private void setupEditor() {
        editorDefaultKeyListener = editorText.getKeyListener();
        // 可拖动触摸热区（v680：系统滚动条负责显示，本区只负责拖动定位）
        editorScrollBar.setListener(ratio -> {
            android.text.Layout layout = editorText.getLayout();
            if (layout == null) return;
            int maxScroll = layout.getHeight()
                    + editorText.getCompoundPaddingTop() + editorText.getCompoundPaddingBottom()
                    - editorText.getHeight();
            if (maxScroll <= 0) return;
            editorText.scrollTo(0, (int) (ratio * maxScroll));
        });
        editorSave.setText(R.string.fm_ui_editor_save);
        editorSave.setTextColor(accent != 0 ? accent : onSurface);
        editorSave.setOnClickListener(v ->
                editorVm.editorSave(done -> {
                    Toast.makeText(this,
                            done ? R.string.fm_ui_editor_saved : R.string.fm_ui_editor_save_failed,
                            Toast.LENGTH_SHORT).show();
                    return kotlin.Unit.INSTANCE;
                }));
        findViewById(R.id.fm_editor_back).setOnClickListener(v -> requestEditorClose());
        editorReadonlyToggle.setOnClickListener(v -> {
            if (!editorFileWritable) {
                Toast.makeText(this, R.string.fm_ui_editor_readonly, Toast.LENGTH_SHORT).show();
                return;
            }
            editorReadonly = !editorReadonly;
            applyEditorReadonly();
        });
        editorText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (applyingHighlight) return;
                editorUserEdited = true;
                vm.editorTextChanged();
                scheduleHighlight();
            }
        });
    }

    private void onEditorUi(EditorUiState ui) {
        if (ui.getPath() == null) {
            hideEditor();
            exitConfirmShown = false;
            return;
        }
        // 打开新文件时重置编辑痕迹（否则上一文件关闭残留的 editorUserEdited 会拦下内容加载）
        String pathStr = ui.getPath().toString();
        if (!pathStr.equals(lastEditorPath)) {
            lastEditorPath = pathStr;
            editorUserEdited = false;
            editorReadonly = true;
        }
        showEditor();
        editorName.setText(ui.getPath().getFileName() != null
                ? ui.getPath().getFileName().toString() : ui.getPath().toString());

        // v0.4.142：加载中显示转圈（大文件读取+排版期间有明确反馈）
        editorLoading.setVisibility(ui.getState() instanceof EditorState.Loading
                ? View.VISIBLE : View.GONE);

        // v0.4.142：大文件截断只读预览——隐藏只读开关与保存按钮，强制只读
        editorPreviewOnly = ui.getPreviewOnly();
        editorReadonlyToggle.setVisibility(editorPreviewOnly ? View.GONE : View.VISIBLE);
        editorSave.setVisibility(editorPreviewOnly ? View.GONE : View.VISIBLE);
        editorNotice.setVisibility(editorPreviewOnly ? View.VISIBLE : View.GONE);

        // 内容：加载完成后且用户尚未编辑才写入（避免重置光标/覆盖输入）
        if (ui.getState() instanceof EditorState.Success && !editorUserEdited) {
            String content = ((EditorState.Success) ui.getState()).getContent();
            if (!content.equals(editorText.getText().toString())) {
                applyingHighlight = true;
                editorText.setText(content);
                applyingHighlight = false;
                // 预览模式跳过语法高亮（省主线程正则开销）
                if (!editorPreviewOnly) scheduleHighlight();
            }
        }
        editorFileWritable = ui.getWritable() && !editorPreviewOnly;
        if (!editorFileWritable) {
            editorReadonly = true; // 文件不可写/预览模式恒只读
        }
        applyEditorReadonly();
        editorDirty.setVisibility(ui.getDirty() ? View.VISIBLE : View.GONE);
        editorDirty.setText(ui.getDirty() ? "●" : "");

        if (ui.getExitConfirm() && !exitConfirmShown) {
            exitConfirmShown = true;
            showExitConfirmDialog();
        } else if (!ui.getExitConfirm()) {
            exitConfirmShown = false;
        }
        if (ui.getError() != null && !ui.getError().isEmpty()) {
            // v0.4.145：错误显示在编辑器内容区（此前仅 Toast——打开失败
            // 显示空白，用户看不到原因）
            applyingHighlight = true;
            editorText.setText("⚠ " + ui.getError());
            applyingHighlight = false;
            Toast.makeText(this, ui.getError(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 只读开关：睁眼=只读，笔=可编辑（v670）。
     * v671 修复：不能 setEnabled(false)——TextView 触摸滚动要求 isEnabled()，
     * disabled 后列表往下翻不了。只读改用 setTextIsSelectable(true)（可滚可选不可输入）
     * + setKeyListener(null) 屏蔽 IME。
     */
    private void applyEditorReadonly() {
        boolean readonly = editorReadonly || !editorFileWritable;
        editorReadonlyToggle.setImageResource(editorReadonly && editorFileWritable
                ? R.drawable.ic_eye : (editorFileWritable ? R.drawable.ic_edit : R.drawable.ic_eye));
        // v673：模式切换保持滚动位置（setTextIsSelectable 切换会重置 scroll）
        int scrollY = editorText.getScrollY();
        if (readonly) {
            editorText.setKeyListener(null);
            editorText.setTextIsSelectable(true);
        } else {
            editorText.setTextIsSelectable(false);
            editorText.setKeyListener(editorDefaultKeyListener);
            // v675：setTextIsSelectable(false) 会把 MovementMethod 置 null，
            // 触摸滚动依赖 movement——必须手动恢复，否则编辑模式滚不动
            editorText.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        }
        // v676：两种模式都保持可聚焦——长按选择/复制/粘贴菜单依赖焦点，
        // 只读模式 keyListener=null 不会弹键盘
        editorText.setFocusableInTouchMode(true);
        editorText.post(() -> {
            android.text.Layout layout = editorText.getLayout();
            if (layout != null) {
                int maxScroll = Math.max(0, layout.getHeight()
                        + editorText.getCompoundPaddingTop() + editorText.getCompoundPaddingBottom()
                        - editorText.getHeight());
                editorText.scrollTo(0, Math.min(scrollY, maxScroll));
            }
        });
    }

    private void showExitConfirmDialog() {
        new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.fm_ui_editor_unsaved_title))
                .setMessage(getString(R.string.fm_ui_editor_unsaved_msg))
                .setPositiveButton(getString(R.string.fm_ui_editor_save_exit), v -> {
                    exitConfirmShown = false;
                    editorVm.editorSave(done -> {
                        if (done) editorVm.editorClose();
                        return kotlin.Unit.INSTANCE;
                    });
                })
                .setNegativeButton(getString(R.string.fm_ui_editor_discard), v -> {
                    exitConfirmShown = false;
                    vm.editorCancelExitConfirm();
                    editorVm.editorClose();
                })
                .setNeutralButton(getString(R.string.cancel), v -> {
                    exitConfirmShown = false;
                    vm.editorCancelExitConfirm();
                })
                .show();
    }

    private void requestEditorClose() {
        if (editorRoot.getVisibility() != View.VISIBLE) return;
        if (vm.editorHasDirty()) {
            vm.editorRequestExitConfirm();
        } else {
            editorVm.editorClose();
        }
    }

    private void showEditor() {
        topBar.setVisibility(View.GONE);

        contentFrame.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        selectionBar.setVisibility(View.GONE);
        editorRoot.setVisibility(View.VISIBLE);
    }
    private void hideEditor() {
        if (editorRoot.getVisibility() != View.VISIBLE) return;
        editorRoot.setVisibility(View.GONE);
        editorUserEdited = false;
        lastEditorPath = null;
        editorReadonly = true;
        editorFileWritable = true;
        editorPreviewOnly = false;
        editorLoading.setVisibility(View.GONE);
        editorNotice.setVisibility(View.GONE);
        editorReadonlyToggle.setVisibility(View.VISIBLE);
        editorSave.setVisibility(View.VISIBLE);
        // guard：清空文本不触发 watcher（否则 editorUserEdited 被置回 true + dirty 被污染）
        applyingHighlight = true;
        editorText.setText("");
        applyingHighlight = false;
        highlightHandler.removeCallbacks(highlightTask);
        topBar.setVisibility(View.VISIBLE);
        contentFrame.setVisibility(View.VISIBLE);

        renderSelectionBar();
    }

    private void scheduleHighlight() {
        highlightHandler.removeCallbacks(highlightTask);
        highlightHandler.postDelayed(highlightTask, 300);
    }

    private void runHighlight() {
        if (editorPreviewOnly) return; // v0.4.142：预览模式跳过高亮
        String name = editorName.getText().toString();
        Spannable sp = FmHighlighter.apply(editorText.getText().toString(), name, isDarkTheme());
        int scrollY = editorText.getScrollY();
        applyingHighlight = true;
        int selStart = editorText.getSelectionStart();
        int selEnd = editorText.getSelectionEnd();
        editorText.setText(sp);
        try {
            editorText.setSelection(Math.min(selStart, editorText.length()), Math.min(selEnd, editorText.length()));
        } catch (IndexOutOfBoundsException ignored) {}
        applyingHighlight = false;
        // 高亮重设文本后恢复滚动位置
        editorText.post(() -> editorText.scrollTo(0, scrollY));
    }

    /** 延迟序列刷新（v680 起不再需要：系统滚动条自动显示，此方法已废弃）。 */
    private void scheduleScrollBarRefresh() {}

    private boolean isDarkTheme() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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

    private void renderDialog(FileManagerUiState s, FileManagerViewModel vmm) {
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
            // v0.4.203：右窗同款网格（此前恒 LinearLayoutManager=竖条列表）
            if (entryAdapterRight != null) {
                entryAdapterRight.setViewType(FmEntryAdapter.VIEW_CARD);
                recyclerRight.setLayoutManager(new GridLayoutManager(this, gridColumns()));
            }
        } else {
            navRail.setVisibility(View.GONE);
            bottomBar.setVisibility(multiSelect ? View.GONE : View.VISIBLE);
            if (entryAdapter != null) entryAdapter.setViewType(FmEntryAdapter.VIEW_LIST);
            recycler.setLayoutManager(new LinearLayoutManager(this));
            if (entryAdapterRight != null) {
                entryAdapterRight.setViewType(FmEntryAdapter.VIEW_LIST);
                recyclerRight.setLayoutManager(new LinearLayoutManager(this));
            }
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
    protected void onDestroy() {
        super.onDestroy();
        highlightHandler.removeCallbacksAndMessages(null);
        releaseAudio();
    }

    @Override
    public void onBackPressed() {
        if (audioPanel.getVisibility() == View.VISIBLE) {
            releaseAudio();
            return;
        }
        if (editorRoot.getVisibility() == View.VISIBLE) {
            requestEditorClose();
            return;
        }
        if (showingTrash) {
        }
        // v0.4.203：返回键走激活窗
        if (vm != null && !activeVm().consumeBack()) {
            super.onBackPressed();
        }
    }

    /** v0.4.203：双窗模式持久化（进程回收/重建后恢复，否则退回单窗）。 */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("fm_dual_mode", dualMode);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        dualMode = savedInstanceState.getBoolean("fm_dual_mode", false);
        if (dualMode) {
            paneRight.setVisibility(View.VISIBLE);
            paneDivider.setVisibility(View.VISIBLE);
            updateDualButtons();
            applyPaneFocus();
        }
    }
}
