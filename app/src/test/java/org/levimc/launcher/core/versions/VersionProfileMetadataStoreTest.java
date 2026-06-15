package org.levimc.launcher.core.versions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.levimc.launcher.util.LauncherStorage;

public class VersionProfileMetadataStoreTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final VersionProfileMetadataStore store = new VersionProfileMetadataStore();
    private final Gson gson = new Gson();

    @Test
    public void customProfileDefaultsToVersionIsolationEnabled() throws Exception {
        File metadataDir = temporaryFolder.newFolder("custom-default");

        VersionProfileMetadata metadata = store.loadOrCreate(
                metadataDir,
                VersionProfileMetadataStore.Defaults.custom("Minecraft_1.21.80", "1.21.80")
        );

        assertEquals(1, metadata.schemaVersion);
        assertEquals("Minecraft_1.21.80", metadata.profileId);
        assertEquals("Minecraft_1.21.80", metadata.directoryName);
        assertEquals("1.21.80", metadata.versionName);
        assertNull(metadata.displayName);
        assertTrue(metadata.versionIsolation);
        assertFalse(metadata.launchVertically);
        assertFalse(metadata.installed);
        assertNull(metadata.packageName);
        assertTrue(profileJson(metadataDir).isFile());
    }

    @Test
    public void installedProfileDefaultsToVersionIsolationDisabled() throws Exception {
        File metadataDir = temporaryFolder.newFolder("installed-default");

        VersionProfileMetadata metadata = store.loadOrCreate(
                metadataDir,
                VersionProfileMetadataStore.Defaults.installed("com.mojang.minecraftpe", "1.21.80")
        );

        assertEquals(LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID, metadata.profileId);
        assertEquals(LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID, metadata.directoryName);
        assertEquals("1.21.80", metadata.versionName);
        assertFalse(metadata.versionIsolation);
        assertFalse(metadata.launchVertically);
        assertTrue(metadata.installed);
        assertEquals("com.mojang.minecraftpe", metadata.packageName);
        assertTrue(profileJson(metadataDir).isFile());
    }

    @Test
    public void legacyTxtFilesAreMergedIntoProfileJson() throws Exception {
        File metadataDir = temporaryFolder.newFolder("legacy");
        write(metadataDir, "version.txt", "1.21.90");
        write(metadataDir, "display_name.txt", "My World Profile");
        write(metadataDir, "version_isolation.txt", "false");
        write(metadataDir, "launch_vertically.txt", "true");

        VersionProfileMetadata metadata = store.loadOrCreate(
                metadataDir,
                VersionProfileMetadataStore.Defaults.custom("Minecraft_1.21.90", "fallback")
        );

        assertEquals("Minecraft_1.21.90", metadata.profileId);
        assertEquals("Minecraft_1.21.90", metadata.directoryName);
        assertEquals("1.21.90", metadata.versionName);
        assertEquals("My World Profile", metadata.displayName);
        assertFalse(metadata.versionIsolation);
        assertTrue(metadata.launchVertically);
        assertFalse(metadata.installed);
        assertTrue(profileJson(metadataDir).isFile());
        assertEquals("1.21.90", read(new File(metadataDir, "version.txt")));
    }

    @Test
    public void profileJsonWinsOverLegacyTxtFiles() throws Exception {
        File metadataDir = temporaryFolder.newFolder("json-priority");
        write(metadataDir, "version.txt", "legacy-version");
        write(metadataDir, "display_name.txt", "Legacy Name");
        VersionProfileMetadata jsonMetadata = new VersionProfileMetadata();
        jsonMetadata.profileId = "Profile";
        jsonMetadata.directoryName = "Profile";
        jsonMetadata.versionName = "json-version";
        jsonMetadata.displayName = "Json Name";
        jsonMetadata.versionIsolation = true;
        jsonMetadata.launchVertically = false;
        jsonMetadata.installed = false;
        store.save(metadataDir, jsonMetadata);

        VersionProfileMetadata metadata = store.loadOrCreate(
                metadataDir,
                VersionProfileMetadataStore.Defaults.custom("Profile", "fallback")
        );

        assertEquals("json-version", metadata.versionName);
        assertEquals("Json Name", metadata.displayName);
    }

    @Test
    public void updateOnlyChangesProfileJsonAndKeepsLegacyTxtUntouched() throws Exception {
        File metadataDir = temporaryFolder.newFolder("update-json");
        write(metadataDir, "version.txt", "1.21.80");
        write(metadataDir, "display_name.txt", "Legacy Name");
        write(metadataDir, "version_isolation.txt", "true");
        write(metadataDir, "launch_vertically.txt", "false");
        store.loadOrCreate(metadataDir, VersionProfileMetadataStore.Defaults.custom("Profile", "fallback"));

        store.update(metadataDir, VersionProfileMetadataStore.Defaults.custom("Profile", "fallback"), metadata -> {
            metadata.displayName = "New Name";
            metadata.versionIsolation = false;
            metadata.launchVertically = true;
        });

        VersionProfileMetadata fromJson = readProfileJson(metadataDir);
        assertEquals("New Name", fromJson.displayName);
        assertFalse(fromJson.versionIsolation);
        assertTrue(fromJson.launchVertically);
        assertEquals("Legacy Name", read(new File(metadataDir, "display_name.txt")));
        assertEquals("true", read(new File(metadataDir, "version_isolation.txt")));
        assertEquals("false", read(new File(metadataDir, "launch_vertically.txt")));
    }

    @Test
    public void damagedProfileJsonFallsBackToLegacyTxtAndRewritesJson() throws Exception {
        File metadataDir = temporaryFolder.newFolder("damaged-with-legacy");
        write(metadataDir, VersionProfileMetadataStore.PROFILE_FILE_NAME, "{bad json");
        write(metadataDir, "version.txt", "1.22.0");
        write(metadataDir, "display_name.txt", "Legacy Rescue");
        write(metadataDir, "version_isolation.txt", "false");
        write(metadataDir, "launch_vertically.txt", "true");

        VersionProfileMetadata metadata = store.loadOrCreate(
                metadataDir,
                VersionProfileMetadataStore.Defaults.custom("Profile", "fallback")
        );

        assertEquals("1.22.0", metadata.versionName);
        assertEquals("Legacy Rescue", metadata.displayName);
        assertFalse(metadata.versionIsolation);
        assertTrue(metadata.launchVertically);
        assertEquals("1.22.0", readProfileJson(metadataDir).versionName);
    }

    @Test
    public void damagedProfileJsonWithoutLegacyFallsBackToDefaultsAndRewritesJson() throws Exception {
        File metadataDir = temporaryFolder.newFolder("damaged-default");
        write(metadataDir, VersionProfileMetadataStore.PROFILE_FILE_NAME, "{bad json");

        VersionProfileMetadata metadata = store.loadOrCreate(
                metadataDir,
                VersionProfileMetadataStore.Defaults.custom("Profile", "1.23.0")
        );

        assertEquals("Profile", metadata.directoryName);
        assertEquals("1.23.0", metadata.versionName);
        assertTrue(metadata.versionIsolation);
        assertEquals("1.23.0", readProfileJson(metadataDir).versionName);
    }

    private VersionProfileMetadata readProfileJson(File metadataDir) throws Exception {
        return gson.fromJson(read(profileJson(metadataDir)), VersionProfileMetadata.class);
    }

    private File profileJson(File metadataDir) {
        return new File(metadataDir, VersionProfileMetadataStore.PROFILE_FILE_NAME);
    }

    private static void write(File dir, String name, String value) throws Exception {
        Files.write(new File(dir, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
    }
}
