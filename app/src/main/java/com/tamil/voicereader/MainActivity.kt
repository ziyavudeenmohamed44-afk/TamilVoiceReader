package com.tamil.voicereader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("TamilReaderPrefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 70, 56, 56)
        }

        val title = TextView(this).apply {
            text = "Smart AI Companion v5.0\n(Tamil + English Live Assistant)"
            textSize = 19f
            setPadding(0, 0, 0, 28)
        }

        val btnMic = Button(this).apply {
            text = "1. Allow Microphone (குரல் உரையாடலுக்கு)"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                } else {
                    Toast.makeText(this@MainActivity, "Microphone ஏற்கனவே ஆன் செய்யப்பட்டுள்ளது!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnOverlay = Button(this).apply {
            text = "2. Enable Floating Orb (Overlay)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Overlay ஏற்கனவே ஆன் செய்யப்பட்டுள்ளது!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "3. Enable Screen Vision (Accessibility)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val aiLabel = TextView(this).apply {
            text = "\nFree AI Brain Key (Groq API Key):\n(இதைப் போட்டால் தான் உண்மையான AI போலப் பேசும்)"
            textSize = 14f
            setPadding(0, 32, 0, 12)
        }

        val etApiKey = EditText(this).apply {
            hint = "gsk_xxxxxxxxxxxxxxxxxxxx"
            setText(prefs.getString("groq_api_key", ""))
        }

        val btnSaveKey = Button(this).apply {
            text = "Save AI Key"
            setOnClickListener {
                prefs.edit().putString("groq_api_key", etApiKey.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "AI Key சேமிக்கப்பட்டது!", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(btnMic)
        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)
        layout.addView(aiLabel)
        layout.addView(etApiKey)
        layout.addView(btnSaveKey)
        setContentView(layout)
    }
}
