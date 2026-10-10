package com.aus.deutschflow.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The microphone grant, expressed the way a permission flow actually needs it.
 *
 * Android's `shouldShowRequestPermissionRationale` returns false both before a
 * permission has ever been requested and after it has been permanently denied
 * ("Don't ask again"), so it cannot, on its own, tell the two apart. That matters
 * here: the right move on a first request is to ask, and the only recovery from a
 * permanent denial is to send the user to Settings - and you cannot offer an action
 * that cannot possibly work.
 *
 * Callers carry an `askedBefore` flag (true the first time they launch the system
 * request) and this does the rest, so the grant/deny/permanent-denial branch lives
 * in one place instead of being hand-rolled per screen. It is a helper, not a
 * framework: each caller still owns its result callback and its own error surface.
 */
enum class PermissionState {
    GRANTED,
    REQUESTABLE,
    DENIED_PERMANENTLY
}

/**
 * The grant decision, as a pure function of the three signals the platform exposes.
 *
 * Extracted from the Context call so the four branches are deterministic and testable
 * without a real permission dialog:
 * - granted -> GRANTED (covers "grant after Settings")
 * - !granted && canShowRationale -> REQUESTABLE (explain, then re-ask)
 * - !granted && !canShowRationale && askedBefore -> DENIED_PERMANENTLY (Settings only)
 * - !granted && !canShowRationale && !askedBefore -> REQUESTABLE (a real first request)
 */
fun computePermissionState(
    granted: Boolean,
    canShowRationale: Boolean,
    askedBefore: Boolean,
): PermissionState = when {
    granted -> PermissionState.GRANTED
    canShowRationale -> PermissionState.REQUESTABLE
    askedBefore -> PermissionState.DENIED_PERMANENTLY
    else -> PermissionState.REQUESTABLE
}

/**
 * Unwraps a [ContextWrapper] chain to the underlying [Activity], if any.
 *
 * `LocalContext.current` is frequently a wrapper rather than the raw Activity, and
 * `shouldShowRequestPermissionRationale` is an Activity-only API - so a wrapped
 * Activity must be unwrapped before its rationale can be read.
 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

fun Context.recordAudioPermissionState(askedBefore: Boolean): PermissionState {
    val granted = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) return PermissionState.GRANTED
    // shouldShowRequestPermissionRationale is Activity-only. Unwrap context wrappers so
    // a wrapped Activity still answers it; a non-Activity context cannot answer it and
    // so can never decide "Don't ask again" and stays REQUESTABLE. (This was the bug:
    // the old `askedBefore -> DENIED_PERMANENTLY` branch fired for non-Activity contexts
    // too once askedBefore was true.)
    val activity = findActivity() ?: return PermissionState.REQUESTABLE
    val canShowRationale = activity.shouldShowRequestPermissionRationale(
        Manifest.permission.RECORD_AUDIO,
    )
    return computePermissionState(
        granted = false, canShowRationale = canShowRationale, askedBefore = askedBefore,
    )
}

/**
 * Opens the system screen for this app's permissions.
 *
 * The only recovery from a permanent denial is leaving the app, so this takes no
 * callback and the caller must not wait for one: when the user returns, the next
 * tap re-checks the permission through [recordAudioPermissionState].
 */
fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
