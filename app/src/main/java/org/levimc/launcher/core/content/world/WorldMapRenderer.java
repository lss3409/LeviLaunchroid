package org.levimc.launcher.core.content.world;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldMapRenderer {
    private final WorldSession session;
    private final LinkedHashMap<String, WorldChunk> chunks =
            new LinkedHashMap<>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WorldChunk> eldest) {
                    // v0.4.214：512 回退官方 24——WorldChunk 解码后每个约
                    // 0.5MB，512 个≈250MB+ 直接 OOM（用户实测：关实体显示
                    // 触发全量重渲染时崩溃，tombstone heap 2422KB until OOM）。
                    // LOD 采样 miss 率高一点但安全优先。
                    return size() > 24;
                }
            };
    private long revision = -1;
    // v0.4.214：LOD 金字塔基图缓存——span16 合成图（16×16 chunk 区域
    // →16×16 像素，每像素=对应 chunk 中心点）。span32/64 采样点全部
    // 落在 span16 网格上，从基图取像素零解码（此前 span64 每 tile 直
    // 接解码 256 chunk×屏幕 150 tile=3.8 万次解码+LRU24 反复淘汰=
    // 平板实测卡死根因）。每张 1KB，1024 张≈1MB。
    private final LinkedHashMap<String, Bitmap> lodCache =
            new LinkedHashMap<>(256, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 1024;
                }
            };

    public WorldMapRenderer(WorldSession session) {
        this.session = session;
    }

    public synchronized WorldChunk chunk(int x, int z, int dimension) throws IOException {
        long current = session.revision();
        if (current != revision) {
            chunks.clear();
            lodCache.clear();
            revision = current;
        }
        String key = x + ":" + z + ":" + dimension;
        WorldChunk chunk = chunks.get(key);
        if (chunk == null) {
            chunk = WorldChunk.load(session, x, z, dimension);
            chunks.put(key, chunk);
        }
        return chunk;
    }

    public synchronized void clear() {
        chunks.clear();
    }

    public Bitmap render(int chunkX, int chunkZ, MapSettings options) throws IOException {
        int[] pixels = new int[256];
        if (options.layer == MapSettings.Layer.SLIME) {
            // v0.4.214：未生成/未加载的 chunk 不显示史莱姆色（用户反馈
            // 未渲染区块也全绿——原实现不查 exists）
            WorldChunk chunk = chunk(chunkX, chunkZ, options.dimension);
            if (!chunk.exists) return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
            java.util.Arrays.fill(
                    pixels,
                    options.dimension == 0 && slime(chunkX, chunkZ) ? 0xff8bcf6e : 0xff303d43);
            return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
        }
        WorldChunk chunk = chunk(chunkX, chunkZ, options.dimension);
        if (!chunk.exists) return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
        int[][] heights = new int[16][16];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                heights[x][z] = Math.min(options.upperY, chunk.top(x, z, options.leaves));
        int[] light = options.layer == MapSettings.Layer.LIGHT ? light(chunk, options.y) : null;
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException();
                int surface = heights[x][z], y = options.slice ? options.y : surface;
                y = Math.min(y, options.upperY);
                int color;
                switch (options.layer) {
                    case HEIGHTMAP -> {
                        // v0.4.214：地理地图标准分层设色（绝对高度色带）
                        color = MapPalette.heightColor(surface);
                    }
                    case BIOMES ->
                            color =
                                    MapPalette.biome(
                                            chunk.biome(x, options.slice ? options.y : surface, z));
                    case FOLIAGE -> {
                        int biome = chunk.biome(x, options.slice ? options.y : surface, z);
                        color = biome < 0 ? 0 : MapPalette.foliage(biome);
                    }
                    case SLIME -> color = slime(chunkX, chunkZ) ? 0xff8bcf6e : 0xff303d43;
                    case XRAY -> {
                        color = 0;
                        String[] filters =
                                (options.xray == null ? "" : options.xray)
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .split(",");
                        for (int scan =
                                        Math.min(
                                                options.slice ? options.y : chunk.maxY(),
                                                options.upperY);
                                scan >= Math.max(options.lowerY, chunk.minY());
                                scan--) {
                            String name = WorldChunk.name(chunk.blockAt(x, scan, z));
                            boolean match = false;
                            for (String filter : filters)
                                if (!filter.trim().isEmpty() && name.contains(filter.trim())) {
                                    match = true;
                                    break;
                                }
                            if (match) {
                                y = scan;
                                // v0.4.214：X 射线用官方矿石色（鲜明易分辨）
                                color = MapPalette.xray(name, chunk.biome(x, scan, z));
                                break;
                            }
                        }
                    }
                    case LIGHT -> {
                        int stored = chunk.storedLight(x, options.y, z, false),
                                value = stored >= 0 ? stored : light[z * 16 + x];
                        color = Color.HSVToColor(new float[] {value * 8, .72f, .24f + value / 20f});
                    }
                    case CAVE -> {
                        int scan = Math.min(options.y, chunk.maxY());
                        boolean space = false, covered = options.y <= surface;
                        color = 0;
                        for (; scan >= Math.max(options.lowerY, chunk.minY()); scan--) {
                            String n = WorldChunk.name(chunk.blockAt(x, scan, z));
                            if (n.contains("leaves") || n.contains("_log")) continue;
                            if (WorldChunk.isAir(n) || n.contains("water")) {
                                if (covered) space = true;
                                continue;
                            }
                            if (!covered) {
                                covered = true;
                                continue;
                            }
                            if (space) {
                                y = scan;
                                color = MapPalette.block(n, chunk.biome(x, scan, z));
                                break;
                            }
                        }
                    }
                    default -> {
                        color = 0;
                        for (int scan = y; scan >= Math.max(options.lowerY, chunk.minY()); scan--) {
                            String n = WorldChunk.name(chunk.blockAt(x, scan, z));
                            if (WorldChunk.isAir(n)
                                    || (!options.leaves && n.contains("leaves"))
                                    || (!options.water && n.contains("water"))) continue;
                            y = scan;
                            color = MapPalette.block(n, chunk.biome(x, scan, z));
                            break;
                        }
                    }
                }
                if (options.shading
                        && (options.layer == MapSettings.Layer.SATELLITE
                                || options.layer == MapSettings.Layer.CAVE)) {
                    int west = x > 0 ? heights[x - 1][z] : surface,
                            north = z > 0 ? heights[x][z - 1] : surface;
                    color =
                            MapPalette.shade(
                                    color,
                                    1f
                                            + Math.max(
                                                    -.22f,
                                                    Math.min(
                                                            .16f,
                                                            (west + north - 2 * surface) * .035f)));
                }
                pixels[z * 16 + x] = MapPalette.shade(color, options.brightness);
            }
        return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
    }

    /** v0.4.214：LOD 超级块 tile——span chunk 区域均匀取 16×16 个采样点
     *  （每点=对应 chunk 的 (8,8) 像素），拼成 16×16 采样图。
     *  用户指令：16/32/64 三个缩放等级分梯次做 LOD。
     *  金字塔：采样点走 lodPixel→span16 基图缓存（16×16 chunk 合成图），
     *  span32/64 零解码从基图取像素；基图 miss 才合成（256 chunk 采样）。 */
    public Bitmap renderLod(int chunkX, int chunkZ, int span, MapSettings options)
            throws IOException {
        if (span <= 1) return render(chunkX, chunkZ, options);
        int[] pixels = new int[256];
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException();
                int cx = chunkX + i * span / 16, cz = chunkZ + j * span / 16;
                pixels[j * 16 + i] = lodPixel(cx, cz, options);
            }
        return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
    }

    /** LOD 采样点像素：从 span16 基图取（=该 chunk 的 (8,8) 像素）。 */
    private int lodPixel(int chunkX, int chunkZ, MapSettings options) throws IOException {
        Bitmap base =
                span16Base(Math.floorDiv(chunkX, 16), Math.floorDiv(chunkZ, 16), options);
        return base.getPixel(Math.floorMod(chunkX, 16), Math.floorMod(chunkZ, 16));
    }

    /** span16 基图：16×16 chunk 区域每 chunk 中心像素合成 16×16 图。 */
    private Bitmap span16Base(int tx, int tz, MapSettings options) throws IOException {
        String key = tx + ":" + tz + ":" + options.dimension + ":" + options.renderKey();
        Bitmap cached = lodCache.get(key);
        if (cached != null) return cached;
        int[] pixels = new int[256];
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException();
                pixels[j * 16 + i] = samplePixel(tx * 16 + i, tz * 16 + j, options);
            }
        Bitmap b = Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
        lodCache.put(key, b);
        return b;
    }

    /** 单像素采样（LOD 快路径）：只扫 (8,8) 一列，不做阴影。 */
    private int samplePixel(int chunkX, int chunkZ, MapSettings options) throws IOException {
        WorldChunk chunk = chunk(chunkX, chunkZ, options.dimension);
        if (!chunk.exists) return 0;
        int surface = Math.min(options.upperY, chunk.top(8, 8, options.leaves));
        int y = options.slice ? options.y : surface;
        y = Math.min(y, options.upperY);
        int minY = Math.max(options.lowerY, chunk.minY());
        switch (options.layer) {
            case HEIGHTMAP -> {
                return MapPalette.heightColor(surface);
            }
            case BIOMES -> {
                return MapPalette.biome(chunk.biome(8, options.slice ? options.y : surface, 8));
            }
            case FOLIAGE -> {
                int biome = chunk.biome(8, options.slice ? options.y : surface, 8);
                return biome < 0 ? 0 : MapPalette.foliage(biome);
            }
            case SLIME -> {
                return slime(chunkX, chunkZ) ? 0xff8bcf6e : 0xff303d43;
            }
            case XRAY -> {
                // v0.4.214：xray 可能为 null（prefs 历史数据）——兜底空串
                String[] filters =
                        (options.xray == null ? "" : options.xray)
                                .toLowerCase(java.util.Locale.ROOT)
                                .split(",");
                for (int scan =
                                Math.min(options.slice ? options.y : chunk.maxY(), options.upperY);
                        scan >= minY;
                        scan--) {
                    String name = WorldChunk.name(chunk.blockAt(8, scan, 8));
                    for (String filter : filters)
                        if (!filter.trim().isEmpty() && name.contains(filter.trim())) {
                            return MapPalette.xray(name, chunk.biome(8, scan, 8));
                        }
                }
                return 0;
            }
            case CAVE -> {
                int scan = Math.min(options.y, chunk.maxY());
                boolean space = false, covered = options.y <= surface;
                for (; scan >= minY; scan--) {
                    String n = WorldChunk.name(chunk.blockAt(8, scan, 8));
                    if (n.contains("leaves") || n.contains("_log")) continue;
                    if (WorldChunk.isAir(n) || n.contains("water")) {
                        if (covered) space = true;
                        continue;
                    }
                    if (!covered) {
                        covered = true;
                        continue;
                    }
                    if (space) return MapPalette.block(n, chunk.biome(8, scan, 8));
                }
                return 0;
            }
            case LIGHT -> {
                // 光照需邻域 BFS，无法单列——回退整 chunk 渲染取 (8,8)
                Bitmap tile = render(chunkX, chunkZ, options);
                int c = tile.getPixel(8, 8);
                tile.recycle();
                return c;
            }
            default -> {
                for (int scan = y; scan >= minY; scan--) {
                    String n = WorldChunk.name(chunk.blockAt(8, scan, 8));
                    if (WorldChunk.isAir(n)
                            || (!options.leaves && n.contains("leaves"))
                            || (!options.water && n.contains("water"))) continue;
                    return MapPalette.block(n, chunk.biome(8, scan, 8));
                }
                return 0;
            }
        }
    }

    public Bitmap regionImage(WorldOperations.Region region, MapSettings options)
            throws IOException {
        if (region.width() > 4096
                || region.depth() > 4096
                || (long) region.width() * region.depth() > 4194304)
            throw new IOException("Image export is limited to 4194304 map pixels");
        Bitmap result =
                Bitmap.createBitmap(region.width(), region.depth(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(result);
        MapSettings settings = options.copy();
        settings.dimension = region.dimension;
        try {
            for (int x = Math.floorDiv(region.x1, 16); x <= Math.floorDiv(region.x2, 16); x++)
                for (int z = Math.floorDiv(region.z1, 16); z <= Math.floorDiv(region.z2, 16); z++) {
                    if (Thread.currentThread().isInterrupted())
                        throw new java.io.InterruptedIOException();
                    Bitmap tile = render(x, z, settings);
                    canvas.drawBitmap(tile, x * 16 - region.x1, z * 16 - region.z1, null);
                    tile.recycle();
                }
            return result;
        } catch (IOException | RuntimeException ex) {
            result.recycle();
            throw ex;
        }
    }

    private int[] light(WorldChunk center, int y) throws IOException {
        int width = 48,
                minY = Math.max(center.dimension == 0 ? -64 : 0, y - 15),
                maxY = Math.min(center.dimension == 0 ? 319 : 255, y + 15),
                height = maxY - minY + 1;
        if (height <= 0) return new int[256];
        byte[] levels = new byte[width * width * height];
        boolean[] opaque = new boolean[levels.length];
        int[] queue = new int[levels.length * 2];
        int read = 0, write = 0;
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                WorldChunk chunk = chunk(center.x + cx, center.z + cz, center.dimension);
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++)
                        for (int yy = minY; yy <= maxY; yy++) {
                            String name = WorldChunk.name(chunk.blockAt(x, yy, z));
                            int i =
                                    ((yy - minY) * width + (cz + 1) * 16 + z) * width
                                            + (cx + 1) * 16
                                            + x;
                            opaque[i] =
                                    !WorldChunk.isAir(name)
                                            && !name.contains("glass")
                                            && !name.contains("leaves")
                                            && !name.contains("water")
                                            && !name.contains("torch")
                                            && !name.contains("flower")
                                            && !name.endsWith(":short_grass")
                                            && !name.endsWith(":tall_grass")
                                            && !name.contains("lantern");
                            int level = MapPalette.emission(name);
                            if (level > 0) {
                                levels[i] = (byte) level;
                                queue[write++] = i;
                            }
                        }
            }
        int plane = width * width;
        while (read < write) {
            if ((read & 1023) == 0 && Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException();
            int i = queue[read++], next = levels[i] - 1;
            if (next <= 0) continue;
            int x = i % width, z = (i / width) % width, yy = i / plane;
            for (int direction = 0; direction < 6; direction++) {
                int n =
                        switch (direction) {
                            case 0 -> x > 0 ? i - 1 : -1;
                            case 1 -> x + 1 < width ? i + 1 : -1;
                            case 2 -> z > 0 ? i - width : -1;
                            case 3 -> z + 1 < width ? i + width : -1;
                            case 4 -> yy > 0 ? i - plane : -1;
                            default -> yy + 1 < height ? i + plane : -1;
                        };
                if (n >= 0 && !opaque[n] && levels[n] < next) {
                    levels[n] = (byte) next;
                    if (write >= queue.length)
                        queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                    queue[write++] = n;
                }
            }
        }
        int[] result = new int[256];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                result[z * 16 + x] = levels[((y - minY) * width + z + 16) * width + x + 16];
        return result;
    }

    public static boolean slime(int chunkX, int chunkZ) {
        int[] state = new int[624];
        state[0] = chunkX * 0x1f1f1f1f ^ chunkZ;
        for (int i = 1; i < state.length; i++)
            state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
        int combined = (state[0] & 0x80000000) | (state[1] & 0x7fffffff);
        int output = state[397] ^ (combined >>> 1) ^ ((combined & 1) != 0 ? 0x9908b0df : 0);
        output ^= output >>> 11;
        output ^= (output << 7) & 0x9d2c5680;
        output ^= (output << 15) & 0xefc60000;
        output ^= output >>> 18;
        return Integer.remainderUnsigned(output, 10) == 0;
    }
}
