package org.levimc.launcher.core.content.worldmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.leveldb.LevelDBEntry;
import org.levimc.launcher.core.content.leveldb.LevelDBReader;
import org.levimc.launcher.core.content.leveldb.NativeLevelDb;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
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
    /** 下界剔除方块黑名单（渲染时视为空气向下穿透；null = 默认
     *  硬编码剔除 bedrock+netherrack）。 */
    public static volatile java.util.Set<String> netherExcludeBlocks = null;
    /** 忽略光源方块（设置页开关）：火把等非固体光源俯视渲染成黄色杂点，
     *  开启后视为空气向下穿透显示地表。 */
    public static volatile boolean ignoreLightBlocks = false;
    /** 坡度阴影开关（v397 设置区）：关闭后平面色块无高差明暗。 */
    public static volatile boolean enableShading = true;
    /** 结构特征检测开关（v397 设置区）：palette 特征猜结构
     * （沙漠神殿/前哨站等无 key 结构）可能误报，可关闭。 */
    public static volatile boolean enableStructureDetection = true;

    /** 下界窗口裁剪：sub 与 [yMin,yMax] 无交集则剔除。
     *  未设 y 范围（全量档）时也跳过基岩天花板层。推演：下界顶基岩在
     *  y123-127（sub 7），y96-111（sub 6）有堡垒顶/玄武岩柱——只裁
     *  sub 7（纯基岩+空气），裁 sub 6 会把 y96+ 建筑顶切掉（v351 显示
     *  异常根因之一）。 */
    private static void applyNetherWindow(Map<Integer, SubChunk> subs) {
        if (subs == null || subs.isEmpty()) {
            return;
        }
        if (netherYMin >= 0) {
            subs.keySet().removeIf(s -> s * 16 + 15 < netherYMin || s * 16 > netherYMax);
        } else {
            subs.keySet().removeIf(s -> s >= 7);
        }
    }

    /** 下界 sub 是否在设定 y 范围内（供流式/全量 filter 用）。 */
    private static boolean netherSubInRange(int sub) {
        if (netherYMin >= 0) {
            return sub * 16 + 15 >= netherYMin && sub * 16 <= netherYMax;
        }
        return sub < 7;
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
    /** 半透明方块 alpha（仅 glass/ice 类，bedrockmap 色表语义）。 */
    private static final Map<String, Integer> blockAlphaTable = new HashMap<>();

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
                        // 半透明方块记录 alpha（bedrockmap 色表语义：玻璃 64 / 冰 190 /
                        // 染色玻璃 ~117）。surfaceColor 遇到这些方块继续向下找固体再混合
                        // ——大块不透明浅蓝玻璃顶"颜色异常"的修复
                        int alpha = rgb.length() >= 4 ? rgb.optInt(3, 255) : 255;
                        if (alpha < 255 && (name.contains("glass") || name.endsWith("ice"))) {
                            blockAlphaTable.put(name, alpha);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            Log.i(TAG, "方块色表加载: " + blockColorTable.size() + " 项, 半透明: "
                    + blockAlphaTable.size());
        } catch (Exception e) {
            Log.w(TAG, "方块色表加载失败，回退内置色表", e);
        }
    }

    /** 方块色表颜色（设置页方块选择器色块图标用；未收录回退灰色）。 */
    public static int blockColor(String fullName) {
        Integer c = blockColorTable.get(fullName);
        return c != null ? c : 0xFF888888;
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

    /** 读取 asset 文件，失败返回空串（HTML 内联资源缺失时降级不崩溃）。 */
    private static String readAssetSafely(String name) {
        try {
            return readAsset(appContext, name);
        } catch (Exception e) {
            Log.w(TAG, "asset 读取失败: " + name, e);
            return "";
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
    /**
     * 无高度图 chunk 的合成高度图：逐列从顶向下找第一个"可见"方块 y
     * （surfaceColor 同款规则：空气穿透、下界 bedrock/netherrack 剔除
     * 穿透、水层计入）。1.26 下界/末地大部分 chunk 没有 0x2b/0x2d
     * 高度图 key，只有 subchunk 数据——阴影/窗口裁剪需要高度值。
     */
    private static int[] synthesizeHeightMap(Map<Integer, SubChunk> subs, int dimension) {
        int[] hmap = new int[256];
        if (subs == null || subs.isEmpty()) {
            return hmap;
        }
        int maxSub = Integer.MIN_VALUE;
        for (Integer s : subs.keySet()) {
            maxSub = Math.max(maxSub, s);
        }
        if (maxSub == Integer.MIN_VALUE) {
            return hmap;
        }
        int yStart = Math.min(maxSub * 16 + 15, 320);
        java.util.Set<String> ex = netherExcludeBlocks;
        for (int i = 0; i < 256; i++) {
            int lx = i & 15;
            int lz = i >> 4;
            for (int y = yStart; y >= -64; y--) {
                int si = Math.floorDiv(y, 16);
                SubChunk sub = subs.get(si);
                if (sub == null) {
                    continue;
                }
                int ly = y - si * 16;
                int idx = sub.getIndex(lx, ly, lz);
                String name = idx < sub.palette.length ? sub.palette[idx] : null;
                if (name == null || isAirName(name)) {
                    if (sub.waterLayer != null) {
                        int widx = sub.waterLayer.getIndex(lx, ly, lz);
                        String wname = widx < sub.waterLayer.palette.length
                                ? sub.waterLayer.palette[widx] : null;
                        if (wname != null && !isAirName(wname)) {
                            hmap[i] = y;
                            break;
                        }
                    }
                    continue;
                }
                if (dimension == DIM_NETHER) {
                    if (ex == null) {
                        if (name.equals("minecraft:bedrock") || name.endsWith("bedrock")
                                || name.equals("minecraft:netherrack")) {
                            continue; // 与 surfaceColor 剔除一致：穿透
                        }
                    } else if (ex.contains(name)) {
                        continue;
                    }
                }
                hmap[i] = y;
                break;
            }
        }
        return hmap;
    }

    private static int applyShading(int color, int[] hmap, int i) {
        if (!enableShading) {
            return color; // v397 设置区开关：平面色块
        }
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
    /** v467：草地灰度模板（grass_block 色表色 147,147,147——
     *  陆地回退列与正常渲染列亮度统一用）。 */
    private static final int GRASS_TEMPLATE_COLOR = 0xFF939393;
    /** v472：回退海列模拟河床的砾石色（与色表 gravel 126,124,122 一致）。
     *  v468 曾用亮沙 0xFFDBD3A0——1.26 存档海区大量列无方块数据（未生成
     *  区块：高度图=海面预测值 63 + 群系数据，但 subchunk 全 air），回退
     *  列水深算不出（高度图存海面非海底）→ op 只能取 0.55 → 45% 亮沙透
     *  出 = 浅蓝灰斑块，与旁边正常海（0.9×水色+0.1×砾石 #497ECB）对比
     *  "太突兀"（用户反馈）。实际海床实测是砾石，统一砾石床+深海 op。 */
    private static final int SEA_BED_COLOR = 0xFF7E7C7A;

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
        /** v459：玩家真实方块坐标（未经 blockScale 降采样——数据面板
         *  显示用；标记绘制仍用 playerBlockX/Z）。-1 = 无。 */
        public int playerRawBlockX = -1;
        public int playerRawBlockY = -1;
        public int playerRawBlockZ = -1;
        /** 本地玩家 UniqueID（db ~local_player，-1 = 无） */
        public long playerUniqueId = -1;
        /** v459：玩家 UUID 文本（player_<uuid> key 的 MsaId 字符串，
         *  36 字符标准格式；1.21 存档 UniqueID 是占位值 ffffffff00000001，
         *  真实 UUID 只在这个 key 里。null = 无）。 */
        public String playerUuid;
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
        /** 矿物热力图（chunk → 热力色，图层开关开启时后台烘焙生成）。 */
        public Map<Long, Integer> chunkOreColors;
        /** 视口按需渲染源（BTR 式）：db 目录与维度；非空时缺失 chunk 由外部按需渲染。 */
        public File chunkSourceDir;
        public int chunkSourceDim;
        /** 缓存文件名后缀（下界 y 段等渲染参数，map 创建时定格）——
         *  保存缓存必须用它而不是当前全局参数：切段时全局参数已改成新段，
         *  旧段数据会存进新段文件名（"切段总是重新渲染"的根因）。 */
        public String chunkCacheSuffix = "";
        /** 缓存保存时的 db 文件指纹列表（"name:size:mtime"）——
         *  增量更新：打开时对比找出新文件，只重渲染这些文件覆盖的 chunk。
         *  LevelDB sst 文件不可变，存档更新只会新增文件/追加 log。 */
        public java.util.List<String> dbFileFingerprints;
        /** 小世界缓存 db 已变化（allowStale 加载的旧图）——调用方
         *  先显示旧图、后台重渲染替换。 */
        public boolean cacheStale = false;
        /** v19：缓存是否完整（烘焙完整跑完落盘时置 true）——完整性
         *  判定依据。此前用 bounds 面积×60% 判 chunk 数——稀疏世界
         *  （TK 实际 24844 chunk 只占 bounds 面积 2.5%）永远"不足"，
         *  静默烘焙每次启动空转。 */
        public boolean cacheComplete = false;
        /** v20：缓存文件是否含 biome 数据（biome 独立文件延迟读用）。 */
        public boolean cacheHasBiome = false;
        /** v413：矿石标点列表（chunk 渲染时收集，随烘焙全图铺开；
         *  独立落盘 ore.bin——缓存 chunk 不重渲染也能读到）。 */
        public java.util.List<OreMarker> oreMarkers;
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
            // bits=0 = 1.26 单值存储（整层同一方块，无字数据）
            this.blocksPerWord = bits > 0 ? 32 / bits : 1;
            this.data = data;
            this.palette = palette;
        }

        /** 读 (x,z,y) 的 palette 索引（BTR 新版 getBlockId 同款位序）。 */
        int getIndex(int x, int y, int z) {
            if (bits == 0) {
                return 0; // 单值存储：全部方块 = palette[0]
            }
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
        int oreStreamed = 0;
        // v432：窗口外矿石层的流式扫描结果（解码→扫→丢，不常驻内存——
        // 手机 536MB heap OOM 根因：全部矿石层 20137 个 SubChunk
        // 常驻 ~400MB 直接爆堆）
        java.util.List<OreMarker> streamOres = new java.util.ArrayList<>();
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
                // v413：地下矿石层（sub ≤ 4）也解码——卫星图渲染不用
                // 但矿石标点检测需要（钻石/金/铁都在 y<80）
                boolean oreLayer = sub <= 4;
                if (maxSub == null || (sub < maxSub - 2 && !oreLayer)
                        || sub > maxSub + 2) {
                    skipped++;
                    continue; // 无 subchunk 数据或深层非矿石层：卫星图不需要
                }
                // v432：窗口外矿石层流式扫描（解码后只留矿石标记，
                // SubChunk 立即丢弃——内存峰值 -50%）
                if (oreLayer && sub < maxSub - 2) {
                    try {
                        SubChunk sc = decodeSubChunk(entry.getValue());
                        if (sc != null) {
                            java.util.Map<Integer, SubChunk> tmp =
                                    new java.util.HashMap<>();
                            tmp.put(sub, sc);
                            collectChunkOres(tmp, chunkKey[0], chunkKey[1],
                                    dimension, streamOres);
                            decoded++;
                            oreStreamed++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to decode ore subchunk at "
                                + chunkKey[0] + "," + chunkKey[1], e);
                    }
                    continue;
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
                + " 解码=" + decoded + " 流式矿石层=" + oreStreamed
                + " surfaceSubs=" + surfaceSubs.size());

        return assembleMap(heightMaps, biomeMaps, subChunks, dimension, streamOres);
    }

    /** 组装全图：收集完 heightMaps/biomeMaps/subChunks 后的共享渲染路径。
     *  v432：streamOres = 第二遍流式扫描的窗口外矿石层标记（与
     *  窗口内 sub≤4 层的标记合并——两组不重叠）。 */
    private static WorldMap assembleMap(Map<Long, int[]> heightMaps,
                                        Map<Long, byte[]> biomeMaps,
                                        Map<Long, Map<Integer, SubChunk>> subChunks,
                                        int dimension,
                                        java.util.List<OreMarker> streamOres) {
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
        if (cells > 16_000_000L) {
            // v411：防御阈值从 1 亿格收紧到 1600 万格——3 个数组
            // 共 192MB，叠加进程既有内存逼近 536MB 上限（实测 44.7M
            // 格世界 3×178MB 直接 OOM 崩溃）。超过走大世界 chunk 路径
            Log.e(TAG, "地图过大拒绝渲染: " + spanX + "x" + spanZ
                    + " 格数=" + cells);
            return null;
        }
        int[] heights;
        int[] colors;
        int[] biomeColors;
        try {
            heights = new int[width * height];
            colors = new int[width * height];
            biomeColors = new int[width * height];
        } catch (OutOfMemoryError oom) {
            // v411：分配失败不崩进程——返回 null 由调用方降级
            Log.e(TAG, "全图数组 OOM: " + width + "x" + height, oom);
            return null;
        }
        Arrays.fill(colors, COLOR_BACKGROUND);
        // v432：回收第一遍/第二遍解码的解压垃圾（subchunk 解压 buffer
        // 是手机 536MB heap OOM 的大头之一）——几十 ms 换 100+MB
        Runtime.getRuntime().gc();

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
        // v413：小世界路径也收集矿石标点
        // v432：先并入第二遍流式扫描的窗口外矿石层标记（不重叠）
        java.util.List<OreMarker> oreMarkers = new java.util.ArrayList<>();
        if (streamOres != null) {
            oreMarkers.addAll(streamOres);
        }
        for (Map.Entry<Long, Map<Integer, SubChunk>> e : subChunks.entrySet()) {
            collectChunkOres(e.getValue(), unpackX(e.getKey()), unpackZ(e.getKey()),
                    dimension, oreMarkers);
        }
        Log.i(TAG, "卫星模式完成: " + width + "x" + height
                + " blocks, chunk 范围=(" + minX + "," + minZ + ")-(" + maxX + "," + maxZ + ")"
                + ", biome 数据=" + biomeMaps.size() + " chunk / " + biomeNonZero + " 非透明像素"
                + ", 解码 subchunk=" + subCount + " (含表面层=" + subChunks.size() + " chunk)"
                + ", 矿石标点=" + oreMarkers.size());
        WorldMap wm = new WorldMap(minBlockX, minBlockZ, width, height, colors, biomeColors);
        if (!oreMarkers.isEmpty()) {
            wm.oreMarkers = oreMarkers;
        }
        return wm;
    }

    /**
     * 大世界流式渲染：155MB 级 db 全量 readAllEntries 会 OOM。
     * 用 LevelDBReader 的过滤/只读 key 模式分三遍流式收集：
     * 1) readKeys：统计每 chunk 实际最高 sub 索引（value 不分配内存）
     * 2) readEntries：高度图/版本 value（0x2b/0x2c）
     * 3) readEntries：只解码窗口内 subchunk（maxSub±2）
     */
    public static WorldMap buildSatelliteMapStreaming(File dbDir, int dimension) {
        return buildSatelliteMapStreaming(dbDir, dimension, null);
    }

    /** 流式渲染进度回调（每渲染 20 chunk 回调一次）。
     *  onProgress：计数进度（导出 HTML 通知百分比用）；
     *  onChunkData：最近一批 chunk 的渲染数据（预渲染渐进显示用）。 */
    public interface StreamProgress {
        void onProgress(int done, int total);

        default void onChunkData(java.util.Map<Long, int[]> colors,
                                 java.util.Map<Long, int[]> biomes,
                                 java.util.List<Long> newKeys) {
        }
    }

    public static WorldMap buildSatelliteMapStreaming(File dbDir, int dimension,
                                                      StreamProgress progress) {
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
                        // 窗口内 subchunk：下界 maxSub-6（v372 渲染窗口
                        // fMin=surfaceSub-4，filter 只读到 maxSub-2 会把
                        // 地表层以下数据丢掉——预渲染合并后主世界错乱根因。
                        // 放宽到 maxSub-6 覆盖树冠与地表高度差 + 河谷）
                        Integer maxSub = maxSubRef.get(pack(ck[0], ck[1]));
                        if (maxSub == null || ck[3] < maxSub - 6 || ck[3] > maxSub + 2) {
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
            // subs 空 → 整片回退 biome 纯色（下界/末地"无阴影无方块"的根因）。
            // 两级排序：
            // ① 先按字节序（compareBytes，v322 同款）——同一 chunk 的所有 key
            //   （高度图+subchunk）前缀相同必然相邻，逐 chunk 收集逻辑依赖此性质；
            //   v374 只用距离排序，输入是无序 HashMap，同 chunk key 在等距组内
            //   仍按无序输入顺序排列 → 下界/末地 subchunk 数据丢失（大世界
            //   下界末地连区块网格都不显示的根因）
            // ② 再按 chunk 距世界原点 (0,0) 距离稳定排序——预渲染从中心向外
            //   环形铺开（用户要的"以世界为圆心向外刷"）。List.sort 是稳定
            //   排序：同 chunk key 距离相同，保持 ① 排好的相邻顺序。
            // 排序 56.9 万条目两次约 1-2 秒，与 30-60 秒渲染相比可忽略
            heightEntries.sort((a, b) -> compareBytes(
                    a.getKey().getRawKey(), b.getKey().getRawKey()));
            heightEntries.sort((a, b) -> {
                int[] ka = parseChunkKey(a.getKey().getRawKey());
                int[] kb = parseChunkKey(b.getKey().getRawKey());
                long da = ka != null ? (long) ka[0] * ka[0] + (long) ka[1] * ka[1]
                        : Long.MAX_VALUE;
                long db = kb != null ? (long) kb[0] * kb[0] + (long) kb[1] * kb[1]
                        : Long.MAX_VALUE;
                return Long.compare(da, db);
            });
            // 逐 chunk 收集 → 渲染 → 释放（sst 内同 chunk key 相邻有序）
            int curCx = Integer.MIN_VALUE;
            int curCz = Integer.MIN_VALUE;
            int[] curHmap = null;
            byte[] curBiomes = null;
            Map<Integer, SubChunk> curSubs = new HashMap<>();
            java.util.List<Long> progressBatch = new java.util.ArrayList<>();
            for (LevelDBEntry entry : heightEntries) {
                // 中断检查：切维度中断旧预渲染时尽快退出（3 万 chunk
                // 渲染循环可达数十秒，期间旧 heightEntries 数百 MB 驻留）
                if (Thread.currentThread().isInterrupted()) {
                    heightEntries = null;
                    reader.close();
                    return null;
                }
                byte[] rawKey = entry.getKey().getRawKey();
                int[] chunkKey = parseChunkKey(rawKey);
                if (chunkKey == null) {
                    continue;
                }
                if (chunkKey[0] != curCx || chunkKey[1] != curCz) {
                    // 换 chunk：渲染上一个并释放。
                    // 1.26 下界/末地大量 chunk 无高度图 key——有 subchunk
                    // 数据时合成高度图（与视口按需渲染同款；此前 curHmap
                    // ==null 直接跳过不渲染，预渲染路径同样丢下界/末地）
                    if (curHmap == null && !curSubs.isEmpty()) {
                        curHmap = synthesizeHeightMap(curSubs, dimension);
                    }
                    if (curHmap != null) {
                        renderChunkToCache(curCx, curCz, curHmap, curBiomes, curSubs,
                                dimension, renderedChunks, chunkColors, chunkBiomeColors,
                                monumentChunks, endCityChunks,
                                finalMinCx, finalMaxCx, finalMinCz, finalMaxCz);
                        decoded += curSubs.size();
                        // 进度回调：每 20 个渲染 chunk 回调一次
                        // （onProgress 计数 + onChunkData 批数据）
                        if (progress != null) {
                            progressBatch.add(pack(curCx, curCz));
                            if (progressBatch.size() >= 20) {
                                progress.onProgress(renderedChunks.size(),
                                        maxSubByChunk.size());
                                progress.onChunkData(chunkColors, chunkBiomeColors,
                                        new java.util.ArrayList<>(progressBatch));
                                progressBatch.clear();
                            }
                        }
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
            // 收尾：渲染最后一个 chunk（同样合成无高度图 chunk 的高度）
            if (curHmap == null && !curSubs.isEmpty()) {
                curHmap = synthesizeHeightMap(curSubs, dimension);
            }
            if (curHmap != null) {
                renderChunkToCache(curCx, curCz, curHmap, curBiomes, curSubs,
                        dimension, renderedChunks, chunkColors, chunkBiomeColors,
                        monumentChunks, endCityChunks,
                        finalMinCx, finalMaxCx, finalMinCz, finalMaxCz);
                decoded += curSubs.size();
            }
            if (progress != null) {
                progress.onProgress(renderedChunks.size(), maxSubByChunk.size());
                if (!progressBatch.isEmpty()) {
                    progress.onChunkData(chunkColors, chunkBiomeColors,
                            new java.util.ArrayList<>(progressBatch));
                    progressBatch.clear();
                }
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
        if (monumentChunks != null && enableStructureDetection) {
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
        // 渲染窗口与按需路径统一（v304 地表层中心）：流式 filter 的
        // maxSub±2 只是读数据窗口（含树冠/建筑顶），渲染必须再裁到
        // 地表窗口——否则高树 chunk 从树冠层渲染、地表被裁，颜色回退/
        // 异常（预渲染合并后"结构附近错误颜色"的根因）
        // v372：下界放宽 -2 → -4（64 方块深）——悬空商店（建筑高出地表
        // 40+ 方块）下方列在窗口内全空，回退 savanna 群系草黄 (191,183,85)
        // 大片"黄色色块"的根因（hmap 同一 chunk 内 63~104 突变，fMin
        // 只到 surfaceSub-2 把低处河床裁掉）
        if (dimension != DIM_NETHER && !subs.isEmpty()) {
            int maxH = 0;
            for (int hh : hmap) {
                if (hh > maxH) {
                    maxH = hh;
                }
            }
            int surfaceSub = Math.floorDiv(maxH - 1, 16);
            int maxSub = Integer.MIN_VALUE;
            for (int s : subs.keySet()) {
                maxSub = Math.max(maxSub, s);
            }
            final int fMin = surfaceSub - 4;
            final int fMax = Math.max(surfaceSub + 2, maxSub);
            subs.keySet().removeIf(s -> s < fMin || s > fMax);
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
    /** 全透明 256 像素（biome 缺 chunk 时写入用）。 */
    private static final int[] EMPTY_CHUNK_COLORS = new int[256];
    // v3：v301 readChunk 多块读取 + v304 地表窗口修复前渲染的缓存数据是错的
    // （subchunk 缺失/地表层被裁），必须失效重渲染——村庄/建筑错乱的直接来源
    // v4：缓存加入 biome 图层色（v3 只存地形色，缓存命中后 biome 图层永远没数据）
    // v5：玻璃穿透回滚（玻璃恢复不透明色表渲染）+ 下界裁剪 sub7（v4 的
    // 玻璃穿透与 s>=6 裁剪渲染结果已错，必须失效）
    // v7：缓存挪到应用私有目录（旧缓存写世界目录，编辑几次膨胀 100+MB——
    // 内容管理显示体积变大的根因）+ 每 chunk 调色板索引压缩（无损 ~3x）
    // v9：玻璃下无固体时回退 biome 色（v7 缓存的纯玻璃色 #AFD5DB 大片
    // "海晶蓝"必须失效）
    // v10：chunk 边界阴影减半（v9 缓存边界阴影未减半，边缘色差须失效）
    // v11：阴影高度差 <2 阈值（v10 缓存仍含建筑边缘 ±20% 亮暗带须失效）
    // v12：忽略光源方块开关（火把等光源方块渲染结果变化须失效）
    // v13：渲染窗口下界 -2 → -4（悬空建筑下方列不再回退群系黄）
    // v14：切维度保存缓存维度错乱（主世界图写进下界缓存文件）须失效
    // v15：流式 filter 窗口下界 maxSub-6（v14 缓存缺失深层数据须失效）
    // v16：流式渲染两级排序（compareBytes 字节序 + 距离稳定排序）——
    //      v15 缓存含 v374 距离排序导致的下界/末地数据丢失结果须失效
    // v17：1.26 单值存储 subchunk 解码（bits=0 header，末地 end_stone 层
    //      实测格式）+ onChunkData NPE 修复——v16 缓存含解码失败/半渲染
    //      结果（末地 122 chunk 坏缓存）须失效
    // v18：缓存头加 db 文件指纹列表（"name:size:mtime"）——增量更新：
    //      打开时 diff 找出新文件只重渲染变化 chunk，db 变化不再全量失效
    // v21：v466-v471 回退水色/河床混合修复（旧缓存是绿色回退/紫灰
    //      COLOR_WATER/沙床浅蓝渲染结果，必须失效重渲）
    private static final int MAP_CACHE_VERSION = 21;

    /** 缓存根目录（应用私有，卸载即清——缓存可再生）。null 时回退旧路径。 */
    private static java.io.File sCacheBase;

    /** 维度名（v395 新目录结构：map_cache/<世界目录名>/<维度名>/）。 */
    private static String dimDirName(int dimension) {
        return dimension == DIM_NETHER ? "nether"
                : dimension == DIM_END ? "end" : "overworld";
    }

    /** 世界缓存子目录：map_cache/<世界目录名（唯一标识）>/。 */
    private static java.io.File worldCacheDir(File dbDir) {
        if (sCacheBase != null) {
            String world = dbDir.getParentFile() != null
                    ? dbDir.getParentFile().getName() : "world";
            java.io.File d = new java.io.File(sCacheBase, world);
            if (!d.isDirectory()) {
                d.mkdirs();
            }
            return d;
        }
        return dbDir.getParentFile();
    }

    /** 维度缓存子目录：<世界>/<维度名>/（自动创建）。 */
    private static java.io.File dimCacheDir(File dbDir, int dimension) {
        java.io.File base = worldCacheDir(dbDir);
        java.io.File d = new java.io.File(base, dimDirName(dimension));
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    /** v395 迁移：把旧扁平缓存文件（<世界名>_map_cache_*.bin 等）移动
     *  到新目录结构（<世界名>/<维度名>/）。迁移后旧路径文件删除，
     *  缓存内容不变无需重渲染。 */
    public static void migrateLegacyCache(File dbDir) {
        try {
            if (sCacheBase == null || dbDir == null || dbDir.getParentFile() == null) {
                return;
            }
            String world = dbDir.getParentFile().getName();
            java.io.File[] old = sCacheBase.listFiles(f -> f.isFile()
                    && f.getName().startsWith(world + "_map_") && f.getName().endsWith(".bin"));
            if (old == null || old.length == 0) {
                return;
            }
            for (java.io.File f : old) {
                String n = f.getName();
                // world_map_cache_<dim><suffix>.bin → <dim>/chunks<suffix>.bin
                // world_map_bounds_<dim>.bin    → <dim>/bounds.bin
                // world_map_small_<dim>.bin     → <dim>/small.bin
                String rest = n.substring((world + "_map_").length(), n.length() - 4);
                if (rest.startsWith("cache_")) {
                    int dim = rest.charAt(6) - '0';
                    String suffix = rest.substring(7);
                    java.io.File dst = new java.io.File(dimCacheDir(dbDir, dim),
                            "chunks" + suffix + ".bin");
                    f.renameTo(dst);
                } else if (rest.startsWith("bounds_")) {
                    int dim = rest.charAt(7) - '0';
                    f.renameTo(new java.io.File(dimCacheDir(dbDir, dim), "bounds.bin"));
                } else if (rest.startsWith("small_")) {
                    int dim = rest.charAt(6) - '0';
                    f.renameTo(new java.io.File(dimCacheDir(dbDir, dim), "small.bin"));
                }
            }
            Log.i(TAG, "旧缓存已迁移到新目录结构: " + old.length + " 个文件");
        } catch (Exception e) {
            Log.w(TAG, "缓存迁移失败", e);
        }
    }

    /** 初始化缓存目录（应用私有 files/map_cache/）。 */
    public static void initCacheDir(android.content.Context ctx) {
        try {
            java.io.File base = new java.io.File(ctx.getExternalFilesDir(null), "map_cache");
            if (!base.isDirectory() && !base.mkdirs()) {
                base = new java.io.File(ctx.getFilesDir(), "map_cache");
                base.mkdirs();
            }
            sCacheBase = base;
        } catch (Exception e) {
            Log.w(TAG, "缓存目录初始化失败", e);
        }
    }

    /** 清理世界目录里遗留的旧缓存文件（v7 前写在世界目录，膨胀世界体积）。 */
    public static void cleanupLegacyWorldCache(File dbDir) {
        File worldDir = dbDir.getParentFile();
        File[] files = worldDir != null ? worldDir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            if ((n.startsWith("map_cache_") || n.startsWith("map_bounds_"))
                    && n.endsWith(".bin")) {
                // v6 旧格式版本已不匹配必失效，直接删（缓存可再生）
                f.delete();
            }
        }
    }

    /** 缓存文件：应用私有目录 <世界名>_map_cache_<dim>.bin（不再写世界目录）。
     *  下界缓存文件名带渲染参数后缀（y 范围 + 剔除名单 hash）——不同设置
     *  各自缓存互不覆盖，切换设置不用每次重渲染。
     *  读取用当前全局参数；保存用 map.chunkCacheSuffix（渲染时定格）——
     *  切段时全局参数已改成新段，用全局参数保存会把旧段数据写进新段文件名。 */
    private static File chunkCacheFile(File dbDir, int dimension) {
        return chunkCacheFileFor(dbDir, dimension, cacheSuffixFor(dimension));
    }

    /** 下界"全部"段的缓存后缀（切段 fallback 显示用——切到未缓存段时
     *  先显示全部段的图，新段后台烘焙完成后自动切换）。 */
    public static String netherYallCacheSuffix() {
        StringBuilder sb = new StringBuilder("_yall");
        java.util.Set<String> ex = netherExcludeBlocks;
        if (ex != null && !ex.isEmpty()) {
            java.util.List<String> names = new java.util.ArrayList<>(ex);
            java.util.Collections.sort(names);
            sb.append("_x").append(Integer.toHexString(names.hashCode()));
        }
        return sb.toString();
    }

    /** 加载指定渲染参数后缀的 chunk 缓存（切段 fallback：读"全部"段）。 */
    public static WorldMap loadChunkCacheWithSuffix(File dbDir, int dimension,
                                                    String suffix) {
        File in = chunkCacheFileFor(dbDir, dimension, suffix);
        if (!in.isFile()) {
            return null;
        }
        return loadChunkCacheFile(in, dbDir, dimension);
    }

    /** 按给定渲染参数算后缀（map.chunkCacheSuffix 的取值来源）。 */
    public static String cacheSuffixFor(int dimension) {
        String suffix = "";
        if (dimension == DIM_NETHER && (netherYMin >= 0 || netherExcludeBlocks != null)) {
            StringBuilder sb = new StringBuilder("_y");
            sb.append(netherYMin >= 0 ? netherYMin + "-" + netherYMax : "all");
            java.util.Set<String> ex = netherExcludeBlocks;
            if (ex != null && !ex.isEmpty()) {
                java.util.List<String> names = new java.util.ArrayList<>(ex);
                java.util.Collections.sort(names);
                sb.append("_x").append(Integer.toHexString(names.hashCode()));
            }
            suffix = sb.toString();
        }
        // v413：坡度阴影进缓存后缀——开关切换后旧缓存自动作废，
        // 直接换缓存读秒生效（此前开关"无效"的根因：旧阴影渲染的
        // 缓存 chunk 没重烘，屏幕显示的还是旧数据）
        if (!enableShading) {
            suffix += "_ns";
        }
        return suffix;
    }

    /** 保存路径：map 创建时定格的渲染参数后缀（不是当前全局参数）。
     *  v395 新目录结构：<世界目录名>/<维度名>/chunks<suffix>.bin。 */
    private static File chunkCacheFileFor(File dbDir, int dimension, String suffix) {
        if (sCacheBase != null) {
            return new File(dimCacheDir(dbDir, dimension), "chunks" + suffix + ".bin");
        }
        return new File(dbDir.getParentFile(), "map_cache_" + dimension + suffix + ".bin");
    }

    // ---------------------------------------------------------------- 小世界全图缓存

    private static final int SMALL_CACHE_MAGIC = 0x4D437653; // "MCvs"
    // v2：v466-v472 回退水色/河床混合修复（旧缓存是绿色回退/紫灰/
    //      沙床浅蓝渲染结果，必须失效重渲）
    private static final int SMALL_CACHE_VERSION = 2;

    /** 小世界全图缓存文件（v395 新结构：<世界>/<维度>/small.bin）。
     *  v413：阴影开关进文件名——切换后小世界缓存也作废。 */
    private static File smallCacheFile(File dbDir, int dimension) {
        String ns = enableShading ? "" : "_ns";
        if (sCacheBase != null) {
            return new File(dimCacheDir(dbDir, dimension), "small" + ns + ".bin");
        }
        return new File(dbDir.getParentFile(), "map_small_" + dimension + ns + ".bin");
    }

    /** 保存小世界全图缓存（colors 数组调色板压缩——打开秒开的依据）。 */
    public static boolean saveSmallMapCache(WorldMap map, File dbDir, int dimension) {
        if (map == null || map.colors == null || dbDir == null) {
            return false;
        }
        File out = smallCacheFile(dbDir, dimension);
        synchronized (CACHE_SAVE_LOCK) {
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
                long[] fp = dbFingerprint(dbDir);
                dos.writeInt(SMALL_CACHE_MAGIC);
                dos.writeInt(SMALL_CACHE_VERSION);
                dos.writeInt(map.width);
                dos.writeInt(map.height);
                dos.writeInt(map.minBlockX);
                dos.writeInt(map.minBlockZ);
                dos.writeLong(fp[0]);
                dos.writeLong(fp[1]);
                writeLargePalette(dos, map.colors);
                boolean hasBiome = map.biomeColors != null;
                dos.writeBoolean(hasBiome);
                if (hasBiome) {
                    writeLargePalette(dos, map.biomeColors);
                }
                Log.i(TAG, "小世界缓存已保存: " + out.getName() + " "
                        + (out.length() / 1024 / 1024) + "MB");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "小世界缓存保存失败", e);
                return false;
            }
        }
    }

    /** 大数组调色板编码：色数(short) + 色值(4B×n) + 索引（n≤256 时 1B/像素，否则 2B）。 */
    private static void writeLargePalette(java.io.DataOutputStream dos, int[] pixels)
            throws java.io.IOException {
        java.util.HashMap<Integer, Integer> idx = new java.util.HashMap<>(512);
        int[] palette = new int[65536];
        int n = 0;
        int[] indices = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            Integer id = idx.get(pixels[i]);
            if (id == null) {
                id = n;
                if (n >= 65536) {
                    throw new java.io.IOException("too many colors");
                }
                palette[n] = pixels[i];
                idx.put(pixels[i], id);
                n++;
            }
            indices[i] = id;
        }
        dos.writeInt(n);
        for (int i = 0; i < n; i++) {
            dos.writeInt(palette[i]);
        }
        if (n <= 256) {
            for (int v : indices) {
                dos.writeByte(v);
            }
        } else {
            for (int v : indices) {
                dos.writeShort(v);
            }
        }
    }

    /** 大数组调色板解码（writeLargePalette 逆操作）。 */
    private static int[] readLargePalette(java.io.DataInputStream dis, int count)
            throws java.io.IOException {
        int n = dis.readInt();
        if (n < 1 || n > 65536) {
            throw new java.io.IOException("bad palette size " + n);
        }
        int[] palette = new int[n];
        for (int i = 0; i < n; i++) {
            palette[i] = dis.readInt();
        }
        int[] pixels = new int[count];
        if (n <= 256) {
            for (int i = 0; i < count; i++) {
                pixels[i] = palette[dis.readUnsignedByte()];
            }
        } else {
            for (int i = 0; i < count; i++) {
                int id = dis.readUnsignedShort();
                pixels[i] = id < n ? palette[id] : 0;
            }
        }
        return pixels;
    }

    /** 加载小世界全图缓存；db 变化或格式不符返回 null。
     *  allowStale=true 时 db 变化也返回旧图（调用方先显示旧图，
     *  后台重渲染后替换——玩家玩过一局后打开不用等 8-20 秒渲染）。 */
    public static WorldMap loadSmallMapCache(File dbDir, int dimension) {
        return loadSmallMapCache(dbDir, dimension, false);
    }

    public static WorldMap loadSmallMapCache(File dbDir, int dimension, boolean allowStale) {
        File in = smallCacheFile(dbDir, dimension);
        if (!in.isFile()) {
            return null;
        }
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(in)))) {
            if (dis.readInt() != SMALL_CACHE_MAGIC || dis.readInt() != SMALL_CACHE_VERSION) {
                return null;
            }
            int w = dis.readInt();
            int h = dis.readInt();
            int minX = dis.readInt();
            int minZ = dis.readInt();
            if (w <= 0 || h <= 0 || (long) w * h > 60L * 1024 * 1024) {
                return null;
            }
            long dbSize = dis.readLong();
            long dbMtime = dis.readLong();
            long[] fp = dbFingerprint(dbDir);
            boolean stale = fp[0] != dbSize || fp[1] != dbMtime;
            if (stale) {
                Log.i(TAG, "小世界缓存失效（db 已变化）"
                        + (allowStale ? "——先显示旧图" : ""));
                if (!allowStale) {
                    return null;
                }
            }
            int[] colors = readLargePalette(dis, w * h);
            int[] biome = null;
            if (dis.readBoolean()) {
                biome = readLargePalette(dis, w * h);
            }
            WorldMap map = new WorldMap(minX, minZ, w, h, colors, biome);
            map.cacheStale = stale;
            Log.i(TAG, "小世界缓存已加载: " + w + "x" + h + " (dim=" + dimension
                    + (stale ? ", 旧图待刷新" : "") + ")");
            return map;
        } catch (Exception e) {
            Log.w(TAG, "小世界缓存加载失败", e);
            return null;
        }
    }

    /** 删除指定维度的全部缓存文件（含 y 段后缀变体）。退出地图清理下界/末地缓存用。 */
    public static void deleteDimCacheFiles(File dbDir, int dimension) {
        try {
            // v395 新结构：删整个维度文件夹（<世界>/<维度>/）
            if (sCacheBase != null) {
                deleteRecursive(new File(dimCacheDir(dbDir, dimension), ""));
            } else {
                String prefix = "map_cache_" + dimension;
                java.io.File dir = dbDir.getParentFile();
                java.io.File[] files = dir != null ? dir.listFiles() : null;
                if (files != null) {
                    for (java.io.File f : files) {
                        if (f.getName().startsWith(prefix) && f.getName().endsWith(".bin")) {
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursive(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) {
                    deleteRecursive(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
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
     * complete：缓存是否完整（v19 完整性标志——烘焙完整跑完置 true，
     * 中断/落盘节流置 false；静默烘焙与打开判定据此决定是否补烘）。
     */
    public static boolean saveChunkCache(WorldMap map, File dbDir, int dimension,
                                         boolean complete) {
        if (map == null || map.chunkColors == null || map.chunkColors.isEmpty()) {
            return false;
        }
        // 保存用 map 创建时定格的渲染参数后缀（切段后全局参数已变，
        // 用全局参数会把旧段数据写进新段文件名）
        File out = chunkCacheFileFor(dbDir, dimension, map.chunkCacheSuffix);
        // 并发写互斥：烘焙线程（增量落盘）/退出保存/切维度保存可能
        // 同时写同一文件 → 文件损坏。
        // 同时先拷快照再写：保存遍历 chunkColors 期间烘焙/视口线程
        // 仍在 put 新 chunk——直接迭代 ConcurrentHashMap 会写出
        // chunk 数与数据不一致的损坏文件 → 下次 loadChunkCache 读
        // 失败 → 重新渲染 → 再保存损坏（"每次点进去都重新渲染"
        // 死循环的根因）
        Map<Long, int[]> snapshot = new HashMap<>(map.chunkColors);
        Map<Long, int[]> biomeSnapshot = null;
        if (map.chunkBiomeColors != null && !map.chunkBiomeColors.isEmpty()) {
            biomeSnapshot = new HashMap<>(map.chunkBiomeColors);
        }
        synchronized (CACHE_SAVE_LOCK) {
            // v398：原子写——先写临时文件再 rename 替换。直接写 chunks.bin
            // 时并发读（下次打开 loadChunkCache 与烘焙落盘节流撞车）读到
            // 半写文件 → EOF 损坏 → 打开重烘死循环的另一根因。
            // Linux rename 原子覆盖，读者永远看到完整文件（旧或新）
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
            long[] fp = dbFingerprint(dbDir);
            int minCx = map.minBlockX / 16;
            int minCz = map.minBlockZ / 16;
            int maxCx = minCx + map.width / 16 - 1;
            int maxCz = minCz + map.height / 16 - 1;
            dos.writeInt(MAP_CACHE_MAGIC);
            dos.writeInt(MAP_CACHE_VERSION);
            dos.writeInt(snapshot.size());
            dos.writeInt(minCx);
            dos.writeInt(minCz);
            dos.writeInt(maxCx);
            dos.writeInt(maxCz);
            dos.writeLong(fp[0]);
            dos.writeLong(fp[1]);
            // v18：db 文件指纹列表（增量更新的依据——sst 不可变，
            // 存档更新 = 新增文件/追加 log，对比找出变化文件）
            java.util.List<String> fps = dbFileFingerprintList(dbDir);
            map.dbFileFingerprints = fps;
            dos.writeInt(fps.size());
            for (String f : fps) {
                byte[] fb = f.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeShort(fb.length);
                dos.write(fb);
            }
            // v19：完整性标志（烘焙完整跑完 = true；节流/退出保存保持
            // map.cacheComplete——完整缓存退出后再存仍是完整）
            dos.writeBoolean(complete);
            boolean hasBiome = biomeSnapshot != null;
            dos.writeBoolean(hasBiome);
            // v20：colors 写 chunks.bin（打开秒读的关键数据）；
            // biome 拆到 chunks_biome.bin（打开不读——图层开启才读，
            // 打开提速 ~40%）
            for (Map.Entry<Long, int[]> e : snapshot.entrySet()) {
                dos.writeInt(unpackX(e.getKey()));
                dos.writeInt(unpackZ(e.getKey()));
                writePaletteChunk(dos, e.getValue());
            }
                if (!tmp.renameTo(out)) {
                    Log.w(TAG, "chunk 缓存 rename 失败: " + out.getName());
                    return false;
                }
                Log.i(TAG, "chunk 缓存已保存: " + out.getName() + " "
                        + (out.length() / 1024 / 1024) + "MB 文件指纹=" + fps.size());
            } catch (Exception e) {
                Log.w(TAG, "chunk 缓存保存失败", e);
                return false;
            }
            // v20：biome 独立文件（同样原子写；打开时延迟读）
            if (biomeSnapshot != null) {
                try {
                    saveChunkBiomeCacheInternal(out, biomeSnapshot);
                } catch (Exception e) {
                    Log.w(TAG, "biome 缓存保存失败", e);
                }
            }
        }
        return true;
    }

    /** v20：biome 独立缓存文件（与 chunks.bin 同目录同后缀）。 */
    private static File chunkBiomeCacheFile(File chunksFile) {
        String n = chunksFile.getName();
        int dot = n.lastIndexOf('.');
        return new File(chunksFile.getParentFile(),
                (dot > 0 ? n.substring(0, dot) : n) + "_biome.bin");
    }

    private static void saveChunkBiomeCacheInternal(File chunksFile,
                                                    Map<Long, int[]> biomeSnapshot)
            throws java.io.IOException {
        File out = chunkBiomeCacheFile(chunksFile);
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
            for (Map.Entry<Long, int[]> e : biomeSnapshot.entrySet()) {
                dos.writeInt(unpackX(e.getKey()));
                dos.writeInt(unpackZ(e.getKey()));
                writePaletteChunk(dos, e.getValue());
            }
            if (!tmp.renameTo(out)) {
                throw new java.io.IOException("rename 失败: " + out.getName());
            }
        }
    }

    /** 矿石标点缓存文件（v413：独立于 chunk 缓存——矿石检测与
     *  渲染参数无关，不随后缀；烘焙完整时一起落盘）。 */
    private static File oreMarkersFile(File dbDir, int dimension) {
        if (sCacheBase != null) {
            return new File(dimCacheDir(dbDir, dimension), "ore.bin");
        }
        return new File(dbDir.getParentFile(), "map_ore_" + dimension + ".bin");
    }

    /** v20：按需读 biome 独立缓存（biome 图层打开时调用；
     *  无文件/损坏返回 null）。 */
    public static Map<Long, int[]> loadChunkBiomeCache(File dbDir,
                                                       int dimension,
                                                       String suffix) {
        File in = chunkBiomeCacheFile(chunkCacheFileFor(dbDir, dimension, suffix));
        if (in == null || !in.isFile()) {
            return null;
        }
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(in)))) {
            Map<Long, int[]> out = new java.util.concurrent.ConcurrentHashMap<>();
            while (true) {
                try {
                    int cx = dis.readInt();
                    int cz = dis.readInt();
                    int[] bc = readPaletteChunk(dis);
                    out.put(pack(cx, cz), bc);
                } catch (java.io.EOFException eof) {
                    break;
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            Log.w(TAG, "biome 缓存加载失败", e);
            return null;
        }
    }

    /** v413：矿石标点落盘（烘焙完成/退出时保存）。 */
    public static void saveOreMarkers(WorldMap map, File dbDir, int dimension) {
        if (map == null || map.oreMarkers == null || map.oreMarkers.isEmpty()
                || dbDir == null) {
            return;
        }
        File out = oreMarkersFile(dbDir, dimension);
        synchronized (CACHE_SAVE_LOCK) {
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
                java.util.List<OreMarker> snapshot;
                synchronized (map) {
                    snapshot = new java.util.ArrayList<>(map.oreMarkers);
                }
                dos.writeInt(0x4F524533); // "ORE3"（v423：强制失效 v421 错位数据——Y/Z 参数反了存过错误坐标）
                dos.writeInt(snapshot.size());
                for (OreMarker m : snapshot) {
                    byte[] nb = m.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    dos.writeShort(nb.length);
                    dos.write(nb);
                    dos.writeInt(m.chunkX);
                    dos.writeInt(m.chunkZ);
                    dos.writeInt(m.blockX);
                    dos.writeInt(m.blockY);
                    dos.writeInt(m.blockZ);
                    dos.writeInt(m.count);
                }
                if (!tmp.renameTo(out)) {
                    Log.w(TAG, "矿石标点 rename 失败: " + out.getName());
                    return;
                }
                Log.i(TAG, "矿石标点已保存: " + snapshot.size() + " 个");
            } catch (Exception e) {
                Log.w(TAG, "矿石标点保存失败", e);
            }
        }
    }

    /** v413：读矿石标点缓存（无/损坏返回 null）。 */
    public static java.util.List<OreMarker> loadOreMarkers(File dbDir, int dimension) {
        File in = oreMarkersFile(dbDir, dimension);
        if (in == null || !in.isFile()) {
            return null;
        }
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(in)))) {
            int magic = dis.readInt();
            if (magic != 0x4F524531 && magic != 0x4F524532 && magic != 0x4F524533) {
                return null;
            }
            boolean hasY = magic == 0x4F524532 || magic == 0x4F524533;
            int count = dis.readInt();
            if (count < 0 || count > 5_000_000) {
                return null;
            }
            java.util.List<OreMarker> out = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int len = dis.readUnsignedShort();
                byte[] nb = new byte[len];
                dis.readFully(nb);
                String name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
                int cx = dis.readInt();
                int cz = dis.readInt();
                int bx = dis.readInt();
                int by = hasY ? dis.readInt() : -1;
                int bz = dis.readInt();
                int cnt = dis.readInt();
                out.add(new OreMarker(name, cx, cz, bx, by, bz, cnt));
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "矿石标点加载失败", e);
            return null;
        }
    }

    /** v436：确保矿石标记可用（缓存命中补扫）——已有标记返回；
     *  先读独立 ORE 缓存文件，无则流式解码矿石层（主世界 sub≤4、
     *  下界 sub≤7、末地跳过——与渲染路径同款窗口）补扫并落盘。
     *  后台线程调用；完成后 map.oreMarkers 同步回填。 */
    public static java.util.List<OreMarker> ensureOreMarkers(WorldMap map,
                                                             File dbDir,
                                                             int dimension) {
        if (map == null || dbDir == null || !dbDir.isDirectory()) {
            return null;
        }
        // v440：大世界不补扫——readAllEntries 全量读 183MB db 是
        // 内存/IO/CPU 洪峰（与视口渲染抢线程，卫星图加载变慢的
        // 根因）；大世界矿石由烘焙 worker 收集落盘
        if (dbFingerprint(dbDir)[0] > 20L * 1024 * 1024) {
            return null;
        }
        synchronized (map) {
            if (map.oreMarkers != null && !map.oreMarkers.isEmpty()) {
                return map.oreMarkers;
            }
        }
        java.util.List<OreMarker> ores = loadOreMarkers(dbDir, dimension);
        if (ores == null) {
            // 流式补扫：只解码矿石层（v432 流式窗口同款逻辑）
            List<LevelDBEntry> entries = null;
            try {
                entries = NativeLevelDb.readAllEntries(dbDir);
            } catch (Throwable ignored) {
            }
            if (entries == null) {
                try {
                    LevelDBReader reader = new LevelDBReader(dbDir);
                    entries = reader.readAllEntries();
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            if (entries == null) {
                return null;
            }
            final int oreMaxSub = dimension == DIM_NETHER ? 7 : 4;
            if (dimension == DIM_END) {
                return null; // 末地无矿石（v433）
            }
            ores = new java.util.ArrayList<>();
            for (LevelDBEntry entry : entries) {
                byte[] rawKey = entry.getKey().getRawKey();
                int[] ck = parseChunkKey(rawKey);
                if (ck == null || ck[2] != dimension || ck[3] < 0
                        || ck[3] > oreMaxSub || !isSubchunkKey(rawKey)) {
                    continue;
                }
                try {
                    SubChunk sc = decodeSubChunk(entry.getValue());
                    if (sc != null) {
                        java.util.Map<Integer, SubChunk> tmp =
                                new java.util.HashMap<>();
                        tmp.put(ck[3], sc);
                        collectChunkOres(tmp, ck[0], ck[1], dimension, ores);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "矿石补扫失败 chunk(" + ck[0] + "," + ck[1] + ")", e);
                }
            }
            // 落盘（下次直接读缓存）
            if (!ores.isEmpty()) {
                WorldMap tmp = new WorldMap(0, 0, 1, 1, null, null);
                tmp.oreMarkers = ores;
                saveOreMarkers(tmp, dbDir, dimension);
            }
        }
        synchronized (map) {
            map.oreMarkers = ores;
        }
        Log.i(TAG, "矿石标记已补全: " + ores.size() + " 个 (dim=" + dimension + ")");
        return ores;
    }

    /** db 文件指纹列表（"name:size:mtime"，名字排序稳定）。 */
    private static java.util.List<String> dbFileFingerprintList(File dbDir) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] files = dbDir.listFiles(f -> f.isFile()
                && (f.getName().endsWith(".ldb") || f.getName().endsWith(".sst")
                || f.getName().endsWith(".log")));
        if (files != null) {
            Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File f : files) {
                out.add(f.getName() + ":" + f.length() + ":" + f.lastModified());
            }
        }
        return out;
    }

    /** 对比 db 当前文件与缓存保存时的指纹——返回新文件/变化文件的名字集合。
     *  空集合 = 存档没变，缓存完全有效。 */
    public static java.util.Set<String> diffDbFiles(File dbDir, WorldMap cached) {
        java.util.Set<String> changed = new java.util.HashSet<>();
        if (dbDir == null || cached == null) {
            return changed;
        }
        java.util.List<String> now = dbFileFingerprintList(dbDir);
        java.util.Set<String> old = cached.dbFileFingerprints != null
                ? new java.util.HashSet<>(cached.dbFileFingerprints)
                : java.util.Collections.emptySet();
        for (String f : now) {
            if (!old.contains(f)) {
                changed.add(f.substring(0, f.indexOf(':')));
            }
        }
        return changed;
    }

    /** 扫变化文件的 key，收集指定维度受影响的 chunk 集合（增量更新：
     *  只重渲染这些 chunk，其余从缓存秒开）。 */
    public static java.util.Set<Long> collectChangedChunks(File dbDir,
                                                           java.util.Set<String> files,
                                                           int dimension) {
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        if (dbDir == null || files == null || files.isEmpty()) {
            return chunks;
        }
        try {
            LevelDBReader reader = new LevelDBReader(dbDir);
            List<byte[]> keys = reader.readKeysFromFiles(files, k -> {
                if (k == null || (k.length != 9 && k.length != 10
                        && k.length != 13 && k.length != 14)) {
                    return false;
                }
                int[] ck = parseChunkKey(k);
                return ck != null && ck[2] == dimension && isSubchunkKey(k);
            });
            reader.close();
            for (byte[] k : keys) {
                int[] ck = parseChunkKey(k);
                if (ck != null) {
                    chunks.add(pack(ck[0], ck[1]));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectChangedChunks 失败", e);
        }
        return chunks;
    }

    /** 缓存写入互斥（烘焙线程/退出保存/增量更新可能并发写同一文件）。 */
    private static final Object CACHE_SAVE_LOCK = new Object();

    /** 烘焙进度回调（每批渲染完成的 chunk 集合——UI 渐进显示用）。 */
    public interface BakeProgress {
        void onBatch(java.util.Set<Long> chunkKeys);

        /** 烘焙开始（v403 进度 HUD 用）：total = 待烘 chunk 总数。 */
        default void onStart(int total) {
        }
    }

    /** 烘焙视口优先队列：视口变化时外部（NbtViewerActivity）把视口
     *  附近的缺失 chunk 加入——烘焙线程先消费此队列（拖动时烘焙
     *  跟着渲染屏幕区域，替代已删除的视口按需渲染）。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Long>
            bakeViewportQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Set<Long> bakeViewportSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile long bakeViewportCx = Long.MIN_VALUE;
    private static volatile long bakeViewportCz = Long.MIN_VALUE;

    /** 视口变化上报：把视口中心 ±20 chunk 内的坐标存入优先队列。 */
    public static void bumpBakeViewport(int blockX, int blockZ) {
        bakeViewportCx = Math.floorDiv(blockX, 16);
        bakeViewportCz = Math.floorDiv(blockZ, 16);
        long ccx = bakeViewportCx;
        long ccz = bakeViewportCz;
        for (int dz = -20; dz <= 20; dz++) {
            for (int dx = -20; dx <= 20; dx++) {
                long key = ((ccx + dx) << 32) | ((ccz + dz) & 0xFFFFFFFFL);
                if (bakeViewportSeen.add(key)) {
                    bakeViewportQueue.add(key);
                }
            }
        }
        // 队列防膨胀：超过 2 万时清掉最旧的（视口快速移动时旧位置作废）
        while (bakeViewportQueue.size() > 20000) {
            bakeViewportQueue.poll();
            // 不清理 seen——重复 add 无害（烘焙端会检查已渲染跳过）
        }
    }

    /** chunk 色数据里是否有非透明像素（全透明 = 未生成/占位，不算已缓存）。 */
    private static boolean hasOpaque(int[] cc) {
        if (cc == null) {
            return false;
        }
        for (int c : cc) {
            if ((c & 0xFF000000) != 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 矿物热力图

    /** 矿物方块 → 热力色（矿石分布图层）。 */
    /** v413：矿石标点（图层显示为色块标记，可点击看详情）。
     *  blockX/blockY/blockZ 为该矿种在 chunk 内的首个位置（v421
     *  起含 Y 轴）。 */
    public static class OreMarker {
        public final String name;   // 方块名（短名）
        public final int chunkX;
        public final int chunkZ;
        public final int blockX;
        public final int blockY;
        public final int blockZ;
        public final int count;

        public OreMarker(String name, int chunkX, int chunkZ,
                         int blockX, int blockZ, int count) {
            this(name, chunkX, chunkZ, blockX, -1, blockZ, count);
        }

        public OreMarker(String name, int chunkX, int chunkZ,
                         int blockX, int blockY, int blockZ, int count) {
            this.name = name;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.count = count;
        }

        /** 标记色（ORE_COLORS 查表，未知洋红）。 */
        public int color() {
            Integer c = ORE_COLORS.get(name);
            return c != null ? c : 0xFFFF00FF;
        }
    }

    private static final java.util.Map<String, Integer> ORE_COLORS = new HashMap<>();
    static {
        ORE_COLORS.put("diamond_ore", 0xFF4AE8FF);      // 钻石：亮青
        ORE_COLORS.put("deepslate_diamond_ore", 0xFF4AE8FF);
        ORE_COLORS.put("emerald_ore", 0xFF3DFF6A);      // 绿宝石：亮绿
        ORE_COLORS.put("deepslate_emerald_ore", 0xFF3DFF6A);
        ORE_COLORS.put("gold_ore", 0xFFFFE24D);         // 金：金黄
        ORE_COLORS.put("deepslate_gold_ore", 0xFFFFE24D);
        ORE_COLORS.put("nether_gold_ore", 0xFFFFE24D);
        ORE_COLORS.put("iron_ore", 0xFFFFB08A);         // 铁：浅棕
        ORE_COLORS.put("deepslate_iron_ore", 0xFFFFB08A);
        ORE_COLORS.put("coal_ore", 0xFF9E9E9E);         // 煤：灰
        ORE_COLORS.put("deepslate_coal_ore", 0xFF9E9E9E);
        ORE_COLORS.put("copper_ore", 0xFFFFA64D);       // 铜：橙
        ORE_COLORS.put("deepslate_copper_ore", 0xFFFFA64D);
        ORE_COLORS.put("redstone_ore", 0xFFFF4D4D);     // 红石：红
        ORE_COLORS.put("deepslate_redstone_ore", 0xFFFF4D4D);
        ORE_COLORS.put("lit_redstone_ore", 0xFFFF4D4D);
        ORE_COLORS.put("lapis_ore", 0xFF4D5DFF);        // 青金石：深蓝
        ORE_COLORS.put("deepslate_lapis_ore", 0xFF4D5DFF);
        ORE_COLORS.put("quartz_ore", 0xFFFFF2F2);       // 下界石英：白
        ORE_COLORS.put("ancient_debris", 0xFFFFB84D);   // 远古残骸：橙黄
    }

    /** 矿物热力色（密度加权混合）：counts 为各矿物数量，返回单色或 0（无矿物）。 */
    private static int oreHeatColor(int[] counts) {
        long r = 0;
        long g = 0;
        long b = 0;
        long total = 0;
        int i = 0;
        for (java.util.Map.Entry<String, Integer> e : ORE_COLORS.entrySet()) {
            int c = counts[i++];
            if (c <= 0) {
                continue;
            }
            int col = e.getValue();
            r += (long) ((col >> 16) & 0xFF) * c;
            g += (long) ((col >> 8) & 0xFF) * c;
            b += (long) (col & 0xFF) * c;
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        r /= total;
        g /= total;
        b /= total;
        // 密度增强：矿物越多 alpha 越高（0.35~1.0）
        int alpha = (int) (255 * Math.min(1.0, 0.35 + total / 20.0));
        return (alpha << 24) | ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    /** 统计单个 chunk 的矿物数量（解码全部 subchunk 的 palette 索引
     *  线性扫描——16×16×N 层）。返回长度 = ORE_COLORS.size() 的计数。 */
    private static int[] countChunkOres(LevelDBReader reader, int cx, int cz,
                                        int dimension) {
        int[] counts = new int[ORE_COLORS.size()];
        try {
            List<LevelDBEntry> entries = reader.readChunk(cx, cz);
            Map<Integer, SubChunk> subs = new HashMap<>();
            for (LevelDBEntry e : entries) {
                byte[] rawKey = e.getKey().getRawKey();
                int[] ck = parseChunkKey(rawKey);
                if (ck == null || ck[2] != dimension || !isSubchunkKey(rawKey)) {
                    continue;
                }
                SubChunk sc = decodeSubChunk(e.getValue());
                if (sc != null) {
                    subs.put(ck[3], sc);
                }
            }
            // 热力图统计全层矿物（不受下界 y 段设置影响——找矿要全量）
            java.util.Map<String, Integer> idxByName = new HashMap<>();
            int i = 0;
            for (String n : ORE_COLORS.keySet()) {
                idxByName.put(n, i++);
            }
            for (SubChunk sc : subs.values()) {
                for (String pn : sc.palette) {
                    if (pn == null) {
                        continue;
                    }
                    Integer oreIdx = null;
                    for (String oreName : ORE_COLORS.keySet()) {
                        if (pn.endsWith(oreName)) {
                            oreIdx = idxByName.get(oreName);
                            break;
                        }
                    }
                    if (oreIdx == null) {
                        continue;
                    }
                    // 该 palette 索引对应的方块数：遍历数据区统计
                    int palIdx = indexOf(sc.palette, pn);
                    for (int j = 0; j < 4096; j++) {
                        if (sc.getIndex(j >> 8, j & 15, (j >> 4) & 15) == palIdx) {
                            counts[oreIdx]++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "矿物统计 chunk(" + cx + "," + cz + ") 失败", e);
        }
        return counts;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (v.equals(arr[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 后台烘焙矿物热力图：逐 chunk 统计矿物 → 热力色存 map.chunkOreColors。
     *  与地形烘焙同款结构（视口优先+距离排序+中断即停），不落盘
     *  （开关图层时按需生成，图层关闭即清）。 */
    public static Thread bakeOreLayer(final File dbDir, final int dimension,
                                      final WorldMap map,
                                      final BakeProgress progress,
                                      final Runnable onDone) {
        Thread t = new Thread(() -> {
            try {
                if (map == null) {
                    if (onDone != null) {
                        onDone.run();
                    }
                    return;
                }
                if (map.chunkOreColors == null) {
                    map.chunkOreColors = new java.util.concurrent.ConcurrentHashMap<>();
                }
                LevelDBReader reader = new LevelDBReader(dbDir);
                List<byte[]> subKeys = reader.readKeys(k -> {
                    int[] ck = parseChunkKey(k);
                    return ck != null && ck[2] == dimension && isSubchunkKey(k);
                });
                reader.close();
                java.util.List<Long> ordered = new java.util.ArrayList<>();
                java.util.Set<Long> seen = new java.util.HashSet<>();
                for (byte[] k : subKeys) {
                    int[] ck = parseChunkKey(k);
                    if (ck != null && seen.add(pack(ck[0], ck[1]))) {
                        ordered.add(pack(ck[0], ck[1]));
                    }
                }
                subKeys = null;
                // v420：画圆中心 = 玩家位置（24 倍视图在玩家处）→ 出生点 → bounds 中心
                final long centerX = map.playerBlockX >= 0
                        ? Math.floorDiv(map.playerBlockX, 16)
                        : map.spawnBlockX >= 0
                            ? Math.floorDiv(map.spawnBlockX, 16)
                            : map.minBlockX / 16L + map.width / 32L;
                final long centerZ = map.playerBlockZ >= 0
                        ? Math.floorDiv(map.playerBlockZ, 16)
                        : map.spawnBlockZ >= 0
                            ? Math.floorDiv(map.spawnBlockZ, 16)
                            : map.minBlockZ / 16L + map.height / 32L;
                ordered.sort((a, b) -> {
                    long ax = (a >> 32) - centerX;
                    long az = (int) (long) a - centerZ;
                    long bx = (b >> 32) - centerX;
                    long bz = (int) (long) b - centerZ;
                    return Long.compare(ax * ax + az * az, bx * bx + bz * bz);
                });
                Log.i(TAG, "矿物热力图烘焙: dim=" + dimension + " chunk=" + ordered.size());
                final java.util.concurrent.atomic.AtomicInteger nextIdx =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                final int bakeThreads = Math.min(6, Math.max(3,
                        Runtime.getRuntime().availableProcessors()));
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(bakeThreads, r -> {
                            Thread bt = new Thread(r, "ore-bake");
                            bt.setPriority(Thread.MIN_PRIORITY);
                            return bt;
                        });
                java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(bakeThreads);
                for (int wi = 0; wi < bakeThreads; wi++) {
                    pool.execute(() -> {
                        LevelDBReader wReader = new LevelDBReader(dbDir);
                        java.util.Set<Long> batch = new java.util.HashSet<>();
                        try {
                            while (true) {
                                if (Thread.currentThread().isInterrupted()
                                        || map.chunkOreColors == null) {
                                    // 图层被关闭（Activity 清 null）：立即停止
                                    break;
                                }
                                long key;
                                Long vpKey = bakeViewportQueue.poll();
                                if (vpKey != null) {
                                    if (map.chunkOreColors.containsKey(vpKey)) {
                                        continue;
                                    }
                                    key = vpKey;
                                } else {
                                    int i = nextIdx.getAndIncrement();
                                    if (i >= ordered.size()) {
                                        break;
                                    }
                                    key = ordered.get(i);
                                }
                                int[] counts = countChunkOres(wReader,
                                        (int) (key >> 32), (int) (long) key, dimension);
                                int color = oreHeatColor(counts);
                                if (color != 0 && map.chunkOreColors != null) {
                                    map.chunkOreColors.put(key, color);
                                    batch.add(key);
                                }
                                if (batch.size() >= 50 && progress != null) {
                                    java.util.Set<Long> out =
                                            new java.util.HashSet<>(batch);
                                    batch.clear();
                                    progress.onBatch(out);
                                }
                            }
                        } finally {
                            if (!batch.isEmpty() && progress != null) {
                                progress.onBatch(new java.util.HashSet<>(batch));
                            }
                            wReader.close();
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                pool.shutdown();
                Log.i(TAG, "矿物热力图完成: " + map.chunkOreColors.size() + " chunk 含矿物");
            } catch (Throwable err) {
                Log.w(TAG, "矿物热力图烘焙失败", err);
            } finally {
                if (onDone != null) {
                    onDone.run();
                }
            }
        }, "ore-bake");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return t;
    }

    /**
     * 后台烘焙：把存档直接转成可视化缓存（用户新思路——不用打开卫星图
     * 跑第一遍，导入世界后/无缓存打开时后台跑）。
     * 逐 chunk 渲染（renderChunkOnDemand），内存 O(1) 无流式渲染的
     * 18 万条目 OOM 风险；每 200 chunk 增量落盘，中断即停（已保存
     * 部分下次继续）。
     * targetMap 非 null 时烘焙结果直接 put 进共享 map（屏幕渐进显示，
     * 与预渲染同款的"以世界为圆心向外刷"效果——渲染顺序按距离从
     * 原点向外）；null 时内部建 map 只落盘（导入后静默烘焙）。
     * 多线程并行（烘焙单线程 2.4 万 chunk 要 20-40 分钟，"渲染到
     * 一半停住"的根因——4 线程低优先级交错取距离排序后的 chunk）。
     */
    public static Thread bakeWorldCache(final File dbDir, final int dimension,
                                        final WorldMap targetMap,
                                        final BakeProgress progress,
                                        final Runnable onDone) {
        return bakeWorldCache(dbDir, dimension, targetMap, progress, onDone, false);
    }

    /** v398：forceRebake=true 时忽略已有缓存全部重渲染（渲染参数变化
     *  ——如坡度阴影开关——后缓存作废重烘）。内存保留旧缓存显示，
     *  烘焙完新渲染逐 chunk 覆盖；中途退出落盘 = 旧缓存 ∪ 已烘部分，
     *  磁盘缓存不缩水（此前清空内存后中断烘焙会覆盖写缩水磁盘缓存）。 */
    public static Thread bakeWorldCache(final File dbDir, final int dimension,
                                        final WorldMap targetMap,
                                        final BakeProgress progress,
                                        final Runnable onDone,
                                        final boolean forceRebake) {
        return bakeWorldCache(dbDir, dimension, targetMap, progress, onDone,
                forceRebake, BakeMode.ACTIVE);
    }

    /** 烘焙模式（v400）：
     *  ACTIVE=卫星图内全速——8 线程大核绑定高优先级（接近原实时渲染
     *  速度，卫星图内烘焙是唯一渲染源）；
     *  SILENT=后台静默——2 线程低优先级 + 每 chunk 限速，启动器
     *  运行期间慢速补烘（导入/游玩过的存档，无需打开卫星图）。 */
    public enum BakeMode { ACTIVE, SILENT }

    public static Thread bakeWorldCache(final File dbDir, final int dimension,
                                        final WorldMap targetMap,
                                        final BakeProgress progress,
                                        final Runnable onDone,
                                        final boolean forceRebake,
                                        final BakeMode mode) {
        final boolean silent = mode == BakeMode.SILENT;
        Thread t = new Thread(() -> {
            // worker 池引用在 try 外声明：中断/异常路径需要 shutdownNow
            // 停掉 worker（此前 latch.await 抛异常后 pool 未关闭，worker
            // 泄漏继续烘焙并触发落盘节流——与退出保存/下次打开读取并发
            // 写 chunks.bin，半写文件 EOF 损坏）
            final java.util.concurrent.ExecutorService[] poolRef =
                    new java.util.concurrent.ExecutorService[1];
            try {
                final WorldMap map;
                WorldMap m = targetMap != null ? targetMap
                        : loadChunkCache(dbDir, dimension);
                if (m == null) {
                    m = buildBoundsOnly(dbDir, dimension);
                }
                map = m;
                if (map == null) {
                    Log.i(TAG, "烘焙失败: 无 bounds (dim=" + dimension + ")");
                    if (onDone != null) {
                        onDone.run();
                    }
                    return;
                }
                map.chunkSourceDir = dbDir;
                map.chunkSourceDim = dimension;
                map.chunkCacheSuffix = cacheSuffixFor(dimension);
                if (map.chunkBiomeColors == null) {
                    map.chunkBiomeColors = new java.util.concurrent.ConcurrentHashMap<>();
                }
                // v403：subKeys 全扫（大世界 18 万 key 要 5-10 秒）移到
                // 后台并行——打开卫星图时视口队列 chunk 立即渲染（1-2
                // 秒出图），不等全扫（"大地图等半天"的主要延迟之一）
                final java.util.List<Long> ordered = new java.util.ArrayList<>();
                final boolean[] keysReady = {false};
                final int[] alreadyCachedHolder = {0};
                // v420：画圆中心 = 玩家位置（24 倍视图在玩家处）→ 出生点 → bounds 中心
                final long centerX = map.playerBlockX >= 0
                        ? Math.floorDiv(map.playerBlockX, 16)
                        : map.spawnBlockX >= 0
                            ? Math.floorDiv(map.spawnBlockX, 16)
                            : map.minBlockX / 16L + map.width / 32L;
                final long centerZ = map.playerBlockZ >= 0
                        ? Math.floorDiv(map.playerBlockZ, 16)
                        : map.spawnBlockZ >= 0
                            ? Math.floorDiv(map.spawnBlockZ, 16)
                            : map.minBlockZ / 16L + map.height / 32L;
                Thread scanThread = new Thread(() -> {
                    try {
                        LevelDBReader scanReader = new LevelDBReader(dbDir);
                        List<byte[]> subKeys = scanReader.readKeys(k -> {
                            int[] ck = parseChunkKey(k);
                            return ck != null && ck[2] == dimension && isSubchunkKey(k);
                        });
                        scanReader.close();
                        java.util.Set<Long> seen = new java.util.HashSet<>();
                        int alreadyCached = 0;
                        synchronized (ordered) {
                            for (byte[] k : subKeys) {
                                int[] ck = parseChunkKey(k);
                                if (ck != null && seen.add(pack(ck[0], ck[1]))) {
                                    long key = pack(ck[0], ck[1]);
                                    // 缺啥补啥：缓存已有（且非全透明占位）
                                    // 的 chunk 跳过不重渲染——缓存 90% 时
                                    // 只烘缺失的 10%（force 模式全部重烘）
                                    int[] cached = map.chunkColors.get(key);
                                    if (!forceRebake && cached != null
                                            && hasOpaque(cached)) {
                                        alreadyCached++;
                                        continue;
                                    }
                                    ordered.add(key);
                                }
                            }
                            // 距离排序：从地图中心向外烘焙（圆形铺开）。
                            // 曾用世界原点 (0,0)——主世界 (0,0) 在整图
                            // 左下角，铺开圆只有一角在屏幕内，视觉呈长条状
                            ordered.sort((a, b) -> {
                                long ax = (a >> 32) - centerX;
                                long az = (int) (long) a - centerZ;
                                long bx = (b >> 32) - centerX;
                                long bz = (int) (long) b - centerZ;
                                return Long.compare(ax * ax + az * az,
                                        bx * bx + bz * bz);
                            });
                            alreadyCachedHolder[0] = alreadyCached;
                            keysReady[0] = true;
                            ordered.notifyAll();
                        }
                        Log.i(TAG, "烘焙开始: dim=" + dimension + " 缺失="
                                + ordered.size() + " 已有=" + alreadyCached
                                + " 中心=(" + centerX + "," + centerZ + ")");
                        if (progress != null) {
                            progress.onStart(ordered.size());
                        }
                    } catch (Throwable err) {
                        Log.w(TAG, "烘焙 key 扫描失败 (dim=" + dimension + ")", err);
                        synchronized (ordered) {
                            keysReady[0] = true; // 失败也放行（空列表直接结束）
                            ordered.notifyAll();
                        }
                    }
                }, "world-bake-scan");
                scanThread.setPriority(silent ? Thread.MIN_PRIORITY
                        : Thread.NORM_PRIORITY);
                scanThread.start();
                // 多线程并行烘焙：原子索引交错取 chunk（保持距离序），
                // 每线程独立 reader（LevelDBReader 无状态线程安全）
                final java.util.concurrent.atomic.AtomicInteger nextIdx =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                final java.util.concurrent.atomic.AtomicInteger rendered =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                // 线程数（v403：ordered 在后台扫描中，大小未知——
                // 直接按核数上限建；SILENT 按核数 2-3 线程——v412
                // "静默快一点"：设备核多给 3 线程）
                int cpus = Runtime.getRuntime().availableProcessors();
                final int bakeThreads = silent ? (cpus >= 8 ? 3 : 2)
                        : Math.min(8, Math.max(6, cpus));
                poolRef[0] =
                        java.util.concurrent.Executors.newFixedThreadPool(bakeThreads, r -> {
                            Thread bt = new Thread(r, "world-bake-w");
                            bt.setPriority(silent ? Thread.MIN_PRIORITY
                                    : Thread.MAX_PRIORITY);
                            return bt;
                        });
                // force 模式全部重烘：视口队列与 ordered 可能重叠
                // （视口优先烘过、nextIdx 又轮到），bakedKeys 并发去重
                // 防重复渲染；非 force 靠 chunkColors.containsKey 去重
                final java.util.Set<Long> bakedKeys = forceRebake
                        ? java.util.concurrent.ConcurrentHashMap.newKeySet() : null;
                java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(bakeThreads);
                for (int wi = 0; wi < bakeThreads; wi++) {
                    // ACTIVE：只给前 bigCoreCount 个 worker 绑大核，其余
                    // 由系统调度到小核——8 线程全绑 4 个大核互相挤兑
                    // （大小核全部用上，v293 教训）
                    final boolean bindBig = !silent
                            && wi < org.levimc.launcher.core.CpuScheduler.bigCoreCount;
                    poolRef[0].execute(() -> {
                        if (bindBig) {
                            // CpuScheduler 反射 sched_setaffinity，失败静默
                            org.levimc.launcher.core.CpuScheduler
                                    .pinCurrentThreadToBigCores();
                        }
                        LevelDBReader wReader = new LevelDBReader(dbDir);
                        java.util.Set<Long> batch = new java.util.HashSet<>();
                        int sinceSave = 0;
                        final long[] lastSaveAt = {System.currentTimeMillis()};
                        try {
                            while (true) {
                                if (Thread.currentThread().isInterrupted()) {
                                    Log.i(TAG, "烘焙 worker 中断退出");
                                    break;
                                }
                                // 视口优先（v392）：先消费视口优先队列——
                                // 拖动时烘焙跟着渲染屏幕区域（替代已删除
                                // 的视口按需渲染），已渲染的跳过
                                long key;
                                Long vpKey = bakeViewportQueue.poll();
                                if (vpKey != null) {
                                    if (bakedKeys != null
                                            ? !bakedKeys.add(vpKey)
                                            : map.chunkColors.containsKey(vpKey)) {
                                        continue;
                                    }
                                    key = vpKey;
                                } else {
                                    // v403：key 列表后台扫描中——等扫描
                                    // 完成再按距离序消费（视口队列已空）
                                    synchronized (ordered) {
                                        while (!keysReady[0]
                                                && !Thread.currentThread().isInterrupted()) {
                                            try {
                                                ordered.wait(100);
                                            } catch (InterruptedException e) {
                                                break;
                                            }
                                        }
                                    }
                                    if (!keysReady[0] || Thread.currentThread().isInterrupted()) {
                                        Log.i(TAG, "烘焙 worker 等待退出: ready="
                                                + keysReady[0] + " 中断="
                                                + Thread.currentThread().isInterrupted());
                                        break;
                                    }
                                    int i = nextIdx.getAndIncrement();
                                    if (i >= ordered.size()) {
                                        Log.i(TAG, "烘焙 worker 队列耗尽: " + i
                                                + "/" + ordered.size());
                                        break;
                                    }
                                    key = ordered.get(i);
                                    if (bakedKeys != null && !bakedKeys.add(key)) {
                                        continue;
                                    }
                                }
                                int cx = (int) (key >> 32);
                                int cz = (int) (long) key;
                                try {
                                    // v413：烘焙顺带收集矿石标点（每 chunk
                                    // 一次地下层扫描），完成后落盘 ore.bin
                                    java.util.List<OreMarker> ores =
                                            new java.util.ArrayList<>(2);
                                    int[][] res = renderChunkOnDemand(
                                            wReader, cx, cz, dimension, ores);
                                    if (!ores.isEmpty()) {
                                        synchronized (map) {
                                            if (map.oreMarkers == null) {
                                                map.oreMarkers =
                                                        new java.util.ArrayList<>();
                                            }
                                            map.oreMarkers.addAll(ores);
                                        }
                                    }
                                    int[] colors = res != null ? res[0] : null;
                                    if (colors != null) {
                                        map.chunkColors.put(key, colors);
                                        if (res[1] != null) {
                                            map.chunkBiomeColors.put(key, res[1]);
                                        }
                                        rendered.incrementAndGet();
                                        batch.add(key);
                                    }
                                } catch (Throwable e) {
                                    // v406：catch Throwable——OOM 等 Error
                                    // 杀死 worker 线程会导致 latch 提前释放
                                    // "烘焙完成 渲染=23/24844" 假完成
                                    Log.w(TAG, "烘焙 chunk(" + cx + "," + cz + ") 失败", e);
                                }
                                // v412：静默烘焙按 UI 帧率动态调速——
                                // 掉帧多就限速（sleep），流畅就全速。
                                // 每 8 个 chunk 查一次掉帧率（查询廉价）
                                if (silent && (rendered.get() & 7) == 0) {
                                    float jr = SilentBakeManager.jankRatio();
                                    if (jr > 0.4f) {
                                        try {
                                            Thread.sleep(60);
                                        } catch (InterruptedException e) {
                                            break;
                                        }
                                    } else if (jr > 0.15f) {
                                        try {
                                            Thread.sleep(15);
                                        } catch (InterruptedException e) {
                                            break;
                                        }
                                    }
                                }
                                // 批通知（UI 渐进）+ 增量落盘
                                if (batch.size() >= 50 && progress != null) {
                                    java.util.Set<Long> out =
                                            new java.util.HashSet<>(batch);
                                    batch.clear();
                                    progress.onBatch(out);
                                }
                                // 落盘节流：每 200 chunk 全量写 25MB 文件 =
                                // 6 线程轮流写+互相等锁的 IO 风暴（"卡"
                                // 的根源）——改每 2000 chunk 或 20 秒一次
                                long nowMs = System.currentTimeMillis();
                                if (++sinceSave >= 2000
                                        || nowMs - lastSaveAt[0] > 20000) {
                                    sinceSave = 0;
                                    lastSaveAt[0] = nowMs;
                                    // 烘焙中落盘：完整性标志 false
                                    saveChunkCache(map, dbDir, dimension, false);
                                }
                            }
                        } finally {
                            if (!batch.isEmpty() && progress != null) {
                                progress.onBatch(new java.util.HashSet<>(batch));
                            }
                            wReader.close();
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                poolRef[0].shutdown();
                // 完整烘焙收尾落盘：完整性标志 true（v19 静默烘焙/
                // 打开补烘判定的依据）
                map.cacheComplete = true;
                saveChunkCache(map, dbDir, dimension, true);
                // v413：矿石标点一起落盘（烘焙全图时收集全）
                saveOreMarkers(map, dbDir, dimension);
                Log.i(TAG, "烘焙完成: dim=" + dimension + " 渲染=" + rendered.get()
                        + "/" + ordered.size());
            } catch (InterruptedException ie) {
                // 中断是正常路径（切维度/退出/参数变化）——shutdownNow
                // 停 worker 防泄漏（I 级，不打堆栈噪音）
                if (poolRef[0] != null) {
                    poolRef[0].shutdownNow();
                }
                Log.i(TAG, "烘焙中断: dim=" + dimension);
            } catch (Throwable err) {
                if (poolRef[0] != null) {
                    poolRef[0].shutdownNow();
                }
                Log.w(TAG, "烘焙失败 (dim=" + dimension + ")", err);
            } finally {
                if (onDone != null) {
                    onDone.run();
                }
            }
        }, "world-bake-" + dimension);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return t;
    }

    /** 增量更新缓存：重渲染变化 chunk 并覆盖（调用方决定保存时机）。
     *  reader 复用（调用方 ThreadLocal）；返回重渲染的 chunk 数。 */

    public static int refreshChangedChunks(LevelDBReader reader, File dbDir,
                                           WorldMap map, java.util.Set<Long> chunks,
                                           int dimension) {
        if (reader == null || map == null || map.chunkColors == null
                || chunks == null || chunks.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (Long key : chunks) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            int cx = (int) (key >> 32);
            int cz = (int) (long) key;
            try {
                int[][] res = renderChunkOnDemand(reader, cx, cz, dimension);
                int[] colors = res != null ? res[0] : null;
                // 变化的 chunk 直接覆盖（旧数据已过时）
                if (colors != null) {
                    map.chunkColors.put(key, colors);
                    if (res[1] != null && map.chunkBiomeColors != null) {
                        map.chunkBiomeColors.put(key, res[1]);
                    }
                }
                done++;
            } catch (Exception e) {
                Log.w(TAG, "增量更新 chunk(" + cx + "," + cz + ") 失败", e);
            }
        }
        return done;
    }

    /** 调色板编码写一个 chunk 的 256 像素（v7）：1B 色数 + n×4B 色值 +
     *  256×1B 索引。无损，地形 chunk 通常 5-30 色 → 256+~100B，
     * 比 256×4B 原始 ARGB 小 3-4 倍。 */
    private static void writePaletteChunk(java.io.DataOutputStream dos, int[] cc)
            throws java.io.IOException {
        java.util.HashMap<Integer, Integer> idx = new java.util.HashMap<>(64);
        int[] palette = new int[256];
        byte[] indices = new byte[256];
        int n = 0;
        for (int i = 0; i < 256; i++) {
            Integer id = idx.get(cc[i]);
            if (id == null) {
                id = n;
                palette[n] = cc[i];
                idx.put(cc[i], id);
                n++;
            }
            indices[i] = id.byteValue();
        }
        // 色数最大 256（16×16 全不同色）——writeByte 会溢出，用 short
        dos.writeShort(n);
        for (int i = 0; i < n; i++) {
            dos.writeInt(palette[i]);
        }
        dos.write(indices);
    }

    /** 调色板解码（writePaletteChunk 的逆操作）。 */
    private static int[] readPaletteChunk(java.io.DataInputStream dis)
            throws java.io.IOException {
        int n = dis.readUnsignedShort();
        if (n < 1 || n > 256) {
            throw new java.io.IOException("bad palette size " + n);
        }
        int[] palette = new int[n];
        for (int i = 0; i < n; i++) {
            palette[i] = dis.readInt();
        }
        int[] cc = new int[256];
        for (int i = 0; i < 256; i++) {
            int id = dis.readUnsignedByte();
            cc[i] = id < n ? palette[id] : palette[0];
        }
        return cc;
    }

    /** 加载磁盘 chunk 缓存。
     *  v18 起：db 变化不再直接失效——带文件指纹列表，打开时 diffDbFiles
     *  找出新文件增量更新对应 chunk（用户玩过存档后只重渲染变化区域）。 */
    public static WorldMap loadChunkCache(File dbDir, int dimension) {
        File in = chunkCacheFile(dbDir, dimension);
        if (!in.isFile()) {
            return null;
        }
        return loadChunkCacheFile(in, dbDir, dimension);
    }

    /** 从指定缓存文件加载（loadChunkCache 与切段 fallback 共用）。 */
    private static WorldMap loadChunkCacheFile(File in, File dbDir, int dimension) {
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
            // v18：文件指纹列表（增量更新依据）。读完后 dbSize/mtime
            // 变化不再失效——diffDbFiles 精确对比每个文件
            int fpCount = dis.readInt();
            java.util.List<String> fps = new java.util.ArrayList<>(fpCount);
            for (int i = 0; i < fpCount; i++) {
                int len = dis.readUnsignedShort();
                byte[] fb = new byte[len];
                dis.readFully(fb);
                fps.add(new String(fb, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (fpCount > 0 && (fps.get(0).isEmpty() || !fps.get(0).contains(":"))) {
                Log.w(TAG, "chunk 缓存文件列表损坏，重新渲染");
                return null;
            }
            long[] fp = dbFingerprint(dbDir);
            if (fpCount == 0 && (fp[0] != dbSize || fp[1] != dbMtime)) {
                Log.i(TAG, "chunk 缓存失效（db 已变化），重新渲染");
                return null;
            }
            Map<Long, int[]> chunkColors = new java.util.concurrent.ConcurrentHashMap<>(count * 2);
            // v19：完整性标志（v18 旧文件无此字节，读失败按 false——
            // 静默烘焙会补烘一次并打上标志）
            boolean complete = false;
            try {
                complete = dis.readBoolean();
            } catch (Exception e) {
                complete = false;
            }
            boolean hasBiome = false;
            try {
                hasBiome = dis.readBoolean();
            } catch (Exception e) {
                hasBiome = false; // 兼容异常情况
            }
            // v20：colors 只读——biome 拆在独立文件 chunks_biome.bin
            // （biome 图层开启时按需读，打开提速 ~40%）
            for (int i = 0; i < count; i++) {
                int cx = dis.readInt();
                int cz = dis.readInt();
                int[] cc = readPaletteChunk(dis);
                chunkColors.put(pack(cx, cz), cc);
            }
            WorldMap map = new WorldMap(minCx * 16, minCz * 16,
                    (maxCx - minCx + 1) * 16, (maxCz - minCz + 1) * 16, null, null);
            map.chunkColors = chunkColors;
            map.chunkBiomeColors = null; // biome 延迟加载
            map.cacheHasBiome = hasBiome;
            map.blockScale = 1;
            map.dbFileFingerprints = fps;
            map.cacheComplete = complete;
            // v413：矿石标点独立缓存（与 chunk 缓存同目录）
            map.oreMarkers = loadOreMarkers(dbDir, dimension);
            Log.i(TAG, "chunk 缓存已加载: " + count + " chunk (biome=" + hasBiome
                    + ", 文件指纹=" + fps.size() + ", 完整=" + complete + ")");
            return map;
        } catch (Exception e) {
            Log.w(TAG, "chunk 缓存加载失败", e);
            return null;
        }
    }

    /** 缓存完整性状态（v400 静默烘焙队列用）。 */
    public enum CacheStatus {
        /** 完整有效：chunk 数充足且 db 指纹未变 */
        COMPLETE,
        /** chunk 数不足（烘焙曾中断） */
        INCOMPLETE,
        /** db 指纹变化（存档玩过/更新过） */
        STALE,
        /** 无缓存文件 */
        MISSING,
        /** 头部损坏（重烘覆盖） */
        BROKEN
    }

    /** 轻量缓存状态检查：只读头部字段与指纹列表，不读 chunk 数据
     *  ——静默烘焙扫描全部世界时近 O(1) 开销（避免 loadChunkCache
     *  完整读 15MB 文件）。 */
    public static CacheStatus peekChunkCacheStatus(File dbDir, int dimension) {
        File in = chunkCacheFile(dbDir, dimension);
        if (in == null || !in.isFile()) {
            return CacheStatus.MISSING;
        }
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(in)))) {
            if (dis.readInt() != MAP_CACHE_MAGIC || dis.readInt() != MAP_CACHE_VERSION) {
                return CacheStatus.BROKEN;
            }
            int count = dis.readInt();
            if (count < 1 || count > 10_000_000) {
                return CacheStatus.BROKEN;
            }
            int minCx = dis.readInt();
            int minCz = dis.readInt();
            int maxCx = dis.readInt();
            int maxCz = dis.readInt();
            long dbSize = dis.readLong();
            long dbMtime = dis.readLong();
            int fpCount = dis.readInt();
            java.util.List<String> fps = new java.util.ArrayList<>(fpCount);
            for (int i = 0; i < fpCount; i++) {
                int len = dis.readUnsignedShort();
                byte[] fb = new byte[len];
                dis.readFully(fb);
                fps.add(new String(fb, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (fpCount > 0 && (fps.get(0).isEmpty() || !fps.get(0).contains(":"))) {
                return CacheStatus.BROKEN;
            }
            if (fpCount == 0) {
                long[] fp = dbFingerprint(dbDir);
                if (fp[0] != dbSize || fp[1] != dbMtime) {
                    return CacheStatus.STALE;
                }
            } else {
                // 与当前 db 文件列表精确对比（sst 不可变，追加即变化）
                if (!fps.equals(dbFileFingerprintList(dbDir))) {
                    return CacheStatus.STALE;
                }
            }
            // v19：完整性用头部标志判定（烘焙完整跑完落盘时置 true）。
            // 此前用 bounds 面积×60% 比 chunk 数——稀疏世界（TK 实际
            // 24844 chunk 只占 bounds 外包矩形面积的 2.5%）永远判"不足"
            // → 静默烘焙每次启动空转 readKeys
            boolean complete = false;
            try {
                complete = dis.readBoolean();
            } catch (Exception e) {
                complete = false; // v18 旧文件无标志 → 补烘一次打上标志
            }
            return complete ? CacheStatus.COMPLETE : CacheStatus.INCOMPLETE;
        } catch (Exception e) {
            return CacheStatus.BROKEN;
        }
    }

    /** 世界范围小文件：首次 readKeys 后缓存，之后打开免扫描。 */
    private static File boundsFile(File dbDir, int dimension) {
        if (sCacheBase != null) {
            // v395 新结构：<世界>/<维度>/bounds.bin
            return new File(dimCacheDir(dbDir, dimension), "bounds.bin");
        }
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
                    // v403 诊断："地图不可用"的根因——记录 db 内容
                    // （无 subchunk key = 空世界/未游玩/非 LevelDB/损坏）
                    File[] files = dbDir != null ? dbDir.listFiles() : null;
                    int n = files != null ? files.length : -1;
                    StringBuilder names = new StringBuilder();
                    if (files != null) {
                        int shown = 0;
                        for (File f : files) {
                            if (shown++ >= 8) {
                                names.append("…");
                                break;
                            }
                            if (f.isFile()) {
                                names.append(f.getName()).append('(')
                                        .append(f.length()).append(") ");
                            } else {
                                names.append(f.getName()).append("/ ");
                            }
                        }
                    }
                    Log.w(TAG, "bounds 扫描无 chunk key: dim=" + dimension
                            + " db=" + dbDir + " 文件数=" + n + " [" + names + "]");
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
        return renderChunkOnDemand(reader, cx, cz, dimension, null);
    }

    /** v413：oreSink 非空时把本 chunk 检测到的矿石标点加入。 */
    public static int[][] renderChunkOnDemand(LevelDBReader reader, int cx, int cz,
                                              int dimension,
                                              java.util.List<OreMarker> oreSink) {
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
                // 1.26 下界/末地大部分 chunk 没有 0x2b/0x2d 高度图 key
                // （实测 TK 世界下界 7752 个有 subchunk 数据的 chunk 只有
                // 8299 个高度图 key，且 (0,0) 等大量 chunk 完全没有）——
                // 此前直接 return null → 视口按需渲染全部空白（大世界
                // 下界/末地"连区块网格都不显示"的根因）。surfaceColor
                // 从顶向下找方块本来就不依赖高度图；有 subchunk 数据
                // 时合成高度图供阴影/窗口裁剪使用
                if (subs.isEmpty()) {
                    return null; // 真·未生成 chunk（无 subchunk 数据）
                }
                hmap = synthesizeHeightMap(subs, dimension);
            }
            // 结构特征检测（视口按需渲染路径同样要做——主世界大世界
            // 不走流式渲染，沙漠神殿/前哨站没有专门 key 只能靠 palette）
            detectOnDemandStructure(cx, cz, subs, dimension);
            // v435：矿石收集必须在窗口裁剪之前——此前 collectChunkOres
            // 在 removeIf 之后执行，地下矿石层（sub≤4）已被裁掉（山地
            // chunk nMin=surfaceSub-4>4），烘焙收集的矿石标记全丢——
            // "矿石热力图层延迟显示/不显示"的根因（注释原意"窗口裁剪
            // 前扫描"但代码顺序与注释矛盾）
            if (oreSink != null) {
                collectChunkOres(subs, cx, cz, dimension, oreSink);
            }
            // 窗口裁剪（下界全留）：以高度图推算的地表层为中心，向下 2 层
            // （河床/海底）到实际最高 sub（树冠/建筑）。之前用「实际最高 sub ±2」
            // ——树/建筑让 maxSub 偏离地表，地表层被裁掉，海洋/平原 chunk
            // 回退 biome 色显示成大片水蓝（"y 轴高度错乱"根因）
            if (dimension == DIM_NETHER) {
                // v420：有高度图的下界 chunk 用表面窗口（渲染提速——
                // 手机下界"烘焙延迟/不显示"的优化；surfaceColor 从顶
                // 向下找表面，深层只在表面全空时才需要）。无高度图
                // （1.26 大量 chunk）保持全量供 synthesizeHeightMap
                if (hmap != null) {
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
                    final int nMin = Math.max(0, surfaceSub - 4);
                    final int nMax = Math.max(surfaceSub + 2, maxSub);
                    subs.keySet().removeIf(s -> s < nMin || s > nMax);
                } else {
                    // 下界：默认全量（设置页可选 y 范围加速）
                    applyNetherWindow(subs);
                }
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
                final int fMin = surfaceSub - 4; // v372：-2 → -4（见流式路径注释）
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
            // v435：矿石收集已移到窗口裁剪前（见上）——此位置删除
            // [0]=卫星色 [1]=biome 图层色（大世界按需渲染此前不生成
            // biome 数据——biome 图层打开后无内容显示的根因）
            return new int[][]{colors, biomeCols};
        } catch (Exception e) {
            Log.w(TAG, "按需渲染 chunk(" + cx + "," + cz + ") 失败", e);
            return null;
        }
    }

    /** v421：统计 chunk 内矿石（每矿种一个标点，精确位置 + 数量）。
     *  逐方块扫描恢复（v417 调色板扫描导致同区块多矿种标点位置
     *  重叠只见一个）——只扫矿石层（主世界 sub ≤ 4，下界全层），
     *  每 chunk 2 万次 getIndex 约 0.4ms，8 线程烘焙影响 <1%。 */
    private static void collectChunkOres(Map<Integer, SubChunk> subs, int cx,
                                         int cz, int dimension,
                                         java.util.List<OreMarker> sink) {
        if (subs == null || subs.isEmpty() || sink == null) {
            return;
        }
        // v433（模拟发现）：末地无矿石——白扫 5068 层（TK 实测）；
        // 下界矿石全在浅层（石英 y 10-117 / 金 y≤29 / 远古残骸
        // y 8-22 → sub≤7）——15 层全扫减半
        if (dimension == DIM_END) {
            return;
        }
        int maxSub = dimension == DIM_NETHER ? 7 : 4;
        java.util.Map<String, int[]> found = new java.util.HashMap<>();
        for (Map.Entry<Integer, SubChunk> e : subs.entrySet()) {
            if (e.getKey() > maxSub) {
                continue;
            }
            SubChunk sub = e.getValue();
            if (sub == null || sub.palette == null) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        int idx = sub.getIndex(x, y, z);
                        String name = idx >= 0 && idx < sub.palette.length
                                ? sub.palette[idx] : null;
                        if (name == null) {
                            continue;
                        }
                        // v432：零分配粗筛——此前每方块都 substring 再
                        // 查表（数千万次临时 String 是手机 OOM 的垃圾源）；
                        // 矿石名必含 "ore"，ancient_debris 例外
                        if (name.indexOf("ore") < 0 && name.indexOf("debris") < 0) {
                            continue;
                        }
                        // palette 名带 minecraft: 前缀（色表 key 不带）
                        String shortName = name.startsWith("minecraft:")
                                ? name.substring(10) : name;
                        if (!ORE_COLORS.containsKey(shortName)) {
                            continue;
                        }
                        int[] acc = found.get(shortName);
                        if (acc == null) {
                            found.put(shortName, new int[]{1,
                                    cx * 16 + x, e.getKey() * 16 + y,
                                    cz * 16 + z});
                        } else {
                            acc[0]++;
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, int[]> e : found.entrySet()) {
            int[] v = e.getValue();
            sink.add(new OreMarker(e.getKey(), cx, cz, v[1], v[2], v[3], v[0]));
        }
    }

    /** 3D 体素区域数据：每列从顶向下 N 层方块（颜色 + y + 方块名）。 */
    public static final class VoxelColumn {
        public final int[] colors; // 从顶向下的方块颜色（不含空气）
        public final int[] ys;
        public final String[] names; // 方块全名（纹理贴图用）

        VoxelColumn(int[] colors, int[] ys, String[] names) {
            this.colors = colors;
            this.ys = ys;
            this.names = names;
        }
    }

    /**
     * 渲染 3D 体素视图数据：以 (centerX, centerZ) 为中心的 size×size 方块区域，
     * 每列从顶向下收集 depth 层非空气方块（等距投影用）。
     */
    public static VoxelColumn[][] renderVoxelRegion(File dbDir, int centerX, int centerZ,
                                                    int dimension, int size, int depth) {
        try {
            int half = size / 2;
            int minCx = Math.floorDiv(centerX - half, 16);
            int maxCx = Math.floorDiv(centerX + half - 1, 16);
            int minCz = Math.floorDiv(centerZ - half, 16);
            int maxCz = Math.floorDiv(centerZ + half - 1, 16);
            LevelDBReader reader = new LevelDBReader(dbDir);
            try {
                // 区域覆盖的 chunk 全解码（不窗口裁剪——3D 需要完整高度）。
                // biome 也要读：草方块/树叶按群系色调 tint，否则色表灰度
                // 显示成灰色像石头（樱花林渲染出石头的根因之一）
                Map<Long, int[]> hmapByChunk = new HashMap<>();
                Map<Long, byte[]> biomeByChunk = new HashMap<>();
                Map<Long, Map<Integer, SubChunk>> subsByChunk = new HashMap<>();
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int cx = minCx; cx <= maxCx; cx++) {
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
                                    int type = (rawKey.length == 13
                                            ? rawKey[12] : rawKey[8]) & 0xFF;
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
                            }
                        }
                        if (hmap != null) {
                            long key = pack(cx, cz);
                            hmapByChunk.put(key, hmap);
                            if (biomes != null) {
                                biomeByChunk.put(key, biomes);
                            }
                            subsByChunk.put(key, subs);
                        }
                    }
                }
                int startX = centerX - half;
                int startZ = centerZ - half;
                VoxelColumn[][] out = new VoxelColumn[size][size];
                for (int dz = 0; dz < size; dz++) {
                    for (int dx = 0; dx < size; dx++) {
                        int wx = startX + dx;
                        int wz = startZ + dz;
                        int cx = Math.floorDiv(wx, 16);
                        int cz = Math.floorDiv(wz, 16);
                        long key = pack(cx, cz);
                        int[] hmap = hmapByChunk.get(key);
                        Map<Integer, SubChunk> subs = subsByChunk.get(key);
                        int lx = wx - cx * 16;
                        int lz = wz - cz * 16;
                        int[] colors = new int[depth];
                        int[] ys = new int[depth];
                        String[] names = new String[depth];
                        java.util.Arrays.fill(colors, 0);
                        int n = 0;
                        // yStart 与 surfaceColor 同款：从实际最高 subchunk 顶
                        // 向下（y320 封顶）——不能用 hmap（生成器预测值 127~201，
                        // 实际方块只到地表/树冠，从预测值向下找会错过树冠/
                        // 渲染出石头——樱花区域渲染石头的根因之二）
                        int maxSubTop = Integer.MIN_VALUE;
                        for (Integer s : subs.keySet()) {
                            if (s > maxSubTop) {
                                maxSubTop = s;
                            }
                        }
                        int yStart = maxSubTop > Integer.MIN_VALUE
                                ? Math.min(maxSubTop * 16 + 15, 320) : 319;
                        byte[] biomes = biomeByChunk.get(key);
                        int biomeId = biomes != null ? biomes[(lz << 4) | lx] & 0xFF : -1;
                        for (int y = yStart; y >= -64 && n < depth; y--) {
                            SubChunk sub = subs.get(Math.floorDiv(y, 16));
                            if (sub == null) {
                                continue;
                            }
                            int localY = y - Math.floorDiv(y, 16) * 16;
                            int idx = sub.getIndex(lx, localY, lz);
                            String name = idx < sub.palette.length ? sub.palette[idx] : null;
                            if (name == null || isAirName(name)) {
                                continue;
                            }
                            boolean isWater = name.equals("minecraft:water")
                                    || name.equals("minecraft:flowing_water");
                            if (isWater) {
                                // 水面：统一水色（色表灰度模板无 biome 会显示灰），
                                // 收集后停止向下——否则海洋区域把海床 14 层
                                // 全渲染成乱石堆（3D 视图错位的根因）
                                colors[n] = 0xFF4B8CEB;
                                ys[n] = y;
                                names[n] = name;
                                n++;
                                break;
                            }
                            colors[n] = tintColor(name, colorForBlock(name), biomeId);
                            ys[n] = y;
                            names[n] = name;
                            n++;
                        }
                        out[dz][dx] = new VoxelColumn(colors, ys, names);
                    }
                }
                return out;
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "renderVoxelRegion 失败", t);
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
        if (!enableStructureDetection) {
            return; // v397 设置区开关：关闭 palette 特征结构检测
        }
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

    /** Bedrock 实体标识符 → 中文名（1.26 全量 156 个，含 villager_v2 等
     *  Bedrock 实际标识符——地图标签/结构详情/HTML 导出统一用）。 */
    public static final java.util.Map<String, String> ENTITY_ZH =
            new java.util.HashMap<>();
    static {
        String[][] pairs = {
                {"agent", "智能体"}, {"alex", "亚历克斯"}, {"allay", "悦灵"}, {"anvil", "铁砧"},
                {"area_effect_cloud", "区域效果云"}, {"armadillo", "犰狳"}, {"armor_stand", "盔甲架"},
                {"armorer", "盔甲匠"}, {"arrow", "箭"}, {"axolotl", "美西螈"}, {"balloon", "气球"},
                {"barnacle", "藤壶"}, {"bat", "蝙蝠"}, {"bee", "蜜蜂"}, {"blaze", "烈焰人"},
                {"boat", "船"}, {"bogged", "沼泽骷髅"}, {"breeze", "旋风人"}, {"butcher", "屠夫"},
                {"camel", "骆驼"}, {"camera", "相机"}, {"cartographer", "制图师"}, {"cat", "猫"},
                {"cave_spider", "洞穴蜘蛛"}, {"chest_boat", "运输船"}, {"chest_minecart", "运输矿车"},
                {"chicken", "鸡"}, {"cleric", "牧师"}, {"cluckshroom", "咯咯菇"}, {"cod", "鳕鱼"},
                {"command_block_minecart", "命令方块矿车"}, {"copper_golem", "铜傀儡"}, {"cow", "牛"},
                {"creaking", "嘎吱怪"}, {"creeper", "苦力怕"}, {"dolphin", "海豚"}, {"donkey", "驴"},
                {"dragon_fireball", "末影龙火球"}, {"drowned", "溺尸"}, {"efe", "埃菲"},
                {"elder_guardian", "远古守卫者"}, {"ender_crystal", "末影水晶"}, {"enderman", "末影人"},
                {"endermite", "末影螨"}, {"evocation_illager", "唤魔者"}, {"falling_block", "下落的方块"},
                {"farmer", "农民"}, {"fireball", "火球"}, {"firefly", "萤火虫"},
                {"fireworks_rocket", "烟花火箭"}, {"fisherman", "渔夫"}, {"fletcher", "制箭师"},
                {"fox", "狐狸"}, {"frog", "青蛙"}, {"ghast", "恶魂"}, {"glow_squid", "发光鱿鱼"},
                {"goat", "山羊"}, {"guardian", "守卫者"}, {"happy_ghast", "快乐恶魂"},
                {"herobrine", "Herobrine"}, {"hoglin", "疣猪兽"}, {"hopper_minecart", "漏斗矿车"},
                {"horse", "马"}, {"husk", "尸壳"}, {"ice_bomb", "冰弹"}, {"iceologer", "冰术士"},
                {"illusioner", "幻术师"}, {"iron_golem", "铁傀儡"}, {"item", "掉落物"},
                {"jeb_", "Jeb"}, {"jellie", "Jellie"}, {"johnny", "Johnny"}, {"kai", "凯"},
                {"leash_knot", "拴绳结"}, {"leatherworker", "皮匠"}, {"librarian", "图书管理员"},
                {"lightning", "闪电"}, {"llama", "羊驼"}, {"magma_cube", "岩浆怪"},
                {"makena", "梅克娜"}, {"mars", "马尔斯"}, {"meerkat", "猫鼬"}, {"merl", "梅尔"},
                {"minecart", "矿车"}, {"moobloom", "哞花"}, {"moolip", "哞唇"},
                {"mooshroom", "哞菇"}, {"mule", "骡"}, {"nautilus", "鹦鹉螺"}, {"noor", "努尔"},
                {"npc", "NPC"}, {"ocelot", "豹猫"}, {"ominous_item_spawner", "不祥物品生成器"},
                {"ostrich", "鸵鸟"}, {"painting", "画"}, {"panda", "熊猫"}, {"parrot", "鹦鹉"},
                {"phantom", "幻翼"}, {"pig", "猪"}, {"piglin", "猪灵"}, {"piglin_brute", "猪灵蛮兵"},
                {"pillager", "掠夺者"}, {"player", "玩家"}, {"polar_bear", "北极熊"},
                {"pufferfish", "河豚"}, {"rabbit", "兔子"}, {"rana", "拉娜"}, {"rascal", "捣蛋鬼"},
                {"ravager", "劫掠兽"}, {"salmon", "鲑鱼"}, {"scaffolding", "脚手架"},
                {"sheep", "绵羊"}, {"shepherd", "牧羊人"}, {"shulker", "潜影贝"},
                {"shulker_bullet", "潜影贝导弹"}, {"silverfish", "蠹虫"}, {"skeleton", "骷髅"},
                {"skeleton_horse", "骷髅马"}, {"slime", "史莱姆"}, {"small_fireball", "小火球"},
                {"sniffer", "嗅探兽"}, {"snow_golem", "雪傀儡"}, {"snowball", "雪球"},
                {"spider", "蜘蛛"}, {"squid", "鱿鱼"}, {"stray", "流髑"}, {"strider", "炽足兽"},
                {"sunny", "桑尼"}, {"tadpole", "蝌蚪"}, {"thrown_trident", "三叉戟"},
                {"tnt", "TNT"}, {"tnt_minecart", "TNT矿车"}, {"toast", "Toast"},
                {"trader_llama", "行商羊驼"}, {"tripod_camera", "三脚架相机"},
                {"tropicalfish", "热带鱼"}, {"turtle", "海龟"}, {"vex", "恼鬼"},
                {"villager", "村民"}, {"villager_v2", "村民"}, {"vindicator", "卫道士"},
                {"vulture", "秃鹫"}, {"wandering_trader", "流浪商人"}, {"warden", "监守者"},
                {"wind_charge_projectile", "风弹"}, {"witch", "女巫"}, {"wither", "凋灵"},
                {"wither_skeleton", "凋零骷髅"}, {"wither_skull", "凋灵之首"},
                {"wither_skull_dangerous", "危险凋灵之首"}, {"wolf", "狼"}, {"xp_orb", "经验球"},
                {"zoglin", "僵尸疣猪兽"}, {"zombie", "僵尸"}, {"zombie_horse", "僵尸马"},
                {"zombie_pigman", "僵尸猪人"}, {"zombie_villager", "僵尸村民"},
                {"zombie_villager_v2", "僵尸村民"},
        };
        for (String[] p : pairs) {
            ENTITY_ZH.put(p[0], p[1]);
        }
    }

    /** 实体名 → 中文（未知返回原名）。 */
    public static String entityLabelZh(String name) {
        String zh = ENTITY_ZH.get(name);
        return zh != null ? zh : name;
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
            int glassY = -1;
            int glassColor = 0;
            int glassAlpha = 255;
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
                // 忽略光源方块（设置页开关）：火把/灯笼/蜡烛等非固体光源
                // 俯视图渲染成黄色杂点——生电建筑周围插满火把时"边缘一圈
                // 黄色"的根因。视为空气向下穿透显示地表（卫星图语义）
                if (ignoreLightBlocks && isLightSourceName(name)) {
                    continue;
                }
                // 下界剔除黑名单（设置页可选）：视为空气向下穿透，
                // 用于看穿下界岩/灵魂沙显示矿物与洞穴。
                // null = 默认硬编码剔除 bedrock+netherrack（基岩天花板 + 大面
                // 积深红下界岩盖住地形细节）
                if (dimension == DIM_NETHER) {
                    java.util.Set<String> ex = netherExcludeBlocks;
                    if (ex == null) {
                        if (name.equals("minecraft:bedrock") || name.endsWith("bedrock")
                                || name.equals("minecraft:netherrack")) {
                            continue;
                        }
                    } else if (ex.contains(name)) {
                        continue;
                    }
                }
                int color = tintColor(name, colorForBlock(name), biomeId);
                if (isWaterName(name)) {
                    if (glassY >= 0) {
                        // 冰面下是水（冻洋）：冰色按 alpha 混在水色上
                        return blendColors(glassColor, color,
                                Math.min(glassAlpha / 255f, 0.9f));
                    }
                    if (waterY < 0) {
                        waterY = y;
                        waterColor = color;
                    }
                    continue; // 继续向下找河床（solid）
                }
                if (waterY >= 0) {
                    // 水覆盖：河床色 + 水面色（maptile.cpp applyWaterOverlay）
                    // v434：用户要求水色再调高——水深 1→0.55 / 2→0.75 /
                    // 2.75+→0.9 封顶（v433 是 0.45/0.65/0.85；v426 原始
                    // 0.15/0.3/0.85）。用户确认满意后此曲线定死不再改
                    float opacity = Math.min(0.2f * (waterY - y) + 0.35f, 0.9f);
                    return blendColors(waterColor, color, opacity);
                }
                // 半透明方块（玻璃/冰，bedrockmap 色表 alpha<255）：不直接
                // 返回——继续向下找固体，再按 alpha 混合。此前玻璃强制
                // 不透明 0xFFC8D8E8，建筑玻璃顶渲染成大块浅蓝（"建筑附近
                // 颜色异常"根因之一）；混合版 = 玻璃色薄纱透出屋内结构
                Integer semiAlpha = blockAlphaTable.get(name);
                if (semiAlpha != null) {
                    if (glassY < 0) {
                        glassY = y;
                        glassColor = color;
                        glassAlpha = semiAlpha;
                    }
                    continue;
                }
                if (glassY >= 0) {
                    return blendColors(glassColor, color,
                            Math.min(glassAlpha / 255f, 0.9f));
                }
                return color;
            }
            if (waterY >= 0) {
                // v469：整列只有水（河床无数据）——此前直接返回纯水色，
                // 海边区块"海的纯色、没有海底的东西"（用户：少套了一层
                // 滤镜）。模拟河床 + 水覆盖混合，与正常海列"海底+海滤
                // 镜"观感一致。
                // v472：扫描到 y=-64 都没找到河床 → 真实水深必然 ≥
                // waterY+64，按 v434 定稿曲线（min(0.2×水深+0.35,0.9)）
                // 直接封顶 0.9。此前用 SEA_LEVEL-height 算深度是错的
                // （高度图存海面 63 非海底 → depth=1 → op=0.55），
                // 45% 亮沙透出成浅蓝灰斑块，与深蓝海对比"太突兀"。
                // 河床色用砾石（与实测海床一致）。
                return blendColors(waterColor, SEA_BED_COLOR, 0.9f);
            }
            if (glassY >= 0) {
                // 玻璃下窗口内无固体（高塔/刷怪塔玻璃顶——下方悬空超过
                // 窗口下界 32 方块）：此前返回纯玻璃色 0xFFAFD5DB，
                // 生电建筑大片"海晶蓝"的根因。回退该列 biome 主色
                // （地表观感）而不是玻璃色
                return biomeColor != 0 ? biomeColor : glassColor;
            }
        }
        // 无 subchunk 数据或找不到方块：
        // 高度 > 0 的已生成 chunk 用 biome 色（BTR BiomeRenderer 风格）；
        // 高度 0 = 未生成区域 → 主世界渲染为海洋（BTR 对 0x2d 全 0 的行为）
        if (height > 0 && biomeColor != 0) {
            // v466：海平面以下的回退列优先显示水色（用户要求"海的
            // 颜色优先"）——1.26 存档海区大量列无方块数据（本地
            // 模拟实测 ~9% 列走此回退），biome 草色在海里成绿色
            // 纯色块。v468：不是纯水色——模拟河床（沙色）+ 水覆盖
            // 混合（与正常渲染列同款曲线），保持"海底+海滤镜"观感
            // （纯水色没有海底内容，用户反馈像一块色）。
            if (dimension == DIM_OVERWORLD && height <= SEA_LEVEL) {
                // v471：水色必须与正常渲染列完全同一条计算链——水是
                // 灰色模板（色表 water_still_grey 230,230,230）× 群系
                // 水色 tint（用户指出：灰模板套群系色才显示颜色，同
                // Levi 图标灰模板+自定义色）。v470 误用 COLOR_WATER
                // (23,33,122) 深蓝紫 → 紫灰色；v468 误用 tint 原值。
                // 这里调用与正常列一致的 tintColor 链。
                // v472：水深不可知（1.26 未生成海区块高度图存海面 63
                // 非海底，方块数据全 air）→ 按 v434 定稿曲线封顶 0.9
                // 渲染成深海，河床用砾石色与实测海床一致。此前
                // depth=max(1,63-63)=1 → op=0.55，45% 亮沙透出成浅
                // 蓝灰斑块嵌在深蓝海里（用户：太突兀）。
                int waterC = tintColor("minecraft:water",
                        colorForBlock("minecraft:water"), biomeId);
                return blendColors(waterC, SEA_BED_COLOR, 0.9f);
            }
            // v467：陆地回退列按"草地灰度模板 × tint"输出——此前直接
            // 返回 tint 原值（如 extreme_hills (138,182,137)），比正常
            // 渲染列（147 模板 × tint ≈ (80,105,79)）亮 ~40%，森林里
            // 出现比周围"偏亮偏土"的色块（用户反馈）。统一乘模板后
            // 与正常草色亮度一致，阴影在 assembleMap 统一应用。
            int[] ft = biomeTintTable.get(biomeId);
            if (ft != null && ft[3] >= 0) {
                return multiplyTint(GRASS_TEMPLATE_COLOR, ft, 3);
            }
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
        // v472：与回退水/尾支统一——同样模拟砾石河床 + 深海 op 0.9，
        // 否则此出口输出纯水色 (67,126,212)，与旁边正常海
        // (74,126,203) 仍有色差边界。
        return height <= SEA_LEVEL
                ? blendColors(
                        tintColor("minecraft:water", colorForBlock("minecraft:water"), biomeId),
                        SEA_BED_COLOR, 0.9f)
                : COLOR_BACKGROUND;
    }

    private static boolean isAirName(String name) {
        return name.endsWith("air"); // minecraft:air / cave_air / void_air
    }

    /** 非固体光源方块（忽略光源开关用）：俯视图渲染成黄色/亮色杂点。
     *  固体光源（萤石/海晶灯/菌光体/南瓜灯）是真实建筑方块，不忽略——
     *  穿透会显示地下。 */
    private static boolean isLightSourceName(String name) {
        return name.equals("minecraft:torch")
                || name.equals("minecraft:soul_torch")
                || name.equals("minecraft:redstone_torch")
                || name.equals("minecraft:lantern")
                || name.equals("minecraft:soul_lantern")
                || name.equals("minecraft:end_rod")
                || name.endsWith("_candle")
                || name.equals("minecraft:fire")
                || name.equals("minecraft:soul_fire")
                || name.equals("minecraft:glow_lichen")
                || name.equals("minecraft:light_block");
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
        // 活塞臂碰撞体（隐形占位方块，色表无条目）——此前落 fallback 灰
        // 0xFF7F7F7F，刷石机等活塞结构旁"大片灰色"根因（实测 hrd 734+190 处）
        if (name.equals("minecraft:piston_arm_collision")
                || name.equals("minecraft:sticky_piston_arm_collision")) return 0xFFA89070;
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
            if (bits == 0 && (header & 1) == 0) {
                // 1.26 单值存储：整层同一方块，palette 条目直接跟在 header
                // 后（无字数据、无 4B 计数）。实测末地 sub=1/2 len=60：
                // 09 01 01 00 | 0A 00 00 08 04 00 "name" 13 00
                // "minecraft:end_stone"——此前按 bits<1 直接 return null，
                // 末地大量 subchunk 解码失败 → 渲染全透明（"末地只渲染
                // 主岛"的根因：主岛有正常 bits 层所以能渲染，end_stone
                // 单值层全失败）
                if (p + 3 > value.length) {
                    if (s == 1) {
                        break;
                    }
                    return null;
                }
                int t0 = value[p] & 0xFF;
                int nl = (value[p + 1] & 0xFF) | ((value[p + 2] & 0xFF) << 8);
                int pe = p + 3 + nl;
                if (pe > value.length) {
                    return null;
                }
                String sname = extractPaletteName(value, pe, t0);
                pe = skipNbtPayload(value, pe, t0);
                if (pe < 0) {
                    return null;
                }
                p = pe;
                String[] single = new String[]{sname != null ? sname : "minecraft:air"};
                if (s == 0) {
                    primary = new SubChunk(0, new byte[0], single);
                } else if (primary != null) {
                    primary.waterLayer = new SubChunk(0, new byte[0], single);
                }
                continue;
            }
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
        // v425：PNG 超过 ~2MB 时降采样（HTML 查看器 WebView 的
        // data: URL 有大小限制）。修复 v421 隐患：createScaledBitmap
        // OOM 返回 null 导致 NPE 导出失败；recycle 逻辑统一在最后
        android.graphics.Bitmap forPng = pngBmp;
        boolean downsampled = false;
        java.io.ByteArrayOutputStream outBos = new java.io.ByteArrayOutputStream();
        try {
            forPng.compress(Bitmap.CompressFormat.PNG, 75, outBos);
            if (outBos.size() > 2 * 1024 * 1024) {
                int sw = Math.max(1, forPng.getWidth() / 2);
                int sh = Math.max(1, forPng.getHeight() / 2);
                android.graphics.Bitmap down =
                        android.graphics.Bitmap.createScaledBitmap(forPng, sw, sh, true);
                if (down != null) {
                    forPng = down;
                    downsampled = true;
                    outBos.reset();
                    forPng.compress(Bitmap.CompressFormat.PNG, 75, outBos);
                    Log.i(TAG, "导出 PNG 降采样: " + pngBmp.getWidth() + "x"
                            + pngBmp.getHeight() + " → " + sw + "x" + sh);
                }
            }
        } finally {
            if (downsampled) {
                pngBmp.recycle(); // 降采样后原图不再使用
            }
        }
        String b64 = android.util.Base64.encodeToString(outBos.toByteArray(),
                android.util.Base64.NO_WRAP);
        outBos.close();
        // 编码完成位图不再需要（未降采样时 forPng 即 pngBmp）
        forPng.recycle();

        // 2) 世界范围（[Z,X] 顺序：lat=Z、lng=X）
        int minX = map.minBlockX;
        int minZ = map.minBlockZ;
        int maxX = minX + map.width;
        int maxZ = minZ + map.height;
        StringBuilder html = new StringBuilder(16384);
        // Leaflet 库内联（assets/leaflet/）——此前走 unpkg CDN，平板离线/
        // 网络不稳时 leaflet.js 加载失败，导出文件打开地图完全空白
        // （"导出成功但地图不显示"的根因）
        String leafletCss = readAssetSafely("leaflet/leaflet.css");
        String leafletJs = readAssetSafely("leaflet/leaflet.js");
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(title)).append("</title>")
                .append("<style>").append(leafletCss).append("</style>")
                .append("<script>").append(leafletJs).append("</script>")
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
                // Leaflet bounds = [[south, west], [north, east]]。v416：
                // CRS.Simple 的 lat 轴向上、PNG 像素 z 轴向下——两者
                // 方向相反，标记 z 不取反会上下偏移 2×|z-中心|（用户
                // "出生点/玩家位置向下偏移很多"的根因）。统一 lat=-z
                .append("var BOUNDS=[[-").append(maxZ).append(',').append(minX).append("],[-")
                .append(minZ).append(',').append(maxX).append("]];")
                .append("L.imageOverlay('data:image/png;base64,").append(b64)
                .append("',BOUNDS).addTo(map);")
                .append("var FZ=map.getBoundsZoom(BOUNDS);")
                // v425：恢复 L.circle（用户提供参考 HTML 同款写法——
                // v413 的 divIcon 在部分 HTML 查看器 WebView 不兼容
                // "打不开"的根因）。radius 随 zoom 换算保持像素观感；
                // lat=-z（v416 坐标对齐）
                .append("function mkCircle(z,x,opts){var px=opts.px||8;delete opts.px;")
                .append("var c=L.circle([-z,x],L.extend({radius:1},opts));")
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
            boolean firstE = true;
            for (int i = 0; i < n; i++) {
                EntityPos ep = entities.get(i);
                // v425：过滤地图范围外实体（"实体标点跑出地图外"的
                // 根因——实体数据含未生成区域/死亡残留的坐标）
                int ex = Math.round(ep.x);
                int ez = Math.round(ep.z);
                if (ex < minX || ex > maxX || ez < minZ || ez > maxZ) {
                    continue;
                }
                if (!firstE) {
                    eb.append(',');
                }
                firstE = false;
                eb.append("{n:'").append(escapeHtml(entityLabelZh(ep.name))).append("',x:")
                        .append(ex).append(",z:").append(ez).append('}');
            }
            eb.append(']');
            html.append("var ents=").append(eb);
            // v425：标点缩小（6px→4px，实体密堆更易区分）
            html.append(";ents.forEach(function(e){groups.e.addLayer(mkCircle(e.z,e.x,")
                    .append("{px:4,color:'#ff7043',weight:1,fillOpacity:.7})")
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
                // v425：跳过 EMPTY 占位/全透明 chunk——史莱姆框不再
                // 画在"没有地图的空白区域"（占位 chunk 是视口渲染的
                // 未生成标记，不是地形）
                int[] cc = map.chunkColors.get(key);
                if (cc == null || !hasOpaque(cc)) {
                    continue;
                }
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
        // v416：rectangle 的 lat 同样取反（z 轴方向）
        html.append(";sls.forEach(function(s){groups.sl.addLayer(L.rectangle(")
                .append("[[-(s[0]*16+16),s[1]*16],[-(s[0]*16),s[1]*16+16]],")
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

    /**
     * 导出选中区域为 MC 结构文件（.mcstructure，NBT 格式）。
     * 区域 = 以 (minX,minZ) 为西北角的 size×size 方块；y 范围取区域内
     * 实际方块的最小/最大高度。方块名只存 palette 名称（不含 block
     * states——结构方块加载时用默认状态，v1 简化）。
     */
    public static boolean exportStructureRegion(File dbDir, int minX, int minZ,
                                                int size, int dimension, File outFile) {
        try {
            int maxX = minX + size - 1;
            int maxZ = minZ + size - 1;
            int minCx = Math.floorDiv(minX, 16);
            int maxCx = Math.floorDiv(maxX, 16);
            int minCz = Math.floorDiv(minZ, 16);
            int maxCz = Math.floorDiv(maxZ, 16);
            LevelDBReader reader = new LevelDBReader(dbDir);
            // 每列 (x,z) → TreeMap<y, name>（非空气；含水跳过——结构导出
            // 不含水面，水面下河床直接落地）
            java.util.Map<Long, java.util.TreeMap<Integer, String>> columns =
                    new java.util.HashMap<>();
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            try {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int cx = minCx; cx <= maxCx; cx++) {
                        List<LevelDBEntry> entries = reader.readChunk(cx, cz);
                        for (LevelDBEntry e : entries) {
                            byte[] rawKey = e.getKey().getRawKey();
                            int[] ck = parseChunkKey(rawKey);
                            if (ck == null || ck[2] != dimension || !isSubchunkKey(rawKey)) {
                                continue;
                            }
                            SubChunk sc = decodeSubChunk(e.getValue());
                            if (sc == null) {
                                continue;
                            }
                            int subIndex = ck[3];
                            for (int lz = 0; lz < 16; lz++) {
                                for (int lx = 0; lx < 16; lx++) {
                                    int wx = cx * 16 + lx;
                                    int wz = cz * 16 + lz;
                                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) {
                                        continue;
                                    }
                                    for (int ly = 0; ly < 16; ly++) {
                                        int idx = sc.getIndex(lx, ly, lz);
                                        String name = idx < sc.palette.length ? sc.palette[idx] : null;
                                        if (name == null || isAirName(name)) {
                                            continue;
                                        }
                                        if (isWaterName(name)) {
                                            continue; // 结构导出跳过水面
                                        }
                                        int y = subIndex * 16 + ly;
                                        java.util.TreeMap<Integer, String> col =
                                                columns.computeIfAbsent(
                                                        (long) (wx - minX) * size + (wz - minZ),
                                                        k -> new java.util.TreeMap<>());
                                        col.put(y, name);
                                        minY = Math.min(minY, y);
                                        maxY = Math.max(maxY, y);
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.close();
            }
            if (columns.isEmpty()) {
                Log.w(TAG, "结构导出：区域内无方块数据");
                return false;
            }
            int sizeY = maxY - minY + 1;
            // palette：名称 → 索引（稳定顺序：首次出现序）
            java.util.List<String> palette = new java.util.ArrayList<>();
            java.util.Map<String, Integer> paletteIdx = new java.util.HashMap<>();
            // block_indices[y][z][x] = palette 索引（-1 空气）
            int[][][] indices = new int[sizeY][size][size];
            for (int[][] zz : indices) {
                for (int[] row : zz) {
                    java.util.Arrays.fill(row, -1);
                }
            }
            for (java.util.Map.Entry<Long, java.util.TreeMap<Integer, String>> col
                    : columns.entrySet()) {
                int lx = (int) (col.getKey() / size);
                int lz = (int) (col.getKey() % size);
                for (java.util.Map.Entry<Integer, String> b : col.getValue().entrySet()) {
                    String name = b.getValue();
                    Integer id = paletteIdx.get(name);
                    if (id == null) {
                        id = palette.size();
                        paletteIdx.put(name, id);
                        palette.add(name);
                    }
                    indices[b.getKey() - minY][lz][lx] = id;
                }
            }
            // NBT 组装（.mcstructure 官方格式）
            NbtTag root = new NbtTag(NbtTag.TAG_COMPOUND, "", null);
            root.getCompound().put("format_version", new NbtTag(NbtTag.TAG_INT, "", 1));
            java.util.List<NbtTag> sizeList = new java.util.ArrayList<>();
            sizeList.add(new NbtTag(NbtTag.TAG_INT, "", size));
            sizeList.add(new NbtTag(NbtTag.TAG_INT, "", sizeY));
            sizeList.add(new NbtTag(NbtTag.TAG_INT, "", size));
            root.getCompound().put("size", new NbtTag(NbtTag.TAG_LIST, "", sizeList));
            // structure.block_indices：size_y 层，每层 size 行（z），每行 size 个 int
            java.util.List<NbtTag> layers = new java.util.ArrayList<>();
            for (int y = 0; y < sizeY; y++) {
                java.util.List<NbtTag> rows = new java.util.ArrayList<>();
                for (int z = 0; z < size; z++) {
                    java.util.List<NbtTag> row = new java.util.ArrayList<>();
                    for (int x = 0; x < size; x++) {
                        row.add(new NbtTag(NbtTag.TAG_INT, "", indices[y][z][x]));
                    }
                    rows.add(new NbtTag(NbtTag.TAG_LIST, "", row));
                }
                layers.add(new NbtTag(NbtTag.TAG_LIST, "", rows));
            }
            NbtTag blockIndices = new NbtTag(NbtTag.TAG_LIST, "", layers);
            // structure.palette.default.block_palette
            java.util.List<NbtTag> palEntries = new java.util.ArrayList<>();
            for (String name : palette) {
                NbtTag entry = new NbtTag(NbtTag.TAG_COMPOUND, "", null);
                entry.getCompound().put("name", new NbtTag(NbtTag.TAG_STRING, "", name));
                entry.getCompound().put("states",
                        new NbtTag(NbtTag.TAG_COMPOUND, "", null));
                entry.getCompound().put("version",
                        new NbtTag(NbtTag.TAG_INT, "", 17825808));
                palEntries.add(entry);
            }
            NbtTag defaultPal = new NbtTag(NbtTag.TAG_COMPOUND, "", null);
            defaultPal.getCompound().put("block_palette",
                    new NbtTag(NbtTag.TAG_LIST, "", palEntries));
            NbtTag paletteTag = new NbtTag(NbtTag.TAG_COMPOUND, "", null);
            paletteTag.getCompound().put("default", defaultPal);
            NbtTag structure = new NbtTag(NbtTag.TAG_COMPOUND, "", null);
            structure.getCompound().put("block_indices", blockIndices);
            structure.getCompound().put("palette", paletteTag);
            root.getCompound().put("structure", structure);
            BedrockNbtWriter writer = new BedrockNbtWriter();
            writer.writeFile(outFile, root);
            Log.i(TAG, "结构导出完成: " + outFile.getName() + " "
                    + size + "x" + sizeY + "x" + size + " palette=" + palette.size());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "结构导出失败", e);
            return false;
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
