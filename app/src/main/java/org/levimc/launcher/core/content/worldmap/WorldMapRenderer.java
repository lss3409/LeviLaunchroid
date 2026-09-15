package org.levimc.launcher.core.content.worldmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.leveldb.LevelDBEntry;
import org.levimc.launcher.core.content.leveldb.LevelDBReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Data2D：高度图（int16[256]）+ biome（byte[256]） */
    private static final int KEY_TYPE_DATA_2D = 0x2D;

    /** 主世界维度 id */
    private static final int DIM_OVERWORLD = 0;

    /** 高度未知标记（解析失败 / 无高度数据的 chunk） */
    private static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    /** HeightMap 中优先使用的键名（Bedrock 2D 高度图） */
    private static final String[] HEIGHT_MAP_KEYS = {
            "WORLD_SURFACE", "WORLD_SURFACE_WG", "OCEAN_FLOOR", "MOTION_BLOCKING"
    };

    private static Context appContext;

    private WorldMapRenderer() {
    }

    /** 在使用前注入应用 Context（用于解析 R.color 地图调色板）。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
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
            LevelDBReader reader = new LevelDBReader(dbDir);
            List<LevelDBEntry> entries = reader.readAllEntries();
            reader.close();
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

        // 1) 收集主世界 chunk 坐标与高度
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Long, Integer> heights = new HashMap<>();
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
            scannedChunks++;
            try {
                int height = UNKNOWN_HEIGHT;
                if (isData2dKey(rawKey)) {
                    // 0x2d Data2D：直接是高度图（int16[256] + biome[256]）
                    height = extractData2dHeight(entry.getValue());
                } else if (isSubchunkKey(rawKey)) {
                    // 0x2f subchunk：8-bit 方块存储找最高非空层
                    height = extractSubchunkHeight(entry.getValue(), chunkKey[3]);
                } else {
                    height = extractChunkHeight(parseChunkNbt(entry.getValue()));
                }
                if (height != UNKNOWN_HEIGHT) {
                    parsedChunks++;
                    // 同一 chunk 多个 subchunk 条目：取最高
                    long key = pack(x, z);
                    Integer old = heights.get(key);
                    if (old == null || height > old) {
                        heights.put(key, height);
                    }
                    // 只有高度已知的 chunk 才参与范围计算，
                    // 否则大量未生成的 0x2d 空 chunk 会把地图撑成大片空白
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
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

        // 2) 计算画布尺寸：每 chunk 1~2 像素，超出上限按桶降采样
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;

        int bucket = 1;
        while (spanX / bucket > maxChunksPerAxis || spanZ / bucket > maxChunksPerAxis) {
            bucket++;
        }

        int cols = (spanX + bucket - 1) / bucket;
        int rows = (spanZ + bucket - 1) / bucket;

        int pixelSize = (cols <= 128 && rows <= 128) ? 2 : 1;
        int width = Math.max(1, cols * pixelSize);
        int height = Math.max(1, rows * pixelSize);
        while (width > MAX_BITMAP_SIZE || height > MAX_BITMAP_SIZE) {
            pixelSize = Math.max(1, pixelSize / 2);
            width = Math.max(1, cols * pixelSize);
            height = Math.max(1, rows * pixelSize);
        }

        // 3) 桶聚合（同桶取最高高度，突出山地）
        int[] bucketHeight = new int[cols * rows];
        Arrays.fill(bucketHeight, UNKNOWN_HEIGHT);
        for (Map.Entry<Long, Integer> e : heights.entrySet()) {
            int x = unpackX(e.getKey());
            int z = unpackZ(e.getKey());
            int bx = Math.min(cols - 1, (x - minX) / bucket);
            int bz = Math.min(rows - 1, (z - minZ) / bucket);
            int idx = bz * cols + bx;
            if (e.getValue() > bucketHeight[idx]) {
                bucketHeight[idx] = e.getValue();
            }
        }

        // 4) 绘制（照搬 BTR HeightmapRenderer 配色：smoothstep 高度渐变 + 邻接高度阴影）
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(false);

        canvas.drawColor(colorFor(UNKNOWN_HEIGHT));
        for (int bz = 0; bz < rows; bz++) {
            for (int bx = 0; bx < cols; bx++) {
                int h = bucketHeight[bz * cols + bx];
                if (h == UNKNOWN_HEIGHT) {
                    continue; // 未知保持底色
                }
                // 邻接高度（BTR：西/北方向的 chunk 高度用于坡度阴影）
                int hW = bx > 0 ? bucketHeight[bz * cols + bx - 1] : h;
                int hN = bz > 0 ? bucketHeight[(bz - 1) * cols + bx] : h;
                paint.setColor(btrHeightColor(h, hW, hN));
                canvas.drawRect(bx * pixelSize, bz * pixelSize,
                        (bx + 1) * pixelSize, (bz + 1) * pixelSize, paint);
            }
        }

        Log.i(TAG, "渲染成功: bitmap=" + width + "x" + height
                + ", 扫描 chunk=" + scannedChunks
                + ", 高度解析成功=" + parsedChunks
                + ", 失败=" + failedChunks
                + ", 未知高度=" + (heights.size() - parsedChunks)
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
            if (type < 0x2C || type > 0x30) {
                return null; // 实体/方块实体等非 chunk 数据
            }
            // 旧格式：无维度段，默认主世界
            int sub = len == 10 ? rawKey[9] : -1;
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), DIM_OVERWORLD, sub};
        }
        if (len == 13 || len == 14) {
            int type = rawKey[12] & 0xFF;
            if (type == KEY_TYPE_LEGACY_MIXED || type == KEY_TYPE_CHUNK_DATA) {
                int sub = len == 14 ? rawKey[13] : -1;
                return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), readIntLE(rawKey, 8), sub};
            }
        }
        return null;
    }

    /** 9 字节且类型 0x2D 的 Data2D key（高度图）。 */
    private static boolean isData2dKey(byte[] rawKey) {
        return rawKey != null && rawKey.length == 9
                && (rawKey[8] & 0xFF) == KEY_TYPE_DATA_2D;
    }

    /** 9/10 字节且类型 0x2F 的 subchunk key。 */
    private static boolean isSubchunkKey(byte[] rawKey) {
        if (rawKey == null || (rawKey.length != 9 && rawKey.length != 10)) {
            return false;
        }
        return (rawKey[8] & 0xFF) == KEY_TYPE_LEGACY_MIXED;
    }

    /**
     * 1.18+ subchunk 方块存储高度：value = [版本 9][storage 数][sub 索引][storage...]。
     * storage 布局（BTR 同款）：[bits 头 1B][数据区 512×bits 字节][palette 数 int32][palette NBT...]。
     * 数据索引 = x + (z<<4) + (y<<8)，y 层连续；从最高 y 层往下找第一个非空层
     * （字节级非零检查，不做逐值解包）。
     */
    private static int extractSubchunkHeight(byte[] value, int subIndex) {
        if (value == null || value.length < 5 || subIndex < 0) {
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
            int dataBytes = 512 * bits; // 4096 值 × bits / 8
            if (p + dataBytes + 4 > value.length) {
                return UNKNOWN_HEIGHT;
            }
            int paletteStart = p + dataBytes;
            if (paletteStart + 4 > value.length) {
                return UNKNOWN_HEIGHT;
            }
            int paletteSize = (value[paletteStart] & 0xFF) | ((value[paletteStart + 1] & 0xFF) << 8)
                    | ((value[paletteStart + 2] & 0xFF) << 16) | ((value[paletteStart + 3] & 0xFF) << 24);
            if (paletteSize < 0 || paletteSize > 65536) {
                return UNKNOWN_HEIGHT;
            }
            int paletteEntriesStart = paletteStart + 4;
            // palette 索引 0 是否是空气：决定全 0 字节层的语义。
            // 索引 0 为空气 → 非零字节 = 有方块；否则（罕见）该存储无法可靠提取。
            boolean zeroIsAir = paletteSize == 0
                    || firstPaletteEntryIsAir(value, paletteEntriesStart, paletteSize);
            if (!zeroIsAir) {
                return UNKNOWN_HEIGHT;
            }
            // 16 个 y 层，每层 256 值 = 32×bits 字节；从最高层往下找非空
            int layerBytes = 32 * bits;
            for (int y = 15; y >= 0; y--) {
                int layerStart = p + y * layerBytes;
                for (int i = 0; i < layerBytes; i++) {
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

    /** palette 第一个条目是否空气（扫描条目头 128 字节内的 "air" 字样）。 */
    private static boolean firstPaletteEntryIsAir(byte[] value, int p, int paletteSize) {
        try {
            if (p + 3 > value.length) {
                return false;
            }
            // 条目 = [类型][名长 2B][名][payload]，只看前 128 字节窗口
            int type = value[p] & 0xFF;
            int nameLen = (value[p + 1] & 0xFF) | ((value[p + 2] & 0xFF) << 8);
            int start = p + 3 + nameLen;
            int end = Math.min(value.length, start + 128);
            for (int i = start; i + 2 < end; i++) {
                if (value[i] == 'a' && value[i + 1] == 'i' && value[i + 2] == 'r') {
                    return true;
                }
            }
            return type != 10; // 非 Compound 条目无法判断，按非空气保守处理
        } catch (Throwable ignored) {
            return false;
        }
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
     */
    private static int extractData2dHeight(byte[] value) {
        if (value == null || value.length < 512) {
            return UNKNOWN_HEIGHT;
        }
        int maxH = 0;
        for (int i = 0; i < 256; i++) {
            int h = (value[i * 2] & 0xFF) | ((value[i * 2 + 1] & 0xFF) << 8);
            if (h > 512) h = h & 0xFF; // 大端脏数据（>512）取低字节
            if (h > maxH) maxH = h;
        }
        return maxH; // 全 0 = 海平面以下（水色），与 BTR 行为一致
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
}
