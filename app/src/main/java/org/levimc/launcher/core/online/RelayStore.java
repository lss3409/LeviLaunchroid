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
     * 固定中转 peers：
     * ① lss3409 电脑局域网口（同网段直连，最低延迟）
     * ② 腾讯云轻量广州服务器（公网中转：异地/流量跨网联机入口，
     *    运行 easytier-core 2.6.4 公共中继，systemd 自启；
     *    控制台防火墙已放行 11010 TCP+UDP）。
     * 注：用户宽带为中国移动 CGNAT（光猫 WAN 是 172.16.64.x 私网、
     * 无公网 IPv4），端口映射不可能，公网入口只能靠云服务器。
     */
    public static final String[] FIXED_RELAYS = {
            "tcp://192.168.1.167:11010",
            "tcp://111.230.150.198:11010",
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
