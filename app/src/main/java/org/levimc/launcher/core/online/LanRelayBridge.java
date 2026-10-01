package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v584：异地局域网入口桥（1.26 新发现机制适配）。
 *
 * 1.26 的局域网"公告"= 服务器周期向广播/组播 19132 发送的 RakNet
 * Open Connection Reply 1 包（无 MOTD；抓包实测：33 字节，含
 * 00ffff00fefefefefdfdfdfd12345678 魔数与服务器 GUID）。异地时该
 * 广播穿不过 EasyTier 虚拟网。
 *
 * 桥接方案：
 *  房主端：监听 19132 组播/广播，截获本机游戏的 Reply 包 → 经
 *          RoomCenter c:lan 协议转发给成员（含世界端口）。
 *  成员端：本地转发器 bind 随机端口 P（绑 VPN 网络）→ 周期把
 *          Reply 包重放到本机广播 255.255.255.255:19132（源端口
 *          天然为 P）→ 成员游戏好友页显示世界；客户端点入后连
 *          P，转发器把 RakNet 流量双向转发到房主虚拟 IP:世界端口。
 */
public final class LanRelayBridge {

    private static final String TAG = "LanRelayBridge";
    private static final int ANN_PORT = 19132;

    private static volatile boolean running;
    private static DatagramSocket relaySocket; // 成员侧转发器 socket
    private static volatile InetSocketAddress clientAddr; // 成员侧：客户端源地址
    private static volatile String hostIp;      // 成员侧：房主虚拟 IP
    private static volatile int worldPort;      // 成员侧：房主世界端口
    private static volatile byte[] lastReply;   // 房主侧：最新 Reply 包

    private LanRelayBridge() {
    }

    // ---------------- 房主侧：抓游戏广播 → 经 RoomCenter 转发 ----------------

    /** 房主启动：监听 19132 截获本机游戏 Reply 广播。 */
    public static synchronized void startHost() {
        stopHost();
        running = true;
        Thread t = new Thread(() -> {
            MulticastSocket ms = null;
            DatagramSocket bs = null;
            try {
                ms = new MulticastSocket(ANN_PORT);
                ms.setReuseAddress(true);
                try {
                    ms.joinGroup(InetAddress.getByName("224.0.2.60"));
                } catch (Exception ignored) {
                }
                ms.setSoTimeout(2000);
                try {
                    bs = new DatagramSocket(null);
                    bs.setReuseAddress(true);
                    bs.bind(new InetSocketAddress("0.0.0.0", ANN_PORT));
                    bs.setSoTimeout(2000);
                } catch (Exception ignored) {
                }
                byte[] buf = new byte[512];
                AtomicLong lastForward = new AtomicLong();
                while (running) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            ms.receive(p);
                        } catch (java.net.SocketTimeoutException e) {
                            if (bs == null) {
                                continue;
                            }
                            bs.receive(p);
                        }
                        byte[] data = new byte[p.getLength()];
                        System.arraycopy(buf, 0, data, 0, p.getLength());
                        // 只转发 RakNet Reply 1（0x01 开头 + 魔数），防刷屏
                        if (data.length < 30 || data[0] != 0x01 || !hasRakNetMagic(data)) {
                            continue;
                        }
                        lastReply = data;
                        long now = System.currentTimeMillis();
                        if (now - lastForward.get() > 800) {
                            lastForward.set(now);
                            RoomCenter.sendLanAnnounce(data, WorldPortProbe.getWorldPort());
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        Log.w(TAG, "房主公告抓取异常", e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "房主公告监听失败", e);
            } finally {
                if (ms != null) {
                    try {
                        ms.close();
                    } catch (Exception ignored) {
                    }
                }
                if (bs != null) {
                    try {
                        bs.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "lan-relay-host");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stopHost() {
        running = false;
        if (relaySocket != null) {
            try {
                relaySocket.close();
            } catch (Exception ignored) {
            }
            relaySocket = null;
        }
    }

    // ---------------- 成员侧：重放公告 + RakNet 双向转发 ----------------

    /** 成员收到房主公告：更新数据并在本机重放。 */
    public static synchronized void onAnnounce(byte[] reply, String host, int port) {
        hostIp = host;
        worldPort = port;
        lastReply = reply;
        if (relaySocket == null || relaySocket.isClosed()) {
            startClientRelay();
        }
    }

    /** 成员启动转发器：bind 随机端口 P（绑 VPN 网络），周期重放公告。 */
    private static synchronized void startClientRelay() {
        if (relaySocket != null && !relaySocket.isClosed()) {
            return;
        }
        try {
            relaySocket = new DatagramSocket(null);
            relaySocket.setReuseAddress(true);
            android.net.Network vpnNet = EasyTierManager.waitForVpnNetwork(10_000);
            if (vpnNet != null) {
                try {
                    vpnNet.bindSocket(relaySocket);
                } catch (Exception be) {
                    Log.w(TAG, "转发器绑定 VPN 网络失败", be);
                }
            }
            relaySocket.bind(new InetSocketAddress("0.0.0.0", 0));
            Log.i(TAG, "异地局域网转发器已启动: 端口 " + relaySocket.getLocalPort());
        } catch (Exception e) {
            Log.w(TAG, "转发器启动失败", e);
            relaySocket = null;
            return;
        }

        // 重放线程：周期广播 Reply 到本机 19132（游戏好友页显示世界）
        Thread ann = new Thread(() -> {
            byte[] data;
            while ((data = lastReply) != null && relaySocket != null && !relaySocket.isClosed()) {
                try {
                    DatagramSocket s = relaySocket;
                    s.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName("255.255.255.255"), ANN_PORT));
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lan-relay-ann");
        ann.setDaemon(true);
        ann.start();

        // 转发线程：客户端 ↔ 房主游戏 双向 RakNet 转发
        Thread fwd = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (relaySocket != null && !relaySocket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    relaySocket.receive(p);
                    String src = p.getAddress().getHostAddress();
                    int sport = p.getPort();
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(buf, 0, data, 0, p.getLength());
                    if (hostIp != null && src.equals(hostIp) && worldPort > 0
                            && (sport == worldPort || clientAddr != null)) {
                        // 房主游戏回包 → 转给客户端
                        InetSocketAddress ca = clientAddr;
                        if (ca != null && !(sport == worldPort && ca == null)) {
                            relaySocket.send(new DatagramPacket(data, data.length, ca));
                        }
                    } else {
                        // 客户端 → 转发给房主游戏
                        if (hostIp != null && worldPort > 0) {
                            clientAddr = new InetSocketAddress(src, sport);
                            relaySocket.send(new DatagramPacket(data, data.length,
                                    InetAddress.getByName(hostIp), worldPort));
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "转发异常", e);
                }
            }
        }, "lan-relay-fwd");
        fwd.setDaemon(true);
        fwd.start();
    }

    public static synchronized void stopClient() {
        if (relaySocket != null) {
            try {
                relaySocket.close();
            } catch (Exception ignored) {
            }
            relaySocket = null;
        }
        clientAddr = null;
        lastReply = null;
    }

    private static boolean hasRakNetMagic(byte[] data) {
        byte[] magic = {0x00, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0xFE,
                (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFD, (byte) 0xFD,
                (byte) 0xFD, (byte) 0xFD, 0x12, 0x34, 0x56, 0x78};
        outer:
        for (int i = 1; i + magic.length <= data.length; i++) {
            for (int j = 0; j < magic.length; j++) {
                if (data[i + j] != magic[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
