package org.levimc.launcher.core.mods;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.views.MainViewModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileHandler {
    private static final String TAG = "FileHandler";
    private static final int BUFFER_SIZE = 8192;
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final String PRELOAD_NATIVE_TYPE = "preload-native";
    private static final String DEFAULT_MOD_AUTHOR = "Unknown";
    private static final String DEFAULT_MOD_ICON = "";
    private static final String DEFAULT_MOD_VERSION = "1.0.0";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Context context;
    private final MainViewModel modManager;
    private String targetPath;

    private static final class PreparedImport {
        final String targetId;
        final File packageDir;
        final File cleanupRoot;

        PreparedImport(String targetId, File packageDir, File cleanupRoot) {
            this.targetId = targetId;
            this.packageDir = packageDir;
            this.cleanupRoot = cleanupRoot;
        }
    }

    public interface FileOperationCallback {
        void onSuccess(int processedFiles);

        void onError(String errorMessage);

        void onProgressUpdate(int progress);
    }

    public FileHandler(Context context, MainViewModel modManager, VersionManager version) {
        this.context = context;
        this.modManager = modManager;

        GameVersion currentVersion = version.getSelectedVersion();
        if (currentVersion != null && currentVersion.modsDir != null) {
            this.targetPath = currentVersion.modsDir.getAbsolutePath();
        } else {
            this.targetPath = null;
        }
    }

    public void processIncomingFilesWithConfirmation(Intent intent, FileOperationCallback callback, boolean isButtonClick) {
        List<Uri> fileUris = extractFileUris(intent);
        List<Uri> supportedUris = new ArrayList<>();
        for (Uri uri : fileUris) {
            String fileName = resolveFileName(uri);
            if (isSupportedImportFile(fileName)) {
                supportedUris.add(uri);
            }
        }

        if (supportedUris.isEmpty()) {
            if (isButtonClick) {
                new CustomAlertDialog(context)
                        .setTitleText(context.getString(R.string.invalid_mod_file))
                        .setTitleColor(Color.RED)
                        .setMessage(context.getString(R.string.invalid_mod_file_reason))
                        .setUseBorderedBackground(true)
                        .setBlurBackground(true)
                        .setPositiveButton(context.getString(R.string.confirm), d -> {})
                        .show();
            }
            return;
        }

        new CustomAlertDialog(context)
                .setTitleText(context.getString(R.string.import_confirmation_title))
                .setMessage(context.getString(R.string.import_confirmation_message, supportedUris.size()))
                .setPositiveButton(context.getString(R.string.confirm), d -> handleFilesWithOverwriteCheck(supportedUris, callback))
                .setNegativeButton(context.getString(R.string.cancel), d -> {
                    if (callback != null) {
                        callback.onError(context.getString(R.string.user_cancelled));
                    }
                })
                .show();
    }

    private void handleFilesWithOverwriteCheck(List<Uri> fileUris, FileOperationCallback callback) {
        new Thread(() -> {
            if (targetPath == null) {
                postError(callback, "No selected version mods directory");
                return;
            }

            File targetDir = new File(targetPath);
            if (!createDirectoryIfNeeded(targetDir)) {
                postError(callback, "Failed to create mods directory");
                return;
            }

            int completed = 0;
            int processed = 0;
            String lastError = null;

            for (Uri uri : fileUris) {
                PreparedImport preparedImport = null;
                try {
                    String fileName = resolveFileName(uri);
                    if (!isSupportedImportFile(fileName)) {
                        throw new IOException("Unsupported mod import file: " + fileName);
                    }

                    preparedImport = prepareImport(uri, fileName);
                    File destinationDir = new File(targetDir, preparedImport.targetId);
                    if (destinationDir.exists() && !confirmOverwrite(preparedImport.targetId)) {
                        continue;
                    }

                    if (destinationDir.exists() && !deleteRecursively(destinationDir)) {
                        throw new IOException("Failed to overwrite existing mod: " + preparedImport.targetId);
                    }

                    copyDirectory(preparedImport.packageDir, destinationDir);
                    ++processed;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    Log.e(TAG, "Failed to import mod", e);
                } finally {
                    if (preparedImport != null) {
                        deleteRecursively(preparedImport.cleanupRoot);
                    }
                    ++completed;
                    postProgress(callback, completed, fileUris.size());
                }
            }

            final int processedCount = processed;
            final String finalError = lastError;
            new Handler(Looper.getMainLooper()).post(() -> {
                modManager.refreshMods();
                if (processedCount > 0) {
                    if (callback != null) {
                        callback.onSuccess(processedCount);
                    }
                } else if (callback != null && finalError != null && !finalError.isEmpty()) {
                    callback.onError(finalError);
                }
            });
        }).start();
    }

    private PreparedImport prepareImport(Uri uri, String fileName) throws IOException {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".so")) {
            return prepareSoImport(uri, fileName);
        }
        if (lowerName.endsWith(".zip")) {
            return prepareZipImport(uri, fileName);
        }
        throw new IOException("Unsupported mod import file: " + fileName);
    }

    private PreparedImport prepareSoImport(Uri uri, String fileName) throws IOException {
        File stagingRoot = createTempDirectory("mod_import_so");
        String displayName = deriveDisplayNameFromLibrary(fileName);
        String targetId = buildTargetId(displayName, stripExtension(fileName));
        File packageDir = new File(stagingRoot, targetId);
        if (!packageDir.mkdirs()) {
            throw new IOException("Failed to prepare mod package directory");
        }

        File libraryFile = new File(packageDir, fileName);
        copyUriToFile(uri, libraryFile);
        writeManifest(packageDir, createNormalizedManifest(new JsonObject(), displayName, fileName, packageDir));
        return new PreparedImport(targetId, packageDir, stagingRoot);
    }

    private PreparedImport prepareZipImport(Uri uri, String fileName) throws IOException {
        File tempZip = new File(context.getCacheDir(), "mod_zip_" + System.currentTimeMillis() + ".zip");
        File stagingRoot = createTempDirectory("mod_import_zip");
        try {
            copyUriToFile(uri, tempZip);
            extractZip(tempZip, stagingRoot);
        } finally {
            if (tempZip.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempZip.delete();
            }
        }

        File modRoot = findImportedModRoot(stagingRoot);
        if (modRoot == null) {
            throw new IOException("Invalid mod zip: no manifest.json or .so entry found");
        }

        List<String> soFiles = collectRelativeSoFiles(modRoot);
        JsonObject manifest = readManifest(modRoot);
        String entryPath = resolveEntryPath(manifest, modRoot, soFiles, fileName);
        if (entryPath == null) {
            throw new IOException("Invalid mod zip: manifest entry is missing or ambiguous");
        }

        String displayName = resolveDisplayName(manifest, entryPath);
        writeManifest(modRoot, createNormalizedManifest(manifest, displayName, entryPath, modRoot));

        String rootName = modRoot.equals(stagingRoot) ? stripExtension(fileName) : modRoot.getName();
        String targetId = buildTargetId(displayName, rootName);
        return new PreparedImport(targetId, modRoot, stagingRoot);
    }

    private JsonObject readManifest(File modRoot) throws IOException {
        File manifestFile = new File(modRoot, MANIFEST_FILE_NAME);
        if (!manifestFile.isFile()) {
            return new JsonObject();
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(manifestFile), StandardCharsets.UTF_8)) {
            JsonObject manifest = GSON.fromJson(reader, JsonObject.class);
            return manifest == null ? new JsonObject() : manifest;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse manifest.json, regenerating it", e);
            return new JsonObject();
        }
    }

    private JsonObject createNormalizedManifest(JsonObject manifest, String displayName, String entryPath, File modRoot) {
        JsonObject normalized = manifest == null ? new JsonObject() : manifest.deepCopy();
        normalized.addProperty("type", PRELOAD_NATIVE_TYPE);
        normalized.addProperty("name", displayName);
        normalized.addProperty("entry", entryPath.replace('\\', '/'));
        normalized.addProperty("author", resolveAuthor(manifest));
        normalized.addProperty("icon", resolveIconPath(manifest, modRoot));
        normalized.addProperty("version", resolveVersion(manifest));
        return normalized;
    }

    private String resolveAuthor(JsonObject manifest) {
        String author = getStringProperty(manifest, "author");
        if (author != null && !author.trim().isEmpty()) {
            return author.trim();
        }

        if (manifest != null && manifest.has("authors") && manifest.get("authors").isJsonArray()) {
            JsonArray authors = manifest.getAsJsonArray("authors");
            for (JsonElement authorElement : authors) {
                if (authorElement == null || authorElement.isJsonNull()) {
                    continue;
                }

                if (authorElement.isJsonPrimitive()) {
                    String authorValue = authorElement.getAsString();
                    if (authorValue != null && !authorValue.trim().isEmpty()) {
                        return authorValue.trim();
                    }
                    continue;
                }

                if (authorElement.isJsonObject()) {
                    String authorName = getStringProperty(authorElement.getAsJsonObject(), "name");
                    if (authorName != null && !authorName.trim().isEmpty()) {
                        return authorName.trim();
                    }
                }
            }
        }

        return DEFAULT_MOD_AUTHOR;
    }

    private String resolveIconPath(JsonObject manifest, File modRoot) {
        String iconPath = normalizeEntryPath(getStringProperty(manifest, "icon"));
        if (iconPath != null) {
            File iconFile = new File(modRoot, iconPath);
            if (iconFile.isFile()) {
                return iconPath;
            }
        }
        return DEFAULT_MOD_ICON;
    }

    private String resolveVersion(JsonObject manifest) {
        String version = getStringProperty(manifest, "version");
        if (version != null && !version.trim().isEmpty()) {
            return version.trim();
        }
        return DEFAULT_MOD_VERSION;
    }

    private void writeManifest(File modRoot, JsonObject manifest) throws IOException {
        File manifestFile = new File(modRoot, MANIFEST_FILE_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(manifestFile), StandardCharsets.UTF_8)) {
            GSON.toJson(manifest, writer);
        }
    }

    private String resolveEntryPath(JsonObject manifest, File modRoot, List<String> soFiles, String sourceFileName) {
        String manifestEntry = getStringProperty(manifest, "entry");
        String normalizedEntry = normalizeEntryPath(manifestEntry);
        if (normalizedEntry != null && new File(modRoot, normalizedEntry).isFile()) {
            return normalizedEntry;
        }

        if (soFiles.isEmpty()) {
            return null;
        }

        if (soFiles.size() == 1) {
            return soFiles.get(0);
        }

        List<String> preferredTokens = new ArrayList<>();
        preferredTokens.add(stripExtension(sourceFileName));
        preferredTokens.add(modRoot.getName());
        if (normalizedEntry != null) {
            preferredTokens.add(new File(normalizedEntry).getName());
        }

        return findMatchingEntryByName(soFiles, preferredTokens);
    }

    private String findMatchingEntryByName(List<String> soFiles, List<String> preferredTokens) {
        List<String> normalizedTokens = new ArrayList<>();
        for (String preferredToken : preferredTokens) {
            String normalized = normalizeMatchToken(preferredToken);
            if (!normalized.isEmpty()) {
                normalizedTokens.add(normalized);
            }
        }

        String matchedEntry = null;
        for (String soFile : soFiles) {
            String fileName = new File(soFile).getName();
            String normalizedFileToken = normalizeMatchToken(fileName);
            for (String preferredToken : normalizedTokens) {
                if (!normalizedFileToken.equals(preferredToken)) {
                    continue;
                }
                if (matchedEntry != null && !matchedEntry.equals(soFile)) {
                    return null;
                }
                matchedEntry = soFile;
            }
        }
        return matchedEntry;
    }

    private String resolveDisplayName(JsonObject manifest, String entryPath) {
        String manifestName = getStringProperty(manifest, "name");
        if (manifestName != null && !manifestName.trim().isEmpty()) {
            return manifestName.trim();
        }
        return deriveDisplayNameFromLibrary(new File(entryPath).getName());
    }

    private String deriveDisplayNameFromLibrary(String fileName) {
        String baseName = stripExtension(fileName);
        if (baseName.startsWith("lib") && baseName.length() > 3) {
            baseName = baseName.substring(3);
        }
        if (baseName.isEmpty()) {
            return "mod";
        }
        return baseName;
    }

    private String buildTargetId(String preferredName, String fallbackName) {
        String candidate = sanitizeDirectoryName(preferredName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        candidate = sanitizeDirectoryName(fallbackName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        return "mod_" + System.currentTimeMillis();
    }

    private String sanitizeDirectoryName(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = value.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_')
                .replace('*', '_')
                .replace('?', '_')
                .replace('"', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace('|', '_');

        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized.trim();
    }

    private String normalizeEntryPath(String entryPath) {
        if (entryPath == null) {
            return null;
        }

        String normalized = entryPath.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }

        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }
        return normalized;
    }

    private String normalizeMatchToken(String value) {
        if (value == null) {
            return "";
        }

        String token = stripExtension(value).trim();
        if (token.startsWith("lib") && token.length() > 3) {
            token = token.substring(3);
        }
        return token.replaceAll("[\\s_\\-.]+", "").toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private List<String> collectRelativeSoFiles(File rootDir) {
        List<String> soFiles = new ArrayList<>();
        collectRelativeSoFilesRecursive(rootDir, rootDir, soFiles);
        soFiles.sort(Comparator.naturalOrder());
        return soFiles;
    }

    private void collectRelativeSoFilesRecursive(File rootDir, File currentDir, List<String> soFiles) {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                collectRelativeSoFilesRecursive(rootDir, file, soFiles);
                continue;
            }

            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".so")) {
                String relativePath = rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                String normalizedPath = normalizeEntryPath(relativePath);
                if (normalizedPath != null) {
                    soFiles.add(normalizedPath);
                }
            }
        }
    }

    private File findImportedModRoot(File extractedRoot) {
        if (isDirectModRoot(extractedRoot)) {
            return extractedRoot;
        }

        List<File> candidates = new ArrayList<>();
        collectCandidateModRoots(extractedRoot, candidates);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    private void collectCandidateModRoots(File currentDir, List<File> candidates) {
        File[] children = currentDir.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (!child.isDirectory() || "__MACOSX".equals(child.getName())) {
                continue;
            }

            if (isDirectModRoot(child)) {
                candidates.add(child);
                continue;
            }
            collectCandidateModRoots(child, candidates);
        }
    }

    private boolean isDirectModRoot(File directory) {
        if (!directory.isDirectory()) {
            return false;
        }

        File manifestFile = new File(directory, MANIFEST_FILE_NAME);
        if (manifestFile.isFile()) {
            return true;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".so")) {
                return true;
            }
        }
        return false;
    }

    private boolean confirmOverwrite(String modName) {
        final Object decisionLock = new Object();
        final boolean[] overwrite = new boolean[1];
        final boolean[] decisionMade = new boolean[1];

        new Handler(Looper.getMainLooper()).post(() -> new CustomAlertDialog(context)
                .setTitleText(context.getString(R.string.overwrite_file_title))
                .setMessage(context.getString(R.string.overwrite_file_message, modName))
                .setPositiveButton(context.getString(R.string.overwrite), dlg -> {
                    synchronized (decisionLock) {
                        overwrite[0] = true;
                        decisionMade[0] = true;
                        decisionLock.notifyAll();
                    }
                })
                .setNegativeButton(context.getString(R.string.skip), dlg -> {
                    synchronized (decisionLock) {
                        overwrite[0] = false;
                        decisionMade[0] = true;
                        decisionLock.notifyAll();
                    }
                })
                .show());

        synchronized (decisionLock) {
            while (!decisionMade[0]) {
                try {
                    decisionLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Overwrite confirmation interrupted", e);
                    return false;
                }
            }
        }

        return overwrite[0];
    }

    private void copyUriToFile(Uri source, File destination) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) {
                throw new IOException("Cannot open input file");
            }
            copyStreamToFile(in, destination);
        }
    }

    private void extractZip(File zipFile, File targetDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];

            while ((entry = zis.getNextEntry()) != null) {
                String normalizedName = normalizeZipEntryName(entry.getName());
                if (normalizedName.isEmpty()) {
                    continue;
                }

                File entryFile = new File(targetDir, normalizedName);
                String targetCanonicalPath = targetDir.getCanonicalPath();
                String entryCanonicalPath = entryFile.getCanonicalPath();
                if (!entryCanonicalPath.startsWith(targetCanonicalPath + File.separator)
                        && !entryCanonicalPath.equals(targetCanonicalPath)) {
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!entryFile.exists() && !entryFile.mkdirs()) {
                        throw new IOException("Failed to create directory: " + entryFile.getAbsolutePath());
                    }
                    continue;
                }

                File parent = entryFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
                }

                try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    private String normalizeZipEntryName(String name) {
        if (name == null) {
            return "";
        }

        String normalized = name.trim().replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private void copyStreamToFile(InputStream input, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (OutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Failed to create directory: " + target.getAbsolutePath());
            }

            File[] files = source.listFiles();
            if (files == null) {
                return;
            }

            for (File file : files) {
                copyDirectory(file, new File(target, file.getName()));
            }
            return;
        }

        try (FileInputStream fis = new FileInputStream(source)) {
            copyStreamToFile(fis, target);
        }
    }

    private File createTempDirectory(String prefix) throws IOException {
        File cacheDir = context.getCacheDir();
        for (int attempt = 0; attempt < 10; attempt++) {
            File dir = new File(cacheDir, prefix + "_" + System.currentTimeMillis() + "_" + attempt);
            if (dir.mkdirs()) {
                return dir;
            }
        }
        throw new IOException("Failed to create temporary directory");
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private void postProgress(FileOperationCallback callback, int completed, int total) {
        if (callback == null || total <= 0) {
            return;
        }
        int progress = Math.min(100, (completed * 100) / total);
        new Handler(Looper.getMainLooper()).post(() -> callback.onProgressUpdate(progress));
    }

    private void postError(FileOperationCallback callback, String message) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(message));
    }

    private boolean isSupportedImportFile(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".so") || lowerName.endsWith(".zip");
    }

    private String getStringProperty(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private List<Uri> extractFileUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null) return uris;

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                uris.add(clipData.getItemAt(i).getUri());
            }
        } else if (intent.getData() != null) {
            uris.add(intent.getData());
        }
        return uris;
    }

    private String resolveFileName(Uri uri) {
        String defaultName = "unknown_" + System.currentTimeMillis();
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        String displayName = cursor.getString(nameIndex);
                        if (displayName != null && !displayName.isEmpty()) {
                            return displayName;
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        }

        String path = uri.getPath();
        if (path != null) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
        }

        String lastSegment = uri.getLastPathSegment();
        return lastSegment != null && !lastSegment.isEmpty() ? lastSegment : defaultName;
    }

    private boolean createDirectoryIfNeeded(File dir) {
        return dir.exists() || dir.mkdirs();
    }
}
