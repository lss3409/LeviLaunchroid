package org.levimc.launcher.core.content;

import android.util.Log;

import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class WorldEditor {
    private static final String TAG = "WorldEditor";

    private static final Map<String, String> FIELD_TRANSLATIONS = createFieldTranslations();

    private static Map<String, String> createFieldTranslations() {
        Map<String, String> map = new HashMap<>();
        map.put("LevelName", "世界名称");
        map.put("WorldName", "世界名称");
        map.put("RandomSeed", "随机种子");
        map.put("LastPlayed", "最后游玩时间");
        map.put("GameType", "游戏模式");
        map.put("GameMode", "游戏模式");
        map.put("Difficulty", "难度");
        map.put("CommandsEnabled", "允许作弊");
        map.put("LimitedWorldWidth", "有限世界宽度");
        map.put("LimitedWorldDepth", "有限世界深度");
        map.put("NetherScale", "下界比例");
        map.put("Time", "时间");
        map.put("DayTime", "白天时间");
        map.put("CurrentTick", "当前刻");
        map.put("SpawnX", "出生点 X");
        map.put("SpawnY", "出生点 Y");
        map.put("SpawnZ", "出生点 Z");
        map.put("SpawnV1Vbr", "出生点");
        map.put("rainTime", "降雨时间");
        map.put("rainLevel", "降雨强度");
        map.put("thunderTime", "雷暴时间");
        map.put("thunderLevel", "雷暴强度");
        map.put("BiomeOverride", "生物群系覆盖");
        map.put("SpawningOverride", "生成覆盖");
        map.put("showcoordinates", "显示坐标");
        map.put("achievementsdisabled", "成就已禁用");
        map.put("educationfeaturesenabled", "教育版功能已启用");
        map.put("bonusChestEnabled", "奖励箱已启用");
        map.put("bonusChestSpawned", "奖励箱已生成");
        map.put("startWithMapEnabled", "起始地图已启用");
        map.put("BaseGameVersion", "基础游戏版本");
        map.put("Generator", "生成器");
        map.put("LevelSeed", "世界种子");
        map.put("GlobalLightning", "全局光照");
        map.put("weather", "天气");
        map.put("RequiresCopiedPackRemovalCheck", "需要检查并清理已复制的包");
        map.put("ServerEditorConnectionPolicy", "服务器编辑器连接策略");
        map.put("Showbordereffect", "显示边界效果");
        map.put("Showdeathmessages", "显示死亡消息");
        map.put("Showrecipemessages", "显示配方消息");
        map.put("Showtags", "显示名称标签");
        map.put("TexturePacksRequired", "需要纹理包");
        map.put("CenterMapsToOrigin", "将地图居中到原点");
        map.put("ConfirmedPlatformLockedContent", "已确认平台锁定内容");
        map.put("InventoryVersion", "物品栏数据版本");
        map.put("IsHardcore", "硬核模式");
        map.put("LANBroadcast", "局域网游戏广播");
        map.put("LANBroadcastIntent", "局域网游戏广播意图");
        map.put("MinimumCompatibleClientVersion", "最低兼容客户端版本");
        map.put("NetworkVersion", "网络协议版本");
        map.put("Platform", "平台");
        map.put("PlatformBroadcastIntent", "平台广播意图");
        map.put("StorageVersion", "存储格式版本");
        map.put("XBLBroadcastIntent", "Xbox Live 广播意图");
        map.put("Attackmobs", "攻击生物");
        map.put("Build", "建造");
        map.put("Doorsandswitches", "使用门和开关");
        map.put("FlySpeed", "飞行速度");
        map.put("Flying", "飞行");
        map.put("Instabuild", "瞬间破坏与放置");
        map.put("Invulnerable", "无敌");
        map.put("Lightning", "召唤雷电");
        map.put("Mayfly", "允许飞行");
        map.put("Mine", "挖掘");
        map.put("Op", "管理员");
        map.put("Opencontainers", "打开容器");
        map.put("Teleport", "传送");
        map.put("VerticalFlySpeed", "垂直飞行速度");
        map.put("WalkSpeed", "行走速度");
        map.put("Doentitydrops", "实体掉落");
        map.put("Doinsomnia", "生成幻翼");
        map.put("Dolimitedcrafting", "限制合成");
        map.put("Domobloot", "生物掉落");
        map.put("Dotiledrops", "方块掉落");
        map.put("Drowningdamage", "溺水伤害");
        map.put("EduOffer", "教育版优惠");
        map.put("EducationFeaturesEnabled", "教育版功能已启用");
        map.put("Falldamage", "摔落伤害");
        map.put("Firedamage", "火焰伤害");
        map.put("Freezedamage", "冰冻伤害");
        map.put("HasBeenLoadedInCreative", "曾在创造模式加载");
        map.put("HasLockedBehaviorPack", "已锁定行为包");
        map.put("HasLockedResourcePack", "已锁定资源包");
        map.put("IsCreatedInEditor", "由编辑器创建");
        map.put("IsFromLockedTemplate", "来自锁定模板");
        map.put("Keepinventory", "保留物品栏");
        map.put("LastOpenedWithVersion", "最后打开的版本");
        map.put("locatorbar", "定位栏");
        map.put("Mobgriefing", "生物破坏");
        map.put("Naturalregeneration", "自然恢复");
        map.put("Prid", "玩家运行时 ID");
        map.put("Projectilescanbreakblocks", "弹射物破坏方块");
        map.put("Pvp", "玩家对战");
        map.put("Recipesunlock", "配方解锁");
        map.put("ForceGameType", "强制游戏模式");
        map.put("MultiplayerGame", "多人游戏");
        map.put("MultiplayerGameIntent", "多人游戏意图");
        map.put("UseMsaGamertagsOnly", "仅使用微软账户玩家代号");
        map.put("FlatWorldLayers", "超平坦世界层");
        map.put("HasUncompleteWorldFileOnDisk", "磁盘上有未完成的世界文件");
        map.put("LimitedWorldOriginX", "有限世界原点 X");
        map.put("LimitedWorldOriginY", "有限世界原点 Y");
        map.put("LimitedWorldOriginZ", "有限世界原点 Z");
        map.put("WorldVersion", "世界版本");
        map.put("AllowAnonymousBlockDropsInEditorWorlds", "编辑器世界允许匿名方块掉落");
        map.put("EditorWorldType", "编辑器世界类型");
        map.put("ImmutableWorld", "不可变世界");
        map.put("IsFromWorldTemplate", "来自世界模板");
        map.put("IsRandomSeedAllowed", "允许随机种子");
        map.put("IsSingleUseWorld", "一次性世界");
        map.put("IsWorldTemplateOptionLocked", "世界模板选项已锁定");
        map.put("LimitedWorldDeath", "有限世界死亡");
        map.put("WorldStartCount", "世界开始次数");
        map.put("PlayerHasDied", "玩家已死亡");
        map.put("Attackplayers", "攻击玩家");
        map.put("Experiments_ever_used", "曾使用实验性玩法");
        map.put("Saved_with_toggled_experiments", "保存时切换过实验性玩法");
        map.put("IsExportedFromEditor", "由编辑器导出");
        map.put("LightningLevel", "闪电等级");
        map.put("PermissionsLevel", "权限等级");
        map.put("PlayerPermissionsLevel", "玩家权限等级");
        map.put("Playerssleepingpercentage", "玩家睡眠比例");
        map.put("Playerwaypoints", "玩家路径点");
        map.put("Tntexplodes", "TNT 爆炸");
        map.put("Tntexplosiondropdecay", "TNT 爆炸掉落衰减");
        map.put("SpawnV1Villagers", "生成村民");
        map.put("Doimmediaterespawn", "立即重生");
        map.put("Domobspawning", "生成生物");
        map.put("Respawnblocksexplode", "重生方块爆炸");
        map.put("SpawnMobs", "生成生物");
        map.put("Spawnradius", "生成半径");
        map.put("DaylightCycle", "昼夜更替");
        map.put("Dodaylightcycle", "昼夜更替");
        map.put("Dofiretick", "火焰蔓延");
        map.put("LightningTime", "闪电时间");
        map.put("Randomtickspeed", "随机刻速度");
        map.put("ServerChunkTickRange", "服务器区块刻范围");
        map.put("Showdaysplayed", "显示游玩天数");
        map.put("CheatsEnabled", "允许作弊");
        map.put("Commandblockoutput", "命令方块输出");
        map.put("Commandblocksenabled", "命令方块已启用");
        map.put("Functioncommandlimit", "函数命令限制");
        map.put("Maxcommandchainlength", "最大命令链长度");
        map.put("Sendcommandfeedback", "发送命令反馈");
        map.put("Doweathercycle", "天气循环");
        return map;
    }

    private final File worldDir;
    private final File levelDatFile;
    
    private NbtTag levelDatRoot;
    private int levelDatVersion;
    private boolean levelDatLoaded = false;

    public WorldEditor(File worldDir) {
        this.worldDir = worldDir;
        this.levelDatFile = new File(worldDir, "level.dat");
    }

    public void loadLevelDat() throws IOException {
        if (!levelDatFile.exists()) {
            throw new IOException("level.dat not found");
        }

        BedrockNbtReader reader = new BedrockNbtReader();
        levelDatRoot = reader.readFile(levelDatFile);
        levelDatVersion = reader.getHeaderVersion();
        levelDatLoaded = true;
        
        Log.d(TAG, "Loaded level.dat, version: " + levelDatVersion);
    }

    public void saveLevelDat() throws IOException {
        if (!levelDatLoaded || levelDatRoot == null) {
            throw new IOException("level.dat not loaded");
        }

        File backup = new File(worldDir, "level.dat.backup");
        if (levelDatFile.exists()) {
            copyFile(levelDatFile, backup);
        }

        BedrockNbtWriter writer = new BedrockNbtWriter();
        writer.setHeaderVersion(levelDatVersion);
        writer.writeFile(levelDatFile, levelDatRoot);

        updateLevelNameFile();
        
        Log.d(TAG, "Saved level.dat");
    }

    private void updateLevelNameFile() {
        if (levelDatRoot == null || levelDatRoot.getType() != NbtTag.TAG_COMPOUND) {
            return;
        }
        
        NbtTag levelNameTag = levelDatRoot.getTag("LevelName");
        if (levelNameTag != null && levelNameTag.getType() == NbtTag.TAG_STRING) {
            String levelName = levelNameTag.getString();
            if (levelName != null && !levelName.isEmpty()) {
                File levelNameFile = new File(worldDir, "levelname.txt");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(levelNameFile)) {
                    fos.write(levelName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Log.d(TAG, "Updated levelname.txt: " + levelName);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to update levelname.txt", e);
                }
            }
        }
    }

    public List<WorldProperty> getLevelDatProperties() {
        List<WorldProperty> properties = new ArrayList<>();
        
        if (levelDatRoot == null || levelDatRoot.getType() != NbtTag.TAG_COMPOUND) {
            return properties;
        }

        Map<String, NbtTag> compound = levelDatRoot.getCompound();
        extractProperties(compound, "", properties);
        
        return properties;
    }

    private void extractProperties(Map<String, NbtTag> compound, String prefix, List<WorldProperty> properties) {
        for (Map.Entry<String, NbtTag> entry : compound.entrySet()) {
            NbtTag tag = entry.getValue();
            String fullPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            
            if (tag.getType() == NbtTag.TAG_COMPOUND) {
                extractProperties(tag.getCompound(), fullPath, properties);
            } else if (tag.isEditable()) {
                properties.add(new WorldProperty(fullPath, tag));
            }
        }
    }

    public void updateLevelDatProperty(String path, Object newValue) {
        if (levelDatRoot == null) return;
        
        String[] parts = path.split("\\.");
        NbtTag current = levelDatRoot;

        for (int i = 0; i < parts.length - 1; i++) {
            if (current.getType() != NbtTag.TAG_COMPOUND) return;
            current = current.getTag(parts[i]);
            if (current == null) return;
        }

        if (current.getType() == NbtTag.TAG_COMPOUND) {
            NbtTag target = current.getTag(parts[parts.length - 1]);
            if (target != null) {
                target.setValue(convertValue(newValue, target.getType()));
            }
        }
    }

    private Object convertValue(Object value, byte targetType) {
        if (value instanceof String) {
            String str = (String) value;
            return switch (targetType) {
                case NbtTag.TAG_BYTE -> Byte.parseByte(str);
                case NbtTag.TAG_SHORT -> Short.parseShort(str);
                case NbtTag.TAG_INT -> Integer.parseInt(str);
                case NbtTag.TAG_LONG -> Long.parseLong(str);
                case NbtTag.TAG_FLOAT -> Float.parseFloat(str);
                case NbtTag.TAG_DOUBLE -> Double.parseDouble(str);
                case NbtTag.TAG_STRING -> str;
                default -> value;
            };
        }
        return value;
    }

    public boolean hasLevelDat() {
        return levelDatFile.exists();
    }

    public boolean isLevelDatLoaded() {
        return levelDatLoaded;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    public static class WorldProperty {
        private final String path;
        private final NbtTag tag;
        private final String category;

        public WorldProperty(String path, NbtTag tag) {
            this.path = path;
            this.tag = tag;
            this.category = categorize(path);
        }

        public String getPath() { return path; }
        public NbtTag getTag() { return tag; }
        public String getName() { return tag.getName(); }
        public String getCategory() { return category; }
        public byte getType() { return tag.getType(); }
        public Object getValue() { return tag.getValue(); }
        
        public String getDisplayName() {
            String name = tag.getName();
            if (name.isEmpty()) {
                String[] parts = path.split("\\.");
                name = parts[parts.length - 1];
            }
            return formatName(name);
        }

        public String getValueString() {
            Object value = tag.getValue();
            if (value == null) return "";
            return value.toString();
        }

        public String getTypeString() {
            return NbtTag.getTypeName(tag.getType());
        }

        private String formatName(String name) {
            String translated = FIELD_TRANSLATIONS.get(name);
            if (translated == null) {
                for (Map.Entry<String, String> entry : FIELD_TRANSLATIONS.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(name)) {
                        translated = entry.getValue();
                        break;
                    }
                }
            }
            if (translated != null) {
                return translated;
            }
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (i > 0 && Character.isUpperCase(c)) {
                    result.append(' ');
                }
                result.append(i == 0 ? Character.toUpperCase(c) : c);
            }
            return result.toString();
        }

        private String categorize(String path) {
            String lower = path.toLowerCase();
            if (lower.contains("spawn") || lower.contains("position") || lower.contains("pos")) {
                return "位置";
            }
            if (lower.contains("game") || lower.contains("mode") || lower.contains("difficulty")) {
                return "游戏设置";
            }
            if (lower.contains("time") || lower.contains("day") || lower.contains("tick")) {
                return "时间";
            }
            if (lower.contains("weather") || lower.contains("rain") || lower.contains("thunder")) {
                return "天气";
            }
            if (lower.contains("player") || lower.contains("xp") || lower.contains("level")) {
                return "玩家";
            }
            if (lower.contains("world") || lower.contains("seed") || lower.contains("generator")) {
                return "世界";
            }
            if (lower.contains("cheat") || lower.contains("command") || lower.contains("allow")) {
                return "作弊与命令";
            }
            if (lower.contains("experiment") || lower.contains("beta")) {
                return "实验性玩法";
            }
            return "其他";
        }

        public boolean isBoolean() {
            if (tag.getType() == NbtTag.TAG_BYTE) {
                byte val = tag.getByte();
                return val == 0 || val == 1;
            }
            return false;
        }
    }
}