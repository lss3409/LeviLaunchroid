package org.levimc.launcher.core.online;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaperConnect 房间中心（v503，UDP 8090）：
 * 房主端 DatagramSocket 维护玩家列表（c:player 心跳 10s 超时踢出，
 * c:ping 测延迟）；成员端 5s 心跳客户端，回调玩家列表与延迟。
 * 注：不用 TCP——Android 上 EasyTier 的 TCP 端到端需要 Wireguard
 * VPN Portal（未启动），UDP 由宿主内核栈直接转发（ping 通即证明）。
 * 协议（UTF-8 JSON 单包）：
 *   c:ping\0{"time":<long>} → {"time","returnTime","gameType":"MinecraftBedrock","gameProtocolType":"UDP","gamePort":19132}
 *   c:player\0{"clientId","playerName"} → {"returnTime","players":[{"player","clientId","isRoomHost"}]}
 */
public final class RoomCenter {

    private static final String TAG = "RoomCenter";
    /** 房间中心端口（v522：8090 与联想系统服务冲突，改 18090）。 */
    public static final int PORT = 18090;
    private static final long HEARTBEAT_MS = 5000;
    private static final long TIMEOUT_MS = 10_000;
    public static final int GAME_PORT = 19132;
    /** 房间成员上限（含房主）。 */
    public static final int MAX_PLAYERS = 8;
    /** 房主的 MC 世界是否已开启（19132 监听），由心跳响应带回（v520）。 */
    public static volatile boolean hostGameOpen = false;
    /** 当前会话信息（v521 游戏内悬浮窗用）：房间码 / 是否房主。 */
    public static volatile String roomCode = null;
    public static volatile boolean isHost = false;

    public static class Player {
        public final String name;
        public final String clientId;
        public final boolean isRoomHost;
        /** 虚拟 IP（v527 语音模块用；房主条目也有，null = 未知）。 */
        public final String addr;
        /** 麦克风模式（v528：0 闭麦 / 1 开麦 / 2 PTT）。 */
        public final int micState;

        Player(String name, String clientId, boolean isRoomHost, String addr, int micState) {
            this.name = name;
            this.clientId = clientId;
            this.isRoomHost = isRoomHost;
            this.addr = addr;
            this.micState = micState;
        }
    }

    /** 玩家列表/延迟回调（工作线程，UI 自行切主线程）。 */
    public interface Listener {
        void onPlayers(List<Player> players, long rttMs);
    }

    // ---- 房主端 ----
    private static DatagramSocket hostSocket;
    private static Thread hostThread;
    private static volatile boolean hostRunning;
    private static final Map<String, Player> players = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** 成员虚拟地址（心跳包源 ip:port），供 LanBridge 转发与房主 ping 成员（v525 含端口）。 */
    private static final Map<String, InetSocketAddress> memberAddrs = new ConcurrentHashMap<>();
    /** 成员麦克风模式（心跳带 micState，v528）。 */
    private static final Map<String, Integer> memberMic = new ConcurrentHashMap<>();
    /** 房主一键禁麦是否生效（v528）。 */
    public static volatile boolean allMuted = false;
    private static String hostName;
    private static String hostClientId;
    private static volatile Listener hostListener;
    /** v521：多监听器（OnlineActivity 页面 + 游戏内悬浮窗同时订阅）。 */
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners(List<Player> list, long rttMs) {
        for (Listener l : listeners) {
            try {
                l.onPlayers(list, rttMs);
            } catch (Exception ignored) {
            }
        }
    }

    public static synchronized void startHost(String name, String clientId, Listener l) {
        stopHost();
        hostName = name;
        hostClientId = clientId;
        hostListener = l;
        // v522 修复：传参监听器同步注册进多监听器注册表（v521 只广播注册表，
        // 传参监听器被静默忽略导致 UI 收不到玩家列表更新）
        if (l != null) {
            addListener(l);
        }
        hostRunning = true;
        try {
            hostSocket = new DatagramSocket(null);
            hostSocket.setReuseAddress(true);
            hostSocket.bind(new InetSocketAddress("0.0.0.0", PORT));
            hostSocket.setSoTimeout(2000);
        } catch (IOException e) {
            Log.e(TAG, "房间中心启动失败", e);
            hostRunning = false;
            return;
        }
        hostThread = new Thread(RoomCenter::hostLoop, "room-center");
        hostThread.setDaemon(true);
        hostThread.start();
        Log.i(TAG, "房间中心已启动（房主）: " + name);
    }

    public static synchronized void stopHost() {
        hostRunning = false;
        if (hostSocket != null) {
            hostSocket.close();
            hostSocket = null;
        }
        if (hostListener != null) {
            removeListener(hostListener);
            hostListener = null;
        }
        players.clear();
        lastSeen.clear();
        memberMic.clear();
        allMuted = false;
    }

    private static void hostLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (hostRunning) {
            try {
                hostSocket.receive(p);
                String req = new String(p.getData(), 0, p.getLength(), "UTF-8");
                int sep = req.indexOf('\0');
                if (sep < 0) {
                    continue;
                }
                String cmd = req.substring(0, sep);
                String body = req.substring(sep + 1);
                if ("c:ping".equals(cmd)) {
                    JSONObject resp = new JSONObject();
                    try {
                        JSONObject q = new JSONObject(body);
                        resp.put("time", q.optLong("time", 0));
                    } catch (Exception ignored) {
                    }
                    resp.put("returnTime", System.currentTimeMillis());
                    resp.put("gameType", "MinecraftBedrock");
                    resp.put("gameProtocolType", "UDP");
                    resp.put("gamePort", GAME_PORT);
                    send(hostSocket, resp.toString(), p.getAddress(), p.getPort());
                } else if ("c:player".equals(cmd)) {
                    try {
                        JSONObject q = new JSONObject(body);
                        String cid = q.optString("clientId", "?");
                        String pname = q.optString("playerName", cid);
                        lastSeen.put(cid, System.currentTimeMillis());
                        memberAddrs.put(cid, new InetSocketAddress(p.getAddress(), p.getPort()));
                        // v528：成员麦克风模式同步
                        memberMic.put(cid, q.optInt("micState", 0));
                        if (!players.containsKey(cid)) {
                            if (players.size() + 1 >= MAX_PLAYERS) {
                                Log.w(TAG, "房间已满，拒绝: " + pname);
                            } else {
                                players.put(cid, new Player(pname, cid, false, null, 0));
                                Log.i(TAG, "玩家加入: " + pname + " (" + cid + ")");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    JSONObject resp = new JSONObject();
                    resp.put("returnTime", System.currentTimeMillis());
                    resp.put("players", buildPlayerListJson());
                    // v520：房主 MC 世界开启状态（19132 监听检测），成员端据此提示
                    resp.put("gameOpen", isMcWorldOpen());
                    send(hostSocket, resp.toString(), p.getAddress(), p.getPort());
                    // 通知房主 UI 刷新玩家列表（v521：多监听器广播）
                    notifyListeners(snapshot(), -1);
                }
            } catch (java.net.SocketTimeoutException e) {
                // 超时：顺带清理过期成员
                cleanupStale();
            } catch (Exception e) {
                if (hostRunning) {
                    Log.w(TAG, "房主循环异常", e);
                }
            }
        }
        Log.i(TAG, "房间中心已停止");
    }

    private static void cleanupStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            if (now - e.getValue() > TIMEOUT_MS) {
                players.remove(e.getKey());
                lastSeen.remove(e.getKey());
                memberAddrs.remove(e.getKey());
            }
        }
    }

    /** 成员虚拟地址列表（供 LanBridge 公告桥单播转发与房主 ping，v525 含端口）。 */
    public static java.util.List<InetSocketAddress> getMemberAddresses() {
        return new java.util.ArrayList<>(memberAddrs.values());
    }

    /** 本机 MC 是否开启了世界（UDP 19132 监听；/proc/net/udp 端口为 LE hex）。 */
    private static boolean isMcWorldOpen() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/udp"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length > 1 && cols[1].toUpperCase().endsWith(":BC4A")) {
                    r.close();
                    return true;
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 房主侧玩家列表（含房主自己）。 */
    private static JSONArray buildPlayerListJson() throws org.json.JSONException {
        cleanupStale();
        JSONArray arr = new JSONArray();
        JSONObject host = new JSONObject();
        host.put("player", displayName(hostName));
        host.put("clientId", hostClientId);
        host.put("isRoomHost", true);
        host.put("addr", hostAddr());
        host.put("micState", org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode());
        arr.put(host);
        for (Player p : players.values()) {
            JSONObject o = new JSONObject();
            o.put("player", p.name);
            o.put("clientId", p.clientId);
            o.put("isRoomHost", false);
            o.put("micState", micOf(p.clientId));
            String ip = addrOf(p.clientId);
            if (ip != null) {
                o.put("addr", ip);
            }
            arr.put(o);
        }
        return arr;
    }

    /** 成员麦克风模式（默认闭麦，v528）。 */
    private static int micOf(String clientId) {
        Integer m = memberMic.get(clientId);
        return m == null ? 0 : m;
    }

    /** 成员虚拟 IP（供语音模块等直接寻址）。 */
    private static String addrOf(String clientId) {
        InetSocketAddress a = memberAddrs.get(clientId);
        return a == null ? null : a.getAddress().getHostAddress();
    }

    /** 房主虚拟 IP（固定 10.144.144.144；内核侧 IP 优先）。 */
    private static String hostAddr() {
        try {
            String vip = EasyTierManager.get().getVirtualIp();
            if (vip != null && !vip.isEmpty()) {
                int slash = vip.indexOf('/');
                return slash > 0 ? vip.substring(0, slash) : vip;
            }
        } catch (Throwable ignored) {
        }
        return "10.144.144.144";
    }

    /** v527：Xbox 登录后心跳动态换名（PlayerIdentity 刷新后自动生效）。 */
    private static String displayName(String fallback) {
        String nick = PlayerIdentity.getCurrentNick();
        return (nick == null || nick.isEmpty()) ? fallback : nick;
    }

    // ---- 成员端 ----
    private static volatile boolean clientRunning;
    private static Thread clientThread;
    private static volatile Listener clientListener;

    public static synchronized void startClient(String hostIp, String name, String clientId, Listener l) {
        stopClient();
        clientRunning = true;
        clientListener = l;
        // v522 修复：传参监听器同步注册进多监听器注册表
        if (l != null) {
            addListener(l);
        }
        clientThread = new Thread(() -> clientLoop(hostIp, name, clientId, l), "room-client");
        clientThread.setDaemon(true);
        clientThread.start();
    }

    public static synchronized void stopClient() {
        clientRunning = false;
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
        if (clientListener != null) {
            removeListener(clientListener);
            clientListener = null;
        }
    }

    private static void clientLoop(String hostIp, String name, String clientId, Listener l) {
        while (clientRunning) {
            try (DatagramSocket s = new DatagramSocket()) {
                InetSocketAddress target = new InetSocketAddress(hostIp, PORT);
                // v525 接收线程：常驻 socket 应答房主的反向 c:ping（房主才能测到
                // 成员延迟/丢包），并解析心跳响应（RTT 用响应回显的 time 计算）。
                Thread receiver = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    while (clientRunning) {
                        try {
                            s.setSoTimeout(1000);
                            s.receive(p);
                            String text = new String(p.getData(), 0, p.getLength(), "UTF-8");
                            if (text.startsWith("c:ping\0")) {
                                answerPing(s, p, text);
                            } else if (text.startsWith("c:mute\0")) {
                                // v528：房主一键禁麦指令 → 语音引擎强制闭麦
                                try {
                                    JSONObject mq = new JSONObject(
                                            text.substring(text.indexOf('\0') + 1));
                                    org.levimc.launcher.core.online.voice.VoiceEngine
                                            .forceMuteStatic(mq.optBoolean("mute", false));
                                } catch (Exception ignored) {
                                }
                            } else {
                                List<Player> list = parsePlayers(text);
                                long rtt = -1;
                                try {
                                    JSONObject o = new JSONObject(text);
                                    long sent = o.optLong("time", 0);
                                    if (sent > 0) {
                                        rtt = System.currentTimeMillis() - sent;
                                    }
                                } catch (Exception ignored) {
                                }
                                if (!list.isEmpty() || rtt > 0) {
                                    notifyListeners(list, rtt);
                                }
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                            // 继续等待
                        } catch (Exception e) {
                            return;
                        }
                    }
                }, "room-client-recv");
                receiver.setDaemon(true);
                receiver.start();
                while (clientRunning) {
                    try {
                        long t0 = System.currentTimeMillis();
                        String req = "c:ping\0{\"time\":" + t0 + "}";
                        byte[] out = req.getBytes("UTF-8");
                        s.send(new DatagramPacket(out, out.length, target));
                        // v527：心跳动态读当前昵称（Xbox 登录后自动换名）
                        // v528：附带麦克风模式（房主端同步全员状态）
                        String hb = "c:player\0{\"clientId\":\"" + clientId
                                + "\",\"playerName\":\"" + escape(displayName(name))
                                + "\",\"micState\":"
                                + org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode()
                                + "}";
                        byte[] out2 = hb.getBytes("UTF-8");
                        s.send(new DatagramPacket(out2, out2.length, target));
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(HEARTBEAT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } catch (Exception e) {
                if (!clientRunning) {
                    return;
                }
                Log.w(TAG, "房间中心不可达（3s 后重试）: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** 应答 c:ping 请求（房主反向探测成员延迟用，v525）。 */
    private static void answerPing(DatagramSocket s, DatagramPacket req, String text) {
        try {
            JSONObject resp = new JSONObject();
            try {
                JSONObject q = new JSONObject(text.substring(text.indexOf('\0') + 1));
                resp.put("time", q.optLong("time", 0));
            } catch (Exception ignored) {
            }
            resp.put("returnTime", System.currentTimeMillis());
            resp.put("gameType", "MinecraftBedrock");
            resp.put("gameProtocolType", "UDP");
            resp.put("gamePort", GAME_PORT);
            byte[] out = resp.toString().getBytes("UTF-8");
            s.send(new DatagramPacket(out, out.length, req.getAddress(), req.getPort()));
        } catch (Exception ignored) {
        }
    }

    private static List<Player> parsePlayers(String json) {
        List<Player> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            // v520：房主世界开启状态（心跳响应带回）
            hostGameOpen = o.optBoolean("gameOpen", false);
            JSONArray arr = o.optJSONArray("players");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null) {
                        String addr = p.optString("addr", null);
                        out.add(new Player(p.optString("player", "?"),
                                p.optString("clientId", "?"), p.optBoolean("isRoomHost", false),
                                addr == null || addr.isEmpty() ? null : addr,
                                p.optInt("micState", 0)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 当前玩家快照（含房主）。 */
    private static List<Player> snapshot() {
        List<Player> out = new ArrayList<>();
        out.add(new Player(displayName(hostName), hostClientId, true, hostAddr(),
                org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode()));
        for (Player p : players.values()) {
            out.add(new Player(p.name, p.clientId, false, addrOf(p.clientId), micOf(p.clientId)));
        }
        return out;
    }

    /** 房主一键禁麦/解除（v528）：c:mute 单播给所有成员。 */
    public static void sendMuteAll(boolean mute) {
        allMuted = mute;
        DatagramSocket s = hostSocket;
        if (s == null || s.isClosed()) {
            return;
        }
        String msg = "c:mute\0{\"mute\":" + mute + "}";
        byte[] out = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (InetSocketAddress addr : memberAddrs.values()) {
            try {
                s.send(new DatagramPacket(out, out.length, addr.getAddress(), addr.getPort()));
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, "一键禁麦广播: " + mute);
    }

    // ---- UDP 工具 ----

    private static void send(DatagramSocket s, String msg, java.net.InetAddress addr, int port)
            throws IOException {
        byte[] out = msg.getBytes("UTF-8");
        s.send(new DatagramPacket(out, out.length, addr, port));
    }

    private static String recv(DatagramSocket s) {
        try {
            byte[] buf = new byte[4096];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return new String(p.getData(), 0, p.getLength(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
