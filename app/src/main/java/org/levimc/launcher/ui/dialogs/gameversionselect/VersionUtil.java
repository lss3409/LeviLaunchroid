package org.levimc.launcher.ui.dialogs.gameversionselect;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VersionUtil {

    public static class GroupByResult {
        public LinkedHashMap<String, VersionGroup> validGroups = new LinkedHashMap<>();
        public LinkedHashMap<String, VersionGroup> errorGroups = new LinkedHashMap<>();
    }

    public static List<BigGroup> buildBigGroups(List<GameVersion> installed, List<GameVersion> custom) {
        List<BigGroup> bigGroups = new ArrayList<>();

        if (!installed.isEmpty()) {
            GroupByResult groupResult = groupByVersion(installed);
            if (!groupResult.validGroups.isEmpty()) {
                BigGroup bg = new BigGroup(R.string.installed_packages);
                bg.versionGroups.addAll(groupResult.validGroups.values());
                bigGroups.add(bg);
            }
            if (!groupResult.errorGroups.isEmpty()) {
                BigGroup bg = new BigGroup(R.string.error_versions);
                bg.versionGroups.addAll(groupResult.errorGroups.values());
                bigGroups.add(bg);
            }
        }
        if (!custom.isEmpty()) {
            GroupByResult groupResult = groupByVersion(custom);
            if (!groupResult.validGroups.isEmpty()) {
                BigGroup bg = new BigGroup(R.string.local_custom);
                bg.versionGroups.addAll(groupResult.validGroups.values());
                bigGroups.add(bg);
            }
            if (!groupResult.errorGroups.isEmpty()) {
                BigGroup bg = new BigGroup(R.string.error_versions);
                bg.versionGroups.addAll(groupResult.errorGroups.values());
                bigGroups.add(bg);
            }
        }
        return bigGroups;
    }

    public static GroupByResult groupByVersion(List<GameVersion> list) {
        Map<String, VersionGroup> validMap = new HashMap<>();
        Map<String, VersionGroup> errorMap = new HashMap<>();

        for (GameVersion gv : list) {
            String code = gv.versionCode;
            boolean valid = isValidVersion(code);
            Map<String, VersionGroup> map = valid ? validMap : errorMap;
            VersionGroup vg = map.get(code);
            if (vg == null) {
                vg = new VersionGroup(code);
                map.put(code, vg);
            }
            vg.versions.add(gv);
        }

        List<String> validKeys = new ArrayList<>(validMap.keySet());
        List<String> errorKeys = new ArrayList<>(errorMap.keySet());

        validKeys.sort((a, b) -> compareVersionCode(b, a));
        errorKeys.sort(String::compareTo);

        GroupByResult result = new GroupByResult();
        for (String key : validKeys)
            result.validGroups.put(key, validMap.get(key));
        for (String key : errorKeys)
            result.errorGroups.put(key, errorMap.get(key));

        return result;
    }

    public static int compareVersionCode(String v1, String v2) {
        if (!isValidVersion(v1) && !isValidVersion(v2)) {
            return v1.compareTo(v2);
        }
        if (!isValidVersion(v1)) {
            return 1;
        }
        if (!isValidVersion(v2)) {
            return -1;
        }
        String[] arr1 = v1.split("\\.");
        String[] arr2 = v2.split("\\.");
        int len = Math.max(arr1.length, arr2.length);
        for (int i = 0; i < len; i++) {
            int n1 = (i < arr1.length) ? Integer.parseInt(arr1[i]) : 0;
            int n2 = (i < arr2.length) ? Integer.parseInt(arr2[i]) : 0;
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }
    
    public static boolean isValidVersion(String v) {
        String[] arr = v.split("\\.");
        for (String s : arr) {
            if (!s.matches("\\d+")) {
                return false;
            }
        }
        return true;
    }
}