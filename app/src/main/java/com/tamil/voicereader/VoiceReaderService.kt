package com.tamil.voicereader

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import java.util.Locale

class VoiceReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var floatingOrb: ImageView
    private lateinit var controlBar: LinearLayout
    private lateinit var btnPlayPause: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnSimplify: TextView

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var sentencesList: List<String> = emptyList()
    private var fullExtractedText: String = ""
    private var currentSentenceIndex = 0
    private var isPaused = false
    private var isSimplifiedMode = false
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
                        currentSentenceIndex++
                        if (currentSentenceIndex < sentencesList.size) {
                            speakCurrentSentence()
                        } else {
                            rootContainer.post { resetUIState() }
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        floatingOrb = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
            }
        }

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                cornerRadius = 50f
                setColor(Color.parseColor("#E61E1E2E"))
            }
        }

        btnPlayPause = createControlButton("⏸️") { togglePauseResume() }
        val btnReplay = createControlButton("🔄") { replayFromStart() }
        btnSpeed = createControlButton("1.0x") { cycleSpeed() }
        btnSimplify = createControlButton("✨சுருக்கம்") { toggleSimplifyMode() }
        val btnVoiceCmd = createControlButton("🎙️") { startVoiceCommand() }
        val btnStop = createControlButton("❌") { stopEverything() }

        controlBar.addView(btnPlayPause)
        controlBar.addView(btnReplay)
        controlBar.addView(btnSpeed)
        controlBar.addView(btnSimplify)
        controlBar.addView(btnVoiceCmd)
        controlBar.addView(btnStop)

        val orbParams = LinearLayout.LayoutParams(140, 140)
        val barParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(16, 0, 0, 0) }

        rootContainer.addView(floatingOrb, orbParams)
        rootContainer.addView(controlBar, barParams)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 350
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

    private fun createControlButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(18, 12, 18, 12)
            setOnClickListener { onClick() }
        }
    }

    private fun onOrbClicked() {
        if (tts?.isSpeaking == true || isPaused) {
            controlBar.visibility = if (controlBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        extractAndStartReading()
    }

    private fun extractAndStartReading() {
        val rootNode = rootInActiveWindow ?: return
        val pkg = rootNode.packageName?.toString() ?: ""

        val rawBlocks = mutableListOf<String>()
        collectCleanBlocks(rootNode, rawBlocks)

        // Filter out system/app chrome strings
        val ignoredPhrases = listOf(
            "Ask Gemini", "Message ChatGPT", "Reply to Claude", "Gemini is AI",
            "ChatGPT can make mistakes", "Claude can make mistakes", "New chat",
            "Gemini Flash", "Gemini Pro", "GPT-4", "Sonnet"
        )

        val contentBlocks = rawBlocks.filter { block ->
            block.length > 15 && ignoredPhrases.none { ignore -> block.contains(ignore, ignoreCase = true) }
        }

        if (contentBlocks.isEmpty()) {
            Toast.makeText(this, "வாசிப்பதற்கு பதில் எதுவும் கிடைக்கவில்லை", Toast.LENGTH_SHORT).show()
            return
        }

        // Smart extraction: Skip the user's short prompt and capture the entire AI response block(s)
        val aiResponse = buildLatestResponse(contentBlocks, pkg)
        fullExtractedText = aiResponse
            .replace(Regex("[*#`_~•]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        isSimplifiedMode = false
        btnSimplify.text = "✨சுருக்கம்"
        prepareAndSpeak(fullExtractedText)
    }

    private fun buildLatestResponse(blocks: List<String>, pkg: String): String {
        // Find the last short block (likely the user's question) and take everything AFTER it
        var lastPromptIndex = -1
        for (i in blocks.indices.reversed()) {
            // Usually user prompts are shorter than 180 chars and followed by longer AI paragraphs
            if (blocks[i].length < 180 && i < blocks.size - 1 && blocks[i + 1].length > 80) {
                lastPromptIndex = i
                break
            }
        }

        val responseBlocks = if (lastPromptIndex != -1) {
            blocks.subList(lastPromptIndex + 1, blocks.size)
        } else {
            // If only answer is visible on screen (e.g., scrolled down in ChatGPT), take all visible content blocks
            blocks
        }

        return responseBlocks.joinToString(". ")
    }

    private fun prepareAndSpeak(text: String) {
        sentencesList = text.split(Regex("(?<=[.!?।\\n])\\s+")).filter { it.isNotBlank() }
        if (sentencesList.isEmpty()) return

        currentSentenceIndex = 0
        isPaused = false
        btnPlayPause.text = "⏸️"
        controlBar.visibility = View.VISIBLE
        updateOrbColor("#E91E63")
        speakCurrentSentence()
    }

    private fun speakCurrentSentence() {
        if (currentSentenceIndex in sentencesList.indices) {
            tts?.setSpeechRate(speechRate)
            val params = Bundle()
            tts?.speak(sentencesList[currentSentenceIndex], TextToSpeech.QUEUE_FLUSH, params, "SENTENCE_$currentSentenceIndex")
        }
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

    private fun replayFromStart() {
        tts?.stop()
        currentSentenceIndex = 0
        isPaused = false
        btnPlayPause.text = "⏸️"
        updateOrbColor("#E91E63")
        speakCurrentSentence()
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

    private fun toggleSimplifyMode() {
        isSimplifiedMode = !isSimplifiedMode
        tts?.stop()
        if (isSimplifiedMode) {
            btnSimplify.text = "📄முழுமை"
            // Extractive summarization: pick first sentence, key numbered/bullet lines, and conclusion
            val allSentences = fullExtractedText.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 20 }
            val summary = if (allSentences.size <= 4) {
                fullExtractedText
            } else {
                val picked = mutableListOf<String>()
                picked.add(allSentences.first())
                allSentences.drop(1).dropLast(1).forEachIndexed { idx, s ->
                    if (idx % 3 == 0 && picked.size < 5) picked.add(s)
                }
                picked.add(allSentences.last())
                "சுருக்கமாக: " + picked.joinToString(". ")
            }
            prepareAndSpeak(summary)
        } else {
            btnSimplify.text = "✨சுருக்கம்"
            prepareAndSpeak(fullExtractedText)
        }
    }

    private fun startVoiceCommand() {
        // Pause current reading while listening to user's voice command
        if (tts?.isSpeaking == true) {
            togglePauseResume()
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition கிடைக்கவில்லை", Toast.LENGTH_SHORT).show()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@VoiceReaderService, "சொல்லுங்கள் (நிறுத்து / சுருக்கு / மீண்டும் / வேகம்)...", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val cmd = matches?.firstOrNull()?.lowercase() ?: ""
                handleVoiceCommand(cmd)
            }
            override fun onError(error: Int) {
                Toast.makeText(this@VoiceReaderService, "புரியவில்லை, மீண்டும் முயற்சிக்கவும்", Toast.LENGTH_SHORT).show()
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

    private fun handleVoiceCommand(cmd: String) {
        when {
            cmd.contains("நிறுத்து") || cmd.contains("stop") || cmd.contains("போதும்") -> stopEverything()
            cmd.contains("மீண்டும்") || cmd.contains("replay") || cmd.contains("முதல்ல") -> replayFromStart()
            cmd.contains("சுருக்கு") || cmd.contains("சுருக்கி") || cmd.contains("short") || cmd.contains("simplify") -> {
                if (!isSimplifiedMode) toggleSimplifyMode() else replayFromStart()
            }
            cmd.contains("முழு") || cmd.contains("full") -> {
                if (isSimplifiedMode) toggleSimplifyMode()
            }
            cmd.contains("வேகம்") || cmd.contains("வேகமா") || cmd.contains("fast") -> cycleSpeed()
            cmd.contains("தொடர்") || cmd.contains("படி") || cmd.contains("resume") -> {
                if (isPaused) togglePauseResume()
            }
            else -> {
                Toast.makeText(this, "கட்டளை: $cmd", Toast.LENGTH_SHORT).show()
                if (isPaused) togglePauseResume()
            }
        }
    }

    private fun stopEverything() {
        tts?.stop()
        resetUIState()
    }

    private fun resetUIState() {
        isPaused = false
        currentSentenceIndex = 0
        controlBar.visibility = View.GONE
        updateOrbColor("#6200EE")
    }

    private fun collectCleanBlocks(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrBlank()) {
            list.add(txt)
        }
        for (i in 0 until node.childCount) {
            collectCleanBlocks(node.getChild(i), list)
        }
    }

    private fun updateOrbColor(hexColor: String) {
        (floatingOrb.background as? GradientDrawable)?.setColor(Color.parseColor(hexColor))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopEverything()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::rootContainer.isInitialized) windowManager.removeView(rootContainer)
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
