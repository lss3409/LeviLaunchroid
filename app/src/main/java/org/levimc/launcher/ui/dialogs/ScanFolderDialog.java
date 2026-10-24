package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
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

import org.json.JSONArray;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;
import org.levimc.launcher.util.AccentStyler;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/** v0.4.178：扫描指定文件夹——内容管理导入的补充入口（贴图按钮）。
 *  多选文件夹持久化（JSON），添加走「自定义存储路径」样式输入框
 *  （空文本+hint 示例）+ SAF 文件管理器选择；创建/删除交互参照
 *  自定义超平坦方案（列表条目+移除按钮）。扫描复用
 *  GlobalImportScanner.scanDirsAsync，结果进 foundSoFar 供
 *  ImportPickerDialog 读取。 */
public final class ScanFolderDialog {

    private static final String PREFS = "import_scan_folders";
    private static final String KEY_DIRS = "dirs";

    public interface OnScanListener {
        void onScanDone(List<GlobalImportScanner.Candidate> result);
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

    /** 打开指定文件夹管理弹窗；扫描完成后回调结果并自动关闭。 */
    public static void show(androidx.activity.ComponentActivity activity, OnScanListener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        List<String> dirs = new ArrayList<>(loadFolders(activity));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(pad, 0, pad, 0);

        TextView hint = new TextView(activity);
        hint.setText(R.string.scan_folder_hint);
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        hint.setTextSize(13f);
        hint.setPadding(0, pad, 0, pad);
        root.addView(hint);

        // 文件夹列表（ScrollView + 动态条目，超平坦式条目+删除）
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 列表限高：条目多时滚动（弹窗限高同理）
        scroll.getLayoutParams();
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (220 * activity.getResources().getDisplayMetrics().density)));

        final List<String> fDirs = dirs;
        final LinearLayout fList = list;
        final CustomAlertDialog[] dialogRef = new CustomAlertDialog[1];

        final Runnable[] redrawRef = new Runnable[1];
        redrawRef[0] = () -> {
            fList.removeAllViews();
            for (int i = 0; i < fDirs.size(); i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView path = new TextView(activity);
                path.setText(fDirs.get(idx));
                path.setTextColor(activity.getColor(R.color.on_surface));
                path.setTextSize(13f);
                path.setPadding(0, (int) (6 * activity.getResources().getDisplayMetrics().density), 0,
                        (int) (6 * activity.getResources().getDisplayMetrics().density));
                ImageButton del = new ImageButton(activity);
                del.setImageResource(R.drawable.ic_delete);
                del.setBackgroundResource(android.R.attr.borderlessButtonStyle);
                del.setColorFilter(0xFF8A8A8A);
                del.setContentDescription(activity.getString(R.string.scan_folder_remove));
                del.setOnClickListener(v -> {
                    fDirs.remove(idx);
                    saveFolders(activity, fDirs);
                    redrawRef[0].run();
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
                empty.setPadding(0, (int) (8 * activity.getResources().getDisplayMetrics().density), 0, 0);
                fList.addView(empty);
            }
        };
        redrawRef[0].run();

        // 底部操作行：添加 + 开始扫描（MaterialButton + AccentStyler）
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, pad, 0, 0);
        MaterialButton add = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonStyle);
        add.setText(R.string.scan_folder_add);
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

        add.setOnClickListener(v -> showAddDialog(activity, fDirs, save -> {
            if (save) {
                saveFolders(activity, fDirs);
                redrawRef[0].run();
            }
        }));

        scan.setOnClickListener(v -> {
            if (fDirs.isEmpty()) {
                Toast.makeText(activity, R.string.scan_folder_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            // 结果给 ImportPickerDialog 的缓存/实时读取通道
            GlobalImportScanner.scanDirsAsync(toFiles(fDirs),
                    new GlobalImportScanner.Listener() {
                        @Override
                        public void onFound(GlobalImportScanner.Candidate c) {
                        }

                        @Override
                        public void onProgress(String path) {
                        }

                        @Override
                        public void onDone(List<GlobalImportScanner.Candidate> result) {
                            activity.runOnUiThread(() -> {
                                if (listener != null) {
                                    listener.onScanDone(result);
                                }
                            });
                        }
                    });
        });

        CustomAlertDialog dialog = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.scan_folder_title))
                .setCustomView(root)
                .setNegativeButton(activity.getString(R.string.cancel), null);
        dialogRef[0] = dialog;
        dialog.show();
    }

    private static List<File> toFiles(List<String> dirs) {
        List<File> out = new ArrayList<>();
        for (String d : dirs) {
            out.add(new File(d));
        }
        return out;
    }

    /** 添加文件夹弹窗：自定义存储路径样式输入框（空文本+hint 示例）
     *  + SAF 文件管理器选择 + 添加。 */
    private static void showAddDialog(androidx.activity.ComponentActivity activity, List<String> dirs,
                                      java.util.function.Consumer<Boolean> onSaved) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(activity);
        input.setHint(R.string.scan_folder_input_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(13f);
        input.setTextColor(activity.getColor(R.color.on_surface));
        input.setHintTextColor(activity.getColor(R.color.text_secondary));
        root.addView(input);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, pad, 0, 0);
        MaterialButton pick = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonStyle);
        pick.setText(R.string.scan_folder_pick);
        pick.setAllCaps(false);
        pick.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        AccentStyler.styleSecondary(activity, pick);
        MaterialButton confirm = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonStyle);
        confirm.setText(R.string.scan_folder_add);
        confirm.setAllCaps(false);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cLp.setMarginStart(pad / 2);
        confirm.setLayoutParams(cLp);
        AccentStyler.stylePrimary(activity, confirm);
        row.addView(pick);
        row.addView(confirm);
        root.addView(row);

        CustomAlertDialog dialog = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.scan_folder_add_title))
                .setCustomView(root)
                .setNegativeButton(activity.getString(R.string.cancel), null);
        dialog.show();

        final CustomAlertDialog fDialog = dialog;
        pick.setOnClickListener(v -> {
            // SAF 文档树选择（primary: 段解析为主存储真实路径）
            try {
                ActivityResultLauncher<Intent> launcher =
                        activity.registerForActivityResult(
                                new ActivityResultContracts.StartActivityForResult(),
                                result -> {
                                    Intent data = result.getData();
                                    if (data != null && data.getData() != null) {
                                        String path = safTreeToPath(data.getData());
                                        if (path != null) {
                                            input.setText(path);
                                        }
                                    }
                                });
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launcher.launch(intent);
            } catch (Exception e) {
                Toast.makeText(activity, R.string.scan_folder_pick_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
        confirm.setOnClickListener(v -> {
            String path = input.getText().toString().trim();
            if (path.isEmpty()) {
                Toast.makeText(activity, R.string.scan_folder_input_hint,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            File f = new File(path);
            if (!f.isDirectory()) {
                Toast.makeText(activity, R.string.scan_folder_not_dir,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!dirs.contains(path)) {
                dirs.add(path);
            }
            fDialog.dismiss();
            onSaved.accept(true);
        });
    }

    /** SAF 树 uri → 真实路径（主存储 primary:Download%2Fmods →
     *  /storage/emulated/0/Download/mods；解析不了返回 null 手输兜底）。 */
    private static String safTreeToPath(Uri uri) {
        try {
            String seg = uri.getLastPathSegment();
            if (seg == null || !seg.startsWith("primary:")) {
                return null;
            }
            String rel = URLDecoder.decode(seg.substring("primary:".length()), "UTF-8");
            String base = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            return base + "/" + rel;
        } catch (Exception e) {
            return null;
        }
    }
}
