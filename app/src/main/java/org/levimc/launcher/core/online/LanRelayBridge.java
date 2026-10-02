package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * v689：回滚 v560 行为——移除代理/隧道/组播注入，仅保留真实世界端口学习。
 *
 * 历史教训：v584 引入的双 socket 对称代理（proxy 绑 VPN 0.0.0.0:19132 +
 * lanSock 绑 WiFi:19132）抢占了 19132 端口，把 v560 的完美链路破坏了——
 * v560 时代：LanBridge 单播公告（房主→成员虚拟 IP:19132）→ 1.26.40 客户端
 * 监听 19132 收公告显示条目 → 客户端 ping 虚拟 IP:19132 → 房主游戏服务器
 * （服务器监听 19132 收 ping）回真 pong → 条目恒久显示 + 点击直连虚拟 IP
 * 不经任何桥（真正的局域网联机，离线不登录账号可用）。
 *
 * 本类现在只做两件事：
 * ① 4445 组播监听学真实世界端口（1.26 端口动态，v683 起）；
 * ② 周期 ping 世界服务器缓存真 pong，经 c:lan 同步成员（深链/联机页用）。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    public static final int ANN_PORT = 19132;

    private static volatile boolean running;
    private static volatile boolean serverSide;
    private static volatile int worldPort;
    private static volatile long worldReadyTs;
    /** 真世界服务器 pong 模板（c:lan 同步成员端用）。 */
    private static volatile byte[] cachedPong;

    /** 基岩版 RakNet 标准 magic（ping/pong 校验字段）。 */
    private static final byte[] MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78};

    private static java.net.MulticastSocket multicastSock;

    private LanRelayBridge() {
    }

    /** 房主开桥（v689：仅端口学习 + c:lan 同步，不再抢占 19132）。 */
    public static synchronized void startHost() {
        stopAll();
        running = true;
        serverSide = true;
        startMulticastListen();
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    // v639：端口学到后延迟 8s 才对外可用（世界加载缓冲）
                    int readyWp = (worldPort > 0
                            && System.currentTimeMillis() - worldReadyTs >= 8000)
                            ? worldPort : 0;
                    // 世界就绪后 ping 本机世界服务器缓存真 pong（c:lan 同步）
                    if (readyWp > 0) {
                        cacheWorldPong();
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
        Log.i(TAG, "世界端口学习已启动（房主，v689 无代理模式）");
    }

    /** 用临时 socket ping 世界服务器并缓存真 pong（不占用 19132）。 */
    private static void cacheWorldPong() {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] ping = new byte[33];
            ping[0] = 0x01;
            long tm = System.currentTimeMillis();
            for (int i = 0; i < 8; i++) {
                ping[1 + i] = (byte) (tm >> (8 * i));
            }
            System.arraycopy(MAGIC, 0, ping, 9, 16);
            s.send(new DatagramPacket(ping, ping.length,
                    InetAddress.getByName("127.0.0.1"), worldPort));
            s.setSoTimeout(1000);
            DatagramPacket p = new DatagramPacket(new byte[512], 512);
            s.receive(p);
            byte[] data = p.getData();
            if (p.getLength() > 17 && (data[0] & 0xFF) == 0x1C) {
                byte[] pong = new byte[p.getLength()];
                System.arraycopy(data, 0, pong, 0, p.getLength());
                cachedPong = pong;
            }
        } catch (Exception ignored) {
        }
    }

    /** 成员（客户端）收到 c:lan（v689：仅缓存 pong 模板，无代理/注入）。 */
    public static synchronized void onAnnounce(byte[] reply, String host,
                                               int port, String nick) {
        if (reply != null && reply.length > 17 && (reply[0] & 0xFF) == 0x1C) {
            cachedPong = reply;
        }
    }

    /**
     * v689：房主监听 224.0.2.60:4445 组播公告学真实世界监听端口
     * （1.26 公告端口动态，v582 抓包结论）。组播包内核复制投递给所有
     * 加入组的 socket，不抢占游戏客户端的 4445 监听。
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

    public static synchronized void stopHost() {
        stopAll();
    }

    /** 桥学到的世界端口（深链邀请兜底用）。 */
    public static int getLearnedWorldPort() {
        return worldPort;
    }

    public static synchronized void stopClient() {
        stopAll();
    }

    private static synchronized void stopAll() {
        running = false;
        if (multicastSock != null) {
            try {
                multicastSock.close();
            } catch (Exception ignored) {
            }
            multicastSock = null;
        }
        worldPort = 0;
        cachedPong = null;
    }
}
