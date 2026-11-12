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
    private var currentIndex = 0
    private var isPaused = false
    private var manualStopOrJump = false
    private var speechRate = 1.0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        setupFloatingUI()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("ta", "IN"))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (manualStopOrJump) {
                        manualStopOrJump = false
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

        // 15% opacity when idle so it stays nearly invisible
        floatingOrb = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            setPadding(28, 28, 28, 28)
            alpha = 0.15f
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
            askGroqAI("திரையில் உள்ள முக்கியமான பதிலை மட்டும் எடுத்து, ஒரு நண்பன் பக்கத்துல இருந்து இயல்பா தமிழ்ல (தேவைப்பட்டா ஆங்கில வார்த்தைகளோட) 3 வரிகளில் சுருக்கமாகப் புரிய வை.")
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
                        floatingOrb.alpha = 0.15f
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
        // Instant reliable reading first!
        val screenText = extractCleanScreenText()
        if (screenText.isBlank()) {
            Toast.makeText(this, "திரையில் வாசிக்க எழுத்துக்கள் இல்லை", Toast.LENGTH_SHORT).show()
            floatingOrb.alpha = 0.15f
            return
        }
        startSpeakingText(screenText)
    }

    private fun extractCleanScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val blocks = mutableListOf<String>()
        collectVisibleNodes(rootNode, blocks)

        val noise = listOf(
            "Ask Gemini", "Message ChatGPT", "Reply to Claude", "Gemini is AI",
            "ChatGPT can make mistakes", "Sponsored", "New chat", "Search or type URL"
        )
        val cleanBlocks = blocks.distinct().filter { b ->
            b.length > 6 && noise.none { n -> b.contains(n, ignoreCase = true) }
        }

        if (cleanBlocks.isEmpty()) return ""

        // Skip short user prompt right above longer AI answer
        var startIdx = 0
        for (i in cleanBlocks.indices) {
            val hasLongBelow = (i + 1 until cleanBlocks.size).any { j -> cleanBlocks[j].length > 50 }
            if (cleanBlocks[i].length in 8..200 && hasLongBelow && i < cleanBlocks.size - 1) {
                startIdx = i + 1
            }
        }

        return cleanBlocks.subList(startIdx, cleanBlocks.size)
            .joinToString(". ")
            .replace(Regex("[*#`_~•]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun collectVisibleNodes(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()

        if (!t.isNullOrBlank() && t.length > 3) list.add(t)
        if (!d.isNullOrBlank() && d.length > 15) list.add(d)

        for (i in 0 until node.childCount) {
            collectVisibleNodes(node.getChild(i), list)
        }
    }

    private fun startSpeakingText(text: String) {
        sentencesList.clear()
        val parts = text.split(Regex("(?<=[.!?।\\n])\\s+")).filter { it.isNotBlank() }
        sentencesList.addAll(parts)
        if (sentencesList.isEmpty()) return

        currentIndex = 0
        isPaused = false
        manualStopOrJump = false
        btnPlayPause.text = "⏸️"
        controlPanel.visibility = View.VISIBLE
        floatingOrb.alpha = 1.0f
        updateOrbColor("#E91E63")
        speakCurrentSentence()
    }

    private fun speakCurrentSentence() {
        if (currentIndex !in sentencesList.indices) return
        val sentence = sentencesList[currentIndex]

        val hasTamil = sentence.any { it in '\u0B80'..'\u0BFF' }
        tts?.setLanguage(if (hasTamil) Locale("ta", "IN") else Locale("en", "IN"))
        tts?.setSpeechRate(speechRate)

        val params = Bundle()
        tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, "S_$currentIndex")
    }

    private fun cycleSpeed() {
        speechRate = when (speechRate) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        btnSpeed.text = "${speechRate}x"
        if (!isPaused && sentencesList.isNotEmpty()) {
            manualStopOrJump = true
            tts?.stop()
            speakCurrentSentence()
        }
    }

    private fun jumpSentence(delta: Int) {
        if (sentencesList.isEmpty()) return
        manualStopOrJump = true
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
            manualStopOrJump = true
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
                val moreText = extractCleanScreenText()
                if (moreText.isNotBlank() && !sentencesList.contains(moreText.take(40))) {
                    startSpeakingText(moreText)
                } else {
                    stopAndHide()
                }
            }, 850)
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
        manualStopOrJump = true
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US"))
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@VoiceReaderService, "🎙️ கேளுங்கள்...", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (query.isNotBlank()) {
                    Toast.makeText(this@VoiceReaderService, "நீங்கள்: $query", Toast.LENGTH_SHORT).show()
                    askGroqAI("பயனர் கேள்வி: '$query'. திரையில் உள்ள தகவலை வைத்து 2-3 வரிகளில் இயல்பாகப் பதில் சொல்.")
                } else {
                    updateOrbColor("#6200EE")
                }
            }
            override fun onError(error: Int) {
                updateOrbColor("#6200EE")
                Toast.makeText(this@VoiceReaderService, "மைக் பிழை ($error) - மீண்டும் தட்டவும்", Toast.LENGTH_SHORT).show()
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

    private fun askGroqAI(instruction: String) {
        val screenContent = extractCleanScreenText()
        val prefs = getSharedPreferences("TamilReaderPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            Toast.makeText(this, "Groq API Key இல்லை — ஆஃப்லைன் சுருக்கம் வாசிக்கிறது", Toast.LENGTH_SHORT).show()
            val shortFallback = screenContent.split(Regex("(?<=[.!?])\\s+")).take(3).joinToString(". ")
            if (shortFallback.isNotBlank()) startSpeakingText(shortFallback)
            return
        }

        manualStopOrJump = true
        tts?.stop()
        updateOrbColor("#9C27B0")
        Toast.makeText(this, "AI யோசிக்கிறது...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val url = URL("https://api.groq.com/openai/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }

                val systemPrompt = "நீ ஒரு நட்பான தமிழ்-ஆங்கில AI உதவியாளர். திரையில் உள்ள தகவலைப் பார்த்து, கேட்கப்பட்டதற்கு 2-3 சிறிய வாக்கியங்களில் இயல்பான பேச்சுத் தமிழில் பதில் சொல். Markdown குறியீடுகள் (*, #) பயன்படுத்தாதே."
                val userMsg = "திரை தகவல்:\n${screenContent.take(3500)}\n\nகட்டளை: $instruction"

                val body = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userMsg) })
                    })
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                    throw Exception("API Error $code: ${errText.take(80)}")
                }

                val res = conn.inputStream.bufferedReader().readText()
                val reply = JSONObject(res)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .replace(Regex("[*#`_~•]"), " ")
                    .trim()

                mainHandler.post {
                    startSpeakingText(reply)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "AI பிழை: ${e.message}", Toast.LENGTH_LONG).show()
                    updateOrbColor("#6200EE")
                    // Fallback to reading screen directly so it never stays silent
                    if (screenContent.isNotBlank()) startSpeakingText(screenContent)
                }
            }
        }
    }

    private fun stopAndHide() {
        manualStopOrJump = true
        tts?.stop()
        isPaused = false
        currentIndex = 0
        sentencesList.clear()
        controlPanel.visibility = View.GONE
        floatingOrb.alpha = 0.15f
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
