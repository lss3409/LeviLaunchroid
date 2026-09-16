package org.levimc.launcher.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.levimc.launcher.core.content.worldmap.WorldMapRenderer;

/**
 * 3D 体素等距视图（Canvas 实现，BedrockMap voxel 交互同款）：
 * 单指横向滑动旋转（连续角度）、双指捏合缩放、画家算法远→近绘制、
 * 左上角 XYZ 三色坐标轴指示器。
 */
public class VoxelView extends View {

    private WorldMapRenderer.VoxelColumn[][] data;
    private int size;
    /** 旋转角（弧度，0 = 北）。 */
    private float angle;
    /** 缩放倍率（0.5x - 4x）。 */
    private float zoom = 1f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleDetector;
    private float lastScrollX;

    public VoxelView(Context context) {
        super(context);
        init();
    }

    public VoxelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(0x33000000);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(3f);
        setBackgroundColor(0xFF12141A);
        gestureDetector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull MotionEvent e) {
                        lastScrollX = e.getX();
                        return true;
                    }

                    @Override
                    public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                            float distanceX, float distanceY) {
                        // 横向滑动 → 旋转；纵向滑动 → 微调俯仰感（缩放）
                        angle -= distanceX * 0.008f;
                        zoom = Math.max(0.5f, Math.min(4f, zoom + distanceY * 0.003f));
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        zoom = zoom >= 2f ? 1f : 2f;
                        invalidate();
                        return true;
                    }
                });
        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        zoom = Math.max(0.5f, Math.min(4f, zoom * detector.getScaleFactor()));
                        invalidate();
                        return true;
                    }
                });
    }

    public void setVoxelData(WorldMapRenderer.VoxelColumn[][] data, int size) {
        this.data = data;
        this.size = size;
        invalidate();
    }

    public void rotateClockwise() {
        angle += (float) Math.PI / 2f;
        invalidate();
    }

    public void toggleZoom() {
        zoom = zoom >= 2f ? 1f : 2f;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data == null || size <= 0) {
            Paint p = new Paint();
            p.setColor(0xFF8A93A3);
            p.setTextSize(30);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("无数据", getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        // 等距投影（连续角度）：世界 (dx, dz) → 屏幕 ((dx cosθ - dz sinθ)·u,
        // (dx sinθ + dz cosθ)·u·0.5 - 高度)，u 随 zoom
        float unit = 8f * zoom;
        float unitH = 10f * zoom;
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f - size * 1.2f * zoom;
        // 画家算法：投影深度 (dx·sinθ + dz·cosθ) 降序（远→近）
        int[][] order = drawOrder(cosA, sinA);
        for (int[] p : order) {
            int dx = p[0];
            int dz = p[1];
            WorldMapRenderer.VoxelColumn col = data[dz][dx];
            int n = 0;
            for (int c : col.colors) {
                if (c == 0) {
                    break;
                }
                n++;
            }
            if (n == 0) {
                continue;
            }
            int baseY = col.ys[0];
            for (int i = n - 1; i >= 0; i--) {
                int y = col.ys[i];
                float px = cx + (dx * cosA - dz * sinA) * unit;
                float py = cy + (dx * sinA + dz * cosA) * unit * 0.5f
                        - (y - baseY) * unitH * 0.12f;
                drawBlock(canvas, px, py, col.colors[i], (y - baseY) * 0.6f, cosA, sinA);
            }
        }
        drawAxis(canvas);
    }

    /** 绘制顺序：投影深度降序。 */
    private int[][] drawOrder(float cosA, float sinA) {
        int[][] order = new int[size * size][2];
        int i = 0;
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                order[i++] = new int[]{dx, dz};
            }
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(
                depth(b[0], b[1], sinA, cosA), depth(a[0], a[1], sinA, cosA)));
        return order;
    }

    private float depth(int dx, int dz, float sinA, float cosA) {
        return dx * sinA + dz * cosA;
    }

    /** 画一个等距方块（顶面菱形 + 两个侧面），侧面明暗随旋转角变化。 */
    private void drawBlock(Canvas canvas, float cx, float topY, int color, float shade,
                           float cosA, float sinA) {
        float u = 8f * zoom;
        float h = 10f * zoom;
        int base = color;
        int r = Math.max(0, Math.min(255, ((base >> 16) & 0xFF) + (int) shade));
        int g = Math.max(0, Math.min(255, ((base >> 8) & 0xFF) + (int) shade));
        int b = Math.max(0, Math.min(255, (base & 0xFF) + (int) shade));
        int lit = 0xFF000000 | (r << 16) | (g << 8) | b;
        // 两个侧面明暗随观察方向交替（等距视觉立体感）
        float side = Math.abs(sinA);
        int leftC = 0xFF000000
                | ((int) (r * (0.55f + 0.2f * side)) << 16)
                | ((int) (g * (0.55f + 0.2f * side)) << 8)
                | (int) (b * (0.55f + 0.2f * side));
        int rightC = 0xFF000000
                | ((int) (r * (0.35f + 0.2f * side)) << 16)
                | ((int) (g * (0.35f + 0.2f * side)) << 8)
                | (int) (b * (0.35f + 0.2f * side));

        // 顶面：单位菱形四顶点经旋转投影
        Path top = new Path();
        top.moveTo(px(cx, topY, 1, 0, u), py(cx, topY, 1, 0, u));
        top.lineTo(px(cx, topY, 0, 1, u), py(cx, topY, 0, 1, u));
        top.lineTo(px(cx, topY, -1, 0, u), py(cx, topY, -1, 0, u));
        top.lineTo(px(cx, topY, 0, -1, u), py(cx, topY, 0, -1, u));
        top.close();
        fillPaint.setColor(lit);
        canvas.drawPath(top, fillPaint);
        canvas.drawPath(top, strokePaint);

        // 侧面 1（左前：-X 与 -Z 边）
        Path side1 = new Path();
        side1.moveTo(px(cx, topY, 0, -1, u), py(cx, topY, 0, -1, u));
        side1.lineTo(px(cx, topY, -1, 0, u), py(cx, topY, -1, 0, u));
        side1.lineTo(px(cx, topY + h, -1, 0, u), py(cx, topY + h, -1, 0, u));
        side1.lineTo(px(cx, topY + h, 0, -1, u), py(cx, topY + h, 0, -1, u));
        side1.close();
        fillPaint.setColor(leftC);
        canvas.drawPath(side1, fillPaint);
        canvas.drawPath(side1, strokePaint);

        // 侧面 2（右前：+X 与 -Z 边）
        Path side2 = new Path();
        side2.moveTo(px(cx, topY, 0, -1, u), py(cx, topY, 0, -1, u));
        side2.lineTo(px(cx, topY, 1, 0, u), py(cx, topY, 1, 0, u));
        side2.lineTo(px(cx, topY + h, 1, 0, u), py(cx, topY + h, 1, 0, u));
        side2.lineTo(px(cx, topY + h, 0, -1, u), py(cx, topY + h, 0, -1, u));
        side2.close();
        fillPaint.setColor(rightC);
        canvas.drawPath(side2, fillPaint);
        canvas.drawPath(side2, strokePaint);
    }

    /** 单位菱形顶点投影（x,y 为逻辑角，u 为半宽）。 */
    private float px(float cx, float topY, float lx, float ly, float u) {
        return cx + (lx * (float) Math.cos(angle) - ly * (float) Math.sin(angle)) * u;
    }

    private float py(float cx, float topY, float lx, float ly, float u) {
        return topY + (lx * (float) Math.sin(angle) + ly * (float) Math.cos(angle)) * u * 0.5f;
    }

    /** 左上角 XYZ 三色坐标轴（X 红 / Y 绿 / Z 蓝）。 */
    private void drawAxis(Canvas canvas) {
        float ox = 60;
        float oy = getHeight() - 60;
        float len = 50;
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        // X 轴（红）：沿 (cosA, sinA·0.5) 方向
        axisPaint.setColor(0xFFE53935);
        canvas.drawLine(ox, oy, ox + cosA * len, oy + sinA * len * 0.5f, axisPaint);
        // Z 轴（蓝）：沿 (-sinA, cosA·0.5) 方向
        axisPaint.setColor(0xFF1E88E5);
        canvas.drawLine(ox, oy, ox - sinA * len, oy + cosA * len * 0.5f, axisPaint);
        // Y 轴（绿）：垂直向上
        axisPaint.setColor(0xFF43A047);
        canvas.drawLine(ox, oy, ox, oy - len, axisPaint);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(18);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setColor(0xFFE53935);
        canvas.drawText("X", ox + cosA * (len + 12), oy + sinA * (len + 12) * 0.5f, text);
        text.setColor(0xFF1E88E5);
        canvas.drawText("Z", ox - sinA * (len + 12), oy + cosA * (len + 12) * 0.5f, text);
        text.setColor(0xFF43A047);
        canvas.drawText("Y", ox + 4, oy - len - 12, text);
    }
}
