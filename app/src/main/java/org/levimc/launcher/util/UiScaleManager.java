package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 全局 UI 自适应缩放：
 * 1) 手机（最小边 < 600dp）自动整体缩小 0.85，避免按平板设计的界面溢出；
 * 2) 用户在个性化里设置的 UI 大小 / 字体大小倍率（对所有设备生效）。
 * 只作用于继承 BaseActivity 的启动器界面，游戏进程 MinecraftActivity
 * 继承 com.mojang.minecraftpe.MainActivity，不经过此处，不受影响。
 */
public final class UiScaleManager {
    /** 手机上整体缩放的倍率（0.85 = 缩小 15%）。
     *  v651：v650 试过 0.72（用户结论：全局缩放治标不治本，且波及平板
     *  个性化观感）——回退 0.85，改为对具体页面做手机专属布局优化。 */
    public static final float PHONE_SCALE = 0.85f;
    /** 最小边（dp）达到该值视为平板，不再自动缩小。 */
    public static final int TABLET_MIN_SW_DP = 600;

    private UiScaleManager() {
    }

    public static Context applyScale(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density;
        int smallestDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / density);

        // 用户设置（个性化 → UI 大小 / 字体大小），重启后生效
        SharedPreferences prefs = context.getSharedPreferences(
                "personalization_prefs", Context.MODE_PRIVATE);
        float userUiScale = clamp(prefs.getFloat("ui_scale",
                PersonalizationManager.UI_SCALE_DEFAULT),
                PersonalizationManager.UI_SCALE_MIN, PersonalizationManager.UI_SCALE_MAX);
        float userFontScale = clamp(prefs.getFloat("font_scale",
                PersonalizationManager.FONT_SCALE_DEFAULT),
                PersonalizationManager.FONT_SCALE_MIN, PersonalizationManager.FONT_SCALE_MAX);

        float autoScale = smallestDp >= TABLET_MIN_SW_DP ? 1.0f : PHONE_SCALE;
        if (autoScale == 1.0f && userUiScale == 1.0f && userFontScale == 1.0f) {
            return context;
        }

        Configuration config = new Configuration(context.getResources().getConfiguration());
        // densityDpi / DENSITY_DEFAULT 即新的 density；scaledDensity 会同步按比例变化，
        // 因此 dp（控件尺寸）与 sp（文字）一起缩放；fontScale 再单独叠加到文字上。
        float targetDensity = density * autoScale * userUiScale;
        config.densityDpi = Math.round(targetDensity * DisplayMetrics.DENSITY_DEFAULT);
        config.fontScale = userFontScale;
        return context.createConfigurationContext(config);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
