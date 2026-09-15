package org.levimc.launcher.ui.activities;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.core.content.leveldb.LevelDBEntry;
import org.levimc.launcher.core.content.leveldb.LevelDBReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
import org.levimc.launcher.core.content.nbt.NbtTag;
import org.levimc.launcher.core.content.worldmap.WorldMapRenderer;
import org.levimc.launcher.databinding.ActivityNbtViewerBinding;
import org.levimc.launcher.databinding.ItemNbtDbEntryBinding;
import org.levimc.launcher.ui.animation.DynamicAnim;
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

    private int currentTab = TAB_LEVEL;
    private int accentColor = 0;

    private NbtTag levelDatRoot;
    private File currentWorldDir;
    private final List<LevelDBEntry> dbEntries = new ArrayList<>();
    private DbEntryAdapter dbAdapter;
    private boolean dbParseRunning = false;

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
        binding.worldMapPlaceholder.setText(R.string.world_map_loading);

        selectTab(TAB_LEVEL);
        loadData(worldDir, worldName);
    }

    private void loadData(File worldDir, String worldName) {
        currentWorldDir = worldDir;
        binding.nbtLoading.setVisibility(View.VISIBLE);
        executor = Executors.newSingleThreadExecutor();
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
            if (dbDir.isDirectory()) {
                try {
                    LevelDBReader reader = new LevelDBReader(dbDir);
                    entries = reader.readAllEntries();
                    reader.close();
                } catch (Exception ignored) {
                }
            } else {
                dbMissing = true;
            }
            Log.i(TAG, "db 读取完成: 条目数=" + entries.size());

            // 世界地图缩略图：复用已读取的 db 条目，后台渲染
            Bitmap mapBitmap = null;
            try {
                mapBitmap = WorldMapRenderer.renderFromEntries(entries,
                        WorldMapRenderer.DEFAULT_MAX_CHUNKS_PER_AXIS);
                Log.i(TAG, "地图渲染完成: " + (mapBitmap != null
                        ? mapBitmap.getWidth() + "x" + mapBitmap.getHeight()
                        : "失败(null)"));
            } catch (Exception e) {
                Log.i(TAG, "地图渲染异常", e);
            }

            final WorldItem fWorld = worldItem;
            final NbtTag fRoot = root;
            final List<LevelDBEntry> fEntries = entries;
            final boolean fLevelMissing = levelDatMissing;
            final boolean fDbMissing = dbMissing;
            final Bitmap fMapBitmap = mapBitmap;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                onDataLoaded(fWorld, fRoot, fEntries, fLevelMissing, fDbMissing, fMapBitmap);
            });
        });
    }

    private void onDataLoaded(WorldItem worldItem, NbtTag root, List<LevelDBEntry> entries,
                              boolean levelDatMissing, boolean dbMissing, Bitmap mapBitmap) {
        binding.nbtLoading.setVisibility(View.GONE);

        // 世界地图缩略图：渲染失败/无 chunk 时显示不可用占位
        binding.worldMapCard.setVisibility(View.VISIBLE);
        if (mapBitmap != null) {
            binding.worldMapImage.setImageBitmap(mapBitmap);
            binding.worldMapPlaceholder.setVisibility(View.GONE);
        } else {
            binding.worldMapPlaceholder.setText(R.string.world_map_unavailable);
        }
        binding.nbtLoading.setVisibility(View.GONE);

        // 世界信息摘要卡
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

    /** 编辑 level.dat 常用字段（世界名/模式/难度/硬核/种子/死亡标记）。 */
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

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);

        // 世界名
        panel.addView(label(R.string.nbt_edit_name));
        EditText nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        String curName = "";
        NbtTag nameTag = root.get("LevelName");
        if (nameTag != null && nameTag.getType() == NbtTag.TAG_STRING) {
            curName = nameTag.getString();
        }
        nameEdit.setText(curName);
        panel.addView(nameEdit);

        // 游戏模式
        panel.addView(label(R.string.nbt_edit_gamemode));
        Spinner gamemodeSpinner = new Spinner(this);
        String[] modes = {
                getString(R.string.nbt_gamemode_survival),
                getString(R.string.nbt_gamemode_creative),
                getString(R.string.nbt_gamemode_adventure)
        };
        gamemodeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modes));
        int curGm = 0;
        NbtTag gmTag = root.get("GameType");
        if (gmTag != null && gmTag.getType() == NbtTag.TAG_INT) {
            curGm = Math.max(0, Math.min(2, gmTag.getInt()));
        }
        gamemodeSpinner.setSelection(curGm);
        panel.addView(gamemodeSpinner);

        // 难度
        panel.addView(label(R.string.nbt_edit_difficulty));
        Spinner difficultySpinner = new Spinner(this);
        String[] difficulties = {
                getString(R.string.nbt_difficulty_peaceful),
                getString(R.string.nbt_difficulty_easy),
                getString(R.string.nbt_difficulty_normal),
                getString(R.string.nbt_difficulty_hard)
        };
        difficultySpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, difficulties));
        int curDiff = 1;
        NbtTag diffTag = root.get("Difficulty");
        if (diffTag != null && diffTag.getType() == NbtTag.TAG_INT) {
            curDiff = Math.max(0, Math.min(3, diffTag.getInt()));
        }
        difficultySpinner.setSelection(curDiff);
        panel.addView(difficultySpinner);

        // 种子
        panel.addView(label(R.string.nbt_edit_seed));
        EditText seedEdit = new EditText(this);
        seedEdit.setSingleLine(true);
        seedEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        NbtTag seedTag = root.get("RandomSeed");
        if (seedTag != null && seedTag.getType() == NbtTag.TAG_LONG) {
            seedEdit.setText(String.valueOf(seedTag.getLong()));
        }
        panel.addView(seedEdit);

        // 硬核开关
        Switch hardcoreSwitch = new Switch(this);
        hardcoreSwitch.setText(R.string.nbt_edit_hardcore);
        NbtTag hcTag = root.get("IsHardcore");
        hardcoreSwitch.setChecked(hcTag != null && hcTag.getByte() != 0);
        panel.addView(hardcoreSwitch);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.nbt_edit_leveldat)
                .setView(scroll)
                .setPositiveButton(R.string.nbt_edit_save, null)
                .setNegativeButton(R.string.nbt_edit_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newName = nameEdit.getText().toString().trim();
                String seedText = seedEdit.getText().toString().trim();
                long newSeed;
                try {
                    newSeed = Long.parseLong(seedText);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.nbt_edit_seed_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                applyLevelDatEdits(root, newName, gamemodeSpinner.getSelectedItemPosition(),
                        difficultySpinner.getSelectedItemPosition(), hardcoreSwitch.isChecked(),
                        newSeed);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private TextView label(int resId) {
        TextView tv = new TextView(this);
        tv.setText(resId);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tv.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        return tv;
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
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
