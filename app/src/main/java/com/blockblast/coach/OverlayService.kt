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
import android.util.Log
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

    private val TAG = "BBCoach"
    private lateinit var wm: WindowManager
    private var fab: View? = null
    private var panel: LinearLayout? = null
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var apiKey = ""
    private val ui = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val projCb = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped")
            ui.post { teardownProjection() }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(99, buildNotif())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("result_code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        val key  = intent?.getStringExtra("api_key") ?: ""

        if (key.isNotEmpty()) apiKey = key
        if (code != -1 && data != null) setupProjection(code, data)
        if (fab == null) addFab()

        return START_STICKY
    }

    // ── Projection ─────────────────────────────────────────────────────────

    private fun setupProjection(code: Int, data: Intent) {
        teardownProjection()
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(projCb, ui)

        val dm  = resources.displayMetrics
        val w   = dm.widthPixels
        val h   = dm.heightPixels
        val dpi = dm.densityDpi

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vDisplay = projection?.createVirtualDisplay(
            "BBCoach", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null
        )
        Log.i(TAG, "VirtualDisplay created ${w}x${h}")
    }

    private fun teardownProjection() {
        try { vDisplay?.release() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projCb) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        vDisplay = null; projection = null; reader = null
    }

    // ── FAB ────────────────────────────────────────────────────────────────

    private fun addFab() {
        val btn = TextView(this).apply {
            text = "🎯"; textSize = 27f; gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0xFF1E40AF.toInt())
            bg.setStroke(5, Color.WHITE)
            background = bg; elevation = 20f
        }

        val lp = WindowManager.LayoutParams(
            130, 130, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 360 }

        var ox=0f; var oy=0f; var lpx=0; var lpy=0; var moved=false

        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox=e.rawX; oy=e.rawY; lpx=lp.x; lpy=lp.y; moved=false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX-ox)>12 || Math.abs(e.rawY-oy)>12) {
                        moved=true
                        lp.x=(lpx+(e.rawX-ox)).toInt()
                        lp.y=(lpy+(e.rawY-oy)).toInt()
                        wm.updateViewLayout(btn, lp)
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap(); true }
                else -> false
            }
        }
        fab = btn
        wm.addView(btn, lp)
    }

    private fun onTap() {
        if (reader == null) {
            showPanel("⚠️ Captura não iniciada.\n\nDesinstale, reinstale o app e confirme TODAS as permissões.", emptyList(), false)
            return
        }
        showPanel("", emptyList(), loading = true)
        // Pequeno delay pra UI do jogo não estar coberta pelo painel
        ui.postDelayed({ captureLoop() }, 800)
    }

    // ── Captura ─────────────────────────────────────────────────────────────

    private fun captureLoop() {
        Thread {
            var bmp: Bitmap? = null
            for (i in 1..8) {
                bmp = grabFrame()
                if (bmp != null) break
                Log.d(TAG, "Tentativa $i falhou, aguardando...")
                Thread.sleep(400)
            }
            if (bmp == null) {
                ui.post {
                    showPanel(
                        "❌ Falha na captura de tela.\n\n" +
                        "Tente:\n" +
                        "1. Fechar o app Coach\n" +
                        "2. Abrir de novo\n" +
                        "3. Aceitar a permissão de gravação",
                        emptyList(), false
                    )
                }
                return@Thread
            }
            val b64 = encode(bmp)
            callAI(b64)
        }.start()
    }

    private fun grabFrame(): Bitmap? {
        return try {
            val img = reader?.acquireLatestImage() ?: return null
            val p   = img.planes[0]
            val pad = p.rowStride - p.pixelStride * img.width
            val tmp = Bitmap.createBitmap(
                img.width + pad / p.pixelStride,
                img.height, Bitmap.Config.ARGB_8888
            )
            tmp.copyPixelsFromBuffer(p.buffer)
            img.close()
            val out = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
            Log.i(TAG, "Frame capturado ${img.width}x${img.height}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "grabFrame: ${e.message}")
            null
        }
    }

    private fun encode(bmp: Bitmap): String {
        val scale  = 720f / bmp.width
        val scaled = Bitmap.createScaledBitmap(bmp, 720, (bmp.height * scale).toInt(), true)
        val buf    = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, buf)
        return Base64.getEncoder().encodeToString(buf.toByteArray())
    }

    // ── Claude API ──────────────────────────────────────────────────────────

    private fun callAI(b64: String) {
        val prompt = """Analise este screenshot do Block Blast. Retorne SOMENTE JSON válido, sem texto extra:
{
  "moves": [
    {"peca": 1, "linha": 5, "coluna": 3, "motivo": "Completa linha 5"},
    {"peca": 2, "linha": 2, "coluna": 6, "motivo": "Prepara combo"},
    {"peca": 3, "linha": 7, "coluna": 1, "motivo": "Fecha canto"}
  ],
  "dica": "Dica estratégica curta em português",
  "game_over": false
}
Tabuleiro 8x8. Linha/coluna de 1(topo/esquerda) a 8. Priorize completar linhas e colunas inteiras. Pense 3 jogadas à frente."""

        val reqBody = JSONObject().apply {
            put("model", "claude-opus-4-5")
            put("max_tokens", 450)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", b64)
                        })
                    })
                    put(JSONObject().apply { put("type","text"); put("text", prompt) })
                })
            }))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(reqBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                ui.post { showPanel("❌ API erro ${resp.code}", emptyList(), false) }
                return
            }
            val txt = JSONObject(body).getJSONArray("content")
                .getJSONObject(0).getString("text")
                .replace("```json","").replace("```","").trim()
            val json  = JSONObject(txt.substring(txt.indexOf('{')))
            val moves = json.optJSONArray("moves") ?: JSONArray()
            val dica  = json.optString("dica","")
            val over  = json.optBoolean("game_over", false)

            val lines = mutableListOf<String>()
            if (dica.isNotEmpty()) { lines += "💡 $dica"; lines += "" }
            for (i in 0 until moves.length()) {
                val m    = moves.getJSONObject(i)
                val ico  = when(i){0->"🔵";1->"🟣";else->"🔹"}
                val step = when(i){0->"AGORA";1->"DEPOIS";else->"ENTÃO"}
                lines += "$ico $step — Peça ${m.getInt("peca")}"
                lines += "   Linha ${m.getInt("linha")}, Coluna ${m.getInt("coluna")}"
                lines += "   ${m.getString("motivo")}"
                if (i < moves.length()-1) lines += ""
            }
            ui.post { showPanel(if(over) "💀 Game Over!" else "", lines, false) }
        } catch (e: Exception) {
            Log.e(TAG, "callAI: ${e.message}")
            ui.post { showPanel("❌ ${e.message?.take(90)}", emptyList(), false) }
        }
    }

    // ── Painel ──────────────────────────────────────────────────────────────

    private fun showPanel(header: String, lines: List<String>, loading: Boolean) {
        ui.post {
            panel?.let { try { wm.removeView(it) } catch (_:Exception){} }
            panel = null

            val pw = (resources.displayMetrics.widthPixels * 0.91f).toInt()

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable()
                bg.setColor(Color.WHITE); bg.cornerRadius = 52f
                bg.setStroke(2, 0xFFDDE1E7.toInt())
                background = bg; elevation = 28f
                setPadding(44, 26, 44, 44)
            }

            // Topo: título + fechar
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = if (loading) "🧠 Analisando..." else "🎯 Jogada recomendada"
                textSize = 14f; setTextColor(0xFF1E40AF.toInt())
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this).apply {
                text = "✕"; textSize = 20f; setTextColor(0xFFAAAAAA.toInt()); setPadding(24,0,0,0)
                setOnClickListener {
                    try { wm.removeView(root) } catch (_:Exception){}
                    panel = null
                }
            })
            root.addView(top)

            // Divisor
            root.addView(View(this).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { setMargins(0,18,0,18) }
            })

            if (loading) {
                root.addView(TextView(this).apply {
                    text = "Perguntando à IA Claude..."; textSize = 14f
                    setTextColor(0xFF666666.toInt()); gravity = Gravity.CENTER; setPadding(0,10,0,10)
                })
            } else {
                if (header.isNotEmpty()) root.addView(TextView(this).apply {
                    text = header; textSize = 15f; setTextColor(0xFFDC2626.toInt())
                    typeface = Typeface.DEFAULT_BOLD; setPadding(0,0,0,12)
                })
                lines.forEach { line ->
                    root.addView(TextView(this).apply {
                        text = line
                        textSize = if (line.startsWith("🔵")||line.startsWith("🟣")||line.startsWith("🔹")) 15f else 13f
                        setTextColor(when {
                            line.startsWith("🔵")||line.startsWith("🟣")||line.startsWith("🔹") -> 0xFF1E40AF.toInt()
                            line.startsWith("💡") -> 0xFF059669.toInt()
                            line.startsWith("   Linha")||line.startsWith("   Coluna") -> 0xFF111827.toInt()
                            line.startsWith("   ") -> 0xFF6B7280.toInt()
                            else -> 0xFF111827.toInt()
                        })
                        if (line.startsWith("🔵")||line.startsWith("🟣")||line.startsWith("🔹"))
                            typeface = Typeface.DEFAULT_BOLD
                        setPadding(0,2,0,2)
                    })
                }
            }

            val lp = WindowManager.LayoutParams(pw, WRAP_CONTENT,
                TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = 55
            panel = root; wm.addView(root, lp)
        }
    }

    // ── Util ────────────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel("coach","BB Coach", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif() = NotificationCompat.Builder(this,"coach")
        .setContentTitle("Block Blast Coach 🎯")
        .setContentText("Toque no botão para analisar")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setPriority(NotificationCompat.PRIORITY_LOW).build()

    override fun onDestroy() {
        super.onDestroy()
        fab?.let { try{wm.removeView(it)}catch(_:Exception){} }
        panel?.let { try{wm.removeView(it)}catch(_:Exception){} }
        teardownProjection()
    }
}
