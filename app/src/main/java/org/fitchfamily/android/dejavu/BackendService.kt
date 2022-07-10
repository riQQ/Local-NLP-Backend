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
import kotlinx.coroutines.*
import org.microg.nlp.api.LocationBackendService
import org.microg.nlp.api.MPermissionHelperActivity
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Created by tfitch on 8/27/17.
 */
class BackendService : LocationBackendService() {
    private var gpsMonitorRunning = false
    private var wifiBroadcastReceiverRegistered = false
    private var permissionsOkay = true

    private var wifiScanInProgress = false
    private var telephonyManager: TelephonyManager? = null
    private val wifiManager: WifiManager by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }
    private val wifiBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scanSuccessful = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    intent.extras?.getBoolean(WifiManager.EXTRA_RESULTS_UPDATED)
                else null
            // don't call onWiFisChanged() if scan wasn't successful
            if (scanSuccessful != false)
                scope.launch {
                    onWiFisChanged(scanSuccessful == true)
                    if (DEBUG) Log.d(TAG, "onReceive: gathered WiFi scan results, to be processed in background")
                    wifiScanInProgress = false // set after observations are queued for processing
                }
            else {
                if (DEBUG) Log.d(TAG, "onReceive: received WiFi scan result intent, but scan not successful")
                wifiScanInProgress = false
            }
        }
    }
    private var gpsLocation: Kalman? = null // Filtered GPS (because GPS is so bad on Moto G4 Play)
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private var mobileJob = scope.launch { }
    private var backgroundJob: Job = scope.launch { }
    private var periodicProcessing: Job = scope.launch {  }

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
    private var oldLocationUpdate = 0L // store last update of the previous period to allow checking whether gpsLocation has changed
    private var oldScanResults = listOf<ScanResult>() // results of previous wifi scan

    //
    // We want only a single background thread to do all the work but we have a couple
    // of asynchronous inputs. So put everything into a work item queue. . . and have
    // a single server pull and process the information.
    //
    private inner class WorkItem(
        var observations: Collection<Observation>,
        var loc: Location?,
    )

    private val workQueue: Queue<WorkItem> = ConcurrentLinkedQueue()

    // information about current device capabilities
    private val airplaneMode get() = Settings.Global.getInt(applicationContext.contentResolver,
        Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    private val canScanWifi get() = wifiManager.isWifiEnabled || (wifiManager.isScanAlwaysAvailable && !airplaneMode)

    /**
     * We are starting to run, get the resources we need to do our job.
     */
    override fun onOpen() {
        Log.d(TAG, "onOpen() entry.")
        super.onOpen()
        instance = this
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
        // cancel jobs, and not the coroutine
        mobileJob.cancel()
        backgroundJob.cancel()
        periodicProcessing.cancel()
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
            if (DEBUG) Log.d(TAG, "update() - NLP asking for location")
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
                if (DEBUG) Log.d(TAG, "onGpsChanged() entry.")
                if (gpsLocation == null)
                    gpsLocation = Kalman(update, GPS_COORDINATE_NOISE)
                else
                    gpsLocation?.update(update)
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
            startProcessingPeriodIfNecessary()
            startWiFiScan()
            startMobileScan()
        }
    }

    private fun startProcessingPeriodIfNecessary() {
        if (emitterCache == null) {
            if (DEBUG) Log.d(TAG, "emitterCache is null: creating")
            emitterCache = Cache(this)
        }
        if (periodicProcessing.isActive) return
        periodicProcessing = scope.launch(Dispatchers.IO) { // IO because there is a lot of wait and db access
            if (DEBUG) Log.d(TAG, "starting new processing period")
            delay(REPORTING_INTERVAL)
            // delay a bit more if wifi scan is still running
            if (wifiScanInProgress) {
                if (DEBUG) Log.d(TAG, "Delaying endOfPeriodProcessing because WiFi scan in progress")
                val waitUntil = nextWlanScanTime + 1000 // delay at max until 1 sec after wifi scan should be finished
                while (SystemClock.elapsedRealtime() < waitUntil) {
                    delay(50)
                    if (!wifiScanInProgress) break
                }
            }
            // delay somewhat more if there are still observations being processed
            // actually this might be useless because processing and endOfPeriod are both @Synchronized
            if (backgroundJob.isActive) {
                if (DEBUG) Log.d(TAG, "Delaying endOfPeriodProcessing because background processing not yet done")
                delay(30) // usually done in 2-20 ms
            }
            endOfPeriodProcessing()
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
        // in case wifi scan doesn't return anything for a long time we simply allow starting another one
        if (!wifiScanInProgress || currentProcessTime > nextWlanScanTime + 3 * WLAN_SCAN_INTERVAL) {
            nextWlanScanTime = currentProcessTime + WLAN_SCAN_INTERVAL
            if (canScanWifi) {
                if (DEBUG) Log.d(TAG, "startWiFiScan() - Starting WiFi collection.")
                wifiScanInProgress = true
                wifiManager.startScan()
            } else if (DEBUG) Log.d(TAG, "startWiFiScan() - WiFi scan is disabled.")
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
        // coverage area for several seconds. No need to sample more often than that and we save
        // resources on the phone.
        val currentProcessTime = SystemClock.elapsedRealtime()
        if (currentProcessTime < nextMobileScanTime || mobileJob.isActive) {
            if (DEBUG) Log.d(TAG, "startMobileScan() - need to wait before starting next scan")
            return
        }
        nextMobileScanTime = currentProcessTime + MOBILE_SCAN_INTERVAL

        // Scanning towers may take some time (does it really?), so do it in background
        if (!airplaneMode) {
            if (DEBUG) Log.d(TAG,"startMobileScan() - Starting mobile signal scan.")
            mobileJob = scope.launch { scanMobile() }
        } else if (DEBUG) Log.d(TAG,"startMobileScan() - Airplane mode is enabled.")
    }

    /**
     * Scan for the mobile (cell) towers the phone sees. If we see any, then add them
     * to the queue for background processing.
     */
    private fun scanMobile() {
        // if (DEBUG) Log.d(TAG, "scanMobile() - calling getMobileTowers().")
        val observations: Collection<Observation> = getMobileTowers()
        if (observations.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "scanMobile() - " + observations.size + " records to be queued for processing.")
            queueForProcessing(observations/*, SystemClock.elapsedRealtime()*/)
        } else if (DEBUG) Log.d(TAG, "scanMobile() - no results")
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
            if (DEBUG) Log.d(TAG, "getMobileTowers(): telephony manager was null")
        }
        val observations = hashSetOf<Observation>()

        // Try most recent API to get all cell information
        val allCells: List<CellInfo> = try {
            telephonyManager!!.allCellInfo ?: emptyList()
        } catch (e: NoSuchMethodError) {
            emptyList()
            // Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().");
        }
        if (allCells.isEmpty()) return deprecatedGetMobileTowers()

        val alternativeMnc by lazy { // determine mnc the other way not more than once per call of getMobileTowers
            telephonyManager!!.networkOperator?.let { if (it.length > 4) it.substring(3) else null }
        }
        if (DEBUG) Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned " + allCells.size + " records.")
        val uptimeNanos = System.nanoTime()
        val realtimeNanos = SystemClock.elapsedRealtimeNanos()
        for (info in allCells) {
            // todo: id.earfcn / arfcn added in api24, can be mapped to frequency and could gelp in range estimation
            //  https://www.cablefree.net/wirelesstechnology/4glte/lte-carrier-frequency-earfcn/
            //  https://en.wikipedia.org/wiki/Absolute_radio-frequency_channel_number
            if (DEBUG) Log.v(TAG, "getMobileTowers(): inputCellInfo: $info")
            val idStr: String
            val asu: Int
            val type: EmitterType
            if (info is CellInfoLte) {
                val id = info.cellIdentity

                // get mnc and mcc as strings if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else
                        id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString?.substringAfter("0")
                    else
                        id.mnc.takeIf { it != intMax }?.toString()
                    ) ?: alternativeMnc ?: continue

                // CellIdentityLte accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.ci == intMax || id.pci == intMax || id.tac == intMax)
                    continue

                idStr = "${EmitterType.LTE}/$mccString/$mncString/${id.ci}/${id.pci}/${id.tac}"
                asu = info.cellSignalStrength.asuLevel * MAXIMUM_ASU / 97
                type = EmitterType.LTE
            } else if (info is CellInfoGsm) {
                val id = info.cellIdentity

                // get mnc and mcc as strings if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else
                        id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            id.mncString?.substringAfter("0")
                    else
                            id.mnc.takeIf { it != intMax }?.toString()
                    ) ?: alternativeMnc ?: continue

                // CellIdentityGsm accessors all state Integer.MAX_VALUE is returned for unknown values.
                // analysis of results show frequent (invalid!) LAC of 0 messing with results, so ignore it
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                idStr = "${EmitterType.GSM}/$mccString/$mncString/${id.lac}/${id.cid}"
                asu = info.cellSignalStrength.asuLevel
                type = EmitterType.GSM
            } else if (info is CellInfoWcdma) {
                val id = info.cellIdentity

                // get mnc and mcc as strings if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else
                        id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString?.substringAfter("0")
                    else
                        id.mnc.takeIf { it != intMax }?.toString()
                    ) ?: alternativeMnc ?: continue

                // CellIdentityWcdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                idStr = "${EmitterType.WCDMA}/$mccString/$mncString/${id.lac}/${id.cid}"
                asu = info.cellSignalStrength.asuLevel
                type = EmitterType.WCDMA

            } else if (info is CellInfoCdma) {
                val id = info.cellIdentity
                // CellIdentityCdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.networkId == intMax || id.systemId == intMax || id.basestationId == intMax)
                    continue

                idStr = "${EmitterType.CDMA}/${id.networkId}/${id.systemId}/${id.basestationId}"
                asu = info.cellSignalStrength.asuLevel
                type = EmitterType.CDMA
            } else {
                Log.d(TAG, "getMobileTowers(): Unsupported Cell type: $info")
                continue
            }
            // for some reason, timestamp for cellInfo is like uptimeMillis (not advancing during sleep),
            // but wifi scanResult.timestamp is like elapsedRealtime (advancing during sleep)
            // since we need the latter for location time, convert it
            // (documentation is not clear about this, and actually indicates the latter... but tests show it's the former)
            val o = Observation(idStr, type, asu, realtimeNanos - uptimeNanos + info.timeStamp)
            observations.add(o)
            if (DEBUG) Log.d(TAG, "valid observation string: $idStr, asu $asu")
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
        val timeNanos = SystemClock.elapsedRealtimeNanos()
        if (info != null && info is GsmCellLocation) {
            val idStr = "${EmitterType.GSM}/$mcc/$mnc/${info.lac}/${info.cid}"
            val o = Observation(idStr, EmitterType.GSM, MINIMUM_ASU, timeNanos)
            observations.add(o)
        } else {
            if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): getCellLocation() returned null or not GsmCellLocation.")
        }
        try {
            val neighbors = telephonyManager!!.neighboringCellInfo
            if (neighbors != null && neighbors.isNotEmpty()) {
                for (neighbor in neighbors) {
                    if (neighbor.cid > 0 && neighbor.lac > 0) {
                        val idStr = "${EmitterType.GSM}/$mcc/$mnc/${neighbor.lac}/${neighbor.cid}"
                        val o = Observation(idStr, EmitterType.GSM, neighbor.rssi, timeNanos)
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

    // Stuff for binding to (basically starting) background AP location collection
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
    private fun onWiFisChanged(definitelyNewResults: Boolean) {
        if (emitterCache == null) {
            return
        }
        val scanResults = wifiManager.scanResults
        if (!definitelyNewResults && scanResults.sameAs(oldScanResults)) {
            // don't continue if scan results didn't change
            if (DEBUG) Log.d(TAG, "onWiFisChanged(): scan results are the same as previous results, discarding")
            return
        }
        if (DEBUG) Log.d(TAG, "onWiFisChanged(): " + scanResults.size + " scan results")

        val observations = scanResults.map { scanResult ->
            if (DEBUG) Log.v(TAG, "rfType=${scanResult.getWifiType()}, ScanResult: $scanResult")
            Observation(
                scanResult.BSSID.lowercase().replace(".", ":"),
                scanResult.getWifiType(),
                WifiManager.calculateSignalLevel(scanResult.level, MAXIMUM_ASU),
                scanResult.timestamp * 1000, // timestamp is elapsedRealtime when WiFi was last seen, in microseconds
                scanResult.SSID,
            )
        }
        if (observations.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "onWiFisChanged(): " + observations.size + " observations")
            queueForProcessing(observations)
        }
        oldScanResults = scanResults
    }

    // for some reason newResults == oldResults equals didn't work when testing
    private fun List<ScanResult>.sameAs(oldResults: List<ScanResult>): Boolean {
        if (size != oldResults.size) return false
        for (i in 0 until size) {
            val new = get(i)
            val old = oldResults[i]
            // compare only main attributes. No change in list order and signal level is likely enough to decide it's unchanged
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
     */
    @Synchronized
    private fun queueForProcessing(observations: Collection<Observation>) {
        // ignore location if it is old (i.e. from a previous processing period),
        // we don't want to update emitters using outdated locations
        val loc = gpsLocation?.let {
            if (it.timeOfUpdate > oldLocationUpdate && notNullIsland(it.location)) it.location
            else {
                if (DEBUG) Log.d(TAG,"queueForProcessing() - Location too old or near null island, ${it.timeOfUpdate - oldLocationUpdate}ms")
                null
            }
        }
        val work = WorkItem(observations, loc)
        workQueue.offer(work)
        if (backgroundJob.isActive)
            return
        if (DEBUG) Log.d(TAG,"queueForProcessing() - Starting new background job")
        backgroundJob = scope.launch {
            var myWork = workQueue.poll()
            while (myWork != null) {
                backgroundProcessing(myWork)
                myWork = workQueue.poll()
            }
        }
    }

    //
    //    Generic private methods
    //

    /**
     * Process a group of observations. Process in this context means
     * 1. Add the emitters to the set of emitters we have seen in this processing period.
     * 2. If the GPS is accurate enough, update our coverage estimates for the emitters.
     * 3. Compute a position based on the current observations.
     * 4. If our collection period is over, report our position to microG/UnifiedNlp and
     * synchronize our information with the flash based database.
     *
     * @param myWork
     */
    @Synchronized
    private fun backgroundProcessing(myWork: WorkItem) {
        if (emitterCache == null) return
        val emitters = HashSet<RfEmitter>()

        // load all emitters into the cache to avoid several single database transactions
        emitterCache!!.loadIds(myWork.observations.map { it.identification })

        // Remember all the emitters we've seen during this processing period
        // and build a set of emitter objects for each RF emitter in the
        // observation set.
        for (observation in myWork.observations) {
            seenSet.add(observation.identification)
            val emitter = emitterCache!![observation.identification]
            emitter.lastObservation = observation
            emitters.add(emitter)
        }

        startProcessingPeriodIfNecessary()

        // Update emitter coverage based on GPS as needed and get the set of locations
        // the emitters are known to be seen at.
        updateEmitters(emitters, myWork.loc)
    }

    /**
     * Update the coverage estimates for the emitters we have just gotten observations for.
     *
     * @param emitters The emitters we have just observed
     * @param gps The GPS position at the time the observations were collected.
     */
    private fun updateEmitters(emitters: Collection<RfEmitter>, gps: Location?) {
        if (gps == null) return
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
    private fun getRfLocations(rfIds: Collection<RfIdentification>): List<Location> {
        emitterCache!!.loadIds(rfIds)
        val locations = rfIds.mapNotNull { emitterCache!![it].location }
        if (DEBUG) Log.d(TAG, "getRfLocations() - returning ${locations.size} locations")
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
     * If we are at the extreme limit of possible coverage (maximumRange)
     * from two emitters then those emitters could be a distance of 2*maximumRange apart.
     * So we will group the emitters based on that large distance.
     *
     * @param locations A collection of the coverages for the current observation set
     * @return The largest set of coverages found within the raw observations. That is
     * the most believable set of coverage areas.
     */
    private fun culledEmitters(locations: Collection<Location>): Set<Location>? {
        divideInGroups(locations).maxByOrNull { it.size }?.let { result ->
            // if we only have one location, use it as long as it's not an invalid emitter
            if (locations.size == 1 && result.single().extras.getString(LOC_RF_TYPE, EmitterType.INVALID.toString()) != EmitterType.INVALID.toString()) {
                if (DEBUG) Log.d(TAG, "culledEmitters() - got only one location, use it")
                return result
            }
            // Determine minimum count for a valid group of emitters.
            // The RfEmitter class will have put the min count into the location
            // it provided.
            result.forEach {
                if (result.size >= it.extras.getInt(LOC_MIN_COUNT, 9999))
                    return result
            }
            if (DEBUG) Log.d(TAG, "culledEmitters() - only got ${result.size}, but " +
                    "${result.minByOrNull { it.extras.getInt(LOC_MIN_COUNT, 9999) }} are required")
        }
        return null
    }

    /**
     * Build a list of sets (or groups) each outer set member is a set of coverage of
     * reasonably near RF emitters. Basically we are grouping the raw observations
     * into clumps based on how believably close together they are. An outlying emitter
     * will likely be put into its own group. Our caller will take the largest set as
     * the most believable group of observations to use to compute a position.
     *
     * @param locations A set of RF emitter coverage records
     * @return A list of coverage sets.
     */
    private fun divideInGroups(locations: Collection<Location>): List<MutableSet<Location>> {
        // Create bins
        val bins = locations.map { hashSetOf(it) }
        for (location in locations) {
            for (locationGroup in bins) {
                if (locationCompatibleWithGroup(location, locationGroup)) {
                    locationGroup.add(location)
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
            if (approximateDistance(location, other.latitude, other.longitude) > location.accuracy + other.accuracy) {
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
    @Synchronized
    private fun endOfPeriodProcessing() {
        if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - end of current period.")
        if (seenSet.isEmpty()) {
            if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - no emitters seen.")
            oldLocationUpdate = gpsLocation?.timeOfUpdate ?: 0L
            return
        }

        // Estimate location using weighted average of the most recent
        // observations from the set of RF emitters we have seen. We cull
        // the locations based on distance from each other to reduce the
        // chance that a moved/moving emitter will be used in the computation.
        val locations: Collection<Location>? = culledEmitters(getRfLocations(seenSet))
        val weightedAverageLocation = computePosition(locations)
        if (weightedAverageLocation != null && notNullIsland(weightedAverageLocation)) {
            if (DEBUG) Log.d(TAG, "endOfPeriodProcessing(): reporting location")
            report(weightedAverageLocation)
        } else
            if (DEBUG) Log.d(TAG, "endOfPeriodProcessing(): no location to report")

        // Sync all of our changes to the on flash database and reset the RF emitters we've seen.
        emitterCache!!.sync()
        seenSet.clear()
        oldLocationUpdate = gpsLocation?.timeOfUpdate ?: 0L
    }

    companion object {
        /**
         * Called by Android when a GPS location reports becomes available.
         *
         * @param locReport The current GPS position estimate
         */
        fun instanceGpsLocationUpdated(locReport: Location) {
            //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
            instance?.onGpsChanged(locReport)
        }
    }

}

private val DEBUG = BuildConfig.DEBUG

private const val TAG = "DejaVu Backend"
const val LOCATION_PROVIDER = "DejaVu"
private val myPerms = arrayOf(
    permission.ACCESS_WIFI_STATE, permission.CHANGE_WIFI_STATE,
    permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION
)
// DEG_TO_METER is only approximate, but an error of 1% is acceptable
//  for latitude it depends on latitude, from ~110500 (equator) ~111700 (poles)
//  for longitude at equator it's ~111300
const val DEG_TO_METER = 111225.0
const val METER_TO_DEG = 1.0 / DEG_TO_METER
const val MIN_COS = 0.01 // for things that are dividing by the cosine

// Define range of received signal strength to be used for all emitter types.
// Basically use the same range of values for LTE and WiFi as GSM defaults to.
const val MAXIMUM_ASU = 31
const val MINIMUM_ASU = 1

// KPH -> Meters/millisec (KPH * 1000) / (60*60*1000) -> KPH/3600
//        const val EXPECTED_SPEED = 120.0f / 3600 // 120KPH (74 MPH)
private const val NULL_ISLAND_DISTANCE = 1000f
private const val intMax = Int.MAX_VALUE

/**
 * Process noise for lat and lon.
 *
 * We do not have an accelerometer, so process noise ought to be large enough
 * to account for reasonable changes in vehicle speed. Assume 0 to 100 kph in
 * 5 seconds (20kph/sec ~= 5.6 m/s**2 acceleration). Or the reverse, 6 m/s**2
 * is about 0-130 kph in 6 seconds
 */
private const val GPS_COORDINATE_NOISE = 3.0
//private const val POSITION_COORDINATE_NOISE = 6.0
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
private const val REPORTING_INTERVAL: Long = 3500 // a bit increased from original
private const val MOBILE_SCAN_INTERVAL = REPORTING_INTERVAL / 2 - 100 // scans are rather fast, but are likely to update slowly
private const val WLAN_SCAN_INTERVAL = REPORTING_INTERVAL - 100 // scans are slow, ca 2.5 s, and a shorter interval does not make sense

// much faster than location.distanceTo(otherLocation)
// and less than 0.1% difference the small (< 1°) distances we're interested in
// (much more inaccurate if latitude changes
fun approximateDistance(loc1: Location, lat2: Double, lon2: Double): Double {
    val distLat = (loc1.latitude - lat2) * DEG_TO_METER
    val distLon = (loc1.longitude - lon2) * DEG_TO_METER * cos(Math.toRadians(loc1.latitude))
    return sqrt(distLat * distLat + distLon * distLon)
}

/**
 * Check if location too close to null island to be real
 *
 * @param loc The location to be checked
 * @return boolean True if away from lat,lon of 0,0
 */
fun notNullIsland(loc: Location): Boolean {
    return approximateDistance(loc, 0.0, 0.0) > NULL_ISLAND_DISTANCE
}

// wifiManager.is6GHzBandSupported might be called to check whether it can be WLAN6
// but wifiManager.is5GHzBandSupported incorrectly returns no on my device, so better don't trust it
// and actually there might be a better way of doing this...
fun ScanResult.getWifiType(): EmitterType =
    when {
        frequency < 3000 -> EmitterType.WLAN2 // 2401 - 2495 MHz
        // 5945 can be WLAN5 and WLAN6, simply don't bother and assume WLAN5 for now
        frequency <= 5945 -> EmitterType.WLAN5 // 5030 - 5990 MHz, but at 5945 WLAN6 starts
        frequency > 6000 -> EmitterType.WLAN6 // 5945 - 7125
        frequency % 10 == 5 -> EmitterType.WLAN6 // in the overlapping range, WLAN6 frequencies end with 5
        else -> EmitterType.WLAN5
    }
