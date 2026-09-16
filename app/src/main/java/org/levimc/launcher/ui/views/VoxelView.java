package org.levimc.launcher.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.levimc.launcher.core.content.worldmap.WorldMapRenderer;

/**
 * 3D 体素等距视图（Canvas 版最小实现，参考 BedrockMap voxel 视图的交互）：
 * 以选定区域中心为原点，等距投影绘制每列方块（顶面 + 两个侧面），
 * 支持 4 向旋转与双击放大。画家算法从远到近绘制保证遮挡正确。
 */
public class VoxelView extends View {

    private WorldMapRenderer.VoxelColumn[][] data;
    private int size;
    /** 旋转方向：0=北 1=东 2=南 3=西（逆时针转 90°）。 */
    private int rotation;
    /** 缩放（1x/2x）。 */
    private int zoom = 1;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        setBackgroundColor(0xFF12141A);
    }

    public void setVoxelData(WorldMapRenderer.VoxelColumn[][] data, int size) {
        this.data = data;
        this.size = size;
        invalidate();
    }

    public void rotateClockwise() {
        rotation = (rotation + 1) & 3;
        invalidate();
    }

    public void toggleZoom() {
        zoom = zoom >= 2 ? 1 : 2;
        invalidate();
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
        // 等距投影：x 轴 → (dx - dz)，y 轴 → (dx + dz) / 2，方块顶面菱形
        float unit = 8f * zoom;      // 顶面菱形半宽
        float unitH = 10f * zoom;    // 侧面高度
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f - size * 1.2f * zoom;
        // 画家算法：从最远列到最近列（旋转改变远→近方向）
        int[][] order = drawOrder();
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
            // 从底向上画（先画低的被高的覆盖）
            int baseY = col.ys[0];
            for (int i = n - 1; i >= 0; i--) {
                int y = col.ys[i];
                float px = cx + (dx - dz) * unit;
                float py = cy + (dx + dz) * unit * 0.5f - (y - baseY) * unitH * 0.12f;
                drawBlock(canvas, px, py, col.colors[i], (y - baseY) * 0.6f);
            }
        }
    }

    /** 绘制顺序：按观察方向远→近排序（旋转切换排序键）。 */
    private int[][] drawOrder() {
        int[][] order = new int[size * size][2];
        int i = 0;
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                order[i++] = new int[]{dx, dz};
            }
        }
        java.util.Arrays.sort(order, (a, b) ->
                Integer.compare(orderKey(b[0], b[1]), orderKey(a[0], a[1])));
        return order;
    }

    /** 各旋转方向的远→近排序键（值大 = 更远，先画）。 */
    private int orderKey(int dx, int dz) {
        switch (rotation) {
            case 1: return dx - dz;       // 东
            case 2: return -(dx + dz);    // 南
            case 3: return dz - dx;       // 西
            default: return dx + dz;      // 北
        }
    }

    /** 画一个等距方块（顶面菱形 + 左右侧面），颜色带高度明暗。 */
    private void drawBlock(Canvas canvas, float cx, float topY, int color, float shade) {
        float u = 8f * zoom;
        float h = 10f * zoom;
        int base = color;
        int r = Math.max(0, Math.min(255, ((base >> 16) & 0xFF) + (int) shade));
        int g = Math.max(0, Math.min(255, ((base >> 8) & 0xFF) + (int) shade));
        int b = Math.max(0, Math.min(255, (base & 0xFF) + (int) shade));
        int lit = 0xFF000000 | (r << 16) | (g << 8) | b;
        int dark = 0xFF000000 | ((r * 3 / 4) << 16) | ((g * 3 / 4) << 8) | (b * 3 / 4);
        int darker = 0xFF000000 | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);

        Path top = new Path();
        top.moveTo(cx, topY);
        top.lineTo(cx + u, topY + u * 0.5f);
        top.lineTo(cx, topY + u);
        top.lineTo(cx - u, topY + u * 0.5f);
        top.close();
        fillPaint.setColor(lit);
        canvas.drawPath(top, fillPaint);
        canvas.drawPath(top, strokePaint);

        Path left = new Path();
        left.moveTo(cx - u, topY + u * 0.5f);
        left.lineTo(cx, topY + u);
        left.lineTo(cx, topY + u + h);
        left.lineTo(cx - u, topY + u * 0.5f + h);
        left.close();
        fillPaint.setColor(dark);
        canvas.drawPath(left, fillPaint);
        canvas.drawPath(left, strokePaint);

        Path right = new Path();
        right.moveTo(cx + u, topY + u * 0.5f);
        right.lineTo(cx, topY + u);
        right.lineTo(cx, topY + u + h);
        right.lineTo(cx + u, topY + u * 0.5f + h);
        right.close();
        fillPaint.setColor(darker);
        canvas.drawPath(right, fillPaint);
        canvas.drawPath(right, strokePaint);
    }
}
