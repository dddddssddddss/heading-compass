package com.example.headingcompass

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** 화면 전체가 버튼 하나. 탭(TalkBack은 두 번 탭)하면 현재 방향을 읽어 준다. */
class MainActivity : Activity() {
    private lateinit var engine: HeadingEngine
    private lateinit var root: LinearLayout
    private lateinit var degText: TextView
    private lateinit var dirText: TextView
    private lateinit var qualityText: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var latest: HeadingSample? = null
    private var lastUiUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        degText = text(88f, Typeface.BOLD).apply { text = "---°" }
        dirText = text(48f, Typeface.BOLD)
        qualityText = text(20f, Typeface.NORMAL).apply { text = "방향 측정 준비 중" }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            isClickable = true
            isFocusable = true
            contentDescription = "방향 측정 준비 중"
            setOnClickListener { announce() }
            addView(degText); addView(dirText); addView(qualityText)
        }
        // 접근성 서비스는 root 하나만 읽도록 자식 텍스트는 숨긴다
        listOf(degText, dirText, qualityText).forEach { it.importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO }
        setContentView(root)

        engine = HeadingEngine(this) { s -> runOnUiThread { show(s) } }
        if (!engine.isSupported) {
            qualityText.text = "이 기기는 방향 센서를 지원하지 않습니다"
            root.contentDescription = qualityText.text
        }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.KOREAN
        }
    }

    override fun onResume() { super.onResume(); engine.start() }
    override fun onPause() { engine.stop(); super.onPause() }
    override fun onDestroy() { engine.shutdown(); tts?.shutdown(); super.onDestroy() }

    private fun show(s: HeadingSample) {
        latest = s
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 100) return        // 화면은 초당 10번이면 충분
        lastUiUpdate = now
        degText.text = String.format(Locale.US, "%03d°", s.heading.roundToInt() % 360)
        dirText.text = s.directionWord
        qualityText.text = s.qualityWord
        qualityText.setTextColor(if (s.quality == Quality.POOR) Color.rgb(0xE0, 0x7A, 0x00) else Color.GRAY)
        root.contentDescription = s.spokenText + ". 두 번 탭하면 현재 방향을 읽습니다"
    }

    private fun announce() {
        val text = latest?.spokenText ?: qualityText.text.toString()
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "heading")
    }

    private fun text(sizeSp: Float, style: Int) = TextView(this).apply {
        textSize = sizeSp
        setTypeface(Typeface.DEFAULT, style)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
