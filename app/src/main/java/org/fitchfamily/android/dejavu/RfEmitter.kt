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

import android.location.Location
import android.os.Bundle
import android.util.Log
import org.fitchfamily.android.dejavu.EmitterType.*
import kotlin.math.abs

/**
 * Created by tfitch on 8/27/17.
 */
/**
 * Models everything we know about an RF emitter: Its identification, most recently received
 * signal level, an estimate of its coverage (center point and radius), etc.
 *
 * Starting with v2 of the database, we store a north-south radius and an east-west radius which
 * allows for a rectangular bounding box rather than a square one.
 *
 * When an RF emitter is first observed we create a new object and, if information exists in
 * the database, populate it from saved information.
 *
 * Periodically we sync our current information about the emitter back to the flash memory
 * based storage.
 */
class RfEmitter(val type: EmitterType, val id: String) {
    internal constructor(identification: RfIdentification) : this(identification.rfType, identification.rfId)

    internal constructor(identification: RfIdentification, emitterInfo: EmitterInfo) : this(identification.rfType, identification.rfId, emitterInfo)

    internal constructor(type: EmitterType, id: String, emitterInfo: EmitterInfo) : this(type, id) {
        if (emitterInfo.radius_ew < 0) {
            coverage = null
            status = EmitterStatus.STATUS_BLACKLISTED
        }
        coverage = BoundingBox(emitterInfo)
        status = EmitterStatus.STATUS_CACHED
        note = emitterInfo.note
    }

    private val ourCharacteristics = type.getRfCharacteristics()
    private var coverage: BoundingBox? = null // null for new or blacklisted emitters
    var note: String = ""
        set(value) {
            if (field == value)
                return
            field = value
            if (isBlacklisted())
                changeStatus(EmitterStatus.STATUS_BLACKLISTED, "emitter blacklisted")
        }
    var lastObservation: Observation? = null // null if we haven't seen this emitter
        set(value) {
            field = value
            note = value?.note ?: ""
        }
    private var status: EmitterStatus = EmitterStatus.STATUS_UNKNOWN

    val uniqueId: String get() = rfIdentification.uniqueId
    val rfIdentification: RfIdentification = RfIdentification(id, type)
    val lat: Double get() = coverage?.center_lat ?: 0.0
    val lon: Double get() = coverage?.center_lon ?: 0.0
    private val radius: Double get() = coverage?.radius ?: 0.0
    val radiusNS: Double get() = coverage?.radius_ns ?: 0.0
    val radiusEW: Double get() = coverage?.radius_ew ?: 0.0

    /**
     * All RfEmitter objects are managed through a cache. The cache needs ages out
     * emitters that have not been seen (or used) in a while. To do that it needs
     * to maintain age information for each RfEmitter object. Having the RfEmitter
     * object itself store the cache age is a bit of a hack, but we do it anyway.
     *
     * @return The current cache age.
     */
    var age = 0 // Count of periods since last used (for caching purposes)
        private set

    /**
     * On equality check, we only check that our type and ID match as that
     * uniquely identifies our RF emitter.
     *
     * @param other The object to check for equality
     * @return True if the objects should be considered the same.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RfIdentification) return false
        return rfIdentification == other
    }

    /**
     * Hash code is used to determine unique objects. Our "uniqueness" is
     * based on which "real life" RF emitter we model, not our current
     * coverage, etc. So our hash code should be the same as the hash
     * code of our identification.
     *
     * @return A hash code for this object.
     */
    override fun hashCode(): Int {
        return rfIdentification.hashCode()
    }

    /**
     * Resets the cache age to zero.
     */
    fun resetAge() {
        age = 0
    }

    /**
     * Increment the cache age for this object.
     */
    fun incrementAge() {
        age++
    }

    /**
     * Periodically the cache sync's all dirty objects to the flash database.
     * This routine is called by the cache to determine if it needs to be sync'd.
     *
     * @return True if this RfEmitter needs to be written to flash.
     */
    fun syncNeeded(): Boolean {
        return (status == EmitterStatus.STATUS_NEW
                    || status == EmitterStatus.STATUS_CHANGED
                    || (status == EmitterStatus.STATUS_BLACKLISTED
                        && coverage != null)
                )
    }

    /**
     * Synchronize this object to the flash based database. This method is called
     * by the cache when it is an appropriate time to assure the flash based
     * database is up to date with our current coverage, etc.
     *
     * @param db The database we should write our data to.
     */
    fun sync(db: Database) {
        var newStatus = status
        when (status) {
            EmitterStatus.STATUS_UNKNOWN -> { }
            EmitterStatus.STATUS_BLACKLISTED ->
                // If our coverage value is not null it implies that we exist in the
                // database as "normal" emitter. If so we ought to either remove the entry (for
                // blacklisted SSIDs) or set invalid radius (for too large coverage).
                if (coverage != null) {
                    if (isBlacklisted()) {
                        db.drop(this)
                        if (DEBUG) Log.d(TAG, "sync('$logString') - Blacklisted dropping from database.")
                    } else {
                        db.setInvalid(this)
                        if (DEBUG) Log.d(TAG, "sync('$logString') - Blacklisted setting to invalid, radius too large: $radius, $radiusEW, $radiusNS.")
                    }
                    coverage = null
                }
            EmitterStatus.STATUS_NEW -> {
                // Not in database, we have location. Add to database
                db.insert(this)
                newStatus = EmitterStatus.STATUS_CACHED
            }
            EmitterStatus.STATUS_CHANGED -> {
                // In database but we have changes
                db.update(this)
                newStatus = EmitterStatus.STATUS_CACHED
            }
            EmitterStatus.STATUS_CACHED -> { }
        }
        changeStatus(newStatus, "sync('$logString')")
    }

    val logString get() = if (DEBUG) "RF Emitter: Type=$type, ID='$id', Note='$note'" else ""

    /**
     * Update our estimate of the coverage and location of the emitter based on a
     * position report from the GPS system.
     *
     * @param gpsLoc A position report from a trusted (non RF emitter) source
     */
    fun updateLocation(gpsLoc: Location) {
        if (status == EmitterStatus.STATUS_BLACKLISTED) return

        // don't update location if there is more than 10 sec difference between last observation
        // and gps location (because we might have moved considerably during this time)
        // this can occur e.g. if a wifi scan takes very long to complete or old scan results are reported
        if (abs((lastObservation?.elapsedRealtimeNanos ?: 0L) - gpsLoc.elapsedRealtimeNanos) > 10 * 1e9) {
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - No update because location and observation " +
                    "differ by more than 10s: ${((lastObservation?.elapsedRealtimeNanos ?: 0L) - gpsLoc.elapsedRealtimeNanos)/1e6}ms")
            return
        }

        // don't update coverage if gps too inaccurate
        val cov = coverage
        if (gpsLoc.accuracy > ourCharacteristics.requiredGpsAccuracy
                // except if distance is really large and we're sure emitter should be out of range
                //   this allows updating emitters that are found unbelievably far
                //   from their known location, so they will be blacklisted
                && (cov == null || approximateDistance(gpsLoc, cov.center_lat, cov.center_lon)
                            < (type.getRfCharacteristics().maximumRange + gpsLoc.accuracy) * 2)
            ) {
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - No update because location inaccurate.")
            return
        }
        if (cov == null) {
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - Emitter is new.")
            coverage = BoundingBox(gpsLoc.latitude, gpsLoc.longitude)
            changeStatus(EmitterStatus.STATUS_NEW, "updateLocation($logString) New")
            return
        }

        // Add the GPS sample to the known bounding box of the emitter.
        if (cov.update(gpsLoc.latitude, gpsLoc.longitude)) {
            // Bounding box has increased, see if it is now unbelievably large
            if (cov.radius > ourCharacteristics.maximumRange)
                changeStatus(EmitterStatus.STATUS_BLACKLISTED, "updateLocation($logString) too large radius")
            else
                changeStatus(EmitterStatus.STATUS_CHANGED, "updateLocation($logString) BBOX update")
        }
    }

    /**
     * User facing location value. Differs from internal one in that we don't report
     * locations that are guarded due to being new or moved.
     *
     * @return The coverage estimate for our RF emitter or null if we don't trust our
     * information.
     */
    val location: Location?
        get() {
            // If we have no observation of the emitter we ought not give a
            // position estimate based on it.
            val observation = lastObservation ?: return null

            if (status == EmitterStatus.STATUS_BLACKLISTED)
                return null

            // If we don't have a coverage estimate we will get back a null location
            val location = generateLocation() ?: return null

            // If we are unbelievably close to null island, don't report location
            if (!notNullIsland(location)) return null

            // Time tags based on time of most recent observation
            location.time = observation.lastUpdateTimeMs
            location.elapsedRealtimeNanos = observation.elapsedRealtimeNanos
            val extras = Bundle()
            extras.putString(LOC_RF_TYPE, type.toString())
            extras.putString(LOC_RF_ID, id)
            extras.putInt(LOC_ASU, observation.asu)
            extras.putInt(LOC_MIN_COUNT, ourCharacteristics.minCount)
            location.extras = extras
            return location
        }

    /**
     * If we have any coverage information, returns an estimate of that coverage.
     * For convenience, we use the standard Location record as it contains a center
     * point and radius (accuracy).
     *
     * @return Coverage estimate for emitter or null it does not exist.
     */
    private fun generateLocation(): Location? {
        val cov = coverage ?: return null
        val location = Location(LOCATION_PROVIDER)
        location.latitude = cov.center_lat
        location.longitude = cov.center_lon

        // Hard limit the minimum accuracy based on the type of emitter
        location.accuracy = radius.toFloat().coerceAtLeast(ourCharacteristics.minimumRange)
        return location
    }

    /**
     * As part of our effort to not use mobile emitters in estimating or location
     * we blacklist ones that match observed patterns.
     *
     * @return True if the emitter is blacklisted (should not be used in position computations).
     */
    private fun isBlacklisted(): Boolean =
        if (note.isEmpty())
            false
        else
             when (type) {
                 WLAN2, WLAN5, WLAN6 -> ssidBlacklisted()
                 BT -> false // if ever added, there should be a BT blacklist too
                 else -> false // Not expecting mobile towers to move around.
            }

    /**
     * Checks the note field (where the SSID is saved) to see if it appears to be
     * an AP that is likely to be moving. Typical checks are to see if substrings
     * in the SSID match that of cell phone manufacturers or match known patterns
     * for public transport (busses, trains, etc.) or in car WLAN defaults.
     *
     * @return True if emitter should be blacklisted.
     */
    private fun ssidBlacklisted(): Boolean {
        val lc = note.lowercase()

        // split lc into continuous occurrences of a-z
        // most 'contains' checks only make sense if the string is a separate word
        // this accelerates comparison a lot, at the risk of missing some wifis
        val lcSplit = lc.split(splitRegex).toSet()

        // Seen a large number of WiFi networks where the SSID is the last
        // three octets of the MAC address. Often in rural areas where the
        // only obvious source would be other automobiles. So suspect that
        // this is the default setup for a number of vehicle manufactures.
        val macSuffix =
            id.substring(id.length - 8).lowercase().replace(":", "")

        val blacklisted =
            lcSplit.any { blacklistWords.contains(it) }
                || blacklistStartsWith.any { lc.startsWith(it) }
                || blacklistEndsWith.any { lc.endsWith(it) }
                || blacklistEquals.contains(lc)
                // a few less simple checks
                || lcSplit.contains("moto") && note.startsWith("MOTO") // "MOTO9564" and "MOTO9916" seen
                || lcSplit.first() == "audi"            // some cars seem to have this AP on-board
                || lc == macSuffix                      // Apparent default SSID name for many cars
                // deal with words not achievable with blacklistWords, checking only if lcSplit.contains(<something>)
                || (lcSplit.contains("admin") && lc.contains("admin@ms"))
                || (lcSplit.contains("guest") && lc.contains("guest@ms"))
                || (lcSplit.contains("contiki") && lc.contains("contiki-wifi"))    // transport
                || (lcSplit.contains("interakti") && lc.contains("nsb_interakti")) // ???
                || (lcSplit.contains("nvram") && lc.contains("nvram warning"))     // transport

        if (DEBUG && blacklisted) Log.d(TAG, "blacklistWifi('$logString'): blacklisted")
        return blacklisted
    }

    /**
     * Our status can only make a small set of allowed transitions. Basically a simple
     * state machine. To assure our transitions are all legal, this routine is used for
     * all changes.
     *
     * @param newStatus The desired new status (state)
     * @param info Logging information for debug purposes
     */
    private fun changeStatus(newStatus: EmitterStatus, info: String) {
        if (newStatus == status) return
        when (status) {
            EmitterStatus.STATUS_BLACKLISTED -> { }
            EmitterStatus.STATUS_CACHED, EmitterStatus.STATUS_CHANGED ->
                when (newStatus) {
                   EmitterStatus.STATUS_BLACKLISTED, EmitterStatus.STATUS_CACHED, EmitterStatus.STATUS_CHANGED ->
                       status = newStatus
                   else -> { }
            }
            EmitterStatus.STATUS_NEW ->
                when (newStatus) {
                    EmitterStatus.STATUS_BLACKLISTED, EmitterStatus.STATUS_CACHED ->
                        status = newStatus
                    else -> { }
            }
            EmitterStatus.STATUS_UNKNOWN ->
                when (newStatus) {
                    EmitterStatus.STATUS_BLACKLISTED, EmitterStatus.STATUS_CACHED, EmitterStatus.STATUS_NEW ->
                        status = newStatus
                    else -> { }
            }
        }
        if (DEBUG) Log.d(TAG, "$info: tried switching to $newStatus, result: $status")
        return
    }
}

private val DEBUG = BuildConfig.DEBUG

private const val TAG = "DejaVu RfEmitter"

// Tag/names for additional information on location records
const val LOC_RF_ID = "rfid"
const val LOC_RF_TYPE = "rftype"
const val LOC_ASU = "asu"
const val LOC_MIN_COUNT = "minCount"

private val splitRegex = "[^a-z]".toRegex() // for splitting SSID into "words"
// use hashSets for fast blacklist*.contains() check
private val blacklistWords = hashSetOf(
    "android", "androidap", "ipad", "phone", "motorola", "huawei", // mobile tethering
    "mobile", // sounds like name for mobile hotspot
    "deinbus", "ecolines", "eurolines", "fernbus", "flixbus", "muenchenlinie",
    "postbus", "skanetrafiken", "oresundstag", "regiojet", // transport

    // Per an instructional video on YouTube, recent (2014 and later) Chrysler-Fiat
    // vehicles have a SSID of the form "Chrysler uconnect xxxxxx" where xxxxxx
    // seems to be a hex digit string (suffix of BSSID?).
    "uconnect", // Chrysler built vehicles
    "chevy", // "Chevy Cruz 7774" and "Davids Chevy" seen.
    "silverado", // GMC Silverado. "Bryces Silverado" seen, maybe move to startsWith?
    "myvolvo", // Volvo in car WiFi, maybe move to startsWith?
    "bmw", // examples: BMW98303 CarPlay, My BMW Hotspot 8303, DIRECT-BMW 67727
)
private val blacklistStartsWith = hashSetOf(
    "moto ", "samsung galaxy", "lg aristo", // mobile tethering
    "cellspot", // T-Mobile US portable cell based WiFi
    "verizon", // Verizon mobile hotspot

    // Per some instructional videos on YouTube, recent (2015 and later)
    // General Motors built vehicles come with a default WiFi SSID of the
    // form "WiFi Hotspot 1234" where the 1234 is different for each car.
    "wifi hotspot ", // Default GM vehicle WiFi name

    // Per instructional video on YouTube, Mercedes cars have and SSID of
    // "MB WLAN nnnnn" where nnnnn is a 5 digit number.
    "mb wlan ",
    "westbahn ", "buswifi", "coachamerica", "disneylandresortexpress",
    "taxilinq", "transitwirelesswifi", // transport, maybe move some to words?
    "yicarcam", // Dashcam WiFi.
    "my seat", // My SEAT 741
    "vw_wlan", // VW WLAN 9266
    "my vw", // My VW 4025
    "my skoda", // My Skoda 3358
    "skoda_wlan", // Skoda_WLAN_5790
)
private val blacklistEndsWith = hashSetOf(
    "corvette", // Chevy Corvette. "TS Corvette" seen.

    // General Motors built vehicles SSID can be changed but the recommended SSID to
    // change to is of the form "first_name vehicle_model" (e.g. "Bryces Silverado").
    "truck", // "Morgans Truck" and "Wally Truck" seen
    "suburban", // Chevy/GMC Suburban. "Laura Suburban" seen
    "terrain", // GMC Terrain. "Nelson Terrain" seen
    "sierra", // GMC pickup. "dees sierra" seen
    "gmc wifi", // General Motors
)
private val blacklistEquals = hashSetOf(
    "amtrak", "amtrakconnect", "cdwifi", "megabus", "westlan","wifi in de trein",
    "svciob", "oebb", "oebb-postbus", "dpmbfree", "telekom_ice", "db ic bus", // transport
)

enum class EmitterType {
    INVALID,
    WLAN2,
    WLAN5,
    WLAN6,
    BT,
    GSM,
    CDMA,
    WCDMA,
    TDSCDMA,
    LTE,
    NR,
}

enum class EmitterStatus {
    STATUS_UNKNOWN,     // Newly discovered emitter, no data for it at all
    STATUS_NEW,         // Not in database but we've got location data for it
    STATUS_CHANGED,     // In database but something has changed
    STATUS_CACHED,      // In database no changes pending
    STATUS_BLACKLISTED  // Has been blacklisted
}
