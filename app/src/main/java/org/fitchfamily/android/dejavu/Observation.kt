package org.fitchfamily.android.dejavu

import org.fitchfamily.android.dejavu.BackendService.Companion.getCorrectedAsu

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

/**
 * Created by tfitch on 10/5/17.
 * modified by helium314 in 2022
 */
/**
 * A single observation made of a RF emitter.
 *
 * Used to convey all the information we have collected in the foreground about
 * a RF emitter we have seen to the background thread that actually does the
 * heavy lifting.
 *
 * It contains an identifier for the RF emitter (type and id), the received signal
 * level and optionally a note about about the emitter.
 */
data class Observation(
    val identification: RfIdentification,
    var asu: Int = MINIMUM_ASU,
    val elapsedRealtimeNanos: Long,
    val note: String = "",
    val suspicious: Boolean = false, // means that we don't trust the device that observation is correct
) {
    internal constructor(id: String, type: EmitterType, asu: Int, realtimeNanos: Long) : this(RfIdentification(id, type), asu, realtimeNanos)

    init {
        asu = identification.rfType
            .getCorrectedAsu(asu.coerceAtLeast(MINIMUM_ASU).coerceAtMost(MAXIMUM_ASU))
    }

    val lastUpdateTimeMs = System.currentTimeMillis()

}
