package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局游戏配置：安全区、视角、视距、触控方案等保存后统一作用到所有版本号，
 * 与“启动器登录一次、所有版本免登录”逻辑一致。
 *
 * 这些配置最终以 options.txt 的 key:value 形式注入到目标版本的
 * <externalFilesDir>/games/com.mojang/minecraftpe/options.txt。
 */
public final class GlobalConfigManager {
    private static final String TAG = "GlobalConfigManager";
    private static final String PREFS_NAME = "global_game_config";

    // Bedrock options.txt 中的键（已按真机 options.txt 校正）
    public static final String KEY_SAFE_ZONE_X = "gfx_safe_zone_x";
    public static final String KEY_SAFE_ZONE_Y = "gfx_safe_zone_y";
    public static final String KEY_FOV = "gfx_field_of_view";
    public static final String KEY_VIEW_DISTANCE = "gfx_viewdistance";
    public static final String KEY_UI_PROFILE = "gfx_ui_profile";
    // 触摸控制模式：0=摇杆并点击交互 1=摇杆并瞄准十字线 2=方向键并点击交互
    public static final String KEY_TOUCH_SCHEME = "ctrl_interactionModel";
    // 分离控制（Split Touch controls）：0=关 1=开
    public static final String KEY_SPLIT_CONTROL = "ctrl_usetouchjoypad";
    // 文件存储位置：0=应用程序(internal) 1=外部(external)，锁死为外部
    public static final String KEY_FILE_STORAGE_LOCATION = "dvce_filestoragelocation";

    private static final String[] MANAGED_KEYS = {
            KEY_SAFE_ZONE_X, KEY_SAFE_ZONE_Y, KEY_FOV, KEY_VIEW_DISTANCE,
            KEY_UI_PROFILE, KEY_TOUCH_SCHEME, KEY_SPLIT_CONTROL
    };

    private GlobalConfigManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key) {
        return prefs(ctx).getString(key, null);
    }

    public static void set(Context ctx, String key, String value) {
        String trimmed = (value == null) ? null : value.trim();
        SharedPreferences.Editor editor = prefs(ctx).edit();
        if (trimmed == null || trimmed.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, trimmed);
        }
        editor.apply();
    }

    public static boolean hasAny(Context ctx) {
        SharedPreferences p = prefs(ctx);
        for (String key : MANAGED_KEYS) {
            if (p.contains(key)) return true;
        }
        return false;
    }

    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        appendIfSet(sb, ctx, KEY_SAFE_ZONE_X, "安全区X");
        appendIfSet(sb, ctx, KEY_SAFE_ZONE_Y, "安全区Y");
        appendIfSet(sb, ctx, KEY_FOV, "视角");
        appendIfSet(sb, ctx, KEY_VIEW_DISTANCE, "视距");
        appendIfSet(sb, ctx, KEY_UI_PROFILE, "UI类型");
        appendIfSet(sb, ctx, KEY_TOUCH_SCHEME, "触摸设置");
        appendIfSet(sb, ctx, KEY_SPLIT_CONTROL, "分离控制");
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    private static void appendIfSet(StringBuilder sb, Context ctx, String key, String label) {
        String value = get(ctx, key);
        if (value != null) {
            if (sb.length() > 0) sb.append("，");
            sb.append(label).append("=").append(value);
        }
    }

    /**
     * 把已保存的全局配置合并写入目标版本 externalFilesDir 下的 options.txt。
     */
    public static void apply(Context ctx, File externalFilesDir) {
        if (externalFilesDir == null) return;
        File optionsFile = new File(externalFilesDir, "games/com.mojang/minecraftpe/options.txt");
        try {
            Map<String, String> merged = readOptions(optionsFile);
            boolean changed = false;
            for (String key : MANAGED_KEYS) {
                String value = get(ctx, key);
                if (value != null) {
                    merged.put(key, value);
                    changed = true;
                }
            }
            // 锁死文件存储位置为外部（1），每次启动强制写回，防止游戏内改回"应用程序"
            merged.put(KEY_FILE_STORAGE_LOCATION, "1");
            changed = true;
            if (changed) {
                writeOptions(optionsFile, merged);
                Log.d(TAG, "Applied global config to " + optionsFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply global config", e);
        }
    }

    private static Map<String, String> readOptions(File file) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        if (!file.exists()) return options;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    options.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
        }
        return options;
    }

    private static void writeOptions(File file, Map<String, String> options) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue() + "\n");
            }
        }
    }
}
