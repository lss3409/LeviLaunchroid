package org.levimc.launcher.core.online;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 中转服务器配置（v498，SharedPreferences 持久化）。
 * 官方公共节点已下线，跨网段联机靠自建中转（easytier-core 公网实例），
 * 配置为 peer URI 列表（tcp://ip:port），加入/创建时合并进 peer 列表。
 */
public final class RelayStore {

    private static final String PREFS = "levimc_relay";
    private static final String KEY_URIS = "uris";

    /**
     * 内置公益中转节点（2026-09 社区活跃维护：风起社公益平台 EasyTier Public Relay，
     * 日本 2Gbps，公益免费，上限 100Mbps；来源 EasyTier Discussions #2429）。
     * 公益节点可能随时变动/下线——UI 提供连通测试与自建入口兜底。
     */
    public static final String[][] PRESET_RELAYS = {
            {"风起社公益节点 · 日本", "tcp://161.33.207.13:51010"},
            {"风起社公益节点 · 日本 (UDP)", "udp://161.33.207.13:51010"},
            {"风起社公益节点 · 日本 (WS)", "ws://161.33.207.13:51011"},
    };

    private RelayStore() {
    }

    /** 读取配置的中转列表（永不为 null）。 */
    public static List<String> load(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_URIS, "");
            if (raw != null && !raw.isEmpty()) {
                for (String uri : raw.split(",")) {
                    String t = uri.trim();
                    if (!t.isEmpty() && !out.contains(t)) {
                        out.add(t);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context ctx, List<String> uris) {
        List<String> clean = new ArrayList<>();
        if (uris != null) {
            for (String u : uris) {
                String t = u == null ? "" : u.trim();
                if (!t.isEmpty() && !clean.contains(t)) {
                    clean.add(t);
                }
            }
        }
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_URIS, String.join(",", clean)).apply();
    }

    /** 规范化用户输入：无协议前缀自动补 tcp://，无端口补 11010。 */
    public static String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (!s.contains("://")) {
            s = "tcp://" + s;
        }
        if (!s.matches(".+:\\d+")) {
            s = s + ":11010";
        }
        return s;
    }
}
