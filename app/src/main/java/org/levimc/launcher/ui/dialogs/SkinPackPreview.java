package org.levimc.launcher.ui.dialogs;

import android.content.Context;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.GlobalImportScanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** v0.4.194/197：皮肤包 3D 预览入口——解包 .mcpack（zip）读 skins.json
 *  提取全部皮肤 PNG（v0.4.197：多皮肤支持，底部相册翻页）。
 *  v0.4.199：内容管理的皮肤包是解包目录（ResourcePackItem.file 只认
 *  目录），extractSkins 增加目录分支——直接读目录下 skins.json + PNG。 */
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

    /** 解包皮肤包：skins.json 的 skins[] 全部 texture → 提取 PNG 到缓存。
     *  v0.4.199：支持两种形态——zip（.mcpack 导入扫描）与解包目录
     *  （内容管理已安装的包）。 */
    private static List<SkinEntry> extractSkins(Context context, File packFile) {
        if (packFile == null || !packFile.exists()) {
            return null;
        }
        File cacheDir = new File(context.getCacheDir(), "skin_preview");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            return null;
        }
        ZipFile zip = null;
        try {
            String jsonText;
            if (packFile.isDirectory()) {
                jsonText = readText(new File(packFile, "skins.json"));
            } else {
                zip = new ZipFile(packFile);
                ZipEntry jsonEntry = findSkinsJson(zip);
                jsonText = jsonEntry == null ? null : readZipText(zip, jsonEntry);
            }
            if (jsonText == null) {
                return null;
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
                File outFile = new File(cacheDir, "skin_" + stamp + "_" + i + ".png");
                boolean copied;
                if (zip != null) {
                    copied = copyZipPng(zip, texture, outFile);
                } else {
                    copied = copyFilePng(new File(packFile, texture), outFile);
                }
                if (!copied) {
                    continue;
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
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** zip 内定位 skins.json（根或任意子目录）。 */
    private static ZipEntry findSkinsJson(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String n = e.getName();
            if (n.equals("skins.json") || n.endsWith("/skins.json")) {
                return e;
            }
        }
        return null;
    }

    private static String readZipText(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[(int) Math.min(entry.getSize(), 128 * 1024)];
            int total = 0;
            int n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(File f) {
        if (!f.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 128 * 1024)];
            int total = 0;
            int n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** zip 内提取 texture PNG（找不到精确路径时按文件名尾匹配兜底）。 */
    private static boolean copyZipPng(ZipFile zip, String texture, File outFile) {
        ZipEntry pngEntry = zip.getEntry(texture);
        if (pngEntry == null) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (e.getName().endsWith("/" + texture) || e.getName().equals(texture)) {
                    pngEntry = e;
                    break;
                }
            }
        }
        if (pngEntry == null) {
            return false;
        }
        try (InputStream in = zip.getInputStream(pngEntry);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 目录形态：texture 相对包根（skins.json 同目录），直接拷贝。 */
    private static boolean copyFilePng(File src, File outFile) {
        if (!src.isFile()) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
