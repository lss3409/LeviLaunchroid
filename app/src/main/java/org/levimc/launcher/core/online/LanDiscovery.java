package org.levimc.launcher.core.online;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 局域网房主发现（v497，UDP 广播，端口 11011）：
 * 官方公共节点已下线，同网段设备靠本机制互指 peer。
 * 房主（创建房间后）监听广播查询并应答网络名；
 * 成员（加入前）广播查询，收集同网络房主的 IP 作为直连 peer。
 */
public final class LanDiscovery {

    private static final String TAG = "LanDiscovery";
    public static final int PORT = 11011;

    private static Thread hostThread;
    private static volatile boolean hostRunning;

    private LanDiscovery() {
    }

    /** 房主端：启动应答服务（重复调用先停旧的）。 */
    public static synchronized void startHost(String networkName) {
        stopHost();
        hostRunning = true;
        hostThread = new Thread(() -> hostLoop(networkName), "lan-host");
        hostThread.setDaemon(true);
        hostThread.start();
    }

    public static synchronized void stopHost() {
        hostRunning = false;
        if (hostThread != null) {
            hostThread.interrupt();
            hostThread = null;
        }
    }

    private static void hostLoop(String networkName) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(PORT));
            byte[] buf = new byte[256];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            Log.i(TAG, "房主应答服务已启动, net=" + networkName);
            while (hostRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    socket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (!msg.contains("\"q\"")) {
                        continue;
                    }
                    String reply = "{\"t\":\"r\",\"net\":\"" + networkName + "\"}";
                    byte[] out = reply.getBytes("UTF-8");
                    socket.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                } catch (java.io.IOException e) {
                    if (!hostRunning) {
                        break;
                    }
                    Log.w(TAG, "房主应答循环异常", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "房主应答服务启动失败", e);
        }
        Log.i(TAG, "房主应答服务已停止");
    }

    /**
     * 成员端：广播查询同网络的房主。
     * @return 房主的直连 peer URI 列表（tcp://ip:11010，可能为空）。
     */
    public static List<String> discover(String networkName, long timeoutMs) {
        List<String> peers = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(0));
            socket.setSoTimeout(400);
            byte[] query = "{\"t\":\"q\"}".getBytes("UTF-8");
            InetAddress bcast = InetAddress.getByName("255.255.255.255");
            byte[] buf = new byte[256];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                // 每轮发一次查询，然后收响应直到超时窗口结束
                socket.send(new DatagramPacket(query, query.length, bcast, PORT));
                long roundEnd = Math.min(System.currentTimeMillis() + 1200, deadline);
                while (System.currentTimeMillis() < roundEnd) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (!msg.contains("\"r\"") || !msg.contains(networkName)) {
                        continue;
                    }
                    byte[] addr = p.getAddress().getAddress();
                    if (addr.length != 4) {
                        continue;
                    }
                    String ip = (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "."
                            + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
                    String uri = "tcp://" + ip + ":" + 11010;
                    if (!peers.contains(uri)) {
                        peers.add(uri);
                        Log.i(TAG, "发现房主: " + uri);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "局域网发现失败", e);
        }
        return peers;
    }
}
