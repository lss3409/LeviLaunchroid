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
    /** v0.4.155：窗口内容 wrapper——直接挂 WindowManager 的 View 会被
     *  WMS 按窗口尺寸 measure（拖动扩全屏时球撑满整屏 =「变大」根因），
     *  wrapper 随窗口伸缩、按钮 48dp 固定在内，平移走按钮 translation
     *  （谷歌 AccessibilityFloatingMenuView「大窗口+内容移动」同款）。 */
    private View wrapperView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;
    
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isDragging = false;
    // v0.4.160d 拖动传输延迟诊断：eventTime 是内核触摸时间戳，
    // uptimeMillis-eventTime = 事件从触摸面板到应用的传输耗时——
    // 「越拖越延迟」若在此增长=系统输入管线积压（应用之外）
    private long diagMoveCount;
    private long diagTransitHeadMs;
    private long diagTransitHeadN;
    private long diagTransitTailMs;
    private long diagTransitTailN;
    private long diagTransitMaxMs;
    private long diagEventGapHeadMs;
    private long diagEventGapTailMs;
    private long diagLastEventUptime;
    /** v0.4.154：拖动展开态——拖动开始把窗口临时扩成全屏，球位置
     *  全部走 translationX/Y（View 属性、RenderThread 合成、零 Binder）；
     *  松手再恢复窗口尺寸并落位。逐 MOVE 事件 updateViewLayout 的同步
     *  Binder relayout 在平板上积压 = 拖动帧率低不跟手（v0.4.153
     *  帧节流只是把更新频率降到帧率，同步等待仍在，未治本）。 */
    private boolean dragExpanded = false;
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
    /** v476：贴边长时间没人动 → 深度隐藏（只露 15% 边缘条 + 更淡）。
     *  用户要求"屏幕边缘长时间没人动也自动隐藏"；触碰边缘条即恢复。 */
    private static final float DEEP_HIDE_RATIO = 0.85f;
    private static final float DEEP_HIDE_ALPHA_SCALE = 0.25f;
    private static final long DEEP_HIDE_DELAY_MS = 15000;
    /** v479：菜单关闭后延迟重启隐藏链（给玩家留拖动窗口）。 */
    private static final long MENU_REHIDE_DELAY_MS = 3000;
    private android.animation.ValueAnimator snapAnimator;
    private boolean edgeHidden = false;
    /** v480：深度隐藏标记（与半隐藏区分——深度隐藏单击恢复成
     *  半隐藏，双击才打开菜单；半隐藏单击按原逻辑直接开菜单）。 */
    private boolean deepHidden = false;
    private int edgeSide = 0; // -1 左 / 1 右 / 0 未贴边
    /** v480：按下时球是否处于深度隐藏态（单击只恢复成半隐藏不
     *  打开菜单——双击第二下才打开）。 */
    private boolean deepOnDown = false;
    /** v479：菜单关闭后延迟重启隐藏链（给玩家留拖动窗口——
     *  关闭即吸附太小不好拖，用户要求留时间）。 */
    private final Runnable menuRehideRunnable = () -> {
        if (isShowing && buttonView != null && edgeSide != 0) {
            hideToEdge();
        }
    };
    private final Runnable fadeOutRunnable = () -> {
        if (buttonView == null) {
            return;
        }
        buttonView.animate().alpha(applyBaseOpacity() * FADE_OUT_ALPHA_SCALE)
                .setDuration(300).start();
    };
    /** v476：深度隐藏——贴边后 15 秒没人动，内容只露 15% + 淡到 25%。 */
    private final Runnable deepHideRunnable = () -> {
        if (buttonView == null || edgeSide == 0) {
            return;
        }
        int size = buttonView.getWidth();
        if (size <= 0) {
            size = wmParams != null ? wmParams.width : 0;
        }
        if (size <= 0) {
            return;
        }
        edgeHidden = true;
        deepHidden = true;
        float offset = size * DEEP_HIDE_RATIO * (edgeSide < 0 ? -1f : 1f);
        if (Math.abs(edgeSide) == 1) {
            buttonView.animate().translationX(offset)
                    .alpha(applyBaseOpacity() * DEEP_HIDE_ALPHA_SCALE)
                    .setDuration(250).start();
        } else {
            buttonView.animate().translationY(offset)
                    .alpha(applyBaseOpacity() * DEEP_HIDE_ALPHA_SCALE)
                    .setDuration(250).start();
        }
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
            // v477：恢复的位置若已在屏幕边缘——重新进入贴边半隐藏
            // +淡出链（v476 恢复时球全亮停在边缘，用户以为"吸附
            // 隐藏失效"）。等布局完成后再半隐藏（getWidth 需要）。
            // 四边判断：-1 左 / 1 右 / -2 顶 / 2 底
            final boolean restoredAtEdge;
            final int restoredEdgeSide;
            if (saved != null) {
                int w = activity.getResources().getDisplayMetrics().widthPixels;
                int h = activity.getResources().getDisplayMetrics().heightPixels;
                if (saved[0] <= 0) {
                    restoredAtEdge = true;
                    restoredEdgeSide = -1;
                } else if (saved[0] >= w - buttonSize) {
                    restoredAtEdge = true;
                    restoredEdgeSide = 1;
                } else if (saved[1] <= 0) {
                    restoredAtEdge = true;
                    restoredEdgeSide = -2;
                } else if (saved[1] >= h - buttonSize) {
                    restoredAtEdge = true;
                    restoredEdgeSide = 2;
                } else {
                    restoredAtEdge = false;
                    restoredEdgeSide = 0;
                }
            } else {
                restoredAtEdge = false;
                restoredEdgeSide = 0;
            }
            if (restoredAtEdge) {
                edgeSide = restoredEdgeSide;
            }

            btn.setOnTouchListener(this::handleTouch);
            // v0.4.155：wrapper 承载窗口尺寸（拖动扩全屏时按钮不撑大），
            // 按钮 48dp 固定在内；半隐藏 translation 由 wrapper 裁剪
            // （FrameLayout 默认 clipChildren），与原窗口 Surface 裁剪等效
            FrameLayout wrapper = new FrameLayout(activity);
            wrapper.addView(buttonView, new FrameLayout.LayoutParams(
                    buttonSize, buttonSize));
            wrapperView = wrapper;
            windowManager.addView(wrapper, wmParams);
            isShowing = true;
            applyOpacity();
            if (restoredAtEdge) {
                buttonView.post(this::hideToEdge);
            }
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

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 按下取消进行中的吸附动画（贴边后再次拖动立即跟手）
                if (snapAnimator != null && snapAnimator.isRunning()) {
                    snapAnimator.cancel();
                }
                // v0.4.154：同样取消贴边/隐藏的 view 动画——半隐藏
                // （translationX/Y 200ms）进行中按下拖动，动画每帧覆写
                // translation 与窗口位置叠加 = 拖动抖动不跟手主因
                if (buttonView != null) {
                    buttonView.animate().cancel();
                }
                // v443：触碰恢复——取消淡出、alpha 复原、贴边隐藏时
                // 先滑出（150ms）；v476：同样取消深度隐藏；
                // v479：取消菜单关闭后的延迟重隐藏（玩家开始拖动）
                // v480：深度隐藏单击只恢复成半隐藏（不滑全出），
                // 半隐藏单击按原逻辑滑全出（随后打开菜单）
                handler.removeCallbacks(fadeOutRunnable);
                handler.removeCallbacks(deepHideRunnable);
                handler.removeCallbacks(menuRehideRunnable);
                deepOnDown = deepHidden;
                if (buttonView != null) {
                    if (deepHidden) {
                        int size = buttonView.getWidth();
                        if (size <= 0) {
                            size = wmParams != null ? wmParams.width : 0;
                        }
                        float half = size * EDGE_HIDE_RATIO
                                * (edgeSide < 0 ? -1f : 1f);
                        if (Math.abs(edgeSide) == 1) {
                            buttonView.animate().translationX(half)
                                    .alpha(applyBaseOpacity())
                                    .setDuration(150).start();
                        } else {
                            buttonView.animate().translationY(half)
                                    .alpha(applyBaseOpacity())
                                    .setDuration(150).start();
                        }
                        deepHidden = false;
                        // 半隐藏态重新武装淡出→深度隐藏链
                        handler.postDelayed(fadeOutRunnable, FADE_OUT_DELAY_MS);
                        handler.postDelayed(deepHideRunnable, DEEP_HIDE_DELAY_MS);
                    } else {
                        buttonView.animate().alpha(applyBaseOpacity())
                                .setDuration(150).start();
                        if (edgeHidden) {
                            buttonView.animate().translationX(0f)
                                    .translationY(0f)
                                    .setDuration(150).start();
                        }
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
                        buttonView.animate().cancel();
                        buttonView.setTranslationX(0f);
                        buttonView.setTranslationY(0f);
                    }
                    if (!dragExpanded) {
                        // v0.4.154：拖动开始窗口临时扩全屏（一次
                        // relayout），此后位置只走 translation——
                        // 零 Binder、RenderThread 合成，跟手
                        expandForDrag();
                    }
                    buttonView.setTranslationX(initialX + dx);
                    buttonView.setTranslationY(initialY + dy);
                    // v0.4.160d：传输延迟诊断——前 1/3 与后 1/3 分段
                    long nowUp = SystemClock.uptimeMillis();
                    long transit = nowUp - event.getEventTime();
                    if (transit > diagTransitMaxMs) {
                        diagTransitMaxMs = transit;
                    }
                    if (diagMoveCount < 150) {
                        diagTransitHeadMs += transit;
                        diagTransitHeadN++;
                        if (diagLastEventUptime > 0) {
                            diagEventGapHeadMs += nowUp - diagLastEventUptime;
                        }
                    } else {
                        diagTransitTailMs += transit;
                        diagTransitTailN++;
                        diagEventGapTailMs += nowUp - diagLastEventUptime;
                    }
                    diagMoveCount++;
                    diagLastEventUptime = nowUp;
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    // v480：深度隐藏态单击只恢复成半隐藏（ACTION_DOWN
                    // 已处理），不打开菜单——双击第二下（此时已是
                    // 半隐藏，按原逻辑）才打开；半隐藏/可见态单击
                    // 直接打开菜单
                    if (!deepOnDown) {
                        handler.post(this::onButtonClick);
                    }
                } else if (isDragging) {
                    // v0.4.160d：松手输出分段诊断（一行，日志有界）
                    android.util.Log.i("ModMenuDrag", "diag moves=" + diagMoveCount
                            + " transitHeadAvgMs=" + (diagTransitHeadN > 0
                            ? diagTransitHeadMs / diagTransitHeadN : 0)
                            + " transitTailAvgMs=" + (diagTransitTailN > 0
                            ? diagTransitTailMs / diagTransitTailN : 0)
                            + " transitMaxMs=" + diagTransitMaxMs
                            + " gapHeadAvgMs=" + (diagTransitHeadN > 1
                            ? diagEventGapHeadMs / (diagTransitHeadN - 1) : 0)
                            + " gapTailAvgMs=" + (diagTransitTailN > 1
                            ? diagEventGapTailMs / (diagTransitTailN - 1) : 0));
                    diagMoveCount = 0;
                    diagTransitHeadMs = 0;
                    diagTransitHeadN = 0;
                    diagTransitTailMs = 0;
                    diagTransitTailN = 0;
                    diagTransitMaxMs = 0;
                    diagEventGapHeadMs = 0;
                    diagEventGapTailMs = 0;
                    diagLastEventUptime = 0;
                    // v0.4.159：收尾统一交给 snapToEdge——dragExpanded
                    // 时「窗口位置/尺寸/translation 同帧插值动画」直接
                    // 从拖动态过渡到吸附点（不再单独 collapse：collapse
                    // 的「窗口定位与 translation 清零不同帧」=闪跳根因）
                    snapToEdge();
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    snapToEdge();
                }
                isDragging = false;
                return true;
        }
        return false;
    }

    /** v0.4.154：拖动开始——窗口临时扩全屏（仅此一次 relayout），
     *  球位置换算成 translation；此后 MOVE 全程 setTranslationX/Y
     *  （RenderThread 合成，无同步 Binder）= 拖动真正跟手。 */
    private void expandForDrag() {
        if (dragExpanded || windowManager == null || buttonView == null
                || wmParams == null) {
            return;
        }
        try {
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            // 球当前绝对位置 = 窗口位置 + 当前 translation（贴边隐藏
            // 偏移已在 MOVE 里清零，这里仍按实际值换算）
            float absX = wmParams.x + buttonView.getTranslationX();
            float absY = wmParams.y + buttonView.getTranslationY();
            wmParams.width = dm.widthPixels;
            wmParams.height = dm.heightPixels;
            wmParams.x = 0;
            wmParams.y = 0;
            windowManager.updateViewLayout(wrapperView, wmParams);
            buttonView.setTranslationX(absX);
            buttonView.setTranslationY(absY);
            dragExpanded = true;
        } catch (Exception ignored) {
        }
    }

    /** v443：贴边半隐藏（谷歌方案）——内容向屏外平移一半被窗口
     *  Surface 裁剪，视觉只剩半个球；3 秒不操作淡到半透明。
     *  v477：支持四边（edgeSide -1 左 / 1 右 / -2 顶 / 2 底）。 */
    private void hideToEdge() {
        if (buttonView == null || edgeSide == 0) {
            return;
        }
        edgeHidden = true;
        deepHidden = false;
        int size = buttonView.getWidth();
        if (size <= 0) {
            size = wmParams != null ? wmParams.width : 0;
        }
        float offset = size * EDGE_HIDE_RATIO * (edgeSide < 0 ? -1f : 1f);
        if (Math.abs(edgeSide) == 1) {
            buttonView.animate().translationX(offset)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            buttonView.animate().translationY(offset)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        handler.removeCallbacks(fadeOutRunnable);
        handler.postDelayed(fadeOutRunnable, FADE_OUT_DELAY_MS);
        // v476：贴边后长时间没人动 → 深度隐藏（只露边缘条）
        handler.removeCallbacks(deepHideRunnable);
        handler.postDelayed(deepHideRunnable, DEEP_HIDE_DELAY_MS);
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
            // v0.4.159：球当前位置——展开态在 translation 上，普通态在窗口位置
            final boolean expanded = dragExpanded;
            int fromX = expanded ? (int) buttonView.getTranslationX() : wmParams.x;
            int fromY = expanded ? (int) buttonView.getTranslationY() : wmParams.y;
            // v477：四边吸附——球心到四条边的距离取最近（用户要求
            // 顶部/底部也能吸附）；edgeSide: -1 左 / 1 右 / -2 顶 / 2 底
            float centerX = fromX + size / 2f;
            float centerY = fromY + size / 2f;
            float distLeft = centerX;
            float distRight = screenW - centerX;
            float distTop = centerY;
            float distBottom = screenH - centerY;
            int newEdgeSide;
            if (distLeft <= distRight && distLeft <= distTop && distLeft <= distBottom) {
                newEdgeSide = -1;
            } else if (distRight <= distLeft && distRight <= distTop && distRight <= distBottom) {
                newEdgeSide = 1;
            } else if (distTop <= distLeft && distTop <= distRight && distTop <= distBottom) {
                newEdgeSide = -2;
            } else {
                newEdgeSide = 2;
            }
            edgeSide = newEdgeSide;
            int rawX = Math.abs(newEdgeSide) == 1
                    ? (newEdgeSide < 0 ? 0 : screenW - size) : fromX;
            int rawY = Math.abs(newEdgeSide) == 2
                    ? (newEdgeSide < 0 ? 0 : screenH - size) : fromY;
            final int targetX = Math.max(0, Math.min(rawX, screenW - size));
            final int targetY = Math.max(0, Math.min(rawY, screenH - size));
            if (expanded) {
                // v0.4.159：展开态收尾——窗口位置/尺寸/translation 同帧
                // 原子插值：视觉位置 = 窗口(t) + translation(t) 从球位置
                // 连续滑动到吸附点（v0.4.154-157 的「先 collapse 再吸附」
                // 因窗口定位与 translation 清零不同帧 = 左上角闪跳根因，
                // 统一动画三量同帧更新彻底消除）
                dragExpanded = false;
                final int fSize = size;
                final float fTx = buttonView.getTranslationX();
                final float fTy = buttonView.getTranslationY();
                snapAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
                snapAnimator.setDuration(250);
                snapAnimator.setInterpolator(
                        new android.view.animation.DecelerateInterpolator(1.5f));
                snapAnimator.addUpdateListener(a -> {
                    float t = (float) a.getAnimatedValue();
                    wmParams.x = (int) (targetX * t);
                    wmParams.y = (int) (targetY * t);
                    // v0.4.161：动画期间窗口尺寸保持全屏不缩——每帧
                    // Surface resize 在平板 WMS 重=吸附动画「跳」（瞬移
                    // 感）的根因；尺寸在动画结束后一次性恢复
                    buttonView.setTranslationX(fTx * (1f - t));
                    buttonView.setTranslationY(fTy * (1f - t));
                    try {
                        windowManager.updateViewLayout(wrapperView, wmParams);
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
                        // v0.4.161：尺寸一次性恢复（窗口左上已在目标
                        // 位置，缩尺寸只影响透明区域，球视觉不动）
                        wmParams.width = fSize;
                        wmParams.height = fSize;
                        buttonView.setTranslationX(0f);
                        buttonView.setTranslationY(0f);
                        try {
                            windowManager.updateViewLayout(wrapperView, wmParams);
                        } catch (Exception ignored) {
                        }
                        saveBallPosition(targetX, targetY);
                        hideToEdge();
                    }
                });
                snapAnimator.start();
                return;
            }
            final int fFromX = fromX;
            final int fFromY = fromY;
            snapAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            snapAnimator.setDuration(250);
            snapAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            snapAnimator.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                wmParams.x = (int) (fFromX + (targetX - fFromX) * t);
                wmParams.y = (int) (fFromY + (targetY - fFromY) * t);
                try {
                    windowManager.updateViewLayout(wrapperView, wmParams);
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
                        windowManager.updateViewLayout(wrapperView, wmParams);
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
        // v477：菜单关闭后重启贴边隐藏链（3s 淡出 + 15s 深度隐藏）——
        // 此前 hideToEdge 只在吸附落定时触发，点开菜单再退出后球
        // 一直全亮停在边缘（用户反馈）。
        // 注意：dismiss 回调在 hide() 开头同步触发，此刻 isShowing
        // 仍为 true（hide() 内部延迟才置 false）——不能再查
        // isShowing，否则条件永远不成立（v477 首版实测不生效根因）。
        // v479：延迟 3 秒再重启隐藏链——关闭后立刻隐藏球太小不好
        // 拖，给玩家留出拖动窗口（用户要求）。
        menuOverlay.setOnDismissListener(() -> {
            handler.removeCallbacks(menuRehideRunnable);
            handler.postDelayed(menuRehideRunnable, MENU_REHIDE_DELAY_MS);
        });
        menuOverlay.show();
    }
    


    public void hide() {
        if (menuOverlay != null) {
            menuOverlay.hide();
            menuOverlay = null;
        }
        dragExpanded = false;
        if (!isShowing || buttonView == null) return;
        handler.removeCallbacks(fadeOutRunnable);
        handler.removeCallbacks(deepHideRunnable);
        handler.removeCallbacks(menuRehideRunnable);
        handler.post(() -> {
            try {
                if (wmParams != null && windowManager != null) {
                    windowManager.removeView(wrapperView != null
                            ? wrapperView : buttonView);
                } else {
                    ViewGroup rootView = activity.findViewById(android.R.id.content);
                    if (rootView != null) {
                        rootView.removeView(buttonView);
                    }
                }
            } catch (Exception ignored) {}
            buttonView = null;
            wrapperView = null;
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
