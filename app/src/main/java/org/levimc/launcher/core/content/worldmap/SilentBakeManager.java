package org.levimc.launcher.core.content.worldmap;

import android.util.Log;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v400 后台静默烘焙管理器：玩家导入或游玩存档后，无需打开卫星图——
 * 只要启动器运行着（前台后台都行），就在后台以慢速（SILENT 模式）
 * 静默烘焙世界的可视化缓存。串行队列：一个世界烘完再烘下一个。
 * 玩家打开卫星图时暂停（卫星图内由 NbtViewerActivity 全速烘焙），
 * 退出卫星图后恢复。
 */
public class SilentBakeManager {
    private static final String TAG = "SilentBake";
    /** 静默只烘主世界：下界/末地数据量小，卫星图内切维度时全速补烘即可 */
    private static final int DIM = 0;

    private static SilentBakeManager instance;

    private final Object lock = new Object();
    /** 队列（保序去重）：worldDir 绝对路径 -> 世界目录 */
    private final java.util.LinkedHashMap<String, File> queue =
            new java.util.LinkedHashMap<>();
    private volatile Thread worker;
    /** 当前烘焙线程（pause 时中断它停掉 worker 池） */
    private volatile Thread currentBake;
    private volatile boolean paused;

    public static synchronized SilentBakeManager get() {
        if (instance == null) {
            instance = new SilentBakeManager();
        }
        return instance;
    }

    // v412 动态调节：主线程掉帧监控（Choreographer 帧间隔 >30ms 计
    // 掉帧，最近 60 帧比例）——静默烘焙据此调速：UI 流畅就快、
    // 掉帧就限速（帧率反馈闭环）
    private static final java.util.concurrent.atomic.AtomicInteger FRAME_TOTAL =
            new java.util.concurrent.atomic.AtomicInteger(60);
    private static final java.util.concurrent.atomic.AtomicInteger FRAME_JANK =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile boolean frameMonitorRegistered;
    private static volatile long lastFrameNanos;

    /** 主线程调用：注册帧监控（幂等）。 */
    public static void registerFrameMonitor() {
        if (frameMonitorRegistered) {
            return;
        }
        frameMonitorRegistered = true;
        try {
            android.view.Choreographer.getInstance().postFrameCallback(
                    new android.view.Choreographer.FrameCallback() {
                        @Override
                        public void doFrame(long frameTimeNanos) {
                            long prev = lastFrameNanos;
                            lastFrameNanos = frameTimeNanos;
                            if (prev > 0) {
                                long gapMs = (frameTimeNanos - prev) / 1_000_000;
                                if (gapMs > 30) {
                                    FRAME_JANK.incrementAndGet();
                                }
                                FRAME_TOTAL.incrementAndGet();
                                // 只保留最近 ~120 帧窗口
                                if (FRAME_TOTAL.get() > 120) {
                                    FRAME_TOTAL.set(60);
                                    FRAME_JANK.set(FRAME_JANK.get() / 2);
                                }
                            }
                            if (frameMonitorRegistered) {
                                android.view.Choreographer.getInstance()
                                        .postFrameCallback(this);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** 最近窗口掉帧比例（0=流畅，1=全掉帧）。烘焙 worker 查此调速。 */
    public static float jankRatio() {
        int total = FRAME_TOTAL.get();
        if (total <= 0) {
            return 0f;
        }
        return FRAME_JANK.get() / (float) total;
    }

    private volatile boolean cacheDirReady;

    /** 确保缓存目录初始化（进程重启后 WorldMapRenderer.sCacheBase
     *  静态字段为 null——不初始化的话静默烘焙会读写旧路径
     *  map_cache_0.bin，白烘且覆盖不了卫星图读的新目录缓存）。 */
    public void init(android.content.Context ctx) {
        if (cacheDirReady || ctx == null) {
            return;
        }
        synchronized (lock) {
            if (cacheDirReady) {
                return;
            }
            try {
                WorldMapRenderer.initCacheDir(ctx.getApplicationContext());
                cacheDirReady = true;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 入队一个世界（已在队列或正在烘焙则忽略）。 */
    public void enqueue(File worldDir) {
        if (worldDir == null || !worldDir.isDirectory()) {
            return;
        }
        synchronized (lock) {
            queue.put(worldDir.getAbsolutePath(), worldDir);
        }
        ensureWorker();
        // v403：立即后台扫 bounds 缓存（不入队、并行）——打开卫星图
        // 时免 readKeys 全扫 5-10 秒（"大地图等半天"的主要延迟）。
        // buildBoundsOnly 无 bounds 缓存时全扫 subchunk key 并落盘
        prepBounds(worldDir);
    }

    /** bounds 缓存未就绪时后台扫描落盘（打开卫星图的等待主要来自
     *  readKeys 全扫；提前扫好后打开秒出 bounds → 烘焙立即开始）。 */
    private void prepBounds(final File worldDir) {
        final File db = new File(worldDir, "db");
        if (!db.isDirectory()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                for (int dim = 0; dim <= 2; dim++) {
                    if (paused) {
                        return;
                    }
                    WorldMapRenderer.buildBoundsOnly(db, dim);
                }
            } catch (Throwable err) {
                Log.w(TAG, "bounds 预扫描失败: " + worldDir.getName(), err);
            }
        }, "silent-bounds");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 扫描世界根目录下所有世界，缓存不完整的入队。
     *  （MainActivity onResume 调用——导入/游玩过后回来时自动补烘） */
    public void scanWorldsDirectories(java.util.List<File> worldsDirs) {
        if (worldsDirs == null || worldsDirs.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            int queued = 0;
            for (File root : worldsDirs) {
                if (paused || root == null || !root.isDirectory()) {
                    continue;
                }
                File[] dirs = root.listFiles(File::isDirectory);
                if (dirs == null) {
                    continue;
                }
                for (File wd : dirs) {
                    if (paused) {
                        break;
                    }
                    File db = new File(wd, "db");
                    if (!db.isDirectory()) {
                        continue;
                    }
                    try {
                        WorldMapRenderer.CacheStatus st =
                                WorldMapRenderer.peekChunkCacheStatus(db, DIM);
                        if (st != WorldMapRenderer.CacheStatus.COMPLETE) {
                            enqueue(wd);
                            queued++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (queued > 0) {
                Log.i(TAG, "静默烘焙扫描: 入队 " + queued + " 个世界");
            }
        }, "silent-scan");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 暂停（玩家进入卫星图——其它存档的静默烘焙停止，
     *  当前世界由卫星图内全速烘焙负责）。 */
    public void pause() {
        paused = true;
        Thread bt = currentBake;
        if (bt != null) {
            bt.interrupt();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    /** 恢复（玩家退出卫星图）。 */
    public void resume() {
        paused = false;
        ensureWorker();
    }

    private void ensureWorker() {
        synchronized (lock) {
            if (paused || queue.isEmpty()) {
                return;
            }
            if (worker != null && worker.isAlive()) {
                return;
            }
            worker = new Thread(this::runQueue, "silent-bake");
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private void runQueue() {
        while (true) {
            final File worldDir;
            synchronized (lock) {
                if (paused || queue.isEmpty()) {
                    worker = null;
                    return;
                }
                Iterator<Map.Entry<String, File>> it = queue.entrySet().iterator();
                worldDir = it.next().getValue();
                it.remove();
            }
            File db = new File(worldDir, "db");
            if (!db.isDirectory()) {
                continue;
            }
            try {
                WorldMapRenderer.CacheStatus st =
                        WorldMapRenderer.peekChunkCacheStatus(db, DIM);
                if (st == WorldMapRenderer.CacheStatus.COMPLETE) {
                    continue; // 已被卫星图内烘焙/增量更新补齐
                }
                Log.i(TAG, "静默烘焙开始: " + worldDir.getName() + " 状态=" + st);
                final CountDownLatch done = new CountDownLatch(1);
                Thread bt = WorldMapRenderer.bakeWorldCache(
                        db, DIM, null, null, done::countDown, false,
                        WorldMapRenderer.BakeMode.SILENT);
                currentBake = bt;
                try {
                    // 等完成；pause() 会 interrupt 本线程与烘焙线程
                    done.await();
                    bt.join(500);
                } catch (InterruptedException e) {
                    // 暂停/被打断：当前世界重新入队，下次继续
                    synchronized (lock) {
                        queue.put(worldDir.getAbsolutePath(), worldDir);
                    }
                    Log.i(TAG, "静默烘焙暂停: " + worldDir.getName());
                    worker = null;
                    return;
                } finally {
                    if (currentBake == bt) {
                        currentBake = null;
                    }
                }
                Log.i(TAG, "静默烘焙完成: " + worldDir.getName());
            } catch (Throwable err) {
                Log.w(TAG, "静默烘焙失败: " + worldDir.getName(), err);
            }
        }
    }
}
