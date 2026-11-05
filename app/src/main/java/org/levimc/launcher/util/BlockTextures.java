package org.levimc.launcher.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** v0.4.214：方块贴图加载器——外部 MCJE 贴图库
 *  （&lt;内容根&gt;/textures/blocks/&lt;方块名&gt;.png，用户可替换，
 *  每次读取同步更新显示）+ LRU 缓存（含 null negative cache）。 */
public final class BlockTextures {
    private static final Map<String, Bitmap> cache =
            new LinkedHashMap<String, Bitmap>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 512;
                }
            };

    private BlockTextures() {}

    /** 方块全名 → 贴图文件名（去 minecraft: 前缀）。 */
    public static String fileName(String blockName) {
        if (blockName == null) return null;
        int colon = blockName.indexOf(':');
        String file = colon > 0 ? blockName.substring(colon + 1) : blockName;
        // v0.4.214：MCJE 特殊文件名别名（水/岩浆流等无同名贴图）
        String alias = ALIASES.get(file);
        return alias != null ? alias : file;
    }

    /** MCJE 文件名别名：方块名 → 实际贴图文件名。 */
    private static final Map<String, String> ALIASES = new java.util.HashMap<>();

    static {
        ALIASES.put("water", "water_still");
        ALIASES.put("flowing_water", "water_flow");
        ALIASES.put("lava", "lava_still");
        ALIASES.put("flowing_lava", "lava_flow");
        // v0.4.214：基岩版方块名 → MCJE 文件名（1.26 存档的旧名/改名）
        ALIASES.put("hardened_clay", "terracotta");
        ALIASES.put("deadbush", "dead_bush");
        ALIASES.put("yellow_flower", "dandelion");
        ALIASES.put("tall_grass", "tall_grass_top");
        ALIASES.put("snow_layer", "snow");
        ALIASES.put("kelp", "kelp_plant");
        ALIASES.put("attached_pumpkin_stem", "pumpkin_stem");
        ALIASES.put("attached_melon_stem", "melon_stem");
    }

    /** 加载方块贴图（顶面优先）；不存在返回 null。 */
    public static Bitmap load(Context context, String blockName) {
        return loadVariant(context, blockName, "top");
    }

    /** 加载指定面的贴图变体：top→&lt;name&gt;_top.png、side→
     *  &lt;name&gt;_side.png，缺失回退 &lt;name&gt;.png。 */
    public static Bitmap loadVariant(Context context, String blockName, String variant) {
        String file = fileName(blockName);
        if (file == null) return null;
        String cacheKey = file + "#" + variant;
        synchronized (cache) {
            if (cache.containsKey(cacheKey)) return cache.get(cacheKey);
            try {
                File dir = LauncherStorage.getTexturesDir(context);
                // 候选：<name>_<variant>.png → <name>.png
                String[] cands =
                        variant == null || variant.isEmpty()
                                ? new String[] {file}
                                : new String[] {file + "_" + variant, file};
                for (String cand : cands) {
                    File f = new File(dir, "blocks/" + cand + ".png");
                    if (f.isFile()) {
                        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                        cache.put(cacheKey, bmp);
                        return bmp;
                    }
                }
                cache.put(cacheKey, null); // negative cache
                return null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
