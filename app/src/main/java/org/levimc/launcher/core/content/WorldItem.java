package org.levimc.launcher.core.content;

import android.util.Log;

import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class WorldItem extends ContentItem {
    private static final String TAG = "WorldItem";

    private String worldName;
    private String gameMode;
    private long lastPlayed;
    private long seed;
    private boolean isValid;
    private boolean isHardcore;

    public WorldItem(String name, File worldDir) {
        super(name, worldDir);
        this.worldName = name;
        loadWorldInfo();
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

    public long getSeed() {
        return seed;
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