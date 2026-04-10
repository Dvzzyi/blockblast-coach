package com.blockblast.coach

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var fabView: View
    private var resultLayout: LinearLayout? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var apiKey: String
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiKey = intent?.getStringExtra("api_key") ?: ""
        val resultCode = intent?.getIntExtra("result_code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            setupImageReader()
        }

        showFab()
        return START_STICKY
    }

    private fun setupImageReader() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val density = dm.densityDpi
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "coach", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun showFab() {
        val fab = TextView(this).apply {
            text = "🎯"
            textSize = 28f
            gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0xFF1D4ED8.toInt())
            bg.setStroke(3, Color.WHITE)
            background = bg
            setPadding(8, 8, 8, 8)
            elevation = 12f
        }

        val params = WindowManager.LayoutParams(
            120, 120,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 300

        fab.setOnClickListener { captureAndAnalyze() }

        var startX = 0f
        var startY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        fab.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    startParamX = params.x
                    startParamY = params.y
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                        params.x = (startParamX + dx).toInt()
                        params.y = (startParamY + dy).toInt()
                        wm.updateViewLayout(fab, params)
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) fab.performClick()
                    false
                }
                else -> false
            }
        }

        fabView = fab
        wm.addView(fab, params)
    }

    private fun captureAndAnalyze() {
        showResult("⏳ Analisando...", emptyList(), true)
        handler.postDelayed({
            val bmp = captureScreen() ?: run {
                showResult("❌ Erro ao capturar tela", emptyList(), false)
                return@postDelayed
            }
            val b64 = bitmapToBase64(bmp)
            callClaude(b64)
        }, 300)
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        } finally {
            image.close()
        }
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bmp, 720, 1280, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun callClaude(imageB64: String) {
        val prompt = """Analise este screenshot do Block Blast. Retorne SOMENTE JSON válido:
{
  "moves": [
    {"peca": 1, "linha": 5, "coluna": 3, "motivo": "Completa linha do fundo"},
    {"peca": 2, "linha": 2, "coluna": 6, "motivo": "Prepara próxima jogada"},
    {"peca": 3, "linha": 0, "coluna": 1, "motivo": "Limpa canto esquerdo"}
  ],
  "dica": "Frase curta de estratégia em português",
  "game_over": false
}
Linhas e colunas são índices 1 a 8. Priorize completar linhas e colunas inteiras."""

        val body = JSONObject().apply {
            put("model", "claude-opus-4-5")
            put("max_tokens", 600)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", imageB64)
                        })
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                })
            }))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Thread {
            try {
                val resp = http.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                val text = JSONObject(raw)
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text")
                    .replace("```json", "").replace("```", "").trim()

                val json = JSONObject(text.substring(text.indexOf('{')))
                val moves = json.getJSONArray("moves")
                val dica = json.optString("dica", "")
                val gameOver = json.optBoolean("game_over", false)

                val lines = mutableListOf<String>()
                if (dica.isNotEmpty()) lines.add("💡 $dica")
                lines.add("")

                for (i in 0 until moves.length()) {
                    val m = moves.getJSONObject(i)
                    val emoji = when (i) { 0 -> "🔵"; 1 -> "🟣"; else -> "🔹" }
                    val step = when (i) { 0 -> "AGORA"; 1 -> "DEPOIS"; else -> "ENTÃO" }
                    lines.add("$emoji $step — Peça ${m.getInt("peca")}")
                    lines.add("   Linha ${m.getInt("linha")}, Col ${m.getInt("coluna")}")
                    lines.add("   ${m.getString("motivo")}")
                    if (i < moves.length() - 1) lines.add("")
                }

                handler.post {
                    showResult(if (gameOver) "💀 Game Over!" else "", lines, false)
                }
            } catch (e: Exception) {
                handler.post {
                    showResult("❌ ${e.message?.take(80)}", emptyList(), false)
                }
            }
        }.start()
    }

    private fun showResult(header: String, lines: List<String>, loading: Boolean) {
        resultLayout?.let { try { wm.removeView(it) } catch (_: Exception) {} }

        val dm = resources.displayMetrics
        val panelW = (dm.widthPixels * 0.88f).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.setColor(Color.WHITE)
            bg.cornerRadius = 48f
            bg.setStroke(2, 0xFFE2E8F0.toInt())
            background = bg
            elevation = 20f
            setPadding(40, 32, 40, 40)
        }

        // Botão fechar
        val closeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(0xFF888888.toInt())
            setPadding(24, 0, 0, 20)
            setOnClickListener {
                try { wm.removeView(layout) } catch (_: Exception) {}
                resultLayout = null
            }
        }
        closeRow.addView(closeBtn)
        layout.addView(closeRow)

        if (loading) {
            layout.addView(TextView(this).apply {
                text = "⏳ Analisando com IA..."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 24)
            })
        } else {
            if (header.isNotEmpty()) {
                layout.addView(TextView(this).apply {
                    text = header
                    textSize = 17f
                    setTextColor(0xFF1D4ED8.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 16)
                })
            }
            lines.forEach { line ->
                layout.addView(TextView(this).apply {
                    text = line
                    textSize = when {
                        line.startsWith("🔵") || line.startsWith("🟣") || line.startsWith("🔹") -> 15f
                        else -> 13f
                    }
                    setTextColor(when {
                        line.startsWith("🔵") || line.startsWith("🟣") || line.startsWith("🔹") -> 0xFF1D4ED8.toInt()
                        line.startsWith("💡") -> 0xFF059669.toInt()
                        line.startsWith("   Col") || line.startsWith("   Linha") -> 0xFF374151.toInt()
                        line.startsWith("   ") -> 0xFF6B7280.toInt()
                        else -> 0xFF111827.toInt()
                    })
                    if (line.startsWith("🔵") || line.startsWith("🟣") || line.startsWith("🔹"))
                        typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 3, 0, 3)
                })
            }
        }

        val params = WindowManager.LayoutParams(
            panelW, WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 80

        resultLayout = layout
        wm.addView(layout, params)
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel("coach", "BB Coach", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "coach")
            .setContentTitle("Block Blast Coach ativo")
            .setContentText("Toque no 🎯 para analisar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(fabView) } catch (_: Exception) {}
        resultLayout?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
