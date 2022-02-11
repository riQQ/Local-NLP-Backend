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
import android.util.Log
import java.util.HashMap
import java.util.HashSet

/**
 * Created by tfitch on 10/4/17.
 */
/**
 * All access to the database is done through this cache:
 *
 * When a RF emitter is seen a get() call is made to the cache. If we have a cache hit
 * the information is directly returned. If we have a cache miss we create a new record
 * and populate it with either default information or information from the flash based
 * database (if it exists in the database).
 *
 * Periodically we are asked to sync any new or changed RF emitter information to the
 * database. When that occurs we group all the changes in one database transaction for
 * speed.
 *
 * If an emitter has not been used for a while we will remove it from the cache (only
 * immediately after a sync() operation so the record will be clean). If the cache grows
 * too large we will clear it to conservery RAM (this should never happen). Again the
 * clear operation will only occur after a sync() so any dirty records will be flushed
 * to the database.
 *
 * Operations on the cache are thread safe. However the underlying RF emitter objects
 * that are returned by the cache are not thread safe. So all work on them should be
 * performed either in a single thread or with synchronization.
 */
internal class Cache(context: Context?) {
    /**
     * Map (since they all must have different identifications) of
     * all the emitters we are working with.
     */
    private val workingSet: MutableMap<String, RfEmitter> = HashMap()
    private var db: Database? = Database(context)

    /**
     * Release all resources associated with the cache. If the cache is
     * dirty, then it is sync'd to the on flash database.
     */
    fun close() {
        synchronized(this) {
            sync()
            this.clear()
            db!!.close()
            db = null
        }
    }

    /**
     * Queries the cache with the given RfIdentification.
     *
     * If the emitter does not exist in the cache, it is
     * added (from the database if known or a new "unknown"
     * entry is created).
     *
     * @param id
     * @return the emitter
     */
    operator fun get(id: RfIdentification?): RfEmitter? {
        if (id == null)
            return null
        synchronized(this) {
            if (db == null)
                return null
            val key = id.toString()
            var result = workingSet[key]
            if (result == null) {
                result = db!!.getEmitter(id)
                if (result == null)
                    result = RfEmitter(id)
                workingSet[key] = result
                if (DEBUG) Log.d(TAG, "get('$key') - Added to cache.")
            }
            result.resetAge()
            return result
        }
    }

    /**
     * Remove all entries from the cache.
     */
    private fun clear() {
        synchronized(this) {
            workingSet.clear()
            if (DEBUG) Log.d(TAG, "clear() - entry")
        }
    }

    /**
     * Updates the database entry for any new or changed emitters.
     * Once the database has been synchronized, cull infrequently used
     * entries. If our cache is still to big after culling, we reset
     * our cache.
     */
    fun sync() {
        synchronized(this) {
            if (db == null)
                return
            var doSync = false

            // Scan all of our emitters to see
            // 1. If any have dirty data to sync to the flash database
            // 2. If any have been unused long enough to remove from cache
            val agedSet = HashSet<RfIdentification>()
            for ((_, emitter) in workingSet) {
                doSync = doSync or emitter.syncNeeded()

                if (DEBUG) Log.d(TAG, "sync('${emitter.rfIdentification}') - Age: " + emitter.age)
                if (emitter.age >= MAX_AGE)
                    agedSet.add(emitter.rfIdentification)
                emitter.incrementAge()
            }

            if (doSync) {
                db!!.beginTransaction()
                for ((_, emitter) in workingSet) {
                    emitter.sync(db!!)
                }
                db!!.endTransaction()
            }

            // Remove aged out items from cache
            for (id in agedSet) {
                val key = id.toString()
                if (DEBUG) Log.d(TAG, "sync('$key') - Aged out, removed from cache.")
                workingSet.remove(key)
            }

            if (workingSet.size > MAX_WORKING_SET_SIZE) {
                if (DEBUG) Log.d(TAG, "sync() - Working set larger than $MAX_WORKING_SET_SIZE, clearing working set.")
                workingSet.clear()
            }
        }
    }

    fun getEmitters(rfType: EmitterType, bb: BoundingBox): Set<RfIdentification> {
        synchronized(this) {
            return if (db == null)
                emptySet()
            else db!!.getEmitters(rfType, bb)
        }
    }

    companion object {
        private const val MAX_WORKING_SET_SIZE = 200
        private const val MAX_AGE = 30
        private val DEBUG = BuildConfig.DEBUG
        private const val TAG = "DejaVu Cache"
    }

}
