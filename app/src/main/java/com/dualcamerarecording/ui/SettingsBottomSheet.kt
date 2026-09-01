package com.dualcamerarecording.ui

import android.view.LayoutInflater
import android.widget.Toast
import com.dualcamerarecording.R
import com.dualcamerarecording.data.PreferencesRepository
import com.dualcamerarecording.databinding.BottomSheetSettingsBinding
import com.dualcamerarecording.locale.LocaleManager
import com.dualcamerarecording.model.AppLanguage
import com.dualcamerarecording.model.AppThemeMode
import com.dualcamerarecording.model.MeterStyle
import com.dualcamerarecording.settings.CameraSettingsStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings bottom sheet. Theme and language changes recreate the Activity so
 * app XML resources reload. Meter style updates via [CameraSettingsStore] so
 * all observers refresh.
 *
 * Adapted from MicGainLevelerApp SettingsBottomSheet pattern.
 */
class SettingsBottomSheet(
    private val activity: AppCompatActivity,
    private val settingsStore: CameraSettingsStore,
    private val onThemeOrLanguageChanged: () -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = BottomSheetSettingsBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        // Persist theme to preferences too — theme is re-read from preferences on
        // recreate(), so without this the Light theme selection was lost on restart.
        val prefs = PreferencesRepository(activity)

        // Set initial state from settings store
        val config = settingsStore.config.value
        when (config.themeMode) {
            AppThemeMode.LIGHT -> binding.themeToggle.check(R.id.themeLightButton)
            AppThemeMode.DARK -> binding.themeToggle.check(R.id.themeDarkButton)
            else -> binding.themeToggle.check(R.id.themeDarkButton)
        }
        when (config.language) {
            AppLanguage.ENGLISH -> binding.languageToggle.check(R.id.langEnglishButton)
            AppLanguage.ITALIAN -> binding.languageToggle.check(R.id.langItalianButton)
        }
        when (config.meterStyle) {
            MeterStyle.DIGITAL -> binding.meterStyleToggle.check(R.id.meterDigitalButton)
            MeterStyle.ANALOG -> binding.meterStyleToggle.check(R.id.meterAnalogButton)
        }

        binding.meterStyleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val style = when (checkedId) {
                R.id.meterAnalogButton -> MeterStyle.ANALOG
                else -> MeterStyle.DIGITAL
            }
            if (style != config.meterStyle) {
                settingsStore.updateMeterStyle(style)
            }
        }

        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLightButton -> AppThemeMode.LIGHT
                else -> AppThemeMode.DARK
            }
            if (mode != config.themeMode) {
                settingsStore.updateThemeMode(mode)
                prefs.themeMode = mode
                // Tell the user the change requires a full restart to take effect.
                // We post the Toast on the activity so it survives the imminent
                // Activity.recreate() (queued Toast tokens are dropped when the
                // Activity is destroyed mid-show).
                val app = activity.applicationContext
                android.os.Handler(activity.mainLooper).post {
                    Toast.makeText(app, R.string.theme_restart_toast, Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
                onThemeOrLanguageChanged()
            }
        }

        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.langItalianButton -> AppLanguage.ITALIAN
                else -> AppLanguage.ENGLISH
            }
            if (language != config.language) {
                settingsStore.updateLanguage(language)
                LocaleManager.saveLanguage(activity, language)
                dialog.dismiss()
                onThemeOrLanguageChanged()
            }
        }

        dialog.show()
    }
}
