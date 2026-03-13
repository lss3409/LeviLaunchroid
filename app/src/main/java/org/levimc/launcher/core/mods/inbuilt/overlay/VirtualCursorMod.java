package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.view.InputDevice;
import android.view.MotionEvent;

public class VirtualCursorMod {
    private static boolean active = false;
    private static float cursorX = 0;
    private static float cursorY = 0;
    
    private static android.widget.ImageView cursorView;
    private static android.view.WindowManager windowManager;
    private static android.view.WindowManager.LayoutParams cursorParams;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean isActive, Activity activity) {
        active = isActive;
        if (active) {
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            if (cursorX == 0 && cursorY == 0) {
                cursorX = metrics.widthPixels / 2f;
                cursorY = metrics.heightPixels / 2f;
            }
        }
        setCursorVisible(active, activity);
    }

    private static void setCursorVisible(boolean visible, Activity activity) {
        if (visible) {
            if (cursorView == null && activity != null) {
                windowManager = (android.view.WindowManager) activity.getSystemService(android.content.Context.WINDOW_SERVICE);
                cursorView = new android.widget.ImageView(activity);
                cursorView.setImageResource(org.levimc.launcher.R.drawable.ic_virtual_cursor);
                
                int size = (int) (24 * activity.getResources().getDisplayMetrics().density);
                cursorParams = new android.view.WindowManager.LayoutParams(
                        size, size,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT
                );
                cursorParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                cursorParams.x = (int) cursorX;
                cursorParams.y = (int) cursorY;
                cursorParams.token = activity.getWindow().getDecorView().getWindowToken();

                try {
                    windowManager.addView(cursorView, cursorParams);
                } catch (Exception ignored) {}
            }
        } else {
            if (cursorView != null && windowManager != null) {
                try {
                    windowManager.removeView(cursorView);
                } catch (Exception ignored) {}
                cursorView = null;
                windowManager = null;
            }
        }
    }

    public static void processTouchEvent(MotionEvent event, Activity activity) {
        if (!active) return;

        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float screenWidth = metrics.widthPixels;
        float screenHeight = metrics.heightPixels;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getHistorySize() > 0) {
                    float dx = event.getX() - event.getHistoricalX(0);
                    float dy = event.getY() - event.getHistoricalY(0);
                    
                    float sensitivity = org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager.getInstance(activity).getCursorSensitivity() / 100f;
                    
                    cursorX += dx * sensitivity;
                    cursorY += dy * sensitivity;

                    // Clamp to screen
                    cursorX = Math.max(0, Math.min(cursorX, screenWidth));
                    cursorY = Math.max(0, Math.min(cursorY, screenHeight));

                    if (cursorView != null && windowManager != null) {
                        cursorParams.x = (int) cursorX;
                        cursorParams.y = (int) cursorY;
                        windowManager.updateViewLayout(cursorView, cursorParams);
                    }

                    sendHoverMove(activity, cursorX, cursorY, event.getEventTime());
                }
                break;
            case MotionEvent.ACTION_UP:
                long duration = event.getEventTime() - event.getDownTime();
                if (duration < 200) {
                    sendClick(activity, cursorX, cursorY, event.getEventTime());
                }
                break;
        }
    }

    private static void sendHoverMove(Activity activity, float x, float y, long time) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;

        MotionEvent hoverEvent = MotionEvent.obtain(
                time, time,
                MotionEvent.ACTION_HOVER_MOVE,
                1, properties, coords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
        );

        try {
            if (activity instanceof org.levimc.launcher.core.minecraft.MinecraftActivity) {
                ((org.levimc.launcher.core.minecraft.MinecraftActivity) activity).dispatchGenericMotionEventToGame(hoverEvent);
            }
        } catch (Exception ignored) {}
        hoverEvent.recycle();
    }

    private static void sendClick(Activity activity, float x, float y, long time) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;

        MotionEvent downEvent = MotionEvent.obtain(
                time, time,
                MotionEvent.ACTION_DOWN,
                1, properties, coords,
                0, MotionEvent.BUTTON_PRIMARY, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
        );

        try {
            if (activity instanceof org.levimc.launcher.core.minecraft.MinecraftActivity) {
                ((org.levimc.launcher.core.minecraft.MinecraftActivity) activity).dispatchTouchEventToGame(downEvent);
            }
        } catch (Exception ignored) {}
        downEvent.recycle();

        MotionEvent upEvent = MotionEvent.obtain(
                time, time + 10,
                MotionEvent.ACTION_UP,
                1, properties, coords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
        );

        try {
            if (activity instanceof org.levimc.launcher.core.minecraft.MinecraftActivity) {
                ((org.levimc.launcher.core.minecraft.MinecraftActivity) activity).dispatchTouchEventToGame(upEvent);
            }
        } catch (Exception ignored) {}
        upEvent.recycle();
    }
}
