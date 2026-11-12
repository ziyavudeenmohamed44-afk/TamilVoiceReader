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
    private lateinit var btnTalk: TextView

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sentencesList = mutableListOf<String>()
    private var readHistorySet = mutableSetOf<String>()
    private var screenContextText = ""
    private var currentIndex = 0
    private var isPaused = false
    private var isRestartingUtterance = false
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

        // Transparent / Ghost Orb when idle (alpha = 0.18f so it never blocks screen)
        floatingOrb = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            setPadding(28, 28, 28, 28)
            alpha = 0.18f
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
                setColor(Color.parseColor("#EB14141F"))
            }
        }

        // Row 1: Navigation & Seek
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

        // Row 2: Speed, Interactive Live Talk, AI Summary & Close
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
        }
        btnSpeed = createBtn("1.0x") { cycleSpeedWithoutRestart() }
        btnTalk = createBtn("🎙️ பேசு") { startInteractiveConversation() }
        val btnSummary = createBtn("✨ சுருக்கு") { askScreenAI("இதை 3 வரிகளில் சுருக்கமாகத் தமிழில் சொல்.") }
        val btnClose = createBtn("❌") { stopAndHide() }

        row2.addView(btnSpeed)
        row2.addView(btnTalk)
        row2.addView(btnSummary)
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
            y = 320
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchDownTime = 0L

        floatingOrb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    floatingOrb.alpha = 1.0f
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchDownTime = System.currentTimeMillis()
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
                    val heldLong = (System.currentTimeMillis() - touchDownTime) > 550
                    if (!moved) {
                        if (heldLong) {
                            // Long press Orb -> Direct Live Voice Question (like Gemini Live)
                            startInteractiveConversation()
                        } else {
                            onOrbTapped()
                        }
                    } else if (controlPanel.visibility != View.VISIBLE) {
                        floatingOrb.alpha = 0.18f
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

    private fun onOrbTapped() {
        floatingOrb.alpha = 1.0f
        if (tts?.isSpeaking == true || isPaused) {
            controlPanel.visibility = if (controlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        readCurrentScreen()
    }

    private fun readCurrentScreen() {
        val rootNode = rootInActiveWindow ?: return
        readHistorySet.clear()
        sentencesList.clear()

        val allBlocks = captureAllVisibleText(rootNode)
        val cleanResponse = extractAIResponseOnly(allBlocks)
        screenContextText = allBlocks.joinToString("\n")

        if (cleanResponse.isBlank()) {
            Toast.makeText(this, "வாசிக்க பதில் இல்லை; 🎙️ பேசு தட்டி கேளுங்கள்", Toast.LENGTH_SHORT).show()
            floatingOrb.alpha = 0.18f
            return
        }

        appendSentences(cleanResponse)
        if (sentencesList.isNotEmpty()) {
            currentIndex = 0
            isPaused = false
            btnPlayPause.text = "⏸️"
            controlPanel.visibility = View.VISIBLE
            floatingOrb.alpha = 1.0f
            updateOrbColor("#E91E63")
            speakCurrentSentence()
        }
    }

    private fun captureAllVisibleText(root: AccessibilityNodeInfo): List<String> {
        val items = mutableListOf<String>()
        collectDeep(root, items)
        return items.distinct()
    }

    private fun collectDeep(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()

        if (!t.isNullOrBlank() && t.length > 3) list.add(t)
        if (!d.isNullOrBlank() && d.length > 15) list.add(d)

        for (i in 0 until node.childCount) {
            collectDeep(node.getChild(i), list)
        }
    }

    private fun extractAIResponseOnly(blocks: List<String>): String {
        val uiNoise = listOf(
            "Ask Gemini", "Message ChatGPT", "Reply to ChatGPT", "Reply to Claude",
            "Gemini is AI", "ChatGPT can make mistakes", "Worked for", "Thinking",
            "Sponsored", "Learn more", "New chat", "Search or type URL"
        )

        val filtered = blocks.filter { b ->
            b.length > 8 && uiNoise.none { n -> b.contains(n, ignoreCase = true) }
        }

        if (filtered.isEmpty()) return ""

        // In ChatGPT & Gemini, user's prompt is a shorter block right above the detailed AI answer.
        // Find the last user prompt block and take ONLY the blocks that appear AFTER it.
        var promptIdx = -1
        for (i in filtered.indices) {
            val current = filtered[i]
            val hasLongerReplyBelow = (i + 1 until filtered.size).any { j -> filtered[j].length > 60 }
            if (current.length in 10..220 && hasLongerReplyBelow && i < filtered.size - 1) {
                promptIdx = i
            }
        }

        val answerBlocks = if (promptIdx != -1 && promptIdx + 1 < filtered.size) {
            filtered.subList(promptIdx + 1, filtered.size)
        } else {
            filtered
        }

        return answerBlocks.joinToString(". ")
            .replace(Regex("[*#`_~•≠]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun appendSentences(text: String) {
        val parts = text.split(Regex("(?<=[.!?।\\n])\\s+")).filter { it.length > 2 }
        for (p in parts) {
            if (readHistorySet.add(p)) sentencesList.add(p)
        }
    }

    private fun speakCurrentSentence() {
        if (currentIndex in sentencesList.indices) {
            tts?.setSpeechRate(speechRate)
            val params = Bundle()
            tts?.speak(sentencesList[currentIndex], TextToSpeech.QUEUE_FLUSH, params, "S_$currentIndex")
        }
    }

    private fun cycleSpeedWithoutRestart() {
        speechRate = when (speechRate) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        btnSpeed.text = "${speechRate}x"
        // Re-speak ONLY the current sentence at the new speed without resetting currentIndex
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
                val newRoot = rootInActiveWindow
                if (newRoot != null) {
                    val newBlocks = captureAllVisibleText(newRoot)
                    screenContextText += "\n" + newBlocks.joinToString("\n")
                    val moreText = extractAIResponseOnly(newBlocks)
                    val oldSize = sentencesList.size
                    appendSentences(moreText)
                    if (sentencesList.size > oldSize) {
                        speakCurrentSentence()
                    } else {
                        stopAndHide()
                    }
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

    // Interactive Live Voice Conversation (like video_2.mp4)
    private fun startInteractiveConversation() {
        isRestartingUtterance = true
        tts?.stop()
        isPaused = true
        btnPlayPause.text = "▶️"
        floatingOrb.alpha = 1.0f
        controlPanel.visibility = View.VISIBLE
        updateOrbColor("#00BCD4") // Cyan = Listening to you

        // Refresh latest screen content before user asks question
        rootInActiveWindow?.let { root ->
            screenContextText = captureAllVisibleText(root).joinToString("\n")
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@VoiceReaderService, "🎙️ கேளுங்கள் (எ.கா: இதுல என்ன சொல்லியிருக்கு?)...", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val userQuestion = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (userQuestion.isNotBlank()) {
                    Toast.makeText(this@VoiceReaderService, "நீங்கள்: $userQuestion", Toast.LENGTH_SHORT).show()
                    askScreenAI(userQuestion)
                }
            }
            override fun onError(error: Int) {
                updateOrbColor("#6200EE")
                Toast.makeText(this@VoiceReaderService, "குரல் கேட்கவில்லை, மீண்டும் தட்டவும்", Toast.LENGTH_SHORT).show()
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

    private fun askScreenAI(userQuestion: String) {
        val prefs = getSharedPreferences("TamilReaderPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            Toast.makeText(this, "முதலில் ஆப்-ல் இலவச Groq API Key சேமிக்கவும்", Toast.LENGTH_LONG).show()
            return
        }

        updateOrbColor("#9C27B0")
        thread {
            try {
                val url = URL("https://api.groq.com/openai/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                val systemPrompt = "நீ ஒரு நட்பான தமிழ் AI உதவியாளர் (Gemini Live போல). பயனரின் போன் திரையில் இருக்கும் தகவலைப் பார்த்து, பயனர் கேட்கும் கேள்விக்கு 2 முதல் 4 சிறிய வாக்கியங்களில் மிக இயல்பான பேச்சுத் தமிழில் நேரடியாகப் பதில் சொல். Markdown குறியீடுகள் (*, #) பயன்படுத்தாதே."
                val userContent = "திரையில் இருக்கும் தகவல்:\n${screenContextText.take(4000)}\n\nபயனரின் கேள்வி: $userQuestion"

                val body = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    })
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val res = conn.inputStream.bufferedReader().readText()
                val reply = JSONObject(res)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .replace(Regex("[*#`_~]"), "")
                    .trim()

                mainHandler.post {
                    readHistorySet.clear()
                    sentencesList.clear()
                    appendSentences(reply)
                    currentIndex = 0
                    isPaused = false
                    btnPlayPause.text = "⏸️"
                    updateOrbColor("#E91E63")
                    speakCurrentSentence()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "AI பதில் பெற முடியவில்லை, இணையம்/Key சரிபார்க்கவும்", Toast.LENGTH_SHORT).show()
                    updateOrbColor("#6200EE")
                }
            }
        }
    }

    private fun stopAndHide() {
        isRestartingUtterance = true
        tts?.stop()
        isPaused = false
        currentIndex = 0
        controlPanel.visibility = View.GONE
        floatingOrb.alpha = 0.18f // Return to transparent ghost mode
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
