package com.example.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String
)

/**
 * Resolves device coordinates using foreground Android LocationManager & Geocoder
 * when foreground location permission has been granted.
 */
object ChatLocationManager {

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(context: Context): Result<CapturedLocation> = withContext(Dispatchers.IO) {
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: error("Location service unavailable.")

            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.PASSIVE_PROVIDER
            }

            val fresh: Location? = runCatching {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancelSignal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancelSignal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        lm,
                        provider,
                        cancelSignal,
                        ContextCompat.getMainExecutor(context)
                    ) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }.getOrNull()

            val location = fresh
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?: error("Unable to read GPS/network fix. Enable Location on device and retry.")

            val label = reverseGeocodeLabel(context, location.latitude, location.longitude)
            CapturedLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                label = label
            )
        }
    }

    private fun reverseGeocodeLabel(context: Context, lat: Double, lng: Double): String {
        val fallback = String.format(Locale.US, "%.5f, %.5f", lat, lng)
        if (!Geocoder.isPresent()) return fallback
        return runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var result: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                geocoder.getFromLocation(lat, lng, 1) { addresses ->
                    val first = addresses.firstOrNull()
                    result = listOfNotNull(first?.featureName, first?.subLocality, first?.locality)
                        .distinct()
                        .joinToString(", ")
                    latch.countDown()
                }
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                result
            } else {
                val first = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                listOfNotNull(first?.featureName, first?.subLocality, first?.locality)
                    .distinct()
                    .joinToString(", ")
            }
            list?.takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
    }

    fun openLocationInMaps(context: Context, lat: Double, lng: Double, label: String) {
        val encodedLabel = Uri.encode(label.ifBlank { "Shared Location" })
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($encodedLabel)")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/?q=$lat,$lng")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}
