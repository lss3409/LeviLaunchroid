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
    public static final int ROOM_CENTER_PORT = 8090;
    private static final long POLL_INTERVAL_MS = 3000;
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
    private volatile ConnMode connMode = ConnMode.UNKNOWN;
    private Context appContext;
    private Listener listener;

    private EasyTierManager() {
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

    public void setListener(Listener l) {
        listener = l;
    }

    /** 加入网络。调用前必须已完成 VpnService.prepare 授权（由 Activity 把关）。 */
    public void join(Context ctx, String networkName, String networkSecret, Listener l) {
        join(ctx, networkName, networkSecret, l, null);
    }

    /** 加入网络。extraPeers 为附加的直连 peer（局域网/自建中转，如 tcp://192.168.1.2:11010）。 */
    public void join(Context ctx, String networkName, String networkSecret, Listener l,
                     java.util.List<String> extraPeers) {
        start(ctx, networkName, networkSecret, l, extraPeers, null);
    }

    /** 创建房间（房主）：固定虚拟 IP + DHCP 关闭，成员 dhcp 以本机 IP 为网段基准分配。 */
    public void host(Context ctx, String networkName, String networkSecret, Listener l,
                     String fixedIpv4) {
        host(ctx, networkName, networkSecret, l, fixedIpv4, null);
    }

    /** 创建房间，extraPeers 合并中转服务器（房主也必须连中转，否则成员经中转找不到房主）。 */
    public void host(Context ctx, String networkName, String networkSecret, Listener l,
                     String fixedIpv4, java.util.List<String> extraPeers) {
        start(ctx, networkName, networkSecret, l, extraPeers, fixedIpv4);
    }

    private void start(Context ctx, String networkName, String networkSecret, Listener l,
                       java.util.List<String> extraPeers, String fixedIpv4) {
        synchronized (lock) {
            stopInternal();
            appContext = ctx.getApplicationContext();
            listener = l;
            virtualIp = null;
            active = true;
            state = State.STARTING;
            notifyState(State.STARTING, null);
            worker = new Thread(() -> {
                // 清理旧实例（上次加入失败/断开后内核实例可能仍在运行）
                try {
                    EasyTierJNI.stopAllInstances();
                } catch (Throwable ignored) {
                }
                runStart(networkName, networkSecret, extraPeers, fixedIpv4);
            }, "easytier-mgr");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runStart(String networkName, String networkSecret,
                          java.util.List<String> extraPeers, String fixedIpv4) {
        // dhcp=true：IP 由网络内其他节点（房主固定 IP）决定网段后自动分配；
        // 单机（无对端）时 EasyTier 不分配虚拟 IP，60s 后提示超时属预期。
        // fixedIpv4 != null = 房主模式（dhcp=false + 固定虚拟 IP）。
        // 注意：EasyTier 官方公共节点已于 2026-05 全部下线（GitHub #2242，
        // 维护者确认"官方已经不提供公共节点了"）——组网必须靠直连 peer
        // （局域网自动发现/自建中转）。
        String toml = "instance_name = \"" + INSTANCE_NAME + "\"\n"
                + "dhcp = " + (fixedIpv4 == null ? "true" : "false") + "\n"
                + (fixedIpv4 != null ? "ipv4 = \"" + fixedIpv4 + "\"\n" : "")
                // 房主节点带协议主机名，房客 RPC 匹配 paper-connect-server-* 发现房间中心
                + (fixedIpv4 != null
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
                startVpn(info.virtualIp, info.cidrs);
                Log.i(TAG, "已连接, 虚拟 IP = " + info.virtualIp);
                notifyState(State.CONNECTED, info.virtualIp);
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
                    // 房主端回退：任意路由对的 peer 直连情况（成员→房主的路径）
                    for (int i = 0; i < prp.length(); i++) {
                        JSONObject pair = prp.optJSONObject(i);
                        if (pair == null) {
                            continue;
                        }
                        JSONObject peer = pair.optJSONObject("peer");
                        if (peer == null) {
                            continue;
                        }
                        JSONArray dcc = peer.optJSONArray("directly_connected_conns");
                        connMode = (dcc != null && dcc.length() > 0)
                                ? ConnMode.P2P : ConnMode.RELAY;
                        break;
                    }
                }
            }
            Log.i(TAG, "poll: running=" + info.running + " ip=" + info.virtualIp
                    + " cidrs=" + info.cidrs + " err=" + info.errorMsg);
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
