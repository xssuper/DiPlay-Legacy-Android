package com.andrerinas.openheadunit.utils

import android.util.Log

/**
 * Stand-in for DiAuto's root shell helper.
 *
 * Upstream offers two implementations: libsu (root) and Shizuku. Both raise the merged library's
 * floor — `dev.rikka.shizuku:provider` declares `minSdk 23` — while DiPlay still ships to Android
 * 4.4. DiPlay also has its own ADB backend (`LocalAdb`), which is how it performs the shell work
 * that DiAuto needed root for.
 *
 * This keeps the API the receiver calls and reports that no privileged backend is attached, so
 * every caller degrades to "that optional fix is unavailable" instead of failing.
 *
 * Upstream calls it in exactly two places: `App.onCreate` (`register`) and the FYT key receiver
 * (`checkPermissionOnBoot`, `setProp("sys.carlink.type", …)`). Both are optional conveniences.
 *
 * To restore: add `dev.rikka.shizuku:api`/`:provider` and `com.github.topjohnwu.libsu:core`, put
 * the upstream file back, and ship the receiver in a minSdk 23 variant.
 */
class SUExecutor {

    private var registered = false
    private var checkedPermissionOnBoot = false

    fun register() {
        if (registered) return
        registered = true
        Log.i(TAG, "No root backend in this build; DiPlay's ADB backend covers the shell work")
    }

    fun unregister() {
        if (!registered) return
        registered = false
    }

    // Don't keep nagging if multiple services request on boot.
    fun checkPermissionOnBoot() {
        if (checkedPermissionOnBoot) return
        checkPermission()
        checkedPermissionOnBoot = true
    }

    fun checkPermission(): Boolean = false

    fun setProp(key: String, value: String): Boolean {
        Log.i(TAG, "setProp($key, $value) skipped: no root backend in this build")
        return false
    }

    private companion object {
        const val TAG = "SUExecutor"
    }
}
