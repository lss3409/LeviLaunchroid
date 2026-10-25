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

        // 图标（图片条目显示彩色缩略图：必须清 tint，否则 SRC_IN 滤镜染成单色）
        int iconRes;
        if (e.isDirectory()) {
            iconRes = R.drawable.ic_folder;
        } else if (e.getArchiveType() != null) {
            iconRes = R.drawable.ic_export;
        } else {
            iconRes = fileTypeIcon(e.getName());
        }
        h.icon.setImageResource(iconRes);
        if (isImage(e.getName())) {
            h.icon.setColorFilter(null);
            loadThumb(e, h.icon);
        } else if (iconRes != R.drawable.ic_file && iconRes != R.drawable.ic_folder
                && iconRes != R.drawable.ic_export) {
            // v0.4.188：分类贴图（json/md/txt/音频/代码）保持原色不染灰
            h.icon.setColorFilter(null);
        } else {
            h.icon.setColorFilter(0xFF8A8A8A);
        }

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

    // ---------------- 图片缩略图（v670：列表内直接显示，恢复旧版能力） ----------------

    private static final java.util.Map<String, android.graphics.Bitmap> THUMB_CACHE =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) {
                    return size() > 128;
                }
            };
    private static final java.util.concurrent.ExecutorService THUMB_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    /** v0.4.188：按扩展名返回分类贴图（用户提供 SVG：
     *  json/md/txt/音频/代码[bin、material 等特殊 MC 代码格式]）。 */
    private static int fileTypeIcon(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".json")) {
            return R.drawable.ic_file_json;
        }
        if (n.endsWith(".md")) {
            return R.drawable.ic_file_md;
        }
        if (n.endsWith(".txt")) {
            return R.drawable.ic_file_txt;
        }
        if (n.endsWith(".ogg") || n.endsWith(".mp3") || n.endsWith(".wav")
                || n.endsWith(".flac") || n.endsWith(".m4a") || n.endsWith(".opus")) {
            return R.drawable.ic_file_audio;
        }
        // 特殊格式 MC 代码文件（bin/material 等）+ 常见代码格式
        if (n.endsWith(".bin") || n.endsWith(".material") || n.endsWith(".mcfunction")
                || n.endsWith(".mcstructure") || n.endsWith(".js") || n.endsWith(".ts")
                || n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".py")
                || n.endsWith(".c") || n.endsWith(".h") || n.endsWith(".cpp")
                || n.endsWith(".lua") || n.endsWith(".sh")) {
            return R.drawable.ic_file_code;
        }
        return R.drawable.ic_file;
    }

    /** v0.4.76：小图整数倍最近邻放大到长边 ~256（列表 icon 用；原图
     *  已回收），无需放大时原样返回。 */
    private static android.graphics.Bitmap upscaleThumb(android.graphics.Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int up = Math.max(1, 256 / Math.max(1, Math.max(w, h)));
        if (up <= 1) {
            return bmp;
        }
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createBitmap(
                w * up, h * up, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(scaled);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setFilterBitmap(false); // 最近邻
        c.drawBitmap(bmp, null, new android.graphics.Rect(0, 0, w * up, h * up), p);
        bmp.recycle();
        return scaled;
    }

    private void loadThumb(FmEntry e, ImageView iv) {
        final String path = e.getPath().toString();
        iv.setTag(path);
        android.graphics.Bitmap cached;
        synchronized (THUMB_CACHE) {
            cached = THUMB_CACHE.get(path);
        }
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        THUMB_POOL.execute(() -> {
            try {
                // v0.4.75+：动态降采样——只压大图；小图（像素贴图）全尺寸
                // 解码（此前 inSampleSize=8 固定：16×16 像素图只剩 2×2，
                // ImageView 放大显示=单色块）
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(path, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return;
                }
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                int sample = 1;
                int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
                while (maxSide / (sample * 2) >= 256) {
                    sample *= 2;
                }
                opts.inSampleSize = sample;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path, opts);
                if (bmp == null) {
                    return;
                }
                // 小图整数倍最近邻放大到长边 ~256（防 ImageView 双线性糊）
                final android.graphics.Bitmap result = upscaleThumb(bmp);
                synchronized (THUMB_CACHE) {
                    THUMB_CACHE.put(path, result);
                }
                iv.post(() -> {
                    if (path.equals(iv.getTag())) {
                        iv.setImageBitmap(result);
                    }
                });
            } catch (Throwable ignored) {
            }
        });
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
