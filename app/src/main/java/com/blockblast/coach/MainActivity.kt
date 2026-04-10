package com.blockblast.coach

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQ_OVERLAY = 1001
    private val REQ_PROJECTION = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        val title = TextView(this).apply {
            text = "🎮 Block Blast Coach"
            textSize = 22f
            setPadding(0, 0, 0, 8)
        }

        val sub = TextView(this).apply {
            text = "IA que te guia em tempo real"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 32)
        }

        val keyLabel = TextView(this).apply {
            text = "Sua API Key Anthropic:"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }

        val keyInput = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        android.text.InputType.TYPE_CLASS_TEXT
            setPadding(0, 0, 0, 32)
        }

        // Carrega key salva
        val prefs = getSharedPreferences("coach", MODE_PRIVATE)
        keyInput.setText(prefs.getString("api_key", ""))

        val startBtn = Button(this).apply {
            text = "▶  Iniciar Coach"
            textSize = 16f
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Cole sua API Key primeiro", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("api_key", key).apply()
                checkOverlayAndStart()
            }
        }

        layout.addView(title)
        layout.addView(sub)
        layout.addView(keyLabel)
        layout.addView(keyInput)
        layout.addView(startBtn)
        setContentView(layout)
    }

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQ_OVERLAY)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestScreenCapture()
                else Toast.makeText(this, "Permissão necessária", Toast.LENGTH_LONG).show()
            }
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val prefs = getSharedPreferences("coach", MODE_PRIVATE)
                    val key = prefs.getString("api_key", "") ?: ""
                    val intent = Intent(this, OverlayService::class.java).apply {
                        putExtra("result_code", resultCode)
                        putExtra("data", data)
                        putExtra("api_key", key)
                    }
                    startForegroundService(intent)
                    finish()
                }
            }
        }
    }
}
