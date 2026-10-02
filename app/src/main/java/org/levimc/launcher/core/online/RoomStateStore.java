package org.levimc.launcher.core.online;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v645：房间恒久化（照搬 Astral 逻辑）——组网成功时把房间码/身份持久化，
 * 进程被杀后重开联机页自动恢复房间（房主按原码重建、成员按原码重新加入）。
 * 用户主动「退出房间」才清除。
 * v704：从 v692 移植回 v560 基线（v693 回滚时删除，用户要求加回联机保护）。
 */
public final class RoomStateStore {

    private static final String PREFS = "room_state";
    private static final String KEY_CODE = "code";
    private static final String KEY_HOST = "host";

    /** 持久化的房间快照。 */
    public static class State {
        public final String code;
        public final boolean isHost;

        public State(String code, boolean isHost) {
            this.code = code;
            this.isHost = isHost;
        }
    }

    private RoomStateStore() {
    }

    public static synchronized void save(Context ctx, String code, boolean isHost) {
        if (code == null || code.isEmpty()) {
            return;
        }
        SharedPreferences.Editor e = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(KEY_CODE, code);
        e.putBoolean(KEY_HOST, isHost);
        e.apply();
    }

    public static synchronized State load(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = p.getString(KEY_CODE, null);
        if (code == null || code.isEmpty()) {
            return null;
        }
        return new State(code, p.getBoolean(KEY_HOST, false));
    }

    /** 主动退出房间时调用（此后不再自动恢复）。 */
    public static synchronized void clear(Context ctx) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }
}
