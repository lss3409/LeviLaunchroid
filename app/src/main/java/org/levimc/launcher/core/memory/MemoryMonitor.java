package org.levimc.launcher.core.memory;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Debug;
import android.os.SystemClock;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * 游戏进程内存监控（仅监控展示，不做任何"优化"）。
 * 游戏与启动器同进程，Java 堆来自 Runtime，native 堆来自 Debug.getNativeHeapAllocatedSize()。
 */
public class MemoryMonitor {

    /** 一次内存快照。 */
    public static class Snapshot {
        /** Java 堆已用（totalMemory - freeMemory），单位字节。 */
        public final long javaHeapUsed;
        /** Java 堆上限（maxMemory，-Xmx 级别），单位字节。 */
        public final long javaHeapMax;
        /** native 堆已分配，单位字节。 */
        public final long nativeHeap;
        /** 总占用 = Runtime.totalMemory() + native 堆，单位字节。 */
        public final long totalUsed;
        /** CPU 频率（MHz），-1 = 读取不可用。 */
        public final int cpuFreqMhz;
        /** 帧率（帧/秒），-1 = 未采样。 */
        public final int fps;
        /** SOC 温度（摄氏度），-1000 = 读取不可用。 */
        public final int socTempC;
        /** CPU 负载百分比（0-100），-1 = 读取不可用。 */
        public final int cpuLoadPct;
        /** 电池电流（mA，正=充电 负=放电），-100000 = 读取不可用。 */
        public final int currentMa;
        /** 电池温度（摄氏度），-1000 = 读取不可用。 */
        public final int battTempC;

        Snapshot(long javaHeapUsed, long javaHeapMax, long nativeHeap, long totalUsed,
                 int cpuFreqMhz, int fps, int socTempC, int cpuLoadPct, int currentMa, int battTempC) {
            this.javaHeapUsed = javaHeapUsed;
            this.javaHeapMax = javaHeapMax;
            this.nativeHeap = nativeHeap;
            this.totalUsed = totalUsed;
            this.cpuFreqMhz = cpuFreqMhz;
            this.fps = fps;
            this.socTempC = socTempC;
            this.cpuLoadPct = cpuLoadPct;
            this.currentMa = currentMa;
            this.battTempC = battTempC;
        }
    }

    /** 采样回调，在 HandlerThread 上调用；回调含当前快照与本次会话峰值快照。 */
    public interface Listener {
        void onSnapshot(Snapshot current, Snapshot peak);
    }

    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static volatile MemoryMonitor INSTANCE;

    private HandlerThread thread;
    private Handler handler;
    private Listener listener;
    private Snapshot peak;

    private MemoryMonitor() {
    }

    private static MemoryMonitor getInstance() {
        if (INSTANCE == null) {
            synchronized (MemoryMonitor.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MemoryMonitor();
                }
            }
        }
        return INSTANCE;
    }

    /** 帧计数（主线程 Choreographer 回调累加），采样时读差值算 FPS。 */
    private static final java.util.concurrent.atomic.AtomicLong FRAME_COUNT = new java.util.concurrent.atomic.AtomicLong();
    private static long lastFrameCount;
    private static long lastFrameSampleAt;
    private static volatile boolean fpsCallbackRegistered;

    /** 主线程调用：注册帧计数回调（用于 FPS 采样）。 */
    public static void registerFrameCounter() {
        if (fpsCallbackRegistered) return;
        fpsCallbackRegistered = true;
        try {
            android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    FRAME_COUNT.incrementAndGet();
                    if (fpsCallbackRegistered) {
                        android.view.Choreographer.getInstance().postFrameCallback(this);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void unregisterFrameCounter() {
        fpsCallbackRegistered = false;
    }

    /** 读 CPU 频率（MHz），失败返回 -1。 */
    private static int readCpuFreqMhz() {
        for (String path : new String[]{
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq"}) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(path))) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    return Integer.parseInt(line.trim()) / 1000;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 读 SOC 温度（遍历 thermal_zone 中 cpu/soc 相关 zone 取最高温），失败返回 -1000。
     *  过滤掉 charger/gpu/电池等异常读数（此前把非 SOC 的 100+ 度 zone 也统计进来了）。 */
    private static int readSocTempC() {
        int max = -1000;
        java.io.File thermalRoot = new java.io.File("/sys/class/thermal");
        java.io.File[] zones = thermalRoot.listFiles((d, n) -> n != null && n.startsWith("thermal_zone"));
        if (zones == null) return max;
        for (java.io.File zone : zones) {
            String type = "";
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(new java.io.File(zone, "type")))) {
                String line = br.readLine();
                if (line != null) type = line.trim().toLowerCase();
            } catch (Throwable ignored) {
            }
            if (type.isEmpty() || !(type.contains("cpu") || type.contains("soc")
                    || type.contains("tsens") || type.contains("apc") || type.contains("ddr"))) {
                continue;
            }
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(new java.io.File(zone, "temp")))) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    int temp = Integer.parseInt(line.trim());
                    if (temp > max && temp < 120000) max = temp;
                }
            } catch (Throwable ignored) {
            }
        }
        return max >= 0 ? max / 1000 : max;
    }

    /** CPU 负载：/proc/stat 两次采样差值计算（第一次返回 -1）。 */
    private static long prevCpuTotal = -1;
    private static long prevCpuIdle = -1;

    private static int readCpuLoadPct() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] parts = line.trim().split("\\s+");
            long idle = 0, total = 0;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4 || i == 5) idle += v; // idle + iowait
            }
            int pct = -1;
            if (prevCpuTotal > 0) {
                long dTotal = total - prevCpuTotal;
                long dIdle = idle - prevCpuIdle;
                if (dTotal > 0) pct = (int) (100 - (dIdle * 100 / dTotal));
            }
            prevCpuTotal = total;
            prevCpuIdle = idle;
            return pct;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 电池电流（mA）：BatteryManager API（sysfs 被 SELinux 拒绝，API 可用）。 */
    private static int readCurrentMa() {
        if (appContext == null) return -100000;
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    appContext.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return -100000;
            int micro = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (micro == Integer.MIN_VALUE) return -100000;
            return micro / 1000;
        } catch (Throwable ignored) {
            return -100000;
        }
    }

    /** 电池温度（摄氏度）：BatteryManager API。 */
    private static int readBattTempC() {
        if (appContext == null) return -1000;
        try {
            Intent intent = appContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return -1000;
            int temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp == Integer.MIN_VALUE) return -1000;
            return temp / 10;
        } catch (Throwable ignored) {
            return -1000;
        }
    }

    private static Context appContext;

    /** 初始化应用 Context（BatteryManager API 需要）。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** 采样一次 FPS（帧计数差值 / 时间差）。 */
    private static int sampleFps() {
        long now = SystemClock.elapsedRealtime();
        long count = FRAME_COUNT.get();
        if (lastFrameSampleAt == 0) {
            lastFrameCount = count;
            lastFrameSampleAt = now;
            return -1;
        }
        long dt = now - lastFrameSampleAt;
        long df = count - lastFrameCount;
        lastFrameCount = count;
        lastFrameSampleAt = now;
        if (dt <= 0) return -1;
        return (int) (df * 1000L / dt);
    }

    /** 立即采集一次当前内存快照。 */
    public static Snapshot snapshot() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        long nativeHeap = Debug.getNativeHeapAllocatedSize();
        long totalUsed = runtime.totalMemory() + nativeHeap;
        return new Snapshot(used, max, nativeHeap, totalUsed, readCpuFreqMhz(), sampleFps(),
                readSocTempC(), readCpuLoadPct(), readCurrentMa(), readBattTempC());
    }

    /**
     * 启动前台监控：HandlerThread 每 1 秒采样并回调。重复调用时先停止旧会话。
     */
    public static synchronized void startForegroundMonitoring(Listener listener) {
        if (listener == null) {
            return;
        }
        MemoryMonitor monitor = getInstance();
        if (monitor.listener != null) {
            // 已有会话：仅换回调即可，避免重复线程。
            monitor.listener = listener;
            return;
        }
        monitor.listener = listener;
        monitor.peak = null;
        monitor.thread = new HandlerThread("MemoryMonitor");
        monitor.thread.start();
        monitor.handler = new Handler(monitor.thread.getLooper());
        monitor.handler.post(monitor.sampleRunnable);
    }

    /** 停止采样并退出 HandlerThread。 */
    public static synchronized void stop() {
        MemoryMonitor monitor = getInstance();
        monitor.listener = null;
        monitor.peak = null;
        if (monitor.thread != null) {
            monitor.thread.quitSafely();
        }
        monitor.thread = null;
        monitor.handler = null;
    }

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            Handler targetHandler;
            Listener targetListener;
            synchronized (MemoryMonitor.class) {
                targetHandler = handler;
                targetListener = listener;
            }
            if (targetHandler == null || targetListener == null) {
                return;
            }

            Snapshot current = snapshot();
            Snapshot peakNow;
            synchronized (MemoryMonitor.class) {
                if (peak == null || current.totalUsed > peak.totalUsed) {
                    peak = current;
                }
                peakNow = peak;
            }

            try {
                targetListener.onSnapshot(current, peakNow);
            } catch (Throwable ignored) {
                // 回调异常不能中断采样循环
            }

            synchronized (MemoryMonitor.class) {
                if (handler != null) {
                    handler.postDelayed(this, SAMPLE_INTERVAL_MS);
                }
            }
        }
    };
}
