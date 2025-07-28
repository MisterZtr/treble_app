package me.phh.treble.app

import android.app.Fragment
import android.content.Context
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceFragment
import android.util.Log
import android.view.View
import android.widget.ListView

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object SamsungSettings : Settings {
    val highBrightess = "key_samsung_high_brightness"
    val gloveMode = "key_samsung_glove_mode"
    val audioStereoMode = "key_samsung_audio_stereo"
    val wirelessChargingTransmit = "key_samsung_wireless_charging_transmit"
    val doubleTapToWake = "key_samsung_double_tap_to_wake"
    val extraSensors = "key_samsung_extra_sensors"
    val alternateAudioPolicy = "key_samsung_alternate_audio_policy"
    val colorspace = "key_samsung_colorspace"
    val brokenFingerprint = "key_samsung_broken_fingerprint"
    val backlightMultiplier = "key_samsung_backlight_multiplier"
    val cameraIds = "key_samsung_camera_ids"
    val fodSingleClick = "key_samsung_fod_single_click"
    val flashStrength = "key_samsung_flash_strength"
    val disableBackMic = "key_samsung_disable_back_mic"

    override fun enabled(context: Context): Boolean {
        val isSamsung = Tools.vendorFpLow.startsWith("samsung/") ||
        Tools.vendorFpLow.startsWith("kddi/scv41_")
        Log.d("PHH", "SamsungSettings enabled() called, isSamsung = $isSamsung")
        return isSamsung
    }
}

class SamsungSettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.pref_samsung)

        if (SamsungSettings.enabled(context)) {
            Log.d("PHH", "Loading Samsung fragment ${SamsungSettings.enabled(context)}")

            findPreference(SamsungSettings.flashStrength)?.let {
                SettingsActivity.bindPreferenceSummaryToValue(it)
            }

            findPreference(SamsungSettings.backlightMultiplier)?.let {
                SettingsActivity.bindPreferenceSummaryToValue(it)
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
}
