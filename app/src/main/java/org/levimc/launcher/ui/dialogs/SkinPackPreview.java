package org.levimc.launcher.ui.dialogs;

import android.content.Context;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** v0.4.194：皮肤包 3D 预览入口——解包 .mcpack（zip）读 skins.json
 *  取首个皮肤 texture（PNG），提取到缓存临时文件后打开
 *  SkinPreviewDialog（WebView + skinview3d；失败降级 2D）。 */
public final class SkinPackPreview {

    public static void open(Context context, GlobalImportScanner.Candidate c) {
        File png = extractFirstSkin(context, c.file);
        if (png == null) {
            Toast.makeText(context, R.string.skin_preview_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        SkinPreviewDialog.show(context, png, c.name);
    }

    /** 解包皮肤包：skins.json 的 skins[0].texture → 提取 PNG 到缓存。 */
    private static File extractFirstSkin(Context context, File packFile) {
        if (packFile == null || !packFile.isFile()) {
            return null;
        }
        File cacheDir = new File(context.getCacheDir(), "skin_preview");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            return null;
        }
        try (ZipFile zip = new ZipFile(packFile)) {
            // 1) 读 skins.json
            ZipEntry jsonEntry = null;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.equals("skins.json") || n.endsWith("/skins.json")) {
                    jsonEntry = e;
                    break;
                }
            }
            if (jsonEntry == null) {
                return null;
            }
            String jsonText;
            try (InputStream in = zip.getInputStream(jsonEntry)) {
                byte[] buf = new byte[(int) Math.min(jsonEntry.getSize(), 64 * 1024)];
                int total = 0;
                int n;
                while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                    total += n;
                }
                jsonText = new String(buf, 0, total, java.nio.charset.StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(jsonText);
            JSONArray skins = root.optJSONArray("skins");
            if (skins == null || skins.length() == 0) {
                return null;
            }
            String texture = skins.optJSONObject(0).optString("texture", null);
            if (texture == null || texture.isEmpty()) {
                return null;
            }
            // 2) 提取 PNG（texture 可能是相对路径）
            ZipEntry pngEntry = zip.getEntry(texture);
            if (pngEntry == null) {
                // 带目录前缀重试
                java.util.Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    if (e.getName().endsWith("/" + texture) || e.getName().equals(texture)) {
                        pngEntry = e;
                        break;
                    }
                }
            }
            if (pngEntry == null) {
                return null;
            }
            File out = new File(cacheDir, "skin_" + System.currentTimeMillis() + ".png");
            try (InputStream in = zip.getInputStream(pngEntry);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
