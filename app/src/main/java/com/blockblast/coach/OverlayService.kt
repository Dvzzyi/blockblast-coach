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
    private var fabView: View? = null
    private var resultLayout: LinearLayout? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var apiKey: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Android 14+ exige este callback registrado ANTES de criar o VirtualDisplay
    private val mpCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                virtualDisplay?.release(); virtualDisplay = null
                imageReader?.close();     imageReader = null
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(42, buildNotif())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiKey = intent?.getStringExtra("api_key") ?: apiKey
        val code = intent?.getIntExtra("result_code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (code != -1 && data != null) {
            initProjection(code, data)
        }

        if (fabView == null) showFab()
        return START_STICKY
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        // Libera projeção anterior se existir
        try {
            virtualDisplay?.release()
            mediaProjection?.unregisterCallback(mpCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        // OBRIGATÓRIO no Android 14: registrar callback antes de criar VirtualDisplay
        mediaProjection?.registerCallback(mpCallback, mainHandler)

        setupReader()
    }

    private fun setupReader() {
        val dm   = resources.displayMetrics
        val w    = dm.widthPixels
        val h    = dm.heightPixels
        val dpi  = dm.densityDpi

        imageReader?.close()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BBCoach", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    // ── FAB ────────────────────────────────────────────────────────────────

    private fun showFab() {
        val size = 128

        val tv = TextView(this).apply {
            text = "🎯"; textSize = 27f; gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0xFF1D4ED8.toInt())
            bg.setStroke(5, Color.WHITE)
            background = bg
            elevation = 16f
        }

        val lp = WindowManager.LayoutParams(
            size, size, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.TOP or Gravity.END; it.x = 20; it.y = 350 }

        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var dragged = false

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN  -> { sx=e.rawX; sy=e.rawY; px=lp.x; py=lp.y; dragged=false; true }
                MotionEvent.ACTION_MOVE  -> {
                    if (Math.abs(e.rawX-sx)>10 || Math.abs(e.rawY-sy)>10) {
                        dragged=true; lp.x=(px+(e.rawX-sx)).toInt(); lp.y=(py+(e.rawY-sy)).toInt()
                        wm.updateViewLayout(tv, lp)
                    }; true
                }
                MotionEvent.ACTION_UP    -> { if (!dragged) onFabClick(); true }
                else -> false
            }
        }

        fabView = tv
        wm.addView(tv, lp)
    }

    private fun onFabClick() {
        if (imageReader == null) {
            toast("Captura não iniciada. Feche e abra o app novamente.")
            return
        }
        showPanel("", emptyList(), loading = true)
        mainHandler.postDelayed({ doCapture() }, 700)
    }

    // ── CAPTURA ─────────────────────────────────────────────────────────────

    private fun doCapture() {
        var bmp: Bitmap? = null
        repeat(6) {
            if (bmp == null) {
                bmp = tryCapture()
                if (bmp == null) Thread.sleep(350)
            }
        }
        if (bmp == null) {
            showPanel("❌ Não conseguiu capturar a tela.\n\nFeche o app Coach e abra novamente, confirmando a permissão de gravação.", emptyList(), false)
            return
        }
        sendToAI(toBase64(bmp!!))
    }

    private fun tryCapture(): Bitmap? {
        return try {
            val img = imageReader?.acquireLatestImage() ?: return null
            val p   = img.planes[0]
            val pad = p.rowStride - p.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + pad/p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(p.buffer)
            img.close()
            Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } catch (_: Exception) { null }
    }

    private fun toBase64(bmp: Bitmap): String {
        val scale  = 720f / bmp.width
        val scaled = Bitmap.createScaledBitmap(bmp, 720, (bmp.height * scale).toInt(), true)
        val out    = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    // ── CLAUDE API ──────────────────────────────────────────────────────────

    private fun sendToAI(b64: String) {
        val prompt = """Analise este screenshot do Block Blast. Retorne SOMENTE JSON:
{
  "moves": [
    {"peca": 1, "linha": 5, "coluna": 3, "motivo": "Completa linha 5"},
    {"peca": 2, "linha": 2, "coluna": 6, "motivo": "Prepara combo"},
    {"peca": 3, "linha": 7, "coluna": 1, "motivo": "Fecha canto"}
  ],
  "dica": "Dica estratégica curta em português",
  "game_over": false
}
Tabuleiro 8x8. Linha/coluna de 1(topo/esquerda) a 8. Pense 3 jogadas à frente. Priorize completar linhas/colunas inteiras."""

        val body = JSONObject().apply {
            put("model", "claude-opus-4-5")
            put("max_tokens", 450)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type","image")
                        put("source", JSONObject().apply {
                            put("type","base64"); put("media_type","image/jpeg"); put("data",b64)
                        })
                    })
                    put(JSONObject().apply { put("type","text"); put("text",prompt) })
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
                val raw  = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    mainHandler.post { showPanel("❌ API ${resp.code}", emptyList(), false) }
                    return@Thread
                }
                val txt = JSONObject(raw).getJSONArray("content")
                    .getJSONObject(0).getString("text")
                    .replace("```json","").replace("```","").trim()
                val json   = JSONObject(txt.substring(txt.indexOf('{')))
                val moves  = json.optJSONArray("moves") ?: JSONArray()
                val dica   = json.optString("dica","")
                val over   = json.optBoolean("game_over", false)

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
                mainHandler.post { showPanel(if(over)"💀 Game Over!" else "", lines, false) }
            } catch (e: Exception) {
                mainHandler.post { showPanel("❌ ${e.message?.take(90)}", emptyList(), false) }
            }
        }.start()
    }

    // ── PAINEL ──────────────────────────────────────────────────────────────

    private fun showPanel(header: String, lines: List<String>, loading: Boolean) {
        mainHandler.post {
            resultLayout?.let { try { wm.removeView(it) } catch (_:Exception){} }
            resultLayout = null

            val pw = (resources.displayMetrics.widthPixels * 0.91f).toInt()

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable()
                bg.setColor(Color.WHITE); bg.cornerRadius = 52f
                bg.setStroke(2, 0xFFDDE1E7.toInt())
                background = bg; elevation = 28f
                setPadding(44, 26, 44, 44)
            }

            // título + fechar
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = if (loading) "🧠 Analisando..." else "🎯 Jogada recomendada"
                textSize = 14f; setTextColor(0xFF1D4ED8.toInt())
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this).apply {
                text = "✕"; textSize = 20f; setTextColor(0xFFAAAAAA.toInt()); setPadding(24,0,0,0)
                setOnClickListener { try{wm.removeView(root)}catch(_:Exception){}; resultLayout=null }
            })
            root.addView(top)

            root.addView(View(this).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT,1).apply{setMargins(0,18,0,18)}
            })

            if (loading) {
                root.addView(TextView(this).apply {
                    text = "Perguntando à IA..."; textSize = 14f
                    setTextColor(0xFF666666.toInt()); gravity = Gravity.CENTER
                    setPadding(0,10,0,10)
                })
            } else {
                if (header.isNotEmpty()) root.addView(tv(header, 16f, 0xFFDC2626.toInt(), bold=true, padB=14))
                lines.forEach { line ->
                    root.addView(TextView(this).apply {
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
                        setPadding(0,2,0,2)
                    })
                }
            }

            val lp = WindowManager.LayoutParams(pw, WRAP_CONTENT,
                TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = 55
            resultLayout = root; wm.addView(root, lp)
        }
    }

    private fun tv(t:String, sz:Float, color:Int, bold:Boolean=false, padB:Int=2) =
        TextView(this).apply {
            text=t; textSize=sz; setTextColor(color)
            if(bold) typeface=Typeface.DEFAULT_BOLD
            setPadding(0,0,0,padB)
        }

    // ── UTIL ────────────────────────────────────────────────────────────────

    private fun toast(msg: String) =
        mainHandler.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }

    private fun createChannel() {
        val ch = NotificationChannel("coach","BB Coach", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif() = NotificationCompat.Builder(this,"coach")
        .setContentTitle("Block Blast Coach 🎯")
        .setContentText("Toque no botão flutuante para analisar")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setPriority(NotificationCompat.PRIORITY_LOW).build()

    override fun onDestroy() {
        super.onDestroy()
        fabView?.let { try{wm.removeView(it)}catch(_:Exception){} }
        resultLayout?.let { try{wm.removeView(it)}catch(_:Exception){} }
        mediaProjection?.unregisterCallback(mpCallback)
        virtualDisplay?.release(); mediaProjection?.stop(); imageReader?.close()
    }
}
