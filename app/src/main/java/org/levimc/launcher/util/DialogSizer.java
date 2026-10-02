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

    /** v0.0.14：手机弹窗宽度占屏比（用户逐版微调定稿 75%）。 */
    private static final float PHONE_WIDTH_RATIO = 0.75f;

    private DialogSizer() {
    }

    /** 按规范计算弹窗宽度（px）。idealDp：平板上的理想内容宽度。
     *  v557：设备缩放（手机 0.85 autoScale + 用户 ui_scale）已由
     *  UiScaleManager.applyScale 写进 densityDpi——弹窗 dp 自动联动，
     *  不再叠加固定 0.85（此前双重缩小导致弹窗过窄）；
     *  用户把个性化缩放调小 = densityDpi 变小 = 弹窗物理变小、
     *  屏占比更接近平板布局。
     *  v612：Material 3 规范——大屏（非手机）对话框距屏幕边缘至少
     *  56dp（expanded breakpoint 留空余），不再贴满屏幕；手机保持
     *  90% 屏宽（compact breakpoint）。 */
    public static int dialogWidth(Context ctx, int idealDp) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int ideal = (int) (idealDp * dm.density);
        if (isPhone(ctx)) {
            // 手机：按屏宽比例（PHONE_WIDTH_RATIO）。
            // 内容根视图 WRAP_CONTENT 不会强撑满。
            return Math.max(0, (int) (dm.widthPixels * PHONE_WIDTH_RATIO));
        }
        // 大屏：宽度 = min(理想宽, 屏宽 − 56dp×2 边距)
        int marginCap = (int) (dm.widthPixels - 56 * dm.density * 2);
        return Math.min(ideal, Math.max(0, marginCap));
    }

    /** 弹窗内容最大高度（px）：屏幕高 78%，防按钮被挤出屏幕（手机尤其）。 */
    public static int dialogMaxHeight(Context ctx) {
        return (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.78f);
    }

    /** v0.0.15：手机弹窗自适应宽度的封顶值（px）——内容少包住内容、
     *  内容多封顶到屏宽 75%（内部换行/滚动）。平板沿用 56dp 边距封顶。 */
    public static int dialogMaxWidthPx(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (isPhone(ctx)) {
            return (int) (dm.widthPixels * PHONE_WIDTH_RATIO);
        }
        return Math.max(0, (int) (dm.widthPixels - 56 * dm.density * 2));
    }

    /** v0.0.15：手机弹窗最小宽度（px）——Material 3 规范 280dp，避免内容
     *  太少时窗口窄得难看。 */
    public static int dialogMinWidthPx(Context ctx) {
        return (int) (280 * ctx.getResources().getDisplayMetrics().density);
    }

    /** 是否手机（最小边 < 800dp）。
     *  v647：原 600dp 阈值会把大屏手机误判成平板——vivo V2536A 最小边
     *  ≈710dp，横屏时走大屏分支弹窗恒 560dp 宽（只占屏宽 36%，用户截图
     *  反馈"比例不正确"）。平板最小边 ≥960dp（12 寸 1280dp），800 阈值
     *  两边余量充足。注意与 UiScaleManager 的 600dp 口径刻意不同——
     *  后者管 UI 缩放系数，本类只管弹窗宽度。 */
    public static boolean isPhone(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int smallestDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / dm.density);
        return smallestDp < 800;
    }
}
