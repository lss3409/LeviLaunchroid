package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v595：异地局域网入口桥——世界服务器侧公告/流量代理。
 *
 * 1.26 发现机制实测（角色修正后）：世界服务器周期向
 * 255.255.255.255:19132 广播公告（0x01+魔数，33 字节），客户端
 * 监听 19132 收到公告即在好友页「局域网」分类显示世界；点入后
 * RakNet 连接流量指向公告源地址。
 *
 * 用户实际场景：vivo（RoomCenter 成员）在游戏里开存档=世界服务器；
 * 平板（RoomCenter 房主）是客户端。服务器广播会经内核本地投递到
 * 本机绑 0.0.0.0:19132 的 socket——服务器侧代理：
 *   ① 拦本机服务器公告广播 → 转发平板（客户端）19132 → 客户端
 *   显示「局域网」世界（源=成员虚拟 IP:19132）；
 *   ② 平板客户端 ping/连接单播到成员虚拟 IP:19132 → 转本机
 *   127.0.0.1:世界端口（端口由 WorldPortProbe 探测——vivo SELinux
 *   允许读端口表，平板才被拒）；
 *   ③ 本机服务器应答（回环源）→ 转回平板客户端源。
 * 客户端全部流量双向代理，联机走 TUN 直达世界服务器。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;

    // ---------------- 房主侧 ----------------

    private static volatile boolean hosting;

    /** 房主开桥：周期把昵称同步给成员（c:lan）。 */
    public static synchronized void startHost() {
        stopHost();
        hosting = true;
        Thread t = new Thread(() -> {
            while (hosting) {
                try {
                    RoomCenter.sendLanAnnounce(new byte[0], 0);
                } catch (Exception e) {
                    if (hosting) {
                        Log.w(TAG, "房主公告同步异常", e);
                    }
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lan-relay-host");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "异地入口桥已启动（房主）");
    }

    public static synchronized void stopHost() {
        hosting = false;
    }

    // ---------------- 成员侧：ping 拦截 + 双向代理 ----------------

    private static volatile boolean proxying;
    private static DatagramSocket proxy;
    private static volatile String hostIp;
    private static volatile int worldPort;
    /** 客户端源地址 → 最近活跃时间（pong 回路由）。 */
    private static final Map<InetSocketAddress, Long> clients =
            new ConcurrentHashMap<>();

    /** 成员收到房主公告（c:lan）：启动/更新代理。 */
    public static synchronized void onAnnounce(byte[] ignoredReply, String host,
                                               int port, String nick) {
        hostIp = host;
        if (port > 0) {
            worldPort = port;
        }
        if (!proxying) {
            startProxy();
        }
    }

    private static synchronized void startProxy() {
        if (proxying) {
            return;
        }
        try {
            proxy = new DatagramSocket(null);
            proxy.setReuseAddress(true);
            android.net.Network vpnNet = EasyTierManager.waitForVpnNetwork(10_000);
            if (vpnNet != null) {
                try {
                    vpnNet.bindSocket(proxy);
                } catch (Exception be) {
                    Log.w(TAG, "代理绑定 VPN 网络失败", be);
                }
            }
            proxy.bind(new InetSocketAddress("0.0.0.0", ANN_PORT));
            proxy.setBroadcast(true);
            Log.i(TAG, "异地入口桥(成员): 代理已启动 端口=" + proxy.getLocalPort()
                    + " 房主=" + hostIp + " 世界端口=" + worldPort);
        } catch (Exception e) {
            Log.w(TAG, "代理启动失败", e);
            proxy = null;
            return;
        }
        proxying = true;

        Thread fwd = new Thread(() -> {
            byte[] buf = new byte[2048];
            long lastLogTs = 0;
            while (proxying && proxy != null && !proxy.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    proxy.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(buf, 0, data, 0, p.getLength());
                    String src = p.getAddress().getHostAddress();
                    int sport = p.getPort();
                    // v593：定位拦截链路——每 3s 最多打一条收包日志
                    long nowTs = System.currentTimeMillis();
                    if (nowTs - lastLogTs > 3000) {
                        lastLogTs = nowTs;
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "桥收包: from " + src + ":" + sport + " len=" + data.length
                                        + " head=" + String.format(java.util.Locale.US, "%02x",
                                                data.length > 0 ? data[0] : -1)
                                        + " wp=" + worldPort);
                    }
                    // v595：本机是世界服务器（vivo 开存档）——方向修正：
                    //   hostIp（平板，世界客户端）来的包 → 转本机 127.0.0.1 世界端口
                    //   本机回环（服务器应答）→ 转回平板客户端源
                    //   本机其他接口地址（服务器公告广播，本地投递）→ 转平板 19132
                    boolean fromClientHost = hostIp != null && src.equals(hostIp);
                    if (fromClientHost) {
                        clients.put(new InetSocketAddress(src, sport),
                                System.currentTimeMillis());
                        int fwdPort = worldPort > 0 ? worldPort : ANN_PORT;
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName("127.0.0.1"), fwdPort));
                    } else if (src.startsWith("127.")) {
                        // 本机服务器应答 → 转给 5s 内活跃的平板客户端
                        long now = System.currentTimeMillis();
                        Iterator<Map.Entry<InetSocketAddress, Long>> it =
                                clients.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<InetSocketAddress, Long> e = it.next();
                            if (now - e.getValue() > 5000) {
                                it.remove();
                                continue;
                            }
                            try {
                                proxy.send(new DatagramPacket(data, data.length,
                                        e.getKey().getAddress(), e.getKey().getPort()));
                            } catch (Exception ignored) {
                            }
                        }
                    } else if (hostIp != null) {
                        // 本机服务器公告（广播本地投递，源=本机 wlan/热点地址）
                        // → 转发平板客户端 19132（客户端监听处收公告显示世界）
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(hostIp), ANN_PORT));
                    }
                } catch (Exception e) {
                    if (proxying) {
                        Log.w(TAG, "代理转发异常", e);
                    }
                }
            }
        }, "lan-relay-fwd");
        fwd.setDaemon(true);
        fwd.start();

        // v595：本机（vivo）是世界服务器——周期探测世界端口（vivo SELinux
        // 允许读端口表，平板被拒；服务器侧探测即可）
        Thread probe = new Thread(() -> {
            while (proxying && proxy != null && !proxy.isClosed()) {
                int wp = WorldPortProbe.getWorldPort();
                if (wp > 0 && wp != worldPort) {
                    worldPort = wp;
                    org.levimc.launcher.util.OnlineDebugLog.log(
                            "异地桥(服务器): 探测到本机世界端口 " + wp);
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lan-relay-probe");
        probe.setDaemon(true);
        probe.start();
        fwd.setDaemon(true);
        fwd.start();
    }

    public static synchronized void stopClient() {
        proxying = false;
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception ignored) {
            }
            proxy = null;
        }
        clients.clear();
        hostIp = null;
        worldPort = 0;
    }
}
