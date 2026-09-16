package org.levimc.launcher.core;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU 大小核调度：把 chunk 渲染线程绑定到大核（游戏式调度，
 * 类似终末地等大作把重负载线程钉在性能核上）。
 * 通过 /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq
 * 识别大核（频率最高的核），sched_setaffinity 绑定。
 */
public final class CpuScheduler {

    private static final String TAG = "CpuScheduler";
    /** 大核数量（init 后可用；失败时保持保守默认值）。 */
    public static int bigCoreCount = 4;
    private static long bigCoreMask = -1;
    private static volatile boolean inited = false;

    private CpuScheduler() {
    }

    /** 初始化：扫描 CPU 拓扑，选频率最高的核作为大核。 */
    public static synchronized void init() {
        if (inited) {
            return;
        }
        inited = true;
        try {
            File base = new File("/sys/devices/system/cpu");
            File[] files = base.listFiles();
            if (files == null) {
                return;
            }
            List<long[]> freqs = new ArrayList<>(); // [cpuId, maxFreqKHz]
            for (File f : files) {
                String n = f.getName();
                if (!n.startsWith("cpu") || n.length() < 4) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(n.substring(3));
                    long freq = readLong(new File(f, "cpufreq/cpuinfo_max_freq"));
                    if (freq > 0) {
                        freqs.add(new long[]{id, freq});
                    }
                } catch (Exception ignored) {
                }
            }
            if (freqs.isEmpty()) {
                return;
            }
            freqs.sort((a, b) -> Long.compare(b[1], a[1]));
            long maxFreq = freqs.get(0)[1];
            // 大核 = 频率 >= 最高频的 88%（骁龙 8e：2×4.32G + 6×3.53G，
            // 3.53/4.32 = 81.7% 会漏掉中核组 → 阈值 88% 只取超大核 2 个；
            // 渲染池需要更多核 → 至少取 4 个最高频核）
            long mask = 0;
            int count = 0;
            for (int i = 0; i < freqs.size(); i++) {
                long f = freqs.get(i)[1];
                if (f >= maxFreq * 88 / 100) {
                    mask |= 1L << freqs.get(i)[0];
                    count++;
                }
            }
            // 大核太少（如只有 2 个超大核）时并入次高组，凑够渲染并行度
            if (count < 4) {
                int need = 4 - count;
                for (int i = 0; i < freqs.size() && need > 0; i++) {
                    long bit = 1L << freqs.get(i)[0];
                    if ((mask & bit) == 0) {
                        mask |= bit;
                        count++;
                        need--;
                    }
                }
            }
            bigCoreMask = mask;
            bigCoreCount = count;
            Log.i(TAG, "大核绑定: " + count + " 核, mask=" + Long.toHexString(mask));
        } catch (Throwable t) {
            Log.w(TAG, "CPU 拓扑扫描失败，保持默认调度", t);
        }
    }

    /** 把当前线程绑定到大核（失败静默，不影响功能）。 */
    public static void pinCurrentThreadToBigCores() {
        if (bigCoreMask < 0) {
            return;
        }
        try {
            // 反射调用：Os.sched_setaffinity 是 API 31+ 公开，低版本走反射
            Class<?> os = Class.forName("android.system.Os");
            java.lang.reflect.Method setAffinity =
                    os.getMethod("sched_setaffinity", int.class, long[].class);
            java.lang.reflect.Method getTid = os.getMethod("gettid");
            int tid = (Integer) getTid.invoke(null);
            setAffinity.invoke(null, tid, new Object[]{new long[]{bigCoreMask}});
        } catch (Throwable ignored) {
        }
    }

    private static long readLong(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[32];
            int n = fis.read(buf);
            if (n <= 0) {
                return -1;
            }
            String s = new String(buf, 0, n).trim();
            if (s.isEmpty()) {
                return -1;
            }
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }
}
