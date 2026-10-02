package org.levimc.launcher.filemanager.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 编辑器可拖动滚动条（v673，v674 参照 FastScroller 行为重写）：
 * 行为对齐 Material FastScroller——按住 thumb 拖动（保持手指偏移）、
 * 点击 track 任意位置即跳转定位；热区 24dp 便于命中，
 * 视觉轨道 2dp / 滑块 6dp 居中。
 */
public class FmEditorScrollBar extends View {

    public interface Listener {
        /** 拖动到比例位置（0..1）。 */
        void onScrollToRatio(float ratio);
    }

    private Listener listener;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int trackColor = 0x1A000000;
    private int thumbColor = 0x80808080;
    private final float density;

    /** 可视高度 / 内容高度（1 = 内容不超屏）。 */
    private float viewportRatio = 1f;
    /** 当前滚动位置比例（0..1）。 */
    private float scrollRatio = 0f;

    private boolean dragging;
    /** 手指相对 thumb 顶部的偏移。 */
    private float dragOffset;

    public FmEditorScrollBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.density = getResources().getDisplayMetrics().density;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 按主题设置轨道/滑块颜色。 */
    public void setColors(int thumb, int track) {
        this.thumbColor = thumb;
        this.trackColor = track;
        invalidate();
    }

    /** 更新滚动状态；内容不超屏时自动隐藏。 */
    public void update(float viewportRatio, float scrollRatio) {
        this.viewportRatio = Math.min(1f, Math.max(0.05f, viewportRatio));
        this.scrollRatio = Math.min(1f, Math.max(0f, scrollRatio));
        boolean show = viewportRatio < 0.995f;
        setVisibility(show ? View.VISIBLE : View.GONE);
        android.util.Log.d("FmScrollBar", "update show=" + show + " barH=" + getHeight()
                + " barW=" + getWidth() + " vis=" + getVisibility());
        invalidate();
        // 自身尚未布局时（高度 0），等布局完成后补一次重绘，确保 onDraw 真正执行
        if (show && getHeight() <= 0) {
            post(this::invalidate);
            post(this::invalidate);
        }
    }

    private float thumbHeight() {
        return Math.max(40 * density, getHeight() * viewportRatio);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        android.util.Log.d("FmScrollBar", "onDraw h=" + h + " w=" + w);
        if (h <= 0) return;

        // 轨道：2dp 圆角条居中
        trackPaint.setColor(trackColor);
        float trackW = 2 * density;
        canvas.drawRoundRect((w - trackW) / 2f, 0, (w + trackW) / 2f, h, trackW / 2f, trackW / 2f, trackPaint);

        // thumb：6dp 宽，高度按可视比例（最小 40dp）
        float thumbH = thumbHeight();
        float maxY = h - thumbH;
        float thumbY = maxY * scrollRatio;
        thumbPaint.setColor(thumbColor);
        float thumbW = 6 * density;
        canvas.drawRoundRect((w - thumbW) / 2f, thumbY, (w + thumbW) / 2f, thumbY + thumbH,
                thumbW / 2f, thumbW / 2f, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float h = getHeight();
        float thumbH = thumbHeight();
        float maxY = h - thumbH;
        if (maxY <= 0) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = true;
                float thumbY = maxY * scrollRatio;
                if (event.getY() >= thumbY && event.getY() <= thumbY + thumbH) {
                    // 按住 thumb：保持手指相对偏移拖动
                    dragOffset = event.getY() - thumbY;
                } else {
                    // 点击 track：thumb 中心对齐手指并跳转
                    dragOffset = thumbH / 2f;
                }
                applyRatio(event.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) applyRatio(event.getY());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private void applyRatio(float y) {
        float thumbH = thumbHeight();
        float maxY = getHeight() - thumbH;
        if (maxY <= 0) return;
        float ratio = (y - dragOffset) / maxY;
        ratio = Math.min(1f, Math.max(0f, ratio));
        scrollRatio = ratio;
        invalidate();
        if (listener != null) listener.onScrollToRatio(ratio);
    }
}
