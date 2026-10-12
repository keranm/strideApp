package dev.stride.hud

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Turn the home alias on or off from adb, on consoles where adb cannot.
 *
 * `pm enable dev.stride.hud/.HomeAlias` is how tools/unchain.sh makes STRIDE
 * the home app, and on Android 9 it works. On Android 13 — a Gen 7 console —
 * the shell is refused ("Shell cannot change component state"), but an app may
 * always change its own components. So the shell asks, and the app does it:
 *
 *     adb shell am broadcast -a dev.stride.hud.KIOSK --ez on true -n dev.stride.hud/.KioskReceiver
 *
 * Guarded by android.permission.DUMP in the manifest, which the shell holds and
 * no installable app can, so nothing else on the console can turn a treadmill
 * into its own home screen, or take that away.
 */
class KioskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.stride.hud.KIOSK") return
        val on = intent.getBooleanExtra("on", true)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "dev.stride.hud.HomeAlias"),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        Log.i("Stride", "kiosk: home alias ${if (on) "enabled" else "disabled"} at the shell's request")
        resultData = if (on) "enabled" else "disabled"
    }
}
