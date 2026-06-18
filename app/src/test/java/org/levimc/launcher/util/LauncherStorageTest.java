package org.levimc.launcher.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class LauncherStorageTest {
    @Test
    public void sanitizeProfileIdKeepsSupportedCharacters() {
        assertEquals("Minecraft_1.21.80-beta", LauncherStorage.sanitizeProfileId("Minecraft_1.21.80-beta"));
    }

    @Test
    public void sanitizeProfileIdReplacesUnsupportedCharacters() {
        assertEquals("My_Version__1", LauncherStorage.sanitizeProfileId("My Version:/1"));
    }

    @Test
    public void sanitizeProfileIdAvoidsReservedNames() {
        assertEquals("_shared_profile", LauncherStorage.sanitizeProfileId("_shared"));
        assertEquals("_legacy_unclassified_profile", LauncherStorage.sanitizeProfileId("_legacy_unclassified"));
    }

    @Test
    public void isReservedProfileIdDetectsRawReservedNames() {
        assertTrue(LauncherStorage.isReservedProfileId("_shared"));
        assertTrue(LauncherStorage.isReservedProfileId("_legacy_unclassified"));
        assertFalse(LauncherStorage.isReservedProfileId("Minecraft_1.21.80"));
    }

    @Test
    public void mediaAppRootUsesPackageMediaDirectory() {
        File mediaDir = new File("/storage/emulated/0/Android/media/org.levimc.launcher");

        assertEquals(mediaDir, LauncherStorage.buildTargetMediaAppRoot(mediaDir));
    }

    @Test
    public void displayPathUsesAndroidMediaPackageDirectory() {
        assertEquals(
                "Android/media/org.levimc.launcher",
                LauncherStorage.buildTargetAppRootDisplayPath("org.levimc.launcher")
        );
    }
}
