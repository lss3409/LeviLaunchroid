package org.levimc.launcher.core.modcatalog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.FileHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class ModCatalogInstaller {
    public interface Callback {
        void onProgress(int progress, boolean importing);
        void onSuccess();
        void onError(String message);
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String TAG = "ModCatalogInstaller";
    /**
     * GitHub Releases 直连在某些网络环境下会被阻断(连接超时/被重置)。仅对
     * github.com 域名的资产 URL 生效:直连失败后按顺序尝试以下镜像前缀,
     * 镜像 URL = 前缀 + 原始完整 URL(如 https://ghfast.top/https://github.com/...)。
     * 安全性说明:镜像返回的内容不被信任,下载完成后仍会执行 SHA-256 校验
     * (见 verifySha256),被篡改的内容会被拒绝,镜像仅用于解决可达性问题。
     */
    private static final String[] GITHUB_MIRROR_PREFIXES = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/"
    };
    private final Context context;
    private final FileHandler fileHandler;

    public ModCatalogInstaller(Context context, FileHandler fileHandler) {
        this.context = context;
        this.fileHandler = fileHandler;
    }

    public void install(ModCatalog.CatalogMod mod, ModCatalog.CatalogRelease release,
                        ModCatalog.CatalogAsset asset, Callback callback) {
        EXECUTOR.execute(() -> {
            File downloadedFile = null;
            try {
                String extension = extensionFor(asset);
                if (extension == null) throw new IllegalArgumentException("Unsupported download file type");
                File downloadDir = new File(context.getCacheDir(), "external_mod_downloads");
                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    throw new IllegalStateException("Could not prepare download folder");
                }
                String assetName = safeName(asset.name);
                if (!assetName.toLowerCase(Locale.ROOT).endsWith(extension)) assetName += extension;
                downloadedFile = new File(downloadDir,
                        safeName(mod.id) + "-" + safeName(release.version) + "-" + assetName);
                Exception lastError = null;
                List<String> candidates = buildDownloadCandidates(asset.downloadUrl);
                for (int i = 0; i < candidates.size(); i++) {
                    String url = candidates.get(i);
                    if (i > 0) {
                        Log.i(TAG, "Direct download failed, retrying via mirror: " + url);
                    }
                    try {
                        downloadToFile(url, downloadedFile, callback);
                        lastError = null;
                        break;
                    } catch (Exception error) {
                        lastError = error;
                        Log.w(TAG, "Download attempt failed: " + url, error);
                        // 丢弃半成品文件,下一次尝试会重新创建(FileOutputStream 本身也会截断)
                        //noinspection ResultOfMethodCallIgnored
                        downloadedFile.delete();
                    }
                }
                if (lastError != null) {
                    throw lastError;
                }
                if (!verifySha256(downloadedFile, asset.sha256)) {
                    throw new IllegalStateException(context.getString(R.string.external_mods_verification_failed));
                }

                File resultFile = downloadedFile;
                context.getMainExecutor().execute(() -> importDownloadedFile(resultFile, mod, release, callback));
            } catch (Exception error) {
                if (downloadedFile != null) downloadedFile.delete();
                deliverError(callback, message(error));
            }
        });
    }

    private void importDownloadedFile(File file, ModCatalog.CatalogMod mod,
                                      ModCatalog.CatalogRelease release, Callback callback) {
        try {
            deliverProgress(callback, 88, true);
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fileHandler.processCatalogModDirectly(intent, mod.name, mod.author,
                    release.version, release.minecraftVersions,
                    new FileHandler.FileOperationCallback() {
                        @Override
                        public void onSuccess(int processedFiles) {
                            file.delete();
                            if (callback != null) callback.onSuccess();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            file.delete();
                            if (callback != null) callback.onError(errorMessage);
                        }

                        @Override
                        public void onProgressUpdate(int progress) {
                            deliverProgress(callback, 88 + progress * 12 / 100, true);
                        }
                    });
        } catch (Exception error) {
            file.delete();
            if (callback != null) callback.onError(message(error));
        }
    }

    /**
     * 构建候选下载 URL 列表:原始直连 URL 永远在首位;仅当其位于 github.com
     * 域名时才依次追加镜像回退 URL(镜像 URL = 镜像前缀 + 原始完整 URL)。
     * 其他域名(如 CDN、自建服务器)一律直连,不做镜像。
     */
    private List<String> buildDownloadCandidates(String url) {
        List<String> candidates = new ArrayList<>();
        candidates.add(url);
        if (isGitHubUrl(url)) {
            for (String mirrorPrefix : GITHUB_MIRROR_PREFIXES) {
                candidates.add(mirrorPrefix + url);
            }
        }
        return candidates;
    }

    private boolean isGitHubUrl(String url) {
        if (url == null) return false;
        try {
            String host = Uri.parse(url).getHost();
            return "github.com".equalsIgnoreCase(host) || "www.github.com".equalsIgnoreCase(host);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 使用共享的 OkHttp 客户端把 {@code url} 流式写入 {@code target}。
     * 失败(IOException/超时/非 2xx)直接抛出,由调用方决定是否回退到下一个镜像。
     * 进度回调与取消逻辑保持原有签名(onProgress(progress, importing))不变。
     */
    private void downloadToFile(String url, File target, Callback callback) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Download failed with HTTP " + response.code());
            }
            long total = response.body().contentLength();
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                long completed = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    completed += read;
                    if (total > 0) {
                        int progress = Math.min(85, (int) ((completed * 85L) / total));
                        deliverProgress(callback, progress, false);
                    }
                }
                output.getFD().sync();
            }
        }
    }

    private boolean verifySha256(File file, String expected) throws Exception {
        if (expected == null || expected.isEmpty()) return true;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return expected.equalsIgnoreCase(actual.toString());
    }

    private void deliverProgress(Callback callback, int progress, boolean importing) {
        if (callback == null) return;
        context.getMainExecutor().execute(() -> callback.onProgress(progress, importing));
    }

    private void deliverError(Callback callback, String message) {
        if (callback == null) return;
        context.getMainExecutor().execute(() -> callback.onError(message));
    }

    private String extensionFor(ModCatalog.CatalogAsset asset) {
        String name = asset == null ? null : asset.name;
        String extension = extensionForName(name);
        if (extension != null) return extension;
        String path = asset == null || asset.downloadUrl == null ? null : Uri.parse(asset.downloadUrl).getPath();
        return extensionForName(path);
    }

    private String extensionForName(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".levipack")) return ".levipack";
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".so")) return ".so";
        return null;
    }

    private String safeName(String value) {
        String safe = value == null ? "mod" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "mod" : safe;
    }

    private String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
