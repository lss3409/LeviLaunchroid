package org.levimc.launcher.core.mods;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Mod {
    private final String id;
    private final String fileName;
    private final String entryPath;
    private final String displayName;
    private final List<String> minecraftVersions;
    private final String author;
    private final String version;
    private final String iconPath;
    private final String manifestPath;
    private final String description;
    private final String modRootPath;
    private final String configDirPath;
    private final boolean hasEditableConfig;
    private final int configFileCount;
    private boolean enabled;
    private int order;

    public Mod(String id, String fileName, String entryPath, String displayName, boolean enabled, int order) {
        this(id, fileName, entryPath, displayName, Collections.emptyList(), enabled, order);
    }

    public Mod(String id, String fileName, String entryPath, String displayName, List<String> minecraftVersions, boolean enabled, int order) {
        this(id, fileName, entryPath, displayName, minecraftVersions, null, null, false, 0, enabled, order);
    }

    public Mod(String id, String fileName, String entryPath, String displayName, List<String> minecraftVersions,
               String modRootPath, String configDirPath, boolean hasEditableConfig, int configFileCount,
               boolean enabled, int order) {
        this(id, fileName, entryPath, displayName, minecraftVersions, null, null, null, null, null,
                modRootPath, configDirPath, hasEditableConfig, configFileCount, enabled, order);
    }

    public Mod(String id, String fileName, String entryPath, String displayName, List<String> minecraftVersions,
               String author, String version, String iconPath, String manifestPath, String description,
               String modRootPath, String configDirPath, boolean hasEditableConfig, int configFileCount,
               boolean enabled, int order) {
        this.id = id;
        this.fileName = fileName;
        this.entryPath = entryPath;
        this.displayName = displayName;
        this.minecraftVersions = minecraftVersions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(minecraftVersions));
        this.author = author;
        this.version = version;
        this.iconPath = iconPath;
        this.manifestPath = manifestPath;
        this.description = description;
        this.modRootPath = modRootPath;
        this.configDirPath = configDirPath;
        this.hasEditableConfig = hasEditableConfig;
        this.configFileCount = configFileCount;
        this.enabled = enabled;
        this.order = order;
    }

    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getEntryPath() {
        return entryPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getMinecraftVersions() {
        return minecraftVersions;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public String getIconPath() {
        return iconPath;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public String getDescription() {
        return description;
    }

    public String getModRootPath() {
        return modRootPath;
    }

    public String getConfigDirPath() {
        return configDirPath;
    }

    public boolean hasEditableConfig() {
        return hasEditableConfig;
    }

    public int getConfigFileCount() {
        return configFileCount;
    }
}
