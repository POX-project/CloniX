package dev.cloneforge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import dev.cloneforge.patcher.installer.RootHelper

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
            applyTheme()
        }

    var isNumberInNameEnabled: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_IN_NAME, true)
        set(value) = prefs.edit().putBoolean(KEY_NUMBER_IN_NAME, value).apply()

    var isNumberInIconEnabled: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_IN_ICON, true)
        set(value) = prefs.edit().putBoolean(KEY_NUMBER_IN_ICON, value).apply()

    var isAutoHueEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HUE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HUE, value).apply()

    var isBadgeIconsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BADGE_ICONS, true)
        set(value) = prefs.edit().putBoolean(KEY_BADGE_ICONS, value).apply()

    var isShizukuInstallEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_INSTALL, dev.cloneforge.patcher.installer.ShizukuInstaller.isShizukuPermissionGranted())
        set(value) = prefs.edit().putBoolean(KEY_SHIZUKU_INSTALL, value).apply()

    var isRootInstallEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOT_INSTALL, RootHelper.isRootAvailable())
        set(value) = prefs.edit().putBoolean(KEY_ROOT_INSTALL, value).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    companion object {
        private const val PREF_FILE = "clonix_settings"
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_NUMBER_IN_NAME = "pref_number_in_name"
        private const val KEY_NUMBER_IN_ICON = "pref_number_in_icon"
        private const val KEY_AUTO_HUE = "pref_auto_hue"
        private const val KEY_BADGE_ICONS = "pref_badge_icons"
        private const val KEY_SHIZUKU_INSTALL = "pref_shizuku_install"
        private const val KEY_ROOT_INSTALL = "pref_root_install"
    }
}
