package org.levimc.launcher.ui.views;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 支持双指缩放与单指平移的地图 ImageView。
 *
 * <p>基于 ScaleGestureDetector + GestureDetector + Matrix 实现，无第三方依赖：
 * <ul>
 *   <li>双指捏合：相对缩放，范围 1x（适配视图 fit-center）~ 8x；</li>
 *   <li>单指拖动：平移，图像边界始终不脱离视图（不足视图大小时自动居中）；</li>
 *   <li>双击：重置回 1x 适配视图状态。</li>
 * </ul>
 *
 * <p>本视图锁定 {@link ScaleType#MATRIX}，缩放矩阵由内部统一管理，
 * 通过 {@link #setImageBitmap} / {@link #setImageDrawable} 换图后自动重新适配。
 */
public class ZoomableImageView extends AppCompatImageView {

    /** 相对缩放下限：1x = 适配视图 */
    private static final float MIN_REL_SCALE = 1f;
    /** 相对缩放上限：8x */
    private static final float MAX_REL_SCALE = 8f;

    private final Matrix matrix = new Matrix();
    /** 1x 基准（fit-center）缩放因子 */
    private float baseScale = 1f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        super.setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        // 锁定 MATRIX：缩放矩阵由本视图管理
        super.setScaleType(ScaleType.MATRIX);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        fitToView();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }

    /** 重置为 1x 适配视图状态（fit-center 居中） */
    private void fitToView() {
        Drawable drawable = getDrawable();
        if (drawable == null
                || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0
                || getWidth() <= 0
                || getHeight() <= 0) {
            matrix.reset();
            setImageMatrix(matrix);
            baseScale = 1f;
            return;
        }
        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        baseScale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight);
        matrix.reset();
        matrix.setScale(baseScale, baseScale);
        matrix.postTranslate((viewWidth - drawableWidth * baseScale) / 2f,
                (viewHeight - drawableHeight * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    /** 当前相对缩放（相对 1x 基准） */
    private float relativeScale() {
        if (baseScale <= 0f) {
            return 1f;
        }
        float[] values = new float[9];
        matrix.getValues(values);
        return values[Matrix.MSCALE_X] / baseScale;
    }

    /** 将相对缩放限制在 [1x, 8x]，以视图中心为基准修正 */
    private void clampScale() {
        if (baseScale <= 0f) {
            return;
        }
        float rel = relativeScale();
        float clamp = 0f;
        if (rel < MIN_REL_SCALE) {
            clamp = MIN_REL_SCALE / rel;
        } else if (rel > MAX_REL_SCALE) {
            clamp = MAX_REL_SCALE / rel;
        }
        if (clamp != 0f) {
            matrix.postScale(clamp, clamp, getWidth() / 2f, getHeight() / 2f);
        }
    }

    /** 修正平移边界：图像不脱离视图；小于视图时居中 */
    private void clampTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null
                || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0) {
            return;
        }
        RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(rect);
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float dx = 0f;
        float dy = 0f;
        if (rect.width() <= viewWidth) {
            dx = (viewWidth - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0f) {
            dx = -rect.left;
        } else if (rect.right < viewWidth) {
            dx = viewWidth - rect.right;
        }
        if (rect.height() <= viewHeight) {
            dy = (viewHeight - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0f) {
            dy = -rect.top;
        } else if (rect.bottom < viewHeight) {
            dy = viewHeight - rect.bottom;
        }
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            if (factor <= 0f || Float.isNaN(factor) || Float.isInfinite(factor)) {
                return true;
            }
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            clampScale();
            clampTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            // 必须返回 true，后续 onScroll / onDoubleTap 才会被回调
            return true;
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                float distanceX, float distanceY) {
            matrix.postTranslate(-distanceX, -distanceY);
            clampTranslation();
            setImageMatrix(matrix);
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            fitToView();
            return true;
        }
    }
}
