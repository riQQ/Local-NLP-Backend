package org.fitchfamily.android.dejavu

class RfCharacteristics (
    val requiredGpsAccuracy: Float,
    val minimumRange: Float,
    val maximumRange: Float,        // Maximum believable coverage radius in meters
    val minCount: Int               // Minimum number of emitters before we can estimate location
)

private const val METERS: Long = 1
private const val KM = METERS * 1000

/**
 * Given an emitter type, return the various characteristics we need to know
 * to model it.
 *
 * @return The characteristics needed to model the emitter
 */
fun EmitterType.getRfCharacteristics(): RfCharacteristics =
    when (this) {
        EmitterType.WLAN2 -> characteristicsWlan24
        EmitterType.WLAN5, EmitterType.WLAN6 -> characteristicsWlan5 // small difference in frequency doesn't change range significantly
        EmitterType.GSM -> characteristicsGsm
        EmitterType.CDMA, EmitterType.WCDMA, EmitterType.TDSCDMA, EmitterType.LTE, EmitterType.NR -> characteristicsLte // maybe use separate characteristics?
        EmitterType.BT -> characteristicsBluetooth
        EmitterType.INVALID -> characteristicsUnknown
    }
fun rfchar(type: EmitterType) = type.getRfCharacteristics() // todo: needed for java -> convert WeightedAverage to kotlin and remove this

// For 2.4 GHz, indoor range seems to be described as about 46 meters
// with outdoor range about 90 meters. Set the minimum range to be about
// 3/4 of the indoor range and the typical range somewhere between
// the indoor and outdoor ranges.
// However we've seem really, really long range detection in rural areas
// so base the move distance on that.
private val characteristicsWlan24 = RfCharacteristics(
    15F * METERS,
    35F * METERS,
    300F * METERS,  // Seen pretty long detection in very rural areas
    2
)

private val characteristicsWlan5 = RfCharacteristics(
    10F * METERS,
    15F * METERS,
    100F * METERS,  // Seen pretty long detection in very rural areas
    2
)

// currently not used, planned for stationary beacons if this proves feasible
private val characteristicsBluetooth = RfCharacteristics(
    5F * METERS,
    2F * METERS,
    100F * METERS, // class 1 devices can have 100 m range
    2
)

private val characteristicsGsm = RfCharacteristics(
    100F * METERS,
    500F * METERS,
    200F * KM, // usual max is around 35 km, but extended range can be around 200 km
    1
)

// LTE cells are typically much smaller than GSM cells, but could also span the same huge areas.
// "small cells" could actually be some 10 m in size, but assuming all cells might be
// small cells would not be feasible, as it would increase requirements on accuracy and
// lead to bad (overly accurate) location reports for LTE cells only seen once
private val characteristicsLte = RfCharacteristics(
    50F * METERS,
    250F * METERS,
    100F * KM, // ca 35 km for macrocells, but apparently extended range possible
    1
)

// todo: 5G millimeter wave have less than 500 m range, but how to separate from "other" 5G?
private val characteristics5Gmm = RfCharacteristics(
    20F * METERS,
    50F * METERS,
    500F * KM,
    1
)

// Unknown emitter type, just throw out some values that make it unlikely that
// we will ever use it (require too accurate a GPS location, etc.).
private val characteristicsUnknown = RfCharacteristics(
    2F * METERS,
    50F * METERS,
    100F * METERS,
    99
)
