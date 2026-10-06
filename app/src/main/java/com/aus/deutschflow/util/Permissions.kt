package com.aus.deutschflow.util

import android.Manifest
import android.app.Activity
import android.content.Context
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

fun Context.recordAudioPermissionState(askedBefore: Boolean): PermissionState = when {
    ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
    // shouldShowRequestPermissionRationale is an Activity-only API, so a plain
    // Context cannot answer it; a non-Activity falls through to the askedBefore
    // rule and is treated as a transient denial (the safe "ask again" branch).
    this is Activity && shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
        PermissionState.REQUESTABLE
    // No rationale owed and we have asked before: the user chose "Don't ask again".
    askedBefore -> PermissionState.DENIED_PERMANENTLY
    // No rationale and we have not asked: a real first request, so ask.
    else -> PermissionState.REQUESTABLE
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
