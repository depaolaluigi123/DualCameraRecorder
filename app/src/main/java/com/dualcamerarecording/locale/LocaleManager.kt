package com.dualcamerarecording.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.os.LocaleListCompat
import com.dualcamerarecording.data.PreferencesRepository
import com.dualcamerarecording.model.AppLanguage
import com.dualcamerarecording.model.AppThemeMode
import java.util.Locale

/**
 * Locale management: wrap context with saved locale for language switching.
 * Adapted from AndroidCamera LocaleManager.
 */
object LocaleManager {

    private const val PREFS_NAME = "dualcamera_prefs"
    private const val KEY_LANGUAGE = "language"

    fun applyLanguage(context: Context, language: AppLanguage): Context {
        return wrapWithSavedLocale(context, language)
    }

    fun getSavedLanguageCode(context: Context): String {
        val code = getSavedLanguage(context)
        return code.code
    }

    /**
     * Wrap the context with the chosen locale AND force the uiMode to match the
     * saved theme. Setting [Configuration.UI_MODE_NIGHT_UNDEFINED] alone (as the
     * previous implementation did) is not enough — on several Android versions
     * that flag is interpreted as "use the system default", which means a device
     * in dark mode still loads the values-night/ resource set even when the user
     * has explicitly picked the Light theme. Forcing [Configuration.UI_MODE_NIGHT_NO]
     * for Light and [Configuration.UI_MODE_NIGHT_YES] for Dark / system-dark makes
     * the resource resolver unambiguously pick values/ vs values-night/.
     */
    fun wrapWithSavedLocale(context: Context, language: AppLanguage): Context {
        val locale = Locale(language.code)
        Locale.setDefault(locale)

        val baseConfig = context.resources.configuration
        val newConfig = Configuration(baseConfig)
        // Apply the chosen locale.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            newConfig.setLocales(android.os.LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            newConfig.setLocale(locale)
        }
        // Force the night mode to match the user's saved theme choice so
        // values-night/ resources are only applied for the Dark theme. SYSTEM
        // follows the current system night mode (we read it from baseConfig,
        // not from the OS, so the application context does not surprise us
        // after a configuration change).
        newConfig.uiMode = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            resolveUiModeForTheme(context, baseConfig)
        return context.createConfigurationContext(newConfig)
    }

    /**
     * Resolve the [Configuration] uiMode that matches the user's saved theme
     * choice. SYSTEM tracks the device's current night mode so toggling the
     * system dark mode still updates the app (matches AppCompatDelegate's
     * MODE_NIGHT_FOLLOW_SYSTEM behavior).
     *
     * IMPORTANT: do NOT call [Context.getApplicationContext] on [context] here.
     * This method is invoked from [Application.attachBaseContext] where the
     * base context's applicationContext is not yet attached and returns null,
     * which crashes the app before it ever shows. PreferencesRepository only
     * needs a context for [Context.getSharedPreferences], and the base context
     * already provides that.
     */
    private fun resolveUiModeForTheme(context: Context, baseConfig: Configuration): Int {
        val themeMode = PreferencesRepository(context).themeMode
        return when (themeMode) {
            AppThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AppThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            AppThemeMode.SYSTEM -> {
                val night = baseConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) {
                    Configuration.UI_MODE_NIGHT_YES
                } else {
                    Configuration.UI_MODE_NIGHT_NO
                }
            }
        }
    }

    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code
        return AppLanguage.fromCode(code)
    }

    fun saveLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }
}