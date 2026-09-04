package dev.elias.assistivetouchpeeker.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** One IMU sample with its own sensor timestamp, converted to seconds. */
data class ImuSample(val timestampSeconds: Double, val x: Float, val y: Float, val z: Float)

/** The buffered accelerometer and gyroscope streams; each carries its own irregular timestamps. */
data class ImuSnapshot(val accelerometer: List<ImuSample>, val gyroscope: List<ImuSample>)

/**
 * Buffers raw accelerometer + gyroscope samples at 100 Hz for the last
 * [BUFFER_DURATION_SECONDS], mirroring the ~3 s recordings the gesture model was
 * trained on. Sensor callbacks append from the SensorManager's own thread;
 * [snapshot] is safe to call from any thread.
 */
class ImuSensorSource(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val lock = Any()
    private val accelBuffer = ArrayDeque<ImuSample>()
    private val gyroBuffer = ArrayDeque<ImuSample>()

    val isAvailable: Boolean get() = accelerometer != null && gyroscope != null

    fun start() {
        sensorManager.registerListener(this, accelerometer, SAMPLE_PERIOD_MICROS)
        sensorManager.registerListener(this, gyroscope, SAMPLE_PERIOD_MICROS)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val buffer = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelBuffer
            Sensor.TYPE_GYROSCOPE -> gyroBuffer
            else -> return
        }
        val sample = ImuSample(
            timestampSeconds = event.timestamp / NANOS_PER_SECOND,
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
        )
        synchronized(lock) {
            buffer.addLast(sample)
            while (buffer.size > 1 && sample.timestampSeconds - buffer.first().timestampSeconds > BUFFER_DURATION_SECONDS) {
                buffer.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** The current buffers, or null until both hold at least [MIN_BUFFER_SECONDS] of data. */
    fun snapshot(): ImuSnapshot? = synchronized(lock) {
        if (spanSeconds(accelBuffer) < MIN_BUFFER_SECONDS || spanSeconds(gyroBuffer) < MIN_BUFFER_SECONDS) {
            return null
        }
        ImuSnapshot(accelerometer = accelBuffer.toList(), gyroscope = gyroBuffer.toList())
    }

    private fun spanSeconds(buffer: ArrayDeque<ImuSample>): Double =
        if (buffer.size < 2) 0.0 else buffer.last().timestampSeconds - buffer.first().timestampSeconds

    companion object {
        private const val SAMPLE_RATE_HZ = 100
        private const val SAMPLE_PERIOD_MICROS = 1_000_000 / SAMPLE_RATE_HZ
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Matches the ~3 s recordings the model was trained on. */
        const val BUFFER_DURATION_SECONDS = 3.0

        /** Minimum buffered span before a 1 s classification window can be built. */
        const val MIN_BUFFER_SECONDS = 1.2
    }
}
