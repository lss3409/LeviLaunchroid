package org.levimc.launcher.core.content.worldmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.leveldb.LevelDBEntry;
import org.levimc.launcher.core.content.leveldb.LevelDBReader;
import org.levimc.launcher.core.content.leveldb.NativeLevelDb;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blocktopograph 风格的世界俯视缩略图渲染器（Bedrock LevelDB）。
 *
 * <p>扫描 LevelDB 中的主世界 chunk，解析每个 chunk 的高度数据并映射为颜色，
 * 每 chunk 1~2 像素绘制成俯视地图。整个渲染过程为 CPU/IO 密集操作，
 * <b>必须在后台线程调用</b>（不要在 UI 线程使用）。
 *
 * <p>chunk key 格式（Bedrock LevelDB）：
 * <ul>
 *   <li>9/10 字节（1.18 前旧格式）：[4B LE x][4B LE z][1B 类型][(1B subchunk)]，
 *       无维度段，一律视为主世界（维度 0）。</li>
 *   <li>13/14 字节（1.18+）：[4B LE x][4B LE z][4B LE 维度id][1B 类型][(1B subchunk)]，
 *       维度 0=主世界、1=地狱、2=末地。类型 0x2F=ChunkVersion（含高度图），
 *       0x30=ChunkData（subchunk 数据，前缀版本字节 47/0x2F）。</li>
 * </ul>
 */
public class WorldMapRenderer {

    private static final String TAG = "WorldMap";

    /** Bitmap 尺寸上限（像素） */
    public static final int MAX_BITMAP_SIZE = 1024;
    /** 单轴 chunk 数超过该值时按桶降采样 */
    public static final int DEFAULT_MAX_CHUNKS_PER_AXIS = 256;

    /** 1.18+ chunk key 类型：ChunkVersion（0x2F）与 ChunkData（0x30） */
    private static final int KEY_TYPE_LEGACY_MIXED = 0x2F;
    private static final int KEY_TYPE_CHUNK_DATA = 0x30;
    /** Data3D（0x2B，1.18+）：高度图 int16[256] + 3D biome 存储 */
    private static final int KEY_TYPE_DATA_3D = 0x2B;
    /** Data2D：高度图（int16[256]）+ biome（byte[256]） */
    private static final int KEY_TYPE_DATA_2D = 0x2D;

    /** 主世界维度 id（内部逻辑编号） */
    private static final int DIM_OVERWORLD = 0;
    /** 下界维度 id（内部逻辑编号） */
    private static final int DIM_NETHER = 1;
    /** 末地维度 id（内部逻辑编号） */
    private static final int DIM_END = 2;

    /**
     * LevelDB key 中存储的维度值 → 内部逻辑编号。
     * 实测 1.26 存档（下界 chunk key dim 字段）：0=主世界、1=下界、2=末地，
     * 与逻辑编号一致直接使用（Key Calculator 的 -1 编码是更早版本的格式）。
     */
    private static int mapDimFromKey(int rawDim) {
        return rawDim;
    }
    /** BlockEntity 方块实体 key tag（bedrock-level 枚举：0x31；0x33 是 PendingTicks） */
    private static final int KEY_TYPE_BLOCK_ENTITY = 0x31;
    /** HardCodedSpawnAreas 结构生成区域 key tag（bedrock-level 枚举：0x39） */
    private static final int KEY_TYPE_HSA = 0x39;

    /** 高度未知标记（解析失败 / 无高度数据的 chunk） */
    private static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    /** HeightMap 中优先使用的键名（Bedrock 2D 高度图） */
    private static final String[] HEIGHT_MAP_KEYS = {
            "WORLD_SURFACE", "WORLD_SURFACE_WG", "OCEAN_FLOOR", "MOTION_BLOCKING"
    };

    private static Context appContext;

    /** bedrockmap 颜色表（assets/biome_color.json / block_color.json，运行时加载）。
     *  biome 色调表：id → {rgbR,G,B, grassR,G,B, leavesR,G,B, waterR,G,B}——
     *  草地/树叶贴图是灰度模板，渲染时乘群系色调（MC 着色器机制）。 */
    private static final Map<Integer, int[]> biomeTintTable = new HashMap<>();
    private static final Map<String, Integer> blockColorTable = new HashMap<>();

    private WorldMapRenderer() {
    }

    /** 在使用前注入应用 Context（用于解析 R.color 地图调色板 + 加载 bedrockmap 颜色表）。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        loadColorTables(appContext);
    }

    /** 加载 bedrockmap 颜色表（JSON → HashMap；失败静默回退内置色表）。 */
    private static void loadColorTables(Context ctx) {
        try {
            org.json.JSONObject biomes = new org.json.JSONObject(readAsset(ctx, "biome_color.json"));
            java.util.Iterator<String> bk = biomes.keys();
            while (bk.hasNext()) {
                String key = bk.next();
                org.json.JSONObject entry = biomes.getJSONObject(key);
                if (entry.has("id") && entry.has("rgb")) {
                    int id = entry.getInt("id");
                    int[] rgb = readRgb3(entry.optJSONArray("rgb"));
                    int[] grass = entry.has("grass") ? readRgb3(entry.optJSONArray("grass")) : rgb;
                    int[] leaves = entry.has("leaves") ? readRgb3(entry.optJSONArray("leaves")) : rgb;
                    int[] water = entry.has("water") ? readRgb3(entry.optJSONArray("water"))
                            : new int[]{63, 118, 228};
                    biomeTintTable.put(id, new int[]{
                            rgb[0], rgb[1], rgb[2],
                            grass[0], grass[1], grass[2],
                            leaves[0], leaves[1], leaves[2],
                            water[0], water[1], water[2]
                    });
                }
            }
            Log.i(TAG, "biome 色调表加载: " + biomeTintTable.size() + " 项");
        } catch (Exception e) {
            Log.w(TAG, "biome 色表加载失败，回退内置色表", e);
        }
        try {
            org.json.JSONObject blocks = new org.json.JSONObject(readAsset(ctx, "block_color.json"));
            java.util.Iterator<String> keys = blocks.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                try {
                    org.json.JSONObject variants = blocks.getJSONObject(name);
                    // 取第一个变体。灰度值（如 grass_block 的 grass_top=147 灰、leaves 灰）
                    // 是色调模板——surfaceColor 会乘群系 grass/leaves 色调（MC 着色器机制）
                    String firstVariant = variants.keys().next();
                    org.json.JSONArray rgb = variants.getJSONArray(firstVariant);
                    if (rgb.length() >= 3) {
                        int color = 0xFF000000 | (rgb.getInt(0) << 16) | (rgb.getInt(1) << 8) | rgb.getInt(2);
                        blockColorTable.put(name, color);
                    }
                } catch (Exception ignored) {
                }
            }
            Log.i(TAG, "方块色表加载: " + blockColorTable.size() + " 项");
        } catch (Exception e) {
            Log.w(TAG, "方块色表加载失败，回退内置色表", e);
        }
    }

    private static int[] readRgb3(org.json.JSONArray rgb) {
        int[] out = new int[3];
        if (rgb != null && rgb.length() >= 3) {
            for (int i = 0; i < 3; i++) {
                out[i] = Math.max(0, Math.min(255, rgb.optInt(i)));
            }
        }
        return out;
    }

    private static String readAsset(Context ctx, String name) throws java.io.IOException {
        try (java.io.InputStream is = ctx.getAssets().open(name);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 渲染主世界俯视缩略图。内部自行读取 LevelDB。
     * 必须在线程中调用（IO + NBT 解析耗时较长）。
     *
     * @param dbDir            世界目录下的 db 目录
     * @param maxChunksPerAxis 单轴 chunk 数上限，超出时按桶降采样
     * @return 渲染好的 Bitmap；无可用 chunk 或读取失败时返回 null
     */
    public static Bitmap renderOverworld(File dbDir, int maxChunksPerAxis) {
        if (dbDir == null || !dbDir.isDirectory()) {
            Log.i(TAG, "渲染失败: db 目录缺失或不可读: " + dbDir);
            return null;
        }
        try {
            // 优先 BTR 同款原生库（自带全部 MCPE 压缩格式），失败回退纯 Java
            List<LevelDBEntry> entries = null;
            try {
                entries = NativeLevelDb.readAllEntries(dbDir);
            } catch (Throwable ignored) {
            }
            if (entries == null) {
                LevelDBReader reader = new LevelDBReader(dbDir);
                entries = reader.readAllEntries();
                reader.close();
            }
            Log.i(TAG, "db 扫描完成: 目录=" + dbDir.getAbsolutePath()
                    + ", 总条目=" + entries.size());
            return renderFromEntries(entries, maxChunksPerAxis);
        } catch (Exception e) {
            Log.i(TAG, "渲染失败: db 读取异常: " + dbDir.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 从已读取的 LevelDB 条目渲染主世界缩略图（避免重复扫描 db）。
     * 必须在线程中调用。
     */
    public static Bitmap renderFromEntries(List<LevelDBEntry> entries, int maxChunksPerAxis) {
        if (entries == null || entries.isEmpty()) {
            Log.i(TAG, "渲染失败: db 缺失或无条目");
            return null;
        }
        if (maxChunksPerAxis <= 0) {
            maxChunksPerAxis = DEFAULT_MAX_CHUNKS_PER_AXIS;
        }

        // 1) 收集主世界 chunk 高度数据。
        // BTR 式逐方块渲染：0x2d Data2D 携带 int16[256] 完整高度图（16×16 每方块一个高度），
        // 用它逐像素绘制；只有 subchunk/ChunkVersion 的 chunk 用 chunk 级高度填充整块。
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Long, int[]> heightMaps = new HashMap<>();   // 16×16 逐方块高度（0x2d）
        Map<Long, Integer> chunkHeights = new HashMap<>(); // chunk 级高度（subchunk 等）
        int scannedChunks = 0;
        int parsedChunks = 0;
        int failedChunks = 0;

        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            int[] chunkKey = parseChunkKey(rawKey);
            if (chunkKey == null || chunkKey[2] != DIM_OVERWORLD) {
                continue;
            }
            int x = chunkKey[0];
            int z = chunkKey[1];
            long key = pack(x, z);
            scannedChunks++;
            try {
                if (isData2dKey(rawKey)) {
                    // 0x2d Data2D：int16[256] 高度图（+ byte[256] biome），逐方块渲染优先
                    int[] hmap = extractData2d(entry.getValue());
                    if (hmap != null && !heightMaps.containsKey(key)) {
                        heightMaps.put(key, hmap);
                        parsedChunks++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                } else {
                    int height = isSubchunkKey(rawKey)
                            ? extractSubchunkHeight(entry.getValue(), chunkKey[3])
                            : extractChunkHeight(parseChunkNbt(entry.getValue()));
                    if (height != UNKNOWN_HEIGHT && !heightMaps.containsKey(key)) {
                        parsedChunks++;
                        // 同一 chunk 多个 subchunk 条目：取最高
                        Integer old = chunkHeights.get(key);
                        if (old == null || height > old) {
                            chunkHeights.put(key, height);
                        }
                        // 只有高度已知的 chunk 才参与范围计算，
                        // 否则大量未生成的 0x2d 空 chunk 会把地图撑成大片空白
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            } catch (Exception e) {
                // 单个 chunk 解析失败不影响整体
                failedChunks++;
                Log.w(TAG, "Failed to process chunk at " + x + "," + z, e);
            }
        }

        if (scannedChunks == 0) {
            Log.i(TAG, "渲染失败: 未扫描到主世界 chunk (chunk 数=0, 总条目="
                    + entries.size() + ")");
            return null;
        }
        if (parsedChunks == 0) {
            Log.i(TAG, "渲染失败: 高度提取全失败 (扫描 chunk=" + scannedChunks
                    + ", 失败/未知=" + (scannedChunks - parsedChunks) + ")");
            return null;
        }

        // chunk 级高度补齐：无 0x2d 的 chunk 用 chunk 高度填满 16×16
        for (Map.Entry<Long, Integer> e : chunkHeights.entrySet()) {
            if (heightMaps.containsKey(e.getKey())) {
                continue;
            }
            int[] hmap = new int[256];
            Arrays.fill(hmap, e.getValue());
            heightMaps.put(e.getKey(), hmap);
        }

        // 2) 画布尺寸：每 chunk 16×16 像素（BTR 同款），超出上限按桶降采样
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;

        int bucket = 1;
        while (spanX / bucket > maxChunksPerAxis || spanZ / bucket > maxChunksPerAxis
                || spanX * 16 / bucket > MAX_BITMAP_SIZE
                || spanZ * 16 / bucket > MAX_BITMAP_SIZE) {
            bucket++;
        }

        int cols = (spanX + bucket - 1) / bucket;
        int rows = (spanZ + bucket - 1) / bucket;
        int width = cols * 16;
        int height = rows * 16;

        // 3) 桶聚合：同桶内逐像素取最高高度（保持山地，桶通常 1~4）
        int[] heightAt = new int[width * height];
        Arrays.fill(heightAt, UNKNOWN_HEIGHT);
        for (Map.Entry<Long, int[]> e : heightMaps.entrySet()) {
            int x = unpackX(e.getKey());
            int z = unpackZ(e.getKey());
            int bx = (x - minX) / bucket;
            int bz = (z - minZ) / bucket;
            if (bx >= cols || bz >= rows) {
                continue;
            }
            int px0 = bx * 16;
            int pz0 = bz * 16;
            int[] hmap = e.getValue();
            for (int i = 0; i < 256; i++) {
                int h = hmap[i];
                if (h == UNKNOWN_HEIGHT) {
                    continue;
                }
                int idx = (pz0 + (i >> 4)) * width + px0 + (i & 15);
                if (h > heightAt[idx]) {
                    heightAt[idx] = h;
                }
            }
        }

        // 4) 逐像素绘制（照搬 BTR HeightmapRenderer：smoothstep 高度渐变 + 邻接高度阴影）
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, colorFor(UNKNOWN_HEIGHT));
        for (int pz = 0; pz < height; pz++) {
            int rowBase = pz * width;
            for (int px = 0; px < width; px++) {
                int idx = rowBase + px;
                int h = heightAt[idx];
                if (h == UNKNOWN_HEIGHT) {
                    continue; // 未知保持底色
                }
                // 邻接高度（BTR：西/北方向的方块高度用于坡度阴影）
                int hW = px > 0 ? heightAt[idx - 1] : h;
                int hN = pz > 0 ? heightAt[idx - width] : h;
                pixels[idx] = btrHeightColor(h, hW, hN);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        Log.i(TAG, "渲染成功: bitmap=" + width + "x" + height
                + " (bucket=" + bucket + ")"
                + ", 扫描 chunk=" + scannedChunks
                + ", 高度解析成功=" + parsedChunks
                + ", 失败=" + failedChunks
                + ", 坐标范围=(" + minX + "," + minZ + ")-(" + maxX + "," + maxZ + ")");
        return bitmap;
    }

    // ---------------------------------------------------------------- key 解析

    /**
     * 解析 chunk key，返回 {x, z, dimension, subIndex}；非 chunk key 返回 null。
     * 9/10 字节（旧格式，无维度段）：类型字节必须是 chunk 数据类
     * （0x2C 版本 / 0x2D Data2D / 0x2E 旧 Data2D / 0x2F subchunk / 0x30 旧地形），
     * 0x31+ 是方块实体/实体数据，不算 chunk；
     * 13/14 字节（1.18+）要求类型字节为 0x2F 或 0x30。
     */
    private static int[] parseChunkKey(byte[] rawKey) {
        if (rawKey == null) {
            return null;
        }
        int len = rawKey.length;
        if (len == 9 || len == 10) {
            int type = rawKey[8] & 0xFF;
            if (type < 0x2B || type > 0x30) {
                return null; // 实体/方块实体等非 chunk 数据
            }
            // 旧格式：无维度段，默认主世界；sub 索引为 signed（0xFC~0xFF = -4~-1）
            int sub = len == 10 ? rawKey[9] : -1;
            if (sub > 127) {
                sub -= 256;
            }
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), DIM_OVERWORLD, sub};
        }
        if (len == 13 || len == 14) {
            int type = rawKey[12] & 0xFF;
            // 0x2B~0x30 均为 chunk 数据（Data3D/Data2D/subchunk/ChunkVersion/ChunkData），
            // 0x31+ 是实体/方块实体等非 chunk key
            if (type >= KEY_TYPE_DATA_3D && type <= KEY_TYPE_CHUNK_DATA) {
                int sub = len == 14 ? rawKey[13] : -1;
                return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4),
                        mapDimFromKey(readIntLE(rawKey, 8)), sub};
            }
        }
        return null;
    }

    /** 高度图 key（Data3D/Data2D，前 512 字节均为 int16[256] 高度）：9B 主世界 / 13B 含维度（下界/末地）。 */
    private static boolean isData2dKey(byte[] rawKey) {
        if (rawKey == null) {
            return false;
        }
        if (rawKey.length == 9) {
            int type = rawKey[8] & 0xFF;
            return type == KEY_TYPE_DATA_2D || type == KEY_TYPE_DATA_3D;
        }
        if (rawKey.length == 13) {
            int type = rawKey[12] & 0xFF;
            return type == KEY_TYPE_DATA_2D || type == KEY_TYPE_DATA_3D;
        }
        return false;
    }

    /** subchunk key（类型 0x2F）：9/10B 主世界 / 14B 含维度与 sub 索引（下界/末地）。 */
    private static boolean isSubchunkKey(byte[] rawKey) {
        if (rawKey == null) {
            return false;
        }
        if (rawKey.length == 9 || rawKey.length == 10) {
            return (rawKey[8] & 0xFF) == KEY_TYPE_LEGACY_MIXED;
        }
        if (rawKey.length == 14) {
            return (rawKey[12] & 0xFF) == KEY_TYPE_LEGACY_MIXED;
        }
        return false;
    }

    /**
     * 1.18+ subchunk 方块存储高度：value = [版本 9][storage 数][sub 索引][storage...]。
     * storage 布局（Tomcc 官方 spec + BTR 同款）：[bits 头 1B][数据区][palette 数 int32 LE][palette NBT...]。
     * 数据区按 4 字节 word 打包：blocksPerWord = floor(32/bits)，wordCount = ceil(4096/bpw)，
     * 数据区 = wordCount × 4 字节（不是 512×bits——bits=3/5/6 时有 padding）。
     * 索引 0 = 空气（与 BTR 一致，不校验 palette 内容）。
     * 从最高 y 层往下找第一个非空层（字节级非零检查，不做逐值解包）。
     */
    private static int extractSubchunkHeight(byte[] value, int subIndex) {
        if (value == null || value.length < 5) {
            return UNKNOWN_HEIGHT;
        }
        int p = 3; // 跳过版本 + storage 数 + sub 索引
        int count = value[1] & 0xFF;
        if (count < 1 || count > 2) {
            return UNKNOWN_HEIGHT;
        }
        for (int s = 0; s < count && p + 2 <= value.length; s++) {
            int header = value[p++] & 0xFF;
            int bits = header >> 1;
            if (bits < 1 || bits > 16) {
                return UNKNOWN_HEIGHT;
            }
            int blocksPerWord = 32 / bits;
            int wordCount = (4096 + blocksPerWord - 1) / blocksPerWord;
            int dataBytes = wordCount * 4;
            if (p + dataBytes + 4 > value.length) {
                return UNKNOWN_HEIGHT;
            }
            int paletteStart = p + dataBytes;
            int paletteSize = (value[paletteStart] & 0xFF) | ((value[paletteStart + 1] & 0xFF) << 8)
                    | ((value[paletteStart + 2] & 0xFF) << 16) | ((value[paletteStart + 3] & 0xFF) << 24);
            if (paletteSize < 0 || paletteSize > 65536) {
                return UNKNOWN_HEIGHT;
            }
            int paletteEntriesStart = paletteStart + 4;
            // 16 个 y 层，每层 256 值 = 32×bits 位（ceil 到字节）；从最高层往下找非空
            int layerBytes = (32 * bits + 7) / 8;
            for (int y = 15; y >= 0; y--) {
                int layerStart = p + y * layerBytes;
                for (int i = 0; i < layerBytes && layerStart + i < paletteStart; i++) {
                    if (value[layerStart + i] != 0) {
                        return subIndex * 16 + y + 1;
                    }
                }
            }
            // 跳到下一个 storage：数据区 + palette 数（int32）+ palette NBT
            p = paletteEntriesStart;
            if (p + 4 > value.length) {
                return UNKNOWN_HEIGHT;
            }
            // 跳过 palette NBT（每个条目至少 3 字节：类型+名长 2B）
            for (int i = 0; i < paletteSize && p + 3 <= value.length; i++) {
                int type = value[p++] & 0xFF;
                if (type == 0) {
                    continue;
                }
                int nameLen = (value[p] & 0xFF) | ((value[p + 1] & 0xFF) << 8);
                p += 2 + nameLen;
                if (p > value.length) {
                    return UNKNOWN_HEIGHT;
                }
                // 条目 payload 粗略跳过：按类型定长，Compound 按 TAG_END 扫描
                p = skipNbtPayload(value, p, type);
                if (p < 0) {
                    return UNKNOWN_HEIGHT;
                }
            }
        }
        return UNKNOWN_HEIGHT;
    }

    /** 粗略跳过 NBT payload（网络 LE 格式，无名字），返回新偏移；失败返回 -1。 */
    private static int skipNbtPayload(byte[] value, int p, int type) {
        switch (type) {
            case NbtTag.TAG_BYTE: return p + 1;
            case NbtTag.TAG_SHORT: return p + 2;
            case NbtTag.TAG_INT: case NbtTag.TAG_FLOAT: return p + 4;
            case NbtTag.TAG_LONG: case NbtTag.TAG_DOUBLE: return p + 8;
            case NbtTag.TAG_STRING: {
                if (p + 2 > value.length) return -1;
                int len = (value[p] & 0xFF) | ((value[p + 1] & 0xFF) << 8);
                return p + 2 + len;
            }
            case NbtTag.TAG_COMPOUND: {
                int depth = 0;
                while (p < value.length) {
                    int t = value[p++] & 0xFF;
                    if (t == 0) {
                        depth--;
                        if (depth <= 0) return p;
                        continue;
                    }
                    if (t == NbtTag.TAG_COMPOUND || t == NbtTag.TAG_LIST) depth++;
                    int nameLen = (value[p] & 0xFF) | ((value[p + 1] & 0xFF) << 8);
                    p += 2 + nameLen;
                    if (p > value.length) return -1;
                    p = skipNbtPayload(value, p, t);
                    if (p < 0) return -1;
                }
                return -1;
            }
            case NbtTag.TAG_LIST: {
                if (p + 5 > value.length) return -1;
                int elemType = value[p] & 0xFF;
                int n = (value[p + 1] & 0xFF) | ((value[p + 2] & 0xFF) << 8)
                        | ((value[p + 3] & 0xFF) << 16) | ((value[p + 4] & 0xFF) << 24);
                p += 5;
                for (int i = 0; i < n && p < value.length; i++) {
                    p = skipNbtPayload(value, p, elemType);
                    if (p < 0) return -1;
                }
                return p;
            }
            case NbtTag.TAG_INT_ARRAY: {
                if (p + 4 > value.length) return -1;
                int n = (value[p] & 0xFF) | ((value[p + 1] & 0xFF) << 8)
                        | ((value[p + 2] & 0xFF) << 16) | ((value[p + 3] & 0xFF) << 24);
                return p + 4 + n * 4;
            }
            default:
                return -1;
        }
    }

    /**
     * Data2D 高度提取：value = int16[256] 高度图（小端）+ byte[256] biome。
     * 与 BTR 一致：高度全 0 的 chunk 视为海平面以下（渲染为深水蓝），
     * 而不是未知——这样已生成的大片海洋区域能正常显示，不会变成空白。
     * 返回 16×16 逐方块高度数组（索引 = x + (z&lt;&lt;4)），失败返回 null。
     */
    private static int[] extractData2d(byte[] value) {
        if (value == null || value.length < 512) {
            return null;
        }
        int[] hmap = new int[256];
        for (int i = 0; i < 256; i++) {
            int h = (value[i * 2] & 0xFF) | ((value[i * 2 + 1] & 0xFF) << 8);
            // 高度图是 2 倍精度（半块）：值 = 实际 y × 2。
            // 实测 tju 海洋 127(=y63.5)、雪原 128(=y64)、山地 201(=y100.5) 全部吻合。
            // 之前不除 2 导致从 y126 向下找，跳过海面直接命中海底海草/沙滩。
            if (h > 640) h = h & 0xFF; // 大端脏数据（>640 = 实际高度 320 上限×2）取低字节
            hmap[i] = h / 2;
        }
        return hmap; // 全 0 = 海平面以下（水色），与 BTR 行为一致
    }

    /**
     * ChunkVersion/ChunkData NBT 的逐方块高度图（下界/末地 1.18+ chunk 无 Data2D key 时使用）：
     * HeightMap compound 中 WORLD_SURFACE 等 int array（256 项，每项一列高度）。
     */
    private static int[] extractChunkHeightMap256(NbtTag root) {
        if (root == null || root.getType() != NbtTag.TAG_COMPOUND) {
            return null;
        }
        NbtTag heightMap = root.getTag("HeightMap");
        if (heightMap == null) {
            heightMap = root.getTag("height_map");
        }
        if (heightMap == null || heightMap.getType() != NbtTag.TAG_COMPOUND) {
            return null;
        }
        for (String key : HEIGHT_MAP_KEYS) {
            int[] arr = heightMap.getTag(key) != null
                    && heightMap.getTag(key).getType() == NbtTag.TAG_INT_ARRAY
                    ? heightMap.getTag(key).getIntArray() : null;
            if (arr != null && arr.length > 0) {
                return expandTo256(arr);
            }
        }
        for (NbtTag tag : heightMap.getCompound().values()) {
            if (tag != null && tag.getType() == NbtTag.TAG_INT_ARRAY && tag.getIntArray().length > 0) {
                return expandTo256(tag.getIntArray());
            }
        }
        return null;
    }

    /** int 数组扩展到 256 项（16×16）并除 2（HeightMap 2 倍精度）；不足 256 用均值补齐，超出截断。 */
    private static int[] expandTo256(int[] arr) {
        int[] out = new int[256];
        int n = Math.min(arr.length, 256);
        long sum = 0;
        for (int i = 0; i < n; i++) {
            out[i] = arr[i] / 2;
            sum += arr[i] / 2;
        }
        if (n < 256) {
            int avg = (int) (sum / Math.max(1, n));
            for (int i = n; i < 256; i++) {
                out[i] = avg;
            }
        }
        return out;
    }

    private static int readIntLE(byte[] data, int pos) {
        return (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    // ---------------------------------------------------------------- NBT 解析

    /**
     * 解析 chunk value 为 NBT。不同格式的 chunk value 有 0~2 字节前缀
     * （版本字节 / subchunk 索引），逐偏移尝试，取第一个能提取出高度的结果；
     * 全部失败则返回第一个可解析的 COMPOUND（高度按未知处理）或 null。
     */
    private static NbtTag parseChunkNbt(byte[] value) {
        if (value == null || value.length < 2) {
            return null;
        }
        NbtTag fallback = null;
        // 先尝试直接解析（0~2 字节前缀）
        for (int offset = 0; offset <= 2 && offset + 2 < value.length; offset++) {
            NbtTag root = tryParseHeightCompound(value, offset);
            if (root != null) {
                return root;
            }
        }
        // 1.18+ 的 ChunkData 是 zlib 压缩的 NBT（前缀 1~2 字节后接 deflate 流）：
        // 逐偏移解压后再解析（设备实测 db 里存在 0x78 0x9C/0xDA 压缩头）。
        for (int offset = 0; offset <= 2 && offset + 2 < value.length; offset++) {
            byte[] inflated = inflateZlib(Arrays.copyOfRange(value, offset, value.length));
            if (inflated == null) {
                continue;
            }
            try {
                NbtTag root = new BedrockNbtReader().readFromBytes(inflated);
                if (root != null && root.getType() == NbtTag.TAG_COMPOUND) {
                    if (extractChunkHeight(root) != UNKNOWN_HEIGHT) {
                        return root;
                    }
                    if (fallback == null) {
                        fallback = root;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    /** 按偏移直接解析并返回带高度的 COMPOUND，失败返回 null。 */
    private static NbtTag tryParseHeightCompound(byte[] value, int offset) {
        try {
            NbtTag root = new BedrockNbtReader()
                    .readFromBytes(Arrays.copyOfRange(value, offset, value.length));
            if (root != null && root.getType() == NbtTag.TAG_COMPOUND
                    && extractChunkHeight(root) != UNKNOWN_HEIGHT) {
                return root;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** zlib 解压（deflate 流），失败返回 null。 */
    private static byte[] inflateZlib(byte[] compressed) {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(compressed);
            byte[] buffer = new byte[Math.max(4096, compressed.length * 4)];
            int written = inflater.inflate(buffer);
            if (written > 0) {
                return Arrays.copyOfRange(buffer, 0, written);
            }
            // 一次 inflate 可能不够（大块数据），累积输出
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (!inflater.finished() && written > 0) {
                out.write(buffer, 0, written);
                written = inflater.inflate(buffer);
            }
            if (out.size() > 0) {
                return out.toByteArray();
            }
        } catch (Exception ignored) {
        } finally {
            inflater.end();
        }
        return null;
    }

    /**
     * 提取 chunk 高度：
     * 1) 优先 "HeightMap"（或 "height_map"）COMPOUND 中的 int 数组
     *    （WORLD_SURFACE 等 2D 高度图），取数组均值作为该 chunk 的代表高度；
     * 2) 失败则回退到 "sections" 列表：取最高非空 section 的顶部 Y
     *    （section Y 字节 × 16 + 16），非空判定依赖 block_palette /
     *    block_states.palette / blocks 任一非空；
     * 3) 仍失败返回 UNKNOWN_HEIGHT（渲染为灰色）。
     */
    private static int extractChunkHeight(NbtTag root) {
        if (root == null || root.getType() != NbtTag.TAG_COMPOUND) {
            return UNKNOWN_HEIGHT;
        }
        int height = heightFromHeightMap(root);
        if (height != UNKNOWN_HEIGHT) {
            return height;
        }
        return heightFromSections(root);
    }

    private static int heightFromHeightMap(NbtTag root) {
        NbtTag heightMap = root.getTag("HeightMap");
        if (heightMap == null) {
            heightMap = root.getTag("height_map");
        }
        if (heightMap == null || heightMap.getType() != NbtTag.TAG_COMPOUND) {
            return UNKNOWN_HEIGHT;
        }
        for (String key : HEIGHT_MAP_KEYS) {
            int height = meanOfIntArray(heightMap.getTag(key));
            if (height != UNKNOWN_HEIGHT) {
                return height;
            }
        }
        for (NbtTag tag : heightMap.getCompound().values()) {
            int height = meanOfIntArray(tag);
            if (height != UNKNOWN_HEIGHT) {
                return height;
            }
        }
        return UNKNOWN_HEIGHT;
    }

    private static int meanOfIntArray(NbtTag tag) {
        if (tag == null || tag.getType() != NbtTag.TAG_INT_ARRAY) {
            return UNKNOWN_HEIGHT;
        }
        int[] array = tag.getIntArray();
        if (array.length == 0) {
            return UNKNOWN_HEIGHT;
        }
        int count = Math.min(array.length, 4096);
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += array[i];
        }
        return (int) (sum / count);
    }

    private static int heightFromSections(NbtTag root) {
        NbtTag sections = root.getTag("sections");
        if (sections == null || sections.getType() != NbtTag.TAG_LIST) {
            return UNKNOWN_HEIGHT;
        }
        int best = UNKNOWN_HEIGHT;
        for (NbtTag section : sections.getList()) {
            if (section.getType() != NbtTag.TAG_COMPOUND || !sectionHasBlocks(section)) {
                continue;
            }
            NbtTag yTag = section.getTag("Y");
            int y = (yTag != null && yTag.getType() == NbtTag.TAG_BYTE) ? yTag.getByte() : 0;
            int top = y * 16 + 16;
            if (top > best) {
                best = top;
            }
        }
        return best;
    }

    private static boolean sectionHasBlocks(NbtTag section) {
        NbtTag palette = section.getTag("block_palette");
        if (palette != null && palette.getType() == NbtTag.TAG_LIST && !palette.getList().isEmpty()) {
            return true;
        }
        NbtTag states = section.getTag("block_states");
        if (states != null && states.getType() == NbtTag.TAG_COMPOUND) {
            NbtTag inner = states.getTag("palette");
            if (inner != null && inner.getType() == NbtTag.TAG_LIST && !inner.getList().isEmpty()) {
                return true;
            }
        }
        NbtTag blocks = section.getTag("blocks");
        return blocks != null && blocks.getType() == NbtTag.TAG_LIST && !blocks.getList().isEmpty();
    }

    // ---------------------------------------------------------------- 颜色映射（BTR HeightmapRenderer 照搬）

    /**
     * BTR HeightmapRenderer 配色：smoothstep 高度渐变（低=蓝、高=红白）+ 坡度阴影。
     * 公式照搬 BTR 源码（HeightmapRenderer.renderToBitmap / SatelliteRenderer.getHeightShading）。
     */
    private static int btrHeightColor(int height, int heightW, int heightN) {
        float yNorm = height / 256f;
        float yNorm2 = yNorm * yNorm;
        // smooth step: 6x^5 - 15x^4 + 10x^3
        yNorm = ((6f * yNorm2) - (15f * yNorm) + 10f) * yNorm2 * yNorm;

        float shading = btrHeightShading(height, heightW, heightN);

        int r = (int) (yNorm * shading * 256f);
        int g = (int) (70f * shading);
        int b = (int) (256f * (1f - yNorm) / (yNorm + 1f));

        r = r < 0 ? 0 : Math.min(r, 255);
        g = g < 0 ? 0 : Math.min(g, 255);
        b = b < 0 ? 0 : Math.min(b, 255);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /** BTR SatelliteRenderer.getHeightShading：坡度阴影（atan 压缩高度差）。 */
    private static float btrHeightShading(int height, int heightW, int heightN) {
        float shadingAmp = 0.8f;
        int samples = 0;
        float heightDiff = 0;
        if (heightW > 0) {
            heightDiff += height - heightW;
            samples++;
        }
        if (heightN > 0) {
            heightDiff += height - heightN;
            samples++;
        }
        heightDiff *= Math.pow(1.05f, samples);
        return (float) ((Math.atan(heightDiff) / Math.PI) * shadingAmp) + 1f;
    }

    /** 未知高度底色。 */
    private static int colorFor(int height) {
        return getColor(R.color.world_map_unknown);
    }

    private static int getColor(int resId) {
        if (appContext != null) {
            try {
                return appContext.getColor(resId);
            } catch (Exception ignored) {
            }
        }
        return 0xFF9E9E9E; // 中灰兜底
    }

    // ---------------------------------------------------------------- BTR 卫星模式

    /** 海平面（BTR 渲染基准） */
    private static final int SEA_LEVEL = 63;

    /** 水色（BTR 老版 water 0x802e43f4 半透明混黑底 50% 的观感） */
    private static final int COLOR_WATER = 0xFF17217A;
    /** 背景（未生成区域，BTR CHESS 深色） */
    /* 未生成区域：透明（露出启动器卡片/个性化背景，含背景图片） */
    private static final int COLOR_BACKGROUND = 0x00000000;

    /**
     * bedrockmap 默认色调（bedrock-level color.cpp default_water/leave/grass_color）：
     * biome_color.json 查不到色调时的兜底。色表里的草地/树叶/水都是灰度模板
     * （grass_top 147/147/147、short_grass 119/119/119、water_still_grey 163/163/163），
     * 必须乘群系色调才是真实颜色（MC 着色器机制）。
     */
    private static final int[] DEFAULT_WATER_TINT = {63, 118, 228};
    private static final int[] DEFAULT_LEAVES_TINT = {113, 167, 77};
    private static final int[] DEFAULT_GRASS_TINT = {142, 185, 113};

    /** 整世界的逐方块表面颜色图（含高度阴影）。 */
    public static class WorldMap {
        public final int width;
        public final int height;
        /** 世界左上角的 block 坐标 */
        public final int minBlockX;
        public final int minBlockZ;
        /** 每 block 的 ARGB 表面色（已含坡度阴影） */
        public final int[] colors;
        /** 每 block 的生物群系色（生物群系图层用；无数据处透明） */
        public final int[] biomeColors;
        /** 玩家位置（block 坐标，-1 = 无玩家数据） */
        public int playerBlockX = -1;
        public int playerBlockZ = -1;
        /** 出生点位置（block 坐标，-1 = 无） */
        public int spawnBlockX = -1;
        public int spawnBlockZ = -1;

        WorldMap(int minBlockX, int minBlockZ, int width, int height, int[] colors, int[] biomeColors) {
            this.minBlockX = minBlockX;
            this.minBlockZ = minBlockZ;
            this.width = width;
            this.height = height;
            this.colors = colors;
            this.biomeColors = biomeColors;
        }

        /** 初始视图中心点（地图内 block 坐标）：玩家 > 出生点 > 地图中心。 */
        public float centerBlockX() {
            if (playerBlockX >= 0) return playerBlockX - minBlockX + 0.5f;
            if (spawnBlockX >= 0) return spawnBlockX - minBlockX + 0.5f;
            return width / 2f;
        }

        public float centerBlockZ() {
            if (playerBlockZ >= 0) return playerBlockZ - minBlockZ + 0.5f;
            if (spawnBlockZ >= 0) return spawnBlockZ - minBlockZ + 0.5f;
            return height / 2f;
        }

        /**
         * 从给定世界 block 坐标找最近的有数据（非透明）位置：
         * 目标点本身有数据直接返回；否则按切比雪夫距离螺旋向外搜索。
         */
        public int[] nearestGeneratedBlock(int worldX, int worldZ) {
            int cx = worldX - minBlockX;
            int cz = worldZ - minBlockZ;
            if (cx >= 0 && cx < width && cz >= 0 && cz < height
                    && (colors[cz * width + cx] & 0xFF000000) != 0) {
                return new int[]{worldX, worldZ};
            }
            int maxR = Math.max(width, height);
            for (int r = 1; r < maxR; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int nx = cx + dx;
                        int nz = cz + dz;
                        if (nx >= 0 && nx < width && nz >= 0 && nz < height
                                && (colors[nz * width + nx] & 0xFF000000) != 0) {
                            return new int[]{minBlockX + nx, minBlockZ + nz};
                        }
                    }
                }
            }
            return new int[]{minBlockX + width / 2, minBlockZ + height / 2};
        }
    }

    /** 单个 subchunk 的方块存储（word 位打包 + palette 名字）。 */
    private static class SubChunk {
        final int bits;
        final int blocksPerWord;
        final byte[] data;      // word 数据区
        final String[] palette; // 索引 → 方块名
        /** 第二个 storage（水层）——bedrock-level sub_chunk 多 layer 同款 */
        SubChunk waterLayer;

        SubChunk(int bits, byte[] data, String[] palette) {
            this.bits = bits;
            this.blocksPerWord = 32 / bits;
            this.data = data;
            this.palette = palette;
        }

        /** 读 (x,z,y) 的 palette 索引（BTR 新版 getBlockId 同款位序）。 */
        int getIndex(int x, int y, int z) {
            int blockPos = ((x * 16) + z) * 16 + y;
            int wordStart = blockPos / blocksPerWord;
            int bitOffset = (blockPos % blocksPerWord) * bits;
            int bitStart = wordStart * 32 + bitOffset;
            int byteStart = bitStart / 8;
            if (byteStart >= data.length) {
                return 0;
            }
            int value = (data[byteStart] & 0xFF)
                    | ((byteStart + 1 < data.length ? data[byteStart + 1] & 0xFF : 0) << 8);
            value >>>= (bitStart % 8);
            int mask = (1 << bits) - 1;
            return value & mask;
        }
    }

    /**
     * BTR 卫星模式：构建整世界的逐方块表面颜色图。
     * 颜色来源：subchunk palette 方块名 → BTR 颜色表（草地类乘 biome 色），
     * 逐列从高度向下找第一个非空气方块；再叠加 BTR 坡度阴影。
     * 必须在线程中调用（全量 subchunk 解码较耗时）。
     */
    public static WorldMap buildSatelliteMap(List<LevelDBEntry> entries) {
        return buildSatelliteMap(entries, DIM_OVERWORLD);
    }

    /** 指定维度构建（0=主世界 1=下界 2=末地；老格式 9/10 字节 key 仅存在于主世界）。 */
    public static WorldMap buildSatelliteMap(List<LevelDBEntry> entries, int dimension) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        // 1) 第一遍：高度图 + biome + 每 chunk 表面 sub 索引
        //    （subchunk 不在第一遍解码——大量地下 subchunk 全解码会导致内存爆炸 OOM）
        Map<Long, int[]> heightMaps = new HashMap<>();
        Map<Long, byte[]> biomeMaps = new HashMap<>(); // chunk → 256 biome id（原样）
        Map<Long, Integer> surfaceSubs = new HashMap<>(); // chunk → 表面 sub 索引
        Map<Long, Map<Integer, SubChunk>> subChunks = new HashMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            int[] chunkKey = parseChunkKey(rawKey);
            if (chunkKey == null) {
                continue;
            }
            // v264 行为：严格按 key 内维度段过滤（末地数据都在 13/14B dim=2 key，
            // 不需要 9/10B 候选 + end_stone 判定兜底）
            if (chunkKey[2] != dimension) {
                continue;
            }
            int x = chunkKey[0];
            int z = chunkKey[1];
            long key = pack(x, z);
            try {
                if (isData2dKey(rawKey)) {
                    int[] hmap = extractData2d(entry.getValue());
                    if (hmap != null && !heightMaps.containsKey(key)) {
                        heightMaps.put(key, hmap);
                        int maxH = 0;
                        for (int h : hmap) {
                            if (h > maxH) {
                                maxH = h;
                            }
                        }
                        surfaceSubs.put(key, Math.floorDiv(maxH - 1, 16));
                        int type = (rawKey.length == 13 ? rawKey[12] : rawKey[8]) & 0xFF;
                        if (type == KEY_TYPE_DATA_2D) {
                            byte[] biomes = extractBiomes2d(entry.getValue());
                            if (biomes != null) {
                                biomeMaps.put(key, biomes);
                            }
                        } else {
                            byte[] biomes = extractBiomes3d(entry.getValue(), hmap);
                            if (biomes != null) {
                                biomeMaps.put(key, biomes);
                            }
                        }
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                } else if (rawKey.length == 13 && chunkKey[3] < 0) {
                    // 13B 0x2F/0x30（1.18+ 下界/末地）：ChunkVersion/ChunkData NBT 含 HeightMap 逐方块高度图
                    int[] hmap = extractChunkHeightMap256(parseChunkNbt(entry.getValue()));
                    if (hmap != null && !heightMaps.containsKey(key)) {
                        heightMaps.put(key, hmap);
                        int maxH = 0;
                        for (int h : hmap) {
                            if (h > maxH) {
                                maxH = h;
                            }
                        }
                        surfaceSubs.put(key, Math.floorDiv(maxH - 1, 16));
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to decode chunk at " + x + "," + z, e);
            }
        }
        if (heightMaps.isEmpty()) {
            Log.i(TAG, "卫星模式失败: 无高度图 chunk (维度=" + dimension + ")");
            return null;
        }

        // 2) 第二遍：只解码表面附近 4 层的 subchunk。
        //    表面层 = max(高度图表面, 实际最高 sub 索引)——高度图是生成器预测值
        //    （实测 tju 主世界高度图 127~201 但 subchunk 只生成到索引 3~4，纯按高度图全错位）。
        //    实际最高 sub 索引只读 key 字节，不 decode value，零成本。
        //    下界高度图不可靠（实测全 128=天花板语义）→ 下界全量解码（数量少，296 个而已）
        Map<Long, Integer> maxSubByChunk = new HashMap<>();
        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            int[] chunkKey = parseChunkKey(rawKey);
            if (chunkKey == null || !isSubchunkKey(rawKey)) {
                continue;
            }
            if (chunkKey[2] != dimension) {
                continue;
            }
            long key = pack(chunkKey[0], chunkKey[1]);
            maxSubByChunk.merge(key, chunkKey[3], Math::max);
        }
        int subKeys = 0;
        int skipped = 0;
        int decoded = 0;
        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            int[] chunkKey = parseChunkKey(rawKey);
            if (chunkKey == null || !isSubchunkKey(rawKey)) {
                continue;
            }
            if (chunkKey[2] != dimension) {
                continue;
            }
            subKeys++;
            long key = pack(chunkKey[0], chunkKey[1]);
            int sub = chunkKey[3];
            if (dimension != DIM_NETHER) {
                // 窗口围绕「实际最高 subchunk」（实测：高度图是生成器预测值 127~201，
                // 实际方块只生成到 sub 3~4，围绕预测值会全跳过）
                Integer maxSub = maxSubByChunk.get(key);
                if (maxSub == null || sub < maxSub - 2 || sub > maxSub + 2) {
                    skipped++;
                    continue; // 无 subchunk 数据或地下/高空层：卫星图不需要
                }
            }
            try {
                SubChunk subChunk = decodeSubChunk(entry.getValue());
                if (subChunk != null) {
                    subChunks.computeIfAbsent(key, k -> new HashMap<>())
                            .put(sub, subChunk);
                    decoded++;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to decode subchunk at " + chunkKey[0] + "," + chunkKey[1], e);
            }
        }
        Log.i(TAG, "第二遍 subchunk: 命中=" + subKeys + " 跳过=" + skipped
                + " 解码=" + decoded + " surfaceSubs=" + surfaceSubs.size());

        // 2) 组装全图：每 chunk 16×16 表面色
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        int width = spanX * 16;
        int height = spanZ * 16;
        int minBlockX = minX * 16;
        int minBlockZ = minZ * 16;
        int[] heights = new int[width * height];
        int[] colors = new int[width * height];
        int[] biomeColors = new int[width * height];
        Arrays.fill(colors, COLOR_BACKGROUND);

        for (Map.Entry<Long, int[]> e : heightMaps.entrySet()) {
            int cx = unpackX(e.getKey());
            int cz = unpackZ(e.getKey());
            int px0 = (cx - minX) * 16;
            int pz0 = (cz - minZ) * 16;
            int[] hmap = e.getValue();
            byte[] biomes = biomeMaps.get(e.getKey());
            Map<Integer, SubChunk> subs = subChunks.get(e.getKey());
            for (int i = 0; i < 256; i++) {
                int lx = i & 15;
                int lz = i >> 4;
                int idx = (pz0 + lz) * width + px0 + lx;
                int h = hmap[i];
                heights[idx] = h;
                colors[idx] = surfaceColor(h, lx, lz, subs, biomes, dimension);
                if (biomes != null) {
                    biomeColors[idx] = biomeGrassColor(biomes[i] & 0xFF);
                }
            }
        }

        // 3) BTR 坡度阴影（西/北邻居高度差）
        for (int pz = 0; pz < height; pz++) {
            int rowBase = pz * width;
            for (int px = 0; px < width; px++) {
                int idx = rowBase + px;
                int h = heights[idx];
                int c = colors[idx];
                if ((c & 0xFF000000) == 0) {
                    continue;
                }
                int hW = px > 0 ? heights[idx - 1] : h;
                int hN = pz > 0 ? heights[idx - width] : h;
                float shading = btrHeightShading(h, hW, hN);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * shading));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * shading));
                int b = Math.min(255, (int) ((c & 0xFF) * shading));
                colors[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        int biomeNonZero = 0;
        for (int c : biomeColors) {
            if (c != 0) {
                biomeNonZero++;
            }
        }
        int subCount = 0;
        for (Map<Integer, SubChunk> m : subChunks.values()) {
            subCount += m.size();
        }
        Log.i(TAG, "卫星模式完成: " + width + "x" + height
                + " blocks, chunk 范围=(" + minX + "," + minZ + ")-(" + maxX + "," + maxZ + ")"
                + ", biome 数据=" + biomeMaps.size() + " chunk / " + biomeNonZero + " 非透明像素"
                + ", 解码 subchunk=" + subCount + " (含表面层=" + subChunks.size() + " chunk)");
        return new WorldMap(minBlockX, minBlockZ, width, height, colors, biomeColors);
    }

    // ---------------------------------------------------------------- 实体 / 结构标记

    /** 实体标记（实体图层：位置 + 简化标识符）。 */
    public static class EntityPos {
        public final float x;
        public final float y;
        public final float z;
        public final String name; // 简化标识符（如 zombie / villager）

        EntityPos(float x, float y, float z, String name) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
        }
    }

    /** 结构标记（结构图层：从方块实体检测出的结构位置）。 */
    public static class StructureMarker {
        public final int x;
        public final int z;
        public final String type; // village / spawner / end_portal

        StructureMarker(int x, int z, String type) {
            this.x = x;
            this.z = z;
            this.type = type;
        }
    }

    /**
     * 解析指定维度的实体位置（实体图层）。
     * Bedrock 1.26 实体存储 key = "actorprefix"(11B) + int64 大端实体唯一 ID（实测 19B key），
     * value 为单个实体 NBT（可能 zlib 压缩），含 Pos(float×3)、identifier。
     * 维度归属：19B actor key 与实体 NBT 均无维度字段（实测），
     * 用 digp 摘要反推——digp key = "digp" + chunkX + chunkZ [+dim]，value 为该 chunk 实体 uid 列表。
     */
    public static List<EntityPos> parseEntities(List<LevelDBEntry> entries, int dimension) {
        List<EntityPos> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        // 1) 收集各维度 chunk 的 actor uid 集合（digp：12B=主世界 / 16B 含维度）
        Map<Integer, Set<String>> dimUids = new HashMap<>();
        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            if (rawKey == null || (rawKey.length != 12 && rawKey.length != 16)
                    || !startsWithAscii(rawKey, "digp")) {
                continue;
            }
            int keyDim = rawKey.length == 16 ? mapDimFromKey(readIntLE(rawKey, 12)) : DIM_OVERWORLD;
            byte[] digest = entry.getValue();
            if (digest == null || digest.length < 8) {
                continue;
            }
            Set<String> uids = dimUids.computeIfAbsent(keyDim, k -> new HashSet<>());
            for (int i = 0; i + 8 <= digest.length; i += 8) {
                uids.add(toHex(java.util.Arrays.copyOfRange(digest, i, i + 8)));
            }
        }
        Log.i(TAG, "digp 维度 uid 集合: " + dimUids.keySet());
        // 2) 实体按 uid 归属维度过滤
        int actorKeys = 0;
        int nbtFailed = 0;
        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            if (rawKey == null || rawKey.length != 19 || !startsWithAscii(rawKey, "actorprefix")) {
                continue;
            }
            actorKeys++;
            // 维度归属：digp 反推 > value DimensionId > 无信息时仅主世界
            String uid = toHex(java.util.Arrays.copyOfRange(rawKey, 11, 19));
            Integer entityDim = null;
            for (Map.Entry<Integer, Set<String>> e : dimUids.entrySet()) {
                if (e.getValue().contains(uid)) {
                    entityDim = e.getKey();
                    break;
                }
            }
            NbtTag root = parseCompoundWithFallback(entry.getValue());
            if (root == null || root.getType() != NbtTag.TAG_COMPOUND) {
                nbtFailed++;
                continue;
            }
            if (entityDim == null) {
                NbtTag dimTag = root.getTag("DimensionId");
                entityDim = dimTag != null ? mapDimFromKey(dimTag.getInt()) : DIM_OVERWORLD;
            }
            if (entityDim != dimension) {
                continue;
            }
            NbtTag pos = root.getTag("Pos");
            if (pos == null || pos.getType() != NbtTag.TAG_LIST || pos.getList().size() < 3) {
                nbtFailed++;
                continue;
            }
            float x = pos.getList().get(0).getFloat();
            float y = pos.getList().get(1).getFloat();
            float z = pos.getList().get(2).getFloat();
            String id = root.getTag("identifier") != null ? root.getTag("identifier").getString() : "";
            if (id.isEmpty()) {
                nbtFailed++;
                continue;
            }
            out.add(new EntityPos(x, y, z, simplifyEntityName(id)));
        }
        Log.i(TAG, "实体解析完成: 维度=" + dimension + ", 实体数=" + out.size()
                + ", actorprefix key 数=" + actorKeys + ", NBT 失败=" + nbtFailed);
        return out;
    }

    /**
     * 解析指定维度的结构标记（结构图层）。
     * 数据源（bedrock-level 源码确认）：
     * ① HardCodedSpawnAreas(0x39)：官方结构生成区域记录
     *    （下界要塞/女巫小屋/海底神殿/掠夺者前哨站）；
     * ② BlockEntity(0x31)：Bell → 村庄、MobSpawner → 刷怪笼、EndPortal → 末地传送门；
     * ③ ChunkData(0x30) NBT 的 "block_entities" list（1.18+ 新格式）。
     */
    public static List<StructureMarker> parseStructureMarkers(List<LevelDBEntry> entries, int dimension) {
        List<StructureMarker> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        int beKeys = 0;
        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            // 0x39 HardCodedSpawnAreas：结构生成区域（官方记录）
            int[] hsaKey = parseTaggedKey(rawKey, KEY_TYPE_HSA);
            if (hsaKey != null && hsaKey[2] == dimension) {
                parseHsa(entry.getValue(), out);
                continue;
            }
            // 1.26：方块实体在 ChunkData NBT 内
            if (rawKey != null && (rawKey.length == 13 || rawKey.length == 14)
                    && (rawKey[12] & 0xFF) == KEY_TYPE_CHUNK_DATA
                    && readIntLE(rawKey, 8) == dimension) {
                NbtTag chunkNbt = parseChunkNbt(entry.getValue());
                if (chunkNbt != null) {
                    NbtTag bes = chunkNbt.getTag("block_entities");
                    if (bes == null) {
                        bes = chunkNbt.getTag("blockentities");
                    }
                    if (bes != null && bes.getType() == NbtTag.TAG_LIST) {
                        beKeys += collectStructureMarkers(bes, out);
                    }
                }
                continue;
            }
            // 0x31 BlockEntity 独立 key（值 = 单个方块实体 NBT compound：id/x/y/z）
            int[] key = parseBlockEntityKey(rawKey);
            if (key == null || key[2] != dimension) {
                continue;
            }
            NbtTag root = parseCompoundWithFallback(entry.getValue());
            if (root == null) {
                continue;
            }
            beKeys++;
            if (root.getType() == NbtTag.TAG_COMPOUND) {
                String id = root.getTag("id") != null ? root.getTag("id").getString() : "";
                String type = structureTypeForBlockEntity(id);
                if (type != null) {
                    NbtTag xTag = root.getTag("x");
                    NbtTag zTag = root.getTag("z");
                    if (xTag != null && zTag != null) {
                        out.add(new StructureMarker(xTag.getInt(), zTag.getInt(), type));
                    }
                }
            }
        }
        Map<String, Integer> typeDist = new HashMap<>();
        for (StructureMarker m : out) {
            typeDist.merge(m.type, 1, Integer::sum);
        }
        Log.i(TAG, "结构标记解析完成: 维度=" + dimension + ", 结构数=" + out.size()
                + ", 方块实体数=" + beKeys + ", 类型分布=" + typeDist);
        return out;
    }

    /** 指定 tag 的 chunk key（9/10B 主世界或 13/14B 含维度）。 */
    private static int[] parseTaggedKey(byte[] rawKey, int tag) {
        if (rawKey == null) {
            return null;
        }
        int len = rawKey.length;
        if ((len == 9 || len == 10) && (rawKey[8] & 0xFF) == tag) {
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), DIM_OVERWORLD};
        }
        if ((len == 13 || len == 14) && (rawKey[12] & 0xFF) == tag) {
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), mapDimFromKey(readIntLE(rawKey, 8))};
        }
        return null;
    }

    /**
     * HardCodedSpawnAreas 解析（bedrock-level hardcoded_spawn_area_list 同款布局）：
     * int32 count + count × (min x/y/z int32 + max x/y/z int32 + 1 type byte)。
     * type：NetherFortress=1、SwampHut=2、OceanMonument=3、PillagerOutpost=5。
     */
    private static void parseHsa(byte[] value, List<StructureMarker> out) {
        if (value == null || value.length < 4) {
            return;
        }
        int count = readIntLE(value, 0);
        if (value.length != count * 25 + 4) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int p = 4 + i * 25;
            int type = value[p + 24];
            String markerType;
            switch (type) {
                case 1: markerType = "fortress"; break;      // 下界要塞
                case 2: markerType = "swamp_hut"; break;     // 女巫小屋
                case 3: markerType = "ocean_monument"; break; // 海底神殿
                case 5: markerType = "outpost"; break;       // 掠夺者前哨站
                default: continue;
            }
            int minX = readIntLE(value, p);
            int maxX = readIntLE(value, p + 12);
            int minZ = readIntLE(value, p + 8);
            int maxZ = readIntLE(value, p + 20);
            out.add(new StructureMarker((minX + maxX) / 2, (minZ + maxZ) / 2, markerType));
        }
    }

    /** 从方块实体 list 收集结构标记，返回条目数。 */
    private static int collectStructureMarkers(NbtTag list, List<StructureMarker> out) {
        int n = 0;
        for (NbtTag be : list.getList()) {
            if (be.getType() != NbtTag.TAG_COMPOUND) {
                continue;
            }
            n++;
            String id = be.getTag("id") != null ? be.getTag("id").getString() : "";
            String type = structureTypeForBlockEntity(id);
            if (type == null) {
                continue;
            }
            NbtTag xTag = be.getTag("x");
            NbtTag zTag = be.getTag("z");
            if (xTag == null || zTag == null) {
                continue;
            }
            out.add(new StructureMarker(xTag.getInt(), zTag.getInt(), type));
        }
        return n;
    }

    /** 方块实体 id → 结构类型；不关心的返回 null。 */
    private static String structureTypeForBlockEntity(String id) {
        switch (id) {
            case "Bell": return "village";
            case "MobSpawner": return "spawner";
            case "TrialSpawner": return "trial_spawner"; // 试炼密室刷怪笼（1.21+）
            case "EndPortal": return "end_portal";
            default: return null;
        }
    }

    /** MT19937（照搬 C++ std::mt19937，bedrock-level is_slime 同款随机源）。 */
    private static class Mt19937 {
        private final int[] mt = new int[624];
        private int index = 624;

        Mt19937(int seed) {
            mt[0] = seed;
            for (int i = 1; i < 624; i++) {
                mt[i] = 0x6c078965 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            }
        }

        int next() {
            if (index >= 624) {
                for (int i = 0; i < 624; i++) {
                    int y = (mt[i] & 0x80000000) | (mt[(i + 1) % 624] & 0x7fffffff);
                    mt[i] = mt[(i + 397) % 624] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                index = 0;
            }
            int y = mt[index++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
        }
    }

    /**
     * 史莱姆区块判定（bedrock-level chunk_pos::is_slime 同款）：
     * seed = (x × 0x1f1f1f1f)u32 ^ z，mt19937(seed) 首个输出 % 10 == 0。
     */
    public static boolean isSlimeChunk(int chunkX, int chunkZ) {
        long seed = (((long) chunkX * 0x1f1f1f1fL) & 0xFFFFFFFFL) ^ (chunkZ & 0xFFFFFFFFL);
        Mt19937 mt = new Mt19937((int) seed);
        return Math.floorMod(mt.next(), 10) == 0;
    }

    /** 实体标识符简化：minecraft:zombie<...> → zombie。 */
    private static String simplifyEntityName(String identifier) {
        String name = identifier;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        int lt = name.indexOf('<');
        if (lt >= 0) {
            name = name.substring(0, lt);
        }
        return name;
    }

    private static boolean startsWithAscii(byte[] data, String prefix) {
        if (data.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 方块实体 key（tag 0x31 BlockEntity）：9/10B 旧格式（主世界）或 13/14B（含维度）。 */
    private static int[] parseBlockEntityKey(byte[] rawKey) {
        if (rawKey == null) {
            return null;
        }
        int len = rawKey.length;
        if ((len == 9 || len == 10) && (rawKey[8] & 0xFF) == KEY_TYPE_BLOCK_ENTITY) {
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), DIM_OVERWORLD};
        }
        if ((len == 13 || len == 14) && (rawKey[12] & 0xFF) == KEY_TYPE_BLOCK_ENTITY) {
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), mapDimFromKey(readIntLE(rawKey, 8))};
        }
        return null;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 解析 NBT：先直接解析，失败按 zlib 解压后再解析（实体/方块实体值两种都有）。 */
    private static NbtTag parseCompoundWithFallback(byte[] value) {
        if (value == null || value.length < 2) {
            return null;
        }
        try {
            NbtTag root = new BedrockNbtReader().readFromBytes(value);
            if (root != null) {
                return root;
            }
        } catch (Exception ignored) {
        }
        byte[] inflated = inflateZlib(value);
        if (inflated == null) {
            return null;
        }
        try {
            return new BedrockNbtReader().readFromBytes(inflated);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 逐列表面色：从高度向下找第一个非空气方块；无 subchunk 数据时回退 biome 色。 */
    private static int surfaceColor(int height, int lx, int lz,
                                    Map<Integer, SubChunk> subs, byte[] biomes, int dimension) {
        int biomeId = biomes != null ? biomes[(lz << 4) | lx] & 0xFF : -1;
        int biomeColor = biomeId >= 0 ? biomeGrassColor(biomeId) : 0;
        if (subs != null && !subs.isEmpty()) {
            // 起始 y：从最高 subchunk 顶部（建筑限高 320 封顶）向下找第一个非空气。
            // 俯视图不依赖高度图——高度图是生成器预测值（实测会偏到 127~201），
            // 直接按实际方块数据从顶向下找，天然"以地表为主"
            int maxSubTop = Integer.MIN_VALUE;
            for (Integer s : subs.keySet()) {
                if (s > maxSubTop) {
                    maxSubTop = s;
                }
            }
            int yStart = maxSubTop > Integer.MIN_VALUE
                    ? Math.min(maxSubTop * 16 + 15, 320)
                    : height - 1;
            // bedrockmap 方案（color.cpp classify_tint + maptile.cpp TRANSPARENT_WATER）：
            // 逐列找 top_y（第一个非空气，可能是水）与 solid_y（第一个非空气非水）。
            // 灰度模板按子串分类乘群系色调；有水覆盖时渲染河床色并叠水面色，
            // opacity = min(0.15×水深, 0.85)——深海几乎纯水色，浅滩透出河床，
            // 解决"海洋显示干河床"（水面数据在 storage 1，只读 storage 0 会漏掉）
            int waterY = -1;
            int waterColor = 0;
            for (int y = yStart; y >= -64; y--) {
                int subIndex = Math.floorDiv(y, 16);
                SubChunk sub = subs.get(subIndex);
                if (sub == null) {
                    continue;
                }
                int localY = y - subIndex * 16;
                // 主方块层（storage 0）；空气处查水层（storage 1）——1.18+ 水方块在第二层
                int idx = sub.getIndex(lx, localY, lz);
                String name = idx < sub.palette.length ? sub.palette[idx] : null;
                if (name == null || isAirName(name)) {
                    if (sub.waterLayer != null) {
                        int widx = sub.waterLayer.getIndex(lx, localY, lz);
                        String wname = widx < sub.waterLayer.palette.length
                                ? sub.waterLayer.palette[widx] : null;
                        if (wname != null && !isAirName(wname)) {
                            name = wname;
                        }
                    }
                }
                if (name == null || isAirName(name)) {
                    continue;
                }
                // 下界：剔除基岩与下界岩（用户要求——基岩天花板 y>96 跳过；
                // 下界岩大面积深红盖住地形细节，跳过它显示底下的玄武岩/灵魂沙/菌岩等），
                // 找不到其它方块时回退 biome 色
                if (dimension == DIM_NETHER
                        && (name.equals("minecraft:bedrock") || name.endsWith("bedrock")
                        || name.equals("minecraft:netherrack"))) {
                    continue;
                }
                int color = tintColor(name, colorForBlock(name), biomeId);
                if (isWaterName(name)) {
                    if (waterY < 0) {
                        waterY = y;
                        waterColor = color;
                    }
                    continue; // 继续向下找河床（solid）
                }
                if (waterY >= 0) {
                    // 水覆盖：河床色 + 水面色（maptile.cpp applyWaterOverlay）
                    float opacity = Math.min(0.15f * (waterY - y), 0.85f);
                    return blendColors(waterColor, color, opacity);
                }
                return color;
            }
            if (waterY >= 0) {
                return waterColor; // 整列只有水（河床无数据）
            }
        }
        // 无 subchunk 数据或找不到方块：
        // 高度 > 0 的已生成 chunk 用 biome 色（BTR BiomeRenderer 风格）；
        // 高度 0 = 未生成区域 → 主世界渲染为海洋（BTR 对 0x2d 全 0 的行为）
        if (height > 0 && biomeColor != 0) {
            return biomeColor;
        }
        if (dimension == DIM_NETHER) {
            // v270 行为：下界没有海，低处回退下界岩色（biome 色优先）
            return height <= 0 ? COLOR_BACKGROUND : (biomeColor != 0 ? biomeColor : 0xFF6B3535);
        }
        if (dimension == DIM_END) {
            // 末地：无高度数据 = 虚空（透明）
            return COLOR_BACKGROUND;
        }
        // 海平面以下无数据：bedrockmap 水色（灰度水模板 × 群系 water 色调）
        return height <= SEA_LEVEL
                ? tintColor("minecraft:water", colorForBlock("minecraft:water"), biomeId)
                : COLOR_BACKGROUND;
    }

    private static boolean isAirName(String name) {
        return name.endsWith("air"); // minecraft:air / cave_air / void_air
    }

    /** 水方块（含流动水）判定。 */
    private static boolean isWaterName(String name) {
        return name.equals("minecraft:water") || name.equals("minecraft:flowing_water");
    }

    /**
     * bedrockmap 着色器（bedrock-level color.cpp blend_color_with_biome 同款）：
     * 按子串分类 water→leave→grass（含 minecraft: 前缀），灰度模板 × 群系色调/255。
     * 子串匹配保证 short_grass（119,119,119 灰模板）等 equals 列表漏掉的
     * 新方块也能乘上色调（MC 着色器机制）；查不到色调时用 bedrock-level 默认色。
     */
    private static int tintColor(String name, int color, int biomeId) {
        // 固定色方块排除：草径（dirt_path 别名）色表里是"土黄成品色"模板
        // （148,121,65），不是灰色模板——乘 grass 色调会变深绿（原版 MC 草径
        // 顶部也不受群系色调影响）。bedrockmap 子串匹配会命中它，这里按原版
        // 观感排除，保持土黄色。
        if (name.equals("minecraft:grass_path") || name.equals("minecraft:dirt_path")) {
            return color;
        }
        int[] tint = biomeTintTable.get(biomeId);
        if (name.contains("water")) {
            return tint != null ? multiplyTint(color, tint, 9)
                    : multiplyTint(color, DEFAULT_WATER_TINT, 0);
        }
        if (name.contains("leave")) {
            return tint != null ? multiplyTint(color, tint, 6)
                    : multiplyTint(color, DEFAULT_LEAVES_TINT, 0);
        }
        if (name.contains("grass")) {
            return tint != null ? multiplyTint(color, tint, 3)
                    : multiplyTint(color, DEFAULT_GRASS_TINT, 0);
        }
        return color;
    }

    /** maptile.cpp applyWaterOverlay 同款：水面色按 opacity 覆盖在河床色上。 */
    private static int blendColors(int top, int bottom, float opacity) {
        int r = Math.round((1 - opacity) * ((bottom >> 16) & 0xFF) + opacity * ((top >> 16) & 0xFF));
        int g = Math.round((1 - opacity) * ((bottom >> 8) & 0xFF) + opacity * ((top >> 8) & 0xFF));
        int b = Math.round((1 - opacity) * (bottom & 0xFF) + opacity * (top & 0xFF));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 灰度模板 × 色调（tint 数组内偏移：0=rgb 3=grass 6=leaves 9=water）。 */
    private static int multiplyTint(int template, int[] tint, int offset) {
        int r = ((template >> 16) & 0xFF) * tint[offset] / 255;
        int g = ((template >> 8) & 0xFF) * tint[offset + 1] / 255;
        int b = (template & 0xFF) * tint[offset + 2] / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** BTR 老版方块颜色表（minecraft: 名 → ARGB；优先 bedrockmap 色表）。 */
    private static int colorForBlock(String name) {
        Integer tableColor = blockColorTable.get(name);
        if (tableColor != null) {
            return tableColor;
        }
        if (name.equals("minecraft:water") || name.equals("minecraft:flowing_water")) {
            return COLOR_WATER;
        }
        if (name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava")) {
            return 0xFFD45A12;
        }
        if (name.equals("minecraft:stone")) return 0xFF464646;
        if (name.equals("minecraft:granite")) return 0xFF8C7167;
        if (name.equals("minecraft:diorite")) return 0xFFC6C6C6;
        if (name.equals("minecraft:andesite")) return 0xFF797777;
        if (name.equals("minecraft:cobblestone")) return 0xFF7A7A7A;
        if (name.equals("minecraft:mossy_cobblestone")) return 0xFF677967;
        if (name.equals("minecraft:bedrock")) return 0xFF535353;
        if (name.equals("minecraft:dirt")) return 0xFF866043;
        if (name.equals("minecraft:farmland")) return 0xFF734B2D;
        if (name.equals("minecraft:grass_path")) return 0xFF9A8B5B;
        if (name.equals("minecraft:podzol")) return 0xFF533A1B;
        if (name.equals("minecraft:mycelium")) return 0xFF6F6369;
        if (name.equals("minecraft:sand")) return 0xFFDBD3A0;
        if (name.equals("minecraft:red_sand")) return 0xFFA7531F;
        if (name.equals("minecraft:sandstone")) return 0xFFDAD29E;
        if (name.equals("minecraft:red_sandstone")) return 0xFFAA561E;
        if (name.equals("minecraft:gravel")) return 0xFF7E7C7A;
        if (name.equals("minecraft:clay")) return 0xFF9EA4B0;
        if (name.equals("minecraft:hardened_clay")
                || name.equals("minecraft:stained_hardened_clay")) return 0xFF5D3828;
        if (name.equals("minecraft:snow") || name.equals("minecraft:snow_layer")) return 0xFFEFFBFB;
        if (name.equals("minecraft:ice")) return 0xFF7DADFF;
        if (name.equals("minecraft:packed_ice")) return 0xFF97B3E4;
        if (name.equals("minecraft:obsidian")) return 0xFF1A0F1E;
        if (name.equals("minecraft:log") || name.equals("minecraft:log2")
                || name.equals("minecraft:wood")) return 0xFF9A7D4D;
        if (name.equals("minecraft:planks")) return 0xFF9C7F4E;
        if (name.equals("minecraft:end_stone")) return 0xFFDDDFA5;
        if (name.equals("minecraft:glowstone")) return 0xFF8F7645;
        if (name.equals("minecraft:soul_sand")) return 0xFF544033;
        if (name.equals("minecraft:wool")) return 0xFFDDDDDD;
        if (name.equals("minecraft:concrete")) return 0xFF9E9E9E;
        if (name.equals("minecraft:concrete_powder")) return 0xFF9E9E9E;
        if (name.equals("minecraft:deepslate")) return 0xFF3C3C3C;
        if (name.equals("minecraft:cobbled_deepslate")) return 0xFF4C4C4C;
        if (name.equals("minecraft:tuff")) return 0xFF5F6258;
        if (name.equals("minecraft:calcite")) return 0xFFDEDCD7;
        if (name.equals("minecraft:dripstone_block")) return 0xFF7B5F52;
        if (name.equals("minecraft:mud")) return 0xFF4A4238;
        if (name.equals("minecraft:mud_bricks")) return 0xFF8A7A66;
        if (name.equals("minecraft:packed_mud")) return 0xFF9D8A6A;
        if (name.equals("minecraft:moss_block")) return 0xFF5A6C31;
        if (name.equals("minecraft:mangrove_roots")) return 0xFF6B4F33;
        if (name.equals("minecraft:muddy_mangrove_roots")) return 0xFF5D4A35;
        if (name.equals("minecraft:powder_snow")) return 0xFFF4F9FB;
        if (name.equals("minecraft:amethyst_block")) return 0xFF7F5FBF;
        if (name.equals("minecraft:basalt")) return 0xFF3E3A3A;
        if (name.equals("minecraft:blackstone")) return 0xFF2E2A2C;
        if (name.equals("minecraft:bone_block")) return 0xFFD8D4C4;
        if (name.equals("minecraft:coal_ore")) return 0xFF454545;
        if (name.equals("minecraft:iron_ore")) return 0xFFB89B82;
        if (name.equals("minecraft:gold_ore")) return 0xFFCFA23F;
        if (name.equals("minecraft:diamond_ore")) return 0xFF7EC9C9;
        if (name.equals("minecraft:redstone_ore")) return 0xFFA33B3B;
        if (name.equals("minecraft:emerald_ore")) return 0xFF3F9E5F;
        if (name.equals("minecraft:lapis_ore")) return 0xFF3F5FB0;
        if (name.equals("minecraft:netherrack")) return 0xFF6B3535;
        if (name.equals("minecraft:end_stone_bricks")) return 0xFFDDE2A8;
        if (name.equals("minecraft:shroomlight")) return 0xFFD58A4B;
        if (name.equals("minecraft:kelp") || name.equals("minecraft:seagrass")) return 0xFF2E5E2E;
        // 建筑/红石常用方块（刷铁机等人工建筑）
        if (name.equals("minecraft:white_concrete")) return 0xFFE3E3E3;
        if (name.equals("minecraft:light_gray_concrete")) return 0xFFA9A9A9;
        if (name.equals("minecraft:gray_concrete")) return 0xFF6E6E6E;
        if (name.equals("minecraft:black_concrete")) return 0xFF2E2E2E;
        if (name.equals("minecraft:red_concrete")) return 0xFFB03B3B;
        if (name.equals("minecraft:orange_concrete")) return 0xFFD8793B;
        if (name.equals("minecraft:yellow_concrete")) return 0xFFD8C23B;
        if (name.equals("minecraft:lime_concrete")) return 0xFF8FC23B;
        if (name.equals("minecraft:green_concrete")) return 0xFF4F7A3B;
        if (name.equals("minecraft:cyan_concrete")) return 0xFF3B8FA8;
        if (name.equals("minecraft:light_blue_concrete")) return 0xFF6E9BC8;
        if (name.equals("minecraft:blue_concrete")) return 0xFF3B4FA8;
        if (name.equals("minecraft:purple_concrete")) return 0xFF7A3BA8;
        if (name.equals("minecraft:magenta_concrete")) return 0xFFB03BA8;
        if (name.equals("minecraft:pink_concrete")) return 0xFFD87A9B;
        if (name.equals("minecraft:brown_concrete")) return 0xFF6E523B;
        if (name.equals("minecraft:hopper")) return 0xFF5F5F5F;
        if (name.equals("minecraft:dropper")) return 0xFF6E6E6E;
        if (name.equals("minecraft:dispenser")) return 0xFF6E6E6E;
        if (name.equals("minecraft:redstone_wire")) return 0xFFB31F1F;
        if (name.equals("minecraft:redstone_block")) return 0xFFB31F1F;
        if (name.equals("minecraft:sea_lantern")) return 0xFFB8D8CC;
        if (name.equals("minecraft:glass")) return 0xFFC8D8E8;
        if (name.equals("minecraft:tnt")) return 0xFFC23B32;
        if (name.equals("minecraft:composter")) return 0xFF6B4F33;
        if (name.equals("minecraft:double_plant")) return 0xFF6FA84C;
        if (name.equals("minecraft:iron_block")) return 0xFFD8D8D8;
        if (name.equals("minecraft:gold_block")) return 0xFFE8C84B;
        if (name.equals("minecraft:diamond_block")) return 0xFF7EC9C9;
        if (name.equals("minecraft:emerald_block")) return 0xFF3F9E5F;
        if (name.equals("minecraft:furnace")) return 0xFF8C8C8C;
        if (name.equals("minecraft:chest")) return 0xFFB08A3E;
        if (name.equals("minecraft:crafting_table")) return 0xFF8A6B3F;
        if (name.equals("minecraft:slime")) return 0xFF7FBF6F;
        if (name.equals("minecraft:quartz_block")) return 0xFFE8E4DC;
        if (name.equals("minecraft:bricks")) return 0xFF9A5B40;
        if (name.equals("minecraft:stone_bricks")) return 0xFF7A7A7A;
        if (name.equals("minecraft:mossy_stone_bricks")) return 0xFF677967;
        if (name.equals("minecraft:prismarine")) return 0xFF6E9A9A;
        if (name.equals("minecraft:sponge")) return 0xFFC8C83B;
        if (name.equals("minecraft:piston") || name.equals("minecraft:sticky_piston")) return 0xFF8C8C8C;
        if (name.equals("minecraft:observer")) return 0xFF6E6E6E;
        if (name.equals("minecraft:repeater")) return 0xFF8C8C8C;
        if (name.equals("minecraft:torch")) return 0xFFE8C83B;
        if (name.equals("minecraft:lantern")) return 0xFFE8C83B;
        if (name.equals("minecraft:ladder")) return 0xFF9A7D4D;
        if (name.equals("minecraft:fence")) return 0xFF9C7F4E;
        if (name.equals("minecraft:cobblestone_wall")) return 0xFF7A7A7A;
        if (name.equals("minecraft:melon_block")) return 0xFF7AB83B;
        if (name.equals("minecraft:pumpkin")) return 0xFFC87A2E;
        if (name.equals("minecraft:hay_block")) return 0xFFC8A83B;
        if (name.equals("minecraft:bookshelf")) return 0xFF8A6B3F;
        if (name.equals("minecraft:noteblock")) return 0xFF6B4F33;
        if (name.equals("minecraft:jukebox")) return 0xFF5F3B2E;
        if (name.equals("minecraft:enchanting_table")) return 0xFF7A3B3B;
        if (name.equals("minecraft:anvil")) return 0xFF5F5F5F;
        if (name.equals("minecraft:cauldron")) return 0xFF4F4F4F;
        if (name.equals("minecraft:rail")) return 0xFF8C8C8C;
        if (name.equals("minecraft:golden_rail")) return 0xFFC8A83B;
        if (name.equals("minecraft:stonecutter")) return 0xFF8C8C8C;
        if (name.equals("minecraft:grindstone")) return 0xFF8C8C8C;
        if (name.equals("minecraft:bell")) return 0xFFD8C23B;
        if (name.equals("minecraft:lectern")) return 0xFF9A7D4D;
        if (name.equals("minecraft:barrel")) return 0xFF8A6B3F;
        if (name.equals("minecraft:smoker") || name.equals("minecraft:blast_furnace")) return 0xFF5F5F5F;
        if (name.equals("minecraft:campfire")) return 0xFF8C8C8C;
        if (name.equals("minecraft:scaffolding")) return 0xFFC8A83B;
        if (name.equals("minecraft:beehive")) return 0xFFC8A83B;
        if (name.equals("minecraft:target")) return 0xFFD8C8C8;
        // ---- 后缀/子串规则（覆盖变体方块名，避免显示灰色） ----
        if (name.endsWith("_leaves")) return 0xFF3F6E2F;                      // 各色树叶：深绿
        if (name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_hyphae")
                || name.endsWith("_stem") || name.equals("minecraft:bamboo_block")) return 0xFF9A7D4D;
        if (name.endsWith("_planks")) return 0xFF9C7F4E;
        if (name.endsWith("_wool")) return 0xFFDDDDDD;
        if (name.endsWith("_carpet")) return 0xFFB0B0B0;
        if (name.endsWith("_concrete")) return 0xFF9E9E9E;
        if (name.endsWith("_terracotta") || name.endsWith("_glazed_terracotta")) return 0xFF9A5B40;
        if (name.contains("_ore") || name.equals("minecraft:copper_ore")) return 0xFFB89B82;
        if (name.endsWith("_bed") || name.endsWith("_bedrock")) return name.endsWith("_bed") ? 0xFFC23B3B : 0xFF535353;
        if (name.endsWith("_door") || name.endsWith("_trapdoor")) return 0xFF8A6B3F;
        if (name.contains("_sign")) return 0xFF8A6B3F;
        if (name.endsWith("_stairs") || name.endsWith("_slab") || name.endsWith("_wall")
                || name.endsWith("_bricks")) return 0xFF8C8C8C;
        if (name.endsWith("_fence") || name.endsWith("_fence_gate")) return 0xFF9C7F4E;
        if (name.endsWith("_button") || name.endsWith("_pressure_plate")) return 0xFF7A7A7A;
        if (name.equals("minecraft:leaf_litter")) return 0xFF8A6B3F;           // 落叶层（1.26 常见）
        if (name.equals("minecraft:glow_lichen")) return 0xFF7A8A6A;           // 发光地衣
        if (name.equals("minecraft:moss_carpet")) return 0xFF5A6C31;           // 苔藓地毯
        if (name.equals("minecraft:magma")) return 0xFFA53B2E;
        if (name.equals("minecraft:bubble_column")) return COLOR_WATER;        // 水泡柱
        if (name.equals("minecraft:raw_copper_block") || name.equals("minecraft:copper_block")
                || name.equals("minecraft:cut_copper")) return 0xFFB06E4A;
        if (name.contains("amethyst")) return 0xFF9A6EC8;                      // 紫水晶系列
        if (name.equals("minecraft:web")) return 0xFFE8E8E8;
        if (name.equals("minecraft:mob_spawner")) return 0xFF3A4A5A;
        if (name.equals("minecraft:lever") || name.contains("comparator")) return 0xFF8C8C8C;
        if (name.equals("minecraft:bee_nest") || name.equals("minecraft:beehive")) return 0xFFC8A83B;
        if (name.equals("minecraft:fire")) return 0xFFE89B3B;
        if (name.equals("minecraft:command_block") || name.equals("minecraft:structure_block")
                || name.equals("minecraft:structure_void")) return 0xFFA87AC8;
        if (name.equals("minecraft:moving_block")) return 0xFF8C8C8C;
        if (name.equals("minecraft:blue_ice")) return 0xFF8EB3E8;
        if (name.equals("minecraft:wheat")) return 0xFFC8B84B;                 // 农田作物
        if (name.equals("minecraft:azalea") || name.equals("minecraft:flowering_azalea")
                || name.equals("minecraft:bush") || name.contains("sapling")) return 0xFF5A8A3B;
        if (name.contains("flower") || name.equals("minecraft:poppy") || name.equals("minecraft:peony")
                || name.equals("minecraft:lilac") || name.equals("minecraft:rose_bush")
                || name.equals("minecraft:wildflowers") || name.contains("tulip")) return 0xFFC86FA8;
        if (name.contains("mushroom")) return 0xFFB03B3B;
        if (name.equals("minecraft:cactus")) return 0xFF4E7A2E;
        // 草地/树叶类兜底（biome 未解析时；子串匹配覆盖 short_grass 等新名字）
        if (name.contains("grass") || name.contains("leave")) return 0xFF6FA84C;
        return 0xFF7F7F7F; // 未知方块：灰
    }

    /** 0x2D Data2D：byte[256] biome id（原样字节，渲染时查色调表）。 */
    private static byte[] extractBiomes2d(byte[] value) {
        if (value == null || value.length < 768) {
            return null;
        }
        return Arrays.copyOfRange(value, 512, 768);
    }

    /**
     * 0x2B Data3D：512 字节后是 3D biome 存储（新版 BTR Data3D.readBiome 同款）：
     * [header][word 数据][palette 数 int32][palette int32[]]。
     * 16 层 cy 覆盖 -64~192（每层 16 方块高），每列取其表面高度对应的 cy 层。
     */
    private static byte[] extractBiomes3d(byte[] value, int[] hmap) {
        if (value == null || value.length < 516) {
            return null;
        }
        byte[] out = new byte[256];
        int offset = 512;
        while (offset + 1 < value.length) {
            int header = value[offset++] & 0xFF;
            if ((header & 0x01) != 0x01) {
                break; // flag=0：结束
            }
            if (header == 0xFF) {
                continue;
            }
            int bits = header >> 1;
            if (bits == 0) {
                if (offset + 4 > value.length) {
                    break;
                }
                byte biome = (byte) readIntLE(value, offset);
                Arrays.fill(out, biome);
                offset += 4;
                continue;
            }
            int blocksPerWord = 32 / bits;
            int wordCount = (4096 + blocksPerWord - 1) / blocksPerWord;
            int paletteOffset = wordCount * 4 + offset;
            if (paletteOffset + 4 > value.length) {
                break;
            }
            int paletteLength = readIntLE(value, paletteOffset);
            paletteOffset += 4;
            if (paletteLength < 1 || paletteLength > 1024
                    || paletteOffset + paletteLength * 4 > value.length) {
                break;
            }
            int[] palette = new int[paletteLength];
            for (int i = 0; i < paletteLength; i++) {
                palette[i] = readIntLE(value, paletteOffset + i * 4);
            }
            // 每列取表面高度对应的 cy 层（biome 16 层覆盖 -64~192，每层 16 方块）
            for (int cx = 0; cx < 16; cx++) {
                for (int cz = 0; cz < 16; cz++) {
                    int i = cz * 16 + cx;
                    int h = hmap != null ? hmap[i] : 64;
                    int cy = Math.max(0, Math.min(15, (h + 63) / 16));
                    int blockPos = ((cx * 16) + cz) * 16 + cy;
                    int wordStart = blockPos / blocksPerWord;
                    int bitOffset = (blockPos % blocksPerWord) * bits;
                    int bitStart = wordStart * 32 + bitOffset;
                    int byteStart = offset + bitStart / 8;
                    if (byteStart + 1 >= value.length) {
                        continue;
                    }
                    int v = (value[byteStart] & 0xFF) | ((value[byteStart + 1] & 0xFF) << 8);
                    v >>>= (bitStart % 8);
                    int idx = v & ((1 << bits) - 1);
                    if (idx < paletteLength) {
                        out[i] = (byte) palette[idx];
                    }
                }
            }
            offset = paletteOffset + 4 * paletteLength;
        }
        return out;
    }

    /** biome id → 地图色（优先 bedrockmap 色表 rgb，回退 BTR 内置表）。 */
    private static int biomeGrassColor(int biomeId) {
        int[] tint = biomeTintTable.get(biomeId);
        if (tint != null) {
            return 0xFF000000 | (tint[0] << 16) | (tint[1] << 8) | tint[2];
        }
        switch (biomeId) {
            case 0: return 0xFF020070;   // ocean
            case 1: return 0xFF8CB060;   // plains
            case 2: return 0xFFFB941B;   // desert
            case 3: return 0xFF5D635D;   // extreme hills
            case 4: return 0xFF1E8A3C;   // forest（提亮：老 BTR 原色 0x026320 过暗近黑）
            case 5: return 0xFF09665B;   // taiga
            case 6: return 0xFF04C88B;   // swampland
            case 7: return 0xFF0101FF;   // river
            case 9: return 0xFFD8DFA8;   // the_end（末地石浅黄）
            case 10: return 0xFF8E8DA1;  // frozen ocean
            case 11: return 0xFFA0A8F0;  // frozen river（冰河蓝灰）
            case 12: return 0xFFE0ECF4;  // ice plains（淡蓝白，非纯白）
            case 16: return 0xFFFADF55;  // beach
            case 21: return 0xFF527A07;  // jungle
            case 23: return 0xFF6E9A4E;  // jungle edge
            case 24: return 0xFF02002F;  // deep ocean
            case 25: return 0xFFA2A484;  // stone beach
            case 26: return 0xFFB8C4C0;  // cold beach（冷岸灰白）
            case 27: return 0xFF307546;  // birch forest
            case 29: return 0xFF425218;  // roofed forest
            case 30: return 0xFF8FBF5A;  // birch forest hills（白桦丘陵浅绿）
            case 32: return 0xFF4E7A4E;  // forest hills
            case 34: return 0xFF6E8A6E;  // taiga hills
            case 35: return 0xFFC0B45E;  // savanna
            case 37: return 0xFFDC4213;  // mesa
            case 40: return 0xFF44A879;  // warm ocean (新版 id)
            case 41: return 0xFF44A879;  // deep warm ocean
            case 42: return 0xFF44A879;  // lukewarm ocean
            case 43: return 0xFF44A879;  // deep lukewarm ocean
            case 44: return 0xFF44A879;  // cold ocean
            case 45: return 0xFF44A879;  // deep cold ocean
            case 46: return 0xFF44A879;  // frozen ocean (新版)
            case 47: return 0xFF44A879;  // deep frozen ocean
            case 48: return 0xFF527A07;  // bamboo jungle
            case 178: return 0xFF6B3535; // soulsand valley
            case 179: return 0xFFA33B3B; // crimson forest
            case 180: return 0xFF3F8E8E; // warped forest
            case 181: return 0xFF5A5A5A; // basalt deltas
            case 182: return 0xFFB8C4B8; // jagged peaks
            case 183: return 0xFFE8ECF4; // frozen peaks
            case 184: return 0xFFD8E4E8; // snowy slopes
            case 185: return 0xFF4E7A4E; // grove
            case 186: return 0xFF7CB85A; // meadow
            case 187: return 0xFF4E8A5A; // lush caves
            case 188: return 0xFF7A6A5A; // dripstone caves（棕灰）
            case 189: return 0xFF7F8A7F; // stony peaks（石灰）
            case 190: return 0xFF3A4A5A; // deep dark
            case 191: return 0xFF2E5E2E; // mangrove swamp
            case 192: return 0xFFD8B4C8; // cherry grove
            case 193: return 0xFF8A9A7A; // pale garden
            default:
                if (biomeId < 128) {
                    return 0xFF8CB060; // 未知 → plains 绿
                }
                // 老版 M 变体 id≥128：按 (id-128) 映射回基础 biome 的近似色
                return biomeGrassColor(biomeId - 128);
        }
    }

    /** 解码 v9 subchunk storage：返回 {bits, word 数据, palette 名字}；失败返回 null。 */
    private static SubChunk decodeSubChunk(byte[] value) {
        if (value == null || value.length < 5 || (value[0] & 0xFF) != 9) {
            return null;
        }
        int p = 3; // 版本 + storage 数 + sub 索引
        int count = value[1] & 0xFF;
        if (count < 1 || count > 2) {
            return null;
        }
        // bedrock-level sub_chunk 多层结构：storage 0 = 主方块层，storage 1 = 水层。
        // 只解码第一个 storage 会导致海面水方块缺失（海洋显示河床的根因）。
        SubChunk primary = null;
        for (int s = 0; s < count && p + 2 <= value.length; s++) {
            int header = value[p++] & 0xFF;
            int bits = header >> 1;
            if (bits < 1 || bits > 16) {
                return null;
            }
            int blocksPerWord = 32 / bits;
            int wordCount = (4096 + blocksPerWord - 1) / blocksPerWord;
            int dataBytes = wordCount * 4;
            if (p + dataBytes + 4 > value.length) {
                return null;
            }
            byte[] data = Arrays.copyOfRange(value, p, p + dataBytes);
            int paletteStart = p + dataBytes;
            int paletteSize = readIntLE(value, paletteStart);
            if (paletteSize < 0 || paletteSize > 4096) {
                return null;
            }
            String[] palette = new String[Math.max(paletteSize, 1)];
            int pe = paletteStart + 4;
            for (int i = 0; i < paletteSize && pe + 3 <= value.length; i++) {
                int type = value[pe++] & 0xFF;
                if (type == 0) {
                    continue;
                }
                int nameLen = (value[pe] & 0xFF) | ((value[pe + 1] & 0xFF) << 8);
                pe += 2 + nameLen;
                if (pe > value.length) {
                    return null;
                }
                palette[i] = extractPaletteName(value, pe, type);
                pe = skipNbtPayload(value, pe, type);
                if (pe < 0) {
                    return null;
                }
            }
            p = pe;
            // 两种 palette 布局兼容（Tomcc spec）：
            // 1) palette 显式含 air 条目（可能在任意索引，不一定在 0）→ 数据索引 k 直接对应 palette[k]；
            // 2) palette 完全不含 air → 数据索引 0=隐式空气，k≥1 对应 palette[k-1]。
            // 之前只查 palette[0]，air 在非 0 位置时误插隐式 air 导致全部方块偏移一位（错位根因）。
            boolean hasAir = false;
            for (String pn : palette) {
                if (pn != null && pn.endsWith("air")) {
                    hasAir = true;
                    break;
                }
            }
            if (!hasAir) {
                String[] withAir = new String[Math.max(paletteSize, 1) + 1];
                withAir[0] = "minecraft:air";
                System.arraycopy(palette, 0, withAir, 1, palette.length);
                palette = withAir;
            }
            if (s == 0) {
                primary = new SubChunk(bits, data, palette);
            } else {
                primary.waterLayer = new SubChunk(bits, data, palette);
            }
        }
        return primary;
    }

    /** 调试导出：渲染整图到 PNG 存应用外部目录（截屏服务异常时的替代验证手段）。 */
    public static void debugExport(WorldMap map) {
        try {
            if (map == null || map.colors == null || map.width <= 0 || map.height <= 0) {
                return;
            }
            int w = map.width;
            int h = map.height;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(map.colors, 0, w, 0, 0, w, h);
            File dir = new File("/sdcard/Download");
            File out = new File(dir, "map_debug.png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
            Log.i(TAG, "调试导出: " + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "调试导出失败", t);
        }
    }

    /** palette NBT 条目（compound）里提取 "name" TAG_String。 */
    private static String extractPaletteName(byte[] value, int p, int type) {
        if (type != NbtTag.TAG_COMPOUND) {
            return null;
        }
        try {
            while (p < value.length) {
                int t = value[p++] & 0xFF;
                if (t == 0) {
                    return null;
                }
                int nameLen = (value[p] & 0xFF) | ((value[p + 1] & 0xFF) << 8);
                p += 2;
                if (p + nameLen > value.length) {
                    return null;
                }
                String tagName = new String(value, p, nameLen, java.nio.charset.StandardCharsets.UTF_8);
                p += nameLen;
                if (t == NbtTag.TAG_STRING && "name".equals(tagName)) {
                    if (p + 2 > value.length) {
                        return null;
                    }
                    int strLen = (value[p] & 0xFF) | ((value[p + 1] & 0xFF) << 8);
                    p += 2;
                    int len = Math.min(strLen, Math.min(96, value.length - p));
                    return new String(value, p, len, java.nio.charset.StandardCharsets.UTF_8);
                }
                p = skipNbtPayload(value, p, t);
                if (p < 0) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
