package com.tamil.voicereader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("TamilReaderPrefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
        }

        val title = TextView(this).apply {
            text = "Tamil AI Voice Companion v3.0\n\n1. கீழே உள்ள அனுமதிகளை ஆன் செய்யவும்:"
            textSize = 18f
            setPadding(0, 0, 0, 30)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Enable Floating Button (Overlay)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Overlay ஏற்கனவே ஆன் செய்யப்பட்டுள்ளது!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "2. Enable Screen Reader (Accessibility)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val aiLabel = TextView(this).apply {
            text = "\n2. இலவச AI சுருக்கம் (Free Groq API Key):\n(console.groq.com-ல் இலவசமாக எடுத்து கீழே பேஸ்ட் செய்யவும்)"
            textSize = 14f
            setPadding(0, 40, 0, 15)
        }

        val etApiKey = EditText(this).apply {
            hint = "gsk_xxxxxxxxxxxxxxxxxxxx"
            setText(prefs.getString("groq_api_key", ""))
        }

        val btnSaveKey = Button(this).apply {
            text = "Save Free AI Key"
            setOnClickListener {
                prefs.edit().putString("groq_api_key", etApiKey.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "AI Key சேமிக்கப்பட்டது!", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)
        layout.addView(aiLabel)
        layout.addView(etApiKey)
        layout.addView(btnSaveKey)
        setContentView(layout)
    }
}
