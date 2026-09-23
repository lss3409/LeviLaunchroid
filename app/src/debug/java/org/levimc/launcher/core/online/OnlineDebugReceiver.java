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
                    EasyTierManager.get().join(context, net, secret, null, found);
                }, "lan-discover").start();
            }
        } else if (ACTION_STOP.equals(action)) {
            EasyTierManager.get().stop(context);
        }
    }
}
