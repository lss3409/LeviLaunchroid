package org.levimc.launcher.core.auth.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.util.JsonIOUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class XalExporter {
    private static final String TAG = "XalExporter";
    private static final Gson GSON = new Gson();

    public static void exportActiveAccount(Context ctx) {
        // 登录流程：写入启动器自身 files/xal，并管理设备密钥 SharedPreferences
        exportActiveAccountTo(ctx, ctx.getApplicationContext().getFilesDir(), true);
    }

    public static void exportActiveAccountToFiles(Context ctx, File targetFilesDir) {
        // 启动流程：仅把 XAL 文件写入目标版本 files/xal，供游戏读取
        if (targetFilesDir == null) return;
        exportActiveAccountTo(ctx, targetFilesDir, false);
    }

    private static void exportActiveAccountTo(Context ctx, File targetFilesDir, boolean manageSharedPrefs) {
        try {
            MsftAccountStore.MsftAccount active = null;
            for (MsftAccountStore.MsftAccount acc : MsftAccountStore.list(ctx)) {
                if (acc.active) {
                    active = acc;
                    break;
                }
            }

            if (active == null || active.serializedAuthManager == null) {
                // 仅登录流程（登出/切换账号，manageSharedPrefs=true）才清理 XAL 数据。
                // 启动流程（进游戏，manageSharedPrefs=false）必须保留 targetFilesDir 下的 xal，
                // 否则会误删游戏内 XAL 库登录写入的账号数据，导致「退回启动器后掉线」。
                if (manageSharedPrefs) {
                    Log.i(TAG, "exportActiveAccount: no active account, clearing " + targetFilesDir.getAbsolutePath());
                    clearXalData(targetFilesDir, ctx);
                }
                return;
            }

            Log.i(TAG, "exportActiveAccount: exporting active account " + active.msUserId
                    + " to " + targetFilesDir.getAbsolutePath() + " managePrefs=" + manageSharedPrefs);
            JsonObject authJson = JsonParser.parseString(active.serializedAuthManager).getAsJsonObject();
            export(ctx, targetFilesDir, authJson, active.msUserId, active.xboxGamertag, active.xuid);
            // 同时把账号索引（Xal.Accounts.json）复制到目标 xal 目录：
            // 游戏侧 Java takeover（Storage.getStoragePath）依赖它定位活跃账号。
            copyAccountsIndex(ctx, targetFilesDir);

        } catch (Exception e) {
            Log.e(TAG, "Failed to export XAL data", e);
        }
    }

    /** 把启动器账号索引 Xal.Accounts.json 复制到目标 filesDir/xal 下。 */
    private static void copyAccountsIndex(Context ctx, File targetFilesDir) {
        try {
            File source = new File(new File(ctx.getApplicationContext().getFilesDir(), "xal"), "Xal.Accounts.json");
            if (!source.isFile()) return;
            File target = new File(new File(targetFilesDir, "xal"), "Xal.Accounts.json");
            // 源与目标相同时直接跳过：否则 FileOutputStream 会把文件截断成空，
            // 导致刚保存的账号立即丢失（登录提示成功但账号消失的元凶）。
            if (source.getCanonicalPath().equals(target.getCanonicalPath())) return;
            File parent = target.getParentFile();
            if (parent == null) return;
            if (!parent.exists()) parent.mkdirs();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(source);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to copy accounts index", e);
        }
    }

    private static void clearXalData(File targetFilesDir, Context sharedPrefsCtx) {
        File xalDir = new File(targetFilesDir, "xal");
        deleteDirectory(xalDir);
        if (sharedPrefsCtx != null) {
            SharedPreferences.Editor edit = sharedPrefsCtx.getSharedPreferences("org.levimc.xal.crypto", Context.MODE_PRIVATE).edit();
            edit.clear();
            edit.apply();
        }
    }

    private static boolean deleteDirectory(File dir) {
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

    public static void export(Context ctx, File targetFilesDir, JsonObject authJson, String msaUserId, String gamertag, String xuid) {
        File root = new File(targetFilesDir, "xal");
        if (!root.exists()) root.mkdirs();

        String b64User = Base64.encodeToString(msaUserId.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        File userDir = new File(root, b64User);
        if (!userDir.exists()) userDir.mkdirs();

        String tid = "1739947436";

        // DeviceIdentity.json
        if (authJson.has("deviceId") && !authJson.get("deviceId").isJsonNull()) {
            String deviceId = authJson.get("deviceId").getAsString();
            JsonObject di = new JsonObject();
            di.addProperty("Id", "{" + deviceId + "}");
            di.addProperty("Key", "Serialized to SharedPreferences");
            File diFile = new File(userDir, "Xal.Production.RETAIL.DeviceIdentity.json");
            JsonIOUtils.write(diFile, GSON.toJson(di));
        }

        // Default.json
        JsonObject def = new JsonObject();
        def.addProperty("default", msaUserId);
        File defFile = new File(userDir, "Xal." + tid + ".Production.Default.json");
        JsonIOUtils.write(defFile, GSON.toJson(def));

        // Msa.json
        if (authJson.has("msaToken")) {
            JsonObject msaJson = authJson.getAsJsonObject("msaToken");
            JsonObject rootMsa = new JsonObject();
            rootMsa.addProperty("user_id", msaUserId);
            rootMsa.addProperty("refresh_token", msaJson.has("refreshToken") && !msaJson.get("refreshToken").isJsonNull() ? msaJson.get("refreshToken").getAsString() : "");
            rootMsa.addProperty("foci", "");

            JsonObject at = new JsonObject();
            String tokenVal = msaJson.has("accessToken") ? msaJson.get("accessToken").getAsString() : "";
            at.addProperty("access_token", tokenVal);
            long expireTimeMs = msaJson.has("expireTimeMs") ? msaJson.get("expireTimeMs").getAsLong() : System.currentTimeMillis();
            at.addProperty("xal_expires", formatDate(expireTimeMs));
            at.addProperty("scopes", "service::user.auth.xboxlive.com::mbi_ssl");

            JsonArray ats = new JsonArray();
            ats.add(at);
            rootMsa.add("access_tokens", ats);
            File msaFile = new File(userDir, "Xal." + tid + ".Production.Msa." + b64User + ".json");
            JsonIOUtils.write(msaFile, GSON.toJson(rootMsa));
        }

        // User.json (User + XSTS tokens)
        JsonObject uRoot = new JsonObject();
        uRoot.addProperty("deviceId", "{" + (authJson.has("deviceId") ? authJson.get("deviceId").getAsString() : "") + "}");
        JsonArray tokens = new JsonArray();

        // XSTS tokens
        if (authJson.has("xboxLiveXstsToken")) tokens.add(buildXstsEnvelope("Xtoken", "http://xboxlive.com", msaUserId, gamertag, xuid, authJson.getAsJsonObject("xboxLiveXstsToken")));
        if (authJson.has("playFabXstsToken")) tokens.add(buildXstsEnvelope("Xtoken", "https://b980a380.minecraft.playfabapi.com/", msaUserId, gamertag, xuid, authJson.getAsJsonObject("playFabXstsToken")));
        if (authJson.has("realmsXstsToken")) tokens.add(buildXstsEnvelope("Xtoken", "https://pocket.realms.minecraft.net/", msaUserId, gamertag, xuid, authJson.getAsJsonObject("realmsXstsToken")));

        // User token
        if (authJson.has("xblUserToken")) tokens.add(buildXstsEnvelope("Utoken", "http://auth.xboxlive.com", msaUserId, gamertag, xuid, authJson.getAsJsonObject("xblUserToken")));

        uRoot.add("tokens", tokens);
        File uFile = new File(userDir, "Xal." + tid + ".Production.RETAIL.User." + b64User + ".json");
        JsonIOUtils.write(uFile, GSON.toJson(uRoot));

        // Dtoken（设备令牌）：Xal.Production.RETAIL.D.json —— 缺失会导致游戏判定未完成登录
        if (authJson.has("xblDeviceToken") && authJson.get("xblDeviceToken").isJsonObject()) {
            JsonObject dRoot = new JsonObject();
            dRoot.addProperty("deviceId", "{" + (authJson.has("deviceId") ? authJson.get("deviceId").getAsString() : "") + "}");
            dRoot.add("token", buildDeviceTitleEnvelope("Dtoken", authJson.getAsJsonObject("xblDeviceToken")));
            JsonIOUtils.write(new File(userDir, "Xal.Production.RETAIL.D.json"), GSON.toJson(dRoot));
        }

        // Ttoken（标题令牌）：Xal.<tid>.Production.RETAIL.T.json
        if (authJson.has("xblTitleToken") && authJson.get("xblTitleToken").isJsonObject()) {
            JsonObject tRoot = new JsonObject();
            tRoot.addProperty("deviceId", "{" + (authJson.has("deviceId") ? authJson.get("deviceId").getAsString() : "") + "}");
            JsonArray tTokens = new JsonArray();
            tTokens.add(buildDeviceTitleEnvelope("Ttoken", authJson.getAsJsonObject("xblTitleToken")));
            tRoot.add("tokens", tTokens);
            JsonIOUtils.write(new File(userDir, "Xal." + tid + ".Production.RETAIL.T.json"), GSON.toJson(tRoot));
        }

        // FOCI 账号列表：Xal.Production.Msa.Foci.1.json
        JsonObject fociRoot = new JsonObject();
        JsonArray fociAccounts = new JsonArray();
        JsonObject fociAccount = new JsonObject();
        fociAccount.addProperty("userId", msaUserId);
        fociAccount.add("foci", new JsonArray());
        fociAccounts.add(fociAccount);
        fociRoot.add("accounts", fociAccounts);
        JsonIOUtils.write(new File(userDir, "Xal.Production.Msa.Foci.1.json"), GSON.toJson(fociRoot));

        // Write KeyPair to SharedPreferences（两个 prefs 名都写：
        // 游戏 Ecdsa 读 com.microsoft.xal.crypto，补丁版读 org.levimc.xal.crypto；
        // 游戏在启动器进程内运行时两者是同一个 prefs 目录，预置后设备身份命中，
        // 游戏不会生成新设备，也就不会弹 WelcomeBack 登录窗）
        if (authJson.has("deviceKeyPair")) {
            JsonObject kp = authJson.getAsJsonObject("deviceKeyPair");
            if (kp.has("publicKey") && kp.has("privateKey")) {
                String pubB64 = kp.get("publicKey").getAsString();
                String privB64 = kp.get("privateKey").getAsString();
                String deviceId = authJson.has("deviceId") ? authJson.get("deviceId").getAsString() : "";

                for (String prefsName : new String[]{"com.microsoft.xal.crypto", "org.levimc.xal.crypto"}) {
                    try {
                        SharedPreferences.Editor edit = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit();
                        edit.putString("id", "{" + deviceId + "}");
                        try {
                            byte[] pubBytes = android.util.Base64.decode(pubB64, android.util.Base64.DEFAULT);
                            byte[] privBytes = android.util.Base64.decode(privB64, android.util.Base64.DEFAULT);
                            edit.putString("public", Base64.encodeToString(pubBytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE));
                            edit.putString("private", Base64.encodeToString(privBytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE));
                        } catch (Exception e) {
                            edit.putString("public", pubB64);
                            edit.putString("private", privB64);
                        }
                        edit.apply();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to write keypair prefs " + prefsName, e);
                    }
                }
            }
        }

        // ==== 游戏原生存储格式（数字哈希文件名）====
        // 游戏自己的 storage handler 把 XAL key 用 FNV-1 64 位哈希后作为文件名，
        // 直接写入 <filesDir>/xal/ 下（无 .json 后缀、无用户子目录）。
        // 实例备份/恢复能保留登录，靠的就是这些数字文件；
        // 启动器必须按同样的格式写入，游戏才会认为已登录。
        // v184 风格：不写数字文件/WebViewStateParams/ClockSkew（这些是 v185+ 的干预，
        // 让游戏走它自己的「引导窗 → Xbox 立即游玩」登录流程，步骤最少）。
        // 游戏登录后自己写出的完整状态由 XalTemplateStore 捕获并传播到其他版本。
        // writeGameNativeFormat(root, authJson, msaUserId, tid, b64User, gamertag, xuid);
    }



    /** 设备/标题令牌的信封（Dtoken/Ttoken，无 MsaUserId、无展示声明）。 */
    private static JsonObject buildDeviceTitleEnvelope(String identityType, JsonObject tokenObj) {
        long expireTimeMs = tokenObj.has("expireTimeMs") ? tokenObj.get("expireTimeMs").getAsLong() : System.currentTimeMillis();
        String tokenStr = tokenObj.has("token") ? tokenObj.get("token").getAsString() : "";

        JsonObject data = new JsonObject();
        data.addProperty("Token", tokenStr);
        data.addProperty("NotAfter", formatDate(expireTimeMs));
        data.addProperty("IssueInstant", formatDate(expireTimeMs - 8 * 60 * 60 * 1000L));
        data.addProperty("ClientAttested", false);
        data.add("DisplayClaims", new JsonObject());

        JsonObject env = new JsonObject();
        env.addProperty("HasSignInDisplayClaims", false);
        env.addProperty("IdentityType", identityType);
        env.addProperty("Environment", "Production");
        env.addProperty("Sandbox", "RETAIL");
        env.addProperty("TokenType", "JWT");
        env.addProperty("RelyingParty", "http://auth.xboxlive.com");
        env.addProperty("SubRelyingParty", "");
        env.add("TokenData", data);
        return env;
    }

    private static JsonObject buildXstsEnvelope(String identityType, String relyingParty, String msaUserId, String gamertag, String xuid, JsonObject tokenObj) {
        long expireTimeMs = tokenObj.has("expireTimeMs") ? tokenObj.get("expireTimeMs").getAsLong() : System.currentTimeMillis();
        String tokenStr = tokenObj.has("token") ? tokenObj.get("token").getAsString() : "";
        String uhs = tokenObj.has("userHash") ? tokenObj.get("userHash").getAsString() : "";

        JsonObject xui = new JsonObject();
        xui.addProperty("uhs", uhs);
        xui.addProperty("gtg", gamertag);
        xui.addProperty("mgt", "");
        xui.addProperty("mgs", "");
        xui.addProperty("umg", "");
        xui.addProperty("xid", xuid);
        xui.addProperty("agg", "");
        xui.addProperty("prv", "");
        xui.addProperty("usr", "");
        xui.addProperty("uer", "");
        xui.addProperty("utr", "");

        JsonArray xuiArr = new JsonArray();
        xuiArr.add(xui);

        JsonObject displayClaims = new JsonObject();
        displayClaims.add("xui", xuiArr);

        JsonObject data = new JsonObject();
        data.addProperty("Token", tokenStr);
        data.addProperty("NotAfter", formatDate(expireTimeMs));
        data.addProperty("IssueInstant", formatDate(expireTimeMs - 8 * 60 * 60 * 1000L)); // Approx 8 hours before expiry
        data.addProperty("ClientAttested", false);
        data.add("DisplayClaims", displayClaims);

        JsonObject env = new JsonObject();
        env.addProperty("MsaUserId", msaUserId);
        env.addProperty("HasSignInDisplayClaims", true);
        env.addProperty("IdentityType", identityType);
        env.addProperty("Environment", "Production");
        env.addProperty("Sandbox", "RETAIL");
        env.addProperty("TokenType", "JWT");
        env.addProperty("RelyingParty", relyingParty);
        env.addProperty("SubRelyingParty", "");
        env.add("TokenData", data);

        return env;
    }

    private static String formatDate(long millis) {
        // 与游戏 XAL 写入的格式一致：7 位小数秒（100ns 精度），如 2026-09-11T08:03:50.8007980Z
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }
}
