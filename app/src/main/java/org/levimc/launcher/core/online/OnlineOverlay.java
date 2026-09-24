package org.levimc.launcher.core.online;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.online.voice.VoiceEngine;
import org.levimc.launcher.util.PersonalizationManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联机悬浮窗（v524 长条形改版，游戏内）：
 * 收起态 = 水平胶囊条 [●] 54ms · 0%丢包 · 2/8人 [▾]（跟随个性化强调色）；
 * 点开 = 紧凑卡片（模式/延迟/丢包/房间码可复制/玩家列表/麦克风三态/退出）。
 * 自带独立 ping 线程测量延迟与丢包（10 次滑动窗口），不依赖心跳间隔。
 * v527：麦克风行（闭麦→开麦→PTT 循环）+ 说话人高亮 + 按住说话。
 */
public final class OnlineOverlay implements RoomCenter.Listener, VoiceEngine.Listener {

    private static final String TAG = "OnlineOverlay";
    private static final int PING_INTERVAL_MS = 2000;
    private static final int PING_WINDOW = 10;
    private static volatile OnlineOverlay instance;

    private final Activity activity;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final int accent;

    private View barView;
    private View cardView;
    private WindowManager.LayoutParams barParams;
    private WindowManager.LayoutParams cardParams;
    private TextView barText;
    private TextView cardState;
    private LinearLayout playersContainer;
    private boolean showing;

    // v527/v528 麦克风 UI：每玩家行一个图标（自己=可点三态按钮，他人=状态显示）
    private TextView pttButton;
    private TextView muteAllButton;
    private boolean micPending;
    private final Map<String, TextView> playerRows = new HashMap<>();
    private final Map<String, android.widget.ImageView> playerMicViews = new HashMap<>();
    private final Map<String, Integer> playerMicStates = new HashMap<>();

    // ping 统计
    private final long[] pingWindow = new long[PING_WINDOW]; // >0 = rtt, -1 = 丢包
    private int pingIdx = 0;
    private int pingFilled = 0;
    private Thread pingThread;

    private int dragStartX, dragStartY, touchStartX, touchStartY;
    private boolean dragging;

    private OnlineOverlay(Activity activity) {
        this.activity = activity;
        this.wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        this.density = activity.getResources().getDisplayMetrics().density;
        int a = 0xFF4AE0A0;
        try {
            a = new PersonalizationManager(activity).getAccentColor();
        } catch (Exception ignored) {
        }
        this.accent = a;
    }

    public static OnlineOverlay get(Activity activity) {
        if (instance == null || instance.activity != activity) {
            instance = new OnlineOverlay(activity);
        }
        return instance;
    }

    public static void hideIfShown() {
        OnlineOverlay o = instance;
        if (o != null) {
            o.hide();
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    // ---------------- 显示/隐藏 ----------------

    public void show() {
        if (showing) {
            return;
        }
        if (EasyTierManager.get().getState() != EasyTierManager.State.CONNECTED) {
            return;
        }
        try {
            buildBar();
            buildCard();
            showing = true;
            RoomCenter.addListener(this);
            VoiceEngine ve = VoiceEngine.get(activity);
            ve.addListener(this);
            ve.start(); // 收包/播放常驻（闭麦也能听），采集按模式激活
            refreshMicUi();
            startPingLoop();
            ui.post(this::refreshBar);
        } catch (Exception e) {
            Log.w(TAG, "悬浮窗显示失败", e);
        }
    }

    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        RoomCenter.removeListener(this);
        VoiceEngine.get(activity).removeListener(this);
        if (pingThread != null) {
            pingThread.interrupt();
            pingThread = null;
        }
        try {
            if (barView != null) {
                wm.removeView(barView);
            }
            if (cardView != null) {
                wm.removeView(cardView);
            }
        } catch (Exception ignored) {
        }
        barView = null;
        cardView = null;
        pttButton = null;
        muteAllButton = null;
        playerRows.clear();
        playerMicViews.clear();
        playerMicStates.clear();
    }

    // ---------------- 长条形收起态 ----------------

    private void buildBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD91B1B22);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), blend(accent, 0x000000, 0.35f));
        bar.setBackground(bg);
        bar.setPadding(dp(12), dp(6), dp(10), dp(6));

        View dot = new View(activity);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setShape(GradientDrawable.OVAL);
        dbg.setColor(accent);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        bar.addView(dot, dotLp);

        barText = new TextView(activity);
        barText.setTextColor(Color.WHITE);
        barText.setTextSize(11);
        barText.setText("--ms · --% · -/-");
        bar.addView(barText);

        TextView caret = new TextView(activity);
        caret.setText("▾");
        caret.setTextColor(0xAAFFFFFF);
        caret.setTextSize(10);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.leftMargin = dp(4);
        bar.addView(caret, cLp);

        barView = bar;
        barParams = baseParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.x = dp(12);
        barParams.y = dp(90);
        barView.setOnTouchListener(this::onBarTouch);
        wm.addView(barView, barParams);
    }

    private void buildCard() {
        int width = dp(250);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B1B22);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), blend(accent, 0x000000, 0.4f));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        cardState = new TextView(activity);
        cardState.setTextColor(accent);
        cardState.setTextSize(13);
        cardState.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(cardState, new LinearLayout.LayoutParams(0, dp(24), 1f));
        TextView collapse = new TextView(activity);
        collapse.setText("收起");
        collapse.setTextColor(0xAAFFFFFF);
        collapse.setTextSize(11);
        header.addView(collapse);
        collapse.setOnClickListener(v -> toggleCard());
        // v528 房主一键全员禁麦（仅房主可见）
        muteAllButton = new TextView(activity);
        muteAllButton.setText(R.string.voice_mute_all);
        muteAllButton.setTextColor(0xAAFFFFFF);
        muteAllButton.setTextSize(11);
        GradientDrawable mabg = new GradientDrawable();
        mabg.setColor(0x1AFFFFFF);
        mabg.setCornerRadius(dp(6));
        muteAllButton.setBackground(mabg);
        LinearLayout.LayoutParams maLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        maLp.leftMargin = dp(10);
        header.addView(muteAllButton, maLp);
        muteAllButton.setVisibility(View.GONE);
        muteAllButton.setOnClickListener(v ->
                RoomCenter.sendMuteAll(!RoomCenter.allMuted));
        card.addView(header);

        TextView code = new TextView(activity);
        code.setTextColor(0xFF8AB4F8);
        code.setTextSize(12);
        code.setText(RoomCenter.roomCode == null ? "" : "P/" + RoomCenter.roomCode);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        codeLp.topMargin = dp(2);
        card.addView(code, codeLp);
        code.setOnClickListener(v -> {
            String c = "P/" + (RoomCenter.roomCode == null ? "" : RoomCenter.roomCode);
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("room", c));
                Toast.makeText(activity, R.string.online_copied, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });

        playersContainer = new LinearLayout(activity);
        playersContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plLp.topMargin = dp(6);
        card.addView(playersContainer, plLp);

        // v528：PTT 按住说话大按钮（仅对讲机模式显示，全宽好按）
        pttButton = new TextView(activity);
        pttButton.setText(R.string.voice_ptt_hold);
        pttButton.setTextColor(Color.WHITE);
        pttButton.setTextSize(13);
        pttButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pttButton.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(accent);
        pbg.setCornerRadius(dp(8));
        pttButton.setBackground(pbg);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        ptLp.topMargin = dp(10);
        card.addView(pttButton, ptLp);
        pttButton.setVisibility(View.GONE);
        pttButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    VoiceEngine.get(activity).pttDown();
                    v.setAlpha(0.7f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    VoiceEngine.get(activity).pttUp();
                    v.setAlpha(1f);
                    return true;
            }
            return false;
        });

        TextView leave = new TextView(activity);
        leave.setText(R.string.online_leave);
        leave.setTextColor(0xFFFF6B6B);
        leave.setTextSize(12);
        leave.setGravity(Gravity.CENTER);
        GradientDrawable lbg = new GradientDrawable();
        lbg.setColor(0x1AFF6B6B);
        lbg.setCornerRadius(dp(8));
        leave.setBackground(lbg);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        lvLp.topMargin = dp(10);
        card.addView(leave, lvLp);
        leave.setOnClickListener(v -> {
            EasyTierManager.get().stop(activity);
            RoomCenter.stopHost();
            RoomCenter.stopClient();
            LanBridge.stopHost();
            VoiceEngine.get(activity).stop();
            hide();
        });

        cardView = card;
        cardParams = baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT);
        cardParams.x = dp(12);
        cardParams.y = dp(90);
        // v525：展开卡片也可拖动
        card.setOnTouchListener(this::onCardTouch);
    }

    private boolean onCardTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = cardParams.x;
                dragStartY = cardParams.y;
                touchStartX = (int) e.getRawX();
                touchStartY = (int) e.getRawY();
                dragging = false;
                // 不消费 DOWN，让子控件（复制/退出按钮）仍可点击
                return false;
            case MotionEvent.ACTION_MOVE: {
                int dx = (int) e.getRawX() - touchStartX;
                int dy = (int) e.getRawY() - touchStartY;
                if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                    dragging = true;
                }
                if (dragging) {
                    cardParams.x = clampX(dragStartX + dx);
                    cardParams.y = clampY(dragStartY + dy);
                    wm.updateViewLayout(cardView, cardParams);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return false;
        }
        return false;
    }

    private WindowManager.LayoutParams baseParams(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.token = activity.getWindow().getDecorView().getWindowToken();
        return p;
    }

    // ---------------- 交互 ----------------

    private boolean onBarTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = barParams.x;
                dragStartY = barParams.y;
                touchStartX = (int) e.getRawX();
                touchStartY = (int) e.getRawY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int dx = (int) e.getRawX() - touchStartX;
                int dy = (int) e.getRawY() - touchStartY;
                if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                    dragging = true;
                }
                if (dragging) {
                    barParams.x = clampX(dragStartX + dx);
                    barParams.y = clampY(dragStartY + dy);
                    wm.updateViewLayout(barView, barParams);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    toggleCard();
                }
                return true;
        }
        return false;
    }

    private int clampX(int x) {
        int max = activity.getResources().getDisplayMetrics().widthPixels - dp(160);
        return Math.max(0, Math.min(x, max));
    }

    private int clampY(int y) {
        int max = activity.getResources().getDisplayMetrics().heightPixels - dp(40);
        return Math.max(0, Math.min(y, max));
    }

    private void toggleCard() {
        if (cardView == null) {
            return;
        }
        if (cardView.getParent() != null) {
            try {
                wm.removeView(cardView);
            } catch (Exception ignored) {
            }
        } else {
            refreshCard();
            try {
                wm.addView(cardView, cardParams);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------- v527 麦克风交互 ----------------

    private void onMicClicked() {
        VoiceEngine ve = VoiceEngine.get(activity);
        if (!ve.hasMicPermission(activity)) {
            micPending = true;
            try {
                activity.requestPermissions(
                        new String[]{android.Manifest.permission.RECORD_AUDIO}, 100);
            } catch (Exception ignored) {
                micPending = false;
            }
            return;
        }
        advanceMicMode();
    }

    private void advanceMicMode() {
        VoiceEngine ve = VoiceEngine.get(activity);
        int m = ve.cycleMode();
        refreshMicUi();
        if (m != VoiceEngine.MODE_MUTED) {
            ve.start();
        }
    }

    /** 麦克风权限结果（MinecraftActivity.onRequestPermissionsResult 转发）。 */
    public void onMicPermissionResult(boolean granted) {
        if (!micPending) {
            return;
        }
        micPending = false;
        VoiceEngine.get(activity).onPermissionResult(granted);
        if (granted) {
            advanceMicMode();
        } else {
            Toast.makeText(activity, R.string.voice_perm_denied, Toast.LENGTH_SHORT).show();
        }
    }

    /** 静态转发入口（Activity 侧调用，悬浮窗未创建时安全忽略）。 */
    public static void forwardMicPermissionResult(Activity activity, boolean granted) {
        OnlineOverlay o = instance;
        if (o != null && o.activity == activity) {
            o.onMicPermissionResult(granted);
        }
    }

    /**
     * v528：刷新全员麦克风图标——自己行可点（三态循环），他人行只读状态；
     * PTT 按住说话按钮与房主一键禁麦按钮同步显隐。
     */
    private void refreshMicUi() {
        if (playersContainer == null) {
            return;
        }
        String selfId = PlayerIdentity.getClientId(activity);
        int selfMode = VoiceEngine.getLastMode();
        for (Map.Entry<String, android.widget.ImageView> e : playerMicViews.entrySet()) {
            boolean self = e.getKey().equals(selfId);
            int m = self ? selfMode
                    : (playerMicStates.containsKey(e.getKey()) ? playerMicStates.get(e.getKey()) : 0);
            android.widget.ImageView mic = e.getValue();
            if (mic == null) {
                continue;
            }
            if (m == VoiceEngine.MODE_OPEN) {
                mic.setImageResource(R.drawable.ic_mic_on);
                mic.setColorFilter(accent);
            } else if (m == VoiceEngine.MODE_PTT) {
                mic.setImageResource(R.drawable.ic_mic_ptt);
                mic.setColorFilter(0xFFFFB74D);
            } else {
                mic.setImageResource(R.drawable.ic_mic_off);
                mic.setColorFilter(0xAAFFFFFF);
            }
        }
        if (pttButton != null) {
            pttButton.setVisibility(selfMode == VoiceEngine.MODE_PTT ? View.VISIBLE : View.GONE);
        }
        if (muteAllButton != null && muteAllButton.getVisibility() == View.VISIBLE) {
            muteAllButton.setText(RoomCenter.allMuted
                    ? R.string.voice_unmute_all : R.string.voice_mute_all);
            muteAllButton.setTextColor(RoomCenter.allMuted ? accent : 0xAAFFFFFF);
        }
    }

    /** 说话人/模式变化：重涂玩家行（高亮正在说话的人）+ 刷新麦克风图标。 */
    @Override
    public void onVoiceChanged() {
        if (!showing) {
            return;
        }
        java.util.Set<String> speaking = VoiceEngine.get(activity).getSpeakingClients();
        String selfId = PlayerIdentity.getClientId(activity);
        for (Map.Entry<String, TextView> e : playerRows.entrySet()) {
            TextView row = e.getValue();
            if (row == null) {
                continue;
            }
            if (speaking.contains(e.getKey())) {
                row.setTextColor(accent);
            } else if (e.getKey().equals(selfId)) {
                row.setTextColor(0xFF8AB4F8);
            } else {
                row.setTextColor(0xDDFFFFFF);
            }
        }
        refreshMicUi();
    }

    // ---------------- 延迟/丢包测量 ----------------

    private void startPingLoop() {
        pingThread = new Thread(() -> {
            while (showing) {
                long rtt = pingOnce();
                synchronized (pingWindow) {
                    pingWindow[pingIdx % PING_WINDOW] = rtt;
                    pingIdx++;
                    if (pingFilled < PING_WINDOW) {
                        pingFilled++;
                    }
                }
                ui.post(this::refreshBar);
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "online-overlay-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    /** 单次 c:ping：房主 ping 各成员（取平均），成员 ping 房主。 */
    private long pingOnce() {
        List<InetSocketAddress> targets = new java.util.ArrayList<>();
        if (RoomCenter.isHost) {
            // v525：ping 成员的源端口（成员客户端常驻 socket 会应答反向 ping）
            targets.addAll(RoomCenter.getMemberAddresses());
        } else {
            targets.add(new InetSocketAddress("10.144.144.144", RoomCenter.PORT));
        }
        if (targets.isEmpty()) {
            return -1;
        }
        long sum = 0;
        int ok = 0;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1200);
            for (InetSocketAddress addr : targets) {
                try {
                    long t0 = System.currentTimeMillis();
                    String req = "c:ping\0{\"time\":" + t0 + "}";
                    byte[] out = req.getBytes("UTF-8");
                    s.send(new DatagramPacket(out, out.length, addr));
                    byte[] buf = new byte[512];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String resp = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (resp.contains("returnTime")) {
                        sum += System.currentTimeMillis() - t0;
                        ok++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return ok == 0 ? -1 : sum / ok;
    }

    private void refreshBar() {
        if (barText == null) {
            return;
        }
        long avg = 0;
        int lost = 0;
        int n = 0;
        synchronized (pingWindow) {
            for (int i = 0; i < pingFilled; i++) {
                long v = pingWindow[i];
                if (v < 0) {
                    lost++;
                } else {
                    avg += v;
                }
                n++;
            }
        }
        int count = 1;
        try {
            int members = RoomCenter.getMemberAddresses().size();
            count = Math.max(1, members + 1);
        } catch (Exception ignored) {
        }
        String loss = n == 0 ? "--" : String.valueOf(lost * 100 / Math.max(1, n));
        long avgRtt = avg <= 0 ? -1 : avg / Math.max(1, n - lost);
        String rtt = avgRtt < 0 ? "--ms" : avgRtt + "ms";
        barText.setText(rtt + " · " + loss + "%丢包 · " + count + "人");
        // v529（清单 #25）：延迟颜色分级 <50 绿 / 50-100 黄 / >100 红
        barText.setTextColor(avgRtt < 0 ? Color.WHITE
                : avgRtt < 50 ? 0xFF4CAF50 : avgRtt < 100 ? 0xFFFFB74D : 0xFFFF6B6B);
    }

    // ---------------- 数据刷新 ----------------

    private void refreshCard() {
        if (cardView == null) {
            return;
        }
        EasyTierManager.ConnMode mode = EasyTierManager.get().getConnMode();
        if (mode == EasyTierManager.ConnMode.RELAY) {
            cardState.setText(R.string.online_step_relay);
            cardState.setTextColor(0xFFFFB74D);
        } else if (mode == EasyTierManager.ConnMode.P2P) {
            cardState.setText(R.string.online_step_p2p);
            cardState.setTextColor(accent);
        } else {
            cardState.setText(R.string.online_step_unknown);
            cardState.setTextColor(0xAAFFFFFF);
        }
    }

    @Override
    public void onPlayers(List<RoomCenter.Player> players, long rttMs) {
        if (!showing) {
            return;
        }
        ui.post(() -> {
            if (!showing) {
                return;
            }
            if (playersContainer != null) {
                playersContainer.removeAllViews();
                playerRows.clear();
                playerMicViews.clear();
                playerMicStates.clear();
                if (players != null) {
                    String selfId = PlayerIdentity.getClientId(activity);
                    for (RoomCenter.Player p : players) {
                        boolean self = p.clientId.equals(selfId);
                        LinearLayout row = new LinearLayout(activity);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(0, dp(3), 0, dp(3));
                        // v529：小头像（Xbox 头像有 URL 时 Glide 覆盖首字圆底）
                        android.widget.FrameLayout avFrame = new android.widget.FrameLayout(activity);
                        TextView avChar = new TextView(activity);
                        avChar.setText(p.name == null || p.name.isEmpty() ? "?" : p.name.substring(0, 1));
                        avChar.setGravity(Gravity.CENTER);
                        avChar.setTextColor(0xFF101016);
                        avChar.setTextSize(10);
                        GradientDrawable avBg = new GradientDrawable();
                        avBg.setColor(accent);
                        avBg.setShape(GradientDrawable.OVAL);
                        avChar.setBackground(avBg);
                        avFrame.addView(avChar, new android.widget.FrameLayout.LayoutParams(
                                dp(20), dp(20)));
                        if (p.avatarUrl != null && !p.avatarUrl.isEmpty()) {
                            android.widget.ImageView avImg = new android.widget.ImageView(activity);
                            com.bumptech.glide.Glide.with(activity)
                                    .load(p.avatarUrl).circleCrop().into(avImg);
                            avFrame.addView(avImg, new android.widget.FrameLayout.LayoutParams(
                                    dp(20), dp(20)));
                        }
                        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(20), dp(20));
                        avLp.rightMargin = dp(6);
                        row.addView(avFrame, avLp);
                        TextView name = new TextView(activity);
                        name.setText((p.isRoomHost ? "👑 " : "") + p.name
                                + (self ? activity.getString(R.string.online_self_suffix) : ""));
                        name.setTextSize(12);
                        row.addView(name, new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        // v528：每玩家行右侧麦克风（自己=可点三态按钮，他人=状态显示）
                        android.widget.ImageView mic = new android.widget.ImageView(activity);
                        mic.setImageResource(R.drawable.ic_mic_off);
                        mic.setColorFilter(0xAAFFFFFF);
                        LinearLayout.LayoutParams micLp;
                        if (self) {
                            micLp = new LinearLayout.LayoutParams(dp(26), dp(26));
                            micLp.leftMargin = dp(6);
                            mic.setPadding(dp(5), dp(5), dp(5), dp(5));
                            GradientDrawable micBg = new GradientDrawable();
                            micBg.setColor(0x22FFFFFF);
                            micBg.setCornerRadius(dp(7));
                            mic.setBackground(micBg);
                            mic.setOnClickListener(v -> onMicClicked());
                        } else {
                            micLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                            micLp.leftMargin = dp(6);
                            mic.setPadding(dp(2), dp(2), dp(2), dp(2));
                        }
                        row.addView(mic, micLp);
                        playersContainer.addView(row);
                        playerRows.put(p.clientId, name);
                        playerMicViews.put(p.clientId, mic);
                        playerMicStates.put(p.clientId, p.micState);
                    }
                }
                // v528：房主显示一键禁麦按钮
                if (muteAllButton != null) {
                    muteAllButton.setVisibility(RoomCenter.isHost ? View.VISIBLE : View.GONE);
                }
                onVoiceChanged(); // 按当前说话状态上色 + 刷新麦克风图标
            }
            refreshBar();
            refreshCard();
        });
    }

    private static int blend(int color, int other, float ratio) {
        int r = (int) (Color.red(color) * (1 - ratio) + Color.red(other) * ratio);
        int g = (int) (Color.green(color) * (1 - ratio) + Color.green(other) * ratio);
        int b = (int) (Color.blue(color) * (1 - ratio) + Color.blue(other) * ratio);
        return Color.rgb(r, g, b);
    }
}
