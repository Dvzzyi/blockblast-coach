package com.blockblast.coach

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQ_OVERLAY    = 1001
    private val REQ_PROJECTION = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "🎮 Block Blast Coach"
            textSize = 22f
            setPadding(0, 0, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "IA que te guia em tempo real"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 32)
        })
        layout.addView(TextView(this).apply {
            text = "Sua API Key Anthropic:"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })

        val prefs = getSharedPreferences("coach", MODE_PRIVATE)

        val keyInput = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("api_key", ""))
            setPadding(0, 0, 0, 32)
        }
        layout.addView(keyInput)

        layout.addView(Button(this).apply {
            text = "▶  Iniciar Coach"
            textSize = 16f
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Cole sua API Key primeiro", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("api_key", key).apply()
                checkOverlay()
            }
        })

        setContentView(layout)
    }

    private fun checkOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestProjection()
                else Toast.makeText(this, "Permissão necessária", Toast.LENGTH_LONG).show()
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val prefs = getSharedPreferences("coach", MODE_PRIVATE)
                    val key   = prefs.getString("api_key", "") ?: ""

                    // Passa token de projeção para o service
                    startForegroundService(Intent(this, OverlayService::class.java).apply {
                        putExtra("result_code", resultCode)
                        putExtra("data", data)
                        putExtra("api_key", key)
                    })

                    Toast.makeText(this, "Coach ativo! Abra o Block Blast 🎯", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Permissão de captura negada", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
