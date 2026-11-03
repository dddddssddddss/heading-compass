package com.example.headingcompass

object HeadingConfig {
    /** 방향을 받을 백엔드 주소. null이면 아무것도 보내지 않는다. 예: "https://example.com/api/heading" */
    val ENDPOINT: String? = null

    /** 이 각도 이상 바뀌었거나 */
    const val SEND_MIN_DELTA_DEG = 5.0
    /** 이 시간이 지나면 전송 (초) */
    const val SEND_MAX_INTERVAL = 1.0

    /** 자편각 계산 기준 위치 (GPS 없이 서울 좌표로 계산, 국내 오차 1° 안팎) */
    const val DECLINATION_LAT = 37.5665f
    const val DECLINATION_LON = 126.9780f

    /** 지구 자기장 세기 정상 범위 (µT). 한국은 약 50µT. 벗어나면 자기 간섭으로 본다. */
    val FIELD_RANGE_MICRO_TESLA = 25.0..70.0
}
