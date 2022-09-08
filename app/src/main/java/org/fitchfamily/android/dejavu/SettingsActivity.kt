package org.fitchfamily.android.dejavu

/*
*    DejaVu - A location provider backend for microG/UnifiedNlp
*
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

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog // deprecated, but replacement is annoying to handle...
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import java.io.File

// deprecated, but replacement is annoying to handle...
class SettingsActivity : PreferenceActivity() {

    private var settingsChanged = false
    private val prefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener {_, key ->
        settingsChanged = true
    }
    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        findPreference(PREF_CULL)?.setOnPreferenceClickListener {
            onClickCull()
            true
        }
        findPreference(PREF_IMPORT)?.setOnPreferenceClickListener {
            onClickImport()
            true
        }
        findPreference(PREF_EXPORT)?.setOnPreferenceClickListener {
            onClickExport()
            true
        }
        findPreference(PREF_SHOW_EMITTERS)?.setOnPreferenceClickListener {
            onClickScan()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if (settingsChanged) {
            BackendService.instance?.apply {
                onClose()
                onOpen()
            }
        }
    }

    private fun onClickCull() {
        val currentChoice = when (prefs.getInt(PREF_CULL, 0)) {
            1 -> R.string.pref_cull_median
            2 -> R.string.pref_cull_none
            else -> R.string.pref_cull_default
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pref_cull_title))
            .setMessage(getString(R.string.pref_cull_message, getString(currentChoice)))
            .setPositiveButton(R.string.pref_cull_default) { _,_ -> prefs.edit().putInt(PREF_CULL, 0).apply() }
            .setNeutralButton(R.string.pref_cull_median) { _,_ -> prefs.edit().putInt(PREF_CULL, 1).apply() }
            .setNegativeButton(R.string.pref_cull_none) { _,_ -> prefs.edit().putInt(PREF_CULL, 2).apply() }
            .show()
    }

    private fun onClickImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, IMPORT_CODE)
    }

    private fun onClickExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT){
            Toast.makeText(this, R.string.export_jb, Toast.LENGTH_LONG).show()
            return
        }
        // todo: how to do it for old api?
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val fileName = "emitters.csv"
            putExtra(Intent.EXTRA_TITLE, fileName)
            type = "application/text"
        }
        startActivityForResult(intent, EXPORT_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null)
            return
        val uri = data.data ?: return
        when (requestCode) {
            IMPORT_CODE -> importFile(uri)
            EXPORT_CODE -> exportToFile(uri)
        }
    }

    private fun importFile(uri: Uri) {
        val inputStream = contentResolver?.openInputStream(uri) ?: return
        val reader = inputStream.bufferedReader()
        // if text, determine format and insert
        // if db, copy content

        // but first open a dialog
        // what to do on collisions (keep local, overwrite, merge)
        val collisionReplace = SQLiteDatabase.CONFLICT_REPLACE
        val collisionKeep = SQLiteDatabase.CONFLICT_IGNORE
        val collisionMerge = 0

        fun readFromFile(collision: Int, readFormat: Int, mlsIgnoreTypes: Set<String>, mlsCountryCodes: Set<String>) {
            val db = Database(this)
            val pd = ProgressDialog(this)
            var importJob: Job? = null
            var i = 0
            var j = 0
            pd.setTitle(R.string.import_title)
            pd.setMessage(getString(R.string.import_number_progress, i, j))
            pd.setOnCancelListener {
                importJob?.cancel()
                Toast.makeText(this, R.string.import_canceled, Toast.LENGTH_LONG).show()
            }
            pd.show()
            val sa = this
            importJob = scope.launch(Dispatchers.IO) {
                db.beginTransaction()
                reader.useLines {
                    val iter = it.iterator()
                    var line: String
                    while (iter.hasNext()) {
                        line = iter.next()
                        try {
                            val splitLine = parseLine(line, readFormat, mlsIgnoreTypes, mlsCountryCodes)
                            if (splitLine == null) j++
                            else {
                                db.insertLine(
                                    collision,
                                    splitLine[0],
                                    splitLine[1],
                                    splitLine[2].toDouble(),
                                    splitLine[3].toDouble(),
                                    splitLine[4].toDouble(),
                                    splitLine[5].toDouble(),
                                    splitLine[6]
                                )
                                i++
                            }
                            if ((i + j) % 500 == 0) {

                                runOnUiThread { pd.setMessage(getString(R.string.import_number_progress, i, j)) }
                                if (!coroutineContext.isActive) {
                                    db.cancelTransaction()
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            db.cancelTransaction()
                            Log.w(TAG, "import / readFromFile - error parsing line $line", e)
                            runOnUiThread { Toast.makeText(sa, getString(R.string.import_error_line, line), Toast.LENGTH_LONG).show() }
                            break
                        }
                    }
                }
                db.endTransaction()
                db.close()
                inputStream.close()
                pd.dismiss()
                runOnUiThread { Toast.makeText(sa, R.string.import_done, Toast.LENGTH_LONG).show() }
            }
        }

        fun readFromDatabase(collision: Int) {
            val db = Database(this)
            val dbFile = File(this.applicationInfo.dataDir + File.separator + "databases" + File.separator + "tmp.db")
            dbFile.delete()
            dbFile.parentFile?.mkdirs()
            val pd = ProgressDialog(this)
            var importJob: Job? = null
            pd.setTitle(R.string.import_title)
            pd.setMessage("0 / ?")
            pd.setOnCancelListener {
                importJob?.cancel()
                Toast.makeText(this, R.string.import_canceled, Toast.LENGTH_LONG).show()
            }
            pd.show()
            val sa = this
            importJob = scope.launch(Dispatchers.IO) {
                dbFile.outputStream().use { inputStream.copyTo(it) }
                inputStream.close()
                try {
                    val otherDb = Database(sa, "tmp.db")
                    val max = otherDb.getSize()
                    pd.setMessage("0 / $max")
                    // read each entry and copy it to db, respecting collision
                    otherDb.beginTransaction()
                    db.beginTransaction()
                    var i = 0
                    otherDb.getAll().forEach {
                        db.insert(it, collision)
                        i++
                        if (i % 100 == 0) {
                            runOnUiThread { pd.setMessage("$i / $max") }
                            if (!coroutineContext.isActive) {
                                db.cancelTransaction()
                                db.close()
                                otherDb.endTransaction()
                                otherDb.close()
                                dbFile.delete()
                                return@launch
                            }
                        }
                    }
                    otherDb.endTransaction()
                    otherDb.close()
                } catch (e: Exception) {
                    Log.w(TAG, "import / readFromDatabase - error", e)
                    db.cancelTransaction()
                    runOnUiThread { Toast.makeText(sa, getString(R.string.import_error_database, e.message), Toast.LENGTH_LONG).show() }
                }
                db.endTransaction()
                dbFile.delete()
                db.close()
                pd.dismiss()
                runOnUiThread { Toast.makeText(sa, R.string.import_done, Toast.LENGTH_LONG).show() }
            }
        }

        fun import(collision: Int) {
            BackendService.instance?.onClose()
            if (uri.toString().endsWith(".db")) {
                readFromDatabase(collision)
                return
            }
            val firstLine = reader.readLine()
            val readFormat = when {
                firstLine == MLS -> 0
                firstLine.substringAfter("database v").toIntOrNull() == 4 -> {
                    reader.readLine() // skip the header line, as it's not used
                    4
                }
                else -> {
                    Toast.makeText(this, R.string.import_error_format, Toast.LENGTH_LONG).show()
                    return
                }
            }

            val mlsIgnoreTypes = mutableSetOf<String>()
            val mlsCountryCodes = mutableSetOf<String>()
            if (readFormat != 0) {
                readFromFile(collision, readFormat, mlsIgnoreTypes, mlsCountryCodes)
                return
            }
            val gsmBox = CheckBox(this).apply {
                text = resources.getString(R.string.import_mls_use_type, "GSM")
                isChecked = true
            }
            val umtsBox = CheckBox(this).apply {
                text = resources.getString(R.string.import_mls_use_type, "UMTS")
                isChecked = true
            }
            val lteBox = CheckBox(this).apply {
                text = resources.getString(R.string.import_mls_use_type, "LTE")
                isChecked = true
            }
            val countryCodesText = TextView(this).apply { setText(R.string.import_mls_country_code_message) }
            val countryCodes = EditText(this)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(gsmBox)
                addView(umtsBox)
                addView(lteBox)
                addView(countryCodesText)
                addView(countryCodes)
                setPadding(30, 10, 30, 10)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.import_mls_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!gsmBox.isChecked) mlsIgnoreTypes.add("GSM")
                    if (!umtsBox.isChecked) mlsIgnoreTypes.add("UMTS")
                    if (!lteBox.isChecked) mlsIgnoreTypes.add("LTE")
                    mlsCountryCodes.addAll(
                        countryCodes.text.toString().split(',').map { it.trim() })
                    readFromFile(collision, readFormat, mlsIgnoreTypes, mlsCountryCodes)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_import_title)
            .setMessage(R.string.import_message)
            .setPositiveButton(R.string.import_replace) { _,_ -> import(collisionReplace) }
            .setNeutralButton(R.string.import_merge) { _,_ -> import(collisionMerge) }
            .setNegativeButton(R.string.import_keep) { _,_ -> import(collisionKeep) }
            .setOnCancelListener { inputStream.close() }
            .show()
    }

    /** converts the line to the 7 required entries for putting in DB */
    private fun parseLine(line: String, readFormat: Int, mlsIgnoreTypes: Set<String>, mlsCountryCodes: Set<String>): List<String>? {
        var splitLine = line.split(',')
        if (readFormat == 0) { // MLS cell export
            if (splitLine.first() in mlsIgnoreTypes || splitLine[1] !in mlsCountryCodes) return null
            val rfid = when (splitLine.first()) {
                "GSM" -> "GSM/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // GSM,202,0,42,26363 -> GSM/202/0/42/26363
                "UMTS" -> "WCDMA/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // UMTS,202,0,6060,4655229 -> WCDMA/202/0/6060/4655229
                "LTE" -> {
                    if (splitLine[5].isEmpty()) return null // why is this the case so often? do some phones not report it (properly)?
                    "LTE/${splitLine[1]}/${splitLine[2]}/${splitLine[4]}/${splitLine[5]}/${splitLine[3]}" //LTE,202,1,3126,35714457,20 -> LTE/202/1/35714457/20/3126
                }
                else -> ""
            }
            splitLine = listOf(rfid, rfid.substringBefore('/'), splitLine[7], splitLine[6], splitLine[8], splitLine[8], "")

        } else if (readFormat == 4 && splitLine.size != 7) {
            // we have one or more comma in ssid, rare enough to not optimize anything
            splitLine = splitLine.subList(0, 6) + splitLine.subList(6, splitLine.size).joinToString(",") // careful, subList endIndex is exclusive!
        }
        return splitLine
    }

    private fun exportToFile(uri: Uri) {
        val os = contentResolver?.openOutputStream(uri)?.bufferedWriter() ?: return
        BackendService.instance?.onClose()
        val db = Database(this)
        val pd = ProgressDialog(this)
        var exportJob: Job? = null
        val max = db.getSize()
        pd.setTitle(R.string.export_title)
        pd.setMessage("0 / $max")
        pd.setOnCancelListener {
            exportJob?.cancel()
            Toast.makeText(this, R.string.export_canceled, Toast.LENGTH_LONG).show()
        }
        pd.show()
        val sa = this
        exportJob = scope.launch(Dispatchers.IO) {
            try {
                os.write("database v4\n")
                os.write("${COL_RFID},${COL_TYPE},${COL_LAT},${COL_LON},${COL_RAD_NS},${COL_RAD_EW},${COL_NOTE}\n")
                db.beginTransaction()

                var i = 0
                db.getAll().forEach {
                    os.write("${it.uniqueId},${it.type},${it.lat},${it.lon},${it.radiusNS},${it.radiusEW},${it.note}\n")
                    i++
                    if (i % 100 == 0) {
                        runOnUiThread { pd.setMessage("$i / $max") }
                        if (!coroutineContext.isActive) {
                            db.cancelTransaction()
                            db.close()
                            os.close()
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "exportToFile - error", e)
                runOnUiThread { Toast.makeText(sa, getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show() }
            }
            db.endTransaction()
            os.close()
            db.close()
            runOnUiThread { Toast.makeText(sa, R.string.export_done, Toast.LENGTH_LONG).show() }
            pd.dismiss()
        }
    }

//    @Suppress("DEPRECATION") // requestSingleUpdate is deprecated, but there is no replacement for api < 30
    private fun onClickScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        )
            return // should not happen, permissions are requested on start

        // request location, because backendService may not be running
        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) // todo: enable this once API is upgraded
//            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, {  }, {  })
//        else
//            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, { }, null)
        val l = object : LocationListener {
            override fun onLocationChanged(p0: Location?) {}
            override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
            override fun onProviderEnabled(p0: String?) {}
            override fun onProviderDisabled(p0: String?) {}
        }
        lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, l, null)

        try {
            runBlocking { withTimeout(3500) {
                while (BackendService.instance == null) { delay(100) }
            } }
            BackendService.instance!!.settingsActivity = this
            Toast.makeText(this, R.string.show_scan_started, Toast.LENGTH_LONG).show()
        } catch (e: CancellationException) {
            Toast.makeText(this, R.string.show_scan_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetTextI18n") // we want to concatenate the text string, requiring resource strings for ids doesn't make sense
    fun showEmitters(emitters: Collection<RfEmitter>) = runOnUiThread {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var d: AlertDialog? = null
        val database = Database(this)
        emitters.sortedBy { it.uniqueId }.forEach { emitter ->
            val emitterInDb = database.getEmitter(emitter.rfIdentification) != null
            val t = TextView(this).apply { text = emitter.uniqueId + (if (emitter.note.isNotBlank()) ", ${emitter.note}" else "") }
            t.setOnClickListener {
                val text = listOfNotNull(
                    getString(R.string.show_scan_details_emitter_type, emitter.type),
                    if (emitter.type == EmitterType.WLAN2 || emitter.type == EmitterType.WLAN5)
                        getString(R.string.show_scan_details_emitter_ssid, emitter.note)
                    else null,
                    getString(R.string.show_scan_details_emitter_center, emitter.lat, emitter.lon),
                    getString(R.string.show_scan_details_emitter_radius_ew, emitter.radiusEW),
                    getString(R.string.show_scan_details_emitter_radius_ns, emitter.radiusNS),
                    if (emitter.status == EmitterStatus.STATUS_BLACKLISTED) getString(R.string.show_scan_details_emitter_blacklisted) else null,
                    if (!emitterInDb) getString(R.string.show_scan_details_emitter_not_in_db) else null,
                ).joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.show_scan_details_emitter, emitter.uniqueId))
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            val b = Button(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                if (emitterInDb) setBackgroundResource(android.R.drawable.ic_delete)
                isEnabled = emitterInDb
            }
            b.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.show_scan_emitter_delete, emitter.uniqueId))
                    .setPositiveButton(R.string.show_scan_emitter_delete_confirm) { _, _ ->
                        val db = Database(this)
                        db.beginTransaction()
                        db.drop(emitter)
                        db.endTransaction()
                        db.close()
                        d?.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            val l = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(b)
                addView(t)
            }
            layout.addView(l)
        }
        database.close()
        layout.setPadding(30,10,30,10)
        d = AlertDialog.Builder(this)
            .setTitle(R.string.show_scan_results)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(android.R.string.ok, null)
            .create()
        d.show()
    }

}

private const val IMPORT_CODE = 6957238
private const val EXPORT_CODE = 75902745

const val PREF_KALMAN = "pref_kalman"
const val PREF_MOBILE = "pref_use_cell"
const val PREF_WIFI = "pref_use_wlan"
const val PREF_BUILD = "build"
const val PREF_CULL = "pref_cull"
const val PREF_IMPORT = "pref_import"
const val PREF_EXPORT = "pref_export"
const val PREF_SHOW_EMITTERS = "pref_show_emitters"

private const val MLS = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"
private const val TAG = "LocalNLP Settings"
