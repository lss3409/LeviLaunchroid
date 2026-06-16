package org.levimc.launcher.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class InstanceBackupManagerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void manifestRoundTripsUnknownFieldsIgnored() {
        String json = "{"
                + "\"format\":\"levilauncher_instance_backup\","
                + "\"schemaVersion\":1,"
                + "\"instanceName\":\"Demo\","
                + "\"directoryName\":\"Minecraft_1.21.80\","
                + "\"profileId\":\"Minecraft_1.21.80\","
                + "\"versionName\":\"1.21.80\","
                + "\"installed\":false,"
                + "\"versionIsolation\":true,"
                + "\"launchVertically\":false,"
                + "\"createdAt\":123,"
                + "\"unknown\":\"ignored\""
                + "}";

        InstanceBackupManager.BackupManifest manifest = InstanceBackupManager.parseManifestJson(json);

        assertEquals("levilauncher_instance_backup", manifest.format);
        assertEquals(1, manifest.schemaVersion);
        assertEquals("Demo", manifest.instanceName);
        assertEquals("Minecraft_1.21.80", manifest.directoryName);
        assertEquals("1.21.80", manifest.versionName);
        assertFalse(manifest.installed);
        assertTrue(manifest.versionIsolation);
    }

    @Test
    public void backupFileNamesAcceptLevibackupAndZip() {
        assertTrue(InstanceBackupManager.isBackupFileName("demo.levibackup"));
        assertTrue(InstanceBackupManager.isBackupFileName("demo.zip"));
        assertTrue(InstanceBackupManager.isBackupFileName("DEMO.LEVIBACKUP"));
        assertFalse(InstanceBackupManager.isBackupFileName("demo.mcworld"));
    }

    @Test
    public void restoredDirectoryNameCreatesCopyOnConflict() throws Exception {
        File root = temporaryFolder.newFolder("minecraft");
        assertTrue(new File(root, "Minecraft_1.21.80").mkdirs());
        assertTrue(new File(root, "Minecraft_1.21.80_restored").mkdirs());

        assertEquals(
                "Minecraft_1.21.80_restored_1",
                InstanceBackupManager.generateRestoredDirectoryName(root, "Minecraft_1.21.80")
        );
    }

    @Test
    public void zipEntrySafetyRejectsTraversal() {
        assertTrue(InstanceBackupManager.isSafeZipEntryName("profile/data/options.txt"));
        assertTrue(InstanceBackupManager.isSafeZipEntryName("./profile/data/options.txt"));
        assertFalse(InstanceBackupManager.isSafeZipEntryName("../evil.txt"));
        assertFalse(InstanceBackupManager.isSafeZipEntryName("profile/../evil.txt"));
        assertFalse(InstanceBackupManager.isSafeZipEntryName("/profile/data.txt"));
        assertFalse(InstanceBackupManager.isSafeZipEntryName("C:/profile/data.txt"));
    }
}
