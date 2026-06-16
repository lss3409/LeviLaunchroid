package org.levimc.launcher.core.mods.config;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class ModConfigManager {
    private static final String TAG = "ModConfigManager";
    public static final String CONFIG_DIR_NAME = "config";
    public static final String DEFAULT_CONFIG_FILE = "config.json";
    public static final String DEFAULT_SCHEMA_FILE = "config.schema.json";
    private static final int COPY_BUFFER_SIZE = 8192;

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public List<ConfigFile> scanConfigFiles(File modRoot) {
        List<ConfigFile> files = new ArrayList<>();
        if (modRoot == null || !modRoot.isDirectory()) {
            return files;
        }

        File configDir = new File(modRoot, CONFIG_DIR_NAME);
        if (!configDir.isDirectory() || !isInside(modRoot, configDir)) {
            return files;
        }

        File[] jsonFiles = configDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase().endsWith(".json")
                && !DEFAULT_SCHEMA_FILE.equalsIgnoreCase(file.getName()));
        if (jsonFiles == null || jsonFiles.length == 0) {
            return files;
        }

        Arrays.sort(jsonFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File jsonFile : jsonFiles) {
            if (!isInside(configDir, jsonFile)) {
                continue;
            }
            File schemaFile = schemaFor(configDir, jsonFile);
            files.add(new ConfigFile(jsonFile, schemaFile != null && schemaFile.isFile() ? schemaFile : null));
        }
        return files;
    }

    public boolean hasEditableConfig(File modRoot) {
        return !scanConfigFiles(modRoot).isEmpty();
    }

    public LoadedConfig load(ConfigFile configFile) throws IOException {
        JsonElement value = readJson(configFile.getFile());
        ModConfigSchema schema = null;
        File schemaFile = configFile.getSchemaFile();
        if (schemaFile != null && schemaFile.isFile()) {
            try {
                schema = ModConfigSchema.fromJson(readJson(schemaFile));
            } catch (Exception e) {
                Log.w(TAG, "Failed to read config schema: " + schemaFile.getAbsolutePath(), e);
            }
        }
        return new LoadedConfig(configFile, value, schema);
    }

    public void save(ConfigFile configFile, JsonElement value) throws IOException {
        File file = configFile.getFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create config directory: " + parent.getAbsolutePath());
        }

        if (file.exists()) {
            copyFile(file, new File(file.getAbsolutePath() + ".backup"));
        }

        File tempFile = new File(file.getAbsolutePath() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
            gson.toJson(value, writer);
        }

        if (file.exists() && !file.delete()) {
            tempFile.delete();
            throw new IOException("Failed to replace config file: " + file.getAbsolutePath());
        }
        if (!tempFile.renameTo(file)) {
            tempFile.delete();
            throw new IOException("Failed to move temporary config file: " + tempFile.getAbsolutePath());
        }
    }

    public static boolean isSupportedConfigFile(File file) {
        return file != null
                && file.isFile()
                && file.getName().toLowerCase().endsWith(".json")
                && !DEFAULT_SCHEMA_FILE.equalsIgnoreCase(file.getName());
    }

    private JsonElement readJson(File file) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            IOException ioException = new IOException("Invalid JSON: " + file.getAbsolutePath());
            ioException.initCause(e);
            throw ioException;
        }
    }

    private File schemaFor(File configDir, File jsonFile) {
        if (DEFAULT_CONFIG_FILE.equalsIgnoreCase(jsonFile.getName())) {
            return new File(configDir, DEFAULT_SCHEMA_FILE);
        }

        String name = jsonFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File specific = new File(configDir, base + ".schema.json");
        if (specific.isFile()) {
            return specific;
        }
        return null;
    }

    private boolean isInside(File root, File child) {
        try {
            String rootPath = root.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private void copyFile(File source, File target) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    public static class ConfigFile {
        private final File file;
        private final File schemaFile;

        public ConfigFile(File file, File schemaFile) {
            this.file = file;
            this.schemaFile = schemaFile;
        }

        public File getFile() {
            return file;
        }

        public File getSchemaFile() {
            return schemaFile;
        }

        public String getDisplayName() {
            return file.getName();
        }
    }

    public static class LoadedConfig {
        private final ConfigFile configFile;
        private final JsonElement value;
        private final ModConfigSchema schema;

        public LoadedConfig(ConfigFile configFile, JsonElement value, ModConfigSchema schema) {
            this.configFile = configFile;
            this.value = value == null ? new JsonObject() : value;
            this.schema = schema;
        }

        public ConfigFile getConfigFile() {
            return configFile;
        }

        public JsonElement getValue() {
            return value;
        }

        public ModConfigSchema getSchema() {
            return schema;
        }
    }
}
