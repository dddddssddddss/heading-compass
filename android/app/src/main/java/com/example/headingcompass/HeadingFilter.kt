package com.example.headingcompass

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

// 플랫폼 무관 방향 계산. iOS의 HeadingFilter.swift와 같은 로직이다.
//
// 1) OS가 합친 자세(회전행렬)에서 "바라보는 방향"을 구한다.
//    폰을 눕히면 폰 윗변, 세우면 후면 카메라가 향하는 쪽 (두 축의 수평 성분 합).
// 2) 그 값을 그대로 쓰지 않고, 자이로 회전량으로 예측한 뒤 나침반 쪽으로 천천히 당긴다.
//    자기 간섭이 감지되면 당기기를 멈추고 자이로만으로 버틴다.

enum class Pose(val json: String) { FLAT("flat"), UPRIGHT("upright"), TILTED("tilted"), FACE_DOWN("faceDown") }
enum class FacingAxis(val json: String) { TOP("top"), CAMERA("camera"), MIXED("mixed") }
enum class Quality(val json: String) { GOOD("good"), POOR("poor"), UNKNOWN("unknown") }

class FusionInput(
    /** 단조 증가 시각 (초) */
    val timestamp: Double,
    /** 기기 → 지구(동, 북, 위) 회전행렬, 행 우선 9개. v_world = M · v_device */
    val deviceToENU: DoubleArray,
    /** 자이로 회전 속도 (rad/s, 기기 좌표). 자이로 없는 기기는 null → 예측·간섭 감지 1을 건너뜀 */
    val rotationRate: DoubleArray?,
    /** 보정된 자기장 세기 (µT). 모르면 null */
    val fieldMicroTesla: Double?,
    /** 자력계가 보정 안 된 상태인지 */
    val magnetometerUncalibrated: Boolean,
    /** OS가 알려준 방향 오차 (°). 모르면 null */
    val osAccuracyDeg: Double?,
)

class FusionOutput(
    val magneticHeading: Double,
    val quality: Quality,
    val accuracyDeg: Double?,
    /** 자기 간섭 때문에 나침반 보정을 멈추고 자이로만으로 추적 중 */
    val gyroHold: Boolean,
    val pose: Pose,
    val axis: FacingAxis,
)

class HeadingFilter {
    private var heading: Double? = null     // 필터된 자북 기준 방향
    private var lastT: Double? = null
    private var lastOS: Double? = null
    private var mismatch = 0.0               // 나침반 회전 속도와 자이로 회전 속도 차이 (°/s, 평활)
    private var disturbedUntil = 0.0

    fun reset() {
        heading = null; lastT = null; lastOS = null; mismatch = 0.0; disturbedUntil = 0.0
    }

    fun update(input: FusionInput): FusionOutput? {
        val m = input.deviceToENU
        val t = input.timestamp
        var dt = lastT?.let { t - it } ?: 0.0
        lastT = t
        if (!(dt > 0 && dt < 0.2)) dt = 0.0

        // 기기 축의 지구 좌표 = 행렬의 열. 지구 '위'의 기기 좌표 = 3번째 행.
        val yE = m[1]; val yN = m[4]                      // 폰 윗변(+Y)
        val zE = m[2]; val zN = m[5]; val zU = m[8]       // 화면 바깥(+Z), 후면 카메라는 −Z

        val fe = yE - zE; val fn = yN - zN
        val osHeading = if (hypot(fe, fn) < 0.15) null else wrap360(Math.toDegrees(atan2(fe, fn)))

        val pose = when {
            zU > 0.82 -> Pose.FLAT
            zU < -0.82 -> Pose.FACE_DOWN
            abs(zU) < 0.42 -> Pose.UPRIGHT
            else -> Pose.TILTED
        }
        val wTop = hypot(yE, yN); val wCam = hypot(zE, zN)
        val axis = when {
            wTop > wCam * 2 -> FacingAxis.TOP
            wCam > wTop * 2 -> FacingAxis.CAMERA
            else -> FacingAxis.MIXED
        }

        // 지구 수직축 기준 회전 속도. 위에서 봤을 때 시계 방향이 방향 증가.
        val w = input.rotationRate
        val yawRate = if (w == null) 0.0 else -Math.toDegrees(w[0] * m[6] + w[1] * m[7] + w[2] * m[8])

        // 자기 간섭 감지 1: 나침반은 도는데 자이로는 안 돈다 (또는 반대)
        val prev = lastOS
        if (w != null && osHeading != null && prev != null && dt > 0) {
            val osRate = wrap180(osHeading - prev) / dt
            mismatch += (abs(osRate - yawRate) - mismatch) * 0.3
            if (mismatch > 30) disturbedUntil = t + 1.0
        }
        lastOS = osHeading

        // 자기 간섭 감지 2: 자기장 세기가 지구 자기장 범위를 벗어남
        val f = input.fieldMicroTesla
        if (f != null && f !in HeadingConfig.FIELD_RANGE_MICRO_TESLA) disturbedUntil = t + 1.0
        val disturbed = t < disturbedUntil

        val acc = input.osAccuracyDeg ?: 0.0
        val quality = when {
            disturbed || input.magnetometerUncalibrated || acc > 30 -> Quality.POOR
            f != null && acc <= 20 -> Quality.GOOD
            else -> Quality.UNKNOWN
        }

        // 예측: 자이로 회전량만큼 돌린다
        heading?.let { if (w != null && dt > 0) heading = wrap360(it + yawRate * dt) }

        // 보정: 나침반 쪽으로 당긴다. 품질이 나쁘면 당기지 않는다.
        if (osHeading != null) {
            val h = heading
            if (h == null) {
                heading = osHeading
            } else {
                val err = wrap180(osHeading - h)
                // 자이로가 없으면 버틸 수단이 없으므로 품질이 나빠도 천천히 따라간다
                val tau = when (quality) { Quality.GOOD -> 0.6; Quality.UNKNOWN -> 2.0; Quality.POOR -> if (w == null) 2.0 else null }
                if (abs(err) > 90 && quality != Quality.POOR) {
                    heading = osHeading                            // 크게 어긋나면 다시 맞춤
                } else if (tau != null && dt > 0) {
                    heading = wrap360(h + err * (1 - exp(-dt / tau)))
                }
            }
        }

        val h = heading ?: return null
        return FusionOutput(h, quality, input.osAccuracyDeg, disturbed, pose, axis)
    }
}

fun wrap360(d: Double): Double { val r = d % 360.0; return if (r < 0) r + 360.0 else r }
fun wrap180(d: Double): Double = wrap360(d + 180.0) - 180.0
