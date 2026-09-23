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
     * ① IPv4 局域网（同网段直连）② IPv6 公网（异地/流量跨网联机，EUI-64
     * 稳定地址 2409:8a55:10a0:1050:ba31:b290:4870:9d8e——电脑重启不变）。
     * 注意：IPv6 前缀 2409:8a55:10a0:1050 由运营商分配，若路由器重启后
     * 前缀变化需同步更新这里；且需路由器/光猫放行 IPv6 入站（防火墙）。
     */
    public static final String[] FIXED_RELAYS = {
            "tcp://192.168.1.167:11010",
            "tcp://[2409:8a55:10a0:1050:ba31:b290:4870:9d8e]:11010",
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
