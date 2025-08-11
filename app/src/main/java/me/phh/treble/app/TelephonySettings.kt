package me.phh.treble.app

import android.app.AlertDialog
import android.app.Fragment
import android.content.Context
import android.os.Bundle
import android.os.SystemProperties
import android.preference.Preference
import android.preference.PreferenceFragment
import android.util.Log
import android.view.View
import android.widget.ListView
import android.widget.Toast

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object TelephonySettings : Settings {
    val mobileSignal = "key_telephony_mobile_signal"
    val restartRil = "key_telephony_restart_ril"
    val forceDisplay5g = "key_telephony_force_display_5g"
    val removeTelephony = "key_telephony_removetelephony"
    val simCount = "key_telephony_simcount"
    val resetSimCount = "key_telephony_reset_simcount"
    var patchSmsc = "key_misc_patch_smsc"
    val restrictednetworking = "key_telephony_restricted_networking"

    override fun enabled(context: Context): Boolean {
        Log.d("PHH", "Initializing Telephony settings")
        return true
    }
}

class TelephonySettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.pref_telephony)

        if (TelephonySettings.enabled(context)) {
            Log.d("PHH", "Loading Telephony fragment ${TelephonySettings.enabled(context)}")

            findPreference(TelephonySettings.mobileSignal)?.let {
                SettingsActivity.bindPreferenceSummaryToValue(it)
            }

            findPreference(TelephonySettings.simCount)?.let {
                SettingsActivity.bindPreferenceSummaryToValue(it)
            }

            findPreference(TelephonySettings.removeTelephony)?.setOnPreferenceClickListener {
                removeTelephonyDialog()
                true
            }

            findPreference(TelephonySettings.resetSimCount)?.setOnPreferenceClickListener {
                resetSimCountDialog()
                true
            }
        }
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

    private fun removeTelephonyDialog() {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Removing Telephony")
        .setMessage("Are you sure? This will delete it forever")
        .setPositiveButton(android.R.string.yes) { dialog, which ->
            try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = process.outputStream
                val writer = outputStream.bufferedWriter()

                writer.write("/system/bin/remove-telephony.sh\n")
                writer.write("exit\n")
                writer.flush()
                writer.close()

                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    Log.d("PHH", "Successfully executed remove-telephony.sh via su shell!")
                    Toast.makeText(activity, R.string.toast_reboot, Toast.LENGTH_LONG).show()
                } else {
                    Log.e("PHH", "Failed with exit code: $exitCode")
                }
            } catch (e: Exception) {
                Log.d("PHH", "Failed to exec su shell directly: ${e.message}")
            }
        }
        .setNegativeButton(android.R.string.no, null)

        builder.show()
    }

    private fun resetSimCountDialog() {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(getString(R.string.reset_simcount))
        .setMessage(getString(R.string.reset_simcount_summary_telephone))
        .setPositiveButton(android.R.string.yes) { dialog, which ->
            SystemProperties.set("persist.sys.phh.sim_count", "reset")
            Toast.makeText(activity, R.string.toast_reboot, Toast.LENGTH_LONG).show()
        }
        .setNegativeButton(android.R.string.no, null)

        builder.show()
    }
}
