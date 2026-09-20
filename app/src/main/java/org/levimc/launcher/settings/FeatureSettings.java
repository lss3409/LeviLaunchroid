package org.levimc.launcher.settings;

import android.content.Context;

public class FeatureSettings {
    private static volatile FeatureSettings INSTANCE;
    private static Context appContext;
    private boolean versionIsolationEnabled = false;
    private boolean launcherManagedMcLoginEnabled = true;
    private boolean msLoginEnabled = false;
    private boolean logcatOverlayEnabled = false;
    /** v450：前台服务恒开（设置页开关已隐藏）。 */
    private boolean foregroundServiceEnabled = true;
    private boolean autoCloseGameOnLaunchNew = false;
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

    /** v450：前台服务恒开（老用户 prefs 里可能是 false——直接返回
     *  true 覆盖，设置页开关已隐藏）。 */
    public boolean isForegroundServiceEnabled() { return true; }
    public void setForegroundServiceEnabled(boolean enabled) { this.foregroundServiceEnabled = enabled; autoSave(); }

    public boolean isAutoCloseGameOnLaunchNew() { return autoCloseGameOnLaunchNew; }
    public void setAutoCloseGameOnLaunchNew(boolean enabled) { this.autoCloseGameOnLaunchNew = enabled; autoSave(); }

    public boolean isCrashUploadEnabled() { return crashUploadEnabled == null || crashUploadEnabled; }
    public void setCrashUploadEnabled(boolean enabled) { this.crashUploadEnabled = enabled; autoSave(); }


    private void autoSave() {
        if (appContext != null) {
            SettingsStorage.save(appContext, this);
        }
    }
}
