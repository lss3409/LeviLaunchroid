package org.levimc.launcher.util;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * v552：弹窗尺寸统一规范——所有 Dialog 共用的宽度/高度计算。
 *
 * 规范（用户定稿）：以平板比例为主设计（idealDp 是平板上的理想内容宽度），
 * 手机（最小边 < 600dp）自动按 {@link UiScaleManager#PHONE_SCALE} 缩小；
 * 用户在个性化里设置的 ui_scale 已通过 densityDpi 生效（dp 物理尺寸联动），
 * 弹窗随之等比缩放，无需额外处理。
 *
 * 宽度 = min(屏幕宽 × 90%, 理想宽 dp × density × 手机缩小系数)，
 * 保证弹窗刚好包住内容、永不超过屏幕。
 */
public final class DialogSizer {

    private DialogSizer() {
    }

    /** 按规范计算弹窗宽度（px）。idealDp：平板上的理想内容宽度。
     *  v557：设备缩放（手机 0.85 autoScale + 用户 ui_scale）已由
     *  UiScaleManager.applyScale 写进 densityDpi——弹窗 dp 自动联动，
     *  不再叠加固定 0.85（此前双重缩小导致弹窗过窄）；
     *  用户把个性化缩放调小 = densityDpi 变小 = 弹窗物理变小、
     *  屏占比更接近平板布局。 */
    public static int dialogWidth(Context ctx, int idealDp) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int ideal = (int) (idealDp * dm.density);
        int screenCap = (int) (dm.widthPixels * 0.9f);
        return Math.min(screenCap, ideal);
    }

    /** 弹窗内容最大高度（px）：屏幕高 78%，防按钮被挤出屏幕（手机尤其）。 */
    public static int dialogMaxHeight(Context ctx) {
        return (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.78f);
    }

    /** 是否手机（与 UiScaleManager 同口径，最小边 < 600dp）。 */
    public static boolean isPhone(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int smallestDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / dm.density);
        return smallestDp < UiScaleManager.TABLET_MIN_SW_DP;
    }
}
