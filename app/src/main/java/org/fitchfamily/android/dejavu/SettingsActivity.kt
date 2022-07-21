package org.fitchfamily.android.dejavu

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment
import android.support.v4.app.FragmentActivity
import java.io.File
import java.io.FileInputStream

class SettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
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
        // try replacing by action_edit for old apis?
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val fileName = "emitters.csv"
            putExtra(Intent.EXTRA_TITLE, fileName)
            type = "application/text"
        }
        startActivityForResult(intent, EXPORT_CODE)
    }

    private fun onClickClear() {
        // todo: confirmation dialog
        //  and close backend service
        BackendService.instance?.onClose()
        this.deleteDatabase(Database.NAME)
    }

    private fun importFile(uri: Uri) {
        // determine what file we have
        val f = File(applicationContext?.applicationInfo?.dataDir + File.separator + "temp_import_file")
        val inputStream = contentResolver?.openInputStream(uri) ?: return
        f.outputStream().use {
            inputStream.copyTo(it)
        }
        inputStream.close()
        // so now we have the data in "f"
        // -> read first line to get info
//        f.inputStream().
        // if text, determine format and insert
        // if db, try to copy content

        // but first open a dialog
        // what to do on collisions (keep local, overwrite, merge)
        // also inform that merge can be slow if db and stuff to insert contains many same emitters
        // and for mls import allow filtering type and country codes
        val collision = 0 // any of the 3 below
        val collisionReplace = SQLiteDatabase.CONFLICT_REPLACE
        val collisionKeep = SQLiteDatabase.CONFLICT_IGNORE
        val collisionMerge = 0
        BackendService.instance?.onClose()
        val db = Database(applicationContext)
        db.beginTransaction()
        f.inputStream().use {
            var line = readLine()
            val readFormat = if (line == "database v4")
                1
            else if (line == "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal")
                2
            else return@use
            while (line != null) {
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
                    // make toast informing about problems (string format, db issues)
                }
                line = readLine()
            }
        }


        db.endTransaction()
        db.close()
        f.delete()
    }

    private fun parseLine(line: String, readFormat: Int): List<String> {
        var splitLine = line.split(',')
        if (readFormat == 1 && splitLine.size != 7) {
            // we have one or more comma in ssid, rare enough to not optimize anything
            splitLine = splitLine.subList(0, 5) + splitLine.subList(6, splitLine.lastIndex)
                .joinToString(",")
        } else if (readFormat == 2) {
            val rfid = when (splitLine.first()) {
                // todo: doing it like this, too often nothing is found... -> check how mozilla backend does it
                //  also the last number for LTE is often nothing in MLS, but must be integer
                //   simply using 0 will not work, never found this in my db... but maybe some phones don't report it?
                "GSM" -> "GSM/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // GSM,202,0,42,26363
                "UMTS" -> "WCDMA/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // UMTS,202,0,6060,4655229
                "LTE" -> "LTE/${splitLine[1]}/${splitLine[2]}/${splitLine[4]}/${splitLine[6]}/${splitLine[5]}" //LTE,202,1,3126,35714457,20
                else -> ""
            }
            splitLine = listOf(rfid, rfid.substringBefore('/'), splitLine[7], splitLine[6], splitLine[8], splitLine[8], "")

        }
        return splitLine
    }

    private fun exportToFile(uri: Uri) {
        val os = contentResolver?.openOutputStream(uri)?.bufferedWriter() ?: return
        BackendService.instance?.onClose()
        val db = Database(applicationContext)
        db.writeAllToCsv { os.write(it + "\n") }
        os.close()
        db.close()
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


}

private const val IMPORT_CODE = 6957238
private const val EXPORT_CODE = 75902745
