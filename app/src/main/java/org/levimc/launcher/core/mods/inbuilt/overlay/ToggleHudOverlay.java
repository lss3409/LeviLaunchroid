package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.view.KeyEvent;

import org.levimc.launcher.R;

import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

public class ToggleHudOverlay extends BaseOverlayButton {
    /** v0.4.211（官方 5bf4c980 跟进）：F1 是否已隐藏游戏 HUD。 */
    private boolean isActive = false;

    public ToggleHudOverlay(Activity activity) {
        super(activity);
    }

    @Override
    protected String getModId() {
        return ModIds.TOGGLE_HUD;
    }

    @Override
    protected int getIconResource() {
        return R.drawable.ic_hud;
    }

    @Override
    protected void onButtonClick() {
        isActive = !isActive;
        sendKey(KeyEvent.KEYCODE_F1);
        // 官方 5bf4c980：F1 隐藏游戏 HUD 时联动隐藏启动器悬浮球/覆盖按钮
        // （只留本按钮，方便再按回来）
        InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
        if (manager != null) {
            manager.setLauncherHudHidden(isActive);
        }
    }

    @Override
    public void hide() {
        if (isActive) {
            isActive = false;
            sendKey(KeyEvent.KEYCODE_F1);
            InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
            if (manager != null) {
                manager.setLauncherHudHidden(false);
            }
        }
        super.hide();
    }
}