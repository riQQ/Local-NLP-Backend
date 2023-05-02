package org.fitchfamily.android.dejavu

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog

/**
 * Activity purely for handling geo uri intents. If the intent is valid and the user confirms
 * they want the location added, [BackendService.geoUriLocationProvided] is called.
 * The activity is always finished when handling is done or intent invalid.
 */
class HandleGeoUriActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!handleGeoUri()) finish() // maybe show toast?
    }

    // returns whether dialog is shown, so activity can be finished if not shown
    private fun handleGeoUri(): Boolean {
        if (BackendService.instance == null) return false
        val data = intent.data ?: return false
        if (data.scheme != "geo") return false

        // taken from StreetComplete (GeoUri.kt)
        val geoUriRegex = Regex("(-?[0-9]*\\.?[0-9]+),(-?[0-9]*\\.?[0-9]+).*?(?:\\?z=([0-9]*\\.?[0-9]+))?")
        val match = geoUriRegex.matchEntire(data.schemeSpecificPart) ?: return false
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return false
        if (latitude < -90 || latitude > +90) return false
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return false
        if (longitude < -180 || longitude > +180) return false

        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.handle_geo_uri_message, latitude, longitude))
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                BackendService.geoUriLocationProvided(latitude, longitude)
                finish()
            }
            .show()
        return true
    }
}
