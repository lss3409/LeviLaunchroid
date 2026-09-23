package org.levimc.launcher.core.online;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.easytier.jni.EasyTierJNI;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 联机组网编排器（v495，单例）：EasyTier 内核 + VpnService 两段式接入。
 *
 * 流程：join() → runNetworkInstance(TOML, dhcp=true) → 轮询 collectNetworkInfos
 * 拿到本节点虚拟 IP → 以该 IP 启动 EasyTierVpnService 挂 TUN → CONNECTED。
 * 状态通过 Listener 回调到 UI 线程。
 */
public final class EasyTierManager {

    private static final String TAG = "EasyTierMgr";
    private static final String INSTANCE_NAME = "paper-connect";
    private static final long POLL_INTERVAL_MS = 3000;
    private static final long IP_TIMEOUT_MS = 60_000;
    private static final String FALLBACK_CIDR = "10.144.0.0/16";

    public enum State {
        IDLE,        // 未连接
        STARTING,    // 正在启动内核
        WAIT_IP,     // 内核已启动，等待 DHCP 分配虚拟 IP
        CONNECTED,   // 已连接（detail = 虚拟 IP）
        FAILED       // 失败（detail = 错误信息）
    }

    /** 状态回调（主线程）。 */
    public interface Listener {
        void onState(State state, String detail);
    }

    private static EasyTierManager sInstance;

    public static synchronized EasyTierManager get() {
        if (sInstance == null) {
            sInstance = new EasyTierManager();
        }
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean active;
    private volatile State state = State.IDLE;
    private volatile String virtualIp;
    private Context appContext;
    private Listener listener;

    private EasyTierManager() {
    }

    public State getState() {
        return state;
    }

    public String getVirtualIp() {
        return virtualIp;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 加入网络。调用前必须已完成 VpnService.prepare 授权（由 Activity 把关）。 */
    public void join(Context ctx, String networkName, String networkSecret, Listener l) {
        synchronized (lock) {
            stopInternal();
            appContext = ctx.getApplicationContext();
            listener = l;
            virtualIp = null;
            active = true;
            state = State.STARTING;
            notifyState(State.STARTING, null);
            worker = new Thread(() -> runJoin(networkName, networkSecret), "easytier-mgr");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runJoin(String networkName, String networkSecret) {
        String toml = "instance_name = \"" + INSTANCE_NAME + "\"\n"
                + "dhcp = true\n"
                + "[network_identity]\n"
                + "network_name = \"" + networkName + "\"\n"
                + "network_secret = \"" + networkSecret + "\"\n"
                + "[[peer]]\n"
                + "uri = \"tcp://public.easytier.top:11010\"\n";
        Log.d(TAG, "TOML 配置:\n" + toml);
        int rc;
        try {
            rc = EasyTierJNI.runNetworkInstance(toml);
        } catch (Throwable t) {
            Log.e(TAG, "内核调用异常", t);
            String msg = t.getMessage();
            postFail("组网内核调用异常: " + t.getClass().getSimpleName()
                    + (msg == null || msg.isEmpty() ? "" : " " + msg));
            return;
        }
        Log.i(TAG, "runNetworkInstance = " + rc);
        if (rc != 0) {
            postFail("组网内核启动失败: " + String.valueOf(EasyTierJNI.getLastError()));
            return;
        }
        notifyState(State.WAIT_IP, null);
        long deadline = System.currentTimeMillis() + IP_TIMEOUT_MS;
        while (active && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            Info info = pollInfo();
            if (info == null) {
                continue;
            }
            if (!info.running) {
                postFail("网络实例未运行" + (info.errorMsg.isEmpty() ? "" : ": " + info.errorMsg));
                return;
            }
            if (info.virtualIp != null) {
                virtualIp = info.virtualIp;
                startVpn(info.virtualIp, info.cidrs);
                Log.i(TAG, "已连接, 虚拟 IP = " + info.virtualIp);
                notifyState(State.CONNECTED, info.virtualIp);
                return;
            }
        }
        postFail("等待虚拟 IP 超时（DHCP 未分配）");
    }

    /** 停止组网：停 VpnService + 停内核实例。 */
    public void stop(Context ctx) {
        synchronized (lock) {
            stopInternal();
            if (ctx != null) {
                try {
                    ctx.stopService(new Intent(ctx, EasyTierVpnService.class));
                } catch (Exception e) {
                    Log.w(TAG, "停 VPN 服务失败", e);
                }
            }
            new Thread(() -> {
                try {
                    EasyTierJNI.stopAllInstances();
                } catch (Throwable t) {
                    Log.w(TAG, "停内核实例失败", t);
                }
            }, "easytier-stop").start();
        }
    }

    private void stopInternal() {
        active = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (state != State.IDLE && state != State.FAILED) {
            state = State.IDLE;
            notifyState(State.IDLE, null);
        }
    }

    // ---- 内部工具 ----

    private void startVpn(String ipv4, List<String> cidrs) {
        try {
            Intent i = new Intent(appContext, EasyTierVpnService.class);
            i.putExtra(EasyTierVpnService.EXTRA_INSTANCE, INSTANCE_NAME);
            i.putExtra(EasyTierVpnService.EXTRA_IPV4, ipv4);
            if (cidrs != null && !cidrs.isEmpty()) {
                i.putExtra(EasyTierVpnService.EXTRA_CIDRS, cidrs.toArray(new String[0]));
            }
            appContext.startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "启动 VpnService 失败", t);
        }
    }

    /** 单次拉取本实例信息（跑在 worker 线程）。 */
    private Info pollInfo() {
        try {
            String json = EasyTierJNI.collectNetworkInfos(10);
            if (json == null || json.isEmpty()) {
                return null;
            }
            JSONObject root = new JSONObject(json);
            JSONObject map = root.optJSONObject("map");
            if (map == null) {
                return null;
            }
            JSONObject inst = map.optJSONObject(INSTANCE_NAME);
            if (inst == null) {
                return null;
            }
            Info info = new Info();
            info.running = inst.optBoolean("running", false);
            info.errorMsg = str(inst, "error_msg", "errorMsg");
            JSONObject node = inst.optJSONObject("my_node_info");
            if (node == null) {
                node = inst.optJSONObject("myNodeInfo");
            }
            if (node != null) {
                JSONObject vip = node.optJSONObject("virtual_ipv4");
                if (vip == null) {
                    vip = node.optJSONObject("virtualIpv4");
                }
                if (vip != null) {
                    JSONObject addr = vip.optJSONObject("address");
                    if (addr != null && addr.has("addr")) {
                        long a = addr.optLong("addr", 0) & 0xFFFFFFFFL;
                        int len = vip.optInt("network_length", vip.optInt("networkLength", 16));
                        info.virtualIp = ((a >> 24) & 0xFF) + "." + ((a >> 16) & 0xFF) + "."
                                + ((a >> 8) & 0xFF) + "." + (a & 0xFF) + "/" + len;
                    }
                }
            }
            JSONArray routes = inst.optJSONArray("routes");
            if (routes != null) {
                for (int i = 0; i < routes.length(); i++) {
                    JSONObject route = routes.optJSONObject(i);
                    if (route == null) {
                        continue;
                    }
                    JSONArray pc = route.optJSONArray("proxy_cidrs");
                    if (pc == null) {
                        pc = route.optJSONArray("proxyCidrs");
                    }
                    if (pc != null) {
                        for (int j = 0; j < pc.length(); j++) {
                            String c = pc.optString(j, null);
                            if (c != null && !c.isEmpty() && !info.cidrs.contains(c)) {
                                info.cidrs.add(c);
                            }
                        }
                    }
                }
            }
            if (info.cidrs.isEmpty()) {
                info.cidrs.add(FALLBACK_CIDR);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "poll: running=" + info.running + " ip=" + info.virtualIp
                        + " cidrs=" + info.cidrs + " err=" + info.errorMsg);
            }
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "解析网络信息失败", t);
            return null;
        }
    }

    private void postFail(String msg) {
        Log.e(TAG, msg);
        active = false;
        notifyState(State.FAILED, msg);
    }

    private void notifyState(State s, String detail) {
        state = s;
        main.post(() -> {
            if (listener != null) {
                listener.onState(s, detail);
            }
        });
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static final class Info {
        boolean running;
        String virtualIp;
        String errorMsg = "";
        final List<String> cidrs = new ArrayList<>();
    }
}
