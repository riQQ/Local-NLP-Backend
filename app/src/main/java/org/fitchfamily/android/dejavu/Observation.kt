package org.fitchfamily.android.dejavu

import org.fitchfamily.android.dejavu.BackendService.Companion.getCorrectedAsu

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
 * Created by tfitch on 10/5/17.
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
) {
    internal constructor(id: String, type: EmitterType, asu: Int, realtimeNanos: Long) : this(RfIdentification(id, type), asu, realtimeNanos)
    internal constructor(id: String, type: EmitterType, asu: Int, realtimeNanos: Long, note: String) :
            this(RfIdentification(id, type), asu, realtimeNanos, note)

    init {
        asu = identification.rfType
            .getCorrectedAsu(asu.coerceAtLeast(MINIMUM_ASU).coerceAtMost(MAXIMUM_ASU))
    }

    val lastUpdateTimeMs = System.currentTimeMillis()

}
