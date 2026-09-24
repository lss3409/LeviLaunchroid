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
 * （OnlineOverlay）共用。内容：头像/名称/皇冠、Xbox XUID、微软账号、
 * Levi 游玩时长、mc-heads.net 皮肤预览、游戏内权限胶囊；
 * 房主视角看成员时额外带「禁麦/解除禁麦」按钮（v530 行点击禁麦迁移至此）。
 */
public final class PlayerDetailCard {

    private PlayerDetailCard() {
    }

    /** 权限档位（与设置项一致，v547）：0 关闭 / 1 仅房主 / 2 所有人。 */
    public static final int PERM_NONE = 0;
    public static final int PERM_HOST_ONLY = 1;
    public static final int PERM_ALL = 2;

    private static final String PREFS = "levimc_permissions";
    private static final String PREFS_VIEW = "levimc_card_view_perm";

    /** 弹详情卡。permitMute=true 时显示禁麦按钮（房主视角看成员）。 */
    public static void show(Activity activity, RoomCenter.Player p, boolean permitMute) {
        android.util.Log.i("PlayerDetailCard", "详情卡: "
                + (p == null ? "自己" : p.name) + " permitMute=" + permitMute);
        String name = p != null ? p.name : org.levimc.launcher.core.online.PlayerIdentity.getNickname(activity);
        String avatarUrl = p != null ? p.avatarUrl : org.levimc.launcher.core.online.PlayerIdentity.getAvatarUrl(activity);
        String xuid = p != null ? p.xuid : org.levimc.launcher.core.online.PlayerIdentity.getCurrentXuid();
        String msUser = p != null ? p.msUser : org.levimc.launcher.core.online.PlayerIdentity.getCurrentMsUser();
        long playMinutes = p != null ? p.playMinutes
                : org.levimc.launcher.core.online.PlayerIdentity.getPlayMinutes(activity);

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

        // Xbox 详情
        TextView detail = new TextView(activity);
        StringBuilder sb = new StringBuilder();
        sb.append("XUID: ").append(xuid == null || xuid.isEmpty() ? "—" : xuid).append('\n');
        sb.append("微软账号: ").append(msUser == null || msUser.isEmpty() ? "—" : msUser).append('\n');
        sb.append("Levi 游玩时长: ").append(formatPlayMinutes(playMinutes));
        detail.setText(sb.toString());
        detail.setTextSize(13);
        detail.setTextColor(activity.getResources().getColor(R.color.text_secondary, activity.getTheme()));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = (int) (12 * d);
        v.addView(detail, dLp);

        // 皮肤预览（mc-heads.net 按昵称渲染）
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

        // 游戏内权限胶囊（访客/成员/管理员）
        TextView permLabel = new TextView(activity);
        permLabel.setText("游戏内权限");
        permLabel.setTextSize(12);
        permLabel.setTextColor(activity.getResources().getColor(R.color.text_secondary, activity.getTheme()));
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plLp.topMargin = (int) (12 * d);
        v.addView(permLabel, plLp);

        LinearLayout permRow = new LinearLayout(activity);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = (int) (6 * d);
        v.addView(permRow, prLp);
        String[] permNames = {"访客", "成员", "管理员"};
        String key = p != null ? p.clientId : "self";
        final int[] current = {getSavedPermission(activity, key)};
        for (int i = 0; i < permNames.length; i++) {
            final int level = i;
            TextView cap = new TextView(activity);
            cap.setText(permNames[i]);
            cap.setTextSize(12);
            cap.setGravity(Gravity.CENTER);
            GradientDrawable capBg = new GradientDrawable();
            capBg.setCornerRadius(16 * d);
            if (current[0] == level) {
                capBg.setColor(accent);
                cap.setTextColor(Color.WHITE);
            } else {
                capBg.setColor(0x22FFFFFF);
                cap.setTextColor(activity.getResources().getColor(R.color.text_secondary, activity.getTheme()));
            }
            cap.setBackground(capBg);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    0, (int) (32 * d), 1f);
            if (i > 0) {
                cLp.leftMargin = (int) (6 * d);
            }
            permRow.addView(cap, cLp);
            cap.setOnClickListener(x -> {
                savePermission(activity, key, level);
                Toast.makeText(activity, "权限已记录: " + permNames[level]
                        + "（写入存档将在后续版本接入）", Toast.LENGTH_SHORT).show();
                current[0] = level;
            });
        }

        // v547：房主视角看成员 → 禁麦/解除禁麦按钮
        if (permitMute && p != null) {
            com.google.android.material.button.MaterialButton muteBtn =
                    new com.google.android.material.button.MaterialButton(activity);
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

    private static int getSavedPermission(Activity activity, String clientId) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getInt(clientId, 1);
    }

    private static void savePermission(Activity activity, String clientId, int level) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putInt(clientId, level).apply();
    }

    private static String formatPlayMinutes(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? h + " 小时 " + m + " 分" : m + " 分钟";
    }

    /** 头像底圆形背景（跟随个性化强调色，v544 起统一）。 */
    private static GradientDrawable accentAvatarBg(int accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(accent);
        return bg;
    }
}
