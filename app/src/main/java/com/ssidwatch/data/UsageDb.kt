package com.ssidwatch.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Traffic(val rx: Long, val tx: Long) {
    val total: Long get() = rx + tx
    operator fun plus(o: Traffic) = Traffic(rx + o.rx, tx + o.tx)

    companion object {
        val ZERO = Traffic(0, 0)
    }
}

/**
 * Local database. Days are ISO strings (yyyy-MM-dd, local time) so they sort correctly.
 *
 *  usage    – bytes per day / SSID / app (uid)
 *  hourly   – bytes per day / hour / SSID (for the day chart)
 *  baseline – last cumulative counters read from Android, per uid
 *  meta     – small key/value store (anchor time, current SSID, ...)
 */
class UsageDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "usage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE usage(day TEXT NOT NULL, ssid TEXT NOT NULL, uid INTEGER NOT NULL, " +
                "rx INTEGER NOT NULL DEFAULT 0, tx INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, ssid, uid))"
        )
        db.execSQL(
            "CREATE TABLE hourly(day TEXT NOT NULL, hour INTEGER NOT NULL, ssid TEXT NOT NULL, " +
                "rx INTEGER NOT NULL DEFAULT 0, tx INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, hour, ssid))"
        )
        db.execSQL("CREATE TABLE baseline(uid INTEGER PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- meta ---------------------------------------------------------------

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        val db = writableDatabase
        if (value == null) {
            db.delete("meta", "k=?", arrayOf(key))
        } else {
            val cv = ContentValues()
            cv.put("k", key)
            cv.put("v", value)
            db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // ---- baselines ----------------------------------------------------------

    fun baselines(): Map<Int, Traffic> {
        val m = HashMap<Int, Traffic>()
        readableDatabase.rawQuery("SELECT uid, rx, tx FROM baseline", null).use { c ->
            while (c.moveToNext()) m[c.getInt(0)] = Traffic(c.getLong(1), c.getLong(2))
        }
        return m
    }

    fun replaceBaselines(map: Map<Int, Traffic>) {
        val db = writableDatabase
        db.delete("baseline", null, null)
        val st = db.compileStatement("INSERT INTO baseline(uid, rx, tx) VALUES(?,?,?)")
        for ((uid, t) in map) {
            st.clearBindings()
            st.bindLong(1, uid.toLong())
            st.bindLong(2, t.rx)
            st.bindLong(3, t.tx)
            st.executeInsert()
        }
        st.close()
    }

    // ---- writes (UPDATE-then-INSERT so it works on old SQLite without UPSERT) --

    fun addUsage(day: String, ssid: String, uid: Int, rx: Long, tx: Long) {
        val db = writableDatabase
        val up = db.compileStatement("UPDATE usage SET rx=rx+?, tx=tx+? WHERE day=? AND ssid=? AND uid=?")
        up.bindLong(1, rx)
        up.bindLong(2, tx)
        up.bindString(3, day)
        up.bindString(4, ssid)
        up.bindLong(5, uid.toLong())
        val n = up.executeUpdateDelete()
        up.close()
        if (n == 0) {
            val cv = ContentValues()
            cv.put("day", day)
            cv.put("ssid", ssid)
            cv.put("uid", uid)
            cv.put("rx", rx)
            cv.put("tx", tx)
            db.insert("usage", null, cv)
        }
    }

    fun addHourly(day: String, hour: Int, ssid: String, rx: Long, tx: Long) {
        val db = writableDatabase
        val up = db.compileStatement("UPDATE hourly SET rx=rx+?, tx=tx+? WHERE day=? AND hour=? AND ssid=?")
        up.bindLong(1, rx)
        up.bindLong(2, tx)
        up.bindString(3, day)
        up.bindLong(4, hour.toLong())
        up.bindString(5, ssid)
        val n = up.executeUpdateDelete()
        up.close()
        if (n == 0) {
            val cv = ContentValues()
            cv.put("day", day)
            cv.put("hour", hour)
            cv.put("ssid", ssid)
            cv.put("rx", rx)
            cv.put("tx", tx)
            db.insert("hourly", null, cv)
        }
    }

    fun deleteNetworkBefore(ssid: String, beforeDay: String) {
        val db = writableDatabase
        db.delete("usage", "ssid=? AND day<?", arrayOf(ssid, beforeDay))
        db.delete("hourly", "ssid=? AND day<?", arrayOf(ssid, beforeDay))
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete("usage", null, null)
        db.delete("hourly", null, null)
        db.delete("baseline", null, null)
        db.delete("meta", null, null)
    }

    // ---- reads --------------------------------------------------------------

    private fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun where(from: String, to: String, ssid: String?): Pair<String, Array<String>> =
        if (ssid == null) "day BETWEEN ? AND ?" to arrayOf(from, to)
        else "day BETWEEN ? AND ? AND ssid=?" to arrayOf(from, to, ssid)

    /** Usage per network (SSID) between two days inclusive, biggest first. */
    fun ssidTotals(from: String, to: String): List<Pair<String, Traffic>> =
        query(
            "SELECT ssid, SUM(rx), SUM(tx) FROM usage WHERE day BETWEEN ? AND ? " +
                "GROUP BY ssid ORDER BY SUM(rx)+SUM(tx) DESC",
            arrayOf(from, to)
        ) { c -> c.getString(0) to Traffic(c.getLong(1), c.getLong(2)) }

    /** Usage per app between two days, optionally on one SSID only, biggest first. */
    fun appTotals(from: String, to: String, ssid: String?): List<Pair<Int, Traffic>> {
        val (w, a) = where(from, to, ssid)
        return query(
            "SELECT uid, SUM(rx), SUM(tx) FROM usage WHERE $w GROUP BY uid " +
                "HAVING SUM(rx)+SUM(tx) > 0 ORDER BY SUM(rx)+SUM(tx) DESC",
            a
        ) { c -> c.getInt(0) to Traffic(c.getLong(1), c.getLong(2)) }
    }

    /** For one app: how its usage splits across networks. */
    fun appSsids(from: String, to: String, uid: Int): List<Pair<String, Traffic>> =
        query(
            "SELECT ssid, SUM(rx), SUM(tx) FROM usage WHERE day BETWEEN ? AND ? AND uid=? " +
                "GROUP BY ssid ORDER BY SUM(rx)+SUM(tx) DESC",
            arrayOf(from, to, uid.toString())
        ) { c -> c.getString(0) to Traffic(c.getLong(1), c.getLong(2)) }

    fun dailyTotals(from: String, to: String, ssid: String?): Map<String, Traffic> {
        val (w, a) = where(from, to, ssid)
        return query("SELECT day, SUM(rx), SUM(tx) FROM usage WHERE $w GROUP BY day", a) { c ->
            c.getString(0) to Traffic(c.getLong(1), c.getLong(2))
        }.toMap()
    }

    fun hourly(day: String, ssid: String?): Array<Traffic> {
        val out = Array(24) { Traffic.ZERO }
        val (w, a) = where(day, day, ssid)
        query("SELECT hour, SUM(rx), SUM(tx) FROM hourly WHERE $w GROUP BY hour", a) { c ->
            Triple(c.getInt(0), c.getLong(1), c.getLong(2))
        }.forEach { (h, rx, tx) -> if (h in 0..23) out[h] = Traffic(rx, tx) }
        return out
    }

    fun total(from: String, to: String, ssid: String?): Traffic {
        val (w, a) = where(from, to, ssid)
        return query("SELECT IFNULL(SUM(rx),0), IFNULL(SUM(tx),0) FROM usage WHERE $w", a) { c ->
            Traffic(c.getLong(0), c.getLong(1))
        }.firstOrNull() ?: Traffic.ZERO
    }

    fun firstDay(): String? =
        readableDatabase.rawQuery("SELECT MIN(day) FROM usage", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    companion object {
        @Volatile
        private var instance: UsageDb? = null

        fun get(ctx: Context): UsageDb =
            instance ?: synchronized(this) {
                instance ?: UsageDb(ctx.applicationContext).also { instance = it }
            }
    }
}
