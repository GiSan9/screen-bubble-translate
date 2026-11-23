package com.example.screenbubbletranslate

import android.app.Service
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
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator

class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var panelView: LinearLayout? = null
    private var translator: Translator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
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

        // Kotak hasil terjemahan melayang di atas layar
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6111827"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            visibility = View.GONE
        }

        val tvSource = TextView(this).apply {
            text = "Sumber: (ketuk 'Terjemahkan Clipboard')"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
        }

        val tvTranslated = TextView(this).apply {
            text = "Hasil terjemahan akan muncul di sini..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 12, 0, 16)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnTranslateClip = Button(this).apply {
            text = "Clipboard -> ID"
            textSize = 12f
        }

        val btnHide = Button(this).apply {
            text = "Sembunyi"
            textSize = 12f
        }

        val btnStopService = Button(this).apply {
            text = "Tutup"
            textSize = 12f
        }

        btnTranslateClip.setOnClickListener {
            val clipText = getClipboardText().orEmpty().trim()
            if (clipText.isEmpty()) {
                tvSource.text = "Clipboard kosong"
                tvTranslated.text = "Salin teks dulu lalu tekan tombol ini."
                return@setOnClickListener
            }
            tvSource.text = "JP: $clipText"
            tvTranslated.text = "Menerjemahkan..."
            translator?.translate(clipText)
                ?.addOnSuccessListener { result ->
                    tvTranslated.text = result
                }
                ?.addOnFailureListener { e ->
                    tvTranslated.text = "Gagal: ${e.message} (pastikan model sudah diunduh)"
                }
        }

        btnHide.setOnClickListener {
            panel.visibility = View.GONE
        }

        btnStopService.setOnClickListener {
            stopSelf()
        }

        btnRow.addView(btnTranslateClip)
        btnRow.addView(btnHide)
        btnRow.addView(btnStopService)

        panel.addView(tvSource)
        panel.addView(tvTranslated)
        panel.addView(btnRow)

        val panelWidth = (320 * resources.displayMetrics.density).toInt()
        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 260
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
                        val showPanel = panel.visibility != View.VISIBLE
                        panel.visibility = if (showPanel) View.VISIBLE else View.GONE
                        if (showPanel) {
                            btnTranslateClip.performClick()
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

    private fun getClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(this)?.toString()
    }

    override fun onDestroy() {
        translator?.close()
        bubbleView?.let { windowManager?.removeView(it) }
        panelView?.let { windowManager?.removeView(it) }
        bubbleView = null
        panelView = null
        super.onDestroy()
    }
}
