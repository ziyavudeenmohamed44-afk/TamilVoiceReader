package com.tamil.voicereader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }

        val title = TextView(this).apply {
            text = "Tamil AI Voice Companion\n\nகீழே உள்ள இரண்டு அனுமதிகளையும் (Permissions) ஆன் செய்யவும்:"
            textSize = 18f
            setPadding(0, 0, 0, 50)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Enable Floating Button (Overlay)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Overlay ஏற்கனவே ஆன் செய்யப்பட்டுள்ளது!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "2. Enable Screen Reader (Accessibility)"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this@MainActivity, "Installed Apps -> Tamil Voice Reader என்பதை ஆன் செய்யவும்", Toast.LENGTH_LONG).show()
            }
        }

        layout.addView(title)
        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)
        setContentView(layout)
    }
}
