package org.levimc.launcher.core.mods;

import android.os.FileObserver;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ModManager {
    private static final String TAG = "ModManager";
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final String PRELOAD_NATIVE_TYPE = "preload-native";
    private static final String DEFAULT_MOD_AUTHOR = "Unknown";
    private static final String DEFAULT_MOD_ICON = "";
    private static final String DEFAULT_MOD_VERSION = "1.0.0";
    private static final int COPY_BUFFER_SIZE = 8192;

    private static volatile ModManager instance;
    private File modsDir;
    private File configFile;
    private final Map<String, Boolean> enabledMap = new LinkedHashMap<>();
    private final List<String> modOrder = new ArrayList<>();
    private FileObserver modDirObserver;
    private GameVersion currentVersion;
    private final MutableLiveData<Void> modsChangedLiveData = new MutableLiveData<>();
    private final Gson gson = new Gson();
    private final Gson manifestGson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final class ModDescriptor {
        final String id;
        final String fileName;
        final String entryPath;
        final String displayName;

        ModDescriptor(String id, String fileName, String entryPath, String displayName) {
            this.id = id;
            this.fileName = fileName;
            this.entryPath = entryPath;
            this.displayName = displayName;
        }
    }

    private ModManager() {}

    public static ModManager getInstance() {
        ModManager result = instance;
        if (result == null) {
            synchronized (ModManager.class) {
                result = instance;
                if (result == null) {
                    instance = result = new ModManager();
                }
            }
        }
        return result;
    }

    public synchronized void setCurrentVersion(GameVersion version) {
        if (Objects.equals(currentVersion, version)) return;
        stopFileObserver();
        currentVersion = version;

        if (version != null && version.modsDir != null) {
            modsDir = version.modsDir;
            modsDir.mkdirs();
            configFile = new File(modsDir, "mods_config.json");
            loadConfig();
            reconcileModsState();
            initFileObserver();
        } else {
            modsDir = null;
            configFile = null;
            enabledMap.clear();
            modOrder.clear();
        }
        notifyModsChanged();
    }

    public GameVersion getCurrentVersion() {
        return currentVersion;
    }

    public synchronized List<Mod> getMods() {
        if (modsDir == null) return new ArrayList<>();

        reconcileModsState();
        List<ModDescriptor> descriptors = discoverMods();
        Map<String, ModDescriptor> descriptorMap = new LinkedHashMap<>();
        for (ModDescriptor descriptor : descriptors) {
            descriptorMap.put(descriptor.id, descriptor);
        }

        List<Mod> mods = new ArrayList<>();
        for (int i = 0; i < modOrder.size(); i++) {
            String modId = modOrder.get(i);
            ModDescriptor descriptor = descriptorMap.get(modId);
            if (descriptor == null) {
                continue;
            }
            mods.add(new Mod(
                    descriptor.id,
                    descriptor.fileName,
                    descriptor.entryPath,
                    descriptor.displayName,
                    enabledMap.getOrDefault(modId, true),
                    i
            ));
        }

        return mods;
    }

    public synchronized void setModEnabled(String modId, boolean enabled) {
        if (modsDir == null) return;
        if (enabledMap.containsKey(modId)) {
            enabledMap.put(modId, enabled);
            saveConfig();
            notifyModsChanged();
        }
    }

    private void loadConfig() {
        enabledMap.clear();
        modOrder.clear();

        if (!configFile.exists()) {
            updateConfigFromDirectory();
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> configList = gson.fromJson(reader, type);

            if (configList != null) {
                for (Map<String, Object> item : configList) {
                    String name = (String) item.get("name");
                    Boolean enabled = (Boolean) item.get("enabled");
                    if (name != null && enabled != null) {
                        enabledMap.put(name, enabled);
                        modOrder.add(name);
                    }
                }
            } else {
                updateConfigFromDirectory();
            }
        } catch (Exception e) {
            updateConfigFromDirectory();
        }
    }

    private void updateConfigFromDirectory() {
        for (ModDescriptor descriptor : discoverMods()) {
            enabledMap.put(descriptor.id, true);
            modOrder.add(descriptor.id);
        }
        saveConfig();
    }

    private void saveConfig() {
        if (configFile == null) return;
        try (FileWriter writer = new FileWriter(configFile)) {
            List<Map<String, Object>> configList = new ArrayList<>();
            for (int i = 0; i < modOrder.size(); i++) {
                String modId = modOrder.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", modId);
                item.put("enabled", enabledMap.get(modId));
                item.put("order", i);
                configList.add(item);
            }
            gson.toJson(configList, writer);
        } catch (Exception ignored) {}
    }

    private void initFileObserver() {
        if (modsDir == null) return;
        modDirObserver = new FileObserver(modsDir.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                notifyModsChanged();
            }
        };
        modDirObserver.startWatching();
    }

    private void stopFileObserver() {
        if (modDirObserver != null) {
            modDirObserver.stopWatching();
            modDirObserver = null;
        }
    }

    public synchronized void deleteMod(String modId) {
        if (modsDir == null) return;

        File modDirectory = new File(modsDir, modId);
        boolean deleted = modDirectory.isDirectory() && deleteRecursively(modDirectory);

        if (deleted) {
            enabledMap.remove(modId);
            modOrder.remove(modId);
            saveConfig();
            notifyModsChanged();
        }
    }

    public synchronized void reorderMods(List<Mod> reorderedMods) {
        if (modsDir == null) return;

        modOrder.clear();
        for (Mod mod : reorderedMods) {
            modOrder.add(mod.getId());
        }
        saveConfig();
        notifyModsChanged();
    }

    private void notifyModsChanged() {
        modsChangedLiveData.postValue(null);
    }

    public MutableLiveData<Void> getModsChangedLiveData() {
        return modsChangedLiveData;
    }

    public synchronized void refreshMods() {
        notifyModsChanged();
    }

    private void reconcileModsState() {
        boolean changed = migrateTopLevelSoMods();
        changed |= syncConfigWithDiscoveredMods(discoverMods());
        if (changed) {
            saveConfig();
        }
    }

    private List<ModDescriptor> discoverMods() {
        List<ModDescriptor> descriptors = new ArrayList<>();
        if (modsDir == null || !modsDir.exists()) {
            return descriptors;
        }

        File[] entries = modsDir.listFiles();
        if (entries == null) {
            return descriptors;
        }

        Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }

            ModDescriptor descriptor = parseDirectoryMod(entry);
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }

        return descriptors;
    }

    private boolean migrateTopLevelSoMods() {
        if (modsDir == null || !modsDir.exists()) {
            return false;
        }

        File[] looseLibraries = modsDir.listFiles(file ->
                file.isFile() && file.getName().toLowerCase().endsWith(".so"));
        if (looseLibraries == null || looseLibraries.length == 0) {
            return false;
        }

        boolean changed = false;
        Arrays.sort(looseLibraries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File looseLibrary : looseLibraries) {
            try {
                changed |= migrateLooseSoMod(looseLibrary);
            } catch (Exception e) {
                Log.e(TAG, "Failed to migrate loose mod library: " + looseLibrary.getAbsolutePath(), e);
            }
        }
        return changed;
    }

    private boolean migrateLooseSoMod(File looseLibrary) throws IOException {
        String fileName = looseLibrary.getName();
        String displayName = deriveDisplayNameFromLibrary(fileName);
        String targetId = buildTargetId(displayName, stripExtension(fileName));
        File targetDirectory = allocateModDirectory(targetId);
        File migratedLibrary = new File(targetDirectory, fileName);

        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IOException("Failed to create mod directory: " + targetDirectory.getAbsolutePath());
        }

        moveFile(looseLibrary, migratedLibrary);
        writeManifest(targetDirectory, displayName, fileName);
        migrateConfigId(fileName, targetDirectory.getName());
        Log.i(TAG, "Migrated loose mod " + fileName + " -> " + targetDirectory.getAbsolutePath());
        return true;
    }

    private void migrateConfigId(String oldId, String newId) {
        if (oldId.equals(newId)) {
            return;
        }
        if (!enabledMap.containsKey(oldId) || enabledMap.containsKey(newId)) {
            return;
        }

        Boolean enabled = enabledMap.remove(oldId);
        if (enabled == null) {
            return;
        }
        enabledMap.put(newId, enabled);

        int orderIndex = modOrder.indexOf(oldId);
        if (orderIndex >= 0) {
            modOrder.set(orderIndex, newId);
        }
    }

    private File allocateModDirectory(String preferredId) {
        File targetDirectory = new File(modsDir, preferredId);
        if (!targetDirectory.exists()) {
            return targetDirectory;
        }

        int index = 1;
        while (true) {
            File candidate = new File(modsDir, preferredId + "_" + index);
            if (!candidate.exists()) {
                return candidate;
            }
            index++;
        }
    }

    private void moveFile(File source, File target) throws IOException {
        if (source.renameTo(target)) {
            return;
        }

        copyFile(source, target);
        if (!source.delete()) {
            throw new IOException("Failed to delete original file after migration: " + source.getAbsolutePath());
        }
    }

    private void writeManifest(File modDirectory, String displayName, String entryFileName) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("type", PRELOAD_NATIVE_TYPE);
        manifest.addProperty("name", displayName);
        manifest.addProperty("entry", entryFileName);
        manifest.addProperty("author", DEFAULT_MOD_AUTHOR);
        manifest.addProperty("icon", DEFAULT_MOD_ICON);
        manifest.addProperty("version", DEFAULT_MOD_VERSION);

        File manifestFile = new File(modDirectory, MANIFEST_FILE_NAME);
        try (FileWriter writer = new FileWriter(manifestFile)) {
            manifestGson.toJson(manifest, writer);
        }
    }

    private void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private ModDescriptor parseDirectoryMod(File modDirectory) {
        File manifestFile = new File(modDirectory, MANIFEST_FILE_NAME);
        if (!manifestFile.isFile()) {
            return null;
        }

        try (FileReader reader = new FileReader(manifestFile)) {
            JsonObject manifest = gson.fromJson(reader, JsonObject.class);
            if (manifest == null || !manifest.has("type") || !manifest.get("type").isJsonPrimitive()) {
                return null;
            }

            String type = manifest.get("type").getAsString();
            if (!PRELOAD_NATIVE_TYPE.equals(type)) {
                return null;
            }

            String entryPath = manifest.has("entry") && manifest.get("entry").isJsonPrimitive()
                    ? sanitizeEntryPath(manifest.get("entry").getAsString())
                    : null;
            if (entryPath == null) {
                Log.w(TAG, "Ignoring mod with invalid entry path: " + modDirectory.getAbsolutePath());
                return null;
            }

            File entryFile = new File(modDirectory, entryPath);
            if (!entryFile.isFile() || !entryFile.getName().endsWith(".so")) {
                Log.w(TAG, "Ignoring mod with missing entry file: " + entryFile.getAbsolutePath());
                return null;
            }

            String displayName = manifest.has("name") && manifest.get("name").isJsonPrimitive()
                    ? manifest.get("name").getAsString()
                    : modDirectory.getName();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = modDirectory.getName();
            }

            return new ModDescriptor(
                    modDirectory.getName(),
                    entryFile.getName(),
                    entryPath,
                    displayName
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse mod manifest: " + manifestFile.getAbsolutePath(), e);
            return null;
        }
    }

    private boolean syncConfigWithDiscoveredMods(List<ModDescriptor> descriptors) {
        Map<String, ModDescriptor> discoveredMap = new LinkedHashMap<>();
        for (ModDescriptor descriptor : descriptors) {
            discoveredMap.put(descriptor.id, descriptor);
        }

        boolean changed = false;
        for (ModDescriptor descriptor : descriptors) {
            if (enabledMap.containsKey(descriptor.id)) {
                continue;
            }

            enabledMap.put(descriptor.id, true);
            modOrder.add(descriptor.id);
            changed = true;
        }

        List<String> staleKeys = new ArrayList<>();
        for (String configKey : enabledMap.keySet()) {
            if (!discoveredMap.containsKey(configKey)) {
                staleKeys.add(configKey);
            }
        }
        for (String staleKey : staleKeys) {
            enabledMap.remove(staleKey);
            changed = true;
        }

        List<String> normalizedOrder = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String modId : modOrder) {
            if (discoveredMap.containsKey(modId) && seen.add(modId)) {
                normalizedOrder.add(modId);
            } else {
                changed = true;
            }
        }
        for (ModDescriptor descriptor : descriptors) {
            if (seen.add(descriptor.id)) {
                normalizedOrder.add(descriptor.id);
                changed = true;
            }
        }

        if (!normalizedOrder.equals(modOrder)) {
            modOrder.clear();
            modOrder.addAll(normalizedOrder);
            changed = true;
        }

        return changed;
    }

    private boolean deleteRecursively(File file) {
        if (!file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private String sanitizeEntryPath(String entryPath) {
        if (entryPath == null) {
            return null;
        }

        String normalized = entryPath.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }

        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }

        return normalized;
    }

    private String deriveDisplayNameFromLibrary(String fileName) {
        String baseName = stripExtension(fileName);
        if (baseName.startsWith("lib") && baseName.length() > 3) {
            baseName = baseName.substring(3);
        }
        return baseName.isEmpty() ? "mod" : baseName;
    }

    private String buildTargetId(String preferredName, String fallbackName) {
        String candidate = sanitizeDirectoryName(preferredName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        candidate = sanitizeDirectoryName(fallbackName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        return "mod_" + System.currentTimeMillis();
    }

    private String sanitizeDirectoryName(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = value.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_')
                .replace('*', '_')
                .replace('?', '_')
                .replace('"', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace('|', '_');

        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized.trim();
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }
}
