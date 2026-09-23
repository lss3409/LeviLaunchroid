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
        return nick;
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
        return loadXboxProfile(ctx)[1];
    }

    /** 从 MsftAccountStore 读 active 账号的 [gamertag, avatarUrl]。 */
    private static String[] loadXboxProfile(Context ctx) {
        try {
            for (org.levimc.launcher.core.auth.MsftAccountStore.MsftAccount acc
                    : org.levimc.launcher.core.auth.MsftAccountStore.list(ctx)) {
                if (acc.active && acc.xboxGamertag != null && !acc.xboxGamertag.isEmpty()) {
                    return new String[]{acc.xboxGamertag,
                            acc.xboxAvatarUrl == null ? "" : acc.xboxAvatarUrl};
                }
            }
            // 无 active 标记时用第一个有昵称的账号
            for (org.levimc.launcher.core.auth.MsftAccountStore.MsftAccount acc
                    : org.levimc.launcher.core.auth.MsftAccountStore.list(ctx)) {
                if (acc.xboxGamertag != null && !acc.xboxGamertag.isEmpty()) {
                    return new String[]{acc.xboxGamertag,
                            acc.xboxAvatarUrl == null ? "" : acc.xboxAvatarUrl};
                }
            }
        } catch (Exception | NoClassDefFoundError ignored) {
        }
        return new String[]{null, ""};
    }
}
