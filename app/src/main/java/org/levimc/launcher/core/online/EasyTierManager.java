package org.levimc.launcher.core.online;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.easytier.jni.EasyTierJNI;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 联机组网编排器（v495，单例）：EasyTier 内核 + VpnService 两段式接入。
 *
 * 流程：join() → runNetworkInstance(TOML, dhcp=true) → 轮询 collectNetworkInfos
 * 拿到本节点虚拟 IP → 以该 IP 启动 EasyTierVpnService 挂 TUN → CONNECTED。
 * 状态通过 Listener 回调到 UI 线程。
 */
public final class EasyTierManager {

    private static final String TAG = "EasyTierMgr";
    private static final String INSTANCE_NAME = "paper-connect";
    /** 房主房间中心 TCP 端口（PaperConnect 协议：hostname = paper-connect-server-<port>）。 */
    /** 房间中心端口（v522：8090 与联想系统服务冲突，改 18090）。 */
    public static final int ROOM_CENTER_PORT = 18090;
    /** v537：IP 轮询 3s→1s（加入房间提速）。 */
    private static final long POLL_INTERVAL_MS = 1000;
    private static final long IP_TIMEOUT_MS = 60_000;
    private static final String FALLBACK_CIDR = "10.144.0.0/16";
    /** 内核 DHCP 默认网段（OSPF 路由未同步时成员会拿到 10.126.126.x）。 */
    private static final String FALLBACK_CIDR_DHCP = "10.126.126.0/24";

    public enum State {
        IDLE,        // 未连接
        STARTING,    // 正在启动内核
        WAIT_IP,     // 内核已启动，等待 DHCP 分配虚拟 IP
        CONNECTED,   // 已连接（detail = 虚拟 IP）
        FAILED       // 失败（detail = 错误信息）
    }

    /** 与房主的连接模式（v505）：P2P 直连 / 中继转发 / 未知。 */
    public enum ConnMode {
        UNKNOWN, P2P, RELAY
    }

    /** 状态回调（主线程）。 */
    public interface Listener {
        void onState(State state, String detail);
    }

    private static EasyTierManager sInstance;

    public static synchronized EasyTierManager get() {
        if (sInstance == null) {
            sInstance = new EasyTierManager();
        }
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean active;
    private volatile State state = State.IDLE;
    private volatile String virtualIp;
    /** 最近一次连接使用的虚拟网段路由（v522 看门狗重拉 VpnService 用）。 */
    private volatile java.util.List<String> lastCidrs = new java.util.ArrayList<>();
    private volatile ConnMode connMode = ConnMode.UNKNOWN;
    /** v561：房主在虚拟网络内的真实 IP（从 peer_route_pairs 解析）。
     *  异地经中转时房主 DHCP 拿到的 IP 不一定是 10.144.144.144——成员端
     *  写死该 IP 会导致心跳连不上（双方都只显示 1 人）。 */
    private volatile String hostVirtualIp;
    /** v565：peer 路由表签名（变化时才写文件日志，避免刷爆）。 */
    private volatile String lastPeerSig = "";
    private static volatile Context appContext;
    /** v571：VPN 授权失效时回调 UI 弹授权窗（重装 APK 后 vivo/ZUI 清授权，
     * establish 永远失败——看门狗拉不起来，必须重新走 prepare 弹窗）。 */
    private static volatile Runnable vpnAuthRequiredCallback;
    private static volatile long lastVpnAuthNotify;
    private Listener listener;

    private EasyTierManager() {
    }

    /** v566：供 RoomCenter/VoiceEngine 拿 VPN 网络绑定 socket 用。 */
    public static Context getAppContext() {
        return appContext;
    }

    /** v571：UI 注册 VPN 授权失效回调（OnlineActivity onCreate）。 */
    public static void setVpnAuthRequiredCallback(Runnable cb) {
        vpnAuthRequiredCallback = cb;
    }

    /** v571：VpnService establish 失败时调用（30s 节流，避免重试循环狂弹）。 */
    public static void notifyVpnAuthorizationRequired() {
        long now = System.currentTimeMillis();
        if (now - lastVpnAuthNotify < 30_000) {
            return;
        }
        lastVpnAuthNotify = now;
        Runnable cb = vpnAuthRequiredCallback;
        if (cb != null) {
            sMainHandler.post(cb);
        }
    }

    private static final android.os.Handler sMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * v566：等待 VPN 网络出现（VpnService establish 完成、系统注册 TRANSPORT_VPN）。
     * 业务 socket（房间中心/语音）显式绑到 VPN 网络，防止"socket 创建早于
     * TUN 建立"时绑到旧网络（公网）导致心跳/语音丢失——v565 实测成员
     * socket 比 TUN 早 8ms 创建。绑定时机由本方法控制。
     */
    public static android.net.Network waitForVpnNetwork(long timeoutMs) {
        Context ctx = appContext;
        if (ctx == null) {
            return null;
        }
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                        return n;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    public State getState() {
        return state;
    }

    public String getVirtualIp() {
        return virtualIp;
    }

    /** 当前与房主的连接模式（v505）。 */
    public ConnMode getConnMode() {
        return connMode;
    }

    /** v561：房主虚拟 IP（peer 路由表解析，异地中继下 DH 分配的真实地址）；
     *  null = 未解析到（调用方回退 10.144.144.144）。 */
    public String getHostVirtualIp() {
        return hostVirtualIp;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 加入网络。调用前必须已完成 VpnService.prepare 授权（由 Activity 把关）。 */
    public void join(Context ctx, String networkName, String networkSecret, Listener l) {
        join(ctx, networkName, networkSecret, l, null, null);
    }

    /** 加入网络。extraPeers 为附加的直连 peer（局域网/自建中转，如 tcp://192.168.1.2:11010）。 */
    public void join(Context ctx, String networkName, String networkSecret, Listener l,
                     java.util.List<String> extraPeers) {
        join(ctx, networkName, networkSecret, l, extraPeers, null);
    }

    /**
     * 加入网络（v565）：memberIpv4 为成员固定虚拟 IP（含前缀，如 10.144.5.2/24）。
     * 传 null 则回退 DHCP 分配。固定 IP 的原因：EasyTier 的 DHCP 是从路由表
     * 第一个 peer 的网段里挑地址——成员通常分到房主网段（10.144.144.x），
     * 目标 IP 落在本机 TUN 的 connected 网段内时内核走邻居解析（虚拟网卡
     * 无 ARP 应答）包被丢弃，心跳发不出去——异地"未找到房主"/只显示
     * 1 人的根因。分到异网段时走 via 路由则正常，所以随机性极强。
     */
    public void join(Context ctx, String networkName, String networkSecret, Listener l,
                     java.util.List<String> extraPeers, String memberIpv4) {
        start(ctx, networkName, networkSecret, l, extraPeers, memberIpv4, false);
    }

    /**
     * v565：成员固定虚拟 IP 派生——按 clientId hash 取 10.144.X.Y/24，
     * X 跳过 144（房主网段），保证成员与房主永远不同网段、同网段成员
     * 间 IP 也不同（Y 亦 hash）。同设备同版本 clientId 不变，IP 稳定。
     */
    public static String memberIpv4For(String clientId) {
        int h = clientId == null ? 0 : clientId.hashCode();
        int x = 2 + Math.floorMod(h, 251);      // 2..252
        if (x >= 144) {
            x++;                                // 跳过房主网段 144 → 2..253 共 250 个网段
        }
        int y = 2 + Math.floorMod(h >>> 8, 250); // .2..251
        return "10.144." + x + "." + y + "/24";
    }

    /** 创建房间（房主）：固定虚拟 IP + DHCP 关闭。 */
    public void host(Context ctx, String networkName, String networkSecret, Listener l,
                     String fixedIpv4) {
        host(ctx, networkName, networkSecret, l, fixedIpv4, null);
    }

    /** 创建房间，extraPeers 合并中转服务器（房主也必须连中转，否则成员经中转找不到房主）。 */
    public void host(Context ctx, String networkName, String networkSecret, Listener l,
                     String fixedIpv4, java.util.List<String> extraPeers) {
        start(ctx, networkName, networkSecret, l, extraPeers, fixedIpv4, true);
    }

    private void start(Context ctx, String networkName, String networkSecret, Listener l,
                       java.util.List<String> extraPeers, String fixedIpv4, boolean isHost) {
        synchronized (lock) {
            stopInternal();
            appContext = ctx.getApplicationContext();
            listener = l;
            virtualIp = null;
            // v572：清上次会话的房主 IP 解析——残留值会让成员的 8s
            // 快速判失败误以为"已看到房主 peer"，最近房间（房主已
            // 解散）加入仍等满 20s 握手超时
            hostVirtualIp = null;
            lastPeerSig = "";
            active = true;
            state = State.STARTING;
            notifyState(State.STARTING, null);
            worker = new Thread(() -> {
                // 清理旧实例（上次加入失败/断开后内核实例可能仍在运行）
                try {
                    EasyTierJNI.stopAllInstances();
                } catch (Throwable ignored) {
                }
                runStart(networkName, networkSecret, extraPeers, fixedIpv4, isHost);
            }, "easytier-mgr");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runStart(String networkName, String networkSecret,
                          java.util.List<String> extraPeers, String fixedIpv4, boolean isHost) {
        // fixedIpv4 != null = 固定虚拟 IP 模式（dhcp=false）：
        //   isHost=true → 房主（带协议主机名，成员 RPC 匹配 paper-connect-server-* 发现房间中心）；
        //   isHost=false → 成员固定派生 IP（v565：不再 DHCP——DHCP 会从房主
        //   网段分地址导致内核邻居解析死路，心跳发不出去）。
        // 单机（无对端）时 DHCP 不分配虚拟 IP，60s 后提示超时属预期。
        // 注意：EasyTier 官方公共节点已于 2026-05 全部下线（GitHub #2242，
        // 维护者确认"官方已经不提供公共节点了"）——组网必须靠直连 peer
        // （局域网自动发现/自建中转）。
        String ipv4Line = "";
        if (fixedIpv4 != null) {
            // v565：CIDR 原样使用（成员固定 IP 自带 /24；房主传纯 IP 时补 /24，
            // 纯 IP 可能被内核忽略导致 dhcp 生效、IP 随机分配——v561 教训）
            ipv4Line = "ipv4 = \"" + (fixedIpv4.contains("/") ? fixedIpv4 : fixedIpv4 + "/24")
                    + "\"\n";
            org.levimc.launcher.util.OnlineDebugLog.log("组网固定IP模式: " + ipv4Line.trim()
                    + (isHost ? "（房主）" : "（成员）"));
        }
        String toml = "instance_name = \"" + INSTANCE_NAME + "\"\n"
                + "dhcp = " + (fixedIpv4 == null ? "true" : "false") + "\n"
                + ipv4Line
                // 房主节点带协议主机名，房客 RPC 匹配 paper-connect-server-* 发现房间中心
                + (isHost
                        ? "hostname = \"paper-connect-server-" + ROOM_CENTER_PORT + "\"\n" : "")
                + "log_level = \"info\"\n"
                // Android 内核默认不监听 11010（poll listeners 只有 ring://），
                // 必须显式开启监听，局域网直连/中转才能连进本机。
                + "listeners = [\"tcp://0.0.0.0:11010\", \"udp://0.0.0.0:11010\"]\n"
                + "[network_identity]\n"
                + "network_name = \"" + networkName + "\"\n"
                + "network_secret = \"" + networkSecret + "\"\n";
        if (extraPeers != null) {
            for (String uri : extraPeers) {
                if (uri != null && !uri.isEmpty()) {
                    toml += "[[peer]]\n" + "uri = \"" + uri + "\"\n";
                }
            }
        }
        Log.d(TAG, "TOML 配置:\n" + toml);
        int rc;
        try {
            rc = EasyTierJNI.runNetworkInstance(toml);
        } catch (Throwable t) {
            Log.e(TAG, "内核调用异常", t);
            String msg = t.getMessage();
            postFail("组网内核调用异常: " + t.getClass().getSimpleName()
                    + (msg == null || msg.isEmpty() ? "" : " " + msg));
            return;
        }
        Log.i(TAG, "runNetworkInstance = " + rc);
        if (rc != 0) {
            postFail("组网内核启动失败: " + String.valueOf(EasyTierJNI.getLastError()));
            return;
        }
        notifyState(State.WAIT_IP, null);
        long deadline = System.currentTimeMillis() + IP_TIMEOUT_MS;
        while (active && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            Info info = pollInfo();
            if (info == null) {
                continue;
            }
            if (!info.running) {
                postFail("网络实例未运行" + (info.errorMsg.isEmpty() ? "" : ": " + info.errorMsg));
                return;
            }
            if (info.virtualIp != null) {
                virtualIp = info.virtualIp;
                lastCidrs = new java.util.ArrayList<>(info.cidrs);
                startVpn(info.virtualIp, info.cidrs);
                // v566：等 VPN 网络注册完成再报 CONNECTED（本线程是 worker，
                // 可阻塞）——之后 RoomCenter/语音 socket 创建时可立即绑定，
                // 避免主线程长等待（v565 竞态：socket 早于 TUN 创建绑旧网络）
                android.net.Network vpnNet = waitForVpnNetwork(10_000);
                if (vpnNet == null) {
                    org.levimc.launcher.util.OnlineDebugLog.log("警告：VPN 网络 10s 未注册，业务 socket 将走默认网络");
                }
                Log.i(TAG, "已连接, 虚拟 IP = " + info.virtualIp);
                notifyState(State.CONNECTED, info.virtualIp);
                startWatchdog();
                return;
            }
        }
        postFail("等待虚拟 IP 超时（DHCP 未分配）");
    }

    /** 停止组网：发停止信号给 VpnService（服务内关 tun）+ 停内核实例。 */
    public void stop(Context ctx) {
        synchronized (lock) {
            stopInternal();
            if (ctx != null) {
                try {
                    // VpnService 被系统 binder 绑定，stopService 不会销毁——
                    // 用 ACTION_STOP 信号让服务内主动关闭 tun 终止 VPN。
                    Intent s = new Intent(ctx, EasyTierVpnService.class);
                    s.setAction(EasyTierVpnService.ACTION_STOP);
                    ctx.startService(s);
                } catch (Exception e) {
                    Log.w(TAG, "发 VPN 停止信号失败", e);
                }
            }
            new Thread(() -> {
                try {
                    EasyTierJNI.stopAllInstances();
                } catch (Throwable t) {
                    Log.w(TAG, "停内核实例失败", t);
                }
            }, "easytier-stop").start();
        }
    }

    private void stopInternal() {
        active = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (state != State.IDLE && state != State.FAILED) {
            state = State.IDLE;
            notifyState(State.IDLE, null);
        }
    }

    // ---- 内部工具 ----

    private void startVpn(String ipv4, List<String> cidrs) {
        try {
            // v573：直接 startService 带新参数（不再先发 ACTION_STOP）——
            // 先 STOP 再启动时系统会把第二个 Intent 吞掉（服务已标记
            // 停止），新实例根本没起来：平板实测只有 onDestroy 循环、
            // establish 日志一条都没有，VPN 永远拉不起来。旧 runTun
            // 由 onStartCommand 内部终止（running=false + closeTun）。
            Intent i = new Intent(appContext, EasyTierVpnService.class);
            i.putExtra(EasyTierVpnService.EXTRA_INSTANCE, INSTANCE_NAME);
            i.putExtra(EasyTierVpnService.EXTRA_IPV4, ipv4);
            if (cidrs != null && !cidrs.isEmpty()) {
                i.putExtra(EasyTierVpnService.EXTRA_CIDRS, cidrs.toArray(new String[0]));
            }
            appContext.startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "启动 VpnService 失败", t);
        }
    }

    /**
     * v522 管理器级看门狗：CONNECTED 后每 5s——
     * ① TUN 丢失（服务被系统解绑/杀死）自动重新拉起 VpnService；
     * ② 周期刷新连接模式（peer 路由变化实时反映到徽章）。
     */
    private void startWatchdog() {
        Thread t = new Thread(() -> {
            // v571：重拉节流——establish 持续失败（未授权等）时至少隔
            // 15s 才重拉一次，避免 5s 一次的"杀服务→重启→失败"风暴
            // （实测造成间歇卡顿 + 文件日志刷爆）
            long lastPull = 0;
            int consecutiveFail = 0;
            while (active && state == State.CONNECTED) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!active || state != State.CONNECTED) {
                    return;
                }
                try {
                    if (!tunExists()) {
                        long now = System.currentTimeMillis();
                        if (now - lastPull < 15_000) {
                            continue;
                        }
                        lastPull = now;
                        consecutiveFail++;
                        // v572：连续 2 次重拉后 TUN 仍不存在——大概率是
                        // VPN 授权被清（重装 APK）而非暂时性吊销，直接
                        // 通知 UI 弹授权窗（不依赖 VpnService 内部回调）
                        if (consecutiveFail >= 2) {
                            org.levimc.launcher.util.OnlineDebugLog.log(
                                    "看门狗连续重拉失败，请求 VPN 授权弹窗");
                            notifyVpnAuthorizationRequired();
                        }
                        Log.w(TAG, "看门狗：TUN 丢失，重新拉起 VpnService");
                        startVpn(virtualIp, lastCidrs);
                        continue;
                    } else {
                        consecutiveFail = 0;
                    }
                    Info info = pollInfo();
                    if (info != null && !info.running) {
                        postFail("网络实例停止" + (info.errorMsg.isEmpty() ? "" : ": " + info.errorMsg));
                        return;
                    }
                    // v525：游戏在前台时兜底挂载联机悬浮窗（覆盖任何挂载时序）
                    try {
                        if (org.levimc.launcher.core.minecraft.MinecraftActivityState.isRunning()) {
                            android.app.Activity game =
                                    org.levimc.launcher.core.minecraft.MinecraftActivityState.getActivity();
                            if (game != null) {
                                main.post(() -> OnlineOverlay.get(game).show());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t2) {
                    Log.w(TAG, "看门狗异常", t2);
                }
            }
        }, "easytier-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** TUN 接口是否存在（VpnService 的 tunN；tunl0 等内核隧道不算）。 */
    private static boolean tunExists() {
        try {
            // v567：不用 /proc/net/dev——SELinux 拒绝 untrusted_app 读
            // proc_net（avc denied 实测），且 contains("tun") 会误匹配
            // 内核 tunl0 隧道设备导致看门狗失明（v566 教训）
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                String n = ifs.nextElement().getName();
                if (n.matches("tun[0-9]{1,2}")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // 读不到就当健康，避免误拉
        }
    }

    /** 单次拉取本实例信息（跑在 worker 线程）。 */
    private Info pollInfo() {
        try {
            String json = EasyTierJNI.collectNetworkInfos(10);
            if (json == null || json.isEmpty()) {
                return null;
            }
            // 尾部含 peers/peer_route_pairs（头部日志过长截断在前）
            Log.i(TAG, "poll json tail: " + (json.length() > 2500
                    ? json.substring(json.length() - 2500) : json));
            JSONObject root = new JSONObject(json);
            JSONObject map = root.optJSONObject("map");
            if (map == null) {
                return null;
            }
            JSONObject inst = map.optJSONObject(INSTANCE_NAME);
            if (inst == null) {
                return null;
            }
            Info info = new Info();
            info.running = inst.optBoolean("running", false);
            info.errorMsg = str(inst, "error_msg", "errorMsg");
            JSONObject node = inst.optJSONObject("my_node_info");
            if (node == null) {
                node = inst.optJSONObject("myNodeInfo");
            }
            if (node != null) {
                JSONObject vip = node.optJSONObject("virtual_ipv4");
                if (vip == null) {
                    vip = node.optJSONObject("virtualIpv4");
                }
                if (vip != null) {
                    JSONObject addr = vip.optJSONObject("address");
                    if (addr != null && addr.has("addr")) {
                        long a = addr.optLong("addr", 0) & 0xFFFFFFFFL;
                        int len = vip.optInt("network_length", vip.optInt("networkLength", 16));
                        info.virtualIp = ((a >> 24) & 0xFF) + "." + ((a >> 16) & 0xFF) + "."
                                + ((a >> 8) & 0xFF) + "." + (a & 0xFF) + "/" + len;
                    }
                }
            }
            JSONArray routes = inst.optJSONArray("routes");
            if (routes != null) {
                for (int i = 0; i < routes.length(); i++) {
                    JSONObject route = routes.optJSONObject(i);
                    if (route == null) {
                        continue;
                    }
                    JSONArray pc = route.optJSONArray("proxy_cidrs");
                    if (pc == null) {
                        pc = route.optJSONArray("proxyCidrs");
                    }
                    if (pc != null) {
                        for (int j = 0; j < pc.length(); j++) {
                            String c = pc.optString(j, null);
                            if (c != null && !c.isEmpty() && !info.cidrs.contains(c)) {
                                info.cidrs.add(c);
                            }
                        }
                    }
                }
            }
            if (info.cidrs.isEmpty()) {
                info.cidrs.add(FALLBACK_CIDR);
                info.cidrs.add(FALLBACK_CIDR_DHCP);
            }
            // 连接模式：peer_route_pairs 里房主路由的 peer 是否有直连 conn。
            // 注意不对称性（v519 修复）：成员的路由表里有房主（paper-connect-server-*）
            // 条目可判定；房主自己就是该节点，路由表里没有这个主机名——房主端
            // 回退为"任一 peer 路由对"判定（看成员路由的直连情况）。
            connMode = ConnMode.UNKNOWN;
            boolean checkedAny = false;
            JSONArray prp = inst.optJSONArray("peer_route_pairs");
            if (prp != null) {
                for (int i = 0; i < prp.length(); i++) {
                    JSONObject pair = prp.optJSONObject(i);
                    if (pair == null) {
                        continue;
                    }
                    JSONObject route = pair.optJSONObject("route");
                    boolean isHostRoute = route != null
                            && route.optString("hostname", "").startsWith("paper-connect-server-");
                    if (!isHostRoute) {
                        continue;
                    }
                    // v561：顺带记录房主虚拟 IP——异地经中转时房主 DHCP 拿到的
                    // 虚拟 IP 不一定是 10.144.144.144，成员端写死该 IP 导致
                    // 心跳连不上（双方都显示 1 人）的根因。路由表里的
                    // ipv4_addr 是房主在虚拟网络内的真实地址。
                    if (route != null) {
                        JSONObject ipa = route.optJSONObject("ipv4_addr");
                        JSONObject adr = ipa == null ? null : ipa.optJSONObject("address");
                        if (adr != null) {
                            long a = adr.optLong("addr", -1);
                            if (a > 0 && a <= 0xFFFFFFFFL) {
                                String ip = ((a >> 24) & 0xFF) + "." + ((a >> 16) & 0xFF)
                                        + "." + ((a >> 8) & 0xFF) + "." + (a & 0xFF);
                                if (!ip.startsWith("0.") && !ip.startsWith("127.")) {
                                    hostVirtualIp = ip;
                                }
                            }
                        }
                    }
                    checkedAny = true;
                    JSONObject peer = pair.optJSONObject("peer");
                    if (peer == null) {
                        connMode = ConnMode.RELAY;
                        continue;
                    }
                    JSONArray dcc = peer.optJSONArray("directly_connected_conns");
                    connMode = (dcc != null && dcc.length() > 0)
                            ? ConnMode.P2P : ConnMode.RELAY;
                }
                if (!checkedAny) {
                    // 房主端回退：任一成员路由对存在直连即 P2P（v520 修复：
                    // 之前取第一个 pair 会拿到中转节点导致误报中继）
                    boolean anyPair = false;
                    for (int i = 0; i < prp.length(); i++) {
                        JSONObject pair = prp.optJSONObject(i);
                        if (pair == null) {
                            continue;
                        }
                        JSONObject peer = pair.optJSONObject("peer");
                        if (peer == null) {
                            continue;
                        }
                        anyPair = true;
                        JSONArray dcc = peer.optJSONArray("directly_connected_conns");
                        if (dcc != null && dcc.length() > 0) {
                            connMode = ConnMode.P2P;
                            break;
                        }
                        connMode = ConnMode.RELAY;
                    }
                    if (!anyPair) {
                        connMode = ConnMode.UNKNOWN;
                    }
                }
            }
            Log.i(TAG, "poll: running=" + info.running + " ip=" + info.virtualIp
                    + " cidrs=" + info.cidrs + " err=" + info.errorMsg);
            // v565：peer 路由表变化时写文件日志（下次异地测试可直接判断
            // 两端 EasyTier 是否真正看到对方、是 P2P 还是中继）
            if (prp != null) {
                java.util.List<String> sigs = new java.util.ArrayList<>();
                for (int i = 0; i < prp.length(); i++) {
                    JSONObject pair = prp.optJSONObject(i);
                    if (pair == null) {
                        continue;
                    }
                    JSONObject rt = pair.optJSONObject("route");
                    JSONObject pr = pair.optJSONObject("peer");
                    String hn = rt == null ? "" : rt.optString("hostname", "");
                    String ip = "";
                    if (rt != null) {
                        JSONObject ipa = rt.optJSONObject("ipv4_addr");
                        JSONObject adr = ipa == null ? null : ipa.optJSONObject("address");
                        if (adr != null) {
                            long a = adr.optLong("addr", -1);
                            if (a > 0 && a <= 0xFFFFFFFFL) {
                                ip = ((a >> 24) & 0xFF) + "." + ((a >> 16) & 0xFF)
                                        + "." + ((a >> 8) & 0xFF) + "." + (a & 0xFF);
                            }
                        }
                    }
                    boolean p2p = pr != null
                            && pr.optJSONArray("directly_connected_conns") != null
                            && pr.optJSONArray("directly_connected_conns").length() > 0;
                    sigs.add(hn + "|" + ip + "|" + (p2p ? "p2p" : "relay"));
                }
                java.util.Collections.sort(sigs);
                String sig = sigs.toString();
                if (!sig.equals(lastPeerSig)) {
                    lastPeerSig = sig;
                    org.levimc.launcher.util.OnlineDebugLog.log("peer 路由表变化: " + sig);
                }
            }
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "解析网络信息失败", t);
            return null;
        }
    }

    private void postFail(String msg) {
        Log.e(TAG, msg);
        active = false;
        notifyState(State.FAILED, msg);
    }

    private void notifyState(State s, String detail) {
        state = s;
        main.post(() -> {
            if (listener != null) {
                listener.onState(s, detail);
            }
        });
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static final class Info {
        boolean running;
        String virtualIp;
        String errorMsg = "";
        final List<String> cidrs = new ArrayList<>();
    }
}
