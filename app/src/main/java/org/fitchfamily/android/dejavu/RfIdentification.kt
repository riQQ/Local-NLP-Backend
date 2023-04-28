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

/**
 * Created by tfitch on 10/4/17.
 * modified by helium314 in 2022
 */
/**
 * This class forms a complete identification for a RF emitter.
 *
 * All it has are two fields: A rfID string that must be unique within a type
 * or class of emitters. And a rfType value that indicates the type of RF
 * emitter we are dealing with.
 */
class RfIdentification(val rfId: String, val rfType: EmitterType) {
    val uniqueId = when (rfType) {
            EmitterType.WLAN2, EmitterType.WLAN5, EmitterType.WLAN6 -> rfType.name + '/' + rfId
            else -> rfId
        }

    override fun toString(): String = uniqueId

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is RfIdentification) return uniqueId == other.uniqueId
        return false
    }

    override fun hashCode(): Int {
        return uniqueId.hashCode()
    }
}
