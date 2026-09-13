package org.levimc.launcher.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.core.content.WorldManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.MinecraftUriHandler;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 快速启动 - 加载世界：选择版本号 → 展示该版本号的世界列表（图标/名称/种子），
 * 点击世界直接以对应版本号启动游戏并加载该世界。
 */
public class WorldPickerActivity extends BaseActivity {

    private VersionManager versionManager;
    private GameVersion selectedVersion;
    private TextView versionButton;
    private LinearLayout worldsContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());

        versionManager = VersionManager.get(this);
        selectedVersion = versionManager.getSelectedVersion();

        setupHeader();
        setupVersionSelector();
        setupWorldsSection();
        loadWorlds();
    }

    private LinearLayout contentRoot;

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

    private int accent() {
        int a = new PersonalizationManager(this).getAccentColor();
        return a != 0 ? a : getColor(R.color.primary);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setupHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        contentRoot.addView(header);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(getColor(R.color.on_surface));
        back.setTextSize(26);
        back.setPadding(0, 0, dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText(R.string.quick_launch_load_world);
        title.setTextColor(accent());
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void setupVersionSelector() {
        TextView label = new TextView(this);
        label.setText(R.string.world_picker_version_label);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(12);
        label.setPadding(0, dp(16), 0, dp(4));
        contentRoot.addView(label);

        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER_VERTICAL);
        selector.setPadding(dp(14), dp(10), dp(14), dp(10));
        selector.setBackground(makeCardBackground());
        selector.setClickable(true);
        selector.setFocusable(true);
        selector.setOnClickListener(v -> showVersionPicker());
        contentRoot.addView(selector);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_minecraft_cube);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.setMarginEnd(dp(10));
        selector.addView(icon, iconParams);

        versionButton = new TextView(this);
        versionButton.setText(versionDisplayName(selectedVersion));
        versionButton.setTextColor(getColor(R.color.on_surface));
        versionButton.setTextSize(14);
        versionButton.setTypeface(null, android.graphics.Typeface.BOLD);
        versionButton.setSingleLine(true);
        versionButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selector.addView(versionButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextColor(getColor(R.color.text_secondary));
        arrow.setTextSize(14);
        selector.addView(arrow);
    }

    private String versionDisplayName(GameVersion version) {
        if (version == null) return getString(R.string.select_version);
        if (version.versionCode != null && !version.versionCode.isEmpty()) {
            String label = version.versionCode;
            if (version.displayName != null && !version.displayName.isEmpty()) {
                String dn = version.displayName;
                int parenIdx = dn.lastIndexOf(" (");
                if (parenIdx > 0) dn = dn.substring(0, parenIdx);
                label = dn + " · " + version.versionCode;
            }
            return label;
        }
        return version.directoryName != null ? version.directoryName : getString(R.string.select_version);
    }

    private void showVersionPicker() {
        List<GameVersion> allVersions = new ArrayList<>();
        List<GameVersion> installed = versionManager.getInstalledVersions();
        List<GameVersion> custom = versionManager.getCustomVersions();
        if (installed != null) allVersions.addAll(installed);
        if (custom != null) allVersions.addAll(custom);

        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_instance_selector, null);
        new PersonalizationManager(this).applyAccentToView(popupView, this);
        PopupWindow popup = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.setOutsideTouchable(true);

        RecyclerView recycler = popupView.findViewById(R.id.recycler_instances);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        VersionAdapter adapter = new VersionAdapter(allVersions, selectedVersion);
        recycler.setAdapter(adapter);
        adapter.setOnItemClickListener(version -> {
            selectedVersion = version;
            versionManager.selectVersion(version);
            versionButton.setText(versionDisplayName(version));
            popup.dismiss();
            loadWorlds();
        });

        popupView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupWidth = popupView.getMeasuredWidth();
        int anchorWidth = versionButton.getWidth();
        int xOffset = anchorWidth - popupWidth;
        popup.showAsDropDown(versionButton, xOffset, 4);
    }

    private void setupWorldsSection() {
        worldsContainer = new LinearLayout(this);
        worldsContainer.setOrientation(LinearLayout.VERTICAL);
        worldsContainer.setPadding(0, dp(12), 0, 0);
        contentRoot.addView(worldsContainer);
    }

    private void loadWorlds() {
        worldsContainer.removeAllViews();

        TextView loading = new TextView(this);
        loading.setText(R.string.world_picker_loading);
        loading.setTextColor(getColor(R.color.text_secondary));
        loading.setTextSize(13);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(24), 0, dp(24));
        worldsContainer.addView(loading);

        new Thread(() -> {
            List<WorldItem> worlds = scanWorlds();
            runOnUiThread(() -> renderWorlds(worlds));
        }, "world-scan").start();
    }

    private List<WorldItem> scanWorlds() {
        if (selectedVersion == null) return new ArrayList<>();
        try {
            FeatureSettings.StorageType resolved = LauncherStorage.normalizeContentStorageType(
                    FeatureSettings.StorageType.EXTERNAL, selectedVersion.versionIsolation);
            File gameData = LauncherStorage.getContentGameDataDir(
                    this, selectedVersion.getStorageProfileId(), resolved);
            if (gameData == null) return new ArrayList<>();
            WorldManager wm = new WorldManager(this);
            wm.setWorldsDirectory(new File(gameData, "minecraftWorlds"));
            return wm.getWorlds();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void renderWorlds(List<WorldItem> worlds) {
        worldsContainer.removeAllViews();
        if (worlds.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.world_picker_no_worlds);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            worldsContainer.addView(empty);
            return;
        }
        for (WorldItem world : worlds) {
            worldsContainer.addView(buildWorldCard(world));
        }
    }

    private View buildWorldCard(WorldItem world) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(cardParams);
        card.setBackground(makeCardBackground());
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> launchWorld(world));

        // 世界图标
        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.setMarginEnd(dp(12));
        card.addView(icon, iconParams);
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

        // 名称 + 种子
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(world.getWorldName());
        name.setTextColor(getColor(R.color.on_surface));
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);

        TextView seedText = new TextView(this);
        seedText.setText(getString(R.string.seed_label, world.getSeed()));
        seedText.setTextColor(getColor(R.color.text_secondary));
        seedText.setTextSize(12);
        seedText.setPadding(0, dp(2), 0, 0);
        info.addView(seedText);

        // 右侧箭头
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(getColor(R.color.text_secondary));
        arrow.setTextSize(18);
        card.addView(arrow);

        return card;
    }

    /** 点击世界：选中对应版本号，构建 minecraft:// URI 启动游戏并加载该世界。 */
    private void launchWorld(WorldItem world) {
        if (selectedVersion != null) {
            versionManager.selectVersion(selectedVersion);
        }
        // 官方 load 参数用世界目录名（level id），不是显示名
        Uri uri = MinecraftUriHandler.buildConnectLocalWorld(world.getWorldId());
        Intent intent = new Intent(this, IntentHandler.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.world_picker_launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private GradientDrawable makeCardBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(getColor(R.color.surface));
        d.setCornerRadius(dp(12));
        return d;
    }

    /** 版本选择列表适配器（复用启动器实例弹窗的 item 布局）。 */
    private static class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.VH> {
        private final List<GameVersion> versions;
        private final GameVersion selected;
        private OnItemClickListener listener;

        interface OnItemClickListener {
            void onClick(GameVersion version);
        }

        void setOnItemClickListener(OnItemClickListener l) {
            this.listener = l;
        }

        VersionAdapter(List<GameVersion> versions, GameVersion selected) {
            this.versions = versions;
            this.selected = selected;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_instance_popup, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GameVersion v = versions.get(position);
            holder.name.setText(displayName(holder.itemView.getContext(), v));
            String code = v.versionCode != null ? v.versionCode : "";
            holder.version.setText(code);
            holder.version.setVisibility(code.isEmpty() ? View.GONE : View.VISIBLE);
            holder.shortcut.setVisibility(View.GONE);
            boolean isSelected = selected != null && selected.directoryName != null
                    && selected.directoryName.equals(v.directoryName);
            holder.check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            int accent = holder.itemView.getContext().getColor(R.color.primary);
            holder.check.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
            holder.itemView.setOnClickListener(_v -> {
                if (listener != null) listener.onClick(v);
            });
        }

        private static String displayName(Context context, GameVersion version) {
            if (version == null) return "";
            if (version.displayName != null && !version.displayName.isEmpty()) return version.displayName;
            if (version.directoryName != null && !version.directoryName.isEmpty()) return version.directoryName;
            return version.versionCode != null ? version.versionCode : "";
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView version;
            final ImageView check;
            final ImageView shortcut;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.instance_name);
                version = v.findViewById(R.id.instance_version);
                check = v.findViewById(R.id.instance_check);
                shortcut = v.findViewById(R.id.instance_shortcut);
            }
        }
    }
}
