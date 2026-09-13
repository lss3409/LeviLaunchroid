package org.levimc.launcher.util;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 全局 UI 自适应缩放：启动器界面按平板尺寸设计，手机上固定 dp 值会显得过大。
 * 这里对手机（最小边 < 600dp）统一下调 density，让所有 dp/sp 按比例缩小；
 * 平板保持原样。只作用于继承 BaseActivity 的启动器界面，游戏进程 MinecraftActivity
 * 继承 com.mojang.minecraftpe.MainActivity，不经过此处，不受影响。
 */
public final class UiScaleManager {
    /** 手机上整体缩放的倍率（0.85 = 缩小 15%）。 */
    public static final float PHONE_SCALE = 0.85f;
    /** 最小边（dp）达到该值视为平板，不再缩放。 */
    public static final int TABLET_MIN_SW_DP = 600;

    private UiScaleManager() {
    }

    public static Context applyScale(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density;
        int smallestDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / density);
        if (smallestDp >= TABLET_MIN_SW_DP) {
            return context;
        }

        Configuration config = new Configuration(context.getResources().getConfiguration());
        // densityDpi / DENSITY_DEFAULT 即新的 density；scaledDensity 会同步按比例缩小，
        // 因此 dp（控件尺寸）与 sp（文字）一起变小。
        float targetDensity = density * PHONE_SCALE;
        config.densityDpi = Math.round(targetDensity * DisplayMetrics.DENSITY_DEFAULT);
        return context.createConfigurationContext(config);
    }
}
