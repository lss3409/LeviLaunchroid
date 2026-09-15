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
    private String gameMode;
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
        if (!isValid) return "Invalid world";
        return String.format("游戏模式: %s", gameMode != null ? gameMode : "未知");
    }

    @Override
    public boolean isValid() {
        return isValid;
    }

    public String getWorldName() {
        return worldName;
    }

    public String getGameMode() {
        return gameMode != null ? gameMode : "Unknown";
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
            List<LevelDBEntry> entries = dbReader.readAllEntries();
            for (LevelDBEntry entry : entries) {
                String name = entry.getKey().getDisplayName();
                byte[] rawKey = entry.getKey().getRawKey();
                boolean isPlayerKey = false;
                if (name != null && (name.contains("local_player") || name.startsWith("player"))) {
                    isPlayerKey = true;
                } else if (rawKey != null && (rawKey.length == 9 || rawKey.length == 10)
                        && !entry.getKey().isChunkKey()) {
                    // 1.19+ actor 二进制 key（8 字节 id + 类型字节）：
                    // 非 chunk 的 9/10 字节 key 大概率是玩家/实体数据，按内容判定。
                    isPlayerKey = true;
                }
                if (!isPlayerKey) continue;
                try {
                    NbtTag root = new BedrockNbtReader().readFromBytes(entry.getValue());
                    if (root == null || root.getType() != NbtTag.TAG_COMPOUND) continue;
                    Map<String, NbtTag> compound = root.getCompound();

                    NbtTag deathTag = compound.get("DeathTime");
                    NbtTag healthTag = compound.get("Health");
                    NbtTag deadTag = compound.get("Dead");
                    NbtTag gameModeTag = compound.get("PlayerGameMode");
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
                        playerHealth = healthTag.getFloat();
                        if (playerHealth <= 0f) {
                            playerDead = true;
                        }
                    }
                    if (isHardcore && gameModeTag != null) {
                        // 硬核死亡后玩家模式变为观察者（实测死亡存档 PlayerGameMode=5）
                        int gm = gameModeTag.getInt();
                        if (gm == 5 || gm == 6) {
                            playerDead = true;
                        }
                    }
                    break;
                } catch (Exception ignored) {
                }
            }
            dbReader.close();
        } catch (Exception e) {
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
                    int gameModeInt = gameModeTag.getInt();
                    gameMode = getGameModeName(gameModeInt);
                }

                NbtTag seedTag = compound.get("RandomSeed");
                if (seedTag != null) {
                    seed = seedTag.getLong();
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

        if (gameMode == null) {
            gameMode = "生存模式";
        }

        lastPlayed = file.lastModified();
    }

    private String getGameModeName(int gameType) {
        return switch (gameType) {
            case 0 -> "生存模式";
            case 1 -> "创造模式";
            case 2 -> "冒险模式";
            case 3 -> "旁观模式";
            default -> "未知";
        };
    }
}