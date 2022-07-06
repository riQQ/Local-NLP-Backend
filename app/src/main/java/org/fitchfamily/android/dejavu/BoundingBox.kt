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
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Created by tfitch on 9/28/17.
 */
data class BoundingBox(
    var center_lat: Double = 0.0,
    var center_lon: Double = 0.0,
    var radius_ns: Double = 0.0,
    var radius_ew: Double = 0.0
    ) {
    var north = -91.0 // Impossibly south
        private set
    var south = 91.0 // Impossibly north
        private set
    var east = -181.0 // Impossibly west
        private set
    var west = 181.0 // Impossibly east
        private set
    var radius = sqrt(radius_ns * radius_ns + radius_ew * radius_ew)
        private set

    constructor(loc: Location) : this() {
        update(loc)
    }

    constructor(lat: Double, lon: Double, radius: Float) : this() {
        update(lat, lon, radius)
    }

    constructor(info: EmitterInfo) : this() {
        update(info.latitude, info.longitude, info.radius_ns, info.radius_ew)
    }

    /**
     * Expand, if needed, the bounding box to include the coverage area
     * implied by a location.
     * @param loc A record describing the coverage of an RF emitter.
     */
    private fun update(loc: Location): Boolean {
        return update(loc.latitude, loc.longitude, loc.accuracy)
    }

    /**
     * Expand bounding box to include an emitter at a lat/lon with a
     * specified radius.
     *
     * @param lat The center latitude for the coverage area.
     * @param lon The center longitude for the coverage area.
     * @param radius The radius of the coverage area.
     */
    private fun update(lat: Double, lon: Double, radius: Float): Boolean {
        return update(lat, lon, radius, radius)
    }

    /**
     * Expand bounding box to include an emitter at a lat/lon with a
     * specified radius.
     *
     * @param lat The center latitude for the coverage area.
     * @param lon The center longitude for the coverage area.
     * @param r_ns The distance from the center to the north (or south) edge.
     * @param r_ew The distance from the center to the east (or west) edge.
     */
    private fun update(lat: Double, lon: Double, r_ns: Float, r_ew: Float): Boolean {
        val locNorth = lat + r_ns * BackendService.METER_TO_DEG
        val locSouth = lat - r_ns * BackendService.METER_TO_DEG
        var cosLat = cos(Math.toRadians(lat))
        val locEast = lon + r_ew * BackendService.METER_TO_DEG / cosLat
        val locWest = lon - r_ew * BackendService.METER_TO_DEG / cosLat

        // return false if emitter bounding box already included in this bounding box
        if (!(locNorth > north || locSouth < south || locEast > east || locWest < west))
            return false

        // set new bounding box edges
        north = north.coerceAtLeast(locNorth)
        south = south.coerceAtMost(locSouth)
        east = east.coerceAtLeast(locEast)
        west = west.coerceAtMost(locWest)

        center_lat = (north + south) / 2.0
        center_lon = (east + west) / 2.0
        radius_ns = ((north - center_lat) * BackendService.DEG_TO_METER)
        cosLat = cos(Math.toRadians(center_lat)).coerceAtLeast(BackendService.MIN_COS)
        radius_ew = (east - center_lon) * BackendService.DEG_TO_METER * cosLat
        radius = sqrt(radius_ns * radius_ns + radius_ew * radius_ew)
        return true
    }

    /**
     * Update the bounding box to include a point at the specified lat/lon
     * @param lat The latitude to be included in the bounding box
     * @param lon The longitude to be included in the bounding box
     * @return whether coverage has changed
     */
    fun update(lat: Double, lon: Double): Boolean {
        var rslt = false
        if (lat > north) {
            north = lat
            rslt = true
        }
        if (lat < south) {
            south = lat
            rslt = true
        }
        if (lon > east) {
            east = lon
            rslt = true
        }
        if (lon < west) {
            west = lon
            rslt = true
        }
        if (rslt) {
            center_lat = (north + south) / 2.0
            center_lon = (east + west) / 2.0
            radius_ns = ((north - center_lat) * BackendService.DEG_TO_METER)
            val cosLat = cos(Math.toRadians(center_lat)).coerceAtLeast(BackendService.MIN_COS)
            radius_ew =
                ((east - center_lon) * BackendService.DEG_TO_METER / cosLat)
            radius = sqrt(radius_ns * radius_ns + radius_ew * radius_ew)
        }
        return rslt
    }

    override fun toString(): String {
        return "($north,$west,$south,$east,$center_lat,$center_lon,$radius_ns,$radius_ew,$radius)"
    }

    fun contains(location: Location): Boolean =
        north > location.latitude && south < location.latitude
                && east > location.longitude && west < location.longitude

}
