package org.levimc.launcher.core.content.world;

import android.graphics.Color;

import java.util.Locale;

public final class MapPalette {
    private MapPalette() {}

    public static int block(String id, int biome) {
        // v0.4.214：旧渲染器 names 数组可能含 null（未知方块）——兜底
        String n = (id == null ? "minecraft:stone" : id).toLowerCase(Locale.ROOT);
        if (WorldChunk.isAir(n)) return Color.TRANSPARENT;
        if (n.startsWith("legacy:")) return 0xffa7a7a7;
        // v0.4.214：地图显示的颜色表换成我们 LBBL 的（block_color.json
        // 精确名表 + BTR 老版大表回退 + 灰度模板×群系色调 tint——
        // 官方硬编码子串匹配整体替换；扫描/水处理/阴影保持官方）
        int color = LbblPalette.blockColor(n);
        if (color == 0xFF7F7F7F) {
            // LBBL 表兜底灰——官方 16 色板子串回退（1.26 新色名方块）
            String[] colors = {
                "white",
                "orange",
                "magenta",
                "light_blue",
                "yellow",
                "lime",
                "pink",
                "gray",
                "light_gray",
                "cyan",
                "purple",
                "blue",
                "brown",
                "green",
                "red",
                "black"
            };
            int[] rgb = {
                0xffdeded8,
                0xffd68a44,
                0xffb565b9,
                0xff76b4d7,
                0xffdfc763,
                0xff9dc955,
                0xffd79baf,
                0xff67686a,
                0xffaaa9a0,
                0xff4c9eab,
                0xff8d5cb4,
                0xff4d65b1,
                0xff8a6547,
                0xff69894c,
                0xffb65d53,
                0xff33363b
            };
            for (int i = 0; i < colors.length; i++) if (n.contains(colors[i] + "_")) return rgb[i];
        }
        return LbblPalette.tint(n, color, biome);
    }

    /** v0.4.214：高度图分层设色色带（地理地图标准方案 hypsometric tints）：
     *  深蓝（深海）→浅蓝→绿（低地）→黄绿→黄（丘陵）→棕（山地）→白（雪峰）。
     *  相邻 stop 之间线性插值；stops 与色带等长（9 色 8 段）。 */
    public static final int[] HEIGHT_BANDS = {
        0xFF14265C,
        0xFF2E5FA8,
        0xFF6E9BC8,
        0xFF3E8A3B,
        0xFF8FB14E,
        0xFFD8C23B,
        0xFFA87867,
        0xFF7A5A3B,
        0xFFE8EEF6
    };
    public static final int[] HEIGHT_STOPS = {-64, 0, 32, 64, 96, 128, 160, 192, 224, 256};

    public static int heightColor(int y) {
        if (y <= HEIGHT_STOPS[0]) return HEIGHT_BANDS[0];
        if (y >= HEIGHT_STOPS[HEIGHT_STOPS.length - 1])
            return HEIGHT_BANDS[HEIGHT_BANDS.length - 1];
        for (int i = 0; i < HEIGHT_STOPS.length - 1; i++) {
            if (y <= HEIGHT_STOPS[i + 1]) {
                float t =
                        (y - HEIGHT_STOPS[i])
                                / (float) (HEIGHT_STOPS[i + 1] - HEIGHT_STOPS[i]);
                int a = HEIGHT_BANDS[i], b = HEIGHT_BANDS[i + 1];
                return Color.argb(
                        255,
                        Math.round((1 - t) * Color.red(a) + t * Color.red(b)),
                        Math.round((1 - t) * Color.green(a) + t * Color.green(b)),
                        Math.round((1 - t) * Color.blue(a) + t * Color.blue(b)));
            }
        }
        return HEIGHT_BANDS[HEIGHT_BANDS.length - 1];
    }

    /** v0.4.214：X 射线图层专用矿石色——用户指定用官方（levilauncher）
     *  的几种矿石颜色：LBBL 表矿石色太淡分辨不出。非矿石回退普通表。 */
    public static int xray(String id, int biome) {
        String n = id.toLowerCase(Locale.ROOT);
        if (n.contains("diamond")) return 0xff49dfd5;
        if (n.contains("emerald")) return 0xff36c67b;
        if (n.contains("lapis")) return 0xff3c63cc;
        if (n.contains("redstone")) return 0xffc44848;
        if (n.contains("copper")) return 0xffbd825b;
        if (n.contains("gold")) return 0xffe8c957;
        if (n.contains("iron")) return 0xffbcae9d;
        if (n.contains("coal")) return 0xff3f4147;
        if (n.contains("ancient_debris")) return 0xff97694f;
        return block(id, biome);
    }

    public static int foliage(int biome) {
        // v0.4.214：群系颜色表用我们 LBBL 的（biome_color.json）
        return LbblPalette.biomeColor(biome);
    }

    public static int biome(int id) {
        // v0.4.214：群系图层颜色同样用我们 LBBL 的表
        if (id < 0) return 0x00000000;
        return LbblPalette.biomeColor(id);
    }

    public static String biomeName(int id) {
        return switch (id) {
            case -1 -> "Unavailable";
            case 0 -> "Ocean";
            case 1 -> "Plains";
            case 2 -> "Desert";
            case 3 -> "Windswept hills";
            case 4 -> "Forest";
            case 5 -> "Taiga";
            case 6 -> "Swamp";
            case 7 -> "River";
            case 8 -> "Nether wastes";
            case 9 -> "The End";
            case 10 -> "Frozen ocean";
            case 11 -> "Frozen river";
            case 12 -> "Snowy plains";
            case 14 -> "Mushroom fields";
            case 16 -> "Beach";
            case 21 -> "Jungle";
            case 24 -> "Deep ocean";
            case 27 -> "Birch forest";
            case 29 -> "Dark forest";
            case 30 -> "Snowy taiga";
            case 35 -> "Savanna";
            case 37 -> "Badlands";
            case 178 -> "Soul sand valley";
            case 179 -> "Crimson forest";
            case 180 -> "Warped forest";
            case 181 -> "Basalt deltas";
            default -> "Biome " + id;
        };
    }

    public static int shade(int color, float multiplier) {
        multiplier = Math.max(.15f, Math.min(1.8f, multiplier));
        return Color.argb(
                Color.alpha(color),
                Math.min(255, (int) (Color.red(color) * multiplier)),
                Math.min(255, (int) (Color.green(color) * multiplier)),
                Math.min(255, (int) (Color.blue(color) * multiplier)));
    }

    public static int emission(String id) {
        if (id.contains("soul_torch") || id.contains("soul_lantern") || id.contains("soul_fire"))
            return 10;
        if (id.contains("redstone_torch")) return 7;
        if (id.contains("torch")) return 14;
        if (id.contains("glow_lichen")) return 7;
        if (id.contains("glowstone")
                || id.contains("sea_lantern")
                || id.contains("shroomlight")
                || id.contains("lava")
                || id.contains("beacon")
                || id.contains("lantern")
                || id.contains("froglight")
                || id.contains("jack_o_lantern")
                || id.contains("fire")) return 15;
        if (id.contains("magma")) return 3;
        return 0;
    }
}
