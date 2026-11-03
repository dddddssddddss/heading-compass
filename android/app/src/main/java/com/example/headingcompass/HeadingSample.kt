package com.example.headingcompass

import org.json.JSONObject
import kotlin.math.roundToInt

/** 앱이 내보내는 방향 한 건. 백엔드로 보내는 JSON과 같다 (iOS와 동일 형식). */
data class HeadingSample(
    /** 진북 기준 바라보는 방향 (0~360°) */
    val heading: Double,
    val magneticHeading: Double,
    val declination: Double,
    /** OS가 알려준 오차 (°). 모르면 null */
    val accuracyDeg: Double?,
    val quality: Quality,
    /** 자기 간섭으로 자이로만으로 추적 중 */
    val gyroHold: Boolean,
    val pose: Pose,
    val axis: FacingAxis,
    /** 측정 시각 (Unix ms) */
    val ts: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("heading", heading)
        .put("magneticHeading", magneticHeading)
        .put("declination", declination)
        .put("accuracyDeg", accuracyDeg ?: JSONObject.NULL)
        .put("quality", quality.json)
        .put("gyroHold", gyroHold)
        .put("pose", pose.json)
        .put("axis", axis.json)
        .put("ts", ts)

    val directionWord: String get() = DIRECTIONS_8[(heading / 45).roundToInt() % 8]

    val qualityWord: String get() = when (quality) {
        Quality.GOOD -> "정확도 좋음"
        Quality.UNKNOWN -> "정확도 확인 중"
        Quality.POOR -> if (gyroHold) "주변 자기 간섭, 방향 부정확할 수 있음" else "방향 부정확, 폰으로 8자를 그려 보정하세요"
    }

    val spokenText: String get() = "$directionWord, ${heading.roundToInt() % 360}도, $qualityWord"

    private companion object {
        val DIRECTIONS_8 = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    }
}
