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

            File templateDir = getTemplateDir(ctx);
            File[] old = templateDir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.isFile()) f.delete();
                }
            }
            int copied = 0;
            for (File e : entries) {
                if (e.isFile() && isNumericFileName(e.getName())) {
                    copyFile(e, new File(templateDir, e.getName()));
                    copied++;
                }
            }
            Log.i(TAG, "Template captured from " + src.getAbsolutePath() + " (" + copied + " files)");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture template", e);
            return false;
        }
    }

    /** 把模板注入目标版本的 internal/xal/（覆盖同名文件）。无模板时不做任何事。 */
    public static void inject(Context ctx, File versionInternalDir) {
        try {
            File templateDir = getTemplateDir(ctx);
            File[] files = templateDir.listFiles((d, name) -> isNumericFileName(name));
            if (files == null || files.length < MIN_COMPLETE_FILES) return;
            if (versionInternalDir == null) return;
            File target = new File(versionInternalDir, "xal");
            if (!target.exists()) target.mkdirs();
            for (File f : files) {
                copyFile(f, new File(target, f.getName()));
            }
            // 关键：把 DeviceIdentity 文件的 Id 对齐当前 prefs 里的设备 Id，
            // 否则文件 Id（模板设备）与 prefs 密钥对 Id（当前设备）不一致，
            // 游戏每次启动都会重建设备身份，导致设备令牌失效、登录状态断裂。
            alignDeviceIdentity(ctx, target);
            Log.i(TAG, "Template injected into " + target.getAbsolutePath() + " (" + files.length + " files)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject template", e);
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
