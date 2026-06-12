package org.levimc.launcher.core.minecraft;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

public class MinecraftLauncher {
    private static final String TAG = "MinecraftLauncher";
    private final Context context;
    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";
    public static final String EXTRA_GAME_VERSION = "org.levimc.launcher.extra.GAME_VERSION";

    public interface LaunchCallback {
        void onLaunchStarted();
        void onLaunchFailed(Exception e);
    }

    public MinecraftLauncher(Context context) {
        this.context = context;
    }

    public static String abiToSystemLibDir(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        return abi;
    }

    public ApplicationInfo createFakeApplicationInfo(GameVersion version, String packageName) {
        ApplicationInfo fakeInfo = new ApplicationInfo();
        File apkFile = new File(version.versionDir, "base.apk.levi");
        fakeInfo.sourceDir = apkFile.getAbsolutePath();
        fakeInfo.publicSourceDir = fakeInfo.sourceDir;
        String systemAbi = abiToSystemLibDir(Build.SUPPORTED_ABIS[0]);
        File dstLibDir = new File(context.getDataDir(), "minecraft/" + version.directoryName + "/lib/" + systemAbi);
        fakeInfo.nativeLibraryDir = dstLibDir.getAbsolutePath();
        fakeInfo.packageName = packageName;
        fakeInfo.dataDir = version.versionDir.getAbsolutePath();

        File splitsFolder = new File(version.versionDir, "splits");
        if (splitsFolder.exists() && splitsFolder.isDirectory()) {
            File[] splits = splitsFolder.listFiles();
            if (splits != null) {
                ArrayList<String> splitPathList = new ArrayList<>();
                for (File f : splits) {
                    if (f.isFile() && f.getName().endsWith(".apk.levi")) {
                        splitPathList.add(f.getAbsolutePath());
                    }
                }
                if (!splitPathList.isEmpty()) {
                    fakeInfo.splitSourceDirs = splitPathList.toArray(new String[0]);
                }
            }
        }
        return fakeInfo;
    }

    public void launch(Intent sourceIntent, GameVersion version) {
        launch(sourceIntent, version, null);
    }

    public void launch(Intent sourceIntent, GameVersion version, LaunchCallback callback) {
        Activity activity = (Activity) context;

        try {
            if (version == null) {
                Log.e(TAG, "No version selected");
                showLaunchErrorOnUi("No version selected");
                notifyLaunchFailed(callback, new IllegalArgumentException("No version selected"));
                return;
            }

            if (version.versionCode != null) {
                try {
                    String[] parts = version.versionCode.split("\\.");
                    if (parts.length >= 2) {
                        int major = Integer.parseInt(parts[0].replaceAll("\\D", ""));
                        int minor = Integer.parseInt(parts[1].replaceAll("\\D", ""));
                        int patch = 0;
                        if (parts.length > 2) {
                            String patchStr = parts[2].replaceAll("\\D.*", "");
                            if (!patchStr.isEmpty()) {
                                patch = Integer.parseInt(patchStr);
                            }
                        }
                        if (major < 1 || (major == 1 && minor < 21) || (major == 1 && minor == 21 && patch < 80)) {
                            activity.runOnUiThread(() -> {
                                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(activity)
                                        .setTitleText(activity.getString(org.levimc.launcher.R.string.unsupported_version_title))
                                        .setMessage(activity.getString(org.levimc.launcher.R.string.unsupported_version_msg))
                                        .setPositiveButton(activity.getString(org.levimc.launcher.R.string.back), null)
                                        .show();
                            });
                            notifyLaunchFailed(callback, new IllegalStateException("Unsupported version"));
                            return;
                        }
                    }
                } catch (Exception e) {
                    // Ignore parsing errors and let it launch
                }
            }

            if (version.needsRepair) {
                activity.runOnUiThread(() ->
                        org.levimc.launcher.core.versions.VersionManager.attemptRepairLibs(activity, version)
                );
                notifyLaunchFailed(callback, new IllegalStateException("Selected version needs repair"));
                return;
            }

            activity.runOnUiThread(() -> {
                try {
                    launchMinecraftActivity(sourceIntent, version, false);
                    notifyLaunchStarted(callback);
                } catch (Exception e) {
                    Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                    showLaunchErrorOnUi("Launch failed: " + e.getMessage());
                    notifyLaunchFailed(callback, e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Launch failed: " + e.getMessage(), e);
            showLaunchErrorOnUi("Launch failed: " + e.getMessage());
            notifyLaunchFailed(callback, e);
        }
    }

    private void fillIntentWithMcPath(Intent sourceIntent, GameVersion version) {
        if (!version.isInstalled || version.versionIsolation) {
            sourceIntent.putExtra("MC_PATH", version.versionDir.getAbsolutePath());
            sourceIntent.putExtra("IS_INSTALLED", version.isInstalled);
        } else {
            sourceIntent.putExtra("MC_PATH", "");
            sourceIntent.putExtra("IS_INSTALLED", version.isInstalled);
        }
    }

    private void launchMinecraftActivity(Intent sourceIntent, GameVersion version, boolean modsEnabled) {
        Activity activity = (Activity) context;

        Intent launchIntent = sourceIntent == null ? new Intent() : new Intent(sourceIntent);
        LaunchTrace trace = LaunchTrace.ensure(launchIntent);
        trace.mark("Building MinecraftLoadingActivity intent");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            launchIntent.putExtra("DISABLE_SPLASH_SCREEN", true);
        }
        fillIntentWithMcPath(launchIntent, version);
        launchIntent.setClass(context, MinecraftLoadingActivity.class);
        launchIntent.putExtra(EXTRA_GAME_VERSION, version);
        launchIntent.putExtra("MODS_ENABLED", modsEnabled);
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode);
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName);
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically);
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation);
        launchIntent.removeExtra("LAUNCH_WITH_URI");

        activity.getSharedPreferences("LauncherPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("game_verified", true)
                .apply();

        activity.startActivity(launchIntent);
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        trace.mark("MinecraftLoadingActivity startActivity called");
    }

    private void showLaunchErrorOnUi(String message) {
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> Toast.makeText(
                activity, "Failed to launch Minecraft: " + message, Toast.LENGTH_LONG).show()
        );
    }

    private void notifyLaunchStarted(LaunchCallback callback) {
        if (callback != null) {
            callback.onLaunchStarted();
        }
    }

    private void notifyLaunchFailed(LaunchCallback callback, Exception e) {
        if (callback != null) {
            callback.onLaunchFailed(e);
        }
    }
}
