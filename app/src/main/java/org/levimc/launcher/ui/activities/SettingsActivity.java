package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;
import androidx.activity.result.contract.ActivityResultContracts;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.LoadingDialog;
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager;
import org.levimc.launcher.util.GlobalConfigManager;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PermissionsHandler;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.ThemeManager;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends BaseActivity {

    private PermissionsHandler permissionsHandler;
    private ActivityResultLauncher<Intent> permissionResultLauncher;
    private ActivityResultLauncher<Intent> bgImagePickerLauncher;
    private ActivityResultLauncher<Uri> folderPickerLauncher;

    private TextView tabBasic;
    private TextView tabPersonalize;
    private TextView tabMigration;
    private TextView tabAbout;
    private TextView tabDebug;

    private View sectionBasic;
    private View sectionPersonalize;
    private View sectionMigration;
    private View sectionAbout;
    private View sectionDebug;

    private int selectedTabIndex = 0;

    private PersonalizationManager personalizationManager;
    private LinearLayout colorGridContainer;
    private LinearLayout moreColorsContainer;
    private TextView bgImageStatus;
    private TextView bgImageBlurValue;
    private TextView bgImageBrightnessValue;
    private ImageView bgImagePreview;
    private TextView customStoragePathCurrent;
    private EditText customStoragePathInput;
    private TextView extendedRootPathCurrent;
    private EditText extendedRootPathInput;
    private SwitchMaterial switchSharedStorageLayout;
    private TextView sharedStorageLayoutStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));

        setupNavBar();
        setupUpdateCheck();

        personalizationManager = new PersonalizationManager(this);

        // v0.0.6：首次打开默认「基础设置」（含语言切换），避免用户切到
        // 别的 tab 后找不到语言入口（用户反馈）。
        // v0.4.0：recreate（界面缩放等）时恢复停留的 tab——否则缩放后
        // 从个性化跳回基础设置（用户反馈，此前出现过的老问题）
        selectedTabIndex = savedInstanceState != null
                ? savedInstanceState.getInt("selected_tab", 0) : 0;

        permissionsHandler = PermissionsHandler.getInstance();
        permissionResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (permissionsHandler != null) {
                        permissionsHandler.onActivityResult(result.getResultCode(), result.getData());
                    }
                }
        );
        permissionsHandler.setActivity(this, permissionResultLauncher);

        bgImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            personalizationManager.setBackgroundImage(uri, this);
                            updateBgImageUI();
                            recreate();
                        }
                    }
                }
        );

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    android.util.Log.d("StoragePicker", "picker result uri=" + uri
                            + " extendedTarget=" + extendedPickerTarget);
                    if (uri != null) {
                        String path = getPathFromTreeUri(uri);
                        android.util.Log.d("StoragePicker", "parsed path=" + path);
                        if (path == null) {
                            return;
                        }
                        // v0.4.12：选完目录自动应用（此前只把路径填进
                        // 输入栏，用户以为 SAF 的"应用"已生效——
                        // "文件管理器选择后点应用不生效"根因）
                        if (extendedPickerTarget && extendedRootPathInput != null) {
                            extendedRootPathInput.setText(path);
                            applyExtendedRootPath();
                        } else if (customStoragePathInput != null) {
                            customStoragePathInput.setText(path);
                            // v0.4.14：跳过确认弹窗直接生效（确认框在 SAF
                            // 返回时序里弹不出来=选择后无反应根因）
                            applyCustomStoragePath(true);
                        }
                    }
                }
        );

        initTabs();
        setupBasicSection();
        setupPersonalizeSection();
        setupMigrationSection();
        setupAboutSection();

        TextView[] tabs = getSettingsTabs();
        if (selectedTabIndex >= tabs.length) {
            selectedTabIndex = 0;
        }
        selectTab(tabs[selectedTabIndex]);

        // v0.0.5：tab 行复位到最左——恢复上次 tab（如「关于」）时
        // 第一个 tab「基础设置」（含语言切换）不能滚出屏幕外
        HorizontalScrollView tabScroll = findViewById(R.id.settings_tab_scroll);
        if (tabScroll != null) {
            tabScroll.post(() -> tabScroll.scrollTo(0, 0));
        }
    }

    /** v711：检查更新卡片（关于 tab）——版本显示 + 手动检查按钮。 */
    private void setupUpdateCheck() {
        try {
            TextView verText = findViewById(R.id.check_update_version);
            if (verText != null) {
                android.content.pm.PackageInfo pi = getPackageManager()
                        .getPackageInfo(getPackageName(), 0);
                verText.setText(getString(R.string.update_current_version,
                        pi != null ? pi.versionName : "?"));
            }
            View btn = findViewById(R.id.check_update_button);
            if (btn != null) {
                org.levimc.launcher.util.AccentStyler.stylePrimary(this, btn);
                btn.setOnClickListener(v -> {
                    Toast.makeText(this, R.string.check_update_checking,
                            Toast.LENGTH_SHORT).show();
                    org.levimc.launcher.core.updates.UpdateChecker.checkAsync(
                            this, true, (status, u) -> {
                                // v0.0.12：检查慢时用户可能已退出设置页，
                                // Activity 销毁后弹窗会 BadTokenException 崩溃（vivo 实测）
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                if (status == org.levimc.launcher.core.updates
                                        .UpdateChecker.RESULT_UPDATE) {
                                    org.levimc.launcher.core.updates.UpdateChecker
                                            .showUpdateDialog(this, u);
                                } else if (status == org.levimc.launcher.core.updates
                                        .UpdateChecker.RESULT_FAILED) {
                                    Toast.makeText(this, R.string.check_update_failed,
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(this, R.string.check_update_latest,
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private void initTabs() {
        tabBasic = findViewById(R.id.tab_basic);
        tabPersonalize = findViewById(R.id.tab_personalize);
        tabMigration = findViewById(R.id.tab_migration);
        tabAbout = findViewById(R.id.tab_about);
        tabDebug = findViewById(R.id.tab_debug);

        sectionBasic = findViewById(R.id.section_basic);
        sectionPersonalize = findViewById(R.id.section_personalize);
        sectionMigration = findViewById(R.id.section_migration);
        sectionAbout = findViewById(R.id.section_about);
        sectionDebug = findViewById(R.id.section_debug);

        tabBasic.setOnClickListener(v -> { selectedTabIndex = 0; selectTab(tabBasic); });
        tabPersonalize.setOnClickListener(v -> { selectedTabIndex = 1; selectTab(tabPersonalize); });
        tabAbout.setOnClickListener(v -> { selectedTabIndex = 2; selectTab(tabAbout); });
        tabMigration.setOnClickListener(v -> { selectedTabIndex = 3; selectTab(tabMigration); });
        tabDebug.setOnClickListener(v -> { selectedTabIndex = 4; selectTab(tabDebug); });
        // v0.4.192：调试页（M3 Dummy UI 展示）
        setupDebugSection();
    }

    private void selectTab(TextView selectedTab) {
        TextView[] tabs = getSettingsTabs();
        View[] sections = {sectionBasic, sectionPersonalize, sectionAbout, sectionMigration, sectionDebug};

        int accent = personalizationManager.getAccentColor();

        for (int i = 0; i < tabs.length; i++) {
            boolean isSelected = tabs[i] == selectedTab;

            if (isSelected) {
                if (accent != 0) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    gd.setColor(accent);
                    gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
                    tabs[i].setBackground(gd);
                } else {
                    tabs[i].setBackgroundResource(R.drawable.bg_tab_selected);
                }
                tabs[i].setTextColor(Color.WHITE);
                tabs[i].setTextSize(13);
            } else {
                tabs[i].setBackgroundResource(R.drawable.bg_tab_unselected);
                tabs[i].setTextColor(getColor(R.color.text_secondary));
            }

            if (isSelected) {
                sections[i].setVisibility(View.VISIBLE);
                sections[i].setAlpha(0f);
                sections[i].animate().alpha(1f).setDuration(200).start();
            } else {
                sections[i].setVisibility(View.GONE);
            }
        }
    }

    private TextView[] getSettingsTabs() {
        return new TextView[]{tabBasic, tabPersonalize, tabAbout, tabMigration, tabDebug};
    }

    private void setupBasicSection() {
        LanguageManager languageManager = new LanguageManager(this);
        FeatureSettings fs = FeatureSettings.getInstance();

        // Keep English at the top (default language).
        // Sort all other languages alphabetically by their display name.
        String[] languageOptions = {
                getString(R.string.english),
                getString(R.string.chinese),
                getString(R.string.french),
                getString(R.string.hindi),
                getString(R.string.indonesian),
                getString(R.string.japanese),
                getString(R.string.portuguese),
                getString(R.string.russian),
                getString(R.string.spanish),
                getString(R.string.turkish),
                getString(R.string.vietnamese)
        };

        String currentCode = languageManager.getCurrentLanguage();
        int defaultIdx = switch (currentCode) {
            case "zh", "zh-CN" -> 1;
            case "fr" -> 2;
            case "hi" -> 3;
            case "idn" -> 4;
            case "ja" -> 5;
            case "pt" -> 6;
            case "ru" -> 7;
            case "es" -> 8;
            case "tr", "tr-TR" -> 9;
            case "vi" -> 10;
            default -> 0;
        };

        TextView languageCurrent = findViewById(R.id.language_current);
        languageCurrent.setText(languageOptions[defaultIdx]);

        Spinner languageSpinner = findViewById(R.id.language_spinner);
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, languageOptions);
        langAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);
        languageSpinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        languageSpinner.setSelection(defaultIdx);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String code = switch (position) {
                    case 1 -> "zh-CN";
                    case 2 -> "fr";
                    case 3 -> "hi";
                    case 4 -> "idn";
                    case 5 -> "ja";
                    case 6 -> "pt";
                    case 7 -> "ru";
                    case 8 -> "es";
                    case 9 -> "tr";
                    case 10 -> "vi";
                    default -> "en";
                };
                if (!code.equals(languageManager.getCurrentLanguage())) {
                    languageManager.setAppLanguage(code);
                }
                languageCurrent.setText(languageOptions[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        SwitchMaterial switchLogcat = findViewById(R.id.switch_logcat);
        switchLogcat.setChecked(fs.isLogcatOverlayEnabled());
        switchLogcat.setOnCheckedChangeListener((btn, checked) -> {
            fs.setLogcatOverlayEnabled(checked);
            try {
                LogcatOverlayManager mgr = LogcatOverlayManager.getInstance();
                if (mgr != null) mgr.refreshVisibility();
            } catch (Throwable ignored) {}
        });

        // v587：前台服务开关（官方原样——默认关，保活行为与官方一致）
        SwitchMaterial switchForegroundService = findViewById(R.id.switch_foreground_service);
        switchForegroundService.setChecked(fs.isForegroundServiceEnabled());
        switchForegroundService.setOnCheckedChangeListener((btn, checked) -> fs.setForegroundServiceEnabled(checked));

        // v0.4.11：手柄光标灵敏度（0.5x-3.0x，默认 1.0x）
        com.google.android.material.slider.Slider sliderCursorSpeed =
                findViewById(R.id.slider_cursor_speed);
        TextView cursorSpeedValue = findViewById(R.id.cursor_speed_value);
        if (sliderCursorSpeed != null) {
            // v0.4.17：滑块跟随个性化强调色（与开关/按钮一致）
            PersonalizationManager pmSpeed = new PersonalizationManager(this);
            int accentSpeed = pmSpeed.hasCustomAccent() ? pmSpeed.getAccentColor()
                    : ContextCompat.getColor(this, R.color.primary);
            sliderCursorSpeed.setThumbTintList(ColorStateList.valueOf(accentSpeed));
            sliderCursorSpeed.setTrackActiveTintList(ColorStateList.valueOf(accentSpeed));
            float saved = getSharedPreferences("settings", MODE_PRIVATE)
                    .getFloat("gamepad_cursor_speed", 1f);
            sliderCursorSpeed.setValue(saved);
            if (cursorSpeedValue != null) {
                cursorSpeedValue.setText(String.format(java.util.Locale.getDefault(),
                        "%.1fx", saved));
            }
            sliderCursorSpeed.addOnChangeListener((s, value, fromUser) -> {
                if (cursorSpeedValue != null) {
                    cursorSpeedValue.setText(String.format(java.util.Locale.getDefault(),
                            "%.1fx", value));
                }
                if (fromUser) {
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                            .putFloat("gamepad_cursor_speed", value).apply();
                }
            });
        }

        setupGlobalConfigSection();
    }

    private void setupGlobalConfigSection() {
        View row = findViewById(R.id.global_config_row);
        Button configureButton = findViewById(R.id.btn_configure_global);
        if (row == null) return;

        refreshGlobalConfigSummary();

        int accent = personalizationManager != null ? personalizationManager.getAccentColor() : 0;
        if (accent != 0 && configureButton != null) {
            configureButton.setBackgroundTintList(ColorStateList.valueOf(accent));
            configureButton.setTextColor(Color.WHITE);
        }

        View.OnClickListener openDialog = v -> showGlobalConfigDialog();
        row.setOnClickListener(openDialog);
        if (configureButton != null) {
            configureButton.setOnClickListener(openDialog);
        }
    }

    private void refreshGlobalConfigSummary() {
        TextView summary = findViewById(R.id.global_config_summary);
        if (summary == null) return;
        String value = GlobalConfigManager.summary(this);
        summary.setText(value != null ? value : getString(R.string.global_config_not_set));
    }

    private void showGlobalConfigDialog() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int) (8 * density), 0, (int) (8 * density), (int) (8 * density));

        ConfigField[] fields = {
                new ConfigField(
                        new String[]{GlobalConfigManager.KEY_VIEW_DISTANCE},
                        getString(R.string.global_config_view_distance),
                        new String[][]{{"6 区块", "96"}, {"8 区块", "128"}, {"10 区块", "160"}, {"12 区块", "192"},
                                {"16 区块", "256"}, {"20 区块", "320"}, {"24 区块", "384"}, {"32 区块", "512"}}),
                new ConfigField(
                        new String[]{GlobalConfigManager.KEY_UI_PROFILE},
                        getString(R.string.global_config_ui_type),
                        new String[][]{{getString(R.string.ui_classic), "0"}, {getString(R.string.ui_pocket), "1"}}),
                new ConfigField(
                        new String[]{GlobalConfigManager.KEY_TOUCH_SCHEME},
                        getString(R.string.global_config_touch_scheme),
                        new String[][]{{getString(R.string.touch_joystick_tap), "0"}, {getString(R.string.touch_joystick_crosshair), "1"}, {getString(R.string.touch_dpad_tap), "2"}}),
                new ConfigField(
                        new String[]{GlobalConfigManager.KEY_SPLIT_CONTROL},
                        getString(R.string.global_config_split_control),
                        new String[][]{{getString(R.string.global_config_off), "0"}, {getString(R.string.global_config_on), "1"}}),
        };

        List<Spinner> spinners = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            ConfigField field = fields[i];

            TextView label = new TextView(this);
            label.setText(field.label);
            label.setTextColor(getColor(R.color.on_surface));
            label.setTextSize(13);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) labelParams.topMargin = (int) (12 * density);
            label.setLayoutParams(labelParams);
            container.addView(label);

            List<String> optionLabels = new ArrayList<>();
            optionLabels.add(getString(R.string.global_config_keep_default));
            for (String[] opt : field.options) optionLabels.add(opt[0]);

            Spinner spinner = new androidx.appcompat.widget.AppCompatSpinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, optionLabels);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
            // 用静态背景，避免默认 state selector 背景在滚动时导致下拉箭头闪烁
            spinner.setBackgroundResource(R.drawable.bg_spinner_outline);

            int selectedIndex = 0;
            String current = GlobalConfigManager.get(this, field.keys[0]);
            if (current != null) {
                for (int j = 0; j < field.options.length; j++) {
                    if (field.options[j][1].equals(current)) {
                        selectedIndex = j + 1;
                        break;
                    }
                }
            }
            spinner.setSelection(selectedIndex);

            LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            spinnerParams.topMargin = (int) (4 * density);
            spinner.setLayoutParams(spinnerParams);
            container.addView(spinner);
            spinners.add(spinner);
        }

        // 视野：滑块（30° ~ 110°）
        TextView fovLabel = new TextView(this);
        fovLabel.setText(getString(R.string.global_config_fov));
        fovLabel.setTextColor(getColor(R.color.on_surface));
        fovLabel.setTextSize(13);
        fovLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams fovLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fovLabelParams.topMargin = (int) (12 * density);
        fovLabel.setLayoutParams(fovLabelParams);
        container.addView(fovLabel);

        final TextView fovValue = new TextView(this);
        fovValue.setTextColor(getColor(R.color.text_secondary));
        fovValue.setTextSize(12);
        fovValue.setGravity(Gravity.END);
        fovValue.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(fovValue);

        final SeekBar fovSeek = new SeekBar(this);
        fovSeek.setMax(80); // 0~80 对应 30°~110°
        int seekColor = personalizationManager != null && personalizationManager.getAccentColor() != 0
                ? personalizationManager.getAccentColor() : getColor(R.color.primary);
        fovSeek.setProgressTintList(ColorStateList.valueOf(seekColor));
        fovSeek.setThumbTintList(ColorStateList.valueOf(seekColor));
        int fovProgress = fovToProgress(GlobalConfigManager.get(this, GlobalConfigManager.KEY_FOV));
        fovSeek.setProgress(fovProgress);
        fovValue.setText((fovProgress + 30) + "°");
        fovSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fovValue.setText((progress + 30) + "°");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        fovSeek.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(fovSeek);

        // 安全区：滑块（0% ~ 100%）
        TextView safeZoneLabel = new TextView(this);
        safeZoneLabel.setText(getString(R.string.global_config_safe_zone));
        safeZoneLabel.setTextColor(getColor(R.color.on_surface));
        safeZoneLabel.setTextSize(13);
        safeZoneLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams safeLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        safeLabelParams.topMargin = (int) (12 * density);
        safeZoneLabel.setLayoutParams(safeLabelParams);
        container.addView(safeZoneLabel);

        final TextView safeZoneValue = new TextView(this);
        safeZoneValue.setTextColor(getColor(R.color.text_secondary));
        safeZoneValue.setTextSize(12);
        safeZoneValue.setGravity(Gravity.END);
        safeZoneValue.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(safeZoneValue);

        final SeekBar safeZoneSeek = new SeekBar(this);
        safeZoneSeek.setMax(100);
        safeZoneSeek.setProgressTintList(ColorStateList.valueOf(seekColor));
        safeZoneSeek.setThumbTintList(ColorStateList.valueOf(seekColor));
        int safePercent = parseSafeZonePercent(GlobalConfigManager.get(this, GlobalConfigManager.KEY_SAFE_ZONE_X));
        safeZoneSeek.setProgress(safePercent);
        safeZoneValue.setText(safePercent + "%");
        safeZoneSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                safeZoneValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        safeZoneSeek.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(safeZoneSeek);

        // 包进 ScrollView，并给一个受约束的高度，让内容真正滚动、标题与按钮始终可见。
        // 高度取屏幕高度的 40%，既保证小屏手机按钮不被挤出，也远小于弹窗 maxHeight(500dp)。
        ScrollView scrollView = new ScrollView(this);
        int scrollHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.4f);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scrollHeight));
        scrollView.addView(container);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.global_config_title))
                .setCustomView(scrollView)
                .setPositiveButton(getString(R.string.apply), v -> {
                    for (int i = 0; i < fields.length; i++) {
                        int idx = spinners.get(i).getSelectedItemPosition();
                        if (idx <= 0) continue; // 不修改
                        String value = fields[i].options[idx - 1][1];
                        for (String key : fields[i].keys) {
                            GlobalConfigManager.set(this, key, value);
                        }
                    }
                    int fovDegrees = 30 + fovSeek.getProgress();
                    GlobalConfigManager.set(this, GlobalConfigManager.KEY_FOV, String.valueOf(fovDegrees));
                    String safeValue = String.valueOf(safeZoneSeek.getProgress() / 100.0);
                    GlobalConfigManager.set(this, GlobalConfigManager.KEY_SAFE_ZONE_X, safeValue);
                    GlobalConfigManager.set(this, GlobalConfigManager.KEY_SAFE_ZONE_Y, safeValue);
                    refreshGlobalConfigSummary();
                    Toast.makeText(this, R.string.global_config_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private int parseSafeZonePercent(String value) {
        if (value == null || value.isEmpty()) return 100;
        try {
            return (int) Math.round(Float.parseFloat(value) * 100f);
        } catch (NumberFormatException ignored) {
            return 100;
        }
    }

    private int fovToProgress(String value) {
        if (value == null || value.isEmpty()) return 40; // 默认 70° → progress 40
        try {
            int degrees = Integer.parseInt(value);
            return Math.max(0, Math.min(80, degrees - 30));
        } catch (NumberFormatException ignored) {
            return 40;
        }
    }

    private static class ConfigField {
        final String[] keys;
        final String label;
        final String[][] options;

        ConfigField(String[] keys, String label, String[][] options) {
            this.keys = keys;
            this.label = label;
            this.options = options;
        }
    }

    private void setupPersonalizeSection() {
        ThemeManager themeManager = new ThemeManager(this);

        View itemSystem = findViewById(R.id.theme_item_system);
        View itemLight = findViewById(R.id.theme_item_light);
        View itemDark = findViewById(R.id.theme_item_dark);

        refreshThemeSelectionUI();

        if (itemSystem != null && itemLight != null && itemDark != null) {
            itemSystem.setOnClickListener(v -> { themeManager.setThemeMode(0); });
            itemLight.setOnClickListener(v -> { themeManager.setThemeMode(1); });
            itemDark.setOnClickListener(v -> { themeManager.setThemeMode(2); });
        }

        setupColorPicker();
        setupBackgroundImagePicker();
    }

    private void refreshThemeSelectionUI() {
        ThemeManager themeManager = new ThemeManager(this);
        int currentMode = themeManager.getCurrentMode();

        TextView textSystem = findViewById(R.id.theme_text_system);
        TextView textLight = findViewById(R.id.theme_text_light);
        TextView textDark = findViewById(R.id.theme_text_dark);

        ImageView iconSystem = findViewById(R.id.theme_icon_system);
        ImageView iconLight = findViewById(R.id.theme_icon_light);
        ImageView iconDark = findViewById(R.id.theme_icon_dark);

        int accent = personalizationManager.getAccentColor();
        int selectedColor = accent != 0 ? accent : getColor(R.color.on_surface);
        int unselectedColor = getColor(R.color.text_secondary);

        if (textSystem != null) {
            textSystem.setTypeface(null, currentMode == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            textSystem.setTextColor(currentMode == 0 ? selectedColor : getColor(R.color.on_surface));
        }
        if (textLight != null) {
            textLight.setTypeface(null, currentMode == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            textLight.setTextColor(currentMode == 1 ? selectedColor : getColor(R.color.on_surface));
        }
        if (textDark != null) {
            textDark.setTypeface(null, currentMode == 2 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            textDark.setTextColor(currentMode == 2 ? selectedColor : getColor(R.color.on_surface));
        }

        if (iconSystem != null) iconSystem.setImageTintList(android.content.res.ColorStateList.valueOf(currentMode == 0 ? selectedColor : unselectedColor));
        if (iconLight != null) iconLight.setImageTintList(android.content.res.ColorStateList.valueOf(currentMode == 1 ? selectedColor : unselectedColor));
        if (iconDark != null) iconDark.setImageTintList(android.content.res.ColorStateList.valueOf(currentMode == 2 ? selectedColor : unselectedColor));
    }

    private void setupColorPicker() {
        colorGridContainer = findViewById(R.id.color_preset_grid);
        moreColorsContainer = findViewById(R.id.color_more_grid);

        if (colorGridContainer != null && moreColorsContainer != null) {
            int currentAccent = personalizationManager.getAccentColor();
            buildColorGrid(colorGridContainer, PersonalizationManager.PRESET_COLORS, currentAccent);
            buildColorGrid(moreColorsContainer, PersonalizationManager.MORE_COLORS, currentAccent);
        }

        android.widget.EditText inputCustomColor = findViewById(R.id.input_custom_color);
        Button btnApplyColor = findViewById(R.id.btn_apply_color);
        if (inputCustomColor != null && btnApplyColor != null) {
            btnApplyColor.setOnClickListener(v -> {
                String input = inputCustomColor.getText().toString().trim();
                try {
                    int color;
                    if (input.startsWith("#")) {
                        color = Color.parseColor(input);
                    } else if (input.contains(",")) {
                        String[] parts = input.split(",");
                        if (parts.length == 3) {
                            color = Color.rgb(Integer.parseInt(parts[0].trim()),
                                    Integer.parseInt(parts[1].trim()),
                                    Integer.parseInt(parts[2].trim()));
                        } else {
                            throw new IllegalArgumentException("Invalid RGB format");
                        }
                    } else {
                        color = Color.parseColor("#" + input);
                    }
                    personalizationManager.setAccentColor(color);
                    refreshColorPickerInPlace();
                } catch (Exception e) {
                    Toast.makeText(this, R.string.invalid_color_format, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void buildColorGrid(LinearLayout container, int[] colors, int selectedColor) {
        container.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int circleSize = (int) (32 * density);
        int margin = (int) (4 * density);
        int checkSize = (int) (14 * density);

        int columns = 15;
        int index = 0;
        while (index < colors.length) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int col = 0; col < columns && index < colors.length; col++, index++) {
                int color = colors[index];

                FrameLayout wrapper = new FrameLayout(this);
                LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(circleSize, circleSize);
                wrapParams.setMargins(margin, margin, margin, margin);
                wrapper.setLayoutParams(wrapParams);

                View circle = new View(this);
                circle.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                GradientDrawable circleDrawable = new GradientDrawable();
                circleDrawable.setShape(GradientDrawable.OVAL);
                circleDrawable.setColor(color);
                if (color == selectedColor) {
                    circleDrawable.setStroke((int) (2 * density), Color.WHITE);
                }
                circle.setBackground(circleDrawable);
                wrapper.addView(circle);

                if (color == selectedColor) {
                    ImageView check = new ImageView(this);
                    FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(checkSize, checkSize);
                    checkParams.gravity = Gravity.CENTER;
                    check.setLayoutParams(checkParams);
                    check.setImageResource(R.drawable.ic_check);
                    check.setColorFilter(Color.WHITE);
                    wrapper.addView(check);
                }

                wrapper.setClickable(true);
                wrapper.setFocusable(true);
                final int finalColor = color;
                wrapper.setOnClickListener(v -> {
                    personalizationManager.setAccentColor(finalColor);
                    refreshColorPickerInPlace();
                });
                DynamicAnim.applyPressScale(wrapper);

                row.addView(wrapper);
            }

            container.addView(row);
        }

    }

    private void refreshColorPickerInPlace() {
        setupColorPicker();
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        
        pm.applyToActivity(this);

        refreshThemeSelectionUI();
        
        TextView[] tabs = getSettingsTabs();
        selectTab(tabs[selectedTabIndex]);
        
        View settingsTitle = findViewById(R.id.settings_title);
        if (settingsTitle instanceof TextView && accent != 0) {
            ((TextView) settingsTitle).setTextColor(accent);
        }
        
        Button btnSelectImage = findViewById(R.id.btn_select_bg_image);
        if (btnSelectImage != null && accent != 0) {
            btnSelectImage.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnSelectImage.setTextColor(Color.WHITE);
        }

        // v0.4.44：上报按钮描边随强调色即时刷新（setupAboutSection 只在启动时执行）
        applyReportButtonAccent();
        
        SwitchMaterial switchLogcat = findViewById(R.id.switch_logcat);
        if (switchLogcat != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchLogcat.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchLogcat.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }

        // v587：前台服务开关着色（官方原样）
        SwitchMaterial switchForegroundService = findViewById(R.id.switch_foreground_service);
        if (switchForegroundService != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchForegroundService.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchForegroundService.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }

        Button btnApplyStorage = findViewById(R.id.btn_apply_custom_storage_path);
        if (btnApplyStorage != null && accent != 0) {
            btnApplyStorage.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnApplyStorage.setTextColor(Color.WHITE);
        }

        Button btnResetStorage = findViewById(R.id.btn_reset_custom_storage_path);
        if (btnResetStorage != null && accent != 0) {
            btnResetStorage.setTextColor(Color.WHITE);
            btnResetStorage.setBackgroundTintList(ColorStateList.valueOf(accent));
        }

        Button btnConfigureGlobal = findViewById(R.id.btn_configure_global);
        if (btnConfigureGlobal != null && accent != 0) {
            btnConfigureGlobal.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnConfigureGlobal.setTextColor(Color.WHITE);
        }

        Button btnApplyColor = findViewById(R.id.btn_apply_color);
        if (btnApplyColor != null && accent != 0) {
            btnApplyColor.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnApplyColor.setTextColor(Color.WHITE);
        }

        int[] helpIconIds = {R.id.help_chunkbase_icon, R.id.help_mcwiki_icon, R.id.help_bilibili_icon};
        for (int id : helpIconIds) {
            ImageView icon = findViewById(id);
            if (icon != null && accent != 0) {
                icon.setImageTintList(ColorStateList.valueOf(accent));
            }
        }

        TextView navAppName = findViewById(R.id.nav_app_name);
        if (navAppName != null && accent != 0) {
            pm.applySolidAccentText(navAppName, accent);
        }
        
        Button navSignInBtn = findViewById(R.id.nav_sign_in_button);
        if (navSignInBtn != null && accent != 0) {
            navSignInBtn.setBackgroundTintList(ColorStateList.valueOf(accent));
            navSignInBtn.setTextColor(Color.WHITE);
        }
        
        int[] navTabIds = {R.id.nav_tab_launch, R.id.nav_tab_instances,
                R.id.nav_tab_online, R.id.nav_tab_settings};
        for (int id : navTabIds) {
            TextView navTab = findViewById(id);
            if (navTab != null && id == R.id.nav_tab_settings && accent != 0) {
                navTab.setTextColor(accent);
                navTab.setTypeface(navTab.getTypeface(), android.graphics.Typeface.BOLD);
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(navTab, ColorStateList.valueOf(accent));
            }
        }
    }

    private void setupBackgroundImagePicker() {
        bgImageStatus = findViewById(R.id.bg_image_status);
        bgImagePreview = findViewById(R.id.bg_image_preview);
        Button btnSelectImage = findViewById(R.id.btn_select_bg_image);
        Button btnClearImage = findViewById(R.id.btn_clear_bg_image);

        if (btnSelectImage == null) return;

        setupBackgroundImageControls();
        setupUiScaleControls();
        updateBgImageUI();

        btnSelectImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            // v0.2.0：背景支持 GIF 动图与视频
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"image/*", "video/*"});
            bgImagePickerLauncher.launch(intent);
        });

        if (btnClearImage != null) {
            btnClearImage.setOnClickListener(v -> {
                personalizationManager.clearBackgroundImage();
                updateBgImageUI();
                recreate();
            });
        }
    }

    private void setupBackgroundImageControls() {
        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        SeekBar brightnessSeek = findViewById(R.id.seek_bg_image_brightness);
        bgImageBlurValue = findViewById(R.id.bg_image_blur_value);
        bgImageBrightnessValue = findViewById(R.id.bg_image_brightness_value);

        if (blurSeek != null) {
            blurSeek.setMax(PersonalizationManager.BG_BLUR_MAX);
            blurSeek.setProgress(personalizationManager.getBackgroundImageBlur());
            updateBgImageBlurValue(blurSeek.getProgress());
            blurSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    updateBgImageBlurValue(progress);
                    if (!fromUser) return;
                    personalizationManager.setBackgroundImageBlur(progress);
                    if (personalizationManager.supportsRealtimeBackgroundBlur()) {
                        refreshBackgroundImageEffects();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    refreshBackgroundImageEffects();
                }
            });
        }

        if (brightnessSeek != null) {
            brightnessSeek.setMin(PersonalizationManager.BG_BRIGHTNESS_MIN);
            brightnessSeek.setMax(PersonalizationManager.BG_BRIGHTNESS_MAX);
            brightnessSeek.setProgress(personalizationManager.getBackgroundImageBrightness());
            updateBgImageBrightnessValue(brightnessSeek.getProgress());
            brightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    updateBgImageBrightnessValue(progress);
                    if (!fromUser) return;
                    personalizationManager.setBackgroundImageBrightness(progress);
                    refreshBackgroundImageColorEffects();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
    }

    private void setupUiScaleControls() {
        SeekBar uiScaleSeek = findViewById(R.id.seek_ui_scale);
        TextView uiScaleValue = findViewById(R.id.ui_scale_value);
        Button resetButton = findViewById(R.id.btn_reset_ui_scale);

        // progress 0..110 → 0.85x..1.4x（30 = 1.0x）
        final float span = PersonalizationManager.UI_SCALE_MAX - PersonalizationManager.UI_SCALE_MIN;
        if (uiScaleSeek != null) {
            uiScaleSeek.setMax(110);
            float cur = personalizationManager.getUiScale();
            uiScaleSeek.setProgress(Math.round((cur - PersonalizationManager.UI_SCALE_MIN) / span * 110f));
            if (uiScaleValue != null) {
                uiScaleValue.setText(getString(R.string.ui_scale_percent, Math.round(cur * 100)));
            }
            uiScaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float scale = PersonalizationManager.UI_SCALE_MIN + span * progress / 110f;
                    if (uiScaleValue != null) {
                        uiScaleValue.setText(getString(R.string.ui_scale_percent, Math.round(scale * 100)));
                    }
                    if (!fromUser) return;
                    // 一个滑块同时控制 UI 与字体（density + fontScale 叠加）
                    personalizationManager.setUiScale(scale);
                    personalizationManager.setFontScale(scale);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    // 实时生效：重建当前 Activity，attachBaseContext 重新应用缩放
                    recreate();
                }
            });
        }
        if (resetButton != null) {
            // 背景 primary + on_primary 文字，applyAccentColorRecursive 自动套用个性化颜色
            resetButton.setOnClickListener(v -> {
                personalizationManager.setUiScale(PersonalizationManager.UI_SCALE_DEFAULT);
                personalizationManager.setFontScale(PersonalizationManager.FONT_SCALE_DEFAULT);
                recreate();
            });
        }
    }

    private void updateBgImageBlurValue(int blurRadius) {
        if (bgImageBlurValue != null) {
            bgImageBlurValue.setText(getString(R.string.bg_image_blur_value, blurRadius));
        }
    }

    private void updateBgImageBrightnessValue(int brightnessPercent) {
        if (bgImageBrightnessValue != null) {
            bgImageBrightnessValue.setText(getString(R.string.bg_image_brightness_value, brightnessPercent));
        }
    }

    private void refreshBackgroundImageEffects() {
        personalizationManager.refreshBackgroundEffects(this);
        if (bgImagePreview != null) {
            personalizationManager.refreshBackgroundImageView(bgImagePreview);
        }
    }

    private void refreshBackgroundImageColorEffects() {
        personalizationManager.refreshBackgroundColorEffects(this);
        if (bgImagePreview != null) {
            personalizationManager.applyBackgroundImageEffects(bgImagePreview);
        }
    }

    private void updateBgImageUI() {
        if (bgImageStatus == null) return;
        boolean hasBackgroundImage = personalizationManager.hasBackgroundImage();
        View effectControls = findViewById(R.id.bg_image_effect_controls);
        if (effectControls != null) {
            effectControls.setVisibility(hasBackgroundImage ? View.VISIBLE : View.GONE);
        }

        if (hasBackgroundImage) {
            bgImageStatus.setText(R.string.bg_image_selected);
            if (bgImagePreview != null) {
                if (personalizationManager.applyBackgroundImageToView(bgImagePreview)) {
                    bgImagePreview.setVisibility(View.VISIBLE);
                }
            }
            View btnClear = findViewById(R.id.btn_clear_bg_image);
            if (btnClear != null) btnClear.setVisibility(View.VISIBLE);
        } else {
            bgImageStatus.setText(R.string.bg_image_none);
            if (bgImagePreview != null) {
                bgImagePreview.setImageDrawable(null);
                bgImagePreview.setVisibility(View.GONE);
            }
            View btnClear = findViewById(R.id.btn_clear_bg_image);
            if (btnClear != null) btnClear.setVisibility(View.GONE);
        }
    }



    private void setupMigrationSection() {
        setupCustomStoragePathSection();
    }

    /** v0.4.13：选择器目标（true=启动器数据路径，false=游戏数据自定义路径）。 */
    private boolean extendedPickerTarget;

    private void setupCustomStoragePathSection() {
        customStoragePathCurrent = findViewById(R.id.custom_storage_path_current);
        customStoragePathInput = findViewById(R.id.input_custom_storage_path);
        Button btnSelectApply = findViewById(R.id.btn_apply_custom_storage_path);
        Button btnReset = findViewById(R.id.btn_reset_custom_storage_path);

        // v0.4.13：启动器数据路径（备份/HTML/结构文件/缓存，默认
        // Download/LeviLauncher，支持更改）
        setupExtendedRootPathSection();

        if (customStoragePathInput != null) {
            // v0.4.23：不预填文本——当前生效路径作灰色示例 hint 显示
            // （与启动器数据路径一致）；预填文本会把按钮卡在「应用」
            String custom = LauncherStorage.getCustomStoragePath(this);
            customStoragePathInput.setHint(custom != null ? custom
                    : LauncherStorage.getTargetAppRootDisplayPath(this));
            customStoragePathInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateSelectApplyButtonState();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnSelectApply != null) {
            // v0.4.20：双态按钮——「选择」（开文件管理器，选完直接
            // 默认应用）/「应用」（应用输入内容）；当前路径显示在
            // 灰色 hint 里（v0.4.23），输入框空即「选择」
            btnSelectApply.setOnClickListener(v -> {
                if (getString(R.string.custom_storage_path_select)
                        .contentEquals(btnSelectApply.getText())) {
                    extendedPickerTarget = false;
                    openFolderPicker();
                } else {
                    applyCustomStoragePath();
                }
            });
        }
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> resetCustomStoragePath());
        }

        refreshCustomStoragePathUi();
        updateSelectApplyButtonState();
    }

    private void updateSelectApplyButtonState() {
        Button btn = findViewById(R.id.btn_apply_custom_storage_path);
        if (btn == null) return;
        // v0.4.23：空=「选择」（当前路径在灰色 hint 里）/有文本=「应用」
        boolean hasText = customStoragePathInput != null
                && !customStoragePathInput.getText().toString().trim().isEmpty();
        btn.setText(hasText ? R.string.custom_storage_path_apply : R.string.custom_storage_path_select);
    }

    private void openFolderPicker() {
        extendedPickerTarget = false;
        if (folderPickerLauncher != null) {
            folderPickerLauncher.launch(null);
        }
    }

    /** v0.4.13：启动器数据路径区块（备份/HTML/结构文件/缓存）。 */
    private void setupExtendedRootPathSection() {
        extendedRootPathCurrent = findViewById(R.id.extended_root_path_current);
        extendedRootPathInput = findViewById(R.id.input_extended_root_path);
        Button btnSelect = findViewById(R.id.btn_apply_extended_root_path);
        Button btnReset = findViewById(R.id.btn_reset_extended_root_path);

        // v0.4.14：示例路径 hint（旧自定义存储输入框同款）
        if (extendedRootPathInput != null) {
            // v0.4.23：示例=当前生效路径，灰色 hint 显示（不再预填
            // 普通色文本——用户要求与自定义存储路径一样灰）
            extendedRootPathInput.setHint(LauncherStorage.getExtendedRootPath(this));
            // v0.4.20：双态按钮联动——空=「选择」/有文本=「应用」
            extendedRootPathInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateExtendedApplyButtonState();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnSelect != null) {
            // v0.4.20：双态按钮（与自定义存储区块一致）；当前路径
            // 显示在灰色 hint 里（v0.4.23），输入框空即「选择」
            btnSelect.setOnClickListener(v -> {
                if (getString(R.string.custom_storage_path_select)
                        .contentEquals(btnSelect.getText())) {
                    extendedPickerTarget = true;
                    if (folderPickerLauncher != null) {
                        folderPickerLauncher.launch(null);
                    }
                } else {
                    applyExtendedRootPath();
                }
            });
        }
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> {
                LauncherStorage.setExtendedRootPath(this, null);
                // v0.4.23：重置后清空文本，hint 显示回默认路径
                if (extendedRootPathInput != null) {
                    extendedRootPathInput.setText("");
                    extendedRootPathInput.setHint(LauncherStorage.getExtendedRootPath(this));
                }
                refreshExtendedRootPathUi();
            });
        }
        refreshExtendedRootPathUi();
        updateExtendedApplyButtonState();
    }

    private void updateExtendedApplyButtonState() {
        Button btn = findViewById(R.id.btn_apply_extended_root_path);
        if (btn == null) {
            return;
        }
        // v0.4.23：空=「选择」（当前路径在灰色 hint 里）/有文本=「应用」
        boolean hasText = extendedRootPathInput != null
                && !extendedRootPathInput.getText().toString().trim().isEmpty();
        btn.setText(hasText ? R.string.custom_storage_path_apply
                : R.string.custom_storage_path_select);
    }

    private void refreshExtendedRootPathUi() {
        if (extendedRootPathCurrent != null) {
            extendedRootPathCurrent.setText(getString(R.string.extended_root_path_current,
                    LauncherStorage.getExtendedRootPath(this)));
        }
    }

    private void applyExtendedRootPath() {
        if (extendedRootPathInput == null) {
            return;
        }
        String path = extendedRootPathInput.getText().toString().trim();
        LauncherStorage.setExtendedRootPath(this, path.isEmpty() ? null : path);
        refreshExtendedRootPathUi();
        // v0.4.23：应用后清空文本，灰色 hint 显示新生效路径（用户
        // 要求示例与自定义存储路径一样灰）——按钮回「选择」
        extendedRootPathInput.setText("");
        extendedRootPathInput.setHint(LauncherStorage.getExtendedRootPath(this));
        // setText("") 与现文本一致时 Watcher 不触发——显式重算按钮
        updateExtendedApplyButtonState();
        // 地图烘焙缓存位置变化：旧缓存读不到会自动重烘（缓存可再生）
    }

    private String getPathFromTreeUri(Uri uri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
            if (docId == null) return null;
            String[] parts = docId.split(":", 2);
            if (parts.length >= 2) {
                String vol = parts[0];
                String rel = parts[1];
                // 内置存储
                if ("primary".equals(vol) || "home".equals(vol)) {
                    return "/storage/emulated/0/" + rel;
                }
                // v0.4.19：SD 卡等外部卷（如 3D65-1710:Levilauncher）——
                // 此前只认 primary，选 SD 卡目录解析失败返回 null
                // （"选择后应用了还是不生效"根因）
                if (vol.matches("[A-Za-z0-9_.-]+")) {
                    return "/storage/" + vol + "/" + rel;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void refreshCustomStoragePathUi() {
        if (customStoragePathCurrent == null) return;

        String custom = LauncherStorage.getCustomStoragePath(this);
        String effective = (custom != null) ? custom : LauncherStorage.getTargetAppRootDisplayPath(this);
        customStoragePathCurrent.setText(getString(R.string.custom_storage_path_current, effective));
    }

    private void applyCustomStoragePath() {
        applyCustomStoragePath(false);
    }

    /** v0.4.14：skipConfirm=选择器回调直用（用户已在 SAF 里选了，
     *  再弹确认框导致"选择后无反应"——确认框在 SAF 返回时序里
     *  弹不出来）；手动点应用按钮保留确认弹窗。 */
    private void applyCustomStoragePath(boolean skipConfirm) {
        if (customStoragePathInput == null) return;
        String path = customStoragePathInput.getText().toString().trim();
        if (path.isEmpty()) {
            LauncherStorage.clearCustomStoragePath(this);
            // v0.4.23：回默认——hint 显示默认路径，按钮回「选择」
            customStoragePathInput.setHint(LauncherStorage.getTargetAppRootDisplayPath(this));
            refreshCustomStoragePathUi();
            refreshVersionsAfterStorageChange();
            updateSelectApplyButtonState();
            return;
        }
        Runnable doApply = () -> {
            LauncherStorage.setCustomStoragePath(this, path);
            // v0.4.23：应用后清空文本，灰色 hint 显示新生效路径——
            // 按钮回「选择」
            customStoragePathInput.setText("");
            customStoragePathInput.setHint(path);
            refreshCustomStoragePathUi();
            refreshVersionsAfterStorageChange();
            updateSelectApplyButtonState();
        };
        if (skipConfirm) {
            doApply.run();
            return;
        }
        // 自定义路径确认弹窗：提示可能导致导入大型包变慢
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.custom_path_confirm_title))
                .setMessage(getString(R.string.custom_path_confirm_message))
                .setPositiveButton(getString(R.string.apply), v -> doApply.run())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void resetCustomStoragePath() {
        LauncherStorage.clearCustomStoragePath(this);
        // v0.4.23：回默认——hint 显示默认路径，按钮回「选择」
        if (customStoragePathInput != null) {
            customStoragePathInput.setText("");
            customStoragePathInput.setHint(LauncherStorage.getTargetAppRootDisplayPath(this));
        }
        refreshCustomStoragePathUi();
        refreshVersionsAfterStorageChange();
    }

    private void refreshVersionsAfterStorageChange() {
        // 存储路径切换后立即重新扫描实例，避免旧路径实例残留
        try {
            VersionManager.get(this).loadAllVersions();
        } catch (Exception ignored) {}
    }

    private void refreshSharedStorageLayoutUi() {
        if (sharedStorageLayoutStatus == null) return;

        int modeRes = LauncherStorage.isUsingNewSharedStorage(this)
                ? R.string.shared_storage_layout_mode_new
                : R.string.shared_storage_layout_mode_legacy;
        sharedStorageLayoutStatus.setText(getString(
                R.string.shared_storage_layout_status,
                getString(modeRes),
                LauncherStorage.getSharedInternalGameDataDisplayPath(this),
                LauncherStorage.getSharedExternalGameDataDisplayPath(this)
        ));
    }

    private void setupAboutSection() {
        findViewById(R.id.help_chunkbase).setOnClickListener(v ->
                openUrl("https://www.chunkbase.com/", getString(R.string.help_chunkbase)));
        findViewById(R.id.help_mcwiki).setOnClickListener(v ->
                openUrl("https://zh.minecraft.wiki/", getString(R.string.help_mcwiki)));
        findViewById(R.id.help_bilibili).setOnClickListener(v ->
                openUrl("https://www.bilibili.com/", getString(R.string.help_bilibili)));
        // v0.4.24：日志上报（收集打包 → 系统邮件预填发送）
        // v0.4.25：用户提供的云上传贴图
        com.google.android.material.button.MaterialButton btnReportLogs =
                findViewById(R.id.btn_report_logs);
        if (btnReportLogs != null) {
            applyReportButtonAccent();
            btnReportLogs.setOnClickListener(v -> org.levimc.launcher.util.LogReporter.report(this));
        }
        // v0.0.24：minebbs/littleskin 死 UI 清理（此前 setVisibility(GONE) 残留）
    }

    /** v0.4.44：上报按钮描边/文字/图标染色（随强调色即时刷新——此前只在
     *  启动时染一次，改色后要重启才生效）。 */
    private void applyReportButtonAccent() {
        com.google.android.material.button.MaterialButton btnReportLogs =
                findViewById(R.id.btn_report_logs);
        if (btnReportLogs == null) {
            return;
        }
        int accent = new PersonalizationManager(this).getAccentColor();
        if (accent == 0) {
            return;
        }
        btnReportLogs.setTextColor(accent);
        btnReportLogs.setStrokeColor(android.content.res.ColorStateList.valueOf(accent));
        btnReportLogs.setIconTint(android.content.res.ColorStateList.valueOf(accent));
    }

    private void openUrl(String url, String title) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_URL, url);
        intent.putExtra(WebViewActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_settings);
        findViewById(R.id.nav_tab_settings).setOnClickListener(v -> {});
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull android.os.Bundle outState) {
        // v0.4.0：recreate（界面缩放等）时保存停留的 tab
        outState.putInt("selected_tab", selectedTabIndex);
        super.onSaveInstanceState(outState);
    }


    /** v0.4.192：调试页——M3 风格 Dummy UI 展示（点击无反馈，供用户
     *  查看样式后点名调整；每个样式带名字标签）。旧 UI 对照放底部。 */
    private void setupDebugSection() {
        LinearLayout host = findViewById(R.id.debug_container);
        if (host == null) return;
        float d = getResources().getDisplayMetrics().density;
        int accent = personalizationManager.getAccentColor();
        int surface = getColor(R.color.surface);
        int onSurface = getColor(R.color.on_surface);
        int textSub = getColor(R.color.text_secondary);

        java.util.function.Function<String, TextView> label = name -> {
            TextView t = new TextView(this);
            t.setText(name);
            t.setTextColor(textSub);
            t.setTextSize(12f);
            t.setPadding(0, (int) (18 * d), 0, (int) (6 * d));
            return t;
        };
        java.util.function.BiFunction<Integer, Integer, LinearLayout> card = (hpx, padpx) -> {
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            int ph = (int) (padpx * d);
            v.setPadding(ph, ph, ph, ph);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(surface);
            gd.setCornerRadius(16 * d);
            v.setBackground(gd);
            v.setElevation(2 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, hpx > 0 ? (int) (hpx * d) : LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (10 * d);
            v.setLayoutParams(lp);
            return v;
        };

        // 1. M3 分段按钮 SegmentedButton
        host.addView(label.apply("1. M3 分段按钮 SegmentedButton"));
        LinearLayout segCard = card.apply(56, 4);
        segCard.setGravity(Gravity.CENTER);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        android.graphics.drawable.GradientDrawable segBg = new android.graphics.drawable.GradientDrawable();
        segBg.setColor(getColor(R.color.surface_variant));
        segBg.setCornerRadius(22 * d);
        seg.setBackground(segBg);
        seg.setPadding((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
        String[] segItems = {"地图", "列表", "标签"};
        for (int i = 0; i < segItems.length; i++) {
            TextView item = new TextView(this);
            item.setText(segItems[i]);
            item.setTextSize(13f);
            item.setGravity(Gravity.CENTER);
            item.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
            if (i == 1) {
                android.graphics.drawable.GradientDrawable sel = new android.graphics.drawable.GradientDrawable();
                sel.setColor(surface);
                sel.setCornerRadius(18 * d);
                item.setBackground(sel);
                item.setElevation(2 * d);
                item.setTextColor(accent != 0 ? accent : onSurface);
                item.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                item.setTextColor(textSub);
            }
            seg.addView(item, new LinearLayout.LayoutParams(
                    0, (int) (36 * d), 1f));
        }
        segCard.addView(seg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * d)));
        host.addView(segCard);

        // 2. M3 填充卡片 FilledCard
        host.addView(label.apply("2. M3 填充卡片 FilledCard"));
        LinearLayout fillCard = card.apply(0, 16);
        android.graphics.drawable.GradientDrawable fcBg = new android.graphics.drawable.GradientDrawable();
        fcBg.setColor(getColor(R.color.surface_variant));
        fcBg.setCornerRadius(12 * d);
        fillCard.setBackground(fcBg);
        TextView fcTitle = new TextView(this);
        fcTitle.setText("卡片标题");
        fcTitle.setTextColor(onSurface);
        fcTitle.setTextSize(15f);
        fcTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fillCard.addView(fcTitle);
        TextView fcBody = new TextView(this);
        fcBody.setText("M3 填充卡片使用 surfaceVariant 底色，圆角 12dp，承载标题与正文内容。");
        fcBody.setTextColor(textSub);
        fcBody.setTextSize(13f);
        fcBody.setPadding(0, (int) (6 * d), 0, (int) (10 * d));
        fillCard.addView(fcBody);
        TextView fcAction = new TextView(this);
        fcAction.setText("操作按钮");
        fcAction.setTextColor(accent != 0 ? accent : onSurface);
        fcAction.setTextSize(13f);
        fcAction.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fillCard.addView(fcAction);
        host.addView(fillCard);

        // 3. M3 顶部栏 TopAppBar
        host.addView(label.apply("3. M3 顶部栏 TopAppBar"));
        LinearLayout topCard = card.apply(48, 0);
        topCard.setPadding(0, 0, 0, 0);
        topCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView nav = new TextView(this);
        nav.setText("☰");
        nav.setTextColor(onSurface);
        nav.setTextSize(18f);
        nav.setPadding((int) (16 * d), 0, (int) (12 * d), 0);
        TextView tbTitle = new TextView(this);
        tbTitle.setText("标题");
        tbTitle.setTextColor(onSurface);
        tbTitle.setTextSize(16f);
        tbTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextView tbAct = new TextView(this);
        tbAct.setText("🔍 ⋮");
        tbAct.setTextColor(textSub);
        tbAct.setTextSize(14f);
        tbAct.setPadding((int) (12 * d), 0, (int) (16 * d), 0);
        topCard.addView(nav);
        topCard.addView(tbTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topCard.addView(tbAct);
        host.addView(topCard);

        // 4. M3 开关行 SwitchRow
        host.addView(label.apply("4. M3 开关行 SwitchRow"));
        LinearLayout swCard = card.apply(52, 14);
        swCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout swText = new LinearLayout(this);
        swText.setOrientation(LinearLayout.VERTICAL);
        TextView swTitle = new TextView(this);
        swTitle.setText("图层显示");
        swTitle.setTextColor(onSurface);
        swTitle.setTextSize(14f);
        swText.addView(swTitle);
        TextView swSub = new TextView(this);
        swSub.setText("开关行的副标题说明");
        swSub.setTextColor(textSub);
        swSub.setTextSize(12f);
        swText.addView(swSub);
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(true);
        sw.setShowText(false);
        if (accent != 0) {
            sw.setTrackTintList(android.content.res.ColorStateList.valueOf(accent));
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        }
        swCard.addView(swText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swCard.addView(sw);
        host.addView(swCard);

        // 5. M3 文本字段 OutlinedTextField
        host.addView(label.apply("5. M3 文本字段 OutlinedTextField"));
        LinearLayout tfCard = card.apply(56, 14);
        EditText tf = new EditText(this);
        tf.setHint("浮动标签风格提示");
        tf.setHintTextColor(textSub);
        tf.setTextColor(onSurface);
        tf.setTextSize(13f);
        tf.setSingleLine(true);
        tf.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        android.graphics.drawable.GradientDrawable tfBg = new android.graphics.drawable.GradientDrawable();
        tfBg.setStroke((int) (1.2 * d), accent != 0 ? accent : textSub);
        tfBg.setCornerRadius(8 * d);
        tfBg.setColor(android.graphics.Color.TRANSPARENT);
        tf.setBackground(tfBg);
        tfCard.addView(tf);
        host.addView(tfCard);

        // 6. M3 列表项 ListItem
        host.addView(label.apply("6. M3 列表项 ListItem"));
        LinearLayout liCard = card.apply(56, 12);
        liCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView liIcon = new TextView(this);
        liIcon.setText("🗺");
        liIcon.setTextSize(18f);
        liIcon.setPadding(0, 0, (int) (12 * d), 0);
        LinearLayout liText = new LinearLayout(this);
        liText.setOrientation(LinearLayout.VERTICAL);
        TextView liTitle = new TextView(this);
        liTitle.setText("列表项标题");
        liTitle.setTextColor(onSurface);
        liTitle.setTextSize(14f);
        liText.addView(liTitle);
        TextView liSub = new TextView(this);
        liSub.setText("三行列表：副标题 + 尾随操作");
        liSub.setTextColor(textSub);
        liSub.setTextSize(12f);
        liText.addView(liSub);
        TextView liTrail = new TextView(this);
        liTrail.setText("›");
        liTrail.setTextColor(textSub);
        liTrail.setTextSize(20f);
        liCard.addView(liIcon);
        liCard.addView(liText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        liCard.addView(liTrail);
        host.addView(liCard);

        // 7. M3 数字徽标 Badge
        host.addView(label.apply("7. M3 数字徽标 Badge"));
        LinearLayout bdCard = card.apply(56, 14);
        bdCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView bdIcon = new TextView(this);
        bdIcon.setText("🔔");
        bdIcon.setTextSize(18f);
        bdIcon.setPadding(0, 0, (int) (16 * d), 0);
        TextView bdBadge = new TextView(this);
        bdBadge.setText("12");
        bdBadge.setTextColor(Color.WHITE);
        bdBadge.setTextSize(11f);
        bdBadge.setGravity(Gravity.CENTER);
        bdBadge.setBackgroundColor(accent != 0 ? accent : 0xFFB3261E);
        bdBadge.getBackground().setTint(accent != 0 ? accent : 0xFFB3261E);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                (int) (18 * d), (int) (18 * d));
        bdBadge.setLayoutParams(blp);
        TextView bdLabel = new TextView(this);
        bdLabel.setText("图标角标（计数徽标）");
        bdLabel.setTextColor(textSub);
        bdLabel.setTextSize(12f);
        bdLabel.setPadding((int) (16 * d), 0, 0, 0);
        bdCard.addView(bdIcon);
        bdCard.addView(bdBadge);
        bdCard.addView(bdLabel);
        host.addView(bdCard);

        // 8. M3 悬浮按钮 FAB
        host.addView(label.apply("8. M3 悬浮按钮 FAB"));
        LinearLayout fabCard = card.apply(64, 14);
        fabCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView fab = new TextView(this);
        fab.setText("+");
        fab.setTextColor(Color.WHITE);
        fab.setTextSize(22f);
        fab.setGravity(Gravity.CENTER);
        fab.setBackgroundColor(accent != 0 ? accent : 0xFF6750A4);
        fab.getBackground().setTint(accent != 0 ? accent : 0xFF6750A4);
        fab.setElevation(6 * d);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                (int) (48 * d), (int) (48 * d));
        fab.setLayoutParams(flp);
        TextView fabExt = new TextView(this);
        fabExt.setText("  扩展 FAB");
        fabExt.setTextColor(accent != 0 ? accent : onSurface);
        fabExt.setTextSize(13f);
        fabExt.setPadding((int) (10 * d), 0, 0, 0);
        fabCard.addView(fab);
        fabCard.addView(fabExt);
        host.addView(fabCard);

        // 9. M3 进度指示 Progress
        host.addView(label.apply("9. M3 进度指示 Progress"));
        LinearLayout pgCard = card.apply(0, 14);
        LinearLayout pgRow = new LinearLayout(this);
        pgRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar circ = new android.widget.ProgressBar(this);
        if (accent != 0) {
            circ.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        }
        circ.setPadding(0, 0, (int) (16 * d), 0);
        pgRow.addView(circ);
        TextView pgLabel = new TextView(this);
        pgLabel.setText("线性进度（下方）+ 圆形进度");
        pgLabel.setTextColor(textSub);
        pgLabel.setTextSize(12f);
        pgRow.addView(pgLabel);
        pgCard.addView(pgRow);
        android.widget.ProgressBar lin = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        lin.setProgress(60);
        if (accent != 0) {
            lin.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        }
        lin.setPadding(0, (int) (8 * d), 0, 0);
        pgCard.addView(lin);
        host.addView(pgCard);

        // 10. M3 芯片组 Chips
        host.addView(label.apply("10. M3 芯片组 Chips"));
        LinearLayout chipCard = card.apply(48, 12);
        chipCard.setGravity(Gravity.CENTER_VERTICAL);
        String[] chipItems = {"全部", "世界", "资源包", "行为包"};
        for (int i = 0; i < chipItems.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(chipItems[i]);
            chip.setTextSize(12f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
            android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
            if (i == 0) {
                cbg.setColor(accent != 0 ? accent : onSurface);
                chip.setTextColor(Color.WHITE);
            } else {
                cbg.setStroke((int) (1 * d), textSub);
                cbg.setColor(android.graphics.Color.TRANSPARENT);
                chip.setTextColor(onSurface);
            }
            cbg.setCornerRadius(14 * d);
            chip.setBackground(cbg);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (int) (28 * d));
            clp.setMarginEnd((int) (6 * d));
            chip.setLayoutParams(clp);
            chipCard.addView(chip);
        }
        host.addView(chipCard);

        // ---- 旧 UI 对照 ----
        host.addView(label.apply("旧版 · 实底按钮（导入按钮样式）"));
        LinearLayout oldCard1 = card.apply(0, 14);
        android.widget.Button oldBtn = new android.widget.Button(this);
        oldBtn.setText("旧版实底按钮");
        oldBtn.setAllCaps(false);
        oldBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.primary)));
        oldBtn.setTextColor(getColor(R.color.on_primary));
        oldCard1.addView(oldBtn);
        host.addView(oldCard1);

        host.addView(label.apply("旧版 · 开关行（带图标）"));
        LinearLayout oldCard2 = card.apply(52, 14);
        oldCard2.setGravity(Gravity.CENTER_VERTICAL);
        TextView oldSwTitle = new TextView(this);
        oldSwTitle.setText("旧版开关行标题");
        oldSwTitle.setTextColor(onSurface);
        oldSwTitle.setTextSize(14f);
        android.widget.Switch oldSw = new android.widget.Switch(this);
        oldSw.setChecked(true);
        oldCard2.addView(oldSwTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        oldCard2.addView(oldSw);
        host.addView(oldCard2);

        host.addView(label.apply("旧版 · 圆角卡片（导入弹窗卡）"));
        LinearLayout oldCard3 = card.apply(56, 14);
        oldCard3.setGravity(Gravity.CENTER);
        TextView oldCardText = new TextView(this);
        oldCardText.setText("旧版卡片内容");
        oldCardText.setTextColor(onSurface);
        oldCardText.setTextSize(13f);
        oldCard3.addView(oldCardText);
        host.addView(oldCard3);
    }

}
