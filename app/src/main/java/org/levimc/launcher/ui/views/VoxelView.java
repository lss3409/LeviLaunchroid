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
    /** 每列非空气方块数（渲染前算好；blockAt 二分查找用）。 */
    private int[][] counts;
    /** 旋转角（弧度）。v487：初始 = 0——刚体旋转晶格下 A=0 就是
     *  经典 2:1 等距视角（π/4 是斜侧躺视角，"地形沿 Z 轴倒下去"根因）。 */
    private float angle = 0f;
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

    /** v487：色调缓存（灰度模板纹理 × 色调——key = 方块名+颜色）。 */
    private final java.util.Map<String, android.graphics.Bitmap> tintCache =
            new java.util.HashMap<>();

    /** v487：草/树叶/水等方块在纹理包里是灰度模板（均值 147 =
     *  GRASS_TEMPLATE_COLOR）——必须乘上 tintColor 后的色调，
     *  否则草顶显示灰白（配深色网格线 = "白色十字/颗粒"根因）。
     *  texel × color/147（模板均值），缓存按 方块名+颜色。 */
    private android.graphics.Bitmap tintedTexture(String blockName,
                                                  android.graphics.Bitmap tex, int color) {
        if (blockName == null || tex == null) {
            return tex;
        }
        boolean tintable = blockName.contains("grass") || blockName.contains("leave")
                || blockName.contains("leaf") || blockName.contains("fern")
                || blockName.contains("vine") || blockName.contains("tallgrass")
                || blockName.contains("water");
        if (!tintable) {
            return tex;
        }
        String key = blockName + "#" + Integer.toHexString(color);
        synchronized (tintCache) {
            android.graphics.Bitmap cached = tintCache.get(key);
            if (cached != null) {
                return cached;
            }
            int w = tex.getWidth();
            int h = tex.getHeight();
            int[] px = new int[w * h];
            tex.getPixels(px, 0, w, 0, 0, w, h);
            float fr = (((color >> 16) & 0xFF) + 8f) / 147f;
            float fg = (((color >> 8) & 0xFF) + 8f) / 147f;
            float fb = ((color & 0xFF) + 8f) / 147f;
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int r = Math.min(255, (int) (((p >> 16) & 0xFF) * fr));
                int g = Math.min(255, (int) (((p >> 8) & 0xFF) * fg));
                int b = Math.min(255, (int) ((p & 0xFF) * fb));
                px[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
            android.graphics.Bitmap tinted = android.graphics.Bitmap.createBitmap(
                    px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
            tintCache.put(key, tinted);
            return tinted;
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
        counts = new int[size][size];
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                WorldMapRenderer.VoxelColumn col = data[dz][dx];
                int n = colBlockCount(col);
                counts[dz][dx] = n;
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
        // v486-2：经典 2:1 等距晶格整体刚体旋转（晶格与菱形一起转，
        // 任意角度严格共边平铺——旧"晶格转、菱形定形"混合投影只在
        // 45° 吻合，其他角度相邻顶面之间出现缺口楔形 = 黑缝根因）：
        // bpx = (dx−dz)cosA·u − (dx+dz)sinA·u/2
        // bpy = (dx−dz)sinA·u + (dx+dz)cosA·u/2 − (y−minY)·blockH
        float gMin = (size - 1) * unit * (Math.min(0f, sinA + cosA * 0.5f)
                + Math.min(0f, cosA * 0.5f - sinA));
        float gMax = (size - 1) * unit * (Math.max(0f, sinA + cosA * 0.5f)
                + Math.max(0f, cosA * 0.5f - sinA));
        float hMin = (size - 1) * unit * (Math.min(0f, cosA) - Math.max(0f, sinA));
        float hMax = (size - 1) * unit * (Math.max(0f, cosA) - Math.min(0f, sinA));
        float dTop = Math.max(Math.abs(sinA), Math.abs(cosA) * 0.5f) * unit;
        float m = Math.max(Math.abs(cosA), Math.abs(sinA) * 0.5f) * unit; // 方块自身半宽
        int pad = 12;
        int bw = (int) (2 * pad + (hMax - hMin) + 2 * m + 0.5f);
        int bh = (int) (2 * pad + ySpan * blockH + 2 * dTop
                + (gMax - gMin) + blockH + 0.5f);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        // v486-3：绘制整体平移 0.5px——所有边落在像素中点之间，
        // 像素中心永不落在共享边上，相邻面填充严格平铺无裂缝
        canvas.translate(0.5f, 0.5f);
        float cx = pad - hMin + m;
        float cy = pad + ySpan * blockH + dTop - gMin;
        // v487：基座底盘删除（用户反馈"悬浮深灰平面"伪影——实心
        // 填充 + 空列基底已让模型自带平坦底面，无需额外底盘）
        // 画家算法：列按投影深度远→近；列内从低到高画（高层最后画
        // 盖住低层顶面）。每个块侧面全高 blockH——相邻块侧面严丝合缝
        // 拼成连续墙面。v486-3：按面剔除——每面的覆盖邻居是该面朝向
        // 的相邻方块（刚体旋转晶格下严格共面，任意角度精确无缝隙）：
        // 顶面 ← 上方同列（上方侧面底边 = 本菱形上边）；
        // side1（L-B 边，+Z 墙）← (dx,dz+1)；
        // side2（B-R 边，+X 墙）← (dx+1,dz)；
        // side3（T-R 边，−Z 墙）← (dx,dz−1)；
        // side4（T-L 边，−X 墙）← (dx−1,dz)。
        // 四面墙全画——旋转到 75°~90° 时朝向观察者的墙不再缺失（空壳）
        int[][] order = drawOrder(cosA, sinA);
        for (int[] p : order) {
            int dx = p[0];
            int dz = p[1];
            WorldMapRenderer.VoxelColumn col = data[dz][dx];
            int n = counts[dz][dx];
            if (n == 0) {
                continue;
            }
            for (int i = n - 1; i >= 0; i--) {
                int y = col.ys[i];
                if (y < minY) {
                    break; // ys 从顶向下，更低的全在裁剪线以下
                }
                float bpx = cx + ((dx - dz) * cosA - (dx + dz) * 0.5f * sinA) * unit;
                float bpy = cy + ((dx - dz) * sinA + (dx + dz) * 0.5f * cosA) * unit
                        - (y - minY) * blockH;
                boolean above = blockAt(dx, dz, y + 1);
                boolean below = blockAt(dx, dz, y - 1);
                boolean frontZ = blockAt(dx, dz + 1, y);
                boolean frontX = blockAt(dx + 1, dz, y);
                boolean backZ = blockAt(dx, dz - 1, y);
                boolean backX = blockAt(dx - 1, dz, y);
                boolean showDiamond = !above;
                boolean showSide1 = !(above && frontZ);
                boolean showSide2 = !(above && frontX);
                boolean showSide3 = !(above && backZ);
                boolean showSide4 = !(above && backX);
                if (!showDiamond && !showSide1 && !showSide2
                        && !showSide3 && !showSide4) {
                    continue;
                }
                drawBlock(canvas, bpx, bpy, col.colors[i], cosA, sinA,
                        unit, blockH, col.names[i], y - minY,
                        above, below, frontZ, frontX, backZ, backX,
                        showDiamond, showSide1, showSide2, showSide3, showSide4);
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

    /** 区域网格内 (dx,dz,y) 是否有方块（AO/剔除邻居查询）。
     *  v486：列深可达 128——ys 降序，二分查找（此前线性扫全列）。 */
    private boolean blockAt(int dx, int dz, int y) {
        if (dx < 0 || dx >= size || dz < 0 || dz >= size || counts == null) {
            return false;
        }
        int[] ys = data[dz][dx].ys;
        int lo = 0;
        int hi = counts[dz][dx] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = ys[mid];
            if (v == y) {
                return true;
            }
            if (v > y) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
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

    /** 绘制顺序：投影深度降序（远→近）。
     *  v486-2：深度 = 刚体旋转晶格 bpy 的网格项
     *  (dx−dz)·sinA + (dx+dz)·cosA/2 = dx·(sinA+cosA/2) + dz·(cosA/2−sinA) */
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
        return dx * (sinA + cosA * 0.5f) + dz * (cosA * 0.5f - sinA);
    }

    /** 画一个等距方块（顶面 MC 原版纹理/纯色 + 两个侧面明暗 + 简易 AO）。
     *  v421：高度着色（bedrockmap 3D 同款——相对高度越高越亮，
     *  地形起伏更立体；±24 亮度差封顶）。
     *  v427：菱形顶点改用传入的 cosA/sinA（快照角度）——此前走 px()/py()
     *  用的是视图字段 angle，快照在 15°/30°…渲染时网格按快照角度摆、
     *  方块却按视图当前角度画，贴图与方块错位重叠（"贴图对不上"根因）。
     *  v484：① relY 改绝对高度（此前每列 baseY 归零 → 抹平地形）；
     *  ② 侧面 × 简易 AO（上/前/下邻居遮挡变暗——体素环境光遮蔽观感）；
     *  ③ 侧面不再描黑边（黑点来源）。
     *  v486-3：④ 四面墙（此前只画 +Z/+X 两面——旋转到 75°~90° 时
     *  朝向观察者的墙缺失变空壳）：side1 = L-B 边（+Z 墙）、
     *  side2 = B-R 边（+X 墙）、side3 = T-R 边（−Z 墙）、
     *  side4 = T-L 边（−X 墙）——经典等距立方体画法（侧面挂在下
     *  边缘而非上边缘，顶面菱形完整可见、纹理正常显示）；⑤ 按面
     *  绘制（由调用方传入各面可见性，见 renderSceneAtLocked 注释）。 */
    private void drawBlock(Canvas canvas, float cx, float topY, int color,
                           float cosA, float sinA, float u, float h, String blockName,
                           int relY, boolean above, boolean below,
                           boolean frontZ, boolean frontX, boolean backZ, boolean backX,
                           boolean showDiamond, boolean showSide1, boolean showSide2,
                           boolean showSide3, boolean showSide4) {
        int base = color;
        float hb = Math.max(-24f, Math.min(24f, relY * 0.5f));
        int r = clamp255(((base >> 16) & 0xFF) + (int) hb);
        int g = clamp255(((base >> 8) & 0xFF) + (int) hb);
        int b = clamp255((base & 0xFF) + (int) hb);
        int lit = 0xFF000000 | (r << 16) | (g << 8) | b;
        // 两侧明暗随观察方向交替（等距视觉立体感）
        float side = Math.abs(sinA);

        // 顶面：有 MC 原版纹理 → 仿射贴图到菱形（结构方块渲染同款观感）；
        // 无纹理回退纯色菱形
        if (showDiamond) {
            android.graphics.Bitmap tex = textureFor(blockName);
            // v487：灰度模板纹理乘色调（草顶灰白"白色十字"根因）
            tex = tintedTexture(blockName, tex, color);
            if (tex != null) {
                android.graphics.Matrix mt = new android.graphics.Matrix();
                float[] src = {0f, 0f, tex.getWidth(), 0f, 0f, tex.getHeight()};
                float[] dst = {
                        px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA),
                        px(cx, topY, 1, 0, u, cosA, sinA), py(cx, topY, 1, 0, u, cosA, sinA),
                        px(cx, topY, -1, 0, u, cosA, sinA), py(cx, topY, -1, 0, u, cosA, sinA)};
                mt.setPolyToPoly(src, 0, dst, 0, 3);
                canvas.save();
                canvas.concat(mt);
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
        }

        // v486-3：四个侧面——从菱形下边缘挂下（经典立方体）。
        // 每面独立 AO（该面朝向的邻居遮挡变暗）
        if (showSide1) {
            float ao = 1f - 0.16f * (above ? 1 : 0)
                    - 0.16f * (frontZ ? 1 : 0) - 0.08f * (below ? 1 : 0);
            fillPaint.setColor(0xFF000000
                    | (clamp255((int) (r * (0.55f + 0.2f * side) * ao)) << 16)
                    | (clamp255((int) (g * (0.55f + 0.2f * side) * ao)) << 8)
                    | clamp255((int) (b * (0.55f + 0.2f * side) * ao)));
            Path s1 = new Path();
            s1.moveTo(px(cx, topY, -1, 0, u, cosA, sinA), py(cx, topY, -1, 0, u, cosA, sinA));
            s1.lineTo(px(cx, topY, 0, 1, u, cosA, sinA), py(cx, topY, 0, 1, u, cosA, sinA));
            s1.lineTo(px(cx, topY + h, 0, 1, u, cosA, sinA), py(cx, topY + h, 0, 1, u, cosA, sinA));
            s1.lineTo(px(cx, topY + h, -1, 0, u, cosA, sinA), py(cx, topY + h, -1, 0, u, cosA, sinA));
            s1.close();
            canvas.drawPath(s1, fillPaint);
        }
        if (showSide2) {
            float ao = 1f - 0.16f * (above ? 1 : 0)
                    - 0.16f * (frontX ? 1 : 0) - 0.08f * (below ? 1 : 0);
            fillPaint.setColor(0xFF000000
                    | (clamp255((int) (r * (0.35f + 0.2f * side) * ao)) << 16)
                    | (clamp255((int) (g * (0.35f + 0.2f * side) * ao)) << 8)
                    | clamp255((int) (b * (0.35f + 0.2f * side) * ao)));
            Path s2 = new Path();
            s2.moveTo(px(cx, topY, 0, 1, u, cosA, sinA), py(cx, topY, 0, 1, u, cosA, sinA));
            s2.lineTo(px(cx, topY, 1, 0, u, cosA, sinA), py(cx, topY, 1, 0, u, cosA, sinA));
            s2.lineTo(px(cx, topY + h, 1, 0, u, cosA, sinA), py(cx, topY + h, 1, 0, u, cosA, sinA));
            s2.lineTo(px(cx, topY + h, 0, 1, u, cosA, sinA), py(cx, topY + h, 0, 1, u, cosA, sinA));
            s2.close();
            canvas.drawPath(s2, fillPaint);
        }
        if (showSide3) {
            float ao = 1f - 0.16f * (above ? 1 : 0)
                    - 0.16f * (backZ ? 1 : 0) - 0.08f * (below ? 1 : 0);
            fillPaint.setColor(0xFF000000
                    | (clamp255((int) (r * (0.55f + 0.2f * side) * ao)) << 16)
                    | (clamp255((int) (g * (0.55f + 0.2f * side) * ao)) << 8)
                    | clamp255((int) (b * (0.55f + 0.2f * side) * ao)));
            Path s3 = new Path();
            s3.moveTo(px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA));
            s3.lineTo(px(cx, topY, 1, 0, u, cosA, sinA), py(cx, topY, 1, 0, u, cosA, sinA));
            s3.lineTo(px(cx, topY + h, 1, 0, u, cosA, sinA), py(cx, topY + h, 1, 0, u, cosA, sinA));
            s3.lineTo(px(cx, topY + h, 0, -1, u, cosA, sinA), py(cx, topY + h, 0, -1, u, cosA, sinA));
            s3.close();
            canvas.drawPath(s3, fillPaint);
        }
        if (showSide4) {
            float ao = 1f - 0.16f * (above ? 1 : 0)
                    - 0.16f * (backX ? 1 : 0) - 0.08f * (below ? 1 : 0);
            fillPaint.setColor(0xFF000000
                    | (clamp255((int) (r * (0.35f + 0.2f * side) * ao)) << 16)
                    | (clamp255((int) (g * (0.35f + 0.2f * side) * ao)) << 8)
                    | clamp255((int) (b * (0.35f + 0.2f * side) * ao)));
            Path s4 = new Path();
            s4.moveTo(px(cx, topY, 0, -1, u, cosA, sinA), py(cx, topY, 0, -1, u, cosA, sinA));
            s4.lineTo(px(cx, topY, -1, 0, u, cosA, sinA), py(cx, topY, -1, 0, u, cosA, sinA));
            s4.lineTo(px(cx, topY + h, -1, 0, u, cosA, sinA), py(cx, topY + h, -1, 0, u, cosA, sinA));
            s4.lineTo(px(cx, topY + h, 0, -1, u, cosA, sinA), py(cx, topY + h, 0, -1, u, cosA, sinA));
            s4.close();
            canvas.drawPath(s4, fillPaint);
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** 单位菱形顶点投影（lx,ly 为逻辑角，u 为半宽；cosA/sinA 为
     *  快照渲染角度——必须与网格摆放角度一致，否则贴图与方块错位）。
     *  v486-2：经典 2:1 等距菱形（未旋转时 T=(0,−u/2)、R=(u,0)、
     *  B=(0,u/2)、L=(−u,0)）随场景整体刚体旋转——晶格与菱形一起转，
     *  任意角度严格保持共边平铺（旧"晶格转、菱形定形"混合投影只在
     *  45° 吻合，其他角度相邻顶面之间出现缺口楔形 = 黑缝根因） */
    private float px(float cx, float topY, float lx, float ly, float u, float cosA, float sinA) {
        return cx + (lx * cosA - ly * sinA * 0.5f) * u;
    }

    private float py(float cx, float topY, float lx, float ly, float u, float cosA, float sinA) {
        return topY + (lx * sinA + ly * cosA * 0.5f) * u;
    }

    /** 左上角 XYZ 三色坐标轴（X 红 / Y 绿 / Z 蓝）。 */
    private void drawAxis(Canvas canvas) {
        float ox = 60;
        float oy = getHeight() - 60;
        float len = 50;
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        // v486-2：轴方向 = 刚体旋转晶格——X 轴 = rotate(u, u/2)、
        // Z 轴 = rotate(−u, u/2)
        float ax = cosA - sinA * 0.5f;
        float ay = sinA + cosA * 0.5f;
        float zx = -cosA - sinA * 0.5f;
        float zy = -sinA + cosA * 0.5f;
        // X 轴（红）
        axisPaint.setColor(0xFFE53935);
        canvas.drawLine(ox, oy, ox + ax * len, oy + ay * len, axisPaint);
        // Z 轴（蓝）
        axisPaint.setColor(0xFF1E88E5);
        canvas.drawLine(ox, oy, ox + zx * len, oy + zy * len, axisPaint);
        // Y 轴（绿）：垂直向上
        axisPaint.setColor(0xFF43A047);
        canvas.drawLine(ox, oy, ox, oy - len, axisPaint);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(18);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setColor(0xFFE53935);
        canvas.drawText("X", ox + ax * (len + 12), oy + ay * (len + 12), text);
        text.setColor(0xFF1E88E5);
        canvas.drawText("Z", ox + zx * (len + 12), oy + zy * (len + 12), text);
        text.setColor(0xFF43A047);
        canvas.drawText("Y", ox + 4, oy - len - 12, text);
    }
}
