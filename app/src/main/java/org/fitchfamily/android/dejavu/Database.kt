package org.fitchfamily.android.dejavu

/*
*    DejaVu - A location provider backend for microG/UnifiedNlp
*
*    Copyright (C) 2017 Tod Fitch
*
*    This program is Free Software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as
*    published by the Free Software Foundation, either version 3 of the
*    License, or (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log
import java.util.HashSet

/**
 *
 * Created by tfitch on 9/1/17.
 */
/**
 * Interface to our on flash SQL database. Note that these methods are not
 * thread safe. However all access to the database is through the Cache object
 * which is thread safe.
 */
class Database(context: Context?) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {
    private var database: SQLiteDatabase? = null
    private var withinTransaction = false
    private var updatesMade = false
    private var sqlSampleInsert: SQLiteStatement? = null
    private var sqlSampleUpdate: SQLiteStatement? = null
    private var sqlAPdrop: SQLiteStatement? = null

    inner class EmitterInfo() {
        var latitude = 0.0
        var longitude = 0.0
        var radius_ns = 0f
        var radius_ew = 0f
        var trust: Int = 0
        var note: String = ""
    }

    override fun onCreate(db: SQLiteDatabase) {
        database = db
        withinTransaction = false
        // Always create version 1 of database, then update the schema
        // in the same order it might occur "in the wild". Avoids having
        // to check to see if the table exists (may be old version)
        // or not (can be new version).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SAMPLES (
                $COL_RFID STRING PRIMARY KEY,
                $COL_TYPE STRING,
                $COL_TRUST INTEGER,
                $COL_LAT REAL,
                $COL_LON REAL,
                $COL_RAD REAL,
                $COL_NOTE STRING
            );
        """.trimIndent()
        )
        onUpgrade(db, 1, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upGradeToVersion2(db)
        if (oldVersion < 3) upGradeToVersion3(db)
        if (oldVersion < 4) upGradeToVersion4(db)
    }

    private fun upGradeToVersion2(db: SQLiteDatabase) {
        if (DEBUG) Log.d(TAG, "upGradeToVersion2(): Entry")
        // Sqlite3 does not support dropping columns so we create a new table with our
        // current fields and copy the old data into it.
        with(db) {
            execSQL("BEGIN TRANSACTION;")
            execSQL("ALTER TABLE " + TABLE_SAMPLES + " RENAME TO " + TABLE_SAMPLES + "_old;")
            execSQL(
                ("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "(" +
                        COL_RFID + " STRING PRIMARY KEY, " +
                        COL_TYPE + " STRING, " +
                        COL_TRUST + " INTEGER, " +
                        COL_LAT + " REAL, " +
                        COL_LON + " REAL, " +
                        COL_RAD_NS + " REAL, " +
                        COL_RAD_EW + " REAL, " +
                        COL_NOTE + " STRING);")
            )
            execSQL(
                ("INSERT INTO " + TABLE_SAMPLES + "(" +
                        COL_RFID + ", " +
                        COL_TYPE + ", " +
                        COL_TRUST + ", " +
                        COL_LAT + ", " +
                        COL_LON + ", " +
                        COL_RAD_NS + ", " +
                        COL_RAD_EW + ", " +
                        COL_NOTE +
                        ") SELECT " +
                        COL_RFID + ", " +
                        COL_TYPE + ", " +
                        COL_TRUST + ", " +
                        COL_LAT + ", " +
                        COL_LON + ", " +
                        COL_RAD + ", " +
                        COL_RAD + ", " +
                        COL_NOTE +
                        " FROM " + TABLE_SAMPLES + "_old;")
            )
            execSQL("DROP TABLE " + TABLE_SAMPLES + "_old;")
            execSQL("COMMIT;")
        }
    }

    private fun upGradeToVersion3(db: SQLiteDatabase) {
        if (DEBUG) Log.d(TAG, "upGradeToVersion3(): Entry")

        // We are changing our key field to a new text field that contains a hash of
        // of the ID and type. In addition, we are dealing with a Lint complaint about
        // using a string field where we ought to be using a text field.
        db.execSQL("BEGIN TRANSACTION;")
        db.execSQL(
            ("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "_new (" +
                    COL_HASH + " TEXT PRIMARY KEY, " +
                    COL_RFID + " TEXT, " +
                    COL_TYPE + " TEXT, " +
                    COL_TRUST + " INTEGER, " +
                    COL_LAT + " REAL, " +
                    COL_LON + " REAL, " +
                    COL_RAD_NS + " REAL, " +
                    COL_RAD_EW + " REAL, " +
                    COL_NOTE + " TEXT);")
        )
        val insert = db.compileStatement(
            ("INSERT INTO " +
                    TABLE_SAMPLES + "_new(" +
                    COL_HASH + ", " +
                    COL_RFID + ", " +
                    COL_TYPE + ", " +
                    COL_TRUST + ", " +
                    COL_LAT + ", " +
                    COL_LON + ", " +
                    COL_RAD_NS + ", " +
                    COL_RAD_EW + ", " +
                    COL_NOTE + ") " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")
        )
        val query = ("SELECT " +
                COL_RFID + "," + COL_TYPE + "," + COL_TRUST + "," + COL_LAT + "," + COL_LON + "," + COL_RAD_NS + "," + COL_RAD_EW + "," + COL_NOTE + " " +
                "FROM " + TABLE_SAMPLES + ";")
        val cursor = db.rawQuery(query, null)
        try {
            if (cursor!!.moveToFirst()) {
                do {
                    val rfId = cursor.getString(0)
                    var rftype = cursor.getString(1)
                    if ((rftype == "WLAN")) rftype = EmitterType.WLAN_24GHZ.toString()
                    val rfid = RfIdentification(rfId, typeOf(rftype))
                    val hash = rfid.uniqueId

                    // Log.d(TAG,"upGradeToVersion2(): Updating '"+rfId.toString()+"'");
                    insert.bindString(1, hash)
                    insert.bindString(2, rfId)
                    insert.bindString(3, rftype)
                    insert.bindString(4, cursor.getString(2))
                    insert.bindString(5, cursor.getString(3))
                    insert.bindString(6, cursor.getString(4))
                    insert.bindString(7, cursor.getString(5))
                    insert.bindString(8, cursor.getString(6))
                    insert.bindString(9, cursor.getString(7))
                    insert.executeInsert()
                    insert.clearBindings()
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }
        db.execSQL("DROP TABLE $TABLE_SAMPLES;")
        db.execSQL("ALTER TABLE ${TABLE_SAMPLES}_new RENAME TO $TABLE_SAMPLES;")
        db.execSQL("COMMIT;")
    }

    private fun upGradeToVersion4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX $SPATIAL_INDEX_SAMPLES ON $TABLE_SAMPLES ($COL_LAT,$COL_LON);"
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
    }

    /**
     * Start an update operation.
     *
     * We make sure we are not already in a transaction, make sure
     * our database is writeable, compile the insert, update and drop
     * statements that are likely to be used, etc. Then we actually
     * start the transaction on the underlying SQL database.
     */
    fun beginTransaction() {
        if (withinTransaction) {
            if (DEBUG) Log.d(TAG, "beginTransaction() - Already in a transaction?")
            return
        }
        withinTransaction = true
        updatesMade = false
        database = writableDatabase
        sqlSampleInsert = database!!.compileStatement(
            ("INSERT INTO " +
                    TABLE_SAMPLES + "(" +
                    COL_HASH + ", " +
                    COL_RFID + ", " +
                    COL_TYPE + ", " +
                    COL_TRUST + ", " +
                    COL_LAT + ", " +
                    COL_LON + ", " +
                    COL_RAD_NS + ", " +
                    COL_RAD_EW + ", " +
                    COL_NOTE + ") " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")
        )
        sqlSampleUpdate = database!!.compileStatement(
            ("UPDATE " +
                    TABLE_SAMPLES + " SET " +
                    COL_TRUST + "=?, " +
                    COL_LAT + "=?, " +
                    COL_LON + "=?, " +
                    COL_RAD_NS + "=?, " +
                    COL_RAD_EW + "=?, " +
                    COL_NOTE + "=? " +
                    "WHERE " + COL_HASH + "=?;")
        )
        sqlAPdrop = database!!.compileStatement(
            ("DELETE FROM " +
                    TABLE_SAMPLES +
                    " WHERE " + COL_HASH + "=?;")
        )
        database!!.beginTransaction()
    }

    /**
     * End a transaction. If we actually made any changes then we mark
     * the transaction as successful. Once marked as successful we
     * end the transaction with the underlying SQL database.
     */
    fun endTransaction() {
        if (!withinTransaction) {
            if (DEBUG) Log.d(TAG, "Asked to end transaction but we are not in one???")
        }
        if (updatesMade) {
            //Log.d(TAG,"endTransaction() - Setting transaction successful.");
            database!!.setTransactionSuccessful()
        }
        updatesMade = false
        database!!.endTransaction()
        withinTransaction = false
    }

    /**
     * Drop an RF emitter from the database.
     *
     * @param emitter The emitter to be dropped.
     */
    fun drop(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Dropping " + emitter.logString + " from db")
        sqlAPdrop!!.bindString(1, emitter.uniqueId)
        sqlAPdrop!!.executeInsert()
        sqlAPdrop!!.clearBindings()
        updatesMade = true
    }

    /**
     * Insert a new RF emitter into the database.
     *
     * @param emitter The emitter to be added.
     */
    fun insert(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Inserting " + emitter.logString + " into db")
        sqlSampleInsert!!.bindString(1, emitter.uniqueId)
        sqlSampleInsert!!.bindString(2, emitter.id)
        sqlSampleInsert!!.bindString(3, emitter.type.toString())
        sqlSampleInsert!!.bindString(4, emitter.trust.toString())
        sqlSampleInsert!!.bindString(5, emitter.lat.toString())
        sqlSampleInsert!!.bindString(6, emitter.lon.toString())
        sqlSampleInsert!!.bindString(7, emitter.radiusNS.toString())
        sqlSampleInsert!!.bindString(8, emitter.radiusEW.toString())
        sqlSampleInsert!!.bindString(9, emitter.note)
        sqlSampleInsert!!.executeInsert()
        sqlSampleInsert!!.clearBindings()
        updatesMade = true
    }

    /**
     * Update information about an emitter already existing in the database
     *
     * @param emitter The emitter to be updated
     */
    fun update(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Updating " + emitter.logString + " in db")

        // the data fields
        sqlSampleUpdate!!.bindString(1, emitter.trust.toString())
        sqlSampleUpdate!!.bindString(2, emitter.lat.toString())
        sqlSampleUpdate!!.bindString(3, emitter.lon.toString())
        sqlSampleUpdate!!.bindString(4, emitter.radiusNS.toString())
        sqlSampleUpdate!!.bindString(5, emitter.radiusEW.toString())
        sqlSampleUpdate!!.bindString(6, emitter.note)

        // the Where fields
        sqlSampleUpdate!!.bindString(7, emitter.uniqueId)
        sqlSampleUpdate!!.executeInsert()
        sqlSampleUpdate!!.clearBindings()
        updatesMade = true
    }

    /**
     * Return a list of all emitters of a specified type within a bounding box.
     *
     * @param rfType The type of emitter the caller is interested in
     * @param bb The lat,lon bounding box.
     * @return A collection of RF emitter identifications
     */
    fun getEmitters(rfType: EmitterType, bb: BoundingBox): HashSet<RfIdentification> {
        val rslt = HashSet<RfIdentification>()
        val query = ("SELECT " +
                COL_RFID + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_TYPE + "='" + rfType +
                "' AND " + COL_LAT + ">='" + bb.south +
                "' AND " + COL_LAT + "<='" + bb.north +
                "' AND " + COL_LON + ">='" + bb.west +
                "' AND " + COL_LON + "<='" + bb.east + "';")

        //Log.d(TAG, "getEmitters(): query='"+query+"'");
        val cursor = readableDatabase.rawQuery(query, null)
        try {
            if (cursor!!.moveToFirst()) {
                do {
                    val e = RfIdentification(cursor.getString(0), rfType)
                    rslt.add(e)
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }
        return rslt
    }

    // get multple emimtters in a single query
    fun getEmitters(ids: Collection<RfIdentification>): List<RfEmitter> {
        val idString = ids.map { "'${it.uniqueId}'" }.joinToString(",")
        val query = ("SELECT " +
                COL_TYPE + ", " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD_NS + ", " +
                COL_RAD_EW + ", " +
                COL_NOTE + ", " +
                COL_RFID + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_HASH + " IN (" + idString + ");")

        // Log.d(TAG, "getEmitter(): query='"+query+"'");
        val c = readableDatabase.rawQuery(query, null)
        val emitters = mutableListOf<RfEmitter>()
        c.use { cursor ->
            if (cursor!!.moveToFirst()) {
                do {
                    val result = RfEmitter(EmitterType.valueOf(cursor.getString(0)), cursor.getString(7))
                    val ei = EmitterInfo()
                    ei.trust = cursor.getInt(1)
                    ei.latitude = cursor.getDouble(2)
                    ei.longitude = cursor.getDouble(3)
                    ei.radius_ns = cursor.getDouble(4).toFloat()
                    ei.radius_ew = cursor.getDouble(5).toFloat()
                    ei.note = cursor.getString(6) ?: ""
                    result.updateInfo(ei)
                    emitters.add(result)
                } while (cursor.moveToNext())
            }
        }
        return emitters

    }

    /**
     * Get all the information we have on an RF emitter
     *
     * @param ident The identification of the emitter caller wants
     * @return A emitter object with all the information we have. Or null if we have nothing.
     */
    fun getEmitter(ident: RfIdentification): RfEmitter? {
        var result: RfEmitter? = null
        val query = ("SELECT " +
                COL_TYPE + ", " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD_NS + ", " +
                COL_RAD_EW + ", " +
                COL_NOTE + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_HASH + "='" + ident.uniqueId + "';")

        // Log.d(TAG, "getEmitter(): query='"+query+"'");
        val cursor = readableDatabase.rawQuery(query, null)
        try {
            if (cursor!!.moveToFirst()) {
                result = RfEmitter(ident)
                val ei = EmitterInfo()
                ei.trust = cursor.getInt(1)
                ei.latitude = cursor.getDouble(2)
                ei.longitude = cursor.getDouble(3)
                ei.radius_ns = cursor.getDouble(4).toFloat()
                ei.radius_ew = cursor.getDouble(5).toFloat()
                ei.note = cursor.getString(6) ?: ""
                result.updateInfo(ei)
            }
        } finally {
            cursor?.close()
        }
        return result
    }

    companion object {
        private const val TAG = "DejaVu DB"
        private val DEBUG = BuildConfig.DEBUG
        private const val VERSION = 4
        private const val NAME = "rf.db"
        private const val TABLE_SAMPLES = "emitters"
        private const val SPATIAL_INDEX_SAMPLES = "emitters_index"
        private const val COL_HASH = "rfHash" // v3 of database
        private const val COL_TYPE = "rfType"
        private const val COL_RFID = "rfID"
        private const val COL_TRUST = "trust"
        private const val COL_LAT = "latitude"
        private const val COL_LON = "longitude"
        private const val COL_RAD = "radius" // v1 of database
        private const val COL_RAD_NS = "radius_ns" // v2 of database
        private const val COL_RAD_EW = "radius_ew" // v2 of database
        private const val COL_NOTE = "note"
    }
}
