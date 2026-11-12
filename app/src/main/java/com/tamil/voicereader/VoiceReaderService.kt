package com.tamil.voicereader

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class VoiceReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var floatingOrb: ImageView
    private lateinit var controlPanel: LinearLayout
    private lateinit var btnPlayPause: TextView
    private lateinit var btnSpeed: TextView

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sentencesList = mutableListOf<String>()
    private var rawScreenDump = ""
    private var currentIndex = 0
    private var isPaused = false
    private var isRestartingUtterance = false
    private var speechRate = 1.05f

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        setupFloatingUI()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("ta", "IN"))
            tts?.setPitch(1.02f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (isRestartingUtterance) {
                        isRestartingUtterance = false
                        return
                    }
                    if (!isPaused) {
                        currentIndex++
                        if (currentIndex < sentencesList.size) {
                            speakCurrentSentence()
                        } else {
                            mainHandler.post { tryAutoScrollOrFinish() }
                        }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun setupFloatingUI() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        floatingOrb = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            setPadding(28, 28, 28, 28)
            alpha = 0.22f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
            }
        }

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(Color.parseColor("#F212121C"))
            }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row1.addView(createBtn("⏮️") { jumpSentence(-1) })
        row1.addView(createBtn("-5s") { jumpSentence(-1) })
        btnPlayPause = createBtn("⏸️") { togglePauseResume() }
        row1.addView(btnPlayPause)
        row1.addView(createBtn("+5s") { jumpSentence(1) })
        row1.addView(createBtn("⏭️") { jumpSentence(1) })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
        }
        btnSpeed = createBtn("1.0x") { cycleSpeed() }
        val btnExplain = createBtn("🧠 புரியவை") {
            smartAnalyzeScreen("திரையில் உள்ள முக்கியமான பதிலை மட்டும் எடுத்து, ஒரு நண்பன் பக்கத்துல இருந்து இயல்பா தமிழ்ல (தேவைப்பட்டா ஆங்கில வார்த்தைகளோட) 3 வரிகளில் புரிய வை. பயனர் கேட்ட கேள்வியைத் திரும்பச் சொல்லாதே.")
        }
        val btnTalk = createBtn("🎙️ பேசு") { startLiveVoiceChat() }
        val btnClose = createBtn("❌") { stopAndHide() }

        row2.addView(btnSpeed)
        row2.addView(btnExplain)
        row2.addView(btnTalk)
        row2.addView(btnClose)

        controlPanel.addView(row1)
        controlPanel.addView(row2)

        rootContainer.addView(floatingOrb, LinearLayout.LayoutParams(125, 125))
        val panelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 10, 0, 0) }
        rootContainer.addView(controlPanel, panelParams)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTime = 0L

        floatingOrb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    floatingOrb.alpha = 1.0f
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(rootContainer, windowParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(event.rawX - initialTouchX) > 12 || Math.abs(event.rawY - initialTouchY) > 12
                    val isLong = (System.currentTimeMillis() - downTime) > 500
                    if (!moved) {
                        if (isLong) startLiveVoiceChat() else onOrbSingleTap()
                    } else if (controlPanel.visibility != View.VISIBLE) {
                        floatingOrb.alpha = 0.22f
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(rootContainer, windowParams)
    }

    private fun createBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 10)
            setOnClickListener { onClick() }
        }
    }

    private fun onOrbSingleTap() {
        floatingOrb.alpha = 1.0f
        if (tts?.isSpeaking == true || isPaused) {
            controlPanel.visibility = if (controlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        // Single tap immediately uses Smart AI Analyze so it reads ChatGPT/Gemini answers naturally, never robotic!
        smartAnalyzeScreen("திரையில் இருக்கும் AI பதில் அல்லது கட்டுரையை மட்டும் எடுத்து, கேள்வியைத் தவிர்த்துவிட்டு, இயல்பான தமிழில் (ஆங்கிலக் கட்டுரையாக இருந்தால் தெளிவான ஆங்கிலம் அல்லது தமிழில்) சுருக்கமாகவும் சுவாரஸ்யமாகவும் வாசித்துச் சொல்.")
    }

    private fun captureDeepScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val bag = LinkedHashSet<String>()
        walkTree(rootNode, bag)
        return bag.joinToString("\n")
    }

    private fun walkTree(node: AccessibilityNodeInfo?, bag: LinkedHashSet<String>) {
        if (node == null) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        val h = node.hintText?.toString()?.trim()

        if (!t.isNullOrBlank() && t.length > 2) bag.add(t)
        if (!d.isNullOrBlank() && d.length > 8 && d != h) bag.add(d)

        for (i in 0 until node.childCount) {
            walkTree(node.getChild(i), bag)
        }
    }

    private fun smartAnalyzeScreen(instruction: String) {
        rawScreenDump = captureDeepScreenText()
        if (rawScreenDump.isBlank()) {
            Toast.makeText(this, "திரையில் தகவல் இல்லை; 🎙️ பேசு தட்டி கேளுங்கள்", Toast.LENGTH_SHORT).show()
            floatingOrb.alpha = 0.22f
            return
        }

        val prefs = getSharedPreferences("TamilReaderPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            // Fallback clean bilingual reader if API key is not entered yet
            val clean = rawScreenDump.replace(Regex("[*#`_~•]"), " ").replace(Regex("\\s+"), " ")
            startSpeakingText(clean)
            return
        }

        controlPanel.visibility = View.VISIBLE
        updateOrbColor("#9C27B0") // Purple = AI Thinking

        thread {
            try {
                val url = URL("https://api.groq.com/openai/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                val systemPrompt = """
                    You are a warm, intelligent bilingual AI voice companion (like Gemini Live) speaking Tamil and English naturally.
                    Analyze the raw screen text from the user's phone (which may be from ChatGPT, Gemini, Chrome, or a PDF).
                    Ignore UI noise (buttons, timestamps, ads, 'Ask Gemini', 'Message ChatGPT', and the user's own prompt).
                    Focus ONLY on the actual AI answer or main article content.
                    Speak like a helpful human friend—never sound like a robotic screen reader.
                    If the user asks in English, reply in natural English. If in Tamil/Tanglish, reply in natural conversational Tamil/Tanglish.
                    Keep responses concise, clear, and free of markdown symbols (*, #, bullets).
                """.trimIndent()

                val userMsg = "Screen Content:\n${rawScreenDump.take(5000)}\n\nTask: $instruction"

                val body = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("temperature", 0.5)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userMsg) })
                    })
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val response = conn.inputStream.bufferedReader().readText()
                val aiReply = JSONObject(response)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .replace(Regex("[*#`_~•]"), " ")
                    .trim()

                mainHandler.post {
                    startSpeakingText(aiReply)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "AI இணைப்பு பிழை: Groq Key / Internet சரிபார்க்கவும்", Toast.LENGTH_SHORT).show()
                    updateOrbColor("#6200EE")
                }
            }
        }
    }

    private fun startSpeakingText(text: String) {
        sentencesList.clear()
        val parts = text.split(Regex("(?<=[.!?।\\n])\\s+")).filter { it.isNotBlank() }
        sentencesList.addAll(parts)
        if (sentencesList.isEmpty()) return

        currentIndex = 0
        isPaused = false
        btnPlayPause.text = "⏸️"
        controlPanel.visibility = View.VISIBLE
        floatingOrb.alpha = 1.0f
        updateOrbColor("#E91E63")
        speakCurrentSentence()
    }

    private fun speakCurrentSentence() {
        if (currentIndex !in sentencesList.indices) return
        val sentence = sentencesList[currentIndex]

        // Auto-detect Tamil vs English script for natural pronunciation
        val hasTamil = sentence.any { it in '\u0B80'..'\u0BFF' }
        if (hasTamil) {
            tts?.setLanguage(Locale("ta", "IN"))
        } else {
            tts?.setLanguage(Locale("en", "IN"))
        }

        tts?.setSpeechRate(speechRate)
        val params = Bundle()
        tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, "S_$currentIndex")
    }

    private fun cycleSpeed() {
        speechRate = when (speechRate) {
            1.05f -> 1.3f
            1.3f -> 1.6f
            1.6f -> 2.0f
            else -> 1.05f
        }
        val label = if (speechRate == 1.05f) "1.0x" else "${speechRate}x"
        btnSpeed.text = label
        if (!isPaused && sentencesList.isNotEmpty()) {
            isRestartingUtterance = true
            tts?.stop()
            speakCurrentSentence()
        }
    }

    private fun jumpSentence(delta: Int) {
        if (sentencesList.isEmpty()) return
        isRestartingUtterance = true
        tts?.stop()
        currentIndex = (currentIndex + delta).coerceIn(0, sentencesList.size - 1)
        isPaused = false
        btnPlayPause.text = "⏸️"
        updateOrbColor("#E91E63")
        speakCurrentSentence()
    }

    private fun togglePauseResume() {
        if (isPaused) {
            isPaused = false
            btnPlayPause.text = "⏸️"
            updateOrbColor("#E91E63")
            speakCurrentSentence()
        } else {
            isPaused = true
            isRestartingUtterance = true
            tts?.stop()
            btnPlayPause.text = "▶️"
            updateOrbColor("#FF9800")
        }
    }

    private fun tryAutoScrollOrFinish() {
        val rootNode = rootInActiveWindow ?: run { stopAndHide(); return }
        val scrollable = findScrollable(rootNode)
        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            mainHandler.postDelayed({
                val nextDump = captureDeepScreenText()
                if (nextDump.isNotBlank() && nextDump != rawScreenDump) {
                    smartAnalyzeScreen("ஸ்க்ரோல் செய்த பிறகு திரையில் புதிதாகத் தெரியும் மீதிப் பதிலை மட்டும் தொடர்ந்து இயல்பாகச் சொல்.")
                } else {
                    stopAndHide()
                }
            }, 900)
        } else {
            stopAndHide()
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun startLiveVoiceChat() {
        isRestartingUtterance = true
        tts?.stop()
        isPaused = true
        btnPlayPause.text = "▶️"
        floatingOrb.alpha = 1.0f
        controlPanel.visibility = View.VISIBLE
        updateOrbColor("#00BCD4")

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Supports both Tamil and Indian English voice input seamlessly
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ta-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US"))
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@VoiceReaderService, "🎙️ தமிழ் அல்லது English-ல் கேளுங்கள்...", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (query.isNotBlank()) {
                    Toast.makeText(this@VoiceReaderService, "நீங்கள்: $query", Toast.LENGTH_SHORT).show()
                    smartAnalyzeScreen("பயனர் குரலில் கேட்ட கேள்வி: '$query'. திரையில் உள்ள தகவலை வைத்து இதற்கு மிக இயல்பாகவும் சுருக்கமாகவும் நண்பன் போலப் பதில் சொல்.")
                }
            }
            override fun onError(error: Int) {
                updateOrbColor("#6200EE")
                Toast.makeText(this@VoiceReaderService, "மைக் அனுமதி ஆன் உள்ளதா எனச் சரிபார்க்கவும்", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun stopAndHide() {
        isRestartingUtterance = true
        tts?.stop()
        isPaused = false
        currentIndex = 0
        controlPanel.visibility = View.GONE
        floatingOrb.alpha = 0.22f
        updateOrbColor("#6200EE")
    }

    private fun updateOrbColor(hexColor: String) {
        (floatingOrb.background as? GradientDrawable)?.setColor(Color.parseColor(hexColor))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAndHide() }
    override fun onDestroy() {
        super.onDestroy()
        if (::rootContainer.isInitialized) windowManager.removeView(rootContainer)
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
