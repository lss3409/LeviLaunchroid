package org.levimc.launcher.core.auth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v709：Bedrock 玩家皮肤服务——登录态（XSTS token）→ 微软官方
 * Minecraft Services profile API 拉皮肤纹理 → 合成正面人形渲染图。
 * 设备直连 api.minecraftservices.com/textures.minecraft.net 被墙
 * （实测超时），全部请求走腾讯云 VPS 转发代理（19090 端口）。
 * 结果磁盘缓存 files/skins/&lt;xuid&gt;.png（7 天），失败静默回退。
 */
public final class SkinService {

    private static final String TAG = "SkinService";
    /** VPS 转发代理（见 VPS ~/skin-proxy/skin_proxy.py）。 */
    private static final String PROXY = "http://111.230.150.198:19090";
    private static final String AUTH_KEY = "levimc-skin-2026";
    private static final long CACHE_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "skin-loader");
                t.setDaemon(true);
                return t;
            });

    public interface Callback {
        void onSkin(Bitmap bmp);
    }

    private SkinService() {
    }

    /** 异步拉取玩家皮肤渲染图（xuid 为空或失败回调 null）。 */
    public static void loadSkin(Context ctx, String xuid, Callback cb) {
        if (xuid == null || xuid.isEmpty()) {
            main.post(() -> cb.onSkin(null));
            return;
        }
        File cache = skinFile(ctx, xuid);
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS) {
            Bitmap bmp = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (bmp != null) {
                main.post(() -> cb.onSkin(bmp));
                return;
            }
        }
        final Context app = ctx.getApplicationContext();
        pool.execute(() -> {
            Bitmap bmp = fetchSkin(app, xuid);
            main.post(() -> cb.onSkin(bmp));
        });
    }

    private static Bitmap fetchSkin(Context app, String xuid) {
        try {
            // 1. 恢复登录态（顺带刷新 token 链，XSTS 过期也能拉起）
            java.util.List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(app);
            MsftAccountStore.MsftAccount account = null;
            for (MsftAccountStore.MsftAccount a : list) {
                if (a.active) {
                    account = a;
                    break;
                }
            }
            if (account == null) {
                return null;
            }
            net.raphimc.minecraftauth.bedrock.BedrockAuthManager authManager =
                    MsftAuthManager.refreshAndAuth(account);
            net.raphimc.minecraftauth.xbl.model.XblXstsToken xsts =
                    authManager.getBedrockXstsToken().getUpToDateUnchecked();
            if (xsts == null) {
                return null;
            }
            // 直接就是 "XBL3.0 x=<token>;<uhs>" 格式
            String identityToken = xsts.getAuthorizationHeader();

            // 2. login_with_xbox 换 Minecraft Services access token（VPS 转发）
            JSONObject loginBody = new JSONObject();
            loginBody.put("identityToken", identityToken);
            JSONObject loginResp = postJson("/mc/authentication/login_with_xbox",
                    loginBody.toString(), null);
            if (loginResp == null) {
                return null;
            }
            String accessToken = loginResp.optString("access_token", "");
            if (accessToken.isEmpty()) {
                return null;
            }

            // 3. 按 XUID 查 profile（自己/成员通用），取 ACTIVE 皮肤纹理 URL
            JSONObject profile = getJson("/mc/minecraft/profile/lookup/xuid/" + xuid,
                    "Bearer " + accessToken);
            if (profile == null) {
                return null;
            }
            String skinUrl = null;
            JSONArray skins = profile.optJSONArray("skins");
            if (skins != null) {
                for (int i = 0; i < skins.length(); i++) {
                    JSONObject sk = skins.optJSONObject(i);
                    if (sk == null || !"ACTIVE".equals(sk.optString("state", ""))) {
                        continue;
                    }
                    String url = sk.optString("url", "");
                    if (!url.isEmpty()) {
                        skinUrl = url;
                        break;
                    }
                }
            }
            if (skinUrl == null) {
                return null;
            }

            // 4. 下载皮肤纹理（VPS 转发 textures.minecraft.net）
            byte[] tex = getBytes("/tex/" + skinUrl.substring("https://textures.minecraft.net/".length()));
            if (tex == null || tex.length == 0) {
                return null;
            }
            Bitmap raw = BitmapFactory.decodeByteArray(tex, 0, tex.length);
            if (raw == null) {
                return null;
            }

            // 5. 合成正面人形渲染图 + 磁盘缓存
            Bitmap avatar = composeFrontAvatar(raw);
            if (avatar == null) {
                return null;
            }
            saveCache(app, xuid, avatar);
            return avatar;
        } catch (Throwable t) {
            Log.w(TAG, "皮肤拉取失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
            return null;
        }
    }

    /** Bedrock 64×64 皮肤纹理 → 正面人形渲染图（头/身/双臂/双腿拼装）。 */
    private static Bitmap composeFrontAvatar(Bitmap tex) {
        try {
            if (tex.getWidth() < 64 || tex.getHeight() < 32) {
                return null;
            }
            final int scale = 8;
            final int W = 16 * scale;   // 头8 + 臂4+4
            final int H = 32 * scale;   // 头8 + 身12 + 腿12
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setFilterBitmap(false);
            // head (8,8)-(16,16)
            drawRegion(c, tex, 8, 8, 8, 8, 4 * scale, 0, scale, p);
            // body (20,20)-(28,32)
            drawRegion(c, tex, 20, 20, 8, 12, 4 * scale, 8 * scale, scale, p);
            // right arm front (44,20)-(48,32) → 屏幕左
            drawRegion(c, tex, 44, 20, 4, 12, 0, 8 * scale, scale, p);
            // left arm front (36,52)-(40,64) → 屏幕右
            drawRegion(c, tex, 36, 52, 4, 12, 12 * scale, 8 * scale, scale, p);
            // right leg front (4,20)-(8,32)
            drawRegion(c, tex, 4, 20, 4, 12, 4 * scale, 20 * scale, scale, p);
            // left leg front (20,52)-(24,64)
            drawRegion(c, tex, 20, 52, 4, 12, 8 * scale, 20 * scale, scale, p);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void drawRegion(Canvas c, Bitmap src, int sx, int sy, int w, int h,
                                   int dx, int dy, int scale, android.graphics.Paint p) {
        android.graphics.Rect s = new android.graphics.Rect(sx, sy, sx + w, sy + h);
        android.graphics.Rect d = new android.graphics.Rect(dx, dy, dx + w * scale, dy + h * scale);
        c.drawBitmap(src, s, d, p);
    }

    // ---- VPS 转发 HTTP 工具 ----

    private static JSONObject postJson(String path, String body, String auth) {
        try {
            HttpURLConnection conn = open(path);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (auth != null) {
                conn.setRequestProperty("Authorization", auth);
            }
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                return null;
            }
            String resp = readAll(is);
            conn.disconnect();
            if (code >= 400) {
                Log.w(TAG, "POST " + path + " → " + code);
                return null;
            }
            return new JSONObject(resp);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject getJson(String path, String auth) {
        try {
            HttpURLConnection conn = open(path);
            if (auth != null) {
                conn.setRequestProperty("Authorization", auth);
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                return null;
            }
            String resp = readAll(is);
            conn.disconnect();
            if (code >= 400) {
                Log.w(TAG, "GET " + path + " → " + code);
                return null;
            }
            return new JSONObject(resp);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] getBytes(String path) {
        try {
            HttpURLConnection conn = open(path);
            int code = conn.getResponseCode();
            if (code >= 400) {
                conn.disconnect();
                return null;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            conn.disconnect();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static HttpURLConnection open(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(PROXY + path).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("X-Auth-Key", AUTH_KEY);
        conn.setRequestProperty("Accept", "application/json,image/png,*/*");
        return conn;
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    private static File skinFile(Context ctx, String xuid) {
        File dir = new File(ctx.getFilesDir(), "skins");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return new File(dir, xuid + ".png");
    }

    private static void saveCache(Context ctx, String xuid, Bitmap bmp) {
        try {
            FileOutputStream fos = new FileOutputStream(skinFile(ctx, xuid));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}
