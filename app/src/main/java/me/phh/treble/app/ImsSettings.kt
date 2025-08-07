package me.phh.treble.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemProperties
import android.preference.Preference
import android.preference.PreferenceFragment
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.ListView
import android.widget.Toast
import dalvik.system.PathClassLoader
import java.io.FileInputStream

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object ImsSettings : Settings {
    val requestNetwork = "key_ims_request_network"
    val createApn = "key_ims_create_apn"
    val forceEnableSettings = "key_ims_force_enable_setting"
    val installImsApk = "key_ims_install_apn"
    val allowBinderThread = "key_ims_allow_binder_thread_on_incoming_calls"

    fun checkHasPhhSignature(): Boolean {
        try {
            val cl = PathClassLoader(
                "/system/framework/services.jar",
                ClassLoader.getSystemClassLoader()
            )
            val pmUtils = cl.loadClass("com.android.server.pm.PackageManagerServiceUtils")
            val field = pmUtils.getDeclaredField("PHH_SIGNATURE")
            Log.d("PHH", "checkHasPhhSignature Field $field")
            return true
        } catch (t: Throwable) {
            Log.d("PHH", "checkHasPhhSignature Field failed")
            return false
        }
    }

    override fun enabled(context: Context): Boolean {
        Log.d("PHH", "Initializing IMS settings")
        return true
    }
}

class ImsSettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.pref_ims)

        // Setup IMS package summary
        val packageSummaryPref = findPreference("key_ims_install_apn")
        val packagesToCheck = listOf(
            "org.codeaurora.ims",
            "com.mediatek.ims",
            "me.phh.ims"
        )
        val installedPackages = activity?.applicationContext?.let { ctx ->
            Tools.isPackageInstalled(ctx, packagesToCheck)
        }
        packageSummaryPref?.summary = if (!installedPackages.isNullOrEmpty()) {
            "Installed packages: ${installedPackages.joinToString()}"
        } else {
            "No IMS packages installed"
        }

        // Setup APN creation
        val createApn = findPreference(ImsSettings.createApn)
        val cursor = activity?.applicationContext?.let { ctx ->
            Tools.checkIfApnExists(ctx, "PHH IMS")
        }
        createApn?.summary = if (cursor != null && cursor.moveToFirst()) {
            "APN PHH IMS already exists"
        } else {
            "No APN PHH IMS found"
        }
        cursor?.close()

        createApn?.setOnPreferenceClickListener {
            handleApnCreation()
            true
        }

        // Setup IMS installation
        val installIms = findPreference(ImsSettings.installImsApk)
        logRadioInfo()
        val (url, message) = determineImsPackage()
        installIms?.title = "Install IMS APK for $message"
        installIms?.setOnPreferenceClickListener {
            downloadAndInstallIms(url)
            true
        }

        Log.d("PHH", "IMS settings loaded successfully")
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_misc_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configura a Toolbar
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        (activity as? AppCompatActivity)?.setSupportActionBar(toolbar)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
        }

        // Configura o ListView
        view.findViewById<ListView>(android.R.id.list)?.apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(32, 56, 32, 32)
        }
    }

    private fun handleApnCreation() {
        val tm = activity?.getSystemService(TelephonyManager::class.java)
        val operator = tm?.simOperator ?: run {
            Log.d("PHH", "No current carrier, bailing out")
            return
        }

        val mcc = operator.substring(0, 3)
        val mnc = operator.substring(3)
        Log.d("PHH", "Got mcc = $mcc and mnc = $mnc")

        val cr = activity?.contentResolver ?: return
        val cv = ContentValues().apply {
            put("name", "PHH IMS")
            put("apn", "ims")
            put("type", "ims")
            put("edited", "1")
            put("user_editable", "1")
            put("user_visible", "1")
            put("protocol", "IPV4V6")
            put("roaming_protocol", "IPV6")
            put("modem_cognitive", "1")
            put("numeric", operator)
            put("mcc", mcc)
            put("mnc", mnc)
        }

        val res = cr.insert(Uri.parse("content://telephony/carriers"), cv)
        findPreference(ImsSettings.createApn)?.summary = if (res != null) {
            "IMS APN successfully added"
        } else {
            "Failed to add IMS APK"
        }
    }

    private fun logRadioInfo() {
        Log.d("PHH", "MTK P radio = ${Ims.gotMtkP}")
        Log.d("PHH", "MTK Q radio = ${Ims.gotMtkQ}")
        Log.d("PHH", "MTK R radio = ${Ims.gotMtkR}")
        Log.d("PHH", "MTK S radio = ${Ims.gotMtkS}")
        Log.d("PHH", "MTK AIDL radio = ${Ims.gotMtkAidl}")
        Log.d("PHH", "Qualcomm HIDL radio = ${Ims.gotQcomHidl}")
        Log.d("PHH", "Qualcomm AIDL radio = ${Ims.gotQcomAidl}")
    }

    private fun determineImsPackage(): Pair<String, String> {
        val signSuffix = if (ImsSettings.checkHasPhhSignature()) "-resigned" else ""
        return when {
            (Ims.gotMtkR || Ims.gotMtkS || Ims.gotMtkAidl) && Build.VERSION.SDK_INT >= 34 ->
            Pair(
                "https://treble.phh.me/ims-mtk-u$signSuffix.apk",
                 "MediaTek R+ vendor"
            )
            Ims.gotMtkP ->
            Pair(
                "https://treble.phh.me/stable/ims-mtk-p$signSuffix.apk",
                 "MediaTek P vendor"
            )
            Ims.gotMtkQ ->
            Pair(
                "https://treble.phh.me/stable/ims-mtk-q$signSuffix.apk",
                 "MediaTek Q vendor"
            )
            Ims.gotMtkR ->
            Pair(
                "https://treble.phh.me/stable/ims-mtk-r$signSuffix.apk",
                 "MediaTek R vendor"
            )
            Ims.gotMtkS ->
            Pair(
                "https://treble.phh.me/stable/ims-mtk-s$signSuffix.apk",
                 "MediaTek S vendor"
            )
            (Ims.gotQcomHidlMoto && SystemProperties.getInt("ro.vndk.version", -1) <= 31) ->
            Pair(
                "https://treble.phh.me/stable/ims-caf-moto$signSuffix.apk",
                 "Qualcomm pre-S vendor (Motorola)"
            )
            (Ims.gotQcomHidl || Ims.gotQcomAidl) && Build.VERSION.SDK_INT >= 34 ->
            Pair(
                "https://treble.phh.me/ims-caf-u$signSuffix.apk",
                 "Qualcomm vendor"
            )
            Ims.gotQcomHidl ->
            Pair(
                "https://treble.phh.me/stable/ims-q.64$signSuffix.apk",
                 "Qualcomm pre-S vendor"
            )
            Ims.gotQcomAidl ->
            Pair(
                "https://treble.phh.me/stable/ims-caf-s$signSuffix.apk",
                 "Qualcomm S+ vendor"
            )
            else -> Pair("https://treble.phh.me/floss-ims-resigned.apk", "Floss IMS (EXPERIMENTAL)")
        }
    }

    @SuppressLint("Range")
    private fun downloadAndInstallIms(url: String) {
        val context = activity ?: return
        Toast.makeText(context, "Downloading IMS APK", Toast.LENGTH_SHORT).show()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadRequest = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("IMS APK")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "ims.apk"
            )
        }

        val myId = dm.enqueue(downloadRequest)

        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != myId) return

                    val query = DownloadManager.Query().setFilterById(myId)
                    val cursor = dm.query(query)
                    if (!cursor.moveToFirst()) {
                        Log.d("PHH", "DownloadManager gave us an empty cursor")
                        return
                    }

                    val localUri = Uri.parse(cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)))
                    cursor.close()
                    installDownloadedApk(context, localUri.path!!)
                    context.unregisterReceiver(this)
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installDownloadedApk(context: Context, path: String) {
        val pi = context.packageManager.packageInstaller
        val sessionId = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = pi.openSession(sessionId)

        Tools.safeSetprop("persist.vendor.vilte_support", "0")
        Toast.makeText(context, "Installing IMS APK", Toast.LENGTH_SHORT).show()

        session.openWrite("hello", 0, -1).use { output ->
            FileInputStream(path).use { input ->
                val buf = ByteArray(512 * 1024)
                while (input.available() > 0) {
                    val l = input.read(buf)
                    output.write(buf, 0, l)
                }
                session.fsync(output)
            }
        }

        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Toast.makeText(
                        context,
                        "IMS apk installed! You may now reboot.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            IntentFilter("me.phh.treble.app.ImsInstalled")
        )

        session.commit(
            PendingIntent.getBroadcast(
                context,
                1,
                Intent("me.phh.treble.app.ImsInstalled"),
                                       PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            ).intentSender
        )
    }
}
