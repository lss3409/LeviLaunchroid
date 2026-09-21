package org.levimc.launcher.ui.dialogs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
import org.levimc.launcher.core.content.nbt.NbtTag;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * v463：权限编辑弹窗（⋮ 菜单「编辑」入口，只做权限）——
 * 读 level.dat 的 PermissionsLevel / PlayerPermissionsLevel，
 * 胶囊选择后写回（写前备份 level.dat.bak）。字段不存在则禁用。
 */
public final class PermissionEditDialog {
    private static final String TAG = "PermissionEdit";

    private PermissionEditDialog() {
    }

    public static void show(Context context, File worldDir) {
        File levelDat = new File(worldDir, "level.dat");
        if (!levelDat.isFile()) {
            Toast.makeText(context, R.string.level_dat_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                NbtTag root = new BedrockNbtReader().readFile(levelDat);
                if (root == null || root.getType() != NbtTag.TAG_COMPOUND) {
                    showToast(context, R.string.nbt_no_data);
                    return;
                }
                Map<String, NbtTag> compound = root.getCompound();
                int perm = readInt(compound, "PermissionsLevel", -1);
                int pperm = readInt(compound, "PlayerPermissionsLevel", -1);
                ((android.app.Activity) context).runOnUiThread(() ->
                        showDialog(context, worldDir, compound, perm, pperm));
            } catch (Exception e) {
                Log.w(TAG, "读取 level.dat 失败", e);
                showToast(context, R.string.nbt_no_data);
            } finally {
                exec.shutdown();
            }
        });
    }

    private static void showToast(Context c, int res) {
        ((android.app.Activity) c).runOnUiThread(() ->
                Toast.makeText(c, res, Toast.LENGTH_SHORT).show());
    }

    private static void showDialog(Context context, File worldDir,
                                   Map<String, NbtTag> compound,
                                   int perm, int pperm) {
        View panel = android.view.LayoutInflater.from(context)
                .inflate(R.layout.permission_edit_dialog, null, false);
        TextView[] capsPerm = {
                panel.findViewById(R.id.caps_perm_0),
                panel.findViewById(R.id.caps_perm_1),
                panel.findViewById(R.id.caps_perm_2)};
        TextView[] capsPperm = {
                panel.findViewById(R.id.caps_pperm_0),
                panel.findViewById(R.id.caps_pperm_1),
                panel.findViewById(R.id.caps_pperm_2),
                panel.findViewById(R.id.caps_pperm_3)};

        int accent = new PersonalizationManager(context).getAccentColor();

        // 权限等级：0 访客 / 1 成员 / 2 操作员（缺省按成员显示但禁用）
        boolean hasPerm = perm >= 0;
        selectCapsule(context, capsPerm, Math.max(0, Math.min(2, hasPerm ? perm : 1)), accent);
        bindCapsuleGroup(context, capsPerm, accent);
        setRowEnabled(capsPerm, hasPerm);

        // 玩家权限等级：0 访客 / 1 成员 / 2 操作员 / 3 自定义
        boolean hasPperm = pperm >= 0;
        selectCapsule(context, capsPperm, Math.max(0, Math.min(3, hasPperm ? pperm : 1)), accent);
        bindCapsuleGroup(context, capsPperm, accent);
        setRowEnabled(capsPperm, hasPperm);

        new CustomAlertDialog(context)
                .setTitleText(context.getString(R.string.nbt_edit_permissions))
                .setCustomView(panel)
                .setPositiveButton(context.getString(R.string.nbt_edit_save), v -> {
                    if (!hasPerm && !hasPperm) {
                        Toast.makeText(context, R.string.nbt_no_data,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    save(context, worldDir, compound,
                            hasPerm ? selectedCapsule(capsPerm) : -1,
                            hasPperm ? selectedCapsule(capsPperm) : -1);
                })
                .setNegativeButton(context.getString(R.string.nbt_edit_cancel), null)
                .show();
    }

    private static void save(Context context, File worldDir, Map<String, NbtTag> compound,
                             int perm, int pperm) {
        File levelDat = new File(worldDir, "level.dat");
        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                File backup = new File(worldDir, "level.dat.bak");
                copyFile(levelDat, backup);
                if (perm >= 0) {
                    compound.put("PermissionsLevel", new NbtTag(NbtTag.TAG_INT,
                            "PermissionsLevel", perm));
                }
                if (pperm >= 0) {
                    compound.put("PlayerPermissionsLevel", new NbtTag(NbtTag.TAG_INT,
                            "PlayerPermissionsLevel", pperm));
                }
                NbtTag root = new NbtTag(NbtTag.TAG_COMPOUND, "", compound);
                BedrockNbtWriter writer = new BedrockNbtWriter();
                writer.setHeaderVersion(10);
                writer.writeFile(levelDat, root);
                showToast(context, R.string.nbt_edit_saved);
                Log.i(TAG, "权限已写回: " + levelDat.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "权限写回失败", e);
                ((android.app.Activity) context).runOnUiThread(() ->
                        Toast.makeText(context,
                                context.getString(R.string.nbt_edit_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show());
            } finally {
                exec.shutdown();
            }
        });
    }

    private static void bindCapsuleGroup(Context c, TextView[] caps, int accent) {
        for (TextView cap : caps) {
            cap.setOnClickListener(v -> {
                for (int i = 0; i < caps.length; i++) {
                    if (caps[i] == cap) {
                        selectCapsule(c, caps, i, accent);
                        break;
                    }
                }
            });
            org.levimc.launcher.ui.animation.DynamicAnim.applyPressScale(cap);
        }
    }

    private static void selectCapsule(Context c, TextView[] caps, int index, int accent) {
        for (int i = 0; i < caps.length; i++) {
            boolean sel = i == index;
            caps[i].setBackgroundResource(sel
                    ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
            if (sel && accent != 0) {
                caps[i].setBackgroundTintList(ColorStateList.valueOf(accent));
            } else {
                caps[i].setBackgroundTintList(null);
            }
            caps[i].setTextColor(ContextCompat.getColor(c,
                    sel ? R.color.on_primary : R.color.text_secondary));
            caps[i].setTypeface(caps[i].getTypeface(), sel ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private static int selectedCapsule(TextView[] caps) {
        for (int i = 0; i < caps.length; i++) {
            if (caps[i].getTypeface() != null && caps[i].getTypeface().isBold()) {
                return i;
            }
        }
        return 0;
    }

    private static void setRowEnabled(TextView[] caps, boolean enabled) {
        for (TextView cap : caps) {
            cap.setEnabled(enabled);
            cap.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private static int readInt(Map<String, NbtTag> compound, String key, int def) {
        NbtTag tag = compound.get(key);
        return tag != null ? tag.getInt() : def;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
