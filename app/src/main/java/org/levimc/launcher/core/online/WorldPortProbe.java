package org.levimc.launcher.core.online;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

/**
 * v583：房主世界端口探测——1.26 起 MC 局域网世界用随机端口（实测
 * 55298/51315/59398/48519 等，不再固定 19132），邀请深链必须带
 * 真实端口。游戏与本应用同进程同 uid，读 /proc/self/net/udp 的
 * 监听端口（SELinux 允许读自己进程，读 /proc/net/* 会被拒），
 * 与上次快照做差集：新出现的 >1024 非保留端口即世界端口。
 */
public final class WorldPortProbe {

    /** 本应用模块固定端口（心跳/语音/EasyTier/局域网发现）。 */
    private static final Set<Integer> RESERVED = new HashSet<>();

    static {
        RESERVED.add(18090); // RoomCenter
        RESERVED.add(18091); // VoiceEngine
        RESERVED.add(11010); // EasyTier listener
        RESERVED.add(11011); // LanDiscovery
    }

    private static final Set<Integer> lastSnapshot = new HashSet<>();

    private WorldPortProbe() {
    }

    /** 探测新出现的世界端口；无新端口返回 0。 */
    public static synchronized int probe() {
        Set<Integer> now = readSelfListenPorts();
        int candidate = 0;
        for (int p : now) {
            if (p <= 1024 || RESERVED.contains(p)) {
                continue;
            }
            if (!lastSnapshot.contains(p)) {
                candidate = p; // 取最后一个新端口（世界端口后开）
            }
        }
        lastSnapshot.clear();
        lastSnapshot.addAll(now);
        return candidate;
    }

    /** 上次探测到的世界端口（无新端口时复用——进世界后点邀请多次）。 */
    private static volatile int lastWorldPort;

    /** 获取世界端口：有新端口取新值并缓存，否则返回缓存值。 */
    public static synchronized int getWorldPort() {
        int p = probe();
        if (p > 0) {
            lastWorldPort = p;
        }
        return lastWorldPort;
    }

    private static Set<Integer> readSelfListenPorts() {
        Set<Integer> out = new HashSet<>();
        for (String path : new String[]{"/proc/self/net/udp", "/proc/self/net/udp6"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                r.readLine(); // header
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 8) {
                        continue;
                    }
                    String local = f[1];
                    String st = f[3];
                    if (!"07".equals(st)) { // 只取监听态
                        continue;
                    }
                    int idx = local.lastIndexOf(':');
                    if (idx < 0) {
                        continue;
                    }
                    try {
                        out.add(Integer.parseInt(local.substring(idx + 1), 16));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
