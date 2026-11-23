package com.example.screenbubbletranslate

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class FloatingBubbleService : Service() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SRC_LANG = "extra_src_lang"
        const val EXTRA_DST_LANG = "extra_dst_lang"

        private val modeList = listOf(
            "Terjemah Layar Penuh",
            "Terjemah Sebagian",
            "Terjemah Otomatis",
            "Terjemahan Real-time"
        )

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var panelView: LinearLayout? = null
    private var translator: Translator? = null

    private var currentMode: String = modeList.first()
    private var srcLang: String = TranslateLanguage.JAPANESE
    private var dstLang: String = TranslateLanguage.INDONESIAN
    private var lastAutoText: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tvModeBadgeRef: TextView? = null
    private var tvResultRef: TextView? = null
    private var etManualRef: EditText? = null
    private var panelRef: LinearLayout? = null

    private val realTimeRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (currentMode == "Terjemahan Real-time" || currentMode == "Terjemah Otomatis") {
                val clip = readClipboard()
                if (!clip.isNullOrBlank() && clip != lastAutoText) {
                    lastAutoText = clip
                    etManualRef?.setText(clip)
                    translateText(clip)
                }
            }
            mainHandler.postDelayed(this, 1500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initUi()
        rebuildTranslator()
        mainHandler.post(realTimeRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { currentMode = it }
        intent?.getStringExtra(EXTRA_SRC_LANG)?.let { srcLang = it }
        intent?.getStringExtra(EXTRA_DST_LANG)?.let { dstLang = it }

        rebuildTranslator()
        updateModeBadge()
        return START_STICKY
    }

    private fun rebuildTranslator() {
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(dstLang)
            .build()
        translator = Translation.getClient(options)
    }

    private fun updateModeBadge() {
        tvModeBadgeRef?.text = "$currentMode (${srcLang.uppercase()} → ${dstLang.uppercase()})"
    }

    private fun initUi() {
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2563EB"))
            setStroke(4, Color.WHITE)
        }

        val bubble = TextView(this).apply {
            text = "TL"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = circleBg
        }

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 160
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 20, 26, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5111827"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#3B82F6"))
            }
            visibility = View.GONE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvModeBadge = TextView(this).apply {
            text = "$currentMode (JA → ID)"
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSwitchMode = TextView(this).apply {
            text = "Ganti Mode"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            setPadding(12, 4, 12, 4)
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 16f
            setPadding(12, 4, 6, 4)
        }

        topBar.addView(tvModeBadge)
        topBar.addView(btnSwitchMode)
        topBar.addView(btnClose)

        val tvResult = TextView(this).apply {
            text = "Ketuk Terjemahkan atau tempel teks di bawah."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 10, 0, 10)
        }

        val etManual = EditText(this).apply {
            hint = "Tempel / ketik teks untuk diterjemahkan..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(18, 12, 18, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }
            layoutParams = lp
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnTranslate = Button(this).apply {
            text = "Terjemahkan"
            textSize = 12f
        }

        val btnCopy = Button(this).apply {
            text = "Salin"
            textSize = 12f
        }

        val btnOff = Button(this).apply {
            text = "OFF"
            textSize = 12f
        }

        actionRow.addView(btnTranslate)
        actionRow.addView(btnCopy)
        actionRow.addView(btnOff)

        panel.addView(topBar)
        panel.addView(tvResult)
        panel.addView(etManual)
        panel.addView(actionRow)

        val panelWidth = (336 * resources.displayMetrics.density).toInt()
        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 232
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        fun setPanelFocusable(focusable: Boolean) {
            panelParams.flags = if (focusable) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (panel.windowToken != null) {
                windowManager?.updateViewLayout(panel, panelParams)
            }
        }

        btnSwitchMode.setOnClickListener {
            val nextIdx = (modeList.indexOf(currentMode) + 1) % modeList.size
            currentMode = modeList[nextIdx]
            updateModeBadge()
            Toast.makeText(this, "Mode: $currentMode", Toast.LENGTH_SHORT).show()
        }

        etManual.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(true)
                v.requestFocus()
            }
            false
        }

        btnTranslate.setOnClickListener {
            val fromInput = etManual.text.toString().trim()
            val textToUse = if (fromInput.isNotEmpty()) fromInput else readClipboard().orEmpty().trim()
            if (textToUse.isEmpty()) {
                tvResult.text = "Tempel/ketik teks dulu lalu tekan Terjemahkan."
                return@setOnClickListener
            }
            etManual.setText(textToUse)
            translateText(textToUse)
        }

        btnCopy.setOnClickListener {
            val out = tvResult.text.toString()
            if (out.isNotBlank() && !out.startsWith("Ketuk Terjemahkan")) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Translated", out))
                Toast.makeText(this, "Hasil disalin", Toast.LENGTH_SHORT).show()
            }
        }

        btnOff.setOnClickListener {
            stopSelf()
        }

        btnClose.setOnClickListener {
            setPanelFocusable(false)
            panel.visibility = View.GONE
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = bubbleParams.x
                    initY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    bubbleParams.x = (initX + dx).coerceAtLeast(0)
                    bubbleParams.y = (initY + dy).coerceAtLeast(0)
                    windowManager?.updateViewLayout(bubble, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val willShow = panel.visibility != View.VISIBLE
                        panel.visibility = if (willShow) View.VISIBLE else View.GONE
                        if (!willShow) {
                            setPanelFocusable(false)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        tvModeBadgeRef = tvModeBadge
        tvResultRef = tvResult
        etManualRef = etManual
        panelRef = panel
        bubbleView = bubble
        panelView = panel
        updateModeBadge()

        windowManager?.addView(panel, panelParams)
        windowManager?.addView(bubble, bubbleParams)
    }

    private fun translateText(text: String) {
        tvResultRef?.text = "Menerjemahkan..."
        translator?.translate(text)
            ?.addOnSuccessListener { result ->
                tvResultRef?.text = result
            }
            ?.addOnFailureListener { e ->
                tvResultRef?.text = "Gagal: ${e.localizedMessage ?: "unduh paket bahasa dulu"}"
            }
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(this)?.toString()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(realTimeRunnable)
        translator?.close()
        bubbleView?.let { windowManager?.removeView(it) }
        panelView?.let { windowManager?.removeView(it) }
        bubbleView = null
        panelView = null
        super.onDestroy()
    }
}
