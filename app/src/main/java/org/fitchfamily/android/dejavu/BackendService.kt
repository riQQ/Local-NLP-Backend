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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.preference.PreferenceManager
import android.telephony.*
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.microg.nlp.api.LocationBackendService
import org.microg.nlp.api.MPermissionHelperActivity

/**
 * Created by tfitch on 8/27/17.
 * modified by helium314 in 2022
 */
class BackendService : LocationBackendService() {
    private var gpsMonitorRunning = false
    private var wifiBroadcastReceiverRegistered = false
    private var permissionsOkay = true

    @Volatile private var wifiScanInProgress = false // will be re-ordered by compiler if not volatile, causing weird issues
    private var telephonyManager: TelephonyManager? = null
    private val wifiManager: WifiManager by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }
    private val wifiBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // don't call onWiFisChanged() if scan definitely wasn't successful
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || intent.extras?.getBoolean(WifiManager.EXTRA_RESULTS_UPDATED) != false)
                wifiJob = scope.launch {
                    onWiFisChanged()
                    if (DEBUG) Log.d(TAG, "onReceive() - gathered WiFi scan results to be queued for processing.")
                    wifiScanInProgress = false // definitely set after observations are queued for processing
                }
            else {
                if (DEBUG) Log.d(TAG, "onReceive() - received WiFi scan result intent, but scan not successful")
                // allow scanning for wifi again soon, but not immediately (sometimes unifiedNLP calls update() too frequently)
                wifiScanInProgress = false
            }
        }
    }
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var mobileJob = scope.launch { }
    private var wifiJob = scope.launch { }
    private var backgroundJob: Job = scope.launch { }
    private var periodicProcessing: Job = scope.launch { }

    //
    // Periodic process information.
    //
    // We keep a set of the WiFi APs we expected to see and ones we've seen and then
    // periodically adjust the trust. Ones we've seen we increment, ones we expected
    // to see but didn't we decrement.
    //
    private val seenSet = hashSetOf<RfIdentification>()
    private var emitterCache: Cache? = null
    /** when the next mobile scan may be started (measured in elapsedRealtime) */
    private var nextMobileScanTime: Long = 0
    /** when the next WiFi scan may be started (measured in elapsedRealtime) */
    private var nextWlanScanTime: Long = 0
    /** results of previous wifi scan by rfID */
    private var oldWifiSignalLevels = hashMapOf<String, Int>()
    /** Filtered GPS (because GPS is so bad on Moto G4 Play) */
    private var kalmanGpsLocation: Kalman? = null
    /** time of the most recent Kalman time in the previous processing period */
    @Volatile private var oldKalmanUpdate = 0L
    /** set to null at the end of each period, used if kalman is switched off */
    @Volatile private var lastGpsOfThisPeriod: Location? = null
    // for calling GpsMonitor
    private val localBroadcastManager by lazy { LocalBroadcastManager.getInstance(this) }

    // settings that are read often, so better copy them here
    // setting changes propagate here because settingsFragment closes BackendService, and thus it's re-opened
    private var useKalman = false
    private var wifiScanEnabled = true
    private var mobileScanEnabled = true
    private var cull = 0
    private var activeMode = false
    private var activeTimeout = 0L

    var settingsActivity: SettingsActivity? = null // crappy way for forwarding scan results

    /**
     * We are starting to run, get the resources we need to do our job.
     */
    public override fun onOpen() {
        Log.d(TAG, "onOpen() entry.")
        super.onOpen()
        instance = this
        nextMobileScanTime = 0
        nextWlanScanTime = 0
        wifiBroadcastReceiverRegistered = false
        wifiScanInProgress = false
        if (emitterCache == null)
            emitterCache = Cache(this)
        permissionsOkay = true
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getString(PREF_BUILD, "") != Build.FINGERPRINT) {
            // remove usual ASU values if build changed
            // because results might be different in a different build
            prefs.edit().apply {
                putString(PREF_BUILD, Build.FINGERPRINT)
                EmitterType.values().forEach {
                    remove("ASU_$it")
                }
            }.apply()
        }

        // take care to use default values from preferences.xml
        useKalman = prefs.getBoolean(PREF_KALMAN, false)
        wifiScanEnabled = prefs.getBoolean(PREF_WIFI, true)
        mobileScanEnabled = prefs.getBoolean(PREF_MOBILE, true)
        cull = prefs.getInt(PREF_CULL, 0)
        activeMode = prefs.getBoolean(PREF_ACTIVE_MODE, false)
        activeTimeout = (prefs.getString(PREF_ACTIVE_TIME, "10")?.toLongOrNull() ?: 10L) * 1000

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

    /** Closing down, release our dynamic resources. */
    @Synchronized
    public override fun onClose() {
        super.onClose()
        Log.d(TAG, "onClose()")
        if (wifiBroadcastReceiverRegistered) {
            unregisterReceiver(wifiBroadcastReceiver)
        }
        mobileJob.cancel() // cancelling scan jobs actually is useless, they aren't cancelable...
        wifiJob.cancel()
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
            val perms = mutableListOf<String>()
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
    public override fun update(): Location? {
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
    @Synchronized
    private fun onGpsChanged(update: Location) {
        if (permissionsOkay && notNullIsland(update)) {
            if (DEBUG) Log.d(TAG, "onGpsChanged(), accuracy ${update.accuracy}")
            if (useKalman)
                kalmanGpsLocation?.update(update) ?: run { kalmanGpsLocation = Kalman(update, GPS_COORDINATE_NOISE) }
            else
                lastGpsOfThisPeriod = update
            scanAllSensors()
        } else
            Log.d(TAG, "onGpsChanged() - Permissions not granted or location invalid, soft fail.")
    }

    /**
     * Kick off new scans for all the sensor types we know about. Typically scans
     * should occur asynchronously so we don't hang up our caller's thread.
     */
    @Synchronized
    private fun scanAllSensors() {
        if (emitterCache == null) {
            if (instance == null) { // this should not be necessary, but better make sure
                if (DEBUG) Log.d(TAG, "scanAllSensors() - instance is null")
                return
            }
            if (DEBUG) Log.d(TAG, "scanAllSensors() - emitterCache is null: creating")
            emitterCache = Cache(this)
        }

        if (DEBUG) Log.d(TAG, "scanAllSensors() - starting scans")
        val wifiScanStarted = startWiFiScan()
        val mobileScanStarted = startMobileScan()
        if (wifiScanStarted || mobileScanStarted)
            startProcessingPeriodIfNecessary() // only try starting new period if a scan was started
    }

    /**
     * Ask Android's WiFi manager to scan for access points (APs). When done the onWiFisChanged()
     * method will be called by Android.
     * @returns whether a scan was started
     */
    @Synchronized
    private fun startWiFiScan(): Boolean {
        // Throttle scanning for WiFi APs. In open terrain an AP could cover a kilometer.
        // Even in a vehicle moving at highway speeds it can take several seconds to traverse
        // the coverage area, no need to waste phone resources scanning too rapidly.
        val currentProcessTime = SystemClock.elapsedRealtime()
        if (currentProcessTime < nextWlanScanTime) {
            if (DEBUG) Log.d(TAG, "startWiFiScan() - need to wait before starting next scan")
            return false
        }
        // in case wifi scan doesn't return anything for a long time we simply allow starting another one
        if (wifiScanEnabled && (!wifiScanInProgress || currentProcessTime > nextWlanScanTime + 2 * WLAN_SCAN_INTERVAL)) {
            if (DEBUG) Log.d(TAG, "startWiFiScan() - Starting WiFi collection.")
            nextWlanScanTime = currentProcessTime + WLAN_SCAN_INTERVAL
            wifiScanInProgress = true
            // launch in background, because this can (rarely) take weirdly long for some reason,
            // and we don't want this blocking anything
            scope.launch { wifiManager.startScan() }
            return true
        } else if (DEBUG) Log.d(TAG, "startWiFiScan() - WiFi scan in progress or disabled, not starting.")
        return false
    }

    /**
     * Scan for mobile (cell) towers in a coroutine
     * @returns whether a scan was started
     */
    @Synchronized
    private fun startMobileScan(): Boolean {
        if (!mobileScanEnabled) {
            if (DEBUG) Log.d(TAG,"startMobileScan() - mobile scan disabled.")
            return false
        }
        // Throttle scanning for mobile towers. Generally each tower covers a significant amount
        // of terrain so even if we are moving fairly rapidly we should remain in a single tower's
        // coverage area for several seconds. No need to sample more often than that and we save
        // resources on the phone.
        val currentProcessTime = SystemClock.elapsedRealtime()
        if (currentProcessTime < nextMobileScanTime || (mobileJob.isActive && currentProcessTime < nextMobileScanTime + 60000)) {
            if (DEBUG) Log.d(TAG, "startMobileScan() - need to wait before starting next scan")
            return false
        }
        if (mobileJob.isActive)
            Log.w(TAG, "startMobileScan() - starting new scan while old scan job is active: something may be wrong")
        nextMobileScanTime = currentProcessTime + MOBILE_SCAN_INTERVAL

        mobileJob = scope.launch { scanMobile() }
        return true
    }

    /**
     * Scan for the mobile (cell) towers the phone sees. If we see any, then add them
     * to the queue for background processing.
     */
    private suspend fun scanMobile() {
        if (DEBUG) Log.d(TAG, "scanMobile() - calling getMobileTowers().")
        val observations: Collection<Observation> = getMobileTowers()
        if (observations.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "scanMobile() - ${observations.size} records to be queued for processing.")
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
    private suspend fun getMobileTowers(): Set<Observation> {
        if (telephonyManager == null) {
            telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (DEBUG) Log.d(TAG, "getMobileTowers(): telephony manager was null")
        }
        val observations = hashSetOf<Observation>()

        // Try recent API to get all cell information, or fall back to deprecated method
        val allCells: List<CellInfo> = try {
                telephonyManager!!.allCellInfo ?: emptyList()
        } catch (e: NoSuchMethodError) {
            Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().")
            emptyList()
        }
        if (allCells.isEmpty()) return deprecatedGetMobileTowers()

        val fallbackMnc by lazy {
            val m = telephonyManager!!.networkOperator?.let { if (it.length > 4) it.substring(3) else null }?.toIntOrNull()
            Log.d(TAG, "getMobileTowers(): using fallback mnc $m") // this triggers quite often... because some results are double with one having invalid mnc
            m
        }
        if (DEBUG) Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned " + allCells.size + " records.")
        val uptimeNanos = System.nanoTime()
        val realtimeNanos = SystemClock.elapsedRealtimeNanos()
        for (info in allCells) {
            if (DEBUG) Log.v(TAG, "getMobileTowers(): inputCellInfo: $info")
            val idStr: String
            val asu: Int
            val type: EmitterType
            if (info is CellInfoLte) {
                val id = info.cellIdentity

                // get mcc as string if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mccString ?: continue
                    else id.mcc.takeIf { it != intMax }?.toString() ?: continue
                // but we can't do this for mnc, as the string may start with 0, which is not
                // backwards compatible with old dejavu database (and deprecated int method, but
                // that can be managed) so we need it as integer in all cases
                val mncInt: Int = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        id.mncString?.toIntOrNull()
                    else id.mnc.takeIf { it != intMax }
                    ) ?: fallbackMnc ?: continue

                // CellIdentityLte accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.ci == intMax || id.pci == intMax || id.tac == intMax)
                    continue

                idStr = "${EmitterType.LTE}/$mccString/$mncInt/${id.ci}/${id.pci}/${id.tac}"
                asu = info.cellSignalStrength.asuLevel * MAXIMUM_ASU / 97
                type = EmitterType.LTE
            } else if (info is CellInfoGsm) {
                val id = info.cellIdentity

                // get mcc as string if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    id.mccString ?: continue
                else id.mcc.takeIf { it != intMax }?.toString() ?: continue
                // but can't do this for mnc, as the string may start with 0, which is not
                // backwards compatible with old dejavu database, and deprecated int method
                // se we need it as integer in all cases
                val mncInt: Int = (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            id.mncString?.toIntOrNull()
                        else id.mnc.takeIf { it != intMax }
                        ) ?: fallbackMnc ?: continue

                // CellIdentityGsm accessors all state Integer.MAX_VALUE is returned for unknown values.
                // analysis of results show frequent (invalid!) LAC of 0 messing with results, so ignore it
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                idStr = "${EmitterType.GSM}/$mccString/$mncInt/${id.lac}/${id.cid}"
                asu = info.cellSignalStrength.asuLevel
                type = EmitterType.GSM
            } else if (info is CellInfoWcdma) {
                val id = info.cellIdentity

                // get mcc as string if available (API 28+)
                val mccString: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    id.mccString ?: continue
                else id.mcc.takeIf { it != intMax }?.toString() ?: continue
                // but can't do this for mnc, as the string may start with 0, which is not
                // backwards compatible with old dejavu database, and deprecated int method
                // se we need it as integer in all cases
                val mncInt: Int = (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            id.mncString?.toIntOrNull()
                        else id.mnc.takeIf { it != intMax }
                        ) ?: fallbackMnc ?: continue

                // CellIdentityWcdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                idStr = "${EmitterType.WCDMA}/$mccString/$mncInt/${id.lac}/${id.cid}"
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
    // todo: remove after api upgrade, and integrate the first part in getMobileTowers
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
        var info: CellLocation? = null

        try {
            val info = telephonyManager!!.cellLocation
        } catch (e: Throwable) {
            if (DEBUG) Log.d(TAG, "getCellLocation(): failed")
        }

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
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun onWiFisChanged() {
        val scanResults = wifiManager.scanResults
        if (DEBUG) Log.d(TAG, "onWiFisChanged(): " + scanResults.size + " scan results")

        // We save the signal levels, because they are important for asu and thus location
        // computation, but cannot always be trusted. Some phones seem to report the signal
        // levels from the previous scan again, usually just for one WiFi type.
        // We don't want to discard these suspicious results, but rather treat them with care.
        val signalLevels = HashMap<String, Int>(scanResults.size * 2) // signal levels by rfId

        val observations = scanResults.map { scanResult ->
            val rfId = RfIdentification(scanResult.BSSID.lowercase().replace(".", ":"), scanResult.getWifiType())
            signalLevels[rfId.uniqueId] = scanResult.level
            val o = Observation(
                rfId,
                WifiManager.calculateSignalLevel(scanResult.level, MAXIMUM_ASU),
                scanResult.timestamp * 1000, // timestamp is elapsedRealtime when WiFi was last seen, in microseconds
                scanResult.SSID,
                scanResult.level == oldWifiSignalLevels[rfId.uniqueId]
            )
            if (DEBUG) Log.v(TAG, "$o, level=${scanResult.level}")
            o
        }
        if (observations.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "onWiFisChanged(): ${observations.size} observations, ${observations.count { it.suspicious }} suspicious")
            queueForProcessing(observations)
        }
        oldWifiSignalLevels = signalLevels
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
    private fun queueForProcessing(observations: Collection<Observation>) {
        val oldBgJob = backgroundJob
        backgroundJob = scope.launch {
            oldBgJob.join()
            backgroundProcessing(observations)
        }
    }

    /**
     * Process a group of observations. Process in this context means
     * 1. Add the emitters to the set of emitters we have seen in this processing period.
     * 2. If the GPS is accurate enough, update our coverage estimates for the emitters.
     * 3. Start a collection period if currently there isn't one active.
     */
    private suspend fun backgroundProcessing(observations: Collection<Observation>) {
        if (emitterCache == null) {
            if (instance == null) { // this should not be necessary, but better make sure
                if (DEBUG) Log.d(TAG, "backgroundProcessing() - instance is null")
                return
            }
            if (DEBUG) Log.d(TAG, "backgroundProcessing() - emitterCache is null: creating")
            emitterCache = Cache(this)
        }
        val emitters = HashSet<RfEmitter>()

        // load all emitters into the cache to avoid several single database transactions
        val ids = observations.map { it.identification }
        emitterCache!!.loadIds(ids)

        // Remember all the emitters we've seen during this processing period
        // and build a set of emitter objects for each RF emitter in the
        // observation set.
        synchronized(seenSet) { seenSet.addAll(ids) }
        for (observation in observations) {
            val emitter = emitterCache!![observation.identification]
            emitter.lastObservation = observation
            emitters.add(emitter)
        }
        // Update emitter coverage based on GPS as needed and get the set of locations
        // the emitters are known to be seen at.
        // First get the location; ignore if it is old (i.e. from a previous processing period),
        // we don't want to update emitters using outdated locations
        val loc = if (useKalman) {
            // Make accuracy better if using kalman. In tests it appears to hit some limit at
            // ca 8.4 m, not sure why (with GPS accuracy always being ~4.5 m at the same time).
            // With this limit, detection of 5 GHz WiFi is impossible (requires 7 m)...
            // thus we use a hacky workaround: just improve kalman accuracy by 1.5 m
            kalmanGpsLocation?.takeIf { it.timeOfUpdate > oldKalmanUpdate }?.location?.apply { accuracy -= 1.5f }
        } else
            lastGpsOfThisPeriod
        updateEmitters(emitters, loc)

        yield() // check if job is canceled, so we don't start a processing period after onClose
        startProcessingPeriodIfNecessary()
    }

    /**
     * Wait one REPORTING_INTERVAL and start processing, unless waiting is already happening
     * If WiFi scan is running, wait up to a second longer, as sometimes scanning is slow.
     * At the end, call [endOfPeriodProcessing], but wait until other jobs are done
     */
    private fun startProcessingPeriodIfNecessary() {
        if (periodicProcessing.isActive) return
        if (DEBUG) Log.d(TAG, "startProcessingPeriodIfNecessary() - starting new processing period")
        periodicProcessing = scope.launch { // IO because it's mostly waiting
            delay(REPORTING_INTERVAL)
            if (DEBUG) Log.d(TAG, "startProcessingPeriodIfNecessary() - reporting interval over, continue")
            // delay a bit more if wifi scan is still running
            if (wifiScanInProgress) {
                if (DEBUG) Log.d(TAG, "startProcessingPeriodIfNecessary() - Delaying endOfPeriodProcessing because WiFi scan in progress")
                val waitUntil = nextWlanScanTime + 1000 // delay at max until 1 sec after wifi scan should be finished
                while (SystemClock.elapsedRealtime() < waitUntil) {
                    delay(50)
                    if (!wifiScanInProgress) {
                        if (DEBUG) Log.d(TAG, "startProcessingPeriodIfNecessary() - wifi scan done, stop waiting")
                        break
                    }
                }
            }
            // the order means that we may miss locations in specific circumstances
            // seen: during backgroundJob.join() a mobile scan is started and finishes (because
            // this is fast), and adds a backgroundJob, but the join() is valid only for the
            // old backgroundJob. Could be improved by not using var here and somehow queuing
            // the new job, but it really doesn't matter because results are immediately going
            // to the next period.
            mobileJob.join()
            wifiJob.join()
            backgroundJob.join()
            scope.launch(Dispatchers.Default) { endOfPeriodProcessing() }
        }
    }

    /**
     * We bulk up operations to reduce writing to flash memory. And there really isn't
     * much need to report location to microG/UnifiedNlp more often than once every three
     * or four seconds. Another reason is that we can average more samples into each
     * report so there is a chance that our position computation is more accurate.
     */
    private fun endOfPeriodProcessing() {
        if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - end of current period.")
        oldKalmanUpdate = kalmanGpsLocation?.timeOfUpdate ?: 0L
        lastGpsOfThisPeriod = null
        if (settingsActivity != null) {
            // only needed for reporting once, so we set it to null immediately
            settingsActivity?.showEmitters(seenSet.map { emitterCache!![it] })
            settingsActivity = null
        }

        val locations: Collection<RfLocation>
        synchronized(seenSet) {
            if (seenSet.isEmpty()) {
                if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - no emitters seen.")
                return
            }
            locations = getRfLocations(seenSet)

            // start GPS if we found emitters, but none has a valid location
            if (activeMode && locations.isEmpty() && seenSet.isNotEmpty()) {
                // Remove blacklisted emitters. They don't lead to a location, so we might not need to start GPS.
                val emittersToUse = seenSet.filterNot { emitterCache?.simpleGet(it)?.status == EmitterStatus.STATUS_BLACKLISTED }
                if (emittersToUse.isNotEmpty()) {
                    // determine accuracy values required to actually put the emitter into the database
                    val (shortRange, longRange) = emittersToUse.partition { it.rfType in shortRangeEmitterTypes }
                    val requiredAccuracyForGps = if (shortRange.isEmpty())
                            // ideally we want to locate all long range emitters
                            longRange.minOf { it.rfType.getRfCharacteristics().minimumRange }
                        else
                            // but for short range emitters, getting one is enough
                            // reason: often the 7 m for 5 GHz WiFi take rather long, using more battery
                            shortRange.maxOf { it.rfType.getRfCharacteristics().minimumRange }
                    val intent = Intent(this, GpsMonitor::class.java)
                    intent.putExtra(ACTIVE_MODE_TIME, activeTimeout)
                    intent.putExtra(ACTIVE_MODE_ACCURACY, requiredAccuracyForGps.toFloat())
                    intent.action = ACTIVE_MODE_ACTION
                    localBroadcastManager.sendBroadcast(intent) // tell GpsMonitor to start GPS
                }
            }

            seenSet.clear() // Reset the RF emitters we've seen.

            // Sync all of our changes to the on flash database in background
            val oldBgJob = backgroundJob
            if (DEBUG && backgroundJob.isActive)
                // unexpected, but seen: see startProcessingPeriodIfNecessary for explanation
                Log.d(TAG, "endOfPeriodProcessing() - background job is active, this is unexpected")
            backgroundJob = scope.launch {
                oldBgJob.join()
                emitterCache!!.sync()
            }
        }

        if (locations.isEmpty()) return
        // Estimate location using weighted average of the most recent
        // observations from the set of RF emitters we have seen. We cull
        // the locations based on distance from each other to reduce the
        // chance that a moved/moving emitter will be used in the computation.
        val weightedAverageLocation = when (cull) {
            1 -> locations.medianCullSafe()
            2 -> locations.weightedAverage()
            else -> culledEmitters(locations)?.weightedAverage()
        }
        if (weightedAverageLocation != null && notNullIsland(weightedAverageLocation)) {
/*            if (DEBUG) { // this is just for testing / comparing the different locations
                // lat positive: alternative puts me further south
                // lon positive: alternative puts me further west
                val weightedNoCull = locations.weightedAverage()
                Log.v(TAG, "avg (${weightedAverageLocation.accuracy}) minus noCull loc (${weightedNoCull.accuracy}) / ${locations.size}: lat ${(weightedAverageLocation.latitude - weightedNoCull.latitude)* DEG_TO_METER}m, lon ${(weightedAverageLocation.longitude - weightedNoCull.longitude) * DEG_TO_METER * cos(Math.toRadians(weightedAverageLocation.latitude))}m")
                val weightedOriginalEmitters = culledEmitters(locations)
                val weightedOriginal = weightedOriginalEmitters?.weightedAverage()
                Log.i(TAG, "avg (${weightedAverageLocation.accuracy}) minus weightedOriginal loc (${weightedOriginal?.accuracy}) / ${weightedOriginalEmitters?.size}: lat ${(weightedAverageLocation.latitude - (weightedOriginal?.latitude?:0.0))* DEG_TO_METER}m, lon ${(weightedAverageLocation.longitude - (weightedOriginal?.longitude?:0.0)) * DEG_TO_METER * cos(Math.toRadians(weightedAverageLocation.latitude))}m")
                val medianCullEmitters = locations.medianCull()
                val medianCull = medianCullEmitters?.weightedAverage()
                Log.v(TAG, "avg (${weightedAverageLocation.accuracy}) minus medianCull loc (${medianCull?.accuracy}) / ${medianCullEmitters?.size}: lat ${(weightedAverageLocation.latitude - (medianCull?.latitude?:0.0))* DEG_TO_METER}m, lon ${(weightedAverageLocation.longitude - (medianCull?.longitude?:0.0)) * DEG_TO_METER * cos(Math.toRadians(weightedAverageLocation.latitude))}m")
                val newThing = locations.medianCullSafe()
                Log.v(TAG, "avg (${weightedAverageLocation.accuracy}) minus medianCullSafe loc (${newThing?.accuracy}): lat ${(weightedAverageLocation.latitude - (newThing?.latitude?:0.0))* DEG_TO_METER}m, lon ${(weightedAverageLocation.longitude - (newThing?.longitude?:0.0)) * DEG_TO_METER * cos(Math.toRadians(weightedAverageLocation.latitude))}m")
            }*/
            if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - reporting location")
            // for some weird reason, reporting may (very rarely) take REALLY long, even minutes
            // this may be an issue of unifiedNLP instead of the backend... anyway, do it in background!
            scope.launch { report(weightedAverageLocation) }
        } else if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - no location to report")
    }

    /**
     * Update the coverage estimates for the emitters we have just gotten observations for.
     *
     * @param emitters The emitters we have just observed
     * @param gps The GPS position at the time the observations were collected.
     */
    private fun updateEmitters(emitters: Collection<RfEmitter>, gps: Location?) {
        if (gps == null) return
        if (DEBUG) Log.d(TAG, "updateEmitters() - updating with accuracy ${gps.accuracy}")
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
        if (DEBUG) Log.d(TAG, "getRfLocations() - returning ${locations.size} locations for ${rfIds.size} emitters")
        return locations
    }

    companion object {
        var instance: BackendService? = null
            private set

        /**
         * Called by Android when a GPS location reports becomes available.
         *
         * @param locReport The current GPS position estimate
         */
        fun instanceGpsLocationUpdated(locReport: Location) {
            //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
            instance?.onGpsChanged(locReport)
        }

        /** Clears the emitter cache. Necessary if changes to database were made no through cache */
        fun resetCache() = instance?.emitterCache?.clear()

        private lateinit var prefs: SharedPreferences

        /**
         * Information whether emitter type always reports the same asu.
         * Contains the always-same asu value, or 0 if not always the same.
         */
        private val asuMap = HashMap<EmitterType, Int>()

        /**
         * Some phone models seem to have problems with reporting asu, and
         * simply report some default value. This function checks and stores whether
         * asu of an EmitterType is always the same value, and returns MINIMUM_ASU in this case
         */
        fun EmitterType.getCorrectedAsu(asu: Int): Int {
            when (asuMap[this]) {
                0 -> return asu // we know this emitter type reports valid asu values
                asu -> return MINIMUM_ASU // all asu values of this emitter type were the same so far
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
                else -> { // not always the same asu -> set map entry to 0
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
private const val GPS_COORDINATE_NOISE = 2.0 // reduced from initial 3.0 to get somewhat better kalman accuracy
//private const val POSITION_COORDINATE_NOISE = 6.0

private val DEBUG = BuildConfig.DEBUG

private const val TAG = "LocalNLP Backend"
private val myPerms = arrayOf(
    permission.ACCESS_WIFI_STATE, permission.CHANGE_WIFI_STATE,
    permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION
)

// Stuff for scanning WiFi APs
private val wifiBroadcastFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)

// shorter name because it's checked often
private const val intMax = Int.MAX_VALUE
