package org.levimc.launcher.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StorageMigrationManagerTest {
    @Test
    public void mapsVersionApkToVersionRoot() {
        assertEquals(
                "minecraft/Minecraft_1.21.80/base.apk.levi",
                StorageMigrationManager.mapLegacyRootRelativePath("minecraft/Minecraft_1.21.80/base.apk.levi")
        );
    }

    @Test
    public void mapsVersionGameDataToFilesRoot() {
        assertEquals(
                "minecraft/Minecraft_1.21.80/internal/games/com.mojang/minecraftWorlds/world1/level.dat",
                StorageMigrationManager.mapLegacyRootRelativePath(
                        "minecraft/Minecraft_1.21.80/games/com.mojang/minecraftWorlds/world1/level.dat"
                )
        );
    }

    @Test
    public void mapsNestedVersionGameDataToExternalFilesRoot() {
        assertEquals(
                "minecraft/Minecraft_1.21.80/external/games/com.mojang/minecraftWorlds/world1/level.dat",
                StorageMigrationManager.mapLegacyRootRelativePath(
                        "minecraft/Minecraft_1.21.80/games/com.mojang/games/com.mojang/minecraftWorlds/world1/level.dat"
                )
        );
    }

    @Test
    public void mapsVersionCacheToProfileCacheRoot() {
        assertEquals(
                "minecraft/Minecraft_1.21.80/cache/foo.bin",
                StorageMigrationManager.mapLegacyRootRelativePath("minecraft/Minecraft_1.21.80/cache/foo.bin")
        );
    }

    @Test
    public void mapsOtherGamesChildrenToDataRoot() {
        assertEquals(
                "minecraft/Minecraft_1.21.80/data/games/other/file.bin",
                StorageMigrationManager.mapLegacyRootRelativePath("minecraft/Minecraft_1.21.80/games/other/file.bin")
        );
    }

    @Test
    public void mapsInstalledVersionDirectoriesToFixedPackageProfile() {
        assertEquals(
                "minecraft/com.mojang.minecraftpe/cache/log.txt",
                StorageMigrationManager.mapLegacyRootRelativePath(
                        "minecraft/com.mojang.minecraftpe_123/cache/log.txt"
                )
        );
    }

    @Test
    public void mapsUnknownRootFilesToUnclassifiedDirectory() {
        assertEquals(
                "minecraft/_legacy_unclassified/random/file.txt",
                StorageMigrationManager.mapLegacyRootRelativePath("random/file.txt")
        );
    }

    @Test
    public void mapsLegacyCrashLogsToCrashLogsRoot() {
        assertEquals(
                "crash_logs/native/tombstone.log",
                StorageMigrationManager.mapLegacyRootRelativePath("crash_logs/native/tombstone.log")
        );
    }

    @Test
    public void mapsReservedLegacyVersionNameToSafeProfile() {
        assertEquals(
                "minecraft/_shared_profile/data/file.bin",
                StorageMigrationManager.mapLegacyRootRelativePath("minecraft/_shared/file.bin")
        );
    }
}
