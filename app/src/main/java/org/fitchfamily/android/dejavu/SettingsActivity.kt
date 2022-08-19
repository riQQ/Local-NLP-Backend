package org.fitchfamily.android.dejavu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import java.io.File

class SettingsActivity : PreferenceActivity() {

    // TODO 1: test everything...
    //  export
    //  import from export, from mls, from db file
    // TODO 2: need to exit / restart app after settings changed? check!

    private var settingsChanged = false
    private val prefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener {_, key ->
        settingsChanged = true
    }

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
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if (settingsChanged)
            BackendService.instance?.onClose()
    }

    private fun onClickCull() {
        // todo: make a nicer radio button menu, with current selection being shown
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_cull_title)
            .setMessage(R.string.pref_cull_message)
            .setPositiveButton(R.string.pref_cull_default) { _,_ -> prefs.edit().putInt(PREF_CULL, 0).apply() }
            .setNeutralButton(R.string.pref_cull_median) { _,_ -> prefs.edit().putInt(PREF_CULL, 1).apply() }
            .setNegativeButton(R.string.pref_cull_none) { _,_ -> prefs.edit().putInt(PREF_CULL, 2).apply() }
            .show()
    }

    private fun onClickImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { // any real difference to ACTION_OPEN_DOCUMENT?
            addCategory(Intent.CATEGORY_OPENABLE)
            // maybe extra_local_only
            type = "*/*"
        }
        startActivityForResult(intent, IMPORT_CODE)
    }

    private fun onClickExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
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
            EXPORT_CODE -> exportToFile(uri) // todo: this may take LONG -> do it in background, and give some feedback that it started / finished
        }
    }

    private fun importFile(uri: Uri) {
        val f = File(this.applicationInfo.dataDir + File.separator + "temp_import_file")
        val inputStream = contentResolver?.openInputStream(uri) ?: return
        f.outputStream().use {
            inputStream.copyTo(it)
        }
        inputStream.close()
        // so now we have the data in "f"
        // if text, determine format and insert
        // if db, copy content

        // but first open a dialog
        // what to do on collisions (keep local, overwrite, merge)
        // TODO:
        //  for mls import allow filtering by type and country code
        //  and make the import faster, it's horribly slow (10 min for 300k emitters)
        //  and show some sort of progress bar
        val collisionReplace = SQLiteDatabase.CONFLICT_REPLACE
        val collisionKeep = SQLiteDatabase.CONFLICT_IGNORE
        val collisionMerge = 0

        fun import(collision: Int) {
            BackendService.instance?.onClose()
            val db = Database(this)
//            db.writableDatabase.beginTransaction()
            if (uri.toString().endsWith(".db")) {
                val dbFile = File(this.applicationInfo.dataDir + File.separator + "databases" + File.separator + "tmp.db")
                dbFile.delete()
                dbFile.parentFile.mkdirs()
                f.copyTo(dbFile)
                try {
                    val otherDb = Database(this, "tmp.db")
                    // read each entry and copy it to db, respecting collision
                    otherDb.writableDatabase.beginTransaction()
//                    val count: Int
//                    otherDb.writableDatabase.rawQuery("SELECT COUNT(*) FROM ${Database.TABLE_SAMPLES}", null).use { count = it.getInt(0) }
                    otherDb.getAll().forEach {
                        db.putLine(collision, it.uniqueId, it.type.toString(), it.lat, it.lon, it.radiusNS, it.radiusEW, it.note)
                    }
                    otherDb.writableDatabase.endTransaction()
                    otherDb.close()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.import_error_database, e.message), Toast.LENGTH_LONG).show()
                }
                dbFile.delete()
            } else {
                f.useLines {
                    val iter = it.iterator()
                    var line = iter.next()
                    val readFormat = when {
                        line == MLS -> 0
                        line.substringAfter("database v").toIntOrNull() == 4 -> {
                            iter.next() // skip the header line, as it's not used
                            4
                        }
                        else -> {
                            Toast.makeText(this, R.string.import_error_format, Toast.LENGTH_LONG).show()
                            return
                        }
                    }
                    while (iter.hasNext()) {
                        line = iter.next()
                        try {
                            val splitLine = parseLine(line, readFormat)
                            db.putLine(
                                collision,
                                splitLine[0],
                                splitLine[1],
                                splitLine[2].toDouble(),
                                splitLine[3].toDouble(),
                                splitLine[4].toDouble(),
                                splitLine[5].toDouble(),
                                splitLine[6]
                            )
                        } catch (e: Exception) {
                            Toast.makeText(this, getString(R.string.import_error_line, line), Toast.LENGTH_LONG).show()
                            Log.i(TAG, "import from file: error parsing line $line", e)
                            return
                        }
                    }
                }
            }
//            db.writableDatabase.endTransaction()
            db.close()
            f.delete()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_import_title)
            .setMessage(R.string.import_message)
            .setPositiveButton(R.string.import_replace) { _,_ -> import(collisionReplace) }
            .setNeutralButton(R.string.import_merge) { _,_ -> import(collisionMerge) }
            .setNegativeButton(R.string.import_keep) { _,_ -> import(collisionKeep) }
            .setOnCancelListener { f.delete() }
            .show()
    }

    /** converts the line to the 7 required entries for putting in DB */
    private fun parseLine(line: String, readFormat: Int): List<String> {
        var splitLine = line.split(',')
        if (readFormat == 0) { // MLS cell export
            val rfid = when (splitLine.first()) {
                // todo: doing it like this, too often nothing is found... -> check how mozilla backend does it
                //  also the last number for LTE is often nothing/empty in MLS, but must be integer
                //   simply using 0 will not work, never found this in my db... but maybe some phones don't report it?
                "GSM" -> "GSM/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // GSM,202,0,42,26363
                "UMTS" -> "WCDMA/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // UMTS,202,0,6060,4655229
                "LTE" -> "LTE/${splitLine[1]}/${splitLine[2]}/${splitLine[4]}/${splitLine[6]}/${splitLine[5]}" //LTE,202,1,3126,35714457,20
                else -> ""
            }
            splitLine = listOf(rfid, rfid.substringBefore('/'), splitLine[7], splitLine[6], splitLine[8], splitLine[8], "")

        } else if (readFormat == 4 && splitLine.size != 7) {
            // we have one or more comma in ssid, rare enough to not optimize anything
            splitLine = splitLine.subList(0, 6) + splitLine.subList(6, splitLine.size).joinToString(",") // careful, endIndex is exclusive!
        }
        return splitLine
    }

    private fun exportToFile(uri: Uri) {
        val os = contentResolver?.openOutputStream(uri)?.bufferedWriter() ?: return
        BackendService.instance?.onClose()
        val db = Database(this)
        os.write("database v4\n")
        os.write("${Database.COL_RFID},${Database.COL_TYPE},${Database.COL_LAT},${Database.COL_LON},${Database.COL_RAD_NS},${Database.COL_RAD_EW},${Database.COL_NOTE}\n")
        db.writableDatabase.beginTransaction()
        // 90s for exporting 300k emitters / 30 MB -> not great, but ok
        db.getAll().forEach { os.write("${it.uniqueId},${it.type},${it.lat},${it.lon},${it.radiusNS},${it.radiusEW},${it.note}\n") }
        db.writableDatabase.endTransaction()
        os.close()
        db.close()
    }

    private fun onClickClear() {
        // todo: confirmation dialog
        //  and close backend service
        //  but why actually? it's basically the same thing as clearing app data
        BackendService.instance?.onClose()
        this.deleteDatabase(Database.NAME)
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

private const val MLS = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"
private const val TAG = "DejaVu Settings"
