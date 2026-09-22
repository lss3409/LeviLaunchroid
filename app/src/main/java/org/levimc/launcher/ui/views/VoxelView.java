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

    // v484：填充/描边去抗锯齿——相邻面 AA 混色会透出背景色形成
    // "破损黑点/裂缝"（0x33 黑边密布所有面也是黑点来源）。
    // 纹理最近邻采样（像素风）——此前 FILTER_BITMAP 糊成一片
    private final Paint fillPaint = new Paint();
    private final Paint strokePaint = new Paint();
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texPaint = new Paint();
    /** 渲染锁：初始场景线程与快照线程共用 Paint 字段，串行化防脏色。 */
    private final Object renderLock = new Object();

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
    /** v427：双指旋转手势的上次两指连线角度（NaN = 未激活）。 */
    private float lastTwoFingerDeg = Float.NaN;

    /** 两指连线与水平轴夹角（度，-180~180）。 */
    private float twoFingerDeg(MotionEvent e) {
        if (e.getPointerCount() < 2) {
            return Float.NaN;
        }
        float dx = e.getX(1) - e.getX(0);
        float dy = e.getY(1) - e.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

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
        // v484：只给顶面描淡边（方块网格观感）——侧面描边是黑点来源
        strokePaint.setColor(0x22000000);
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
                        // v427：双指时 onScroll 也会触发（GestureDetector 收到
                        // 全部事件）——与双指旋转手势叠加会双重旋转/抖动，
                        // 双指交给两指连线角度手势
                        if (e2.getPointerCount() > 1) {
                            return true;
                        }
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
        synchronized (renderLock) {
            return renderSceneAtLocked(cosA, sinA);
        }
    }

    private android.graphics.Bitmap renderSceneAtLocked(float cosA, float sinA) {
        if (data == null || size <= 0) {
            return null;
        }
        float unit = 8f;
        // v481：方块高度 = 单位宽（标准等距立方体）——此前
        // unitH*0.12 = 1.2px/块，块被压成纸片菱形（"渲染是菱形
        // 不是方块"根因）。参考 bedrockmap 等距体素比例。
        float blockH = unit;
        // v484：全局 min/max y 锚定地形。此前 baseY = 每列自己的顶块——
        // 所有列的顶块画在同一屏高，地形起伏被抹平成"悬空平板"，
        // 底部只剩锯齿状悬挂碎块；且 sideH 用 ys[i]-ys[i-1]（上一块
        // 在上方）算出负值恒为 0——顶块以下全是没侧面的纸片菱形，
        // 整柱像随机散落的碎块（"稀疏破碎"根因）
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                WorldMapRenderer.VoxelColumn col = data[dz][dx];
                int n = colBlockCount(col);
                if (n == 0) {
                    continue;
                }
                if (col.ys[0] > maxY) {
                    maxY = col.ys[0];
                }
                if (col.ys[n - 1] < minY) {
                    minY = col.ys[n - 1];
                }
            }
        }
        if (minY > maxY) {
            // 区域没有任何非空气方块：画占位提示（返回 null 会让
            // onDraw 永远卡在"生成中…"）
            android.graphics.Bitmap empty = android.graphics.Bitmap.createBitmap(
                    320, 80, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas ec = new Canvas(empty);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFF8A93A3);
            tp.setTextSize(26);
            tp.setTextAlign(Paint.Align.CENTER);
            ec.drawText("该区域无方块数据", 160, 46, tp);
            return empty;
        }
        // 极端高差（悬崖+深谷选区）裁剪底部，防位图过高
        if (maxY - minY > 256) {
            minY = maxY - 256;
        }
        int ySpan = maxY - minY;
        // 网格投影范围（任意旋转角）：
        // x = (dx·cosA − dz·sinA)·unit，y = (dx·sinA + dz·cosA)·unit·0.5
        float gMin = (size - 1) * (Math.min(0f, sinA) + Math.min(0f, cosA)) * unit * 0.5f;
        float gMax = (size - 1) * (Math.max(0f, sinA) + Math.max(0f, cosA)) * unit * 0.5f;
        float hMin = (size - 1) * (Math.min(0f, cosA) - Math.max(0f, sinA)) * unit;
        float hMax = (size - 1) * (Math.max(0f, cosA) - Math.min(0f, sinA)) * unit;
        float m = Math.max(Math.abs(cosA), Math.abs(sinA)) * unit; // 方块自身半宽
        int pad = 12;
        int bw = (int) (2 * pad + (hMax - hMin) + 2 * m + 0.5f);
        int bh = (int) (2 * pad + ySpan * blockH + unit * 0.5f
                + (gMax - gMin) + blockH + 0.5f);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = pad - hMin + m;
        float cy = pad + ySpan * blockH + unit * 0.5f - gMin;
        // 基座底盘：minY 下半格的暗色大菱形——模型不悬空，
        // 空列/区域边缘露出的地面
        float plateCx = cx + (size - 1) / 2f * (cosA - sinA) * unit;
        float plateCy = cy + (size - 1) / 2f * (sinA + cosA) * unit * 0.5f
                + blockH * 0.5f;
        float ph = (size + 1) * 0.5f;
        Path plate = new Path();
        plate.moveTo(px(plateCx, plateCy, ph, 0, unit, cosA, sinA),
                py(plateCx, plateCy, ph, 0, unit, cosA, sinA));
        plate.lineTo(px(plateCx, plateCy, 0, ph, unit, cosA, sinA),
                py(plateCx, plateCy, 0, ph, unit, cosA, sinA));
        plate.lineTo(px(plateCx, plateCy, -ph, 0, unit, cosA, sinA),
                py(plateCx, plateCy, -ph, 0, unit, cosA, sinA));
        plate.lineTo(px(plateCx, plateCy, 0, -ph, unit, cosA, sinA),
                py(plateCx, plateCy, 0, -ph, unit, cosA, sinA));
        plate.close();
        fillPaint.setColor(0xFF262B33);
        canvas.drawPath(plate, fillPaint);
        // 画家算法：列按投影深度远→近；列内从低到高画（高层最后画
        // 盖住低层顶面）。每个块侧面全高 blockH——相邻块侧面严丝合缝
        // 拼成连续墙面
        int fx = Math.round(sinA); // 视角前方邻居（AO 用）
        int fz = Math.round(cosA);
        int[][] order = drawOrder(cosA, sinA);
        for (int[] p : order) {
            int dx = p[0];
            int dz = p[1];
            WorldMapRenderer.VoxelColumn col = data[dz][dx];
            int n = colBlockCount(col);
            if (n == 0) {
                continue;
            }
            for (int i = n - 1; i >= 0; i--) {
                int y = col.ys[i];
                if (y < minY) {
                    break; // ys 从顶向下，更低的全在裁剪线以下
                }
                float px = cx + (dx * cosA - dz * sinA) * unit;
                float py = cy + (dx * sinA + dz * cosA) * unit * 0.5f
                        - (y - minY) * blockH;
                boolean above = blockAt(dx, dz, y + 1);
                boolean below = blockAt(dx, dz, y - 1);
                boolean front = blockAt(dx + fx, dz + fz, y);
                drawBlock(canvas, px, py, col.colors[i], cosA, sinA,
                        unit, blockH, col.names[i], y - minY, above, below, front);
            }
        }
        return bmp;
    }

    /** 列内非空气方块数（colors 遇 0 即终止）。 */
    private static int colBlockCount(WorldMapRenderer.VoxelColumn col) {
        int n = 0;
        for (int c : col.colors) {
            if (c == 0) {
                break;
            }
            n++;
        }
        return n;
    }

    /** 区域网格内 (dx,dz,y) 是否有方块（AO 邻居查询；列深 ≤8，线性查找）。 */
    private boolean blockAt(int dx, int dz, int y) {
        if (dx < 0 || dx >= size || dz < 0 || dz >= size) {
            return false;
        }
        WorldMapRenderer.VoxelColumn col = data[dz][dx];
        for (int i = 0; i < col.ys.length; i++) {
            if (col.colors[i] == 0) {
                break;
            }
            if (col.ys[i] == y) {
                return true;
            }
        }
        return false;
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
        // v427：双指旋转手势（720° 任意角度——与捏合缩放并存，
        // 旋转看两指连线角度、缩放看两指距离，互不干扰）
        if (event.getPointerCount() == 2) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_DOWN) {
                lastTwoFingerDeg = twoFingerDeg(event);
            } else if (action == MotionEvent.ACTION_MOVE
                    && !Float.isNaN(lastTwoFingerDeg)) {
                float deg = twoFingerDeg(event);
                float delta = deg - lastTwoFingerDeg;
                // 跨 ±180° 归一化（atan2 跳变）
                if (delta > 180f) {
                    delta -= 360f;
                } else if (delta < -180f) {
                    delta += 360f;
                }
                if (Math.abs(delta) > 0.1f) {
                    angle += Math.toRadians(delta);
                    lastTwoFingerDeg = deg;
                    invalidate();
                }
                return true;
            }
        } else {
            lastTwoFingerDeg = Float.NaN;
        }
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
        // v481：残余角按位图实际角度（bmpIdx）算——此前按请求角算，
        // 快照未生成回退到邻近角时残余角不符，场景随旋转跳变
        int idx = snapIdx(angle);
        android.graphics.Bitmap bmp = angleSnaps[idx];
        int bmpIdx = idx;
        if (bmp == null) {
            requestSnap(idx);
            bmpIdx = nearestExistingSnapIdx(idx);
            bmp = angleSnaps[bmpIdx];
            if (bmp == null) {
                bmp = sceneBmp;
                bmpIdx = 0;
            }
        }
        canvas.save();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.translate(cx, cy);
        canvas.rotate((float) Math.toDegrees(angle) - bmpIdx * 15f);
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
                        // v484：快照位图内存上限——高差大的场景单张
                        // 可达 2.6MB，24 张全生成会爆手机 heap
                        // （536MB 上限设备）；只留 8 张，回收离当前
                        // 角最远的（0 号基础场景常驻）
                        int kept = 0;
                        for (android.graphics.Bitmap s : angleSnaps) {
                            if (s != null) {
                                kept++;
                            }
                        }
                        if (kept > 8) {
                            for (int d = 1; d < 24; d++) {
                                int ia = Math.floorMod(idx - d, 24);
                                if (ia != 0 && angleSnaps[ia] != null) {
                                    angleSnaps[ia].recycle();
                                    angleSnaps[ia] = null;
                                    break;
                                }
                            }
                        }
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

    /** 最近已生成快照的索引（未生成时回退 0）——
     *  v481：调用方按返回索引算残余旋转角。 */
    private int nearestExistingSnapIdx(int idx) {
        for (int d = 1; d < 24; d++) {
            int ia = Math.floorMod(idx + d, 24);
            if (angleSnaps[ia] != null) {
                return ia;
            }
            int ib = Math.floorMod(idx - d, 24);
            if (angleSnaps[ib] != null) {
                return ib;
            }
        }
        return 0;
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

    /** 画一个等距方块（顶面 MC 原版纹理/纯色 + 两个侧面明暗 + 简易 AO）。
     *  v421：高度着色（bedrockmap 3D 同款——相对高度越高越亮，
     *  地形起伏更立体；±24 亮度差封顶）。
     *  v427：菱形顶点改用传入的 cosA/sinA（快照角度）——此前走 px()/py()
     *  用的是视图字段 angle，快照在 15°/30°…渲染时网格按快照角度摆、
     *  方块却按视图当前角度画，贴图与方块错位重叠（"贴图对不上"根因）。
     *  v484：① relY 改绝对高度（此前每列 baseY 归零 → 抹平地形）；
     *  ② 侧面 × 简易 AO（上方/前方/下方邻居遮挡变暗——体素环境
     *  光遮蔽观感，被围的缝发暗）；③ 侧面不再描黑边（黑点来源）。 */
    private void drawBlock(Canvas canvas, float cx, float topY, int color,
                           float cosA, float sinA, float u, float h, String blockName,
                           int relY, boolean above, boolean below, boolean front) {
        int base = color;
        float hb = Math.max(-24f, Math.min(24f, relY * 0.5f));
        int r = clamp255(((base >> 16) & 0xFF) + (int) hb);
        int g = clamp255(((base >> 8) & 0xFF) + (int) hb);
        int b = clamp255((base & 0xFF) + (int) hb);
        int lit = 0xFF000000 | (r << 16) | (g << 8) | b;
        // 简易体素 AO：被邻居围住的侧面变暗
        float ao = 1f - 0.12f * (above ? 1 : 0)
                - 0.12f * (front ? 1 : 0)
                - 0.06f * (below ? 1 : 0);
        // 两个侧面明暗随观察方向交替（等距视觉立体感）
        float side = Math.abs(sinA);
        int leftC = 0xFF000000
                | (clamp255((int) (r * (0.55f + 0.2f * side) * ao)) << 16)
                | (clamp255((int) (g * (0.55f + 0.2f * side) * ao)) << 8)
                | clamp255((int) (b * (0.55f + 0.2f * side) * ao));
        int rightC = 0xFF000000
                | (clamp255((int) (r * (0.35f + 0.2f * side) * ao)) << 16)
                | (clamp255((int) (g * (0.35f + 0.2f * side) * ao)) << 8)
                | clamp255((int) (b * (0.35f + 0.2f * side) * ao));

        // 顶面：有 MC 原版纹理 → 仿射贴图到菱形（结构方块渲染同款观感）；
        // 无纹理回退纯色菱形
        android.graphics.Bitmap tex = textureFor(blockName);
        if (tex != null) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            float[] src = {0f, 0f, tex.getWidth(), 0f, 0f, tex.getHeight()};
            float[] dst = {
                    px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA),
                    px(cx, topY, 1, 0, u, cosA, sinA), py(cx, topY, 1, 0, u, cosA, sinA),
                    px(cx, topY, -1, 0, u, cosA, sinA), py(cx, topY, -1, 0, u, cosA, sinA)};
            m.setPolyToPoly(src, 0, dst, 0, 3);
            canvas.save();
            canvas.concat(m);
            canvas.drawBitmap(tex, 0f, 0f, texPaint);
            canvas.restore();
        } else {
            Path top = new Path();
            top.moveTo(px(cx, topY, 1, 0, u, cosA, sinA), py(cx, topY, 1, 0, u, cosA, sinA));
            top.lineTo(px(cx, topY, 0, 1, u, cosA, sinA), py(cx, topY, 0, 1, u, cosA, sinA));
            top.lineTo(px(cx, topY, -1, 0, u, cosA, sinA), py(cx, topY, -1, 0, u, cosA, sinA));
            top.lineTo(px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA));
            top.close();
            fillPaint.setColor(lit);
            canvas.drawPath(top, fillPaint);
            canvas.drawPath(top, strokePaint);
        }

        // 侧面 1（左前：-X 与 -Z 边）——v484 去描边（黑点来源）
        Path side1 = new Path();
        side1.moveTo(px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA));
        side1.lineTo(px(cx, topY, -1, 0, u, cosA, sinA), py(cx, topY, -1, 0, u, cosA, sinA));
        side1.lineTo(px(cx, topY + h, -1, 0, u, cosA, sinA), py(cx, topY + h, -1, 0, u, cosA, sinA));
        side1.lineTo(px(cx, topY + h, 0, -1, u, cosA, sinA), py(cx, topY + h, 0, -1, u, cosA, sinA));
        side1.close();
        fillPaint.setColor(leftC);
        canvas.drawPath(side1, fillPaint);

        // 侧面 2（右前：+X 与 -Z 边）
        Path side2 = new Path();
        side2.moveTo(px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA));
        side2.lineTo(px(cx, topY, 1, 0, u, cosA, sinA), py(cx, topY, 1, 0, u, cosA, sinA));
        side2.lineTo(px(cx, topY + h, 1, 0, u, cosA, sinA), py(cx, topY + h, 1, 0, u, cosA, sinA));
        side2.lineTo(px(cx, topY + h, 0, -1, u, cosA, sinA), py(cx, topY + h, 0, -1, u, cosA, sinA));
        side2.close();
        fillPaint.setColor(rightC);
        canvas.drawPath(side2, fillPaint);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** 单位菱形顶点投影（lx,ly 为逻辑角，u 为半宽；cosA/sinA 为
     *  快照渲染角度——必须与网格摆放角度一致，否则贴图与方块错位）。 */
    private float px(float cx, float topY, float lx, float ly, float u, float cosA, float sinA) {
        return cx + (lx * cosA - ly * sinA) * u;
    }

    private float py(float cx, float topY, float lx, float ly, float u, float cosA, float sinA) {
        return topY + (lx * sinA + ly * cosA) * u * 0.5f;
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
