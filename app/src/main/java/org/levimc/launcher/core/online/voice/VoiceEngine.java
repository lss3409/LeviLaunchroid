package org.levimc.launcher.core.online.voice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.levimc.launcher.core.online.EasyTierManager;
import org.levimc.launcher.core.online.RoomCenter;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 联机语音引擎（v527）：
 * 三态麦克风 MUTED（闭麦）→ OPEN（开麦）→ PTT（对讲机，按住说话）循环。
 *
 * 链路：AudioRecord 16kHz/16bit 单声道 20ms 帧 → 系统降噪
 * （NoiseSuppressor + AcousticEchoCanceler，设备不支持自动跳过）→
 * 软件自适应噪声门限 VAD（Vad）→ 语音帧 [seq][PCM] 经 UDP 18091
 * 单播给房间内其他玩家（地址来自 RoomCenter 玩家列表 addr 字段）。
 * 接收端每发送方抖动缓冲（丢包补静音），混音后 AudioTrack 播放。
 * 静音抑制：只有语音帧才发送，省带宽省电。
 */
public final class VoiceEngine implements RoomCenter.Listener {

    private static final String TAG = "VoiceEngine";
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_MS = 20;
    public static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000; // 320
    /** 语音端口（与房间中心 18090 相邻）。 */
    public static final int MIC_PORT = 18091;
    /** 抖动缓冲上限（12 帧 = 240ms）。 */
    private static final int MAX_JITTER_FRAMES = 12;

    public static final int MODE_MUTED = 0;
    public static final int MODE_OPEN = 1;
    public static final int MODE_PTT = 2;

    /** 说话人/模式变化回调（主线程）。 */
    public interface Listener {
        void onVoiceChanged();
    }

    private static volatile VoiceEngine instance;
    /** 最近一次模式（无 Context 读取，RoomCenter 心跳/玩家列表同步用，v528）。 */
    private static volatile int lastMode = MODE_MUTED;
    /** 是否被房主禁麦（v530：禁麦期间点击自己麦克风被拒，解除后恢复自由）。 */
    private static volatile boolean mutedByHost;
    /** v547：降噪开关（悬浮窗设置项；关=跳过系统 NoiseSuppressor/AEC）。 */
    private static volatile boolean noiseSuppression = true;
    /** v547：降噪等级 0 低 / 1 中 / 2 高（VAD 阈值系数 0.6/1.0/1.5）。 */
    private static volatile int noiseLevel = 1;
    /** v547：设置变化后需要重建 AudioRecord（applyEffects 只在创建时生效）。 */
    private static volatile boolean captureDirty;
    /** 当前采集音量电平 0-100（v533 PTT 声波动效用）。 */
    private static volatile int currentLevel;
    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 虚拟 IP → clientId（RoomCenter 玩家列表维护）。 */
    private final Map<String, String> peers = new ConcurrentHashMap<>();
    private final Set<String> speakingClients = new CopyOnWriteArraySet<>();
    private final Map<String, SenderBuf> jitter = new ConcurrentHashMap<>();
    private final Map<String, Long> lastVoiceAt = new ConcurrentHashMap<>();
    private final Vad vad = new Vad();

    private volatile int mode = MODE_MUTED;
    private volatile boolean pttPressed;
    private volatile boolean running;
    private volatile boolean suspended;
    private volatile boolean permGranted;

    private DatagramSocket socket;
    private Thread recvThread;
    private Thread playThread;
    private Thread capThread;
    private Thread speakerThread;
    private String selfIp;
    private int sendSeq;

    private VoiceEngine(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public static VoiceEngine get(Context ctx) {
        if (instance == null) {
            synchronized (VoiceEngine.class) {
                if (instance == null) {
                    instance = new VoiceEngine(ctx);
                }
            }
        }
        return instance;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public int getMode() {
        return mode;
    }

    /** 无 Context 读取最近模式（RoomCenter 心跳同步用，v528）。 */
    public static int getLastMode() {
        return lastMode;
    }

    /** 当前采集音量电平 0-100（v533：PTT 按住说话的声波动效）。 */
    public static int getCurrentLevel() {
        return currentLevel;
    }

    public Set<String> getSpeakingClients() {
        return speakingClients;
    }

    public boolean hasMicPermission(Context ctx) {
        try {
            return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 下一档模式（MUTED→OPEN→PTT→MUTED），返回新模式；
     *  v530：被房主禁麦时拒绝切换（返回 -1）。 */
    public int cycleMode() {
        if (mutedByHost) {
            Log.i(TAG, "被房主禁麦，拒绝切换模式");
            return -1;
        }
        int next;
        synchronized (this) {
            next = (mode + 1) % 3;
            mode = next;
            if (next != MODE_PTT) {
                pttPressed = false;
            }
        }
        lastMode = mode;
        // v534：开麦状态即时同步对端（成员 kick 心跳 / 房主推送名单）
        try {
            RoomCenter.notifyLocalStateChanged();
        } catch (Throwable ignored) {
        }
        notifyChanged();
        return next;
    }

    public void pttDown() {
        Log.d(TAG, "PTT 按下");
        pttPressed = true;
    }

    public void pttUp() {
        Log.d(TAG, "PTT 松开");
        pttPressed = false;
    }

    /** 是否被房主禁麦（v530，UI 弹提示用）。 */
    public static boolean isMutedByHost() {
        return mutedByHost;
    }

    /** v547：降噪开关/等级（悬浮窗设置项）。开关/等级变化后重建采集生效。 */
    public static void setNoiseSuppression(boolean on) {
        if (noiseSuppression != on) {
            noiseSuppression = on;
            captureDirty = true;
        }
    }

    public static boolean isNoiseSuppressionOn() {
        return noiseSuppression;
    }

    public static void setNoiseLevel(int level) {
        int lv = Math.max(0, Math.min(2, level));
        if (noiseLevel != lv) {
            noiseLevel = lv;
            // 0 低 0.6 / 1 中 1.0 / 2 高 1.5（VAD 阈值系数）
            Vad.setLevelFactor(lv == 0 ? 0.6 : lv == 2 ? 1.5 : 1.0);
        }
    }

    public static int getNoiseLevel() {
        return noiseLevel;
    }

    /** 房主个体禁麦指令（c:mute 单播，无 Context 静态入口，v530）。 */
    public static void setMutedByHostStatic(boolean mute) {
        VoiceEngine ve = instance;
        if (ve != null) {
            ve.setMutedByHost(mute);
        } else {
            mutedByHost = mute;
        }
    }

    /** 被房主禁麦：切回闭麦并锁定；解除：只解锁（不自动开麦）。
     *  v546：幂等化——心跳名单每 2s 同步一次 muted（冗余通道），状态未变时
     *  不刷 UI 不打日志。 */
    public void setMutedByHost(boolean mute) {
        boolean changed = mutedByHost != mute;
        mutedByHost = mute;
        if (mute) {
            synchronized (this) {
                mode = MODE_MUTED;
                pttPressed = false;
            }
            lastMode = mode;
            if (changed) {
                Log.i(TAG, "被房主禁麦");
                // v534：被禁麦后状态即时同步（心跳 kick）
                try {
                    RoomCenter.notifyLocalStateChanged();
                } catch (Throwable ignored) {
                }
                notifyChanged();
            }
        } else if (changed) {
            Log.i(TAG, "房主已解除禁麦");
            notifyChanged();
        }
    }

    // ---------------- 生命周期 ----------------

    /** 启动引擎（房间连接后调用）：收包/播放/说话人监测常驻；采集只在开麦态激活。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        suspended = false;
        permGranted = hasMicPermission(app);
        try {
            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                // v566：语音走虚拟网（成员间直连虚拟 IP），显式绑 VPN 网络
                // 防"socket 早于 TUN 建立"绑旧网络竞态
                android.net.Network vpnNet = EasyTierManager.waitForVpnNetwork(5_000);
                if (vpnNet != null) {
                    try {
                        vpnNet.bindSocket(socket);
                    } catch (Exception be) {
                        Log.w(TAG, "语音 socket 绑定 VPN 网络失败", be);
                    }
                }
                socket.bind(new InetSocketAddress("0.0.0.0", MIC_PORT));
            }
        } catch (Exception e) {
            Log.w(TAG, "语音端口绑定失败", e);
        }
        String vip = EasyTierManager.get().getVirtualIp();
        if (vip != null && !vip.isEmpty()) {
            int slash = vip.indexOf('/');
            selfIp = slash > 0 ? vip.substring(0, slash) : vip;
        }
        RoomCenter.addListener(this);
        recvThread = new Thread(this::recvLoop, "voice-recv");
        recvThread.setDaemon(true);
        recvThread.start();
        playThread = new Thread(this::playLoop, "voice-play");
        playThread.setDaemon(true);
        playThread.start();
        capThread = new Thread(this::capLoop, "voice-cap");
        capThread.setDaemon(true);
        capThread.start();
        speakerThread = new Thread(this::speakerLoop, "voice-speaker");
        speakerThread.setDaemon(true);
        speakerThread.start();
        Log.i(TAG, "语音引擎已启动");
    }

    /** 游戏退到后台：停采集（保留收包播放与模式）。 */
    public synchronized void suspend() {
        if (!running || suspended) {
            return;
        }
        suspended = true;
        Log.i(TAG, "语音引擎已暂停（后台）");
    }

    /** 回到前台：恢复采集。 */
    public synchronized void resume() {
        if (!running || !suspended) {
            return;
        }
        suspended = false;
        Log.i(TAG, "语音引擎已恢复");
    }

    /** 完全停止（退出房间/断开）。 */
    public synchronized void stop() {
        running = false;
        suspended = false;
        pttPressed = false;
        RoomCenter.removeListener(this);
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
        jitter.clear();
        lastVoiceAt.clear();
        peers.clear();
        speakingClients.clear();
        Log.i(TAG, "语音引擎已停止");
    }

    /** 麦克风权限结果（MinecraftActivity 转发）。 */
    public void onPermissionResult(boolean granted) {
        permGranted = granted;
        Log.i(TAG, "麦克风权限: " + granted);
    }

    // ---------------- 采集 ----------------

    private void capLoop() {
        AudioRecord rec = null;
        long frameCount = 0;
        try {
            while (running) {
                // v528：非闭麦模式采集常驻（PTT 按住即发零延迟，不再等采集启动）
                if (suspended || mode == MODE_MUTED || !permGranted) {
                    releaseQuiet(rec);
                    rec = null;
                    Thread.sleep(300);
                    continue;
                }
                // v547：降噪开关变化 → 释放重建 AudioRecord（applyEffects 创建时生效）
                if (captureDirty) {
                    captureDirty = false;
                    releaseQuiet(rec);
                    rec = null;
                }
                if (rec == null) {
                    rec = createRecord();
                    if (rec == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    try {
                        rec.startRecording();
                    } catch (Exception e) {
                        releaseQuiet(rec);
                        rec = null;
                        Thread.sleep(500);
                        continue;
                    }
                    applyEffects(rec);
                }
                short[] buf = new short[FRAME_SAMPLES];
                int got = readFully(rec, buf);
                if (got < FRAME_SAMPLES) {
                    continue;
                }
                // 降噪：系统 NS/AEC（applyEffects）+ 软件噪声门限 VAD
                boolean voice = vad.process(buf);
                // v533：电平映射 0-100（静音≈0，正常说话 40-70，大声≈100）
                double e = vad.getLastRms();
                double db = 10 * Math.log10(e + 1);
                int lv = (int) Math.round((db - 22) * 100 / 48);
                currentLevel = Math.max(0, Math.min(100, lv));
                // v528：开麦走 VAD 门；PTT 按住就发（对讲机语义，静音帧也发）
                boolean send = mode == MODE_OPEN ? voice : pttPressed;
                if (send) {
                    sendFrame(buf);
                }
                if (++frameCount % 100 == 0) {
                    Log.d(TAG, "VAD 采样: rms=" + Math.round(Math.sqrt(vad.getLastRms()))
                            + " floor=" + Math.round(Math.sqrt(vad.getLastFloor()))
                            + " voice=" + voice + " mode=" + mode + " ptt=" + pttPressed);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "采集线程异常", e);
        } finally {
            releaseQuiet(rec);
        }
    }

    private static AudioRecord createRecord() {
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                return null;
            }
            return new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_SAMPLES * 2 * 8));
        } catch (Exception e) {
            return null;
        }
    }

    /** 系统降噪：NoiseSuppressor + AcousticEchoCanceler（API 33 起弃用但多数设备仍有效）。
     *  v547：noiseSuppression=false 时跳过（悬浮窗设置项）。 */
    @SuppressWarnings("deprecation")
    private static void applyEffects(AudioRecord rec) {
        if (!noiseSuppression) {
            Log.i(TAG, "降噪已关闭（设置）");
            return;
        }
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns =
                        android.media.audiofx.NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) {
                    ns.setEnabled(true);
                    Log.i(TAG, "系统降噪已启用");
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler aec =
                        android.media.audiofx.AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) {
                    aec.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int readFully(AudioRecord rec, short[] buf) {
        int off = 0;
        while (off < buf.length) {
            int n = rec.read(buf, off, buf.length - off);
            if (n <= 0) {
                return off;
            }
            off += n;
        }
        return off;
    }

    private static void releaseQuiet(AudioRecord rec) {
        if (rec == null) {
            return;
        }
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        try {
            rec.release();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 发送 ----------------

    private void sendFrame(short[] buf) {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) {
            return;
        }
        byte[] pkt = new byte[4 + FRAME_SAMPLES * 2];
        ByteBuffer bb = ByteBuffer.wrap(pkt);
        bb.putInt(sendSeq++);
        for (short v : buf) {
            bb.putShort(v);
        }
        for (String ip : peers.keySet()) {
            if (ip.equals(selfIp)) {
                continue;
            }
            try {
                s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(ip), MIC_PORT));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------- 接收 ----------------

    private void recvLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            DatagramSocket s = socket;
            if (s == null || s.isClosed()) {
                break;
            }
            try {
                s.receive(p);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                }
                continue;
            }
            if (p.getLength() < 4 + FRAME_SAMPLES * 2) {
                continue; // 只认整帧（v1 无 FEC/半帧）
            }
            ByteBuffer bb = ByteBuffer.wrap(p.getData(), p.getOffset(), p.getLength());
            int seq = bb.getInt();
            byte[] frame = new byte[FRAME_SAMPLES * 2];
            bb.get(frame);
            String ip = p.getAddress().getHostAddress();
            if (ip.equals(selfIp)) {
                continue; // 防环回（自己发到广播/自己）
            }
            lastVoiceAt.put(ip, System.currentTimeMillis());
            SenderBuf sb = jitter.computeIfAbsent(ip, k -> new SenderBuf());
            sb.push(seq, frame);
        }
    }

    /** 每发送方抖动缓冲（丢包补静音，乱序丢弃）。
     *  v528：ArrayDeque 不接受 null——静音用全零哨兵帧（混音加零无副作用）。 */
    static final class SenderBuf {
        static final byte[] EMPTY = new byte[FRAME_SAMPLES * 2];
        final java.util.ArrayDeque<byte[]> q = new java.util.ArrayDeque<>();
        int lastSeq = -1;

        synchronized void push(int seq, byte[] frame) {
            int gap = (lastSeq < 0) ? 0 : seq - lastSeq - 1;
            if (gap < 0) {
                return; // 重复/乱序
            }
            if (gap > 600) {
                q.clear();
                gap = 0; // 长时间静音后恢复：重置对齐
            }
            int fill = Math.min(gap, MAX_JITTER_FRAMES);
            for (int i = 0; i < fill; i++) {
                q.add(EMPTY);
            }
            q.add(frame);
            lastSeq = seq;
            while (q.size() > MAX_JITTER_FRAMES) {
                q.poll();
            }
        }

        synchronized byte[] poll() {
            return q.poll();
        }
    }

    // ---------------- 播放 ----------------

    private void playLoop() {
        AudioTrack t = null;
        try {
            while (running) {
                if (t == null) {
                    t = createTrack();
                    if (t == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    try {
                        t.play();
                    } catch (Exception e) {
                        releaseQuiet(t);
                        t = null;
                        Thread.sleep(500);
                        continue;
                    }
                }
                long next = System.nanoTime();
                short[] mix = new short[FRAME_SAMPLES];
                for (SenderBuf sb : jitter.values()) {
                    byte[] f = sb.poll();
                    if (f == null) {
                        continue; // 无人说话/丢包 = 静音
                    }
                    ByteBuffer fb = ByteBuffer.wrap(f);
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        int v = mix[i] + fb.getShort();
                        if (v > 32767) {
                            v = 32767;
                        } else if (v < -32768) {
                            v = -32768;
                        }
                        mix[i] = (short) v;
                    }
                }
                t.write(mix, 0, mix.length);
                next += FRAME_MS * 1_000_000L;
                long sleepMs = (next - System.nanoTime()) / 1_000_000L;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                } else {
                    next = System.nanoTime(); // 追帧：丢一帧节奏
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "播放线程异常", e);
        } finally {
            releaseQuiet(t);
        }
    }

    private static AudioTrack createTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                return null;
            }
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, FRAME_SAMPLES * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private static void releaseQuiet(AudioTrack t) {
        if (t == null) {
            return;
        }
        try {
            t.stop();
        } catch (Exception ignored) {
        }
        try {
            t.release();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 说话人监测 ----------------

    private void speakerLoop() {
        while (running) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return;
            }
            Set<String> active = new CopyOnWriteArraySet<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : lastVoiceAt.entrySet()) {
                if (now - e.getValue() < 800) {
                    String cid = peers.get(e.getKey());
                    if (cid != null) {
                        active.add(cid);
                    }
                }
            }
            if (!active.equals(speakingClients)) {
                speakingClients.clear();
                speakingClients.addAll(active);
                notifyChanged();
            }
        }
    }

    // ---------------- RoomCenter 回调 ----------------

    @Override
    public void onPlayers(List<RoomCenter.Player> players, long rttMs) {
        if (players == null) {
            return;
        }
        Map<String, String> m = new ConcurrentHashMap<>();
        for (RoomCenter.Player p : players) {
            if (p.addr != null && !p.addr.isEmpty()) {
                m.put(p.addr, p.clientId);
            }
        }
        peers.clear();
        peers.putAll(m);
    }

    private void notifyChanged() {
        ui.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onVoiceChanged();
                } catch (Exception ignored) {
                }
            }
        });
    }
}
