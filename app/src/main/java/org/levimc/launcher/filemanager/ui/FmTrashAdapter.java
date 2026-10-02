package org.levimc.launcher.filemanager.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.filemanager.viewmodel.TrashItemView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 回收站条目适配器（v666）。 */
public class FmTrashAdapter extends RecyclerView.Adapter<FmTrashAdapter.Holder> {

    public interface Listener {
        void onItemClick(TrashItemView item);
        void onItemLongClick(TrashItemView item);
    }

    private final Listener listener;
    private final List<TrashItemView> items = new ArrayList<>();
    private final Set<String> selection;
    private final boolean multiSelect;
    private final int accent;
    private final int textPrimary;
    private final int textSecondary;
    private final int pageBg;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public FmTrashAdapter(Listener listener, Set<String> selection, boolean multiSelect,
                          int accent, int textPrimary, int textSecondary, int pageBg) {
        this.listener = listener;
        this.selection = selection;
        this.multiSelect = multiSelect;
        this.accent = accent;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.pageBg = pageBg;
    }

    public void submit(List<TrashItemView> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fm_trash, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        TrashItemView t = items.get(position);
        h.item = t;
        h.name.setText(t.getName());
        h.name.setTextColor(textPrimary);
        h.sub.setText(FMT.format(new Date(t.getDeletedAt())) + " · " + FmEntryAdapter.fmtSize(t.getSize()));
        h.sub.setTextColor(textSecondary);
        h.icon.setImageResource(t.isFolder() ? R.drawable.ic_folder : R.drawable.ic_file);
        h.icon.setColorFilter(t.getCorrupted() ? Color.rgb(0xB3, 0x26, 0x1E) : 0xFF8A8A8A);

        boolean selected = multiSelect && selection.contains(t.getUuid());
        applySelectionStyle(h.itemView, selected);
        h.check.setVisibility(multiSelect ? View.VISIBLE : View.GONE);
        h.check.setSelected(selected);

        h.itemView.setOnClickListener(v -> {
            if (multiSelect) listener.onItemLongClick(t);
            else listener.onItemClick(t);
        });
        h.itemView.setOnLongClickListener(v -> {
            listener.onItemLongClick(t);
            return true;
        });
    }

    private void applySelectionStyle(View v, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * v.getResources().getDisplayMetrics().density);
        if (selected) {
            bg.setColor(blend(pageBg, accent, 0.18f));
            bg.setStroke((int) (2 * v.getResources().getDisplayMetrics().density), accent);
        } else {
            bg.setColor(Color.TRANSPARENT);
        }
        v.setBackground(bg);
    }

    private static int blend(int fg, int bg, float ratio) {
        return Color.rgb(
                (int) (Color.red(fg) * ratio + Color.red(bg) * (1 - ratio)),
                (int) (Color.green(fg) * ratio + Color.green(bg) * (1 - ratio)),
                (int) (Color.blue(fg) * ratio + Color.blue(bg) * (1 - ratio)));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TrashItemView item;
        final ImageView icon;
        final TextView name;
        final TextView sub;
        final ImageView check;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.fm_icon);
            name = itemView.findViewById(R.id.fm_name);
            sub = itemView.findViewById(R.id.fm_sub);
            check = itemView.findViewById(R.id.fm_check);
        }
    }
}
