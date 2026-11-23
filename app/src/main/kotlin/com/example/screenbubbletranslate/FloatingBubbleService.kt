package com.example.screenbubbletranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        private const val CHANNEL_ID = "bubble_tl_channel"
        private const val NOTIF_ID = 1001

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
    private var cropBoxView: FrameLayout? = null
    private var cropParams: WindowManager.LayoutParams? = null

    private var translator: Translator? = null
    private val ocrHelper = OcrHelper()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    private var currentMode: String = modeList.first()
    private var srcLang: String = TranslateLanguage.JAPANESE
    private var dstLang: String = TranslateLanguage.INDONESIAN
    private var lastRecognizedText: String = ""
    private var isBusyOcr: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tvModeBadgeRef: TextView? = null
    private var tvResultRef: TextView? = null
    private var etManualRef: EditText? = null

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (currentMode == "Terjemahan Real-time" || currentMode == "Terjemah Otomatis") {
                captureAndTranslate(silentIfSame = true)
            }
            mainHandler.postDelayed(this, 2200L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(360)
        screenHeight = metrics.heightPixels.coerceAtLeast(640)
        screenDensity = metrics.densityDpi.coerceAtLeast(160)

        startAsForeground()
        initUi()
        rebuildTranslator()
        mainHandler.postDelayed(autoLoopRunnable, 1500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { currentMode = it }
        intent?.getStringExtra(EXTRA_SRC_LANG)?.let { srcLang = it }
        intent?.getStringExtra(EXTRA_DST_LANG)?.let { dstLang = it }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            setupMediaProjection(resultCode, dataIntent)
        }

        rebuildTranslator()
        updateModeVisibility()
        return START_STICKY
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bubble Translate Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("Bubble Translate Aktif")
            .setContentText("Layanan overlay & tangkap layar berjalan")
            .setSmallIcon(android.R.drawable.ic_menu_search)

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: return
        mediaProjection = mp

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "BubbleTranslateCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun rebuildTranslator() {
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(dstLang)
            .build()
        translator = Translation.getClient(options)
    }

    private fun updateModeVisibility() {
        tvModeBadgeRef?.text = "$currentMode (${srcLang.uppercase()} → ${dstLang.uppercase()})"
        val showCrop = currentMode == "Terjemah Sebagian"
        cropBoxView?.visibility = if (showCrop) View.VISIBLE else View.GONE
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

        // Kotak area untuk mode "Terjemah Sebagian"
        val cropW = (screenWidth * 0.82f).toInt().coerceAtLeast(260)
        val cropH = (screenHeight * 0.20f).toInt().coerceAtLeast(140)
        val cParams = WindowManager.LayoutParams(
            cropW,
            cropH,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - cropW) / 2).coerceAtLeast(0)
            y = (screenHeight * 0.55f).toInt().coerceAtLeast(0)
        }
        cropParams = cParams

        val cropBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2622C55E"))
                setStroke(4, Color.parseColor("#22C55E"))
                cornerRadius = 16f
            }
            visibility = View.GONE
        }

        val cropLabel = TextView(this).apply {
            text = "Geser Area Terjemah Sebagian"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B314532D"))
            textSize = 11f
            setPadding(12, 4, 12, 4)
        }
        cropBox.addView(cropLabel)

        var cInitX = 0
        var cInitY = 0
        var cTouchX = 0f
        var cTouchY = 0f
        cropBox.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cInitX = cParams.x
                    cInitY = cParams.y
                    cTouchX = event.rawX
                    cTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    cParams.x = (cInitX + (event.rawX - cTouchX).toInt())
                        .coerceIn(0, (screenWidth - cParams.width).coerceAtLeast(0))
                    cParams.y = (cInitY + (event.rawY - cTouchY).toInt())
                        .coerceIn(0, (screenHeight - cParams.height).coerceAtLeast(0))
                    windowManager?.updateViewLayout(cropBox, cParams)
                    true
                }
                else -> false
            }
        }

        // Panel hasil terjemahan melayang
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
            text = "Ketuk Pindai Layar atau tempel teks di bawah."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 10, 0, 10)
        }

        val etManual = EditText(this).apply {
            hint = "Teks hasil OCR / ketik manual..."
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

        val btnScanNow = Button(this).apply {
            text = "Pindai"
            textSize = 12f
        }

        val btnTranslate = Button(this).apply {
            text = "Terjemah"
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

        actionRow.addView(btnScanNow)
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
            updateModeVisibility()
            Toast.makeText(this, "Mode: $currentMode", Toast.LENGTH_SHORT).show()
        }

        etManual.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(true)
                v.requestFocus()
            }
            false
        }

        btnScanNow.setOnClickListener {
            captureAndTranslate(silentIfSame = false)
        }

        btnTranslate.setOnClickListener {
            val textToUse = etManual.text.toString().trim().ifEmpty {
                readClipboard().orEmpty().trim()
            }
            if (textToUse.isEmpty()) {
                tvResult.text = "Masukkan teks atau ketuk Pindai."
                return@setOnClickListener
            }
            etManual.setText(textToUse)
            translateText(textToUse)
        }

        btnCopy.setOnClickListener {
            val out = tvResult.text.toString()
            if (out.isNotBlank() && !out.startsWith("Ketuk Pindai")) {
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
                        if (currentMode == "Terjemah Layar Penuh" || currentMode == "Terjemah Sebagian") {
                            panel.visibility = View.VISIBLE
                            captureAndTranslate(silentIfSame = false)
                        } else {
                            val willShow = panel.visibility != View.VISIBLE
                            panel.visibility = if (willShow) View.VISIBLE else View.GONE
                            if (!willShow) {
                                setPanelFocusable(false)
                            }
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
        panelView = panel
        cropBoxView = cropBox
        bubbleView = bubble
        updateModeVisibility()

        windowManager?.addView(cropBox, cParams)
        windowManager?.addView(panel, panelParams)
        windowManager?.addView(bubble, bubbleParams)
    }

    private fun captureAndTranslate(silentIfSame: Boolean) {
        if (isBusyOcr) return
        val reader = imageReader ?: return

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        isBusyOcr = true
        try {
            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val fullBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride.coerceAtLeast(1),
                height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(plane.buffer)

            val croppedBitmap = if (currentMode == "Terjemah Sebagian") {
                val cp = cropParams
                val left = (cp?.x ?: 0).coerceIn(0, (fullBitmap.width - 1).coerceAtLeast(0))
                val top = (cp?.y ?: 0).coerceIn(0, (fullBitmap.height - 1).coerceAtLeast(0))
                val w = (cp?.width ?: fullBitmap.width)
                    .coerceAtMost((fullBitmap.width - left).coerceAtLeast(1))
                val h = (cp?.height ?: fullBitmap.height)
                    .coerceAtMost((fullBitmap.height - top).coerceAtLeast(1))
                Bitmap.createBitmap(fullBitmap, left, top, w, h)
            } else {
                Bitmap.createBitmap(fullBitmap, 0, 0, width.coerceAtMost(fullBitmap.width), height.coerceAtMost(fullBitmap.height))
            }

            if (croppedBitmap !== fullBitmap) {
                fullBitmap.recycle()
            }

            ocrHelper.recognize(
                bitmap = croppedBitmap,
                srcLangCode = srcLang,
                onSuccess = { detected ->
                    croppedBitmap.recycle()
                    isBusyOcr = false
                    if (detected.isBlank()) {
                        if (!silentIfSame) {
                            tvResultRef?.text = "Tidak ada teks terdeteksi di layar."
                        }
                        return@recognize
                    }
                    if (silentIfSame && detected == lastRecognizedText) {
                        return@recognize
                    }
                    lastRecognizedText = detected
                    etManualRef?.setText(detected)
                    panelView?.visibility = View.VISIBLE
                    translateText(detected)
                },
                onError = { err ->
                    croppedBitmap.recycle()
                    isBusyOcr = false
                    if (!silentIfSame) {
                        tvResultRef?.text = "OCR error: $err"
                    }
                }
            )
        } catch (e: Exception) {
            isBusyOcr = false
            if (!silentIfSame) {
                tvResultRef?.text = "Gagal tangkap layar: ${e.localizedMessage ?: "error"}"
            }
        } finally {
            image.close()
        }
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
        mainHandler.removeCallbacks(autoLoopRunnable)
        releaseProjection()
        translator?.close()
        bubbleView?.let { windowManager?.removeView(it) }
        panelView?.let { windowManager?.removeView(it) }
        cropBoxView?.let { windowManager?.removeView(it) }
        bubbleView = null
        panelView = null
        cropBoxView = null
        super.onDestroy()
    }
}
