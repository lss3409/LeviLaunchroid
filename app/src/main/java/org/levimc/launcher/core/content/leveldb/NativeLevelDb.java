package org.levimc.launcher.core.content.leveldb;

import android.util.Log;

import com.litl.leveldb.DB;
import com.litl.leveldb.Iterator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * BTR 同款原生 LevelDB 读取（libleveldbjni.so，照搬自 Blocktopograph）。
 * 原生库自带 MCPE 全部压缩格式（含 type 4 raw deflate），读不到时由
 * 调用方回退纯 Java LevelDBReader。
 */
public final class NativeLevelDb {

    private static final String TAG = "NativeLevelDb";
    private static volatile boolean loadFailed = false;

    private NativeLevelDb() {
    }

    /** 原生库是否可用（进程内缓存检测结果）。 */
    public static boolean isAvailable() {
        if (loadFailed) {
            return false;
        }
        try {
            System.loadLibrary("leveldbjni");
            return true;
        } catch (Throwable t) {
            loadFailed = true;
            Log.w(TAG, "leveldbjni 加载失败，回退纯 Java 解析", t);
            return false;
        }
    }

    /** 遍历 db 全部条目（原生库）。失败返回 null 由调用方回退。 */
    public static List<LevelDBEntry> readAllEntries(File dbDir) {
        if (!isAvailable() || dbDir == null || !dbDir.isDirectory()) {
            return null;
        }
        DB db = new DB(dbDir);
        try {
            db.open();
            List<LevelDBEntry> entries = new ArrayList<>();
            Iterator it = db.iterator();
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] key = it.getKey();
                byte[] value = it.getValue();
                if (key != null && value != null) {
                    entries.add(new LevelDBEntry(key, value));
                }
            }
            it.close();
            Log.d(TAG, "原生读取条目数: " + entries.size());
            return entries;
        } catch (Throwable t) {
            Log.w(TAG, "原生读取失败: " + dbDir.getAbsolutePath(), t);
            return null;
        } finally {
            try {
                db.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
