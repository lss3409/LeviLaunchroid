package org.levimc.launcher.core.online;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中转服务器（v504 简化）：官方公共节点已下线、社区公益节点不稳定，
 * 产品固定使用 lss3409 自建中转（用户电脑跑 easytier-core）。
 * 地址硬编码在此——用户电脑做公网映射（frp/DDNS 把 11010 TCP/UDP
 * 映射出来）后更新 FIXED_RELAY 即可，无需用户配置。
 */
public final class RelayStore {

    /** 服务器支持：lss3409。 */
    public static final String SUPPORTED_BY = "lss3409";

    /**
     * 固定中转 peers（lss3409 电脑，公共中继模式）：
     * ① IPv4 局域网（同网段直连，最低延迟）
     * ② 光猫公网 IPv4 端口转发（异地/流量跨网联机：光猫 11010 TCP+UDP
     *    映射到 192.168.1.167）。61.223.109.213 是 PPPoE 动态地址，
     *    光猫重连后可能变化，变化后更新此处即可（后续可接 DDNS 自动同步）。
     * 已移除原 IPv6 公网条目（2409:8a55:...，2026-09-24 实测不可达）：
     * 光猫有线/WiFi 间 IPv6 隔离，且外部 IPv6 入站被拦。
     */
    public static final String[] FIXED_RELAYS = {
            "tcp://192.168.1.167:11010",
            "tcp://61.223.109.213:11010",
    };

    private RelayStore() {
    }

    /** 组网时合并的中转列表。 */
    public static List<String> load(Context ctx) {
        List<String> out = new ArrayList<>(FIXED_RELAYS.length);
        Collections.addAll(out, FIXED_RELAYS);
        return out;
    }

    /** 兼容旧版本存储迁移：清理已无用的用户自建配置。 */
    public static void cleanupLegacy(Context ctx) {
        try {
            ctx.getApplicationContext().getSharedPreferences("levimc_relay", Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Exception ignored) {
        }
    }
}
