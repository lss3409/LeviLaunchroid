package org.levimc.launcher.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v0.4.209：极限存档唯一标识 JSON 存储（用户拍板：标识走 JSON 不写入存档 db）。
 *
 * 文件：files/hardcore_world_ids.json
 * 条目：id（w- 前缀）、worldName、seed、dirName（最近一次扫描所在目录）。
 *
 * 匹配优先级：
 *  1. 目录名精确匹配（世界目录没变）；
 *  2. 世界名+种子匹配（改名/移动/复制 = 同存档并入，沿用旧 id）；
 *  3. 都不中 → 生成新 id 补打（老存档自动获得标识）。
 */
public final class HardcoreWorldIdStore {
    private static final String TAG = "HardcoreWorldIdStore";
    private static final String FILE_NAME = "hardcore_world_ids.json";

    public static class Entry {
        public String id;
        public String worldName;
        public long seed;
        public String dirName;
    }

    private final File file;
    private List<Entry> entries = new ArrayList<>();

    public HardcoreWorldIdStore(Context context) {
        this.file = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    private synchronized void load() {
        entries = new ArrayList<>();
        if (!file.exists()) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    Entry e = new Entry();
                    e.id = o.optString("id");
                    e.worldName = o.optString("worldName");
                    e.seed = o.optLong("seed");
                    e.dirName = o.optString("dirName");
                    if (!e.id.isEmpty()) entries.add(e);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load world id store: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("worldName", e.worldName != null ? e.worldName : "");
                o.put("seed", e.seed);
                o.put("dirName", e.dirName != null ? e.dirName : "");
                arr.put(o);
            }
            root.put("entries", arr);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save world id store: " + e.getMessage());
        }
    }

    /** 按目录名精确匹配。 */
    public synchronized Entry findByDirName(String dirName) {
        if (dirName == null) return null;
        for (Entry e : entries) {
            if (dirName.equals(e.dirName)) return e;
        }
        return null;
    }

    /** 按世界名+种子匹配（同存档并入）。名字按备份目录 sanitize
     *  规则对齐（旧目录名里的名字是 sanitize 后的，JSON 存原始名）。 */
    public synchronized Entry findByNameAndSeed(String worldName, long seed) {
        if (worldName == null) return null;
        String q = sanitize(worldName);
        for (Entry e : entries) {
            if (q.equals(sanitize(e.worldName)) && e.seed == seed) return e;
        }
        return null;
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 解析/补打标识：目录名命中 → 同名同种子并入 → 补打新 id。
     * 命中后更新 dirName（存档移动/改名跟随）。
     */
    public synchronized String resolveId(String dirName, String worldName, long seed) {
        Entry e = findByDirName(dirName);
        if (e == null) {
            e = findByNameAndSeed(worldName, seed);
        }
        if (e == null) {
            e = new Entry();
            e.id = "w-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            e.worldName = worldName;
            e.seed = seed;
            e.dirName = dirName;
            entries.add(e);
            OnlineDebugLog.log("hb-id 补打新标识 id=" + e.id + " name=" + worldName
                    + " seed=" + seed + " dir=" + dirName);
        } else {
            e.dirName = dirName;
            if (e.worldName == null || e.worldName.isEmpty()) e.worldName = worldName;
            if (e.seed == 0) e.seed = seed;
        }
        save();
        return e.id;
    }

    /** 所有条目（合并按钮反查用）。 */
    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    /** 旧命名备份目录名（世界名_seed种子），合并按钮找旧目录用。 */
    public static String legacyDirName(String worldName, long seed) {
        return HardcoreBackupManager.backupDirName(worldName, seed);
    }

    /** 新命名备份目录名（id_xxx）。 */
    public static String idDirName(String id) {
        return "id_" + id;
    }
}
