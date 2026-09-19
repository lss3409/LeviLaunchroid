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
    /** 预渲染场景位图（数据加载后画一次；旋转/缩放只变换位图，
     *  每帧 17 万 path 重绘是"卡死界面"的根因）。 */
    private android.graphics.Bitmap sceneBmp;
    /** 单指旋转灵敏度（每像素弧度；静态——设置页可调）。 */
    public static volatile float scrollSensitivity = 0.012f;
    /** 多角度快照（每 15° 一张，懒加载）——任意角度旋转时侧面明暗
     *  随角度变化（单张快照旋转只能平面转，"不支持 720°"的根因）。 */
    private final android.graphics.Bitmap[] angleSnaps = new android.graphics.Bitmap[24];
    private final java.util.Set<Integer> snapRequested = new java.util.HashSet<>();
    private final java.util.concurrent.ExecutorService snapPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);

    /** minecraft 方块名 → assets/voxel_textures 文件名（原版纹理优先）。 */
    private static final java.util.Map<String, String> TEX_MAP = new java.util.HashMap<>();
    static {
        TEX_MAP.put("minecraft:grass_block", "grass_top");
        TEX_MAP.put("minecraft:dirt", "dirt");
        TEX_MAP.put("minecraft:stone", "stone");
        TEX_MAP.put("minecraft:cobblestone", "cobblestone");
        TEX_MAP.put("minecraft:oak_log", "log_oak");
        TEX_MAP.put("minecraft:oak_planks", "planks_oak");
        TEX_MAP.put("minecraft:water", "water_still");
        TEX_MAP.put("minecraft:flowing_water", "water_still");
        TEX_MAP.put("minecraft:sand", "sand");
        TEX_MAP.put("minecraft:sandstone", "sandstone_top");
        TEX_MAP.put("minecraft:brick_block", "brick");
        TEX_MAP.put("minecraft:glass", "glass");
        TEX_MAP.put("minecraft:leaves", "leaves_oak_opaque");
        TEX_MAP.put("minecraft:leaves2", "leaves_oak_opaque");
        TEX_MAP.put("minecraft:netherrack", "netherrack");
        TEX_MAP.put("minecraft:soul_sand", "soul_sand");
        TEX_MAP.put("minecraft:bedrock", "bedrock");
        TEX_MAP.put("minecraft:gravel", "gravel");
        TEX_MAP.put("minecraft:magma", "magma");
        TEX_MAP.put("minecraft:snow", "snow");
        TEX_MAP.put("minecraft:snow_layer", "snow");
        TEX_MAP.put("minecraft:tnt", "tnt_top");
        TEX_MAP.put("minecraft:bookshelf", "bookshelf");
        TEX_MAP.put("minecraft:crafting_table", "crafting_table_top");
        TEX_MAP.put("minecraft:soul_soil", "soul-soil");
        TEX_MAP.put("minecraft:basalt", "basalt");
        TEX_MAP.put("minecraft:blackstone", "blackstone");
        TEX_MAP.put("minecraft:crimson_planks", "crimson-planks");
        TEX_MAP.put("minecraft:warped_planks", "warped-planks");
    }

    /** 纹理缓存（进程级；assets 读取）。 */
    private static final java.util.Map<String, android.graphics.Bitmap> texCache =
            new java.util.HashMap<>();

    /** 方块名 → 纹理位图（无纹理回退 null 走纯色）。
     *  TEX_MAP 特例优先（Bedrock 命名与 minecraft 名不一致的），
     *  之后按自动规则 minecraft 名去前缀直接查原版纹理文件——
     *  674 张 vanilla blocks 纹理全量打包（结构 NBT 渲染铺垫）。 */
    private android.graphics.Bitmap textureFor(String blockName) {
        if (blockName == null) {
            return null;
        }
        String file = TEX_MAP.get(blockName);
        if (file == null && blockName.startsWith("minecraft:")) {
            file = blockName.substring("minecraft:".length());
        }
        if (file == null) {
            return null;
        }
        synchronized (texCache) {
            if (texCache.containsKey(file)) {
                return texCache.get(file); // 含 null negative cache
            }
            try (java.io.InputStream in = getContext().getAssets()
                    .open("voxel_textures/" + file + ".png")) {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                texCache.put(file, bmp);
                return bmp;
            } catch (Exception e) {
                texCache.put(file, null); // negative cache：不存在的文件不再试
                return null;
            }
        }
    }

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
                        // 单指滑动只旋转（缩放交给双指捏合——混绑会让单指旋转
                        // 时误触缩放）；灵敏度可调（设置页），默认 0.012/像素，
                        // 一屏 ≈ 0.7 圈，连续拖支持 720°
                        angle -= distanceX * scrollSensitivity;
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
        // 后台线程预渲染场景位图（离屏 Canvas 线程安全），完成后回填
        new Thread(() -> {
            android.graphics.Bitmap bmp = renderScene();
            post(() -> {
                if (sceneBmp != null) {
                    sceneBmp.recycle();
                }
                sceneBmp = bmp;
                angleSnaps[0] = bmp; // 0° 快照即基础场景
                invalidate();
            });
        }, "voxel-scene").start();
        invalidate();
    }

    /** 预渲染场景位图（原角度快照；旋转/缩放时变换位图）。 */
    private android.graphics.Bitmap renderScene() {
        return renderSceneAt(1f, 0f);
    }

    /** 指定角度渲染场景位图（多角度快照用）。 */
    private android.graphics.Bitmap renderSceneAt(float cosA, float sinA) {
        if (data == null || size <= 0) {
            return null;
        }
        float unit = 8f;
        float unitH = 10f;
        int bw = (int) (size * unit * 2.2f);
        int bh = (int) (size * unit * 1.3f + 30 * unitH * 0.12f + 80);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = bw / 2f;
        float cy = bh / 2f - size * 1.2f;
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
            // 从低到高画（高层盖低层）。此前从高到低——最低块最后画把
            // 整列顶面全盖掉，只剩纸片菱形。侧面高度收到下一块顶面为止：
            // 柱内间距 1 层（1.2px）只露细边，地表/悬空块画全高——标准
            // 等距体素观感（方块有棱有面）
            for (int i = 0; i < n; i++) {
                int y = col.ys[i];
                float px = cx + (dx * cosA - dz * sinA) * unit;
                float py = cy + (dx * sinA + dz * cosA) * unit * 0.5f
                        - (y - baseY) * unitH * 0.12f;
                float sideH = 10f;
                if (i > 0) {
                    float gap = (y - col.ys[i - 1]) * unitH * 0.12f;
                    sideH = Math.min(10f, Math.max(0f, gap));
                }
                drawBlock(canvas, px, py, col.colors[i], (y - baseY) * 0.6f,
                        cosA, sinA, 8f, sideH, col.names[i], y - baseY);
            }
        }
        return bmp;
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
            canvas.drawText("加载中…", getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        if (sceneBmp == null) {
            Paint p = new Paint();
            p.setColor(0xFF8A93A3);
            p.setTextSize(30);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("生成中…", getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        // 多角度快照（每 15°）：旋转取最近角度快照 + 残余角微调——
        // 侧面明暗随角度变化，支持 720° 连续旋转（单张快照只能平面转）
        int idx = snapIdx(angle);
        android.graphics.Bitmap bmp = angleSnaps[idx];
        if (bmp == null) {
            requestSnap(idx);
            bmp = nearestExistingSnap(idx);
            if (bmp == null) {
                bmp = sceneBmp;
            }
        }
        canvas.save();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.translate(cx, cy);
        canvas.rotate((float) Math.toDegrees(angle) - idx * 15f);
        canvas.scale(zoom, zoom);
        canvas.drawBitmap(bmp, -bmp.getWidth() / 2f, -bmp.getHeight() / 2f, null);
        canvas.restore();
        drawAxis(canvas);
    }

    /** 角度 → 快照索引（每 15° 一格，环绕 24 格）。 */
    private int snapIdx(float angleRad) {
        int d = (int) Math.round(Math.toDegrees(angleRad) / 15.0);
        return Math.floorMod(d, 24);
    }

    /** 后台渲染指定角度快照（懒加载，单线程队列）。 */
    private void requestSnap(int idx) {
        synchronized (snapRequested) {
            if (!snapRequested.add(idx)) {
                return;
            }
        }
        final float ang = (float) Math.toRadians(idx * 15);
        snapPool.execute(() -> {
            try {
                android.graphics.Bitmap b = renderSceneAt(
                        (float) Math.cos(ang), (float) Math.sin(ang));
                if (b != null) {
                    post(() -> {
                        angleSnaps[idx] = b;
                        invalidate();
                    });
                }
            } finally {
                synchronized (snapRequested) {
                    snapRequested.remove(idx);
                }
            }
        });
    }

    /** 最近已生成的快照（未生成时回退）。 */
    private android.graphics.Bitmap nearestExistingSnap(int idx) {
        for (int d = 1; d < 24; d++) {
            android.graphics.Bitmap a = angleSnaps[Math.floorMod(idx + d, 24)];
            if (a != null) {
                return a;
            }
            android.graphics.Bitmap b = angleSnaps[Math.floorMod(idx - d, 24)];
            if (b != null) {
                return b;
            }
        }
        return angleSnaps[0] != null ? angleSnaps[0] : null;
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

    /** 画一个等距方块（顶面 MC 原版纹理/纯色 + 两个侧面明暗）。
     *  v421：高度着色（bedrockmap 3D 同款——相对高度越高越亮，
     *  地形起伏更立体；±24 亮度差封顶）。 */
    private void drawBlock(Canvas canvas, float cx, float topY, int color, float shade,
                           float cosA, float sinA, float u, float h, String blockName,
                           int relY) {
        int base = color;
        float hb = Math.max(-24f, Math.min(24f, relY * 0.5f));
        int r = Math.max(0, Math.min(255, ((base >> 16) & 0xFF) + (int) shade + (int) hb));
        int g = Math.max(0, Math.min(255, ((base >> 8) & 0xFF) + (int) shade + (int) hb));
        int b = Math.max(0, Math.min(255, (base & 0xFF) + (int) shade + (int) hb));
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

        // 顶面：有 MC 原版纹理 → 仿射贴图到菱形（结构方块渲染同款观感）；
        // 无纹理回退纯色菱形
        android.graphics.Bitmap tex = textureFor(blockName);
        if (tex != null) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            float[] src = {0f, 0f, tex.getWidth(), 0f, 0f, tex.getHeight()};
            float[] dst = {
                    px(cx, topY, 0, -1, u), py(cx, topY, 0, -1, u),
                    px(cx, topY, 1, 0, u), py(cx, topY, 1, 0, u),
                    px(cx, topY, -1, 0, u), py(cx, topY, -1, 0, u)};
            m.setPolyToPoly(src, 0, dst, 0, 3);
            canvas.save();
            canvas.concat(m);
            canvas.drawBitmap(tex, 0f, 0f, texPaint);
            canvas.restore();
        } else {
            Path top = new Path();
            top.moveTo(px(cx, topY, 1, 0, u), py(cx, topY, 1, 0, u));
            top.lineTo(px(cx, topY, 0, 1, u), py(cx, topY, 0, 1, u));
            top.lineTo(px(cx, topY, -1, 0, u), py(cx, topY, -1, 0, u));
            top.lineTo(px(cx, topY, 0, -1, u), py(cx, topY, 0, -1, u));
            top.close();
            fillPaint.setColor(lit);
            canvas.drawPath(top, fillPaint);
            canvas.drawPath(top, strokePaint);
        }

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
