package org.levimc.launcher.core.content;

import android.util.Log;

import org.levimc.launcher.core.content.leveldb.LevelDBEntry;
import org.levimc.launcher.core.content.leveldb.LevelDBReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class WorldItem extends ContentItem {
    private static final String TAG = "WorldItem";

    private String worldName;
    /** v413：存档唯一标识（导出 .mcworld 注入的 leviWorldId；
     *  老存档无则 null，备份分类回退世界名_seed）。 */
    private String leviWorldId;
    /** v416：level.dat LastPlayed（游戏进入世界时间戳）。 */
    private long levelLastPlayed;
    /** v0.0.11：存 int 游戏类型（-1 未知），显示时按语言转文本。 */
    private int gameType = -1;
    private long lastPlayed;
    private long seed;
    private boolean isValid;
    private boolean isHardcore;
    private boolean playerDead;
    private float playerHealth = -1f;

    public WorldItem(String name, File worldDir) {
        super(name, worldDir);
        this.worldName = name;
        loadWorldInfo();
        loadPlayerState();
    }

    @Override
    public String getType() {
        return "World";
    }

    @Override
    public String getDescription() {
        return "World";
    }

    /** v0.0.11：按语言返回描述（调用方有 Context 时使用）。 */
    public String getDescription(android.content.Context ctx) {
        return ctx.getString(org.levimc.launcher.R.string.game_mode_label,
                getGameMode(ctx));
    }

    @Override
    public boolean isValid() {
        return isValid;
    }

    public String getWorldName() {
        return worldName;
    }

    /** v0.0.24：修复存档游戏模式恒显示 Unknown——无参版本硬编码 "Unknown"，
     *  三处 UI 调用方误用。改为与有参版本同源的映射（英文，无 Context 时用）。 */
    public String getGameMode() {
        switch (gameType) {
            case 0: return "Survival";
            case 1: return "Creative";
            case 2: return "Adventure";
            case 3: return "Spectator";
            default: return "Unknown";
        }
    }

    /** v0.0.11：按语言返回游戏模式文本。 */
    public String getGameMode(android.content.Context ctx) {
        int res;
        switch (gameType) {
            case 0:
                res = org.levimc.launcher.R.string.game_mode_survival;
                break;
            case 1:
                res = org.levimc.launcher.R.string.game_mode_creative;
                break;
            case 2:
                res = org.levimc.launcher.R.string.game_mode_adventure;
                break;
            case 3:
                res = org.levimc.launcher.R.string.game_mode_spectator;
                break;
            default:
                res = org.levimc.launcher.R.string.game_mode_unknown;
        }
        return ctx.getString(res);
    }

    public File getIconFile() {
        if (file == null) return null;
        String[] names = {"world_icon.jpeg", "world_icon.jpg", "world_icon.png"};
        for (String name : names) {
            File icon = new File(file, name);
            if (icon.isFile()) return icon;
        }
        return null;
    }

    /** v413：存档唯一标识（无则 null）。 */
    public String getLeviWorldId() {
        return leviWorldId;
    }

    /** v416：level.dat 的 LastPlayed（游戏进入世界时间戳；0 = 无字段）。 */
    public long getLevelLastPlayed() {
        return levelLastPlayed;
    }

    public long getSeed() {
        return seed;
    }

    /** 极限模式玩家是否已死亡（读取 LevelDB 本地玩家 NBT 的 DeathTime/Health）。 */
    public boolean isPlayerDead() {
        return playerDead;
    }

    /** 玩家当前生命值（-1 = 未读到）。 */
    public float getPlayerHealth() {
        return playerHealth;
    }

    /** 读本地玩家状态（db 的玩家 NBT）：死亡标记 + 生命值。 */
    private void loadPlayerState() {
        File dbDir = new File(file, "db");
        if (!dbDir.isDirectory()) return;
        try {
            LevelDBReader dbReader = new LevelDBReader(dbDir);
            // 只按前缀定位读取玩家 key（index block 二分，只解压 1-2 个 data block）。
            // 之前 readEntries(isPlayerKey) 的 9/10B 宽泛判定会匹配十几万条
            // actor/chunk key，等于全表扫描（183MB 世界 5 秒，阻塞地图首屏）。
            List<LevelDBEntry> entries = new java.util.ArrayList<>();
            entries.addAll(dbReader.readEntriesByPrefix(
                    "~local_player".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            entries.addAll(dbReader.readEntriesByPrefix(
                    "player".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            for (LevelDBEntry entry : entries) {
                try {
                    NbtTag root = new BedrockNbtReader().readFromBytes(entry.getValue());
                    if (root == null || root.getType() != NbtTag.TAG_COMPOUND) continue;
                    Map<String, NbtTag> compound = root.getCompound();

                    NbtTag deathTag = compound.get("DeathTime");
                    NbtTag healthTag = compound.get("Health");
                    NbtTag deadTag = compound.get("Dead");

                    if (deathTag == null && healthTag == null && deadTag == null) continue;
                    if (deathTag != null && deathTag.getInt() > 0) {
                        // 死亡画面倒计时（TAG_Short ticks，硬核死亡后退出时仍 >0）
                        playerDead = true;
                    }
                    if (deadTag != null && deadTag.getByte() != 0) {
                        // 1.19+ 玩家数据的 Dead 标记（TAG_Byte）
                        playerDead = true;
                    }
                    if (healthTag != null) {
                        // v457：Health 字段类型兼容（部分版本是 Short/Int，
                        // getFloat 对非浮点类型返回 0——"生命值异常"根因）
                        switch (healthTag.getType()) {
                            case NbtTag.TAG_FLOAT:
                                playerHealth = healthTag.getFloat();
                                break;
                            case NbtTag.TAG_INT:
                                playerHealth = healthTag.getInt();
                                break;
                            case NbtTag.TAG_SHORT:
                                playerHealth = healthTag.getShort();
                                break;
                            default:
                                playerHealth = healthTag.getFloat();
                        }
                        if (playerHealth <= 0f) {
                            playerDead = true;
                        }
                    } else {
                        // v457：1.18+ 健康在 Attributes 列表
                        // （minecraft:health 的 Current）
                        NbtTag attrs = compound.get("Attributes");
                        if (attrs != null && attrs.getType() == NbtTag.TAG_LIST) {
                            for (NbtTag a : attrs.getList()) {
                                if (a == null || a.getType() != NbtTag.TAG_COMPOUND) {
                                    continue;
                                }
                                NbtTag nm = a.getCompound().get("Name");
                                if (nm != null && nm.getString() != null
                                        && nm.getString().contains("health")) {
                                    NbtTag cur = a.getCompound().get("Current");
                                    if (cur != null) {
                                        playerHealth = cur.getFloat();
                                        if (playerHealth <= 0f) {
                                            playerDead = true;
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    // PlayerGameMode 不可靠（实测未死存档也会是 5/6），不参与死亡判定。
                    // 死亡只认：DeathTime>0、Dead!=0、Health<=0（以及 level.dat 的 PlayerHasDied）。
                    break;
                } catch (Exception ignored) {
                }
            }
            dbReader.close();
        } catch (Throwable e) {
            // 捕获 Throwable：OOM 等 Error 不能让整个世界扫描崩溃
            Log.w(TAG, "Failed to read player state for " + file.getName(), e);
        }
    }

    public boolean isHardcore() {
        return isHardcore;
    }

    /** 世界唯一标识：目录名（随机生成，导入/创建时稳定），用于极限存档备份的识别。 */
    public String getWorldId() {
        return file != null ? file.getName() : name;
    }

    private void loadWorldInfo() {
        if (file == null || !file.exists() || !file.isDirectory()) {
            isValid = false;
            return;
        }

        File levelDat = new File(file, "level.dat");
        File levelNameFile = new File(file, "levelname.txt");

        if (!levelDat.exists()) {
            isValid = false;
            return;
        }

        isValid = true;

        if (levelNameFile.exists()) {
            try (FileInputStream fis = new FileInputStream(levelNameFile)) {
                byte[] data = new byte[(int) levelNameFile.length()];
                fis.read(data);
                worldName = new String(data, StandardCharsets.UTF_8).trim();
                if (!worldName.isEmpty()) {
                    this.name = worldName;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read levelname.txt for " + file.getName(), e);
            }
        }

        try {
            BedrockNbtReader reader = new BedrockNbtReader();
            NbtTag root = reader.readFile(levelDat);

            if (root != null && root.getType() == NbtTag.TAG_COMPOUND) {
                Map<String, NbtTag> compound = root.getCompound();

                NbtTag gameModeTag = compound.get("GameType");
                if (gameModeTag != null) {
                    gameType = gameModeTag.getInt();
                }

                NbtTag seedTag = compound.get("RandomSeed");
                if (seedTag != null) {
                    seed = seedTag.getLong();
                }

                // v413：存档唯一标识（导出的 .mcworld 注入的 leviWorldId）
                NbtTag idTag = compound.get("leviWorldId");
                if (idTag != null) {
                    leviWorldId = idTag.getString();
                }

                // v416：level.dat 的 LastPlayed（游戏进入世界时更新——
                // 比目录 mtime 可靠的变化依据，启动器自身操作不改它）
                NbtTag lpTag = compound.get("LastPlayed");
                if (lpTag != null) {
                    levelLastPlayed = lpTag.getLong();
                }

                NbtTag hardcoreTag = compound.get("IsHardcore");
                if (hardcoreTag != null) {
                    isHardcore = hardcoreTag.getByte() != 0;
                }

                // 官方死亡标记（硬核死亡后游戏写入 level.dat）
                NbtTag diedTag = compound.get("PlayerHasDied");
                if (diedTag != null && diedTag.getByte() != 0) {
                    playerDead = true;
                }

                if (worldName == null || worldName.isEmpty() || worldName.equals(file.getName())) {
                    NbtTag levelNameTag = compound.get("LevelName");
                    if (levelNameTag != null && levelNameTag.getType() == NbtTag.TAG_STRING) {
                        String nbtName = levelNameTag.getString();
                        if (nbtName != null && !nbtName.isEmpty()) {
                            worldName = nbtName;
                            this.name = worldName;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read level.dat for " + file.getName(), e);
        }

        if (gameType < 0) {
            gameType = 0; // 默认生存模式
        }

        lastPlayed = file.lastModified();
    }
}