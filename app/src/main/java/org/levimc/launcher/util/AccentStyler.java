package org.levimc.launcher.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;

import com.google.android.material.button.MaterialButton;

/**
 * v537：个性化强调色按钮样式统一工具（重写版）。
 *
 * 此前问题：依赖 PersonalizationManager.applyAccentColorRecursive 的接管条件
 * （getBackgroundTintList() != null 且原色 == primary）——MaterialButton 的
 * getBackgroundTintList 可能返回 null，导致主按钮漏染、保持默认主题色。
 *
 * 现在：主操作按钮一律无条件染成用户强调色，并带按压态加深。
 * 调用方：页面按钮在 onCreate 调用；弹窗视图（不在 Activity 树内）在
 * 弹窗创建后对弹窗内按钮调用。
 */
public final class AccentStyler {

    private AccentStyler() {
    }

    /** 主操作按钮：强调色背景 + 白字 + 按压加深（一次性染多个）。 */
    public static void stylePrimary(Context ctx, View... buttons) {
        if (buttons == null) {
            return;
        }
        int accent = new PersonalizationManager(ctx).getAccentColor();
        int pressed = darken(accent, 0.85f);
        int[][] states = {{android.R.attr.state_pressed}, {}};
        ColorStateList csl = new ColorStateList(states, new int[]{pressed, accent});
        for (View v : buttons) {
            if (v instanceof MaterialButton) {
                MaterialButton b = (MaterialButton) v;
                b.setBackgroundTintList(csl);
                b.setTextColor(Color.WHITE);
            }
        }
    }

    /** 次要按钮（TextButton）：文字染强调色，背景透明。 */
    public static void styleSecondary(Context ctx, View... buttons) {
        if (buttons == null) {
            return;
        }
        int accent = new PersonalizationManager(ctx).getAccentColor();
        for (View v : buttons) {
            if (v instanceof MaterialButton) {
                MaterialButton b = (MaterialButton) v;
                b.setTextColor(accent);
            } else if (v instanceof android.widget.TextView) {
                ((android.widget.TextView) v).setTextColor(accent);
            }
        }
    }

    private static int darken(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }
}
