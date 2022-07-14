package org.fitchfamily.android.dejavu

import android.location.Location
import android.net.wifi.ScanResult
import android.os.Bundle
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

private val DEBUG = BuildConfig.DEBUG
private const val TAG = "DejaVu Util"

// DEG_TO_METER is only approximate, but an error of 1% is acceptable
//  for latitude it depends on latitude, from ~110500 (equator) ~111700 (poles)
//  for longitude at equator it's ~111300
const val DEG_TO_METER = 111225.0
const val METER_TO_DEG = 1.0 / DEG_TO_METER
const val MIN_COS = 0.01 // for things that are dividing by the cosine

private const val NULL_ISLAND_DISTANCE = 1000f
private const val NULL_ISLAND_DISTANCE_DEG = NULL_ISLAND_DISTANCE * METER_TO_DEG

// Define range of received signal strength to be used for all emitter types.
// Basically use the same range of values for LTE and WiFi as GSM defaults to.
const val MAXIMUM_ASU = 31
const val MINIMUM_ASU = 1

// KPH -> Meters/millisec (KPH * 1000) / (60*60*1000) -> KPH/3600
//        const val EXPECTED_SPEED = 120.0f / 3600 // 120KPH (74 MPH)
const val LOCATION_PROVIDER = "DejaVu"
private const val MINIMUM_BELIEVABLE_ACCURACY = 15.0F

// much faster than location.distanceTo(otherLocation)
// and less than 0.1% difference the small (< 1°) distances we're interested in
fun approximateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val distLat = (lat1 - lat2)
    val distLon = (lon1 - lon2) * cos(Math.toRadians(lat1))
    return sqrt(distLat * distLat + distLon * distLon) * DEG_TO_METER
}

/**
 * Check if location too close to null island to be real
 *
 * @param loc The location to be checked
 * @return boolean True if away from lat,lon of 0,0
 */
fun notNullIsland(loc: Location): Boolean = notNullIsland(loc.latitude, loc.longitude)
// simplified check that should avoid distance calculation in almost every case where this return true
fun notNullIsland(lat: Double, lon: Double): Boolean {
    return abs(lat) > NULL_ISLAND_DISTANCE_DEG
            || abs(lon) > NULL_ISLAND_DISTANCE_DEG
            || approximateDistance(lat, lon, 0.0, 0.0) > NULL_ISLAND_DISTANCE
}

// wifiManager.is6GHzBandSupported might be called to check whether it can be WLAN6
// but wifiManager.is5GHzBandSupported incorrectly returns no on some devices, so can we trust
// it to be correct for 6 GHz?
// anyway, there might be a better way of determining WiFi type
fun ScanResult.getWifiType(): EmitterType =
    when {
        frequency < 3000 -> EmitterType.WLAN2 // 2401 - 2495 MHz
        // 5945 can be WLAN5 and WLAN6, simply don't bother and assume WLAN5 for now
        frequency <= 5945 -> EmitterType.WLAN5 // 5030 - 5990 MHz, but at 5945 WLAN6 starts
        frequency > 6000 -> EmitterType.WLAN6 // 5945 - 7125
        frequency % 10 == 5 -> EmitterType.WLAN6 // in the overlapping range, WLAN6 frequencies end with 5
        else -> EmitterType.WLAN5
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
fun culledEmitters(locations: Collection<RfLocation>): Set<RfLocation>? {
    divideInGroups(locations).maxByOrNull { it.size }?.let { result ->
        // if we only have one location, use it as long as it's not an invalid emitter
        if (locations.size == 1 && result.single().type != EmitterType.INVALID) {
            if (DEBUG) Log.d(TAG, "culledEmitters() - got only one location, use it")
            return result
        }
        // Determine minimum count for a valid group of emitters.
        // The RfEmitter class will have put the min count into the location
        // it provided.
        result.forEach {
            if (result.size >= it.type.getRfCharacteristics().minCount)
                return result
        }
        if (DEBUG) Log.d(TAG, "culledEmitters() - only got ${result.size}, but " +
                "${result.minByOrNull { it.type.getRfCharacteristics().minCount }} are required")
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
private fun divideInGroups(locations: Collection<RfLocation>): List<MutableSet<RfLocation>> {
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
private fun locationCompatibleWithGroup(location: RfLocation, locGroup: Set<RfLocation>): Boolean {
    // If the location is within range of all current members of the
    // group, then we are compatible.
    for (other in locGroup) {
        if (approximateDistance(location.lat, location.lon, other.lat, other.lon) > location.accuracyEstimate + other.accuracyEstimate) {
            return false
        }
    }
    return true
}

/**
 * Shorter version of the original WeightedAverage, with adjusted weight to consider emitters
 * we don't know much about.
 * This ignores multiplying longitude accuracy by cosLat when converting to degrees, and
 * later dividing by cosLat when converting back to meters. It doesn't cancel out completely
 * because the used latitudes generally are slightly different, but differences are negligible
 * for our use.
 */
// main difference to the old WeightedAverage: accuracy is also influenced by how far
// apart the emitters are (sounds more relevant than it is, due to only "compatible" locations
// being used anyway)
fun Collection<RfLocation>.weightedAverage(): Location {
    val latitudes = DoubleArray(size)
    val longitudes = DoubleArray(size)
    val accuracies = DoubleArray(size)
    val weights = DoubleArray(size)
    forEachIndexed { i, it ->
        latitudes[i] = it.lat
        longitudes[i] = it.lon
        val minRange = it.type.getRfCharacteristics().minimumRange

        // weight should be asu / accuracyEstimate, but if we have seen the emitter only at a few
        // places close together, accuracyEstimate will be minimumRange even though we really don't
        // know where the emitter is. We want to decrease the weight for such emitters, and we do
        // it by increasing accuracy value for purpose of determining weight if radius is
        // suspiciously small. For radius 0 we us minimumRange + 0.5 * maximumRange
        val accuracyPartOfWeight = if (it.radius > minRange / 2) it.accuracyEstimate
        else minRange + (0.5 - it.radius / minRange) * it.type.getRfCharacteristics().maximumRange
        weights[i] = it.asu / accuracyPartOfWeight

        // The actual accuracy we want to use for this location is an adjusted accuracyEstimate.
        // If asu is good, we're likely close to the emitter, so we can decrease accuracy value.
        // For asu 31 (maximum) we use 0.74 * minRange + 0.26 * accuracyEstimate
        val asuAdjustedAccuracy = minRange + (1 - ((it.asu - MINIMUM_ASU) * 1.0 / (MAXIMUM_ASU + 10))) * (it.accuracyEstimate - minRange)

        // <Comment on the factor 0.5 from original WeightedAverage.java>
        // Our input has an accuracy based on the detection of the edge of the coverage area.
        // So assume that is a high (two sigma) probability and, worse, assume we can turn that
        // into normal distribution error statistic. We will assume our standard deviation (one
        // sigma) is half of our accuracy.
        accuracies[i] = asuAdjustedAccuracy * METER_TO_DEG * 0.5
    }
    return Location(LOCATION_PROVIDER).apply {
        extras = Bundle().apply { putInt("AVERAGED_OF", size) } // todo: was putInt in original weighted average, does it matter?

        // set newest times
        time = maxOf { it.time }
        elapsedRealtimeNanos = maxOf { it.elapsedRealtimeNanos }

        // set weighted means
        val latMean = weightedMean(latitudes, weights)
        val latVariance = weightedVariance(latMean, latitudes, accuracies, weights)
        val lonMean = weightedMean(longitudes, weights)
        val lonVariance = weightedVariance(lonMean, longitudes, accuracies, weights)
        latitude = latMean
        longitude = lonMean
        accuracy = (sqrt(latVariance + lonVariance) * DEG_TO_METER)
            .toFloat().coerceAtLeast(MINIMUM_BELIEVABLE_ACCURACY)
    }
}

/**
 * @returns the weighted mean of the given positions, accuracies and weights
 */
private fun weightedMean(positions: DoubleArray, weights: DoubleArray): Double {
    var weightedSum = 0.0
    positions.forEachIndexed { i, position ->
        weightedSum += position * weights[i]
    }
    return weightedSum / weights.sum()
}

/**
 * @returns the weighted variance of the given positions, accuracies and weights.
 * Variance and not stdDev because we need to square it anyway
 *
 * Actually this is not really correct, but it's good enough...
 * What we want from accuracy:
 *  more (very) similar locations should improve accuracy
 *  positions far apart should give worse accuracy, even if the single accuracies are similar
 */
private fun weightedVariance(weightedMeanPosition: Double, positions: DoubleArray, accuracies: DoubleArray, weights: DoubleArray): Double {
    // we have a situation like
    // https://stats.stackexchange.com/questions/454120/how-can-i-calculate-uncertainty-of-the-mean-of-a-set-of-samples-with-different-u#comment844099_454266
    // but we already have weights... so come up with something that gives reasonable results
    var weightedVarianceSum = 0.0
    positions.forEachIndexed { i, position ->
        weightedVarianceSum += weights[i] * weights[i] * (accuracies[i] * accuracies[i] + (position - weightedMeanPosition) * (position - weightedMeanPosition))
    }

    // this is not really variance, but still similar enough to claim it is
    // dividing by size should be fine...
    return weightedVarianceSum / (positions.size * weights.sumOf { it * it })
}
