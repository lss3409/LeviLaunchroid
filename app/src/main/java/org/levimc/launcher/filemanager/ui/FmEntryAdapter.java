package org.levimc.launcher.filemanager.ui;

import android.content.res.ColorStateList;
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
import org.levimc.launcher.filemanager.logic.entry.FmEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文件管理器条目适配器（v666）。
 * 两种视图：竖屏列表行（图标 + 名称 + 大小/时间副标题 + 更多按钮）、横屏网格卡片。
 * 触摸铁律：点击/长按统一挂在容器上，子 View 除更多按钮外不设监听。
 */
public class FmEntryAdapter extends RecyclerView.Adapter<FmEntryAdapter.Holder> {

    public static final int VIEW_LIST = 0;
    public static final int VIEW_CARD = 1;

    public interface Listener {
        void onEntryClick(FmEntry entry);
        void onEntryLongClick(FmEntry entry);
        void onMoreClick(FmEntry entry);
    }

    private final Listener listener;
    private final List<FmEntry> entries = new ArrayList<>();
    /** 外部传入的数据列表引用（引用比较用，Kotlin data class copy 保留旧引用）。 */
    private List<FmEntry> boundList;
    private Set<String> selection;
    private boolean multiSelect;
    private final int accent;
    private final int textPrimary;
    private final int textSecondary;
    private final int pageBg;
    private int viewType = VIEW_LIST;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public FmEntryAdapter(Listener listener, Set<String> selection, boolean multiSelect,
                          int accent, int textPrimary, int textSecondary, int pageBg) {
        this.listener = listener;
        this.selection = selection;
        this.multiSelect = multiSelect;
        this.accent = accent;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.pageBg = pageBg;
    }

    public void setViewType(int type) {
        if (viewType != type) {
            viewType = type;
            notifyDataSetChanged();
        }
    }

    public void submit(List<FmEntry> list) {
        boundList = list;
        entries.clear();
        entries.addAll(list);
        notifyDataSetChanged();
    }

    /** 选中态增量更新（状态高频刷新时避免重建适配器）。 */
    public void updateSelection(Set<String> newSelection, boolean newMultiSelect) {
        if (newSelection == selection && newMultiSelect == multiSelect) return;
        selection = newSelection != null ? newSelection : java.util.Collections.emptySet();
        multiSelect = newMultiSelect;
        notifyDataSetChanged();
    }

    /** 当前绑定的数据列表（供调用方做引用比较）。 */
    public List<FmEntry> getBoundList() {
        return boundList;
    }

    public int getViewType() {
        return viewType;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(
                viewType == VIEW_CARD ? R.layout.item_fm_entry_card : R.layout.item_fm_entry, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        FmEntry e = entries.get(position);
        h.entry = e;

        h.name.setText(e.getName());
        h.name.setTextColor(textPrimary);

        // 图标
        int iconRes;
        if (e.isDirectory()) {
            iconRes = R.drawable.ic_folder;
        } else if (e.getArchiveType() != null) {
            iconRes = R.drawable.ic_export;
        } else {
            iconRes = R.drawable.ic_file;
        }
        h.icon.setImageResource(iconRes);
        h.icon.setColorFilter(0xFF8A8A8A);

        // 副标题：大小 + 时间
        if (h.sub != null) {
            if (e.isDirectory()) {
                h.sub.setText(FMT.format(new Date(e.getModifiedMs())));
            } else {
                h.sub.setText(fmtSize(e.getSize()) + " · " + FMT.format(new Date(e.getModifiedMs())));
            }
            h.sub.setTextColor(textSecondary);
        }

        // 选中态：accent 描边 + 18% 半透明底
        boolean selected = multiSelect && selection.contains(key(e));
        applySelectionStyle(h.itemView, selected);
        if (h.check != null) {
            h.check.setVisibility(multiSelect ? View.VISIBLE : View.GONE);
            h.check.setSelected(selected);
        }

        // 触摸统一挂容器；更多按钮单独监听（点击不冒泡不影响长按）
        h.itemView.setOnClickListener(v -> {
            if (multiSelect) {
                listener.onEntryLongClick(e);
            } else {
                listener.onEntryClick(e);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            listener.onEntryLongClick(e);
            return true;
        });
        if (h.more != null) {
            h.more.setOnClickListener(v -> listener.onMoreClick(e));
        }
    }

    private static String key(FmEntry e) {
        return e.getPath().toString();
    }

    private void applySelectionStyle(View v, boolean selected) {
        // 照 ImportPickerDialog：每次新建 drawable（不 mutate 共享背景），blend 页面底色与 accent
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
        float r = Color.red(fg) * ratio + Color.red(bg) * (1 - ratio);
        float g = Color.green(fg) * ratio + Color.green(bg) * (1 - ratio);
        float b = Color.blue(fg) * ratio + Color.blue(bg) * (1 - ratio);
        return Color.rgb((int) r, (int) g, (int) b);
    }

    public static String fmtSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1048576) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        if (size < 1073741824L) return String.format(Locale.getDefault(), "%.1f MB", size / 1048576.0);
        return String.format(Locale.getDefault(), "%.2f GB", size / 1073741824.0);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @Override
    public int getItemViewType(int position) {
        return viewType;
    }

    static class Holder extends RecyclerView.ViewHolder {
        FmEntry entry;
        final ImageView icon;
        final TextView name;
        final TextView sub;
        final ImageView check;
        final ImageView more;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.fm_icon);
            name = itemView.findViewById(R.id.fm_name);
            sub = itemView.findViewById(R.id.fm_sub);
            check = itemView.findViewById(R.id.fm_check);
            more = itemView.findViewById(R.id.fm_more);
        }
    }
}
