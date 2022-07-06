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

/**
 * Created by tfitch on 10/4/17.
 */
/**
 * This class forms a complete identification for a RF emitter.
 *
 * All it has are two fields: A rfID string that must be unique within a type
 * or class of emitters. And a rfType value that indicates the type of RF
 * emitter we are dealing with.
 */
data class RfIdentification(
        val rfId: String,
        val rfType: EmitterType
    ) {
    // the string is already unique, and the hash isn't any shorter
    val uniqueId = toString()

    override fun toString(): String =
        when (rfType) {
            EmitterType.WLAN2, EmitterType.WLAN5, EmitterType.WLAN6 -> rfType.name + '/' + rfId
            else -> rfId
        }

}
