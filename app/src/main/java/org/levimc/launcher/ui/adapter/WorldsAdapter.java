package org.levimc.launcher.ui.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.ui.views.ContentActionPopup;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WorldsAdapter extends RecyclerView.Adapter<WorldsAdapter.WorldViewHolder> {

    private final List<WorldItem> worlds = new ArrayList<>();
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    /** v445：置顶集合 + 手动顺序快照（SharedPreferences 持久化）。 */
    private final Set<String> pinnedPaths = new LinkedHashSet<>();
    private final List<String> orderSnapshot = new ArrayList<>();
    private OnWorldActionListener onWorldActionListener;
    private OnSelectionChangedListener onSelectionChangedListener;
    private boolean selectionMode;

    public interface OnWorldActionListener {
        void onWorldExport(WorldItem world);
        void onWorldDelete(WorldItem world);
        void onWorldBackup(WorldItem world);
        void onWorldEdit(WorldItem world);
        void onWorldViewMap(WorldItem world);
        void onWorldExtractStructures(WorldItem world);
        void onWorldTransfer(WorldItem world);
        void onWorldLocate(WorldItem world);
        /** v444：游玩（卡片 ▶ 按钮 / 卡片主体点击）。 */
        void onWorldPlay(WorldItem world);
        /** v445：置顶/取消置顶。 */
        void onWorldTogglePin(WorldItem world);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    public void setOnWorldActionListener(OnWorldActionListener listener) {
        this.onWorldActionListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.onSelectionChangedListener = listener;
    }

    public void updateWorlds(List<WorldItem> updatedWorlds) {
        worlds.clear();
        if (updatedWorlds != null) worlds.addAll(updatedWorlds);
        // v445：排序——置顶组在前（组内按顺序快照/时间），非置顶
        // 按最后修改时间降序（最新游玩在前）
        worlds.sort(this::compareWorlds);
        notifyDataSetChanged();
    }

    /** v445：置顶集合（外部读 SharedPreferences 注入）。 */
    public void setPinnedPaths(Set<String> pinned) {
        pinnedPaths.clear();
        if (pinned != null) pinnedPaths.addAll(pinned);
    }

    /** v445：手动排序快照（拖动后保存，外部注入）。 */
    public void setOrderSnapshot(List<String> order) {
        orderSnapshot.clear();
        if (order != null) orderSnapshot.addAll(order);
    }

    /** v445：当前列表顺序快照（拖动结束后外部取走保存）。 */
    public List<String> currentOrderSnapshot() {
        List<String> out = new ArrayList<>();
        for (WorldItem world : worlds) out.add(pathOf(world));
        return out;
    }

    /** v445：是否置顶（卡片背景深浅区分用）。 */
    public boolean isPinned(WorldItem world) {
        return pinnedPaths.contains(pathOf(world));
    }

    /** v445：拖动换位（ItemTouchHelper onMove）。 */
    public void moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= worlds.size() || to >= worlds.size()) {
            return;
        }
        WorldItem item = worlds.remove(from);
        worlds.add(to, item);
        notifyItemMoved(from, to);
    }

    /** v445：位置对应世界（onMove 跨组判定用）。 */
    public WorldItem getWorldAt(int pos) {
        if (pos < 0 || pos >= worlds.size()) {
            return null;
        }
        return worlds.get(pos);
    }

    private int compareWorlds(WorldItem a, WorldItem b) {
        String pa = pathOf(a);
        String pb = pathOf(b);
        boolean ia = pinnedPaths.contains(pa);
        boolean ib = pinnedPaths.contains(pb);
        if (ia != ib) {
            return ia ? -1 : 1;
        }
        if (ia) {
            int oa = orderSnapshot.indexOf(pa);
            int ob = orderSnapshot.indexOf(pb);
            if (oa >= 0 && ob >= 0 && oa != ob) {
                return oa - ob;
            }
        }
        long ta = a.getFile() != null ? a.getFile().lastModified() : 0;
        long tb = b.getFile() != null ? b.getFile().lastModified() : 0;
        return Long.compare(tb, ta); // 最新游玩在前
    }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selectedPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectedCount() {
        return selectedPaths.size();
    }

    public ArrayList<String> getSelectedPaths() {
        return new ArrayList<>(selectedPaths);
    }

    public void restoreSelection(List<String> paths, boolean active) {
        selectedPaths.clear();
        if (paths != null) selectedPaths.addAll(paths);
        selectionMode = active;
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public List<WorldItem> getSelectedItems(List<WorldItem> source) {
        List<WorldItem> result = new ArrayList<>();
        if (source == null) return result;
        for (WorldItem world : source) {
            if (selectedPaths.contains(pathOf(world))) result.add(world);
        }
        return result;
    }

    public void selectAllVisible() {
        selectionMode = true;
        for (WorldItem world : worlds) selectedPaths.add(pathOf(world));
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void clearSelection() {
        selectedPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public boolean areAllVisibleSelected() {
        if (worlds.isEmpty()) return false;
        for (WorldItem world : worlds) {
            if (!selectedPaths.contains(pathOf(world))) return false;
        }
        return true;
    }

    public void retainSelections(List<WorldItem> source) {
        Set<String> valid = new HashSet<>();
        if (source != null) {
            for (WorldItem world : source) valid.add(pathOf(world));
        }
        if (selectedPaths.retainAll(valid)) {
            notifyDataSetChanged();
            notifySelectionChanged();
        }
    }

    @NonNull
    @Override
    public WorldViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_world, parent, false);
        return new WorldViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull WorldViewHolder holder, int position) {
        WorldItem world = worlds.get(position);
        boolean selected = selectedPaths.contains(pathOf(world));

        holder.worldName.setText(world.getWorldName());
        holder.hardcoreTag.setVisibility(world.isHardcore() ? View.VISIBLE : View.GONE);
        // 极限红心角标：极限模式显示（已死亡用死亡红心，活着用普通红心）
        if (world.isHardcore() && holder.hardcoreHeartBadge != null) {
            holder.hardcoreHeartBadge.setVisibility(View.VISIBLE);
            holder.hardcoreHeartBadge.setImageResource(world.isPlayerDead()
                    ? R.drawable.ic_hardcore_heart_dead : R.drawable.ic_hardcore_heart_alive);
        } else if (holder.hardcoreHeartBadge != null) {
            holder.hardcoreHeartBadge.setVisibility(View.GONE);
        }
        holder.worldDescription.setText(holder.itemView.getContext().getString(R.string.world_meta, world.getGameMode(), world.getFormattedSize()));
        holder.worldLastPlayed.setText(holder.itemView.getContext().getString(R.string.world_last_played, world.getFormattedLastModified()));
        holder.worldSeed.setText(holder.itemView.getContext().getString(R.string.seed_label, world.getSeed()));
        holder.worldSeed.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) holder.itemView.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("seed", String.valueOf(world.getSeed())));
                Toast.makeText(holder.itemView.getContext(),
                        holder.itemView.getContext().getString(R.string.seed_copied),
                        Toast.LENGTH_SHORT).show();
            }
        });

        holder.itemView.setActivated(selected);
        holder.playButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.editButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.overflowButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.selectionIndicator.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.selectionIndicator.setAlpha(selected ? 1f : 0.28f);

        File icon = world.getIconFile();
        Glide.with(holder.worldIcon)
                .load(icon != null ? icon : R.drawable.ic_world)
                .error(R.drawable.ic_world)
                .into(holder.worldIcon);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(world);
            } else {
                // v444：卡片主体点击 = 游玩（原版世界列表同款语义）
                if (onWorldActionListener != null) onWorldActionListener.onWorldPlay(world);
            }
        });
        // v445：长按交给 ItemTouchHelper 拖动排序（多选模式关闭时）；
        // 多选模式长按仍切选中。多选入口 = 顶栏"选择"按钮
        holder.itemView.setOnLongClickListener(v -> {
            if (selectionMode) {
                toggleSelection(world);
            }
            return false; // 不消费——ItemTouchHelper 接管长按拖动
        });
        holder.playButton.setOnClickListener(v -> {
            // v444：▶ 按钮 = 立即游玩该存档
            if (onWorldActionListener != null) onWorldActionListener.onWorldPlay(world);
        });
        // v445：▶ 背景跟随个性化 accent 色（XML 默认 primary；
        // applyAccentToView 不覆盖背景 tint，需手动同步）
        int accent = new PersonalizationManager(holder.itemView.getContext()).getAccentColor();
        if (accent != 0) {
            holder.playButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(accent));
        }
        holder.editButton.setOnClickListener(v -> {
            // 地图按钮：直接打开世界数据/地图查看（NBT 查看器）
            if (onWorldActionListener != null) onWorldActionListener.onWorldViewMap(world);
        });
        holder.overflowButton.setOnClickListener(v -> showOverflow(holder.overflowButton, world));

        PersonalizationManager pm = new PersonalizationManager(holder.itemView.getContext());
        // v445：置顶卡片背景更深（与普通卡片区分）；非置顶恢复
        // 默认卡片背景——View 复用后置顶背景会残留（applyGlassToView
        // 不改 surface_high 色，取消置顶深色不变回的根因）
        if (isPinned(world)) {
            holder.itemView.setBackground(holder.itemView.getContext().getDrawable(
                    R.drawable.bg_world_card_pinned));
        } else {
            holder.itemView.setBackground(holder.itemView.getContext().getDrawable(
                    R.drawable.bg_content_item));
        }
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, holder.itemView.getContext());
    }

    private void showOverflow(View anchor, WorldItem world) {
        // v445：置顶项（状态文案随当前置顶状态切换）
        boolean pinned = pinnedPaths.contains(pathOf(world));
        ContentActionPopup.show(anchor, world.getWorldName(), Arrays.asList(
                new ContentActionPopup.Action(R.drawable.ic_edit, R.string.edit, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldEdit(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_export, R.string.export, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldExport(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_pin, pinned ? R.string.unpin_world : R.string.pin_world, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldTogglePin(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_export, R.string.export, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldExport(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_backup, R.string.backup, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldBackup(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_structure, R.string.extract_structures, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldExtractStructures(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_transfer, R.string.transfer, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldTransfer(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_folder, R.string.locate, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldLocate(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_delete, R.string.delete, true, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldDelete(world);
                })
        ));
    }

    private void toggleSelection(WorldItem world) {
        String path = pathOf(world);
        if (!selectedPaths.add(path)) selectedPaths.remove(path);
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (onSelectionChangedListener != null) onSelectionChangedListener.onSelectionChanged(selectedPaths.size());
    }

    private String pathOf(WorldItem world) {
        File file = world != null ? world.getFile() : null;
        if (file == null) return "";
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    @Override
    public int getItemCount() {
        return worlds.size();
    }

    static class WorldViewHolder extends RecyclerView.ViewHolder {
        final ImageView worldIcon;
        final TextView worldName;
        final TextView worldLastPlayed;
        final TextView worldDescription;
        final TextView worldSeed;
        final TextView hardcoreTag;
        final ImageButton playButton;
        final ImageButton editButton;
        final ImageButton overflowButton;
        final ImageView selectionIndicator;
        final ImageView hardcoreHeartBadge;

        WorldViewHolder(@NonNull View itemView) {
            super(itemView);
            worldIcon = itemView.findViewById(R.id.world_icon);
            worldName = itemView.findViewById(R.id.world_name);
            worldLastPlayed = itemView.findViewById(R.id.world_last_played);
            worldDescription = itemView.findViewById(R.id.world_description);
            worldSeed = itemView.findViewById(R.id.world_seed);
            hardcoreTag = itemView.findViewById(R.id.world_hardcore_tag);
            playButton = itemView.findViewById(R.id.world_play_button);
            editButton = itemView.findViewById(R.id.world_edit_button);
            overflowButton = itemView.findViewById(R.id.world_overflow_button);
            selectionIndicator = itemView.findViewById(R.id.world_selection_indicator);
            hardcoreHeartBadge = itemView.findViewById(R.id.world_hardcore_heart_badge);
        }
    }
}
