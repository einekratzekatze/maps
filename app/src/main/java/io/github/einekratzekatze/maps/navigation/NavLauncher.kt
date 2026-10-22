package io.github.einekratzekatze.maps.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import io.github.einekratzekatze.maps.R

/** Starts a chooser for [NavIntentBuilder.build], excluding this app. Needs the `geo:` `<queries>` entry. */
object NavLauncher {
    /** @return true when a chooser was started. */
    fun launch(context: Context, target: NavTarget, self: ComponentName, origin: String? = null, mode: TravelMode? = null): Boolean {
        val intent = NavIntentBuilder.build(target)
        val routeIntent = NavIntentBuilder.buildRoute(origin, target, mode, GOOGLE_MAPS)
            .takeIf { context.packageManager.resolveActivity(it, 0) != null }
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != context.packageName }
        if (handlers.isEmpty() && routeIntent == null) {
            Toast.makeText(context, R.string.error_no_navigation_app, Toast.LENGTH_LONG).show()
            return false
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.title_navigate_with))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(self))
        if (routeIntent != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(routeIntent))
        return try {
            context.startActivity(chooser)
            true
        } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.error_no_navigation_app, Toast.LENGTH_LONG).show()
            false
        }
    }

    private const val GOOGLE_MAPS = "com.google.android.apps.maps"
}
