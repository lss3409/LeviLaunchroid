package org.levimc.launcher.core.mods;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class ModNativeLoader {
    private static final String TAG = "ModNativeLoader";

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public static void loadEnabledSoMods(ModManager modManager, File cacheDir) {
        if (modManager.getCurrentVersion() == null || modManager.getCurrentVersion().modsDir == null) {
            Log.w(TAG, "No selected version mods directory available");
            return;
        }

        List<Mod> mods = modManager.getMods();
        File cacheModsDir = new File(cacheDir, "mods");
        if (!cacheModsDir.exists() && !cacheModsDir.mkdirs()) {
            Log.e(TAG, "Failed to create cache mod directory: " + cacheModsDir.getAbsolutePath());
            return;
        }

        for (Mod mod : mods) {
            if (!mod.isEnabled()) {
                continue;
            }

            try {
                File targetFile = prepareCachedEntry(modManager, cacheModsDir, mod);
                if (targetFile == null || !targetFile.isFile()) {
                    Log.e(TAG, "Entry not found after copy: " + (targetFile == null ? "<null>" : targetFile.getAbsolutePath()));
                    continue;
                }

                ensureReadOnly(targetFile);
                System.load(targetFile.getAbsolutePath());
                Log.i(TAG, "Loaded so: " + targetFile.getName());
            } catch (IOException | UnsatisfiedLinkError e) {
                Log.e(TAG, "Can't load " + mod.getDisplayName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static File prepareCachedEntry(ModManager modManager, File cacheModsDir, Mod mod) throws IOException {
        File sourceDirectory = new File(modManager.getCurrentVersion().modsDir, mod.getId());
        if (!sourceDirectory.isDirectory()) {
            throw new IOException("Mod package directory does not exist: " + sourceDirectory.getAbsolutePath());
        }

        File targetDirectory = new File(cacheModsDir, mod.getId());
        if (targetDirectory.exists() && !deleteRecursively(targetDirectory)) {
            throw new IOException("Failed to clear cached mod directory: " + targetDirectory.getAbsolutePath());
        }
        copyDirectory(sourceDirectory, targetDirectory);
        File targetFile = new File(targetDirectory, mod.getEntryPath());
        ensureReadOnly(targetFile);
        return targetFile;
    }

    private static void copyFile(File src, File dst) throws IOException {
        ensureParentDirectory(dst);
        if (dst.exists() && !dst.delete()) {
            throw new IOException("Failed to replace existing file: " + dst.getAbsolutePath());
        }

        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            markReadOnlyBeforeWrite(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.getFD().sync();
        }

        ensureReadOnly(dst);
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("Failed to create directory: " + dst.getAbsolutePath());
            }

            File[] children = src.listFiles();
            if (children == null) {
                return;
            }

            for (File child : children) {
                copyDirectory(child, new File(dst, child.getName()));
            }
            return;
        }

        copyFile(src, dst);
    }

    private static void ensureParentDirectory(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }
    }

    private static void markReadOnlyBeforeWrite(File file) throws IOException {
        if (!file.setReadOnly() && file.canWrite()) {
            throw new IOException("Failed to mark file read-only before write: " + file.getAbsolutePath());
        }
    }

    private static void ensureReadOnly(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("Expected regular file: " + file.getAbsolutePath());
        }

        if (!file.setReadable(true, true) && !file.canRead()) {
            throw new IOException("Failed to mark file readable: " + file.getAbsolutePath());
        }

        if (!file.setReadOnly() && file.canWrite()) {
            throw new IOException("Failed to keep file read-only: " + file.getAbsolutePath());
        }
    }

    private static boolean deleteRecursively(File file) {
        if (!file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
}
