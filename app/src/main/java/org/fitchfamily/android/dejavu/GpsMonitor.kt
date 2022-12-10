package org.fitchfamily.android.dejavu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.fitchfamily.android.dejavu.BackendService.Companion.instanceGpsLocationUpdated

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
/**
 * Created by tfitch on 8/31/17.
 */
/**
 * A passive GPS monitor. We don't want to turn on the GPS as the backend
 * runs continuously and we would quickly drain the battery. But if some
 * other app turns on the GPS we want to listen in on its reports. The GPS
 * reports are used as a primary (trusted) source of position that we can
 * use to map the coverage of the RF emitters we detect.
 */
class GpsMonitor : Service(), LocationListener {
    private val locationManager: LocationManager by lazy { applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager }
    private val gpsLocationManager: LocationManager by lazy { applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager }
    private var monitoring = false
    private var gpsEnabled = false
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind() entry.")
        return Binder()
    }

    // for active mode
    private val scope: CoroutineScope by lazy { CoroutineScope(Job() + Dispatchers.IO) }
    private var gpsRunning: Job? = null
    private var targetAccuracy = 0.0f
    private val intentFilter = IntentFilter(ACTIVE_MODE_ACTION)
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (DEBUG) Log.d(TAG, "onReceive() - received intent")
            val time = intent?.extras?.getLong(ACTIVE_MODE_TIME) ?: return
            val accuracy = intent.extras?.getFloat(ACTIVE_MODE_ACCURACY) ?: return
            getGpsPosition(time, accuracy)
        }
    }
    // without notification, gps will only run in if app in in foreground (i.e. in settings)
    private val notification by lazy {
        // before we can use the notification we need a channel on Oreo and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = NotificationManagerCompat.from(this)
            val channel = NotificationChannel(CHANNEL_ID , getString(R.string.pref_active_mode_title), NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.active_mode_active))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate()")
        monitoring = try {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, GPS_SAMPLE_TIME, GPS_SAMPLE_DISTANCE, this)
            true
        } catch (ex: SecurityException) {
            Log.w(TAG, "onCreate() failed: ", ex)
            false
        }
        gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        if (monitoring) {
            locationManager.removeUpdates(this)
            if (gpsRunning?.isActive == true)
                stopGps()
            monitoring = false
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
    }

    /**
     * The passive provider we are monitoring will give positions from all
     * providers on the phone (including ourselves) we ignore all providers other
     * than the GPS. The GPS reports we pass on to our main backend service for
     * it to use in mapping RF emitter coverage.
     *
     * At least one Bluetooth GPS unit seems to return locations near 0.0,0.0
     * until it has a good lock. This can result in our believing the local
     * emitters are located on "null island" which then leads to other problems.
     * So protect ourselves and ignore any GPS readings close to 0.0,0.0 as there
     * is no land in that area and thus no possibility of mobile or WLAN emitters.
     *
     * @param location A position report from a location provider
     */
    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            if (gpsRunning?.isActive == true && location.accuracy <= targetAccuracy) {
                if (DEBUG) Log.d(TAG, "onLocationChanged() - target accuracy achieved (${location.accuracy} m), stopping GPS")
                stopGps()
            }
            instanceGpsLocationUpdated(location)
        }
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        Log.d(TAG, "onStatusChanged() - provider $provider, status $status")
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "onProviderEnabled() - $provider")
        gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "onProviderDisabled() - $provider")
        // todo: apparently this is sometimes seconds after GPS was disabled, anything that can be done here?
        gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /**
     * Try getting GPS location for a while. Will be stopped after a location with the target accuracy
     * is received or the timeout is over.
     */
    private fun getGpsPosition(timeout: Long, accuracy: Float) {
        if (!gpsEnabled || gpsRunning?.isActive == true) {
            if (DEBUG) Log.d(TAG, "getGpsPosition() - not starting GPS. GPS provider enabled: $gpsEnabled, GPS running: ${gpsRunning?.isActive}")
            return
        }
        if (DEBUG) Log.d(TAG, "getGpsPosition() - trying to start for $timeout ms with accuracy target $accuracy m")
        try {
            startForeground(NOTIFICATION_ID, notification)
            notification.`when` = System.currentTimeMillis()
            gpsLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_SAMPLE_TIME, GPS_SAMPLE_DISTANCE, this)
            gpsRunning = scope.launch(Dispatchers.IO) { gpsTimeout(timeout) }
            targetAccuracy = accuracy
        } catch (ex: SecurityException) {
            Log.w(TAG, "getGpsPosition() - starting GPS failed", ex)
        }
    }

    /**
     * Wait for [timeout] ms and then stop GPS updates. Via [gpsRunning] this also serves as
     * indicator whether active GPS is on.
     * This is NOT delay([timeout]), because delay does not advance when the system is sleeping,
     * while elapsedRealtime does.
     */
    private suspend fun gpsTimeout(timeout: Long) {
        val t = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < t + timeout) {
            delay(200)
        }
        if (DEBUG) Log.d(TAG, "gpsTimeout() - stopping GPS")
        stopGps()
    }

    private fun stopGps() {
        gpsLocationManager.removeUpdates(this)
        gpsRunning?.cancel()
        stopForeground(true)
    }

    companion object {
        private const val TAG = "LocalNLP GpsMonitor"
        private val DEBUG = BuildConfig.DEBUG
        private const val GPS_SAMPLE_TIME = 0L
        private const val GPS_SAMPLE_DISTANCE = 0f
    }
}

const val ACTIVE_MODE_TIME = "time"
const val ACTIVE_MODE_ACCURACY = "accuracy"
const val ACTIVE_MODE_ACTION = "start_gps"
private const val NOTIFICATION_ID = 76593265 // does it matter?
private const val CHANNEL_ID = "gps_active"
