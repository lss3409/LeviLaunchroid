package org.levimc.launcher.core.content;

import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v602：全局导入扫描器——点导入时扫描手机常见目录，找出可导入的
 * 存档（mcworld）/资源包（mcpack）/行为包（mcaddon）/结构文件
 * （mcstructure），支持：
 *   - 直接按扩展名识别 .mcworld/.mcpack/.mcaddon/.mcstructure；
 *   - .zip 压缩包内检视：含 level.dat=存档、manifest.json（modules
 *     type: data=行为包 / resources=资源包）、.mcstructure=结构；
 *   - 已解压的文件夹：同样的完整性检查（有清单文件才认）；
 *   - zip/文件夹内的 pack_icon.png 与 manifest 名称版本一并解析。
 *
 * 深度限制 + 目录白名单，避免全盘遍历失控；Android/data 各应用
 * 下载目录在 MANAGE_EXTERNAL_STORAGE 权限下可读（manifest 已声明）。
 */
public final class GlobalImportScanner {

    public static final int TYPE_WORLD = 0;
    public static final int TYPE_RESOURCE = 1;
    public static final int TYPE_BEHAVIOR = 2;
    public static final int TYPE_STRUCTURE = 3;

    public static class Candidate {
        public int type;
        public String name;
        public String version = "";
        public File file;
        public long size;
        public String path;
        /** pack_icon.png 原始字节（可能为 null，UI 用默认图标）。 */
        public byte[] icon;

        public String typeLabel() {
            switch (type) {
                case TYPE_WORLD:
                    return "存档";
                case TYPE_RESOURCE:
                    return "资源包";
                case TYPE_BEHAVIOR:
                    return "行为包";
                default:
                    return "结构";
            }
        }
    }

    public interface Listener {
        void onProgress(String scanning);

        void onDone(List<Candidate> candidates);
    }

    private GlobalImportScanner() {
    }

    public static void scanAsync(Listener listener) {
        Thread t = new Thread(() -> {
            try {
                List<Candidate> out = scan(listener);
                if (listener != null) {
                    listener.onDone(out);
                }
            } catch (Throwable e) {
                if (listener != null) {
                    listener.onDone(new ArrayList<>());
                }
            }
        }, "global-import-scan");
        t.setDaemon(true);
        t.start();
    }

    private static List<Candidate> scan(Listener listener) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File sd = Environment.getExternalStorageDirectory();
        // v604：全盘扫描（用户明确要求，不设目录白名单）——仅跳过
        // 缓存/缩略图类目录与自己的数据目录，深度上限保护
        if (listener != null) {
            listener.onProgress(sd.getAbsolutePath());
        }
        scanDir(sd, 12, out, seen, listener);
        return out;
    }

    private static void scanDir(File dir, int depth, List<Candidate> out,
                                Set<String> seen, Listener listener) {
        if (depth <= 0 || dir == null || !dir.isDirectory()) {
            return;
        }
        String dn = dir.getName();
        if (dn.equals(".thumbnails") || dn.equals("cache") || dn.equals("Cache")
                || dn.equals(".cache") || dn.equals("tmp") || dn.equals("temp")) {
            return;
        }
        // 跳过启动器自身与游戏目录（里面的包会被误扫）
        String path = dir.getAbsolutePath();
        if (path.contains("org.levimc.launcher") || path.contains("com.mojang")) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        for (File f : files) {
            if (out.size() > 200) {
                return; // 上限保护
            }
            String name = f.getName().toLowerCase(Locale.US);
            try {
                if (f.isDirectory()) {
                    // 已解压的包/存档目录：完整性子目录检查
                    Candidate c = checkFolder(f);
                    if (c != null && seen.add(c.path)) {
                        out.add(c);
                        continue; // 命中后不再深挖其内部
                    }
                    scanDir(f, depth - 1, out, seen, listener);
                } else if (f.isFile()) {
                    if (name.endsWith(".mcworld")) {
                        Candidate c = checkZipWorld(f);
                        if (c != null && seen.add(c.path)) {
                            out.add(c);
                        }
                    } else if (name.endsWith(".mcpack") || name.endsWith(".mcaddon")) {
                        Candidate c = checkZipPack(f);
                        if (c != null && seen.add(c.path)) {
                            out.add(c);
                        }
                    } else if (name.endsWith(".mcstructure")) {
                        Candidate c = new Candidate();
                        c.type = TYPE_STRUCTURE;
                        c.name = f.getName();
                        c.file = f;
                        c.size = f.length();
                        c.path = f.getAbsolutePath();
                        if (seen.add(c.path)) {
                            out.add(c);
                        }
                    } else if (name.endsWith(".zip")) {
                        Candidate c = checkZip(f);
                        if (c != null && seen.add(c.path)) {
                            out.add(c);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 检查文件夹：manifest.json（资源/行为包）、level.dat（存档）、.mcstructure。 */
    private static Candidate checkFolder(File dir) {
        File manifest = new File(dir, "manifest.json");
        File levelDat = new File(dir, "level.dat");
        File levelName = new File(dir, "levelname.txt");
        if (levelDat.isFile()) {
            Candidate c = new Candidate();
            c.type = TYPE_WORLD;
            c.name = readText(levelName, 64).trim();
            if (c.name.isEmpty()) {
                c.name = dir.getName();
            }
            c.file = dir;
            c.size = dirSize(dir);
            c.path = dir.getAbsolutePath();
            File icon = new File(dir, "world_icon.jpeg");
            if (!icon.isFile()) {
                icon = new File(dir, "world_icon.png");
            }
            c.icon = readBytes(icon, 2 * 1024 * 1024);
            return c;
        }
        if (manifest.isFile()) {
            try {
                JSONObject root = new JSONObject(readText(manifest, 256 * 1024));
                JSONObject header = root.optJSONObject("header");
                String name = header != null ? header.optString("name", "") : "";
                String ver = "";
                if (header != null) {
                    Object v = header.opt("version");
                    if (v instanceof JSONObject) {
                        ver = ((JSONObject) v).optString("version", "");
                    }
                }
                String moduleType = "";
                if (root.optJSONArray("modules") != null
                        && root.optJSONArray("modules").length() > 0) {
                    moduleType = root.optJSONArray("modules")
                            .optJSONObject(0).optString("type", "");
                }
                Candidate c = new Candidate();
                if ("data".equals(moduleType)) {
                    c.type = TYPE_BEHAVIOR;
                } else {
                    c.type = TYPE_RESOURCE;
                }
                c.name = name.isEmpty() ? dir.getName() : name;
                c.version = ver;
                c.file = dir;
                c.size = dirSize(dir);
                c.path = dir.getAbsolutePath();
                File icon = new File(dir, "pack_icon.png");
                c.icon = readBytes(icon, 2 * 1024 * 1024);
                return c;
            } catch (Throwable ignored) {
            }
        }
        // 结构文件目录
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File s : subs) {
                if (s.isFile() && s.getName().toLowerCase(Locale.US).endsWith(".mcstructure")) {
                    Candidate c = new Candidate();
                    c.type = TYPE_STRUCTURE;
                    c.name = s.getName();
                    c.file = dir;
                    c.size = dirSize(dir);
                    c.path = dir.getAbsolutePath();
                    return c;
                }
            }
        }
        return null;
    }

    private static Candidate checkZipWorld(File f) {
        Candidate c = new Candidate();
        c.type = TYPE_WORLD;
        c.name = stripExt(f.getName());
        c.file = f;
        c.size = f.length();
        c.path = f.getAbsolutePath();
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry ln = zf.getEntry("levelname.txt");
            if (ln != null) {
                byte[] b = readZipEntry(zf, ln, 64);
                if (b != null) {
                    String n = new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!n.isEmpty()) {
                        c.name = n;
                    }
                }
            }
            ZipEntry icon = findEntry(zf, "world_icon.jpeg", "world_icon.png");
            if (icon != null) {
                c.icon = readZipEntry(zf, icon, 2 * 1024 * 1024);
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static Candidate checkZipPack(File f) {
        Candidate c = new Candidate();
        c.type = f.getName().toLowerCase(Locale.US).endsWith(".mcaddon")
                ? TYPE_BEHAVIOR : TYPE_RESOURCE;
        c.name = stripExt(f.getName());
        c.file = f;
        c.size = f.length();
        c.path = f.getAbsolutePath();
        fillZipPackMeta(f, c);
        return c;
    }

    /** .zip 内检视：level.dat=存档，manifest.json=包，.mcstructure=结构。 */
    private static Candidate checkZip(File f) {
        try (ZipFile zf = new ZipFile(f)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            boolean hasLevelDat = false;
            boolean hasManifest = false;
            boolean hasStructure = false;
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toLowerCase(Locale.US);
                if (n.endsWith("level.dat")) {
                    hasLevelDat = true;
                } else if (n.endsWith("manifest.json")) {
                    hasManifest = true;
                } else if (n.endsWith(".mcstructure")) {
                    hasStructure = true;
                }
            }
            if (hasLevelDat) {
                return checkZipWorld(f);
            }
            if (hasManifest) {
                Candidate c = checkZipPack(f);
                // mcaddon 含 data 模块 → 行为包
                try (ZipFile zf2 = new ZipFile(f)) {
                    ZipEntry mf = findEntry(zf2, "manifest.json");
                    if (mf != null) {
                        byte[] b = readZipEntry(zf2, mf, 256 * 1024);
                        if (b != null) {
                            JSONObject root = new JSONObject(new String(b,
                                    java.nio.charset.StandardCharsets.UTF_8));
                            if (root.optJSONArray("modules") != null
                                    && root.optJSONArray("modules").length() > 0) {
                                String mt = root.optJSONArray("modules")
                                        .optJSONObject(0).optString("type", "");
                                c.type = "data".equals(mt) ? TYPE_BEHAVIOR : TYPE_RESOURCE;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return c;
            }
            if (hasStructure) {
                Candidate c = new Candidate();
                c.type = TYPE_STRUCTURE;
                c.name = stripExt(f.getName());
                c.file = f;
                c.size = f.length();
                c.path = f.getAbsolutePath();
                return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void fillZipPackMeta(File f, Candidate c) {
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry mf = findEntry(zf, "manifest.json");
            if (mf != null) {
                byte[] b = readZipEntry(zf, mf, 256 * 1024);
                if (b != null) {
                    JSONObject root = new JSONObject(new String(b,
                            java.nio.charset.StandardCharsets.UTF_8));
                    JSONObject header = root.optJSONObject("header");
                    if (header != null) {
                        String n = header.optString("name", "");
                        if (!n.isEmpty()) {
                            c.name = n;
                        }
                        Object v = header.opt("version");
                        if (v instanceof JSONObject) {
                            c.version = ((JSONObject) v).optString("version", "");
                        }
                    }
                }
            }
            ZipEntry icon = findEntry(zf, "pack_icon.png");
            if (icon != null) {
                c.icon = readZipEntry(zf, icon, 2 * 1024 * 1024);
            }
        } catch (Throwable ignored) {
        }
    }

    /** zip 内查找条目（跳过一层前缀目录）。 */
    private static ZipEntry findEntry(ZipFile zf, String... names) {
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            int slash = n.indexOf('/');
            String tail = slash >= 0 ? n.substring(slash + 1) : n;
            for (String want : names) {
                if (n.equals(want) || tail.equals(want)) {
                    return e;
                }
            }
        }
        return null;
    }

    private static byte[] readZipEntry(ZipFile zf, ZipEntry e, int max) {
        if (e.getSize() > max) {
            return null;
        }
        try (java.io.InputStream in = zf.getInputStream(e);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > max) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readText(File f, int max) {
        byte[] b = readBytes(f, max);
        if (b == null) {
            return "";
        }
        try {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readBytes(File f, int max) {
        if (f == null || !f.isFile() || f.length() > max) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > max) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            }
        }
        return total;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
