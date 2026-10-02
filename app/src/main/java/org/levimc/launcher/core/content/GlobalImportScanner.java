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
        /** v606：zip 内包含的子包（仅 mcaddon 多包，二级菜单跳转用）。 */
        public List<SubManifest> subManifests;
        /** v610：主清单信息（单包时的清单文件信息，二级菜单显示）。 */
        public SubManifest mainManifest;
        /** v611：皮肤包标记（默认图用启动器的 ic_tshirt）。 */
        public boolean skinPack;
        /** v609：存档 level.dat 解析出的有用信息（二级菜单显示）。 */
        public LevelInfo levelInfo;

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

    /** v609：zip 内子包清单信息（manifest.json 关键字段）。 */
    public static class SubManifest {
        public String name = "";
        public String type = ""; // data=行为包 / resources=资源包
        public String version = "";
        public String description = "";
        /** 依赖的包 uuid 列表（addon 的 dependencies 字段）。 */
        public List<String> dependencies;

        public boolean isBehavior() {
            return "data".equals(type);
        }

        public boolean isSkin() {
            return "skin_pack".equals(type);
        }
    }

    /** v609：存档 level.dat 解析信息（二级菜单显示）。 */
    public static class LevelInfo {
        public String version = "";
        public String seed = "";
        public String gameType = "";
        public String levelName = "";
        public long lastPlayed;
    }

    public interface Listener {
        void onProgress(String scanning);

        /** v647：流式回调——每发现一个候选即通知（扫描工作线程，UI 侧自行 post）。 */
        void onFound(Candidate c);

        void onDone(List<Candidate> candidates);
    }

    private GlobalImportScanner() {
    }

    /** v647：当前监听者（弹窗关闭重开时换绑，扫描线程继续跑不重启）。 */
    private static volatile Listener sListener;
    private static volatile boolean sScanning;
    /** v647：已发现候选数（换绑监听者时新弹窗显示既有进度）。 */
    private static volatile int sLiveCount;

    public static boolean isScanning() {
        return sScanning;
    }

    public static int liveCount() {
        return sLiveCount;
    }

    public static void scanAsync(Listener listener) {
        synchronized (GlobalImportScanner.class) {
            if (sScanning) {
                // v647：已有扫描在跑——直接换绑监听者，新弹窗接管结果流
                sListener = listener;
                return;
            }
            sScanning = true;
            sListener = listener;
        }
        Thread t = new Thread(() -> {
            try {
                List<Candidate> out = scan(sListener);
                Listener l = sListener;
                sScanning = false;
                sListener = null;
                if (l != null) {
                    l.onDone(out);
                }
            } catch (Throwable e) {
                Listener l = sListener;
                sScanning = false;
                sListener = null;
                if (l != null) {
                    l.onDone(new ArrayList<>());
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
        sLiveCount = 0;
        // v604：全盘扫描（用户明确要求，不设目录白名单）——仅跳过
        // 缓存/缩略图类目录与自己的数据目录，深度上限保护
        Listener l = sListener;
        if (l != null) {
            l.onProgress(sd.getAbsolutePath());
        }
        scanDir(sd, 16, out, seen);
        return out;
    }

    /** v647：候选入列 + 流式通知（每次读最新监听者，换绑即生效）。 */
    private static void addFound(List<Candidate> out, Candidate c) {
        out.add(c);
        sLiveCount = out.size();
        Listener l = sListener;
        if (l != null) {
            try {
                l.onFound(c);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void scanDir(File dir, int depth, List<Candidate> out,
                                Set<String> seen) {
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
            if (out.size() > 1500) {
                return; // 上限保护
            }
            String name = f.getName().toLowerCase(Locale.US);
            try {
                if (f.isDirectory()) {
                    // v609：文件夹只认完整 mcworld（五件套齐全），
                    // 不再识别解压的资源包文件夹
                    Candidate c = checkWorldFolder(f);
                    if (c != null && seen.add(c.path)) {
                        addFound(out, c);
                    }
                    scanDir(f, depth - 1, out, seen);
                } else if (f.isFile()) {
                    // v609：跳过自己的备份/包格式；只认 mc 系列后缀，
                    // 不再扫通用 .zip
                    if (name.endsWith(".levibackup") || name.endsWith(".levipack")) {
                        continue;
                    }
                    if (name.endsWith(".mcworld")) {
                        // 内容校验：必须含 level.dat，否则排除
                        Candidate c = checkZipWorld(f);
                        if (c != null && seen.add(c.path)) {
                            addFound(out, c);
                        }
                    } else if (name.endsWith(".mcpack") || name.endsWith(".mcaddon")) {
                        // 内容校验：必须含 manifest.json，否则排除
                        Candidate c = checkZipPack(f);
                        if (c != null && seen.add(c.path)) {
                            addFound(out, c);
                        }
                    } else if (name.endsWith(".mcstructure")) {
                        Candidate c = new Candidate();
                        c.type = TYPE_STRUCTURE;
                        c.name = f.getName();
                        c.file = f;
                        c.size = f.length();
                        c.path = f.getAbsolutePath();
                        if (seen.add(c.path)) {
                            addFound(out, c);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v609：文件夹只认完整 mcworld——五件套必须齐全：
     * db 文件夹 + level.dat + level.dat_old + levelname.txt + world_icon.jpeg。
     */
    private static Candidate checkWorldFolder(File dir) {
        File levelDat = new File(dir, "level.dat");
        File levelDatOld = new File(dir, "level.dat_old");
        File levelName = new File(dir, "levelname.txt");
        File worldIcon = new File(dir, "world_icon.jpeg");
        File db = new File(dir, "db");
        if (!levelDat.isFile() || !levelDatOld.isFile() || !levelName.isFile()
                || !worldIcon.isFile() || !db.isDirectory()) {
            return null;
        }
        Candidate c = new Candidate();
        c.type = TYPE_WORLD;
        c.name = readText(levelName, 64).trim();
        if (c.name.isEmpty()) {
            c.name = dir.getName();
        }
        c.file = dir;
        c.size = dirSize(dir);
        c.path = dir.getAbsolutePath();
        c.icon = readBytes(worldIcon, 2 * 1024 * 1024);
        c.levelInfo = parseLevelDat(readBytes(levelDat, 16 * 1024 * 1024));
        return c;
    }

    private static Candidate checkZipWorld(File f) {
        Candidate c = new Candidate();
        c.type = TYPE_WORLD;
        c.name = stripExt(f.getName());
        c.file = f;
        c.size = f.length();
        c.path = f.getAbsolutePath();
        boolean hasLevelDat = false;
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
            // v609：内容校验——必须含 level.dat（基岩存档），否则排除
            ZipEntry ld = findEntry(zf, "level.dat");
            if (ld != null) {
                hasLevelDat = true;
                byte[] ldBytes = readZipEntry(zf, ld, 16 * 1024 * 1024);
                c.levelInfo = parseLevelDat(ldBytes);
            }
        } catch (Throwable ignored) {
        }
        if (!hasLevelDat) {
            return null;
        }
        return c;
    }

    private static Candidate checkZipPack(File f) {
        // v609：内容校验——manifest.json 缺失的排除（防止伪装后缀）
        Candidate c = new Candidate();
        boolean isAddon = f.getName().toLowerCase(Locale.US).endsWith(".mcaddon");
        c.type = isAddon ? TYPE_BEHAVIOR : TYPE_RESOURCE;
        c.name = stripExt(f.getName());
        c.file = f;
        c.size = f.length();
        c.path = f.getAbsolutePath();
        // v610：bp/rp 子包收集是 mcaddon 独有——mcpack 只读单清单
        if (!fillZipPackMeta(f, c, isAddon)) {
            return null;
        }
        return c;
    }

    /**
     * v609：解析包 zip 内 manifest。
     * v610：仅 mcaddon 收集多包（bp/rp 子包）；mcpack 只取单个清单
     * （资源包 zip 里多 manifest 是嵌套依赖目录，不是子包）。
     * 返回是否至少含一个 manifest（缺失即伪包排除）。
     */
    private static boolean fillZipPackMeta(File f, Candidate c, boolean collectSubs) {
        try (ZipFile zf = new ZipFile(f)) {
            List<SubManifest> subs = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith("manifest.json")) {
                    continue;
                }
                byte[] b = readZipEntry(zf, e, 256 * 1024);
                if (b == null) {
                    continue;
                }
                try {
                    JSONObject root = new JSONObject(new String(b,
                            java.nio.charset.StandardCharsets.UTF_8));
                    JSONObject header = root.optJSONObject("header");
                    SubManifest sm = new SubManifest();
                    sm.name = header != null ? header.optString("name", "") : "";
                    Object v = header != null ? header.opt("version") : null;
                    if (v instanceof JSONObject) {
                        sm.version = ((JSONObject) v).optString("version", "");
                    }
                    sm.description = header != null ? header.optString("description", "") : "";
                    if (root.optJSONArray("modules") != null
                            && root.optJSONArray("modules").length() > 0) {
                        sm.type = root.optJSONArray("modules")
                                .optJSONObject(0).optString("type", "");
                    }
                    // v609：依赖包 uuid 收集（addon 的 dependencies 字段）
                    if (root.optJSONArray("dependencies") != null) {
                        sm.dependencies = new ArrayList<>();
                        for (int i = 0; i < root.optJSONArray("dependencies").length(); i++) {
                            JSONObject dep = root.optJSONArray("dependencies").optJSONObject(i);
                            if (dep != null && dep.has("uuid")) {
                                sm.dependencies.add(dep.optString("uuid"));
                            }
                        }
                    }
                    if (!sm.name.isEmpty() || sm.type != null) {
                        subs.add(sm);
                    }
                    // v610：mcpack 只取第一个清单
                    if (!collectSubs) {
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (subs.isEmpty()) {
                return false;
            }
            SubManifest first = subs.get(0);
            // v610：多包才保留子包列表（单清单不显示子包分区）；
            // 主清单信息始终保存给二级菜单
            c.subManifests = subs.size() > 1 ? subs : null;
            c.mainManifest = first;
            c.skinPack = first.isSkin();
            if (c.name.isEmpty() || c.name.equals(stripExt(f.getName()))) {
                c.name = first.name.isEmpty() ? stripExt(f.getName()) : first.name;
            }
            if (c.version.isEmpty()) {
                c.version = first.version;
            }
            if (first.isBehavior()) {
                c.type = TYPE_BEHAVIOR;
            }
            ZipEntry icon = findEntry(zf, "pack_icon.png");
            if (icon != null) {
                c.icon = readZipEntry(zf, icon, 2 * 1024 * 1024);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** v648：从原文件重新提取图标（持久化缓存恢复用，后台线程调用）。
     *  世界→world_icon.jpeg；包→zip/folder 内 pack_icon.png；结构→null。 */
    public static byte[] extractIcon(File f, int type) {
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            if (type == TYPE_WORLD) {
                return f.isDirectory() ? readBytes(new File(f, "world_icon.jpeg"), 2 * 1024 * 1024)
                        : readIconFromZip(f);
            }
            if (type == TYPE_RESOURCE || type == TYPE_BEHAVIOR) {
                return f.isDirectory() ? readBytes(new File(f, "pack_icon.png"), 2 * 1024 * 1024)
                        : readIconFromZip(f);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] readIconFromZip(File f) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
            ZipEntry icon = findEntry(zf, "pack_icon.png");
            if (icon != null) {
                return readZipEntry(zf, icon, 2 * 1024 * 1024);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** v609：解析存档 level.dat（NBT）——版本/种子/模式/最后游玩时间。 */
    private static LevelInfo parseLevelDat(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }
        try {
            org.levimc.launcher.core.content.nbt.BedrockNbtReader reader =
                    new org.levimc.launcher.core.content.nbt.BedrockNbtReader();
            org.levimc.launcher.core.content.nbt.NbtTag root = reader.readFromBytes(data);
            if (root == null) {
                return null;
            }
            LevelInfo info = new LevelInfo();
            org.levimc.launcher.core.content.nbt.NbtTag ln = root.getTag("LevelName");
            if (ln != null) {
                info.levelName = ln.getString();
            }
            // lastOpenedWithVersion: int 数组 [major, minor, patch, revision]
            org.levimc.launcher.core.content.nbt.NbtTag lv = root.getTag("lastOpenedWithVersion");
            if (lv != null) {
                StringBuilder sb = new StringBuilder();
                if (lv.getType() == org.levimc.launcher.core.content.nbt.NbtTag.TAG_INT_ARRAY) {
                    int[] arr = lv.getIntArray();
                    for (int x : arr) {
                        if (sb.length() > 0) {
                            sb.append('.');
                        }
                        sb.append(x);
                    }
                } else if (lv.getType() == org.levimc.launcher.core.content.nbt.NbtTag.TAG_LIST) {
                    for (org.levimc.launcher.core.content.nbt.NbtTag o : lv.getList()) {
                        if (sb.length() > 0) {
                            sb.append('.');
                        }
                        sb.append(o.getValue());
                    }
                }
                info.version = sb.toString();
            }
            org.levimc.launcher.core.content.nbt.NbtTag seed = root.getTag("RandomSeed");
            if (seed != null && seed.getValue() != null) {
                info.seed = String.valueOf(seed.getValue());
            }
            org.levimc.launcher.core.content.nbt.NbtTag gt = root.getTag("GameType");
            if (gt != null && gt.getValue() != null) {
                info.gameType = String.valueOf(gt.getValue());
            }
            org.levimc.launcher.core.content.nbt.NbtTag lp = root.getTag("LastPlayed");
            if (lp != null && lp.getValue() instanceof Number) {
                info.lastPlayed = ((Number) lp.getValue()).longValue();
            }
            return info;
        } catch (Throwable ignored) {
            return null;
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
