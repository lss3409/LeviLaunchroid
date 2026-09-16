package org.levimc.launcher.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.levimc.launcher.core.content.BlueprintDb;
import org.levimc.launcher.core.content.worldmap.WorldMapRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BTR 式世界地图视图：缩放/平移时按当前比例重新采样方块颜色，
 * 像素随缩放级别实时更新（放大看清方块、缩小看全图），
 * 而不是放大一张固定分辨率的位图。
 *
 * <ul>
 *   <li>双指捏合：连续缩放，每方块像素数 0.05 ~ 16（BTR 上限 16px/block）；</li>
 *   <li>单指拖动：平移；</li>
 *   <li>双击：整图适配视图 / 放大切换；</li>
 *   <li>长按：回调地图坐标（用于添加标点）；</li>
 *   <li>单击：显示该处坐标（十字标记 + 坐标标签）；</li>
 *   <li>标点跳转：平滑飞行动画（HTML 原型 flyTo 式，非瞬移）；</li>
 *   <li>叠加渲染：标点标记、联动虚线（含跨维度 1:8）、区块网格、距离标注、
 *       生物群系图层、实体标记（actorprefix 解析）、结构标记（村庄/刷怪笼/末地传送门）。</li>
 * </ul>
 */
public class WorldMapView extends View {

    /** 每 block 的最大屏幕像素（BTR 1.0x 缩放级 = 16px/block） */
    private static final float MAX_PIXELS_PER_BLOCK = 24f;
    /** 每 block 的最小屏幕像素（避免整数运算下采样失真） */
    private static final float MIN_PIXELS_PER_BLOCK = 0.05f;

    /** 标点分类 → 图标颜色（与 PRD 一致） */
    private static final Map<String, Integer> CATEGORY_COLOR = new HashMap<>();
    static {
        CATEGORY_COLOR.put(BlueprintDb.CAT_BASE, Color.parseColor("#ffd54f"));
        CATEGORY_COLOR.put(BlueprintDb.CAT_PORTAL, Color.parseColor("#ab47bc"));
        CATEGORY_COLOR.put(BlueprintDb.CAT_FARM, Color.parseColor("#ef5350"));
        CATEGORY_COLOR.put(BlueprintDb.CAT_VILLAGE, Color.parseColor("#4ade80"));
        CATEGORY_COLOR.put(BlueprintDb.CAT_STRUCTURE, Color.parseColor("#f5a623"));
        CATEGORY_COLOR.put(BlueprintDb.CAT_CUSTOM, Color.parseColor("#ffd54f"));
    }

    private WorldMapRenderer.WorldMap map;
    /** 当前缩放：每 block 的屏幕像素 */
    private float pixelsPerBlock = 1f;
    /** 世界左上角 block 在屏幕上的偏移（像素） */
    private float offsetX = 0f;
    private float offsetY = 0f;

    /** 当前维度（overworld/nether/end），用于标点过滤与 1:8 换算 */
    private String dimension = "overworld";
    /** 标点与连线（渲染层数据，来自 BlueprintDb） */
    private List<BlueprintDb.Point> points = new ArrayList<>();
    private List<BlueprintDb.Link> links = new ArrayList<>();
    /** 实体标记（实体图层，来自 db actorprefix 解析） */
    private List<WorldMapRenderer.EntityPos> entities = new ArrayList<>();
    /** 结构标记（结构图层，来自方块实体检测） */
    private List<WorldMapRenderer.StructureMarker> structures = new ArrayList<>();
    /** 图层开关 */
    private boolean showGrid = false;
    private boolean showBiomeLayer = false;
    private boolean showEntities = false;
    private boolean showStructures = false;
    private boolean showSlimeChunks = false;
    /** 单击显示坐标：点击处十字标记（-1 = 无） */
    private int tapBlockX = -1;
    private int tapBlockZ = -1;
    /** 连线模式 / 测距模式：高亮待选起点 */
    private boolean linkPickMode = false;
    private long highlightPointId = -1;

    private OnMapInteractListener listener;
    private OnViewChangedListener viewChangedListener;

    /** 地图交互回调（长按添加标点 / 点击标点弹详情 / 单击显示坐标）。 */
    public interface OnMapInteractListener {
        void onLongPress(int blockX, int blockZ);
        void onPointClick(BlueprintDb.Point point);
        void onMapTap(int blockX, int blockZ);
    }

    /** 视图变化回调（缩放/平移后更新坐标 HUD）。 */
    public interface OnViewChangedListener {
        void onViewChanged(int centerBlockX, int centerBlockZ);
    }

    public void setOnViewChangedListener(OnViewChangedListener l) {
        this.viewChangedListener = l;
    }

    /** 视口按需渲染回调（BTR 式）：视口内缺失的 chunk 由外部渲染后填回 chunkColors。 */
    public interface OnChunksNeededListener {
        void onChunksNeeded(java.util.Set<Long> chunkKeys);
    }

    private OnChunksNeededListener chunksNeededListener;
    /** 已请求未渲染的 chunk（去重，防重复提交）。 */
    private final java.util.Set<Long> pendingChunks = new java.util.HashSet<>();

    public void setOnChunksNeededListener(OnChunksNeededListener l) {
        this.chunksNeededListener = l;
    }

    /** 外部按需渲染完成后调用：清除 pending 标记并重绘。 */
    public void onChunksRendered(java.util.Set<Long> chunkKeys) {
        pendingChunks.removeAll(chunkKeys);
        // chunk tile 路径：tile 在下一帧 onDraw 惰性生成，只需重绘
        invalidate();
    }

    /**
     * 内存优化（BTR 式离屏卸载）：开启后视口（含 1 屏缓冲）外的 chunk
     * 数据与 tile 直接回收，滑到哪渲染到哪——平板 3840×2560 高分辨率
     * 下渲染压力大，长时间滑动会累积几千 chunk（每 chunk 1KB 色表 +
     * 1KB tile），卸载后内存恒定在视口规模。
     */
    private boolean memoryOptimized = false;
    private long lastEvictTime = 0;

    public void setMemoryOptimized(boolean on) {
        memoryOptimized = on;
        if (on) {
            evictOffScreen();
        }
    }

    /** 卸载视口外（1 屏缓冲）的 chunk 数据与 tile；滑回时重新按需渲染。 */
    private void evictOffScreen() {
        if (map == null || map.chunkColors == null || map.chunkColors.isEmpty()) {
            return;
        }
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        float invPpb = 1f / pixelsPerBlock;
        double leftWorld = (0 - (double) offsetX - viewW) * invPpb + map.minBlockX;
        double rightWorld = (viewW - (double) offsetX + viewW) * invPpb + map.minBlockX;
        double topWorld = (0 - (double) offsetY - viewH) * invPpb + map.minBlockZ;
        double bottomWorld = (viewH - (double) offsetY + viewH) * invPpb + map.minBlockZ;
        int minCx = Math.floorDiv((int) Math.floor(leftWorld), 16);
        int maxCx = Math.floorDiv((int) Math.ceil(rightWorld), 16);
        int minCz = Math.floorDiv((int) Math.floor(topWorld), 16);
        int maxCz = Math.floorDiv((int) Math.ceil(bottomWorld), 16);
        java.util.Iterator<java.util.Map.Entry<Long, Bitmap>> it = chunkTiles.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, Bitmap> e = it.next();
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) {
                e.getValue().recycle();
                it.remove();
            }
        }
        map.chunkColors.keySet().removeIf(k -> {
            int cx = (int) (k >> 32);
            int cz = (int) (long) k;
            return cx < minCx || cx > maxCx || cz < minCz || cz > maxCz;
        });
        // 被卸载的 chunk 必须同时移出 pendingChunks——否则 onDraw 收集缺失时
        // 被 pending 拦截，滑回去永远不再请求渲染（内存优化开启后滑动出现
        // 成片空白的根因）
        if (!pendingChunks.isEmpty()) {
            pendingChunks.removeIf(k -> {
                int cx = (int) (k >> 32);
                int cz = (int) (long) k;
                return cx < minCx || cx > maxCx || cz < minCz || cz > maxCz;
            });
        }
        chunkDataCache.clear();
        if (lodMini != null) {
            lodMini.recycle();
            lodMini = null;
        }
        invalidate();
    }

    /** 通知视图变化（供 HUD 更新），在缩放/平移/跳转后调用。 */
    private void notifyViewChanged() {
        if (viewChangedListener != null && map != null && getWidth() > 0) {
            int[] center = screenToBlock(getWidth() / 2f, getHeight() / 2f);
            viewChangedListener.onViewChanged(center[0], center[1]);
        }
    }

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public WorldMapView(Context context) {
        this(context, null);
    }

    public WorldMapView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WorldMapView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    /** 设置世界数据：初始比整图放大 2 倍居中显示（BTR 打开时是放大的局部视图）。 */
    public void setWorldMap(@Nullable WorldMapRenderer.WorldMap map) {
        setWorldMap(map, false);
    }

    /** 设置世界数据；keepView=true 时保留当前缩放与位置（y 轴偏移重载等场景）。 */
    public void setWorldMap(@Nullable WorldMapRenderer.WorldMap map, boolean keepView) {
        this.map = map;
        // 换图后旧 tile/LOD 全部失效
        for (Bitmap b : chunkTiles.values()) {
            b.recycle();
        }
        chunkTiles.clear();
        chunkDataCache.clear();
        if (lodMini != null) {
            lodMini.recycle();
            lodMini = null;
        }
        if (!keepView || !viewInitialized) {
            initialView();
            viewInitialized = true;
        }
        invalidateFullRender();
        notifyViewChanged();
    }

    private boolean viewInitialized = false;

    /** 强制下一帧全量重采样（换图/图层切换等底层颜色变化时必须调用）。 */
    private void invalidateFullRender() {
        cachedPpb = -1f;
        invalidate();
    }

    /** 设置标点/连线数据并重绘。 */
    public void setBlueprintData(List<BlueprintDb.Point> points, List<BlueprintDb.Link> links) {
        this.points = points != null ? points : new ArrayList<>();
        this.links = links != null ? links : new ArrayList<>();
        invalidate();
    }

    /** 设置当前维度（影响标点过滤与跨维度连线换算）。 */
    public void setDimension(String dimension) {
        this.dimension = dimension;
        invalidate();
    }

    /** 区块网格开关。 */
    public void setShowGrid(boolean show) {
        this.showGrid = show;
        invalidate();
    }

    /** 生物群系图层开关（整图切换为 biome 色，需全量重采样）。 */
    public void setShowBiomeLayer(boolean show) {
        this.showBiomeLayer = show;
        // chunk tile 用的数据源随图层切换变化，全部重建
        for (Bitmap b : chunkTiles.values()) {
            b.recycle();
        }
        chunkTiles.clear();
        if (lodMini != null) {
            lodMini.recycle();
            lodMini = null;
        }
        invalidateFullRender();
    }

    /** 实体图层开关 + 数据。 */
    public void setEntityData(List<WorldMapRenderer.EntityPos> entities) {
        this.entities = entities != null ? entities : new ArrayList<>();
        invalidate();
    }

    public void setShowEntities(boolean show) {
        this.showEntities = show;
        invalidate();
    }

    /** 结构图层开关 + 数据。 */
    public void setStructureMarkers(List<WorldMapRenderer.StructureMarker> structures) {
        this.structures = structures != null ? structures : new ArrayList<>();
        invalidate();
    }

    public void setShowStructures(boolean show) {
        this.showStructures = show;
        invalidate();
    }

    /** 史莱姆区块图层开关（仅主世界）。 */
    public void setShowSlimeChunks(boolean show) {
        this.showSlimeChunks = show;
        invalidate();
    }

    /** 连线/测距模式高亮（-1 取消）。 */
    public void setHighlightPoint(long pointId) {
        this.highlightPointId = pointId;
        invalidate();
    }

    public void setOnMapInteractListener(OnMapInteractListener listener) {
        this.listener = listener;
    }

    /** chunk 坐标 → 缓存 key（与 WorldMapRenderer.pack 一致）。 */
    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * 世界块坐标 → 屏幕坐标。double 计算：float 在大数抵消时精度只剩
     * ~0.04px（offsetX 可达 -30 万），相邻 tile 的 dst 之间会出现亚像素
     * 缝隙/重叠毛刺——BTR 同款 rounding errors 教训（HALF_WORLDSIZE 必须
     * 2 的幂、tile 网格原点对齐，就是为了消灭这类误差）。
     */
    private float worldToScreenX(float worldBlockX) {
        return (float) ((double) offsetX + (worldBlockX - map.minBlockX) * (double) pixelsPerBlock);
    }

    private float worldToScreenY(float worldBlockZ) {
        return (float) ((double) offsetY + (worldBlockZ - map.minBlockZ) * (double) pixelsPerBlock);
    }

    /** 屏幕坐标 → 世界 block 坐标（double 计算，同精度修复）。 */
    public int[] screenToBlock(float sx, float sy) {
        int bx = (int) ((sx - (double) offsetX) / (double) pixelsPerBlock) + map.minBlockX;
        int bz = (int) ((sy - (double) offsetY) / (double) pixelsPerBlock) + map.minBlockZ;
        return new int[]{bx, bz};
    }

    /** 世界坐标 → 屏幕坐标（世界可能换维度坐标系，直接用 block 偏移）。 */
    public float[] blockToScreen(int blockX, int blockZ) {
        return new float[]{
                worldToScreenX(blockX + 0.5f),
                worldToScreenY(blockZ + 0.5f)
        };
    }

    /** 平移视角使世界坐标居中（标点列表跳转）。 */
    public void centerOn(int blockX, int blockZ) {
        if (map == null || getWidth() <= 0) {
            return;
        }
        offsetX = getWidth() / 2f - (blockX - map.minBlockX + 0.5f) * pixelsPerBlock;
        offsetY = getHeight() / 2f - (blockZ - map.minBlockZ + 0.5f) * pixelsPerBlock;
        clampTranslation();
        invalidate();
        notifyViewChanged();
    }

    /**
     * 平滑飞向目标点（HTML 原型 flyTo 式动画，非瞬移）：
     * 平移 + 自动放大到至少 4px/block，450ms decelerate 插值。
     */
    public void animateTo(int blockX, int blockZ) {
        if (map == null || getWidth() <= 0) {
            centerOn(blockX, blockZ);
            return;
        }
        float targetPpb = clampPixelsPerBlock(Math.max(pixelsPerBlock, 4f));
        final float startPpb = pixelsPerBlock;
        final float startOffsetX = offsetX;
        final float startOffsetY = offsetY;
        final float dPpb = targetPpb - startPpb;
        final float dOffsetX = getWidth() / 2f - (blockX - map.minBlockX + 0.5f) * targetPpb - startOffsetX;
        final float dOffsetY = getHeight() / 2f - (blockZ - map.minBlockZ + 0.5f) * targetPpb - startOffsetY;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(450);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        // 动画期间同样用缓存位图预览（以屏幕中心为锚），结束全量重绘
        scaleFocusX = getWidth() / 2f;
        scaleFocusY = getHeight() / 2f;
        scalePreviewActive = true;
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            pixelsPerBlock = startPpb + dPpb * t;
            offsetX = startOffsetX + dOffsetX * t;
            offsetY = startOffsetY + dOffsetY * t;
            clampTranslation();
            invalidate();
            notifyViewChanged();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                scalePreviewActive = false;
                invalidate();
                notifyViewChanged();
            }
        });
        anim.start();
    }

    /** 初始视图：比整图适配放大 4 倍，居中于玩家附近有数据的位置（未生成区块回退最近生成区）。 */
    private void initialView() {
        float fit = fitScale();
        if (fit <= 0f) {
            pixelsPerBlock = 1f;
            offsetX = 0f;
            offsetY = 0f;
            return;
        }

        pixelsPerBlock = clampPixelsPerBlock(Math.max(fit, 20f));
        // 目标点：玩家 > 出生点；若该处未生成（透明），螺旋找最近的有数据位置
        int targetX = map.playerBlockX >= 0 ? map.playerBlockX
                : map.spawnBlockX >= 0 ? map.spawnBlockX : map.minBlockX + map.width / 2;
        int targetZ = map.playerBlockZ >= 0 ? map.playerBlockZ
                : map.spawnBlockZ >= 0 ? map.spawnBlockZ : map.minBlockZ + map.height / 2;
        int[] center = map.nearestGeneratedBlock(targetX, targetZ);
        // 以中心点居中（地图大于视图时），小于视图则居中
        if (map.width * pixelsPerBlock > getWidth()) {
            offsetX = getWidth() / 2f - (center[0] - map.minBlockX + 0.5f) * pixelsPerBlock;
        } else {
            offsetX = (getWidth() - map.width * pixelsPerBlock) / 2f;
        }
        if (map.height * pixelsPerBlock > getHeight()) {
            offsetY = getHeight() / 2f - (center[1] - map.minBlockZ + 0.5f) * pixelsPerBlock;
        } else {
            offsetY = (getHeight() - map.height * pixelsPerBlock) / 2f;
        }
        clampTranslation();
    }

    /** 整图适配视图（fit-center）。 */
    private void fitToView() {
        float fit = fitScale();
        if (fit <= 0f) {
            pixelsPerBlock = 1f;
            offsetX = 0f;
            offsetY = 0f;
            return;
        }
        pixelsPerBlock = clampPixelsPerBlock(fit);
        offsetX = (getWidth() - map.width * pixelsPerBlock) / 2f;
        offsetY = (getHeight() - map.height * pixelsPerBlock) / 2f;
    }

    /** 整图适配所需每方块像素数；视图/数据未就绪返回 0。 */
    private float fitScale() {
        if (map == null || getWidth() <= 0 || getHeight() <= 0) {
            return 0f;
        }
        return Math.min(getWidth() / (float) map.width, getHeight() / (float) map.height);
    }

    private float clampPixelsPerBlock(float v) {
        return Math.max(MIN_PIXELS_PER_BLOCK, Math.min(MAX_PIXELS_PER_BLOCK, v));
    }

    /** 修正平移边界：地图边缘不脱离视图（小于视图时居中）。 */
    private void clampTranslation() {
        if (map == null || getWidth() <= 0) {
            return;
        }
        float mapW = map.width * pixelsPerBlock;
        float mapH = map.height * pixelsPerBlock;
        if (mapW <= getWidth()) {
            offsetX = (getWidth() - mapW) / 2f;
        } else {
            offsetX = Math.min(0f, Math.max(getWidth() - mapW, offsetX));
        }
        if (mapH <= getHeight()) {
            offsetY = (getHeight() - mapH) / 2f;
        } else {
            offsetY = Math.min(0f, Math.max(getHeight() - mapH, offsetY));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 只在尺寸真正变化时重排视角（悬浮层布局抖动会反复触发
        // onSizeChanged → initialView → 全量重采样 → invalidate 死循环）
        if (w != oldw || h != oldh) {
            initialView();
        }
    }

    // ---------------- 底层位图缓存（拖动时平移缓存位图，避免每帧全屏重采样） ----------------

    private Bitmap cachedBmp;
    private int cachedW;
    private int cachedH;
    private float cachedPpb = -1f;
    private float cachedOffsetX;
    private float cachedOffsetY;
    private int[] pixelsBuf;
    /** setPixels 行批量大小：逐行调用会累积 GPU 同步（每行 ~1ms，2400 行 = 2.4 秒）。 */
    private static final int ROW_BATCH = 32;
    /** 缩放/跳转动画期间用缓存位图做变换预览（捏合焦点锚点），结束再全量重采样 */
    private boolean scalePreviewActive;
    private float scaleFocusX;
    private float scaleFocusY;

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        // 大世界 bounds-only 地图只有 chunkColors（colors==null），
        // 必须放行 chunk 路径——否则首屏空白且视口按需渲染永不触发
        if (map == null || (map.colors == null && map.chunkColors == null)) {
            return;
        }
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        // 清空画布（平移缓存位图路径只覆盖部分区域，不清会叠出"盗梦空间"残影）
        canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR);
        if (map.chunkColors != null) {
            // 大世界：chunk tile 平铺（BTR 同款）——零重采样、零 39MB 大位图，
            // 每帧只 drawBitmap 视口内 chunk 的小 tile（GPU 加速）
            drawChunkLayer(canvas);
            // 内存优化：节流 1 秒卸载一次视口外 chunk（滑到哪渲染到哪）
            if (memoryOptimized) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastEvictTime > 1000) {
                    lastEvictTime = now;
                    evictOffScreen();
                }
            }
        } else {
            // 小世界整图路径：缓存位图三分支
            // 1) 纯平移 → 直接平移缓存位图，零重采样（拖动流畅）；
            // 2) 缩放/跳转动画期间 → 缓存位图按捏合焦点做缩放预览；
            // 3) 其它 → 全量重采样。
            boolean ppbChanged = Math.abs(pixelsPerBlock - cachedPpb) > 1e-4f;
            boolean farMoved = Math.abs(offsetX - cachedOffsetX) >= viewW
                    || Math.abs(offsetY - cachedOffsetY) >= viewH;
            if (scalePreviewActive && cachedBmp != null && !farMoved) {
                // 缓存位图变换预览：屏幕 = offset + k × (缓存坐标 - cachedOffset)
                float k = pixelsPerBlock / cachedPpb;
                canvas.save();
                canvas.translate(offsetX, offsetY);
                canvas.scale(k, k);
                canvas.translate(-cachedOffsetX, -cachedOffsetY);
                canvas.drawBitmap(cachedBmp, 0, 0, null);
                canvas.restore();
            } else if (cachedBmp == null || cachedW != viewW || cachedH != viewH
                    || ppbChanged || farMoved) {
                // 缓存位图必须 ARGB_8888：RGB_565 无 alpha 通道，未生成区域的透明色
                // (COLOR_BACKGROUND=0) 写入后被存成纯黑，地图上出现成片黑块
                // （tju 大范围稀疏世界未生成 chunk 多，问题尤为明显）。
                if (pixelsBuf == null || pixelsBuf.length != viewW * ROW_BATCH) {
                    pixelsBuf = new int[viewW * ROW_BATCH];
                }
                if (cachedBmp == null || cachedW != viewW || cachedH != viewH) {
                    if (cachedBmp != null) {
                        cachedBmp.recycle();
                    }
                    cachedBmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888);
                    cachedW = viewW;
                    cachedH = viewH;
                }
                resampleRegion(0, viewH);
                cachedPpb = pixelsPerBlock;
                cachedOffsetX = offsetX;
                cachedOffsetY = offsetY;
                canvas.drawBitmap(cachedBmp, 0, 0, null);
            } else {
                // 平移缓存位图（边缘露出背景，松手/移出屏幕后自动全量重绘）
                canvas.drawBitmap(cachedBmp, offsetX - cachedOffsetX, offsetY - cachedOffsetY, null);
            }
        }
        drawGrid(canvas);
        drawSlimeChunks(canvas);
        drawLinks(canvas);
        drawPoints(canvas);
        drawEntities(canvas);
        drawStructures(canvas);
        drawTapMarker(canvas);
        drawMarkers(canvas); // 玩家/出生点标记画在最上层（不被实体贴图遮挡）
    }

    // ---------------- 大世界 chunk tile 渲染（BTR 同款） ----------------

    /** chunk tile 缓存（16×16 ARGB 小位图，LRU 上限 2048 个 ≈ 2MB）。 */
    private final java.util.LinkedHashMap<Long, Bitmap> chunkTiles =
            new java.util.LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, Bitmap> eldest) {
                    if (size() > 2048) {
                        eldest.getValue().recycle();
                        return true;
                    }
                    return false;
                }
            };
    /** 缩略 LOD 位图（每 chunk 1 像素）：视口 chunk 数过多时整图一次 drawBitmap。 */
    private Bitmap lodMini;

    /** 大世界底层绘制：视口 chunk 多时画 LOD 缩略图，否则 tile 平铺。 */
    private void drawChunkLayer(android.graphics.Canvas canvas) {
        int viewW = getWidth();
        int viewH = getHeight();
        float ppb = pixelsPerBlock;
        float invPpb = 1f / ppb;
        // 视口 chunk 范围（钳到地图边界）
        double leftWorld = (0 - (double) offsetX) * invPpb + map.minBlockX;
        double rightWorld = (viewW - (double) offsetX) * invPpb + map.minBlockX;
        double topWorld = (0 - (double) offsetY) * invPpb + map.minBlockZ;
        double bottomWorld = (viewH - (double) offsetY) * invPpb + map.minBlockZ;
        int minCx = Math.floorDiv(map.minBlockX, 16);
        int minCz = Math.floorDiv(map.minBlockZ, 16);
        int maxCx = Math.floorDiv(map.minBlockX + map.width - 1, 16);
        int maxCz = Math.floorDiv(map.minBlockZ + map.height - 1, 16);
        int firstCx = Math.max(minCx, Math.floorDiv((int) Math.floor(leftWorld), 16));
        int firstCz = Math.max(minCz, Math.floorDiv((int) Math.floor(topWorld), 16));
        int lastCx = Math.min(maxCx, Math.floorDiv((int) Math.ceil(rightWorld), 16));
        int lastCz = Math.min(maxCz, Math.floorDiv((int) Math.ceil(bottomWorld), 16));
        int visibleChunks = (lastCx - firstCx + 1) * (lastCz - firstCz + 1);
        final java.util.Set<Long> missing = chunksNeededListener != null
                ? new java.util.HashSet<>() : null;
        if (visibleChunks > 4096) {
            // LOD：全图缩略一次 drawBitmap（缩小到整图可视时视口含全图 chunk，
            // tile 平铺 2.5 万次 drawBitmap 每帧会卡死）
            ensureLodMini();
            if (lodMini != null) {
                float left = worldToScreenX(minCx * 16f);
                float top = worldToScreenY(minCz * 16f);
                android.graphics.RectF dst = new android.graphics.RectF(left, top,
                        left + (maxCx - minCx + 1) * 16f * ppb,
                        top + (maxCz - minCz + 1) * 16f * ppb);
                Paint p = new Paint();
                p.setFilterBitmap(true);
                canvas.drawBitmap(lodMini, null, dst, p);
            }
            return;
        }
        Paint tilePaint = new Paint();
        tilePaint.setFilterBitmap(false); // 最近邻放大，保持块状像素风
        android.graphics.RectF dst = new android.graphics.RectF();
        for (int cz = firstCz; cz <= lastCz; cz++) {
            float top = worldToScreenY(cz * 16f);
            for (int cx = firstCx; cx <= lastCx; cx++) {
                long ck = packChunk(cx, cz);
                Bitmap tile = chunkTiles.get(ck);
                if (tile == null) {
                    tile = ensureChunkTile(ck);
                    if (tile == null) {
                        // 视口按需渲染：收集缺失 chunk（限一次，避免每帧重复报告）
                        if (missing != null && missing.size() < 2048
                                && !pendingChunks.contains(ck)) {
                            missing.add(ck);
                        }
                        continue;
                    }
                }
                dst.set(worldToScreenX(cx * 16f), top,
                        worldToScreenX(cx * 16f + 16f), top + 16f * ppb);
                canvas.drawBitmap(tile, null, dst, tilePaint);
            }
        }
        // 视口按需渲染：报告缺失 chunk（外部后台渲染后 onChunksRendered 重绘）
        if (missing != null && !missing.isEmpty()) {
            pendingChunks.addAll(missing);
            java.util.Set<Long> report = new java.util.HashSet<>(missing);
            missing.clear();
            OnChunksNeededListener l = chunksNeededListener;
            if (l != null) {
                l.onChunksNeeded(report);
            }
        }
    }

    /** 惰性生成 chunk tile（chunkColors 有数据但 tile 未建时）。 */
    private Bitmap ensureChunkTile(long ck) {
        int[] cc = map.chunkColors != null ? map.chunkColors.get(ck) : null;
        if (cc == null) {
            return null;
        }
        if (showBiomeLayer && map.chunkBiomeColors != null) {
            int[] bc = map.chunkBiomeColors.get(ck);
            if (bc != null) {
                cc = bc;
            }
        }
        Bitmap tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        tile.setPixels(cc, 0, 16, 0, 0, 16, 16);
        chunkTiles.put(ck, tile);
        return tile;
    }

    /** 生成/刷新 LOD 缩略图（每 chunk 1 像素代表色）。 */
    private void ensureLodMini() {
        if (map == null || map.chunkColors == null) {
            return;
        }
        int minCx = Math.floorDiv(map.minBlockX, 16);
        int minCz = Math.floorDiv(map.minBlockZ, 16);
        int maxCx = Math.floorDiv(map.minBlockX + map.width - 1, 16);
        int maxCz = Math.floorDiv(map.minBlockZ + map.height - 1, 16);
        int cw = maxCx - minCx + 1;
        int ch = maxCz - minCz + 1;
        if (cw <= 0 || ch <= 0 || cw * ch > 16 * 1024 * 1024) {
            return;
        }
        Bitmap mini = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        for (java.util.Map.Entry<Long, int[]> e : map.chunkColors.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            int px = cx - minCx;
            int pz = cz - minCz;
            if (px < 0 || px >= cw || pz < 0 || pz >= ch) {
                continue;
            }
            for (int v : e.getValue()) {
                if ((v & 0xFF000000) != 0) {
                    mini.setPixel(px, pz, v);
                    break;
                }
            }
        }
        if (lodMini != null) {
            lodMini.recycle();
        }
        lodMini = mini;
    }

    /**
     * 重采样行区间 [sy0, sy1) 到缓存位图（仅小世界 colors 数组路径）。
     * 按行填充，setPixels 按 ROW_BATCH 行批量写（逐行调用会累积 GPU 同步）。
     */
    private void resampleRegion(int sy0, int sy1) {
        if (map == null || cachedBmp == null || map.colors == null) {
            return;
        }
        int viewW = cachedW;
        float invPpb = 1f / pixelsPerBlock;
        int[] biomeSrc = showBiomeLayer ? map.biomeColors : null;
        int sy = sy0;
        while (sy < sy1) {
            int batchEnd = Math.min(sy1, sy + ROW_BATCH);
            int rows = batchEnd - sy;
            for (int r = 0; r < rows; r++) {
                int curSy = sy + r;
                int rowOff = r * viewW;
                int by = (int) ((curSy - offsetY) * invPpb);
                if (by < 0 || by >= map.height) {
                    // 地图外行：必须显式写透明，否则缓存位图残留旧帧（盗梦空间套图）
                    java.util.Arrays.fill(pixelsBuf, rowOff, rowOff + viewW, 0);
                    continue;
                }
                int bRow = by * map.width;
                for (int sx = 0; sx < viewW; sx++) {
                    int bx = (int) ((sx - offsetX) * invPpb);
                    if (bx < 0 || bx >= map.width) {
                        pixelsBuf[rowOff + sx] = 0;
                        continue;
                    }
                    int idx = bRow + bx;
                    if (biomeSrc != null && biomeSrc[idx] != 0) {
                        pixelsBuf[rowOff + sx] = biomeSrc[idx];
                    } else {
                        pixelsBuf[rowOff + sx] = map.colors[idx];
                    }
                }
            }
            cachedBmp.setPixels(pixelsBuf, 0, viewW, 0, sy, viewW, rows);
            sy = batchEnd;
        }
    }

    /** 史莱姆区块（半透明绿块；仅主世界；bedrock-level is_slime 同款算法）。 */
    private void drawSlimeChunks(Canvas canvas) {
        if (!showSlimeChunks || map == null || !"overworld".equals(dimension)) {
            return;
        }
        float chunkPx = 16f * pixelsPerBlock;
        if (chunkPx < 14f) {
            return; // 缩小到看不清区块时不画
        }
        // 只遍历视口内的 chunk：全图遍历在 928×1089 大世界上是每帧
        // 100 万次 isSlimeChunk（"开了史莱姆区块好卡"的根因）
        double leftWorld = (0 - (double) offsetX) / pixelsPerBlock + map.minBlockX;
        double rightWorld = (getWidth() - (double) offsetX) / pixelsPerBlock + map.minBlockX;
        double topWorld = (0 - (double) offsetY) / pixelsPerBlock + map.minBlockZ;
        double bottomWorld = (getHeight() - (double) offsetY) / pixelsPerBlock + map.minBlockZ;
        int firstCx = Math.floorDiv((int) Math.floor(leftWorld), 16);
        int firstCz = Math.floorDiv((int) Math.floor(topWorld), 16);
        int lastCx = Math.floorDiv((int) Math.ceil(rightWorld), 16);
        int lastCz = Math.floorDiv((int) Math.ceil(bottomWorld), 16);
        Paint slimePaint = new Paint();
        slimePaint.setColor(0x2E00C853);
        Paint borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0x8800E676);
        borderPaint.setStrokeWidth(1.5f);
        for (int cx = firstCx; cx <= lastCx; cx++) {
            for (int cz = firstCz; cz <= lastCz; cz++) {
                if (!WorldMapRenderer.isSlimeChunk(cx, cz)) {
                    continue;
                }
                // 未渲染（视口按需还没画出来）的 chunk 不显示史莱姆图层
                if (!chunkRendered(cx, cz)) {
                    continue;
                }
                float sx = worldToScreenX(cx * 16f);
                float sy = worldToScreenY(cz * 16f);
                if (sx + chunkPx < 0 || sx > getWidth() || sy + chunkPx < 0 || sy > getHeight()) {
                    continue;
                }
                canvas.drawRect(sx, sy, sx + chunkPx, sy + chunkPx, slimePaint);
                canvas.drawRect(sx, sy, sx + chunkPx, sy + chunkPx, borderPaint);
            }
        }
    }

    /** chunk 是否有非透明地形数据（EMPTY 占位/全透明的空区块视为无数据）。 */
    private final Map<Long, Boolean> chunkDataCache = new HashMap<>();

    /** 该 chunk 是否实际有地图数据；整图路径（colors 数组）恒为 true。 */
    private boolean chunkRendered(int cx, int cz) {
        if (map == null) {
            return false;
        }
        if (map.chunkColors != null) {
            long ck = packChunk(cx, cz);
            Boolean cached = chunkDataCache.get(ck);
            if (cached != null) {
                return cached;
            }
            int[] cc = map.chunkColors.get(ck);
            boolean has = false;
            if (cc != null) {
                for (int v : cc) {
                    if ((v & 0xFF000000) != 0) {
                        has = true;
                        break;
                    }
                }
            }
            if (cc != null) {
                // 只有已渲染（含 EMPTY 占位）的 chunk 才缓存判定；
                // 缺失 chunk 之后会渲染出来，不能缓存 false
                if (chunkDataCache.size() > 200000) {
                    chunkDataCache.clear();
                }
                chunkDataCache.put(ck, has);
            }
            return has;
        }
        return true;
    }

    /** 颜色混合：overlay 按 alpha 叠在 base 上（base 不透明时结果不透明）。 */
    private static int blendColor(int base, int overlay, float alpha) {
        int r = (int) (((base >> 16) & 0xFF) * (1f - alpha) + ((overlay >> 16) & 0xFF) * alpha);
        int g = (int) (((base >> 8) & 0xFF) * (1f - alpha) + ((overlay >> 8) & 0xFF) * alpha);
        int b = (int) ((base & 0xFF) * (1f - alpha) + (overlay & 0xFF) * alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 区块网格（chunk 边界按绝对世界坐标对齐 16 的倍数；放大后叠加 4-block 细线）。 */
    private void drawGrid(Canvas canvas) {
        if (!showGrid || pixelsPerBlock < 0.5f) {
            return;
        }
        float step = 16f * pixelsPerBlock;
        if (step < 12f) {
            return;
        }
        // 屏幕边缘对应的世界 block 坐标（绝对坐标对齐，不依赖 offset 起点）
        double leftWorld = (0 - (double) offsetX) / pixelsPerBlock + map.minBlockX;
        double rightWorld = (getWidth() - (double) offsetX) / pixelsPerBlock + map.minBlockX;
        double topWorld = (0 - (double) offsetY) / pixelsPerBlock + map.minBlockZ;
        double bottomWorld = (getHeight() - (double) offsetY) / pixelsPerBlock + map.minBlockZ;
        Paint chunk = new Paint();
        chunk.setColor(0x55FFFFFF);
        chunk.setStrokeWidth(1.5f);
        for (int bx = (int) Math.ceil(leftWorld / 16f) * 16; bx <= rightWorld; bx += 16) {
            float sx = worldToScreenX(bx);
            canvas.drawLine(sx, 0, sx, getHeight(), chunk);
        }
        for (int bz = (int) Math.ceil(topWorld / 16f) * 16; bz <= bottomWorld; bz += 16) {
            float sy = worldToScreenY(bz);
            canvas.drawLine(0, sy, getWidth(), sy, chunk);
        }
        // 放大到 4px/block 以上时叠加 4-block 细线（区块内细分，与主线重叠的跳过）
        if (pixelsPerBlock >= 4f) {
            Paint fine = new Paint();
            fine.setColor(0x28FFFFFF);
            fine.setStrokeWidth(1f);
            for (int bx = (int) Math.ceil(leftWorld / 4f) * 4; bx <= rightWorld; bx += 4) {
                if (Math.floorMod(bx, 16) == 0) {
                    continue;
                }
                float sx = worldToScreenX(bx);
                canvas.drawLine(sx, 0, sx, getHeight(), fine);
            }
            for (int bz = (int) Math.ceil(topWorld / 4f) * 4; bz <= bottomWorld; bz += 4) {
                if (Math.floorMod(bz, 16) == 0) {
                    continue;
                }
                float sy = worldToScreenY(bz);
                canvas.drawLine(0, sy, getWidth(), sy, fine);
            }
        }
    }

    /** 联动虚线（跨维度 1:8 映射）+ 中点距离标注。 */
    private void drawLinks(Canvas canvas) {
        if (links.isEmpty()) {
            return;
        }
        Map<Long, BlueprintDb.Point> byId = new HashMap<>();
        for (BlueprintDb.Point p : points) {
            byId.put(p.id, p);
        }
        Paint linePaint = new Paint();
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1.5f, pixelsPerBlock * 0.12f));
        linePaint.setPathEffect(new DashPathEffect(new float[]{6f, 8f}, 0f));
        Paint labelPaint = new Paint();
        labelPaint.setAntiAlias(true);
        labelPaint.setTextSize(Math.max(10f, pixelsPerBlock * 0.9f));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));

        for (BlueprintDb.Link link : links) {
            BlueprintDb.Point a = byId.get(link.fromId);
            BlueprintDb.Point b = byId.get(link.toId);
            if (a == null || b == null) {
                continue;
            }
            // 当前维度只画与本维度相关的线
            boolean aInDim = a.dimension.equals(dimension);
            boolean bInDim = b.dimension.equals(dimension);
            if (!aInDim && !bInDim) {
                continue;
            }
            // 跨维度：下界坐标 ×8 映射到主世界显示
            float ax = a.dimension.equals("nether") ? a.x * 8f : a.x;
            float az = a.dimension.equals("nether") ? a.z * 8f : a.z;
            float bx = b.dimension.equals("nether") ? b.x * 8f : b.x;
            float bz = b.dimension.equals("nether") ? b.z * 8f : b.z;
            float[] sa = blockToScreen((int) ax, (int) az);
            float[] sb = blockToScreen((int) bx, (int) bz);
            linePaint.setColor(Color.parseColor(link.color != null ? link.color : "#64b5f6"));
            canvas.drawLine(sa[0], sa[1], sb[0], sb[1], linePaint);
            // 距离标注（1:8 双距离）
            double ow = Math.hypot(bx - ax, bz - az);
            String text = a.dimension.equals(b.dimension)
                    ? Math.round(ow) + "m"
                    : "主世界 " + Math.round(ow) + "m / 下界 " + Math.round(ow / 8) + "m";
            labelPaint.setColor(0xFFFFFFFF);
            float midX = (sa[0] + sb[0]) / 2f;
            float midY = (sa[1] + sb[1]) / 2f;
            // 标签底衬
            float tw = labelPaint.measureText(text);
            Paint bgPaint = new Paint();
            bgPaint.setColor(0x99000000);
            canvas.drawRoundRect(midX - tw / 2f - 4f, midY - 8f, midX + tw / 2f + 4f, midY + 10f, 6f, 6f, bgPaint);
            canvas.drawText(text, midX - tw / 2f, midY + 4f, labelPaint);
        }
    }

    /** 标点标记（分类色圆点 + 名称标签）。 */
    private void drawPoints(Canvas canvas) {
        if (points.isEmpty()) {
            return;
        }
        Paint fill = new Paint();
        fill.setAntiAlias(true);
        Paint stroke = new Paint();
        stroke.setAntiAlias(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xCC000000);
        stroke.setStrokeWidth(Math.max(1f, pixelsPerBlock * 0.1f));
        Paint labelPaint = new Paint();
        labelPaint.setAntiAlias(true);
        labelPaint.setTextSize(Math.max(10f, pixelsPerBlock * 0.85f));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));

        for (BlueprintDb.Point p : points) {
            if (!p.dimension.equals(dimension)) {
                continue;
            }
            float[] s = blockToScreen(p.x, p.z);
            if (s[0] < -40 || s[0] > getWidth() + 40 || s[1] < -40 || s[1] > getHeight() + 40) {
                continue;
            }
            float r = Math.max(5f, pixelsPerBlock * 0.7f);
            Integer catColor = CATEGORY_COLOR.get(p.category);
            fill.setColor(catColor != null ? catColor : Color.parseColor("#ffd54f"));
            canvas.drawCircle(s[0], s[1], r, fill);
            canvas.drawCircle(s[0], s[1], r, stroke);
            if (p.id == highlightPointId) {
                Paint hl = new Paint();
                hl.setAntiAlias(true);
                hl.setStyle(Paint.Style.STROKE);
                hl.setColor(0xFFFFFFFF);
                hl.setStrokeWidth(Math.max(2f, pixelsPerBlock * 0.15f));
                canvas.drawCircle(s[0], s[1], r + 4f, hl);
            }
            // 名称标签
            String name = p.name;
            float tw = labelPaint.measureText(name);
            Paint bgPaint = new Paint();
            bgPaint.setColor(0x99000000);
            canvas.drawRoundRect(s[0] - tw / 2f - 4f, s[1] + r + 3f, s[0] + tw / 2f + 4f,
                    s[1] + r + 3f + labelPaint.getTextSize() + 6f, 5f, 5f, bgPaint);
            labelPaint.setColor(0xFFFFFFFF);
            canvas.drawText(name, s[0] - tw / 2f, s[1] + r + 3f + labelPaint.getTextSize(), labelPaint);
        }
    }

    /** 绘制玩家标记（绿色圆点）与出生点（白色描边圆点）。 */
    private void drawMarkers(android.graphics.Canvas canvas) {
        if (map == null) {
            return;
        }
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        if (map.spawnBlockX >= 0) {
            float sx = map.spawnBlockX - map.minBlockX + 0.5f;
            float sz = map.spawnBlockZ - map.minBlockZ + 0.5f;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, pixelsPerBlock * 0.18f));
            paint.setColor(0xCCFFFFFF);
            canvas.drawCircle(offsetX + sx * pixelsPerBlock, offsetY + sz * pixelsPerBlock,
                    Math.max(5f, pixelsPerBlock * 0.8f), paint);
        }
        if (map.playerBlockX >= 0) {
            float px = map.playerBlockX - map.minBlockX + 0.5f;
            float pz = map.playerBlockZ - map.minBlockZ + 0.5f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xFF3FE33F); // 绿色玩家点
            canvas.drawCircle(offsetX + px * pixelsPerBlock, offsetY + pz * pixelsPerBlock,
                    Math.max(4f, pixelsPerBlock * 0.7f), paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, pixelsPerBlock * 0.15f));
            paint.setColor(0xCC000000);
            canvas.drawCircle(offsetX + px * pixelsPerBlock, offsetY + pz * pixelsPerBlock,
                    Math.max(4f, pixelsPerBlock * 0.7f), paint);
        }
    }

    /** 实体标记（实体图层：BedrockMap 贴图，缩小级回退分类色圆点，放大后显示名称）。 */
    private void drawEntities(Canvas canvas) {
        if (!showEntities || entities.isEmpty() || pixelsPerBlock < 0.4f) {
            return;
        }
        if (!entitiesLogged) {
            entitiesLogged = true;
            android.util.Log.i("WorldMapView", "drawEntities 绘制: 实体数=" + entities.size()
                    + " ppb=" + pixelsPerBlock + " 首个实体=" + entities.get(0).name
                    + " at(" + entities.get(0).x + "," + entities.get(0).z + ")");
        }
        Paint fill = new Paint();
        fill.setAntiAlias(true);
        Paint labelPaint = null;
        if (pixelsPerBlock >= 2f) {
            labelPaint = new Paint();
            labelPaint.setAntiAlias(true);
            labelPaint.setTextSize(10f);
            labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
            labelPaint.setColor(0xFFFFFFFF);
        }
        float r = Math.max(2.5f, pixelsPerBlock * 0.35f);
        // 贴图：放大到 0.8px/block 以上才用（缩太小时图标重叠糊屏）
        boolean useIcons = pixelsPerBlock >= 0.8f;
        float iconSize = Math.max(16f, pixelsPerBlock * 1.6f);
        Paint iconPaint = new Paint();
        iconPaint.setFilterBitmap(true);
        for (WorldMapRenderer.EntityPos e : entities) {
            // 未渲染 chunk 上的实体不显示（视口按需渲染出来后才出现）
            if (!chunkRendered(Math.floorDiv((int) e.x, 16), Math.floorDiv((int) e.z, 16))) {
                continue;
            }
            float sx = worldToScreenX(e.x + 0.5f);
            float sy = worldToScreenY(e.z + 0.5f);
            if (sx < -40 || sx > getWidth() + 40 || sy < -40 || sy > getHeight() + 40) {
                continue;
            }
            Bitmap icon = useIcons ? iconBitmap(e.name) : null;
            if (icon != null) {
                canvas.drawBitmap(icon, null,
                        new android.graphics.RectF(sx - iconSize / 2f, sy - iconSize / 2f,
                                sx + iconSize / 2f, sy + iconSize / 2f), iconPaint);
                r = iconSize / 2f;
            } else {
                fill.setColor(entityColor(e.name));
                canvas.drawCircle(sx, sy, r, fill);
            }
            if (labelPaint != null) {
                String label = entityLabel(e.name);
                float tw = labelPaint.measureText(label);
                Paint bgPaint = new Paint();
                bgPaint.setColor(0x99000000);
                canvas.drawRoundRect(sx - tw / 2f - 3f, sy + r + 2f, sx + tw / 2f + 3f,
                        sy + r + 2f + labelPaint.getTextSize() + 4f, 4f, 4f, bgPaint);
                canvas.drawText(label, sx - tw / 2f, sy + r + 2f + labelPaint.getTextSize(), labelPaint);
            }
        }
    }

    /** 贴图缓存：资源名 → 32px Bitmap（null 也缓存，避免重复查找）。 */
    private final Map<String, Bitmap> iconBitmapCache = new HashMap<>();

    /** 按资源名取图标 Bitmap（BedrockMap 图标，位于 drawable-nodpi）。 */
    private Bitmap iconBitmap(String name) {
        if (iconBitmapCache.containsKey(name)) {
            return iconBitmapCache.get(name);
        }
        int resId = getResources().getIdentifier(name, "drawable", getContext().getPackageName());
        Bitmap b = null;
        if (resId != 0) {
            Bitmap src = BitmapFactory.decodeResource(getResources(), resId);
            if (src != null) {
                if (src.getWidth() != 32 || src.getHeight() != 32) {
                    b = Bitmap.createScaledBitmap(src, 32, 32, true);
                    if (b != src) {
                        src.recycle();
                    }
                } else {
                    b = src;
                }
            }
        }
        iconBitmapCache.put(name, b);
        return b;
    }

    /** 实体颜色：怪物红、村民棕、玩家绿、动物/其它白。 */
    private static int entityColor(String name) {
        switch (name) {
            case "zombie": case "zombie_villager": case "husk": case "drowned":
            case "skeleton": case "stray": case "wither_skeleton": case "creeper":
            case "spider": case "cave_spider": case "enderman": case "blaze":
            case "ghast": case "phantom": case "slime": case "magma_cube":
            case "witch": case "evoker": case "vindicator": case "pillager":
            case "ravager": case "zoglin": case "hoglin": case "piglin":
            case "piglin_brute": case "guardian": case "elder_guardian":
            case "shulker": case "vex": case "silverfish": case "endermite":
            case "warden": case "breeze": case "bogged":
                return 0xFFE53935;
            case "villager": case "wandering_trader":
                return 0xFFB08A3E;
            case "player":
                return 0xFF3FE33F;
            default:
                return 0xFFFFFFFF;
        }
    }

    /** 实体名 → 中文标签（未知保持原名）。 */
    private static String entityLabel(String name) {
        switch (name) {
            case "villager": return "村民";
            case "wandering_trader": return "流浪商人";
            case "zombie": return "僵尸";
            case "zombie_villager": return "僵尸村民";
            case "husk": return "尸壳";
            case "drowned": return "溺尸";
            case "skeleton": return "骷髅";
            case "stray": return "流髑";
            case "wither_skeleton": return "凋零骷髅";
            case "creeper": return "苦力怕";
            case "spider": return "蜘蛛";
            case "enderman": return "末影人";
            case "slime": return "史莱姆";
            case "phantom": return "幻翼";
            case "witch": return "女巫";
            case "pillager": return "掠夺者";
            case "vindicator": return "卫道士";
            case "evoker": return "唤魔者";
            case "ravager": return "劫掠兽";
            case "guardian": return "守卫者";
            case "elder_guardian": return "远古守卫者";
            case "shulker": return "潜影贝";
            case "warden": return "监守者";
            case "blaze": return "烈焰人";
            case "ghast": return "恶魂";
            case "magma_cube": return "岩浆怪";
            case "hoglin": return "疣猪兽";
            case "piglin": return "猪灵";
            case "player": return "玩家";
            case "cow": return "牛";
            case "sheep": return "羊";
            case "pig": return "猪";
            case "chicken": return "鸡";
            case "horse": return "马";
            case "donkey": return "驴";
            case "llama": return "羊驼";
            case "wolf": return "狼";
            case "cat": return "猫";
            case "fox": return "狐狸";
            case "bee": return "蜜蜂";
            case "rabbit": return "兔子";
            case "turtle": return "海龟";
            case "axolotl": return "美西螈";
            case "frog": return "青蛙";
            case "camel": return "骆驼";
            case "sniffer": return "嗅探兽";
            case "allay": return "悦灵";
            case "armor_stand": return "盔甲架";
            case "boat": return "船";
            case "minecart": return "矿车";
            case "item": return "掉落物";
            case "xp_orb": return "经验球";
            case "tnt": return "TNT";
            default: return name;
        }
    }

    /** 结构标记（结构图层：村庄房子图标 / 刷怪笼 / 末地传送门）。 */
    private void drawStructures(Canvas canvas) {
        if (!showStructures || structures.isEmpty() || pixelsPerBlock < 0.5f) {
            return;
        }
        Paint p = new Paint();
        p.setAntiAlias(true);
        Paint stroke = new Paint();
        stroke.setAntiAlias(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.5f, pixelsPerBlock * 0.12f));
        stroke.setColor(0xCC000000);
        Paint labelPaint = null;
        if (pixelsPerBlock >= 0.9f) {
            labelPaint = new Paint();
            labelPaint.setAntiAlias(true);
            labelPaint.setTextSize(10f);
            labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
            labelPaint.setColor(0xFFFFFFFF);
        }
        Paint iconPaint = new Paint();
        iconPaint.setFilterBitmap(true);
        for (WorldMapRenderer.StructureMarker m : structures) {
            // 未渲染 chunk 上的结构不显示（视口按需渲染出来后才出现）
            if (!chunkRendered(Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16))) {
                continue;
            }
            float sx = worldToScreenX(m.x + 0.5f);
            float sy = worldToScreenY(m.z + 0.5f);
            if (sx < -60 || sx > getWidth() + 60 || sy < -60 || sy > getHeight() + 60) {
                continue;
            }
            float r = Math.max(7f, pixelsPerBlock * 0.9f);
            // 白底圆（深色地形上保持醒目，对应地图 App 结构标记样式）
            Paint bgCircle = new Paint();
            bgCircle.setAntiAlias(true);
            bgCircle.setColor(0xD9FFFFFF);
            canvas.drawCircle(sx, sy, r, bgCircle);
            stroke.setColor(0x80000000);
            stroke.setStrokeWidth(1.5f);
            canvas.drawCircle(sx, sy, r, stroke);
            stroke.setColor(0xCC000000);
            // 结构贴图（BedrockMap block_actor 图标），失败回退自绘形状
            String iconName = structureIconName(m.type);
            Bitmap icon = iconName != null ? iconBitmap(iconName) : null;
            if (icon != null) {
                float isz = r * 1.7f;
                canvas.drawBitmap(icon, null,
                        new android.graphics.RectF(sx - isz / 2f, sy - isz / 2f,
                                sx + isz / 2f, sy + isz / 2f), iconPaint);
                r = isz / 2f;
            } else {
                drawStructureShape(canvas, m.type, sx, sy, r, p, stroke);
            }
            if (labelPaint != null) {
                String label = structureLabel(m.type);
                float tw = labelPaint.measureText(label);
                Paint bgPaint = new Paint();
                bgPaint.setColor(0x99000000);
                canvas.drawRoundRect(sx - tw / 2f - 3f, sy + r + 2f, sx + tw / 2f + 3f,
                        sy + r + 2f + labelPaint.getTextSize() + 4f, 4f, 4f, bgPaint);
                canvas.drawText(label, sx - tw / 2f, sy + r + 2f + labelPaint.getTextSize(), labelPaint);
            }
        }
    }

    /** 结构类型 → 贴图资源名（无贴图的返回 null 走自绘）。 */
    private static String structureIconName(String type) {
        switch (type) {
            case "village": return "bell";
            case "spawner": return "mobspawner";
            case "trial_spawner": return "trialspawner";
            case "end_portal": return "endgateway";
            default: return null;
        }
    }

    /** 结构类型 → 中文名。 */
    private static String structureLabel(String type) {
        switch (type) {
            case "village": return "村庄";
            case "spawner": return "刷怪笼";
            case "trial_spawner": return "试炼刷怪笼";
            case "end_portal": return "末地传送门";
            case "fortress": return "下界要塞";
            case "swamp_hut": return "女巫小屋";
            case "ocean_monument": return "海底神殿";
            case "outpost": return "掠夺者前哨站";
            case "end_city": return "末地城";
            default: return type;
        }
    }

    /** 结构自绘形状（贴图缺失时回退）。 */
    private void drawStructureShape(Canvas canvas, String type, float sx, float sy, float r,
                                    Paint p, Paint stroke) {
            switch (type) {
                case "village": {
                    // 房子：三角屋顶 + 方形身体
                    p.setColor(0xFF8D6E63);
                    Path path = new Path();
                    path.moveTo(sx, sy - r);
                    path.lineTo(sx - r, sy + r * 0.15f);
                    path.lineTo(sx + r, sy + r * 0.15f);
                    path.close();
                    canvas.drawPath(path, p);
                    canvas.drawPath(path, stroke);
                    canvas.drawRect(sx - r * 0.65f, sy + r * 0.2f, sx + r * 0.65f, sy + r, p);
                    canvas.drawRect(sx - r * 0.65f, sy + r * 0.2f, sx + r * 0.65f, sy + r, stroke);
                    break;
                }
                case "spawner": {
                    // 刷怪笼：方框 + 内部网格
                    p.setColor(0xFFE53935);
                    canvas.drawRect(sx - r * 0.7f, sy - r * 0.7f, sx + r * 0.7f, sy + r * 0.7f, p);
                    canvas.drawRect(sx - r * 0.7f, sy - r * 0.7f, sx + r * 0.7f, sy + r * 0.7f, stroke);
                    stroke.setColor(0xCCFFFFFF);
                    canvas.drawLine(sx - r * 0.7f, sy, sx + r * 0.7f, sy, stroke);
                    canvas.drawLine(sx, sy - r * 0.7f, sx, sy + r * 0.7f, stroke);
                    stroke.setColor(0xCC000000);
                    break;
                }
                case "end_portal": {
                    // 末地传送门：绿紫圆环
                    p.setColor(0xFF7E57C2);
                    canvas.drawCircle(sx, sy, r * 0.75f, p);
                    canvas.drawCircle(sx, sy, r * 0.75f, stroke);
                    p.setColor(0xFF26C6DA);
                    canvas.drawCircle(sx, sy, r * 0.3f, p);
                    break;
                }
                case "fortress": {
                    // 下界要塞：深红城齿
                    p.setColor(0xFF8C2F2F);
                    for (int i = -1; i <= 1; i++) {
                        canvas.drawRect(sx + i * r * 0.8f - r * 0.2f, sy - r * 0.9f,
                                sx + i * r * 0.8f + r * 0.2f, sy + r * 0.6f, p);
                        canvas.drawRect(sx + i * r * 0.8f - r * 0.2f, sy - r * 0.9f,
                                sx + i * r * 0.8f + r * 0.2f, sy + r * 0.6f, stroke);
                    }
                    break;
                }
                case "trial_spawner": {
                    // 试炼刷怪笼：青色笼（与红色普通刷怪笼区分）
                    p.setColor(0xFF2E7D6E);
                    canvas.drawRect(sx - r * 0.7f, sy - r * 0.7f, sx + r * 0.7f, sy + r * 0.7f, p);
                    canvas.drawRect(sx - r * 0.7f, sy - r * 0.7f, sx + r * 0.7f, sy + r * 0.7f, stroke);
                    stroke.setColor(0xCCFFFFFF);
                    canvas.drawLine(sx - r * 0.7f, sy, sx + r * 0.7f, sy, stroke);
                    canvas.drawLine(sx, sy - r * 0.7f, sx, sy + r * 0.7f, stroke);
                    stroke.setColor(0xCC000000);
                    break;
                }
                case "swamp_hut": {
                    // 女巫小屋：棕色小屋
                    p.setColor(0xFF6B4F33);
                    canvas.drawRect(sx - r * 0.6f, sy - r * 0.6f, sx + r * 0.6f, sy + r * 0.6f, p);
                    canvas.drawRect(sx - r * 0.6f, sy - r * 0.6f, sx + r * 0.6f, sy + r * 0.6f, stroke);
                    canvas.drawRect(sx - r * 0.15f, sy - r * 0.15f, sx + r * 0.15f, sy + r * 0.15f, stroke);
                    break;
                }
                case "ocean_monument": {
                    // 海底神殿：青绿方尖碑
                    p.setColor(0xFF3E8E7E);
                    canvas.drawRect(sx - r * 0.5f, sy - r * 0.9f, sx + r * 0.5f, sy + r * 0.6f, p);
                    canvas.drawRect(sx - r * 0.5f, sy - r * 0.9f, sx + r * 0.5f, sy + r * 0.6f, stroke);
                    canvas.drawRect(sx - r * 0.25f, sy - r * 0.55f, sx + r * 0.25f, sy - r * 0.3f, stroke);
                    break;
                }
                case "outpost": {
                    // 掠夺者前哨站：深灰塔
                    p.setColor(0xFF4E4E45);
                    canvas.drawRect(sx - r * 0.45f, sy - r * 0.9f, sx + r * 0.45f, sy + r * 0.6f, p);
                    canvas.drawRect(sx - r * 0.45f, sy - r * 0.9f, sx + r * 0.45f, sy + r * 0.6f, stroke);
                    canvas.drawRect(sx - r * 0.3f, sy - r * 0.55f, sx + r * 0.3f, sy - r * 0.25f, stroke);
                    break;
                }
                case "end_city": {
                    // 末地城：紫色尖塔
                    p.setColor(0xFFB58CD6);
                    canvas.drawRect(sx - r * 0.45f, sy - r * 0.9f, sx + r * 0.45f, sy + r * 0.6f, p);
                    canvas.drawRect(sx - r * 0.45f, sy - r * 0.9f, sx + r * 0.45f, sy + r * 0.6f, stroke);
                    canvas.drawRect(sx - r * 0.2f, sy - r * 0.55f, sx + r * 0.2f, sy - r * 0.2f, stroke);
                    break;
                }
            }
    }

    /** 单击坐标标记：十字 + 坐标标签（HTML 原型点击显示坐标行为）。 */
    private boolean entitiesLogged = false;

    private void drawTapMarker(Canvas canvas) {
        if (tapBlockX < 0 || tapBlockZ < 0 || map == null) {
            return;
        }
        float sx = worldToScreenX(tapBlockX + 0.5f);
        float sy = worldToScreenY(tapBlockZ + 0.5f);
        if (sx < 0 || sx > getWidth() || sy < 0 || sy > getHeight()) {
            return;
        }
        Paint line = new Paint();
        line.setAntiAlias(true);
        line.setStrokeWidth(2f);
        float arm = Math.max(10f, pixelsPerBlock * 0.8f);
        line.setColor(0x99000000);
        canvas.drawLine(sx - arm, sy, sx + arm, sy, line);
        canvas.drawLine(sx, sy - arm, sx, sy + arm, line);
        line.setStrokeWidth(1f);
        line.setColor(0xFFFFFFFF);
        canvas.drawLine(sx - arm, sy, sx + arm, sy, line);
        canvas.drawLine(sx, sy - arm, sx, sy + arm, line);
        // 坐标标签
        String text = "(" + tapBlockX + ", " + tapBlockZ + ")";
        Paint labelPaint = new Paint();
        labelPaint.setAntiAlias(true);
        labelPaint.setTextSize(11f);
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        labelPaint.setColor(0xFFFFFFFF);
        float tw = labelPaint.measureText(text);
        Paint bgPaint = new Paint();
        bgPaint.setColor(0xCC000000);
        canvas.drawRoundRect(sx - tw / 2f - 5f, sy + arm + 3f, sx + tw / 2f + 5f,
                sy + arm + 3f + labelPaint.getTextSize() + 7f, 6f, 6f, bgPaint);
        canvas.drawText(text, sx - tw / 2f, sy + arm + 3f + labelPaint.getTextSize(), labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (map == null) {
            return super.onTouchEvent(event);
        }
        // 阻止父布局（ScrollView 等）拦截拖动手势
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        // 松手：全量重采样一帧（拖动期间是位图平移预览，边缘/新区域要刷新）
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (!scalePreviewActive) {
                invalidateFullRender();
            }
        }
        return true;
    }

    /** 命中检测：点击位置 24px 内的标点。 */
    private BlueprintDb.Point hitTestPoint(float sx, float sy) {
        for (BlueprintDb.Point p : points) {
            if (!p.dimension.equals(dimension)) {
                continue;
            }
            float[] s = blockToScreen(p.x, p.z);
            if (Math.abs(s[0] - sx) <= 24f && Math.abs(s[1] - sy) <= 24f) {
                return p;
            }
        }
        return null;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            if (factor <= 0f || Float.isNaN(factor) || Float.isInfinite(factor)) {
                return true;
            }
            float newPpb = clampPixelsPerBlock(pixelsPerBlock * factor);
            float applied = newPpb / pixelsPerBlock;
            // 以捏合焦点为锚点缩放
            offsetX = detector.getFocusX() - (detector.getFocusX() - offsetX) * applied;
            offsetY = detector.getFocusY() - (detector.getFocusY() - offsetY) * applied;
            pixelsPerBlock = newPpb;
            // 缩放期间用缓存位图做预览（不重采样，保证捏合流畅），结束全量重绘
            scaleFocusX = detector.getFocusX();
            scaleFocusY = detector.getFocusY();
            scalePreviewActive = cachedBmp != null;
            clampTranslation();
            invalidate();
            notifyViewChanged();
            return true;
        }

        @Override
        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
            // 捏合结束：全量重采样一帧
            scalePreviewActive = false;
            invalidate();
            notifyViewChanged();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        /** 本次触摸累计移动距离（单击微动不清除十字标记） */
        private float scrollAccum = 0f;

        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            scrollAccum = 0f;
            return true;
        }

        @Override
        public void onLongPress(@NonNull MotionEvent e) {
            // 长按 → 地图坐标回调（添加标点）
            tapBlockX = -1;
            tapBlockZ = -1;
            if (listener != null && map != null) {
                int[] b = screenToBlock(e.getX(), e.getY());
                listener.onLongPress(b[0], b[1]);
            }
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            if (map == null) {
                return false;
            }
            // 单击标点 → 详情回调
            BlueprintDb.Point hit = hitTestPoint(e.getX(), e.getY());
            if (hit != null && listener != null) {
                listener.onPointClick(hit);
                return true;
            }
            // 单击空地 → 显示该处坐标（十字标记 + 标签，HTML 原型行为）
            int[] b = screenToBlock(e.getX(), e.getY());
            tapBlockX = b[0];
            tapBlockZ = b[1];
            invalidate();
            if (listener != null) {
                listener.onMapTap(b[0], b[1]);
            }
            return true;
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                float distanceX, float distanceY) {
            offsetX -= distanceX;
            offsetY -= distanceY;
            // 拖动超过 16px 才清除点击标记（单击时手指微动不吞掉十字）
            scrollAccum += Math.abs(distanceX) + Math.abs(distanceY);
            if (scrollAccum > 16f) {
                tapBlockX = -1;
                tapBlockZ = -1;
            }
            clampTranslation();
            invalidate();
            notifyViewChanged();
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            if (map == null) {
                return true;
            }
            // 双击缩放时清除单击坐标标记
            tapBlockX = -1;
            tapBlockZ = -1;
            // 整图适配 ↔ 2 倍放大（相对当前）
            float fit = Math.min(getWidth() / (float) map.width, getHeight() / (float) map.height);
            if (pixelsPerBlock > fit * 1.5f) {
                fitToView();
            } else {
                float target = clampPixelsPerBlock(fit * 4f);
                float applied = target / pixelsPerBlock;
                offsetX = e.getX() - (e.getX() - offsetX) * applied;
                offsetY = e.getY() - (e.getY() - offsetY) * applied;
                pixelsPerBlock = target;
                clampTranslation();
            }
            invalidate();
            notifyViewChanged();
            return true;
        }
    }
}
