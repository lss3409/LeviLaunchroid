package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v588：异地局域网入口桥——合成 pong 公告 + ping/pong 代理。
 *
 * 1.26 发现机制实测：客户端周期向 255.255.255.255:19132 广播带
 * RakNet 魔数的 ping（0x01），服务器单播回 pong（0x1C 含世界名）
 * ——服务器自身不广播任何公告。v584 房主抓到的"公告"其实是成员
 * 客户端广播的 ping，重放回去会被客户端当作自己的包忽略（用户
 * 实测：世界不显示，或显示后验证失败跳到"好友"分类）。
 *
 * 方案：
 * 房主侧 startHost()：周期探测世界随机端口（WorldPortProbe）→
 *   c:lan 同步（端口 + 房主昵称）。
 * 成员侧 onAnnounce()：单个随机端口 socket（绑 VPN 网络）——
 *   ① 周期合成 RakNet pong（0x1C + 时间戳 + 固定 id + 魔数 +
 *   MOTD=房主昵称）广播到 255.255.255.255:19132（源=成员虚拟 IP:
 *   本 socket 端口，客户端把源当世界服务器地址，好友页「局域网」
 *   分类显示世界）；
 *   ② 客户端 ping/连接请求单播到本 socket → 转发房主世界端口
 *   （房主游戏真实应答，pong 里的真实世界名刷新显示）；
 *   ③ 房主回包 → 转回客户端。点入后 RakNet 连接流量双向代理，
 *   联机走 TUN 直达房主世界。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;
    /** 重放/转发间隔（游戏原生公告节奏约 1s）。 */
    private static final long INTERVAL_MS = 1000;

    /** RakNet 魔数：pong 包需回显此 16 字节。 */
    private static final byte[] MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    // ---------------- 房主侧 ----------------

    private static volatile boolean hosting;

    /** 房主开桥：周期探测世界端口并同步给成员（c:lan）。 */
    public static synchronized void startHost() {
        stopHost();
        hosting = true;
        Thread t = new Thread(() -> {
            int lastWp = -1;
            while (hosting) {
                try {
                    int wp = WorldPortProbe.getWorldPort();
                    RoomCenter.sendLanAnnounce(new byte[0], wp);
                    // v589：世界端口变化时打文件日志（定位 c:lan 链路断点）
                    if (wp != lastWp) {
                        lastWp = wp;
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "异地桥(房主): c:lan 已发 wp=" + wp);
                    }
                } catch (Exception e) {
                    if (hosting) {
                        Log.w(TAG, "房主端口同步异常", e);
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

    // ---------------- 成员侧：合成 pong + ping/pong 代理 ----------------

    private static volatile boolean proxying;
    private static DatagramSocket proxy;
    private static volatile String hostIp;
    private static volatile int worldPort;
    private static volatile String hostNick = "PaperConnect";
    /** 客户端地址（源端口）→ 最近活跃时间，房主回包按此路由。 */
    private static final Map<InetSocketAddress, Long> clients =
            new ConcurrentHashMap<>();

    /** 成员收到房主世界端口/昵称（c:lan）：更新并启动代理。 */
    public static synchronized void onAnnounce(byte[] ignoredReply, String host,
                                               int port, String nick) {
        hostIp = host;
        worldPort = port;
        if (nick != null && !nick.isEmpty()) {
            hostNick = nick;
        }
        org.levimc.launcher.util.OnlineDebugLog.log(
                "异地桥(成员): 收到 c:lan host=" + host + " wp=" + port
                        + " nick=" + nick + " proxying=" + proxying);
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
            Log.i(TAG, "异地入口桥(成员): 代理已启动 端口=" + proxy.getLocalPort()
                    + " 房主=" + hostIp + ":" + worldPort);
        } catch (Exception e) {
            Log.w(TAG, "代理启动失败", e);
            proxy = null;
            return;
        }
        proxying = true;

        Thread announce = new Thread(() -> {
            while (proxying && proxy != null && !proxy.isClosed()) {
                byte[] pong = buildPong(hostNick);
                try {
                    proxy.send(new DatagramPacket(pong, pong.length,
                            InetAddress.getByName("255.255.255.255"), ANN_PORT));
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lan-relay-replay");
        announce.setDaemon(true);
        announce.start();

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

    /**
     * 合成 RakNet Unconnected Pong（基岩版局域网公告格式）：
     * 0x1C + 时间戳(8) + serverId(8) + 魔数(16) + MOTD 长度(2 BE) + MOTD。
     */
    private static byte[] buildPong(String motd) {
        byte[] name = (motd == null ? "PaperConnect" : motd)
                .getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[35 + name.length];
        out[0] = 0x1C;
        long t = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            out[1 + i] = (byte) (t >>> (8 * (7 - i)));
        }
        // serverId：固定即可（客户端按它去重）
        out[9] = 0x11;
        out[16] = 0x22;
        System.arraycopy(MAGIC, 0, out, 17, MAGIC.length);
        out[33] = (byte) ((name.length >>> 8) & 0xFF);
        out[34] = (byte) (name.length & 0xFF);
        System.arraycopy(name, 0, out, 35, name.length);
        return out;
    }
}
