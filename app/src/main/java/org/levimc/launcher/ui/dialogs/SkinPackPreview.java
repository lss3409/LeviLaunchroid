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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** v0.4.194/197：皮肤包 3D 预览入口——解包 .mcpack（zip）读 skins.json
 *  提取全部皮肤 PNG（v0.4.197：多皮肤支持，底部相册翻页）。 */
public final class SkinPackPreview {

    /** 单个皮肤条目。 */
    public static class SkinEntry {
        public final String name;
        public final File png;

        SkinEntry(String name, File png) {
            this.name = name;
            this.png = png;
        }
    }

    public static void open(Context context, GlobalImportScanner.Candidate c) {
        open(context, c.file, c.name);
    }

    /** v0.4.198：内容管理三点菜单入口（ResourcePackItem 无 Candidate）。 */
    public static void open(Context context, File packFile, String name) {
        List<SkinEntry> skins = extractSkins(context, packFile);
        if (skins == null || skins.isEmpty()) {
            Toast.makeText(context, R.string.skin_preview_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        SkinPreviewDialog.show(context, skins, 0, name);
    }

    /** 解包皮肤包：skins.json 的 skins[] 全部 texture → 提取 PNG 到缓存。 */
    private static List<SkinEntry> extractSkins(Context context, File packFile) {
        if (packFile == null || !packFile.isFile()) {
            return null;
        }
        File cacheDir = new File(context.getCacheDir(), "skin_preview");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            return null;
        }
        try (ZipFile zip = new ZipFile(packFile)) {
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
                byte[] buf = new byte[(int) Math.min(jsonEntry.getSize(), 128 * 1024)];
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
            List<SkinEntry> out = new ArrayList<>();
            long stamp = System.currentTimeMillis();
            for (int i = 0; i < skins.length(); i++) {
                JSONObject s = skins.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String texture = s.optString("texture", null);
                if (texture == null || texture.isEmpty()) {
                    continue;
                }
                ZipEntry pngEntry = zip.getEntry(texture);
                if (pngEntry == null) {
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
                    continue;
                }
                File outFile = new File(cacheDir, "skin_" + stamp + "_" + i + ".png");
                try (InputStream in = zip.getInputStream(pngEntry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                String name = s.optString("localization_name", null);
                if (name == null || name.isEmpty()) {
                    name = texture.replaceAll(".*/", "").replaceAll("\\.png$", "");
                }
                out.add(new SkinEntry(name, outFile));
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
