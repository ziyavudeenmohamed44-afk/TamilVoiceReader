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
    private lateinit var btnSimplify: TextView

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sentencesList = mutableListOf<String>()
    private var readHistorySet = mutableSetOf<String>()
    private var fullCapturedText = ""
    private var currentIndex = 0
    private var isPaused = false
    private var isSimplifiedMode = false
    private var autoScrollEnabled = true
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
                    if (!isPaused) {
                        currentIndex++
                        if (currentIndex < sentencesList.size) {
                            speakCurrentSentence()
                        } else if (autoScrollEnabled && !isSimplifiedMode) {
                            mainHandler.post { tryAutoScrollAndContinue() }
                        } else {
                            mainHandler.post { resetUIState() }
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
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
            }
        }

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(18, 14, 18, 14)
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.parseColor("#F0181824"))
            }
        }

        // Row 1: Navigation & Seek Controls
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnPrev = createBtn("⏮️") { jumpParagraph(-1) }
        val btnBack5s = createBtn("-5s") { seekSeconds(-5) }
        btnPlayPause = createBtn("⏸️") { togglePauseResume() }
        val btnFwd5s = createBtn("+5s") { seekSeconds(5) }
        val btnNext = createBtn("⏭️") { jumpParagraph(1) }

        row1.addView(btnPrev)
        row1.addView(btnBack5s)
        row1.addView(btnPlayPause)
        row1.addView(btnFwd5s)
        row1.addView(btnNext)

        // Row 2: Speed, AI Simplify, Voice & Close
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        btnSpeed = createBtn("1.0x") { cycleSpeed() }
        btnSimplify = createBtn("✨AI சுருக்கம்") { triggerAISimplify() }
        val btnVoice = createBtn("🎙️") { startVoiceCommand() }
        val btnClose = createBtn("❌") { stopEverything() }

        row2.addView(btnSpeed)
        row2.addView(btnSimplify)
        row2.addView(btnVoice)
        row2.addView(btnClose)

        controlPanel.addView(row1)
        controlPanel.addView(row2)

        rootContainer.addView(floatingOrb, LinearLayout.LayoutParams(135, 135))
        val panelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 0) }
        rootContainer.addView(controlPanel, panelParams)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingOrb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(rootContainer, windowParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(event.rawX - initialTouchX) < 12 && Math.abs(event.rawY - initialTouchY) < 12) {
                        onOrbClicked()
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
            setPadding(18, 10, 18, 10)
            setOnClickListener { onClick() }
        }
    }

    private fun onOrbClicked() {
        if (tts?.isSpeaking == true || isPaused) {
            controlPanel.visibility = if (controlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        startFreshReading()
    }

    private fun startFreshReading() {
        val rootNode = rootInActiveWindow ?: return
        readHistorySet.clear()
        sentencesList.clear()
        fullCapturedText = ""
        isSimplifiedMode = false
        btnSimplify.text = "✨AI சுருக்கம்"

        val blocks = extractScreenBlocks(rootNode)
        if (blocks.isEmpty()) {
            Toast.makeText(this, "திரையில் வாசிக்க உரை இல்லை", Toast.LENGTH_SHORT).show()
            return
        }

        val cleanedText = filterAndJoinBlocks(blocks)
        fullCapturedText = cleanedText
        appendSentences(cleanedText)

        if (sentencesList.isNotEmpty()) {
            currentIndex = 0
            isPaused = false
            btnPlayPause.text = "⏸️"
            controlPanel.visibility = View.VISIBLE
            updateOrbColor("#E91E63")
            speakCurrentSentence()
        }
    }

    private fun extractScreenBlocks(root: AccessibilityNodeInfo): List<String> {
        val raw = mutableListOf<String>()
        traverseNode(root, raw)
        return raw.distinct()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        // Capture both text (Gemini/Native) and contentDescription (ChatGPT/Chrome/WebView/PDF)
        val txt = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!txt.isNullOrBlank() && txt.length > 12) {
            list.add(txt)
        } else if (!desc.isNullOrBlank() && desc.length > 20) {
            list.add(desc)
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), list)
        }
    }

    private fun filterAndJoinBlocks(blocks: List<String>): String {
        val noise = listOf(
            "Ask Gemini", "Message ChatGPT", "Reply to Claude", "Gemini is AI",
            "ChatGPT can make mistakes", "Sponsored", "Trip.com", "Install",
            "Learn more", "Sign in", "New chat", "Search or type URL"
        )

        val validBlocks = blocks.filter { b ->
            noise.none { n -> b.contains(n, ignoreCase = true) }
        }

        // Skip short user question at the top if followed by long AI/article paragraphs
        var startIdx = 0
        for (i in validBlocks.indices.reversed()) {
            if (validBlocks[i].length < 150 && i < validBlocks.size - 1 && validBlocks[i + 1].length > 90) {
                startIdx = i + 1
                break
            }
        }

        val selected = validBlocks.subList(startIdx, validBlocks.size)
        return selected.joinToString(". ")
            .replace(Regex("[*#`_~•]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun appendSentences(text: String) {
        val newSentences = text.split(Regex("(?<=[.!?।\\n])\\s+")).filter { it.length > 3 }
        for (s in newSentences) {
            if (readHistorySet.add(s)) {
                sentencesList.add(s)
            }
        }
    }

    private fun tryAutoScrollAndContinue() {
        val rootNode = rootInActiveWindow ?: run {
            resetUIState()
            return
        }
        val scrollable = findScrollableNode(rootNode)
        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            mainHandler.postDelayed({
                val updatedRoot = rootInActiveWindow
                if (updatedRoot != null) {
                    val newBlocks = extractScreenBlocks(updatedRoot)
                    val newText = filterAndJoinBlocks(newBlocks)
                    val prevCount = sentencesList.size
                    fullCapturedText += " " + newText
                    appendSentences(newText)
                    if (sentencesList.size > prevCount) {
                        speakCurrentSentence()
                    } else {
                        resetUIState()
                    }
                } else {
                    resetUIState()
                }
            }, 900)
        } else {
            resetUIState()
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun speakCurrentSentence() {
        if (currentIndex in sentencesList.indices) {
            tts?.setSpeechRate(speechRate)
            val params = Bundle()
            tts?.speak(sentencesList[currentIndex], TextToSpeech.QUEUE_FLUSH, params, "UTT_$currentIndex")
        }
    }

    private fun jumpParagraph(delta: Int) {
        if (sentencesList.isEmpty()) return
        tts?.stop()
        currentIndex = (currentIndex + delta).coerceIn(0, sentencesList.size - 1)
        isPaused = false
        btnPlayPause.text = "⏸️"
        updateOrbColor("#E91E63")
        speakCurrentSentence()
    }

    private fun seekSeconds(seconds: Int) {
        // Approximate 1 sentence ≈ 4-5 seconds
        val sentenceStep = if (seconds > 0) 1 else -1
        jumpParagraph(sentenceStep)
    }

    private fun togglePauseResume() {
        if (isPaused) {
            isPaused = false
            btnPlayPause.text = "⏸️"
            updateOrbColor("#E91E63")
            speakCurrentSentence()
        } else {
            isPaused = true
            tts?.stop()
            btnPlayPause.text = "▶️"
            updateOrbColor("#FF9800")
        }
    }

    private fun cycleSpeed() {
        speechRate = when (speechRate) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        btnSpeed.text = "${speechRate}x"
        if (!isPaused && tts?.isSpeaking == true) {
            tts?.stop()
            speakCurrentSentence()
        }
    }

    private fun triggerAISimplify() {
        if (isSimplifiedMode) {
            isSimplifiedMode = false
            btnSimplify.text = "✨AI சுருக்கம்"
            sentencesList.clear()
            readHistorySet.clear()
            appendSentences(fullCapturedText)
            currentIndex = 0
            speakCurrentSentence()
            return
        }

        val prefs = getSharedPreferences("TamilReaderPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        tts?.stop()
        Toast.makeText(this, "AI சுருக்கமாகத் தயாரிக்கிறது...", Toast.LENGTH_SHORT).show()

        if (apiKey.isBlank()) {
            // Fallback smart local summary if no free Groq key is added yet
            isSimplifiedMode = true
            btnSimplify.text = "📄முழுமை"
            val shortList = sentencesList.filterIndexed { idx, _ -> idx == 0 || idx % 3 == 0 }.take(5)
            sentencesList = shortList.toMutableList()
            currentIndex = 0
            speakCurrentSentence()
            return
        }

        thread {
            try {
                val url = URL("https://api.groq.com/openai/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                val prompt = "கீழே உள்ள தகவலை ஒரு நண்பன் பக்கத்தில் இருந்து எளிமையாகவும் இயல்பாகவும் தமிழில் புரிய வைப்பது போல 4 அல்லது 5 சிறிய வாக்கியங்களில் சுருக்கிச் சொல் (Markdown குறியீடுகள் இல்லாமல்):\n\n${fullCapturedText.take(3500)}"

                val body = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val responseStr = conn.inputStream.bufferedReader().readText()
                val aiReply = JSONObject(responseStr)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .replace(Regex("[*#`_~]"), "")
                    .trim()

                mainHandler.post {
                    isSimplifiedMode = true
                    btnSimplify.text = "📄முழுமை"
                    sentencesList.clear()
                    readHistorySet.clear()
                    appendSentences(aiReply)
                    currentIndex = 0
                    isPaused = false
                    btnPlayPause.text = "⏸️"
                    speakCurrentSentence()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "AI பிழை: Key சரிபார்க்கவும்", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startVoiceCommand() {
        if (tts?.isSpeaking == true) togglePauseResume()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@VoiceReaderService, "சொல்லுங்கள் (நிறுத்து / சுருக்கு / அடுத்து / முந்தைய)...", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val cmd = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                when {
                    cmd.contains("நிறுத்து") || cmd.contains("stop") -> stopEverything()
                    cmd.contains("சுருக்கு") || cmd.contains("simplify") -> triggerAISimplify()
                    cmd.contains("அடுத்து") || cmd.contains("next") -> jumpParagraph(1)
                    cmd.contains("முந்தைய") || cmd.contains("back") -> jumpParagraph(-1)
                    cmd.contains("வேகம்") || cmd.contains("fast") -> cycleSpeed()
                    else -> if (isPaused) togglePauseResume()
                }
            }
            override fun onError(error: Int) { if (isPaused) togglePauseResume() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun stopEverything() {
        tts?.stop()
        resetUIState()
    }

    private fun resetUIState() {
        isPaused = false
        currentIndex = 0
        controlPanel.visibility = View.GONE
        updateOrbColor("#6200EE")
    }

    private fun updateOrbColor(hexColor: String) {
        (floatingOrb.background as? GradientDrawable)?.setColor(Color.parseColor(hexColor))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopEverything() }
    override fun onDestroy() {
        super.onDestroy()
        if (::rootContainer.isInitialized) windowManager.removeView(rootContainer)
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
