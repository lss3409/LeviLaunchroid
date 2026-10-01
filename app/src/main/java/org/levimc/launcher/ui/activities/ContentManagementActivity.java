package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentImporter;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityContentManagementBinding;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
public class ContentManagementActivity extends BaseActivity {
    
    private static final String PREFS_NAME = "content_management";
    private static final String KEY_STORAGE_TYPE = "storage_type";
    
    private ActivityContentManagementBinding binding;
    private ContentManager contentManager;
    private ContentImporter contentImporter;
    private VersionManager versionManager;
    private FeatureSettings.StorageType currentStorageType = FeatureSettings.StorageType.INTERNAL;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> importLauncher;
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
        binding = ActivityContentManagementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DynamicAnim.applyPressScaleRecursively(binding.getRoot());

        setupActivityResultLaunchers();
        initializeManagers();
        setupUI();
        loadCurrentVersion();
    }

    private void setupActivityResultLaunchers() {
        importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    List<Uri> uris = new ArrayList<>();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            uris.add(data.getClipData().getItemAt(i).getUri());
                        }
                    } else if (data.getData() != null) {
                        uris.add(data.getData());
                    }
                    if (!uris.isEmpty()) {
                        handleImport(uris);
                    }
                }
            }
        );
    }

    private void initializeManagers() {
        contentManager = ContentManager.getInstance(this);
        contentImporter = new ContentImporter(this);
        versionManager = VersionManager.get(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadStorageType();
    }

    private void loadStorageType() {
        // 锁死使用外部存储（external/games/com.mojang），配合游戏内文件存储位置=外部
        currentStorageType = FeatureSettings.StorageType.EXTERNAL;
    }

    private void saveStorageType() {
        prefs.edit().putString(KEY_STORAGE_TYPE, currentStorageType.name()).apply();
    }

    private void setupUI() {
        binding.importContentButton.setOnClickListener(v -> {
            // v602：导入按钮改为全局扫描可视化选择（存档/资源包/行为包/
            // 结构，zip 与已解压文件夹都能识别），兜底走原文件管理器
            org.levimc.launcher.ui.dialogs.ImportPickerDialog.show(this,
                    new org.levimc.launcher.ui.dialogs.ImportPickerDialog.Listener() {
                        @Override
                        public void onPick(java.io.File file) {
                            handleImport(java.util.Collections.singletonList(
                                    android.net.Uri.fromFile(file)));
                        }

                        @Override
                        public void onPickFromFiles() {
                            startImport();
                        }
                    });
        });
        binding.versionText.setOnClickListener(v -> showVersionPicker());
        binding.viewSharedFolderButton.setOnClickListener(v -> showSharedFolderPicker());

        setupCategoryButtons();
        setupContentCountObservers();
    }

    private void showVersionPicker() {
        List<GameVersion> allVersions = new ArrayList<>();
        List<GameVersion> installed = versionManager.getInstalledVersions();
        List<GameVersion> custom = versionManager.getCustomVersions();
        if (installed != null) allVersions.addAll(installed);
        if (custom != null) allVersions.addAll(custom);
        if (allVersions.isEmpty()) return;

        GameVersion selected = versionManager.getSelectedVersion();
        RadioGroup[] groupRef = new RadioGroup[1];
        View content = createVersionSelectionView(allVersions, selected, groupRef);

        new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.select_version))
                .setCustomView(content)
                .setPositiveButton(getString(R.string.apply), v -> {
                    RadioGroup group = groupRef[0];
                    int checkedId = group != null ? group.getCheckedRadioButtonId() : -1;
                    if (checkedId >= 0 && checkedId < allVersions.size()) {
                        versionManager.selectVersion(allVersions.get(checkedId));
                        loadCurrentVersion();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private View createVersionSelectionView(List<GameVersion> versions, GameVersion current, RadioGroup[] groupRef) {
        float density = getResources().getDisplayMetrics().density;
        int rowVerticalPadding = (int) (10 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int) (2 * density), 0, (int) (2 * density), 0);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);

        int selectedIndex = -1;
        for (int i = 0; i < versions.size(); i++) {
            GameVersion v = versions.get(i);
            RadioButton rb = new RadioButton(this);
            rb.setId(i);
            rb.setText(v.displayName);
            rb.setTextColor(getResources().getColor(R.color.on_surface, getTheme()));
            rb.setTextSize(13);
            rb.setSingleLine(false);
            rb.setPadding(0, rowVerticalPadding, 0, rowVerticalPadding);
            applyRadioTheme(rb);
            group.addView(rb);
            if (current != null && current.directoryName != null && current.directoryName.equals(v.directoryName)) {
                selectedIndex = i;
            }
        }
        if (selectedIndex >= 0) {
            group.check(selectedIndex);
        }

        scrollView.addView(group);
        container.addView(scrollView);

        int rowHeight = (int) (48 * density);
        int maxHeight = (int) (260 * density);
        int minHeight = (int) (96 * density);
        int listHeight = Math.min(maxHeight, Math.max(minHeight, versions.size() * rowHeight));
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeight));

        groupRef[0] = group;
        return container;
    }

    private void applyRadioTheme(RadioButton radioButton) {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent == 0) {
            accent = ContextCompat.getColor(this, R.color.primary);
        }
        int unchecked = getResources().getColor(R.color.text_secondary, getTheme());
        ColorStateList tint = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{accent, unchecked}
        );
        radioButton.setButtonTintList(tint);
    }

    private static String getInstanceDisplayName(GameVersion version) {
        if (version == null) return "";
        String displayName = stripVersionSuffix(version.displayName, version.versionCode);
        if (!TextUtils.isEmpty(displayName)) return displayName;
        if (!TextUtils.isEmpty(version.directoryName)) return version.directoryName;
        return !TextUtils.isEmpty(version.versionCode) ? version.versionCode : "";
    }

    private static String getInstanceVersionText(GameVersion version) {
        if (version == null) return "";
        if (!TextUtils.isEmpty(version.versionCode)) return version.versionCode;
        return !TextUtils.isEmpty(version.directoryName) ? version.directoryName : "";
    }

    private static String stripVersionSuffix(String displayName, String versionCode) {
        if (TextUtils.isEmpty(displayName)) return "";
        String trimmedName = displayName.trim();
        if (TextUtils.isEmpty(versionCode)) return trimmedName;
        String suffix = " (" + versionCode + ")";
        if (trimmedName.endsWith(suffix)) {
            return trimmedName.substring(0, trimmedName.length() - suffix.length()).trim();
        }
        return trimmedName;
    }

    private static class InstancePopupAdapter extends RecyclerView.Adapter<InstancePopupAdapter.VH> {
        private final List<GameVersion> allVersions;
        private List<GameVersion> filteredVersions;
        private final GameVersion selectedVersion;
        private OnItemClickListener listener;

        interface OnItemClickListener {
            void onClick(GameVersion version);
        }

        void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

        InstancePopupAdapter(List<GameVersion> versions, GameVersion selected) {
            this.allVersions = versions;
            this.filteredVersions = new ArrayList<>(versions);
            this.selectedVersion = selected;
        }

        void filter(String query) {
            if (query == null || query.isEmpty()) {
                filteredVersions = new ArrayList<>(allVersions);
            } else {
                String q = query.toLowerCase();
                filteredVersions = new ArrayList<>();
                for (GameVersion v : allVersions) {
                    String name = getInstanceDisplayName(v).toLowerCase();
                    String code = v.versionCode != null ? v.versionCode.toLowerCase() : "";
                    String dir = v.directoryName != null ? v.directoryName.toLowerCase() : "";
                    if (name.contains(q) || code.contains(q) || dir.contains(q)) {
                        filteredVersions.add(v);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_instance_popup, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GameVersion v = filteredVersions.get(position);
            boolean isSelected = selectedVersion != null
                    && selectedVersion.directoryName != null
                    && selectedVersion.directoryName.equals(v.directoryName);

            holder.name.setText(getInstanceDisplayName(v));
            String versionText = getInstanceVersionText(v);
            holder.version.setText(versionText);
            holder.version.setVisibility(TextUtils.isEmpty(versionText) ? View.GONE : View.VISIBLE);
            holder.itemView.setActivated(isSelected);
            holder.check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            holder.tag.setVisibility(View.GONE);
            applyInstanceSelectionStyle(holder, isSelected);

            holder.itemView.setOnClickListener(_v -> {
                if (listener != null) listener.onClick(v);
            });
        }

        private static void applyInstanceSelectionStyle(@NonNull VH holder, boolean isSelected) {
            android.content.Context context = holder.itemView.getContext();
            PersonalizationManager pm = new PersonalizationManager(context);
            int accent = pm.getAccentColor();
            if (accent == 0) {
                accent = ContextCompat.getColor(context, R.color.primary);
            }

            float density = context.getResources().getDisplayMetrics().density;
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(10 * density);
            if (isSelected) {
                background.setColor(Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent)));
                background.setStroke(Math.max(1, (int) (1 * density)), accent);
            } else {
                background.setColor(Color.TRANSPARENT);
                background.setStroke(0, Color.TRANSPARENT);
            }
            holder.itemView.setBackground(background);
            holder.check.setImageTintList(ColorStateList.valueOf(accent));
        }

        @Override public int getItemCount() { return filteredVersions.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView name, version, tag;
            ImageView check;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.instance_name);
                version = v.findViewById(R.id.instance_version);
                tag = v.findViewById(R.id.instance_tag);
                check = v.findViewById(R.id.instance_check);
            }
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/vnd.android.package-archive"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        importLauncher.launch(intent);
    }

    private void handleImport(List<Uri> uris) {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) {
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }

        File worldsDir = getWorldsDirectory();
        File resourcePacksDir = getPackDirectory("resource_packs");
        File behaviorPacksDir = getPackDirectory("behavior_packs");
        File skinPacksDir = getPackDirectory("skin_packs");
        File structuresDir = new File(org.levimc.launcher.util.LauncherStorage.getSharedGameDataDir(this, true), "structures");

        showProgressDialog(getString(R.string.importing_content));
        if (progressDialog != null) {
            progressDialog.setOnHideListener(() -> hideProgressDialog());
        }

        contentImporter.importContent(uris, resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir, structuresDir,
            currentVersion.versionCode != null && !currentVersion.versionCode.isEmpty()
                    ? currentVersion.versionCode : currentVersion.displayName,
            new ContentImporter.ImportCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        hideProgressDialog();
                        Toast.makeText(ContentManagementActivity.this, message, Toast.LENGTH_SHORT).show();
                        contentManager.refreshContent();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        hideProgressDialog();
                        Toast.makeText(ContentManagementActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onProgress(int current, int total, String fileName) {
                    runOnUiThread(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.setDetail(getString(R.string.importing_file_detail, current, total, fileName));
                        }
                    });
                }

                @Override
                public void onVersionWarnings(List<String> warnings) {
                    runOnUiThread(() -> ContentImporter.showVersionWarningsAndNotify(
                            ContentManagementActivity.this, warnings));
                }
            });
    }

    private File getPackDirectory(String packType) {
        File gameDataDir = getGameDataDirForType(currentStorageType);
        return gameDataDir == null ? null : new File(gameDataDir, packType);
    }

    private void setupCategoryButtons() {
        binding.worldsButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_WORLDS));
        binding.skinPacksButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_SKIN_PACKS));
        binding.resourcePacksButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_RESOURCE_PACKS));
        binding.behaviorPacksButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_BEHAVIOR_PACKS));
        binding.screenshotsButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_SCREENSHOTS));
        binding.serversButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_SERVERS));
    }

    private void setupContentCountObservers() {
        contentManager.getWorldsLiveData().observe(this, list ->
                setCount(binding.worldsCount, R.string.worlds_category, list == null ? 0 : list.size()));
        contentManager.getSkinPacksLiveData().observe(this, list ->
                setCount(binding.skinPacksCount, R.string.skin_packs_category, list == null ? 0 : list.size()));
        contentManager.getScreenshotsLiveData().observe(this, list ->
                setCount(binding.screenshotsCount, R.string.screenshots_category, list == null ? 0 : list.size()));
        contentManager.getResourcePacksLiveData().observe(this, list ->
                setCount(binding.resourcePacksCount, R.string.resource_packs_category, list == null ? 0 : list.size()));
        contentManager.getBehaviorPacksLiveData().observe(this, list ->
                setCount(binding.behaviorPacksCount, R.string.behavior_packs_category, list == null ? 0 : list.size()));
        contentManager.getServersLiveData().observe(this, list ->
                setCount(binding.serversCount, R.string.servers_category, list == null ? 0 : list.size()));
    }

    private void setCount(TextView textView, int labelRes, int count) {
        textView.setText(getString(labelRes) + " (" + count + ")");
    }

    private void showSharedFolderPicker() {
        startActivity(new Intent(this, SharedFolderActivity.class));
    }

    private void openContentList(int contentType) {
        Intent intent = new Intent(this, ContentListActivity.class);
        intent.putExtra(ContentListActivity.EXTRA_CONTENT_TYPE, contentType);
        intent.putExtra(ContentListActivity.EXTRA_CURRENT_STORAGE_TYPE, currentStorageType.name());
        
        if (contentType == ContentListActivity.TYPE_WORLDS) {
            File worldsDir = getWorldsDirectory();
            if (worldsDir != null) {
                intent.putExtra(ContentListActivity.EXTRA_WORLDS_DIRECTORY, worldsDir.getAbsolutePath());
            }
        }
        
        startActivity(intent);
    }

    private File getWorldsDirectory() {
        File gameDataDir = getGameDataDirForType(currentStorageType);
        return gameDataDir == null ? null : new File(gameDataDir, "minecraftWorlds");
    }

    private void loadCurrentVersion() {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion != null) {
            binding.versionText.setText(currentVersion.displayName);
            updateStorageDirectories();
        } else {
            binding.versionText.setText(getString(R.string.not_found_version));
            Toast.makeText(this, R.string.dialog_title_no_version, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStorageDirectories() {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return;

        File worldsDir;
        File resourcePacksDir;
        File behaviorPacksDir;
        File skinPacksDir;
        File screenshotsDir;
        File minecraftPeDir;

        File gameDataDir = getGameDataDirForType(currentStorageType);
        if (gameDataDir == null) {
                worldsDir = null;
                resourcePacksDir = null;
                behaviorPacksDir = null;
                skinPacksDir = null;
                screenshotsDir = null;
                minecraftPeDir = null;
        } else {
                worldsDir = new File(gameDataDir, "minecraftWorlds");
                resourcePacksDir = new File(gameDataDir, "resource_packs");
                behaviorPacksDir = new File(gameDataDir, "behavior_packs");
                skinPacksDir = new File(gameDataDir, "skin_packs");
                screenshotsDir = new File(gameDataDir, "Screenshots");
                minecraftPeDir = new File(gameDataDir, "minecraftpe");
        }

        contentManager.setStorageDirectories(worldsDir, resourcePacksDir, behaviorPacksDir, skinPacksDir, screenshotsDir, minecraftPeDir);
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

    private void normalizeCurrentStorageType() {
        GameVersion currentVersion = versionManager != null ? versionManager.getSelectedVersion() : null;
        if (currentVersion == null) return;
        currentStorageType = LauncherStorage.normalizeContentStorageType(
                currentStorageType,
                currentVersion.versionIsolation
        );
    }

    private FeatureSettings.StorageType parseStorageType(String value) {
        try {
            return FeatureSettings.StorageType.valueOf(value);
        } catch (Exception ignored) {
            return FeatureSettings.StorageType.INTERNAL;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStorageDirectories();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (contentImporter != null) {
            contentImporter.shutdown();
        }
    }
}
