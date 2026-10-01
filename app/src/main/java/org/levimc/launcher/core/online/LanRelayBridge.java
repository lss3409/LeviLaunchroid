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
 * v586：异地局域网入口桥——公告重放 + ping/pong 代理。
 *
 * 实测 1.26 发现机制：客户端周期向 255.255.255.255:19132 广播带
 * RakNet 魔数的包（0x01 开头，33 字节），收到包即在好友页「局域网」
 * 分类显示世界；随后客户端会 ping 包源地址做存活验证并索取世界名，
 * 没人应答世界就消失（v584 用户实测：先显示「局域网世界」随后
 * 变成「好友的世界」——验证失败被移除 + XBL 好友服务补位）。
 * mDNS 无关（5353 只抓到 adb 服务记录）。
 *
 * 方案：
 * 房主侧 startHost()：监听 19132 截获本机游戏公告包（魔数过滤）→
 *   c:lan 周期转发（数据 + 探测到的世界随机端口）给成员。
 * 成员侧 onAnnounce()：单个随机端口 socket（绑 VPN 网络）——
 *   ① 周期把公告包重放到 255.255.255.255:19132（源=成员虚拟 IP:本
 *   socket 端口，客户端把源当世界服务器地址）；
 *   ② 客户端 ping/连接请求单播到本 socket → 转发房主世界端口
 *   （房主游戏真实应答）；
 *   ③ 房主回包（pong/MOTD/连接握手）→ 转回客户端。
 * 客户端验证通过 → 世界稳定留在「局域网」分类，点入后 RakNet
 * 连接流量同样双向代理，联机走 TUN 直达房主世界。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;
    /** 重放/转发间隔（游戏原生公告节奏约 1s）。 */
    private static final long INTERVAL_MS = 1000;

    /** RakNet 魔数：包内含此 16 字节即游戏局域网包。 */
    private static final byte[] MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    // ---------------- 房主侧 ----------------

    private static volatile boolean hosting;
    private static volatile byte[] lastReply;

    /** 房主开桥：抓本机游戏公告 → c:lan 周期转发。 */
    public static synchronized void startHost() {
        stopHost();
        hosting = true;
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("0.0.0.0", ANN_PORT));
                s.setSoTimeout(4000);
                byte[] buf = new byte[2048];
                while (hosting) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (java.net.SocketTimeoutException e) {
                        // 没抓到新包也周期兜底：端口可能已变，继续同步
                        pushToMembers();
                        continue;
                    }
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(buf, 0, data, 0, p.getLength());
                    if (data.length >= 17 && data[0] == 0x01 && hasMagic(data)) {
                        lastReply = data;
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "异地入口桥(房主): 抓到游戏公告 len=" + data.length
                                        + " from " + p.getAddress().getHostAddress()
                                        + ":" + p.getPort());
                        pushToMembers();
                    }
                }
            } catch (Exception e) {
                if (hosting) {
                    Log.w(TAG, "房主抓公告异常", e);
                }
            }
        }, "lan-relay-host");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "异地入口桥已启动（房主）");
    }

    /** 把最新公告包 + 世界端口发给全体成员（c:lan）。 */
    private static void pushToMembers() {
        byte[] data = lastReply;
        if (data == null) {
            data = buildTemplatePing();
        }
        RoomCenter.sendLanAnnounce(data, WorldPortProbe.getWorldPort());
    }

    /** 抓不到真实公告时的模板包：0x01 + 时间戳 + 魔数 + 固定 id（33 字节）。 */
    private static byte[] buildTemplatePing() {
        byte[] t = new byte[33];
        t[0] = 0x01;
        System.arraycopy(MAGIC, 0, t, 9, MAGIC.length);
        t[25] = 0x11; // 固定 guid 尾巴，客户端按魔数识别即可
        return t;
    }

    public static synchronized void stopHost() {
        hosting = false;
        lastReply = null;
    }

    // ---------------- 成员侧：重放 + ping/pong 代理 ----------------

    private static volatile boolean proxying;
    private static DatagramSocket proxy;
    private static volatile String hostIp;
    private static volatile int worldPort;
    /** 客户端地址（源端口）→ 最近活跃时间，房主回包按此路由。 */
    private static final Map<InetSocketAddress, Long> clients =
            new ConcurrentHashMap<>();

    /** 成员收到房主公告（c:lan）：更新数据并启动代理。 */
    public static synchronized void onAnnounce(byte[] reply, String host, int port) {
        if (reply != null && reply.length >= 17) {
            lastReply = reply;
        }
        hostIp = host;
        worldPort = port;
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
            proxy.bind(new InetSocketAddress("0.0.0.0", 0)); // 随机端口，避开游戏 19132
            proxy.setBroadcast(true);
            Log.i(TAG, "异地入口桥(成员): 代理已启动 端口=" + proxy.getLocalPort());
        } catch (Exception e) {
            Log.w(TAG, "代理启动失败", e);
            proxy = null;
            return;
        }
        proxying = true;

        Thread replay = new Thread(() -> {
            while (proxying && proxy != null && !proxy.isClosed()) {
                byte[] data = lastReply;
                if (data != null) {
                    try {
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName("255.255.255.255"), ANN_PORT));
                    } catch (Exception ignored) {
                    }
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lan-relay-replay");
        replay.setDaemon(true);
        replay.start();

        Thread fwd = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (proxying && proxy != null && !proxy.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    proxy.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(buf, 0, data, 0, p.getLength());
                    String src = p.getAddress().getHostAddress();
                    int sport = p.getPort();
                    boolean fromHost = hostIp != null && worldPort > 0
                            && src.equals(hostIp) && sport == worldPort;
                    if (fromHost) {
                        // 房主应答 → 转给 5s 内活跃的客户端
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
                    } else if (hostIp != null && worldPort > 0 && data.length >= 1) {
                        // 客户端流量 → 记录并转发房主世界端口
                        clients.put(new InetSocketAddress(src, sport),
                                System.currentTimeMillis());
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(hostIp), worldPort));
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

    /** 包内是否含 RakNet 魔数。 */
    private static boolean hasMagic(byte[] data) {
        outer:
        for (int i = 0; i + MAGIC.length <= data.length; i++) {
            for (int j = 0; j < MAGIC.length; j++) {
                if (data[i + j] != MAGIC[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
