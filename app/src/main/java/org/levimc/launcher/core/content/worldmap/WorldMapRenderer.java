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
            int[] chunkKey = parseChunkKey(entry.getKey().getRawKey());
            if (chunkKey == null || chunkKey[2] != DIM_OVERWORLD) {
                continue;
            }
            int x = chunkKey[0];
            int z = chunkKey[1];
            scannedChunks++;
            try {
                int height = extractChunkHeight(parseChunkNbt(entry.getValue()));
                if (height != UNKNOWN_HEIGHT) {
                    parsedChunks++;
                }
                heights.put(pack(x, z), height);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
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

        // 4) 绘制
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(false);

        canvas.drawColor(colorFor(UNKNOWN_HEIGHT));
        for (int bz = 0; bz < rows; bz++) {
            for (int bx = 0; bx < cols; bx++) {
                int h = bucketHeight[bz * cols + bx];
                paint.setColor(colorFor(h));
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
     * 解析 chunk key，返回 {x, z, dimension}；非 chunk key 返回 null。
     * 9/10 字节（旧格式，无维度段）一律视为 chunk 且维度=主世界；
     * 13/14 字节（1.18+）要求类型字节为 0x2F 或 0x30。
     */
    private static int[] parseChunkKey(byte[] rawKey) {
        if (rawKey == null) {
            return null;
        }
        int len = rawKey.length;
        if (len == 9 || len == 10) {
            // 旧格式：无维度段，默认主世界
            return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), DIM_OVERWORLD};
        }
        if (len == 13 || len == 14) {
            int type = rawKey[12] & 0xFF;
            if (type == KEY_TYPE_LEGACY_MIXED || type == KEY_TYPE_CHUNK_DATA) {
                return new int[]{readIntLE(rawKey, 0), readIntLE(rawKey, 4), readIntLE(rawKey, 8)};
            }
        }
        return null;
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

    // ---------------------------------------------------------------- 颜色映射

    /**
     * 高度 → 颜色映射（R.color 资源，浅色/深色模式共用同一调色板）：
     * &lt;62 深水蓝 · 62-70 浅水/沙滩黄 · 70-85 草绿 · 85-100 深绿 ·
     * 100-130 岩石灰 · &gt;130 雪白 · 未知 中灰。
     */
    private static int colorFor(int height) {
        if (height == UNKNOWN_HEIGHT) {
            return getColor(R.color.world_map_unknown);
        }
        if (height < 62) {
            return getColor(R.color.world_map_water_deep);
        }
        if (height < 70) {
            return getColor(R.color.world_map_sand);
        }
        if (height < 85) {
            return getColor(R.color.world_map_grass);
        }
        if (height < 100) {
            return getColor(R.color.world_map_forest);
        }
        if (height < 130) {
            return getColor(R.color.world_map_rock);
        }
        return getColor(R.color.world_map_snow);
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
