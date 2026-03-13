package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

public class VirtualCursorOverlay extends BaseOverlayButton {

    public VirtualCursorOverlay(Activity activity) {
        super(activity);
    }

    @Override
    protected String getModId() {
        return ModIds.VIRTUAL_CURSOR;
    }

    @Override
    protected int getIconResource() {
        return R.drawable.ic_cursor_overlay;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.overlay_virtual_cursor;
    }

    @Override
    protected float getWidthScale() {
        return 1.8f;
    }

    @Override
    protected float getHeightScale() {
        return 0.65f;
    }

    @Override
    protected void onButtonClick() {
        boolean currentState = VirtualCursorMod.isActive();
        VirtualCursorMod.setActive(!currentState, activity);
        
        if (!currentState) {
            Toast.makeText(activity, activity.getString(R.string.virtual_cursor_enabled), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, activity.getString(R.string.virtual_cursor_disabled), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void hide() {
        if (VirtualCursorMod.isActive()) {
            VirtualCursorMod.setActive(false, activity);
            Toast.makeText(activity, activity.getString(R.string.virtual_cursor_disabled), Toast.LENGTH_SHORT).show();
        }
        super.hide();
    }
}