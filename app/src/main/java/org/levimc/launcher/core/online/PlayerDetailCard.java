package org.levimc.launcher.core.online;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

/**
 * v547：玩家详情卡公共组件——联机页（OnlineActivity）与游戏内悬浮窗
 * （OnlineOverlay）共用。内容：头像/名称/皇冠、Xbox XUID、Levi 游玩时长、
 * 最近在线时间、mc-heads.net 皮肤预览；
 * 房主视角看成员时额外带「禁麦/解除禁麦」按钮（v530 行点击禁麦迁移至此）。
 * v549：删微软账号/游戏内权限胶囊（用户要求）；点卡片外区域可关闭（同二维码弹窗）。
 */
public final class PlayerDetailCard {

    private PlayerDetailCard() {
    }

    /** 权限档位（与设置项一致，v547）：0 关闭 / 1 仅房主 / 2 所有人。 */
    public static final int PERM_NONE = 0;
    public static final int PERM_HOST_ONLY = 1;
    public static final int PERM_ALL = 2;

    private static final String PREFS_VIEW = "levimc_card_view_perm";

    /** 弹详情卡。permitMute=true 时显示禁麦按钮（房主视角看成员）。 */
    public static void show(Activity activity, RoomCenter.Player p, boolean permitMute) {
        android.util.Log.i("PlayerDetailCard", "详情卡: "
                + (p == null ? "自己" : p.name) + " permitMute=" + permitMute);
        String name = p != null ? p.name : org.levimc.launcher.core.online.PlayerIdentity.getNickname(activity);
        String avatarUrl = p != null ? p.avatarUrl : org.levimc.launcher.core.online.PlayerIdentity.getAvatarUrl(activity);
        String xuid = p != null ? p.xuid : org.levimc.launcher.core.online.PlayerIdentity.getCurrentXuid();
        long playMinutes = p != null ? p.playMinutes
                : org.levimc.launcher.core.online.PlayerIdentity.getPlayMinutes(activity);
        long lastActive = p != null ? p.lastActive
                : org.levimc.launcher.core.online.PlayerIdentity.getLastActiveStatic();

        float d = activity.getResources().getDisplayMetrics().density;
        int accent = new PersonalizationManager(activity).getAccentColor();
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding((int) (20 * d), (int) (18 * d), (int) (20 * d), (int) (18 * d));

        // 头部：头像 + 名字 + 房主皇冠
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.FrameLayout avFrame = new android.widget.FrameLayout(activity);
        TextView avChar = new TextView(activity);
        avChar.setText(name == null || name.isEmpty() ? "?" : name.substring(0, 1));
        avChar.setGravity(Gravity.CENTER);
        avChar.setTextColor(Color.WHITE);
        avChar.setTextSize(16);
        avChar.setBackground(accentAvatarBg(accent));
        avFrame.addView(avChar, new android.widget.FrameLayout.LayoutParams((int) (44 * d), (int) (44 * d)));
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            ImageView avImg = new ImageView(activity);
            com.bumptech.glide.Glide.with(activity).load(avatarUrl).circleCrop().into(avImg);
            avFrame.addView(avImg, new android.widget.FrameLayout.LayoutParams((int) (44 * d), (int) (44 * d)));
        }
        header.addView(avFrame);
        TextView nameTv = new TextView(activity);
        nameTv.setText((p != null && p.isRoomHost ? "👑 " : "") + name);
        nameTv.setTextSize(16);
        nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
        nameTv.setTextColor(activity.getResources().getColor(R.color.on_surface, activity.getTheme()));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nLp.leftMargin = (int) (12 * d);
        header.addView(nameTv, nLp);
        v.addView(header);

        // 详情（v549：删微软账号行，加最近在线）
        TextView detail = new TextView(activity);
        StringBuilder sb = new StringBuilder();
        sb.append("XUID: ").append(xuid == null || xuid.isEmpty() ? "—" : xuid).append('\n');
        sb.append("Levi 游玩时长: ").append(formatPlayMinutes(playMinutes)).append('\n');
        sb.append("最近在线: ").append(formatLastActive(lastActive));
        detail.setText(sb.toString());
        detail.setTextSize(13);
        detail.setTextColor(activity.getResources().getColor(R.color.text_secondary, activity.getTheme()));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = (int) (12 * d);
        v.addView(detail, dLp);

        // 皮肤预览（mc-heads.net 按昵称渲染；未设置皮肤显示默认 Steve/Alex）
        ImageView skin = new ImageView(activity);
        skin.setAdjustViewBounds(true);
        try {
            String skinUrl = "https://mc-heads.net/body/"
                    + java.net.URLEncoder.encode(name, "UTF-8") + ".png";
            com.bumptech.glide.Glide.with(activity).load(skinUrl).into(skin);
        } catch (Exception ignored) {
        }
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                (int) (90 * d), (int) (160 * d));
        sLp.topMargin = (int) (10 * d);
        sLp.gravity = Gravity.CENTER_HORIZONTAL;
        v.addView(skin, sLp);

        // v547：房主视角看成员 → 禁麦/解除禁麦按钮
        if (permitMute && p != null) {
            // v552：MaterialButton 必须用 Material 主题 context——游戏进程
            // Activity 是 AppCompat 主题会抛 ThemeEnforcement 崩溃（tombstone 549），
            // 用弹窗同款 LeviDialogTheme（MaterialComponents）包裹
            com.google.android.material.button.MaterialButton muteBtn =
                    new com.google.android.material.button.MaterialButton(
                            new android.view.ContextThemeWrapper(activity,
                                    org.levimc.launcher.R.style.LeviDialogTheme));
            boolean muted = RoomCenter.isMuted(p.clientId);
            muteBtn.setText(muted ? "解除禁麦" : "禁麦");
            org.levimc.launcher.util.AccentStyler.stylePrimary(activity, muteBtn);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * d));
            mLp.topMargin = (int) (14 * d);
            v.addView(muteBtn, mLp);
            muteBtn.setOnClickListener(x -> {
                boolean nowMuted = !RoomCenter.isMuted(p.clientId);
                RoomCenter.sendMute(p.clientId, nowMuted);
                Toast.makeText(activity, nowMuted ? "已禁麦 " + p.name : "已解除禁麦 " + p.name,
                        Toast.LENGTH_SHORT).show();
                muteBtn.setText(nowMuted ? "解除禁麦" : "禁麦");
            });
        }

        org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(activity);
        dialog.setCustomView(v);
        // v549：点卡片外区域可关闭（与二维码弹窗一致）
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /** 查看目标玩家详情卡前的权限检查（被查看者的「卡片查看权限」随心跳广播，
     *  存在 Player.viewPerm，v547）。viewerIsHost：查看者是否房主；
     *  房主与查看自己永远放行。 */
    public static boolean canView(Activity activity, RoomCenter.Player p, boolean viewerIsHost) {
        if (p == null || p.clientId == null) {
            return true;
        }
        String selfId = PlayerIdentity.getClientId(activity);
        if (p.clientId.equals(selfId) || viewerIsHost) {
            return true;
        }
        return p.viewPerm == PERM_ALL;
    }

    /** 本机「卡片查看权限」设置（谁可以看我的详情卡），设置按钮弹窗用；
     *  同步进 RoomCenter 心跳广播。 */
    public static void setViewPerm(Activity activity, int perm) {
        activity.getSharedPreferences(PREFS_VIEW, Activity.MODE_PRIVATE)
                .edit().putInt("self", perm).apply();
        RoomCenter.setSelfViewPerm(perm);
    }

    public static int getViewPerm(Activity activity) {
        return activity.getSharedPreferences(PREFS_VIEW, Activity.MODE_PRIVATE)
                .getInt("self", PERM_ALL);
    }

    private static String formatPlayMinutes(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? h + " 小时 " + m + " 分" : m + " 分钟";
    }

    /** v549：最近在线格式化——刚刚/x 分钟前/x 小时前/x 天前/具体日期。 */
    private static String formatLastActive(long ts) {
        if (ts <= 0) {
            return "—";
        }
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000L) {
            return "刚刚";
        }
        long minutes = diff / 60_000L;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 7) {
            return days + " 天前";
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.ROOT);
        return fmt.format(new java.util.Date(ts));
    }

    /** 头像底圆形背景（跟随个性化强调色，v544 起统一）。 */
    private static GradientDrawable accentAvatarBg(int accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(accent);
        return bg;
    }
}
