package org.levimc.launcher.core.online.voice;

/**
 * v527 自适应噪声门限 VAD：
 * 静音段缓慢下压噪声底估计（EMA），语音判定阈值 = max(噪声底×8, 绝对下限)。
 * 判定为语音才发送（静音抑制）；尾音保持 5 帧（100ms）防吞字。
 * 纯 Java 实现，可在 hrd_db 本地单测。
 */
final class Vad {

    /** 噪声底估计初值（16bit PCM 平均能量；RMS≈45）。 */
    private static final double FLOOR_INIT = 2000;
    /** 语音阈值 = 噪声底 × FLOOR_GAIN（约 18dB）。 */
    private static final double FLOOR_GAIN = 8;
    /** 绝对能量下限（RMS≈77，低于此一律判静音）。 */
    private static final double ABS_THRESHOLD = 6000;
    /** 尾音保持帧数（20ms/帧 → 100ms）。 */
    private static final int HANG_FRAMES = 5;

    private double floor = FLOOR_INIT;
    private int hangover;
    private double lastRms;
    private double lastFloor;

    /** 单帧判定：true = 语音帧（应发送）。 */
    boolean process(short[] buf) {
        double e = energy(buf);
        lastRms = e;
        if (e < floor * 2) {
            // 静音段：噪声底向当前能量缓慢收敛
            floor = 0.97 * floor + 0.03 * e;
            if (floor < 50) {
                floor = 50;
            }
        }
        lastFloor = floor;
        boolean voice = e > Math.max(floor * FLOOR_GAIN, ABS_THRESHOLD);
        if (voice) {
            hangover = HANG_FRAMES;
        } else if (hangover > 0) {
            hangover--;
            voice = true;
        }
        return voice;
    }

    /** 最近一帧能量（调试用，v528）。 */
    double getLastRms() {
        return lastRms;
    }

    /** 当前噪声底（调试用，v528）。 */
    double getLastFloor() {
        return lastFloor;
    }

    /** 帧平均能量（样本平方均值）。 */
    static double energy(short[] buf) {
        double sum = 0;
        for (short s : buf) {
            sum += (double) s * s;
        }
        return sum / buf.length;
    }
}
