package com.example.headingcompass

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * HeadingConfig.ENDPOINT로 방향을 POST한다. 주소가 없으면 아무것도 하지 않는다.
 * 5° 이상 바뀌었거나 1초가 지났을 때만 보내고, 이전 요청이 끝나기 전에는 새로 보내지 않는다.
 */
class HeadingReporter(context: Context) {
    private val deviceId: String
    private var lastHeading: Double? = null
    private var lastTime = 0.0
    private val inFlight = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()

    init {
        val prefs = context.getSharedPreferences("heading", Context.MODE_PRIVATE)
        deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    fun offer(sample: HeadingSample, now: Double) {
        val endpoint = HeadingConfig.ENDPOINT ?: return
        if (inFlight.get()) return
        val last = lastHeading
        if (last != null && abs(wrap180(sample.heading - last)) < HeadingConfig.SEND_MIN_DELTA_DEG &&
            now - lastTime < HeadingConfig.SEND_MAX_INTERVAL) return

        lastHeading = sample.heading
        lastTime = now
        inFlight.set(true)

        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("platform", "android")
            .put("sample", sample.toJson())
            .toString()
            .toByteArray()

        io.execute {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun shutdown() = io.shutdownNow()
}
