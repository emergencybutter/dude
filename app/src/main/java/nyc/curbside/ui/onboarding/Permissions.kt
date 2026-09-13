package nyc.curbside.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The four things Curbside asks for, described in the user's terms rather than Android's.
 *
 * Each one is here because something visible stops working without it, and [cost] says what. A
 * permission nobody can connect to a feature is a permission that gets denied, and every one of
 * these denials is silent at runtime: no fix, no pin, no warning, no error.
 */
internal enum class Ask(val title: String, val why: String, val cost: String) {

    /**
     * The only signal that works with no car stereo and no running app. Without it
     * [nyc.curbside.detect.DetectionRegistrar.ensureRegistered] refuses at its first line and the
     * sensor-hub path is never armed.
     */
    ACTIVITY(
        title = "Physical activity",
        why = "Your phone's motion chip already knows when a car trip starts and ends. Curbside " +
            "asks it to say so — that is what lets detection cost no battery and work with the " +
            "app closed.",
        cost = "Without it, Curbside only notices a drive when your car stereo connects.",
    ),

    LOCATION(
        title = "Location",
        why = "One position, taken at the moment Curbside decides you have parked. It is the " +
            "only time the app ever turns on the GPS, and it lasts a few seconds.",
        cost = "Without it, there is no pin — only whatever you drop by hand.",
    ),

    /**
     * The capture runs as expedited work seconds after the engine goes off, with no screen up. On
     * API 31+ that is an expedited job rather than a foreground service, so the app does not count
     * as "in use" and the fused client returns nothing without this.
     */
    BACKGROUND(
        title = "Location while closed",
        why = "You park, switch off, and walk away — Curbside is not on screen at that moment, " +
            "which is exactly when it needs the fix. Android treats that as a separate, stricter " +
            "permission.",
        cost = "Without it, Curbside notices you parked and then records nothing.",
    ),

    NOTIFICATIONS(
        title = "Notifications",
        why = "The warning before the sweeper arrives, and the confirmation when a spot is saved.",
        cost = "Without it, Curbside can know you are about to be ticketed and have no way to " +
            "tell you.",
    );

    /** Empty where this version of Android has no such permission; those count as granted. */
    val request: Array<String>
        get() = when (this) {
            ACTIVITY -> sinceQ(Manifest.permission.ACTIVITY_RECOGNITION)
            LOCATION -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            // Implied by foreground location before Q, where there was no such distinction.
            BACKGROUND -> sinceQ(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        }

    fun isGranted(context: Context): Boolean = when (this) {
        // Approximate location is a few hundred metres across. That names a neighbourhood, not a
        // block, so anything short of precise is a denial as far as parking is concerned.
        LOCATION -> granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> request.all { granted(context, it) }
    }

    private fun sinceQ(permission: String): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(permission) else emptyArray()

    companion object {
        /**
         * Everything except [BACKGROUND], which Android insists on being asked for on its own and
         * only once foreground location is already held. Bundling it gets the whole request denied.
         */
        val upFront: List<Ask> = listOf(ACTIVITY, LOCATION, NOTIFICATIONS)
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * What the "all the time" choice is actually called on this phone.
 *
 * Android 11 moved it out of the permission dialog and onto the settings page, and the wording
 * differs by version and OEM. Quoting the real label is the difference between an instruction the
 * user can follow and one they cannot find.
 */
internal fun backgroundOptionLabel(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.backgroundPermissionOptionLabel.toString()
    } else {
        "Allow all the time"
    }

/** Android 11+ will not show a dialog for background location; the settings page is the only way. */
internal fun backgroundNeedsSettings(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

internal fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
