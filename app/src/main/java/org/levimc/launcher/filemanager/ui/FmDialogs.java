package org.levimc.launcher.filemanager.ui;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.filemanager.config.FmConfig;
import org.levimc.launcher.filemanager.logic.compress.CompressFormat;
import org.levimc.launcher.filemanager.logic.compress.CompressOptions;
import org.levimc.launcher.filemanager.logic.entry.FmEntry;
import org.levimc.launcher.filemanager.logic.ops.ConflictResolution;
import org.levimc.launcher.filemanager.viewmodel.DialogIntent;
import org.levimc.launcher.filemanager.viewmodel.FileManagerViewModel;
import org.levimc.launcher.filemanager.viewmodel.SearchUiState;
import org.levimc.launcher.filemanager.viewmodel.SortConfig;
import org.levimc.launcher.ui.activities.BaseActivity;
import org.levimc.launcher.ui.activities.TextEditorActivity;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件管理器对话框工厂（Zalith FmDialogs 的 View 体系等价实现，v666）。
 * 全部通过 CustomAlertDialog 弹出，样式自动兼容个性化强调色。
 */
public class FmDialogs {

    /** 内置编辑器支持的文本后缀（Activity 分发共用）。 */
    public static final String[] TEXT_SUFFIX = {
            ".txt", ".log", ".json", ".jsonc", ".xml", ".yml", ".yaml", ".properties", ".mcmeta",
            ".cfg", ".conf", ".ini", ".md", ".html", ".js", ".css", ".csv", ".tsv", ".lang",
            // v0.4.74：常见文本格式补全（脚本/配置/字幕等）
            ".toml", ".ts", ".kt", ".java", ".py", ".sh", ".bat", ".sql", ".lua",
            ".mcfunction", ".srt", ".diff", ".patch", ".tex", ".nfo"
    };

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private static String fmtSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", size / 1048576.0);
        return String.format(Locale.getDefault(), "%.2f GB", size / 1073741824.0);
    }

    private static String fmtTime(long ms) {
        return FMT.format(new Date(ms));
    }

    // ---------------- 弹窗追踪（避免对话框叠加） ----------------

    private static CustomAlertDialog lastDialog;

    /** 弹窗前关闭上一个文件管理器对话框；点击屏幕外可关闭（与导入扫描弹窗一致）。 */
    private static void trackShow(CustomAlertDialog dialog) {
        dismissLast();
        lastDialog = dialog;
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /** 关闭当前文件管理器对话框（若无则不动）。 */
    public static void dismissLast() {
        if (lastDialog != null) {
            lastDialog.dismissImmediately();
            lastDialog = null;
        }
    }

    private static EditText buildInput(BaseActivity a, String hint, String text, boolean singleLine) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setText(text);
        et.setSingleLine(singleLine);
        et.setInputType(singleLine ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = (int) (12 * a.getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        et.setLayoutParams(lp);
        return et;
    }

    // ---------------- 新建 ----------------

    public static void showCreateDialog(BaseActivity a, FileManagerViewModel vm, boolean isFolder) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_name_hint), "", true);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(isFolder ? R.string.fm_ui_new_folder : R.string.fm_ui_new_file))
                .setCustomView(et)
                .setPositiveButton(a.getString(R.string.fm_ui_create), v ->
                        vm.submitCreate(et.getText().toString().trim(), isFolder, ok -> kotlin.Unit.INSTANCE))
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    /** v0.4.203：MT 式新建——先输名称（文件可带后缀，如 a.txt），
     *  再点「新建文件夹」或「新建文件」确认类型。 */
    public static void showCreateDialogMt(BaseActivity a, FileManagerViewModel vm) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_name_hint_ext), "", true);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_new))
                .setCustomView(et)
                .setPositiveButton(a.getString(R.string.fm_ui_new_folder), v ->
                        vm.submitCreate(et.getText().toString().trim(), true,
                                ok -> kotlin.Unit.INSTANCE))
                .setNeutralButton(a.getString(R.string.fm_ui_new_file), v ->
                        vm.submitCreate(et.getText().toString().trim(), false,
                                ok -> kotlin.Unit.INSTANCE))
                .setNegativeButton(a.getString(R.string.cancel), null);
        trackShow(dialog);
    }

    // ---------------- 重命名 ----------------

    public static void showRenameDialog(BaseActivity a, FileManagerViewModel vm, FmEntry entry) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_name_hint), entry.getName(), true);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_rename))
                .setCustomView(et)
                .setPositiveButton(a.getString(R.string.confirm), v ->
                        vm.submitRename(entry, et.getText().toString().trim(), () -> kotlin.Unit.INSTANCE))
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    // ---------------- 删除确认 ----------------

    /** 单条删除：先暂存选中再弹确认；取消时撤销暂存。 */
    public static void showDeleteConfirm(BaseActivity a, FileManagerViewModel vm, FmEntry entry) {
        vm.stageSingleDelete(entry);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_delete_confirm))
                .setMessage(entry.getName())
                .setNeutralButton(a.getString(R.string.fm_ui_delete_permanent), v -> {
                    vm.deleteSelected(false);
                })
                .setPositiveButton(a.getString(R.string.fm_ui_move_to_trash), v -> {
                    vm.deleteSelected(true);
                })
                .setNegativeButton(a.getString(R.string.cancel), v -> vm.cancelStagedDelete())
                ;
        dialog.setOnDismissListener(d -> vm.cancelStagedDelete());
        trackShow(dialog);
    }

    /** 多选批量删除。 */
    public static void showBatchDeleteConfirm(BaseActivity a, FileManagerViewModel vm, int count) {
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_delete_confirm))
                .setMessage(a.getString(R.string.fm_ui_delete_batch_msg, count))
                .setNeutralButton(a.getString(R.string.fm_ui_delete_permanent), v -> vm.deleteSelected(false))
                .setPositiveButton(a.getString(R.string.fm_ui_move_to_trash), v -> vm.deleteSelected(true))
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    // ---------------- 排序 ----------------

    public static void showSortDialog(BaseActivity a, FileManagerViewModel vm, SortConfig cur) {
        // 六项：三字段 × 升降序，点击即应用
        String[] items = {
                a.getString(R.string.fm_ui_sort_name) + " ↑",
                a.getString(R.string.fm_ui_sort_name) + " ↓",
                a.getString(R.string.fm_ui_sort_size) + " ↑",
                a.getString(R.string.fm_ui_sort_size) + " ↓",
                a.getString(R.string.fm_ui_sort_time) + " ↑",
                a.getString(R.string.fm_ui_sort_time) + " ↓"
        };
        FmConfig.SortField[] fields = {FmConfig.SortField.NAME, FmConfig.SortField.NAME,
                FmConfig.SortField.SIZE, FmConfig.SortField.SIZE,
                FmConfig.SortField.MODIFIED, FmConfig.SortField.MODIFIED};
        boolean[] dirs = {true, false, true, false, true, false};
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_sort))
                .setItems(items, (d, which) ->
                        vm.setSortConfig(new SortConfig(fields[which], dirs[which], cur.getFolderFirst())))
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    // ---------------- 路径跳转 ----------------

    public static void showJumpDialog(BaseActivity a, FileManagerViewModel vm, String currentPath) {
        // v0.4.89：跳转弹窗升级——显示父级目录链（点击上跳）+ 当前目录
        // 子文件夹列表（点击进入）+ 手动输入路径兜底
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);

        java.io.File cur = null;
        try {
            cur = new java.io.File(currentPath);
        } catch (Exception ignored) {
        }
        // 当前路径（蓝色，提示可点元素颜色一致）
        android.widget.TextView curTv = new android.widget.TextView(a);
        curTv.setText(currentPath);
        curTv.setTextColor(0xFF3B82F6);
        curTv.setTextSize(12);
        curTv.setPadding((int) (4 * d), 0, (int) (4 * d), (int) (8 * d));
        box.addView(curTv);

        // v0.4.90：完整路径段列表（根→当前，每段点击跳到该路径为止）
        if (cur != null) {
            java.util.List<java.io.File> chain = new java.util.ArrayList<>();
            java.io.File f = cur;
            while (f != null) {
                chain.add(0, f);
                f = f.getParentFile();
            }
            for (java.io.File dir : chain) {
                String label = dir.getParent() == null
                        ? dir.getPath() : dir.getName();
                if (dir.equals(cur)) {
                    label = "● " + label; // 当前段标记
                }
                box.addView(jumpRow(a, vm, label, dir.getAbsolutePath()));
            }
        }

        // 子文件夹列表
        if (cur != null && cur.isDirectory()) {
            java.io.File[] subs = cur.listFiles(f -> f.isDirectory());
            if (subs != null && subs.length > 0) {
                java.util.Arrays.sort(subs,
                        (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                android.widget.TextView subTitle = new android.widget.TextView(a);
                subTitle.setText(a.getString(R.string.fm_ui_subfolders));
                subTitle.setTextColor(a.getResources().getColor(R.color.text_secondary, a.getTheme()));
                subTitle.setTextSize(11);
                subTitle.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), (int) (4 * d));
                box.addView(subTitle);
                for (java.io.File sub : subs) {
                    box.addView(jumpRow(a, vm, sub.getName(), sub.getAbsolutePath()));
                }
            }
        }

        // 手动输入兜底
        EditText et = buildInput(a, a.getString(R.string.fm_ui_jump_hint), currentPath, true);
        et.setHint(a.getString(R.string.fm_ui_jump_hint));
        box.addView(et);

        // 限高滚动（父链/子文件夹多时防溢出）
        android.widget.ScrollView sc = new android.widget.ScrollView(a);
        sc.addView(box);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f);
        sc.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_jump))
                .setCustomView(sc)
                .setPositiveButton(a.getString(R.string.confirm), v -> {
                    if (!vm.submitJump(et.getText().toString().trim())) {
                        android.widget.Toast.makeText(a, R.string.fm_ui_jump_invalid, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    /** v0.4.89：跳转弹窗行（名称 + 点击跳转）。 */
    private static android.widget.TextView jumpRow(BaseActivity a, FileManagerViewModel vm,
                                                   String label, String path) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(label);
        tv.setTextColor(0xFF3B82F6);
        tv.setTextSize(13);
        tv.setPadding((int) (8 * d), (int) (7 * d), (int) (8 * d), (int) (7 * d));
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tv.setBackgroundResource(org.levimc.launcher.R.drawable.bg_search_box);
        tv.setOnClickListener(v -> vm.submitJump(path));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (4 * d);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ---------------- 属性 ----------------

    public static void showPropertiesDialog(BaseActivity a, FmEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(a.getString(R.string.fm_ui_prop_name)).append("：").append(entry.getName()).append('\n');
        sb.append(a.getString(R.string.fm_ui_prop_path)).append("：").append(entry.getPath()).append('\n');
        sb.append(a.getString(R.string.fm_ui_prop_type)).append("：")
                .append(entry.isDirectory() ? a.getString(R.string.fm_ui_folder) : a.getString(R.string.fm_ui_file)).append('\n');
        sb.append(a.getString(R.string.fm_ui_prop_size)).append("：").append(fmtSize(entry.getSize())).append('\n');
        sb.append(a.getString(R.string.fm_ui_prop_time)).append("：").append(fmtTime(entry.getModifiedMs()));
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_properties))
                .setMessage(sb.toString())
                .setPositiveButton(a.getString(R.string.confirm), null)
                ;
                trackShow(dialog);
    }

    /** v0.4.203：长按条目气泡菜单（MT 同款，导入菜单删除气泡样式）——
     *  复制/移动/重命名/压缩/删除/属性/分享/打开方式（按类型裁剪）。
     *  替代旧三点菜单 showEntryMenu（已删）。 */
    /** v0.4.204：长按条目气泡菜单（MT 同款）。
     *  · 双窗（peerVm 非空）复制/移动带方向箭头「复制 →/← 复制」——点击
     *    直接复制/移动到对面窗当前目录（先注入剪贴板再粘贴，跳过状态流往返）；
     *  · 「选择」=单选该条目（进多选模式）；
     *  · 气泡动态定位：下方空间不足自动向上弹；内容超高 45% 屏高进 ScrollView。 */
    /** v0.4.207：长按条目气泡菜单（MT 同款）。
     *  · 双窗（peerVm 非空）复制/移动带方向箭头「复制 →/← 复制」——点击
     *    直接复制/移动到对面窗当前目录（注入剪贴板后粘贴，跳过状态流
     *    往返），不弹任何提示，完成后由调用方做目标窗动画；
     *  · 单窗复制/移动只登记剪贴板（横幅粘贴）；
     *  · 「选择」=单选该条目（进多选模式）。 */
    public static void showEntryBubble(BaseActivity a, FileManagerViewModel vm, FmEntry entry,
                                       View anchor, FileManagerViewModel peerVm,
                                       boolean arrowRight, Runnable onPeerAction) {
        // v0.4.208：入口诊断日志（长按无反应排查）
        org.levimc.launcher.util.OnlineDebugLog.log("fm-bubble 长按菜单 entry="
                + (entry != null ? entry.getName() : "null") + " dual=" + (peerVm != null));
        final boolean dual = peerVm != null;
        float density = a.getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        labels.add(dual
                ? (arrowRight ? a.getString(R.string.fm_ui_copy) + " →"
                        : "← " + a.getString(R.string.fm_ui_copy))
                : a.getString(R.string.fm_ui_copy));
        actions.add(() -> {
            dismissLast();
            if (dual) {
                // v0.4.207：直发重写——静默粘贴到对面窗当前目录（冲突
                // 自动保留两者、永不弹窗），不登记剪贴板不显示横幅
                peerVm.pasteSilent(
                        java.util.Collections.singletonList(entry.getPath()), false);
                if (onPeerAction != null) {
                    onPeerAction.run();
                }
            } else {
                vm.copyEntry(entry);
            }
        });
        labels.add(dual
                ? (arrowRight ? a.getString(R.string.fm_ui_cut) + " →"
                        : "← " + a.getString(R.string.fm_ui_cut))
                : a.getString(R.string.fm_ui_cut));
        actions.add(() -> {
            dismissLast();
            if (dual) {
                peerVm.pasteSilent(
                        java.util.Collections.singletonList(entry.getPath()), true);
                if (onPeerAction != null) {
                    onPeerAction.run();
                }
            } else {
                vm.cutEntry(entry);
            }
        });
        // 选择（单选进多选）
        labels.add(a.getString(R.string.fm_ui_select));
        actions.add(() -> vm.toggleSelection(entry));
        labels.add(a.getString(R.string.fm_ui_rename));
        actions.add(() -> showRenameDialog(a, vm, entry));
        labels.add(a.getString(R.string.fm_ui_compress));
        actions.add(() -> vm.compressEntry(entry));
        labels.add(a.getString(R.string.fm_ui_delete));
        actions.add(() -> showDeleteConfirm(a, vm, entry));
        labels.add(a.getString(R.string.fm_ui_properties));
        actions.add(() -> showPropertiesDialog(a, entry));
        labels.add(a.getString(R.string.fm_ui_share));
        actions.add(() -> shareEntry(a, entry));
        if (!entry.isDirectory()) {
            labels.add(a.getString(R.string.fm_ui_open_with));
            actions.add(() -> showOpenWithChoices(a, entry, () -> {}));
        }
        // 气泡容器：surface 圆角 + 阴影（与导入菜单删除确认气泡同款）
        LinearLayout bubble = new LinearLayout(a);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(pad, (int) (10 * density), pad, (int) (10 * density));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(a.getColor(R.color.surface));
        bg.setCornerRadius(12 * density);
        bg.setStroke(1, a.getColor(R.color.surface));
        bubble.setBackground(bg);
        bubble.setElevation(8 * density);
        int textMain = a.getColor(R.color.on_surface);
        final PopupWindow[] popupRef = new PopupWindow[1];
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            TextView item = new TextView(a);
            item.setText(labels.get(i));
            item.setTextColor(textMain);
            item.setTextSize(13);
            item.setPadding((int) (8 * density), (int) (7 * density),
                    (int) (8 * density), (int) (7 * density));
            item.setOnClickListener(v -> {
                popupDismiss(popupRef[0]);
                actions.get(idx).run();
            });
            bubble.addView(item);
        }
        // v0.4.204：限高——超过 45% 屏高进滚动（防菜单项多时超出屏幕）
        View content = bubble;
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.45f);
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        if (bubble.getMeasuredHeight() > maxH) {
            ScrollView scroll = new ScrollView(a);
            scroll.addView(bubble);
            content = scroll;
        }
        final PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.min(bubble.getMeasuredHeight(), maxH) + (int) (4 * density), true);
        popupRef[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(8 * density);
        // v0.4.204：动态定位——下方空间不足向上弹（不写死，实测测量）
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int bubbleH = Math.min(bubble.getMeasuredHeight(), maxH) + (int) (4 * density);
        int spaceBelow = screenH - loc[1] - anchor.getHeight();
        if (spaceBelow < bubbleH && loc[1] > bubbleH) {
            popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + bubbleH));
        } else {
            popup.showAsDropDown(anchor, 0, (int) (2 * density));
        }
    }

    /** popup 引用可能未初始化（item 点击早于 popup 赋值？防御空指针）。 */
    private static void popupDismiss(PopupWindow p) {
        if (p != null) {
            try {
                p.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    /** v0.4.203：分享条目（FileProvider 授权，避免 file:// 崩溃）。 */
    public static void shareEntry(BaseActivity a, FmEntry entry) {
        try {
            java.io.File f = new java.io.File(entry.getPath().toString());
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    a, a.getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(guessMime(entry.getName()));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, null));
        } catch (Exception e) {
            Toast.makeText(a, a.getString(R.string.fm_ui_share_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** v0.4.203：压缩包二级浏览（单击 zip 静默进——MT 拆包查看同款逻辑）。
     *  仅 ZIP（7z/tar 走解压流程）；条目平铺显示完整路径，点文件解压到
     *  cache 临时目录后按类型预览/系统打开。 */
    public static void showArchiveBrowse(BaseActivity a, java.io.File archive) {
        final java.util.List<String[]> items = new java.util.ArrayList<>(); // [name, path]
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(archive)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry ze = en.nextElement();
                if (!ze.isDirectory()) {
                    items.add(new String[]{ze.getName(), ze.getName()});
                }
            }
        } catch (Exception e) {
            Toast.makeText(a, a.getString(R.string.fm_ui_archive_open_failed),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.isEmpty()) {
            Toast.makeText(a, a.getString(R.string.fm_ui_archive_empty),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        float density = a.getResources().getDisplayMetrics().density;
        int textMain = a.getColor(R.color.on_surface);
        int textSecondary = a.getColor(R.color.text_secondary);
        for (String[] it : items) {
            TextView row = new TextView(a);
            row.setText(it[0]);
            row.setTextColor(textMain);
            row.setTextSize(13);
            row.setMaxLines(1);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setPadding((int) (6 * density), (int) (8 * density),
                    (int) (6 * density), (int) (8 * density));
            row.setOnClickListener(v -> {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(archive)) {
                    java.util.zip.ZipEntry ze = zf.getEntry(it[1]);
                    if (ze == null) {
                        return;
                    }
                    java.io.File tmp = new java.io.File(a.getCacheDir(), "archive_view");
                    tmp.mkdirs();
                    java.io.File out = new java.io.File(tmp,
                            it[1].substring(it[1].lastIndexOf('/') + 1));
                    try (java.io.InputStream in = zf.getInputStream(ze);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    org.levimc.launcher.filemanager.logic.FmCompatKt.openWithSystem(a, out);
                } catch (Exception ignored) {
                    Toast.makeText(a, a.getString(R.string.fm_ui_archive_open_failed),
                            Toast.LENGTH_SHORT).show();
                }
            });
            list.addView(row);
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(archive.getName())
                .setCustomView(scroll)
                .setNegativeButton(a.getString(R.string.cancel), null);
        trackShow(dialog);
    }

    private static void showOpenWithChoices(BaseActivity a, FmEntry entry, Runnable onOpen) {
        String[] ways = new String[]{
                a.getString(R.string.fm_ui_open_internal),
                a.getString(R.string.fm_ui_open_system)};
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(entry.getName())
                .setItems(ways, (d, which) -> {
                    if (which == 0) {
                        if (onOpen != null) {
                            onOpen.run();
                        }
                    } else {
                        org.levimc.launcher.filemanager.logic.FmCompatKt.openWithSystem(
                                a, new File(entry.getPath().toString()));
                    }
                })
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    /**
     * 图片弹窗预览（v669 恢复旧版能力）：后台解码 + 按屏高算 inSampleSize 防大图 OOM。
     */
    public static void showImagePreview(BaseActivity a, File file) {
        android.widget.ImageView imageView = new android.widget.ImageView(a);
        imageView.setAdjustViewBounds(true);
        // v0.4.147：宽度也设上限——此前只限高，横图按宽度撑开把弹窗
        // 顶爆，取消按钮被挤出屏幕点不到（手机实测）
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.45f);
        imageView.setMaxHeight(maxH);
        int maxW = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.85f);
        imageView.setMaxWidth(maxW);

        // v0.4.142：解码期间显示转圈（大图解码有明确反馈）
        float density = a.getResources().getDisplayMetrics().density;
        android.widget.ProgressBar loading = new android.widget.ProgressBar(a);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                (int) (36 * density), (int) (36 * density), android.view.Gravity.CENTER);
        box.addView(imageView);
        box.addView(loading, lp);

        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(file.getName())
                .setCustomView(box)
                .setNegativeButton(a.getString(R.string.close), v -> dismissLast())
                ;
        trackShow(dialog);

        new Thread(() -> {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outHeight / sample > maxH * 2) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            // v0.4.75：低像素图整数倍最近邻放大（像素图软件同款）——
            // ImageView 默认双线性放大把像素图糊成单色块
            android.graphics.Bitmap finalBitmap = upscaleNearest(bitmap);
            a.runOnUiThread(() -> {
                loading.setVisibility(android.view.View.GONE);
                if (finalBitmap != null) imageView.setImageBitmap(finalBitmap);
            });
        }).start();
    }

    /** v0.4.75：低像素图整数倍最近邻预放大（长边到 ≤2048），
     *  返回新位图（原图已回收）；无需放大时原样返回。 */
    private static android.graphics.Bitmap upscaleNearest(android.graphics.Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int up = Math.min(2048 / Math.max(1, w), 2048 / Math.max(1, h));
        if (up <= 1) {
            return bitmap;
        }
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createBitmap(
                w * up, h * up, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(scaled);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setFilterBitmap(false); // 最近邻
        c.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, w * up, h * up), p);
        bitmap.recycle();
        return scaled;
    }

    /** 跳转 MT 管理器深度编辑（v670，照旧版 openInMt）。 */
    public static void openInMt(BaseActivity a, FmEntry entry) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    a, a.getPackageName() + ".fileprovider",
                    new File(entry.getPath().toString()));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setPackage("bin.mt.plus");
            a.startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(a, R.string.no_file_manager, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    public static String guessMime(String name) {
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") || name.endsWith(".gif")) return "image/*";
        if (name.endsWith(".mp3") || name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".flac")) return "audio/*";
        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")) return "video/*";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (name.endsWith(".zip") || name.endsWith(".jar")) return "application/zip";
        return "*/*";
    }

    // ---------------- 搜索 ----------------

    public static void showSearchSetup(BaseActivity a, FileManagerViewModel vm) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_search_hint), "", true);
        CheckBox cb = new CheckBox(a);
        cb.setText(a.getString(R.string.fm_ui_search_case));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 8, 0, 0);
        box.addView(et);
        box.addView(cb);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_search))
                .setCustomView(box)
                .setPositiveButton(a.getString(R.string.fm_ui_search), v ->
                        vm.submitSearch(et.getText().toString().trim(), cb.isChecked()))
                .setNegativeButton(a.getString(R.string.cancel), v -> vm.dismissDialog())
                ;
                trackShow(dialog);
    }

    public static void showSearchResults(BaseActivity a, FileManagerViewModel vm, SearchUiState ui) {
        if (ui == null) {
            CustomAlertDialog dialog = new CustomAlertDialog(a)
                    .setTitleText(a.getString(R.string.fm_ui_search))
                    .setMessage(a.getString(R.string.fm_ui_loading))
                    ;
                    trackShow(dialog);
            return;
        }
        List<SearchHitRow> rows = new java.util.ArrayList<>();
        for (org.levimc.launcher.filemanager.viewmodel.SearchHitView hit : ui.getHits()) {
            rows.add(new SearchHitRow(hit));
        }
        if (rows.isEmpty()) {
            CustomAlertDialog dialog = new CustomAlertDialog(a)
                    .setTitleText(a.getString(R.string.fm_ui_search))
                    .setMessage(a.getString(R.string.fm_ui_search_empty))
                    .setPositiveButton(a.getString(R.string.confirm), v -> vm.backToSearchSetup())
                    ;
                    trackShow(dialog);
            return;
        }
        String[] items = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            items[i] = rows.get(i).label;
        }
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_search_result, ui.getHits().size()))
                .setItems(items, (d, which) -> {
                    vm.navigateToSearchHit(rows.get(which).hit);
                    vm.dismissDialog();
                })
                .setNegativeButton(a.getString(R.string.fm_ui_search_again), v -> vm.backToSearchSetup())
                ;
                trackShow(dialog);
    }

    private static class SearchHitRow {
        final org.levimc.launcher.filemanager.viewmodel.SearchHitView hit;
        final String label;
        SearchHitRow(org.levimc.launcher.filemanager.viewmodel.SearchHitView hit) {
            this.hit = hit;
            this.label = hit.getName() + "  (" + (hit.isDirectory() ? "📁" : fmtSize(hit.getSize())) + ")";
        }
    }

    // ---------------- 压缩 ----------------

    public static void showCompressSetup(BaseActivity a, FileManagerViewModel vm, DialogIntent.CompressSetup intent) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_name_hint), intent.getDefaultName(), true);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_compress))
                .setCustomView(et)
                .setPositiveButton(a.getString(R.string.confirm), v ->
                        vm.onCompressSetupConfirmed(et.getText().toString().trim(), intent.getSources(),
                                new CompressOptions(CompressFormat.ZIP, null, null, null)))
                .setNegativeButton(a.getString(R.string.cancel), v -> vm.dismissDialog())
                ;
                trackShow(dialog);
    }

    /** 压缩/解压输出位置选择：当前目录或 SAF。 */
    public static void showOutputChoice(BaseActivity a, FileManagerViewModel vm, boolean extract) {
        String[] items = {
                a.getString(R.string.fm_ui_output_current),
                a.getString(R.string.fm_ui_output_saf)
        };
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(extract ? R.string.fm_ui_extract_to : R.string.fm_ui_compress_to))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (extract) vm.onExtractOutputChoiceCurrent();
                        else vm.onCompressOutputChoiceCurrent();
                    } else {
                        if (extract) vm.onExtractOutputChoiceSaf();
                        else vm.onCompressOutputChoiceSaf();
                    }
                })
                .setNegativeButton(a.getString(R.string.cancel), v -> vm.dismissDialog())
                ;
                trackShow(dialog);
    }

    /** 压缩包冲突：覆盖/跳过/保留两者。 */
    public static void showConflict(BaseActivity a, FileManagerViewModel vm, String name, boolean extract) {
        String msg = a.getString(R.string.fm_ui_conflict_msg, name);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_conflict))
                .setMessage(msg)
                .setNeutralButton(a.getString(R.string.fm_ui_keep_both), v -> {
                    if (extract) vm.resolveExtractConflict(ConflictResolution.KEEP_BOTH);
                    else vm.resolveCompressConflict(ConflictResolution.KEEP_BOTH);
                })
                .setPositiveButton(a.getString(R.string.fm_ui_overwrite), v -> {
                    if (extract) vm.resolveExtractConflict(ConflictResolution.OVERWRITE);
                    else vm.resolveCompressConflict(ConflictResolution.OVERWRITE);
                })
                .setNegativeButton(a.getString(R.string.fm_ui_skip), v -> {
                    if (extract) vm.resolveExtractConflict(ConflictResolution.SKIP);
                    else vm.resolveCompressConflict(ConflictResolution.SKIP);
                })
                ;
                trackShow(dialog);
    }

    // ---------------- 解压 ----------------

    public static void showExtractSetup(BaseActivity a, FileManagerViewModel vm, DialogIntent.ExtractSetup intent) {
        CheckBox cb = new CheckBox(a);
        cb.setText(a.getString(R.string.fm_ui_extract_independent));
        cb.setChecked(true);
        cb.setPadding(0, 16, 0, 0);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_extract))
                .setMessage(intent.getArchiveName())
                .setCustomView(cb)
                .setPositiveButton(a.getString(R.string.confirm), v ->
                        vm.onExtractSetupConfirmed(cb.isChecked()))
                .setNegativeButton(a.getString(R.string.cancel), v -> vm.dismissDialog())
                ;
                trackShow(dialog);
    }

    public static void showExtractPassword(BaseActivity a, FileManagerViewModel vm, String errorText) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_password_hint), "", true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        if (errorText != null && !errorText.isEmpty()) {
            TextView err = new TextView(a);
            err.setText(errorText);
            err.setTextColor(Color.rgb(0xB3, 0x26, 0x1E));
            box.addView(err);
        }
        box.addView(et);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_password))
                .setCustomView(box)
                .setPositiveButton(a.getString(R.string.confirm), v ->
                        vm.onExtractPasswordConfirmed(et.getText().toString()))
                .setNegativeButton(a.getString(R.string.cancel), v -> vm.onExtractOutputPickedCancelled())
                ;
                trackShow(dialog);
    }

    // ---------------- 粘贴冲突 ----------------

    public static void showPasteConflict(BaseActivity a, FileManagerViewModel vm, DialogIntent.PasteConflict intent) {
        org.levimc.launcher.filemanager.logic.ops.ConflictItem item =
                intent.getRequest().getConflicts().get(intent.getCurrentIndex());
        String name = item.getSource().getFileName() != null
                ? item.getSource().getFileName().toString() : item.getSource().toString();
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_conflict))
                .setMessage(a.getString(R.string.fm_ui_conflict_msg, name))
                .setNeutralButton(a.getString(R.string.fm_ui_keep_both), v ->
                        vm.resolvePasteConflict(ConflictResolution.KEEP_BOTH))
                .setPositiveButton(a.getString(R.string.fm_ui_overwrite), v ->
                        vm.resolvePasteConflict(ConflictResolution.OVERWRITE))
                .setNegativeButton(a.getString(R.string.fm_ui_skip), v ->
                        vm.resolvePasteConflict(ConflictResolution.SKIP))
                ;
                trackShow(dialog);
    }

    // ---------------- 回收站恢复冲突 ----------------

    public static void showTrashRestoreConflict(BaseActivity a, FileManagerViewModel vm, DialogIntent.TrashRestoreConflict intent) {
        org.levimc.launcher.filemanager.logic.trash.TrashItem item =
                intent.getConflictItems().get(intent.getPendingIndex()).getFirst();
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_conflict))
                .setMessage(a.getString(R.string.fm_ui_conflict_msg, item.getName()))
                .setNeutralButton(a.getString(R.string.fm_ui_keep_both), v ->
                        vm.resolveTrashRestoreConflict(ConflictResolution.KEEP_BOTH))
                .setPositiveButton(a.getString(R.string.fm_ui_overwrite), v ->
                        vm.resolveTrashRestoreConflict(ConflictResolution.OVERWRITE))
                .setNegativeButton(a.getString(R.string.fm_ui_skip), v ->
                        vm.resolveTrashRestoreConflict(ConflictResolution.SKIP))
                ;
                trackShow(dialog);
    }

    // ---------------- 回收站条目操作 ----------------

    /** 回收站条目点击弹操作菜单：恢复 / 彻底删除。 */
    public static void showTrashItemMenu(BaseActivity a, FileManagerViewModel vm,
                                         org.levimc.launcher.filemanager.logic.trash.TrashItem item) {
        String[] items = {
                a.getString(R.string.fm_ui_restore),
                a.getString(R.string.fm_ui_purge)
        };
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(item.getName())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        vm.restoreTrashItem(item);
                    } else {
                        confirmPurgeItem(a, vm, item);
                    }
                })
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    /** 单个回收站项目彻底删除确认。 */
    private static void confirmPurgeItem(BaseActivity a, FileManagerViewModel vm,
                                         org.levimc.launcher.filemanager.logic.trash.TrashItem item) {
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_delete_confirm))
                .setMessage(item.getName())
                .setPositiveButton(a.getString(R.string.fm_ui_purge), v ->
                        vm.purgeTrashItem(item))
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }
}
