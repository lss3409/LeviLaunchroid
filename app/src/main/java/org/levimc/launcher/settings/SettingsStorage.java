package org.levimc.launcher.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class SettingsStorage {
    private static final String SP_NAME = "feature_settings";
    private static final String KEY_SETTINGS_JSON = "settings_json";
    private static final Gson gson = new Gson();

    public static void save(Context context, FeatureSettings settings) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String json = gson.toJson(settings);
        sp.edit().putString(KEY_SETTINGS_JSON, json).apply();
    }

    public static FeatureSettings load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_SETTINGS_JSON, null);
        if (json == null) {
            return new FeatureSettings();
        }
        try {
            FeatureSettings loaded = gson.fromJson(json, FeatureSettings.class);
            // v704：前台服务默认值一次性迁移——v587 时代默认 false 的旧存档
            // （Gson 缺字段时为 false，不跑字段初始化器）强制改为开启。
            // 用户实测 v490 前台服务不杀后台（联机掉线根因之一）。
            // 迁移后用户可在设置页自行关闭（开关仍生效）。
            if (!sp.getBoolean("fg_default_migrated", false)) {
                loaded.setForegroundServiceEnabled(true);
                save(context, loaded);
                sp.edit().putBoolean("fg_default_migrated", true).apply();
            }
            return loaded;
        } catch (JsonSyntaxException e) {
            return new FeatureSettings();
        }
    }

    public static void clear(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_SETTINGS_JSON).apply();
    }
}