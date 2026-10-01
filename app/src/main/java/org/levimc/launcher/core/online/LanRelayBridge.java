package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v597：异地局域网入口桥——世界服务器（房主）侧公告/流量代理。
 *
 * 1.26 发现机制实测：世界服务器周期向 255.255.255.255:19132 广播
 * 公告（0x01+魔数，33 字节，源端口=世界监听 socket），客户端监听
 * 19132 收到公告即在好友页「局域网」分类显示世界；点入后 RakNet
 * 连接流量指向公告源地址。
 *
 * 用户场景：平板=房主=世界服务器（开存档），vivo=成员=客户端
 * （好友页看局域网入口）。服务器广播会经内核本地投递到本机绑
 * 0.0.0.0:19132 的 socket——服务器侧代理：
 *   ① 拦本机服务器公告广播 → 学习公告源端口=世界端口 → 转发
 *   客户端（vivo）19132 → 客户端好友页显示「局域网」世界
 *   （源=房主虚拟 IP:19132）；
 *   ② 客户端 ping/连接单播到房主虚拟 IP:19132 → 转本机
 *   127.0.0.1:世界端口（本机服务器应答）；
 *   ③ 本机服务器应答（回环源）→ 转回客户端源。
 * 客户端全部流量双向代理，联机走 TUN 直达世界服务器。
 *
 * 平板 SELinux 拒读 /proc/self/net/udp——世界端口不靠 WorldPortProbe，
 * 直接从公告源端口学习（实测公告即从世界监听 socket 发出）。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;

    // ---------------- 服务器（房主）侧代理 ----------------

    private static volatile boolean hosting;
    private static DatagramSocket proxy;
    private static volatile int worldPort;
    /** 客户端（vivo）源地址 → 最近活跃时间（服务器应答回路由）。 */
    private static final Map<InetSocketAddress, Long> clients =
            new ConcurrentHashMap<>();

    /** 房主开桥：启动代理 + 周期 c:lan 同步。 */
    public static synchronized void startHost() {
        stopHost();
        hosting = true;
        startProxy();
        Thread t = new Thread(() -> {
            while (hosting) {
                try {
                    RoomCenter.sendLanAnnounce(new byte[0], worldPort);
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
        Log.i(TAG, "异地入口桥已启动（房主/世界服务器）");
    }

    public static synchronized void stopHost() {
        hosting = false;
        stopProxy();
    }

    /** 成员收到 c:lan：本场景客户端侧无需代理，仅记录。 */
    public static synchronized void onAnnounce(byte[] ignoredReply, String host,
                                               int port, String nick) {
        Log.i(TAG, "异地桥(成员/客户端): 房主=" + host + " wp=" + port);
    }

    public static synchronized void stopClient() {
        // 客户端侧无代理（保留接口兼容旧接线）
    }

    private static synchronized void startProxy() {
        if (proxy != null && !proxy.isClosed()) {
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
            Log.i(TAG, "异地入口桥(服务器): 代理已启动 端口=" + proxy.getLocalPort());
        } catch (Exception e) {
            Log.w(TAG, "代理启动失败", e);
            proxy = null;
            return;
        }

        Thread fwd = new Thread(() -> {
            byte[] buf = new byte[2048];
            long lastLogTs = 0;
            while (hosting && proxy != null && !proxy.isClosed()) {
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
                    String clientIp = getClientIp();
                    boolean fromClient = clientIp != null && src.equals(clientIp);
                    if (fromClient) {
                        // 客户端流量（ping/连接）→ 记录源 → 转本机服务器
                        clients.put(new InetSocketAddress(src, sport),
                                System.currentTimeMillis());
                        int fwdPort = worldPort > 0 ? worldPort : ANN_PORT;
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName("127.0.0.1"), fwdPort));
                    } else if (src.startsWith("127.")) {
                        // 本机服务器应答 → 转给 5s 内活跃的客户端
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
                    } else if (clientIp != null) {
                        // 本机服务器公告（广播本地投递，源=本机接口地址）。
                        // v596：公告源端口即世界监听 socket 端口——直接学习
                        if (sport > 1024 && worldPort != sport) {
                            worldPort = sport;
                            org.levimc.launcher.util.OnlineDebugLog.log(
                                    "异地桥(服务器): 公告源端口学习为世界端口 " + sport);
                        }
                        // → 转发客户端 19132（客户端监听处收公告显示世界）
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(clientIp), ANN_PORT));
                    }
                } catch (Exception e) {
                    if (hosting) {
                        Log.w(TAG, "代理转发异常", e);
                    }
                }
            }
        }, "lan-relay-fwd");
        fwd.setDaemon(true);
        fwd.start();
    }

    /** 客户端（成员）虚拟 IP：房主从 RoomCenter 成员地址动态取。 */
    private static String getClientIp() {
        try {
            List<InetSocketAddress> members = RoomCenter.getMemberAddresses();
            if (members != null && !members.isEmpty()) {
                return members.get(0).getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static synchronized void stopProxy() {
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception ignored) {
            }
            proxy = null;
        }
        clients.clear();
        worldPort = 0;
    }
}
