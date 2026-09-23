package org.levimc.launcher.core.online;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
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
 */
public final class EasyTierVpnService extends VpnService {

    private static final String TAG = "EasyTierVpn";
    private static final String CHANNEL_ID = "levimc_vpn";
    private static final int NOTIF_ID = 0xE451;

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
        if (instance == null || ipv4 == null) {
            Log.e(TAG, "缺少必要参数 instance=" + instance + " ipv4=" + ipv4);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        Thread t = new Thread(() -> runTun(instance, ipv4, cidrs), "easytier-vpn");
        t.setDaemon(true);
        t.start();
        return START_STICKY;
    }

    private void runTun(String instance, String ipv4, String[] cidrs) {
        try {
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
            } else {
                // 虚拟网段覆盖：10.144.0.0/16（房主固定网段）+
                // 10.126.126.0/24（内核 DHCP 默认网段，OSPF 未同步时的分配结果）——
                // 成员可能拿到任一网段，两边 TUN 都要路由才能双向互通。
                builder.addRoute("10.144.0.0", 16);
                builder.addRoute("10.126.126.0", 24);
            }
            tun = builder.establish();
            if (tun == null) {
                Log.e(TAG, "TUN 建立失败（可能用户拒绝了 VPN 授权）");
                stopSelf();
                return;
            }
            int rc = EasyTierJNI.setTunFd(instance, tun.getFd());
            Log.i(TAG, "setTunFd(" + instance + ") = " + rc + " ip=" + ip + "/" + len);
            if (rc != 0) {
                Log.e(TAG, "setTunFd 失败: " + EasyTierJNI.getLastError());
                stopSelf();
                return;
            }
            running = true;
            while (running && tun != null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "TUN 设置失败", t);
            stopSelf();
        } finally {
            running = false;
            if (tun != null) {
                try {
                    tun.close();
                } catch (Exception ignored) {
                }
                tun = null;
            }
        }
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
