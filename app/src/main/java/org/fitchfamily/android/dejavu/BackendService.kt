package org.fitchfamily.android.dejavu

/*
*    DejaVu - A location provider backend for microG/UnifiedNlp
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

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import android.telephony.*
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import kotlinx.coroutines.*
import org.microg.nlp.api.LocationBackendService
import org.microg.nlp.api.MPermissionHelperActivity
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Created by tfitch on 8/27/17.
 * modified by helium314 in 2022
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
    private var nextMobileScanTime: Long = 0// when the next mobile scan may be started (measured in elapsedRealtime)
    private var nextWlanScanTime: Long = 0 // when the next WiFi scan may be started (measured in elapsedRealtime)
    private var oldLocationUpdate = 0L // time of the most recent GPS update in the previous processing period
    private val oldScanResults = mutableListOf<ScanResult>() // results of previous wifi scan

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
        prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (prefs.getString("build", "") != Build.FINGERPRINT) {
            // remove usual ASU values if build changed
            // because results might be different in a different build
            prefs.edit().apply {
                putString("build", Build.FINGERPRINT)
                EmitterType.values().forEach {
                    remove("ASU_$it")
                }
            }.apply()
        }
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
     * Scan for mobile (cell) towers in a coroutine
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
            queueForProcessing(observations)
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

        // Try recent API to get all cell information, or fall back to deprecated method
        val allCells: List<CellInfo> = try {
            telephonyManager!!.allCellInfo ?: emptyList()
        } catch (e: NoSuchMethodError) {
            emptyList()
            // Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().");
        }
        if (allCells.isEmpty()) return deprecatedGetMobileTowers()

        val fallbackMnc by lazy {
            Log.i(TAG, "getMobileTowers(): using fallback mnc")
            telephonyManager!!.networkOperator?.let { if (it.length > 4) it.substring(3) else null }
        }
        if (DEBUG) Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned " + allCells.size + " records.")
        val uptimeNanos = System.nanoTime()
        val realtimeNanos = SystemClock.elapsedRealtimeNanos()
        for (info in allCells) {
            // todo: id.earfcn / arfcn added in api24, can be mapped to frequency and could help with range estimation
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
                    else id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString?.substringAfter("0")
                    else id.mnc.takeIf { it != intMax }?.toString()
                    ) ?: fallbackMnc ?: continue

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
                    else id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString?.substringAfter("0")
                    else id.mnc.takeIf { it != intMax }?.toString()
                    ) ?: fallbackMnc ?: continue

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
                    else id.mcc.takeIf { it != intMax }?.toString() ?: continue
                val mncString: String = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString?.substringAfter("0")
                    else id.mnc.takeIf { it != intMax }?.toString()
                    ) ?: fallbackMnc ?: continue

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
                // todo: add NR and TDSCDMA after api update
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
     * Method removed in API 29
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
        oldScanResults.clear()
        oldScanResults.addAll(scanResults)
    }

    /**
     * Check whether scan results are (probably) the same. Only SSID, BSSID, signal level and
     * timestamp are compared for each result, plus the length and order of the list.
     * For some reason newResults == oldResults equals didn't work when testing.
     */
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

    //
    //    Generic private methods
    //

    /**
     * Add a collection of observations to our background thread's work queue. If
     * no thread currently exists, start one.
     *
     * @param observations A set of RF emitter observations (all must be of the same type)
     */
    // @Synchronized // todo: synchronized probably not necessary, check if this is true
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
        if (!backgroundJob.isActive) {
            if (DEBUG) Log.d(TAG, "queueForProcessing() - Starting new background job")
            backgroundJob = scope.launch {
                var myWork = workQueue.poll()
                while (myWork != null) {
                    backgroundProcessing(myWork)
                    myWork = workQueue.poll()
                }
            }
        }
    }

    /**
     * Wait one REPORTING_INTERVAL and start processing, unless waiting is already happening
     */
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
            // actually this might be useless because backgroundProcessing and endOfPeriod are both @Synchronized
            if (backgroundJob.isActive) {
                if (DEBUG) Log.d(TAG, "Delaying endOfPeriodProcessing because background processing not yet done")
                delay(30) // usually done in 2-20 ms
            }
            endOfPeriodProcessing()
        }
    }

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
        val weightedAverageLocation = culledEmitters(getRfLocations(seenSet))?.weightedAverage()
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
    private fun getRfLocations(rfIds: Collection<RfIdentification>): List<RfLocation> {
        emitterCache!!.loadIds(rfIds)
        val locations = rfIds.mapNotNull { emitterCache!![it].location }
        if (DEBUG) Log.d(TAG, "getRfLocations() - returning ${locations.size} locations")
        return locations
    }

    companion object {
        private var instance: BackendService? = null

        /**
         * Called by Android when a GPS location reports becomes available.
         *
         * @param locReport The current GPS position estimate
         */
        fun instanceGpsLocationUpdated(locReport: Location) {
            //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
            instance?.onGpsChanged(locReport)
        }

        // stores the previous asu result, or 0 if asu is not always the same
        private val asuMap = HashMap<EmitterType, Int>()
        private lateinit var prefs: SharedPreferences

        // sets asu to 1 if the emitter type always reports the same asu
        // problem likely depends on phone model
        /**
         * Some phone models seem to have problems with reporting asu, and
         * simply report some default value. This function checks and stores whether
         * asu of an EmitterType is always the same value, and returns MINIMUM_ASU in this case
         */
        fun EmitterType.getCorrectedAsu(asu: Int): Int {
            when (asuMap[this]) {
                0 -> return asu
                asu -> return MINIMUM_ASU
                null -> { // need to fill map
                    val prefKey = "ASU_$this"
                    if (prefs.contains(prefKey)) { // just insert value and call again
                        asuMap[this] = prefs.getInt(prefKey, 0)
                        return this.getCorrectedAsu(asu)
                    }
                    // first time we see this emitter type, store asu and trust the result
                    prefs.edit().putInt(prefKey, asu).apply()
                    asuMap[this] = asu
                    return asu
                }
                else -> { // not always the same asu -> set to 0
                    val prefKey = "ASU_$this"
                    asuMap[this] = 0
                    prefs.edit().putInt(prefKey, 0).apply()
                    return asu
                }
            }
        }

    }

}

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

private val DEBUG = BuildConfig.DEBUG

private const val TAG = "DejaVu Backend"
private val myPerms = arrayOf(
    permission.ACCESS_WIFI_STATE, permission.CHANGE_WIFI_STATE,
    permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION
)

// Stuff for scanning WiFi APs
private val wifiBroadcastFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)

// shorter name because it's checked often
private const val intMax = Int.MAX_VALUE
