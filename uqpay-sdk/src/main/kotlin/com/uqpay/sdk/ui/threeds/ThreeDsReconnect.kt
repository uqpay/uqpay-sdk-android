package com.uqpay.sdk.ui.threeds

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Reloads the 3-D Secure page by itself when the network comes back, **if the host app can
 * observe connectivity at all**.
 *
 * Observing the default network needs `ACCESS_NETWORK_STATE`. This SDK does not declare it:
 * a permission in a library's manifest is merged into every merchant's app, and adding one
 * for a convenience is not this SDK's decision to make on their behalf. Most apps already
 * hold it; where the host does, the reload is automatic, and where it does not, this does
 * nothing and the customer uses **Try again**.
 *
 * ### It fires on a *return*, never on "a network exists"
 *
 * `registerDefaultNetworkCallback` reports the current network as soon as it is registered.
 * Acting on that would turn a page that failed *with* a working network — the ACS is down,
 * DNS is wrong — into a reload loop: fail, register, "available", reload, fail. So the
 * reload is armed only once the device has been seen with no default network, either at
 * registration or through `onLost`; each automatic reload therefore costs one real
 * offline-to-online transition.
 */
internal object ThreeDsReconnect {

    /**
     * How long after the network is reported before the reload. A network is announced
     * before it can carry traffic; reloading at once fails again and spends the one
     * automatic attempt this transition earned.
     */
    const val SETTLE_MILLIS: Long = 1_000L

    @Composable
    fun RetryWhenBackOnline(onBackOnline: () -> Unit) {
        val context = LocalContext.current.applicationContext
        val currentOnBackOnline by rememberUpdatedState(onBackOnline)
        DisposableEffect(context) {
            val handler = Handler(Looper.getMainLooper())
            val reload = Runnable { currentOnBackOnline() }
            val callback = register(context) { handler.postDelayed(reload, SETTLE_MILLIS) }
            onDispose {
                handler.removeCallbacks(reload)
                callback?.let { unregister(context, it) }
            }
        }
    }

    /**
     * Registers for the default network's return. Null when the host app lacks the
     * permission or the platform refuses; the caller then simply has no automatic reload.
     * [onReturned] is called on a connectivity thread, at most once per offline-to-online
     * transition.
     */
    @SuppressLint("MissingPermission") // Checked at runtime; deliberately not declared. See the class KDoc.
    private fun register(context: Context, onReturned: () -> Unit): ConnectivityManager.NetworkCallback? {
        val granted = context.checkPermission(
            Manifest.permission.ACCESS_NETWORK_STATE,
            Process.myPid(),
            Process.myUid(),
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                @Volatile
                private var offline = manager.activeNetwork == null

                override fun onLost(network: Network) {
                    offline = true
                }

                override fun onAvailable(network: Network) {
                    if (!offline) return
                    offline = false
                    onReturned()
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            callback
        } catch (_: RuntimeException) {
            // SecurityException on a ROM that disagrees about the permission, or the
            // platform's per-app callback limit. Neither is worth a crash mid-payment.
            null
        }
    }

    private fun unregister(context: Context, callback: ConnectivityManager.NetworkCallback) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Already unregistered by the platform; nothing to release.
        }
    }
}
