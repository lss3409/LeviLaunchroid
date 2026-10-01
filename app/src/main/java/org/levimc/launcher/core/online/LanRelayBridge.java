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
 * v598：异地局域网入口桥——双端对称代理。
 *
 * 1.26 发现机制实测：
 *   世界服务器周期向 255.255.255.255:19132 广播公告（0x01+魔数 33
 *   字节，源端口=世界监听 socket）；客户端不监听 19132，只周期广播
 *   ping（同格式 0x01）；服务器监听 19132 收 ping 后单播回 pong
 *   （0x1C 含世界名）。
 *
 * 用户场景：平板=房主=世界服务器，vivo=成员=客户端。两端都跑代理
 * （绑 0.0.0.0:19132，VPN 网络，本地广播投递拦到本机游戏包）：
 *
 * 服务器端（房主，serverSide=true）：
 *   拦本机公告广播 → 学习源端口=世界端口 → 转客户端 19132；
 *   客户端 ping/连接（src=对端）→ 转 127.0.0.1:19132/世界端口；
 *   本机服务器应答（127.x）→ 转回对端 19132。
 *
 * 客户端端（成员，serverSide=false）：
 *   拦本机客户端 ping 广播 → 转服务器 19132；
 *   服务器应答（src=对端）→ 转本机客户端源（clients）；
 *   公告（src=对端 0x01）→ 转 127.0.0.1:19132（本机客户端监听处）。
 *
 * 连接建立后（0x80+ 帧流）：客户端直发服务器虚拟 IP:19132（经服务器
 * 代理转世界端口），服务器应答原路返回（经客户端代理转客户端源）——
 * 方向由两端角色自然区分，无需拆解帧内容。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;

    private static volatile boolean running;
    private static DatagramSocket proxy;
    private static volatile boolean serverSide;
    private static volatile String peerIp;
    private static volatile int worldPort;
    /** 本机客户端源地址 → 最近活跃时间（应答回路由）。 */
    private static final Map<InetSocketAddress, Long> clients =
            new ConcurrentHashMap<>();
    /** v636：真世界服务器 pong 模板（房主缓存→c:lan 同步→成员秒回合成）。 */
    private static volatile byte[] cachedPong;
    /** 基岩版 RakNet 标准 magic（ping/pong 校验字段）。 */
    private static final byte[] MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78};

    /** 房主（世界服务器）开桥：启动服务器侧代理 + 周期 c:lan。 */
    public static synchronized void startHost() {
        stopAll();
        running = true;
        serverSide = true;
        startProxy();
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    // v636：世界端口学到后主动 ping 本机世界服务器，缓存真
                    // pong（含服务器 GUID/世界名），经 c:lan 同步给成员端——
                    // 成员收到客户端 ping 时用模板秒回合成 pong（真 pong 穿
                    // 隧道延迟超客户端等待超时，条目永远不显示——Astral 同款
                    // 方案：真 pong 缓存 + 成员端秒回）
                    if (worldPort > 0 && proxy != null && !proxy.isClosed()) {
                        byte[] ping = new byte[33];
                        ping[0] = 0x01;
                        long tm = System.currentTimeMillis();
                        for (int i = 0; i < 8; i++) {
                            ping[1 + i] = (byte) (tm >> (8 * i));
                        }
                        System.arraycopy(MAGIC, 0, ping, 9, 16);
                        // client GUID 8 字节留零
                        proxy.send(new DatagramPacket(ping, ping.length,
                                InetAddress.getByName("127.0.0.1"), worldPort));
                    }
                    RoomCenter.sendLanAnnounce(
                            cachedPong != null ? cachedPong : new byte[0], worldPort);
                } catch (Exception e) {
                    if (running) {
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

    /** 成员（客户端）收到 c:lan：启动客户端侧代理 + 缓存 pong 模板。 */
    public static synchronized void onAnnounce(byte[] reply, String host,
                                               int port, String nick) {
        peerIp = host;
        // v636：reply = 房主缓存的世界服务器真 pong（0x1C 开头）
        if (reply != null && reply.length > 17 && (reply[0] & 0xFF) == 0x1C) {
            cachedPong = reply;
        }
        if (!running) {
            running = true;
            serverSide = false;
            startProxy();
            Log.i(TAG, "异地入口桥已启动（成员/客户端）房主=" + host);
        }
    }

    public static synchronized void stopHost() {
        stopAll();
    }

    /** v599：桥学到的世界端口（公告源端口）——深链邀请兜底用
     * （平板 SELinux 拒读端口表，WorldPortProbe 恒 0）。 */
    public static int getLearnedWorldPort() {
        return worldPort;
    }

    public static synchronized void stopClient() {
        stopAll();
    }

    private static synchronized void stopAll() {
        running = false;
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception ignored) {
            }
            proxy = null;
        }
        clients.clear();
        worldPort = 0;
        peerIp = null;
        cachedPong = null;
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
            Log.i(TAG, "异地入口桥代理已启动 serverSide=" + serverSide
                    + " 端口=" + proxy.getLocalPort());
        } catch (Exception e) {
            Log.w(TAG, "代理启动失败", e);
            proxy = null;
            return;
        }

        Thread fwd = new Thread(() -> {
            byte[] buf = new byte[2048];
            long lastLogTs = 0;
            while (running && proxy != null && !proxy.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    proxy.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(buf, 0, data, 0, p.getLength());
                    String src = p.getAddress().getHostAddress();
                    int sport = p.getPort();
                    long nowTs = System.currentTimeMillis();
                    if (nowTs - lastLogTs > 3000) {
                        lastLogTs = nowTs;
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "桥收包: from " + src + ":" + sport + " len=" + data.length
                                        + " head=" + String.format(java.util.Locale.US, "%02x",
                                                data.length > 0 ? data[0] : -1)
                                        + " wp=" + worldPort + " side=" + (serverSide ? "S" : "C"));
                    }
                    String peer = peerIp != null ? peerIp : resolvePeer();
                    boolean fromPeer = peer != null && src.equals(peer);
                    int head = data.length > 0 ? (data[0] & 0xFF) : -1;
                    if (fromPeer) {
                        if (head == 0x01) {
                            // 对端 ping/公告 → 本机 19132（服务器应答 ping，
                            // 客户端监听处收公告显示世界）
                            proxy.send(new DatagramPacket(data, data.length,
                                    InetAddress.getByName("127.0.0.1"), ANN_PORT));
                        } else if (serverSide) {
                            // 客户端流量 → 本机服务器世界端口
                            int fwdPort = worldPort > 0 ? worldPort : ANN_PORT;
                            proxy.send(new DatagramPacket(data, data.length,
                                    InetAddress.getByName("127.0.0.1"), fwdPort));
                        } else {
                            // 服务器应答 → 本机客户端源
                            sendToClients(data);
                        }
                    } else if (src.startsWith("127.")) {
                        // v636：本机世界服务器真 pong → 缓存模板（成员端秒回用）
                        if (serverSide && head == 0x1C) {
                            cachedPong = data;
                            org.levimc.launcher.util.OnlineDebugLog.log(
                                    "异地桥(服务器): 缓存世界 pong " + data.length
                                            + " 字节（c:lan 同步成员端）");
                        }
                        // 本机服务器应答 → 对端 19132
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(peer), ANN_PORT));
                    } else if (peer != null) {
                        // 本机游戏广播/流量（本地投递）：
                        // 服务器端=公告（学习世界端口）；客户端端=ping/数据
                        if (serverSide && head == 0x01 && sport > 1024
                                && worldPort != sport) {
                            worldPort = sport;
                            org.levimc.launcher.util.OnlineDebugLog.log(
                                    "异地桥(服务器): 公告源端口学习为世界端口 " + sport);
                        }
                        clients.put(new InetSocketAddress(src, sport),
                                System.currentTimeMillis());
                        // v636：成员端收到本机客户端 ping 且已有 pong 模板——
                        // 秒回合成 pong（替换 magic 为 ping 里的），游戏好友页
                        // 立即显示局域网世界条目；ping 仍照常转发房主双保险
                        if (!serverSide && head == 0x01 && cachedPong != null) {
                            byte[] pong = cachedPong.clone();
                            if (data.length >= 25) {
                                // pong magic 在 offset 17（0x1C+time8+GUID8）
                                System.arraycopy(data, 9, pong, 17, 16);
                            }
                            proxy.send(new DatagramPacket(pong, pong.length,
                                    InetAddress.getByName(src), sport));
                            org.levimc.launcher.util.OnlineDebugLog.log(
                                    "异地桥(成员): 秒回合成 pong → " + src + ":" + sport);
                        }
                        proxy.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(peer), ANN_PORT));
                    }
                } catch (Exception e) {
                    if (running) {
                        Log.w(TAG, "代理转发异常", e);
                    }
                }
            }
        }, "lan-relay-fwd");
        fwd.setDaemon(true);
        fwd.start();
    }

    /** 房主侧对端 = 成员虚拟 IP（RoomCenter 动态取）。 */
    private static String resolvePeer() {
        try {
            List<InetSocketAddress> members = RoomCenter.getMemberAddresses();
            if (members != null && !members.isEmpty()) {
                return members.get(0).getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 转给 5s 内活跃的本机客户端。 */
    private static void sendToClients(byte[] data) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<InetSocketAddress, Long>> it = clients.entrySet().iterator();
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
    }
}
