package org.levimc.launcher.core.content;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ContentImporter {
    private static final String TAG = "ContentImporter";
    private static final int BUFFER_SIZE = 1048576;

    private final Context context;
    private final ExecutorService executor;

    public interface ImportCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(int current, int total, String fileName);
    }

    public static class ImportResult {
        public int resourcePacksImported = 0;
        public int behaviorPacksImported = 0;
        public int skinPacksImported = 0;
        public int worldsImported = 0;
        public int structuresImported = 0;
    }

    public ContentImporter(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void importContent(List<Uri> uris, File resourcePacksDir, File behaviorPacksDir,
                              File skinPacksDir, File worldsDir, File structuresDir, ImportCallback callback) {
        executor.execute(() -> {
            try {
                ImportResult totalResult = new ImportResult();
                StringBuilder errors = new StringBuilder();

                int total = uris.size();
                int index = 0;
                for (Uri uri : uris) {
                    index++;
                    try {
                        String fileName = getFileName(uri);
                        if (fileName == null || fileName.isEmpty()) {
                            fileName = "content.mcpack";
                        }
                        String lowerName = fileName.toLowerCase();
                        Log.d(TAG, "Importing file: " + fileName);
                        if (callback != null) {
                            callback.onProgress(index, total, fileName);
                        }

                        InputStream inputStream = context.getContentResolver().openInputStream(uri);
                        if (inputStream == null) {
                            errors.append("Cannot open file: ").append(fileName).append("\n");
                            continue;
                        }

                        File tempFile = new File(context.getCacheDir(), "temp_import_" + System.currentTimeMillis());
                        copyStreamToFile(inputStream, tempFile);
                        inputStream.close();

                        ImportResult result = new ImportResult();

                        if (lowerName.endsWith(".mcworld")) {
                            importMcworld(tempFile, worldsDir, result);
                        } else if (lowerName.endsWith(".mcstructure")) {
                            importMcstructure(tempFile, fileName, structuresDir, result);
                        } else if (lowerName.endsWith(".mcaddon")) {
                            importMcaddon(tempFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                        } else if (lowerName.endsWith(".mcpack")) {
                            importMcpack(tempFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                        } else if (lowerName.endsWith(".zip")) {
                            importGenericArchive(tempFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir, structuresDir, result);
                        } else {
                            importMcpack(tempFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                            if (result.resourcePacksImported == 0 && result.behaviorPacksImported == 0 && result.skinPacksImported == 0) {
                                importMcaddon(tempFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                            }
                            if (result.resourcePacksImported == 0 && result.behaviorPacksImported == 0 &&
                                result.skinPacksImported == 0 && result.worldsImported == 0) {
                                importMcworld(tempFile, worldsDir, result);
                            }
                        }

                        totalResult.resourcePacksImported += result.resourcePacksImported;
                        totalResult.behaviorPacksImported += result.behaviorPacksImported;
                        totalResult.skinPacksImported += result.skinPacksImported;
                        totalResult.worldsImported += result.worldsImported;
                        totalResult.structuresImported += result.structuresImported;

                        tempFile.delete();

                    } catch (Exception e) {
                        Log.e(TAG, "Import failed for uri: " + uri, e);
                        errors.append("导入失败 ").append(uri.getLastPathSegment()).append(": ").append(e.getMessage()).append("\n");
                    }
                }

                StringBuilder message = new StringBuilder();
                if (totalResult.worldsImported > 0) {
                    message.append("世界: ").append(totalResult.worldsImported).append(" ");
                }
                if (totalResult.resourcePacksImported > 0) {
                    message.append("资源包: ").append(totalResult.resourcePacksImported).append(" ");
                }
                if (totalResult.behaviorPacksImported > 0) {
                    message.append("行为包: ").append(totalResult.behaviorPacksImported).append(" ");
                }
                if (totalResult.skinPacksImported > 0) {
                    message.append("皮肤包: ").append(totalResult.skinPacksImported).append(" ");
                }
                if (totalResult.structuresImported > 0) {
                    message.append("结构文件: ").append(totalResult.structuresImported).append(" ");
                }

                if (message.length() == 0) {
                    callback.onError(errors.length() > 0 ? errors.toString().trim() : "没有导入任何内容");
                } else {
                    String finalMessage = "已导入: " + message.toString().trim();
                    if (errors.length() > 0) {
                        finalMessage += "\n错误:\n" + errors.toString().trim();
                    }
                    callback.onSuccess(finalMessage);
                }

            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                callback.onError("导入失败: " + e.getMessage());
            }
        });
    }

    private void importMcstructure(File file, String fileName, File structuresDir, ImportResult result) throws IOException {
        if (structuresDir == null) return;
        if (!structuresDir.exists()) structuresDir.mkdirs();
        File target = new File(structuresDir, fileName);
        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
        result.structuresImported++;
    }

    private void importMcworld(File zipFile, File worldsDir, ImportResult result) throws IOException {
        if (worldsDir == null) return;
        if (!worldsDir.exists()) worldsDir.mkdirs();

        File tempDir = new File(context.getCacheDir(), "temp_world_" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            extractZip(zipFile, tempDir);
            File worldDir = findWorldDirectory(tempDir);
            if (worldDir == null) {
                return;
            }

            String worldName = generateRandomName();
            File targetDir = new File(worldsDir, worldName);
            copyDirectory(worldDir, targetDir);
            result.worldsImported++;
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private void importMcpack(File zipFile, File resourcePacksDir, File behaviorPacksDir, 
                              File skinPacksDir, ImportResult result) throws IOException {
        File tempDir = new File(context.getCacheDir(), "temp_pack_" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            extractZip(zipFile, tempDir);
            File packDir = findPackDirectory(tempDir);
            if (packDir == null) {
                return;
            }

            File manifestFile = new File(packDir, "manifest.json");
            if (!manifestFile.exists()) {
                return;
            }

            PackInfo packInfo = parseManifest(manifestFile);
            if (packInfo == null) {
                return;
            }

            // 重复导入检测：uuid 相同则跳过
            if (packInfo.uuid != null) {
                boolean duplicate =
                        (packInfo.isResourcePack && resourcePacksDir != null && isDuplicatePack(resourcePacksDir, packInfo.uuid)) ||
                        (packInfo.isBehaviorPack && behaviorPacksDir != null && isDuplicatePack(behaviorPacksDir, packInfo.uuid)) ||
                        (packInfo.isSkinPack && skinPacksDir != null && isDuplicatePack(skinPacksDir, packInfo.uuid));
                if (duplicate) {
                    return;
                }
            }

            String packName = generateRandomName();

            if (packInfo.isResourcePack && resourcePacksDir != null) {
                if (!resourcePacksDir.exists()) resourcePacksDir.mkdirs();
                File targetDir = new File(resourcePacksDir, packName);
                copyDirectory(packDir, targetDir);
                result.resourcePacksImported++;
            }
            if (packInfo.isBehaviorPack && behaviorPacksDir != null) {
                if (!behaviorPacksDir.exists()) behaviorPacksDir.mkdirs();
                File targetDir = new File(behaviorPacksDir, packName);
                copyDirectory(packDir, targetDir);
                result.behaviorPacksImported++;
            }
            if (packInfo.isSkinPack && skinPacksDir != null) {
                if (!skinPacksDir.exists()) skinPacksDir.mkdirs();
                File targetDir = new File(skinPacksDir, packName);
                copyDirectory(packDir, targetDir);
                result.skinPacksImported++;
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private void importMcaddon(File zipFile, File resourcePacksDir, File behaviorPacksDir, 
                               File skinPacksDir, ImportResult result) throws IOException {
        File tempDir = new File(context.getCacheDir(), "temp_addon_" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            extractZip(zipFile, tempDir);

            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().toLowerCase().endsWith(".mcpack")) {
                        importMcpack(file, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                    }
                }
            }

            List<PackDirectory> packDirs = findAllPackDirectories(tempDir);
            for (PackDirectory packDirectory : packDirs) {
                PackInfo packInfo = parseManifest(new File(packDirectory.dir, "manifest.json"));
                if (packInfo == null) continue;

                String packName = generateRandomName();

                if (packInfo.isResourcePack && resourcePacksDir != null) {
                    if (!resourcePacksDir.exists()) resourcePacksDir.mkdirs();
                    File targetDir = new File(resourcePacksDir, packName);
                    copyDirectory(packDirectory.dir, targetDir);
                    result.resourcePacksImported++;
                }
                if (packInfo.isBehaviorPack && behaviorPacksDir != null) {
                    if (!behaviorPacksDir.exists()) behaviorPacksDir.mkdirs();
                    File targetDir = new File(behaviorPacksDir, packName);
                    copyDirectory(packDirectory.dir, targetDir);
                    result.behaviorPacksImported++;
                }
                if (packInfo.isSkinPack && skinPacksDir != null) {
                    if (!skinPacksDir.exists()) skinPacksDir.mkdirs();
                    File targetDir = new File(skinPacksDir, packName);
                    copyDirectory(packDirectory.dir, targetDir);
                    result.skinPacksImported++;
                }
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 通用压缩包导入：解压后递归查找其中嵌套的 .mcpack / .mcworld / .mcaddon / .mcstructure，
     * 只要压缩包里有 MC 资源就逐个导入；若解压后本身就是一个包或世界，则直接导入。
     */
    private void importGenericArchive(File zipFile, File resourcePacksDir, File behaviorPacksDir,
                                      File skinPacksDir, File worldsDir, File structuresDir,
                                      ImportResult result) throws IOException {
        File tempDir = new File(context.getCacheDir(), "temp_archive_" + System.currentTimeMillis());
        tempDir.mkdirs();
        try {
            extractZip(zipFile, tempDir);

            int before = countImported(result);

            // 1) 递归查找嵌套的 .mcpack / .mcaddon / .mcworld / .mcstructure 文件
            List<File> nestedFiles = new ArrayList<>();
            collectNestedContentFiles(tempDir, nestedFiles);
            for (File nested : nestedFiles) {
                try {
                    String name = nested.getName().toLowerCase();
                    if (name.endsWith(".mcworld")) {
                        importMcworld(nested, worldsDir, result);
                    } else if (name.endsWith(".mcstructure")) {
                        importMcstructure(nested, nested.getName(), structuresDir, result);
                    } else if (name.endsWith(".mcaddon")) {
                        importMcaddon(nested, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                    } else {
                        importMcpack(nested, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to import nested file: " + nested.getName(), e);
                }
            }

            // 2) 若没有识别到任何嵌套文件，尝试把解压结果当作单个包/世界导入
            if (countImported(result) == before) {
                importMcpack(zipFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                if (countImported(result) == before) {
                    importMcaddon(zipFile, resourcePacksDir, behaviorPacksDir, skinPacksDir, result);
                }
                if (countImported(result) == before) {
                    importMcworld(zipFile, worldsDir, result);
                }
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private int countImported(ImportResult result) {
        return result.resourcePacksImported + result.behaviorPacksImported
                + result.skinPacksImported + result.worldsImported + result.structuresImported;
    }

    private void collectNestedContentFiles(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collectNestedContentFiles(file, out);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".mcpack") || name.endsWith(".mcworld")
                        || name.endsWith(".mcaddon") || name.endsWith(".mcstructure")) {
                    out.add(file);
                }
            }
        }
    }

    private static class PackInfo {
        boolean isResourcePack = false;
        boolean isBehaviorPack = false;
        boolean isSkinPack = false;
        String uuid = null;
    }

    private static class PackDirectory {
        File dir;
        PackDirectory(File dir) {
            this.dir = dir;
        }
    }

    private PackInfo parseManifest(File manifestFile) {
        try {
            byte[] data = new byte[(int) manifestFile.length()];
            try (FileInputStream fis = new FileInputStream(manifestFile)) {
                fis.read(data);
            }
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            jsonStr = removeJsonComments(jsonStr);
            JSONObject manifest = new JSONObject(jsonStr);

            PackInfo info = new PackInfo();

            if (manifest.has("modules")) {
                JSONArray modules = manifest.getJSONArray("modules");
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject module = modules.getJSONObject(i);
                    if (module.has("type")) {
                        String type = module.getString("type").toLowerCase();
                        switch (type) {
                            case "resources":
                                info.isResourcePack = true;
                                break;
                            case "data":
                            case "script":
                                info.isBehaviorPack = true;
                                break;
                            case "skin_pack":
                                info.isSkinPack = true;
                                break;
                        }
                    }
                }
            }

            if (manifest.has("header")) {
                JSONObject header = manifest.getJSONObject("header");
                if (header.has("uuid")) {
                    info.uuid = header.getString("uuid");
                }
            }

            return info;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse manifest", e);
            return null;
        }
    }

    private String removeJsonComments(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            char next = (i + 1 < json.length()) ? json.charAt(i + 1) : 0;

            if (inSingleLineComment) {
                if (c == '\n') {
                    inSingleLineComment = false;
                    result.append(c);
                }
                continue;
            }

            if (inMultiLineComment) {
                if (c == '*' && next == '/') {
                    inMultiLineComment = false;
                    i++;
                }
                continue;
            }

            if (inString) {
                result.append(c);
                if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                result.append(c);
                continue;
            }

            if (c == '/' && next == '/') {
                inSingleLineComment = true;
                i++;
                continue;
            }

            if (c == '/' && next == '*') {
                inMultiLineComment = true;
                i++;
                continue;
            }

            result.append(c);
        }

        return result.toString();
    }

    private List<PackDirectory> findAllPackDirectories(File searchDir) {
        List<PackDirectory> result = new ArrayList<>();
        findPackDirectoriesRecursive(searchDir, result, searchDir);
        return result;
    }

    private void findPackDirectoriesRecursive(File dir, List<PackDirectory> result, File rootDir) {
        if (dir == null || !dir.isDirectory()) return;

        File manifest = new File(dir, "manifest.json");
        if (manifest.exists() && !dir.equals(rootDir)) {
            result.add(new PackDirectory(dir));
            return;
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findPackDirectoriesRecursive(file, result, rootDir);
                }
            }
        }
    }

    private File findWorldDirectory(File searchDir) {
        File levelDat = new File(searchDir, "level.dat");
        if (levelDat.exists()) {
            return searchDir;
        }

        File[] files = searchDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File found = findWorldDirectory(file);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private File findPackDirectory(File searchDir) {
        File manifest = new File(searchDir, "manifest.json");
        if (manifest.exists()) {
            return searchDir;
        }

        File[] files = searchDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File found = findPackDirectory(file);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private String generateRandomName() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean isDuplicatePack(File dir, String uuid) {
        if (dir == null || !dir.exists() || uuid == null) return false;
        File[] files = dir.listFiles(File::isDirectory);
        if (files == null) return false;
        for (File file : files) {
            File manifest = new File(file, "manifest.json");
            if (!manifest.exists()) continue;
            PackInfo existing = parseManifest(manifest);
            if (existing != null && uuid.equalsIgnoreCase(existing.uuid)) {
                return true;
            }
        }
        return false;
    }

    private void extractZip(File zipFile, File targetDir) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[BUFFER_SIZE];

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = normalizeZipEntryName(entry.getName());
                if (entryName.startsWith("../") || entryName.contains("/../")) {
                    continue;
                }
                File entryFile = new File(targetDir, entryName);

                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    entryFile.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(entryFile)) {
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private String normalizeZipEntryName(String name) {
        String n = name.trim();
        n = n.replace("\\", "/");
        if (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        while (n.contains("//")) {
            n = n.replace("//", "/");
        }
        return n;
    }

    private void copyStreamToFile(InputStream input, File output) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = input.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists()) {
                target.mkdirs();
            }
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirectory(file, new File(target, file.getName()));
                }
            }
        } else {
            copyFile(source, target);
        }
    }

    private void copyFile(File source, File target) throws IOException {
        target.getParentFile().mkdirs();
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return false;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get filename from content URI", e);
            }
        }
        if (result == null) {
            String path = uri.getPath();
            if (path != null) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    result = path.substring(lastSlash + 1);
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        Log.d(TAG, "getFileName: uri=" + uri + ", result=" + result);
        return result;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
