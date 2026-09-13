package org.levimc.launcher.ui.adapter;

import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ResourcePackItem;
import org.levimc.launcher.util.McFormatUtils;
import org.levimc.launcher.util.PreloadManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ResourcePacksAdapter extends RecyclerView.Adapter<ResourcePacksAdapter.ResourcePackViewHolder> {

    private List<ResourcePackItem> resourcePacks = new ArrayList<>();
    private OnResourcePackActionListener onResourcePackActionListener;
    private boolean showPreloadSwitch = false;

    public interface OnResourcePackActionListener {
        void onResourcePackDelete(ResourcePackItem pack);
        void onResourcePackTransfer(ResourcePackItem pack);
        void onResourcePackExport(ResourcePackItem pack);
        void onResourcePackLocate(ResourcePackItem pack);
        void onResourcePackPreloadChanged(ResourcePackItem pack, boolean preloaded);
    }

    public ResourcePacksAdapter() {
    }

    public void setOnResourcePackActionListener(OnResourcePackActionListener listener) {
        this.onResourcePackActionListener = listener;
    }

    public void setShowPreloadSwitch(boolean show) {
        this.showPreloadSwitch = show;
    }

    public void updateResourcePacks(List<ResourcePackItem> resourcePacks) {
        this.resourcePacks = resourcePacks != null ? resourcePacks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ResourcePackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_resource_pack, parent, false);
        return new ResourcePackViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ResourcePackViewHolder holder, int position) {
        ResourcePackItem pack = resourcePacks.get(position);

        holder.packName.setText(McFormatUtils.format(pack.getPackName()));
        holder.packDescription.setText(McFormatUtils.format(pack.getDescription()));
        holder.packSize.setText("Size: " + pack.getFormattedSize());

        String uuid = pack.getUuid();
        holder.packUuid.setText(uuid != null && !uuid.isEmpty() ? "UUID: " + uuid : "");

        holder.packIcon.setImageResource(R.drawable.ic_photo);
        File iconFile = new File(pack.getFile(), "pack_icon.png");
        if (iconFile.exists()) {
            final String iconPath = iconFile.getAbsolutePath();
            holder.packIcon.setTag(iconPath);
            new Thread(() -> {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                android.graphics.Bitmap bmp = BitmapFactory.decodeFile(iconPath, opts);
                holder.packIcon.post(() -> {
                    if (iconPath.equals(holder.packIcon.getTag()) && bmp != null) {
                        holder.packIcon.setImageBitmap(bmp);
                    }
                });
            }).start();
        }

        holder.locateButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackLocate(pack);
            }
        });

        holder.exportButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackExport(pack);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackDelete(pack);
            }
        });

        holder.transferButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackTransfer(pack);
            }
        });

        // 预加载开关（仅共享文件夹模式显示）
        if (showPreloadSwitch && pack.getUuid() != null && !pack.getUuid().isEmpty()) {
            holder.preloadLabel.setVisibility(View.VISIBLE);
            holder.preloadSwitch.setVisibility(View.VISIBLE);
            PreloadManager preloadManager = new PreloadManager(holder.itemView.getContext());
            boolean preloaded = preloadManager.isPreloaded(pack);
            holder.preloadSwitch.setOnCheckedChangeListener(null);
            holder.preloadSwitch.setChecked(preloaded);
            holder.preloadSwitch.setOnCheckedChangeListener((btn, checked) -> {
                preloadManager.setPreloaded(pack.getUuid(), checked);
                if (onResourcePackActionListener != null) {
                    onResourcePackActionListener.onResourcePackPreloadChanged(pack, checked);
                }
            });
        } else {
            if (holder.preloadLabel != null) holder.preloadLabel.setVisibility(View.GONE);
            if (holder.preloadSwitch != null) holder.preloadSwitch.setVisibility(View.GONE);
        }

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(holder.itemView.getContext());
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, holder.itemView.getContext());
    }

    @Override
    public int getItemCount() {
        return resourcePacks.size();
    }

    static class ResourcePackViewHolder extends RecyclerView.ViewHolder {
        ImageView packIcon;
        TextView packName;
        TextView packDescription;
        TextView packSize;
        TextView packUuid;
        Button locateButton;
        Button exportButton;
        Button deleteButton;
        Button transferButton;
        View preloadLabel;
        SwitchMaterial preloadSwitch;

        public ResourcePackViewHolder(@NonNull View itemView) {
            super(itemView);
            packIcon = itemView.findViewById(R.id.pack_icon);
            packName = itemView.findViewById(R.id.pack_name);
            packDescription = itemView.findViewById(R.id.pack_description);
            packSize = itemView.findViewById(R.id.pack_size);
            packUuid = itemView.findViewById(R.id.pack_uuid);
            locateButton = itemView.findViewById(R.id.pack_locate_button);
            exportButton = itemView.findViewById(R.id.pack_export_button);
            deleteButton = itemView.findViewById(R.id.pack_delete_button);
            transferButton = itemView.findViewById(R.id.pack_transfer_button);
            preloadLabel = itemView.findViewById(R.id.preload_label);
            preloadSwitch = itemView.findViewById(R.id.preload_switch);
        }
    }
}
