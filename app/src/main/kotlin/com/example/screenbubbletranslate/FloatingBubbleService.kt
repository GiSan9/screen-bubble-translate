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
import android.os.IBinder
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
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var panelView: LinearLayout? = null
    private var translator: Translator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)

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

        val tvTitle = TextView(this).apply {
            text = "JP → ID Bubble"
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 16f
            setPadding(16, 4, 8, 4)
        }

        topBar.addView(tvTitle)
        topBar.addView(btnClose)

        val tvOriginal = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            visibility = View.GONE
            setPadding(0, 8, 0, 4)
        }

        val tvResult = TextView(this).apply {
            text = "Ketuk Translate untuk menerjemahkan teks."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 8, 0, 12)
        }

        val etManual = EditText(this).apply {
            hint = "Tempel / ketik teks Jepang..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(18, 12, 18, 12)
            visibility = View.GONE
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
            text = "Translate"
            textSize = 12f
        }

        val btnInputToggle = Button(this).apply {
            text = "Input Teks"
            textSize = 12f
        }

        val btnCopyResult = Button(this).apply {
            text = "Salin"
            textSize = 12f
        }

        actionRow.addView(btnTranslate)
        actionRow.addView(btnInputToggle)
        actionRow.addView(btnCopyResult)

        panel.addView(topBar)
        panel.addView(tvOriginal)
        panel.addView(tvResult)
        panel.addView(etManual)
        panel.addView(actionRow)

        val panelWidth = (330 * resources.displayMetrics.density).toInt()
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

        fun readClipboard(): String? {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            return clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        }

        fun runTranslate(textToTranslate: String) {
            if (textToTranslate.isBlank()) {
                etManual.visibility = View.VISIBLE
                setPanelFocusable(true)
                etManual.requestFocus()
                tvResult.text = "Clipboard tidak terbaca. Tempel/ketik teks lalu tekan Translate."
                return
            }

            tvOriginal.visibility = View.VISIBLE
            tvOriginal.text = textToTranslate
            tvResult.text = "Menerjemahkan..."

            translator?.translate(textToTranslate)
                ?.addOnSuccessListener { translated ->
                    tvResult.text = translated
                }
                ?.addOnFailureListener { e ->
                    tvResult.text = "Gagal: ${e.localizedMessage ?: "cek model JP & ID"}"
                }
        }

        // Geser panel dari bagian judul
        var pInitX = 0
        var pInitY = 0
        var pTouchX = 0f
        var pTouchY = 0f
        tvTitle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pInitX = panelParams.x
                    pInitY = panelParams.y
                    pTouchX = event.rawX
                    pTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = (pInitX + (event.rawX - pTouchX).toInt()).coerceAtLeast(0)
                    panelParams.y = (pInitY + (event.rawY - pTouchY).toInt()).coerceAtLeast(0)
                    windowManager?.updateViewLayout(panel, panelParams)
                    true
                }
                else -> false
            }
        }

        etManual.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(true)
                v.requestFocus()
            }
            false
        }

        btnTranslate.setOnClickListener {
            val manualText = etManual.text.toString().trim()
            if (etManual.visibility == View.VISIBLE && manualText.isNotEmpty()) {
                runTranslate(manualText)
            } else {
                val clip = readClipboard()
                if (!clip.isNullOrBlank()) {
                    etManual.setText(clip)
                    runTranslate(clip)
                } else {
                    runTranslate(manualText)
                }
            }
        }

        btnInputToggle.setOnClickListener {
            val showInput = etManual.visibility != View.VISIBLE
            etManual.visibility = if (showInput) View.VISIBLE else View.GONE
            setPanelFocusable(showInput)
            if (showInput) {
                etManual.requestFocus()
            }
        }

        btnCopyResult.setOnClickListener {
            val textOut = tvResult.text.toString()
            if (textOut.isNotBlank() && !textOut.startsWith("Ketuk Translate") && !textOut.startsWith("Menerjemahkan")) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Translated JP->ID", textOut))
                Toast.makeText(this, "Hasil terjemahan disalin", Toast.LENGTH_SHORT).show()
            }
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
                        if (willShow) {
                            val clip = readClipboard()
                            if (!clip.isNullOrBlank()) {
                                etManual.setText(clip)
                                runTranslate(clip)
                            }
                        } else {
                            setPanelFocusable(false)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        panelView = panel
        windowManager?.addView(panel, panelParams)
        windowManager?.addView(bubble, bubbleParams)
    }

    override fun onDestroy() {
        isRunning = false
        translator?.close()
        bubbleView?.let { windowManager?.removeView(it) }
        panelView?.let { windowManager?.removeView(it) }
        bubbleView = null
        panelView = null
        super.onDestroy()
    }
}
