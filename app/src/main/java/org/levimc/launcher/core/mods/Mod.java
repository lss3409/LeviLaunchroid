package org.levimc.launcher.core.mods;

public class Mod {
    private final String id;
    private final String fileName;
    private final String entryPath;
    private final String displayName;
    private boolean enabled;
    private int order;

    public Mod(String id, String fileName, String entryPath, String displayName, boolean enabled, int order) {
        this.id = id;
        this.fileName = fileName;
        this.entryPath = entryPath;
        this.displayName = displayName;
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
}
