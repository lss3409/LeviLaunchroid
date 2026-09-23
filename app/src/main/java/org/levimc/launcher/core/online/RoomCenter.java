package org.levimc.launcher.core.online;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
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
    public static final int PORT = 8090;
    private static final long HEARTBEAT_MS = 5000;
    private static final long TIMEOUT_MS = 10_000;
    public static final int GAME_PORT = 19132;
    /** 房间成员上限（含房主）。 */
    public static final int MAX_PLAYERS = 8;

    public static class Player {
        public final String name;
        public final String clientId;
        public final boolean isRoomHost;

        Player(String name, String clientId, boolean isRoomHost) {
            this.name = name;
            this.clientId = clientId;
            this.isRoomHost = isRoomHost;
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
    private static String hostName;
    private static String hostClientId;

    public static synchronized void startHost(String name, String clientId, Listener l) {
        stopHost();
        hostName = name;
        hostClientId = clientId;
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
        players.clear();
        lastSeen.clear();
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
                        if (!players.containsKey(cid)) {
                            if (players.size() + 1 >= MAX_PLAYERS) {
                                Log.w(TAG, "房间已满，拒绝: " + pname);
                            } else {
                                players.put(cid, new Player(pname, cid, false));
                                Log.i(TAG, "玩家加入: " + pname + " (" + cid + ")");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    JSONObject resp = new JSONObject();
                    resp.put("returnTime", System.currentTimeMillis());
                    resp.put("players", buildPlayerListJson());
                    send(hostSocket, resp.toString(), p.getAddress(), p.getPort());
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
            }
        }
    }

    /** 房主侧玩家列表（含房主自己）。 */
    private static JSONArray buildPlayerListJson() throws org.json.JSONException {
        cleanupStale();
        JSONArray arr = new JSONArray();
        JSONObject host = new JSONObject();
        host.put("player", hostName);
        host.put("clientId", hostClientId);
        host.put("isRoomHost", true);
        arr.put(host);
        for (Player p : players.values()) {
            JSONObject o = new JSONObject();
            o.put("player", p.name);
            o.put("clientId", p.clientId);
            o.put("isRoomHost", false);
            arr.put(o);
        }
        return arr;
    }

    // ---- 成员端 ----
    private static volatile boolean clientRunning;
    private static Thread clientThread;

    public static synchronized void startClient(String hostIp, String name, String clientId, Listener l) {
        stopClient();
        clientRunning = true;
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
    }

    private static void clientLoop(String hostIp, String name, String clientId, Listener l) {
        while (clientRunning) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(2500);
                InetSocketAddress target = new InetSocketAddress(hostIp, PORT);
                while (clientRunning) {
                    // 延迟探测
                    long pingRtt = -1;
                    try {
                        long t0 = System.currentTimeMillis();
                        String req = "c:ping\0{\"time\":" + t0 + "}";
                        byte[] out = req.getBytes("UTF-8");
                        s.send(new DatagramPacket(out, out.length, target));
                        String resp = recv(s);
                        if (resp != null && resp.contains("returnTime")) {
                            pingRtt = System.currentTimeMillis() - t0;
                        }
                    } catch (Exception ignored) {
                    }
                    // 心跳 + 玩家列表
                    List<Player> list = new ArrayList<>();
                    try {
                        String req = "c:player\0{\"clientId\":\"" + clientId
                                + "\",\"playerName\":\"" + escape(name) + "\"}";
                        byte[] out = req.getBytes("UTF-8");
                        s.send(new DatagramPacket(out, out.length, target));
                        String resp = recv(s);
                        if (resp != null) {
                            list = parsePlayers(resp);
                        }
                    } catch (Exception ignored) {
                    }
                    if (l != null && (pingRtt > 0 || !list.isEmpty())) {
                        l.onPlayers(list, pingRtt);
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

    private static List<Player> parsePlayers(String json) {
        List<Player> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("players");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null) {
                        out.add(new Player(p.optString("player", "?"),
                                p.optString("clientId", "?"), p.optBoolean("isRoomHost", false)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
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
