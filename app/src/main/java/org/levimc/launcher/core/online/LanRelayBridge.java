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
 * v592：异地局域网入口桥——拦截客户端 ping 广播 + 双向代理。
 *
 * 1.26 发现机制实测：
 *   客户端周期向 255.255.255.255:19132 广播 RakNet ping（0x01+魔数）；
 *   服务器（房主游戏）监听 19132 收到 ping 后单播回 pong（0x1C 含
 *   世界名，源端口=世界随机端口）；客户端收到 pong 即在好友页
 *   「局域网」分类显示世界。客户端本身不监听 19132。
 *
 * 异地时 ping 广播进 TUN 被 EasyTier 吞掉（v584 重放公告、v588 合成
 * pong、v590 动态端口、v591 单播回环全部无效的根因——客户端根本不
 * 收 19132 的单播）。正确入口 = 拦客户端自己发出的 ping 广播：
 * 客户端广播会经内核本地投递到同网络（VPN mark 相同）绑 0.0.0.0:19132
 * 的 socket。
 *
 * 方案：
 * 房主侧 startHost()：周期 c:lan 同步房主昵称（成员据此启动代理）。
 * 成员侧 onAnnounce()：单 socket 绑 0.0.0.0:19132（VPN 网络）——
 *   ① 收到本机客户端广播 ping → 记录客户端源 → 转发房主 19132
 *   （服务器在 19132 监听发现 ping）；
 *   ② 房主回 pong/握手包（源=房主世界端口或 19132）→ 转回客户端
 *   源，源端口≠19132 时学习为世界端口；
 *   ③ 客户端后续连接流量 → 转发房主（已学端口或 19132）。
 * 客户端视角世界服务器 = 成员虚拟 IP:19132，全部流量双向代理，
 * 联机走 TUN 直达房主世界。
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
                                                data.length > 0 ? data[0] : -1));
                    }
                    // 房主回包判定：已学习的 worldPort 或发现端口 19132
                    boolean fromHost = hostIp != null && src.equals(hostIp)
                            && (sport == worldPort || sport == ANN_PORT);
                    if (fromHost) {
                        // 房主回包：源端口≠19132 即真实世界端口（学习）
                        if (sport != ANN_PORT && worldPort != sport) {
                            worldPort = sport;
                            org.levimc.launcher.util.OnlineDebugLog.log(
                                    "异地桥(成员): 学习到房主世界端口 " + sport);
                        }
                        // 转给 5s 内活跃的客户端
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
                    } else if (hostIp != null && data.length >= 1) {
                        // 客户端流量（本机广播 ping / 单播连接）→ 记录并转发房主
                        int fwdPort = worldPort > 0 ? worldPort : ANN_PORT;
                        clients.put(new InetSocketAddress(src, sport),
                                System.currentTimeMillis());
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(hostIp), fwdPort));
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
