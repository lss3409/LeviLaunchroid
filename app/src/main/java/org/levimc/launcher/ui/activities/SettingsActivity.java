package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
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
    private int updateButtonTapCount = 0;
    private long lastUpdateButtonTapTime = 0;
    private static final int EASTER_EGG_TAP_COUNT = 3;
    private static final long TAP_TIMEOUT_MS = 2000;

    private TextView tabBasic;
    private TextView tabPersonalize;
    private TextView tabMigration;
    private TextView tabAbout;

    private View sectionBasic;
    private View sectionPersonalize;
    private View sectionMigration;
    private View sectionAbout;

    private static final String KEY_SELECTED_TAB = "selected_tab_index";
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

        // v0.0.6：不再恢复上次停留的 tab——每次打开设置页默认「基础设置」
        // （含语言切换），避免用户切到别的 tab 后找不到语言入口（用户反馈）
        selectedTabIndex = 0;

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
                    if (uri != null) {
                        String path = getPathFromTreeUri(uri);
                        if (path != null && customStoragePathInput != null) {
                            customStoragePathInput.setText(path);
                            updateSelectApplyButtonState();
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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex);
    }

    /** v711：检查更新卡片（关于 tab）——版本显示 + 手动检查按钮。 */
    private void setupUpdateCheck() {
        try {
            TextView verText = findViewById(R.id.check_update_version);
            if (verText != null) {
                android.content.pm.PackageInfo pi = getPackageManager()
                        .getPackageInfo(getPackageName(), 0);
                verText.setText("当前版本 " + (pi != null ? pi.versionName : "?"));
            }
            View btn = findViewById(R.id.check_update_button);
            if (btn != null) {
                org.levimc.launcher.util.AccentStyler.stylePrimary(this, btn);
                btn.setOnClickListener(v -> {
                    Toast.makeText(this, R.string.check_update_checking,
                            Toast.LENGTH_SHORT).show();
                    org.levimc.launcher.core.updates.UpdateChecker.checkAsync(
                            this, true, (status, u) -> {
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

        sectionBasic = findViewById(R.id.section_basic);
        sectionPersonalize = findViewById(R.id.section_personalize);
        sectionMigration = findViewById(R.id.section_migration);
        sectionAbout = findViewById(R.id.section_about);

        tabBasic.setOnClickListener(v -> { selectedTabIndex = 0; selectTab(tabBasic); });
        tabPersonalize.setOnClickListener(v -> { selectedTabIndex = 1; selectTab(tabPersonalize); });
        tabAbout.setOnClickListener(v -> { selectedTabIndex = 2; selectTab(tabAbout); });
        tabMigration.setOnClickListener(v -> { selectedTabIndex = 3; selectTab(tabMigration); });
    }

    private void selectTab(TextView selectedTab) {
        TextView[] tabs = getSettingsTabs();
        View[] sections = {sectionBasic, sectionPersonalize, sectionAbout, sectionMigration};

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
        return new TextView[]{tabBasic, tabPersonalize, tabAbout, tabMigration};
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

        int[] helpIconIds = {R.id.help_chunkbase_icon, R.id.help_mcwiki_icon, R.id.help_bilibili_icon, R.id.help_minebbs_icon, R.id.help_littleskin_icon};
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
            intent.setType("image/*");
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

    private void setupCustomStoragePathSection() {
        customStoragePathCurrent = findViewById(R.id.custom_storage_path_current);
        customStoragePathInput = findViewById(R.id.input_custom_storage_path);
        Button btnSelectApply = findViewById(R.id.btn_apply_custom_storage_path);
        Button btnReset = findViewById(R.id.btn_reset_custom_storage_path);

        if (customStoragePathInput != null) {
            customStoragePathInput.setHint(LauncherStorage.getTargetAppRootDisplayPath(this));
            String custom = LauncherStorage.getCustomStoragePath(this);
            if (custom != null) {
                customStoragePathInput.setText(custom);
            }
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
            btnSelectApply.setOnClickListener(v -> {
                if (getString(R.string.custom_storage_path_select).contentEquals(btnSelectApply.getText())) {
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
        boolean hasText = customStoragePathInput != null
                && !customStoragePathInput.getText().toString().trim().isEmpty();
        btn.setText(hasText ? R.string.custom_storage_path_apply : R.string.custom_storage_path_select);
    }

    private void openFolderPicker() {
        if (folderPickerLauncher != null) {
            folderPickerLauncher.launch(null);
        }
    }

    private String getPathFromTreeUri(Uri uri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
            if (docId == null) return null;
            String[] parts = docId.split(":");
            if (parts.length >= 2 && ("primary".equals(parts[0]) || "home".equals(parts[0]))) {
                return "/storage/emulated/0/" + parts[1];
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
        if (customStoragePathInput == null) return;
        String path = customStoragePathInput.getText().toString().trim();
        if (path.isEmpty()) {
            LauncherStorage.clearCustomStoragePath(this);
            refreshCustomStoragePathUi();
            refreshVersionsAfterStorageChange();
            return;
        }
        // 自定义路径确认弹窗：提示可能导致导入大型包变慢
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.custom_path_confirm_title))
                .setMessage(getString(R.string.custom_path_confirm_message))
                .setPositiveButton(getString(R.string.apply), v -> {
                    LauncherStorage.setCustomStoragePath(this, path);
                    refreshCustomStoragePathUi();
                    refreshVersionsAfterStorageChange();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void resetCustomStoragePath() {
        LauncherStorage.clearCustomStoragePath(this);
        if (customStoragePathInput != null) {
            customStoragePathInput.setText("");
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
        findViewById(R.id.help_minebbs).setVisibility(View.GONE);
        findViewById(R.id.help_littleskin).setVisibility(View.GONE);
    }

    private void openUrl(String url, String title) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_URL, url);
        intent.putExtra(WebViewActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private void handleUpdateButtonClick() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdateButtonTapTime > TAP_TIMEOUT_MS) {
            updateButtonTapCount = 0;
        }

        updateButtonTapCount++;
        lastUpdateButtonTapTime = currentTime;

        if (updateButtonTapCount >= EASTER_EGG_TAP_COUNT) {
            updateButtonTapCount = 0;
            triggerEasterEgg();
        }
    }

    private void triggerEasterEgg() {
        try {
            String encoded = "aHR0cHM6Ly95b3V0dS5iZS9GdHV0TEE2M0NwOD9zaT1CSExEWHZLOTZPZ1A0NUI4";
            String url = new String(Base64.decode(encoded, Base64.DEFAULT));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_settings);
        findViewById(R.id.nav_tab_settings).setOnClickListener(v -> {});
    }

}