package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.config.ModConfigManager;
import org.levimc.launcher.ui.adapter.ModConfigAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModConfigActivity extends BaseActivity {
    public static final String EXTRA_MOD_NAME = "mod_name";
    public static final String EXTRA_MOD_ROOT = "mod_root";

    private final ModConfigManager configManager = new ModConfigManager();
    private final List<ModConfigManager.ConfigFile> configFiles = new ArrayList<>();
    private TextView titleText;
    private Spinner configSpinner;
    private RecyclerView recyclerView;
    private ProgressBar loadingProgress;
    private TextView emptyText;
    private ModConfigAdapter adapter;
    private ModConfigManager.ConfigFile currentFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mod_config);

        View root = findViewById(R.id.mod_config_root);
        if (root != null) {
            DynamicAnim.applyPressScaleRecursively(root);
        }

        titleText = findViewById(R.id.mod_config_title);
        configSpinner = findViewById(R.id.mod_config_file_spinner);
        recyclerView = findViewById(R.id.mod_config_recycler);
        loadingProgress = findViewById(R.id.mod_config_loading);
        emptyText = findViewById(R.id.mod_config_empty);
        Button saveButton = findViewById(R.id.mod_config_save_button);
        PersonalizationManager pm = new PersonalizationManager(this);
        pm.applySolidAccentText(titleText, pm.getAccentColor());
        pm.applyAccentToView(saveButton, this);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        saveButton.setOnClickListener(v -> saveCurrentConfig());

        String modName = getIntent().getStringExtra(EXTRA_MOD_NAME);
        String modRootPath = getIntent().getStringExtra(EXTRA_MOD_ROOT);
        titleText.setText(getString(R.string.mod_config_title_format, modName == null ? "" : modName));

        if (modRootPath == null || modRootPath.trim().isEmpty()) {
            showEmpty(getString(R.string.mod_config_not_found));
            return;
        }

        loadConfigFiles(new File(modRootPath));
    }

    private void loadConfigFiles(File modRoot) {
        loadingProgress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<ModConfigManager.ConfigFile> files = configManager.scanConfigFiles(modRoot);
            runOnUiThread(() -> {
                loadingProgress.setVisibility(View.GONE);
                configFiles.clear();
                configFiles.addAll(files);
                if (configFiles.isEmpty()) {
                    showEmpty(getString(R.string.mod_config_not_found));
                    return;
                }
                setupSpinner();
                loadConfig(configFiles.get(0));
            });
        }).start();
    }

    private void setupSpinner() {
        List<String> names = new ArrayList<>();
        for (ModConfigManager.ConfigFile file : configFiles) {
            names.add(file.getDisplayName());
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, names);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        configSpinner.setAdapter(spinnerAdapter);
        configSpinner.setVisibility(configFiles.size() > 1 ? View.VISIBLE : View.GONE);
        configSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < configFiles.size() && configFiles.get(position) != currentFile) {
                    loadConfig(configFiles.get(position));
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void loadConfig(ModConfigManager.ConfigFile file) {
        currentFile = file;
        loadingProgress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                ModConfigManager.LoadedConfig loadedConfig = configManager.load(file);
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    adapter = new ModConfigAdapter(loadedConfig.getValue(), loadedConfig.getSchema());
                    recyclerView.setAdapter(adapter);
                    recyclerView.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    showEmpty(getString(R.string.mod_config_load_failed, e.getMessage()));
                });
            }
        }).start();
    }

    private void saveCurrentConfig() {
        if (adapter == null || currentFile == null) {
            return;
        }

        String validationError = adapter.validate();
        if (validationError != null) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.mod_config_validation_failed))
                    .setMessage(validationError)
                    .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                    .show();
            return;
        }

        loadingProgress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                configManager.save(currentFile, adapter.getRoot());
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.mod_config_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    new CustomAlertDialog(this)
                            .setTitleText(getString(R.string.mod_config_save_failed))
                            .setMessage(e.getMessage())
                            .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                            .show();
                });
            }
        }).start();
    }

    private void showEmpty(String message) {
        recyclerView.setVisibility(View.GONE);
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
    }
}
