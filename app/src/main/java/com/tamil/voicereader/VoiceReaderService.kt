package com.tamil.voicereader

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import java.util.Locale

class VoiceReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingOrb: ImageView
    private var tts: TextToSpeech? = null
    private var isSpeaking = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        setupFloatingOrb()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ta", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "போன் Settings-ல் Tamil TTS டவுன்லோட் செய்யவும்", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupFloatingOrb() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingOrb = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            setPadding(35, 35, 35, 35)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
            }
        }

        val params = WindowManager.LayoutParams(
            150,
            150,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 400
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingOrb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingOrb, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        onOrbClicked()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingOrb, params)
    }

    private fun onOrbClicked() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
            isSpeaking = false
            updateOrbColor("#6200EE")
            Toast.makeText(this, "நிறுத்தப்பட்டது", Toast.LENGTH_SHORT).show()
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Toast.makeText(this, "திரையைப் படிக்க முடியவில்லை", Toast.LENGTH_SHORT).show()
            return
        }

        val extractedTexts = mutableListOf<String>()
        collectScreenText(rootNode, extractedTexts)

        // Filter out short UI labels and pick the longest/latest AI response blocks
        val meaningfulTexts = extractedTexts.filter { it.length > 25 }
        val textToRead = if (meaningfulTexts.isNotEmpty()) {
            meaningfulTexts.takeLast(3).joinToString(". ")
        } else {
            extractedTexts.joinToString(". ")
        }

        val cleanText = textToRead
            .replace(Regex("[*#`_~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanText.isNotEmpty()) {
            isSpeaking = true
            updateOrbColor("#E91E63")
            val params = Bundle()
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "AI_RESPONSE")
        } else {
            Toast.makeText(this, "வாசிப்பதற்கு உரை எதுவும் இல்லை", Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectScreenText(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        if (node.text != null && node.text.toString().isNotBlank()) {
            list.add(node.text.toString().trim())
        }
        for (i in 0 until node.childCount) {
            collectScreenText(node.getChild(i), list)
        }
    }

    private fun updateOrbColor(hexColor: String) {
        (floatingOrb.background as? GradientDrawable)?.setColor(Color.parseColor(hexColor))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingOrb.isInitialized) {
            windowManager.removeView(floatingOrb)
        }
        tts?.shutdown()
    }
}
