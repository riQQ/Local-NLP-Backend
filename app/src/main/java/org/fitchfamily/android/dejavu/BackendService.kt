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

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.telephony.*
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import org.fitchfamily.android.dejavu.RfEmitter.Companion.getRfCharacteristics
import org.microg.nlp.api.LocationBackendService
import org.microg.nlp.api.MPermissionHelperActivity
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Created by tfitch on 8/27/17.
 */
class BackendService : LocationBackendService() {
    private var gpsMonitorRunning = false
    private var wifiBroadcastReceiverRegistered = false
    private var permissionsOkay = true

    // We use a threads for potentially slow operations.
    // TODO: coroutine?
    private var mobileThread: Thread? = null
    private var backgroundThread: Thread? = null
    private var wifiScanInProgress = false
    private var telephonyManager: TelephonyManager? = null
    private var wifiManager: WifiManager? = null
    private val wifiBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onWiFisChanged()
        }
    }
    private var gpsLocation: Kalman? = null // Filtered GPS (because GPS is so bad on Moto G4 Play)
    private var oldLocationUpdate = 0L // store last update of the previous period to allow checking whether gpsLocation has changed

    //
    // Periodic process information.
    //
    // We keep a set of the WiFi APs we expected to see and ones we've seen and then
    // periodically adjust the trust. Ones we've seen we increment, ones we expected
    // to see but didn't we decrement.
    //
    private val seenSet = hashSetOf<RfIdentification>()
    private var emitterCache: Cache? = null
    private var nextMobileScanTime: Long = 0
    private var nextWlanScanTime: Long = 0
    private var nextReportTime: Long = 0

    //
    // We want only a single background thread to do all the work but we have a couple
    // of asynchronous inputs. So put everything into a work item queue. . . and have
    // a single server pull and process the information.
    //
    private inner class WorkItem(
        var observations: Collection<Observation>,
        var loc: Location?,
        //var time: Long // never actually used
    )

    private val workQueue: Queue<WorkItem> = ConcurrentLinkedQueue()
    private var oldScanResults = listOf<ScanResult>()

    private val airplaneMode get() = Settings.Global.getInt(applicationContext.contentResolver,
        Settings.Global.AIRPLANE_MODE_ON, 0)

    /**
     * We are starting to run, get the resources we need to do our job.
     */
    override fun onOpen() {
        Log.d(TAG, "onOpen() entry.")
        super.onOpen()
        instance = this
        nextReportTime = 0
        nextMobileScanTime = 0
        nextWlanScanTime = 0
        wifiBroadcastReceiverRegistered = false
        wifiScanInProgress = false
        if (emitterCache == null) emitterCache = Cache(this)
        permissionsOkay = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check our needed permissions, don't run unless we can.
            for (s in myPerms) {
                permissionsOkay =
                    permissionsOkay and (checkSelfPermission(s) == PackageManager.PERMISSION_GRANTED)
            }
        }
        if (permissionsOkay) {
            setGpsMonitorRunning(true)
            this.registerReceiver(wifiBroadcastReceiver, wifiBroadcastFilter)
            wifiBroadcastReceiverRegistered = true
        } else {
            Log.d(TAG, "onOpen() - Permissions not granted, soft fail.")
        }
    }

    /**
     * Closing down, release our dynamic resources.
     */
    @Synchronized
    override fun onClose() {
        super.onClose()
        Log.d(TAG, "onClose()")
        if (wifiBroadcastReceiverRegistered) {
            unregisterReceiver(wifiBroadcastReceiver)
        }
        setGpsMonitorRunning(false)
        if (emitterCache != null) {
            emitterCache!!.close()
            emitterCache = null
        }
        if (instance === this) {
            instance = null
        }
    }

    /**
     * Called by MicroG/UnifiedNlp when our backend is enabled. We return a list of
     * the Android permissions we need but have not (yet) been granted. MicroG will
     * handle putting up the dialog boxes, etc. to get our permissions granted.
     *
     * @return An intent with the list of permissions we need to run.
     */
    override fun getInitIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Build list of permissions we need but have not been granted
            val perms: MutableList<String> = LinkedList()
            for (s in myPerms) {
                if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) perms.add(s)
            }

            // Send the list of permissions we need to UnifiedNlp so it can ask for
            // them to be granted.
            if (perms.isEmpty()) return null
            val intent = Intent(this, MPermissionHelperActivity::class.java)
            intent.putExtra(MPermissionHelperActivity.EXTRA_PERMISSIONS, perms.toTypedArray())
            return intent
        }
        return super.getInitIntent()
    }

    /**
     * Called by microG/UnifiedNlp when it wants a position update. We return a null indicating
     * we don't have a current position but treat it as a good time to kick off a scan of all
     * our RF sensors.
     *
     * @return Always null.
     */
    override fun update(): Location? {
        //Log.d(TAG, "update() entry.");
        if (permissionsOkay) {
            scanAllSensors()
        } else {
            Log.d(TAG, "update() - Permissions not granted, soft fail.")
        }
        return null
    }
    //
    // Private methods
    //
    /**
     * Called when we have a new GPS position report from Android. We update our local
     * Kalman filter (our best guess on GPS reported position) and since our location is
     * pretty current it is a good time to kick of a scan of RF sensors.
     *
     * @param update The current GPS reported location
     */
    private fun onGpsChanged(update: Location) {
        synchronized(this) {
            if (permissionsOkay && notNullIsland(update)) {
                if (DEBUG) Log.d(TAG, "onGpsChanged() entry.");
                if (gpsLocation == null)
                    gpsLocation = Kalman(update, GPS_COORDINATE_NOISE)
                else
                    gpsLocation!!.update(update)
                scanAllSensors()
            } else
                Log.d(TAG, "onGpsChanged() - Permissions not granted, soft fail.")
        }
    }

    /**
     * Kick off new scans for all the sensor types we know about. Typically scans
     * should occur asynchronously so we don't hang up our caller's thread.
     */
    private fun scanAllSensors() {
        synchronized(this) {
            if (emitterCache == null) {
                if (DEBUG) Log.d(TAG, "scanAllSensors() - emitterCache is null?!?")
                return
            }

            if (DEBUG) Log.d(TAG, "scanAllSensors() - starting scans")
            startWiFiScan()
            if (airplaneMode == 0)
                startMobileScan()
            else
                if (DEBUG) Log.d(TAG, "scanAllSensors() - airplane mode enabled, not scanning for mobile towers")
        }
    }

    /**
     * Ask Android's WiFi manager to scan for access points (APs). When done the onWiFisChanged()
     * method will be called by Android.
     */
    private fun startWiFiScan() {
        // Throttle scanning for WiFi APs. In open terrain an AP could cover a kilometer.
        // Even in a vehicle moving at highway speeds it can take several seconds to traverse
        // the coverage area, no need to waste phone resources scanning too rapidly.
        val currentProcessTime = SystemClock.elapsedRealtime()
        if (currentProcessTime < nextWlanScanTime) {
            if (DEBUG) Log.d(TAG, "startWiFiScan() - need to wait before starting next scan")
            return
        }
        nextWlanScanTime = currentProcessTime + WLAN_SCAN_INTERVAL
        if (wifiManager == null) {
            wifiManager = this.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        }
        if (wifiManager != null && !wifiScanInProgress) {
            if (wifiManager!!.isWifiEnabled || wifiManager!!.isScanAlwaysAvailable) {
                if (DEBUG) Log.d(TAG, "startWiFiScan() - Starting WiFi collection.")
                wifiScanInProgress = true
                wifiManager!!.startScan()
            }
        } else if (DEBUG) Log.d(TAG, "startWiFiScan() - WiFi scan in progress, not starting.")
    }

    /**
     * Start a separate thread to scan for mobile (cell) towers. This can take some time so
     * we won't do it in the caller's thread.
     */
    @Synchronized
    private fun startMobileScan() {
        // Throttle scanning for mobile towers. Generally each tower covers a significant amount
        // of terrain so even if we are moving fairly rapidly we should remain in a single tower's
        // coverage area for several seconds. No need to sample more ofen than that and we save
        // resources on the phone.
        val currentProcessTime = SystemClock.elapsedRealtime()
        if (currentProcessTime < nextMobileScanTime) {
            if (DEBUG) Log.d(TAG, "startMobileScan() - need to wait before starting next scan")
            return
        }
        nextMobileScanTime = currentProcessTime + MOBILE_SCAN_INTERVAL

        // Scanning towers takes some time, so do it in a separate thread.
        if (mobileThread != null) {
            if (DEBUG) Log.d(TAG, "startMobileScan() - Thread exists.")
            return
        }
        if (DEBUG) Log.d(TAG,"startMobileScan() - Starting mobile signal scan thread.")
        mobileThread = Thread { // TODO: coroutine?
            scanMobile()
            mobileThread = null
        }
        mobileThread!!.start()
    }

    /**
     * Scan for the mobile (cell) towers the phone sees. If we see any, then add them
     * to the queue for background processing.
     */
    private fun scanMobile() {
        // Log.d(TAG, "scanMobile() - calling getMobileTowers().");
        val observations: Collection<Observation> = getMobileTowers()
        if (observations.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "scanMobile() " + observations.size + " records to be queued for processing.")
            queueForProcessing(observations/*, SystemClock.elapsedRealtime()*/)
        }
    }

    /**
     * Get the set of mobile (cell) towers that Android claims the phone can see.
     * we use the current API but fall back to deprecated methods if we get a null
     * or empty result from the current API.
     *
     * @return A set of mobile tower observations
     */
    @SuppressLint("MissingPermission")
    private fun getMobileTowers(): Set<Observation> {
        if (telephonyManager == null) {
            telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        }
        val observations = hashSetOf<Observation>()

        // Try most recent API to get all cell information
        val allCells: List<CellInfo> = try {
            telephonyManager!!.allCellInfo
        } catch (e: NoSuchMethodError) {
            emptyList()
            // Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().");
        }
        if (allCells.isEmpty()) return deprecatedGetMobileTowers()

        // TODO: mnc can be null... damn
        val intMax = Int.MAX_VALUE
        val alternativeMnc by lazy { // determine mnc the other way not more than once per call of getMobileTowers
            telephonyManager!!.networkOperator?.let { if (it.length > 5) it.substring(3) else null }
        }
        if (DEBUG) Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned " + allCells.size + " records.")
        for (info in allCells) {
            if (DEBUG) Log.v(TAG, "getMobileTowers(): inputCellInfo: $info")
            if (info is CellInfoLte) {
                val id = info.cellIdentity

                // get mnc and mcc as strings if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else
                        id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString
                    else
                        id.mnc.takeIf { it != intMax }?.toString()
                        ) ?: alternativeMnc ?: continue

                // CellIdentityLte accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.ci == intMax || id.pci == intMax || id.tac == intMax)
                    continue

                val idStr = "LTE/$mccString/$mncString/${id.ci}/${id.pci}/${id.tac}"
                val asu = info.cellSignalStrength.asuLevel * MAXIMUM_ASU / 97
                val o = Observation(idStr, EmitterType.MOBILE, asu)
                observations.add(o)
                if (DEBUG) Log.d(TAG, "valid observation string: $idStr, asu $asu")

            } else if (info is CellInfoGsm) {
                val id = info.cellIdentity

                // get mnc and mcc as strings if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else
                        id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            id.mncString
                        else
                            id.mnc.takeIf { it != intMax }?.toString()
                        ) ?: alternativeMnc ?: continue

                // CellIdentityGsm accessors all state Integer.MAX_VALUE is returned for unknown values.
                // analysis of results show frequent invalid LAC of 0 messing with results
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                val idStr = "GSM/$mccString/$mncString/${id.lac}/${id.cid}"
                val asu = info.cellSignalStrength.asuLevel
                val o = Observation(idStr, EmitterType.MOBILE, asu)
                observations.add(o)
                if (DEBUG) Log.d(TAG, "valid observation string: $idStr, asu $asu")

            } else if (info is CellInfoWcdma) {
                val id = info.cellIdentity

                // get mnc and mcc as strings if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else
                        id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString
                    else
                        id.mnc.takeIf { it != intMax }?.toString()
                        ) ?: alternativeMnc ?: continue

                // CellIdentityWcdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                val idStr = "WCDMA/$mccString/$mncString/${id.lac}/${id.cid}"
                val asu = info.cellSignalStrength.asuLevel
                val o = Observation(idStr, EmitterType.MOBILE, asu)
                observations.add(o)
                if (DEBUG) Log.d(TAG, "valid observation string: $idStr, asu $asu")

            } else if (info is CellInfoCdma) {
                val id = info.cellIdentity
                // CellIdentityCdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.networkId == intMax || id.systemId == intMax || id.basestationId == intMax)
                    continue

                val idStr = "CDMA/${id.networkId}/${id.systemId}/${id.basestationId}"
                val asu = info.cellSignalStrength.asuLevel
                val o = Observation(idStr, EmitterType.MOBILE, asu)
                observations.add(o)
                if (DEBUG) Log.d(TAG, "valid observation string: $idStr, asu $asu")

            } else
                Log.d(TAG, "getMobileTowers(): Unsupported Cell type: $info")
        }
        if (DEBUG) Log.d(TAG, "getMobileTowers(): Observations: $observations")
        return observations
    }

    /**
     * Use old but still implemented methods to gather information about the mobile (cell)
     * towers our phone sees. Only called if the non-deprecated methods fail to return a
     * usable result.
     *
     * @return A set of observations for all the towers Android is reporting.
     */
    @SuppressLint("MissingPermission")
    private fun deprecatedGetMobileTowers(): HashSet<Observation> {
        if (DEBUG) Log.d(TAG, "getMobileTowers(): allCells null or empty, using deprecated")
        val observations = hashSetOf<Observation>()
        val mncString = telephonyManager!!.networkOperator
        if (mncString == null || mncString.length < 5 || mncString.length > 6) {
            if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): mncString is NULL or not recognized.")
            return observations
        }
        val mcc = mncString.substring(0, 3).toIntOrNull() ?: return observations
        val mnc = mncString.substring(3).toIntOrNull() ?: return observations
        val info = telephonyManager!!.cellLocation
        if (info != null && info is GsmCellLocation) {
            val idStr = "GSM/$mcc/$mnc/${info.lac}/${info.cid}"
            val o = Observation(idStr, EmitterType.MOBILE, MINIMUM_ASU)
            observations.add(o)
        } else {
            if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): getCellLocation() returned null or not GsmCellLocation.")
        }
        try {
            val neighbors = telephonyManager!!.neighboringCellInfo
            if (neighbors != null && neighbors.isNotEmpty()) {
                for (neighbor in neighbors) {
                    if (neighbor.cid > 0 && neighbor.lac > 0) {
                        val idStr = "GSM" + "/" + mcc + "/" +
                                mnc + "/" + neighbor.lac + "/" +
                                neighbor.cid
                        val o = Observation(idStr, EmitterType.MOBILE, neighbor.rssi)
                        observations.add(o)
                    }
                }
            } else {
                if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): getNeighboringCellInfo() returned null or empty set.")
            }
        } catch (e: NoSuchMethodError) {
            if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): no such method: getNeighboringCellInfo().")
        }
        return observations
    }

    // Stuff for binding to (basically starting) background AP location
    // collection
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            if (DEBUG) Log.d(TAG, "mConnection.onServiceConnected()")
        }

        override fun onServiceDisconnected(className: ComponentName) {
            if (DEBUG) Log.d(TAG, "mConnection.onServiceDisconnected()")
        }
    }

    /**
     * Control whether or not we are listening for position reports from other sources.
     * The only one we care about is the GPS, thus the name.
     *
     * @param enable A boolean value, true enables monitoring.
     */
    private fun setGpsMonitorRunning(enable: Boolean) {
        if (DEBUG) Log.d(TAG, "setGpsMonitorRunning($enable)")
        if (enable != gpsMonitorRunning) {
            if (enable) {
                bindService(Intent(this, GpsMonitor::class.java), mConnection, BIND_AUTO_CREATE)
            } else {
                unbindService(mConnection)
            }
            gpsMonitorRunning = enable
        }
    }

    /**
     * Call back method entered when Android has completed a scan for WiFi emitters in
     * the area.
     */
    @Synchronized
    private fun onWiFisChanged() {
        if (wifiManager != null && emitterCache != null) {
            val scanResults = wifiManager!!.scanResults
            if (scanResults.sameAs(oldScanResults)) {
                if (DEBUG) Log.d(TAG, "onWiFisChanged(): scan results are the same as old results")
                //return // TODO: only log for now
            }
            val observations = hashSetOf<Observation>()
            if (DEBUG) Log.d(TAG, "onWiFisChanged(): " + scanResults.size + " scan results")
            for (scanResult in scanResults) {
                val bssid = scanResult.BSSID.lowercase().replace(".", ":")
                val rfType = if (is5GHz(scanResult)) EmitterType.WLAN_5GHZ
                    else EmitterType.WLAN_24GHZ
                if (DEBUG) Log.v(TAG, "rfType=$rfType, ScanResult=$scanResult")
                val observation = Observation(bssid,
                    rfType,
                    WifiManager.calculateSignalLevel(scanResult.level, MAXIMUM_ASU),
                    scanResult.SSID
                )
                observations.add(observation)
            }
            if (observations.isNotEmpty()) {
                if (DEBUG) Log.d(TAG, "onWiFisChanged(): " + observations.size + " observations")
                queueForProcessing(observations/*, SystemClock.elapsedRealtime()*/)
            }
            oldScanResults = scanResults
        }
        wifiScanInProgress = false
    }

    // android likes providing the same lists with new timestamps, so compare using signal levels
    //  TODO: but with this, no location will be provided instead of a potentially wrong location
    //   also not so good... maybe user choice?
    private fun List<ScanResult>.sameAs(oldResults: List<ScanResult>): Boolean {
        if (size != oldResults.size) return false
        for (i in 0 until size) {
            val new = get(i)
            val old = oldResults[i]
            if (new.BSSID == old.BSSID && new.SSID == old.SSID && new.level == old.level && new.frequency == old.frequency)
                continue
            return false
        }
        return true
    }

    /**
     * Add a collection of observations to our background thread's work queue. If
     * no thread currently exists, start one.
     *
     * @param observations A set of RF emitter observations (all must be of the same type)
     * @param timeMs The time the observations were made. (ends up unused, thus commented)
     */
    @Synchronized
    private fun queueForProcessing(observations: Collection<Observation> /*, timeMs: Long*/) {
        // TODO: set location null if location is too old?
        val loc = if (gpsLocation != null && notNullIsland(gpsLocation!!.location))
                gpsLocation!!.location
            else
                null
        val work = WorkItem(observations, loc /*, timeMs*/)
        workQueue.offer(work)
        if (backgroundThread != null)
            return
        if (DEBUG) Log.d(TAG,"queueForProcessing() - Creating new background thread");
        backgroundThread = Thread { // TODO: coroutine?
            var myWork = workQueue.poll()
            while (myWork != null) {
                backgroundProcessing(myWork)
                myWork = workQueue.poll()
            }
            backgroundThread = null
        }
        backgroundThread!!.start()
    }

    //
    //    Generic private methods
    //

    /**
     * Process a group of observations. Process in this context means
     * 1. Add the emitters to the set of emitters we have seen in this processing period.
     * 2. If the GPS is accurate enough, update our coverage estimates for the emitters.
     * 3. If the GPS is accurate enough, update a list of emitters we think we should have seen.
     * 3. Compute a position based on the current observations.
     * 4. If our collection period is over, report our position to microG/UnifiedNlp and
     * synchonize our information with the flash based database.
     *
     * @param myWork
     */
    @Synchronized
    private fun backgroundProcessing(myWork: WorkItem) {
        if (emitterCache == null) return
        val emitters: MutableCollection<RfEmitter> = HashSet()

        // load all emitters into the cache to avoid several single database transactions
        emitterCache!!.loadIds(myWork.observations.map { it.identification })

        // Remember all the emitters we've seen during this processing period
        // and build a set of emitter objects for each RF emitter in the
        // observation set.
        for (observation in myWork.observations) {
            seenSet.add(observation.identification)
            val emitter = emitterCache!![observation.identification]
            if (emitter != null) {
                emitter.lastObservation = observation
                emitters.add(emitter)
            }
        }

        // Update emitter coverage based on GPS as needed and get the set of locations
        // the emitters are known to be seen at.
        updateEmitters(emitters, myWork.loc/*, myWork.time*/)

        // Check for the end of our collection period. If we are in a new period
        // then finish off the processing for the previous period.
        val currentProcessTime = SystemClock.elapsedRealtime()

        // if a wifi scan is running, wait a bit longer to allow finishing
        // this helps a lot if a scan is started after end of processing period
        //  then the results from mobile scan are usually available before wifi scan, and thus
        //  either a pure mobile location is reported, or a wifi location with old wifis
        val delayUntil = if (wifiScanInProgress)
                nextWlanScanTime.coerceAtLeast(nextReportTime)
            else
                nextReportTime

        if (currentProcessTime >= delayUntil) {
            nextReportTime = currentProcessTime + REPORTING_INTERVAL
            endOfPeriodProcessing()
        }
    }

    /**
     * Update the coverage estimates for the emitters we have just gotten observations for.
     *
     * @param emitters The emitters we have just observed
     * @param gps The GPS position at the time the observations were collected.
     * @param curTime The time the observations were collected (not used, thus commented)
     */
    @Synchronized
    private fun updateEmitters(emitters: Collection<RfEmitter>, gps: Location? /*, curTime: Long*/) {
        if (emitterCache == null) {
            Log.d(TAG, "updateEmitters() - emitterCache is null?!?")
            emitterCache = Cache(this)
        }
        for (emitter in emitters) {
            emitter.updateLocation(gps)
        }
    }

    /**
     * Get coverage estimates for a list of emitter IDs. Locations are marked with the
     * time of last update, etc.
     *
     * @param rfIds IDs of the emitters desired
     * @return A list of the coverage areas for the emitters
     */
    private fun getRfLocations(rfIds: Collection<RfIdentification>?): List<Location> {
        val locations = LinkedList<Location>()
        for (id in rfIds!!) {
            val emitter = emitterCache!![id]
            if (emitter != null) {
                val location = emitter.location
                if (location != null) {
                    locations.add(location)
                }
            }
        }
        return locations
    }

    /**
     * Compute our current location using a weighted average algorithm. We also keep
     * track of the types of emitters we have seen for the end of period processing.
     *
     * For any given reporting interval, we will only use an emitter once, so we keep
     * a set of used emitters.
     *
     * @param locations The set of coverage information for the current observations
     */
    private fun computePosition(locations: Collection<Location>?): Location? {
        locations ?: return null
        val weightedAverage = WeightedAverage()
        for (location in locations) {
            weightedAverage.add(location)
        }
        return weightedAverage.result()
    }

    /**
     *
     * The collector service attempts to detect and not report moved/moving emitters.
     * But it (and thus our database) can't be perfect. This routine looks at all the
     * emitters and returns the largest subset (group) that are within a reasonable
     * distance of one another.
     *
     * The hope is that a single moved/moving emitters that is seen now but whose
     * location was detected miles away can be excluded from the set of APs
     * we use to determine where the phone is at this moment.
     *
     * We do this by creating collections of emitters where all the emitters in a group
     * are within a plausible distance of one another. A single emitters may end up
     * in multiple groups. When done, we return the largest group.
     *
     * If we are at the extreme limit of possible coverage (movedThreshold)
     * from two emitters then those emitters could be a distance of 2*movedThreshold apart.
     * So we will group the emitters based on that large distance.
     *
     * @param locations A collection of the coverages for the current observation set
     * @return The largest set of coverages found within the raw observations. That is
     * the most believable set of coverage areas.
     */
    private fun culledEmitters(locations: Collection<Location>): Set<Location>? {
        val locationGroups = divideInGroups(locations)
        val clsList: List<MutableSet<Location>> = ArrayList(locationGroups).sortedByDescending { it.size }
        if (clsList.isNotEmpty()) {
            val result: Set<Location> = clsList[0]

            // Determine minimum count for a valid group of emitters.
            // The RfEmitter class will have put the min count into the location
            // it provided.
            var requiredCount = 99999 // Some impossibly big number
            for (l in result) {
                requiredCount = l.extras.getInt(RfEmitter.LOC_MIN_COUNT, 9999).coerceAtMost(requiredCount)
            }
            //Log.d(TAG,"culledEmitters() reqdCount="+reqdCount+", size="+rslt.size());
            if (result.size >= requiredCount) return result
        }
        return null
    }

    /**
     * Build a set of sets (or groups) each outer set member is a set of coverage of
     * reasonably near RF emitters. Basically we are grouping the raw observations
     * into clumps based on how believably close together they are. An outlying emitter
     * will likely be put into its own group. Our caller will take the largest set as
     * the most believable group of observations to use to compute a position.
     *
     * @param locations A set of RF emitter coverage records
     * @return A set of coverage sets.
     */
    private fun divideInGroups(locations: Collection<Location>): Set<MutableSet<Location>> {
        val bins: MutableSet<MutableSet<Location>> = HashSet()

        // Create a bins
        for (location in locations) {
            val locGroup: MutableSet<Location> = HashSet()
            locGroup.add(location)
            bins.add(locGroup)
        }
        for (location in locations) {
            for (locGroup in bins) {
                if (locationCompatibleWithGroup(location, locGroup)) {
                    locGroup.add(location)
                }
            }
        }
        return bins
    }

    /**
     * Check to see if the coverage area (location) of an RF emitter is close
     * enough to others in a group that we can believably add it to the group.
     * @param location The coverage area of the candidate emitter
     * @param locGroup The coverage areas of the emitters already in the group
     * @return True if location is close to others in group
     */
    private fun locationCompatibleWithGroup(location: Location, locGroup: Set<Location>): Boolean {

        // If the location is within range of all current members of the
        // group, then we are compatible.
        for (other in locGroup) {
            val testDistance = (location.distanceTo(other) -
                    location.accuracy -
                    other.accuracy).toDouble()
            if (testDistance > 0.0) {
                //Log.d(TAG,"locationCompatibleWithGroup(): "+testDistance);
                return false
            }
        }
        return true
    }

    /**
     * We bulk up operations to reduce writing to flash memory. And there really isn't
     * much need to report location to microG/UnifiedNlp more often than once every three
     * or four seconds. Another reason is that we can average more samples into each
     * report so there is a chance that our position computation is more accurate.
     */
    private fun endOfPeriodProcessing() {
        if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - Starting new process period.")

        // Estimate location using weighted average of the most recent
        // observations from the set of RF emitters we have seen. We cull
        // the locations based on distance from each other to reduce the
        // chance that a moved/moving emitter will be used in the computation.
        val locations: Collection<Location>? = culledEmitters(getRfLocations(seenSet))
        val weightedAverageLocation = computePosition(locations)
        if (weightedAverageLocation != null && notNullIsland(weightedAverageLocation)) {
            //Log.d(TAG, "endOfPeriodProcessing(): " + weightedAverageLocation.toString());
            report(weightedAverageLocation)
        }

        // load all emitters into the cache to avoid several single database transactions
        emitterCache!!.loadIds(seenSet)

        // Increment the trust of the emitters we've seen and decrement the trust
        // of the emitters we expected to see but didn't.
        for (id in seenSet) {
            val e = emitterCache!![id]
            e?.incrementTrust()
        }

        // If we are dealing with very movable emitters, then try to detect ones that
        // have moved out of the area. We do that by collecting the set of emitters
        // that we expected to see in this area based on the GPS and our own location
        // computation.
        val expectedSet: MutableSet<RfIdentification> = HashSet()
        if (weightedAverageLocation != null // getExpected is slow when the database is large, so don't check every time
            && (getRfLocations(seenSet).size - locations!!.size > 2 || SystemClock.elapsedRealtime() % 8 == 1L)
        ) {
            // we create expectedSet exclusively to decrease trust of emitters that are not founf
            //   so there is no need to add emitters that have
            // TODO 1: there should be more type like lte or 5g
            // TODO 2: limit to types the device is (currently) capable of?
            //  e.g. don't look for LTE if the device is not LTE capable, or it is switched to 2g only
            //  also don't look for wifis if wifi is off!
            //   this is important...
            //   how to detect difference between wifi off and wifi really off (no background scanning)?
            //   plus, wifis should be skipped if last scan was found to have returned an old list
            emitterCache!!.sync() // getExpected() ends bypassing the cache, so sync first
            for (emitterType in EmitterType.values()) {
                if (getRfCharacteristics(emitterType).decreaseTrust == 0)
                    continue
                expectedSet.addAll(getExpected(weightedAverageLocation, emitterType))
            }
            // only do if gps location has updated since the last period
            if (gpsLocation?.timeOfUpdate ?: 0 > oldLocationUpdate) {
                val location = gpsLocation!!.location
                for (emitterType in EmitterType.values()) {
                    if (getRfCharacteristics(emitterType).decreaseTrust == 0)
                        continue
                    expectedSet.addAll(getExpected(location, emitterType))
                }
            }
        }
        for (identification in expectedSet) {
            if (!seenSet.contains(identification)) {
                val emitter = emitterCache!![identification]
                emitter?.decrementTrust()
            }
        }

        // Sync all of our changes to the on flash database and reset the RF emitters we've seen.
        emitterCache!!.sync()
        seenSet.clear()
        oldLocationUpdate = gpsLocation?.timeOfUpdate ?: 0L
    }

    /**
     * Add all the RF emitters of the specified type within the specified bounding
     * box to the set of emitters we expect to see. This is used to age out emitters
     * that may have changed locations (or gone off the air). When aged out we
     * can remove them from our database.
     *
     * @param loc The location we think we are at.
     * @param rfType The type of RF emitters we expect to see within the bounding
     * box.
     * @return A set of IDs for the RF emitters we should expect in this location.
     */
    private fun getExpected(loc: Location?, rfType: EmitterType): Set<RfIdentification> {
        val rfChar = getRfCharacteristics(rfType)
        if (loc == null || loc.accuracy > rfChar.typicalRange) return HashSet()
        val bb = BoundingBox(loc.latitude, loc.longitude, rfChar.typicalRange)
        return emitterCache!!.getEmitters(rfType, bb)
    }

    companion object {
        private val DEBUG = BuildConfig.DEBUG

        private const val TAG = "DejaVu Backend"
        const val LOCATION_PROVIDER = "DejaVu"
        private val myPerms = arrayOf(
            permission.ACCESS_WIFI_STATE, permission.CHANGE_WIFI_STATE,
            permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION
        )
        const val DEG_TO_METER = 111225.0
        const val METER_TO_DEG = 1.0 / DEG_TO_METER
        const val MIN_COS = 0.01 // for things that are dividing by the cosine

        // Define range of received signal strength to be used for all emitter types.
        // Basically use the same range of values for LTE and WiFi as GSM defaults to.
        const val MAXIMUM_ASU = 31
        const val MINIMUM_ASU = 1

        // KPH -> Meters/millisec (KPH * 1000) / (60*60*1000) -> KPH/3600
        const val EXPECTED_SPEED = 120.0f / 3600 // 120KPH (74 MPH)
        private const val NULL_ISLAND_DISTANCE = 1000f
        private val nullIsland = Location(LOCATION_PROVIDER).apply {
            latitude = 0.0
            longitude = 0.0
        }

        /**
         * Process noise for lat and lon.
         *
         * We do not have an accelerometer, so process noise ought to be large enough
         * to account for reasonable changes in vehicle speed. Assume 0 to 100 kph in
         * 5 seconds (20kph/sec ~= 5.6 m/s**2 acceleration). Or the reverse, 6 m/s**2
         * is about 0-130 kph in 6 seconds
         */
        private const val GPS_COORDINATE_NOISE = 3.0
        private const val POSITION_COORDINATE_NOISE = 6.0
        private var instance: BackendService? = null

        // Stuff for scanning WiFi APs
        private val wifiBroadcastFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)

        //
        // Scanning and reporting are resource intensive operations, so we throttle
        // them. Ideally the intervals should be multiples of one another.
        //
        // We are triggered by external events, so we really don't run periodically.
        // So these numbers are the minimum time. Actual will be at least that based
        // on when we get GPS locations and/or update requests from microG/UnifiedNlp.
        //
        // values are milliseconds
        private const val REPORTING_INTERVAL: Long = 3500 // a bit decreased from original
        private const val MOBILE_SCAN_INTERVAL = REPORTING_INTERVAL / 2 - 100 // scans are rather fast, but are likely to update slowly
        private const val WLAN_SCAN_INTERVAL = REPORTING_INTERVAL - 100 // scans are slow, ca 2.5 s, and a higher interval does not make sense
        //
        // Other public methods
        //
        /**
         * Called by Android when a GPS location reports becomes available.
         *
         * @param locReport The current GPS position estimate
         */
        fun instanceGpsLocationUpdated(locReport: Location) {
            //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
            instance?.onGpsChanged(locReport)
        }

        /**
         * Check if location too close to null island to be real
         *
         * @param loc The location to be checked
         * @return boolean True if away from lat,lon of 0,0
         */
        fun notNullIsland(loc: Location?): Boolean {
            return nullIsland.distanceTo(loc) > NULL_ISLAND_DISTANCE
        }

        /**
         * This seems like it ought to be in ScanResult but I get an unidentified error
         * @param sr Result from a WLAN/WiFi scan
         * @return True if in the 5GHZ range
         */
        fun is5GHz(sr: ScanResult): Boolean {
            val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && sr.channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ)
                    sr.centerFreq0
                else
                    sr.frequency
            return freq > 2500
        }
    }
}