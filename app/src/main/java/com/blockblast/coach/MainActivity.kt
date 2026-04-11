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

    companion object {
        const val REQ_OVERLAY    = 1001
        const val REQ_PROJECTION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("coach", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "🎮 Block Blast Coach"
            textSize = 22f; setPadding(0, 0, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "Android 15 — POCO"
            textSize = 13f; setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, 32)
        })
        layout.addView(TextView(this).apply {
            text = "API Key Anthropic:"; textSize = 14f; setPadding(0, 0, 0, 8)
        })

        val keyInput = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("api_key", ""))
            setPadding(16, 12, 16, 32)
        }
        layout.addView(keyInput)

        layout.addView(Button(this).apply {
            text = "▶  Iniciar Coach"
            textSize = 16f
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Cole sua API Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("api_key", key).apply()
                checkPermissions()
            }
        })

        setContentView(layout)
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        } else {
            askProjection()
        }
    }

    private fun askProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) askProjection()
                else Toast.makeText(this, "Permissão overlay necessária", Toast.LENGTH_LONG).show()
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val key = getSharedPreferences("coach", MODE_PRIVATE)
                        .getString("api_key", "") ?: ""

                    // No Android 15: inicia o service passando o token imediatamente
                    startForegroundService(Intent(this, OverlayService::class.java).apply {
                        putExtra("result_code", resultCode)
                        putExtra("data", data)
                        putExtra("api_key", key)
                    })

                    Toast.makeText(this, "🎯 Coach ativo! Abra o Block Blast", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Permissão de captura negada", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
