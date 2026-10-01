package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * v617：异地局域网组播公告（照搬 ZalithLauncher/Terracotta 机制）。
 *
 * Terracotta（burningtnt/Terracotta，ZalithLauncher2 的联机核心）实现：
 * 房主对本机每个网络地址（含 EasyTier TUN 虚拟网 IP）各绑一个 socket，
 * 组播发送 MC 局域网公告到 224.0.2.60:4445（[MOTD]..[/MOTD][AD]port[/AD]
 * 明文，1.5s 间隔）；成员 join 组播收公告解析端口。组播会被 EasyTier
 * 虚拟网转发（广播会被吞——我们 v584-v592 全用广播所以全部失败）。
 *
 * Android 1.26 的发现机制实测为"客户端广播 ping 到 19132 + 服务器单播
 * pong"，但老版组播协议（4445）可能仍被客户端接受——本类为实验：
 * 双端同时跑（房主发公告、成员收公告），日志确认链路后接入 UI。
 */
public final class TerracottaLan {

    private static final String TAG = "TerracottaLan";
    private static final String GROUP_V4 = "224.0.2.60";
    private static final int PORT = 4445;
    private static final long INTERVAL_MS = 1500;

    private static volatile boolean announcing;
    private static volatile boolean scanning;
    private static volatile String lastMotd = "PaperConnect 房间";
    private static volatile int lastGamePort;

    private TerracottaLan() {
    }

    // ---------------- 房主侧：组播公告 ----------------

    /** 房主开公告：对本机所有地址（含 TUN）组播发 MOTD/AD。 */
    public static synchronized void startAnnounce(String motd) {
        stopAnnounce();
        if (motd != null && !motd.isEmpty()) {
            lastMotd = motd;
        }
        announcing = true;
        Thread t = new Thread(() -> {
            List<DatagramSocket> sockets = new ArrayList<>();
            // 本机所有接口地址（含 tun0 虚拟网 IP）
            for (InetAddress addr : localAddresses()) {
                try {
                    DatagramSocket s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(addr, 0));
                    if (addr.getAddress().length == 4) {
                        s.setBroadcast(true);
                    }
                    sockets.add(s);
                } catch (Exception e) {
                    Log.w(TAG, "公告 socket 绑定失败: " + addr, e);
                }
            }
            Log.i(TAG, "组播公告已启动，socket 数=" + sockets.size()
                    + " 地址=" + localAddresses());
            InetAddress group = InetAddress.getByName(GROUP_V4);
            while (announcing) {
                int port = LanRelayBridge.getLearnedWorldPort();
                if (port > 0) {
                    lastGamePort = port;
                }
                // 世界未开启时不发公告（Terracotta 语义：开世界才公告）
                if (lastGamePort <= 0) {
                    try {
                        Thread.sleep(INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                String msg = "[MOTD]" + lastMotd + "[/MOTD][AD]"
                        + lastGamePort + "[/AD]";
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                for (DatagramSocket s : sockets) {
                    try {
                        s.send(new DatagramPacket(data, data.length, group, PORT));
                    } catch (Exception ignored) {
                    }
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            for (DatagramSocket s : sockets) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }, "terracotta-announce");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stopAnnounce() {
        announcing = false;
    }

    // ---------------- 成员侧：组播扫描 ----------------

    /** 成员开扫描：join 组播收公告，写文件日志。 */
    public static synchronized void startScan() {
        stopScan();
        scanning = true;
        Thread t = new Thread(() -> {
            try (MulticastSocket ms = new MulticastSocket(PORT)) {
                ms.setReuseAddress(true);
                // 对每个接口 join 组播（组播经 TUN 虚拟网进来时必须
                // 在 tun0 上有成员资格，默认接口 join 收不到）
                InetAddress group = InetAddress.getByName(GROUP_V4);
                int joined = 0;
                for (NetworkInterface ni : interfaces()) {
                    try {
                        ms.joinGroup(new InetSocketAddress(group, PORT), ni);
                        joined++;
                    } catch (Exception ignored) {
                    }
                }
                try {
                    ms.joinGroup(group);
                    joined++;
                } catch (Exception e) {
                    Log.w(TAG, "默认接口 join 组播失败", e);
                }
                org.levimc.launcher.util.OnlineDebugLog.log(
                        "TerracottaLan: 组播扫描已启动 (" + GROUP_V4 + ":" + PORT
                                + ")，已 join " + joined + " 个接口");
                ms.setSoTimeout(2000);
                byte[] buf = new byte[2048];
                org.levimc.launcher.util.OnlineDebugLog.log(
                        "TerracottaLan: 组播扫描已启动 (" + GROUP_V4 + ":" + PORT + ")");
                while (scanning) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ms.receive(p);
                        String text = new String(p.getData(), 0, p.getLength(),
                                StandardCharsets.UTF_8);
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "TerracottaLan: 收到组播公告 from "
                                        + p.getAddress().getHostAddress() + ":"
                                        + p.getPort() + " len=" + p.getLength()
                                        + " [" + text + "]");
                        // 解析端口供后续使用
                        int b = text.indexOf("[AD]");
                        int e = text.indexOf("[/AD]");
                        if (b >= 0 && e > b) {
                            try {
                                int port = Integer.parseInt(
                                        text.substring(b + 4, e).trim());
                                lastGamePort = port;
                                org.levimc.launcher.util.OnlineDebugLog.log(
                                        "TerracottaLan: 解析到服务器端口 " + port);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "组播扫描异常", e);
            }
        }, "terracotta-scan");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stopScan() {
        scanning = false;
    }

    /** 成员收到的服务器端口（扫描结果）。 */
    public static int getScannedPort() {
        return lastGamePort;
    }

    private static List<InetAddress> localAddresses() {
        List<InetAddress> out = new ArrayList<>();
        for (NetworkInterface ni : interfaces()) {
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (a.isLoopbackAddress()) {
                    continue;
                }
                out.add(a);
            }
        }
        // 兜底：TUN 虚拟 IP（EasyTier 组网地址，接口枚举可能漏）
        try {
            String vip = EasyTierManager.get().getVirtualIp();
            if (vip != null && !vip.isEmpty()) {
                InetAddress v = InetAddress.getByName(vip);
                if (!out.contains(v)) {
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机所有活动非回环网络接口（含 tun0 虚拟网接口）。 */
    private static List<NetworkInterface> interfaces() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                out.add(ni);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
