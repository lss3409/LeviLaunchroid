package org.levimc.launcher.core.online;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * v642：联机房间收藏（照搬 Astral 收藏功能）——SharedPreferences 持久化，
 * 收藏项：房间码（短码）/房间名/游戏名。首页预览 + 一键加入用。
 */
public final class OnlineBookmarks {

    private static final String PREFS = "online_bookmarks";
    private static final String KEY = "items";

    /** 收藏项。 */
    public static class Item {
        public final String code;
        public final String name;
        public final String game;

        public Item(String code, String name, String game) {
            this.code = code;
            this.name = name;
            this.game = game;
        }
    }

    private OnlineBookmarks() {
    }

    public static synchronized List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            String raw = ctx.getApplicationContext().getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE).getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String code = o.optString("code", "");
                String name = o.optString("name", "");
                String game = o.optString("game", "");
                if (!code.isEmpty()) {
                    out.add(new Item(code, name, game));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized boolean isBookmarked(Context ctx, String code) {
        for (Item it : load(ctx)) {
            if (it.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 添加收藏（去重；返回是否新增）。 */
    public static synchronized boolean add(Context ctx, String code, String name, String game) {
        List<Item> list = load(ctx);
        for (Item it : list) {
            if (it.code.equals(code)) {
                return false;
            }
        }
        list.add(new Item(code, name, game));
        save(ctx, list);
        return true;
    }

    public static synchronized void remove(Context ctx, String code) {
        List<Item> list = load(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).code.equals(code)) {
                list.remove(i);
                break;
            }
        }
        save(ctx, list);
    }

    private static void save(Context ctx, List<Item> list) {
        JSONArray arr = new JSONArray();
        for (Item it : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("code", it.code);
                o.put("name", it.name);
                o.put("game", it.game);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
