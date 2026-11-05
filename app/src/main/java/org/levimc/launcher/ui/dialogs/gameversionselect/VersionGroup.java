package org.levimc.launcher.ui.dialogs.gameversionselect;

import org.levimc.launcher.core.versions.GameVersion;

import java.util.ArrayList;
import java.util.List;

public class VersionGroup {
    public String versionCode;
    public List<GameVersion> versions = new ArrayList<>();

    public VersionGroup(String code) {
        this.versionCode = code;
    }
}