package org.levimc.launcher.core.online;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 调试后门（仅 debug 构建，release manifest 不注册）：
 * 部分设备（vivo）adb 输入注入被系统干扰，无法通过 UI 输入邀请码，
 * 用广播直接触发加入流程供双机联调。
 *
 * adb shell am broadcast -a org.levimc.launcher.DEBUG_JOIN --es code YNZEU61D2206HXRG
 * adb shell am broadcast -a org.levimc.launcher.DEBUG_STOP
 */
public final class OnlineDebugReceiver extends BroadcastReceiver {

    public static final String ACTION_JOIN = "org.levimc.launcher.DEBUG_JOIN";
    public static final String ACTION_STOP = "org.levimc.launcher.DEBUG_STOP";
    /** 设置中转（逗号分隔可多个；空串清除）。 */
    public static final String ACTION_SET_RELAY = "org.levimc.launcher.DEBUG_SET_RELAY";
    /** UDP 广播探测（验证 allowBypass：VPN 挂载后新 socket 的广播能否走真实网络）。 */
    public static final String ACTION_PROBE = "org.levimc.launcher.DEBUG_PROBE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i("OnlineDebug", "收到调试广播: " + action);
        if (ACTION_JOIN.equals(action)) {
            String code = intent.getStringExtra("code");
            // 可选：直连 peer（局域网/自建中转），如 tcp://192.168.1.234:11010
            String peer = intent.getStringExtra("peer");
            // 广播传入的裸 16 位码无短横线分组，先格式化再解析
            InviteCode.Result r = InviteCode.parse(InviteCode.formatInput(code));
            if (!r.ok()) {
                Log.e("OnlineDebug", "调试码无效: " + code);
                return;
            }
            java.util.List<String> peers;
            if (peer != null && !peer.isEmpty()) {
                peers = java.util.Collections.singletonList(peer);
                EasyTierManager.get().join(context, r.parsed.networkName, r.parsed.networkSecret, null, peers);
            } else {
                // 与 UI 加入流程一致：先局域网发现房主
                String net = r.parsed.networkName;
                String secret = r.parsed.networkSecret;
                new Thread(() -> {
                    java.util.List<String> found = LanDiscovery.discover(net, 3000);
                    Log.i("OnlineDebug", "局域网发现房主: " + found);
                    EasyTierManager.get().join(context, net, secret,
                            (state, detail) -> {
                                if (state == EasyTierManager.State.CONNECTED) {
                                    // 调试路径无 UI，组网成功后直接启动房间中心心跳
                                    String nick = PlayerIdentity.getNickname(context);
                                    String cid = PlayerIdentity.getClientId(context);
                                    RoomCenter.startClient("10.144.144.144", nick, cid, null);
                                    Log.i("OnlineDebug", "房间中心客户端已启动: " + nick);
                                }
                            }, found);
                }, "lan-discover").start();
            }
        } else if (ACTION_STOP.equals(action)) {
            EasyTierManager.get().stop(context);
        } else if (ACTION_SET_RELAY.equals(action)) {
            String raw = intent.getStringExtra("uri");
            java.util.List<String> uris = new java.util.ArrayList<>();
            if (raw != null && !raw.isEmpty()) {
                for (String part : raw.split(",")) {
                    String u = RelayStore.normalize(part);
                    if (!u.isEmpty()) {
                        uris.add(u);
                    }
                }
            }
            RelayStore.save(context, uris);
            Log.i("OnlineDebug", "中转已设置: " + uris);
        } else if (ACTION_PROBE.equals(action)) {
            new Thread(() -> {
                // 空网络名 = 不过滤，收所有应答（验证广播链路本身）
                java.util.List<String> found = LanDiscovery.discover("", 3000);
                Log.i("OnlineDebug", "广播探测结果（allowBypass 验证）: " + found);
            }, "bcast-probe").start();
        }
    }
}
