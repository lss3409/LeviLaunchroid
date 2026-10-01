package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * MC 基岩版局域网公告桥（v517）：
 * EasyTier 是三层虚拟网，MC 的局域网发现广播（255.255.255.255:19132）
 * 无法穿越 TUN——异地成员的好友页永远看不到房主世界（实测确认）。
 * 方案：房主端合成符合基岩版 LAN 协议的世界公告（MOTD:...;AD:base64），
 * 每 1 秒以单播发给每个成员的虚拟 IP:19132。成员 MC 的 19132 监听
 * socket（绑 0.0.0.0，非房主也会监听以接收公告）收到后好友页即显示
 * 世界；成员点击加入时按包源地址回连 10.144.144.144:19132（TUN 直通）。
 *
 * 不做 19132 本地捕获转发：同端口双绑会把游戏自身的单播流量抢到我们
 * socket 上，反而破坏联机。
 */
public final class LanBridge {

    private static final String TAG = "LanBridge";
    public static final int LAN_PORT = 19132;
    /** 公告间隔（房主 MC 原生广播节奏约 1s）。 */
    private static final long INTERVAL_MS = 1000;

    private static volatile boolean running;
    private static Thread thread;
    private static String motd = "PaperConnect 房间";

    private LanBridge() {
    }

    /** 房主开桥：合成公告并周期性单播给所有成员。 */
    public static synchronized void startHost(String hostName) {
        stopHost();
        motd = (hostName == null || hostName.isEmpty()) ? "PaperConnect 房间" : hostName;
        running = true;
        thread = new Thread(() -> {
            byte[] pkt = buildAnnouncement(motd);
            try (DatagramSocket s = new DatagramSocket()) {
                while (running) {
                    List<InetSocketAddress> members = RoomCenter.getMemberAddresses();
                    for (InetSocketAddress addr : members) {
                        try {
                            byte[] out = pkt;
                            DatagramPacket p = new DatagramPacket(out, out.length,
                                    addr.getAddress(), LAN_PORT);
                            s.send(p);
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        Thread.sleep(INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "公告桥异常", e);
            }
        }, "lan-bridge");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "公告桥已启动（房主）: " + motd);
    }

    public static synchronized void stopHost() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    // ---------------- v580：LAN 公告抓包调试（后门触发） ----------------

    private static volatile boolean dumping;
    private static Thread dumpThread;

    /** 抓真实 MC 局域网公告（组播 224.0.2.60 多端口 + 广播），hex 写文件日志——
     * 用于实测修正 protocol 号与公告格式（McProtocol.VERSION 当前为占位值）。
     * v582：1.26 公告端口不再固定 19132，多端口并发监听。 */
    public static synchronized void startDebugDump() {
        stopDebugDump();
        dumping = true;
        int[] ports = {19132, 2168, 4445, 19133};
        for (int port : ports) {
            int p = port;
            Thread t = new Thread(() -> {
                java.net.MulticastSocket ms = null;
                java.net.DatagramSocket bs = null;
                try {
                    ms = new java.net.MulticastSocket(p);
                    ms.setReuseAddress(true);
                    try {
                        ms.joinGroup(java.net.InetAddress.getByName("224.0.2.60"));
                    } catch (Exception ignored) {
                    }
                    ms.setSoTimeout(4000);
                    // 同时监听广播（部分版本发 255.255.255.255）
                    try {
                        bs = new java.net.DatagramSocket(null);
                        bs.setReuseAddress(true);
                        bs.bind(new java.net.InetSocketAddress("0.0.0.0", p));
                        bs.setSoTimeout(4000);
                    } catch (Exception ignored) {
                    }
                    byte[] buf = new byte[2048];
                    org.levimc.launcher.util.OnlineDebugLog.log("LAN 抓包已启动（端口 " + p + "）");
                    while (dumping) {
                        try {
                            java.net.DatagramPacket pkt = new java.net.DatagramPacket(buf, buf.length);
                            if (ms != null) {
                                ms.receive(pkt);
                            } else {
                                bs.receive(pkt);
                            }
                            String hex = bytesToHex(pkt.getData(), Math.min(pkt.getLength(), 192));
                            org.levimc.launcher.util.OnlineDebugLog.log("LAN 公告 from "
                                    + pkt.getAddress().getHostAddress() + ":" + pkt.getPort()
                                    + " len=" + pkt.getLength() + " [" + hex + "]");
                            String text = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                            if (text.startsWith("MOTD:")) {
                                org.levimc.launcher.util.OnlineDebugLog.log("LAN 公告明文: " + text);
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                        } catch (Exception e) {
                            Log.w(TAG, "抓包异常", e);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "抓包启动失败(端口 " + p + ")", e);
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
            }, "lan-dump-" + p);
            t.setDaemon(true);
            t.start();
        }
    }

    public static synchronized void stopDebugDump() {
        dumping = false;
        if (dumpThread != null) {
            dumpThread.interrupt();
            dumpThread = null;
        }
    }

    private static String bytesToHex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(java.util.Locale.US, "%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 合成一条基岩版 LAN 公告：
     * "MOTD:<motd>;AD:<base64>"
     * AD = "MCPE;<motd>;<protocol>;<version>;<players>;<max>;<guid>;<submotd>;
     *       <gamemode>;<gamemodeNum>;<portV4>;<portV6>;"
     * protocol 取 MC 1.26.45 实测值（见 FACT；用 0 时客户端可能忽略公告）。
     */
    private static byte[] buildAnnouncement(String name) {
        int protocol = McProtocol.VERSION;
        String version = "1.26.45";
        String guid = "1145141919"; // 稳定即可，客户端按 guid 去重
        String ad = "MCPE;" + name + ";" + protocol + ";" + version
                + ";1;8;" + guid + ";PaperConnect 联机;1;1;"
                + LAN_PORT + ";" + LAN_PORT + ";";
        String pkt = "MOTD:" + name + ";AD:"
                + Base64.getEncoder().encodeToString(ad.getBytes(StandardCharsets.UTF_8));
        return pkt.getBytes(StandardCharsets.UTF_8);
    }

    /** MC 1.26.45 的网络协议版本（占位，待抓包实测后修正）。 */
    private static final class McProtocol {
        static final int VERSION = 776;
    }
}
