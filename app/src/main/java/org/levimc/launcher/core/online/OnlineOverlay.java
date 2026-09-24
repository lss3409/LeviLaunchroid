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

import java.util.List;

/**
 * 联机悬浮窗（v521，游戏内，参考蓝盾简洁风）：
 * 房间会话激活 + 进入游戏时显示——收起态 = 小圆泡（模式色 + 人数），
 * 点开 = 紧凑卡片（模式/延迟/房间码可复制/玩家列表/退出房间）。
 * 数据来自 RoomCenter 多监听器广播 + EasyTierManager 状态。
 */
public final class OnlineOverlay implements RoomCenter.Listener {

    private static final String TAG = "OnlineOverlay";
    private static volatile OnlineOverlay instance;

    private final Activity activity;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;

    private FrameLayout bubbleView;
    private View cardView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams cardParams;
    private TextView bubbleCount;
    private LinearLayout playersContainer;
    private TextView cardState;
    private TextView cardLatency;
    private boolean showing;

    private int dragStartX, dragStartY, touchStartX, touchStartY;
    private boolean dragging;

    private OnlineOverlay(Activity activity) {
        this.activity = activity;
        this.wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        this.density = activity.getResources().getDisplayMetrics().density;
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
            buildBubble();
            buildCard();
            showing = true;
            RoomCenter.addListener(this);
            ui.post(this::refreshFromState);
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
        try {
            if (bubbleView != null) {
                wm.removeView(bubbleView);
            }
            if (cardView != null) {
                wm.removeView(cardView);
            }
        } catch (Exception ignored) {
        }
        bubbleView = null;
        cardView = null;
    }

    // ---------------- UI 构建 ----------------

    private void buildBubble() {
        bubbleView = new FrameLayout(activity);
        int size = dp(46);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC1E1E24);
        bg.setStroke(dp(2), 0xFF4AE0A0);
        bubbleView.setBackground(bg);
        bubbleView.setAlpha(0.9f);

        bubbleCount = new TextView(activity);
        bubbleCount.setTextColor(Color.WHITE);
        bubbleCount.setTextSize(12);
        bubbleCount.setGravity(Gravity.CENTER);
        bubbleCount.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        bubbleView.addView(bubbleCount, lp);

        bubbleView.setOnTouchListener(this::onBubbleTouch);
        bubbleParams = baseParams(size, size);
        bubbleParams.x = dp(12);
        bubbleParams.y = dp(120);
        wm.addView(bubbleView, bubbleParams);
    }

    private void buildCard() {
        int width = dp(240);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B1B22);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 头部：状态 + 延迟
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        cardState = new TextView(activity);
        cardState.setTextColor(0xFF4AE0A0);
        cardState.setTextSize(13);
        cardState.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(cardState, new LinearLayout.LayoutParams(0, dp(24), 1f));
        cardLatency = new TextView(activity);
        cardLatency.setTextColor(0x99FFFFFF);
        cardLatency.setTextSize(11);
        header.addView(cardLatency);
        card.addView(header);

        // 房间码（点按复制）
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

        // 玩家列表
        playersContainer = new LinearLayout(activity);
        playersContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plLp.topMargin = dp(6);
        card.addView(playersContainer, plLp);

        // 退出房间
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
            hide();
        });

        cardView = card;
        cardParams = baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT);
        cardParams.x = dp(12);
        cardParams.y = dp(120);
        cardParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
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

    private boolean onBubbleTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = bubbleParams.x;
                dragStartY = bubbleParams.y;
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
                    bubbleParams.x = clampX(dragStartX + dx);
                    bubbleParams.y = clampY(dragStartY + dy);
                    wm.updateViewLayout(bubbleView, bubbleParams);
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
        int max = activity.getResources().getDisplayMetrics().widthPixels - dp(46);
        return Math.max(0, Math.min(x, max));
    }

    private int clampY(int y) {
        int max = activity.getResources().getDisplayMetrics().heightPixels - dp(46);
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

    // ---------------- 数据刷新 ----------------

    private void refreshFromState() {
        if (!showing) {
            return;
        }
        if (EasyTierManager.get().getState() != EasyTierManager.State.CONNECTED) {
            hide();
            return;
        }
        EasyTierManager.ConnMode mode = EasyTierManager.get().getConnMode();
        int color = 0xFF4AE0A0;
        if (mode == EasyTierManager.ConnMode.RELAY) {
            color = 0xFFFFB74D;
        } else if (mode == EasyTierManager.ConnMode.UNKNOWN) {
            color = 0xFF9E9E9E;
        }
        if (bubbleView != null) {
            GradientDrawable bg = (GradientDrawable) bubbleView.getBackground();
            bg.setStroke(dp(2), color);
        }
        refreshCard();
    }

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
            cardState.setTextColor(0xFF4AE0A0);
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
            int count = players == null ? 0 : players.size();
            if (bubbleCount != null) {
                bubbleCount.setText(count + "/" + RoomCenter.MAX_PLAYERS);
            }
            if (rttMs > 0 && cardLatency != null) {
                cardLatency.setText(rttMs + "ms");
            }
            if (playersContainer != null) {
                playersContainer.removeAllViews();
                if (players != null) {
                    String selfId = PlayerIdentity.getClientId(activity);
                    for (RoomCenter.Player p : players) {
                        TextView row = new TextView(activity);
                        String name = p.isRoomHost ? "👑 " + p.name : p.name;
                        row.setText(name);
                        row.setTextSize(12);
                        row.setTextColor(p.clientId.equals(selfId) ? 0xFF8AB4F8 : 0xDDFFFFFF);
                        row.setPadding(0, dp(3), 0, dp(3));
                        playersContainer.addView(row);
                    }
                }
            }
            refreshFromState();
        });
    }
}
