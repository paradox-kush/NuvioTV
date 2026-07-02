package com.nuvio.tv.core.iptv.match

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class MatchKind(val slug: String) { MOVIE("movie"), SERIES("series") }

/** One catalog entry as stored in the index. [ext] = container extension (movies only). */
data class IndexedItem(val sid: Int, val name: String, val year: Int?, val tmdb: Int?, val ext: String?)

/** A confirmed (or confirmed-absent when [sid] is null) TMDB->stream mapping. */
data class CachedMapping(val sid: Int?, val matchedName: String?, val updatedAtMs: Long)

data class UnsyncedMapping(val kind: String, val tmdb: Int, val sid: Int?, val matchedName: String?, val updatedAtMs: Long)

/**
 * Disk-backed lookup index per provider+kind: normalized-name keys and bulk-list tmdb ids
 * over the full catalog, plus the cache of verified tmdb->sid mappings (the thing Supabase
 * syncs across devices). Twin of NuvioMobile's XtreamMatchIndex, on framework SQLite.
 *
 * SQLite on purpose, not an in-memory map: a 175k-item catalog costs ~90MB as JVM maps —
 * fatal on 128-256MB TV heaps — vs ~2MB of page cache here, and it survives restarts.
 */
@Singleton
class XtreamMatchIndex @Inject constructor(@ApplicationContext context: Context) {

    private val helper = object : SQLiteOpenHelper(context, "xtream_match.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX items_tmdb ON items(provider, kind, tmdb)")
            db.execSQL("CREATE TABLE keys(provider TEXT NOT NULL, kind TEXT NOT NULL, k TEXT NOT NULL, sid INTEGER NOT NULL, PRIMARY KEY(provider, kind, k, sid)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE tmdb_map(provider TEXT NOT NULL, kind TEXT NOT NULL, tmdb INTEGER NOT NULL, sid INTEGER, matched_name TEXT, updated_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, tmdb)) WITHOUT ROWID")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // index tables are rebuildable caches; mappings re-pull from Supabase
            db.execSQL("DROP TABLE IF EXISTS items"); db.execSQL("DROP TABLE IF EXISTS keys")
            db.execSQL("DROP TABLE IF EXISTS idx_meta"); db.execSQL("DROP TABLE IF EXISTS tmdb_map")
            onCreate(db)
        }
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    suspend fun builtAt(provider: String, kind: MatchKind): Long? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT built_at FROM idx_meta WHERE provider = ? AND kind = ?", arrayOf(provider, kind.slug)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /**
     * Replaces the whole index for one provider+kind. Chunked transactions keep the write
     * lock short; the meta row is written LAST so a crashed rebuild reads as stale.
     */
    suspend fun rebuild(provider: String, kind: MatchKind, items: List<IndexedItem>) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.delete("items", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            db.delete("keys", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            db.delete("idx_meta", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        for (chunk in items.chunked(5_000)) {
            db.beginTransaction()
            try {
                val itemStmt = db.compileStatement("INSERT OR REPLACE INTO items(provider, kind, sid, name, year, tmdb, ext) VALUES(?,?,?,?,?,?,?)")
                val keyStmt = db.compileStatement("INSERT OR REPLACE INTO keys(provider, kind, k, sid) VALUES(?,?,?,?)")
                for (it in chunk) {
                    itemStmt.clearBindings()
                    itemStmt.bindString(1, provider); itemStmt.bindString(2, kind.slug); itemStmt.bindLong(3, it.sid.toLong())
                    itemStmt.bindString(4, it.name)
                    if (it.year != null) itemStmt.bindLong(5, it.year.toLong()) else itemStmt.bindNull(5)
                    if (it.tmdb != null) itemStmt.bindLong(6, it.tmdb.toLong()) else itemStmt.bindNull(6)
                    if (it.ext != null) itemStmt.bindString(7, it.ext) else itemStmt.bindNull(7)
                    itemStmt.executeInsert()
                    for (key in TitleNormalizer.keysOf(it.name)) {
                        keyStmt.clearBindings()
                        keyStmt.bindString(1, provider); keyStmt.bindString(2, kind.slug); keyStmt.bindString(3, key); keyStmt.bindLong(4, it.sid.toLong())
                        keyStmt.executeInsert()
                    }
                }
                itemStmt.close(); keyStmt.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO idx_meta(provider, kind, built_at, item_count) VALUES(?,?,?,?)",
                arrayOf<Any?>(provider, kind.slug, System.currentTimeMillis(), items.size)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** All items indexed under a normalized key. */
    suspend fun probe(provider: String, kind: MatchKind, key: String): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT i.sid, i.name, i.year, i.tmdb, i.ext FROM keys x JOIN items i ON i.provider = x.provider AND i.kind = x.kind AND i.sid = x.sid WHERE x.provider = ? AND x.kind = ? AND x.k = ?",
            arrayOf(provider, kind.slug, key)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        IndexedItem(
                            sid = c.getLong(0).toInt(),
                            name = c.getString(1),
                            year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                            ext = if (c.isNull(4)) null else c.getString(4),
                        )
                    )
                }
            }
        }
    }

    /** Tier-1: items whose bulk-list tmdb id already matches. */
    suspend fun byTmdb(provider: String, kind: MatchKind, tmdb: Int): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext FROM items WHERE provider = ? AND kind = ? AND tmdb = ?",
            arrayOf(provider, kind.slug, tmdb.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        IndexedItem(
                            sid = c.getLong(0).toInt(),
                            name = c.getString(1),
                            year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                            ext = if (c.isNull(4)) null else c.getString(4),
                        )
                    )
                }
            }
        }
    }

    suspend fun item(provider: String, kind: MatchKind, sid: Int): IndexedItem? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext FROM items WHERE provider = ? AND kind = ? AND sid = ?",
            arrayOf(provider, kind.slug, sid.toString())
        ).use { c ->
            if (!c.moveToFirst()) null
            else IndexedItem(
                sid = c.getLong(0).toInt(),
                name = c.getString(1),
                year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                ext = if (c.isNull(4)) null else c.getString(4),
            )
        }
    }

    // --- verified-mapping cache (local mirror of the Supabase iptv_tmdb_map rows) ---

    suspend fun cachedMapping(provider: String, kind: MatchKind, tmdb: Int): CachedMapping? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND kind = ? AND tmdb = ?",
            arrayOf(provider, kind.slug, tmdb.toString())
        ).use { c ->
            if (!c.moveToFirst()) null
            else CachedMapping(
                sid = if (c.isNull(0)) null else c.getLong(0).toInt(),
                matchedName = if (c.isNull(1)) null else c.getString(1),
                updatedAtMs = c.getLong(2),
            )
        }
    }

    suspend fun putMapping(
        provider: String, kind: MatchKind, tmdb: Int, sid: Int?, matchedName: String?,
        synced: Boolean = false, updatedAtMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        db.execSQL(
            "INSERT OR REPLACE INTO tmdb_map(provider, kind, tmdb, sid, matched_name, updated_at, synced) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(provider, kind.slug, tmdb, sid, matchedName, updatedAtMs, if (synced) 1 else 0)
        )
    }

    /** Rows not yet pushed to Supabase. */
    suspend fun unsyncedMappings(provider: String): List<UnsyncedMapping> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT kind, tmdb, sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND synced = 0",
            arrayOf(provider)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        UnsyncedMapping(
                            kind = c.getString(0),
                            tmdb = c.getLong(1).toInt(),
                            sid = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            matchedName = if (c.isNull(3)) null else c.getString(3),
                            updatedAtMs = c.getLong(4),
                        )
                    )
                }
            }
        }
    }

    suspend fun markSynced(provider: String, kind: String, tmdb: Int) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE tmdb_map SET synced = 1 WHERE provider = ? AND kind = ? AND tmdb = ?", arrayOf<Any?>(provider, kind, tmdb))
    }
}
