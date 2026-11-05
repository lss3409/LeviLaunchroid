package org.levimc.launcher.core.content.world;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * LBBL 颜色表（用户指令 v0.4.214：地图显示的颜色表换成我们 LBBL 的）。
 * assets/block_color.json 精确名表 + BTR 老版大表回退（原 colorForBlock）
 * + bedrockmap 群系色调（biome_color.json：灰度模板 × grass/leaves/water tint）。
 * 官方 MapPalette 的硬编码子串匹配换成本表查询；扫描/水处理/阴影逻辑保持官方。
 */
public final class LbblPalette {
    private LbblPalette() {}

    private static final String TAG = "LbblPalette";
    private static final int COLOR_WATER = 0xFF17217A;
    private static final int[] DEFAULT_WATER_TINT = {75, 140, 235};
    private static final int[] DEFAULT_LEAVES_TINT = {113, 167, 77};
    private static final int[] DEFAULT_GRASS_TINT = {142, 185, 113};
    private static final int GRASS_TEMPLATE_COLOR = 0xFF939393;

    private static final Map<Integer, int[]> biomeTintTable = new HashMap<>();
    private static final Map<String, Integer> blockColorTable = new HashMap<>();
    private static final Map<String, Integer> blockAlphaTable = new HashMap<>();
    private static final Map<Integer, String> biomeIdToCode = new HashMap<>();

    /** 群系选择列表条目（id / 代码名 / 图层色）。 */
    public static final class BiomeEntry {
        public final int id;
        public final String code;
        public final int color;

        BiomeEntry(int id, String code, int color) {
            this.id = id;
            this.code = code;
            this.color = color;
        }
    }

    /** 在使用前注入应用 Context（加载 bedrockmap 颜色表）。 */
    public static void init(Context context) {
        loadColorTables(context.getApplicationContext());
    }

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
                    // 群系选择列表用（"default" 是兜底条目，id 与 plains
                    // 重复，跳过）
                    if (!key.equals("default")) biomeIdToCode.put(id, key);
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

    /** 群系选择列表（按 id 升序；颜色=图层 rgb）。 */
    public static java.util.List<BiomeEntry> biomeEntries() {
        java.util.List<BiomeEntry> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, String> e : biomeIdToCode.entrySet()) {
            int color = 0xFF808080;
            int[] rgb = biomeTintTable.get(e.getKey());
            if (rgb != null) {
                color = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            }
            out.add(new BiomeEntry(e.getKey(), e.getValue(), color));
        }
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    public static int tint(String name, int color, int biomeId) {
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

    private static int multiplyTint(int template, int[] tint, int offset) {
        int r = ((template >> 16) & 0xFF) * tint[offset] / 255;
        int g = ((template >> 8) & 0xFF) * tint[offset + 1] / 255;
        int b = (template & 0xFF) * tint[offset + 2] / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int blockColor(String name) {
        Integer tableColor = blockColorTable.get(name);
        if (tableColor != null) {
            return tableColor;
        }
        if (name.equals("minecraft:water") || name.equals("minecraft:flowing_water")) {
            return COLOR_WATER;
        }
        if (name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava")) {
            // v0.4.0：熔岩专用亮橙（说明书 5.5：0xFFFF5A00 附近）
            return 0xFFFF5A00;
        }
        // v0.4.0：下界表面常用方块补色（unmined 颜色表参考）
        if (name.equals("minecraft:soul_soil")) return 0xFF4A352B;
        if (name.equals("minecraft:crimson_nylium")) return 0xFF8A1E1E;
        if (name.equals("minecraft:warped_nylium")) return 0xFF1E5A5A;
        if (name.equals("minecraft:magma_block")) return 0xFF8A3A10;
        if (name.equals("minecraft:nether_gold_ore")) return 0xFF8A6A3A;
        if (name.equals("minecraft:nether_quartz_ore")) return 0xFFA89A8A;
        if (name.equals("minecraft:ancient_debris")) return 0xFF6A5A4A;
        if (name.equals("minecraft:bone_block")) return 0xFFD8D0B8;
        if (name.equals("minecraft:blackstone")) return 0xFF2A2628;
        if (name.equals("minecraft:gilded_blackstone")) return 0xFF5A4630;
        if (name.equals("minecraft:basalt")) return 0xFF4A4A52;
        if (name.equals("minecraft:polished_basalt")) return 0xFF5A5A62;
        if (name.equals("minecraft:soul_sand")) return 0xFF5A4636;
        if (name.equals("minecraft:glowstone")) return 0xFFD8B85A;
        if (name.equals("minecraft:netherrack")) return 0xFF6E2A2A;
        if (name.equals("minecraft:shroomlight")) return 0xFFD87A2A;
        if (name.equals("minecraft:crying_obsidian")) return 0xFF2A1A4A;
        if (name.equals("minecraft:obsidian")) return 0xFF14101E;
        if (name.equals("minecraft:gravel")) return 0xFF7A7068;
        if (name.equals("minecraft:nether_wart_block")) return 0xFF7A1E1E;
        if (name.equals("minecraft:warped_wart_block")) return 0xFF1E7A7A;
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

    public static int biomeColor(int biomeId) {
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
                return biomeColor(biomeId - 128);
        }
    }
}
