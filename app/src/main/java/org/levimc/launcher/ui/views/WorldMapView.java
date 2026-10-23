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
import org.levimc.launcher.R;

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
    // v0.4.110：最大缩放倍率用户可调（默认 36，档位含无限）
    private float maxPixelsPerBlock = 36f;
    private static final float UNLIMITED_PPB = 1000000f;
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
    private volatile boolean showBiomeLayer = false;
    /** 矿物热力图图层（chunk 级矿物密度色）。 */
    private boolean showOreLayer = false;
    private boolean showEntities = false;
    private boolean showStructures = false;
    private boolean showSlimeChunks = false;
    /** 单击显示坐标：点击处十字标记（-1 = 无） */
    private int tapBlockX = -1;
    private int tapBlockZ = -1;
    // v0.4.138：十字标记屏幕锚定坐标（显示层；tapBlock 为读取层）
    private float tapScreenX = -1f;
    private float tapScreenY = -1f;
    // v0.4.139：指针显示模式（HUD 勾选开关）
    // v0.4.146：删「指针始终显示」开关（用户拍板）——指针默认
    // 屏幕锚定（始终显示同款逻辑），手势中隐藏
    private boolean tapCenterEnabled = false; // 指针固定地图查看器中心点
    private final android.graphics.RectF centerRowRect = new android.graphics.RectF();
    /** 连线模式 / 测距模式：高亮待选起点 */
    private boolean linkPickMode = false;
    private long highlightPointId = -1;

    private OnMapInteractListener listener;
    private OnViewChangedListener viewChangedListener;
    /** v417：上次通知 HUD 时的缩放倍率（onDraw 变化检测兜底）。 */
    private float lastNotifiedPpb = -1f;

    /** v424：缩放倍率写入统一入口——任何路径改倍率立即通知 HUD
     *  （"力大砖飞"：无条件直接通知，不做差异判断——v363 实时显示
     *  的体验回归，此前条件检测在部分缩放路径漏通知）。
     *  v425：独立 zoomListener 链路——与 viewChangedListener（坐标
     *  HUD）完全隔离，杜绝烘焙 HUD 等其它 setText 干扰。 */
    private void setPpb(float v) {
        pixelsPerBlock = v;
        if (zoomListener != null && !scaling) {
            // v0.4.98：捏合动画中冻结 HUD 更新（每帧 setText 触发布局/
            // 重录=120Hz 下主线程 8ms 帧的帮凶），onScaleEnd 补发
            zoomListener.onZoomChanged(v);
        }
        notifyViewChanged();
    }

    /** v425：缩放倍率变化回调（HUD 倍率实时显示专用链路）。 */
    public interface OnZoomChangedListener {
        void onZoomChanged(float pixelsPerBlock);
    }

    private OnZoomChangedListener zoomListener;

    public void setOnZoomChangedListener(OnZoomChangedListener l) {
        this.zoomListener = l;
    }

    /** v403：打开时整图适配（fit 全图显示"大的缩放比例"）——
     *  主世界大地图用（用户反馈打开只看到放大 26 倍的一小块，
     *  要缩很多次才看到全貌）；下界/末地保持放大起步（v380
     *  教训：fit 后 ×0.44 太小进 LOD 黑屏）。setWorldMap 前设置。 */
    public boolean initialFitAll = false;

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

    /** 当前缩放倍率（每方块像素数，HUD 显示用）。 */
    public float getPixelsPerBlock() {
        return pixelsPerBlock;
    }

    /** v0.4.7：手柄平移（R3+右摇杆拖动地图），屏幕像素单位。 */
    public void panBy(float dxPx, float dyPx) {
        offsetX += dxPx;
        offsetY += dyPx;
        tapBlockX = -1;
        tapBlockZ = -1;
        clampTranslation();
        invalidate();
        notifyViewChanged();
    }

    /** v0.4.7：手柄缩放（屏幕中心锚点），factor>1 放大 <1 缩小。 */
    public void zoomBy(float factor) {
        if (map == null || factor <= 0f || Float.isNaN(factor) || Float.isInfinite(factor)) {
            return;
        }
        float newPpb = clampPixelsPerBlock(pixelsPerBlock * factor);
        float applied = newPpb / pixelsPerBlock;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        offsetX = cx - (cx - offsetX) * applied;
        offsetY = cy - (cy - offsetY) * applied;
        setPpb(newPpb);
        clampTranslation();
        invalidate();
        notifyViewChanged();
    }

    /** v0.4.7：屏幕坐标处群系 id（手柄光标悬停显示群系名用）。 */
    public int biomeIdAt(float sx, float sy) {
        if (map == null) {
            return -1;
        }
        int bx = (int) ((sx - (double) offsetX) / (double) pixelsPerBlock) + map.minBlockX;
        int bz = (int) ((sy - (double) offsetY) / (double) pixelsPerBlock) + map.minBlockZ;
        return map.biomeIdAtBlock(bx, bz);
    }

    /** 清空全部 chunk 渲染数据（渲染参数变化时调用——如忽略光源开关）：
     *  只清 chunkColors 不够——旧 tile 会残留旧渲染结果
     *  （"开了忽略光源还是显示黄色"的根因）。 */
    public void clearChunkData() {
        for (Bitmap b : chunkTiles.values()) {
            b.recycle();
        }
        chunkTiles.clear();
        clearChunkLod();
        chunkDataCache.clear();
        invalidate();
    }

    /** v0.4.100：清 chunk LOD 超级块缓存（chunk 数据/图层变化时）——
     *  代次 +1 让在途后台生成结果跨图丢弃。 */
    private void clearChunkLod() {
        chunkGen++;
        for (Bitmap b : chunkLodCache.values()) {
            b.recycle();
        }
        chunkLodCache.clear();
        chunkLodPending.clear();
    }

    /** 烘焙/增量更新渲染完成后调用（v0.4.59：LOD 已删除，只需重绘）。 */
    public void onChunksRendered(java.util.Set<Long> chunkKeys) {
        // v0.4.102：空集合=烘焙完成信号——块图基于旧 chunk 数据已过期，
        // 清缓存重新预生成（烘焙中每批不清：否则块永远生成不完）
        if (chunkKeys != null && chunkKeys.isEmpty()) {
            clearChunkLod();
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
        clearChunkLod();
        chunkDataCache.clear();
        // v0.4.52：换图后旧全图失效（新图数据不同，调用方会重拼）
        clearFullMapImage();
        // v0.4.54：换图后旧史莱姆遮罩失效
        if (slimeMaskBitmap != null && !slimeMaskBitmap.isRecycled()) {
            slimeMaskBitmap.recycle();
        }
        slimeMaskBitmap = null;
        // v0.4.53：map 自带 RLE 全图（秒开缓存）时直接挂载
        if (map != null && map.fullImage != null && !map.fullImage.isRecycled()) {
            installFullMap(map.fullImage, map.fullImageW, map.fullImageH,
                    map.fullImageBlockScale);
        }
        if (!keepView || !viewInitialized) {
            initialView();
            viewInitialized = true;
        }
        invalidateFullRender();
        notifyViewChanged();
    }

    // ---- v0.4.52：全图纹理模式（HTML 同款渲染——一张降采样全图 +
    //      drawBitmap 变换，平移缩放纯 GPU；未就绪时回退 chunk tile） ----

    /** 全图 Bitmap（拼好后设置；null = 未就绪，走原 chunk 渲染）。 */
    private android.graphics.Bitmap fullMapBitmap;
    private int fullMapWidth;
    private int fullMapHeight;
    /** 全图 1 像素对应的方块数（map.width / 全图宽）。 */
    private int fullMapBlockScale;
    /** v0.4.61：全图绘制最近邻采样（HTML imageSmoothingEnabled=false
     *  同款）——双线性会把色块地图糊掉（"还是糊"的根因）。 */
    private final android.graphics.Paint fullMapPaint = new android.graphics.Paint();
    /** v0.4.81 恢复：mip 未就绪时缩小的双线性兜底画笔（v0.4.53 同款
     *  软糊观感；v0.4.76 删掉后最近邻缩小混叠=刚打开糊根因）。 */
    private android.graphics.Paint fullMapLinearPaint;
    private final android.graphics.RectF fullMapDst = new android.graphics.RectF();
    /** 全图模式的缩放上限（px/方块；HTML 等效 2048x）。 */
    private static final float FULL_MAX_PIXELS_PER_BLOCK = 512f;

    /** 设置全图纹理（接管 Bitmap 所有权，WorldMapView 负责回收）。 */
    public void setFullMapImage(android.graphics.Bitmap bmp, int composedW, int composedH,
                                int blockScale) {
        if (fullMapBitmap != null && fullMapBitmap != bmp) {
            releaseLodChain();
            if (lodInFlight > 0) {
                // 后台 LOD 任务还在读旧源：退休延后回收（任务结束自会释放）
                retiredSources.add(fullMapBitmap);
            } else {
                fullMapBitmap.recycle();
            }
        }
        installFullMap(bmp, composedW, composedH, blockScale);
    }

    private void installFullMap(android.graphics.Bitmap bmp, int w, int h, int blockScale) {
        fullMapBitmap = bmp;
        fullMapWidth = w;
        fullMapHeight = h;
        fullMapBlockScale = Math.max(1, blockScale);
        invalidateFullRender();
        prewarmLod();
    }

    /** v0.4.97：全图就绪后预热常用 LOD 级——m2/m4 覆盖 scalePx≥0.19
     *  的绝大多数缩放区间，缩放动画中切换级别不再兜底原图（那是
     *  "开了 LOD 还卡"的一半原因）。m8+ 保持按需（内存考虑）。
     *  v0.4.98：小图（≤2048²）不走 LOD，跳过预热。 */
    private void prewarmLod() {
        if (!lodEnabled || fullMapBitmap == null || fullMapBitmap.isRecycled()) {
            return;
        }
        // v0.4.154：小图（≤2048²）也预热 m2（缩小走 LOD 选级后
        // 需要 mip 就绪，否则首帧兜底双线性=糊）——m2 位图 1/4
        // 内存（≤4MB），代价可忽略
        int warmTo = Math.min(lodMaxLevel, 4);
        for (int lv = 2; lv <= warmTo; lv *= 2) {
            requestLodLevel(lv);
        }
    }

    private void clearFullMapImage() {
        if (fullMapBitmap == null) {
            return;
        }
        releaseLodChain();
        if (lodInFlight > 0) {
            retiredSources.add(fullMapBitmap);
        } else if (!fullMapBitmap.isRecycled()) {
            fullMapBitmap.recycle();
        }
        fullMapBitmap = null;
    }

    /** 全图模式绘制：单次 drawBitmap + GPU 变换（零 CPU 重采样）。
     *  放大（scalePx≥1）最近邻保持锐利色块；缩小（scalePx<1）走 LOD
     *  金字塔（v0.4.95，HTML 瓦片 mip 同款——按倍率选预降采样图，
     *  避免全图双线性缩小="缩到一定比例卡"根因）。 */
    private void drawFullMapLayer(android.graphics.Canvas canvas) {
        if (map == null || fullMapBitmap == null || fullMapBitmap.isRecycled()) {
            return;
        }
        curLodSpan = 0;
        float scalePx = fullMapBlockScale * pixelsPerBlock;
        fullMapDst.set(offsetX, offsetY,
                offsetX + fullMapWidth * scalePx,
                offsetY + fullMapHeight * scalePx);
        if (scalePx >= 1f) {
            canvas.drawBitmap(fullMapBitmap, null, fullMapDst, fullMapPaint);
            lastDrawPath = "full-near";
            return;
        }
        if (fullMapLinearPaint == null) {
            fullMapLinearPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        }
        // v0.4.98：小图（≤2048²）放大恒走原图——LOD 降采样只会糊它
        // （fit 视图 m2 放大 1.5-1.9 倍最近邻=色块，"小地图糊"根因）
        // v0.4.154：缩小不再双线性兜底——2048 全图双线性缩小=软糊
        // （重新烘焙后保留缩小视图「地图糊、退出重进才恢复」根因），
        // 缩小走下方 LOD 选级路径（mip 最近邻，清晰）
        if (fullMapWidth <= 2048 && fullMapHeight <= 2048 && scalePx >= 1f) {
            canvas.drawBitmap(fullMapBitmap, null, fullMapDst, fullMapPaint);
            lastDrawPath = "full-small";
            return;
        }
        if (!lodEnabled) {
            // LOD 关：原图直接双线性缩小（v0.4.87 行为）
            canvas.drawBitmap(fullMapBitmap, null, fullMapDst, fullMapLinearPaint);
            lastDrawPath = "full-linear";
            return;
        }
        // 选级：level×scalePx ≥ 阈值的最大 2 的幂（level=1 即原图，
        // 缩到阈值以下就换 1/2、1/4…——HTML mip 同款区间）
        // v0.4.111：阈值×帧率自适应系数——小地图全图路径也吃自适应
        // （低帧率更早切级=糊换流畅，覆盖 chunk 路径之外的小地图）
        int level = 1;
        // v0.4.118：全程实时自适应；v0.4.119 ppb≤1 更激进
        // （与 chunk 路径共用 effectiveAdapt）
        while (scalePx * level < lodThreshold * effectiveAdapt()
                && level < lodMaxLevel) {
            level *= 2;
        }
        Bitmap lodBmp = lodLevels[Integer.numberOfTrailingZeros(level)];
        if (lodBmp == null || lodBmp.isRecycled()) {
            // 该级未就绪：原图双线性兜底（与 HTML 生成中先画上一级同款）+ 后台生成
            canvas.drawBitmap(fullMapBitmap, null, fullMapDst, fullMapLinearPaint);
            requestLodLevel(level);
            lastDrawPath = "lod-fallback";
            return;
        }
        // v0.4.98：LOD 级恒双线性——最近邻放大 m2=色块放大糊
        // （"小地图糊"的另一半原因）；原图放大（scalePx≥1）仍最近邻
        // 保持像素锐利。m_k 显示比例接近 1，双线性代价可忽略
        canvas.drawBitmap(lodBmp, null, fullMapDst, fullMapLinearPaint);
        lastDrawPath = "lod" + level;
    }

    // ---- v0.4.95：LOD 金字塔（HTML 瓦片 mip 同款）——按需后台生成
    //      1/2、1/4… 降采样链，缩放时按倍率选级，单次 drawBitmap ----

    /** LOD 开关（关=原图直接缩小，v0.4.87 行为）。 */
    private boolean lodEnabled = true;
    /** 切换阈值：scalePx 缩到阈值以下才切下一级（默认 0.75，HTML 同款）。 */
    private float lodThreshold = 0.75f;
    /** 最大降采样倍数（2 的幂：2/4/8/16/32/64/128/256，自定义输入）。 */
    private int lodMaxLevel = 16;
    /** 降采样链：index=log2(倍数)，lodLevels[1]=1/2、[2]=1/4…（[0] 恒空=原图）。 */
    private final android.graphics.Bitmap[] lodLevels = new android.graphics.Bitmap[9];
    private final boolean[] lodPending = new boolean[9];
    /** 被替换后等待在途 LOD 任务结束再回收的源（避免后台 createScaledBitmap
     *  读已回收位图崩溃——Bitmap 原生像素在 recycle 后不可读）。 */
    private final java.util.List<android.graphics.Bitmap> retiredSources =
            new java.util.ArrayList<>();
    /** 引用旧源的在途 LOD 任务数（只在主线程增减）。 */
    private int lodInFlight = 0;
    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService LOD_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "WorldMapView-LOD");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    public void setLodEnabled(boolean enabled) {
        lodEnabled = enabled;
        invalidate();
    }

    public void setLodThreshold(float threshold) {
        // v0.4.97：范围放开到 0.05~0.99（用户要更大调节空间）
        lodThreshold = Math.max(0.05f, Math.min(0.99f, threshold));
        invalidate();
    }

    public void setLodMaxLevel(int level) {
        // v0.4.98：自定义输入支持 2-256，钳到 2 的幂
        lodMaxLevel = Math.max(2, Math.min(256, Integer.highestOneBit(level)));
        // 清理超出新上限的链级
        int maxIdx = Integer.numberOfTrailingZeros(lodMaxLevel);
        for (int i = maxIdx + 1; i < lodLevels.length; i++) {
            if (lodLevels[i] != null) {
                lodLevels[i].recycle();
                lodLevels[i] = null;
            }
            lodPending[i] = false;
        }
        invalidate();
    }

    /** 释放 LOD 链（换图/清图时调用；原图位图由调用方管理）。 */
    private void releaseLodChain() {
        for (int i = 0; i < lodLevels.length; i++) {
            if (lodLevels[i] != null) {
                lodLevels[i].recycle();
                lodLevels[i] = null;
            }
            lodPending[i] = false;
        }
    }

    /** 后台生成第 level 级降采样图（幂等：已生成/已排队则跳过）。 */
    private void requestLodLevel(int level) {
        final int idx = Integer.numberOfTrailingZeros(level);
        if (lodLevels[idx] != null || lodPending[idx]) {
            return;
        }
        final android.graphics.Bitmap src = fullMapBitmap;
        if (src == null || src.isRecycled()) {
            return;
        }
        lodPending[idx] = true;
        lodInFlight++;
        LOD_EXECUTOR.execute(() -> {
            android.graphics.Bitmap scaled = null;
            try {
                if (!src.isRecycled()) {
                    scaled = android.graphics.Bitmap.createScaledBitmap(src,
                            Math.max(1, src.getWidth() / level),
                            Math.max(1, src.getHeight() / level), true);
                }
            } catch (Throwable ignored) {
                // OOM/源已回收：兜底绘制继续用原图
            }
            final android.graphics.Bitmap out = scaled;
            MAIN_HANDLER.post(() -> {
                lodInFlight--;
                lodPending[idx] = false;
                if (fullMapBitmap != src) {
                    // 源已被替换：丢弃结果，回收自己引用的退休源
                    if (out != null) {
                        out.recycle();
                    }
                    if (retiredSources.remove(src)) {
                        src.recycle();
                    }
                    return;
                }
                if (out == null) {
                    return;
                }
                android.graphics.Bitmap old = lodLevels[idx];
                lodLevels[idx] = out;
                if (old != null && old != out) {
                    old.recycle();
                }
                invalidate();
            });
        });
    }

    // ---- v0.4.54：史莱姆 chunk 遮罩（每 chunk 1 像素预渲染位图，
    //      单次 drawBitmap——替代每帧几千次 drawRect 的卡顿） ----

    private android.graphics.Bitmap slimeMaskBitmap;
    private int slimeMaskCx0;
    private int slimeMaskCz0;
    private int slimeMaskW;
    private int slimeMaskH;
    private final android.graphics.RectF slimeMaskDst = new android.graphics.RectF();
    /** 遮罩缩小绘制用（filterBitmap=true，惰性建）。 */
    private Paint slimeMaskSoftPaint;

    /** 设置史莱姆遮罩（接管 Bitmap 所有权）；null 清除（回退逐块绘制）。 */
    public void setSlimeMask(android.graphics.Bitmap mask, int cx0, int cz0, int w, int h) {
        if (slimeMaskBitmap != null && slimeMaskBitmap != mask && !slimeMaskBitmap.isRecycled()) {
            slimeMaskBitmap.recycle();
        }
        slimeMaskBitmap = mask;
        slimeMaskCx0 = cx0;
        slimeMaskCz0 = cz0;
        slimeMaskW = w;
        slimeMaskH = h;
        invalidate();
    }

    private boolean viewInitialized = false;

    /** 强制下一帧全量重采样（换图/图层切换等底层颜色变化时必须调用）。 */
    private void invalidateFullRender() {
        cachedPpb = -1f;
        invalidate();
    }

    /** 实体列表（结构详情弹窗附近实体查询）。 */
    public List<WorldMapRenderer.EntityPos> getEntities() {
        return entities;
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
        clearChunkLod();
        // v0.4.122：遮罩位图由 Activity 层按开关重新生成
        invalidateFullRender();
    }

    /** 矿物热力图图层开关（v396：chunk 级矿物密度色）。 */
    public void setShowOreLayer(boolean show) {
        this.showOreLayer = show;
        for (Bitmap b : chunkTiles.values()) {
            b.recycle();
        }
        chunkTiles.clear();
        clearChunkLod();
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
        // v427：拷贝隔离——调用方持有活动列表，直接引用会被外部
        // removeIf 静默修改（结构开关"关掉不显示"的帮凶）
        this.structures = structures != null
                ? new ArrayList<>(structures) : new ArrayList<>();
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

    /** v0.4.67：外部查询区块是否已渲染（标点&结构列表过滤未加载
     *  区块条目——与地图标记同语义）。 */
    public boolean isChunkRendered(int blockX, int blockZ) {
        return chunkRendered(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
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

    /** 屏幕坐标 → 世界 block 坐标（double 计算，同精度修复）。
     *  v0.4.7：map 为空返回 null（悬停 HUD 查询时防 NPE）。 */
    public int[] screenToBlock(float sx, float sy) {
        if (map == null) {
            return null;
        }
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
        // v0.4.71：电影式跳转——先把缩放比例调小（全景过渡）、定位、
        // 再放大到目标倍率，平滑稍慢（800ms）
        final float fitPpb = Math.max(MIN_PIXELS_PER_BLOCK, fitScale());
        final float startPpb = pixelsPerBlock;
        final float startOffsetX = offsetX;
        final float startOffsetY = offsetY;
        final float dOffsetX = getWidth() / 2f - (blockX - map.minBlockX + 0.5f) * targetPpb - startOffsetX;
        final float dOffsetY = getHeight() / 2f - (blockZ - map.minBlockZ + 0.5f) * targetPpb - startOffsetY;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(800);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        // 动画期间同样用缓存位图预览（以屏幕中心为锚），结束全量重绘
        scaleFocusX = getWidth() / 2f;
        scaleFocusY = getHeight() / 2f;
        scalePreviewActive = true;
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // V 形缩放：前 40% 缩小到全景（fit），后 60% 放大到目标
            float ppb;
            if (t < 0.4f) {
                ppb = startPpb + (fitPpb - startPpb) * (t / 0.4f);
            } else {
                ppb = fitPpb + (targetPpb - fitPpb) * ((t - 0.4f) / 0.6f);
            }
            setPpb(ppb);
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
                // v0.4.69：跳转结束强制全量重采样——此前仅 invalidate，
                // 若 ppb 未变且跳转距离小于视口宽，farMoved 判定不触发，
                // 平移缓存位图只覆盖旧视口，"跳转目标区块不渲染"的根因
                invalidateFullRender();
                notifyViewChanged();
            }
        });
        anim.start();
    }

    /** 初始视图：比整图适配放大 4 倍，居中于玩家附近有数据的位置（未生成区块回退最近生成区）。 */
    private void initialView() {
        float fit = fitScale();
        if (fit <= 0f) {
            setPpb(1f);
            offsetX = 0f;
            offsetY = 0f;
            return;
        }

        // v416：大世界打开默认最大倍率（打开只渲染视口一两个区块，
        // 秒显示不黑屏）；v0.4.112：小世界（卫星图数组）打开 fit 全图
        // ——"小地图默认缩放比例太高"修复（此前小地图也走最大倍率起步）
        // v0.4.113：主世界大图也 fit 起步（用户"大地图默认最大倍率"
        // 反馈；clod 渐进填充保证秒显）——下界/末地保持放大起步
        // （v380 教训：fit 进 LOD 黑屏），由 initialFitAll 按维度区分；
        // 有记忆时 restoreView 覆盖本方法
        if (map.colors != null || initialFitAll) {
            setPpb(fit);
        } else {
            setPpb(maxPixelsPerBlock);
        }
        // 目标点：出生点 > 玩家（打开地图停在出生点，用户要求）；
        // 若该处未生成（透明），螺旋找最近的有数据位置
        int targetX = map.spawnBlockX >= 0 ? map.spawnBlockX
                : map.playerBlockX >= 0 ? map.playerBlockX
                : map.minBlockX + map.width / 2;
        int targetZ = map.spawnBlockZ >= 0 ? map.spawnBlockZ
                : map.playerBlockZ >= 0 ? map.playerBlockZ
                : map.minBlockZ + map.height / 2;
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
    public void fitToView() {
        float fit = fitScale();
        if (fit <= 0f) {
            setPpb(1f);
            offsetX = 0f;
            offsetY = 0f;
            return;
        }
        setPpb(clampPixelsPerBlock(fit));
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
        // v412：最小缩放 = 整图适配（fit 全图）——"缩放到刚好看到
        // 全部地图就行"，不能再缩出地图外（fitScale 未就绪时用常量）
        float min = Math.max(MIN_PIXELS_PER_BLOCK, fitScale());
        // v0.4.63：缩放上限统一恢复 24 px/块（用户线索「不糊的时候
        // 就是 24 的倍率」——512 无限拉大即全图模式糊状态的标志）。
        // 放大路径走 tile/cachedBmp 1px/块原分辨率，24 上限内锐利
        float max = maxPixelsPerBlock;
        return Math.max(min, Math.min(max, v));
    }

    /** 修正平移边界：v412 拖拽范围放宽——地图可拖出视口 40%
     *  （用户要求"拖拽范围大一点"；小于视图时仍居中）。 */
    private void clampTranslation() {
        if (map == null || getWidth() <= 0) {
            return;
        }
        float mapW = map.width * pixelsPerBlock;
        float mapH = map.height * pixelsPerBlock;
        if (mapW <= getWidth()) {
            offsetX = (getWidth() - mapW) / 2f;
        } else {
            float slackX = getWidth() * 0.4f;
            offsetX = Math.min(slackX,
                    Math.max(getWidth() - mapW - slackX, offsetX));
        }
        if (mapH <= getHeight()) {
            offsetY = (getHeight() - mapH) / 2f;
        } else {
            float slackY = getHeight() * 0.4f;
            offsetY = Math.min(slackY,
                    Math.max(getHeight() - mapH - slackY, offsetY));
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
    /** 缩放进行中：暂停按需渲染缺失报告（缩放停止后恢复）。 */
    private boolean scaling;
    /** v0.4.99：拖拽中（onScroll 连续回调期间）——与捏合同样跳过叠加层。 */
    private boolean panning;
    /** v0.4.99：诊断字段——本帧绘制路径（节流日志用）。 */
    private String lastDrawPath = "-";
    // v0.4.118：tile 绘制 Paint/RectF 字段复用（每帧 700 张 tile 不新建对象）
    private final android.graphics.Paint tilePaint = new android.graphics.Paint();
    private final android.graphics.RectF tileDst = new android.graphics.RectF();

    // v0.4.123：标点/叠加层绘制 Paint/RectF/Path 字段复用——此前
    // 实体/结构/标点循环内每帧新建 Paint/RectF（几千标点 = 每帧几千
    // 个小对象 = GC 抖动掉帧，AI 诊断属实）
    private final android.graphics.Paint entFillPaint = new android.graphics.Paint();
    private final android.graphics.Paint entLabelPaint = new android.graphics.Paint();
    private final android.graphics.Paint entIconPaint = new android.graphics.Paint();
    private final android.graphics.Paint entBgPaint = new android.graphics.Paint();
    private final android.graphics.RectF entIconDst = new android.graphics.RectF();
    private final android.graphics.Paint structPaint = new android.graphics.Paint();
    private final android.graphics.Paint structStrokePaint = new android.graphics.Paint();
    private final android.graphics.Paint structLabelPaint = new android.graphics.Paint();
    private final android.graphics.Paint structIconPaint = new android.graphics.Paint();
    private final android.graphics.Paint structBgPaint = new android.graphics.Paint();
    private final android.graphics.RectF structIconDst = new android.graphics.RectF();
    private final android.graphics.Paint oreTexPaint =
            new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Path orePath = new android.graphics.Path();
    private final android.graphics.RectF oreIconDst = new android.graphics.RectF();
    private final android.graphics.Paint pointLabelPaint = new android.graphics.Paint();
    private final android.graphics.Paint pointHlPaint = new android.graphics.Paint();
    {
        entFillPaint.setAntiAlias(true);
        entLabelPaint.setAntiAlias(true);
        entLabelPaint.setTextSize(10f);
        entLabelPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-condensed", android.graphics.Typeface.BOLD));
        entLabelPaint.setColor(0xFFFFFFFF);
        entIconPaint.setFilterBitmap(true);
        structPaint.setAntiAlias(true);
        structStrokePaint.setAntiAlias(true);
        structStrokePaint.setStyle(android.graphics.Paint.Style.STROKE);
        structLabelPaint.setAntiAlias(true);
        structLabelPaint.setTextSize(10f);
        structLabelPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-condensed", android.graphics.Typeface.BOLD));
        structLabelPaint.setColor(0xFFFFFFFF);
        structIconPaint.setFilterBitmap(true);
        structBgPaint.setAntiAlias(true);
        structBgPaint.setColor(0xD9FFFFFF);
        pointLabelPaint.setAntiAlias(true);
        pointLabelPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-condensed", android.graphics.Typeface.BOLD));
        pointHlPaint.setAntiAlias(true);
        pointHlPaint.setStyle(android.graphics.Paint.Style.STROKE);
        pointHlPaint.setColor(0xFFFFFFFF);
    }

    private int dbgFrame;

    /** v0.4.109：HUD 静止刷新——Choreographer 帧循环，每 500ms
     *  invalidate 一次让 FPS HUD 持续更新（静止不操作时也跳动）。 */
    private final android.view.Choreographer.FrameCallback hudRefreshCallback =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    // v0.4.112：每帧局部 invalidate HUD 区域——静止时
                    // 帧率数字持续跳动（游戏式实时 FPS）；地图主体
                    // 因 clip 跳过不重绘（零浪费）
                    if (hudRect.width() > 0) {
                        invalidate((int) hudRect.left - 16,
                                (int) hudRect.top - 16,
                                (int) hudRect.right + 16,
                                (int) hudRect.bottom + 48);
                    } else {
                        invalidate();
                    }
                    android.view.Choreographer.getInstance()
                            .postFrameCallback(this);
                }
            };
    private long hudLastInvalidate = 0;
    private final android.graphics.RectF hudRect = new android.graphics.RectF();

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        android.view.Choreographer.getInstance()
                .postFrameCallback(hudRefreshCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        android.view.Choreographer.getInstance()
                .removeFrameCallback(hudRefreshCallback);
        super.onDetachedFromWindow();
    }

    // ---- v0.4.106：帧率自适应 LOD（3A 游戏同款）——帧率低自动
    //      加大「N 方块=1 像素」（span 提升=块更粗=更流畅），恢复后
    //      回落用户设定值；右上角 FPS HUD 实时显示。 ----
    private boolean lodAdaptive = true;
    /** 帧率 EMA（帧/秒）。 */
    private float fpsEma = 60f;
    /** 自适应系数 EMA（1=用户设定值，低帧率时升高）。 */
    private float adaptEma = 1f;
    // v0.4.137：本帧实际绘制标点计数（HUD 显示，直观看到抽稀效果）
    private int drawnEntities = 0;
    private int drawnOres = 0;
    private int drawnStructures = 0;
    // v0.4.138：矿石抽稀步长记忆（手势动画中冻结防闪烁）
    private int lastOreStride = 1;
    // v0.4.140：实体抽稀步长记忆（同款）
    private int lastEntStride = 1;
    // v0.4.133：读取状态（FPS HUD 附加行，读取完成置 null）
    private volatile String readStatus = null;

    public void setReadStatus(String status) {
        readStatus = status;
        invalidate();
    }

    // v0.4.135：烘焙进度状态（FPS HUD 附加行，与底部烘焙提示同步）
    private volatile String bakeStatus = null;

    public void setBakeStatus(String status) {
        bakeStatus = status;
        invalidate();
    }
    private long lastFrameNanos = 0;
    private long fpsWindowStart = 0;
    private int fpsWindowFrames = 0;
    private float lastFpsShown = -1f;
    private final android.graphics.Paint fpsBgPaint = new android.graphics.Paint();
    private final android.graphics.Paint fpsTextPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF fpsBgRect = new android.graphics.RectF();
    private final android.graphics.Rect fpsTextBounds = new android.graphics.Rect();

    /** v0.4.110：最大缩放倍率（0=无限）。 */
    public void setMaxPixelsPerBlock(float maxPpb) {
        maxPixelsPerBlock = maxPpb <= 0 ? UNLIMITED_PPB : maxPpb;
        invalidate();
    }

    /** v0.4.113：视图状态存取（记忆功能——退出记住缩放与位置）。
     *  getPixelsPerBlock 已存在于下方，这里只补偏移 getter 与恢复。 */
    public float getOffsetX() {
        return offsetX;
    }

    public float getOffsetY() {
        return offsetY;
    }

    public void restoreView(float ppb, float ox, float oy) {
        setPpb(clampPixelsPerBlock(ppb));
        offsetX = ox;
        offsetY = oy;
        clampTranslation();
        invalidate();
    }

    /** v0.4.114：释放全部大内存资源（Activity onDestroy 调用）——
     *  连续进出世界时旧图不回收 + 静态 LOD 线程池任务积压持有旧
     *  WorldMap = 新世界加载 20MB 位图 OOM 的根因链（tombstone
     *  readLargePalette 崩溃）。 */
    public void release() {
        map = null;
        chunkGen++; // 排队中的 LOD 任务开头检查直接丢弃
        chunkLodCache.clear();
        chunkLodPending.clear();
        if (biomeMaskBitmap != null && !biomeMaskBitmap.isRecycled()) {
            biomeMaskBitmap.recycle();
        }
        biomeMaskBitmap = null;
        for (android.graphics.Bitmap b : chunkTiles.values()) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        chunkTiles.clear();
        if (cachedBmp != null && !cachedBmp.isRecycled()) {
            cachedBmp.recycle();
        }
        cachedBmp = null;
        clearFullMapImage();
        if (slimeMaskBitmap != null && !slimeMaskBitmap.isRecycled()) {
            slimeMaskBitmap.recycle();
        }
        slimeMaskBitmap = null;
    }

    public void setLodAdaptive(boolean adaptive) {
        android.util.Log.i("WorldMap", "setLodAdaptive " + adaptive
                + " (was " + lodAdaptive + ", adaptEma=" + adaptEma + ")");
        lodAdaptive = adaptive;
        if (!adaptive) {
            adaptEma = 1f; // 关=恒定用户设定值
        }
        // v0.4.125：切换立即清块图缓存——span 随开关变化，旧 span
        // 块图继续命中会让"热切换"看起来无效（画面不变）；清掉后
        // 按新 span 立即重建（渐进填充 1-2 秒，切换感知明显）
        clearChunkLod();
        invalidateFullRender();
    }

    /** onDraw 帧率测量（每帧开头调用）。 */
    private void measureFps() {
        long now = System.nanoTime();
        if (lastFrameNanos > 0) {
            float fps = 1e9f / Math.max(1, now - lastFrameNanos);
            // v0.4.118：帧率 EMA 加快（0.08→0.2）——掉帧即时反映
            fpsEma = fpsEma + (fps - fpsEma) * 0.2f;
            // 自适应系数：60fps→1.0，30fps→2.0，15fps→4.0（线性钳制）
            if (lodAdaptive) {
                float target = Math.max(1.2f, Math.min(5f, 80f / Math.max(6f, fpsEma)));
                // v0.4.118：不对称响应——掉帧快速降质（alpha 0.3，
                // 约 3 帧见效），恢复慢升（0.06）避免质量震荡；
                // 全程实时（用户要求：帧率波动时就生效，不只在
                // 缩放动画——不同设备性能不同，掉帧即降质）
                float alpha = target > adaptEma ? 0.3f : 0.06f;
                adaptEma = adaptEma + (target - adaptEma) * alpha;
            }
        }
        lastFrameNanos = now;
        // HUD 500ms 窗口更新
        fpsWindowFrames++;
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        } else if (now - fpsWindowStart > 500_000_000L) {
            lastFpsShown = fpsWindowFrames * 1e9f / (now - fpsWindowStart);
            fpsWindowStart = now;
            fpsWindowFrames = 0;
        }
    }

    /** v0.4.107：右上角状态 HUD（galaxy 玻璃拟态风格）——多行：
     *  帧率/缩放等级/方块px/屏幕中心群系；下移至顶栏下方不再被遮挡。
     *  v0.4.107：hudToast 短暂气泡（导出成功等事件提示，2.5s 淡出）。 */
    private String hudToastText;
    private long hudToastUntil = 0;

    /** 事件提示气泡（右上角 HUD 下方，短暂显示后淡出）。 */
    public void showHudToast(String text) {
        hudToastText = text;
        hudToastUntil = System.currentTimeMillis() + 2500;
        invalidate();
    }

    private void drawFpsHud(android.graphics.Canvas canvas) {
        if (lastFpsShown < 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        android.content.Context ctx = getContext();
        // 多行内容：帧率 / 缩放（px·方块）/ 手势+中心坐标 / 群系 /
        // 读取状态 / 烘焙进度（v0.4.144：全部走字符串资源 11 语言）
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(String.format(java.util.Locale.US,
                ctx.getString(R.string.hud_fps_zoom), lastFpsShown, pixelsPerBlock));
        lines.add(String.format(java.util.Locale.US,
                ctx.getString(R.string.hud_blocks_per_pixel),
                1f / Math.max(0.001f, pixelsPerBlock)));
        // v0.4.135：玩家操作状态 + 屏幕中心世界坐标（实时）
        String gestureName = ctx.getString(scaling ? R.string.hud_gesture_scale
                : panning ? R.string.hud_gesture_pan : R.string.hud_gesture_idle);
        if (map != null && getWidth() > 0 && getHeight() > 0) {
            double cx = (getWidth() / 2.0 - offsetX) / pixelsPerBlock + map.minBlockX;
            double cz = (getHeight() / 2.0 - offsetY) / pixelsPerBlock + map.minBlockZ;
            lines.add(gestureName + ctx.getString(R.string.hud_center)
                    + (long) cx + ", " + (long) cz);
        } else {
            lines.add(gestureName);
        }
        if (map != null && getWidth() > 0 && getHeight() > 0) {
            // v0.4.139：群系名用「指针指向位置」（十字标记锚点/中心
            // 准星），无指针时用屏幕中心
            float bx = tapCenterEnabled ? getWidth() / 2f
                    : tapScreenX >= 0 ? tapScreenX : getWidth() / 2f;
            float by = tapCenterEnabled ? getHeight() / 2f
                    : tapScreenY >= 0 ? tapScreenY : getHeight() / 2f;
            int biomeId = biomeIdAt(bx, by);
            if (biomeId > 0) {
                String en = org.levimc.launcher.core.content.worldmap.WorldMapRenderer
                        .biomeName(biomeId);
                if (en != null) {
                    lines.add(ctx.getString(R.string.hud_pointer_biome,
                            org.levimc.launcher.core.content.worldmap.BiomeNames
                                    .displayName(en)));
                }
            }
        }
        // v0.4.139：指针显示模式勾选（HUD 内点击切换）
        // v0.4.146：删「指针始终显示」行（用户拍板）
        lines.add((tapCenterEnabled ? "☑" : "☐") + " "
                + ctx.getString(R.string.hud_pin_center));
        // v0.4.137：本帧实际绘制标点数（抽稀后的真实数量，直观看到
        // 哪个图层在吃性能）
        lines.add(ctx.getString(R.string.hud_marker_count,
                drawnEntities, drawnOres, drawnStructures));
        // v0.4.133：读取状态行（"正在读取群系数据…"等，读取完成清空）
        if (readStatus != null && !readStatus.isEmpty()) {
            lines.add(readStatus);
        }
        // v0.4.135：烘焙进度行（与底部烘焙提示同步）
        if (bakeStatus != null && !bakeStatus.isEmpty()) {
            lines.add(bakeStatus);
        }
        fpsTextPaint.setTextSize(11 * density);
        float pad = 6 * density;
        float lineH = 15 * density;
        float bgW = 0;
        for (String s : lines) {
            fpsTextPaint.getTextBounds(s, 0, s.length(), fpsTextBounds);
            bgW = Math.max(bgW, fpsTextBounds.width());
        }
        float bgH = lineH * lines.size() + pad * 2;
        // v0.4.107：下移到顶栏下方（顶栏收起 ~48dp+边距，104dp 安全）
        fpsBgRect.set(getWidth() - bgW - pad * 2 - 12 * density,
                104 * density,
                getWidth() - 12 * density,
                104 * density + bgH);
        hudRect.set(fpsBgRect); // v0.4.112：保存 HUD 区域（局部刷新用）
        fpsBgPaint.setColor(0x66000000);
        fpsBgPaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawRoundRect(fpsBgRect, 10 * density, 10 * density, fpsBgPaint);
        fpsTextPaint.setColor(0xFFCCCCCC);
        float ty = fpsBgRect.top + pad + lineH * 0.6f;
        // v0.4.139：记录勾选开关行的命中区域（HUD 内点击切换）
        // v0.4.144：按勾选行序计数（此前 contains("始终显示") 硬编码
        // 中文，多语言后失效）；v0.4.146 只剩「指针固定中心」一行
        centerRowRect.setEmpty();
        for (String s : lines) {
            canvas.drawText(s, fpsBgRect.left + pad, ty, fpsTextPaint);
            if (s.startsWith("☑") || s.startsWith("☐")) {
                centerRowRect.set(fpsBgRect.left, ty - lineH + 2f,
                        fpsBgRect.right, ty + 2f);
            }
            ty += lineH;
        }
        // 事件气泡（HUD 下方）
        if (hudToastText != null && System.currentTimeMillis() < hudToastUntil) {
            fpsTextPaint.getTextBounds(hudToastText, 0, hudToastText.length(), fpsTextBounds);
            float tw = fpsTextBounds.width() + pad * 2;
            android.graphics.RectF toastRect = new android.graphics.RectF(
                    getWidth() - tw - 12 * density,
                    fpsBgRect.bottom + 6 * density,
                    getWidth() - 12 * density,
                    fpsBgRect.bottom + 6 * density + lineH + pad * 2);
            // 淡出：最后 600ms 线性降透明度
            long remain = hudToastUntil - System.currentTimeMillis();
            int alpha = (int) Math.max(0, Math.min(255, remain * 255 / 600));
            fpsBgPaint.setColor((alpha << 24) | 0x000000);
            canvas.drawRoundRect(toastRect, 10 * density, 10 * density, fpsBgPaint);
            fpsTextPaint.setColor(0xCCFFFFFF & (alpha << 24) | 0x00FFFFFF);
            canvas.drawText(hudToastText, toastRect.left + pad,
                    toastRect.top + pad + lineH * 0.6f, fpsTextPaint);
            invalidate(); // 淡出动画帧
        } else {
            hudToastText = null;
        }
    }

    // ---- v0.4.101：chunk LOD 动态梯度超级块——bs>1 世界缩小（ppb<3）
    //      时视口 chunk tile 数爆炸（ppb=0.67 时 8 万张/帧，日志实锤）。
    //      span（2 的幂 4-64）随 ppb 连续选级：块输出 64×64，块像素
    //      显示尺寸恒定 ~1-3px（阈值参数控制），块屏 ~100px 视口 ~950
    //      块——三级跳变已废弃（0.5 倍用 lod16=16 方块/px 放大 8 倍
    //      双线性="糊成狗屎"根因）。key=(span<<56)|packChunk。 ----
    /** v0.4.152：LOD 块缓存容量动态=视口块数×2（512-3072 夹取）。
     *  固定 cap（512/2048）在平板 0.78 倍率视口 ~1000 块时不够——
     *  视口边缘块（离屏幕中心远、生成慢的「上方」区域）永远处于
     *  淘汰→重新请求→生成→再淘汰 循环=持续闪烁。每帧按视口块数
     *  更新，cap 恒 ≥2×视口 → 视口内块永不因容量被淘汰。 */
    private volatile int lodCacheCap = 512;
    private final java.util.LinkedHashMap<Long, Bitmap> chunkLodCache =
            new java.util.LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, Bitmap> eldest) {
                    // v0.4.102：span=16 全图预生成 ≈1400 块（64²×4=16KB/块
                    // → 2048 块=32MB），太小会被预生成自己挤爆
                    // v0.4.152：容量动态跟随视口（见 lodCacheCap 注释）
                    if (size() > lodCacheCap) {
                        eldest.getValue().recycle();
                        return true;
                    }
                    return false;
                }
            };
    private final java.util.Set<Long> chunkLodPending = new java.util.HashSet<>();
    // v0.4.122：biome 遮罩位图（slimeMask 同款：每 chunk 4×4 像素
    // 群系色全图位图，单次 drawBitmap 任意倍率叠加）
    private android.graphics.Bitmap biomeMaskBitmap;
    private int biomeMaskCx0;
    private int biomeMaskCz0;
    private int biomeMaskW;
    private int biomeMaskH;
    private final android.graphics.RectF biomeMaskDst = new android.graphics.RectF();
    /** 换图代次——后台块生成结果跨图丢弃（防串污染）。 */
    // volatile：LOD 后台任务开头读它快速丢弃换图/退出后的无用任务
    // （v0.4.114——连续进出世界时静态线程池队列积压任务持有旧
    // WorldMap 无法回收 → 新世界加载 OOM 的根因链）
    private volatile int chunkGen = 0;

    /** v0.4.101：本帧 chunk LOD 选中的 span（诊断日志用）。 */
    private int curLodSpan = 0;

    /** v0.4.101：目标块像素显示尺寸（阈值映射：高阈值=流畅糊、
     *  低阈值=清晰块多）。v0.4.103：动画与静止同一目标（span 稳定=
     *  缓存命中率高，杜绝 v0.4.102 的 span 抖动引发整链 miss）。
     *  v0.4.106：× 帧率自适应系数（低帧率自动放大目标=糊换流畅）。
     *  v0.4.118：全程实时自适应（回退 v0.4.116 的静止回 1——
     *  用户要求掉帧即降质不区分手势；下界糊的另一半根因是 biome
     *  叠加层已在 v0.4.116 修复）。
     *  v0.4.119：ppb≤1 区间更激进——用户实测平板静止 120 帧、
     *  缩到 1.0 及以下暴跌 70-80 帧；低倍率即使帧率高也预先
     *  降质（effectiveAdapt floor 1.6），掉帧时照常跟随。 */
    private float effectiveAdapt() {
        // v0.4.124：开关热切换且差异可感知——关=用户设定质量（清晰
        // 但低倍率掉帧）；开=帧率实时跟随+低倍率保底降质（流畅）。
        // 此前 floor 与开关无关，开/关块图糊度相同="开关无感"根因
        if (!lodAdaptive) {
            return 1f;
        }
        return Math.max(adaptEma, pixelsPerBlock <= 1f ? 1.6f : 1f);
    }

    private float lodBlockPx() {
        return (1f + (lodThreshold - 0.05f) / 0.9f * 2f) * effectiveAdapt();
    }

    /** v0.4.101：按 ppb 选 span——块像素 ≤ 目标 且 块屏 ≥96px（块数
     *  可控），2 的幂阶梯（4/8/16/32/64），只随 ppb 跨 2 倍变化。 */
    private int pickLodSpan() {
        float ppb = pixelsPerBlock;
        float maxSpanF = Math.min(64f, 4f * lodBlockPx() / ppb);
        int span = 4;
        while (span * 2 <= maxSpanF && span * 16f * ppb < 96f) {
            span *= 2;
        }
        // v0.4.130：关自适应=保守档——固定 4 方块/像素（span 16，
        // 块图 64px 覆盖 16×16 方块），清晰显示；开自适应动态
        // span 可达 16 方块/像素（span 64，激进流畅）
        if (!lodAdaptive && span > 16) {
            span = 16;
        }
        return span;
    }
    private float scaleFocusX;
    private float scaleFocusY;

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        measureFps();
        // v0.4.112：HUD 局部刷新帧——canvas 被 clip 到 HUD 区域时
        // 只画 HUD（静止时帧率持续跳动，地图主体零重绘）
        if (hudRect.width() > 0) {
            android.graphics.Rect cb = canvas.getClipBounds();
            if (cb != null && cb.width() < getWidth() / 2
                    && cb.bottom < getHeight() / 2) {
                drawFpsHud(canvas);
                return;
            }
        }
        // 大世界 bounds-only 地图只有 chunkColors（colors==null），
        // 必须放行 chunk 路径——否则首屏空白且视口按需渲染永不触发
        if (map == null || (map.colors == null && map.chunkColors == null)) {
            return;
        }
        // v417：缩放倍率 HUD 兜底实时更新——任何路径改了 ppb
        // （捏合/双击/程序化）onDraw 检测到变化就通知（用户反馈
        // "缩放后要点屏幕才更新倍率"）
        if (Float.compare(pixelsPerBlock, lastNotifiedPpb) != 0 && !scaling) {
            // v0.4.98：捏合动画中冻结（onScaleEnd 补发）
            lastNotifiedPpb = pixelsPerBlock;
            notifyViewChanged();
        }
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        // 清空画布（平移缓存位图路径只覆盖部分区域，不清会叠出"盗梦空间"残影）；
        // v457：清空后铺启动器背景色——未渲染区块显示启动器背景
        //（此前 CLEAR 透明，露出 Activity 根背景与地图区域不一致）
        canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR);
        if (map != null) {
            canvas.drawColor(launcherBackgroundColor());
        }
        // v0.4.122：biome 遮罩位图叠加（v0.4.119 小世界禁用全图纹理
        // 条件删除——遮罩画在全图纹理之上，全倍率显示群系）
        if (fullMapBitmap != null && !fullMapBitmap.isRecycled()
                && (fullMapBlockScale <= 1
                        ? (pixelsPerBlock < 3f || map.chunkColors == null)
                        : (pixelsPerBlock <= 2f / Math.max(1f, fullMapBlockScale)
                                || (map.chunkColors == null && map.colors == null)))) {
            // 全图纹理模式（HTML 同款——单次 drawBitmap 变换）：
            // ① blockScale==1 全分辨率（小世界恒全图；主世界大图 ppb<3
            //   走全图+mip 小纹理，ppb≥3 回退 chunk tile）——
            //   v0.4.86：此前恒走全图，高倍放大每帧从 4096² 大纹理
            //   采样=GPU 带宽压力=大地图缩放卡；ppb≥3 时视口 tile 数
            //   (2560/(16×3))²≈2800 张 16×16 小图，CPU/GPU 都轻
            // ② blockScale>1 降采样全图（下界稀疏大 bounds）：仅在全图
            //   真正缩小（scalePx=blockScale×ppb≤1）时使用——v0.4.77
            //   误用 ppb≤1（blockScale=8 时 ppb=0.5→scalePx=4=全图放大
            //   4 倍=平均色块放大=下界"一直糊"根因）；其余回退 chunk
            //   tile（1px/方块最近邻锐利）
            // ③ 骨架期无底层可退
            drawFullMapLayer(canvas);
        } else if (map.chunkColors != null) {
            // 大世界：chunk tile 平铺（BTR 同款）——零重采样、零 39MB 大位图，
            // 每帧只 drawBitmap 视口内 chunk 的小 tile（GPU 加速）
            drawChunkLayer(canvas);
        } else {
            // 小世界整图路径：缓存位图三分支
            // 1) 纯平移 → 直接平移缓存位图，零重采样（拖动流畅）；
            // 2) 缩放/跳转动画期间 → 缓存位图按捏合焦点做缩放预览；
            // 3) 其它 → 全量重采样。
            lastDrawPath = "cached";
            boolean ppbChanged = Math.abs(pixelsPerBlock - cachedPpb) > 1e-4f;
            boolean farMoved = Math.abs(offsetX - cachedOffsetX) >= viewW
                    || Math.abs(offsetY - cachedOffsetY) >= viewH;
            if (scalePreviewActive && cachedBmp != null && !farMoved) {
                // 缓存位图变换预览：屏幕 = offset + k × (缓存坐标 - cachedOffset)
                // v0.4.75+：最近邻画笔（默认双线性把放大预览糊掉——
                // "小地图最大缩放变糊"根因，HTML 同款锐利）
                float k = pixelsPerBlock / cachedPpb;
                canvas.save();
                canvas.translate(offsetX, offsetY);
                canvas.scale(k, k);
                canvas.translate(-cachedOffsetX, -cachedOffsetY);
                canvas.drawBitmap(cachedBmp, 0, 0, fullMapPaint);
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
        // v0.4.97：捏合缩放动画期间只画底图+玩家标记——大地图缩小时
        // 视口覆盖全图，矿石标点/结构/实体几千条每帧重绘才是缩放卡的
        // 主因（LOD 只优化了底图那一下）；onScaleEnd 全量补画
        // v0.4.109：拖拽恢复叠加层（用户反馈滑动不松手时实体/矿石/
        // 史莱姆/网格不显示——拖拽视口大小不变，标点数不变，可画）
        // v0.4.110：捏合也全量绘制（用户要求缩放中信息不消失；
        // 大地图缩小时标点多性能有压力，但自适应 LOD 兜底流畅度）
        drawBiomeMask(canvas); // v0.4.122：biome 遮罩叠加（底图之上网格之下）
        drawGrid(canvas);
        drawSlimeChunks(canvas);
        drawLinks(canvas);
        drawPoints(canvas);
        drawOreMarkers(canvas); // v413：矿石标点（色块菱形）
        drawEntities(canvas);
        drawStructures(canvas);
        drawVoxelSelection(canvas); // 3D 区域选择矩形（最上层）
        drawTapMarker(canvas); // v0.4.109：十字标记移出跳过块（缩放中不消失）
        drawMarkers(canvas); // 玩家/出生点标记画在最上层（不被实体贴图遮挡）
        drawFpsHud(canvas); // v0.4.106：右上角帧率 HUD（最上层）
        // v0.4.99：节流诊断日志（32 帧一次≈0.27s@120Hz）——验证 LOD
        // 参数生效路径：path 显示本帧绘制分支，lod2/4/8=用了对应
        // 预降采样图，chunk/cached=全图路径根本没走（LOD 管不着）
        if ((++dbgFrame & 31) == 0) {
            android.util.Log.i("WorldMap", "draw path=" + lastDrawPath
                    + " ppb=" + String.format(java.util.Locale.US, "%.3f", pixelsPerBlock)
                    + " scalePx=" + String.format(java.util.Locale.US, "%.2f",
                            fullMapBlockScale * pixelsPerBlock)
                    + " bs=" + fullMapBlockScale
                    + " lod=" + lodEnabled + "/" + String.format(java.util.Locale.US,
                            "%.2f", lodThreshold) + "/" + lodMaxLevel
                    + " span=" + curLodSpan
                    + " gesture=" + (scaling ? "scale" : panning ? "pan" : "-"));
        }
    }

    /** v413：矿石标点图层——v425 起优先用 MC 原版贴图
     *  （assets/ore_textures/<短名>.png，如 diamond_ore.png），
     *  无贴图回退色块菱形（视口内才画，大地图 2 万+ 标点全画会卡）。 */
    /** v0.4.165：矿石层滞回状态（跨 ppb 阈值往返防闪烁）。 */
    private boolean oreLayerVisible = true;

    private void drawOreMarkers(android.graphics.Canvas canvas) {
        if (!showOreLayer || map == null || map.oreMarkers == null
                || map.oreMarkers.isEmpty()) {
            return;
        }
        // v0.4.113：ppb<0.5 跳过——缩小到全图时 2 万矿石标点
        // 密集超过屏幕分辨率（画了也是噪点糊屏），每帧 2 万个
        // path+draw=主线程 150ms/帧（0.37 倍卡根因主犯）
        // v0.4.165：滞回（0.5 隐藏 / 0.45 恢复）——捏合跨阈值往返
        // 时整层忽隐忽现 = 滑动/捏合中矿石闪烁根因
        if (pixelsPerBlock < (oreLayerVisible ? 0.45f : 0.5f)) {
            oreLayerVisible = false;
            return;
        }
        oreLayerVisible = true;
        // v0.4.118：抽稀不限倍率——5.5 倍时小世界视口内仍有
        // 4000+ 矿石点每帧全画（"5.5倍也开始卡"）；点击检测走
        // 全量列表与绘制无关。视口内超过 1200 个按比例跳画
        int oreStride = 1;
        if (pixelsPerBlock < 16f && map.oreMarkers.size() > 1500) {
            if (scaling || panning) {
                // v0.4.138：手势动画中冻结抽稀步长——stride 随视口
                // 计数每帧跳变=矿石点集合变化=闪烁根因；手势结束重算
                oreStride = Math.max(1, lastOreStride);
            } else {
                // v0.4.140：总量近似（省 2 万次计数循环）
                oreStride = Math.max(1, map.oreMarkers.size() / 1200);
                lastOreStride = oreStride;
            }
        }
        int oreCount = 0;
        drawnOres = 0;
        float r = Math.max(3.5f, pixelsPerBlock * 0.9f);
        // v0.4.123：Path/Paint/RectF 字段复用（每帧新建=GC 抖动）
        android.graphics.Path p = orePath;
        p.reset();
        android.graphics.Paint texPaint = oreTexPaint;
        for (WorldMapRenderer.OreMarker m : map.oreMarkers) {
            float sx = offsetX + (m.blockX - map.minBlockX + 0.5f) * pixelsPerBlock;
            float sy = offsetY + (m.blockZ - map.minBlockZ + 0.5f) * pixelsPerBlock;
            // v0.4.138：边缘缓冲 20→60——滑动时矿石点进出边界突然
            // 出现/消失=闪烁的另一半根因
            if (sx < -60 || sx > getWidth() + 60 || sy < -60 || sy > getHeight() + 60) {
                continue;
            }
            if ((oreCount++ % oreStride) != 0) {
                continue;
            }
            drawnOres++;
            android.graphics.Bitmap tex = oreTexture(m.name);
            if (tex != null) {
                float ts = Math.max(r * 2.2f, pixelsPerBlock * 1.5f);
                oreIconDst.set(sx - ts / 2f, sy - ts / 2f,
                        sx + ts / 2f, sy + ts / 2f);
                canvas.drawBitmap(tex, null, oreIconDst, texPaint);
                continue;
            }
            p.reset();
            p.moveTo(sx, sy - r);
            p.lineTo(sx + r, sy);
            p.lineTo(sx, sy + r);
            p.lineTo(sx - r, sy);
            p.close();
            canvas.drawPath(p, oreFillPaint(m.color()));
        }
    }

    /** v425：矿石贴图缓存（null 也缓存防反复 IO）。 */
    private static final java.util.Map<String, android.graphics.Bitmap> ORE_TEX_CACHE =
            new java.util.HashMap<>();

    private android.graphics.Bitmap oreTexture(String shortName) {
        android.graphics.Bitmap b = ORE_TEX_CACHE.get(shortName);
        if (b == null && !ORE_TEX_CACHE.containsKey(shortName)) {
            try {
                java.io.InputStream is = getContext().getAssets()
                        .open("ore_textures/" + shortName + ".png");
                b = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
            } catch (Exception e) {
                b = null;
            }
            ORE_TEX_CACHE.put(shortName, b);
        }
        return b;
    }

    private final java.util.Map<Integer, android.graphics.Paint> oreFillPaints =
            new java.util.HashMap<>();

    private android.graphics.Paint oreFillPaint(int color) {
        android.graphics.Paint p = oreFillPaints.get(color);
        if (p == null) {
            p = new android.graphics.Paint();
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(color);
            oreFillPaints.put(color, p);
        }
        return p;
    }

    /** 3D 区域选择矩形（半透明填充 + 主题色边框）。 */
    private void drawVoxelSelection(Canvas canvas) {
        if (!voxelSelecting) {
            return;
        }
        float sx1 = worldToScreenX(Math.min(voxelStartX, voxelCurX));
        float sy1 = worldToScreenY(Math.min(voxelStartZ, voxelCurZ));
        float sx2 = worldToScreenX(Math.max(voxelStartX, voxelCurX) + 1);
        float sy2 = worldToScreenY(Math.max(voxelStartZ, voxelCurZ) + 1);
        Paint fill = new Paint();
        fill.setColor(0x335B9CF6);
        canvas.drawRect(sx1, sy1, sx2, sy2, fill);
        Paint border = new Paint();
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        border.setColor(0xFF5B9CF6);
        canvas.drawRect(sx1, sy1, sx2, sy2, border);
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
    /** 大世界底层绘制：chunk tile 平铺。
     *  v0.4.100：ppb<3 走 chunk LOD 超级块（视口 tile 数 ÷16~÷256，
     *  修缩放卡）；ppb≥3 逐 chunk tile 最近邻（锐利放大）。 */
    private void drawChunkLayer(android.graphics.Canvas canvas) {
        lastDrawPath = "chunk";
        // v0.4.101：ppb<3 走动态 span 超级块（块像素恒定目标、块屏
        // ~100px），LOD 开关统一控制；ppb≥3 逐 chunk 锐利
        if (lodEnabled && pixelsPerBlock < 3f) {
            drawChunkLodLayer(canvas, pickLodSpan());
            return;
        }
        int viewW = getWidth();
        int viewH = getHeight();
        float ppb = pixelsPerBlock;
        float invPpb = 1f / ppb;
        int minCx = Math.floorDiv(map.minBlockX, 16);
        int minCz = Math.floorDiv(map.minBlockZ, 16);
        int maxCx = Math.floorDiv(map.minBlockX + map.width - 1, 16);
        int maxCz = Math.floorDiv(map.minBlockZ + map.height - 1, 16);
        double leftWorld = (0 - (double) offsetX) * invPpb + map.minBlockX;
        double rightWorld = (viewW - (double) offsetX) * invPpb + map.minBlockX;
        double topWorld = (0 - (double) offsetY) * invPpb + map.minBlockZ;
        double bottomWorld = (viewH - (double) offsetY) * invPpb + map.minBlockZ;
        int firstCx = Math.max(minCx, Math.floorDiv((int) Math.floor(leftWorld), 16));
        int firstCz = Math.max(minCz, Math.floorDiv((int) Math.floor(topWorld), 16));
        int lastCx = Math.min(maxCx, Math.floorDiv((int) Math.ceil(rightWorld), 16));
        int lastCz = Math.min(maxCz, Math.floorDiv((int) Math.ceil(bottomWorld), 16));
        drawChunkRange(canvas, firstCx, firstCz, lastCx, lastCz);
    }

    /** 逐 chunk tile 绘制 [firstCx..lastCx]×[firstCz..lastCz]（ppb≥3 全视口
     *  或超级块未就绪的兜底区域）。 */
    private void drawChunkRange(android.graphics.Canvas canvas,
                                int firstCx, int firstCz, int lastCx, int lastCz) {
        float ppb = pixelsPerBlock;
        // v0.4.118：Paint/RectF 字段复用（5.5 倍 700 张 tile 每帧
        // 新建对象加剧 GC 压力）
        tilePaint.setFilterBitmap(false); // 最近邻放大，保持块状像素风
        for (int cz = firstCz; cz <= lastCz; cz++) {
            float top = worldToScreenY(cz * 16f);
            for (int cx = firstCx; cx <= lastCx; cx++) {
                long ck = packChunk(cx, cz);
                Bitmap tile = chunkTiles.get(ck);
                if (tile == null) {
                    tile = ensureChunkTile(ck);
                    if (tile == null) {
                        // v392：视口按需渲染已删除——缺失 chunk 由烘焙
                        // 补全（视口优先队列保证拖动时屏幕区域先烘）
                        continue;
                    }
                }
                tileDst.set(worldToScreenX(cx * 16f), top,
                        worldToScreenX(cx * 16f + 16f), top + 16f * ppb);
                canvas.drawBitmap(tile, null, tileDst, tilePaint);
            }
        }
    }

    /** v0.4.101：chunk LOD 动态梯度层——块覆盖 span×span chunk，输出
     *  64×64（每输出像素 = span/4 方块均值）；miss 逐级兜底
     *  （span→span/2→chunk），生成请求按距屏幕中心排序（中心先出）。 */
    private void drawChunkLodLayer(android.graphics.Canvas canvas, int span) {
        lastDrawPath = "clod" + span;
        curLodSpan = span;
        int viewW = getWidth();
        int viewH = getHeight();
        float invPpb = 1f / pixelsPerBlock;
        int minBx = Math.floorDiv(Math.floorDiv(map.minBlockX, 16), span);
        int minBz = Math.floorDiv(Math.floorDiv(map.minBlockZ, 16), span);
        int maxBx = Math.floorDiv(Math.floorDiv(map.minBlockX + map.width - 1, 16), span);
        int maxBz = Math.floorDiv(Math.floorDiv(map.minBlockZ + map.height - 1, 16), span);
        double leftWorld = (0 - (double) offsetX) * invPpb + map.minBlockX;
        double rightWorld = (viewW - (double) offsetX) * invPpb + map.minBlockX;
        double topWorld = (0 - (double) offsetY) * invPpb + map.minBlockZ;
        double bottomWorld = (viewH - (double) offsetY) * invPpb + map.minBlockZ;
        int firstBx = Math.max(minBx,
                Math.floorDiv(Math.floorDiv((int) Math.floor(leftWorld), 16), span));
        int firstBz = Math.max(minBz,
                Math.floorDiv(Math.floorDiv((int) Math.floor(topWorld), 16), span));
        int lastBx = Math.min(maxBx,
                Math.floorDiv(Math.floorDiv((int) Math.ceil(rightWorld), 16), span));
        int lastBz = Math.min(maxBz,
                Math.floorDiv(Math.floorDiv((int) Math.ceil(bottomWorld), 16), span));
        // v0.4.152：容量动态跟随视口块数（闪烁根除，见 lodCacheCap 注释）
        int viewBlocks = (lastBx - firstBx + 1) * (lastBz - firstBz + 1);
        lodCacheCap = Math.max(512, Math.min(3072, viewBlocks * 2));
        drawChunkLodRange(canvas, span, firstBx, firstBz, lastBx, lastBz, true);
    }

    /** 画块范围；miss 块收集后按距屏幕中心排序请求（中心先出）。
     *  v0.4.107：**删递归兜底**——miss 块统一画背景（渐进填充，
     *  网页地图瓦片同款），绝不混绘不同 span 的块（"左边半扇和
     *  右边半扇画面不一样"根因：兜底 span/2 块像素大 2 倍=视觉像
     *  另一缩放比例）。全屏只出现「目标 span 块 + 背景格」两种状态，
     *  生成完 invalidate 填充，左右永远一致。 */
    private void drawChunkLodRange(android.graphics.Canvas canvas, int span,
                                   int firstBx, int firstBz, int lastBx, int lastBz,
                                   boolean collectMiss) {
        Paint lodPaint = new Paint();
        lodPaint.setFilterBitmap(true); // 块级降采样图：双线性（缩小/放大都平滑）
        android.graphics.RectF dst = new android.graphics.RectF();
        java.util.List<long[]> missList = collectMiss ? new java.util.ArrayList<>() : null;
        for (int bz = firstBz; bz <= lastBz; bz++) {
            for (int bx = firstBx; bx <= lastBx; bx++) {
                long key = lodKey(span, bx, bz);
                Bitmap bmp = chunkLodCache.get(key);
                if (bmp == null) {
                    if (missList != null) {
                        missList.add(new long[]{key, bx, bz});
                    }
                    // miss：背景渐进填充（不画任何兜底——分辨率一致性第一）
                    continue;
                }
                dst.set(worldToScreenX(bx * span * 16f),
                        worldToScreenY(bz * span * 16f),
                        worldToScreenX((bx + 1) * span * 16f),
                        worldToScreenY((bz + 1) * span * 16f));
                canvas.drawBitmap(bmp, null, dst, lodPaint);
            }
        }
        if (missList != null && !missList.isEmpty()) {
            // 按距屏幕中心距离升序（中心块先生成，缩放停止后最先清晰）
            float worldCx = (getWidth() / 2f - offsetX) / pixelsPerBlock + map.minBlockX;
            float worldCz = (getHeight() / 2f - offsetY) / pixelsPerBlock + map.minBlockZ;
            missList.sort((a, b) -> {
                float dax = a[1] * span * 16f - worldCx;
                float daz = a[2] * span * 16f - worldCz;
                float dbx = b[1] * span * 16f - worldCx;
                float dbz = b[2] * span * 16f - worldCz;
                return Float.compare(dax * dax + daz * daz, dbx * dbx + dbz * dbz);
            });
            // v0.4.103：请求限流——动画中每帧最多 300、队列有界（缩放
            // 快速移动时视口外的过期块不抢占生成线程）
            int budget = scaling ? 300 : missList.size();
            // v0.4.109：队列满时清空重排（视口块永远优先，旧过期块
            // 不再阻塞生成——右侧大片背景不填充根因）
            if (chunkLodPending.size() > 3000) {
                chunkLodPending.clear();
            }
            for (long[] m : missList) {
                if (budget-- <= 0) {
                    break;
                }
                requestChunkLod((int) m[1], (int) m[2], span);
            }
        }
    }

    /** 缓存 key：span（2 的幂 4-64）+ 块坐标。 */
    private static long lodKey(int span, int bx, int bz) {
        // v0.4.111：修位碰撞——旧实现 (span<<56)|packChunk 的 span 位
        // （56-62）被负 X 块坐标的符号扩展位（32-63 全 1）掩掉 →
        // 负 X 区域（地图左半边）不同 span 块 key 相同 → 缩放换挡后
        // 拿到旧 span 块图 =「左边残留一张别的比例的图片」根因。
        // 修复：先掩掉 packChunk 的符号扩展位，再放 span
        return ((long) span << 56) | (packChunk(bx, bz) & 0x00FFFFFFFFFFFFFFL);
    }

    /** v0.4.101：请求后台生成超级块（幂等；代次防跨图污染）。 */
    private void requestChunkLod(int bx, int bz, int span) {
        final long key = lodKey(span, bx, bz);
        if (!chunkLodPending.add(key)) {
            return;
        }
        final int gen = chunkGen;
        final WorldMapRenderer.WorldMap srcMap = map;
        LOD_EXECUTOR.execute(() -> {
            // v0.4.114：换图/退出后丢弃排队任务（span 64 块图生成
            // 单任务数十 ms，几千任务积压=旧图内存长期不可回收）
            if (gen != chunkGen || map != srcMap) {
                chunkLodPending.remove(key);
                return;
            }
            Bitmap bmp = null;
            try {
                bmp = buildChunkLod(srcMap, bx, bz, span);
            } catch (Throwable ignored) {
                // 数据并发修改/换图：丢弃，下帧重试
            }
            final Bitmap out = bmp;
            MAIN_HANDLER.post(() -> {
                chunkLodPending.remove(key);
                if (out == null || gen != chunkGen || map != srcMap) {
                    if (out != null) {
                        out.recycle();
                    }
                    return;
                }
                chunkLodCache.put(key, out);
                invalidate();
            });
        });
    }

    /** 后台生成超级块：span×span chunk 平均成 64×64（每输出像素 =
     *  span/4 方块均值；透明像素不参与平均，全透明输出 0）。 */
    private static Bitmap buildChunkLod(WorldMapRenderer.WorldMap srcMap,
                                        int bx, int bz, int span) {
        int outPx = 64;
        long[] rsum = new long[outPx * outPx];
        long[] gsum = new long[outPx * outPx];
        long[] bsum = new long[outPx * outPx];
        int[] cnt = new int[outPx * outPx];
        java.util.Map<Long, int[]> colors = srcMap.chunkColors;
        if (colors == null) {
            return null;
        }
        int step = span / 4; // 每输出像素 = step×step 方块（span≥4 恒整除）
        for (int kz = 0; kz < span; kz++) {
            for (int kx = 0; kx < span; kx++) {
                int[] cc = colors.get(packChunk(bx * span + kx, bz * span + kz));
                if (cc == null) {
                    continue;
                }
                for (int pz = 0; pz < 16; pz++) {
                    int rowBase = pz * 16;
                    for (int px = 0; px < 16; px++) {
                        int c = cc[rowBase + px];
                        if ((c & 0xFF000000) == 0) {
                            continue;
                        }
                        int dx = (kx * 16 + px) / step;
                        int dy = (kz * 16 + pz) / step;
                        int idx = dy * outPx + dx;
                        rsum[idx] += (c >> 16) & 0xFF;
                        gsum[idx] += (c >> 8) & 0xFF;
                        bsum[idx] += c & 0xFF;
                        cnt[idx]++;
                    }
                }
            }
        }
        int[] out = new int[outPx * outPx];
        boolean any = false;
        for (int i = 0; i < out.length; i++) {
            if (cnt[i] == 0) {
                out[i] = 0;
                continue;
            }
            any = true;
            out[i] = (0xFF << 24)
                    | ((int) (rsum[i] / cnt[i]) << 16)
                    | ((int) (gsum[i] / cnt[i]) << 8)
                    | (int) (bsum[i] / cnt[i]);
        }
        if (!any) {
            return null;
        }
        Bitmap bmp = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888);
        bmp.setPixels(out, 0, outPx, 0, 0, outPx, outPx);
        return bmp;
    }

    /** v0.4.126：biome 独立扫描批次更新遮罩（UI 线程 setPixels 渐进）。
     *  batch = {bmp, px快照, stride, w, h, cx0, cz0}（scanBiomeMaskAsync）。 */
    public void setBiomeMaskPixels(Object[] batch) {
        android.graphics.Bitmap bmp = (android.graphics.Bitmap) batch[0];
        int[] px = (int[]) batch[1];
        int stride = (Integer) batch[2];
        int w = (Integer) batch[3];
        int h = (Integer) batch[4];
        if (biomeMaskBitmap != null && biomeMaskBitmap != bmp
                && !biomeMaskBitmap.isRecycled()) {
            biomeMaskBitmap.recycle();
        }
        biomeMaskBitmap = bmp;
        bmp.setPixels(px, 0, stride, 0, 0, stride, h);
        biomeMaskCx0 = (Integer) batch[5];
        biomeMaskCz0 = (Integer) batch[6];
        biomeMaskW = w;
        biomeMaskH = h;
        invalidate();
    }

    /** v0.4.122：设置 biome 遮罩位图（Activity 层后台生成后回调）。
     *  mask 每像素 = 4×4 方块群系代表色（生成见 buildBiomeChunkMask）。 */
    public void setBiomeMask(android.graphics.Bitmap mask, int cx0, int cz0, int w, int h) {
        if (biomeMaskBitmap != null && biomeMaskBitmap != mask
                && !biomeMaskBitmap.isRecycled()) {
            biomeMaskBitmap.recycle();
        }
        biomeMaskBitmap = mask;
        biomeMaskCx0 = cx0;
        biomeMaskCz0 = cz0;
        biomeMaskW = w;
        biomeMaskH = h;
        invalidate();
    }

    /** v0.4.122：biome 遮罩绘制（slimeMask 同款变换——单次
     *  drawBitmap，任意倍率/全图纹理/chunk 路径都叠加）。 */
    private boolean biomeMaskLogged = false;

    private void drawBiomeMask(android.graphics.Canvas canvas) {
        // v0.4.130/131：ppb≥3 不画遮罩——chunk tile 路径（ppb≥3）
        // 直接显示精细群系色；1-3 区间是块图路径（surface 色底图），
        // 遮罩叠加无冲突（v0.4.130 的 ppb≥1 上限留了 1-3 空档=
        // "0.96/1.17 倍群系失效"根因）
        if (!showBiomeLayer || biomeMaskBitmap == null
                || biomeMaskBitmap.isRecycled() || map == null
                || pixelsPerBlock >= 3f) {
            if (!biomeMaskLogged) {
                biomeMaskLogged = true;
                android.util.Log.i("WorldMap", "drawBiomeMask SKIP show="
                        + showBiomeLayer + " bmp=" + (biomeMaskBitmap != null));
            }
            return;
        }
        // v0.4.127：每遮罩像素 = 1 chunk = 16 方块
        float scale = 16f * pixelsPerBlock;
        float leftX = offsetX + (biomeMaskCx0 * 16L - map.minBlockX)
                * pixelsPerBlock;
        float topY = offsetY + (biomeMaskCz0 * 16L - map.minBlockZ)
                * pixelsPerBlock;
        biomeMaskDst.set(leftX, topY,
                leftX + biomeMaskW * scale, topY + biomeMaskH * scale);
        if (scale >= 1f) {
            canvas.drawBitmap(biomeMaskBitmap, null, biomeMaskDst, fullMapPaint);
        } else {
            canvas.drawBitmap(biomeMaskBitmap, null, biomeMaskDst, fullMapLinearPaint);
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

    /* v0.4.59：LOD（缩略图/众数采样/增量更新）整体删除——
     * 全图纹理模式接管缩小场景（HTML 同款零糊渲染），本路径
     * 只在放大时走（视口 chunk 数有限，tile 平铺流畅）。 */

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
        // v0.4.54：遮罩位图路径——单次 drawBitmap（每 chunk 1 像素预渲染，
        // 只含有数据的史莱姆 chunk；缩放任意比例零额外开销）。
        // 放大（chunkPx>=1）最近邻保持锐利色块；缩小线性滤波避免
        // 亚像素绿点被最近邻采样丢弃（缩小看全图时史莱姆不可见）
        if (slimeMaskBitmap != null && !slimeMaskBitmap.isRecycled()) {
            float chunkPx = 16f * pixelsPerBlock;
            float leftX = offsetX + (slimeMaskCx0 * 16L - map.minBlockX) * pixelsPerBlock;
            float topY = offsetY + (slimeMaskCz0 * 16L - map.minBlockZ) * pixelsPerBlock;
            slimeMaskDst.set(leftX, topY,
                    leftX + slimeMaskW * chunkPx, topY + slimeMaskH * chunkPx);
            if (chunkPx >= 1f) {
                canvas.drawBitmap(slimeMaskBitmap, null, slimeMaskDst, fullMapPaint);
            } else {
                if (slimeMaskSoftPaint == null) {
                    slimeMaskSoftPaint = new Paint();
                    slimeMaskSoftPaint.setFilterBitmap(true);
                }
                canvas.drawBitmap(slimeMaskBitmap, null, slimeMaskDst, slimeMaskSoftPaint);
            }
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
        // v0.4.55：遮罩未就绪时的保底——视口 chunk 过多（缩小看全图）
        // 逐块遍历每帧几千次 isSlimeChunk+drawRect 会卡死。超限直接
        // 不画，等遮罩位图就位（compose 完成后）再显示
        long viewChunks = (long) (lastCx - firstCx + 1) * (lastCz - firstCz + 1);
        if (viewChunks > 2500) {
            return;
        }
        Paint slimePaint = new Paint();
        slimePaint.setColor(0x7F00C853); // v0.4.165：18%→50% 太淡看不清
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
        if (map.colors != null) {
            // v0.4.54：小世界查全图数组真实 alpha（此前恒 true——
            // bounds 包络内未生成区域 COLOR_BACKGROUND 透明也画框）
            long ck = packChunk(cx, cz);
            Boolean cached = chunkDataCache.get(ck);
            if (cached != null) {
                return cached;
            }
            int bx = cx * 16 - map.minBlockX;
            int bz = cz * 16 - map.minBlockZ;
            boolean has = false;
            if (bx < map.width && bz < map.height && bx + 16 > 0 && bz + 16 > 0) {
                int lx0 = Math.max(0, bx);
                int lx1 = Math.min(map.width, bx + 16);
                int lz0 = Math.max(0, bz);
                int lz1 = Math.min(map.height, bz + 16);
                for (int lz = lz0; lz < lz1 && !has; lz++) {
                    int row = lz * map.width;
                    for (int lx = lx0; lx < lx1; lx++) {
                        if ((map.colors[row + lx] & 0xFF000000) != 0) {
                            has = true;
                            break;
                        }
                    }
                }
            }
            if (chunkDataCache.size() > 200000) {
                chunkDataCache.clear();
            }
            chunkDataCache.put(ck, has);
            return has;
        }
        // v0.4.54：无像素数据（骨架/RLE 快图期）不画史莱姆——此前
        // 恒 true，进图瞬间全 bounds 画框
        return false;
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
        // v0.4.123：Paint 字段复用（此前每帧/每点新建）
        Paint fill = entFillPaint;
        Paint stroke = structStrokePaint;
        stroke.setColor(0xCC000000);
        stroke.setStrokeWidth(Math.max(1f, pixelsPerBlock * 0.1f));
        Paint labelPaint = pointLabelPaint;
        labelPaint.setTextSize(Math.max(10f, pixelsPerBlock * 0.85f));

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
                pointHlPaint.setStrokeWidth(Math.max(2f, pixelsPerBlock * 0.15f));
                canvas.drawCircle(s[0], s[1], r + 4f, pointHlPaint);
            }
            // 名称标签
            String name = p.name;
            float tw = labelPaint.measureText(name);
            entBgPaint.setColor(0x99000000);
            canvas.drawRoundRect(s[0] - tw / 2f - 4f, s[1] + r + 3f, s[0] + tw / 2f + 4f,
                    s[1] + r + 3f + labelPaint.getTextSize() + 6f, 5f, 5f, entBgPaint);
            labelPaint.setColor(0xFFFFFFFF);
            canvas.drawText(name, s[0] - tw / 2f, s[1] + r + 3f + labelPaint.getTextSize(), labelPaint);
        }
    }

    /** v457：启动器背景色（未渲染区块背景）。 */
    private int launcherBackgroundColor() {
        try {
            return androidx.core.content.ContextCompat.getColor(
                    getContext(), org.levimc.launcher.R.color.background);
        } catch (Throwable ignored) {
            return 0xFFF8FFF0;
        }
    }

    /** v457：玩家标记贴图缓存（16×16 PNG）。 */
    private android.graphics.Bitmap playerMarkerBmp;

    /** v457：玩家标记贴图（用户提供的 16×16 PNG，进程级缓存）。 */
    private android.graphics.Bitmap playerMarkerBitmap() {
        if (playerMarkerBmp != null) {
            return playerMarkerBmp;
        }
        try (java.io.InputStream in = getContext().getAssets()
                .open("map_markers/player_marker.png")) {
            playerMarkerBmp = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        return playerMarkerBmp;
    }

    /** 绘制玩家标记（v457：贴图图标，底部中心锚定坐标点）
     *  与出生点（房子图标，底部中心锚定）。 */
    private void drawMarkers(android.graphics.Canvas canvas) {
        if (map == null) {
            return;
        }
        if (map.spawnBlockX >= 0) {
            float sx = offsetX + (map.spawnBlockX - map.minBlockX + 0.5f) * pixelsPerBlock;
            float sz = offsetY + (map.spawnBlockZ - map.minBlockZ + 0.5f) * pixelsPerBlock;
            float size = Math.max(32f, pixelsPerBlock * 3.0f); // v0.4.112：缩小状态标记加大可见
            android.graphics.drawable.Drawable house =
                    androidx.core.content.ContextCompat.getDrawable(
                            getContext(), org.levimc.launcher.R.drawable.ic_house);
            if (house != null) {
                house.setBounds((int) (sx - size / 2f), (int) (sz - size),
                        (int) (sx + size / 2f), (int) sz);
                house.draw(canvas);
            }
        }
        if (map.playerBlockX >= 0) {
            float px = offsetX + (map.playerBlockX - map.minBlockX + 0.5f) * pixelsPerBlock;
            float pz = offsetY + (map.playerBlockZ - map.minBlockZ + 0.5f) * pixelsPerBlock;
            android.graphics.Bitmap bmp = playerMarkerBitmap();
            if (bmp != null) {
                float size = Math.max(34f, pixelsPerBlock * 3.2f); // v0.4.112：缩小状态标记加大可见
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                                | android.graphics.Paint.FILTER_BITMAP_FLAG);
                android.graphics.RectF dst = new android.graphics.RectF(
                        px - size / 2f, pz - size, px + size / 2f, pz);
                canvas.drawBitmap(bmp, null, dst, p);
            } else {
                // 贴图缺失回退：绿点
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setAntiAlias(true);
                paint.setColor(0xFF3FE33F);
                canvas.drawCircle(px, pz, Math.max(4f, pixelsPerBlock * 0.7f), paint);
            }
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
        // v0.4.123：Paint 字段复用（此前每帧新建——几千实体每帧
        // new Paint/RectF = GC 抖动掉帧）
        Paint fill = entFillPaint;
        Paint labelPaint = pixelsPerBlock >= 2f ? entLabelPaint : null;
        float r = Math.max(2.5f, pixelsPerBlock * 0.35f);
        // 贴图：放大到 0.8px/block 以上才用（缩太小时图标重叠糊屏）
        boolean useIcons = pixelsPerBlock >= 0.8f;
        float iconSize = Math.max(16f, pixelsPerBlock * 1.6f);
        Paint iconPaint = entIconPaint;
        // v0.4.113：抽稀——视口内实体超 1200 时隔位绘制
        // v0.4.118：扩到全倍率（与矿石同款；点击检测走全量列表
        // 与绘制无关）
        // v0.4.140：stride 用总量近似（省掉 2 万次计数循环——遍历
        // 成本虽低但计数循环是纯浪费；手势中冻结已有 oreStride 同款）
        int entStride = 1;
        if (pixelsPerBlock < 16f && entities.size() > 1500) {
            if (scaling || panning) {
                entStride = Math.max(1, lastEntStride);
            } else {
                entStride = Math.max(1, entities.size() / 1200);
                lastEntStride = entStride;
            }
        }
        int entCount = 0;
        drawnEntities = 0;
        for (WorldMapRenderer.EntityPos e : entities) {
            // 未渲染 chunk 上的实体不显示（视口按需渲染出来后才出现）
            if (!chunkRendered(Math.floorDiv((int) e.x, 16), Math.floorDiv((int) e.z, 16))) {
                continue;
            }
            if ((entCount++ % entStride) != 0) {
                continue; // v0.4.113：抽稀跳过
            }
            float sx = worldToScreenX(e.x + 0.5f);
            float sy = worldToScreenY(e.z + 0.5f);
            if (sx < -40 || sx > getWidth() + 40 || sy < -40 || sy > getHeight() + 40) {
                continue;
            }
            drawnEntities++;
            Bitmap icon = useIcons ? iconBitmap(e.name) : null;
            if (icon != null) {
                // v0.4.123：RectF 字段复用（此前每实体 new 一个）
                entIconDst.set(sx - iconSize / 2f, sy - iconSize / 2f,
                        sx + iconSize / 2f, sy + iconSize / 2f);
                canvas.drawBitmap(icon, null, entIconDst, iconPaint);
                r = iconSize / 2f;
            } else {
                fill.setColor(entityColor(e.name));
                canvas.drawCircle(sx, sy, r, fill);
            }
            if (labelPaint != null) {
                String label = entityLabel(e.name);
                float tw = labelPaint.measureText(label);
                entBgPaint.setColor(0x99000000);
                canvas.drawRoundRect(sx - tw / 2f - 3f, sy + r + 2f, sx + tw / 2f + 3f,
                        sy + r + 2f + labelPaint.getTextSize() + 4f, 4f, 4f, entBgPaint);
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
        if (b == null) {
            // v0.4.60：结构贴图走 assets/structure_icons/（游戏原版
            // 16×16 方块贴图，最近邻放大到 32——双线性会把像素风
            // 糊掉）
            try (java.io.InputStream is = getContext().getAssets()
                    .open("structure_icons/" + name + ".png")) {
                Bitmap src = BitmapFactory.decodeStream(is);
                if (src != null) {
                    b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    Paint np = new Paint();
                    np.setFilterBitmap(false);
                    c.drawBitmap(src, null, new android.graphics.RectF(0, 0, 32, 32), np);
                    src.recycle();
                }
            } catch (Exception ignored) {
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

    /** 实体名 → 中文标签（共享映射表见 WorldMapRenderer.ENTITY_ZH，
     *  未知保持原名；switch 为旧版兜底）。 */
    public static String entityLabel(String name) {
        String zh = WorldMapRenderer.ENTITY_ZH.get(name);
        if (zh != null) {
            return zh;
        }
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
        // v0.4.123：Paint 字段复用（此前每帧/每结构新建）
        Paint p = structPaint;
        Paint stroke = structStrokePaint;
        stroke.setStrokeWidth(Math.max(1.5f, pixelsPerBlock * 0.12f));
        stroke.setColor(0xCC000000);
        Paint labelPaint = pixelsPerBlock >= 0.9f ? structLabelPaint : null;
        Paint iconPaint = structIconPaint;
        drawnStructures = 0;
        for (WorldMapRenderer.StructureMarker m : structures) {
            // v0.4.136：SeedFinder 预测结构（predicted=true）不要求
            // chunk 已渲染——预测位置本就允许未生成区域显示（恢复
            // v0.4.57 设计；v0.4.61 收紧导致"丛林神殿/林地府邸/前哨
            // 站不显示"根因）；db 实测结构仍要求渲染后显示
            if (!m.predicted
                    && !chunkRendered(Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16))) {
                continue;
            }
            float sx = worldToScreenX(m.x + 0.5f);
            float sy = worldToScreenY(m.z + 0.5f);
            if (sx < -60 || sx > getWidth() + 60 || sy < -60 || sy > getHeight() + 60) {
                continue;
            }
            drawnStructures++;
            float r = Math.max(7f, pixelsPerBlock * 0.9f);
            // 结构贴图（游戏原版方块贴图），失败回退白底圆+自绘形状
            String iconName = structureIconName(m.type);
            Bitmap icon = iconName != null ? iconBitmap(iconName) : null;
            if (icon != null) {
                // v0.4.61：有贴图时不画白底圆——旧标点样式与贴图
                // 重叠难看（"新贴图叠在旧白色标点上"的根因）
                float isz = r * 1.7f;
                structIconDst.set(sx - isz / 2f, sy - isz / 2f,
                        sx + isz / 2f, sy + isz / 2f);
                canvas.drawBitmap(icon, null, structIconDst, iconPaint);
                r = isz / 2f;
            } else {
                // 白底圆（深色地形上保持醒目，无贴图类型回退样式）
                canvas.drawCircle(sx, sy, r, structBgPaint);
                stroke.setColor(0x80000000);
                stroke.setStrokeWidth(1.5f);
                canvas.drawCircle(sx, sy, r, stroke);
                stroke.setColor(0xCC000000);
                drawStructureShape(canvas, m.type, sx, sy, r, p, stroke);
            }
            if (labelPaint != null) {
                String label = structureLabel(getContext(), m.type);
                float tw = labelPaint.measureText(label);
                entBgPaint.setColor(0x99000000);
                canvas.drawRoundRect(sx - tw / 2f - 3f, sy + r + 2f, sx + tw / 2f + 3f,
                        sy + r + 2f + labelPaint.getTextSize() + 4f, 4f, 4f, entBgPaint);
                canvas.drawText(label, sx - tw / 2f, sy + r + 2f + labelPaint.getTextSize(), labelPaint);
            }
        }
    }

    /** 结构类型 → 贴图资源名（无贴图的返回 null 走自绘）。
     *  v0.4.60：改用游戏原版方块贴图（assets/structure_icons/，
     *  从 1.26 vanilla 资源包 blocks.brarchive 提取）。
     *  v0.4.66：改 public——标点栏列表条目也要用贴图。 */
    public static String structureIconName(String type) {
        switch (type) {
            case "village": return "bell_side";
            case "spawner": return "mob_spawner";
            case "trial_spawner": return "trial_spawner_side_active";
            case "end_portal": return "end_portal";
            case "fortress": return "nether_brick";
            case "end_city": return "purpur_block";
            case "desert_pyramid": return "sandstone_normal";
            case "desert_temple": return "sandstone_normal";
            case "outpost": return "stripped_dark_oak_log";
            case "jungle_temple":
            case "jungle_pyramid": return "cobblestone_mossy";
            case "swamp_hut": return "planks_spruce";
            case "igloo": return "snow";
            case "ocean_ruin": return "stonebrick_mossy";
            case "shipwreck": return "barrel_side";
            case "ocean_monument":
            case "monument": return "prismarine_rough";
            case "woodland_mansion":
            case "mansion": return "planks_big_oak";
            case "pillager_outpost": return "stripped_dark_oak_log";
            case "ruined_portal": return "obsidian";
            case "ancient_city": return "deepslate_bricks";
            case "buried_treasure":
            case "treasure": return "chest_front";
            case "mineshaft": return "rail_normal";
            case "trail_ruins": return "suspicious_gravel_0";
            case "trial_chambers": return "copper_grate";
            default: return null;
        }
    }

    /** 结构类型 → 本地化名（v0.4.61：11 语言资源词条；static 供标点栏复用）。 */
    public static String structureLabel(android.content.Context ctx, String type) {
        switch (type) {
            case "village": return ctx.getString(R.string.structure_label_village);
            case "spawner": return ctx.getString(R.string.structure_label_spawner);
            case "trial_spawner": return ctx.getString(R.string.structure_label_trial_spawner);
            case "end_portal": return ctx.getString(R.string.structure_label_end_portal);
            case "fortress": return ctx.getString(R.string.structure_label_fortress);
            case "swamp_hut": return ctx.getString(R.string.structure_label_swamp_hut);
            case "ocean_monument":
            case "monument": return ctx.getString(R.string.structure_label_ocean_monument);
            case "outpost": return ctx.getString(R.string.structure_label_outpost);
            case "desert_temple": return ctx.getString(R.string.structure_label_desert_temple);
            case "end_city": return ctx.getString(R.string.structure_label_end_city);
            // v0.4.57：SeedFinder 离线查找的类型名（cubiomes struct2str）
            case "desert_pyramid": return ctx.getString(R.string.structure_label_desert_pyramid);
            case "jungle_temple": return ctx.getString(R.string.structure_label_jungle_temple);
            case "igloo": return ctx.getString(R.string.structure_label_igloo);
            case "ocean_ruin": return ctx.getString(R.string.structure_label_ocean_ruin);
            case "shipwreck": return ctx.getString(R.string.structure_label_shipwreck);
            case "woodland_mansion":
            case "mansion": return ctx.getString(R.string.structure_label_woodland_mansion);
            case "pillager_outpost": return ctx.getString(R.string.structure_label_pillager_outpost);
            case "ruined_portal": return ctx.getString(R.string.structure_label_ruined_portal);
            case "ancient_city": return ctx.getString(R.string.structure_label_ancient_city);
            case "buried_treasure":
            case "treasure": return ctx.getString(R.string.structure_label_buried_treasure);
            case "mineshaft": return ctx.getString(R.string.structure_label_mineshaft);
            case "trail_ruins": return ctx.getString(R.string.structure_label_trail_ruins);
            case "trial_chambers": return ctx.getString(R.string.structure_label_trial_chambers);
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
                case "desert_temple": {
                    // 沙漠神殿：砂岩金字塔（顶角 + 门洞）
                    p.setColor(0xFFD8C08A);
                    Path tp = new Path();
                    tp.moveTo(sx, sy - r);
                    tp.lineTo(sx - r * 0.8f, sy + r * 0.6f);
                    tp.lineTo(sx + r * 0.8f, sy + r * 0.6f);
                    tp.close();
                    canvas.drawPath(tp, p);
                    canvas.drawPath(tp, stroke);
                    canvas.drawRect(sx - r * 0.25f, sy - r * 0.1f, sx + r * 0.25f, sy + r * 0.4f, stroke);
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
        if (map == null) {
            return;
        }
        float sx;
        float sy;
        if (tapCenterEnabled) {
            // v0.4.139：中心准星模式——固定屏幕中心（读取层实时
            // 换算中心世界坐标）
            sx = getWidth() / 2f;
            sy = getHeight() / 2f;
        } else if (tapScreenX >= 0) {
            // v0.4.146：默认屏幕锚定（始终显示同款逻辑——指针钉在
            // 屏幕上，任何缩放都不跑出视口；此前 world-follow 世界
            // 坐标在特定缩放下换算到屏幕外="老 bug 指针不显示"）。
            // v0.4.165：滑动/捏合中永久清除——不再自动恢复，
            // 下次点击屏幕才会重新显示（用户要求）
            if (scaling || panning) {
                tapScreenX = -1f;
                return;
            }
            sx = tapScreenX;
            sy = tapScreenY;
        } else {
            return;
        }
        int[] live = screenToBlock(sx, sy);
        Paint line = new Paint();
        line.setAntiAlias(true);
        line.setStrokeWidth(2f);
        float arm = Math.max(12f, pixelsPerBlock * 1.2f);
        line.setColor(0x99000000);
        canvas.drawLine(sx - arm, sy, sx + arm, sy, line);
        canvas.drawLine(sx, sy - arm, sx, sy + arm, line);
        line.setStrokeWidth(1f);
        line.setColor(0xFFFFFFFF);
        canvas.drawLine(sx - arm, sy, sx + arm, sy, line);
        canvas.drawLine(sx, sy - arm, sx, sy + arm, line);
        // 坐标标签（实时世界坐标）
        String text = "(" + live[0] + ", " + live[1] + ")";
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
    public boolean onGenericMotionEvent(MotionEvent event) {
        // v0.4.0：鼠标滚轮缩放（外接鼠标/触控板），以光标位置为锚点
        if (map != null && event.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0f) {
                float factor = v > 0 ? 1.25f : 0.8f;
                float newPpb = clampPixelsPerBlock(pixelsPerBlock * factor);
                float applied = newPpb / pixelsPerBlock;
                offsetX = event.getX() - (event.getX() - offsetX) * applied;
                offsetY = event.getY() - (event.getY() - offsetY) * applied;
                setPpb(newPpb);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (map == null) {
            return super.onTouchEvent(event);
        }
        // 3D 区域选择模式（BedrockMap 右键拖选同款）：按下记起点，
        // 拖动拉出矩形，松手回调区域并退出模式
        if (voxelSelectMode) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int[] b = screenToBlock(event.getX(), event.getY());
                    voxelStartX = b[0];
                    voxelStartZ = b[1];
                    voxelCurX = b[0];
                    voxelCurZ = b[1];
                    voxelSelecting = true;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (voxelSelecting) {
                        int[] b = screenToBlock(event.getX(), event.getY());
                        voxelCurX = b[0];
                        voxelCurZ = b[1];
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (voxelSelecting && regionSelectListener != null) {
                        int minX = Math.min(voxelStartX, voxelCurX);
                        int maxX = Math.max(voxelStartX, voxelCurX);
                        int minZ = Math.min(voxelStartZ, voxelCurZ);
                        int maxZ = Math.max(voxelStartZ, voxelCurZ);
                        // 至少 2×2 方块才算选区
                        if (maxX - minX >= 1 && maxZ - minZ >= 1) {
                            regionSelectListener.onRegionSelected(minX, minZ, maxX, maxZ);
                        }
                    }
                    voxelSelecting = false;
                    voxelSelectMode = false;
                    invalidate();
                    return true;
                }
            }
            return true;
        }
        // 阻止父布局（ScrollView 等）拦截拖动手势
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        // 松手：全量重采样一帧（拖动期间是位图平移预览，边缘/新区域要刷新）
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // v0.4.99：拖拽结束——补画叠加层 + 全量重采样
            panning = false;
            if (!scalePreviewActive) {
                invalidateFullRender();
            }
        }
        return true;
    }

    /** 3D 区域选择回调（松手时返回选中矩形区域）。 */
    public interface OnRegionSelectListener {
        void onRegionSelected(int minX, int minZ, int maxX, int maxZ);
    }

    private OnRegionSelectListener regionSelectListener;
    private boolean voxelSelectMode;
    private boolean voxelSelecting;
    private int voxelStartX;
    private int voxelStartZ;
    private int voxelCurX;
    private int voxelCurZ;

    public void setOnRegionSelectListener(OnRegionSelectListener l) {
        this.regionSelectListener = l;
    }

    /** 进入/退出 3D 区域选择模式。 */
    public void setVoxelSelectMode(boolean on) {
        voxelSelectMode = on;
        voxelSelecting = false;
        invalidate();
    }

    /** 结构标记点击回调（详情弹窗：NBT/附近实体/坐标）。 */
    public interface OnStructureClickListener {
        void onStructureClick(WorldMapRenderer.StructureMarker marker);
    }

    private OnStructureClickListener structureClickListener;

    public void setOnStructureClickListener(OnStructureClickListener l) {
        this.structureClickListener = l;
    }

    /** v413：矿石标点点击回调（详情弹窗：矿石名/坐标/数量）。 */
    public interface OnOreClickListener {
        void onOreClick(WorldMapRenderer.OreMarker marker);
    }

    private OnOreClickListener oreClickListener;

    public void setOnOreClickListener(OnOreClickListener l) {
        this.oreClickListener = l;
    }

    /** v413/v417：实体点击回调——v417 改为传递点击点附近的实体
     *  列表（实体密堆时弹列表选择，而不是只命中一个）。 */
    public interface OnEntityClickListener {
        void onEntityClick(java.util.List<WorldMapRenderer.EntityPos> entities);
    }

    private OnEntityClickListener entityClickListener;

    public void setOnEntityClickListener(OnEntityClickListener l) {
        this.entityClickListener = l;
    }

    /** v413：矿石标点命中检测。 */
    private WorldMapRenderer.OreMarker hitTestOre(float sx, float sy) {
        if (map == null || map.oreMarkers == null) {
            return null;
        }
        float r = Math.max(20f, pixelsPerBlock * 1.6f);
        for (WorldMapRenderer.OreMarker m : map.oreMarkers) {
            float px = offsetX + (m.blockX - map.minBlockX + 0.5f) * pixelsPerBlock;
            float py = offsetY + (m.blockZ - map.minBlockZ + 0.5f) * pixelsPerBlock;
            if (Math.abs(sx - px) <= r && Math.abs(sy - py) <= r) {
                return m;
            }
        }
        return null;
    }

    /** v417：点击点附近实体收集——检测半径加大（32px，小屏幕
     *  点击困难），密堆时返回半径内全部实体（最多 20 个）。 */
    private java.util.List<WorldMapRenderer.EntityPos> collectEntitiesNear(
            float sx, float sy) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        java.util.List<WorldMapRenderer.EntityPos> near =
                new java.util.ArrayList<>();
        for (WorldMapRenderer.EntityPos ep : entities) {
            if (near.size() >= 20) {
                break;
            }
            if (!chunkRendered(Math.floorDiv((int) ep.x, 16),
                    Math.floorDiv((int) ep.z, 16))) {
                continue;
            }
            float px = worldToScreenX(ep.x);
            float py = worldToScreenY(ep.z);
            if (Math.abs(sx - px) <= 32f && Math.abs(sy - py) <= 32f) {
                near.add(ep);
            }
        }
        return near.isEmpty() ? null : near;
    }

    /** 结构标记命中检测（半径 max(24px, ppb×1.4)）。 */
    private WorldMapRenderer.StructureMarker hitTestStructure(float sx, float sy) {
        for (WorldMapRenderer.StructureMarker m : structures) {
            if (!chunkRendered(Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16))) {
                continue;
            }
            float px = worldToScreenX(m.x + 0.5f);
            float py = worldToScreenY(m.z + 0.5f);
            // v417：检测范围加大（小屏幕点击困难）
            float r = Math.max(38f, pixelsPerBlock * 2f);
            if (Math.abs(sx - px) <= r && Math.abs(sy - py) <= r) {
                return m;
            }
        }
        return null;
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
            setPpb(newPpb);
            // 缩放期间用缓存位图做预览（不重采样，保证捏合流畅），结束全量重绘
            scaleFocusX = detector.getFocusX();
            scaleFocusY = detector.getFocusY();
            scalePreviewActive = cachedBmp != null;
            // 缩放中暂停按需渲染报告：视口每帧剧变，收集的 missing 是瞬态
            // 集合，批量提交后缩放停止前又过期——渲染队列被过期的 chunk
            // 塞满，等停止后当前视口反而排最后（"缩放乱搞渲染就乱"根因）
            scaling = true;
            clampTranslation();
            invalidate();
            notifyViewChanged();
            return true;
        }

        @Override
        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
            // 捏合结束：全量重采样一帧 + 恢复按需渲染报告
            // （onDraw 下一帧收集当前视口真实缺失，只渲染需要的 chunk）
            scalePreviewActive = false;
            scaling = false;
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
            // v0.4.144：不再清空指针——捏合/慢速滑动时 GestureDetector
            // 可能误触发 onLongPress，此前清空导致「指针始终显示勾选后
            // 手势中指针消失」（用户实测）
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
            // 单击结构标记 → 结构详情回调（NBT/附近实体/坐标）
            if (showStructures && structureClickListener != null) {
                WorldMapRenderer.StructureMarker sm = hitTestStructure(e.getX(), e.getY());
                if (sm != null) {
                    structureClickListener.onStructureClick(sm);
                    return true;
                }
            }
            // v413：单击矿石标点 → 详情回调（矿石名/坐标/数量）
            if (showOreLayer && oreClickListener != null) {
                WorldMapRenderer.OreMarker om = hitTestOre(e.getX(), e.getY());
                if (om != null) {
                    oreClickListener.onOreClick(om);
                    return true;
                }
            }
            // v413/v417：单击实体 → 附近实体列表回调（密堆弹列表）
            // v0.4.54：实体显示关闭时点击也不命中（此前只关绘制没关点击）
            if (showEntities && entityClickListener != null) {
                java.util.List<WorldMapRenderer.EntityPos> near =
                        collectEntitiesNear(e.getX(), e.getY());
                if (near != null) {
                    entityClickListener.onEntityClick(near);
                    return true;
                }
            }
            // v0.4.139：点击落在 HUD 勾选行上 → 切换指针模式
            // v0.4.146：删「指针始终显示」行（用户拍板）
            if (centerRowRect.contains(e.getX(), e.getY())) {
                tapCenterEnabled = !tapCenterEnabled;
                invalidate();
                return true;
            }
            // 单击空地 → 显示该处坐标（十字标记 + 标签，HTML 原型行为）
            // v0.4.138：显示/读取分离——十字锚定屏幕坐标（缩放时
            // 纹丝不动，不再随地图移出屏幕="缩放到一定比例后消失"
            // 根因）；世界坐标在绘制时实时从屏幕坐标换算（读取层）
            int[] b = screenToBlock(e.getX(), e.getY());
            tapBlockX = b[0];
            tapBlockZ = b[1];
            tapScreenX = e.getX();
            tapScreenY = e.getY();
            invalidate();
            if (listener != null) {
                listener.onMapTap(b[0], b[1]);
            }
            return true;
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                float distanceX, float distanceY) {
            panning = true;
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
            tapScreenX = -1f;
            tapScreenY = -1f;
            // 整图适配 ↔ 2 倍放大（相对当前）
            float fit = Math.min(getWidth() / (float) map.width, getHeight() / (float) map.height);
            if (pixelsPerBlock > fit * 1.5f) {
                fitToView();
            } else {
                float target = clampPixelsPerBlock(fit * 4f);
                float applied = target / pixelsPerBlock;
                offsetX = e.getX() - (e.getX() - offsetX) * applied;
                offsetY = e.getY() - (e.getY() - offsetY) * applied;
                setPpb(target);
                clampTranslation();
            }
            invalidate();
            notifyViewChanged();
            return true;
        }
    }
}
