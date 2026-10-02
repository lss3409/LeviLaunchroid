package org.levimc.launcher.filemanager.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 编辑器可拖动滚动条（v673）：
 * 右侧竖条，thumb 高度按可视比例，按下/拖动即可翻页定位。
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
        setVisibility(viewportRatio < 0.995f ? View.VISIBLE : View.GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (h <= 0) return;

        // 轨道：2dp 圆角条居中
        trackPaint.setColor(trackColor);
        float trackW = 2 * density;
        canvas.drawRoundRect((w - trackW) / 2f, 0, (w + trackW) / 2f, h, trackW / 2f, trackW / 2f, trackPaint);

        // thumb：6dp 宽，高度按可视比例（最小 32dp）
        float minThumb = 32 * density;
        float thumbH = Math.max(minThumb, h * viewportRatio);
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
        float thumbH = Math.max(32 * density, h * viewportRatio);
        float maxY = h - thumbH;
        if (maxY <= 0) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float ratio = (event.getY() - thumbH / 2f) / maxY;
                ratio = Math.min(1f, Math.max(0f, ratio));
                scrollRatio = ratio;
                invalidate();
                if (listener != null) listener.onScrollToRatio(ratio);
                return true;
            }
            default:
                return true;
        }
    }
}
