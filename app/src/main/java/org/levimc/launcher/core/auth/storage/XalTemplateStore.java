package org.levimc.launcher.core.auth.storage;

import android.content.Context;
import android.util.Log;

import org.levimc.launcher.util.JsonIOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * XAL 登录状态模板（过渡方案）：
 * 游戏内登录成功后，游戏会把 8 个数字哈希文件名文件写进版本的 internal/xal/。
 * 这套文件是「游戏设备 + 游戏会话签发」的完整登录状态，游戏读取后即可静默登录
 * （等价于实例备份恢复的效果）。
 *
 * 本类负责：
 * 1. capture：把某版本中游戏内登录写出的完整状态复制为模板（存到启动器 files/xal_template/）
 * 2. inject：启动任意版本前，把模板注入该版本的 internal/xal/，实现静默登录
 */
public class XalTemplateStore {
    private static final String TAG = "XalTemplateStore";
    private static final String TEMPLATE_DIR_NAME = "xal_template";
    /** 完整登录状态至少包含的文件数（DeviceIdentity/Default/Msa/User/D/T/ClockSkew/WebViewStateParams 共 8 个）。 */
    private static final int MIN_COMPLETE_FILES = 8;

    /** 设备级文件哈希（与账号无关，任何账号共用）：DeviceIdentity/D/T/ClockSkew/WebViewStateParams。 */
    private static final java.util.Set<String> DEVICE_FILES = new java.util.HashSet<>(java.util.Arrays.asList(
            "16049017696330990596", // Xal.Production.RETAIL.DeviceIdentity
            "14748840670442735584", // Xal.Production.RETAIL.D
            "14791165800392185591", // Xal.1739947436.Production.RETAIL.T
            "8795356459289257587",  // ClockSkew
            "10337803361694514047"  // WebViewStateParams
    ));
    /** Default 文件哈希：指向默认账号，账号切换时必须随 active 账号重写。 */
    private static final String DEFAULT_FILE = "1734634999945796391";

    public static File getTemplateDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), TEMPLATE_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 模板是否存在且完整。 */
    public static boolean hasTemplate(Context ctx) {
        File dir = getTemplateDir(ctx);
        if (!dir.isDirectory()) return false;
        File[] files = dir.listFiles((d, name) -> isNumericFileName(name));
        return files != null && files.length >= MIN_COMPLETE_FILES;
    }

    /**
     * 扫描版本的 internal/xal 目录，若存在完整登录状态（数字文件名 8 个以上），
     * 则复制数字文件为模板（覆盖旧模板）。返回 true 表示模板已更新。
     *
     * 说明：启动器导出会额外留下 Xal.Accounts.json 和 b64 用户子目录，
     * 不影响判定——只要数字文件齐全即可（游戏内登录后会用自己的状态覆盖这些数字文件）。
     * 模板只复制数字文件名文件。
     *
     * 多账号过滤：只保留设备级文件 + Default + Default 指向账号的 Msa/User，
     * 其他账号的 Msa/User（历史残留）不进模板，避免注入时污染目标版本。
     */
    public static boolean capture(Context ctx, File versionInternalDir) {
        try {
            if (versionInternalDir == null) return false;
            File src = new File(versionInternalDir, "xal");
            if (!src.isDirectory()) return false;
            File[] entries = src.listFiles();
            if (entries == null) return false;

            int numericCount = 0;
            for (File e : entries) {
                if (e.isFile() && isNumericFileName(e.getName())) numericCount++;
            }
            if (numericCount < MIN_COMPLETE_FILES) return false;

            // 默认账号（Default 文件指向），模板只保留它的 Msa/User
            String defaultUid = readDefaultUserId(src);

            File templateDir = getTemplateDir(ctx);
            File[] old = templateDir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.isFile()) f.delete();
                }
            }
            int copied = 0;
            for (File e : entries) {
                if (!e.isFile() || !isNumericFileName(e.getName())) continue;
                String name = e.getName();
                if (DEVICE_FILES.contains(name) || DEFAULT_FILE.equals(name)) {
                    copyFile(e, new File(templateDir, name));
                    copied++;
                    continue;
                }
                if (!defaultUid.isEmpty() && isAccountFileOf(name, defaultUid)) {
                    copyFile(e, new File(templateDir, name));
                    copied++;
                }
            }
            Log.i(TAG, "Template captured from " + src.getAbsolutePath() + " (" + copied + " files, default=" + defaultUid + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture template", e);
            return false;
        }
    }

    /** 读 Default 文件（{"default":"<msaUserId>"}）指向的账号，无则返回空串。 */
    private static String readDefaultUserId(File xalDir) {
        try {
            File f = new File(xalDir, DEFAULT_FILE);
            if (!f.isFile()) return "";
            String json = JsonIOUtils.read(f);
            if (json == null || json.isEmpty()) return "";
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return obj.has("default") ? obj.get("default").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 判断数字文件名是否属于某账号的 Msa/User 文件。 */
    private static boolean isAccountFileOf(String numericName, String msaUserId) {
        try {
            String b64 = android.util.Base64.encodeToString(msaUserId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            String msa = Long.toUnsignedString(fnv1_64("Xal.1739947436.Production.Msa." + b64));
            String user = Long.toUnsignedString(fnv1_64("Xal.1739947436.Production.RETAIL.User." + b64));
            return numericName.equals(msa) || numericName.equals(user);
        } catch (Exception e) {
            return false;
        }
    }

    /** FNV-1 64 位哈希（游戏把 XAL key 变成数字文件名）。 */
    private static long fnv1_64(String s) {
        long h = 0xcbf29ce484222325L;
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte x : b) {
            h *= 0x100000001b3L;
            h ^= (x & 0xFFL);
        }
        return h;
    }

    /**
     * 把模板注入目标版本的 internal/xal/（覆盖同名文件）。无模板时不做任何事。
     *
     * 多账号策略：
     * - 启动器有 active 账号时：只注入设备级文件（DeviceIdentity/D/T/ClockSkew/WebViewStateParams），
     *   账号级文件（Default/Msa/User）由启动器导出按 active 账号写入——模板账号不覆盖 active 账号。
     * - 启动器无 active 账号时：全量注入（恢复游戏内登录状态，等价于实例备份恢复）。
     */
    public static void inject(Context ctx, File versionInternalDir) {
        try {
            File templateDir = getTemplateDir(ctx);
            File[] files = templateDir.listFiles((d, name) -> isNumericFileName(name));
            if (files == null || files.length < MIN_COMPLETE_FILES) return;
            if (versionInternalDir == null) return;
            File target = new File(versionInternalDir, "xal");
            if (!target.exists()) target.mkdirs();

            String activeUserId = findActiveUserId(ctx);
            int copied = 0;
            for (File f : files) {
                String name = f.getName();
                if (activeUserId != null) {
                    // 有 active 账号：跳过账号级文件（Default/Msa/User），避免模板账号与 active 账号冲突
                    if (!DEVICE_FILES.contains(name)) continue;
                }
                copyFile(f, new File(target, name));
                copied++;
            }
            // 关键：把 DeviceIdentity 文件的 Id 对齐当前 prefs 里的设备 Id，
            // 否则文件 Id（模板设备）与 prefs 密钥对 Id（当前设备）不一致，
            // 游戏每次启动都会重建设备身份，导致设备令牌失效、登录状态断裂。
            alignDeviceIdentity(ctx, target);
            // WebViewStateParams 里的 msaUserId 必须与当前账号一致，否则游戏弹 Sisu 登录页
            if (activeUserId != null) {
                alignWebViewStateUser(target, activeUserId);
            }
            Log.i(TAG, "Template injected into " + target.getAbsolutePath() + " (" + copied + " files, active=" + activeUserId + ")");
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject template", e);
        }
    }

    /** 启动器注册列表中的 active 账号 msaUserId，无则返回 null。 */
    private static String findActiveUserId(Context ctx) {
        try {
            for (org.levimc.launcher.core.auth.MsftAccountStore.MsftAccount a
                    : org.levimc.launcher.core.auth.MsftAccountStore.list(ctx)) {
                if (a.active && a.msUserId != null && !a.msUserId.isEmpty()) return a.msUserId;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read active account", e);
        }
        return null;
    }

    /** 把注入的 WebViewStateParams 文件 msaUserId 改成当前账号（避免 Sisu 欢迎页弹出）。 */
    private static void alignWebViewStateUser(File xalDir, String msaUserId) {
        try {
            File f = new File(xalDir, "10337803361694514047"); // fnv1("WebViewStateParams")
            if (!f.isFile()) return;
            String json = JsonIOUtils.read(f);
            if (json == null || json.isEmpty()) return;
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("WebViewAdditionalArgs") || !obj.get("WebViewAdditionalArgs").isJsonObject()) return;
            com.google.gson.JsonObject args = obj.getAsJsonObject("WebViewAdditionalArgs");
            String cur = args.has("msaUserId") ? args.get("msaUserId").getAsString() : "";
            if (msaUserId.equals(cur)) return;
            args.addProperty("msaUserId", msaUserId);
            JsonIOUtils.write(f, obj.toString());
            Log.i(TAG, "WebViewStateParams msaUserId aligned to active account");
        } catch (Exception e) {
            Log.w(TAG, "Failed to align WebViewStateParams user", e);
        }
    }

    /** 把注入的 DeviceIdentity 文件 Id 改成 prefs 里的当前设备 Id（保持文件与密钥对一致）。 */
    private static void alignDeviceIdentity(Context ctx, File xalDir) {
        try {
            String prefsId = ctx.getSharedPreferences("com.microsoft.xal.crypto", Context.MODE_PRIVATE)
                    .getString("id", "");
            if (prefsId.isEmpty()) return;
            String targetId = prefsId.startsWith("{") && prefsId.endsWith("}")
                    ? prefsId.substring(1, prefsId.length() - 1) : prefsId;
            File f = new File(xalDir, "16049017696330990596"); // fnv1("Xal.Production.RETAIL.DeviceIdentity")
            if (!f.isFile()) return;
            String json = JsonIOUtils.read(f);
            if (json == null || json.isEmpty()) return;
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String cur = obj.has("Id") ? obj.get("Id").getAsString() : "";
            if (targetId.equals(cur)) return;
            obj.addProperty("Id", "{" + targetId + "}");
            JsonIOUtils.write(f, obj.toString());
            Log.i(TAG, "DeviceIdentity aligned to prefs device: " + targetId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to align device identity", e);
        }
    }

    /** 数字哈希文件名（游戏 XAL 存储的文件名特征；排除 Xal.Accounts.json 等普通名）。 */
    private static boolean isNumericFileName(String name) {
        if (name == null || name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }
}
