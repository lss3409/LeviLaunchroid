package org.levimc.launcher.settings;

import android.content.Context;

public class FeatureSettings {
    private static volatile FeatureSettings INSTANCE;
    private static Context appContext;
    private boolean versionIsolationEnabled = false;
    private boolean launcherManagedMcLoginEnabled = true;
    /** v0.0.11：登录入口不再受开关控制（设置页开关已随 tab 改版移除，
     *  BaseActivity.refreshNavAccountUI 不再读取此字段）；字段保留兼容旧存档。 */
    private boolean msLoginEnabled = true;
    private boolean logcatOverlayEnabled = false;
    /** v704：前台服务默认开启（用户实测 v490 不杀后台，联机掉线根因）；设置页开关可关。 */
    private boolean foregroundServiceEnabled = true;
    private Boolean crashUploadEnabled = true;

    public enum StorageType {
        INTERNAL,
        EXTERNAL,
        VERSION_ISOLATION,
        VERSION_ISOLATION_INTERNAL,
        VERSION_ISOLATION_EXTERNAL
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        // 首次启动时把默认设置（如 launcherManagedMcLoginEnabled=true）持久化到 SharedPreferences，
        // 确保游戏进程的 native XAL 库（com.microsoft.xal.androidjava.Storage / Ecdsa）能读到 takeover 开关
        SettingsStorage.save(appContext, getInstance());
    }

    public static FeatureSettings getInstance() {
        if (INSTANCE == null) {
            synchronized (FeatureSettings.class) {
                if (INSTANCE == null) {
                    INSTANCE = SettingsStorage.load(appContext);
                    if (INSTANCE == null) {
                        INSTANCE = new FeatureSettings();
                    }
                }
            }
        }
        return INSTANCE;
    }

    public boolean isVersionIsolationEnabled() { return versionIsolationEnabled; }
    public void setVersionIsolationEnabled(boolean enabled) { this.versionIsolationEnabled = enabled; autoSave(); }

    public boolean isLauncherManagedMcLoginEnabled() { return launcherManagedMcLoginEnabled; }
    public void setLauncherManagedMcLoginEnabled(boolean enabled) { this.launcherManagedMcLoginEnabled = enabled; autoSave(); }

    public boolean isMsLoginEnabled() { return msLoginEnabled; }
    public void setMsLoginEnabled(boolean enabled) { this.msLoginEnabled = enabled; autoSave(); }

    public boolean isLogcatOverlayEnabled() { return logcatOverlayEnabled; }
    public void setLogcatOverlayEnabled(boolean enabled) { this.logcatOverlayEnabled = enabled; autoSave(); }

    /** v587：保活回退官方——默认关，设置页「前台服务」开关可开（官方行为）。 */
    public boolean isForegroundServiceEnabled() { return foregroundServiceEnabled; }
    public void setForegroundServiceEnabled(boolean enabled) { this.foregroundServiceEnabled = enabled; autoSave(); }

    public boolean isCrashUploadEnabled() { return crashUploadEnabled == null || crashUploadEnabled; }
    public void setCrashUploadEnabled(boolean enabled) { this.crashUploadEnabled = enabled; autoSave(); }


    private void autoSave() {
        if (appContext != null) {
            SettingsStorage.save(appContext, this);
        }
    }
}
