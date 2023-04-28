package org.fitchfamily.android.dejavu

/*
*    Local NLP Backend / DejaVu - A location provider backend for microG/UnifiedNlp
*
*    Copyright (C) 2017 Tod Fitch
*    Copyright (C) 2022 Helium314
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

/**
 * Created by tfitch on 10/4/17.
 * modified by helium314 in 2022
 */
/**
 * All access to the database, except for import/export, is done through this cache:
 *
 * When a RF emitter is seen a get() call is made to the cache. If we have a cache hit
 * the information is directly returned. If we have a cache miss we create a new record
 * and populate it with either default information.
 * Emitters are not loaded from database when using get(), they need to be loaded first
 * using loadIds(), which channels all the emitters to load into a single db query
 *
 * Periodically we are asked to sync any new or changed RF emitter information to the
 * database. When that occurs we group all the changes in one database transaction for
 * speed.
 *
 * If an emitter has not been used for a while we will remove it from the cache (only
 * immediately after a sync() operation so the record will be clean). If the cache grows
 * too large we will clear it to conserve RAM (this should never happen). Again the
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
    private val workingSet = hashMapOf<String, RfEmitter>()
    private var db: Database? = Database.instance ?: Database(context)

    /**
     * Release all resources associated with the cache. If the cache is
     * dirty, then it is synced to the on flash database.
     */
    fun close() {
        synchronized(this) {
            sync()
            this.clear()
            db?.close()
            db = null
        }
    }

    /**
     * Queries the cache with the given RfIdentification.
     *
     * If the emitter does not exist in the cache, a new
     * a new "unknown" entry is created.
     * It is NOT fetched from database in this case.
     * This should be done be calling loadIds before cache.get,
     * because fetching emitters one by one is slower than
     * getting all at once. And cache.get is ALWAYS called
     * in a loop over many ids
     *
     * @param id
     * @return the emitter
     */
    operator fun get(id: RfIdentification): RfEmitter {
        val key = id.uniqueId
        return workingSet[key]?.apply { resetAge() } ?: run {
            val result = RfEmitter(id)
            synchronized(this) { workingSet[key] = result }
            result
        }
    }

    /** Simply gets the emitter if it's cached */
    fun simpleGet(id: RfIdentification): RfEmitter? = workingSet[id.uniqueId]

    /**
     * Loads the given RfIdentifications from database
     *
     * This is a performance improvement over loading emitters on get(),
     * as all emitters are loaded in a single db query.
     * Emitters not loaded from db are still added to the working set. This is done
     * because usually [get] is called on each id after loading, and adding a new
     * id requires synchronized, which my be a bit slow.
     */
    fun loadIds(ids: Collection<RfIdentification>) {
        val idsToLoad = ids.filterNot { workingSet.containsKey(it.uniqueId) }
        if (DEBUG) Log.d(TAG, "loadIds() - Fetching ${idsToLoad.size} ids not in working set from db.")
        if (idsToLoad.isEmpty()) return
        synchronized(this) {
            val emitters = db?.getEmitters(idsToLoad) ?: return
            emitters.forEach { workingSet[it.uniqueId] = it }
            idsToLoad.forEach {
                if (!workingSet.containsKey(it.uniqueId))
                    workingSet[it.uniqueId] = RfEmitter(it)
            }
        }
    }

    /**
     * Remove all entries from the cache.
     */
    fun clear() {
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
        if (db == null) return

        synchronized(this) {
            // Scan all of our emitters to see
            // 1. If any have dirty data to sync to the flash database
            // 2. If any have been unused long enough to remove from cache
            val agedEmitters = mutableListOf<RfIdentification>()
            val emittersInNeedOfSync = mutableListOf<RfEmitter>()
            workingSet.values.forEach {
                if (it.age >= MAX_AGE)
                    agedEmitters.add(it.rfIdentification)
                it.incrementAge()
                if (it.syncNeeded())
                    emittersInNeedOfSync.add(it)
            }

            if (emittersInNeedOfSync.isNotEmpty()) db?.let { db ->
                if (DEBUG) Log.d(TAG, "sync() - syncing ${emittersInNeedOfSync.size} emitters with db")
                db.beginTransaction()
                emittersInNeedOfSync.forEach {
                    it.sync(db)
                }
                db.endTransaction()
            }

            // Remove aged out items from cache
            agedEmitters.forEach {
                workingSet.remove(it.uniqueId)
                if (DEBUG) Log.d(TAG, "sync('${it.uniqueId}') - Aged out, removed from cache.")
            }

            // clear cache is we have really a lot of emitters cached
            if (workingSet.size > MAX_WORKING_SET_SIZE) {
                if (DEBUG) Log.d(TAG, "sync() - Working set larger than $MAX_WORKING_SET_SIZE, clearing working set.")
                workingSet.clear()
            }
        }
    }

    companion object {
        private const val MAX_WORKING_SET_SIZE = 500
        private const val MAX_AGE = 30
        private val DEBUG = BuildConfig.DEBUG
        private const val TAG = "LocalNLP Cache"
    }

}
