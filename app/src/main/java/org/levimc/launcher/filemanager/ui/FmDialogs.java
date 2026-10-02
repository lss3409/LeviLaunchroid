package org.levimc.launcher.filemanager.ui;

import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    private static final String[] TEXT_SUFFIX = {
            ".txt", ".log", ".json", ".xml", ".yml", ".yaml", ".properties", ".mcmeta",
            ".cfg", ".conf", ".ini", ".md", ".html", ".js", ".css", ".csv", ".lang"
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

    /** 弹窗前关闭上一个文件管理器对话框。 */
    private static void trackShow(CustomAlertDialog dialog) {
        dismissLast();
        lastDialog = dialog;
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
                        vm.submitCreate(et.getText().toString().trim(), isFolder, ok -> {}))
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    // ---------------- 重命名 ----------------

    public static void showRenameDialog(BaseActivity a, FileManagerViewModel vm, FmEntry entry) {
        EditText et = buildInput(a, a.getString(R.string.fm_ui_name_hint), entry.getName(), true);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_rename))
                .setCustomView(et)
                .setPositiveButton(a.getString(R.string.confirm), v ->
                        vm.submitRename(entry, et.getText().toString().trim(), () -> {}))
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
                .setOnDismissListener(d -> vm.cancelStagedDelete())
                ;
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
        EditText et = buildInput(a, a.getString(R.string.fm_ui_jump_hint), currentPath, true);
        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.fm_ui_jump))
                .setCustomView(et)
                .setPositiveButton(a.getString(R.string.confirm), v -> {
                    if (!vm.submitJump(et.getText().toString().trim())) {
                        android.widget.Toast.makeText(a, R.string.fm_ui_jump_invalid, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
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

    // ---------------- 条目操作菜单 ----------------

    public static void showEntryMenu(BaseActivity a, FileManagerViewModel vm, FmEntry entry, Runnable refresh) {
        List<String> items = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        // 打开
        items.add(entry.isDirectory() ? a.getString(R.string.fm_ui_open) : a.getString(R.string.fm_ui_open_with));
        actions.add(() -> {
            if (entry.isDirectory()) {
                vm.enterDirectory(entry);
            } else {
                openFile(a, entry);
            }
        });
        items.add(a.getString(R.string.fm_ui_copy));
        actions.add(() -> vm.copyEntry(entry));
        items.add(a.getString(R.string.fm_ui_cut));
        actions.add(() -> vm.cutEntry(entry));
        items.add(a.getString(R.string.fm_ui_rename));
        actions.add(() -> showRenameDialog(a, vm, entry));
        if (entry.getArchiveType() != null) {
            items.add(a.getString(R.string.fm_ui_extract));
            actions.add(() -> vm.showExtract(entry));
        } else {
            items.add(a.getString(R.string.fm_ui_compress));
            actions.add(() -> vm.compressEntry(entry));
        }
        items.add(a.getString(R.string.fm_ui_properties));
        actions.add(() -> showPropertiesDialog(a, entry));
        items.add(a.getString(R.string.fm_ui_share));
        actions.add(() -> org.levimc.launcher.filemanager.logic.FmCompatKt.shareFile(a, new File(entry.getPath().toString())));
        items.add(a.getString(R.string.fm_ui_delete));
        actions.add(() -> showDeleteConfirm(a, vm, entry));

        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(entry.getName())
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(a.getString(R.string.cancel), null)
                ;
                trackShow(dialog);
    }

    /** 文件打开：文本类走内置编辑器，其余交系统。 */
    public static void openFile(BaseActivity a, FmEntry entry) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        for (String suffix : TEXT_SUFFIX) {
            if (name.endsWith(suffix)) {
                Intent intent = new Intent(a, TextEditorActivity.class);
                intent.putExtra(TextEditorActivity.EXTRA_PATH, entry.getPath().toString());
                a.startActivity(intent);
                return;
            }
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(android.net.Uri.fromFile(new File(entry.getPath().toString())), guessMime(name));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(view, null));
        } catch (Exception e) {
            a.toast(a.getString(R.string.fm_ui_open_failed));
        }
    }

    private static String guessMime(String name) {
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
                .setNegativeButton(a.getString(R.string.fm_ui_search_again), (d, w) -> vm.backToSearchSetup())
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
