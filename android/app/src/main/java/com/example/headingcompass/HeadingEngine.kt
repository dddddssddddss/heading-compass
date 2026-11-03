package com.example.headingcompass

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Android 센서 → HeadingFilter → HeadingSample.
 *
 * - TYPE_ROTATION_VECTOR: OS가 자이로·가속도·자력계를 합친 자세 (자북 기준), values[4] = 방향 오차(rad)
 * - TYPE_GYROSCOPE: 회전 속도 (예측과 자기 간섭 감지용)
 * - TYPE_MAGNETIC_FIELD: 보정된 자기장 세기와 보정 상태
 * - GeomagneticField: 자편각. GPS 없이 HeadingConfig의 기준 좌표로 계산한다.
 */
class HeadingEngine(context: Context, private val onSample: (HeadingSample) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val filter = HeadingFilter()
    private val reporter = HeadingReporter(context)
    private val rot = FloatArray(9)

    private var gyro: DoubleArray? = null
    private var fieldMicroTesla: Double? = null
    private var magUncalibrated = false
    private var declination = 0.0

    val isSupported: Boolean get() = rotationSensor != null

    fun start() {
        val rv = rotationSensor ?: return
        filter.reset()
        gyro = null
        declination = GeomagneticField(
            HeadingConfig.DECLINATION_LAT, HeadingConfig.DECLINATION_LON, 0f, System.currentTimeMillis()
        ).declination.toDouble()
        sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        gyroSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensors.unregisterListener(this)

    fun shutdown() { stop(); reporter.shutdown() }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE ->
                gyro = doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val (x, y, z) = event.values
                magUncalibrated = event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                fieldMicroTesla = if (magUncalibrated) null else sqrt((x * x + y * y + z * z).toDouble())
            }
            Sensor.TYPE_ROTATION_VECTOR -> handleRotation(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magUncalibrated = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
    }

    private fun handleRotation(event: SensorEvent) {
        // v_world(동,북,위) = R · v_device
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val accRad = if (event.values.size > 4) event.values[4] else -1f
        val input = FusionInput(
            timestamp = event.timestamp / 1e9,
            deviceToENU = DoubleArray(9) { rot[it].toDouble() },
            rotationRate = if (gyroSensor == null) null else gyro ?: doubleArrayOf(0.0, 0.0, 0.0),
            fieldMicroTesla = fieldMicroTesla,
            magnetometerUncalibrated = magUncalibrated,
            osAccuracyDeg = if (accRad >= 0) Math.toDegrees(accRad.toDouble()) else null,
        )
        val out = filter.update(input) ?: return
        val sample = HeadingSample(
            heading = wrap360(out.magneticHeading + declination),
            magneticHeading = out.magneticHeading,
            declination = declination,
            accuracyDeg = out.accuracyDeg,
            quality = out.quality,
            gyroHold = out.gyroHold,
            pose = out.pose,
            axis = out.axis,
            ts = System.currentTimeMillis(),
        )
        reporter.offer(sample, input.timestamp)
        onSample(sample)
    }
}
