package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentImporter;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.databinding.ActivitySharedFolderBinding;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SharedFolderActivity extends BaseActivity {

    private ActivitySharedFolderBinding binding;
    private ContentManager contentManager;
    private ContentImporter contentImporter;
    private ActivityResultLauncher<Intent> importLauncher;
    private org.levimc.launcher.ui.dialogs.LoadingDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySharedFolderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DynamicAnim.applyPressScaleRecursively(binding.getRoot());

        contentManager = ContentManager.getInstance(this);
        contentImporter = new ContentImporter(this);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        List<Uri> uris = new ArrayList<>();
                        if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            for (int i = 0; i < count; i++) {
                                uris.add(result.getData().getClipData().getItemAt(i).getUri());
                            }
                        } else if (result.getData().getData() != null) {
                            uris.add(result.getData().getData());
                        }
                        if (!uris.isEmpty()) {
                            handleImport(uris);
                        }
                    }
                }
        );

        configureSharedDirectories();
        setupButtons();
        setupCountObservers();
        binding.importButton.setOnClickListener(v -> startImport());
    }

    private void configureSharedDirectories() {
        File gameDataDir = LauncherStorage.getSharedGameDataDir(this, true);
        contentManager.setStorageDirectories(
                new File(gameDataDir, "minecraftWorlds"),
                new File(gameDataDir, "resource_packs"),
                new File(gameDataDir, "behavior_packs"),
                new File(gameDataDir, "skin_packs"),
                new File(gameDataDir, "Screenshots"),
                new File(gameDataDir, "minecraftpe"));
        contentManager.setStructuresDirectory(new File(gameDataDir, "structures"));
    }

    private void setupButtons() {
        binding.sharedWorldsButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_WORLDS));
        binding.sharedSkinPacksButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_SKIN_PACKS));
        binding.sharedScreenshotsButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_SCREENSHOTS));
        binding.sharedResourcePacksButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_RESOURCE_PACKS));
        binding.sharedBehaviorPacksButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_BEHAVIOR_PACKS));
        binding.sharedServersButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_SERVERS));
        binding.sharedStructuresButton.setOnClickListener(v -> openContent(ContentListActivity.TYPE_STRUCTURES));
    }

    private void openContent(int contentType) {
        Intent intent = new Intent(this, ContentListActivity.class);
        intent.putExtra(ContentListActivity.EXTRA_CONTENT_TYPE, contentType);
        intent.putExtra(ContentListActivity.EXTRA_SHARED_MODE, true);
        startActivity(intent);
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/vnd.android.package-archive"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        importLauncher.launch(intent);
    }

    /** v532：当前选中实例的游戏版本号（导入包版本对比用；无则 null）。 */
    private String currentGameVersion() {
        try {
            org.levimc.launcher.core.versions.GameVersion v =
                    org.levimc.launcher.core.versions.VersionManager.get(this).getSelectedVersion();
            if (v != null && v.versionCode != null && !v.versionCode.isEmpty()) {
                return v.versionCode;
            }
            if (v != null) {
                return v.displayName;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void handleImport(List<Uri> uris) {
        File gameDataDir = LauncherStorage.getSharedGameDataDir(this, true);
        File worldsDir = new File(gameDataDir, "minecraftWorlds");
        File resourcePacksDir = new File(gameDataDir, "resource_packs");
        File behaviorPacksDir = new File(gameDataDir, "behavior_packs");
        File skinPacksDir = new File(gameDataDir, "skin_packs");
        File structuresDir = new File(gameDataDir, "structures");

        if (progressDialog == null) {
            progressDialog = new org.levimc.launcher.ui.dialogs.LoadingDialog(this);
        }
        progressDialog.show();
        progressDialog.setMessage(getString(R.string.importing_content));
        // 隐藏按钮：点击后关闭弹窗，导入在后台静默继续，完成后仍有提示
        progressDialog.setOnHideListener(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        });

        contentImporter.importContent(uris, resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir, structuresDir,
                currentGameVersion(),
                new ContentImporter.ImportCallback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUiThread(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                            Toast.makeText(SharedFolderActivity.this, message, Toast.LENGTH_SHORT).show();
                            contentManager.refreshContent();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                            Toast.makeText(SharedFolderActivity.this, error, Toast.LENGTH_LONG).show();
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
                    public void onVersionWarnings(java.util.List<String> warnings) {
                        runOnUiThread(() -> ContentImporter.showVersionWarningsAndNotify(
                                SharedFolderActivity.this, warnings));
                    }
                });
    }

    private void setupCountObservers() {
        contentManager.getWorldsLiveData().observe(this, list ->
                setCount(binding.sharedWorldsCount, R.string.worlds_category, list == null ? 0 : list.size()));
        contentManager.getSkinPacksLiveData().observe(this, list ->
                setCount(binding.sharedSkinPacksCount, R.string.skin_packs_category, list == null ? 0 : list.size()));
        contentManager.getScreenshotsLiveData().observe(this, list ->
                setCount(binding.sharedScreenshotsCount, R.string.screenshots_category, list == null ? 0 : list.size()));
        contentManager.getResourcePacksLiveData().observe(this, list ->
                setCount(binding.sharedResourcePacksCount, R.string.resource_packs_category, list == null ? 0 : list.size()));
        contentManager.getBehaviorPacksLiveData().observe(this, list ->
                setCount(binding.sharedBehaviorPacksCount, R.string.behavior_packs_category, list == null ? 0 : list.size()));
        contentManager.getServersLiveData().observe(this, list ->
                setCount(binding.sharedServersCount, R.string.servers_category, list == null ? 0 : list.size()));
        contentManager.getStructuresLiveData().observe(this, list ->
                setCount(binding.sharedStructuresCount, R.string.structures_category, list == null ? 0 : list.size()));
    }

    private void setCount(TextView textView, int labelRes, int count) {
        textView.setText(getString(labelRes) + " (" + count + ")");
    }
}
