package dev.jarvis.voice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the proximity sensor once when the wake word fires: covered = in a pocket or bag.
 * The sensor is registered only for that single reading (no continuous battery cost). Prefers the
 * wake-up variant so a reading arrives even with the screen off. No sensor or no reading in time =
 * treat as "not in pocket" (show the screen), which is the safer default for seeing sensitive prompts.
 */
class ProximityPocketDetector(context: Context, private val timeoutMs: Long = 400) : PocketDetector {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    val available: Boolean get() = sensor != null

    override suspend fun inPocket(): Boolean {
        val s = sensor ?: return false
        val mgr = sensors ?: return false
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        mgr.unregisterListener(this)
                        if (cont.isActive) cont.resume(isNear(event.values.firstOrNull() ?: s.maximumRange, s.maximumRange))
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                mgr.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { mgr.unregisterListener(listener) }
            }
        } ?: false
    }

    companion object {
        /**
         * Many proximity sensors are binary (report 0 or maximumRange); others report centimetres.
         * "Near" = below both the sensor's range and ~5 cm.
         */
        fun isNear(distance: Float, maxRange: Float): Boolean = distance < minOf(maxRange, 5f)
    }
}
