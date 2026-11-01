package org.levimc.launcher.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * v0.4.210：地图查看器 chunk 哈希存储（新区块检测）。
 *
 * 文件：files/chunk_hashes_<世界目录名>.json
 * 结构：{"dims": {"overworld": {"x_z": "hash8", ...}, "nether": {...}}}
 * hash8 = chunk tile（16×16 渲染结果）SHA-256 前 4 字节 hex。
 *
 * 判定新区块：坐标无记录（新生成的 chunk）或哈希变化（更新的地图）
 * → 地图查看器首次显示时刷浅绿蒙版；本次会话渲染过的 chunk 在提交
 * 后写入存储，下次打开不再标绿。
 */
public final class ChunkHashStore {
    private static final String TAG = "ChunkHashStore";

    private final File file;
    private final Map<String, Map<String, String>> dims = new HashMap<>();

    public ChunkHashStore(Context context, String worldDirName) {
        this.file = new File(context.getFilesDir(),
                "chunk_hashes_" + sanitize(worldDirName) + ".json");
        load();
    }

    private synchronized void load() {
        dims.clear();
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
            JSONObject dimsObj = root.optJSONObject("dims");
            if (dimsObj == null) return;
            for (Iterator<String> it = dimsObj.keys(); it.hasNext(); ) {
                String dim = it.next();
                JSONObject dimObj = dimsObj.optJSONObject(dim);
                if (dimObj == null) continue;
                Map<String, String> m = new HashMap<>();
                for (Iterator<String> kt = dimObj.keys(); kt.hasNext(); ) {
                    String k = kt.next();
                    String v = dimObj.optString(k);
                    if (!v.isEmpty()) m.put(k, v);
                }
                dims.put(dim, m);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load chunk hashes: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            JSONObject dimsObj = new JSONObject();
            for (Map.Entry<String, Map<String, String>> de : dims.entrySet()) {
                JSONObject dimObj = new JSONObject();
                for (Map.Entry<String, String> e : de.getValue().entrySet()) {
                    dimObj.put(e.getKey(), e.getValue());
                }
                dimsObj.put(de.getKey(), dimObj);
            }
            root.put("dims", dimsObj);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save chunk hashes: " + e.getMessage());
        }
    }

    /** 查上次记录的哈希；无记录返回 null。 */
    public synchronized String getHash(String dim, int cx, int cz) {
        Map<String, String> m = dims.get(dim);
        if (m == null) return null;
        return m.get(keyOf(cx, cz));
    }

    /** 写入哈希（本会话渲染过的 chunk 提交）。 */
    public synchronized void putHash(String dim, int cx, int cz, String hash) {
        Map<String, String> m = dims.get(dim);
        if (m == null) {
            m = new HashMap<>();
            dims.put(dim, m);
        }
        m.put(keyOf(cx, cz), hash);
    }

    public static String keyOf(int cx, int cz) {
        return cx + "_" + cz;
    }

    private static String sanitize(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
