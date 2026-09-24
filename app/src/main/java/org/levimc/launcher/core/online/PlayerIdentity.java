package org.levimc.launcher.core.online;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * 联机玩家身份（v500）：不登录 Xbox 也能联机（EasyTier 虚拟局域网与
 * Xbox 在线服务无关）。首次进入生成随机昵称「玩家_XXXX」与设备 UUID；
 * clientId = LeviLauncher-<版本>-<UUID 前 8 位>，用于房间内区分玩家。
 */
public final class PlayerIdentity {

    private static final String PREFS = "levimc_identity";
    private static final String KEY_NICK = "nickname";
    private static final String KEY_UUID = "device_uuid";
    private static final String NICK_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static volatile String cachedNick;
    private static volatile String cachedUuid;
    /** 最近一次解析出的昵称（无 Context 读取，RoomCenter 心跳用，v527）。 */
    private static volatile String currentNick;
    /** 最近一次解析出的头像 URL（心跳同步用，v529）。 */
    private static volatile String currentAvatarUrl;
    /** v544：Xbox XUID / 微软账号（心跳同步用，供玩家详情卡展示）。 */
    private static volatile String currentXuid;
    private static volatile String currentMsUser;
    /** v544：最近一次读取的游玩分钟（无 Context 心跳用）。 */
    private static volatile long currentPlayMinutes;

    private PlayerIdentity() {
    }

    /** 显示昵称（gamertag 优先——启动器已登录 Xbox 时用；否则随机昵称）。 */
    public static String getNickname(Context ctx) {
        if (cachedNick != null) {
            return cachedNick;
        }
        String gamertag = loadGamertag(ctx);
        if (gamertag != null && !gamertag.isEmpty()) {
            cachedNick = gamertag;
            currentNick = gamertag;
            return cachedNick;
        }
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String nick = sp.getString(KEY_NICK, null);
        if (nick == null || nick.isEmpty()) {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder("玩家_");
            for (int i = 0; i < 4; i++) {
                sb.append(NICK_CHARS.charAt(r.nextInt(NICK_CHARS.length())));
            }
            nick = sb.toString();
            sp.edit().putString(KEY_NICK, nick).apply();
        }
        cachedNick = nick;
        currentNick = nick;
        return nick;
    }

    /** 无 Context 读取最近一次解析出的昵称（心跳等热路径用）。 */
    public static String getCurrentNick() {
        return currentNick;
    }

    /** 无 Context 读取最近一次解析出的头像 URL（心跳同步用，v529）。 */
    public static String getCurrentAvatarUrl() {
        return currentAvatarUrl;
    }

    /** v544：XUID（玩家详情卡展示）。 */
    public static String getCurrentXuid() {
        return currentXuid;
    }

    /** v544：微软账号（通常为邮箱，玩家详情卡展示）。 */
    public static String getCurrentMsUser() {
        return currentMsUser;
    }

    /** v544：累计游玩时长（分钟，SharedPreferences 累加）。 */
    public static long getPlayMinutes(Context ctx) {
        long m = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("play_minutes", 0);
        currentPlayMinutes = m;
        return m;
    }

    /** v544：最近一次读取的游玩分钟（无 Context 心跳用）。 */
    public static long getCurrentPlayMinutes() {
        return currentPlayMinutes;
    }

    /** v544：MinecraftActivity 会话结束时长累加。 */
    public static void addPlayMinutes(Context ctx, long minutes) {
        if (minutes <= 0) {
            return;
        }
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong("play_minutes", getPlayMinutes(ctx) + minutes).apply();
    }

    /**
     * v527：Xbox 登录/切换账号后刷新身份（MsftAccountStore 回调）。
     * 清缓存并立即用新 gamertag 更新 currentNick——进程内登录后
     * 联机 ID 马上换成 Xbox 的，不用重启进程。
     */
    public static void refresh(Context ctx) {
        String[] profile = loadXboxProfile(ctx);
        String gamertag = profile[0];
        currentAvatarUrl = profile[1];
        currentXuid = profile[2];
        currentMsUser = profile[3];
        if (gamertag != null && !gamertag.isEmpty()) {
            cachedNick = gamertag;
            currentNick = gamertag;
        } else {
            cachedNick = null; // 登出后回落到随机昵称
        }
    }

    /** 用户自定义昵称。 */
    public static void setNickname(Context ctx, String nick) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NICK, nick).apply();
        cachedNick = nick;
    }

    /** 设备唯一标识（首次生成后持久化）。 */
    public static String getDeviceUuid(Context ctx) {
        if (cachedUuid != null) {
            return cachedUuid;
        }
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uuid = sp.getString(KEY_UUID, null);
        if (uuid == null || uuid.isEmpty()) {
            uuid = java.util.UUID.randomUUID().toString();
            sp.edit().putString(KEY_UUID, uuid).apply();
        }
        cachedUuid = uuid;
        return uuid;
    }

    /** 房间内唯一标识：LeviLauncher-<版本>-<UUID 前 8 位>。 */
    public static String getClientId(Context ctx) {
        String ver = "1.0";
        try {
            ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String uuid = getDeviceUuid(ctx).replace("-", "");
        return "LeviLauncher-" + ver + "-" + uuid.substring(0, 8).toUpperCase();
    }

    /** 读取启动器已登录的 Xbox gamertag（无则返回 null）。 */
    private static String loadGamertag(Context ctx) {
        String[] profile = loadXboxProfile(ctx);
        return profile[0];
    }

    /** 读取 Xbox 账号头像 URL（无则 null）。 */
    public static String getAvatarUrl(Context ctx) {
        String[] profile = loadXboxProfile(ctx);
        currentAvatarUrl = profile[1];
        currentXuid = profile[2];
        currentMsUser = profile[3];
        return profile[1];
    }

    /** v544：从 MsftAccountStore 读 active 账号的 [gamertag, avatarUrl, xuid, msUserId]。 */
    private static String[] loadXboxProfile(Context ctx) {
        try {
            for (org.levimc.launcher.core.auth.MsftAccountStore.MsftAccount acc
                    : org.levimc.launcher.core.auth.MsftAccountStore.list(ctx)) {
                if (acc.active && acc.xboxGamertag != null && !acc.xboxGamertag.isEmpty()) {
                    return new String[]{acc.xboxGamertag,
                            acc.xboxAvatarUrl == null ? "" : acc.xboxAvatarUrl,
                            acc.xuid == null ? "" : acc.xuid,
                            acc.msUserId == null ? "" : acc.msUserId};
                }
            }
            // 无 active 标记时用第一个有昵称的账号
            for (org.levimc.launcher.core.auth.MsftAccountStore.MsftAccount acc
                    : org.levimc.launcher.core.auth.MsftAccountStore.list(ctx)) {
                if (acc.xboxGamertag != null && !acc.xboxGamertag.isEmpty()) {
                    return new String[]{acc.xboxGamertag,
                            acc.xboxAvatarUrl == null ? "" : acc.xboxAvatarUrl,
                            acc.xuid == null ? "" : acc.xuid,
                            acc.msUserId == null ? "" : acc.msUserId};
                }
            }
        } catch (Exception | NoClassDefFoundError ignored) {
        }
        return new String[]{null, "", "", ""};
    }
}
