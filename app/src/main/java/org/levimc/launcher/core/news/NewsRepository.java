package org.levimc.launcher.core.news;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class NewsRepository {
    public interface Callback {
        void onResult(NewsFeed feed, Throwable error);
    }

    public static final String REMOTE_URL = "https://raw.githubusercontent.com/LiteLDev/LeviLaunchroid/main/resources/launcher/news.json";
    /** v0.4.165：LBBL 更新公告源（lss3409/LeviLaunchroid 仓库同款
     *  news.json，铃铛双栏位——LBBL 公告 + LeviLauncher 官方）。 */
    public static final String REMOTE_URL_LBBL = "https://raw.githubusercontent.com/lss3409/LeviLaunchroid/main/resources/launcher/news_lbbl.json";
    /** v0.4.201：新闻多源回退（检查更新同款镜像前缀）——raw 直连
     *  国内经常失败只显示内置旧公告，失败依次试 ghproxy 镜像。 */
    private static final String[] NEWS_MIRRORS = {
            "https://ghproxy.net/",
            "https://gh-proxy.org/",
            "https://gh.zwy.one/",
            "https://mirror.ghproxy.com/",
    };
    private static final String ASSET_PATH = "launcher/news.json";
    private static final String CACHE_FILE = "launcher_news.json";
    private static final String CACHE_FILE_LBBL = "launcher_news_lbbl.json";
    private static final String PREFS = "launcher_news_repository";
    private static final String KEY_LAST_REFRESH = "last_refresh";
    private static final String KEY_LAST_REFRESH_LBBL = "last_refresh_lbbl";
    private static final long REFRESH_INTERVAL_MS = 5L * 60L * 1000L;
    private static final Gson GSON = new Gson();
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object REFRESH_LOCK = new Object();
    private static final List<Callback> REFRESH_CALLBACKS = new ArrayList<>();
    private static boolean refreshing;
    private static volatile NewsFeed memoryCache;
    private static volatile NewsFeed memoryCacheLbbl;

    private NewsRepository() {
    }

    public static void loadCached(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> deliver(callback, loadLocal(appContext), null));
    }

    /** v0.4.165：LBBL 源缓存读取（独立缓存文件）。 */
    public static void loadCachedLbbl(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> deliver(callback, loadLocalLbbl(appContext), null));
    }

    public static void refreshIfStale(Context context, Callback callback) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_REFRESH, 0L);
        if (elapsed < REFRESH_INTERVAL_MS) {
            loadCached(context, callback);
            return;
        }
        refresh(context, callback);
    }

    /** v0.4.165：LBBL 源时效刷新。 */
    public static void refreshIfStaleLbbl(Context context, Callback callback) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_REFRESH_LBBL, 0L);
        if (elapsed < REFRESH_INTERVAL_MS) {
            loadCachedLbbl(context, callback);
            return;
        }
        refreshLbbl(context, callback);
    }

    public static void refresh(Context context, Callback callback) {
        refreshFrom(context, callback, false);
    }

    /** v0.4.165：LBBL 源强制刷新。 */
    public static void refreshLbbl(Context context, Callback callback) {
        refreshFrom(context, callback, true);
    }

    private static void refreshFrom(Context context, Callback callback, boolean lbbl) {
        Context appContext = context.getApplicationContext();
        synchronized (REFRESH_LOCK) {
            if (callback != null) REFRESH_CALLBACKS.add(callback);
            if (refreshing) return;
            refreshing = true;
        }
        final boolean fLbbl = lbbl;
        EXECUTOR.execute(() -> {
            NewsFeed result;
            Throwable resultError = null;
            try {
                String json = fetchNewsJson(fLbbl);
                NewsFeed feed = parse(json);
                if (fLbbl) {
                    writeCacheLbbl(appContext, json);
                    memoryCacheLbbl = feed;
                } else {
                    writeCache(appContext, json);
                    memoryCache = feed;
                }
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(fLbbl ? KEY_LAST_REFRESH_LBBL : KEY_LAST_REFRESH,
                                System.currentTimeMillis()).apply();
                NewsState.notifyChanged(appContext);
                result = feed;
            } catch (Throwable error) {
                result = fLbbl ? loadLocalLbbl(appContext) : loadLocal(appContext);
                resultError = error;
            }

            List<Callback> callbacks;
            synchronized (REFRESH_LOCK) {
                refreshing = false;
                callbacks = new ArrayList<>(REFRESH_CALLBACKS);
                REFRESH_CALLBACKS.clear();
            }
            for (Callback pending : callbacks) deliver(pending, result, resultError);
        });
    }

    /** v0.4.201：直链 → ghproxy 镜像前缀顺序重试拉取新闻 JSON（任一成功即停）。 */
    private static String fetchNewsJson(boolean lbbl) throws Exception {
        String base = lbbl ? REMOTE_URL_LBBL : REMOTE_URL;
        String[] urls = new String[NEWS_MIRRORS.length + 1];
        urls[0] = base;
        for (int i = 0; i < NEWS_MIRRORS.length; i++) {
            urls[i + 1] = NEWS_MIRRORS[i] + base;
        }
        Exception last = null;
        for (String url : urls) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("Cache-Control", "no-cache")
                        .build();
                try (Response response = HTTP.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException(
                                "News request failed with HTTP " + response.code());
                    }
                    return response.body().string();
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("all news mirrors failed");
    }

    private static NewsFeed loadLocal(Context context) {
        NewsFeed cached = memoryCache;
        if (cached != null) return cached;
        File cacheFile = new File(context.getFilesDir(), CACHE_FILE);
        if (cacheFile.isFile()) {
            try (InputStream input = new FileInputStream(cacheFile)) {
                cached = parse(readUtf8(input));
                memoryCache = cached;
                return cached;
            } catch (Exception ignored) {
            }
        }
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            cached = parse(readUtf8(input));
        } catch (Exception ignored) {
            cached = new NewsFeed();
        }
        memoryCache = cached;
        return cached;
    }

    /** v0.4.165：LBBL 源本地缓存（无 asset 兜底——空 feed）。 */
    private static NewsFeed loadLocalLbbl(Context context) {
        NewsFeed cached = memoryCacheLbbl;
        if (cached != null) return cached;
        File cacheFile = new File(context.getFilesDir(), CACHE_FILE_LBBL);
        if (cacheFile.isFile()) {
            try (InputStream input = new FileInputStream(cacheFile)) {
                cached = parse(readUtf8(input));
                memoryCacheLbbl = cached;
                return cached;
            } catch (Exception ignored) {
            }
        }
        // v0.4.178：asset 兜底（resources 目录映射为 assets，APK 内置
        // 公告——平板连 GitHub raw 失败时也有内容，不再是空 feed）
        try (InputStream input = context.getAssets().open("launcher/news_lbbl.json")) {
            cached = parse(readUtf8(input));
        } catch (Exception ignored) {
            cached = new NewsFeed();
        }
        memoryCacheLbbl = cached;
        return cached;
    }

    private static NewsFeed parse(String json) {
        NewsFeed feed = GSON.fromJson(json, NewsFeed.class);
        if (feed == null || feed.schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported news feed schema");
        }
        if (feed.items == null) feed.items = new ArrayList<>();
        feed.items.removeIf(item -> item == null
                || TextUtils.isEmpty(item.id)
                || TextUtils.isEmpty(item.title)
                || TextUtils.isEmpty(item.publishedAt));
        feed.items.sort(Comparator.comparing((NewsItem item) -> item.publishedAt).reversed());
        return feed;
    }

    private static String readUtf8(InputStream input) throws Exception {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeCache(Context context, String json) throws Exception {
        File target = new File(context.getFilesDir(), CACHE_FILE);
        File temporary = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** v0.4.165：LBBL 源缓存写入（独立文件）。 */
    private static void writeCacheLbbl(Context context, String json) throws Exception {
        File target = new File(context.getFilesDir(), CACHE_FILE_LBBL);
        File temporary = new File(context.getFilesDir(), CACHE_FILE_LBBL + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deliver(Callback callback, NewsFeed feed, Throwable error) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(feed, error));
    }
}
