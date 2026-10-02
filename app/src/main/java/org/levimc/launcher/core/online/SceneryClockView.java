package org.levimc.launcher.core.online;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

/**
 * v697：联机页风景卡表盘时钟（时分秒针 + 平滑秒针动画）。
 * 表盘静态部分缓存为 Bitmap，指针每帧重绘（postInvalidateOnAnimation）。
 * 配色为风景卡深色背景上的白色系。
 */
public class SceneryClockView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap dialCache;
    private int lastCacheW;
    private int lastCacheH;

    public SceneryClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        // 表盘（缓存：外圈 + 刻度）
        if (dialCache == null || lastCacheW != w || lastCacheH != h) {
            dialCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            lastCacheW = w;
            lastCacheH = h;
            Canvas dc = new Canvas(dialCache);
            drawDial(dc, cx, cy, r);
        }
        canvas.drawBitmap(dialCache, 0, 0, null);

        // 当前时间（秒针平滑：毫秒参与角度）
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int millis = cal.get(Calendar.MILLISECOND);

        float secAngle = (second + millis / 1000f) / 60f * 360f;
        float minAngle = (minute + second / 60f) / 60f * 360f;
        float hourAngle = ((hour % 12) + minute / 60f) / 12f * 360f;

        // 时针（短粗）
        drawHand(canvas, cx, cy, r * 0.42f, hourAngle, r * 0.055f, 0xFFF2F6F9);
        // 分针（长）
        drawHand(canvas, cx, cy, r * 0.62f, minAngle, r * 0.038f, 0xFFE8EEF3);
        // 秒针（细长，淡色 + 尾延长）
        drawHand(canvas, cx, cy, r * 0.72f, secAngle, r * 0.018f, 0xFFFFD9A0);
        // 中心帽
        paint.setColor(0xFFF2F6F9);
        canvas.drawCircle(cx, cy, r * 0.045f, paint);

        // 连续动画
        postInvalidateOnAnimation();
    }

    private void drawDial(Canvas c, float cx, float cy, float r) {
        // 外圈
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.035f);
        paint.setColor(0x59FFFFFF);
        c.drawCircle(cx, cy, r - r * 0.03f, paint);
        // 内圈细环
        paint.setStrokeWidth(r * 0.012f);
        paint.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, r * 0.86f, paint);
        // 12 个刻度
        paint.setStrokeWidth(r * 0.02f);
        for (int i = 0; i < 12; i++) {
            double ang = Math.toRadians(i * 30 - 90);
            boolean hourMark = i % 3 == 0;
            float len = hourMark ? r * 0.11f : r * 0.06f;
            float outer = r * 0.91f;
            float inner = outer - len;
            paint.setColor(hourMark ? 0xE6FFFFFF : 0x73FFFFFF);
            c.drawLine(
                    (float) (cx + outer * Math.cos(ang)),
                    (float) (cy + outer * Math.sin(ang)),
                    (float) (cx + inner * Math.cos(ang)),
                    (float) (cy + inner * Math.sin(ang)),
                    paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawHand(Canvas c, float cx, float cy, float len, float angle,
                          float width, int color) {
        double rad = Math.toRadians(angle - 90);
        float ex = (float) (cx + len * Math.cos(rad));
        float ey = (float) (cy + len * Math.sin(rad));
        // 尾端短延长（平衡感）
        float tail = len * 0.12f;
        float tx = (float) (cx - tail * Math.cos(rad));
        float ty = (float) (cy - tail * Math.sin(rad));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        c.drawLine(tx, ty, ex, ey, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
