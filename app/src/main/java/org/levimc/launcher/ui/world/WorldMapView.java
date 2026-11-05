package org.levimc.launcher.ui.world;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.world.MapPalette;
import org.levimc.launcher.core.content.world.MapSettings;
import org.levimc.launcher.core.content.world.WorldMapRenderer;
import org.levimc.launcher.core.content.world.WorldRecords;
import org.levimc.launcher.core.content.world.WorldSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WorldMapView extends View {
    public interface Listener {
        void inspect(int x, int z, boolean longPress);

        // v0.4.214：点击命中结构图标（type=结构类型名）
        void structure(int x, int z, String type);

        void moved(double x, double z, float scale);

        void error(String message);

        // v0.4.214：框选变化——粗框完成/顶点拖点后回调（points=顶点链
        // block 坐标顺时针；null=选区已清除）
        void selected(int[][] points);
    }

    private static final class Tile {
        Bitmap image;
        String error;
        List<WorldRecords.Marker> markers = new ArrayList<>();
    }

    private final WorldSession session;
    private final WorldMapRenderer renderer;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path crosshair = new Path();
    private final float density;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleDetector;
    private final Map<Long, Tile> tiles =
            new LinkedHashMap<>(256, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Tile> eldest) {
                    // v0.4.214：统一 32768——平板大屏 scale 1.4 时屏幕内
                    // tile 达 18600 个（实测），2048/8192 上限都触发边缘
                    // 淘汰重载循环（pending 恒定 44=反复重载，棋盘格「不
                    // 显示」）。16×16 ARGB tile 仅 1KB，32768≈32MB 可接受。
                    return size() > 32768;
                }
            };
    private final Set<Long> pending = new HashSet<>();
    private List<WorldRecords.Marker> players = new ArrayList<>(), bookmarks = new ArrayList<>();
    private ThreadPoolExecutor worker;
    private Listener listener;
    private MapSettings settings = new MapSettings();
    private double centerX, centerZ;
    private float scale = 5;
    private int generation;
    private long contentRevision = -1;
    private boolean active;
    private String lastError = "";
    private Integer selectionX, selectionZ, selectionEndX, selectionEndZ;
    private int background, surface, foreground, accent;
    private int frameCount;
    // v0.4.214：LOD 梯次跨度（1=原样 chunk；16/32/64=16/32/64 块每像素采样）
    private int lodSpan = 1;
    // v0.4.214：左侧被抽屉覆盖的像素宽——overlay 布局下准星/点击/
    // 缩放锚点/底部 HUD 都以「可视中心」为准（drawer 面板展开时偏移）
    private int leftInset;
    // v0.4.214：框选工具（LBBL 方案）——单指滑动粗框选→四角与区块
    // 交接处顶点方块级拖动微调（凹/凸形）→应用唤出二级菜单。
    // 顶点链：顺时针 block 坐标，正交多边形（相邻段水平/垂直交替）
    private boolean selectMode;
    private int selStage; // 0=空闲 1=粗框绘制中 2=微调中
    private final List<int[]> selPoints = new ArrayList<>();
    private int[] selBox; // 粗框完成时的 bbox（拖点 clamp 基准）
    private int[] dragStart, dragCur; // 粗框世界坐标
    private int dragIdx = -1;
    private List<int[]> dragSnapshot;
    private int[] dragDown, dragOrigin; // 拖点按下时的手指/顶点世界坐标（相对位移）
    private float downRawX, downRawY; // DOWN 屏幕坐标（单击抖动防护）
    private float lastUpX, lastUpY; // 最后收手屏幕坐标（二级菜单气泡锚点）
    // v0.4.214：四角分工——左上=整体平移（橙方块）；右上/右下/左下=
    // 缩放（对角锚定，resize 图标）。中间点=形变手柄（accent 方块）
    private int[] cornerIdx;
    private boolean dragMove, dragScale;
    private int[] dragAnchor;
    private Bitmap resizeIcon;
    // v0.4.214：仅左上=平移、右下=缩放（用户拍板简化——右上/左下
    // 手柄命中手感问题多轮，只保留对角线两端最直观的两个）
    private int moveCorner = 0, scaleCorner = 2;
    private final java.util.ArrayDeque<List<int[]>> selUndo = new java.util.ArrayDeque<>();

    public WorldMapView(Context context, WorldSession session, WorldMapRenderer renderer) {
        super(context);
        this.session = session;
        this.renderer = renderer;
        density = context.getResources().getDisplayMetrics().density;
        float arm = 11 * density, gap = 3 * density;
        crosshair.moveTo(-arm, 0);
        crosshair.lineTo(-gap, 0);
        crosshair.moveTo(gap, 0);
        crosshair.lineTo(arm, 0);
        crosshair.moveTo(0, -arm);
        crosshair.lineTo(0, -gap);
        crosshair.moveTo(0, gap);
        crosshair.lineTo(0, arm);
        background = WorldUi.color(context, R.color.background);
        surface = WorldUi.color(context, R.color.surface_variant);
        foreground = WorldUi.color(context, R.color.on_surface);
        accent = WorldUi.accent(context);
        setFocusable(true);
        setContentDescription(getContext().getString(R.string.world_map_desc));
        gestures =
                new GestureDetector(
                        context,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onScroll(
                                    MotionEvent start, MotionEvent current, float dx, float dy) {
                                if (!scaleDetector.isInProgress()) {
                                    centerX += dx / scale;
                                    centerZ += dy / scale;
                                    changed();
                                }
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent e) {
                                int wx = worldX(e.getX()), wz = worldZ(e.getY());
                                // v0.4.214：先检测结构图标命中（点击结构弹信息）
                                String type = hitStructure(wx, wz);
                                if (type != null && listener != null) {
                                    listener.structure(wx, wz, type);
                                } else if (listener != null) {
                                    listener.inspect(wx, wz, false);
                                }
                                performClick();
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent e) {
                                zoom(1.7f, e.getX(), e.getY());
                                return true;
                            }

                            @Override
                            public void onLongPress(MotionEvent e) {
                                if (!scaleDetector.isInProgress() && listener != null)
                                    listener.inspect(worldX(e.getX()), worldZ(e.getY()), true);
                            }
                        });
        scaleDetector =
                new ScaleGestureDetector(
                        context,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            @Override
                            public boolean onScale(ScaleGestureDetector detector) {
                                zoom(
                                        detector.getScaleFactor(),
                                        detector.getFocusX(),
                                        detector.getFocusY());
                                return true;
                            }
                        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void configure(MapSettings settings) {
        long revision = session.revision();
        boolean changed =
                contentRevision != revision
                        || !this.settings.renderKey().equals(settings.renderKey())
                        || this.settings.entities != settings.entities
                        || this.settings.blockEntities != settings.blockEntities
                        || !this.settings.entityFilter.equals(settings.entityFilter)
                        || !this.settings.blockEntityFilter.equals(settings.blockEntityFilter);
        this.settings = settings.copy();
        contentRevision = revision;
        if (changed) refresh();
        else invalidate();
    }

    public void setPlayers(List<WorldRecords.Marker> value) {
        players = new ArrayList<>(value);
        invalidate();
    }

    public void setBookmarks(List<WorldRecords.Marker> value) {
        bookmarks = new ArrayList<>(value);
        invalidate();
    }

    public void center(double x, double z) {
        centerX = Math.max(-30000000, Math.min(30000000, x));
        centerZ = Math.max(-30000000, Math.min(30000000, z));
        changed();
    }

    /** v0.4.214：左侧被抽屉覆盖的像素宽（overlay 布局下可视中心偏移）。 */
    public void setLeftInset(int px) {
        if (leftInset == px) return;
        leftInset = px;
        invalidate();
    }

    /** v0.4.214：最后收手屏幕坐标（二级菜单气泡锚定手指右侧）。 */
    public float lastUpX() {
        return lastUpX;
    }

    public float lastUpY() {
        return lastUpY;
    }

    public void viewport(double x, double z, float scale) {
        // v0.4.214：缩放下限放宽到 1/64（16/32/64 三档 LOD 缩放等级）
        this.scale = Math.max(1 / 64f, Math.min(48, scale));
        center(x, z);
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public float zoom() {
        return scale;
    }

    public void zoom(float multiplier) {
        zoom(multiplier, getWidth() / 2f, getHeight() / 2f);
    }

    private void zoom(float multiplier, float focusX, float focusY) {
        // v0.4.214：锚点用可视中心（左侧抽屉覆盖区不算可视区）
        float cx = visualCenterX();
        double x = centerX + (focusX - cx) / scale,
                z = centerZ + (focusY - getHeight() / 2f) / scale;
        scale = Math.max(1 / 64f, Math.min(48, scale * multiplier));
        centerX = x - (focusX - cx) / scale;
        centerZ = z - (focusY - getHeight() / 2f) / scale;
        changed();
    }

    private void changed() {
        centerX = Math.max(-30000000, Math.min(30000000, centerX));
        centerZ = Math.max(-30000000, Math.min(30000000, centerZ));
        // v0.4.214：16/32/64 三档 LOD 梯次（用户指令）——1px=16块→span16、
        // 1px=32块→span32、1px=64块→span64；迟滞防边界来回切换。
        int span;
        if (lodSpan == 64) span = scale > 1 / 24f ? (scale > 1 / 12f ? 1 : 16) : 64;
        else if (lodSpan == 32) span = scale <= 1 / 48f ? 64 : (scale > 1 / 20f ? (scale > 1 / 12f ? 1 : 16) : 32);
        else if (lodSpan == 16) span = scale <= 1 / 40f ? (scale <= 1 / 56f ? 64 : 32) : (scale > 1 / 12f ? 1 : 16);
        else span = scale <= 1 / 14f ? (scale <= 1 / 28f ? (scale <= 1 / 56f ? 64 : 32) : 16) : 1;
        if (span != lodSpan) {
            lodSpan = span;
            generation++;
            pending.clear();
            tiles.clear();
            if (worker != null) worker.getQueue().clear();
        }
        if (listener != null) listener.moved(centerX, centerZ, scale);
        invalidate();
    }

    private int worldX(float x) {
        return (int) Math.floor(centerX + (x - visualCenterX()) / scale);
    }

    private int worldZ(float y) {
        return (int) Math.floor(centerZ + (y - getHeight() / 2f) / scale);
    }

    /** 可视中心 x（左侧被抽屉覆盖的像素不计入可视区）。 */
    private float visualCenterX() {
        return leftInset + (getWidth() - leftInset) / 2f;
    }

    public void selection(Integer x, Integer z, Integer endX, Integer endZ) {
        selectionX = x;
        selectionZ = z;
        selectionEndX = endX;
        selectionEndZ = endZ;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // v0.4.214：框选模式——单指滑动粗框/拖顶点微调，双指缩放仍可用
        if (selectMode) return onSelectTouch(e);
        scaleDetector.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    // ---------------- v0.4.214：框选工具 ----------------

    /** 进入/退出框选模式。退出时清空选区与撤销栈。 */
    public void setSelectMode(boolean mode) {
        selectMode = mode;
        selStage = 0;
        dragIdx = -1;
        dragOrigin = null;
        dragSnapshot = null;
        selPoints.clear();
        selUndo.clear();
        selBox = null;
        invalidate();
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public boolean hasSelection() {
        return selectMode && selStage == 2 && !selPoints.isEmpty();
    }

    /** 顶点链快照（旋转恢复用）。 */
    public int[][] selectionPoints() {
        int[][] out = new int[selPoints.size()][];
        for (int i = 0; i < out.length; i++)
            out[i] = selPoints.get(i).clone();
        return out;
    }

    /** 旋转恢复选区。 */
    public void restoreSelection(int[][] points) {
        selPoints.clear();
        if (points != null)
            for (int[] p : points) selPoints.add(new int[] {p[0], p[1]});
        selStage = selPoints.isEmpty() ? 0 : 2;
        selBox = null;
        invalidate();
    }

    /** 撤销：回退上一步顶点拖点。 */
    public void undoSelection() {
        if (selUndo.isEmpty()) return;
        List<int[]> prev = selUndo.pop();
        selPoints.clear();
        for (int[] p : prev) selPoints.add(new int[] {p[0], p[1]});
        dragIdx = -1;
        notifySelected();
        invalidate();
    }

    /** 关闭选区（保留框选模式，等待下一次粗框）。 */
    public void clearSelection() {
        selStage = 0;
        selPoints.clear();
        selUndo.clear();
        selBox = null;
        dragIdx = -1;
        if (listener != null) listener.selected(null);
        invalidate();
    }

    private void notifySelected() {
        if (listener != null) listener.selected(selectionPoints());
    }

    private boolean onSelectTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY();
                downRawX = x;
                downRawY = y;
                // 微调态：命中顶点=开始拖点（先压撤销栈，记录按下偏移
                // 保持相对位移——顶点不跳到手指位置）
                if (selStage == 2) {
                    int idx = hitPoint(x, y);
                    if (idx >= 0) {
                        android.util.Log.i(
                                "LeviWorldMap",
                                "sel down idx=" + idx + " corner=" + isCorner(idx)
                                        + " move=" + (idx == cornerIdx[moveCorner])
                                        + " scale=" + (idx == cornerIdx[scaleCorner])
                                        + " pt=" + selPoints.get(idx)[0] + ","
                                        + selPoints.get(idx)[1]
                                        + " screenScale=" + WorldMapView.this.scale);
                        dragIdx = idx;
                        dragSnapshot = copyPoints(selPoints);
                        selUndo.push(dragSnapshot);
                        if (selUndo.size() > 50) selUndo.removeLast();
                        dragDown = worldCoordI(x, y);
                        dragOrigin = selPoints.get(idx).clone();
                        // v0.4.214：四角分工——左上=整体平移；其余三角=
                        // 缩放（对角锚定+方向符号）；中间点=形变
                        dragMove = idx == cornerIdx[moveCorner];
                        dragScale = idx == cornerIdx[scaleCorner];
                        if (dragScale) {
                            // 锚=对角角（快照）——右下角锚=左上角
                            dragAnchor = dragSnapshot.get(cornerIdx[moveCorner]).clone();
                        }
                        return true;
                    }
                }
                // 未命中顶点：暂缓——MOVE 超 slop 才确认新粗框（误触不毁选区）
                dragStart = worldCoordI(x, y);
                dragCur = dragStart;
                dragIdx = -1;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragIdx >= 0) {
                    int[] w = worldCoordI(e.getX(), e.getY());
                    // 单击抖动防护：手指移动 < 4dp 不触发缩放/形变/平移
                    // （此前单击角点时的触摸抖动会执行一次缩放计算，
                    //  选区小时比例大幅跳变=「单击变线」根因）
                    if (Math.abs(e.getX() - downRawX) < 4 * density
                            && Math.abs(e.getY() - downRawY) < 4 * density) return true;
                    if (dragScale) {
                        // 缩放角：双轴自由缩放——角点完全跟手（直接
                        // 跟随手指 x/z），以对角为固定锚。按下偏移
                        // 补偿：按在图标边缘时按下瞬间比例不变
                        int[] corner = dragSnapshot.get(dragIdx);
                        int wx = w[0] - (dragDown[0] - dragOrigin[0]),
                                wz = w[1] - (dragDown[1] - dragOrigin[1]);
                        double sx =
                                clampScale(
                                        (wx - dragAnchor[0])
                                                / (double) (corner[0] - dragAnchor[0]));
                        double sz =
                                clampScale(
                                        (wz - dragAnchor[1])
                                                / (double) (corner[1] - dragAnchor[1]));
                        if ((frameCount & 7) == 0)
                            android.util.Log.i(
                                    "LeviWorldMap",
                                    "sel scale sx=" + sx + " sz=" + sz
                                            + " wx=" + wx + " wz=" + wz
                                            + " corner=" + corner[0] + "," + corner[1]
                                            + " anchor=" + dragAnchor[0] + "," + dragAnchor[1]
                                            + " down=" + dragDown[0] + "," + dragDown[1]
                                            + " origin=" + dragOrigin[0] + "," + dragOrigin[1]);
                        for (int i = 0; i < selPoints.size(); i++) {
                            int[] s = dragSnapshot.get(i), p = selPoints.get(i);
                            p[0] =
                                    (int)
                                            Math.round(
                                                    dragAnchor[0]
                                                            + (s[0] - dragAnchor[0]) * sx);
                            p[1] =
                                    (int)
                                            Math.round(
                                                    dragAnchor[1]
                                                            + (s[1] - dragAnchor[1]) * sz);
                        }
                    } else if (dragMove) {
                        // 四角=整体平移：所有顶点以快照为基准随手指移动
                        int dx = w[0] - dragDown[0], dz = w[1] - dragDown[1];
                        for (int i = 0; i < selPoints.size(); i++) {
                            int[] s = dragSnapshot.get(i), p = selPoints.get(i);
                            p[0] =
                                    (int)
                                            Math.max(
                                                    -30000000,
                                                    Math.min(30000000, s[0] + (long) dx));
                            p[1] =
                                    (int)
                                            Math.max(
                                                    -30000000,
                                                    Math.min(30000000, s[1] + (long) dz));
                        }
                    } else {
                        // 中间点 2D 自由拖动（相对位移）——斜边允许，多边形
                        // 顶点编辑（参照 Aseprite/Pixelorama 选区手柄惯例）
                        int[] a = selPoints.get(dragIdx);
                        int nx = clampDrag(dragOrigin[0] + w[0] - dragDown[0], selBox[0], selBox[2]),
                                nz = clampDrag(dragOrigin[1] + w[1] - dragDown[1], selBox[1], selBox[3]);
                        int ox = a[0], oz = a[1];
                        a[0] = nx;
                        a[1] = nz;
                        // 自交防护：顶点越过对边会导致多边形自交，
                        // 填充失效只剩描边=「选区变成一根线」
                        if (selfIntersects()) {
                            a[0] = ox;
                            a[1] = oz;
                        }
                    }
                    invalidate();
                    return true;
                }
                if (selStage == 1) {
                    dragCur = worldCoordI(e.getX(), e.getY());
                    invalidate();
                    return true;
                }
                // 未在粗框：移动超 slop 才确认新粗框（替代旧选区）
                if (selStage == 0 || selStage == 2) {
                    int[] w = worldCoordI(e.getX(), e.getY());
                    if (Math.abs(w[0] - dragStart[0]) * scale > 12 * density
                            || Math.abs(w[1] - dragStart[1]) * scale > 12 * density) {
                        selStage = 1;
                        dragCur = w;
                        selUndo.clear();
                        invalidate();
                    }
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                // v0.4.214：记录收手屏幕坐标（二级菜单气泡动态锚点）
                lastUpX = e.getRawX();
                lastUpY = e.getRawY();
                if (dragIdx >= 0) {
                    dragIdx = -1;
                    dragSnapshot = null;
                    dragOrigin = null;
                    dragMove = false;
                    dragScale = false;
                    // 重算 bbox（缩放后中间点拖动的 clamp 基准）
                    if (!selPoints.isEmpty()) {
                        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE,
                                z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
                        for (int[] p : selPoints) {
                            x0 = Math.min(x0, p[0]);
                            x1 = Math.max(x1, p[0]);
                            z0 = Math.min(z0, p[1]);
                            z1 = Math.max(z1, p[1]);
                        }
                        selBox = new int[] {x0, z0, x1, z1};
                    }
                    // 调试：拖点结束后顶点 bbox（选区异常排查）
                    if (!selPoints.isEmpty()) {
                        int bx0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE,
                                bz0 = Integer.MAX_VALUE, bz1 = Integer.MIN_VALUE;
                        for (int[] p : selPoints) {
                            bx0 = Math.min(bx0, p[0]);
                            bx1 = Math.max(bx1, p[0]);
                            bz0 = Math.min(bz0, p[1]);
                            bz1 = Math.max(bz1, p[1]);
                        }
                        android.util.Log.i(
                                "LeviWorldMap",
                                "sel up bbox=" + bx0 + "," + bz0 + " - " + bx1 + "," + bz1
                                        + " pts=" + selPoints.size());
                    }
                    notifySelected();
                    invalidate();
                    return true;
                }
                if (selStage == 1) {
                    // 无拖动=误触取消（旧选区已在 MOVE 确认时被替换，
                    // 未超 slop 则旧选区完好）
                    float moved =
                            (float)
                                    Math.hypot(
                                            (dragCur[0] - dragStart[0]) * scale,
                                            (dragCur[1] - dragStart[1]) * scale);
                    if (moved < 8 * density) {
                        selStage = selPoints.isEmpty() ? 0 : 2;
                    } else {
                        buildSelPoints();
                        selStage = 2;
                        notifySelected();
                    }
                    invalidate();
                    return true;
                }
                return true;
            }
        }
        return true;
    }

    /** 拖点 clamp：初始 bbox 外扩 64 方块内自由（防手滑飞出，撤销兜底）。 */
    private int clampDrag(int v, int lo, int hi) {
        return Math.max(lo - 64, Math.min(hi + 64, v));
    }

    /** 缩放比例 clamp（防翻转/退化——下限 0.25 防缩成一根线）。 */
    private static double clampScale(double v) {
        return Math.max(0.25, Math.min(10, v));
    }

    /** 多边形自交检测（非相邻段对，O(n²)，n≤几十开销可忽略）。 */
    private boolean selfIntersects() {
        int n = selPoints.size();
        if (n < 4) return false;
        for (int i = 0; i < n; i++) {
            int[] a = selPoints.get(i), b = selPoints.get((i + 1) % n);
            for (int j = i + 2; j < n; j++) {
                if (j == i || (j + 1) % n == i) continue; // 共享端点/相邻段
                int[] c = selPoints.get(j), d = selPoints.get((j + 1) % n);
                if (segmentsIntersect(a, b, c, d)) return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(int[] a, int[] b, int[] c, int[] d) {
        long o1 = cross(a, b, c), o2 = cross(a, b, d),
                o3 = cross(c, d, a), o4 = cross(c, d, b);
        if (o1 == 0 && onSeg(a, b, c)) return true;
        if (o2 == 0 && onSeg(a, b, d)) return true;
        if (o3 == 0 && onSeg(c, d, a)) return true;
        if (o4 == 0 && onSeg(c, d, b)) return true;
        return ((o1 > 0) != (o2 > 0)) && ((o3 > 0) != (o4 > 0));
    }

    private static long cross(int[] a, int[] b, int[] p) {
        return (long) (b[0] - a[0]) * (p[1] - a[1])
                - (long) (b[1] - a[1]) * (p[0] - a[0]);
    }

    private static boolean onSeg(int[] a, int[] b, int[] p) {
        return Math.min(a[0], b[0]) <= p[0]
                && p[0] <= Math.max(a[0], b[0])
                && Math.min(a[1], b[1]) <= p[1]
                && p[1] <= Math.max(a[1], b[1]);
    }

    private List<int[]> copyPoints(List<int[]> src) {
        List<int[]> out = new ArrayList<>(src.size());
        for (int[] p : src) out.add(new int[] {p[0], p[1]});
        return out;
    }

    /** 顶点命中检测——两轮分层（参照 Pixelorama 手柄优先级惯例）：
     *  ① 角手柄=图标方框内含判定（±11dp 半宽）：手指在缩放图标内就
     *  归该角，不再与旁边交接点/其他角按距离竞争（此前 scale 小时
     *  角点间距 < 22dp 容差→按右上角可能命中其他角或中间点）；
     *  ② 未命中才查中间形变点（16dp 最近距离）。 */
    private int hitPoint(float sx, float sy) {
        double left = centerX - getWidth() / (2.0 * scale),
                top = centerZ - getHeight() / (2.0 * scale);
        float iconHalf = 11 * density;
        for (int c : new int[] {cornerIdx[moveCorner], cornerIdx[scaleCorner]}) {
            int[] p = selPoints.get(c);
            float px = (float) ((p[0] - left) * scale), pz = (float) ((p[1] - top) * scale);
            if (Math.abs(px - sx) <= iconHalf && Math.abs(pz - sy) <= iconHalf) return c;
        }
        int best = -1;
        double bestDist = 16 * density;
        for (int i = 0; i < selPoints.size(); i++) {
            if (isCorner(i)) continue;
            int[] p = selPoints.get(i);
            float px = (float) ((p[0] - left) * scale), pz = (float) ((p[1] - top) * scale);
            double d = Math.hypot(px - sx, pz - sy);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 由粗框生成顶点链：四角+每 16 方块（区块交接处）一个顶点，顺时针。
     *  端点与 16 网格点重合时跳过（防重复顶点破坏正交链）。 */
    private void buildSelPoints() {
        int x0 = Math.min(dragStart[0], dragCur[0]), x1 = Math.max(dragStart[0], dragCur[0]),
                z0 = Math.min(dragStart[1], dragCur[1]), z1 = Math.max(dragStart[1], dragCur[1]);
        selBox = new int[] {x0, z0, x1, z1};
        selPoints.clear();
        selPoints.add(new int[] {x0, z0});
        int topLeft = selPoints.size() - 1;
        for (int x = ceilDiv(x0, 16) * 16; x < x1; x += 16)
            if (x > x0) selPoints.add(new int[] {x, z0});
        if (x1 != x0) selPoints.add(new int[] {x1, z0});
        int topRight = selPoints.size() - 1;
        for (int z = ceilDiv(z0, 16) * 16; z < z1; z += 16)
            if (z > z0) selPoints.add(new int[] {x1, z});
        if (z1 != z0) selPoints.add(new int[] {x1, z1});
        int bottomRight = selPoints.size() - 1;
        for (int x = floorDiv(x1, 16) * 16; x > x0; x -= 16)
            if (x < x1) selPoints.add(new int[] {x, z1});
        if (x1 != x0) selPoints.add(new int[] {x0, z1});
        int bottomLeft = selPoints.size() - 1;
        for (int z = floorDiv(z1, 16) * 16; z > z0; z -= 16)
            if (z < z1) selPoints.add(new int[] {x0, z});
        cornerIdx = new int[] {topLeft, topRight, bottomRight, bottomLeft};
    }

    private boolean isCorner(int idx) {
        if (cornerIdx == null) return false;
        for (int c : cornerIdx) if (c == idx) return true;
        return false;
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }

    private static int floorDiv(int a, int b) {
        return Math.floorDiv(a, b);
    }

    private int[] worldCoordI(float x, float y) {
        return new int[] {worldX(x), worldZ(y)};
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void start() {
        if (active) return;
        active = true;
        worker =
                new ThreadPoolExecutor(
                        1,
                        1,
                        10,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(48),
                        r -> {
                            Thread t = new Thread(r, "LeviWorldMap");
                            t.setPriority(Thread.MIN_PRIORITY);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        android.util.Log.i("LeviWorldMap", "start() w=" + getWidth() + " h=" + getHeight());
        invalidate();
    }

    public void stop() {
        active = false;
        generation++;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        pending.clear();
        tiles.clear();
    }

    public void refresh() {
        generation++;
        if (worker != null) worker.getQueue().clear();
        pending.clear();
        tiles.clear();
        lastError = "";
        invalidate();
    }

    // v0.4.214：key 改 long 打包坐标——官方字符串拼接每帧几千次 GC 分配
    // 是 OOM 崩溃（tombstone key()）的直接触发点；generation/revision/
    // renderKey 变化已由 configure()/changed() 的 refresh/清缓存兜底
    private long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void request(int x, int z) {
        if (!active || worker == null || pending.size() >= 48) return;
        long id = key(x, z);
        if (tiles.containsKey(id) || !pending.add(id)) return;
        int expected = generation;
        MapSettings options = settings.copy();
        int span = lodSpan;
        try {
            worker.execute(
                    () -> {
                        Tile tile = new Tile();
                        try {
                            // v0.4.214：span>1 走 LOD 采样 tile（16/32/64 梯次）
                            tile.image = renderer.renderLod(x, z, span, options);
                            if (span == 1 && (options.entities || options.blockEntities))
                                tile.markers = WorldRecords.chunkMarkers(session, x, z, options);
                        } catch (Exception ex) {
                            if (Thread.currentThread().isInterrupted()) return;
                            tile.error = ex.getMessage();
                            // v0.4.214：渲染错误日志（地图不显示排查）
                            android.util.Log.w(
                                    "LeviWorldMap",
                                    "render fail " + x + "," + z + ": " + ex,
                                    ex instanceof java.io.IOException ? null : ex);
                        }
                        if (Thread.currentThread().isInterrupted()) return;
                        post(
                                () -> {
                                    pending.remove(id);
                                    if (!active || generation != expected) return;
                                    tiles.put(id, tile);
                                    if (tile.error != null && !tile.error.equals(lastError)) {
                                        lastError = tile.error;
                                        if (listener != null) listener.error(tile.error);
                                    }
                                    invalidate();
                                });
                    });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            pending.remove(id);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(background);
        // v0.4.214：诊断日志（每 32 帧）——地图不显示排查（官方原版无日志）
        if ((frameCount++ & 31) == 0) {
            android.util.Log.i(
                    "LeviWorldMap",
                    "draw scale=" + scale + " center=" + (int) centerX + "," + (int) centerZ
                            + " tiles=" + tiles.size() + " pending=" + pending.size()
                            + " active=" + active + " w=" + getWidth() + " h=" + getHeight());
        }
        double left = centerX - getWidth() / (2.0 * scale),
                top = centerZ - getHeight() / (2.0 * scale);
        // v0.4.214：LOD 梯次——tile 覆盖 16×span 方块（16/32/64 三档）
        int cell = 16 * lodSpan;
        int minX = Math.floorDiv((int) Math.floor(left), cell),
                minZ = Math.floorDiv((int) Math.floor(top), cell),
                maxX = Math.floorDiv((int) Math.ceil(left + getWidth() / scale), cell),
                maxZ = Math.floorDiv((int) Math.ceil(top + getHeight() / scale), cell);
        List<int[]> missing = new ArrayList<>();
        paint.setFilterBitmap(false);
        paint.setStyle(Paint.Style.FILL);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++) {
                float sx = (float) ((x * cell - left) * scale),
                        sz = (float) ((z * cell - top) * scale),
                        size = cell * scale;
                Tile tile = tiles.get(key(x, z));
                RectF rect = new RectF(sx, sz, sx + size, sz + size);
                paint.setColor(((x ^ z) & 1) == 0 ? surface : WorldUi.translucent(surface, 120));
                canvas.drawRect(rect, paint);
                if (tile != null && tile.image != null)
                    canvas.drawBitmap(tile.image, null, rect, paint);
                if (tile == null && missing.size() < 4096) missing.add(new int[] {x, z});
                if (tile != null && tile.error != null) {
                    paint.setColor(WorldUi.translucent(accent, 130));
                    paint.setStrokeWidth(1);
                    canvas.drawLine(sx, sz, sx + size, sz + size, paint);
                }
                if (lodSpan == 1
                        && settings.slimeOverlay
                        && settings.dimension == 0
                        && tile != null
                        && tile.image != null
                        && WorldMapRenderer.slime(x, z)) {
                    paint.setColor(0x5579cd62);
                    canvas.drawRect(rect, paint);
                }
                if (settings.grid && scale >= 2) {
                    paint.setColor(WorldUi.translucent(foreground, 55));
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1);
                    canvas.drawRect(rect, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        missing.sort(
                java.util.Comparator.comparingDouble(
                        a ->
                                Math.abs(a[0] * cell + cell / 2.0 - centerX)
                                        + Math.abs(a[1] * cell + cell / 2.0 - centerZ)));
        for (int i = 0; i < Math.min(48, missing.size()); i++)
            request(missing.get(i)[0], missing.get(i)[1]);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++) {
                Tile tile = tiles.get(key(x, z));
                if (tile != null)
                    for (WorldRecords.Marker marker : tile.markers)
                        drawMarker(canvas, marker, left, top);
            }
        if (settings.players)
            for (WorldRecords.Marker marker : players) drawMarker(canvas, marker, left, top);
        for (WorldRecords.Marker marker : bookmarks) drawMarker(canvas, marker, left, top);
        drawStructures(canvas, left, top); // v0.4.214：结构标记（LBBL 方案）
        if (selectMode) {
            drawSelection(canvas, left, top); // v0.4.214：框选多边形绘制
        } else if (selectionX != null && selectionZ != null) {
            int x2 = selectionEndX == null ? selectionX : selectionEndX,
                    z2 = selectionEndZ == null ? selectionZ : selectionEndZ;
            RectF selected =
                    new RectF(
                            (float) ((Math.min(selectionX, x2) - left) * scale),
                            (float) ((Math.min(selectionZ, z2) - top) * scale),
                            (float) ((Math.max(selectionX, x2) + 1 - left) * scale),
                            (float) ((Math.max(selectionZ, z2) + 1 - top) * scale));
            paint.setColor(WorldUi.translucent(accent, 48));
            canvas.drawRect(selected, paint);
            paint.setColor(accent);
            paint.setStrokeWidth(WorldUi.dp(getContext(), 2));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(selected, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        drawCrosshair(canvas);
        if (settings.coordinates) {
            paint.setColor(foreground);
            paint.setTextSize(WorldUi.dp(getContext(), 11));
            canvas.drawText(
                    "N ↑",
                    leftInset + WorldUi.dp(getContext(), 12),
                    WorldUi.dp(getContext(), 20),
                    paint);
            int length =
                    Math.max(1, (int) Math.pow(2, Math.floor(Math.log(90 / scale) / Math.log(2))));
            float line = length * scale;
            canvas.drawLine(
                    leftInset + 12,
                    getHeight() - 18,
                    leftInset + 12 + line,
                    getHeight() - 18,
                    paint);
            canvas.drawText(length + " blocks", leftInset + 12, getHeight() - 25, paint);
            // v0.4.214：中心坐标显示（用户要求放地图底部中间，LBBL 方案）
            // ——「中间」=可视区中间（左侧抽屉覆盖区不算）
            String coord =
                    (int) Math.floor(centerX) + ", " + (int) Math.floor(centerZ)
                            + (settings.slice ? "  y=" + settings.y : "");
            float tw = paint.measureText(coord);
            canvas.drawText(
                    coord,
                    (leftInset + getWidth() - tw) / 2f,
                    getHeight() - WorldUi.dp(getContext(), 10),
                    paint);
        }
        // v0.4.214：高度图图例（分层设色对照表，地理标准方案）——
        // 左下角色带 + 高度标注
        if (settings.coordinates && settings.layer == MapSettings.Layer.HEIGHTMAP) {
            drawHeightLegend(canvas);
        }
    }

    private void drawHeightLegend(Canvas canvas) {
        float dp = density;
        float barX = leftInset + 12 * dp, barW = 12 * dp;
        float barTop = getHeight() - 46 * dp - 160 * dp;
        float barH = 160 * dp;
        int[] stops = MapPalette.HEIGHT_STOPS;
        int[] bands = MapPalette.HEIGHT_BANDS;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bands.length; i++) {
            float segH = barH / bands.length;
            paint.setColor(bands[i]);
            canvas.drawRect(barX, barTop + i * segH, barX + barW, barTop + (i + 1) * segH, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(WorldUi.translucent(foreground, 120));
        canvas.drawRect(barX, barTop, barX + barW, barTop + barH, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(foreground);
        paint.setTextSize(10 * dp);
        // 标注：色带底部=-64，顶部=256；每 64 高度一条标注
        for (int y = 64; y >= -64; y -= 64) {
            float t = (256 - y) / 320f;
            float ly = barTop + t * barH;
            paint.setColor(WorldUi.translucent(foreground, 160));
            canvas.drawLine(barX + barW, ly, barX + barW + 4 * dp, ly, paint);
            paint.setColor(foreground);
            canvas.drawText(String.valueOf(y), barX + barW + 8 * dp, ly + 3 * dp, paint);
        }
    }

    // v0.4.214：结构标记（LBBL 方案——自然结构：林地府邸/海底神殿等，
    // SeedStructureFinder 种子预测 + 游戏原版贴图图标）
    private List<org.levimc.launcher.core.content.worldmap.WorldMapRenderer.StructureMarker>
            structures = new ArrayList<>();
    private boolean showStructures = true;
    private final Map<String, Bitmap> structureIcons =
            new LinkedHashMap<>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 48;
                }
            };

    public void setStructures(
            List<org.levimc.launcher.core.content.worldmap.WorldMapRenderer.StructureMarker>
                    value) {
        structures = new ArrayList<>(value);
        invalidate();
    }

    public void setShowStructures(boolean show) {
        showStructures = show;
        invalidate();
    }

    /** 点击坐标命中结构图标（容差=图标半径的世界距离）。 */
    private String hitStructure(int x, int z) {
        if (!showStructures || structures.isEmpty() || lodSpan > 1) return null;
        double tolerance = Math.max(7, scale * 0.9) * 1.7 / scale;
        String best = null;
        double bestDist = tolerance;
        for (org.levimc.launcher.core.content.worldmap.WorldMapRenderer.StructureMarker m :
                structures) {
            double d = Math.hypot(m.x - x, m.z - z);
            if (d < bestDist) {
                bestDist = d;
                best = m.type;
            }
        }
        return best;
    }

    private void drawStructures(Canvas canvas, double left, double top) {
        if (!showStructures || structures.isEmpty() || lodSpan > 1) return;
        paint.setFilterBitmap(false);
        paint.setStyle(Paint.Style.FILL);
        for (org.levimc.launcher.core.content.worldmap.WorldMapRenderer.StructureMarker m :
                structures) {
            float sx = (float) ((m.x + 0.5 - left) * scale);
            float sz = (float) ((m.z + 0.5 - top) * scale);
            if (sx < -64 || sx > getWidth() + 64 || sz < -64 || sz > getHeight() + 64) continue;
            float r = Math.max(7f, scale * 0.9f);
            String iconName =
                    org.levimc.launcher.ui.views.WorldMapView.structureIconName(m.type);
            Bitmap icon = iconName != null ? structureIconBitmap(iconName) : null;
            if (icon != null) {
                float isz = r * 1.7f;
                canvas.drawBitmap(
                        icon,
                        null,
                        new RectF(sx - isz / 2f, sz - isz / 2f, sx + isz / 2f, sz + isz / 2f),
                        paint);
                r = isz / 2f;
            } else {
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(sx, sz, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0x80000000);
                canvas.drawCircle(sx, sz, r, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent);
                canvas.drawCircle(sx, sz, r * 0.5f, paint);
            }
            if (scale >= 4) {
                String label =
                        org.levimc.launcher.ui.views.WorldMapView.structureLabel(
                                getContext(), m.type);
                paint.setColor(foreground);
                paint.setTextSize(WorldUi.dp(getContext(), 11));
                float tw = paint.measureText(label);
                canvas.drawText(
                        label,
                        sx - tw / 2f,
                        sz + r + WorldUi.dp(getContext(), 13),
                        paint);
            }
        }
    }

    private Bitmap structureIconBitmap(String name) {
        Bitmap cached = structureIcons.get(name);
        if (cached != null) return cached;
        int resId =
                getResources().getIdentifier(name, "drawable", getContext().getPackageName());
        Bitmap b = null;
        if (resId != 0) {
            Bitmap src = BitmapFactory.decodeResource(getResources(), resId);
            if (src != null) {
                b =
                        src.getWidth() == 32
                                ? src
                                : Bitmap.createScaledBitmap(src, 32, 32, true);
            }
        }
        if (b == null) {
            // 游戏原版方块贴图（assets/structure_icons/，最近邻放大保持像素风）
            try (java.io.InputStream is =
                    getContext().getAssets().open("structure_icons/" + name + ".png")) {
                Bitmap src = BitmapFactory.decodeStream(is);
                if (src != null) {
                    b = Bitmap.createScaledBitmap(src, 32, 32, false);
                    src.recycle();
                }
            } catch (Exception ignored) {
            }
        }
        if (b != null) structureIcons.put(name, b);
        return b;
    }

    private void drawMarker(Canvas canvas, WorldRecords.Marker marker, double left, double top) {
        if (marker.dimension != settings.dimension) return;
        float x = (float) ((marker.x - left) * scale),
                z = (float) ((marker.z - top) * scale),
                radius =
                        WorldUi.dp(getContext(), marker.kind == WorldRecords.Marker.PLAYER ? 5 : 3);
        if (x < 0 || z < 0 || x > getWidth() || z > getHeight()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawCircle(x, z, radius + 2, paint);
        paint.setColor(
                marker.kind == WorldRecords.Marker.PLAYER
                        ? accent
                        : marker.kind == WorldRecords.Marker.ENTITY
                                ? 0xffe7a24d
                                : marker.kind == WorldRecords.Marker.BOOKMARK
                                        ? 0xffc27eb8
                                        : 0xff69adc2);
        canvas.drawCircle(x, z, radius, paint);
    }

    /** v0.4.214：框选绘制——粗框虚线矩形；成框后多边形半透明填充+
     *  accent 描边+顶点小方块（可拖点）。 */
    private void drawSelection(Canvas canvas, double left, double top) {
        if (selStage == 1 && dragStart != null && dragCur != null) {
            float x0 = (float) ((Math.min(dragStart[0], dragCur[0]) - left) * scale),
                    z0 = (float) ((Math.min(dragStart[1], dragCur[1]) - top) * scale),
                    x1 = (float) ((Math.max(dragStart[0], dragCur[0]) + 1 - left) * scale),
                    z1 = (float) ((Math.max(dragStart[1], dragCur[1]) + 1 - top) * scale);
            paint.setColor(accent);
            paint.setStrokeWidth(2 * density);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(x0, z0, x1, z1, paint);
            paint.setStyle(Paint.Style.FILL);
            return;
        }
        if (selPoints.isEmpty()) return;
        Path poly = new Path();
        for (int i = 0; i < selPoints.size(); i++) {
            int[] p = selPoints.get(i);
            float sx = (float) ((p[0] - left) * scale), sz = (float) ((p[1] - top) * scale);
            if (i == 0) poly.moveTo(sx, sz);
            else poly.lineTo(sx, sz);
        }
        poly.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(WorldUi.translucent(accent, 40));
        canvas.drawPath(poly, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(accent);
        paint.setStrokeWidth(2 * density);
        canvas.drawPath(poly, paint);
        // 顶点手柄（屏幕固定尺寸）——仅左上橙方块=整体平移、右下
        // resize 图标=缩放（对角锚定）；右上/左下无手柄；中间点
        // accent 方块=形变
        float half = 5.5f * density;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < selPoints.size(); i++) {
            int[] p = selPoints.get(i);
            float sx = (float) ((p[0] - left) * scale), sz = (float) ((p[1] - top) * scale);
            if (i == cornerIdx[scaleCorner]) {
                Bitmap icon = resizeIcon();
                if (icon != null) {
                    float isz = 11f * density;
                    // 深色垫底让白色图标在浅色地图上可见
                    paint.setColor(0x8014161C);
                    canvas.drawRect(sx - isz, sz - isz, sx + isz, sz + isz, paint);
                    canvas.drawBitmap(
                            icon,
                            null,
                            new RectF(sx - isz, sz - isz, sx + isz, sz + isz),
                            paint);
                } else {
                    paint.setColor(0xFF69ADC2);
                    canvas.drawRect(sx - half, sz - half, sx + half, sz + half, paint);
                }
            } else if (i == cornerIdx[moveCorner]) {
                paint.setColor(0xFFE7A24D);
                canvas.drawRect(sx - half, sz - half, sx + half, sz + half, paint);
            } else if (!isCorner(i)) {
                paint.setColor(accent);
                canvas.drawRect(sx - half, sz - half, sx + half, sz + half, paint);
            }
        }
        paint.setColor(0xffffffff);
        paint.setStrokeWidth(1.5f * density);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < selPoints.size(); i++) {
            if (i == cornerIdx[scaleCorner]) continue; // 图标角不描白边
            if (isCorner(i) && i != cornerIdx[moveCorner]) continue;
            int[] p = selPoints.get(i);
            float sx = (float) ((p[0] - left) * scale), sz = (float) ((p[1] - top) * scale);
            canvas.drawRect(sx - half, sz - half, sx + half, sz + half, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private Bitmap resizeIcon() {
        if (resizeIcon == null)
            resizeIcon =
                    BitmapFactory.decodeResource(
                            getResources(), R.drawable.ic_resize_handle);
        return resizeIcon;
    }

    private void drawCrosshair(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(visualCenterX(), getHeight() / 2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xe614161c);
        paint.setStrokeWidth(5 * density);
        canvas.drawPath(crosshair, paint);
        paint.setColor(0xffffffff);
        paint.setStrokeWidth(2 * density);
        canvas.drawPath(crosshair, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xe614161c);
        canvas.drawCircle(0, 0, 2.5f * density, paint);
        paint.setColor(0xffffffff);
        canvas.drawCircle(0, 0, density, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(density);
        canvas.restoreToCount(save);
    }

    public Bitmap screenshot() {
        Bitmap image =
                Bitmap.createBitmap(
                        Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
        draw(new Canvas(image));
        return image;
    }
}
