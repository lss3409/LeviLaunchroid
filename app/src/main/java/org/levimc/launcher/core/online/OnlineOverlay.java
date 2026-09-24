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
    private android.widget.FrameLayout pttFrame;
    private TextView pttButton;
    private LinearLayout pttWave;
    private final View[] pttBars = new View[5];
    // v534：悬浮窗内加入/离开提示行
    private TextView playerBanner;
    /** v559：设置面板（悬浮窗同款 UI，内嵌卡片显示/隐藏）。 */
    private LinearLayout settingsPanel;
    /** v559：卡片里可被设置面板隐藏的内容行（切换时保存可见性）。 */
    private final List<View> settingsHideTargets = new java.util.ArrayList<>();
    private final java.util.Set<String> lastOverlayIds = new java.util.HashSet<>();
    private final java.util.Map<String, String> lastOverlayNames = new java.util.HashMap<>();
    private final Runnable hidePlayerBannerRunnable = () -> {
        if (playerBanner != null) {
            playerBanner.setVisibility(View.GONE);
        }
    };
    private boolean waveRunning;
    private int waveTick;
    private final Runnable waveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!waveRunning) {
                return;
            }
            waveTick++;
            int level = VoiceEngine.getCurrentLevel();
            for (int i = 0; i < pttBars.length; i++) {
                View bar = pttBars[i];
                if (bar == null) {
                    continue;
                }
                // 微信式：条高 = 基础 + 电平调制 + 条间相位差伪随机起伏
                float phase = (float) Math.sin(i * 1.9 + waveTick * 0.85);
                int h = dp(5) + (int) (dp(13) * (level / 100f) * (0.55f + 0.45f * phase));
                bar.setLayoutParams(new LinearLayout.LayoutParams(dp(3), Math.max(dp(4), h)));
            }
            ui.postDelayed(this, 60);
        }
    };

    private void startWave() {
        VoiceEngine.get(activity).pttDown();
        waveRunning = true;
        waveTick = 0;
        pttButton.setVisibility(View.INVISIBLE);
        pttWave.setVisibility(View.VISIBLE);
        ui.post(waveRunnable);
    }

    private void stopWave() {
        VoiceEngine.get(activity).pttUp();
        waveRunning = false;
        ui.removeCallbacks(waveRunnable);
        pttWave.setVisibility(View.GONE);
        pttButton.setVisibility(View.VISIBLE);
    }

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
            // v549：游戏内悬浮窗显示 = 玩家在线，刷新最近在线时间戳
            PlayerIdentity.touchLastActive(activity);
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
        waveRunning = false;
        ui.removeCallbacks(waveRunnable);
        ui.removeCallbacks(hidePlayerBannerRunnable);
        pttButton = null;
        pttWave = null;
        pttFrame = null;
        playerBanner = null;
        lastOverlayIds.clear();
        lastOverlayNames.clear();
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
        // v547：设置按钮（只在游戏内悬浮窗——降噪开关/等级 + 详情卡查看权限）
        // v559：改为内嵌面板切换（悬浮窗同款 UI，不再弹居中弹窗）
        TextView settings = new TextView(activity);
        settings.setText("⚙");
        settings.setTextColor(0xAAFFFFFF);
        settings.setTextSize(13);
        settings.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        sLp.rightMargin = dp(6);
        header.addView(settings, sLp);
        settings.setOnClickListener(v -> toggleSettingsPanel());
        TextView collapse = new TextView(activity);
        collapse.setText("收起");
        collapse.setTextColor(0xAAFFFFFF);
        collapse.setTextSize(11);
        header.addView(collapse);
        collapse.setOnClickListener(v -> toggleCard());
        card.addView(header);

        // v534：加入/离开提示行（游戏内悬浮窗也提示，不只启动器联机页）
        playerBanner = new TextView(activity);
        playerBanner.setTextSize(11);
        playerBanner.setVisibility(View.GONE);
        card.addView(playerBanner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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

        // v535：PTT 按住说话大按钮——文字与声纹同框重叠，按住后按钮"变身"声纹动画
        pttFrame = new android.widget.FrameLayout(activity);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        ptLp.topMargin = dp(10);
        card.addView(pttFrame, ptLp);
        pttFrame.setVisibility(View.GONE);

        pttButton = new TextView(activity);
        // v535：悬浮窗统一中文（此前英文设备回退英文导致中英混排）
        pttButton.setText("按住说话");
        pttButton.setTextColor(Color.WHITE);
        pttButton.setTextSize(13);
        pttButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pttButton.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(accent);
        pbg.setCornerRadius(dp(8));
        pttButton.setBackground(pbg);
        pttFrame.addView(pttButton, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // 声纹层：与文字同尺寸重叠，按住时可见
        pttWave = new LinearLayout(activity);
        pttWave.setOrientation(LinearLayout.HORIZONTAL);
        pttWave.setGravity(Gravity.CENTER);
        GradientDrawable wbg = new GradientDrawable();
        wbg.setColor(accent);
        wbg.setCornerRadius(dp(8));
        pttWave.setBackground(wbg);
        pttFrame.addView(pttWave, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        pttWave.setVisibility(View.GONE);
        for (int i = 0; i < 5; i++) {
            View bar = new View(activity);
            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(0xCCFFFFFF);
            barBg.setCornerRadius(dp(2));
            bar.setBackground(barBg);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(6));
            if (i > 0) {
                barLp.leftMargin = dp(3);
            }
            pttWave.addView(bar, barLp);
            pttBars[i] = bar;
        }
        // 整个按钮区域接收按住事件（文字与声纹共用）
        pttFrame.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startWave();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopWave();
                    return true;
            }
            return false;
        });

        TextView leave = new TextView(activity);
        leave.setText("退出房间"); // v535：悬浮窗统一中文
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

        // v559：设置面板（悬浮窗同款 UI——深色卡片内的行式设置项）
        buildSettingsPanel(card);
        // v559：设置面板显示时隐藏常规内容行
        settingsHideTargets.clear();
        settingsHideTargets.add(code);
        settingsHideTargets.add(playersContainer);
        settingsHideTargets.add(pttFrame);
        settingsHideTargets.add(leave);
        if (playerBanner != null) {
            settingsHideTargets.add(playerBanner);
        }

        cardView = card;
        cardParams = baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT);
        cardParams.x = dp(12);
        cardParams.y = dp(90);
        // v525：展开卡片也可拖动
        card.setOnTouchListener(this::onCardTouch);
    }

    /**
     * v559：构建设置面板——悬浮窗同款 UI（深色卡片内行式设置项），
     * 点 ⚙ 在玩家列表与设置面板之间切换，不再弹居中弹窗。
     */
    private void buildSettingsPanel(LinearLayout card) {
        settingsPanel = new LinearLayout(activity);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.topMargin = dp(8);
        card.addView(settingsPanel, spLp);

        // 返回行（点 ⚙ 或此行回到玩家列表）
        TextView backRow = new TextView(activity);
        backRow.setText("← 返回");
        backRow.setTextColor(0xAAFFFFFF);
        backRow.setTextSize(11);
        settingsPanel.addView(backRow);
        backRow.setOnClickListener(v -> toggleSettingsPanel());

        // 降噪开关
        TextView noiseLabel = new TextView(activity);
        noiseLabel.setText(R.string.online_settings_noise);
        noiseLabel.setTextSize(12);
        noiseLabel.setTextColor(0xFFF5F5F5);
        LinearLayout.LayoutParams nlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlLp.topMargin = dp(10);
        settingsPanel.addView(noiseLabel, nlLp);
        final boolean[] ns = {VoiceEngine.isNoiseSuppressionOn()};
        TextView noiseToggle = new TextView(activity);
        refreshToggle(noiseToggle, ns[0]);
        LinearLayout.LayoutParams ntLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        ntLp.topMargin = dp(6);
        settingsPanel.addView(noiseToggle, ntLp);
        noiseToggle.setOnClickListener(x -> {
            ns[0] = !ns[0];
            VoiceEngine.setNoiseSuppression(ns[0]);
            refreshToggle(noiseToggle, ns[0]);
        });

        // 降噪等级
        TextView levelLabel = new TextView(activity);
        levelLabel.setText(R.string.online_settings_noise_level);
        levelLabel.setTextSize(12);
        levelLabel.setTextColor(0xFFF5F5F5);
        LinearLayout.LayoutParams llLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llLp.topMargin = dp(12);
        settingsPanel.addView(levelLabel, llLp);
        LinearLayout levelRow = new LinearLayout(activity);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
        lrLp.topMargin = dp(6);
        settingsPanel.addView(levelRow, lrLp);
        int[] levelNames = {R.string.online_settings_noise_low,
                R.string.online_settings_noise_medium, R.string.online_settings_noise_high};
        final int[] curLevel = {VoiceEngine.getNoiseLevel()};
        for (int i = 0; i < 3; i++) {
            final int level = i;
            TextView cap = new TextView(activity);
            cap.setText(levelNames[i]);
            cap.setTextSize(12);
            cap.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            if (i > 0) {
                cLp.leftMargin = dp(6);
            }
            levelRow.addView(cap, cLp);
            refreshCapsule(cap, curLevel[0] == level);
            cap.setOnClickListener(x -> {
                VoiceEngine.setNoiseLevel(level);
                curLevel[0] = level;
                for (int j = 0; j < levelRow.getChildCount(); j++) {
                    refreshCapsule((TextView) levelRow.getChildAt(j), j == level);
                }
            });
        }

        // 详情卡查看权限
        TextView permLabel = new TextView(activity);
        permLabel.setText(R.string.online_settings_view_perm);
        permLabel.setTextSize(12);
        permLabel.setTextColor(0xFFF5F5F5);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plLp.topMargin = dp(12);
        settingsPanel.addView(permLabel, plLp);
        LinearLayout permRow = new LinearLayout(activity);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
        prLp.topMargin = dp(6);
        settingsPanel.addView(permRow, prLp);
        int[] permNames = {R.string.online_settings_perm_none,
                R.string.online_settings_perm_host, R.string.online_settings_perm_all};
        final int[] curPerm = {PlayerDetailCard.getViewPerm(activity)};
        for (int i = 0; i < 3; i++) {
            final int perm = i;
            TextView cap = new TextView(activity);
            cap.setText(permNames[i]);
            cap.setTextSize(12);
            cap.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            if (i > 0) {
                cLp.leftMargin = dp(6);
            }
            permRow.addView(cap, cLp);
            refreshCapsule(cap, curPerm[0] == perm);
            cap.setOnClickListener(x -> {
                PlayerDetailCard.setViewPerm(activity, perm);
                curPerm[0] = perm;
                for (int j = 0; j < permRow.getChildCount(); j++) {
                    refreshCapsule((TextView) permRow.getChildAt(j), j == perm);
                }
            });
        }
    }

    /** v559：设置面板与常规内容互斥切换。 */
    private void toggleSettingsPanel() {
        if (settingsPanel == null) {
            return;
        }
        boolean show = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        for (View target : settingsHideTargets) {
            if (target != null) {
                target.setVisibility(show ? View.GONE : View.VISIBLE);
            }
        }
        // 回列表时 PTT 行按模式恢复显示
        if (!show && pttFrame != null) {
            pttFrame.setVisibility(VoiceEngine.getLastMode() == VoiceEngine.MODE_PTT
                    ? View.VISIBLE : View.GONE);
        }
    }

    /** 开关胶囊样式刷新（开=强调色底深字，关=暗底亮字）。 */
    private void refreshToggle(TextView t, boolean on) {
        t.setText(on ? "开" : "关");
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        // v558：强调色（抹茶绿等亮色）底上用深色字——白字在亮色底上对比度不足看不清
        t.setTextColor(on ? 0xFF101016 : 0xFFF2F2F2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? accent : 0x33FFFFFF);
        bg.setCornerRadius(dp(9));
        t.setBackground(bg);
    }

    /** 选项胶囊样式刷新（选中=强调色底深字，未选=暗底亮字）。 */
    private void refreshCapsule(TextView t, boolean selected) {
        // v558：选中态深字（同启动器按钮 on_primary），亮色强调色底上清晰可见
        t.setTextColor(selected ? 0xFF101016 : 0xFFF2F2F2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? accent : 0x33FFFFFF);
        bg.setCornerRadius(dp(9));
        t.setBackground(bg);
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
        // v530：被房主禁麦期间不能自己开麦
        if (ve.isMutedByHost()) {
            Toast.makeText(activity, R.string.voice_muted_by_host, Toast.LENGTH_SHORT).show();
            return;
        }
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
            // v546：被禁麦的麦克风染红锁定（房主视角成员行 + 成员自己行）
            boolean mutedLocked = self ? VoiceEngine.isMutedByHost()
                    : RoomCenter.isMuted(e.getKey());
            if (mutedLocked) {
                mic.setImageResource(R.drawable.ic_mic_off);
                mic.setColorFilter(0xFFFF6B6B);
            } else if (m == VoiceEngine.MODE_OPEN) {
                mic.setImageResource(R.drawable.ic_mic_on);
                mic.setColorFilter(accent);
            } else if (m == VoiceEngine.MODE_PTT) {
                // v538：对讲机颜色统一白色（与麦克风图标一致，不再染黄）
                mic.setImageResource(R.drawable.ic_mic_ptt);
                mic.setColorFilter(Color.WHITE);
            } else {
                mic.setImageResource(R.drawable.ic_mic_off);
                mic.setColorFilter(0xAAFFFFFF);
            }
        }
        if (pttFrame != null) {
            pttFrame.setVisibility(selfMode == VoiceEngine.MODE_PTT ? View.VISIBLE : View.GONE);
        }
        if (pttWave != null) {
            pttWave.setVisibility(selfMode == VoiceEngine.MODE_PTT && waveRunning
                    ? View.VISIBLE : View.GONE);
        }
    }

    /** v534：悬浮窗内玩家名单 diff → 加入/离开提示行。 */
    private void detectOverlayChanges(List<RoomCenter.Player> players, String selfId) {
        if (players == null || playerBanner == null) {
            return;
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (RoomCenter.Player p : players) {
            if (!p.clientId.equals(selfId)) {
                ids.add(p.clientId);
                names.put(p.clientId, p.name);
            }
        }
        java.util.List<String> joins = new java.util.ArrayList<>();
        java.util.List<String> leaves = new java.util.ArrayList<>();
        if (!lastOverlayIds.isEmpty()) {
            for (String id : ids) {
                if (!lastOverlayIds.contains(id)) {
                    joins.add(names.get(id));
                }
            }
            for (String id : lastOverlayIds) {
                if (!ids.contains(id)) {
                    leaves.add(lastOverlayNames.get(id));
                }
            }
        }
        lastOverlayIds.clear();
        lastOverlayIds.addAll(ids);
        lastOverlayNames.clear();
        lastOverlayNames.putAll(names);
        if (!joins.isEmpty()) {
            showPlayerBanner(activity.getString(R.string.online_join_banner_fmt,
                    String.join("」「", joins)), 0xFF9BE29B);
        } else if (!leaves.isEmpty()) {
            showPlayerBanner(activity.getString(R.string.online_leave_banner_fmt,
                    String.join("」「", leaves)), 0xFFAAAAAA);
        }
    }

    private void showPlayerBanner(String text, int color) {
        playerBanner.setText(text);
        playerBanner.setTextColor(color);
        playerBanner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hidePlayerBannerRunnable);
        ui.postDelayed(hidePlayerBannerRunnable, 2500);
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
        // v547：人数用最近名单快照（成员端 memberAddrs 恒空，之前永远显示 1 人）
        int count = RoomCenter.getLastPlayerCount();
        String loss = n == 0 ? "--" : String.valueOf(lost * 100 / Math.max(1, n));
        long avgRtt = avg <= 0 ? -1 : avg / Math.max(1, n - lost);
        String rtt = avgRtt < 0 ? "--ms" : avgRtt + "ms";
        String full = rtt + " · " + loss + "%丢包 · " + count + "人";
        // v529（清单 #25）+ v541：只给"延迟"部分上色（红黄绿），丢包/人数保持白色
        android.text.SpannableString ss = new android.text.SpannableString(full);
        int rttColor = avgRtt < 0 ? Color.WHITE
                : avgRtt < 50 ? 0xFF4CAF50 : avgRtt < 100 ? 0xFFFFB74D : 0xFFFF6B6B;
        ss.setSpan(new android.text.style.ForegroundColorSpan(rttColor), 0, rtt.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        barText.setText(ss);
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
                String selfId2 = PlayerIdentity.getClientId(activity);
                // v534：悬浮窗内加入/离开提示（名单 diff）
                detectOverlayChanges(players, selfId2);
                playersContainer.removeAllViews();
                playerRows.clear();
                playerMicViews.clear();
                playerMicStates.clear();
                if (players != null) {
                    String selfId = selfId2;
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
                        // v530：被房主禁麦的成员名字前加 🔇 标记（房主视角）；
                        // v546：成员端自己行看本机 VoiceEngine 禁麦状态（此前成员端
                        // 永远看不到自己的禁麦标记——RoomCenter.isMuted 是房主本地名单）
                        boolean muted = RoomCenter.isMuted(p.clientId)
                                || (self && VoiceEngine.isMutedByHost());
                        name.setText((p.isRoomHost ? "👑 " : "") + (muted ? "🔇 " : "")
                                + p.name
                                + (self ? activity.getString(R.string.online_self_suffix) : ""));
                        name.setTextSize(12);
                        row.addView(name, new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        // v547：点击玩家行弹详情卡（联机页同款）；
                        // 房主点成员行时卡片内带「禁麦/解除禁麦」按钮
                        // （v530 的行点击直接禁麦交互迁移进详情卡）
                        row.setOnClickListener(v -> {
                            if (PlayerDetailCard.canView(activity, p, RoomCenter.isHost)) {
                                PlayerDetailCard.show(activity, p,
                                        RoomCenter.isHost && !self && !p.isRoomHost);
                            } else {
                                Toast.makeText(activity, R.string.online_card_no_permission,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
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
