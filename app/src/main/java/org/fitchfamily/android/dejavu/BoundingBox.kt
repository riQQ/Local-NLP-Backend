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

import android.location.Location
import java.lang.Math.toRadians
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Created by tfitch on 9/28/17.
 * modified by helium314 in 2022
 */
class BoundingBox private constructor() {
    var center_lat: Double = 0.0
        private set
    var center_lon: Double = 0.0
        private set
    var radius_ns: Double = 0.0
        private set
    var radius_ew: Double = 0.0
        private set

    var north = -91.0 // Impossibly south
        private set
    var south = 91.0 // Impossibly north
        private set
    var east = -181.0 // Impossibly west
        private set
    var west = 181.0 // Impossibly east
        private set
    var radius = 0.0
        private set

    constructor(info: EmitterInfo) : this(info.latitude, info.longitude, info.radius_ns, info.radius_ew)

    constructor(lat: Double, lon: Double) : this() {
        update(lat, lon)
    }

    constructor(lat: Double, lon: Double, r_ns: Double, r_ew: Double) : this() {
        if (r_ns < 0 || r_ew < 0) throw IllegalArgumentException("radii cannot be < 0")
        center_lat = lat
        center_lon = lon
        radius_ns = r_ns
        radius_ew = r_ew
        radius = sqrt(radius_ns * radius_ns + radius_ew * radius_ew)

        north = center_lat + radius_ns * METER_TO_DEG
        south = center_lat - radius_ns * METER_TO_DEG
        val cosLat = cos(toRadians(center_lat)).coerceAtLeast(MIN_COS)
        east = center_lon + radius_ew * METER_TO_DEG / cosLat
        west = center_lon - radius_ew * METER_TO_DEG / cosLat
    }

    /**
     * Update the bounding box to include a point at the specified lat/lon
     * @param lat The latitude to be included in the bounding box
     * @param lon The longitude to be included in the bounding box
     * @return whether coverage has changed
     */
    fun update(lat: Double, lon: Double): Boolean {
        var updated = false
        if (lat > north) {
            north = lat
            updated = true
        }
        if (lat < south) {
            south = lat
            updated = true
        }
        if (lon > east) {
            east = lon
            updated = true
        }
        if (lon < west) {
            west = lon
            updated = true
        }
        if (updated) {
            center_lat = (north + south) / 2.0
            center_lon = (east + west) / 2.0
            radius_ns = ((north - center_lat) * DEG_TO_METER)
            val cosLat = cos(toRadians(center_lat)).coerceAtLeast(MIN_COS)
            radius_ew = ((east - center_lon) * DEG_TO_METER * cosLat)
            radius = sqrt(radius_ns * radius_ns + radius_ew * radius_ew)
        }
        return updated
    }

    override fun toString(): String {
        return "($north,$west,$south,$east,$center_lat,$center_lon,$radius_ns,$radius_ew,$radius)"
    }

    fun contains(location: Location): Boolean =
        north > location.latitude && south < location.latitude
                && east > location.longitude && west < location.longitude

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoundingBox) return false
        return center_lat == other.center_lat && center_lon == other.center_lon
                && radius_ns == other.radius_ns && radius_ew == other.radius_ew
    }
}
