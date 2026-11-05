package org.levimc.launcher.ui.dialogs.gameversionselect;

import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

public class BigGroup {
    public int groupTitleResId;
    public List<VersionGroup> versionGroups = new ArrayList<>();

    public BigGroup(@StringRes int titleResId) {
        this.groupTitleResId = titleResId;
    }
}