package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;

public class ModMenuButton {

    private final Activity activity;
    private View buttonView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;
    
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private long touchDownTime = 0;
    private static final long TAP_TIMEOUT = 200;
    private static final float DRAG_THRESHOLD = 10f;
    /** v443：贴边半隐藏比例——吸附后内容向屏外平移 50%（谷歌
     *  AccessibilityFloatingMenuView 方案：窗口位置保持屏内，内容
     *  translationX 移出窗口边界被 Surface 裁剪 = 视觉半隐藏；
     *  负 x 出屏会被 ROM 钳制所以不能用）。 */
    private static final float EDGE_HIDE_RATIO = 0.5f;
    /** v443：半隐藏后的淡出透明度（谷歌 fadeOut 同款——3 秒不操作
     *  淡到半透明，触碰恢复）。 */
    private static final float FADE_OUT_ALPHA_SCALE = 0.55f;
    private static final long FADE_OUT_DELAY_MS = 3000;
    private android.animation.ValueAnimator snapAnimator;
    private boolean edgeHidden = false;
    private int edgeSide = 0; // -1 左 / 1 右 / 0 未贴边
    private final Runnable fadeOutRunnable = () -> {
        if (buttonView == null) {
            return;
        }
        buttonView.animate().alpha(applyBaseOpacity() * FADE_OUT_ALPHA_SCALE)
                .setDuration(300).start();
    };
    
    private ModMenuOverlay menuOverlay;
    
    public ModMenuButton(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }
    
    public void show(int startX, int startY) {
        if (isShowing) return;
        handler.postDelayed(() -> showInternal(startX, startY), 500);
    }
    
    private void showInternal(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;

        try {
            buttonView = LayoutInflater.from(activity).inflate(R.layout.overlay_mod_menu_button, null);
            ImageButton btn = buttonView.findViewById(R.id.mod_menu_fab);

            float density = activity.getResources().getDisplayMetrics().density;
            int buttonSize = (int) (53 * density);

            wmParams = new WindowManager.LayoutParams(
                buttonSize,
                buttonSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
            );
            wmParams.gravity = Gravity.TOP | Gravity.START;
            // v475：SHORT_EDGES——vivo 手机横屏时系统把刘海安全区
            // （实测 126px）套到面板子窗口上，x=0 实际渲染在 126px
            // 处（dumpsys: display=[126,0][2750,1260]）→ 吸左缘永远
            // 留一条缝、右缘正常（右侧无安全区）。ModMenuOverlay
            // 已有同款设置，这里补上。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                wmParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            // v442：恢复上次吸附的位置（重启/重进后球还在老地方）
            int[] saved = loadBallPosition();
            wmParams.x = saved != null ? saved[0] : startX;
            wmParams.y = saved != null ? saved[1] : startY;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            
            btn.setOnTouchListener(this::handleTouch);
            windowManager.addView(buttonView, wmParams);
            isShowing = true;
            applyOpacity();
        } catch (Exception e) {
            showFallback(startX, startY);
        }
    }
    
    private void showFallback(int startX, int startY) {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        
        buttonView = LayoutInflater.from(activity).inflate(R.layout.overlay_mod_menu_button, null);
        ImageButton btn = buttonView.findViewById(R.id.mod_menu_fab);
        
        float density = activity.getResources().getDisplayMetrics().density;
        int buttonSize = (int) (48 * density);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = startX;
        params.topMargin = startY;
        
        btn.setOnTouchListener(this::handleTouchFallback);
        rootView.addView(buttonView, params);
        isShowing = true;
        wmParams = null;
        applyOpacity();
    }

    private void applyOpacity() {
        if (buttonView != null) {
            buttonView.setAlpha(applyBaseOpacity());
        }
    }

    /** v443：用户设置的悬浮球透明度（0-1）。 */
    private float applyBaseOpacity() {
        return InbuiltModManager.getInstance(activity).getModMenuButtonOpacity() / 100f;
    }

    private void applyButtonOpacity() {
        applyOpacity();
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 按下取消进行中的吸附动画（贴边后再次拖动立即跟手）
                if (snapAnimator != null && snapAnimator.isRunning()) {
                    snapAnimator.cancel();
                }
                // v443：触碰恢复——取消淡出、alpha 复原、贴边隐藏时
                // 先滑出（150ms）
                handler.removeCallbacks(fadeOutRunnable);
                if (buttonView != null) {
                    buttonView.animate().alpha(applyBaseOpacity())
                            .setDuration(150).start();
                    if (edgeHidden) {
                        buttonView.animate().translationX(0f)
                                .setDuration(150).start();
                    }
                }
                initialX = wmParams.x;
                initialY = wmParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging && windowManager != null && buttonView != null) {
                    if (edgeHidden) {
                        // 拖动时滑出全显再跟手
                        edgeHidden = false;
                        buttonView.setTranslationX(0f);
                    }
                    wmParams.x = (int) (initialX + dx);
                    wmParams.y = (int) (initialY + dy);
                    windowManager.updateViewLayout(buttonView, wmParams);
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    handler.post(this::onButtonClick);
                } else if (isDragging) {
                    // v442：松手吸附到最近屏幕边缘 + 半隐藏
                    snapToEdge();
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }

    /** v443：贴边半隐藏（谷歌方案）——内容向屏外平移一半被窗口
     *  Surface 裁剪，视觉只剩半个球；3 秒不操作淡到半透明。 */
    private void hideToEdge() {
        if (buttonView == null || edgeSide == 0) {
            return;
        }
        edgeHidden = true;
        int size = buttonView.getWidth();
        if (size <= 0) {
            size = wmParams != null ? wmParams.width : 0;
        }
        float offset = size * EDGE_HIDE_RATIO * (edgeSide < 0 ? -1f : 1f);
        buttonView.animate().translationX(offset)
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        handler.removeCallbacks(fadeOutRunnable);
        handler.postDelayed(fadeOutRunnable, FADE_OUT_DELAY_MS);
    }

    /** v442：吸附到最近左右边缘（窗口位置保持屏内——负 x 出屏会
     *  被 ROM 钳制），Y 钳制在屏幕内，250ms decelerate 动画；
     *  落定后半隐藏 + 位置持久化。
     *  v445：屏幕尺寸改用 DisplayMetrics（getCurrentWindowMetrics
     *  的 bounds 与 WM 坐标系统可能不一致——手机实测"到不了
     *  屏幕边缘"）；动画结束再做一次精确校正（消除插值舍入）。 */
    private void snapToEdge() {
        if (windowManager == null || buttonView == null || wmParams == null) {
            return;
        }
        try {
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int size = buttonView.getWidth();
            if (size <= 0) {
                size = wmParams.width;
            }
            int fromX = wmParams.x;
            int fromY = wmParams.y;
            // 球心判断左右：球心在屏幕左半 → 吸左边缘
            float centerX = wmParams.x + size / 2f;
            boolean snapLeft = centerX < screenW / 2f;
            edgeSide = snapLeft ? -1 : 1;
            int targetX = snapLeft ? 0 : screenW - size;
            int targetY = Math.max(0, Math.min(wmParams.y, screenH - size));
            snapAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            snapAnimator.setDuration(250);
            snapAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            snapAnimator.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                wmParams.x = (int) (fromX + (targetX - fromX) * t);
                wmParams.y = (int) (fromY + (targetY - fromY) * t);
                try {
                    windowManager.updateViewLayout(buttonView, wmParams);
                } catch (Exception ignored) {
                }
            });
            snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    // v445：精确校正——动画 int 插值可能停在距边缘
                    // 1-2px 处，直接设置目标值
                    wmParams.x = targetX;
                    wmParams.y = targetY;
                    try {
                        windowManager.updateViewLayout(buttonView, wmParams);
                    } catch (Exception ignored) {
                    }
                    saveBallPosition(targetX, targetY);
                    hideToEdge();
                }
            });
            snapAnimator.start();
        } catch (Throwable ignored) {
        }
    }

    /** v442：位置持久化（吸附落定后保存；show 时恢复）。 */
    private void saveBallPosition(int x, int y) {
        try {
            activity.getSharedPreferences("mod_menu_ball", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("x", x).putInt("y", y).apply();
        } catch (Throwable ignored) {
        }
    }

    /** v442：读保存位置（无则 null 用默认初始位置）。 */
    private int[] loadBallPosition() {
        try {
            android.content.SharedPreferences sp = activity
                    .getSharedPreferences("mod_menu_ball", android.content.Context.MODE_PRIVATE);
            if (sp.contains("x") && sp.contains("y")) {
                return new int[]{sp.getInt("x", 0), sp.getInt("y", 0)};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    
    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) buttonView.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = params.leftMargin;
                initialY = params.topMargin;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging) {
                    params.leftMargin = (int) (initialX + dx);
                    params.topMargin = (int) (initialY + dy);
                    buttonView.setLayoutParams(params);
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    handler.post(this::onButtonClick);
                } else if (isDragging) {
                    // v442：fallback 路径同样吸附边缘+半隐藏
                    snapToEdgeFallback();
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }

    /** v442：fallback（FrameLayout）路径的边缘吸附。 */
    private void snapToEdgeFallback() {
        if (buttonView == null) {
            return;
        }
        try {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) buttonView.getLayoutParams();
            android.graphics.Rect bounds = windowManager != null
                    ? windowManager.getCurrentWindowMetrics().getBounds()
                    : new android.graphics.Rect(0, 0,
                            activity.getResources().getDisplayMetrics().widthPixels,
                            activity.getResources().getDisplayMetrics().heightPixels);
            int screenW = bounds.width();
            int screenH = bounds.height();
            int size = buttonView.getWidth();
            if (size <= 0) {
                size = params.width;
            }
            int fromX = params.leftMargin;
            int fromY = params.topMargin;
            boolean snapLeft = params.leftMargin + size / 2f < screenW / 2f;
            edgeSide = snapLeft ? -1 : 1;
            // v443：窗口位置保持屏内（负 margin 出屏被 ROM 钳制）
            int targetX = snapLeft ? 0 : screenW - size;
            int targetY = Math.max(0, Math.min(params.topMargin, screenH - size));
            snapAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            snapAnimator.setDuration(250);
            snapAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            snapAnimator.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                params.leftMargin = (int) (fromX + (targetX - fromX) * t);
                params.topMargin = (int) (fromY + (targetY - fromY) * t);
                buttonView.setLayoutParams(params);
            });
            snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    saveBallPosition(targetX, targetY);
                    hideToEdge();
                }
            });
            snapAnimator.start();
        } catch (Throwable ignored) {
        }
    }

    private void onButtonClick() {
        // 悬浮球：打开模组菜单
        if (menuOverlay == null) {
            menuOverlay = new ModMenuOverlay(activity);
        }
        menuOverlay.show();
    }
    

    
    private void applyConfigurationChanges(String modId) {
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.applyConfigurationChanges(modId);
        }
    }
    
    public void hide() {
        if (menuOverlay != null) {
            menuOverlay.hide();
            menuOverlay = null;
        }
        if (!isShowing || buttonView == null) return;
        handler.post(() -> {
            try {
                if (wmParams != null && windowManager != null) {
                    windowManager.removeView(buttonView);
                } else {
                    ViewGroup rootView = activity.findViewById(android.R.id.content);
                    if (rootView != null) {
                        rootView.removeView(buttonView);
                    }
                }
            } catch (Exception ignored) {}
            buttonView = null;
            isShowing = false;
        });
    }
    
    public void setVisibility(int visibility) {
        if (buttonView != null) {
            buttonView.setVisibility(visibility);
        }
    }
    
    public boolean isShowing() {
        return isShowing;
    }

    public boolean isMenuShowing() {
        return menuOverlay != null && menuOverlay.isShowing();
    }

    public void hideMenu() {
        if (menuOverlay != null && menuOverlay.isShowing()) {
            menuOverlay.hide();
        }
    }
}
