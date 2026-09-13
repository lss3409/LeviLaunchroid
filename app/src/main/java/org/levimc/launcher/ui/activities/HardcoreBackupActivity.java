package org.levimc.launcher.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatSpinner;
import androidx.annotation.Nullable;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.LoadingDialog;
import org.levimc.launcher.util.HardcoreBackupManager;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HardcoreBackupActivity extends BaseActivity {

    private HardcoreBackupManager backupManager;
    private List<WorldItem> hardcoreWorlds = new ArrayList<>();
    private LinearLayout worldsContainer;
    private SwitchMaterial switchEnabled;
    private Spinner intervalUnitSpinner;
    private LinearLayout triggerContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());

        backupManager = new HardcoreBackupManager(this);
        setupHeader();
        setupIntervalSection();
        setupTriggerSection();
        setupWorldsSection();

        loadHardcoreWorlds();
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(getColor(R.color.background));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        this.contentRoot = root;
        return scrollView;
    }

    private LinearLayout contentRoot;

    private int accent() {
        int a = new PersonalizationManager(this).getAccentColor();
        return a != 0 ? a : getColor(R.color.primary);
    }

    private void setupHeader() {
        TextView title = new TextView(this);
        title.setText(R.string.hardcore_manage_title);
        title.setTextColor(accent());
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        contentRoot.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.hardcore_manage_desc);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setTextSize(13);
        desc.setPadding(0, dp(4), 0, 0);
        contentRoot.addView(desc);
    }

    private void setupIntervalSection() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(8));
        contentRoot.addView(row);

        TextView label = new TextView(this);
        label.setText(R.string.hardcore_backup_enabled);
        label.setTextColor(getColor(R.color.on_surface));
        label.setTextSize(15);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        switchEnabled = new SwitchMaterial(this);
        switchEnabled.setChecked(backupManager.isEnabled());
        int accent = accent();
        int[][] states = {{android.R.attr.state_checked}, {}};
        switchEnabled.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
        switchEnabled.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{android.graphics.Color.argb(100, android.graphics.Color.red(accent),
                        android.graphics.Color.green(accent), android.graphics.Color.blue(accent)), 0xFF555555}));
        row.addView(switchEnabled);

        // 间隔设置（滚轮选择数值 + 单位）
        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalRow.setPadding(0, dp(4), 0, dp(12));
        contentRoot.addView(intervalRow);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText(R.string.hardcore_backup_interval);
        intervalLabel.setTextColor(getColor(R.color.on_surface));
        intervalLabel.setTextSize(14);
        intervalRow.addView(intervalLabel);

        // NumberPicker 滚轮：分钟 1~60，天 1~30
        final android.widget.NumberPicker numberPicker = new android.widget.NumberPicker(this);
        boolean isDays = "days".equals(backupManager.getIntervalUnit());
        numberPicker.setMinValue(1);
        numberPicker.setMaxValue(isDays ? 30 : 60);
        numberPicker.setValue((int) backupManager.getIntervalValue());
        numberPicker.setWrapSelectorWheel(true);
        numberPicker.setTextColor(getColor(R.color.on_surface));
        // 禁用文本输入：只允许滚轮滑动，避免中间数值可点击弹输入框导致显示错乱
        numberPicker.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(dp(96), dp(140));
        pickerParams.setMargins(dp(12), 0, dp(8), 0);
        intervalRow.addView(numberPicker, pickerParams);

        intervalUnitSpinner = new AppCompatSpinner(this);
        String[] units = {getString(R.string.hardcore_backup_minutes), getString(R.string.hardcore_backup_days)};
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, R.layout.spinner_item, units);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        intervalUnitSpinner.setAdapter(adapter);
        intervalUnitSpinner.setSelection(isDays ? 1 : 0);
        intervalUnitSpinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        intervalUnitSpinner.setBackgroundResource(R.drawable.bg_spinner_outline);
        intervalRow.addView(intervalUnitSpinner, new LinearLayout.LayoutParams(dp(96), dp(44)));

        // 切换单位时调整滚轮范围
        intervalUnitSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean days = position == 1;
                int max = days ? 30 : 60;
                numberPicker.setMaxValue(max);
                if (numberPicker.getValue() > max) {
                    numberPicker.setValue(max);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // 保存间隔按钮
        Button saveBtn = new Button(this);
        saveBtn.setText(R.string.apply);
        styleButton(saveBtn, accent(), Color.WHITE);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        saveParams.setMargins(dp(12), 0, 0, 0);
        intervalRow.addView(saveBtn, saveParams);

        saveBtn.setOnClickListener(v -> {
            // 应用间隔后自动开启定时备份
            backupManager.setEnabled(true);
            switchEnabled.setChecked(true);
            long value = numberPicker.getValue();
            String unit = intervalUnitSpinner.getSelectedItemPosition() == 1 ? "days" : "minutes";
            backupManager.setInterval(value, unit);
            Toast.makeText(this, R.string.hardcore_settings_applied, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupTriggerSection() {
        TextView title = new TextView(this);
        title.setText(R.string.hardcore_trigger_title);
        title.setTextColor(getColor(R.color.on_surface));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(8));
        contentRoot.addView(title);

        triggerContainer = new LinearLayout(this);
        triggerContainer.setOrientation(LinearLayout.VERTICAL);
        contentRoot.addView(triggerContainer);
        refreshTriggerCards();
    }

    private void refreshTriggerCards() {
        triggerContainer.removeAllViews();
        String current = backupManager.getBackupTrigger();
        triggerContainer.addView(buildTriggerCard(
                HardcoreBackupManager.TRIGGER_INGAME,
                getString(R.string.hardcore_trigger_ingame),
                getString(R.string.hardcore_trigger_ingame_desc),
                current));
        triggerContainer.addView(buildTriggerCard(
                HardcoreBackupManager.TRIGGER_PAUSE,
                getString(R.string.hardcore_trigger_pause),
                getString(R.string.hardcore_trigger_pause_desc),
                current));
        triggerContainer.addView(buildTriggerCard(
                HardcoreBackupManager.TRIGGER_EXIT,
                getString(R.string.hardcore_trigger_exit),
                getString(R.string.hardcore_trigger_exit_desc),
                current));
    }

    private View buildTriggerCard(String trigger, String titleText, String descText, String current) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(cardParams);

        boolean selected = trigger.equals(current);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(getColor(R.color.surface));
        bg.setCornerRadius(dp(12));
        if (selected) {
            bg.setStroke(dp(2), accent());
        }
        card.setBackground(bg);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(titleText);
        name.setTextColor(selected ? accent() : getColor(R.color.on_surface));
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);

        TextView desc = new TextView(this);
        desc.setText(descText);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setTextSize(12);
        desc.setPadding(0, dp(2), 0, 0);
        info.addView(desc);

        TextView dot = new TextView(this);
        dot.setText(selected ? "●" : "○");
        dot.setTextColor(selected ? accent() : getColor(R.color.text_secondary));
        dot.setTextSize(20);
        card.addView(dot);

        card.setOnClickListener(v -> {
            backupManager.setBackupTrigger(trigger);
            refreshTriggerCards();
        });

        return card;
    }

    private void setupWorldsSection() {
        TextView sectionTitle = new TextView(this);
        sectionTitle.setText(R.string.worlds_category);
        sectionTitle.setTextColor(accent());
        sectionTitle.setTextSize(16);
        sectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionTitle.setPadding(0, dp(8), 0, dp(4));
        contentRoot.addView(sectionTitle);

        worldsContainer = new LinearLayout(this);
        worldsContainer.setOrientation(LinearLayout.VERTICAL);
        contentRoot.addView(worldsContainer);
    }

    private void loadHardcoreWorlds() {
        new Thread(() -> {
            hardcoreWorlds = HardcoreBackupManager.scanHardcoreWorlds(this);
            runOnUiThread(this::renderWorlds);
        }, "hardcore-scan").start();
    }

    private void renderWorlds() {
        worldsContainer.removeAllViews();
        if (hardcoreWorlds.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.hardcore_no_worlds);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            worldsContainer.addView(empty);
            return;
        }
        for (WorldItem world : hardcoreWorlds) {
            worldsContainer.addView(buildWorldCard(world));
        }
    }

    private View buildWorldCard(WorldItem world) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);
        card.setBackground(makeCardBackground());
        new PersonalizationManager(this).applyGlassToView(card);

        // 顶部：左侧世界图标 + 右侧世界名/种子/最近备份时间
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.setMarginEnd(dp(12));
        header.addView(icon, iconParams);
        icon.setImageResource(R.drawable.ic_world);
        File iconFile = new File(world.getFile(), "world_icon.jpeg");
        if (iconFile.exists()) {
            final String iconPath = iconFile.getAbsolutePath();
            icon.setTag(iconPath);
            new Thread(() -> {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                android.graphics.Bitmap bmp = BitmapFactory.decodeFile(iconPath, opts);
                icon.post(() -> {
                    if (iconPath.equals(icon.getTag()) && bmp != null) {
                        icon.setImageBitmap(bmp);
                    }
                });
            }).start();
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        header.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(world.getWorldName());
        name.setTextColor(getColor(R.color.on_surface));
        name.setTextSize(16);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);

        TextView seedText = new TextView(this);
        seedText.setText(getString(R.string.seed_label, world.getSeed()));
        seedText.setTextColor(getColor(R.color.text_secondary));
        seedText.setTextSize(12);
        seedText.setPadding(0, dp(2), 0, 0);
        seedText.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("seed", String.valueOf(world.getSeed())));
                Toast.makeText(this, R.string.seed_copied, Toast.LENGTH_SHORT).show();
            }
        });
        info.addView(seedText);

        long lastBackup = backupManager.getLastBackupTime(world);
        TextView lastBackupText = new TextView(this);
        if (lastBackup > 0) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            lastBackupText.setText(getString(R.string.hardcore_last_backup, fmt.format(new Date(lastBackup))));
        } else {
            lastBackupText.setText(getString(R.string.hardcore_never_backup));
        }
        lastBackupText.setTextColor(getColor(R.color.text_secondary));
        lastBackupText.setTextSize(12);
        lastBackupText.setPadding(0, dp(2), 0, 0);
        info.addView(lastBackupText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        card.addView(buttons);

        Button backupNow = new Button(this);
        backupNow.setText(R.string.hardcore_backup_now);
        backupNow.setTextSize(12);
        styleButton(backupNow, accent(), Color.WHITE);
        buttons.addView(backupNow, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button viewBackups = new Button(this);
        viewBackups.setText(R.string.hardcore_view_backups);
        viewBackups.setTextSize(12);
        styleButton(viewBackups, Color.TRANSPARENT, accent());
        LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        viewParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(viewBackups, viewParams);

        // 回档按钮：有备份时可用，点击后选择备份时间点回档
        java.util.List<HardcoreBackupManager.BackupRecord> backups = backupManager.listBackups(world);
        Button restoreBtn = new Button(this);
        restoreBtn.setText(R.string.restore_world);
        restoreBtn.setTextSize(12);
        styleButton(restoreBtn, Color.TRANSPARENT, accent());
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        restoreParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(restoreBtn, restoreParams);
        restoreBtn.setEnabled(!backups.isEmpty());
        restoreBtn.setAlpha(backups.isEmpty() ? 0.4f : 1f);
        restoreBtn.setOnClickListener(v -> showRestorePicker(world));

        backupNow.setOnClickListener(v -> doBackupNow(world));
        viewBackups.setOnClickListener(v -> openBackupDirectory(world));

        return card;
    }

    /** 回档：弹出备份时间点列表，选择后确认回档到该时间点。 */
    private void showRestorePicker(WorldItem world) {
        java.util.List<HardcoreBackupManager.BackupRecord> backups = backupManager.listBackups(world);
        if (backups.isEmpty()) {
            Toast.makeText(this, R.string.hardcore_no_backups, Toast.LENGTH_SHORT).show();
            return;
        }

        final HardcoreBackupManager.BackupRecord[] selected = {backups.get(0)};
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText(R.string.restore_select_backup_hint);
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setTextSize(12);
        hint.setPadding(dp(4), 0, dp(4), dp(10));
        container.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 自适应高度：按条目数量伸缩，最多占屏幕 55%，超过可滚动
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        int listHeight = Math.min(maxHeight, backups.size() * dp(74) + dp(4));
        container.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeight));

        final Runnable[] renderRows = new Runnable[1];
        renderRows[0] = () -> {
            list.removeAllViews();
            for (HardcoreBackupManager.BackupRecord rec : backups) {
                list.addView(buildBackupRow(rec, fmt, selected, renderRows[0]));
            }
        };
        renderRows[0].run();

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.restore_select_backup_title))
                .setTitleColor(accent())
                .setCustomView(container)
                .setPositiveButton(getString(R.string.restore_world), v ->
                        confirmRestoreSelected(world, selected[0], fmt))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /** 单个备份时间点行：时间 + 大小，选中态用 accent 描边与圆点标识。 */
    private View buildBackupRow(HardcoreBackupManager.BackupRecord rec,
                                SimpleDateFormat fmt,
                                HardcoreBackupManager.BackupRecord[] selected,
                                Runnable refreshRows) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowParams);

        boolean isSelected = rec == selected[0];
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(getColor(R.color.surface));
        bg.setCornerRadius(dp(12));
        if (isSelected) {
            bg.setStroke(dp(2), accent());
        }
        row.setBackground(bg);
        new PersonalizationManager(this).applyGlassToView(row);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(this);
        time.setText(fmt.format(new Date(rec.timestamp)));
        time.setTextColor(isSelected ? accent() : getColor(R.color.on_surface));
        time.setTextSize(14);
        time.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(time);

        TextView size = new TextView(this);
        size.setText(getString(R.string.restore_backup_size, formatSize(rec.file.length())));
        size.setTextColor(getColor(R.color.text_secondary));
        size.setTextSize(12);
        size.setPadding(0, dp(2), 0, 0);
        info.addView(size);

        TextView dot = new TextView(this);
        dot.setText(isSelected ? "●" : "○");
        dot.setTextColor(isSelected ? accent() : getColor(R.color.text_secondary));
        dot.setTextSize(18);
        row.addView(dot);

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            selected[0] = rec;
            refreshRows.run();
        });
        return row;
    }

    /** 确认回档到选中的时间点。 */
    private void confirmRestoreSelected(WorldItem world, HardcoreBackupManager.BackupRecord rec,
                                        SimpleDateFormat fmt) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.restore_world))
                .setMessage(getString(R.string.restore_world_confirm_time,
                        fmt.format(new Date(rec.timestamp))))
                .setPositiveButton(getString(R.string.confirm), v -> performRestore(world, rec))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /** 执行回档：删除当前世界，用选定备份原地替换。 */
    private void performRestore(WorldItem world, HardcoreBackupManager.BackupRecord rec) {
        new Thread(() -> {
            boolean ok;
            try {
                ok = backupManager.restoreWorldFromBackup(world, rec);
            } catch (Exception e) {
                ok = false;
            }
            boolean finalOk = ok;
            runOnUiThread(() -> {
                Toast.makeText(this,
                        finalOk ? R.string.restore_world_success : R.string.restore_world_failed,
                        Toast.LENGTH_LONG).show();
                if (finalOk) loadHardcoreWorlds();
            });
        }, "restore-world").start();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void doBackupNow(WorldItem world) {
        LoadingDialog loading = new LoadingDialog(this);
        loading.setMessage(getString(R.string.instance_backup_in_progress));
        loading.show();
        new Thread(() -> {
            try {
                String path = backupManager.backupWorldIfChanged(world);
                runOnUiThread(() -> {
                    try { loading.dismiss(); } catch (Exception ignored) {}
                    if (path == null) {
                        Toast.makeText(this, R.string.hardcore_no_change_skip, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, getString(R.string.instance_backup_success_message, path), Toast.LENGTH_LONG).show();
                        // 立即刷新卡片，显示最新的「最近备份」时间
                        renderWorlds();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    try { loading.dismiss(); } catch (Exception ignored) {}
                    Toast.makeText(this, getString(R.string.instance_backup_failed_message, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        }, "hardcore-backup-now").start();
    }

    /** 查看备份：通过备份目录里的信息文件间接跳到 MT 管理器（无法直接打开文件夹）。 */
    private void openBackupDirectory(WorldItem world) {
        File infoFile = backupManager.ensureInfoFile(world);
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", infoFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setPackage("bin.mt.plus");
            startActivity(intent);
        } catch (Exception e) {
            // 未安装 MT 管理器，回退到内置文件管理器打开该目录
            File backupDir = infoFile.getParentFile();
            Intent intent = new Intent(this, FileManagerActivity.class);
            intent.putExtra(FileManagerActivity.EXTRA_PATH, backupDir.getAbsolutePath());
            startActivity(intent);
        }
    }

    private GradientDrawable makeCardBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(getColor(R.color.surface));
        d.setCornerRadius(dp(12));
        return d;
    }

    /** 圆角按钮背景，兼容个性化 accent 与自适应 UI。 */
    private void styleButton(Button button, int bgColor, int textColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(20));
        button.setBackground(d);
        button.setTextColor(textColor);
        button.setAllCaps(false);
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }
}
