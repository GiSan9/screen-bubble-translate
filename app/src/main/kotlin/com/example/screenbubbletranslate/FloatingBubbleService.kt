package com.example.screenbubbletranslate

import android.app.Service
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
            x = 24
            y = 180
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5111827"))
                cornerRadius = 32f
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            visibility = View.GONE
        }

        val tvHeader = TextView(this).apply {
            text = "QUICK TRANSLATE • JP → ID"
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 11f
        }

        val etInputJp = EditText(this).apply {
            hint = "Tempel / ketik teks Jepang di sini..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(20, 16, 20, 16)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
                bottomMargin = 10
            }
            layoutParams = lp
        }

        val tvTranslated = TextView(this).apply {
            text = "Hasil terjemahan akan muncul di sini..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 6, 0, 14)
        }

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnTranslate = Button(this).apply {
            text = "Terjemahkan"
            textSize = 12f
        }

        val btnSampleJp = Button(this).apply {
            text = "Test JP"
            textSize = 12f
        }

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnHide = Button(this).apply {
            text = "Sembunyi"
            textSize = 12f
        }

        val btnTurnOff = Button(this).apply {
            text = "OFF"
            textSize = 12f
        }

        val panelWidth = (332 * resources.displayMetrics.density).toInt()
        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 256
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

        fun translateText(rawText: String) {
            val clean = rawText.trim()
            if (clean.isEmpty()) {
                tvTranslated.text = "Isi atau tempel teks Jepang dulu, atau tekan 'Test JP'."
                return
            }
            tvTranslated.text = "Menerjemahkan..."
            translator?.translate(clean)
                ?.addOnSuccessListener { result ->
                    tvTranslated.text = result
                }
                ?.addOnFailureListener { e ->
                    tvTranslated.text = "Gagal: ${e.localizedMessage ?: "cek model JP & ID"}"
                }
        }

        etInputJp.setOnFocusChangeListener { _, hasFocus ->
            setPanelFocusable(hasFocus)
        }

        btnTranslate.setOnClickListener {
            translateText(etInputJp.text.toString())
        }

        btnSampleJp.setOnClickListener {
            val sample = "おはようございます、今日もよろしくお願いします。"
            etInputJp.setText(sample)
            translateText(sample)
        }

        btnHide.setOnClickListener {
            setPanelFocusable(false)
            panel.visibility = View.GONE
        }

        btnTurnOff.setOnClickListener {
            stopSelf()
        }

        btnRow1.addView(btnTranslate)
        btnRow1.addView(btnSampleJp)

        btnRow2.addView(btnHide)
        btnRow2.addView(btnTurnOff)

        panel.addView(tvHeader)
        panel.addView(etInputJp)
        panel.addView(tvTranslated)
        panel.addView(btnRow1)
        panel.addView(btnRow2)

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
