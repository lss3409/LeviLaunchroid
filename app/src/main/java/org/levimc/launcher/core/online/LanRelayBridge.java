package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v637：异地局域网入口桥——双 socket 对称代理（Astral 同款架构）。
 *
 * 1.26 发现机制实测：
 *   世界服务器周期向 255.255.255.255:19132 广播公告（0x01+魔数 33
 *   字节，源端口=世界监听 socket）；客户端不监听 19132，只周期广播
 *   ping（同格式 0x01）；服务器监听 19132 收 ping 后单播回 pong。
 *
 * 用户场景：平板=房主=世界服务器，vivo=成员=客户端。
 *
 * 每端两个 socket：
 *   proxy（绑 0.0.0.0:19132 + VPN 网络）：收广播 ping / 隧道收发。
 *   lanSock（绑本机 WiFi IP:19132，不走 VPN）：冒充局域网服务器——
 *     对客户端秒回合成 pong（源 IP=WiFi IP → 条目地址=WiFi IP），
 *     收客户端连接流量；返回流量也从它发回客户端。
 * 虚拟网 IP 上的回环会被 EasyTier 内核 smoltcp 吞掉（v636 实测连接
 * 流量丢失根因），因此客户端侧全部走 WiFi 本机地址，不进 TUN。
 *
 * 房主侧（serverSide=true）：
 *   lanSock/proxy 拦本机公告广播 → 学习源端口=世界端口 → 隧道转发；
 *   隧道来的客户端连接 → 转 127.0.0.1:世界端口；本机服务器应答 →
 *   隧道回对端。周期 ping 本机世界服务器缓存真 pong → c:lan 同步。
 *
 * 成员侧（serverSide=false）：
 *   客户端广播 ping（proxy 收）→ 秒回合成 pong（lanSock 发，源=WiFi
 *   IP）；客户端连接 WiFi IP:19132（lanSock 收）→ 隧道转房主；
 *   隧道来的服务器应答 → lanSock 发回客户端源。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;

    private static volatile boolean running;
    private static DatagramSocket proxy;   // VPN 网络 socket（隧道侧）
    private static DatagramSocket lanSock; // WiFi 本机 socket（客户端侧）
    private static volatile boolean serverSide;
    private static volatile String peerIp;
    private static volatile int worldPort;
    private static String lanIp;
    /** 本机客户端源地址 → 最近活跃时间（应答回路由）。 */
    private static final Map<InetSocketAddress, Long> clients =
            new ConcurrentHashMap<>();
    /** v636：真世界服务器 pong 模板（房主缓存→c:lan 同步→成员秒回合成）。 */
    private static volatile byte[] cachedPong;
    /** v637：同网原生发现检测（本机接口外来的 0x01 公告=同网有真服务器，
     * 禁用合成 pong 防重复条目——同网原生发现本来就该工作）。 */
    private static volatile boolean sameLan;
    /** v639：世界端口学到的时刻（世界加载完成后端口才对外可用——用户
     * 实测"深链端口给太早，存档还没进去端口就出来了"）。 */
    private static volatile long worldReadyTs;
    /** 基岩版 RakNet 标准 magic（ping/pong 校验字段）。 */
    private static final byte[] MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78};

    /** 房主（世界服务器）开桥：启动双 socket 代理 + 周期缓存 pong/c:lan。 */
    public static synchronized void startHost() {
        stopAll();
        running = true;
        serverSide = true;
        startProxy();
        startMulticastListen();
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    // v639：端口学到后延迟 8s 才对外可用（世界加载缓冲——
                    // 用户实测"端口给太早，存档还没进去端口就出来了"）
                    int readyWp = (worldPort > 0
                            && System.currentTimeMillis() - worldReadyTs >= 8000)
                            ? worldPort : 0;
                    // v636：世界就绪后主动 ping 本机世界服务器，缓存真 pong
                    //（含服务器 GUID），经 c:lan 同步成员端——成员秒回合成
                    // pong，条目立即显示（真 pong 穿隧道超客户端超时）
                    if (readyWp > 0 && proxy != null && !proxy.isClosed()) {
                        byte[] ping = new byte[33];
                        ping[0] = 0x01;
                        long tm = System.currentTimeMillis();
                        for (int i = 0; i < 8; i++) {
                            ping[1 + i] = (byte) (tm >> (8 * i));
                        }
                        System.arraycopy(MAGIC, 0, ping, 9, 16);
                        proxy.send(new DatagramPacket(ping, ping.length,
                                InetAddress.getByName("127.0.0.1"), worldPort));
                    }
                    RoomCenter.sendLanAnnounce(
                            cachedPong != null ? cachedPong : new byte[0], readyWp);
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

    private static java.net.MulticastSocket multicastSock;

    /**
     * v683：房主监听 224.0.2.60:4445 组播公告（1.26 世界服务器的公告走组播
     * 而非 19132 广播——v617 组播走真实网络，之前只监听 19132 导致真实世界
     * 端口学不到）。从公告源端口学真实世界监听端口，供隧道转发（fwdPort）。
     * 学不到也不影响显示（显示走 LanBridge 单播 MOTD + 合成 pong 兜底）。
     */
    private static void startMulticastListen() {
        Thread t = new Thread(() -> {
            try {
                java.net.MulticastSocket ms = new java.net.MulticastSocket(null);
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(4445));
                ms.joinGroup(InetAddress.getByName("224.0.2.60"));
                ms.setSoTimeout(3000);
                multicastSock = ms;
                org.levimc.launcher.util.OnlineDebugLog.log(
                        "异地桥(服务器): 4445 组播监听已启动（学习真实世界端口）");
                byte[] buf = new byte[256];
                while (running && !ms.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ms.receive(p);
                        byte[] data = p.getData();
                        if (p.getLength() >= 25 && (data[0] & 0xFF) == 0x01
                                && p.getPort() > 1024) {
                            boolean magicOk = true;
                            for (int i = 0; i < 16; i++) {
                                if (data[9 + i] != MAGIC[i]) {
                                    magicOk = false;
                                    break;
                                }
                            }
                            if (magicOk && worldPort != p.getPort()) {
                                worldPort = p.getPort();
                                worldReadyTs = System.currentTimeMillis();
                                org.levimc.launcher.util.OnlineDebugLog.log(
                                        "异地桥(服务器): 组播公告学到世界端口 " + worldPort);
                            }
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "4445 组播监听异常", e);
                }
            }
        }, "lan-mcast-4445");
        t.setDaemon(true);
        t.start();
    }

    /** 成员（客户端）收到 c:lan：启动代理 + 缓存 pong 模板 + 4445 组播注入。 */
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
        // v638：房主世界就绪（wp>0 且真 pong 已缓存=服务器可应答）→
        // 成员端注入 4445 组播公告（Astral multicast.rs 同款：游戏客户端
        // 原生消费 224.0.2.60:4445 的 [MOTD]/[AD] 公告显示局域网条目——
        // v618 平板刷屏实锤 1.26.45 客户端监听该组播；注意 1.26.40 客户端
        // 不监听——vivo 版本差异）。AD 固定 19132=本机桥端口，客户端点
        // 条目连本机 WiFi IP:19132 → lanSock → 隧道 → 房主世界。
        // v639：pong 缓存就绪才注入（世界可应答，防端口过早）。
        if (port > 0 && cachedPong != null) {
            startMulticastInject();
        }
    }

    private static volatile boolean injecting;
    private static java.net.MulticastSocket injectSock;

    /** v638：成员端 4445 组播公告注入（源=本机 WiFi IP，客户端条目指向本机桥）。 */
    private static synchronized void startMulticastInject() {
        if (injecting) {
            return;
        }
        injecting = true;
        Thread t = new Thread(() -> {
            try {
                injectSock = new java.net.MulticastSocket(null);
                injectSock.setReuseAddress(true);
                if (lanIp != null) {
                    injectSock.bind(new InetSocketAddress(lanIp, 0));
                } else {
                    injectSock.bind(new InetSocketAddress(0));
                }
                injectSock.setLoopbackMode(true); // 防本机发现器吃回注入（风暴）
                injectSock.setTimeToLive(1);
                InetAddress group = InetAddress.getByName("224.0.2.60");
                byte[] data = ("[MOTD]PaperConnect 房主世界[/MOTD][AD]19132[/AD]")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                org.levimc.launcher.util.OnlineDebugLog.log(
                        "异地桥(成员): 4445 组播公告注入已启动（AD=19132）");
                while (injecting && injectSock != null && !injectSock.isClosed()) {
                    injectSock.send(new DatagramPacket(data, data.length, group, 4445));
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "4445 注入异常", e);
            }
        }, "lan-inject-4445");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stopHost() {
        stopAll();
    }

    /** v599：桥学到的世界端口（公告源端口）——深链邀请兜底用。 */
    public static int getLearnedWorldPort() {
        return worldPort;
    }

    public static synchronized void stopClient() {
        stopAll();
    }

    private static synchronized void stopAll() {
        running = false;
        injecting = false;
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception ignored) {
            }
            proxy = null;
        }
        if (lanSock != null) {
            try {
                lanSock.close();
            } catch (Exception ignored) {
            }
            lanSock = null;
        }
        if (injectSock != null) {
            try {
                injectSock.close();
            } catch (Exception ignored) {
            }
            injectSock = null;
        }
        if (multicastSock != null) {
            try {
                multicastSock.close();
            } catch (Exception ignored) {
            }
            multicastSock = null;
        }
        clients.clear();
        worldPort = 0;
        peerIp = null;
        cachedPong = null;
        sameLan = false;
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
        } catch (Exception e) {
            Log.w(TAG, "代理启动失败", e);
            proxy = null;
            return;
        }
        // v637：WiFi 本机 socket（冒充局域网服务器，不回环经 TUN）
        lanIp = wifiIp();
        if (lanIp != null) {
            try {
                lanSock = new DatagramSocket(null);
                lanSock.setReuseAddress(true);
                lanSock.bind(new InetSocketAddress(lanIp, ANN_PORT));
                lanSock.setBroadcast(true);
            } catch (Exception e) {
                Log.w(TAG, "局域网 socket 绑定失败 " + lanIp, e);
                lanSock = null;
            }
        }
        Log.i(TAG, "异地入口桥代理已启动 serverSide=" + serverSide
                + " lanIp=" + lanIp + " 端口=" + ANN_PORT);

        startReceiveLoop(proxy, false);
        if (lanSock != null) {
            startReceiveLoop(lanSock, true);
        }
    }

    /** 接收循环：proxy（隧道/广播侧）与 lanSock（本机客户端侧）共用处理。 */
    private static void startReceiveLoop(final DatagramSocket sock, final boolean fromLan) {
        Thread fwd = new Thread(() -> {
            byte[] buf = new byte[2048];
            long lastLogTs = 0;
            while (running && sock != null && !sock.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(buf, 0, data, 0, p.getLength());
                    handlePacket(sock, fromLan, data, p.getAddress().getHostAddress(),
                            p.getPort(), lastLogTs);
                } catch (Exception e) {
                    if (running) {
                        Log.w(TAG, "代理转发异常", e);
                    }
                }
            }
        }, fromLan ? "lan-relay-lan" : "lan-relay-fwd");
        fwd.setDaemon(true);
        fwd.start();
    }

    private static void handlePacket(DatagramSocket sock, boolean fromLan, byte[] data,
                                     String src, int sport, long lastLogTs) {
        long nowTs = System.currentTimeMillis();
        if (nowTs - lastLogTs > 3000) {
            org.levimc.launcher.util.OnlineDebugLog.log(
                    "桥收包: from " + src + ":" + sport + " len=" + data.length
                            + " head=" + String.format(java.util.Locale.US, "%02x",
                            data.length > 0 ? data[0] : -1)
                            + " wp=" + worldPort + " side=" + (serverSide ? "S" : "C")
                            + (fromLan ? " lan" : ""));
        }
        String peer = peerIp != null ? peerIp : resolvePeer();
        boolean fromPeer = peer != null && src.equals(peer);
        int head = data.length > 0 ? (data[0] & 0xFF) : -1;
        try {
            if (fromPeer) {
                if (head == 0x01) {
                    // v686：成员客户端 ping（条目地址=房主虚拟 IP，ping 走隧道
                    // 到此）。只回合成 pong（固定 GUID）——不转发真实世界端口：
                    // 真 pong 带房主真 GUID，客户端会把它合并进 Xbox 好友世界
                    // （条目被 Xbox 路线吞掉的根因）。
                    byte[] pong = buildPongReply(data);
                    if (pong != null) {
                        proxy.send(new DatagramPacket(pong, pong.length,
                                InetAddress.getByName(src), sport));
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "异地桥(服务器): 回合成 pong → " + src + ":" + sport);
                    }
                } else if (serverSide) {
                    // 客户端连接流量 → 本机服务器世界端口
                    int fwdPort = worldPort > 0 ? worldPort : ANN_PORT;
                    proxy.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName("127.0.0.1"), fwdPort));
                } else {
                    // 服务器应答 → 本机客户端源（lanSock 发，源=WiFi IP）
                    sendToClients(data);
                }
            } else if (src.startsWith("127.")) {
                // 本机世界服务器真 pong → 缓存模板（成员端秒回用）
                if (serverSide && head == 0x1C) {
                    cachedPong = data;
                    org.levimc.launcher.util.OnlineDebugLog.log(
                            "异地桥(服务器): 缓存世界 pong " + data.length
                                    + " 字节（c:lan 同步成员端）");
                }
                // 本机服务器应答 → 对端 19132（隧道）
                proxy.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName(peer), ANN_PORT));
            } else {
                // 本机游戏广播/流量：服务器端=公告（学习世界端口）；
                // 客户端端=ping（合成秒回）/连接（lanSock 收）
                // v683：排除虚拟网段源（成员 ping 经隧道源端口 19132 曾被
                // 误学为世界端口）；真实端口由 4445 组播监听学习
                if (serverSide && head == 0x01 && sport > 1024
                        && !isVirtualIp(src) && worldPort != sport) {
                    worldPort = sport;
                    worldReadyTs = System.currentTimeMillis(); // v639：就绪计时
                    org.levimc.launcher.util.OnlineDebugLog.log(
                            "异地桥(服务器): 公告源端口学习为世界端口 " + sport);
                }
                clients.put(new InetSocketAddress(src, sport),
                        System.currentTimeMillis());
                // v637：同网原生发现检测——本机接口之外的设备发 0x01
                //（同网真世界服务器公告）→ 同网环境，禁用合成 pong
                //（原生发现本来就能显示条目，合成只会重复）
                if (!serverSide && head == 0x01 && !isLocalIp(src)) {
                    if (!sameLan) {
                        sameLan = true;
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "异地桥(成员): 检测到同网公告 " + src + "，禁用合成 pong");
                    }
                }
                // v636：成员端秒回合成 pong（lanSock 发→源 IP=WiFi IP，
                // 客户端条目地址=WiFi IP，点连接走 lanSock 不经虚拟网）
                // v683（持久化）：cachedPong 缺失时用合成 pong 模板——
                // 客户端 ping 总有应答，条目不再因无应答而消失；
                // pong 内 AD 端口统一改写为 19132（本机桥端口），客户端
                // 连接 WiFi IP:19132 → lanSock → 隧道 → 房主世界
                if (!serverSide && !sameLan && head == 0x01) {
                    byte[] pong = buildPongReply(data);
                    if (pong != null) {
                        sendLocal(pong, src, sport);
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "异地桥(成员): 秒回合成 pong → " + src + ":" + sport);
                    }
                }
                // 转发对端（隧道侧）
                proxy.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName(peer), ANN_PORT));
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "桥转发异常", e);
            }
        }
    }

    /** v683：虚拟网段判断（EasyTier 隧道源，不可作为世界端口学习源）。 */
    private static boolean isVirtualIp(String ip) {
        return ip.startsWith("10.144.") || ip.startsWith("100.")
                || ip.startsWith("10.0.") || ip.startsWith("10.10.");
    }

    /**
     * v687（v686 撤销）：回 v683 真 GUID 逻辑——客户端连接时校验服务器 GUID，
     * 假 GUID 导致"进不去"（v686 实测）。cachedPong 可用时以其为模板
     * （真 GUID，连接校验通过）+ 改写 AD 端口为 19132 本机桥端口；
     * 缺失时合成模板（固定 GUID——仅作显示兜底，cachedPong 就绪后即被替代）。
     */
    private static byte[] buildPongReply(byte[] ping) {
        try {
            byte[] base;
            if (cachedPong != null && cachedPong.length > 17
                    && (cachedPong[0] & 0xFF) == 0x1C) {
                base = cachedPong.clone();
                if (ping != null && ping.length >= 25) {
                    // pong magic 在 offset 17（0x1C+time8+GUID8），
                    // 用 ping 里的 magic 回填（RakNet 校验）
                    System.arraycopy(ping, 9, base, 17, 16);
                }
                base = rewritePongPorts(base, ANN_PORT);
            } else {
                String ad = "MCPE;PaperConnect 房间;" + 776 + ";1.26.45;1;8;1145141919;"
                        + "PaperConnect 联机;1;1;" + ANN_PORT + ";" + ANN_PORT + ";";
                byte[] adBytes = ad.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                base = new byte[33 + adBytes.length];
                base[0] = 0x1C;
                long tm = System.currentTimeMillis();
                for (int i = 0; i < 8; i++) {
                    base[1 + i] = (byte) (tm >> (8 * i));
                }
                for (int i = 0; i < 8; i++) {
                    base[9 + i] = 0x42;
                }
                if (ping != null && ping.length >= 25) {
                    System.arraycopy(ping, 9, base, 17, 16);
                } else {
                    System.arraycopy(MAGIC, 0, base, 17, 16);
                }
                System.arraycopy(adBytes, 0, base, 33, adBytes.length);
            }
            return base;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v683：改写 pong 的 AD 端口（portV4/portV6 → 本机桥端口）。
     * pong 结构：0x1C + time8 + GUID8 + AD 字符串（UTF-8）。
     */
    private static byte[] rewritePongPorts(byte[] pong, int newPort) {
        try {
            if (pong.length <= 17) {
                return pong;
            }
            String ad = new String(pong, 17, pong.length - 17,
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = ad.split(";", -1);
            if (parts.length >= 12) {
                parts[parts.length - 3] = String.valueOf(newPort); // portV4
                parts[parts.length - 2] = String.valueOf(newPort); // portV6
            } else {
                return pong;
            }
            String newAd = String.join(";", parts);
            byte[] adBytes = newAd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] out = new byte[17 + adBytes.length];
            System.arraycopy(pong, 0, out, 0, 17);
            System.arraycopy(adBytes, 0, out, 17, adBytes.length);
            return out;
        } catch (Exception e) {
            return pong;
        }
    }

    /** lanSock 发回本机客户端（源 IP=WiFi IP，模拟局域网服务器）。 */
    private static void sendLocal(byte[] data, String dst, int dport) {
        if (lanSock == null || lanSock.isClosed()) {
            return;
        }
        try {
            lanSock.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName(dst), dport));
        } catch (Exception ignored) {
        }
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

    /** 转给 5s 内活跃的本机客户端（lanSock 发，源=WiFi IP）。 */
    private static void sendToClients(byte[] data) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<InetSocketAddress, Long>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InetSocketAddress, Long> e = it.next();
            if (now - e.getValue() > 5000) {
                it.remove();
                continue;
            }
            sendLocal(data, e.getKey().getAddress().getHostAddress(), e.getKey().getPort());
        }
    }

    /** 本机 WiFi 网段 IPv4（非回环非虚拟）。 */
    private static String wifiIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || a.isSiteLocalAddress()) {
                        byte[] b = a.getAddress();
                        if (b.length == 4 && !a.isLoopbackAddress()) {
                            // 排除 EasyTier 虚拟网段 10.144.x
                            if ((b[0] & 0xFF) == 10 && (b[1] & 0xFF) == 144) {
                                continue;
                            }
                            return a.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 判断 IP 是否本机接口地址（用于区分本机客户端 ping 与同网设备公告）。 */
    private static boolean isLocalIp(String ip) {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    if (addrs.nextElement().getHostAddress().equals(ip)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
