package com.siullph.sunrisealarm

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The volume-buttons flashlight: pressing volume up and volume down together switches the
 * flashlight on (at [level]) or off. [VolumeKeysService] listens for the buttons; this holds
 * its settings and turns that service on.
 *
 * Only an accessibility service can see the volume buttons in other apps, so the user has to
 * switch it on once in Accessibility settings, unless the app already holds
 * WRITE_SECURE_SETTINGS (the Night Light permission), which lets it switch itself on.
 */
object FlashlightShortcut {
    private const val PREFS = "flashlight_shortcut"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEVEL = "level"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** The flashlight level the shortcut switches on at; full strength until chosen. */
    fun level(context: Context, torch: Torch): Int =
        prefs(context).getInt(KEY_LEVEL, torch.maxLevel).coerceIn(1, torch.maxLevel)

    fun setLevel(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LEVEL, value).apply()
    }

    private fun component(context: Context) = ComponentName(context, VolumeKeysService::class.java)

    /** Whether [VolumeKeysService] is switched on in Accessibility settings. */
    fun serviceOn(context: Context): Boolean {
        val cr = context.contentResolver
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val ours = component(context)
        return enabledServices(context).any { ComponentName.unflattenFromString(it) == ours }
    }

    /**
     * Switches [VolumeKeysService] on without the user going to Accessibility settings.
     * Needs WRITE_SECURE_SETTINGS; returns false without it.
     */
    fun turnServiceOn(context: Context): Boolean {
        if (!DisplayFilters.canOverride(context)) return false
        if (serviceOn(context)) return true
        return try {
            val cr = context.contentResolver
            val others = enabledServices(context)
                .filterNot { ComponentName.unflattenFromString(it) == component(context) }
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (others + component(context).flattenToString()).joinToString(":"),
            )
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not switch on the volume buttons service", e)
            false
        }
    }

    private fun enabledServices(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .filter { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
