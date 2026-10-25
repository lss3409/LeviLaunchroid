package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;
import org.levimc.launcher.util.AccentStyler;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/** v0.4.179：扫描指定文件夹——入口在导入菜单（ImportPickerDialog）。
 *  交互完全按用户指定：
 *  · 新建文件夹 → 列表生成带文本框的空实例 + 「选择/应用」双态按钮
 *    （复用存储自定义路径逻辑：空=选择，有文本=应用，当前路径在
 *    hint；选择=SAF 文件管理器选目录自动应用）
 *  · 删除 → 气泡确认框（Snackbar「删除此文件夹？」+确认）
 *  · 扫描复用 GlobalImportScanner.scanDirsAsync，结果走 foundSoFar */
public final class ScanFolderDialog {

    private static final String PREFS = "import_scan_folders";
    private static final String KEY_DIRS = "dirs";

    public interface OnScanListener {
        void onScanDone(List<GlobalImportScanner.Candidate> result);
    }

    /** v0.4.184：SAF 选目录 launcher——ActivityResult API 必须在
     *  Activity STARTED 前注册（点击时才注册=IllegalStateException
     *  tombstone 0.4.182）；调用 Activity onCreate 时 installTreePicker
     *  预注册，点击时直接用。 */
    private static ActivityResultLauncher<Uri> sTreePicker;
    private static EditText sSafTarget;

    /** Activity onCreate 调用（ContentManagement/SharedFolder 等）。 */
    public static void installTreePicker(androidx.activity.ComponentActivity activity) {
        sTreePicker = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    // v0.4.185：诊断——回调是否到达、uri 解析结果
                    android.util.Log.i("ScanFolderDiag", "treePicker callback uri="
                            + uri + " sSafTarget=" + (sSafTarget != null));
                    if (uri != null) {
                        String path = safTreeToPath(uri);
                        android.util.Log.i("ScanFolderDiag", "safTreeToPath=" + path);
                        if (path != null && sSafTarget != null) {
                            sSafTarget.setText(path);
                            // v0.4.184：setText 触发 Watcher 切「应用」态，
                            // 不自动 apply（用户确认后再点应用）
                        }
                    }
                });
    }

    public static List<String> loadFolders(Context context) {
        List<String> out = new ArrayList<>();
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DIRS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveFolders(Context context, List<String> dirs) {
        JSONArray arr = new JSONArray();
        for (String d : dirs) {
            arr.put(d);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DIRS, arr.toString()).apply();
    }

    /** SAF 树 uri → 真实路径（primary: 段解析；失败返回 null 手输兜底）。
     *  v0.4.188：先整体 URLDecode 再检查前缀——平板 SAF 返回的
     *  lastPathSegment 常是 primary%3ADownload%3Amods（冒号被编码），
     *  解码前 startsWith("primary:") 恒 false=「选择后空白」根因。 */
    private static String safTreeToPath(Uri uri) {
        try {
            String seg = uri.getLastPathSegment();
            if (seg == null) {
                return null;
            }
            String decoded = URLDecoder.decode(seg, "UTF-8");
            if (!decoded.startsWith("primary:")) {
                // 兼容无编码形式（某些 ROM 直接给 primary:Download）
                if (!seg.startsWith("primary:")) {
                    return null;
                }
                decoded = seg;
            }
            String rel = decoded.substring("primary:".length());
            String base = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            return base + "/" + rel;
        } catch (Exception e) {
            return null;
        }
    }

    /** 打开指定文件夹管理弹窗；扫描完成后回调结果并自动关闭。 */
    public static void show(androidx.activity.ComponentActivity activity,
                            OnScanListener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        List<String> dirs = new ArrayList<>(loadFolders(activity));
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        // v0.4.189：与导入菜单同尺寸的二级菜单（DialogSizer 同款）
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int width = org.levimc.launcher.util.DialogSizer.dialogWidth(activity, 560);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_rounded_card);
        root.setPadding((int) (20 * density), (int) (12 * density),
                (int) (20 * density), (int) (16 * density));

        // 顶部：〈返回键（左上角）+ 标题
        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(activity);
        back.setText("〈");
        back.setTextSize(22f);
        back.setTextColor(activity.getColor(R.color.text_secondary));
        back.setPadding(0, 0, (int) (12 * density), 0);
        back.setOnClickListener(v -> {
            dialog.dismiss();
            // 返回上一级菜单（导入菜单）
            if (activity instanceof android.content.Context) {
                org.levimc.launcher.ui.dialogs.ImportPickerDialog.show(activity,
                        new org.levimc.launcher.ui.dialogs.ImportPickerDialog.Listener() {
                            @Override
                            public void onPick(java.io.File file) {
                            }
                            @Override
                            public void onPickFromFiles() {
                            }
                        });
            }
        });
        TextView title = new TextView(activity);
        title.setText(R.string.scan_folder_title);
        title.setTextColor(activity.getColor(R.color.on_surface));
        title.setTextSize(16f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        topRow.addView(back);
        topRow.addView(title);
        root.addView(topRow);

        TextView hint = new TextView(activity);
        hint.setText(R.string.scan_folder_hint);
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        hint.setTextSize(13f);
        hint.setPadding(0, pad, 0, pad);
        root.addView(hint);

        // 文件夹列表（ScrollView + 动态条目）
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (240 * density)));

        final List<String> fDirs = dirs;
        final LinearLayout fList = list;
        final CustomAlertDialog[] dialogRef = new CustomAlertDialog[1];
        final Dialog[] fDialogHolder = new Dialog[]{dialog};
        final Runnable[] redrawRef = new Runnable[1];
        // v0.4.184：SAF launcher 由 Activity onCreate 预注册（installTreePicker），
        // 选完 setText 后由输入框 Watcher 自动切「应用」态
        redrawRef[0] = () -> {
            fList.removeAllViews();
            for (int i = 0; i < fDirs.size(); i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView path = new TextView(activity);
                path.setText(fDirs.get(idx));
                path.setTextColor(activity.getColor(R.color.on_surface));
                path.setTextSize(13f);
                path.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
                ImageButton del = new ImageButton(activity);
                del.setImageResource(R.drawable.ic_delete);
                // v0.4.179：selectableItemBackgroundBorderless 必须
                // resolveAttribute 拿 drawable ID——直接 setBackgroundResource
                // (android.R.attr.borderlessButtonStyle) 把 attr 当 drawable
                // 用 = ResourcesNotFoundException（tombstone 0.4.177）
                android.util.TypedValue tv = new android.util.TypedValue();
                activity.getTheme().resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, tv, true);
                del.setBackgroundResource(tv.resourceId);
                del.setColorFilter(0xFF8A8A8A);
                del.setContentDescription(activity.getString(R.string.scan_folder_remove));
                del.setOnClickListener(v -> {
                    // 气泡删除确认（用户指定）
                    Snackbar.make(activity.findViewById(android.R.id.content),
                                    activity.getString(R.string.scan_folder_delete_confirm,
                                            fDirs.get(idx)),
                                    Snackbar.LENGTH_LONG)
                            .setAction(R.string.scan_folder_confirm, view -> {
                                fDirs.remove(idx);
                                saveFolders(activity, fDirs);
                                redrawRef[0].run();
                            })
                            .setActionTextColor(
                                    new org.levimc.launcher.util.PersonalizationManager(activity)
                                            .getAccentColor())
                            .show();
                });
                row.addView(path, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(del, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                fList.addView(row);
            }
            if (fDirs.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText(R.string.scan_folder_empty);
                empty.setTextColor(activity.getColor(R.color.text_secondary));
                empty.setTextSize(13f);
                empty.setPadding(0, (int) (8 * density), 0, 0);
                fList.addView(empty);
            }
        };
        redrawRef[0].run();

        // 底部操作行：新建文件夹 + 开始扫描
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, pad, 0, 0);
        MaterialButton add = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonStyle);
        add.setText(R.string.scan_folder_new);
        add.setAllCaps(false);
        add.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        AccentStyler.stylePrimary(activity, add);
        MaterialButton scan = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonStyle);
        scan.setText(R.string.scan_folder_start);
        scan.setAllCaps(false);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scanLp.setMarginStart(pad / 2);
        scan.setLayoutParams(scanLp);
        AccentStyler.stylePrimary(activity, scan);
        actions.addView(add);
        actions.addView(scan);
        root.addView(actions);

        // 新建文件夹：追加带文本框的空实例条目（选择/应用双态按钮）
        final boolean[] instanceOpen = {false};
        add.setOnClickListener(v -> {
            if (instanceOpen[0]) {
                return; // 已有未应用的空实例
            }
            instanceOpen[0] = true;
            fList.removeAllViews();
            // 先重画已有条目
            redrawRef[0].run();
            // 空实例行：文本框 + 选择/应用
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            EditText input = new EditText(activity);
            input.setHint(R.string.scan_folder_input_hint);
            input.setSingleLine(true);
            input.setTextSize(13f);
            input.setTextColor(activity.getColor(R.color.on_surface));
            input.setHintTextColor(activity.getColor(R.color.text_secondary));
            MaterialButton pick = new MaterialButton(activity, null,
                    com.google.android.material.R.attr.materialButtonStyle);
            pick.setAllCaps(false);
            // v0.4.23 双态逻辑：空=「选择」（开文件管理器，选完自动
            // 应用）/有文本=「应用」（应用输入内容）
            pick.setText(R.string.scan_folder_select);
            AccentStyler.stylePrimary(activity, pick);
            final MaterialButton fPick = pick;
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int ct, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int bf, int ct) {
                    fPick.setText(input.getText().toString().trim().isEmpty()
                            ? R.string.scan_folder_select
                            : R.string.scan_folder_apply);
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
            pick.setOnClickListener(pv -> {
                if (input.getText().toString().trim().isEmpty()) {
                    sSafTarget = input;
                    try {
                        if (sTreePicker != null) {
                            sTreePicker.launch(null);
                        } else {
                            Toast.makeText(activity, R.string.scan_folder_pick_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(activity, R.string.scan_folder_pick_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (applyNewFolder(activity, fDirs, input, redrawRef[0])) {
                        instanceOpen[0] = false;
                    }
                }
            });
            row.addView(input, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.setMarginStart((int) (8 * density));
            row.addView(pick, bLp);
            fList.addView(row);
        });

        scan.setOnClickListener(v -> {
            if (fDirs.isEmpty()) {
                Toast.makeText(activity, R.string.scan_folder_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            fDialogHolder[0].dismiss();
            List<File> roots = new ArrayList<>();
            for (String d : fDirs) {
                roots.add(new File(d));
            }
            GlobalImportScanner.scanDirsAsync(roots,
                    new GlobalImportScanner.Listener() {
                        @Override public void onFound(GlobalImportScanner.Candidate c) {}
                        @Override public void onProgress(String path) {}
                        @Override public void onDone(List<GlobalImportScanner.Candidate> result) {
                            activity.runOnUiThread(() -> {
                                if (listener != null) {
                                    listener.onScanDone(result);
                                }
                            });
                        }
                    });
        });

        // v0.4.189：二级菜单尺寸同导入菜单（DialogSizer 560dp）
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    /** 应用空实例的输入：校验目录存在 → 加入列表并重画；失败提示。 */
    private static boolean applyNewFolder(Context activity, List<String> dirs,
                                          EditText input, Runnable redraw) {
        String path = input.getText().toString().trim();
        if (path.isEmpty()) {
            return false;
        }
        File f = new File(path);
        if (!f.isDirectory()) {
            Toast.makeText(activity, R.string.scan_folder_not_dir, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!dirs.contains(path)) {
            dirs.add(path);
        }
        saveFolders(activity, dirs);
        redraw.run();
        return true;
    }
}
