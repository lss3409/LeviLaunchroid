package org.levimc.launcher.core.online;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaperConnect 房间中心（v502，TCP，端口 8090）：
 * 房主端 ServerSocket 维护玩家列表（c:player 心跳 10s 超时踢出，c:ping 测延迟）；
 * 成员端 5s 心跳客户端，回调玩家列表与延迟。
 * 协议（\0 分隔 UTF-8 JSON）：
 *   c:ping\0{"time":<long>} → {"time","returnTime","gameType":"MinecraftBedrock","gameProtocolType":"UDP","gamePort":19132}
 *   c:player\0{"clientId","playerName"} → {"returnTime","players":[{"player","clientId","isRoomHost"}]}
 */
public final class RoomCenter {

    private static final String TAG = "RoomCenter";
    public static final int PORT = 8090;
    private static final long HEARTBEAT_MS = 5000;
    private static final long TIMEOUT_MS = 10_000;
    public static final int GAME_PORT = 19132;

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
    private static ServerSocket server;
    private static Thread acceptThread;
    private static volatile boolean hostRunning;
    private static final Map<String, Player> players = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private static String hostName;
    private static String hostClientId;
    private static volatile Listener hostListener;

    public static synchronized void startHost(String name, String clientId, Listener l) {
        stopHost();
        hostName = name;
        hostClientId = clientId;
        hostListener = l;
        hostRunning = true;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", PORT));
        } catch (IOException e) {
            Log.e(TAG, "房间中心启动失败", e);
            hostRunning = false;
            return;
        }
        acceptThread = new Thread(RoomCenter::acceptLoop, "room-center");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "房间中心已启动（房主）: " + name);
    }

    public static synchronized void stopHost() {
        hostRunning = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
        players.clear();
        lastSeen.clear();
    }

    private static void acceptLoop() {
        while (hostRunning) {
            try {
                Socket s = server.accept();
                new Thread(() -> handleHostConn(s), "room-conn").start();
            } catch (IOException e) {
                if (hostRunning) {
                    Log.w(TAG, "accept 异常", e);
                }
            }
        }
    }

    private static void handleHostConn(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout((int) TIMEOUT_MS);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            while (hostRunning) {
                String req = readPacket(in);
                if (req == null) {
                    break;
                }
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
                    writePacket(out, resp.toString());
                } else if ("c:player".equals(cmd)) {
                    try {
                        JSONObject q = new JSONObject(body);
                        String cid = q.optString("clientId", "?");
                        String pname = q.optString("playerName", cid);
                        lastSeen.put(cid, System.currentTimeMillis());
                        if (!players.containsKey(cid)) {
                            players.put(cid, new Player(pname, cid, false));
                            Log.i(TAG, "玩家加入: " + pname + " (" + cid + ")");
                        }
                    } catch (Exception ignored) {
                    }
                    JSONObject resp = new JSONObject();
                    resp.put("returnTime", System.currentTimeMillis());
                    resp.put("players", buildPlayerListJson());
                    writePacket(out, resp.toString());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "连接处理异常", e);
        }
    }

    /** 房主侧玩家列表（含房主自己 + 清理超时成员）。 */
    private static JSONArray buildPlayerListJson() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            if (now - e.getValue() > TIMEOUT_MS) {
                players.remove(e.getKey());
                lastSeen.remove(e.getKey());
            }
        }
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
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(hostIp, PORT), 4000);
                s.setSoTimeout(4000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                while (clientRunning) {
                    // 延迟探测
                    long pingRtt = -1;
                    try {
                        long t0 = System.currentTimeMillis();
                        writePacket(out, "c:ping\0{\"time\":" + t0 + "}");
                        String resp = readPacket(in);
                        if (resp != null && !resp.isEmpty()) {
                            pingRtt = System.currentTimeMillis() - t0;
                        }
                    } catch (Exception e) {
                        throw e;
                    }
                    // 心跳 + 玩家列表
                    writePacket(out, "c:player\0{\"clientId\":\"" + clientId
                            + "\",\"playerName\":\"" + escape(name) + "\"}");
                    String resp = readPacket(in);
                    if (resp != null && !resp.isEmpty()) {
                        List<Player> list = parsePlayers(resp);
                        if (l != null) {
                            l.onPlayers(list, pingRtt);
                        }
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
                Log.w(TAG, "房间中心连接失败（3s 后重试）: " + e);
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

    // ---- IO 工具 ----

    private static String readPacket(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == 0) {
                break;
            }
            buf.write(c);
        }
        if (buf.size() == 0 && c == -1) {
            return null;
        }
        return buf.toString("UTF-8");
    }

    private static void writePacket(OutputStream out, String s) throws IOException {
        out.write(s.getBytes("UTF-8"));
        out.write(0);
        out.flush();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
