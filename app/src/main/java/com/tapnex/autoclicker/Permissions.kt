package com.tapnex.autoclicker

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService

object Permissions {
    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** True only when the user has turned on TAPNEX in Settings > Accessibility. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(context, TapnexAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }
}
