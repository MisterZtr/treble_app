package me.phh.treble.app

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemProperties
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.text.format.Formatter
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.URL
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread
import okhttp3.*
import org.json.JSONObject
import org.json.JSONTokener
import org.tukaani.xz.XZInputStream

class UpdaterActivity : AppCompatActivity() {
    private var OTA_JSON_URL: String = SystemProperties.get("ro.system.ota.json_url") ?: ""
    private var prefs: SharedPreferences? = null
    private var hasUpdate = false
    private var isUpdating = false
    private var otaJson = JSONObject()
    private var changelogText: String = ""
    private val REQUEST_CODE_PICK_OTA = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updater)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.title_activity_updater)
        }

        prefs = getSharedPreferences("ota_prefs", MODE_PRIVATE)
        OTA_JSON_URL = prefs?.getString("ota_json_url", OTA_JSON_URL) ?: OTA_JSON_URL

        updateUiElements(false)

        val btn_update = findViewById<Button>(R.id.btn_update)
        val btn_manual_ota = findViewById<Button>(R.id.btn_manual_ota)
        val btn_clear_ota = findViewById<Button>(R.id.btn_clear_ota)
        val btn_refresh_urls = findViewById<Button>(R.id.btn_refresh_urls)

        btn_update.setOnClickListener {
            if (!isUpdating) {
                if (hasUpdate) {
                    isUpdating = true
                    downloadUpdate()
                } else {
                    isUpdating = true
                    checkUpdate()
                }
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_activity_updater))
                    .setMessage(getString(R.string.prevent_exit_message))
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
            }
        }

        btn_manual_ota.setOnClickListener {
            if (!isUpdating) {
                startFilePicker()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_activity_updater))
                    .setMessage(getString(R.string.prevent_exit_message))
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
            }
        }

        btn_clear_ota.setOnClickListener {
            if (!isUpdating) {
                clearOtaFiles()
            } else {
                Toast.makeText(this, "Cannot clear OTA files during update", Toast.LENGTH_SHORT).show()
            }
        }

        btn_refresh_urls.setOnClickListener {
            if (!isUpdating) {
                refreshUrls()
            } else {
                Toast.makeText(this, "Cannot refresh URLs during update", Toast.LENGTH_SHORT).show()
            }
        }

        if (!isUpdating) {
            checkUpdate()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun clearOtaFiles() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_ota_message))
            .setMessage("This will delete all temporary OTA files. Continue?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    val otaFiles = cacheDir.listFiles { file -> file.name.startsWith("ota") && file.name.endsWith(".xz") }
                    var deletedCount = 0
                    otaFiles?.forEach { file ->
                        if (file.delete()) {
                            deletedCount++
                            Log.d("PHH", "Deleted OTA file: ${file.absolutePath}")
                        } else {
                            Log.e("PHH", "Failed to delete OTA file: ${file.absolutePath}")
                        }
                    }
                    Toast.makeText(this, "Deleted $deletedCount OTA files", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("PHH", "Error deleting OTA files: ${e.message}", e)
                    Toast.makeText(this, "Failed to delete OTA files: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun refreshUrls() {
        OTA_JSON_URL = SystemProperties.get("ro.system.ota.json_url") ?: ""

        if (OTA_JSON_URL.isEmpty()) {
            Log.e("PHH", "Nenhuma URL de OTA configurada")
            Toast.makeText(this, "Nenhuma URL de OTA configurada", Toast.LENGTH_SHORT).show()
            return
        }

        prefs?.edit()?.putString("ota_json_url", OTA_JSON_URL)?.apply()
        Log.d("PHH", "Usando OTA_JSON_URL: $OTA_JSON_URL")

        Toast.makeText(this, "Refreshing OTA URLs...", Toast.LENGTH_SHORT).show()

        isUpdating = true
        checkUpdate()
    }

    override fun onBackPressed() {
        if (isUpdating) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_activity_updater))
                .setMessage(getString(R.string.prevent_exit_message))
                .setPositiveButton(android.R.string.yes) { _, _ ->
                    super.onBackPressed()
                    finish()
                }
                .setNegativeButton(android.R.string.no) { _, _ -> }
                .show()
        } else {
            super.onBackPressed()
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_delete_ota -> {
                Log.e("PHH", "Initiating OTA uninstallation")
                val builder = AlertDialog.Builder(this)
                builder.setTitle(getString(R.string.warning_dialog_title))
                builder.setMessage("This will uninstall the OTA and restore the original system state. Continue?")
                builder.setPositiveButton(android.R.string.yes) { _, _ ->
                    Log.e("PHH", "OTA uninstallation in progress")
                    SystemProperties.set("sys.phh.uninstall-ota", "true")
                }
                builder.setNegativeButton(android.R.string.no) { _, _ ->
                    Log.e("PHH", "OTA uninstallation canceled")
                }
                builder.show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun checkUpdate() {
        val btn_update = findViewById<Button>(R.id.btn_update)
        val progress_container = findViewById<LinearLayout>(R.id.progress_container)
        val update_title = findViewById<TextView>(R.id.txt_update_title)

        runOnUiThread {
            btn_update.visibility = View.INVISIBLE
            progress_container.visibility = View.INVISIBLE
            update_title.text = getString(R.string.checking_update_title)
        }

        if (!isDynamic()) {
            Log.e("PHH", "Device does not support dynamic partitions, skipping update check")
            runOnUiThread {
                hasUpdate = false
                updateUiElements(false)
                Toast.makeText(this, "Device does not support OTA updates", Toast.LENGTH_SHORT).show()
            }
            return
        }

        isMagiskInstalled()
        if (OTA_JSON_URL.isEmpty()) {
            Log.e("PHH", "OTA_JSON_URL is empty")
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.error_dialog_title))
                    .setMessage("OTA update URL is not configured.")
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
                hasUpdate = false
                updateUiElements(false)
                btn_update.visibility = View.VISIBLE
                Toast.makeText(this, "No OTA URL configured", Toast.LENGTH_SHORT).show()
            }
            return
        }

        Log.d("PHH", "Checking OTA info at: $OTA_JSON_URL")
        val request = Request.Builder().url(OTA_JSON_URL).build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PHH", "Failed downloading OTA info. Error: ${e.message}", e)
                runOnUiThread {
                    AlertDialog.Builder(this@UpdaterActivity)
                        .setTitle(getString(R.string.error_dialog_title))
                        .setMessage("Failed to check for updates: ${e.message}")
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .show()
                    hasUpdate = false
                    updateUiElements(false)
                    btn_update.visibility = View.VISIBLE
                    isUpdating = false
                    Toast.makeText(this@UpdaterActivity, "Failed to check updates: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    Log.d("PHH", "Got response with code: ${response.code}")
                    if ((response.code == 200 || response.code == 304) && response.body != null) {
                        val body = response.body?.string()
                        Log.d("PHH", "Response body: $body")
                        if (body.isNullOrEmpty()) {
                            throw IllegalStateException("Empty response body")
                        }
                        otaJson = JSONTokener(body).nextValue() as JSONObject
                        runOnUiThread {
                            hasUpdate = existsUpdate()
                            updateUiElements(false)
                            btn_update.visibility = View.VISIBLE
                            if (hasUpdate) {
                                val version = getUpdateVersion()
                                val gsiName = if (otaJson.has("gsi")) otaJson.getString("gsi") else "Unknown"
                                val variant = getUpdateVariant()
                                Toast.makeText(
                                    this@UpdaterActivity,
                                    getString(R.string.update_available_message, version, gsiName, variant),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@UpdaterActivity,
                                    getString(R.string.no_update_available_message, getVariant()),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            if (hasUpdate && otaJson.has("changelog")) {
                                loadChangelog(otaJson.getString("changelog"))
                            }
                            isUpdating = false
                        }
                    } else {
                        throw IOException("Invalid HTTP response. Code: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e("PHH", "Error processing OTA response: ${e.message}", e)
                    runOnUiThread {
                        AlertDialog.Builder(this@UpdaterActivity)
                            .setTitle(getString(R.string.error_dialog_title))
                            .setMessage("Failed to process update info: ${e.message}")
                            .setPositiveButton(android.R.string.ok) { _, _ -> }
                            .show()
                        hasUpdate = false
                        updateUiElements(false)
                        btn_update.visibility = View.VISIBLE
                        isUpdating = false
                        Toast.makeText(this@UpdaterActivity, "Error processing update: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun updateUiElements(wasUpdated: Boolean) {
        val btn_update = findViewById<Button>(R.id.btn_update)
        val btn_manual_ota = findViewById<Button>(R.id.btn_manual_ota)
        val btn_clear_ota = findViewById<Button>(R.id.btn_clear_ota)
        val btn_refresh_urls = findViewById<Button>(R.id.btn_refresh_urls)
        val update_title = findViewById<TextView>(R.id.txt_update_title)
        val update_description = findViewById<TextView>(R.id.txt_update_description)

        if (!wasUpdated) {
            btn_update.visibility = View.VISIBLE
            btn_manual_ota.visibility = View.VISIBLE
            btn_clear_ota.visibility = View.VISIBLE
            btn_refresh_urls.visibility = View.VISIBLE
        }

        var update_description_text = "Current Android version: ${getAndroidVersion()}\n"
        update_description_text += "Current GSI variant: ${getVariant()}\n"
        update_description_text += "Current security patch: ${getPatchDate()}\n\n"

        if (hasUpdate) {
            update_description_text += "Update version: ${getUpdateVersion()}\n"
            update_description_text += "Update download size: ${getUpdateSize()}\n"
            if (otaJson.has("gsi")) {
                try {
                    update_description_text += "Update GSI name: ${otaJson.getString("gsi")}\n"
                } catch (e: Exception) {
                    Log.e("PHH", "Error reading GSI field: ${e.message}", e)
                    update_description_text += "Update GSI name: Unknown\n"
                }
            } else {
                update_description_text += "Update GSI name: Unknown\n"
            }
            val updateVariant = getUpdateVariant()
            update_description_text += "Update GSI variant: $updateVariant\n"
            if (otaJson.has("security_patch")) {
                try {
                    val securityPatch = otaJson.getString("security_patch")
                    update_description_text += "Update security patch: $securityPatch\n"
                } catch (e: Exception) {
                    Log.e("PHH", "Error reading security_patch field: ${e.message}", e)
                    update_description_text += "Update security patch: Unknown\n"
                }
            } else {
                update_description_text += "Update security patch: Unknown\n"
            }
            if (changelogText.isNotEmpty()) {
                update_description_text += "Changelog:\n$changelogText\n"
            }
            update_title.text = getString(R.string.update_found_title)
            btn_update.text = getString(R.string.update_found_button)
        } else if (!wasUpdated) {
            update_description_text += "No update available for variant: ${getVariant()}\n"
            update_title.text = getString(R.string.update_not_found_title)
            btn_update.text = getString(R.string.update_not_found_button)
        }
        update_description.text = update_description_text
    }

    private fun getUpdateVariant(): String {
        try {
            if (otaJson.length() > 0 && otaJson.has("variants")) {
                val otaVariants = otaJson.getJSONArray("variants")
                Log.d("PHH", "OTA variants found: ${otaVariants.length()}")
                for (i in 0 until otaVariants.length()) {
                    val otaVariant = otaVariants.get(i) as JSONObject
                    if (!otaVariant.has("name")) {
                        Log.e("PHH", "Invalid variant at index $i: missing name")
                        continue
                    }
                    val otaVariantName = otaVariant.getString("name")
                    Log.d("PHH", "Checking OTA variant: $otaVariantName against device variant: ${getVariant()}")
                    if (otaVariantName == getVariant()) {
                        Log.d("PHH", "Matching variant found: $otaVariantName")
                        return otaVariantName
                    }
                }
                Log.e("PHH", "No matching variant found for: ${getVariant()}")
                return "No matching variant"
            } else {
                Log.e("PHH", "OTA JSON missing 'variants' field or empty")
                return "Unknown"
            }
        } catch (e: Exception) {
            Log.e("PHH", "Error reading update variant: ${e.message}", e)
            return "Unknown"
        }
    }

    private fun loadChangelog(changelogUrl: String) {
        if (changelogUrl.isEmpty()) {
            Log.e("PHH", "Changelog URL is empty")
            return
        }

        Log.d("PHH", "Loading changelog from: $changelogUrl")
        val request = Request.Builder().url(changelogUrl).build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PHH", "Failed to load changelog: ${e.message}", e)
                runOnUiThread {
                    changelogText = "Failed to load changelog."
                    updateUiElements(false)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.code == 200 && response.body != null) {
                        val body = response.body?.string()
                        Log.d("PHH", "Changelog content: $body")
                        runOnUiThread {
                            changelogText = body ?: "Changelog unavailable."
                            updateUiElements(false)
                        }
                    } else {
                        throw IOException("Invalid response for changelog. Code: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e("PHH", "Error loading changelog: ${e.message}", e)
                    runOnUiThread {
                        changelogText = "Failed to load changelog: ${e.message}"
                        updateUiElements(false)
                    }
                }
            }
        })
    }

    private fun getAndroidVersion(): String {
        return SystemProperties.get("ro.system.build.version.release") ?: "Unknown"
    }

    private fun getPatchDate(): String {
        val patchDate = SystemProperties.get("ro.build.version.security_patch") ?: "Unknown"
        Log.d("PHH", "Security patch date: $patchDate")
        return try {
            val localDate = LocalDate.parse(patchDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        } catch (e: Exception) {
            Log.e("PHH", "Error parsing patch date: ${e.message}", e)
            patchDate
        }
    }

    private fun getUpdateVersion(): String {
        return try {
            if (otaJson.has("version")) {
                otaJson.getString("version")
            } else {
                Log.e("PHH", "OTA JSON missing 'version' field")
                "Unknown"
            }
        } catch (e: Exception) {
            Log.e("PHH", "Error reading version: ${e.message}", e)
            "Unknown"
        }
    }

    private fun getUpdateSize(): String {
        try {
            if (otaJson.length() > 0 && otaJson.has("variants")) {
                val otaVariants = otaJson.getJSONArray("variants")
                Log.d("PHH", "OTA variants found: ${otaVariants.length()}")
                for (i in 0 until otaVariants.length()) {
                    val otaVariant = otaVariants.get(i) as JSONObject
                    if (!otaVariant.has("name") || !otaVariant.has("size")) {
                        Log.e("PHH", "Invalid variant at index $i: missing name or size")
                        continue
                    }
                    val otaVariantName = otaVariant.getString("name")
                    Log.d("PHH", "Checking OTA variant: $otaVariantName against device variant: ${getVariant()}")
                    if (otaVariantName == getVariant()) {
                        val otaSize = otaVariant.getString("size")
                        Log.d("PHH", "OTA variant size: $otaSize")
                        return Formatter.formatShortFileSize(
                            this.applicationContext,
                            otaSize.toLong()
                        )
                    }
                }
                Log.e("PHH", "No matching variant found for: ${getVariant()}")
                return "No matching variant"
            } else {
                Log.e("PHH", "OTA JSON missing 'variants' field or empty")
                return "Unknown"
            }
        } catch (e: Exception) {
            Log.e("PHH", "Error reading update size: ${e.message}", e)
            return "Unknown"
        }
    }

    private fun isDynamic(): Boolean {
        val isDynamic = SystemProperties.get("ro.boot.dynamic_partitions")
        if (isDynamic != "true") {
            Log.e("PHH", "Device is not dynamic")
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.error_dialog_title))
                .setMessage(getString(R.string.dynamic_device_message))
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
            return false
        }
        Log.d("PHH", "Device is dynamic")
        return true
    }

    private fun isMagiskInstalled() {
        val magiskBin = File("/system/bin/magisk")
        if (magiskBin.exists()) {
            Log.d("PHH", "Magisk is installed")
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.warning_dialog_title))
                .setMessage(getString(R.string.magisk_exists_message))
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }

    private fun existsUpdate(): Boolean {
        try {
            if (otaJson.length() == 0) {
                Log.e("PHH", "OTA JSON is empty")
                return false
            }
            if (!otaJson.has("date")) {
                Log.e("PHH", "OTA JSON missing 'date' field")
                return false
            }
            val otaDate = otaJson.getString("date")
            Log.d("PHH", "OTA image date: $otaDate")
            val buildDate = SystemProperties.get("ro.system.build.date.utc")
            Log.d("PHH", "System image date: $buildDate")
            val otaDateLong = otaDate.toLongOrNull()
            val buildDateLong = buildDate.toLongOrNull()
            if (otaDateLong == null || buildDateLong == null) {
                Log.e("PHH", "Invalid date format: otaDate=$otaDate, buildDate=$buildDate")
                return false
            }
            if (otaDateLong > buildDateLong) {
                Log.d("PHH", "System image date is newer than the currently installed")
                return true
            }
            Log.d("PHH", "System image date is older than or equal to the currently installed")
            return false
        } catch (e: Exception) {
            Log.e("PHH", "Error checking update: ${e.message}", e)
            return false
        }
    }

    private fun getVariant(): String {
        var flavor = SystemProperties.get("ro.build.flavor").replace(Regex("-user(debug)?"), "")
        val secure = File("/system/phh/secure")
        var vndklite = File("/system_ext/apex/com.android.vndk.v29/lib64/vendor.qti.qcril.am@1.0.so")
        if (flavor.contains("_a64_")) {
            vndklite = File("/system_ext/apex/com.android.vndk.v29/lib/libstdc++.so")
        }
        if (secure.exists()) {
            flavor += "-secure"
        } else if (vndklite.exists()) {
            flavor += "-vndklite"
        }
        Log.d("PHH", "Variant GSI: $flavor")
        return flavor
    }

    private fun getUrl(): String {
        try {
            if (otaJson.length() == 0) {
                Log.e("PHH", "OTA JSON is empty")
                return ""
            }
            if (!otaJson.has("variants")) {
                Log.e("PHH", "OTA JSON missing 'variants' field")
                return ""
            }
            val otaVariants = otaJson.getJSONArray("variants")
            Log.d("PHH", "OTA variants found: ${otaVariants.length()}")
            for (i in 0 until otaVariants.length()) {
                val otaVariant = otaVariants.get(i) as JSONObject
                if (!otaVariant.has("name") || !otaVariant.has("url")) {
                    Log.e("PHH", "Invalid variant at index $i: missing name or url")
                    continue
                }
                val otaVariantName = otaVariant.getString("name")
                Log.d("PHH", "Checking OTA variant: $otaVariantName against device variant: ${getVariant()}")
                if (otaVariantName == getVariant()) {
                    val url = otaVariant.getString("url")
                    Log.d("PHH", "OTA URL: $url")
                    return url
                }
            }
            Log.e("PHH", "No matching variant found for: ${getVariant()}")
            return ""
        } catch (e: Exception) {
            Log.e("PHH", "Error getting OTA URL: ${e.message}", e)
            return ""
        }
    }

    private fun downloadUpdate() {
        val progress_container = findViewById<LinearLayout>(R.id.progress_container)
        val progress_bar = findViewById<ProgressBar>(R.id.progress_horizontal)
        val progress_text = findViewById<TextView>(R.id.progress_value)
        val btn_update = findViewById<Button>(R.id.btn_update)
        val btn_manual_ota = findViewById<Button>(R.id.btn_manual_ota)
        val btn_clear_ota = findViewById<Button>(R.id.btn_clear_ota)
        val btn_refresh_urls = findViewById<Button>(R.id.btn_refresh_urls)
        val update_title = findViewById<TextView>(R.id.txt_update_title)

        btn_update.visibility = View.INVISIBLE
        btn_manual_ota.visibility = View.INVISIBLE
        btn_clear_ota.visibility = View.INVISIBLE
        btn_refresh_urls.visibility = View.INVISIBLE

        val url = getUrl()
        Log.d("PHH", "Attempting to download from URL: $url")
        if (url.isEmpty()) {
            Log.e("PHH", "Empty URL")
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.error_dialog_title))
                    .setMessage(getString(R.string.update_error_message))
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
                isUpdating = false
                hasUpdate = false
                updateUiElements(false)
                btn_manual_ota.visibility = View.VISIBLE
                btn_clear_ota.visibility = View.VISIBLE
                btn_refresh_urls.visibility = View.VISIBLE
            }
            return
        }

        try {
            thread {
                var httpsConnection = URL(url).openConnection() as HttpsURLConnection
                Log.d("PHH", "Initial connection response code: ${httpsConnection.responseCode}")
                if (httpsConnection.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    httpsConnection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    httpsConnection.responseCode == HttpURLConnection.HTTP_SEE_OTHER
                ) {
                    val newUrl = httpsConnection.getHeaderField("Location")
                    Log.d("PHH", "Redirecting to: $newUrl")
                    httpsConnection = URL(newUrl).openConnection() as HttpsURLConnection
                }
                val completeFileSize = httpsConnection.contentLengthLong
                Log.d("PHH", "Download size is: $completeFileSize")

                runOnUiThread {
                    update_title.text = getString(R.string.downloading_update_title)
                    if (completeFileSize > 0) {
                        progress_bar.isIndeterminate = false
                        progress_bar.progress = 0
                        progress_text.text = "Downloading 0%"
                    } else {
                        progress_bar.isIndeterminate = true
                        progress_text.text = "Downloading..."
                    }
                    progress_container.visibility = View.VISIBLE
                }

                httpsConnection.inputStream.use { httpStream ->
                    val buffer = ByteArray(1024 * 1024)
                    var bytesRead = 0L
                    val tempFile = File.createTempFile("ota", ".xz", cacheDir)
                    FileOutputStream(tempFile).use { tempOut ->
                        var read: Int
                        while (httpStream.read(buffer).also { read = it } != -1) {
                            tempOut.write(buffer, 0, read)
                            bytesRead += read
                            if (completeFileSize > 0) {
                                val progress = (100 * bytesRead) / completeFileSize
                                runOnUiThread {
                                    if (progress < 100) {
                                        progress_bar.progress = progress.toInt()
                                        progress_text.text = "Downloading ${progress.toInt()}%"
                                    }
                                }
                            }
                        }
                    }

                    Log.d("PHH", "Download completed, starting extraction")
                    FileInputStream(tempFile).use { fileStream: InputStream ->
                        var hasSuccess = false
                        try {
                            Log.d("PHH", "OTA image install started")
                            prepareOTA()
                            Log.d("PHH", "New slot created")
                            Log.d("PHH", "OTA image extracting")
                            extractUpdate(fileStream, completeFileSize)
                            Log.d("PHH", "OTA image extracted")
                            applyUpdate()
                            Log.d("PHH", "Slot switch made")
                            Log.d("PHH", "OTA image install finished")
                            hasSuccess = true
                        } catch (e: Exception) {
                            Log.e("PHH", "Failed applying OTA image. Error: ${e.message}", e)
                        } finally {
                            tempFile.delete()
                        }
                        runOnUiThread {
                            val builder = AlertDialog.Builder(this)
                            if (hasSuccess) {
                                builder.setTitle(getString(R.string.title_activity_updater))
                                builder.setMessage(getString(R.string.success_install_message))
                            } else {
                                progress_container.visibility = View.GONE
                                builder.setTitle(getString(R.string.error_dialog_title))
                                builder.setMessage(getString(R.string.failed_install_message))
                            }
                            builder.setPositiveButton(android.R.string.ok) { _, _ -> }
                            builder.show()
                            isUpdating = false
                            hasUpdate = false
                            updateUiElements(true)
                            btn_manual_ota.visibility = View.VISIBLE
                            btn_clear_ota.visibility = View.VISIBLE
                            btn_refresh_urls.visibility = View.VISIBLE
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PHH", "Failed downloading OTA image. Error: ${e.message}", e)
            runOnUiThread {
                progress_container.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.error_dialog_title))
                    .setMessage(getString(R.string.failed_download_message))
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
                isUpdating = false
                hasUpdate = false
                updateUiElements(false)
                btn_manual_ota.visibility = View.VISIBLE
                btn_clear_ota.visibility = View.VISIBLE
                btn_refresh_urls.visibility = View.VISIBLE
            }
        }
    }

    private fun prepareOTA() {
        val progress_container = findViewById<LinearLayout>(R.id.progress_container)
        val progress_bar = findViewById<ProgressBar>(R.id.progress_horizontal)
        val progress_text = findViewById<TextView>(R.id.progress_value)
        val update_title = findViewById<TextView>(R.id.txt_update_title)

        runOnUiThread {
            update_title.text = getString(R.string.preparing_update_title)
            progress_bar.isIndeterminate = true
            progress_text.text = "Preparing storage for OTA..."
            progress_container.visibility = View.VISIBLE
        }

        SystemProperties.set("ctl.start", "phh-ota-make")
        Thread.sleep(1000)

        while (!SystemProperties.get("init.svc.phh-ota-make", "").equals("stopped")) {
            val state = SystemProperties.get("init.svc.phh-ota-make", "not-defined")
            Log.d("PHH", "Current value of phh-ota-make svc is $state")
            Thread.sleep(100)
        }
    }

    private fun extractUpdate(stream: InputStream, completeFileSize: Long) {
        val progress_container = findViewById<LinearLayout>(R.id.progress_container)
        val progress_bar = findViewById<ProgressBar>(R.id.progress_horizontal)
        val progress_text = findViewById<TextView>(R.id.progress_value)
        val update_title = findViewById<TextView>(R.id.txt_update_title)

        Log.d("PHH", "Starting extraction with file size: $completeFileSize")
        runOnUiThread {
            update_title.text = getString(R.string.downloading_update_title)
            if (completeFileSize > 0) {
                progress_bar.isIndeterminate = false
                progress_bar.progress = 0
                progress_text.text = "Extracting 0%"
            } else {
                progress_bar.isIndeterminate = true
                progress_text.text = "Extracting..."
            }
            progress_container.visibility = View.VISIBLE
        }

        val xzOut = XZInputStream(object : InputStream() {
            var nBytesRead = 0L
            override fun read(): Int {
                nBytesRead++
                return stream.read()
            }

            override fun available(): Int = stream.available()
            override fun close() = stream.close()
            override fun mark(readlimit: Int) = stream.mark(readlimit)
            override fun markSupported(): Boolean = stream.markSupported()
            override fun read(b: ByteArray?): Int {
                val n = stream.read(b)
                nBytesRead += n
                return n
            }

            override fun read(b: ByteArray?, off: Int, len: Int): Int {
                val n = stream.read(b, off, len)
                nBytesRead += n
                if (completeFileSize > 0) {
                    val extProgress = (100 * nBytesRead) / completeFileSize
                    runOnUiThread {
                        if (extProgress < 100) {
                            progress_bar.progress = extProgress.toInt()
                            progress_text.text = "Extracting ${extProgress.toInt()}%"
                        }
                    }
                }
                return n
            }

            override fun reset() = stream.reset()
            override fun skip(n: Long): Long = stream.skip(n)
        })

        FileOutputStream("/dev/phh-ota").use { blockDev ->
            val extBuf = ByteArray(1024 * 1024)
            var totalWritten = 0L
            while (true) {
                val extRead = xzOut.read(extBuf)
                if (extRead == -1) break
                blockDev.write(extBuf, 0, extRead)
                totalWritten += extRead
                Log.d("PHH", "Total written to block dev is $totalWritten")
            }
        }

        runOnUiThread {
            if (completeFileSize > 0) {
                progress_bar.progress = 100
                progress_text.text = "Extracting 100%"
            } else {
                progress_bar.isIndeterminate = false
                progress_text.text = "Extraction complete"
            }
        }
    }

    private fun applyUpdate() {
        val progress_container = findViewById<LinearLayout>(R.id.progress_container)
        val progress_bar = findViewById<ProgressBar>(R.id.progress_horizontal)
        val progress_text = findViewById<TextView>(R.id.progress_value)
        val update_title = findViewById<TextView>(R.id.txt_update_title)

        runOnUiThread {
            update_title.text = getString(R.string.applying_update_title)
            progress_text.text = "Switching slot..."
            progress_container.visibility = View.VISIBLE
        }

        SystemProperties.set("ctl.start", "phh-ota-switch")
        Thread.sleep(1000)

        while (!SystemProperties.get("init.svc.phh-ota-switch", "").equals("stopped")) {
            val state = SystemProperties.get("init.svc.phh-ota-switch", "not-defined")
            Log.d("PHH", "Current value of phh-ota-switch svc is $state")
            Thread.sleep(100)
        }

        runOnUiThread {
            progress_text.text = "Switched slot. OTA finalized."
        }
    }

    private fun startFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(Intent.createChooser(intent, "Select OTA File"), REQUEST_CODE_PICK_OTA)
        } catch (e: Exception) {
            Log.e("PHH", "Failed to open file picker: ${e.message}", e)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.error_dialog_title))
                .setMessage("Unable to open file picker: ${e.message}")
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_OTA && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                isUpdating = true
                processManualUpdate(uri)
            } ?: run {
                Log.e("PHH", "No file selected")
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.error_dialog_title))
                    .setMessage("No file selected")
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
                isUpdating = false
            }
        } else {
            Log.e("PHH", "File picker canceled or failed: resultCode=$resultCode")
            isUpdating = false
        }
    }

    private fun processManualUpdate(uri: Uri) {
        val progress_container = findViewById<LinearLayout>(R.id.progress_container)
        val progress_bar = findViewById<ProgressBar>(R.id.progress_horizontal)
        val progress_text = findViewById<TextView>(R.id.progress_value)
        val btn_update = findViewById<Button>(R.id.btn_update)
        val btn_manual_ota = findViewById<Button>(R.id.btn_manual_ota)
        val btn_clear_ota = findViewById<Button>(R.id.btn_clear_ota)
        val btn_refresh_urls = findViewById<Button>(R.id.btn_refresh_urls)

        btn_update.visibility = View.INVISIBLE
        btn_manual_ota.visibility = View.INVISIBLE
        btn_clear_ota.visibility = View.INVISIBLE
        btn_refresh_urls.visibility = View.INVISIBLE

        try {
            thread {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    var hasSuccess = false
                    try {
                        Log.d("PHH", "Manual OTA image install started")
                        prepareOTA()
                        Log.d("PHH", "New slot created")
                        Log.d("PHH", "Manual OTA image extracting")
                        val fileSize = contentResolver.openFileDescriptor(uri, "r")?.statSize ?: -1L
                        extractUpdate(inputStream, fileSize)
                        Log.d("PHH", "Manual OTA image extracted")
                        applyUpdate()
                        Log.d("PHH", "Slot switch made")
                        Log.d("PHH", "Manual OTA image install finished")
                        hasSuccess = true
                    } catch (e: Exception) {
                        Log.e("PHH", "Failed applying manual OTA image. Error: ${e.message}", e)
                    }
                    runOnUiThread {
                        val builder = AlertDialog.Builder(this)
                        if (hasSuccess) {
                            builder.setTitle(getString(R.string.title_activity_updater))
                            builder.setMessage(getString(R.string.success_install_message))
                        } else {
                            progress_container.visibility = View.GONE
                            builder.setTitle(getString(R.string.error_dialog_title))
                            builder.setMessage(getString(R.string.failed_install_message))
                        }
                        builder.setPositiveButton(android.R.string.ok) { _, _ -> }
                        builder.show()
                        isUpdating = false
                        updateUiElements(true)
                        btn_manual_ota.visibility = View.VISIBLE
                        btn_clear_ota.visibility = View.VISIBLE
                        btn_refresh_urls.visibility = View.VISIBLE
                    }
                } ?: run {
                    Log.e("PHH", "Failed to open OTA file")
                    runOnUiThread {
                        progress_container.visibility = View.GONE
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.error_dialog_title))
                            .setMessage("Failed to open OTA file")
                            .setPositiveButton(android.R.string.ok) { _, _ -> }
                            .show()
                        isUpdating = false
                        updateUiElements(false)
                        btn_manual_ota.visibility = View.VISIBLE
                        btn_clear_ota.visibility = View.VISIBLE
                        btn_refresh_urls.visibility = View.VISIBLE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PHH", "Error processing manual OTA: ${e.message}", e)
            runOnUiThread {
                progress_container.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.error_dialog_title))
                    .setMessage("Error processing OTA file: ${e.message}")
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
                isUpdating = false
                updateUiElements(false)
                btn_manual_ota.visibility = View.VISIBLE
                btn_clear_ota.visibility = View.VISIBLE
                btn_refresh_urls.visibility = View.VISIBLE
            }
        }
    }
}
