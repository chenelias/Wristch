package dev.eliaschen.wristch.context

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Where the phone is, as a line the model can paste into a message.
 *
 * The point of this source is the example the project was described with - "come eat at
 * 廣弘小館, here: https://maps.google.com/..." - so the place name matters more than the
 * coordinates, and the Maps link matters more than either.
 */
internal object LocationSource {

    private const val TAG = "WristchContext"

    /**
     * A fix, or null if there is no usable one.
     *
     * Asks for a fresh one and falls back to the last known: a cold GPS fix can take
     * longer than the whole run is willing to wait, and a fix from a few minutes ago is
     * still the right neighbourhood.
     */
    @SuppressLint("MissingPermission") // The collector only calls this once granted.
    suspend fun snippet(context: Context): String? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null

        val fix = runCatching { current(context, manager, provider) }.getOrElse {
            Log.w(TAG, "location failed: ${it.message}")
            null
        } ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: return null

        val place = describe(context, fix)
        val link = "https://maps.google.com/?q=${fix.latitude},${fix.longitude}"
        return buildString {
            append("Current location: ")
            append(place ?: format(fix))
            append("\nMap link: ")
            append(link)
        }
    }

    private suspend fun current(
        context: Context,
        manager: LocationManager,
        provider: String,
    ): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }

    /** The street address, when the platform geocoder can name one. */
    private suspend fun describe(context: Context, fix: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(fix.latitude, fix.longitude, 1) { results ->
                if (continuation.isActive) continuation.resume(results.firstOrNull())
            }
        }
        val line = address?.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: return null
        // The feature name is the shop or building when there is one, which is the half of
        // the address a person would actually say out loud.
        val name = address.featureName?.takeIf { it.isNotBlank() && !line.startsWith(it) }
        return if (name == null) line else "$name, $line"
    }

    private fun format(fix: Location) =
        String.format(Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude)
}
