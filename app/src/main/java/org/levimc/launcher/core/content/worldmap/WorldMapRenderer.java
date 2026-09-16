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
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
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
    /** 下界渲染 y 范围（设置页可调；netherYMin<0 = 全量渲染）。 */
    public static volatile int netherYMin = -1;
    public static volatile int netherYMax = -1;

    /** 下界窗口裁剪：sub 与 [yMin,yMax] 无交集则剔除（全量时不动）。 */
    private static void applyNetherWindow(Map<Integer, SubChunk> subs) {
        if (netherYMin < 0 || subs == null || subs.isEmpty()) {
            return;
        }
        subs.keySet().removeIf(s -> s * 16 + 15 < netherYMin || s * 16 > netherYMax);
    }

    /** 下界 sub 是否在设定 y 范围内（供流式/全量 filter 用）。 */
    private static boolean netherSubInRange(int sub) {
        return netherYMin < 0 || (sub * 16 + 15 >= netherYMin && sub * 16 <= netherYMax);
    }
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
                    // bedrock-level color.cpp 语义：biome_grass_map 只存有 grass 键的条目，
                    // 找不到时 blend_with_biome 用 default_grass_color。不能回退 rgb——
                    // river rgb=[0,0,255] 纯蓝、cherry_groves 粉、deep_dark 深黑，
                    // 回退 rgb 会把河岸草方块染成深蓝（实测 -1942,1069）。
                    // 缺失 key 存 -1 标记：biomeGrassColor 按 grass→water→海洋类
                    // 默认水色/陆地类默认草色的顺序取主色（biome 图层不再直接用 rgb）
                    int[] grass = entry.has("grass") ? readRgb3(entry.optJSONArray("grass"))
                            : new int[]{-1, -1, -1};
                    int[] leaves = entry.has("leaves") ? readRgb3(entry.optJSONArray("leaves"))
                            : new int[]{-1, -1, -1};
                    int[] water = entry.has("water") ? readRgb3(entry.optJSONArray("water"))
                            : new int[]{-1, -1, -1};
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
                // 14B key 的 sub 索引同样是 signed（下界/末地 y<0 的 subchunk
                // 是 0xFC~0xFF = -4~-1）——之前未做符号转换，sub 解析成 252，
                // surfaceColor 永远找不到该层 → 下界/末地整片回退 biome 纯色块
                int sub = len == 14 ? rawKey[13] : -1;
                if (sub > 127) {
                    sub -= 256;
                }
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

    /** 无符号字节序比较（LevelDB key 排序语义）。 */
    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a != null ? a.length : 0, b != null ? b.length : 0);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return (a != null ? a.length : 0) - (b != null ? b.length : 0);
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

    /** chunk 内坡度阴影（BTR btrHeightShading 同款：西/北邻居高度差，
     * chunk 边界取自身高度——樱花树冠/山地的立体感来源）。 */
    private static int applyShading(int color, int[] hmap, int i) {
        if ((color & 0xFF000000) == 0) {
            return color;
        }
        int lx = i & 15;
        int lz = i >> 4;
        int h = hmap[i];
        int hW = lx > 0 ? hmap[i - 1] : h;
        int hN = lz > 0 ? hmap[i - 16] : h;
        float shading = btrHeightShading(h, hW, hN);
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * shading));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * shading));
        int b = Math.min(255, (int) ((color & 0xFF) * shading));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
    private static final int[] DEFAULT_WATER_TINT = {75, 140, 235};
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
        public int playerBlockY = -1;
        public int playerBlockZ = -1;
        /** 本地玩家 UniqueID（db ~local_player，-1 = 无） */
        public long playerUniqueId = -1;
        /** 流式渲染时 palette 检测到的结构标记（海底神殿/末地城；小世界路径为 null） */
        public List<StructureMarker> detectedStructures;
        /** 降采样比例（大世界 4×4 代表 chunk = 4；普通世界 1）。标记坐标需除以该值。 */
        public int blockScale = 1;
        /**
         * 大世界 chunk 色缓存（BTR/blocktopograph 同款思路：chunk 级 16×16 色，
         * 精度 100% 且内存 O(chunk 数)——155MB 世界 2.5 万 chunk 仅 25MB，
         * 整图数组需 1GB）。key = pack(cx,cz)，value = 256 ARGB。
         * 小世界路径为 null（用 colors 整图数组）。
         */
        public Map<Long, int[]> chunkColors;
        /** 大世界 chunk biome 色缓存（biome 图层用；同 chunkColors 布局）。 */
        public Map<Long, int[]> chunkBiomeColors;
        /** 视口按需渲染源（BTR 式）：db 目录与维度；非空时缺失 chunk 由外部按需渲染。 */
        public File chunkSourceDir;
        public int chunkSourceDim;
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
         * chunk 缓存路径按 chunk 粒度搜索（block 级在大世界上是
         * 4×maxR² ≈ 12 亿次查找，主线程 ANR）。
         */
        public int[] nearestGeneratedBlock(int worldX, int worldZ) {
            int cx = worldX - minBlockX;
            int cz = worldZ - minBlockZ;
            if (isBlockVisible(cx, cz)) {
                return new int[]{worldX, worldZ};
            }
            if (chunkColors != null) {
                // 按需渲染模式的 bounds-only 空地图：无数据可搜，直接停目标点
                // （曾在这里螺旋搜索全图 474 万次 CHM 查找，主线程卡 7.5 秒）
                if (chunkColors.isEmpty()) {
                    return new int[]{worldX, worldZ};
                }
                int tcx = Math.floorDiv(minBlockX + cx, 16);
                int tcz = Math.floorDiv(minBlockZ + cz, 16);
                if (chunkHasData(tcx, tcz)) {
                    return new int[]{worldX, worldZ};
                }
                // 螺旋搜索上限 128 圈（~6.5 万次查找 ≈ 100ms）；
                // 按需渲染的数据只在视口附近，远距离搜索无意义且卡主线程
                int maxRc = Math.min(Math.max(width, height) / 16 + 1, 128);
                for (int r = 1; r <= maxRc; r++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                continue;
                            }
                            int nx = tcx + dx;
                            int nz = tcz + dz;
                            if (chunkHasData(nx, nz)) {
                                return new int[]{nx * 16 + 8, nz * 16 + 8};
                            }
                        }
                    }
                }
                // 附近无数据（视口按需渲染尚未填充）：停在目标点，渲染后自然填补
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
                        if (isBlockVisible(nx, nz)) {
                            return new int[]{minBlockX + nx, minBlockZ + nz};
                        }
                    }
                }
            }
            return new int[]{minBlockX + width / 2, minBlockZ + height / 2};
        }

        /** chunk 是否有任何非透明像素。 */
        private boolean chunkHasData(int cx, int cz) {
            int[] cc = chunkColors.get(pack(cx, cz));
            if (cc == null) {
                return false;
            }
            for (int v : cc) {
                if ((v & 0xFF000000) != 0) {
                    return true;
                }
            }
            return false;
        }

        /** 块可见性判定（兼容整图数组与 chunk 缓存两种路径）。 */
        private boolean isBlockVisible(int bx, int bz) {
            if (bx < 0 || bx >= width || bz < 0 || bz >= height) {
                return false;
            }
            if (colors != null) {
                return (colors[bz * width + bx] & 0xFF000000) != 0;
            }
            if (chunkColors != null) {
                int[] cc = chunkColors.get(pack(Math.floorDiv(minBlockX + bx, 16),
                        Math.floorDiv(minBlockZ + bz, 16)));
                if (cc == null) {
                    return false;
                }
                int lx = Math.floorMod(minBlockX + bx, 16);
                int lz = Math.floorMod(minBlockZ + bz, 16);
                return (cc[(lz << 4) | lx] & 0xFF000000) != 0;
            }
            return false;
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
            if (dimension == DIM_NETHER) {
                // 下界默认全量；设置页 y 范围生效时过滤
                if (!netherSubInRange(sub)) {
                    skipped++;
                    continue;
                }
            } else {
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

        return assembleMap(heightMaps, biomeMaps, subChunks, dimension);
    }

    /** 组装全图：收集完 heightMaps/biomeMaps/subChunks 后的共享渲染路径。 */
    private static WorldMap assembleMap(Map<Long, int[]> heightMaps,
                                        Map<Long, byte[]> biomeMaps,
                                        Map<Long, Map<Integer, SubChunk>> subChunks,
                                        int dimension) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Long key : heightMaps.keySet()) {
            minX = Math.min(minX, unpackX(key));
            maxX = Math.max(maxX, unpackX(key));
            minZ = Math.min(minZ, unpackZ(key));
            maxZ = Math.max(maxZ, unpackZ(key));
        }
        // 2) 组装全图：每 chunk 16×16 表面色
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        int width = spanX * 16;
        int height = spanZ * 16;
        int minBlockX = minX * 16;
        int minBlockZ = minZ * 16;
        long cells = (long) width * height;
        Log.i(TAG, "assembleMap: chunk 范围=(" + minX + "," + minZ + ")-(" + maxX + "," + maxZ
                + ") span=" + spanX + "x" + spanZ + " 数组=" + (cells * 4 / 1024 / 1024)
                + "MB heightMaps=" + heightMaps.size());
        if (cells > 100_000_000L) {
            // 防御：>1 亿格（3 个 400MB 数组）必 OOM，拒绝渲染
            Log.e(TAG, "地图过大拒绝渲染: " + spanX + "x" + spanZ);
            return null;
        }
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

    /**
     * 大世界流式渲染：155MB 级 db 全量 readAllEntries 会 OOM。
     * 用 LevelDBReader 的过滤/只读 key 模式分三遍流式收集：
     * 1) readKeys：统计每 chunk 实际最高 sub 索引（value 不分配内存）
     * 2) readEntries：高度图/版本 value（0x2b/0x2c）
     * 3) readEntries：只解码窗口内 subchunk（maxSub±2）
     */
    public static WorldMap buildSatelliteMapStreaming(File dbDir, int dimension) {
        List<StructureMarker> detected = new ArrayList<>();
        Map<Long, Integer> monumentChunks = new HashMap<>();
        Map<Long, Integer> endCityChunks = new HashMap<>();
        java.util.Set<Long> renderedChunks = new java.util.HashSet<>();
        Map<Long, int[]> chunkColors = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Long, int[]> chunkBiomeColors = new java.util.concurrent.ConcurrentHashMap<>();
        final int[] finalMinCx = {Integer.MAX_VALUE};
        final int[] finalMaxCx = {Integer.MIN_VALUE};
        final int[] finalMinCz = {Integer.MAX_VALUE};
        final int[] finalMaxCz = {Integer.MIN_VALUE};
        int decoded = 0;
        try {
            LevelDBReader reader = new LevelDBReader(dbDir);
            // 1) 第一遍：只读 subchunk key 统计最高 sub（窗口围绕实际 maxSub，
            //    高度图是生成器预测值不可靠）；同时统计 chunk 范围
            Map<Long, Integer> maxSubByChunk = new HashMap<>();
            List<byte[]> subKeys = reader.readKeys(k -> {
                int[] ck = parseChunkKey(k);
                return ck != null && ck[3] >= 0 && ck[2] == dimension && isSubchunkKey(k);
            });
            int minCx = Integer.MAX_VALUE;
            int maxCx = Integer.MIN_VALUE;
            int minCz = Integer.MAX_VALUE;
            int maxCz = Integer.MIN_VALUE;
            for (byte[] k : subKeys) {
                int[] ck = parseChunkKey(k);
                if (ck == null) {
                    continue;
                }
                long key = pack(ck[0], ck[1]);
                maxSubByChunk.merge(key, ck[3], Math::max);
                minCx = Math.min(minCx, ck[0]);
                maxCx = Math.max(maxCx, ck[0]);
                minCz = Math.min(minCz, ck[1]);
                maxCz = Math.max(maxCz, ck[1]);
            }
            Log.i(TAG, "流式第一遍: subchunk key 数=" + subKeys.size()
                    + ", 有 sub 数据的 chunk 数=" + maxSubByChunk.size());
            subKeys = null; // 释放
            // 2) 第二遍（BTR/blocktopograph 同款 chunk 缓存方案，精度 100%）：
            //    高度图 + 窗口内 subchunk 同遍读取，逐 chunk 渲染 16×16 色后
            //    立即释放——内存 O(单 chunk)，整图色缓存仅 chunk 数×1KB
            //    （155MB 世界 2.5 万 chunk ≈ 25MB，整图数组需 1GB）
            final Map<Long, Integer> maxSubRef = maxSubByChunk;
            final int[] filterDiag = new int[6]; // [总key, 高度图, subchunk通过, subchunk窗口拒, sub<0拒, parseNull]
            List<LevelDBEntry> heightEntries = reader.readEntries(k -> {
                filterDiag[0]++;
                int[] ck = parseChunkKey(k);
                if (ck == null || ck[2] != dimension) {
                    return false;
                }
                // 类型字节：9/10B 在 k[8]（10B 的 k[9] 是 sub 字节！），13/14B 在 k[12]
                int type = k[k.length == 13 || k.length == 14 ? 12 : 8] & 0xFF;
                if (type == KEY_TYPE_DATA_3D || type == 0x2C || type == KEY_TYPE_DATA_2D) {
                    filterDiag[1]++;
                    return true; // 高度图
                }
                if (type == KEY_TYPE_LEGACY_MIXED) {
                    if (ck[3] < 0) {
                        filterDiag[4]++;
                        return false;
                    }
                    if (dimension == DIM_NETHER) {
                        // 下界默认全量；设置页 y 范围生效时过滤
                        if (!netherSubInRange(ck[3])) {
                            filterDiag[3]++;
                            return false;
                        }
                    } else {
                        // 窗口内 subchunk（maxSub±2）
                        Integer maxSub = maxSubRef.get(pack(ck[0], ck[1]));
                        if (maxSub == null || ck[3] < maxSub - 2 || ck[3] > maxSub + 2) {
                            filterDiag[3]++;
                            return false;
                        }
                    }
                    filterDiag[2]++;
                    return true;
                }
                return false;
            });
            Log.i(TAG, "流式 filter 诊断: 总key=" + filterDiag[0] + " 高度图=" + filterDiag[1]
                    + " subchunk通过=" + filterDiag[2] + " 窗口拒=" + filterDiag[3]
                    + " 负sub拒=" + filterDiag[4]);
            // readEntries 内部是 HashMap 无序遍历——逐 chunk 收集逻辑依赖
            // 同 chunk key 相邻（"换 chunk 渲染上一个"），乱序时 subchunk 先到、
            // 高度图后到，换 chunk 重置 curSubs 把 subchunk 丢掉 → 渲染时
            // subs 空 → 整片回退 biome 纯色（下界/末地"无阴影无方块"的根因）
            heightEntries.sort((a, b) -> compareBytes(
                    a.getKey().getRawKey(), b.getKey().getRawKey()));
            // 逐 chunk 收集 → 渲染 → 释放（sst 内同 chunk key 相邻有序）
            int curCx = Integer.MIN_VALUE;
            int curCz = Integer.MIN_VALUE;
            int[] curHmap = null;
            byte[] curBiomes = null;
            Map<Integer, SubChunk> curSubs = new HashMap<>();
            for (LevelDBEntry entry : heightEntries) {
                byte[] rawKey = entry.getKey().getRawKey();
                int[] chunkKey = parseChunkKey(rawKey);
                if (chunkKey == null) {
                    continue;
                }
                if (chunkKey[0] != curCx || chunkKey[1] != curCz) {
                    // 换 chunk：渲染上一个并释放
                    if (curHmap != null) {
                        renderChunkToCache(curCx, curCz, curHmap, curBiomes, curSubs,
                                dimension, renderedChunks, chunkColors, chunkBiomeColors,
                                monumentChunks, endCityChunks,
                                finalMinCx, finalMaxCx, finalMinCz, finalMaxCz);
                        decoded += curSubs.size();
                    }
                    curCx = chunkKey[0];
                    curCz = chunkKey[1];
                    curHmap = null;
                    curBiomes = null;
                    curSubs = new HashMap<>();
                }
                if (isSubchunkKey(rawKey)) {
                    try {
                        SubChunk sc = decodeSubChunk(entry.getValue());
                        if (sc != null) {
                            curSubs.put(chunkKey[3], sc);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to decode subchunk at " + chunkKey[0] + "," + chunkKey[1], e);
                    }
                    continue;
                }
                try {
                    if (isData2dKey(rawKey)) {
                        int[] hmap = extractData2d(entry.getValue());
                        if (hmap != null && curHmap == null) {
                            curHmap = hmap;
                            int type = (rawKey.length == 13 ? rawKey[12] : rawKey[8]) & 0xFF;
                            if (type == KEY_TYPE_DATA_2D) {
                                byte[] biomes = extractBiomes2d(entry.getValue());
                                if (biomes != null) {
                                    curBiomes = biomes;
                                }
                            } else {
                                byte[] biomes = extractBiomes3d(entry.getValue(), hmap);
                                if (biomes != null) {
                                    curBiomes = biomes;
                                }
                            }
                        }
                    } else if (rawKey.length == 13 && chunkKey[3] < 0) {
                        // 13B 0x2c ChunkVersion NBT HeightMap（1.18+ 下界/末地）
                        int[] hmap = extractChunkHeightMap256(parseChunkNbt(entry.getValue()));
                        if (hmap != null && curHmap == null) {
                            curHmap = hmap;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to decode chunk at " + chunkKey[0] + "," + chunkKey[1], e);
                }
            }
            if (curHmap != null) {
                renderChunkToCache(curCx, curCz, curHmap, curBiomes, curSubs,
                        dimension, renderedChunks, chunkColors, chunkBiomeColors,
                        monumentChunks, endCityChunks,
                        finalMinCx, finalMaxCx, finalMinCz, finalMaxCz);
                decoded += curSubs.size();
            }
            Log.i(TAG, "流式第二遍: 渲染 chunk 数=" + renderedChunks.size()
                    + " 解码 subchunk=" + decoded);
            heightEntries = null;
            reader.close();
            // monumentChunks 值区分结构类型：1 海底神殿 / 2 沙漠神殿 / 3 前哨站
            Map<Long, Integer> temples = new HashMap<>();
            Map<Long, Integer> outposts = new HashMap<>();
            for (Map.Entry<Long, Integer> e : monumentChunks.entrySet()) {
                if (e.getValue() == 2) {
                    temples.put(e.getKey(), 1);
                } else if (e.getValue() == 3) {
                    outposts.put(e.getKey(), 1);
                }
            }
            monumentChunks.entrySet().removeIf(e -> e.getValue() != 1);
            clusterStructureChunks(monumentChunks, "ocean_monument", detected);
            clusterStructureChunks(endCityChunks, "end_city", detected);
            clusterStructureChunks(temples, "desert_temple", detected);
            clusterStructureChunks(outposts, "outpost", detected);
            if (!detected.isEmpty()) {
                Log.i(TAG, "流式结构检测: " + detected.size() + " 个");
            }
        } catch (Exception e) {
            Log.w(TAG, "流式渲染失败", e);
            return null;
        }
        if (renderedChunks.isEmpty()) {
            Log.i(TAG, "卫星模式失败: 无高度图 chunk (维度=" + dimension + ")");
            return null;
        }
        int spanX = finalMaxCx[0] - finalMinCx[0] + 1;
        int spanZ = finalMaxCz[0] - finalMinCz[0] + 1;
        WorldMap map = new WorldMap(finalMinCx[0] * 16, finalMinCz[0] * 16,
                spanX * 16, spanZ * 16, null, null);
        map.chunkColors = chunkColors;
        map.chunkBiomeColors = chunkBiomeColors;
        map.detectedStructures = detected;
        map.blockScale = 1;
        Log.i(TAG, "流式完成: chunk 缓存=" + chunkColors.size()
                + " 范围=(" + finalMinCx[0] + "," + finalMinCz[0] + ")-("
                + finalMaxCx[0] + "," + finalMaxCz[0] + ")");
        return map;
    }

    /** 渲染单个 chunk 的 16×16 表面色到 chunk 缓存（渲染后数据即可释放）。 */
    private static void renderChunkToCache(int cx, int cz, int[] hmap, byte[] biomes,
                                           Map<Integer, SubChunk> subs, int dimension,
                                           java.util.Set<Long> renderedChunks,
                                           Map<Long, int[]> chunkColors,
                                           Map<Long, int[]> chunkBiomeColors,
                                           Map<Long, Integer> monumentChunks,
                                           Map<Long, Integer> endCityChunks,
                                           int[] finalMinCx, int[] finalMaxCx,
                                           int[] finalMinCz, int[] finalMaxCz) {
        long key = pack(cx, cz);
        if (!renderedChunks.add(key)) {
            return; // 旧文件里的过期数据（新版本已渲染）
        }
        // palette 结构特征（1.26 无 HSA 记录）——chunk 级一次判定：
        // 海底神殿 sea_lantern+prismarine / 末地城 purpur+end_stone_bricks /
        // 沙漠神殿 chiseled_sandstone / 掠夺者前哨站 dark_oak+stone
        if (monumentChunks != null) {
            boolean lantern = false;
            boolean prismarine = false;
            boolean purpur = false;
            boolean endBricks = false;
            boolean chiseledSandstone = false;
            boolean orangeTerracotta = false;
            boolean blueTerracotta = false;
            boolean tnt = false;
            boolean cutSandstone = false;
            boolean darkOak = false;
            boolean darkOakLog = false;
            boolean stone = false;
            boolean mossy = false;
            for (SubChunk sc : subs.values()) {
                for (String pn : sc.palette) {
                    if (pn == null) {
                        continue;
                    }
                    if (pn.contains("sea_lantern")) {
                        lantern = true;
                    } else if (pn.contains("prismarine")) {
                        prismarine = true;
                    } else if (pn.contains("purpur")) {
                        purpur = true;
                    } else if (pn.contains("end_stone_bricks")) {
                        endBricks = true;
                    } else if (pn.contains("chiseled_sandstone")) {
                        chiseledSandstone = true;
                    } else if (pn.contains("cut_sandstone")) {
                        cutSandstone = true;
                    } else if (pn.contains("orange_terracotta")) {
                        orangeTerracotta = true;
                    } else if (pn.contains("blue_terracotta")) {
                        blueTerracotta = true;
                    } else if (pn.contains("tnt")) {
                        tnt = true;
                    } else if (pn.contains("dark_oak_planks")) {
                        darkOak = true;
                    } else if (pn.contains("dark_oak_log")) {
                        darkOakLog = true;
                    } else if (pn.contains("mossy_cobblestone")) {
                        mossy = true; // 必须在 cobblestone 之前：子串会吞掉
                    } else if (pn.contains("cobblestone")) {
                        stone = true;
                    }
                }
            }
            if (lantern && prismarine) {
                monumentChunks.put(key, 1);
            }
            if (dimension == DIM_END && purpur && endBricks) {
                endCityChunks.put(key, 1);
            }
            if (dimension == DIM_OVERWORLD && (chiseledSandstone
                    || (orangeTerracotta && blueTerracotta && tnt))) {
                monumentChunks.put(key, 2); // 复用 map：value 2 = 沙漠神殿（特征收紧，与按需路径一致）
            }
            if (dimension == DIM_OVERWORLD && darkOak && darkOakLog && stone && mossy) {
                monumentChunks.put(key, 3); // value 3 = 掠夺者前哨站
            }
        }
        int[] colors = new int[256];
        int[] biomeCols = new int[256];
        boolean hasAny = false;
        for (int i = 0; i < 256; i++) {
            int lx = i & 15;
            int lz = i >> 4;
            int h = hmap[i];
            int color = surfaceColor(h, lx, lz, subs, biomes, dimension);
            // BTR 坡度阴影（樱花树冠/山地的立体感来源；chunk 边界取自身高度）
            color = applyShading(color, hmap, i);
            colors[i] = color;
            if ((color & 0xFF000000) != 0) {
                hasAny = true;
            }
            if (biomes != null) {
                biomeCols[i] = biomeGrassColor(biomes[i] & 0xFF);
            }
        }
        if (!hasAny) {
            return; // 全透明（无高度数据）不占缓存
        }
        chunkColors.put(key, colors);
        if (biomes != null) {
            chunkBiomeColors.put(key, biomeCols);
        }
        finalMinCx[0] = Math.min(finalMinCx[0], cx);
        finalMaxCx[0] = Math.max(finalMaxCx[0], cx);
        finalMinCz[0] = Math.min(finalMinCz[0], cz);
        finalMaxCz[0] = Math.max(finalMaxCz[0], cz);
    }

    // ---------------------------------------------------------------- chunk 缓存磁盘持久化

    private static final int MAP_CACHE_MAGIC = 0x4D435632; // "MCv2"
    // v3：v301 readChunk 多块读取 + v304 地表窗口修复前渲染的缓存数据是错的
    // （subchunk 缺失/地表层被裁），必须失效重渲染——村庄/建筑错乱的直接来源
    // v4：缓存加入 biome 图层色（v3 只存地形色，缓存命中后 biome 图层永远没数据）
    private static final int MAP_CACHE_VERSION = 4;

    /** 缓存文件：db 目录旁 map_cache_<dim>.bin（随世界走，卸载备份都在）。 */
    private static File chunkCacheFile(File dbDir, int dimension) {
        return new File(dbDir.getParentFile(), "map_cache_" + dimension + ".bin");
    }

    /** db 指纹：文件总大小 + 最新修改时间（变了就失效重渲染）。 */
    private static long[] dbFingerprint(File dbDir) {
        long total = 0;
        long latest = 0;
        File[] files = dbDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    total += f.length();
                    latest = Math.max(latest, f.lastModified());
                }
            }
        }
        return new long[]{total, latest};
    }

    /**
     * 保存 chunk 色缓存到磁盘（BTR 式"打开不重渲染"：下次进入直接读缓存秒开）。
     * 格式：magic/version/chunkCount/minCx/minCz/maxCx/maxCz/dbSize/dbMtime
     * + 每 chunk(cx,cz,256×ARGB)。155MB 世界缓存文件 ≈ 25MB，写入 <1s。
     */
    public static boolean saveChunkCache(WorldMap map, File dbDir, int dimension) {
        if (map == null || map.chunkColors == null || map.chunkColors.isEmpty()) {
            return false;
        }
        File out = chunkCacheFile(dbDir, dimension);
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
            long[] fp = dbFingerprint(dbDir);
            int minCx = map.minBlockX / 16;
            int minCz = map.minBlockZ / 16;
            int maxCx = minCx + map.width / 16 - 1;
            int maxCz = minCz + map.height / 16 - 1;
            dos.writeInt(MAP_CACHE_MAGIC);
            dos.writeInt(MAP_CACHE_VERSION);
            dos.writeInt(map.chunkColors.size());
            dos.writeInt(minCx);
            dos.writeInt(minCz);
            dos.writeInt(maxCx);
            dos.writeInt(maxCz);
            dos.writeLong(fp[0]);
            dos.writeLong(fp[1]);
            boolean hasBiome = map.chunkBiomeColors != null && !map.chunkBiomeColors.isEmpty();
            dos.writeBoolean(hasBiome);
            for (Map.Entry<Long, int[]> e : map.chunkColors.entrySet()) {
                dos.writeInt(unpackX(e.getKey()));
                dos.writeInt(unpackZ(e.getKey()));
                int[] cc = e.getValue();
                for (int i = 0; i < 256; i++) {
                    dos.writeInt(cc[i]);
                }
                int[] bc = hasBiome ? map.chunkBiomeColors.get(e.getKey()) : null;
                for (int i = 0; i < 256; i++) {
                    dos.writeInt(bc != null ? bc[i] : 0);
                }
            }
            Log.i(TAG, "chunk 缓存已保存: " + out.getName() + " "
                    + (out.length() / 1024 / 1024) + "MB");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "chunk 缓存保存失败", e);
            return false;
        }
    }

    /** 加载磁盘 chunk 缓存；db 指纹不匹配（世界改过）返回 null 走全量渲染。 */
    public static WorldMap loadChunkCache(File dbDir, int dimension) {
        File in = chunkCacheFile(dbDir, dimension);
        if (!in.isFile()) {
            return null;
        }
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(in)))) {
            if (dis.readInt() != MAP_CACHE_MAGIC || dis.readInt() != MAP_CACHE_VERSION) {
                return null;
            }
            int count = dis.readInt();
            if (count < 1 || count > 10_000_000) {
                return null;
            }
            int minCx = dis.readInt();
            int minCz = dis.readInt();
            int maxCx = dis.readInt();
            int maxCz = dis.readInt();
            long dbSize = dis.readLong();
            long dbMtime = dis.readLong();
            long[] fp = dbFingerprint(dbDir);
            if (fp[0] != dbSize || fp[1] != dbMtime) {
                Log.i(TAG, "chunk 缓存失效（db 已变化），重新渲染");
                return null;
            }
            Map<Long, int[]> chunkColors = new java.util.concurrent.ConcurrentHashMap<>(count * 2);
            boolean hasBiome = false;
            try {
                hasBiome = dis.readBoolean();
            } catch (Exception e) {
                hasBiome = false; // 兼容异常情况
            }
            Map<Long, int[]> chunkBiomeColors = hasBiome
                    ? new java.util.concurrent.ConcurrentHashMap<>(count * 2) : null;
            for (int i = 0; i < count; i++) {
                int cx = dis.readInt();
                int cz = dis.readInt();
                int[] cc = new int[256];
                for (int j = 0; j < 256; j++) {
                    cc[j] = dis.readInt();
                }
                chunkColors.put(pack(cx, cz), cc);
                if (chunkBiomeColors != null) {
                    int[] bc = new int[256];
                    for (int j = 0; j < 256; j++) {
                        bc[j] = dis.readInt();
                    }
                    chunkBiomeColors.put(pack(cx, cz), bc);
                }
            }
            WorldMap map = new WorldMap(minCx * 16, minCz * 16,
                    (maxCx - minCx + 1) * 16, (maxCz - minCz + 1) * 16, null, null);
            map.chunkColors = chunkColors;
            map.chunkBiomeColors = chunkBiomeColors;
            map.blockScale = 1;
            Log.i(TAG, "chunk 缓存已加载: " + count + " chunk (biome=" + hasBiome + ")");
            return map;
        } catch (Exception e) {
            Log.w(TAG, "chunk 缓存加载失败", e);
            return null;
        }
    }

    /** 世界范围小文件：首次 readKeys 后缓存，之后打开免扫描。 */
    private static File boundsFile(File dbDir, int dimension) {
        return new File(dbDir.getParentFile(), "map_bounds_" + dimension + ".bin");
    }

    /**
     * BTR 式快速进入：只拿世界范围（chunk 边界）建空地图。
     * 有 bounds 缓存文件直接读（毫秒）；无则 readKeys 扫一遍 subchunk
     * key（155MB 世界约 5 秒）并保存。渲染交给视口按需。
     */
    public static WorldMap buildBoundsOnly(File dbDir, int dimension) {
        File bf = boundsFile(dbDir, dimension);
        try {
            int minCx;
            int maxCx;
            int minCz;
            int maxCz;
            if (bf.isFile()) {
                try (java.io.DataInputStream dis = new java.io.DataInputStream(
                        new java.io.FileInputStream(bf))) {
                    minCx = dis.readInt();
                    maxCx = dis.readInt();
                    minCz = dis.readInt();
                    maxCz = dis.readInt();
                }
                Log.i(TAG, "bounds 缓存加载: (" + minCx + "," + minCz + ")-(" + maxCx + "," + maxCz + ")");
            } else {
                LevelDBReader reader = new LevelDBReader(dbDir);
                List<byte[]> subKeys = reader.readKeys(k -> {
                    int[] ck = parseChunkKey(k);
                    return ck != null && ck[2] == dimension && isSubchunkKey(k);
                });
                reader.close();
                minCx = Integer.MAX_VALUE;
                maxCx = Integer.MIN_VALUE;
                minCz = Integer.MAX_VALUE;
                maxCz = Integer.MIN_VALUE;
                for (byte[] k : subKeys) {
                    int[] ck = parseChunkKey(k);
                    if (ck == null) {
                        continue;
                    }
                    minCx = Math.min(minCx, ck[0]);
                    maxCx = Math.max(maxCx, ck[0]);
                    minCz = Math.min(minCz, ck[1]);
                    maxCz = Math.max(maxCz, ck[1]);
                }
                if (minCx == Integer.MAX_VALUE) {
                    return null;
                }
                try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                        new java.io.FileOutputStream(bf))) {
                    dos.writeInt(minCx);
                    dos.writeInt(maxCx);
                    dos.writeInt(minCz);
                    dos.writeInt(maxCz);
                }
                Log.i(TAG, "bounds 已扫描保存: (" + minCx + "," + minCz + ")-(" + maxCx + "," + maxCz + ")");
            }
            WorldMap map = new WorldMap(minCx * 16, minCz * 16,
                    (maxCx - minCx + 1) * 16, (maxCz - minCz + 1) * 16, null, null);
            map.chunkColors = new java.util.concurrent.ConcurrentHashMap<>();
            map.chunkBiomeColors = new java.util.concurrent.ConcurrentHashMap<>();
            map.blockScale = 1;
            return map;
        } catch (Exception e) {
            Log.w(TAG, "buildBoundsOnly 失败", e);
            return null;
        }
    }

    /**
     * 视口按需渲染（BTR 式）：用 index block 定位只读一个 chunk 的数据，
     * 渲染 16×16 表面色返回。玩家滑动到新区域时逐 chunk 增量渲染，
     * 打开地图不再等待全量渲染。
     */
    public static int[][] renderChunkOnDemand(File dbDir, int cx, int cz, int dimension) {
        try {
            LevelDBReader reader = new LevelDBReader(dbDir);
            try {
                return renderChunkOnDemand(reader, cx, cz, dimension);
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "renderChunkOnDemand 失败 (" + cx + "," + cz + ")", t);
            return null;
        }
    }

    /** 复用 reader 版本（渲染线程 ThreadLocal 持有——每 chunk 新建 reader
     *  要重开全部 sst 文件，是拖动跟不上渲染的主因）。 */
    public static int[][] renderChunkOnDemand(LevelDBReader reader, int cx, int cz,
                                              int dimension) {
        try {
            List<LevelDBEntry> entries = reader.readChunk(cx, cz);
            int[] hmap = null;
            byte[] biomes = null;
            Map<Integer, SubChunk> subs = new HashMap<>();
            for (LevelDBEntry e : entries) {
                byte[] rawKey = e.getKey().getRawKey();
                int[] ck = parseChunkKey(rawKey);
                if (ck == null || ck[2] != dimension) {
                    continue;
                }
                if (isSubchunkKey(rawKey)) {
                    SubChunk sc = decodeSubChunk(e.getValue());
                    if (sc != null) {
                        subs.put(ck[3], sc);
                    }
                } else if (isData2dKey(rawKey)) {
                    int[] hm = extractData2d(e.getValue());
                    if (hm != null && hmap == null) {
                        hmap = hm;
                        int type = (rawKey.length == 13 ? rawKey[12] : rawKey[8]) & 0xFF;
                        if (type == KEY_TYPE_DATA_2D) {
                            byte[] bm = extractBiomes2d(e.getValue());
                            if (bm != null) {
                                biomes = bm;
                            }
                        } else {
                            byte[] bm = extractBiomes3d(e.getValue(), hm);
                            if (bm != null) {
                                biomes = bm;
                            }
                        }
                    }
                } else if (rawKey.length == 13 && ck[3] < 0) {
                    int[] hm = extractChunkHeightMap256(parseChunkNbt(e.getValue()));
                    if (hm != null && hmap == null) {
                        hmap = hm;
                    }
                }
            }
            if (hmap == null) {
                return null; // 无高度数据（未生成 chunk）
            }
            // 结构特征检测（视口按需渲染路径同样要做——主世界大世界
            // 不走流式渲染，沙漠神殿/前哨站没有专门 key 只能靠 palette）
            detectOnDemandStructure(cx, cz, subs, dimension);
            // 窗口裁剪（下界全留）：以高度图推算的地表层为中心，向下 2 层
            // （河床/海底）到实际最高 sub（树冠/建筑）。之前用「实际最高 sub ±2」
            // ——树/建筑让 maxSub 偏离地表，地表层被裁掉，海洋/平原 chunk
            // 回退 biome 色显示成大片水蓝（"y 轴高度错乱"根因）
            if (dimension == DIM_NETHER) {
                // 下界：默认全量（sub 0-7 每层都有方块，窗口裁剪会漏熔岩海/洞穴）；
                // 设置页可选 y 范围加速
                applyNetherWindow(subs);
            } else if (!subs.isEmpty()) {
                int maxH = 0;
                for (int h : hmap) {
                    if (h > maxH) {
                        maxH = h;
                    }
                }
                int surfaceSub = Math.floorDiv(maxH - 1, 16);
                int maxSub = Integer.MIN_VALUE;
                for (int s : subs.keySet()) {
                    maxSub = Math.max(maxSub, s);
                }
                final int fMin = surfaceSub - 2;
                final int fMax = Math.max(surfaceSub + 2, maxSub);
                subs.keySet().removeIf(s -> s < fMin || s > fMax);
            }
            int[] colors = new int[256];
            int[] biomeCols = biomes != null ? new int[256] : null;
            for (int i = 0; i < 256; i++) {
                int lx = i & 15;
                int lz = i >> 4;
                int c = surfaceColor(hmap[i], lx, lz, subs, biomes, dimension);
                colors[i] = applyShading(c, hmap, i);
                if (biomeCols != null) {
                    biomeCols[i] = biomeGrassColor(biomes[i] & 0xFF);
                }
            }
            // [0]=卫星色 [1]=biome 图层色（大世界按需渲染此前不生成
            // biome 数据——biome 图层打开后无内容显示的根因）
            return new int[][]{colors, biomeCols};
        } catch (Exception e) {
            Log.w(TAG, "按需渲染 chunk(" + cx + "," + cz + ") 失败", e);
            return null;
        }
    }

    /** 大世界流式实体解析：只读实体/玩家相关 key。 */
    public static List<EntityPos> parseEntitiesStreaming(File dbDir, int dimension) {
        try {
            LevelDBReader reader = new LevelDBReader(dbDir);
            List<LevelDBEntry> entries = reader.readEntries(k -> {
                // 19B actorprefix 实体 key / 16B digp / 玩家字符串 key
                if (k != null && k.length == 19 && k[0] == 'a') {
                    return true;
                }
                if (k != null && k.length == 16 && k[0] == 'd' && k[1] == 'i') {
                    return true;
                }
                if (k != null && k.length >= 8) {
                    boolean printable = true;
                    for (byte b : k) {
                        if (b < 32 || b > 126) {
                            printable = false;
                            break;
                        }
                    }
                    if (printable) {
                        String s = new String(k, java.nio.charset.StandardCharsets.US_ASCII);
                        return s.startsWith("player") || s.startsWith("~local_player");
                    }
                }
                return false;
            });
            reader.close();
            return parseEntities(entries, dimension);
        } catch (Exception e) {
            Log.w(TAG, "流式实体解析失败", e);
            return new ArrayList<>();
        }
    }

    /** 大世界流式结构解析：方块实体/HSA/村庄等非 subchunk 结构 key。 */
    private static final java.util.List<String> strKeyDiag = new java.util.ArrayList<>();
    public static List<StructureMarker> parseStructureMarkersStreaming(File dbDir, int dimension) {
        try {
            LevelDBReader reader = new LevelDBReader(dbDir);
            List<LevelDBEntry> entries = reader.readEntries(k -> {
                if (k == null) {
                    return false;
                }
                int len = k.length;
                // 9/10B：0x31 方块实体 / 0x39 HSA（老存档）；13/14B：0x30 ChunkData / 0x39
                if (len == 9 || len == 10 || len == 13 || len == 14) {
                    int type = k[len == 13 || len == 14 ? 12 : 8] & 0xFF;
                    if (type == KEY_TYPE_BLOCK_ENTITY || type == KEY_TYPE_HSA
                            || type == KEY_TYPE_CHUNK_DATA) {
                        int[] ck = parseChunkKey(k);
                        return ck != null && ck[2] == dimension;
                    }
                }
                // VILLAGE_ 字符串 key
                if (len > 20) {
                    boolean printable = true;
                    for (byte b : k) {
                        if (b < 32 || b > 126) {
                            printable = false;
                            break;
                        }
                    }
                    if (printable) {
                        String s = new String(k, java.nio.charset.StandardCharsets.US_ASCII);
                        // 诊断：收集全部长字符串 key，统计结构类 key 前缀
                        if (!s.startsWith("VILLAGE_") && !s.startsWith("player")
                                && !s.startsWith("~")) {
                            if (strKeyDiag.size() < 30) {
                                strKeyDiag.add(s.length() > 60 ? s.substring(0, 60) : s);
                            }
                            // 沙漠神殿/前哨站等结构 key 一并收下（按值解析，非结构数据
                            // 在 parseStructureMarkers 里不会产生标记）
                            return !s.startsWith("level.dat") && !s.contains("_saved");
                        }
                        return s.startsWith("VILLAGE_");
                    }
                }
                return false;
            });
            reader.close();
            if (!strKeyDiag.isEmpty()) {
                Log.i(TAG, "结构 key 诊断: " + strKeyDiag);
            }
            return parseStructureMarkers(entries, dimension);
        } catch (Exception e) {
            Log.w(TAG, "流式结构解析失败", e);
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------- 实体 / 结构标记

    /** 实体标记（实体图层：位置 + 简化标识符）。 */
    public static class EntityPos {
        public final float x;
        public final float y;
        public final float z;
        public final String name; // 简化标识符（如 zombie / villager）

        public EntityPos(float x, float y, float z, String name) {
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
        public final String type; // village / spawner / end_portal / desert_temple / outpost
        /** 结构 NBT 数据（村庄/方块实体等有 key 数据的结构；palette 特征检测的为 null）。 */
        public final String nbtDetail;

        public StructureMarker(int x, int z, String type) {
            this(x, z, type, null);
        }

        public StructureMarker(int x, int z, String type, String nbtDetail) {
            this.x = x;
            this.z = z;
            this.type = type;
            this.nbtDetail = nbtDetail;
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
            // 字符串 key：VILLAGE_<维度>_<uuid>_INFO（1.18+ 村庄标记；实测 1.26 HSA 已废弃）
            if (rawKey != null && rawKey.length > 20) {
                String skey = safeStringKey(rawKey);
                if (skey != null && skey.startsWith("VILLAGE_") && skey.endsWith("_INFO")) {
                    int villageDim = skey.contains("Nether") ? DIM_NETHER
                            : skey.contains("End") ? DIM_END : DIM_OVERWORLD;
                    if (villageDim == dimension) {
                        parseVillageInfo(entry.getValue(), out);
                    }
                    continue;
                }
            }
            // 0x39 HardCodedSpawnAreas：结构生成区域（官方记录，老版本存档）
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
        // 1.26 无 HSA 记录的结构：palette 特征检测（轻量——只读 palette 名字，
        // 不拷贝方块数据区，避免内存爆炸）。
        // 海底神殿：sea_lantern + prismarine 聚块（实测 74Q 世界 3×3 chunk 聚块）。
        // 末地城：purpur_block + end_stone_bricks（仅末地维度，排除主世界玩家建筑误报）。
        Map<Long, Integer> monumentChunks = new HashMap<>();
        Map<Long, Integer> endCityChunks = new HashMap<>();
        for (LevelDBEntry entry : entries) {
            byte[] rawKey = entry.getKey().getRawKey();
            int[] ck = parseChunkKey(rawKey);
            if (ck == null || ck[3] < 0 || ck[2] != dimension || !isSubchunkKey(rawKey)) {
                continue;
            }
            String[] pal = scanPaletteNames(entry.getValue());
            if (pal == null) {
                continue;
            }
            boolean lantern = false;
            boolean prismarine = false;
            boolean purpur = false;
            boolean endBricks = false;
            for (String pn : pal) {
                if (pn == null) {
                    continue;
                }
                if (pn.contains("sea_lantern")) {
                    lantern = true;
                } else if (pn.contains("prismarine")) {
                    prismarine = true;
                } else if (pn.contains("purpur")) {
                    purpur = true;
                } else if (pn.contains("end_stone_bricks")) {
                    endBricks = true;
                }
            }
            long key = pack(ck[0], ck[1]);
            if (lantern && prismarine) {
                monumentChunks.put(key, 1);
            }
            if (dimension == DIM_END && purpur && endBricks) {
                endCityChunks.put(key, 1);
            }
        }
        clusterStructureChunks(monumentChunks, "ocean_monument", out);
        clusterStructureChunks(endCityChunks, "end_city", out);

        Map<String, Integer> typeDist = new HashMap<>();
        for (StructureMarker m : out) {
            typeDist.merge(m.type, 1, Integer::sum);
        }
        Log.i(TAG, "结构标记解析完成: 维度=" + dimension + ", 结构数=" + out.size()
                + ", 方块实体数=" + beKeys + ", 类型分布=" + typeDist);
        return out;
    }

    /** 字符串 key 判断（全可打印 ASCII 且长度合理）。 */
    private static String safeStringKey(byte[] rawKey) {
        for (byte b : rawKey) {
            if (b < 32 || b > 126) {
                return null;
            }
        }
        return new String(rawKey, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** NBT 标签 → 简短文本（结构详情弹窗用，最多 3 层/60 项）。 */
    private static String tagToText(NbtTag t) {
        if (t == null) {
            return "null";
        }
        switch (t.getType()) {
            case NbtTag.TAG_STRING: return "\"" + t.getString() + "\"";
            case NbtTag.TAG_INT: return String.valueOf(t.getInt());
            case NbtTag.TAG_LONG: return String.valueOf(t.getLong());
            case NbtTag.TAG_FLOAT: return String.valueOf(t.getFloat());
            case NbtTag.TAG_DOUBLE: return String.valueOf(t.getDouble());
            case NbtTag.TAG_BYTE: return String.valueOf(t.getByte());
            case NbtTag.TAG_SHORT: return String.valueOf(t.getShort());
            case NbtTag.TAG_LIST: {
                java.util.List<NbtTag> l = t.getList();
                StringBuilder sb = new StringBuilder("[");
                int n = Math.min(8, l != null ? l.size() : 0);
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(tagToText(l.get(i)));
                }
                if (l != null && l.size() > n) sb.append(", ...");
                return sb.append(']').toString();
            }
            case NbtTag.TAG_COMPOUND: return compoundToText(t);
            default: return "?";
        }
    }

    /** compound → "key=val, ..." 文本（结构详情弹窗）。 */
    private static String compoundToText(NbtTag c) {
        if (c == null || c.getType() != NbtTag.TAG_COMPOUND || c.getCompound() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (java.util.Map.Entry<String, NbtTag> e : c.getCompound().entrySet()) {
            if (n++ >= 12) {
                sb.append("\n...");
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append(" = ").append(tagToText(e.getValue()));
        }
        return sb.toString();
    }

    /** VILLAGE_*_INFO NBT：X0/X1/Z0/Z1 边界 → 村庄中心标记。 */
    private static void parseVillageInfo(byte[] value, List<StructureMarker> out) {
        NbtTag root = parseCompoundWithFallback(value);
        if (root == null || root.getType() != NbtTag.TAG_COMPOUND) {
            return;
        }
        NbtTag x0t = root.getTag("X0");
        NbtTag x1t = root.getTag("X1");
        NbtTag z0t = root.getTag("Z0");
        NbtTag z1t = root.getTag("Z1");
        if (x0t == null || x1t == null || z0t == null || z1t == null) {
            return;
        }
        int cx = (x0t.getInt() + x1t.getInt()) / 2;
        int cz = (z0t.getInt() + z1t.getInt()) / 2;
        // NBT 详情：边界 + 关键字段（供结构标点点击弹窗展示）
        StringBuilder detail = new StringBuilder();
        detail.append("边界 X0=").append(x0t.getInt()).append(" X1=").append(x1t.getInt())
                .append(" Z0=").append(z0t.getInt()).append(" Z1=").append(z1t.getInt());
        for (String k : new String[]{"DWELLERS", "Tick", "Population", "Faction"}) {
            NbtTag t = root.getTag(k);
            if (t != null) {
                detail.append('\n').append(k).append('=').append(tagToText(t));
            }
        }
        out.add(new StructureMarker(cx, cz, "village", detail.toString()));
    }

    /** 轻量读取 subchunk 全部 storage 的 palette 名字（不解码方块数据区）。 */
    private static String[] scanPaletteNames(byte[] value) {
        if (value == null || value.length < 5 || (value[0] & 0xFF) != 9) {
            return null;
        }
        int p = 3;
        int count = value[1] & 0xFF;
        if (count < 1 || count > 2) {
            return null;
        }
        List<String> names = new ArrayList<>();
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
            int ps = p + dataBytes; // 跳过数据区（不拷贝）
            int paletteSize = readIntLE(value, ps);
            if (paletteSize < 0 || paletteSize > 4096) {
                return null;
            }
            int pe = ps + 4;
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
                String name = extractPaletteName(value, pe, type);
                pe = skipNbtPayload(value, pe, type);
                if (pe < 0) {
                    return null;
                }
                if (name != null) {
                    names.add(name);
                }
            }
            p = pe;
        }
        return names.toArray(new String[0]);
    }

    /** 视口按需渲染路径检测到的结构标记（供图层合并；相邻 chunk 聚合成一个）。 */
    private static final java.util.List<StructureMarker> onDemandStructures =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Set<Long> onDemandStructureChunks =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 按需渲染 chunk 的结构特征检测：沙漠神殿 chiseled_sandstone /
     *  前哨站 dark_oak+cobblestone；相邻特征 chunk 合并为一个标记。 */
    private static void detectOnDemandStructure(int cx, int cz,
                                                Map<Integer, SubChunk> subs, int dimension) {
        if (dimension != DIM_OVERWORLD || subs.isEmpty()) {
            return;
        }
        long key = pack(cx, cz);
        if (!onDemandStructureChunks.add(key)) {
            return; // 已判定过
        }
        boolean chiseledSandstone = false;
        boolean cutSandstone = false;
        boolean blueTerracotta = false;
        boolean tnt = false;
        boolean orangeTerracotta = false;
        boolean darkOak = false;
        boolean darkOakLog = false;
        boolean stone = false;
        boolean mossy = false;
        for (SubChunk sc : subs.values()) {
            for (String pn : sc.palette) {
                if (pn == null) {
                    continue;
                }
                if (pn.contains("chiseled_sandstone")) {
                    chiseledSandstone = true;
                } else if (pn.contains("cut_sandstone")) {
                    cutSandstone = true;
                } else if (pn.contains("orange_terracotta")) {
                    orangeTerracotta = true;
                } else if (pn.contains("blue_terracotta")) {
                    blueTerracotta = true;
                } else if (pn.contains("tnt")) {
                    tnt = true;
                } else if (pn.contains("dark_oak_planks")) {
                    darkOak = true;
                } else if (pn.contains("dark_oak_log")) {
                    darkOakLog = true;
                } else if (pn.contains("mossy_cobblestone")) {
                    mossy = true; // 必须在 cobblestone 之前：子串会吞掉
                } else if (pn.contains("cobblestone")) {
                    stone = true;
                }
            }
        }
        // 结构特征收紧（1.26 无结构 key，只能视觉方案）：
        // 沙漠神殿 = 錾制砂岩 或 陶瓦环+TNT 陷阱三者同时（单 TNT/陶瓦对
        // 误判玩家建筑——出生点附近 TNT 误判的根因）
        // 前哨站 = 深橡木板+原木+圆石+苔石（玩家生电房常见前三者组合）
        String type = (chiseledSandstone || (orangeTerracotta && blueTerracotta && tnt))
                ? "desert_temple"
                : darkOak && darkOakLog && stone && mossy ? "outpost" : null;
        if (type == null) {
            return;
        }
        synchronized (onDemandStructures) {
            // 与相邻已有标记合并（同类型、chunk 距离 ≤2）
            for (StructureMarker m : onDemandStructures) {
                if (m.type.equals(type)
                        && Math.abs(Math.floorDiv(m.x, 16) - cx) <= 2
                        && Math.abs(Math.floorDiv(m.z, 16) - cz) <= 2) {
                    return; // 已在附近标记过
                }
            }
            onDemandStructures.add(new StructureMarker(cx * 16 + 8, cz * 16 + 8, type));
            Log.i(TAG, "按需结构检测: " + type + " @ chunk(" + cx + "," + cz + ")");
        }
    }

    /** 取按需渲染检测到的结构标记（调用方合并进结构图层）。 */
    public static List<StructureMarker> takeOnDemandStructures() {
        synchronized (onDemandStructures) {
            return new ArrayList<>(onDemandStructures);
        }
    }

    /** 相邻 chunk 聚块：每个连通分量输出一个结构标记（块中心）。 */
    private static void clusterStructureChunks(Map<Long, Integer> chunks, String type,
                                               List<StructureMarker> out) {
        Set<Long> visited = new HashSet<>();
        for (Long start : chunks.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            // BFS 收集连通分量
            List<Long> comp = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                comp.add(cur);
                int cx = unpackX(cur);
                int cz = unpackZ(cur);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        long nb = pack(cx + dx, cz + dz);
                        if (chunks.containsKey(nb) && !visited.contains(nb)) {
                            visited.add(nb);
                            queue.add(nb);
                        }
                    }
                }
            }
            // 分量中心（block 坐标）
            long sumX = 0;
            long sumZ = 0;
            for (Long c : comp) {
                sumX += unpackX(c) * 16L + 8;
                sumZ += unpackZ(c) * 16L + 8;
            }
            out.add(new StructureMarker((int) (sumX / comp.size()),
                    (int) (sumZ / comp.size()), type));
        }
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
            // 方块实体 NBT 详情（刷怪笼的 SpawnData/末地门的坐标等）
            out.add(new StructureMarker(xTag.getInt(), zTag.getInt(), type,
                    compoundToText(be)));
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
        // 只对"灰度模板"乘群系色调（原版 MC 着色器机制：贴图是灰度模板才被
        // 群系色调染色）。成品色方块（seagrass 50,126,8 / kelp 86,130,42 /
        // grass_path 148,121,65 等色表里已带真实色）乘 tint 会变暗发黑——
        // 水中"发黑"色块、草径变深绿的根因。bedrockmap 无此判断（其色表里
        // 被 tint 的方块恰好都是灰模板），这里按原版观感修正。
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        boolean grayTemplate = Math.abs(r - g) < 14 && Math.abs(g - b) < 14;
        if (!grayTemplate) {
            return color; // 成品色直接返回
        }
        int[] tint = biomeTintTable.get(biomeId);
        // bedrockmap color.cpp classify_tint 优先级：water → leave → grass，
        // 并补上 bedrockmap 缺失的灰度模板：fern/vine（草类）、leaf_litter（leaf）。
        // tint 缺失 key（-1 标记）时回退默认色
        if (name.contains("water")) {
            return tint != null && tint[9] >= 0 ? multiplyTint(color, tint, 9)
                    : multiplyTint(color, DEFAULT_WATER_TINT, 0);
        }
        if (name.contains("leave") || name.contains("leaf")) {
            return tint != null && tint[6] >= 0 ? multiplyTint(color, tint, 6)
                    : multiplyTint(color, DEFAULT_LEAVES_TINT, 0);
        }
        if (name.contains("grass") || name.contains("fern") || name.contains("vine")) {
            return tint != null && tint[3] >= 0 ? multiplyTint(color, tint, 3)
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
        // 1.26 硫磺洞穴新方块（bedrock-level 色表未收录，按 wiki map color/贴图观感补色）
        if (name.equals("minecraft:sulfur")) return 0xFFD8C442;                 // 硫磺：明黄
        if (name.equals("minecraft:potent_sulfur")) return 0xFFECc828;          // 强硫磺：亮黄
        if (name.equals("minecraft:sulfur_spike")) return 0xFFC4AA2E;           // 硫磺尖刺：暗黄
        if (name.equals("minecraft:cinnabar")) return 0xFF993333;               // 辰砂：朱红（wiki map color）
        if (name.equals("minecraft:iron_chain")) return 0xFF525256;             // 铁链：铁灰
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
    /** 海洋/河流类 biome（无 grass/water tint 条目，biome 图层用默认水色）。 */
    /** 雪地类 biome（无 grass tint：地表被雪覆盖，biome 图层用雪白）。 */
    private static boolean isSnowBiome(int biomeId) {
        switch (biomeId) {
            case 10:  // legacy_frozen_ocean
            case 11:  // frozen_river
            case 12:  // ice_plains
            case 13:  // ice_mountains
            case 26:  // cold_beach
            case 30:  // cold_taiga
            case 46:  // frozen_ocean
            case 47:  // deep_frozen_ocean
            case 140: // ice_plains_spikes
            case 183: // snow_capped_peaks
            case 184: // snowy_slopes
                return true;
            default: return false;
        }
    }

    /** 末地类 biome（the_end；1.26 末地 chunk 实测全部 biome id 9）。 */
    private static boolean isEndBiome(int biomeId) {
        return biomeId == 9;
    }

    private static boolean isWaterBiome(int biomeId) {
        switch (biomeId) {
            case 0: case 7: case 10: case 11: case 24:
            case 40: case 41: case 42: case 43: case 44: case 45: case 46: case 47:
                return true;
            default: return false;
        }
    }

    private static int biomeGrassColor(int biomeId) {
        int[] tint = biomeTintTable.get(biomeId);
        if (tint != null) {
            // biome 图层主色：雪地 → grass → water → （海洋类默认水色/陆地类默认草色）。
            // 不能用 rgb——river rgb=[0,0,255] 纯蓝、ocean rgb=[0,0,112] 深蓝近黑，
            // 实测 TK 大世界 (285,-26) 海洋显示成黑色大方块、河岸显示纯蓝的根因。
            // 雪地必须最先判断：ice_plains 等色表里也有 grass tint[128,180,151]，
            // grass 分支先命中会把雪原染成绿色（tju 雪地刷绿的根因）
            if (isSnowBiome(biomeId)) {
                return 0xFFE8EEF6;
            }
            if (isEndBiome(biomeId)) {
                // 末地类 biome：the_end 色表只有 rgb 无 grass/water，
                // 回退默认草绿 → 末地城附近区块全绿（TK 末地 1417 chunk 全 biome 9）
                return 0xFFD8DFA8; // 末地石浅黄
            }
            if (tint[3] >= 0) {
                return 0xFF000000 | (tint[3] << 16) | (tint[4] << 8) | tint[5];
            }
            if (tint[6] >= 0) {
                return 0xFF000000 | (tint[6] << 16) | (tint[7] << 8) | tint[8];
            }
            if (isWaterBiome(biomeId)) {
                return 0xFF000000 | (DEFAULT_WATER_TINT[0] << 16)
                        | (DEFAULT_WATER_TINT[1] << 8) | DEFAULT_WATER_TINT[2];
            }
            return 0xFF000000 | (DEFAULT_GRASS_TINT[0] << 16)
                    | (DEFAULT_GRASS_TINT[1] << 8) | DEFAULT_GRASS_TINT[2];
        }
        switch (biomeId) {
            case 0: return 0xFF2E4A9E;   // ocean（原 0xFF020070 深蓝近黑，biome 图层显示成黑块）
            case 1: return 0xFF8CB060;   // plains
            case 2: return 0xFFFB941B;   // desert
            case 3: return 0xFF5D635D;   // extreme hills
            case 4: return 0xFF1E8A3C;   // forest（提亮：老 BTR 原色 0x026320 过暗近黑）
            case 5: return 0xFF09665B;   // taiga
            case 6: return 0xFF04C88B;   // swampland
            case 7: return 0xFF3F76E4;   // river（原纯蓝 0xFF0101FF）
            case 9: return 0xFFD8DFA8;   // the_end（末地石浅黄）
            case 10: return 0xFFE8EEF6;  // frozen ocean（冰面雪白）
            case 11: return 0xFFE8EEF6;  // frozen river（冰面雪白）
            case 12: return 0xFFE8EEF6;  // ice plains（雪白）
            case 13: return 0xFFE8EEF6;  // ice mountains
            case 16: return 0xFFFADF55;  // beach
            case 21: return 0xFF527A07;  // jungle
            case 23: return 0xFF6E9A4E;  // jungle edge
            case 24: return 0xFF1E3A8F;  // deep ocean（原 0xFF02002F 近黑）
            case 25: return 0xFFA2A484;  // stone beach
            case 26: return 0xFFE8EEF6;  // cold beach（积雪滩）
            case 27: return 0xFF307546;  // birch forest
            case 29: return 0xFF425218;  // roofed forest
            case 30: return 0xFFE8EEF6;  // cold taiga（雪地针叶林）
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
            Log.i(TAG, "decodeSubChunk 失败@version: " + (value == null ? "null"
                    : value.length + " head=" + (value.length > 0 ? value[0] & 0xFF : -1)));
            return null;
        }
        int p = 3; // 版本 + storage 数 + sub 索引
        int count = value[1] & 0xFF;
        if (count < 1 || count > 2) {
            Log.i(TAG, "decodeSubChunk 失败@count=" + count);
            return null;
        }
        // bedrock-level sub_chunk 多层结构：storage 0 = 主方块层，storage 1 = 水层。
        // 只解码第一个 storage 会导致海面水方块缺失（海洋显示河床的根因）。
        SubChunk primary = null;

        for (int s = 0; s < count && p + 2 <= value.length; s++) {
            int header = value[p++] & 0xFF;
            int bits = header >> 1;
            if (bits < 1 || bits > 16) {
                if (s == 1) {
                    // 1.18+ 无水的 subchunk 水层 storage header=0（bits 非法）：
                    // 该层无数据，跳过（下界/末地/高空层常见——之前直接
                    // return null 导致整个 subchunk 解码失败，"解码 subchunk=0"）
                    break;
                }
                StringBuilder hb = new StringBuilder();
                for (int k = 0; k < Math.min(24, value.length); k++) {
                    hb.append(String.format("%02X ", value[k]));
                }
                Log.i(TAG, "decodeSubChunk 失败=" + bits + " header=" + header
                        + " len=" + value.length + " hex=" + hb);
                return null;
            }
            int blocksPerWord = 32 / bits;
            int wordCount = (4096 + blocksPerWord - 1) / blocksPerWord;
            int dataBytes = wordCount * 4;
            if (p + dataBytes + 4 > value.length) {
                Log.i(TAG, "decodeSubChunk 失败@dataBytes=" + dataBytes
                        + " p=" + p + " len=" + value.length);
                return null;
            }
            byte[] data = Arrays.copyOfRange(value, p, p + dataBytes);
            int paletteStart = p + dataBytes;
            int paletteSize = readIntLE(value, paletteStart);
            if (paletteSize < 0 || paletteSize > 4096) {
                Log.i(TAG, "decodeSubChunk 失败@paletteSize=" + paletteSize);
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
                    Log.i(TAG, "decodeSubChunk 失败@nameLen=" + nameLen + " i=" + i);
                    return null;
                }
                palette[i] = extractPaletteName(value, pe, type);
                pe = skipNbtPayload(value, pe, type);
                if (pe < 0) {
                    Log.i(TAG, "decodeSubChunk 失败@skipNbt type=" + type + " i=" + i);
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
    /**
     * 导出交互式 HTML 地图（PRD 7.3/7.4：Leaflet CRS.Simple 平面坐标系 +
     * 卫星图 base64 内嵌 + 标点 circleMarker + 联动虚线 + 距离标注，
     * 单文件双击可开）。lat=Z、lng=X。
     * points: {name,x,z,color} / links: {x1,z1,x2,z2,color}
     */
    public static File exportWorldHtml(WorldMap map, File outDir, String fileName,
                                       String title, long seed, String versionStr,
                                       int playerX, int playerZ, int spawnX, int spawnZ,
                                       java.util.List<String[]> points,
                                       java.util.List<String[]> links,
                                       java.util.List<StructureMarker> structures,
                                       java.util.List<EntityPos> entities)
            throws Exception {
        // 1) 卫星图 PNG（小世界全图方块级；大世界 chunk 每 chunk 4×4 采样 = 4 倍精度）
        Bitmap pngBmp;
        int pngW;
        if (map.colors != null) {
            int w = map.width;
            int h = map.height;
            if (w <= 0 || h <= 0 || (long) w * h > 60L * 1024 * 1024) {
                throw new IllegalStateException("地图过大: " + w + "x" + h);
            }
            pngBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            pngBmp.setPixels(map.colors, 0, w, 0, 0, w, h);
            pngW = w;
        } else if (map.chunkColors != null && !map.chunkColors.isEmpty()) {
            int minCx = Integer.MAX_VALUE;
            int maxCx = Integer.MIN_VALUE;
            int minCz = Integer.MAX_VALUE;
            int maxCz = Integer.MIN_VALUE;
            for (Long key : map.chunkColors.keySet()) {
                int cx = (int) (key >> 32);
                int cz = (int) (long) key;
                minCx = Math.min(minCx, cx);
                maxCx = Math.max(maxCx, cx);
                minCz = Math.min(minCz, cz);
                maxCz = Math.max(maxCz, cz);
            }
            int cw = maxCx - minCx + 1;
            int ch = maxCz - minCz + 1;
            long total = (long) cw * ch * 16;
            if (cw <= 0 || ch <= 0 || total > 32L * 1024 * 1024) {
                throw new IllegalStateException("chunk 范围过大: " + cw + "x" + ch);
            }
            final int SCALE = 4;
            pngW = cw * SCALE;
            int pngH = ch * SCALE;
            pngBmp = Bitmap.createBitmap(pngW, pngH, Bitmap.Config.ARGB_8888);
            for (Map.Entry<Long, int[]> e : map.chunkColors.entrySet()) {
                int cx = (int) (e.getKey() >> 32);
                int cz = (int) (long) e.getKey();
                int px = (cx - minCx) * SCALE;
                int pz = (cz - minCz) * SCALE;
                int[] tile = e.getValue();
                // 16×16 tile 按 4×4 网格采样，每格 4×4 方块取非透明均值
                for (int gy = 0; gy < SCALE; gy++) {
                    for (int gx = 0; gx < SCALE; gx++) {
                        int r = 0;
                        int g = 0;
                        int b = 0;
                        int n = 0;
                        for (int y = gy * 4; y < gy * 4 + 4; y++) {
                            int base = y * 16 + gx * 4;
                            for (int x = 0; x < 4; x++) {
                                int v = tile[base + x];
                                if ((v & 0xFF000000) != 0) {
                                    r += (v >> 16) & 0xFF;
                                    g += (v >> 8) & 0xFF;
                                    b += v & 0xFF;
                                    n++;
                                }
                            }
                        }
                        if (n > 0) {
                            pngBmp.setPixel(px + gx, pz + gy,
                                    0xFF000000 | (r / n << 16) | (g / n << 8) | (b / n));
                        }
                    }
                }
            }
        } else {
            throw new IllegalStateException("无地图数据");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pngBmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
        pngBmp.recycle();
        String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                android.util.Base64.NO_WRAP);
        bos.close();

        // 2) 世界范围（[Z,X] 顺序：lat=Z、lng=X）
        int minX = map.minBlockX;
        int minZ = map.minBlockZ;
        int maxX = minX + map.width;
        int maxZ = minZ + map.height;
        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(title)).append("</title>")
                .append("<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>")
                .append("<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>")
                .append("<style>")
                .append("body{margin:0;background:#12141a;font-family:system-ui,sans-serif}")
                .append("#map{position:absolute;top:0;bottom:0;width:100%}")
                .append("#map img{image-rendering:pixelated}")
                .append("#panel{position:absolute;top:10px;right:10px;z-index:1000;background:rgba(20,24,32,.93);")
                .append("color:#e8eaf0;border:1px solid #3a6b8a;border-radius:12px;padding:12px 14px;")
                .append("max-width:300px;font-size:13px;box-shadow:0 2px 12px rgba(0,0,0,.5)}")
                .append("#panel h3{margin:0 0 8px;font-size:15px;color:#8ce0ff}")
                .append("#panel td{padding:2px 6px 2px 0;color:#a8b4c4;white-space:nowrap}")
                .append("#panel td.v{color:#fff;font-family:monospace}")
                .append("#layers{margin-top:8px;border-top:1px solid #3a6b8a;padding-top:6px}")
                .append("#layers label{display:block;padding:2px 0;cursor:pointer;color:#c8d0dc}")
                .append("#layers input{margin-right:6px;accent-color:#5b9cf6}")
                .append("#panel button{margin-top:6px;width:100%;padding:5px;background:#2a3342;color:#8ce0ff;")
                .append("border:1px solid #3a6b8a;border-radius:6px;cursor:pointer}")
                .append("#collapse{position:absolute;top:10px;right:10px;z-index:1001;background:rgba(20,24,32,.93);")
                .append("color:#8ce0ff;border:1px solid #3a6b8a;border-radius:8px;padding:4px 10px;cursor:pointer}")
                .append("#hint{margin-top:6px;color:#7a8aa0;font-size:11px}")
                .append(".dist-label{background:rgba(20,24,32,.85);color:#8ce0ff;font:12px monospace;")
                .append("padding:2px 8px;border-radius:10px;border:1px solid #3a6b8a}")
                .append(".pt-popup b{color:#ffd54f}")
                .append(".pt-popup .del{display:inline-block;margin-top:6px;color:#ff8a80;")
                .append("cursor:pointer;text-decoration:underline}")
                .append("</style></head><body>")
                .append("<div id=\"map\"></div>")
                .append("<button id=\"collapse\" onclick=\"togglePanel()\">收起</button>")
                .append("<div id=\"panel\"><h3>").append(escapeHtml(title)).append("</h3><table>")
                .append("<tr><td>种子</td><td class=\"v\">").append(seed).append("</td></tr>")
                .append("<tr><td>游戏版本</td><td class=\"v\">").append(escapeHtml(versionStr))
                .append("</td></tr>");
        if (playerX != Integer.MIN_VALUE) {
            html.append("<tr><td>玩家</td><td class=\"v\">").append(playerX).append(", ")
                    .append(playerZ).append("</td></tr>");
        }
        if (spawnX != Integer.MIN_VALUE) {
            html.append("<tr><td>出生点</td><td class=\"v\">").append(spawnX).append(", ")
                    .append(spawnZ).append("</td></tr>");
        }
        html.append("<tr><td>地图范围</td><td class=\"v\">").append(map.width).append(" × ")
                .append(map.height).append(" 方块</td></tr>")
                .append("</table><div id=\"layers\">")
                .append("<label><input type=\"checkbox\" id=\"ck-p\" checked onchange=\"tg('p')\">标点</label>")
                .append("<label><input type=\"checkbox\" id=\"ck-l\" checked onchange=\"tg('l')\">连线</label>")
                .append("<label><input type=\"checkbox\" id=\"ck-s\" checked onchange=\"tg('s')\">结构</label>")
                .append("<label><input type=\"checkbox\" id=\"ck-e\" onchange=\"tg('e')\">实体</label>")
                .append("<label><input type=\"checkbox\" id=\"ck-sl\" onchange=\"tg('sl')\">史莱姆区块</label>")
                .append("</div>")
                .append("<button onclick=\"exportJson()\">导出标点 JSON</button>")
                .append("<button onclick=\"clearSaved()\">清空新增标点</button>")
                .append("<div id=\"hint\">长按地图 = 添加标点；点击标点气泡内可删除</div></div>")
                .append("<script>")
                .append("var map=L.map('map',{crs:L.CRS.Simple,minZoom:-5,maxZoom:3});")
                .append("var BOUNDS=[[").append(minZ).append(',').append(maxX).append("],[")
                .append(maxZ).append(',').append(minX).append("]];")
                .append("L.imageOverlay('data:image/png;base64,").append(b64)
                .append("',BOUNDS).addTo(map);")
                .append("var FZ=map.getBoundsZoom(BOUNDS);")
                .append("function mkCircle(z,x,opts){var px=opts.px||8;delete opts.px;")
                .append("var c=L.circle([z,x],L.extend({radius:1},opts));")
                .append("var up=function(){var s=Math.min(px,Math.max(3,px*Math.pow(2,map.getZoom()-FZ)));")
                .append("c.setRadius(s/Math.pow(2,map.getZoom()));};")
                .append("map.on('zoomend',up);up();return c;}")
                .append("var groups={p:L.layerGroup(),l:L.layerGroup(),s:L.layerGroup(),")
                .append("e:L.layerGroup(),sl:L.layerGroup()};")
                .append("function tg(k){if(document.getElementById('ck-'+k).checked){groups[k].addTo(map);}")
                .append("else{map.removeLayer(groups[k]);}}")
                .append("function togglePanel(){var p=document.getElementById('panel');")
                .append("var b=document.getElementById('collapse');")
                .append("if(p.style.display==='none'){p.style.display='block';b.textContent='收起';}")
                .append("else{p.style.display='none';b.textContent='信息';}}");
        // 标点（数据库内标点固定；新增标点走 localStorage + 长按）
        if (points != null && !points.isEmpty()) {
            html.append("var pts=");
            html.append(jsonArray(points));
            html.append(";pts.forEach(function(p){groups.p.addLayer(mkCircle(p.z,p.x,")
                    .append("{px:8,color:p.c,weight:2,fillOpacity:.85})")
                    .append(".bindPopup('<b>'+p.n+'</b><br>X:'+p.x+' Z:'+p.z));});");
        }
        // 连线（虚线 + 距离标注）
        if (links != null && !links.isEmpty()) {
            html.append("var lks=");
            html.append(jsonArray(links));
            html.append(";lks.forEach(function(l){groups.l.addLayer(L.polyline([[l.z1,l.x1],[l.z2,l.x2]],")
                    .append("{color:l.c,dashArray:'6,8',weight:2}));")
                    .append("var d=Math.round(Math.hypot(l.x2-l.x1,l.z2-l.z1));")
                    .append("groups.l.addLayer(L.marker([(l.z1+l.z2)/2,(l.x1+l.x2)/2],{icon:L.divIcon({className:'dist-label',")
                    .append("html:'<span>'+d+'m</span>',iconSize:[60,20]})}));});");
        }
        // 结构标记
        if (structures != null && !structures.isEmpty()) {
            html.append("var sts=");
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < structures.size(); i++) {
                StructureMarker m = structures.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{t:'").append(escapeHtml(m.type)).append("',x:")
                        .append(m.x).append(",z:").append(m.z).append('}');
            }
            sb.append(']');
            html.append(sb);
            html.append(";sts.forEach(function(s){groups.s.addLayer(mkCircle(s.z,s.x,")
                    .append("{px:10,color:'#f5a623',weight:2,fillOpacity:.85})")
                    .append(".bindPopup('<b>'+s.t+'</b><br>X:'+s.x+' Z:'+s.z));});");
        }
        // 实体（数量大，默认关闭，上限 6000）
        if (entities != null && !entities.isEmpty()) {
            int n = Math.min(entities.size(), 6000);
            StringBuilder eb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                EntityPos ep = entities.get(i);
                if (i > 0) {
                    eb.append(',');
                }
                eb.append("{n:'").append(escapeHtml(ep.name)).append("',x:")
                        .append(Math.round(ep.x)).append(",z:")
                        .append(Math.round(ep.z)).append('}');
            }
            eb.append(']');
            html.append("var ents=").append(eb);
            html.append(";ents.forEach(function(e){groups.e.addLayer(mkCircle(e.z,e.x,")
                    .append("{px:6,color:'#ff7043',weight:1,fillOpacity:.7})")
                    .append(".bindPopup('<b>'+e.n+'</b><br>X:'+e.x+' Z:'+e.z));});");
        }
        // 史莱姆区块（只列有地形数据的 chunk：大世界查 chunkColors key，
        // 小世界按 chunk 网格扫 colors 非透明像素——此前小世界 chunkColors==null
        // 导致史莱姆开关无论开关都没数据）
        StringBuilder sl = new StringBuilder("[");
        boolean first = true;
        if (map.chunkColors != null) {
            for (Long key : map.chunkColors.keySet()) {
                int cx = (int) (key >> 32);
                int cz = (int) (long) key;
                if (isSlimeChunk(cx, cz)) {
                    if (!first) {
                        sl.append(',');
                    }
                    first = false;
                    sl.append('[').append(cz).append(',').append(cx).append(']');
                }
            }
        } else if (map.colors != null) {
            int cw = map.width / 16;
            int ch = map.height / 16;
            for (int cz = 0; cz < ch; cz++) {
                for (int cx = 0; cx < cw; cx++) {
                    if (!isSlimeChunk(map.minBlockX / 16 + cx, map.minBlockZ / 16 + cz)) {
                        continue;
                    }
                    boolean has = false;
                    int yEnd = Math.min((cz + 1) * 16, map.height);
                    int xEnd = Math.min((cx + 1) * 16, map.width);
                    for (int y = cz * 16; y < yEnd && !has; y++) {
                        int base = y * map.width + cx * 16;
                        for (int x = 0; x < xEnd - cx * 16; x++) {
                            if ((map.colors[base + x] & 0xFF000000) != 0) {
                                has = true;
                                break;
                            }
                        }
                    }
                    if (has) {
                        if (!first) {
                            sl.append(',');
                        }
                        first = false;
                        sl.append('[').append(map.minBlockZ / 16 + cz).append(',')
                                .append(map.minBlockX / 16 + cx).append(']');
                    }
                }
            }
        }
        sl.append(']');
        html.append("var sls=").append(sl);
        html.append(";sls.forEach(function(s){groups.sl.addLayer(L.rectangle(")
                .append("[[s[0]*16,s[1]*16],[s[0]*16+16,s[1]*16+16]],")
                .append("{color:'#4ade80',weight:1,fillOpacity:.18}));});");
        // 玩家/出生点
        if (playerX != Integer.MIN_VALUE) {
            html.append("mkCircle(").append(playerZ).append(',').append(playerX)
                    .append(",{px:7,color:'#4ade80',weight:2,fillOpacity:.95}).addTo(map)")
                    .append(".bindPopup('<b>玩家</b><br>X:").append(playerX).append(" Z:")
                    .append(playerZ).append("');");
        }
        if (spawnX != Integer.MIN_VALUE) {
            html.append("mkCircle(").append(spawnZ).append(',').append(spawnX)
                    .append(",{px:7,color:'#5b9cf6',weight:2,fillOpacity:.95}).addTo(map)")
                    .append(".bindPopup('<b>出生点</b><br>X:").append(spawnX).append(" Z:")
                    .append(spawnZ).append("');");
        }
        // 标点编辑（localStorage 持久化：长按地图添加、气泡内删除、导出 JSON。
        // 移动端长按 = contextmenu 事件，桌面端右键同样触发——不能用 Shift+点击，
        // 安卓浏览器没有 Shift）
        html.append("var SAVED_KEY='levip_").append(escapeHtml(title)).append("';")
                .append("var savedPts=[];try{savedPts=JSON.parse(localStorage.getItem(SAVED_KEY))||[];}catch(e){}")
                .append("var ptRecs=[];")
                .append("function redrawPts(){ptRecs.forEach(function(r){groups.p.removeLayer(r.c);});ptRecs=[];")
                .append("savedPts.forEach(function(p){var c=mkCircle(p.z,p.x,")
                .append("{px:8,color:p.c||'#ffd54f',weight:2,fillOpacity:.85});")
                .append("c.bindPopup('<div class=\"pt-popup\"><b>'+p.n+'</b><br>X:'+p.x+' Z:'+p.z")
                .append("+'<span class=\"del\">删除</span></div>');")
                .append("c.on('popupopen',function(ev){var el=ev.popup.getElement().querySelector('.del');")
                .append("if(el){el.onclick=function(){delPt(ptRecs.indexOf(c));c.closePopup();};}});")
                .append("groups.p.addLayer(c);ptRecs.push(c);});}")
                .append("function delPt(i){var c=ptRecs[i];if(!c)return;")
                .append("savedPts.splice(i,1);localStorage.setItem(SAVED_KEY,JSON.stringify(savedPts));redrawPts();}")
                .append("var pendingPt=null;")
                .append("function cancelPt(){var b=document.getElementById('pt-dlg');if(b){b.remove();}pendingPt=null;}")
                .append("function savePt(){var i=document.getElementById('pt-name');")
                .append("var n=i?i.value.trim():'';if(!n||!pendingPt){return;}")
                .append("savedPts.push({n:n,x:Math.round(pendingPt.lng),z:Math.round(pendingPt.lat),c:'#ffd54f'});")
                .append("localStorage.setItem(SAVED_KEY,JSON.stringify(savedPts));cancelPt();redrawPts();}")
                .append("map.on('contextmenu',function(e){if(e.originalEvent){e.originalEvent.preventDefault();}")
                .append("var ll=e.latlng||(e.originalEvent?map.mouseEventToLatLng(e.originalEvent):null);")
                .append("if(!ll){return;}pendingPt=ll;")
                .append("var d=document.createElement('div');d.id='pt-dlg';")
                .append("d.style.cssText='position:fixed;left:50%;top:35%;transform:translate(-50%,-50%);")
                .append("z-index:2000;background:#20262e;color:#e8eaf0;padding:14px;border:1px solid #3a6b8a;")
                .append("border-radius:10px;box-shadow:0 4px 16px rgba(0,0,0,.6)';")
                .append("d.innerHTML='<div style=\"margin-bottom:8px;color:#8ce0ff\">添加标点</div>")
                .append("<input id=\"pt-name\" placeholder=\"标点名称\" style=\"width:220px;padding:6px;")
                .append("background:#12141a;color:#fff;border:1px solid #3a6b8a;border-radius:6px\">")
                .append("<div style=\"margin-top:10px;text-align:right\">")
                .append("<button id=\"pt-cancel\" style=\"margin-right:8px;background:#2a3342;color:#a8b4c4;")
                .append("border:1px solid #3a6b8a;border-radius:6px;padding:5px 12px\">取消</button>")
                .append("<button id=\"pt-save\" style=\"background:#5b9cf6;color:#fff;border:none;")
                .append("border-radius:6px;padding:5px 12px\">保存</button></div>';")
                .append("document.body.appendChild(d);")
                .append("var inp=document.getElementById('pt-name');")
                .append("var cbtn=document.getElementById('pt-cancel');")
                .append("var sbtn=document.getElementById('pt-save');")
                .append("if(cbtn){cbtn.onclick=cancelPt;}")
                .append("if(sbtn){sbtn.onclick=function(){if(inp){inp.blur();}setTimeout(savePt,120);}}")
                .append("if(inp){inp.focus();}});")
                .append("function exportJson(){var a=document.createElement('a');")
                .append("a.href='data:application/json;charset=utf-8,'+encodeURIComponent(JSON.stringify(savedPts,null,2));")
                .append("a.download='points.json';a.click();}")
                .append("function clearSaved(){if(!confirm('清空所有新增标点？'))return;")
                .append("savedPts=[];localStorage.removeItem(SAVED_KEY);redrawPts();}")
                .append("redrawPts();")
                .append("groups.p.addTo(map);groups.l.addTo(map);groups.s.addTo(map);")
                .append("map.fitBounds(BOUNDS);</script></body></html>");
        File out = new File(outDir, fileName);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jsonArray(java.util.List<String[]> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{n:'").append(escapeHtml(r[0])).append("',x:")
                    .append(Integer.parseInt(r[1])).append(",z:")
                    .append(Integer.parseInt(r[2])).append(",c:'")
                    .append(r.length > 3 ? r[3] : "#ffd54f").append("'}");
        }
        return sb.append(']').toString();
    }

    public static void debugExport(WorldMap map) {
        try {
            if (map == null || map.width <= 0 || map.height <= 0) {
                return;
            }
            int w = map.width;
            int h = map.height;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (map.colors != null) {
                bmp.setPixels(map.colors, 0, w, 0, 0, w, h);
            } else if (map.chunkColors != null) {
                // chunk 缓存路径：逐 chunk 拼装（155MB 世界 928×1089 chunk 图太大，
                // 导出缩小为每 chunk 1 像素的代表色概览）
                int cw = w / 16;
                int ch = h / 16;
                Bitmap mini = Bitmap.createBitmap(Math.max(1, cw), Math.max(1, ch),
                        Bitmap.Config.ARGB_8888);
                for (Map.Entry<Long, int[]> e : map.chunkColors.entrySet()) {
                    int cx = unpackX(e.getKey());
                    int cz = unpackZ(e.getKey());
                    int px = cx - map.minBlockX / 16;
                    int pz = cz - map.minBlockZ / 16;
                    if (px < 0 || px >= cw || pz < 0 || pz >= ch) {
                        continue;
                    }
                    int[] cc = e.getValue();
                    // 代表色 = 该 chunk 内最暗非透明色的均值（简化：取第 128 个非透明）
                    int c = 0;
                    for (int v : cc) {
                        if ((v & 0xFF000000) != 0) {
                            c = v;
                            break;
                        }
                    }
                    mini.setPixel(px, pz, c);
                }
                bmp = mini;
            }
            // scoped storage 下 /sdcard/Download 直接写会 EACCES，
            // 优先写 app 外部目录（/sdcard/Android/data/org.levimc.launcher/files/）
            File dir = new File("/sdcard/Android/data/org.levimc.launcher/files");
            if (!dir.exists() || !dir.canWrite()) {
                dir = new File("/sdcard/Download");
            }
            File out = new File(dir, "map_debug.png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
            Log.i(TAG, "调试导出: " + out.getAbsolutePath());
            // biome 图层调试导出（chunk 路径）
            if (map.biomeColors != null) {
                int bw = map.width;
                int bh = map.height;
                Bitmap bio = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bio.setPixels(map.biomeColors, 0, bw, 0, 0, bw, bh);
                File bout = new File(dir, "map_debug_biome.png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(bout)) {
                    bio.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                bio.recycle();
                Log.i(TAG, "调试导出 biome: " + bout.getAbsolutePath());
            } else if (map.chunkBiomeColors != null && !map.chunkBiomeColors.isEmpty()) {
                int cw = Math.max(1, w / 16);
                int ch = Math.max(1, h / 16);
                Bitmap bio = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                for (Map.Entry<Long, int[]> e : map.chunkBiomeColors.entrySet()) {
                    int cx = unpackX(e.getKey());
                    int cz = unpackZ(e.getKey());
                    int px = cx - map.minBlockX / 16;
                    int pz = cz - map.minBlockZ / 16;
                    if (px < 0 || px >= cw || pz < 0 || pz >= ch) {
                        continue;
                    }
                    int c = 0;
                    for (int v : e.getValue()) {
                        if ((v & 0xFF000000) != 0) {
                            c = v;
                            break;
                        }
                    }
                    bio.setPixel(px, pz, c);
                }
                File bout = new File(dir, "map_debug_biome.png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(bout)) {
                    bio.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                bio.recycle();
                Log.i(TAG, "调试导出 biome: " + bout.getAbsolutePath());
            }
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
