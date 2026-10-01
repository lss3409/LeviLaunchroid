package org.levimc.launcher.core.online;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.easytier.jni.EasyTierJNI;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.activities.OnlineActivity;

/**
 * 联机组网 VPN 服务（v495）：建立 TUN 接口并交给 EasyTier 内核收发。
 * 两段式：EasyTierManager 先用 DHCP 模式跑内核拿到虚拟 IP，
 * 再把该 IP 作为 TUN 地址启动本服务，setTunFd 挂接。
 *
 * v515 看门狗：实测两台设备都出现过"VPN 被系统吊销/隧道消失但服务还活着"——
 * 平板（ZUI）与手机（OriginOS，Clash 自动复活抢 VPN 坑）均复现。
 * 修复：onRevoke 标记 + 每秒监测 TUN 存活，丢失即自动重建（重挂 setTunFd，
 * 内核实例仍在进程内无需重启）；参数持久化，系统杀服务后 START_STICKY
 * 重启（intent 为 null）也能自愈。
 */
public final class EasyTierVpnService extends VpnService {

    private static final String TAG = "EasyTierVpn";
    private static final String CHANNEL_ID = "levimc_vpn";
    private static final int NOTIF_ID = 0xE451;
    private static final String PREFS = "levimc_vpn_state";
    private static final int MAX_REESTABLISH = 3;

    /** 由 Manager 启动时传入：EasyTier 实例名。 */
    public static final String EXTRA_INSTANCE = "instance";
    /** 虚拟 IP（可带 /前缀长度，如 10.144.1.2/16）。 */
    public static final String EXTRA_IPV4 = "ipv4";
    /** EasyTier 虚拟网段路由（决定哪些流量进 TUN）。 */
    public static final String EXTRA_CIDRS = "cidrs";
    /** 停止信号：VpnService 被系统 binder 绑定，stopService 不会销毁——
     *  必须再次 startService 带本 action，服务内主动关 tun 才能终止 VPN。 */
    public static final String ACTION_STOP = "org.levimc.launcher.action.STOP_VPN";

    private ParcelFileDescriptor tun;
    private volatile boolean running;
    private volatile boolean revoked;
    private String lastInstance;
    private String lastIpv4;
    private String[] lastCidrs;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "收到停止信号，关闭 TUN");
            running = false;
            closeTun();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        String instance = intent == null ? null : intent.getStringExtra(EXTRA_INSTANCE);
        String ipv4 = intent == null ? null : intent.getStringExtra(EXTRA_IPV4);
        String[] cidrs = intent == null ? null : intent.getStringArrayExtra(EXTRA_CIDRS);
        if (instance != null && ipv4 != null) {
            lastInstance = instance;
            lastIpv4 = ipv4;
            lastCidrs = cidrs;
            persistState();
        } else if (lastInstance == null) {
            restoreState();
        }
        if (lastInstance == null || lastIpv4 == null) {
            Log.e(TAG, "缺少必要参数且无持久化状态，放弃启动");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable fg) {
            // v568：app 处于后台时 Android 15+/ZUI 拒绝前台服务启动
            // （ForegroundServiceStartNotAllowedException）——不崩溃，
            // 停止并等 Manager 看门狗在 app 前台时重拉（tombstone 567 根因）
            Log.w(TAG, "startForeground 被拒（后台限制），等看门狗重拉", fg);
            org.levimc.launcher.util.OnlineDebugLog.log("startForeground 被拒（app 在后台）: "
                    + fg.getClass().getSimpleName());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        Thread t = new Thread(() -> runTun(lastInstance, lastIpv4, lastCidrs), "easytier-vpn");
        t.setDaemon(true);
        t.start();
        return START_STICKY;
    }

    private void persistState() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            e.putString("instance", lastInstance);
            e.putString("ipv4", lastIpv4);
            StringBuilder sb = new StringBuilder();
            if (lastCidrs != null) {
                for (String c : lastCidrs) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(c);
                }
            }
            e.putString("cidrs", sb.toString());
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreState() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            lastInstance = sp.getString("instance", null);
            lastIpv4 = sp.getString("ipv4", null);
            String joined = sp.getString("cidrs", "");
            if (!joined.isEmpty()) {
                lastCidrs = joined.split(",");
            }
        } catch (Exception ignored) {
        }
    }

    private void runTun(String instance, String ipv4, String[] cidrs) {
        int reestablishCount = 0;
        try {
            // v568：establish 失败（未授权/系统拦截）不再 stopSelf——停止中
            // 实例被看门狗重拉时 startForeground 会抛异常崩进程（tombstone
            // 567）。改循环重试：用户稍后授权（如建房流程补的 prepare 弹窗）
            // 时自动恢复，无需重启服务。
            while (running) {
                tun = establishTun(ipv4, cidrs);
                if (tun != null) {
                    break;
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (tun == null) {
                Log.e(TAG, "TUN 建立失败且服务已停止");
                return;
            }
            // v568：setTunFd 失败也重试（内核实例可能刚重建尚未就绪），
            // 不 stopSelf（停止中实例被重拉会崩进程，tombstone 567 教训）
            int rc;
            while (running) {
                rc = EasyTierJNI.setTunFd(instance, tun.getFd());
                Log.i(TAG, "setTunFd(" + instance + ") = " + rc + " ip=" + ipv4);
                if (rc == 0) {
                    break;
                }
                Log.e(TAG, "setTunFd 失败: " + EasyTierJNI.getLastError());
                org.levimc.launcher.util.OnlineDebugLog.log("setTunFd 失败 rc=" + rc
                        + ": " + EasyTierJNI.getLastError() + "，3s 后重试");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            running = true;
            // v515 看门狗：TUN 被吊销（其他 VPN 抢占/系统回收）后自动重建
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                if (!running) {
                    break;
                }
                boolean lost = revoked || !tunExists();
                if (!lost) {
                    reestablishCount = 0;
                    continue;
                }
                revoked = false;
                reestablishCount++;
                Log.w(TAG, "检测到 VPN 隧道丢失，重建 TUN（第 " + reestablishCount + " 次）");
                if (reestablishCount > MAX_REESTABLISH) {
                    Log.e(TAG, "TUN 重建连续失败 " + MAX_REESTABLISH + " 次，放弃（可能授权被撤销）");
                    stopSelf();
                    return;
                }
                closeTun();
                tun = establishTun(lastIpv4, lastCidrs);
                if (tun == null) {
                    continue;
                }
                int rc2 = EasyTierJNI.setTunFd(lastInstance, tun.getFd());
                if (rc2 != 0) {
                    Log.e(TAG, "重建 setTunFd 失败: " + EasyTierJNI.getLastError());
                    closeTun();
                    continue;
                }
                reestablishCount = 0;
                Log.i(TAG, "TUN 已自动重建");
            }
        } catch (Throwable t) {
            Log.e(TAG, "TUN 设置失败", t);
            org.levimc.launcher.util.OnlineDebugLog.log("runTun 异常: "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            stopSelf();
        } finally {
            running = false;
            closeTun();
        }
    }

    private ParcelFileDescriptor establishTun(String ipv4, String[] cidrs) {
        String ip = ipv4;
        int len = 16;
        int slash = ipv4.indexOf('/');
        if (slash > 0) {
            ip = ipv4.substring(0, slash);
            try {
                len = Integer.parseInt(ipv4.substring(slash + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        Builder builder = new Builder()
                .setSession(getString(R.string.online_vpn_session))
                // 关键：允许非 VPN 网段流量回落真实网络。Android VPN 建立后
                // 成为默认网络，之后启动的应用（如 MC）socket 绑 VPN 网络，
                // 无 allowBypass 时局域网广播/组播被静默丢弃——同网段
                // 局域网联机入口消失。回落不影响虚拟网段流量（照走 VPN）。
                // v566 注意：不能 addDisallowedApplication——游戏与本应用
                // 同 UID，排除后游戏进程连房主虚拟 IP 会走真实网络失败；
                // EasyTier 内核在移动端没有独立 socket（全部包写进 TUN fd），
                // 排除也救不了它。内核中继包经 TUN→allowBypass 回落真实
                // 网络（TUN 活着时正常；TUN 死亡由 tunExists 精确检测 +
                // 看门狗自动重建兜底）。
                .allowBypass()
                .addAddress(ip, len)
                .addDnsServer("223.5.5.5")
                .addDnsServer("114.114.114.114");
        // 只把 EasyTier 虚拟网段路由进 TUN，不影响正常上网流量
        if (cidrs != null) {
            for (String cidr : cidrs) {
                int s = cidr.indexOf('/');
                if (s > 0) {
                    try {
                        builder.addRoute(cidr.substring(0, s), Integer.parseInt(cidr.substring(s + 1)));
                    } catch (Exception e) {
                        Log.w(TAG, "无效 CIDR: " + cidr, e);
                    }
                }
            }
        }
        // v562：兜底网段无条件加（不再走 else）——poll 的 cidrs 在异地/部分
        // 网络下路由学习不全（成员端可能只有自己 DHCP 网段），发往房主
        // 固定网段 10.144.x.x 的心跳不进 TUN 直接丢失，表现=组网成功但
        // 房间中心不通（"未找到房主"/双方只显示 1 人，异地高发、局域网
        // 恰好路由学全所以一直正常）。10.144.0.0/16（房主固定网段）+
        // 10.126.126.0/24（内核 DHCP 默认网段）两边 TUN 都要路由才能互通。
        builder.addRoute("10.144.0.0", 16);
        builder.addRoute("10.126.126.0", 24);
        org.levimc.launcher.util.OnlineDebugLog.log("TUN 路由: ipv4=" + ipv4
                + " cidrs=" + java.util.Arrays.toString(cidrs)
                + " + 兜底 10.144.0.0/16,10.126.126.0/24");
        try {
            ParcelFileDescriptor fd = builder.establish();
            if (fd == null) {
                // v567：区分失败原因——establish 返回 null 通常是系统拒绝
                // （无 VPN 授权/ZUI 上层拦截），写文件日志（logcat 会冻结）
                org.levimc.launcher.util.OnlineDebugLog.log("TUN establish 返回 null（无授权或被系统拦截）");
            }
            return fd;
        } catch (Throwable t) {
            Log.e(TAG, "establish 异常", t);
            org.levimc.launcher.util.OnlineDebugLog.log("TUN establish 异常: "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    /** TUN 接口是否存在（VpnService 的 tunN；tunl0 等内核隧道不算）。 */
    private boolean tunExists() {
        try {
            // v567：不用 /proc/net/dev——SELinux 拒绝 untrusted_app 读
            // proc_net（avc denied 实测），且 contains("tun") 会误匹配
            // 内核 tunl0 隧道设备导致看门狗失明（v566 教训）
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                String n = ifs.nextElement().getName();
                if (n.matches("tun[0-9]{1,2}")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // 读不到就当健康，避免误判重建
            return true;
        }
    }

    @Override
    public void onRevoke() {
        revoked = true;
        Log.w(TAG, "VPN 被系统吊销（其他 VPN 抢占或系统回收），看门狗将自动重建");
        org.levimc.launcher.util.OnlineDebugLog.log("VPN 被系统吊销，看门狗重建中");
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.online_vpn_notif_channel), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, OnlineActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_online)
                .setContentTitle(getString(R.string.online_vpn_notif_title))
                .setContentText(getString(R.string.online_vpn_notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        closeTun();
        Log.i(TAG, "VPN 服务销毁");
        org.levimc.launcher.util.OnlineDebugLog.log("VpnService onDestroy（系统杀服务或主动停止）");
        super.onDestroy();
    }

    private void closeTun() {
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
    }
}
