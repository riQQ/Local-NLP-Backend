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

import android.util.Log
import java.lang.Exception
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Created by tfitch on 10/4/17.
 */
/**
 * This class forms a complete identification for a RF emitter.
 *
 * All it has are two fields: A rfID string that must be unique within a type
 * or class of emitters. And a rtType value that indicates the type of RF
 * emitter we are dealing with.
 */
data class RfIdentification(
        val rfId: String,
        val rfType: EmitterType
    ) : Comparable<RfIdentification> {
    val uniqueId = generateUniqueId(rfType, rfId)

    override operator fun compareTo(other: RfIdentification): Int {
        return uniqueId.compareTo(other.uniqueId)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RfIdentification) return false
        return uniqueId == other.uniqueId
    }

    /**
     * Return a hash code for Android to determine if we are like
     * some other object. Since we already have a unique ID computed
     * for our database records, use that but turn it into the int
     * expected by Android.
     *
     * @return Int Android hash code
     */
    override fun hashCode(): Int {
        return uniqueId.hashCode()
    }

    /**
     * Generate a unique string for our RF identification. Using MD5 as it
     * ought not have collisions but is relatively cheap to compute. Since
     * we aren't doing cryptography here we need not worry about it being
     * a secure hash.
     *
     * @param rfType The type of emitter
     * @param rfIdent The ID string unique to the type of emitter
     * @return String A unique identification string
     */
    private fun generateUniqueId(rfType: EmitterType, rfIdent: String): String {
        var hashtext = "$rfType:$rfIdent"
        try {
            val bytes = hashtext.toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(bytes)
            val bigInt = BigInteger(1, digest)
            hashtext = bigInt.toString(16)
            while (hashtext.length < 32) {
                hashtext = "0$hashtext"
            }
        } catch (e: Exception) {
            Log.d(TAG, "genUniqueId(): Exception" + e.message)
        }
        return hashtext
    }

    companion object {
        private const val TAG = "DejaVu RfIdent"
    }

}