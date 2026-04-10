package com.blockblast.coach

import android.app.*
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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }
    }

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
            mediaProjection?.registerCallback(projectionCallback, handler)
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

        imageReader?.close()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BBCoach", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun showFab() {
        val fab = TextView(this).apply {
            text = "🎯"
            textSize = 26f
            gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0xFF1D4ED8.toInt())
            bg.setStroke(4, Color.WHITE)
            background = bg
            setPadding(10, 10, 10, 10)
            elevation = 16f
        }

        val params = WindowManager.LayoutParams(
            130, 130,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 300

        var startRawX = 0f; var startRawY = 0f
        var startPX = 0; var startPY = 0
        var moved = false

        fab.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    startPX = params.x; startPY = params.y
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true
                        params.x = (startPX + dx).toInt()
                        params.y = (startPY + dy).toInt()
                        wm.updateViewLayout(fab, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) captureAndAnalyze()
                    true
                }
                else -> false
            }
        }

        fabView = fab
        wm.addView(fab, params)
    }

    private fun captureAndAnalyze() {
        showResult("", emptyList(), true)
        handler.postDelayed({
            var bmp: Bitmap? = null
            for (i in 1..5) {
                bmp = captureScreen()
                if (bmp != null) break
                Thread.sleep(300)
            }
            if (bmp == null) {
                showResult("❌ Não conseguiu capturar.\nFeche e abra o Coach novamente.", emptyList(), false)
                return@postDelayed
            }
            callClaude(bitmapToBase64(bmp))
        }, 600)
    }

    private fun captureScreen(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
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
            image.close()
            Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        } catch (_: Exception) { null }
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val scale = 720f / bmp.width
        val scaled = Bitmap.createScaledBitmap(bmp, 720, (bmp.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
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
  "dica": "Dica curta em português",
  "game_over": false
}
Tabuleiro 8x8. Linha/coluna 1-8 (1=topo/esquerda). Priorize completar linhas inteiras."""

        val body = JSONObject().apply {
            put("model", "claude-opus-4-5")
            put("max_tokens", 500)
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
                    put(JSONObject().apply { put("type", "text"); put("text", prompt) })
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
                if (!resp.isSuccessful) {
                    handler.post { showResult("❌ API: ${resp.code}", emptyList(), false) }
                    return@Thread
                }
                val text = JSONObject(raw).getJSONArray("content")
                    .getJSONObject(0).getString("text")
                    .replace("```json","").replace("```","").trim()
                val json = JSONObject(text.substring(text.indexOf('{')))
                val moves = json.optJSONArray("moves") ?: JSONArray()
                val dica = json.optString("dica","")
                val gameOver = json.optBoolean("game_over", false)

                val lines = mutableListOf<String>()
                if (dica.isNotEmpty()) { lines.add("💡 $dica"); lines.add("") }
                for (i in 0 until moves.length()) {
                    val m = moves.getJSONObject(i)
                    val emoji = when(i){0->"🔵";1->"🟣";else->"🔹"}
                    val step = when(i){0->"AGORA";1->"DEPOIS";else->"ENTÃO"}
                    lines.add("$emoji $step — Peça ${m.getInt("peca")}")
                    lines.add("   Linha ${m.getInt("linha")}, Coluna ${m.getInt("coluna")}")
                    lines.add("   ${m.getString("motivo")}")
                    if (i < moves.length()-1) lines.add("")
                }
                handler.post { showResult(if(gameOver)"💀 Game Over!" else "", lines, false) }
            } catch (e: Exception) {
                handler.post { showResult("❌ ${e.message?.take(80)}", emptyList(), false) }
            }
        }.start()
    }

    private fun showResult(header: String, lines: List<String>, loading: Boolean) {
        resultLayout?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        resultLayout = null

        val panelW = (resources.displayMetrics.widthPixels * 0.90f).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.setColor(Color.WHITE)
            bg.cornerRadius = 52f
            bg.setStroke(2, 0xFFDDE3EA.toInt())
            background = bg
            elevation = 24f
            setPadding(44, 28, 44, 44)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = if (loading) "🧠 Analisando..." else "🎯 Jogada recomendada"
            textSize = 14f
            setTextColor(0xFF1D4ED8.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(24, 0, 0, 0)
            setOnClickListener {
                try { wm.removeView(layout) } catch (_: Exception) {}
                resultLayout = null
            }
        })
        layout.addView(topRow)

        layout.addView(View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { setMargins(0,16,0,16) }
        })

        if (loading) {
            layout.addView(TextView(this).apply {
                text = "Perguntando à IA..."; textSize = 14f
                setTextColor(0xFF666666.toInt()); gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
            })
        } else {
            if (header.isNotEmpty()) layout.addView(TextView(this).apply {
                text = header; textSize = 16f
                setTextColor(0xFFDC2626.toInt()); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 12)
            })
            lines.forEach { line ->
                layout.addView(TextView(this).apply {
                    text = line
                    textSize = if (line.startsWith("🔵")||line.startsWith("🟣")||line.startsWith("🔹")) 15f else 13f
                    setTextColor(when {
                        line.startsWith("🔵")||line.startsWith("🟣")||line.startsWith("🔹") -> 0xFF1D4ED8.toInt()
                        line.startsWith("💡") -> 0xFF059669.toInt()
                        line.startsWith("   Linha")||line.startsWith("   Coluna") -> 0xFF111827.toInt()
                        line.startsWith("   ") -> 0xFF6B7280.toInt()
                        else -> 0xFF111827.toInt()
                    })
                    if (line.startsWith("🔵")||line.startsWith("🟣")||line.startsWith("🔹"))
                        typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 2, 0, 2)
                })
            }
        }

        val params = WindowManager.LayoutParams(panelW, WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 60
        resultLayout = layout
        wm.addView(layout, params)
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel("coach", "BB Coach", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "coach")
        .setContentTitle("Block Blast Coach 🎯")
        .setContentText("Toque no botão flutuante para analisar")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(fabView) } catch (_: Exception) {}
        resultLayout?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        mediaProjection?.unregisterCallback(projectionCallback)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }
}
