package com.dualcamerarecording.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.dualcamerarecording.R
import com.dualcamerarecording.model.AppThemeMode

/**
 * Theme management for the app-owned Light / Dark themes.
 *
 * The themes are NOT DayNight — they are fixed styles defined in themes.xml
 * (Theme.DualCameraRecorder.Light / .Dark). Switching is therefore done with
 * [setTheme] in the Activity (via [styleRes]) plus a [recreate], NOT with
 * AppCompatDelegate.setDefaultNightMode (which only affects DayNight themes).
 *
 * Pattern copied from MicGainLevelerApp ThemeManager.
 */
class ThemeManager {

    /** Apply the theme via AppCompatDelegate. Kept for backwards compatibility;
     *  the real switch happens through [styleRes] + Activity.setTheme(). */
    fun applyTheme(mode: AppThemeMode) {
        // No-op for the app-owned themes; setTheme(styleRes(mode)) is the
        // authoritative path. Left intentionally empty to avoid forcing a
        // DayNight mode that does not match the Light/Dark style resources.
    }

    /**
     * Resolve the app theme mode to a concrete style resource id. SYSTEM maps
     * to Light or Dark based on the current system night mode.
     */
    fun styleRes(mode: AppThemeMode, context: Context): Int = when (mode) {
        AppThemeMode.LIGHT -> R.style.Theme_DualCameraRecorder_Light
        AppThemeMode.DARK -> R.style.Theme_DualCameraRecorder_Dark
        AppThemeMode.SYSTEM -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) {
                R.style.Theme_DualCameraRecorder_Dark
            } else {
                R.style.Theme_DualCameraRecorder_Light
            }
        }
    }

    /**
     * Resolve a color resource for the current theme (light or dark).
     */
    fun resolveColor(context: Context, @ColorRes resId: Int): Int {
        return ContextCompat.getColor(context, resId)
    }

    /**
     * Data class holding theme palette colors.
     */
    data class ThemePalette(
        @ColorInt val backgroundColor: Int,
        @ColorInt val surfaceColor: Int,
        @ColorInt val primaryColor: Int,
        @ColorInt val textColor: Int,
        @ColorInt val onSurfaceColor: Int,
        @ColorInt val onErrorColor: Int
    )

    fun loadPalette(context: Context): ThemePalette {
        return ThemePalette(
            backgroundColor = resolveColor(context, R.color.color_background),
            surfaceColor = resolveColor(context, R.color.color_surface),
            primaryColor = resolveColor(context, R.color.color_primary),
            textColor = resolveColor(context, R.color.color_text_primary),
            onSurfaceColor = resolveColor(context, R.color.color_on_secondary),
            onErrorColor = resolveColor(context, R.color.error)
        )
    }
}
