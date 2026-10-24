package org.levimc.launcher.core.content.worldmap;

/**
 * v0.4.52：全图拼合器（HTML 导出与地图查看器全图纹理模式共用）。
 *
 * 从 WorldMap 的 chunk 色缓存（大世界）或整图数组（小世界）拼一张
 * 降采样全图（边长上限 4096）。未渲染区域颜色 0（ARGB 全透明）——
 * Bitmap 里天然透明，HTML 侧由 JS 显式置 alpha=0。
 */
public final class MapImageComposer {

    /** 拼图边长上限（超限按 2 的倍数降采样）。 */
    public static final int MAX_DIM = 4096;

    private MapImageComposer() {
    }

    /** 拼整图像素：返回 {width, height, pixels...}（降采样后）。无数据返回 null。 */
    public static int[] compose(WorldMapRenderer.WorldMap map) {
        return compose(map, MAX_DIM, false);
    }

    /** v0.4.73：可指定边长上限（HTML 导出放宽到 8192 保清晰度）。 */
    public static int[] compose(WorldMapRenderer.WorldMap map, int maxDim) {
        return compose(map, maxDim, false);
    }

    /** v0.4.180：useBiome=true 时群系图层开启——chunk 色直接换成
     *  chunkBiomeColors（与 tile/LOD 路径同款替代，群系显示重写
     *  统一数据源）。 */
    public static int[] compose(WorldMapRenderer.WorldMap map, int maxDim,
                                boolean useBiome) {
        if (map == null || map.width <= 0 || map.height <= 0) {
            return null;
        }
        int cap = Math.max(1, maxDim);
        int scale = 1;
        while (map.width / scale > cap || map.height / scale > cap) {
            scale <<= 1;
        }
        int w = Math.max(1, map.width / scale);
        int h = Math.max(1, map.height / scale);
        int[] out = new int[w * h + 2];
        out[0] = w;
        out[1] = h;

        if (map.colors != null) {
            // 小世界：整图数组逐像素降采样（取左上）
            int[] src = map.colors;
            int sw = map.width;
            for (int z = 0; z < h; z++) {
                int srcZ = z * scale * sw;
                int dst = z * w + 2;
                for (int x = 0; x < w; x++) {
                    int i = srcZ + x * scale;
                    out[dst + x] = i < src.length ? src[i] : 0;
                }
            }
            return out;
        }

        if (map.chunkColors == null || map.chunkColors.isEmpty()) {
            return null;
        }
        // 大世界：遍历 chunk 填充（像素色缓存在 16×16 int[] 中）
        // v0.4.180：群系图层开启时 chunk 色换成 chunkBiomeColors
        // （tile/LOD 同款替代——全图纹理也统一群系数据源）
        java.util.Map<Long, int[]> biomeSrc =
                (useBiome && map.chunkBiomeColors != null)
                        ? map.chunkBiomeColors : null;
        for (java.util.Map.Entry<Long, int[]> e : map.chunkColors.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            int[] chunk = e.getValue();
            if (chunk == null || chunk.length < 256) {
                continue;
            }
            if (biomeSrc != null) {
                int[] bc = biomeSrc.get(e.getKey());
                if (bc != null && bc.length >= 256) {
                    chunk = bc;
                }
            }
            int baseBx = cx * 16;
            int baseBz = cz * 16;
            int sx = Math.max(0, map.minBlockX - baseBx);
            int sz = Math.max(0, map.minBlockZ - baseBz);
            int ex = Math.min(16, map.minBlockX + map.width - baseBx);
            int ez = Math.min(16, map.minBlockZ + map.height - baseBz);
            if (sx >= ex || sz >= ez) {
                continue;
            }
            for (int lz = sz; lz < ez; lz++) {
                int bz = baseBz + lz;
                int oz = (bz - map.minBlockZ) / scale;
                if (oz < 0 || oz >= h) {
                    continue;
                }
                int chunkRow = lz * 16;
                int dstRow = oz * w + 2;
                for (int lx = sx; lx < ex; lx++) {
                    if (lx % scale != 0) {
                        continue;
                    }
                    int bx = baseBx + lx;
                    int ox = (bx - map.minBlockX) / scale;
                    if (ox < 0 || ox >= w) {
                        continue;
                    }
                    out[dstRow + ox] = chunk[chunkRow + lx];
                }
            }
        }
        return out;
    }

    /** 拼好的像素转 Bitmap（ARGB_8888；颜色 0 天然全透明）。 */
    public static android.graphics.Bitmap toBitmap(int[] composed) {
        if (composed == null || composed.length < 3) {
            return null;
        }
        int w = composed[0], h = composed[1];
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        bmp.setPixels(composed, 2, w, 0, 0, w, h);
        return bmp;
    }

    /** 全图 1 像素对应的方块数（map.width / 全图宽，≥1 整数倍）。 */
    public static int blockScale(WorldMapRenderer.WorldMap map, int composedW) {
        return Math.max(1, map.width / Math.max(1, composedW));
    }
}
