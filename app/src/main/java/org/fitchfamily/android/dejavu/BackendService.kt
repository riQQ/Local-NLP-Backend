package org.fitchfamily.android.dejavu

/*
*    Local NLP Backend / DejaVu - A location provider backend for microG/UnifiedNlp
*
*    Copyright (C) 2017 Tod Fitch
*    Copyright (C) 2023 Helium314
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.telephony.*
import android.telephony.cdma.CdmaCellLocation
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.microg.nlp.api.LocationBackendService
import org.microg.nlp.api.MPermissionHelperActivity
import kotlin.math.abs

/**
 * Created by tfitch on 8/27/17.
 * modified by helium314 in 2022
 */
class BackendService : LocationBackendService() {
    private var gpsMonitorRunning = false
    private var wifiBroadcastReceiverRegistered = false
    private var permissionsOkay = true

    @Volatile private var wifiScanInProgress = false // will be re-ordered by compiler if not volatile, causing weird issues
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
    private val telephonyManager: TelephonyManager by lazy { getSystemService(TELEPHONY_SERVICE) as TelephonyManager }

    // this causes java.lang.NoClassDefFoundError messages on start with Android 9 and lower,
    // but there are no further issues
    private val callInfoCallback = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        null
    else
        object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(activeCellInfo: MutableList<CellInfo>) {
                if (DEBUG) Log.d(TAG, "onCellInfo(): cell info update arrived")
                val oldMobileJob = mobileJob
                mobileJob = scope.launch {
                    oldMobileJob.join()
                    processCellInfos(activeCellInfo)
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
    /** a set of emitters we've seen within the current processing period */
    private val seenSet = hashSetOf<RfIdentification>()
    /**
     * emitters from the previous processing period, to be used when GPS location is received
     * soon after processing period finishes (especially useful for active mode and scan throttling)
     */
    private val oldEmitters = mutableListOf<RfIdentification>()
    private var emitterCache: Cache? = null
    /** when the next mobile scan may be started (measured in elapsedRealtime) */
    private var nextMobileScanTime: Long = 0
    /** when the next WiFi scan may be started (measured in elapsedRealtime) */
    private var nextWlanScanTime: Long = 0
    /** results of previous wifi scan by rfID */
    private var oldWifiSignalLevels = hashMapOf<String, Int>()
    /** timestamp of the newest cell returned by the previous call to getAllCellInfo, used because
     * newer Android version may return cached data, which we want to detect and discard */
    private var cellInfoTimestamp: Long = 0
    /** Filtered GPS (because GPS is so bad on Moto G4 Play) */
    private var kalmanGpsLocation: Kalman? = null
    /** time of the most recent Kalman time in the previous processing period */
    @Volatile private var oldKalmanUpdate = 0L
    /** set to null at the end of each period, used if kalman is switched off */
    @Volatile private var lastGpsOfThisPeriod: Location? = null
    // for calling GpsMonitor
    private val localBroadcastManager by lazy { LocalBroadcastManager.getInstance(this) }
    /** emitters that recently triggered a GPS start in active mode */
    private val activeModeStarters = hashSetOf<RfIdentification>()
    /** target accuracy for last start of active mode */
    private var activeModeAccuracyTarget = 0f

    // settings that are read often, so better copy them here
    // setting changes propagate here because settingsFragment closes BackendService, and thus it's re-opened
    private var useKalman = false
    private var wifiScanEnabled = true
    private var mobileScanEnabled = true
    private var cull = 0
    private var activeMode = 0
    private var activeModeTimeout = 0L

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
        prefs = PreferenceManager.getDefaultSharedPreferences(this) // to fix deprecation: use androidx.preference:preference
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

        reloadSettings()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && perms.contains(permission.ACCESS_BACKGROUND_LOCATION)) {
                /* Newer Android versions make things annoying: we need to ask for location WITHOUT
                 * background location first, and then background location separately. Otherwise
                 * the permissions are automatically denied without informing the user.
                 * Luckily there is some weird behavior in microG: The module gets disabled immediately
                 * after asking for location permissions. Thus we can ask for background permission
                 * when the user (hopefully) tries to enable the module a second time.
                 * Maybe this is intentional from microG for this reason?
                 */
                if (perms.size > 1) // remove background location if we also ask for other permissions
                    perms.remove(permission.ACCESS_BACKGROUND_LOCATION)
                else // ask for background location only, and make toast to explain why we ask a second time
                    Handler(Looper.getMainLooper()).post { // see https://stackoverflow.com/a/66385775
                        Toast.makeText(applicationContext, R.string.background_location_permission_toast, Toast.LENGTH_LONG).show()
                    }
            }
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

    /** reloads settings from shared preferences */
    fun reloadSettings() {
        // take care to use default values from preferences.xml
        useKalman = prefs.getBoolean(PREF_KALMAN, false)
        wifiScanEnabled = prefs.getBoolean(PREF_WIFI, true)
        mobileScanEnabled = prefs.getBoolean(PREF_MOBILE, true)
        cull = prefs.getInt(PREF_CULL, 0)
        // using strings instead of integers because this is android default
        // directly using int is much more work in settings, so let's just be lazy...
        activeMode = prefs.getString(PREF_ACTIVE_MODE, "0")?.toIntOrNull() ?: 0
        activeModeTimeout = (prefs.getString(PREF_ACTIVE_TIME, "10")?.toLongOrNull() ?: 10L) * 1000
    }

    /** stops scans until settings are reloaded, used when database is used by settings */
    fun pause() {
        wifiScanEnabled = false
        mobileScanEnabled = false
        backgroundJob.cancel()
        wifiJob.cancel()
        mobileJob.cancel()
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
    private fun onLocationChanged(update: Location) {
        if (permissionsOkay && notNullIsland(update)) {
            if (DEBUG) Log.d(TAG, "onLocationChanged() - ${update.provider}, accuracy ${update.accuracy}")
            lastGpsOfThisPeriod = update
            if (useKalman)
                kalmanGpsLocation?.update(update) ?: run { kalmanGpsLocation = Kalman(update, GPS_COORDINATE_NOISE) }
            if (oldEmitters.isNotEmpty()) {
                // If oldEmitters is not empty, we update the emitters using the new location.
                // The time difference is checked as part of updating, so using too old emitters
                // should not cause any issues with outdated data.
                val location = if (useKalman) kalmanGpsLocation?.location?.apply { accuracy -= 1.5f }
                    else update
                if (DEBUG) Log.d(TAG, "onGpsChanged() - updating old emitters")
                updateEmitters(oldEmitters.mapNotNull { emitterCache?.get(it) }, location)
            }
            scanAllSensors(update)
        } else
            Log.d(TAG, "onGpsChanged() - Permissions not granted or location invalid, soft fail.")
    }

    /**
     * Kick off new scans for all the sensor types we know about. Typically scans
     * should occur asynchronously so we don't hang up our caller's thread.
     * if a [location] is provided, WiFi scan is only started if accucacy is sufficient
     */
    @Synchronized
    private fun scanAllSensors(location: Location? = null) {
        if (emitterCache == null) {
            if (instance == null) { // this should not be necessary, but better make sure
                if (DEBUG) Log.d(TAG, "scanAllSensors() - instance is null")
                return
            }
            if (DEBUG) Log.d(TAG, "scanAllSensors() - emitterCache is null: creating")
            emitterCache = Cache(this)
        }

        if (DEBUG) Log.d(TAG, "scanAllSensors() - starting scans")
        val wifiScanStarted = (location?.accuracy ?: 0f) < shortRangeMinAccuracy && startWiFiScan()
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
    @Suppress("Deprecation") // initiating a WiFi scan is deprecated... well thanks, Google.
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

        mobileJob = scope.launch { getMobileTowers() }
        return true
    }

    /**
     * Get the set of mobile (cell) towers that Android claims the phone can see.
     * we use the current API but fall back to deprecated methods if we get a null
     * or empty result from the current API.
     *
     * The used functions processCellInfos and deprecatedGetMobileTowers call enqueueForProcessing
     * on any found observations
     */
    @SuppressLint("MissingPermission")
    private fun getMobileTowers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            telephonyManager.requestCellInfoUpdate(mainExecutor, callInfoCallback!!)
            if (DEBUG) Log.d(TAG, "getMobileTowers(): requested cell info update")
            return
        }

        // Try recent API to get all cell information, or fall back to deprecated method
        val allCells: List<CellInfo> = try {
            telephonyManager.allCellInfo ?: emptyList()
        } catch (e: NoSuchMethodError) {
            Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().")
            emptyList()
        }
        if (allCells.isEmpty()) deprecatedGetMobileTowers()

        if (DEBUG) Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned " + allCells.size + " records.")
        processCellInfos(allCells)
    }

    /** check validity of cellInfos and call queueForProcessing using extracted observations */
    // there are a lot of deprecated functions, but the replacements have mostly been added in
    // API28+, so we need to use to old ones too
    @Suppress("Deprecation")
    private fun processCellInfos(allCells: List<CellInfo>) {
        // from https://developer.android.com/reference/android/telephony/TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,%20android.telephony.TelephonyManager.CellInfoCallback)
        // Apps targeting Android Q or higher will no longer trigger a refresh of the cached CellInfo by invoking this API. Instead, those apps will receive the latest cached results, which may not be current.
        // -> now that we target API33 we need to check timestamp to be sure we actually got updated cell info
        val newCells = allCells.filter { it.timeStamp > cellInfoTimestamp }
        if (newCells.isEmpty()) {
            if (DEBUG) Log.d(TAG, "processCellInfos() - no cells newer than old timestamp")
            return
        }
        if (DEBUG && newCells.size != allCells.size)
            Log.d(TAG, "processCellInfos() - removed ${allCells.size - newCells.size} old cells")
        cellInfoTimestamp = newCells.maxOf { it.timeStamp }

        val observations = hashSetOf<Observation>()
        val fallbackMnc by lazy {
            val m = telephonyManager.networkOperator?.takeIf { it.length > 4 }?.substring(3)?.toIntOrNull()
            if (DEBUG) Log.d(TAG, "processCellInfos() - using fallback mnc $m") // this triggers quite often... because some results are double with one having invalid mnc
            m
        }
        val nanoTime = System.nanoTime()
        for (info in newCells) {
            if (DEBUG) Log.v(TAG, "processCellInfos() - inputCellInfo: $info")
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

                type = EmitterType.LTE
                idStr = "$type/$mccString/$mncInt/${id.ci}/${id.pci}/${id.tac}"
                asu = info.cellSignalStrength.asuLevel * MAXIMUM_ASU / 97
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

                // analysis of results show frequent (invalid!) LAC of 0 messing with results, so ignore it
                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                type = EmitterType.GSM
                idStr = "$type/$mccString/$mncInt/${id.lac}/${id.cid}"
                asu = info.cellSignalStrength.asuLevel
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

                if (id.lac == intMax || id.lac == 0 || id.cid == intMax)
                    continue

                type = EmitterType.WCDMA
                idStr = "$type/$mccString/$mncInt/${id.lac}/${id.cid}"
                asu = info.cellSignalStrength.asuLevel
            } else if (info is CellInfoCdma) {
                val id = info.cellIdentity
                if (id.networkId == intMax || id.systemId == intMax || id.basestationId == intMax)
                    continue

                type = EmitterType.CDMA
                idStr = "$type/${id.networkId}/${id.systemId}/${id.basestationId}"
                asu = info.cellSignalStrength.asuLevel
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoTdscdma) {
                val id = info.cellIdentity
                if (id.mncString == null || id.mccString == null || id.lac == intMax || id.cid == intMax || id.cpid == intMax)
                    continue

                type = EmitterType.TDSCDMA
                idStr = "$type}/${id.mncString}/${id.mccString}/${id.lac}/${id.cid}&${id.cpid}"
                asu = info.cellSignalStrength.asuLevel
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                val id = info.cellIdentity as? CellIdentityNr ?: continue // why is casting necessary??
                if (id.mncString == null || id.mccString == null || id.nci == Long.MAX_VALUE || id.pci == intMax || id.tac == intMax)
                    continue

                asu = info.cellSignalStrength.asuLevel
                type = if (id.nrarfcn > 2000000 && id.nrarfcn != intMax) EmitterType.NR_FR2 // FR2 supposedly starting at 2016667
                    else EmitterType.NR
                idStr = "$type/${id.mncString}/${id.mccString}/${id.nci}/${id.pci}&${id.tac}"
            } else {
                Log.d(TAG, "processCellInfos() - Unsupported Cell type: $info")
                continue
            }
            /*
             * timeStamp for CellInfo is poorly documented, and thus may be either in uptimeMillis
             * style (not advancing during sleep) or elapsedRealtimeMillis style (advancing during
             * sleep). Thus for phones that don't have timeStampMillis (which always is realtime),
             * we need to find out which style is correct. (both are indeed found in reality!)
             */
            val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    info.timestampMillis * 1000000 // we want nanoseconds, but actually ns precision is not that important
                } else {
                    if (abs(info.timeStamp - nanoTime) < 1000000000L * 100L) // if difference < 100 s -> assume uptime
                        SystemClock.elapsedRealtimeNanos() - nanoTime + info.timeStamp
                    else
                        info.timeStamp // else assume realtime
                }
            val o = Observation(idStr, type, asu, timestamp)
            observations.add(o)
            if (DEBUG) Log.d(TAG, "processCellInfos() - valid observation string: $idStr, asu $asu")
        }
        if (DEBUG) Log.d(TAG, "processCellInfos() - Observations: $observations")
        if (observations.isNotEmpty())
            queueForProcessing(observations)
    }

    /**
     * Use old but still implemented methods to gather information about the mobile (cell)
     * towers our phone sees. Only called if the non-deprecated methods fail to return
     * usable cell infos.
     * getNeighboringCellInfo method was removed in API 29, but we still try using it
     * through reflection, as on older phones getAllCellInfo might not work correctly.
     */
    @SuppressLint("MissingPermission")
    @Suppress("Deprecation") // yes, deprecatedGetMobileTowers used deprecated (and even removed) methods
    private fun deprecatedGetMobileTowers() {
        if (DEBUG) Log.d(TAG, "getMobileTowers(): allCells null or empty, using deprecated")
        val observations = hashSetOf<Observation>()
        val mncString = telephonyManager.networkOperator
        if (mncString == null || mncString.length < 5 || mncString.length > 6) {
            if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): mncString is NULL or not recognized.")
            return
        }
        val mcc = mncString.substring(0, 3).toIntOrNull() ?: return
        val mnc = mncString.substring(3).toIntOrNull() ?: return
        val timeNanos = SystemClock.elapsedRealtimeNanos()

        try {
            val info = telephonyManager.cellLocation
            if (info is GsmCellLocation) {
                val idStr = "${EmitterType.GSM}/$mcc/$mnc/${info.lac}/${info.cid}"
                val o = Observation(idStr, EmitterType.GSM, MINIMUM_ASU, timeNanos)
                observations.add(o)
            } else if (info is CdmaCellLocation) {
                val idStr = "${EmitterType.CDMA}/${info.networkId}/${info.systemId}/${info.baseStationId}"
                val o = Observation(idStr, EmitterType.CDMA, MINIMUM_ASU, timeNanos)
                observations.add(o)
            } else if (DEBUG)
                Log.d(TAG, "deprecatedGetMobileTowers(): getCellLocation() returned null or not unknown CellLocation.")
        } catch (e: Throwable) {
            // this happens e.g. in MIUI 12 where getCellLocation is apparently (always?)) crashing
            // see https://github.com/Helium314/Local-NLP-Backend/pull/5#discussion_r991915543
            if (DEBUG) Log.d(TAG, "getCellLocation(): failed")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (observations.isNotEmpty())
                queueForProcessing(observations)
            return // getNeighboringCellInfo does not exist for these API levels, so no need to try
        }
        try {
            // getNeighboringCellInfo can't be called directly when targeting API29+, so try using reflection
            val neighbors = telephonyManager.javaClass.getMethod("getNeighboringCellInfo").invoke(telephonyManager) as? List<NeighboringCellInfo>
            if (neighbors != null && neighbors.isNotEmpty()) {
                for (neighbor in neighbors) {
                    if (neighbor.cid > 0 && neighbor.lac > 0) {
                        // these are GSM cells. 3G can be detected, but don't contain enough info for identification
                        val idStr = "${EmitterType.GSM}/$mcc/$mnc/${neighbor.lac}/${neighbor.cid}"
                        val o = Observation(idStr, EmitterType.GSM, neighbor.rssi, timeNanos)
                        observations.add(o)
                    }
                }
            } else {
                if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): getNeighboringCellInfo() returned null or empty set.")
            }
        } catch (e: Exception) {
            if (DEBUG) Log.d(TAG, "deprecatedGetMobileTowers(): error calling getNeighboringCellInfo(): ${e.message}.")
        }
        if (observations.isNotEmpty())
            queueForProcessing(observations)
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
        if (signalLevels == oldWifiSignalLevels && signalLevels.size > 2) {
            if (DEBUG) Log.d(TAG, "onWiFisChanged(): WiFi signal levels unchanged, discarding scan results because they are likely outdated")
            return
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
        val locations: Collection<RfLocation>
        oldEmitters.clear()

        synchronized(seenSet) {
            if (lastGpsOfThisPeriod == null) // fill oldEmitters only if there was no GPS location
                oldEmitters.addAll(seenSet)
            else if (activeMode != 0 && (lastGpsOfThisPeriod?.accuracy ?: 0f) < activeModeAccuracyTarget)
                activeModeStarters.clear() // clear activeModeStarters if we have a GPS location of the required accuracy

            oldKalmanUpdate = kalmanGpsLocation?.timeOfUpdate ?: 0L
            lastGpsOfThisPeriod = null
            if (settingsActivity != null) {
                // only needed for reporting once, so we set it to null immediately
                settingsActivity?.showEmitters(seenSet.map { emitterCache!![it] })
                settingsActivity = null
            }

            if (seenSet.isEmpty()) {
                if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - no emitters seen.")
                return
            }
            locations = getRfLocations(seenSet)

            // start GPS if wanted by active mode setting
            if (activeMode != 0 && seenSet.isNotEmpty()) {
                val validEmitters = seenSet.filterNot { emitterCache?.simpleGet(it)?.status == EmitterStatus.STATUS_BLACKLISTED }
                if (validEmitters.isNotEmpty())
                    startActiveMode(locations, validEmitters)
            }

            seenSet.clear() // Reset the RF emitters we've seen.
        }

        // Sync all of our changes to the on flash database in background
        val oldBgJob = backgroundJob
        if (DEBUG && backgroundJob.isActive)
            // unexpected, but seen: see startProcessingPeriodIfNecessary for explanation
            Log.d(TAG, "endOfPeriodProcessing() - background job is active, this is unexpected")
        backgroundJob = scope.launch {
            oldBgJob.join()
            emitterCache!!.sync()
        }

        if (locations.isEmpty()) {
            if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - no location to report")
            return
        }

        // clear activeModeStarters if we have a location of the most precise type required
        // this will clear too often for aggressive setting, but in aggressive mode battery drain is expected anyway
        if (activeMode != 0 && activeModeStarters.isNotEmpty()
                && locations.minOf { it.id.rfType.getRfCharacteristics().requiredGpsAccuracy } <= activeModeAccuracyTarget)
            activeModeStarters.clear()

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
        } else if (DEBUG) Log.d(TAG, "endOfPeriodProcessing() - determined location is null or nullIsland")
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

    /** Start active mode dependent on the settings. [emitters] must not be empty. */
    private fun startActiveMode(locations: Collection<RfLocation>, emitters: Collection<RfIdentification>) {
        if (DEBUG) Log.d(TAG, "startActiveMode() - determine whether GPS should be started, setting: $activeMode")

        val (shortRangeEmitters, longRangeEmitters) = emitters.partition { it.rfType in shortRangeEmitterTypes }
        val (shortRangeLocations, longRangeLocations) = locations.partition { it.id.rfType in shortRangeEmitterTypes }

        // determine which emitters are used to start active mode, depending on active mode setting
        val emittersToUse: Collection<RfIdentification>
        when (activeMode) {
            1 -> { // all emitters, but only if we have no location
                if (locations.isNotEmpty()) {
                    if (DEBUG) Log.d(TAG, "startActiveMode() - not starting GPS because we have a location")
                    return
                }
                emittersToUse = emitters
            }
            2, 3 -> { // all short range emitters plus unknown long range emitters, but only if we have no short range location
                if (shortRangeLocations.isNotEmpty()) {
                    if (DEBUG) Log.d(TAG, "startActiveMode() - not starting GPS because we have a short range location")
                    return
                }
                val longRangeLocationEmitters = HashSet<RfIdentification>(longRangeLocations.size)
                longRangeLocations.forEach { longRangeLocationEmitters.add(it.id) }
                emittersToUse = shortRangeEmitters + longRangeEmitters.filterNot { it in longRangeLocationEmitters }
            }
            4 -> { // all unknown emitters
                val locationEmitters = HashSet<RfIdentification>(locations.size)
                locations.forEach { locationEmitters.add(it.id) }
                emittersToUse = emitters.filterNot { it in locationEmitters }
            }
            else -> {
                if (DEBUG) Log.d(TAG, "startActiveMode() - not starting GPS because we have an invalid active mode setting")
                return
            }
        }
        // do nothing if all emitters were already used to start active mode recently
        if (emittersToUse.isEmpty() || activeModeStarters.containsAll(emittersToUse)) {
            if (DEBUG) Log.d(TAG, "startActiveMode() - not starting GPS because we have no emitters to use (${emittersToUse.isEmpty()}) or we recently started GPS all found emitters (${activeModeStarters.containsAll(emittersToUse)})")
            return
        }
        activeModeStarters.addAll(emittersToUse)

        // now we want to start active mode -> need to determine our target accuracy
        activeModeAccuracyTarget = when (activeMode) {
            1, 2 -> { // try to conserve battery by not aiming for highest accuracy
                if (shortRangeEmitters.isEmpty())
                    // in this case, emittersToUse contains only long range emitters that didn't provide a location
                    emittersToUse.minOf { it.rfType.getRfCharacteristics().requiredGpsAccuracy }
                else
                    shortRangeEmitters.maxOf { it.rfType.getRfCharacteristics().requiredGpsAccuracy }
            }
            3, 4 -> emittersToUse.minOf { it.rfType.getRfCharacteristics().requiredGpsAccuracy }
            else -> {
                if (DEBUG) Log.d(TAG, "startActiveMode() - not starting GPS because we have an invalid active mode setting")
                return
            }
        }

        val intent = Intent(this, GpsMonitor::class.java)
        intent.putExtra(ACTIVE_MODE_TIME, activeModeTimeout)
        intent.putExtra(ACTIVE_MODE_ACCURACY, activeModeAccuracyTarget)
        val reasonEmitter = emitterCache!![emittersToUse.minBy { it.rfType.getRfCharacteristics().requiredGpsAccuracy }]
        val emitterText = if (reasonEmitter.note.isBlank()) reasonEmitter.uniqueId
            else "${reasonEmitter.type} ${reasonEmitter.note}"
        intent.putExtra(ACTIVE_MODE_TEXT, getString(R.string.active_mode_active, emitterText, emittersToUse.size))
        intent.action = ACTIVE_MODE_ACTION
        if (DEBUG) Log.d(TAG, "startActiveMode() - send intent to start GPS because of emitters $emittersToUse")
        localBroadcastManager.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "LocalNLP Backend"
        var instance: BackendService? = null
            private set

        /**
         * Called by Android when a GPS location reports becomes available.
         *
         * @param locReport The current GPS position estimate
         */
        fun instanceGpsLocationUpdated(locReport: Location) {
            //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
            instance?.onLocationChanged(locReport)
        }

        /**
         * Called if a valid geo uri intent containing latitude and longitude is received.
         * The location is treated like GPS location with accuracy 0.
         */
        fun geoUriLocationProvided(latitude: Double, longitude: Double) {
            Log.d(TAG, "handleExternalLocation() - accepting location provided via geoUri intent")
            val loc = Location("geoUri")
            loc.latitude = latitude
            loc.longitude = longitude
            loc.accuracy = 0f
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            instance?.onLocationChanged(loc)
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

private val myPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    arrayOf(
        permission.ACCESS_WIFI_STATE, permission.CHANGE_WIFI_STATE,
        permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION, permission.ACCESS_BACKGROUND_LOCATION
    )
} else {
    arrayOf(
        permission.ACCESS_WIFI_STATE, permission.CHANGE_WIFI_STATE,
        permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION
    )
}

// Stuff for scanning WiFi APs
private val wifiBroadcastFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)

// shorter name because it's checked often
private const val intMax = Int.MAX_VALUE

private val shortRangeMinAccuracy = shortRangeEmitterTypes.maxOf { it.getRfCharacteristics().requiredGpsAccuracy }
