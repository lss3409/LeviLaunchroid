package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.levimc.launcher.core.minecraft.MinecraftActivity;
import org.levimc.launcher.core.minecraft.MinecraftActivityState;
import org.levimc.launcher.core.minecraft.MinecraftImportIntents;
import org.levimc.launcher.util.InstanceBackupManager;
import org.levimc.launcher.util.MinecraftUriHandler;

public class IntentHandler extends BaseActivity {
    private static final String TAG = "IntentHandler";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent originalIntent) {
        Uri data = originalIntent.getData();

        if (data != null && MinecraftUriHandler.isMinecraftUri(data)) {
            handleMinecraftUri(originalIntent, data);
            return;
        }

        if (MinecraftImportIntents.isMinecraftResourceIntent(this, originalIntent)) {
            if (MinecraftImportIntents.forwardToRunningMinecraft(this, originalIntent)) {
                finish();
                return;
            }

            Intent launcherIntent = new Intent(originalIntent);
            launcherIntent.setClass(this, MainActivity.class);
            startActivity(launcherIntent);
            finish();
            return;
        }

        if (InstanceBackupManager.isBackupIntent(this, originalIntent)) {
            Intent launcherIntent = new Intent(originalIntent);
            launcherIntent.setClass(this, InstancesActivity.class);
            launcherIntent.putExtra(InstancesActivity.EXTRA_RESTORE_BACKUP_ON_OPEN, true);
            launcherIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(launcherIntent);
            finish();
            return;
        }
        
        Intent newIntent = new Intent(originalIntent);
        if (isMinecraftActivityRunning()) {
            newIntent.setClass(this, MinecraftActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {

            if (isMcRunning()) {
                newIntent.setClassName(this, "com.mojang.minecraftpe.Launcher");
            } else {
                newIntent.setClassName(this, "org.levimc.launcher.ui.activities.MainActivity");
            }
        }

        startActivity(newIntent);
        finish();
    }

    private void handleMinecraftUri(Intent originalIntent, Uri uri) {
        Log.d(TAG, "Handling Minecraft URI: " + uri.toString());
        
        MinecraftUriHandler.MinecraftUri parsedUri = MinecraftUriHandler.parse(uri);
        if (parsedUri != null) {
            Log.d(TAG, "Parsed Minecraft URI action: " + parsedUri.action);
        }
        
        Intent newIntent = new Intent(originalIntent);
        newIntent.putExtra("MINECRAFT_URI", uri.toString());
        newIntent.putExtra("MINECRAFT_URI_ACTION", parsedUri != null ? parsedUri.action : "");
        
        if (isMinecraftActivityRunning()) {
            newIntent.setClass(this, MinecraftActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(newIntent);
        } else {
            newIntent.setClass(this, MainActivity.class);
            newIntent.putExtra("LAUNCH_WITH_URI", true);
            startActivity(newIntent);
        }
        
        finish();
    }

    private boolean isMinecraftActivityRunning() {
        return MinecraftActivityState.isRunning(this);
    }

    private boolean isMcRunning() {
        try {
            Class<?> clazz = Class.forName("com.mojang.minecraftpe.Launcher", false, getClassLoader());
            Log.d(TAG, "Minecraft PE Launcher class exists!");
            return true;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "Minecraft PE Launcher class not found.");
            return false;
        }
    }
}
