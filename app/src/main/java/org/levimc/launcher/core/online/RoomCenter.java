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
    /** v534：心跳 5s→2s（开麦/人数状态同步提速），状态变更另有 kick 即时推送。 */
    private static final long HEARTBEAT_MS = 2000;
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
        /** Xbox 头像 URL（v529 跨设备同步；null = 无）。 */
        public final String avatarUrl;
        /** v544：Xbox XUID / 微软账号 / Levi 游玩分钟（玩家详情卡展示）。 */
        public final String xuid;
        public final String msUser;
        public final long playMinutes;
        /** v547：详情卡查看权限（0 关闭 / 1 仅房主 / 2 所有人），随心跳广播。 */
        public final int viewPerm;
        /** v549：最近在线时间（epoch ms，启动器活跃时间戳，详情卡展示）。 */
        public final long lastActive;

        /** v547：public——UI 层构造本机快照（自己行的详情卡）用。 */
        public Player(String name, String clientId, boolean isRoomHost, String addr, int micState,
               String avatarUrl, String xuid, String msUser, long playMinutes, int viewPerm,
               long lastActive) {
            this.name = name;
            this.clientId = clientId;
            this.isRoomHost = isRoomHost;
            this.addr = addr;
            this.micState = micState;
            this.avatarUrl = avatarUrl;
            this.xuid = xuid;
            this.msUser = msUser;
            this.playMinutes = playMinutes;
            this.viewPerm = viewPerm;
            this.lastActive = lastActive;
        }
    }

    /** 玩家列表/延迟回调（工作线程，UI 自行切主线程）。 */
    public interface Listener {
        void onPlayers(List<Player> players, long rttMs);

        /** v560：房主邀请进入世界（c:invite）——成员端深链一键连接。 */
        default void onInvite(String hostIp, int port) {
        }
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
    /** 成员 Xbox 头像 URL（心跳带 avatarUrl，v529）。 */
    private static final Map<String, String> memberAvatar = new ConcurrentHashMap<>();
    /** v544：成员 XUID / 微软账号 / 游玩分钟（心跳携带）。 */
    private static final Map<String, String> memberXuid = new ConcurrentHashMap<>();
    private static final Map<String, String> memberMsUser = new ConcurrentHashMap<>();
    private static final Map<String, Long> memberPlay = new ConcurrentHashMap<>();
    /** 房主禁麦名单（v530：个体禁麦，替换 v528 全员禁麦）。 */
    private static final java.util.Set<String> hostMuted =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** v547：成员详情卡查看权限（心跳带 viewPerm）。 */
    private static final Map<String, Integer> memberViewPerm = new ConcurrentHashMap<>();
    /** v549：成员最近在线时间（心跳带 lastActive，详情卡展示）。 */
    private static final Map<String, Long> memberLastActive = new ConcurrentHashMap<>();
    /** v547：本机详情卡查看权限（心跳广播用，PlayerDetailCard 设置项写入）。 */
    private static volatile int selfViewPerm = 2;
    private static String hostName;
    private static String hostClientId;
    private static volatile Listener hostListener;
    /** v521：多监听器（OnlineActivity 页面 + 游戏内悬浮窗同时订阅）。 */
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** v547：最近一次玩家名单人数（含房主）。成员端无 memberAddrs，
     *  悬浮窗人数只能从名单快照读。 */
    private static volatile int lastPlayerCount = 1;

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners(List<Player> list, long rttMs) {
        // v547：名单快照人数（含房主），悬浮窗"X人"用——成员端读不到 memberAddrs
        if (list != null && !list.isEmpty()) {
            lastPlayerCount = list.size();
        }
        for (Listener l : listeners) {
            try {
                l.onPlayers(list, rttMs);
            } catch (Exception ignored) {
            }
        }
    }

    /** v547：最近名单人数（含房主），悬浮窗统计用。 */
    public static int getLastPlayerCount() {
        return Math.max(1, lastPlayerCount);
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
            // v566：房主中心显式绑 VPN 网络（防"socket 创建早于 TUN 建立"
            // 绑旧网络竞态；CONNECTED 回调前 VPN 网络已注册，此处瞬时返回，
            // 5s 兜底防主线程长阻塞）
            android.net.Network vpnNet = EasyTierManager.waitForVpnNetwork(5_000);
            if (vpnNet != null) {
                try {
                    vpnNet.bindSocket(hostSocket);
                    org.levimc.launcher.util.OnlineDebugLog.log("房主中心已绑定 VPN 网络");
                } catch (Exception be) {
                    Log.w(TAG, "房主 socket 绑定 VPN 网络失败", be);
                }
            } else {
                org.levimc.launcher.util.OnlineDebugLog.log("警告：5s 未见 VPN 网络，房主中心走默认网络");
            }
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
        memberAvatar.clear();
        memberXuid.clear();
        memberMsUser.clear();
        memberPlay.clear();
        memberViewPerm.clear();
        memberLastActive.clear();
        hostMuted.clear();
    }

    /** v547：本机详情卡查看权限设置（PlayerDetailCard 设置项写入，心跳广播）。 */
    public static void setSelfViewPerm(int perm) {
        selfViewPerm = perm;
        notifyLocalStateChanged();
    }

    /**
     * v560：房主邀请全体成员进入世界（URI 一键直达）——c:invite 单播，
     * 成员端收到后深链启动游戏自动连接房主的局域网世界。
     */
    public static void sendInviteAll() {
        DatagramSocket s = hostSocket;
        if (s == null || s.isClosed()) {
            Log.w(TAG, "邀请失败：房间中心不可用");
            return;
        }
        // v583：1.26 世界端口随机——邀请前探测真实端口（新端口取新值，
        // 无新端口复用上次缓存），不再写死 19132
        int gamePort = WorldPortProbe.getWorldPort();
        if (gamePort <= 0) {
            gamePort = GAME_PORT;
        }
        org.levimc.launcher.util.OnlineDebugLog.log("邀请成员进入世界: port=" + gamePort);
        String msg = "c:invite\0{\"hostIp\":\"" + hostAddr()
                + "\",\"port\":" + gamePort + "}";
        byte[] out = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int sent = 0;
        for (InetSocketAddress addr : memberAddrs.values()) {
            try {
                s.send(new DatagramPacket(out, out.length, addr.getAddress(), addr.getPort()));
                sent++;
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, "已邀请 " + sent + " 名成员进入世界: " + hostAddr());
    }

    /** v584：房主转发本机游戏的 RakNet 公告（c:lan）给全体成员。 */
    private static int lanSendCount;

    public static void sendLanAnnounce(byte[] replyData, int worldPort) {
        DatagramSocket s = hostSocket;
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("data", android.util.Base64.encodeToString(replyData,
                    android.util.Base64.NO_WRAP));
            o.put("worldPort", worldPort);
            // v588：房主昵称随公告下发（成员侧合成 pong 的世界名）
            o.put("nick", hostName != null ? hostName : "");
            String msg = "c:lan\0" + o;
            byte[] out = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int sent = 0;
            for (InetSocketAddress addr : memberAddrs.values()) {
                try {
                    s.send(new DatagramPacket(out, out.length, addr.getAddress(), addr.getPort()));
                    sent++;
                } catch (Exception ignored) {
                }
            }
            // v589：定位 c:lan 断点——每 10 次打一条文件日志
            if ((lanSendCount++ & 0x7) == 0) {
                org.levimc.launcher.util.OnlineDebugLog.log(
                        "RoomCenter: c:lan 已发给 " + sent + " 名成员 (wp=" + worldPort
                                + " memberAddrs=" + memberAddrs.size() + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "公告转发失败", e);
        }
    }

    private static void hostLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        // v565：房主收到任意成员包写文件日志（异地排查：区分"成员没发/
        // 发了没到/房主没收到"三选一）
        boolean hostFirstPacket = true;
        while (hostRunning) {
            try {
                hostSocket.receive(p);
                if (hostFirstPacket) {
                    hostFirstPacket = false;
                    org.levimc.launcher.util.OnlineDebugLog.log("房主收到首个成员包: "
                            + p.getAddress().getHostAddress() + ":" + p.getPort());
                }
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
                } else if ("c:bye".equals(cmd)) {
                    // v534：成员主动退出——立即移除并广播（不等 10s 超时）
                    try {
                        JSONObject q = new JSONObject(body);
                        String cid = q.optString("clientId", null);
                        if (cid != null && players.remove(cid) != null) {
                            lastSeen.remove(cid);
                            memberAddrs.remove(cid);
                            memberMic.remove(cid);
                            memberAvatar.remove(cid);
                            memberViewPerm.remove(cid);
                            memberLastActive.remove(cid);
                            hostMuted.remove(cid);
                            Log.i(TAG, "成员主动退出: " + cid);
                            notifyListeners(snapshot(), -1);
                        }
                    } catch (Exception ignored) {
                    }
                } else if ("c:player".equals(cmd)) {
                    try {
                        JSONObject q = new JSONObject(body);
                        String cid = q.optString("clientId", "?");
                        String pname = q.optString("playerName", cid);
                        lastSeen.put(cid, System.currentTimeMillis());
                        memberAddrs.put(cid, new InetSocketAddress(p.getAddress(), p.getPort()));
                        // v528：成员麦克风模式同步
                        memberMic.put(cid, q.optInt("micState", 0));
                        // v529：成员头像同步；v544：XUID/微软账号/游玩时长同步
                        String av = q.optString("avatarUrl", null);
                        if (av != null && !av.isEmpty()) {
                            memberAvatar.put(cid, av);
                        }
                        String xu = q.optString("xuid", null);
                        if (xu != null && !xu.isEmpty()) {
                            memberXuid.put(cid, xu);
                        }
                        String mu = q.optString("msUser", null);
                        if (mu != null && !mu.isEmpty()) {
                            memberMsUser.put(cid, mu);
                        }
                        memberPlay.put(cid, q.optLong("playMinutes", 0));
                        // v547：成员详情卡查看权限
                        memberViewPerm.put(cid, q.optInt("viewPerm", 2));
                        // v549：成员最近在线时间
                        long la = q.optLong("lastActive", 0);
                        if (la > 0) {
                            memberLastActive.put(cid, la);
                        }
                        if (!players.containsKey(cid)) {
                            if (players.size() + 1 >= MAX_PLAYERS) {
                                Log.w(TAG, "房间已满，拒绝: " + pname);
                            } else {
                                players.put(cid, new Player(pname, cid, false, null, 0, null,
                                        null, null, 0, 2, System.currentTimeMillis()));
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
                // 超时：顺带清理过期成员；v530：有移除时广播名单（成员退出横幅/列表刷新）
                if (cleanupStale()) {
                    notifyListeners(snapshot(), -1);
                }
                // v534：房主本地状态变化（麦克风模式等）→ 主动推送玩家列表给成员
                if (hostPushRequested) {
                    hostPushRequested = false;
                    pushPlayerListToMembers();
                }
            } catch (Exception e) {
                if (hostRunning) {
                    Log.w(TAG, "房主循环异常", e);
                }
            }
        }
        Log.i(TAG, "房间中心已停止");
    }

    /** 清理超时成员；有成员被移除时返回 true（调用方需广播玩家列表，v530）。 */
    private static boolean cleanupStale() {
        long now = System.currentTimeMillis();
        boolean removed = false;
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            if (now - e.getValue() > TIMEOUT_MS) {
                players.remove(e.getKey());
                lastSeen.remove(e.getKey());
                memberAddrs.remove(e.getKey());
                memberMic.remove(e.getKey());
                memberAvatar.remove(e.getKey());
                Log.i(TAG, "成员超时移除: " + e.getKey());
                removed = true;
            }
        }
        return removed;
    }

    /** 成员虚拟地址列表（供 LanBridge 公告桥单播转发与房主 ping，v525 含端口）。 */
    public static java.util.List<InetSocketAddress> getMemberAddresses() {
        return new java.util.ArrayList<>(memberAddrs.values());
    }

    /** v534：房主向全体成员主动推送玩家列表（开麦状态等即时同步）。 */
    private static void pushPlayerListToMembers() {
        DatagramSocket s = hostSocket;
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            // v546：包成与心跳响应一致的 {"players":[...],"gameOpen":...}。
            // 此前直接发 JSONArray，成员端 parsePlayers 用 JSONObject 解析必失败
            // ——房主状态变化从未即时同步到成员端（v534 遗留 bug）
            JSONObject wrap = new JSONObject();
            wrap.put("players", buildPlayerListJson());
            wrap.put("gameOpen", isMcWorldOpen());
            String json = wrap.toString();
            byte[] out = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (InetSocketAddress addr : memberAddrs.values()) {
                try {
                    s.send(new DatagramPacket(out, out.length, addr.getAddress(), addr.getPort()));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
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
        // v547：房主详情卡查看权限广播
        host.put("viewPerm", selfViewPerm);
        // v549：房主最近在线时间广播
        host.put("lastActive", PlayerIdentity.getLastActiveStatic());
        putAvatar(host, PlayerIdentity.getCurrentAvatarUrl());
        putStr(host, "xuid", PlayerIdentity.getCurrentXuid());
        putStr(host, "msUser", PlayerIdentity.getCurrentMsUser());
        try {
            host.put("playMinutes", PlayerIdentity.getCurrentPlayMinutes());
        } catch (Exception ignored) {
        }
        arr.put(host);
        for (Player p : players.values()) {
            JSONObject o = new JSONObject();
            o.put("player", p.name);
            o.put("clientId", p.clientId);
            o.put("isRoomHost", false);
            o.put("micState", micOf(p.clientId));
            // v546：禁麦状态随名单广播（成员端冗余通道——c:mute 单播丢失也能
            // 靠 2s 心跳响应恢复禁麦）
            o.put("muted", hostMuted.contains(p.clientId));
            // v547：成员详情卡查看权限广播
            Integer vp = memberViewPerm.get(p.clientId);
            o.put("viewPerm", vp == null ? 2 : vp);
            // v549：成员最近在线时间广播
            Long la = memberLastActive.get(p.clientId);
            if (la != null) {
                o.put("lastActive", la);
            }
            putAvatar(o, memberAvatar.get(p.clientId));
            putStr(o, "xuid", memberXuid.get(p.clientId));
            putStr(o, "msUser", memberMsUser.get(p.clientId));
            Long pm = memberPlay.get(p.clientId);
            if (pm != null) {
                try {
                    o.put("playMinutes", pm);
                } catch (Exception ignored) {
                }
            }
            String ip = addrOf(p.clientId);
            if (ip != null) {
                o.put("addr", ip);
            }
            arr.put(o);
        }
        return arr;
    }

    /** 非空字符串写入（v544）。 */
    private static void putStr(JSONObject o, String key, String v) {
        if (v != null && !v.isEmpty()) {
            try {
                o.put(key, v);
            } catch (Exception ignored) {
            }
        }
    }

    /** 头像 URL 非空才写入（org.json put null 会删 key）。 */
    private static void putAvatar(JSONObject o, String url) {
        if (url != null && !url.isEmpty()) {
            try {
                o.put("avatarUrl", url);
            } catch (Exception ignored) {
            }
        }
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
    /** v534：成员端"状态变更即时推送"——置位后心跳循环提前发送。 */
    private static volatile boolean heartbeatKick;
    /** v534：最近房主地址（退出时发 c:bye 即时通知房主）。 */
    private static volatile InetSocketAddress lastHostTarget;
    /** 房主端自己的麦克风/状态变化时向成员即时推送玩家列表（v534）。 */
    private static volatile boolean hostPushRequested;

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

    /**
     * v534：本地联机状态变化（麦克风模式等）→ 即时同步对端：
     * 成员端置 kick 让心跳立即发出；房主端向全体成员主动推送玩家列表。
     */
    public static void notifyLocalStateChanged() {
        if (isHost) {
            hostPushRequested = true;
        } else {
            heartbeatKick = true;
        }
    }

    public static synchronized void stopClient() {
        // v546：退出房间清除禁麦锁（否则残留 mutedByHost 会带到下一个房间）
        org.levimc.launcher.core.online.voice.VoiceEngine.setMutedByHostStatic(false);
        // v534：退出前即时通知房主（c:bye），房主端立刻刷新玩家列表
        InetSocketAddress host = lastHostTarget;
        String cid = currentClientId;
        if (host != null && cid != null) {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] out = ("c:bye\0{\"clientId\":\"" + cid + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                s.send(new DatagramPacket(out, out.length, host.getAddress(), host.getPort()));
            } catch (Exception ignored) {
            }
        }
        clientRunning = false;
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
        if (clientListener != null) {
            removeListener(clientListener);
            clientListener = null;
        }
        lastHostTarget = null;
        currentClientId = null;
    }

    /** v534：最近成员端 clientId（c:bye 用）。 */
    private static volatile String currentClientId;
    /** v562：成员端首包日志一次性标志（文件日志防刷）。 */
    private static volatile boolean firstRosterLogged;

    private static void clientLoop(String hostIp, String name, String clientId, Listener l) {
        while (clientRunning) {
            try (DatagramSocket s = new DatagramSocket(null)) {
                // v566：成员心跳 socket 显式绑 VPN 网络，同时等待 VPN 就绪，
                // 解决"socket 创建早于 TUN 建立"绑旧网络的竞态
                android.net.Network vpnNet = EasyTierManager.waitForVpnNetwork(15_000);
                if (vpnNet != null) {
                    try {
                        vpnNet.bindSocket(s);
                        org.levimc.launcher.util.OnlineDebugLog.log("成员心跳已绑定 VPN 网络");
                    } catch (Exception be) {
                        Log.w(TAG, "成员 socket 绑定 VPN 网络失败", be);
                    }
                } else {
                    org.levimc.launcher.util.OnlineDebugLog.log("警告：15s 未见 VPN 网络，成员心跳走默认网络");
                }
                InetSocketAddress target = new InetSocketAddress(hostIp, PORT);
                // v534：记录房主地址与本机 clientId（退出时发 c:bye）
                lastHostTarget = target;
                currentClientId = clientId;
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
                                // v530：房主个体禁麦指令（单播给我）→ 语音引擎处理
                                try {
                                    JSONObject mq = new JSONObject(
                                            text.substring(text.indexOf('\0') + 1));
                                    boolean mute = mq.optBoolean("mute", false);
                                    Log.i(TAG, "收到房主禁麦指令: mute=" + mute);
                                    org.levimc.launcher.core.online.voice.VoiceEngine
                                            .setMutedByHostStatic(mute);
                                } catch (Exception ignored) {
                                }
                            } else if (text.startsWith("c:lan\0")) {
                                // v584：房主游戏 RakNet 公告转发（异地局域网入口桥）
                                try {
                                    JSONObject lq = new JSONObject(
                                            text.substring(text.indexOf('\0') + 1));
                                    String b64 = lq.optString("data", "");
                                    int wp = lq.optInt("worldPort", 0);
                                    // v588：data 可空（1.26 服务器不广播，成员侧合成
                                    // pong）；nick 为成员合成 pong 的世界名
                                    String nick = lq.optString("nick", "");
                                    org.levimc.launcher.util.OnlineDebugLog.log(
                                            "RoomCenter(成员): 收到 c:lan wp=" + wp
                                                    + " from " + p.getAddress().getHostAddress());
                                    // v590：wp=0 也启动（平板 SELinux 读不到端口表，
                                    // 转发先走 19132，房主回包源端口动态学习）
                                    byte[] reply = b64.isEmpty() ? new byte[0]
                                            : android.util.Base64.decode(b64,
                                                    android.util.Base64.NO_WRAP);
                                    LanRelayBridge.onAnnounce(reply,
                                            p.getAddress().getHostAddress(), wp, nick);
                                } catch (Exception ignored) {
                                }
                            } else if (text.startsWith("c:invite\0")) {
                                // v560：房主邀请进入世界（URI 一键直达）→ 广播给监听器
                                try {
                                    JSONObject iq = new JSONObject(
                                            text.substring(text.indexOf('\0') + 1));
                                    String hip = iq.optString("hostIp", "");
                                    int port = iq.optInt("port", 19132);
                                    if (!hip.isEmpty()) {
                                        Log.i(TAG, "收到房主世界邀请: " + hip + ":" + port);
                                        for (Listener lst : listeners) {
                                            try {
                                                lst.onInvite(hip, port);
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            } else {
                                List<Player> list = parsePlayers(text);
                                // v562：首次收到房主响应写文件日志（vivo logcat 不可用）
                                if (!firstRosterLogged) {
                                    firstRosterLogged = true;
                                    org.levimc.launcher.util.OnlineDebugLog.log(
                                            "成员端收到房主首个响应，名单 " + list.size() + " 人");
                                }
                                long rtt = -1;
                                try {
                                    JSONObject o = new JSONObject(text);
                                    long sent = o.optLong("time", 0);
                                    if (sent > 0) {
                                        rtt = System.currentTimeMillis() - sent;
                                    }
                                } catch (Exception ignored) {
                                }
                                // v531：仅含玩家名单的响应才广播——c:ping 响应
                                // （无 players 数组）会产生空名单，成员端据此误判
                                // "房主离开了房间"（rtt 更新由心跳响应承担）
                                if (!list.isEmpty()) {
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
                // v565：心跳发送计数——每 10 次写文件日志（异地排查：
                // 若计数增长但房主端无"收到首个包"，说明包在虚拟网络内丢失）
                int heartbeatSent = 0;
                while (clientRunning) {
                    try {
                        long t0 = System.currentTimeMillis();
                        String req = "c:ping\0{\"time\":" + t0 + "}";
                        byte[] out = req.getBytes("UTF-8");
                        s.send(new DatagramPacket(out, out.length, target));
                        // v527：心跳动态读当前昵称（Xbox 登录后自动换名）
                        // v528：附带麦克风模式（房主端同步全员状态）
                        // v529：附带头像 URL
                        String hb = "c:player\0{\"clientId\":\"" + clientId
                                + "\",\"playerName\":\"" + escape(displayName(name))
                                + "\",\"micState\":"
                                + org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode()
                                + ",\"avatarUrl\":\""
                                + escape(PlayerIdentity.getCurrentAvatarUrl())
                                + "\",\"xuid\":\"" + escape(PlayerIdentity.getCurrentXuid())
                                + "\",\"msUser\":\"" + escape(PlayerIdentity.getCurrentMsUser())
                                + "\",\"playMinutes\":" + PlayerIdentity.getCurrentPlayMinutes()
                                // v547：详情卡查看权限随心跳广播
                                + ",\"viewPerm\":" + selfViewPerm
                                // v549：最近在线时间随心跳广播
                                + ",\"lastActive\":" + PlayerIdentity.getLastActiveStatic()
                                + "}";
                        byte[] out2 = hb.getBytes("UTF-8");
                        s.send(new DatagramPacket(out2, out2.length, target));
                    } catch (Exception ignored) {
                    }
                    heartbeatSent++;
                    if (heartbeatSent % 10 == 0) {
                        org.levimc.launcher.util.OnlineDebugLog.log("成员已发心跳 "
                                + heartbeatSent + " 次 → " + target.getAddress().getHostAddress()
                                + ":" + target.getPort());
                    }
                    // v534：kick 机制——状态变更时提前结束等待立即发心跳
                    try {
                        for (int i = 0; i < HEARTBEAT_MS / 500 && clientRunning; i++) {
                            Thread.sleep(500);
                            if (heartbeatKick) {
                                heartbeatKick = false;
                                break;
                            }
                        }
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
                        String av = p.optString("avatarUrl", null);
                        String xu = p.optString("xuid", null);
                        String mu = p.optString("msUser", null);
                        String cid = p.optString("clientId", "?");
                        // v546：名单里自己的 muted 字段 = 房主权威禁麦状态。
                        // c:mute 单播丢失时靠它 2s 内恢复（冗余通道）
                        if (cid.equals(currentClientId)) {
                            org.levimc.launcher.core.online.voice.VoiceEngine
                                    .setMutedByHostStatic(p.optBoolean("muted", false));
                        }
                        out.add(new Player(p.optString("player", "?"),
                                cid, p.optBoolean("isRoomHost", false),
                                addr == null || addr.isEmpty() ? null : addr,
                                p.optInt("micState", 0),
                                av == null || av.isEmpty() ? null : av,
                                xu == null || xu.isEmpty() ? null : xu,
                                mu == null || mu.isEmpty() ? null : mu,
                                p.optLong("playMinutes", 0),
                                p.optInt("viewPerm", 2),
                                p.optLong("lastActive", 0)));
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
                org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode(),
                PlayerIdentity.getCurrentAvatarUrl(),
                PlayerIdentity.getCurrentXuid(), PlayerIdentity.getCurrentMsUser(),
                PlayerIdentity.getCurrentPlayMinutes(), selfViewPerm,
                PlayerIdentity.getLastActiveStatic()));
        for (Player p : players.values()) {
            Long pm = memberPlay.get(p.clientId);
            Integer vp = memberViewPerm.get(p.clientId);
            Long la = memberLastActive.get(p.clientId);
            out.add(new Player(p.name, p.clientId, false, addrOf(p.clientId), micOf(p.clientId),
                    memberAvatar.get(p.clientId),
                    memberXuid.get(p.clientId), memberMsUser.get(p.clientId),
                    pm == null ? 0 : pm, vp == null ? 2 : vp,
                    la == null ? 0 : la));
        }
        return out;
    }

    /** 该成员是否被房主禁麦（v530）。 */
    public static boolean isMuted(String clientId) {
        return hostMuted.contains(clientId);
    }

    /**
     * v530 个体禁麦/解除（仅房主可调）：c:mute 单播给目标成员。
     * 只能禁麦/解除，不能强制开麦（解除后成员仍闭麦直到自己开）。
     */
    public static void sendMute(String clientId, boolean mute) {
        if (mute) {
            hostMuted.add(clientId);
        } else {
            hostMuted.remove(clientId);
        }
        DatagramSocket s = hostSocket;
        if (s != null && !s.isClosed()) {
            InetSocketAddress addr = memberAddrs.get(clientId);
            if (addr != null) {
                String msg = "c:mute\0{\"clientId\":\"" + clientId + "\",\"mute\":" + mute + "}";
                byte[] out = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try {
                    s.send(new DatagramPacket(out, out.length, addr.getAddress(), addr.getPort()));
                    Log.i(TAG, "成员禁麦单播已发: " + clientId + " mute=" + mute
                            + " -> " + addr);
                } catch (Exception e) {
                    Log.w(TAG, "成员禁麦单播失败: " + clientId, e);
                }
            } else {
                Log.w(TAG, "成员禁麦目标地址缺失: " + clientId + "（改走名单推送）");
            }
        }
        Log.i(TAG, "成员禁麦: " + clientId + " mute=" + mute);
        // v546：无论单播是否发出，都主动推送一次玩家名单——muted 字段随
        // 名单广播，成员端 2s 内必然收到（c:mute 单播丢失的冗余通道）
        hostPushRequested = true;
        // 房主本地玩家列表刷新（被禁麦成员的 🔇 标记即时更新）
        notifyListeners(snapshot(), -1);
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
