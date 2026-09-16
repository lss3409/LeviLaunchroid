package org.levimc.launcher.core.content;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 标点 / 联动虚线数据库（PRD 07.2 schema）。
 * 每个世界独立表记录（按 world_id 过滤），跨维度连线在渲染层做 1:8 换算。
 */
public class BlueprintDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "blueprint.db";
    private static final int DB_VERSION = 1;

    /** 标点分类（决定图标颜色） */
    public static final String CAT_BASE = "base";           // 基地
    public static final String CAT_PORTAL = "portal";       // 传送门
    public static final String CAT_FARM = "farm";           // 刷怪塔
    public static final String CAT_VILLAGE = "village";     // 村庄
    public static final String CAT_STRUCTURE = "structure"; // 结构
    public static final String CAT_CUSTOM = "custom";       // 自定义

    /** 连线类型 */
    public static final String LINK_LOGISTICS = "logistics"; // 物流
    public static final String LINK_REDSTONE = "redstone";   // 红石
    public static final String LINK_RAIL = "rail";           // 铁路
    public static final String LINK_PORTAL = "portal";       // 跨维度门户

    public static class Point {
        public long id;
        public String worldId;
        public String name;
        public int x, y, z;
        public String dimension; // overworld | nether | end
        public String category;
        public String detail;
        public String color;

        public Point() {
            this.dimension = "overworld";
            this.category = CAT_CUSTOM;
            this.color = "#ffd54f";
        }
    }

    public static class Link {
        public long id;
        public String worldId;
        public long fromId;
        public long toId;
        public String type;
        public String color;

        public Link() {
            this.type = LINK_LOGISTICS;
            this.color = "#64b5f6";
        }
    }

    public BlueprintDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "world_id TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL DEFAULT 64," +
                "z INTEGER NOT NULL," +
                "dimension TEXT NOT NULL DEFAULT 'overworld'," +
                "category TEXT NOT NULL DEFAULT 'custom'," +
                "detail TEXT DEFAULT ''," +
                "color TEXT DEFAULT '#ffd54f'," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE links (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "world_id TEXT NOT NULL," +
                "from_id INTEGER NOT NULL," +
                "to_id INTEGER NOT NULL," +
                "type TEXT NOT NULL DEFAULT 'logistics'," +
                "color TEXT NOT NULL DEFAULT '#64b5f6')");
        db.execSQL("CREATE INDEX idx_points_world ON points(world_id)");
        db.execSQL("CREATE INDEX idx_links_world ON links(world_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ---- 标点 ----

    public long addPoint(Point p) {
        ContentValues v = new ContentValues();
        v.put("world_id", p.worldId);
        v.put("name", p.name);
        v.put("x", p.x);
        v.put("y", p.y);
        v.put("z", p.z);
        v.put("dimension", p.dimension);
        v.put("category", p.category);
        v.put("detail", p.detail != null ? p.detail : "");
        v.put("color", p.color != null ? p.color : "#ffd54f");
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("points", null, v);
    }

    public void updatePoint(Point p) {
        ContentValues v = new ContentValues();
        v.put("name", p.name);
        v.put("x", p.x);
        v.put("y", p.y);
        v.put("z", p.z);
        v.put("dimension", p.dimension);
        v.put("category", p.category);
        v.put("detail", p.detail != null ? p.detail : "");
        v.put("color", p.color != null ? p.color : "#ffd54f");
        getWritableDatabase().update("points", v, "id=?", new String[]{String.valueOf(p.id)});
    }

    public void deletePoint(long id) {
        getWritableDatabase().delete("points", "id=?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete("links", "from_id=? OR to_id=?", new String[]{String.valueOf(id), String.valueOf(id)});
    }

    public List<Point> getPoints(String worldId) {
        List<Point> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("points", null, "world_id=?",
                new String[]{worldId}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                Point p = new Point();
                p.id = c.getLong(c.getColumnIndexOrThrow("id"));
                p.worldId = worldId;
                p.name = c.getString(c.getColumnIndexOrThrow("name"));
                p.x = c.getInt(c.getColumnIndexOrThrow("x"));
                p.y = c.getInt(c.getColumnIndexOrThrow("y"));
                p.z = c.getInt(c.getColumnIndexOrThrow("z"));
                p.dimension = c.getString(c.getColumnIndexOrThrow("dimension"));
                p.category = c.getString(c.getColumnIndexOrThrow("category"));
                p.detail = c.getString(c.getColumnIndexOrThrow("detail"));
                p.color = c.getString(c.getColumnIndexOrThrow("color"));
                out.add(p);
            }
        }
        return out;
    }

    // ---- 连线 ----

    public long addLink(Link l) {
        ContentValues v = new ContentValues();
        v.put("world_id", l.worldId);
        v.put("from_id", l.fromId);
        v.put("to_id", l.toId);
        v.put("type", l.type != null ? l.type : LINK_LOGISTICS);
        v.put("color", l.color != null ? l.color : "#64b5f6");
        return getWritableDatabase().insert("links", null, v);
    }

    public void deleteLink(long id) {
        getWritableDatabase().delete("links", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Link> getLinks(String worldId) {
        List<Link> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("links", null, "world_id=?",
                new String[]{worldId}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                Link l = new Link();
                l.id = c.getLong(c.getColumnIndexOrThrow("id"));
                l.worldId = worldId;
                l.fromId = c.getLong(c.getColumnIndexOrThrow("from_id"));
                l.toId = c.getLong(c.getColumnIndexOrThrow("to_id"));
                l.type = c.getString(c.getColumnIndexOrThrow("type"));
                l.color = c.getString(c.getColumnIndexOrThrow("color"));
                out.add(l);
            }
        }
        return out;
    }
}
