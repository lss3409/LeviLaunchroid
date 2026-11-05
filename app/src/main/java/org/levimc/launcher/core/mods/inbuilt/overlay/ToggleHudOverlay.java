package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.view.KeyEvent;
import android.widget.ImageButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

public class ToggleHudOverlay extends BaseOverlayButton {
<<<<<<< HEAD
    /** v0.4.211（官方 5bf4c980 跟进）：F1 是否已隐藏游戏 HUD。 */
=======
>>>>>>> v1.6.0
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
        return isActive ? R.drawable.ic_toggle_hud_pressed : R.drawable.ic_toggle_hud_normal;
    }

    @Override
    protected void onButtonClick() {
        isActive = !isActive;
        sendKey(KeyEvent.KEYCODE_F1);
<<<<<<< HEAD
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
=======
        InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
        if (manager != null) manager.setLauncherHudHidden(isActive);
        activity.runOnUiThread(this::updateButtonState);
>>>>>>> v1.6.0
    }

    @Override
    public void hide() {
        if (isActive) {
            isActive = false;
            sendKey(KeyEvent.KEYCODE_F1);
            InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
            if (manager != null) manager.setLauncherHudHidden(false);
        }
        super.hide();
    }

    private void updateButtonState() {
        if (overlayView instanceof ImageButton) {
            ImageButton btn = (ImageButton) overlayView;
            btn.setAlpha(getButtonOpacity());
            btn.setImageResource(isActive
                    ? R.drawable.ic_toggle_hud_pressed
                    : R.drawable.ic_toggle_hud_normal);
        }
    }
}
