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
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

        var pendingResultCode: Int = Activity.RESULT_CANCELED
        var pendingProjectionData: Intent? = null

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
    private var tvSourcePreviewRef: TextView? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                releaseDisplayOnly()
            }
        }
    }

    private val autoLoopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (currentMode == "Terjemahan Real-time" || currentMode == "Terjemah Otomatis") {
                captureAndTranslate(silentIfSame = true)
            }
            mainHandler.postDelayed(this, 2600L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(360)
            screenHeight = metrics.heightPixels.coerceAtLeast(640)
            screenDensity = metrics.densityDpi.coerceAtLeast(160)

            startAsForegroundSafe()
            initUiSafe()
            rebuildTranslator()
            mainHandler.postDelayed(autoLoopRunnable, 2000L)
        } catch (e: Exception) {
            Toast.makeText(this, "Init error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { currentMode = it }
        intent?.getStringExtra(EXTRA_SRC_LANG)?.let { srcLang = it }
        intent?.getStringExtra(EXTRA_DST_LANG)?.let { dstLang = it }

        rebuildTranslator()
        updateModeVisibility()

        val data = pendingProjectionData
        if (pendingResultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
            mainHandler.postDelayed({
                setupMediaProjectionSafe(pendingResultCode, data)
            }, 350L)
        }
        return START_STICKY
    }

    private fun startAsForegroundSafe() {
        try {
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
                .setContentText("Layanan overlay berjalan")
                .setSmallIcon(android.R.drawable.ic_menu_search)

            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    private fun setupMediaProjectionSafe(resultCode: Int, data: Intent) {
        try {
            releaseProjection()
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data) ?: return
            mediaProjection = mp
            mp.registerCallback(projectionCallback, mainHandler)

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
                mainHandler
            )
        } catch (e: Exception) {
            Toast.makeText(this, "OCR layar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseDisplayOnly() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {
        }
    }

    private fun releaseProjection() {
        releaseDisplayOnly()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun rebuildTranslator() {
        try {
            translator?.close()
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcLang)
                .setTargetLanguage(dstLang)
                .build()
            translator = Translation.getClient(options)
        } catch (_: Exception) {
        }
    }

    private fun updateModeVisibility() {
        tvModeBadgeRef?.text = "$currentMode (${srcLang.uppercase()} → ${dstLang.uppercase()})"
        val showCrop = currentMode == "Terjemah Sebagian"
        cropBoxView?.visibility = if (showCrop) View.VISIBLE else View.GONE
    }

    /**
     * Membersihkan teks OCR agar lebih ringkas dan tidak penuh noise UI/file manager.
     */
    private fun cleanOcrText(raw: String): String {
        val dateRegex = Regex("""^\d{1,2}/\d{1,2}/\d{2,4}(\s*\+.*)?$""")
        val sizeRegex = Regex("""^\d+(\.\d+)?\s*(kb|mb|gb|b)$""", RegexOption.IGNORE_CASE)
        val clockRegex = Regex("""^\d{1,2}\.\d{2}(\s+[A-Z])?$""")
        val noiseTokens = setOf("zip", "rar", "7z", "apk", "xapk", "obb", "folder", "item", "items", "unduh")

        val lines = raw.lines()
            .map { it.trim() }
            .filter { line ->
                if (line.length < 2) return@filter false
                val lower = line.lowercase()
                if (lower in noiseTokens) return@filter false
                if (dateRegex.matches(lower)) return@filter false
                if (sizeRegex.matches(lower)) return@filter false
                if (clockRegex.matches(line)) return@filter false
                true
            }
            .distinct()

        val joined = lines.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
        return if (joined.length > 320) joined.take(320) + "..." else joined
    }

    private fun initUiSafe() {
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

        val sizePx = (54 * resources.displayMetrics.density).toInt()
        val bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - sizePx - 24).coerceAtLeast(16)
            y = (screenHeight * 0.28f).toInt().coerceAtLeast(80)
        }

        // Kotak area untuk mode "Terjemah Sebagian"
        val cropW = (screenWidth * 0.86f).toInt().coerceAtLeast(260)
        val cropH = (screenHeight * 0.18f).toInt().coerceAtLeast(120)
        val cParams = WindowManager.LayoutParams(
            cropW,
            cropH,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - cropW) / 2).coerceAtLeast(0)
            y = (screenHeight * 0.50f).toInt().coerceAtLeast(0)
        }
        cropParams = cParams

        val cropBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2222C55E"))
                setStroke(3, Color.parseColor("#22C55E"))
                cornerRadius = 16f
            }
            visibility = View.GONE
        }

        val cropLabel = TextView(this).apply {
            text = "Area Sebagian (geser)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC14532D"))
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

        // Panel hasil terjemahan yang rapi di bagian bawah layar
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2111827"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#334155"))
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
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSwitchMode = TextView(this).apply {
            text = "Mode"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            setPadding(12, 4, 12, 4)
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 15f
            setPadding(12, 4, 4, 4)
        }

        topBar.addView(tvModeBadge)
        topBar.addView(btnSwitchMode)
        topBar.addView(btnClose)

        val tvSourcePreview = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, 6, 0, 2)
        }

        val scrollResult = ScrollView(this).apply {
            val maxH = (130 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6
                bottomMargin = 10
            }
            post {
                if (height > maxH) {
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                        height = maxH
                    }
                }
            }
        }

        val tvResult = TextView(this).apply {
            text = "Ketuk Pindai untuk menerjemahkan teks di layar."
            setTextColor(Color.WHITE)
            textSize = 15f
            setLineSpacing(4f, 1.15f)
        }
        scrollResult.addView(tvResult)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnScanNow = Button(this).apply {
            text = "Pindai"
            textSize = 11f
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            layoutParams = lp
        }

        val btnCopy = Button(this).apply {
            text = "Salin"
            textSize = 11f
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginHorizontal = 6
            }
            layoutParams = lp
        }

        val btnOff = Button(this).apply {
            text = "OFF"
            textSize = 11f
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6
            }
            layoutParams = lp
        }

        actionRow.addView(btnScanNow)
        actionRow.addView(btnCopy)
        actionRow.addView(btnOff)

        panel.addView(topBar)
        panel.addView(tvSourcePreview)
        panel.addView(scrollResult)
        panel.addView(actionRow)

        val panelWidth = (screenWidth * 0.92f).toInt().coerceAtLeast(280)
        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - panelWidth) / 2).coerceAtLeast(0)
            y = (screenHeight * 0.72f).toInt().coerceAtLeast(0)
        }

        btnSwitchMode.setOnClickListener {
            val nextIdx = (modeList.indexOf(currentMode) + 1) % modeList.size
            currentMode = modeList[nextIdx]
            updateModeVisibility()
            Toast.makeText(this, "Mode: $currentMode", Toast.LENGTH_SHORT).show()
        }

        btnScanNow.setOnClickListener {
            captureAndTranslate(silentIfSame = false)
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
                    bubbleParams.x = (initX + dx).coerceIn(0, (screenWidth - bubbleParams.width).coerceAtLeast(0))
                    bubbleParams.y = (initY + dy).coerceIn(0, (screenHeight - bubbleParams.height).coerceAtLeast(0))
                    windowManager?.updateViewLayout(bubble, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val willShow = panel.visibility != View.VISIBLE
                        panel.visibility = if (willShow) View.VISIBLE else View.GONE
                        if (willShow && (currentMode == "Terjemah Layar Penuh" || currentMode == "Terjemah Sebagian")) {
                            captureAndTranslate(silentIfSame = false)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        tvModeBadgeRef = tvModeBadge
        tvResultRef = tvResult
        tvSourcePreviewRef = tvSourcePreview
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
        val reader = imageReader ?: run {
            if (!silentIfSame) {
                tvResultRef?.text = "Tangkap layar belum siap. Tekan Mulai ulang di aplikasi."
            }
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: run {
            if (!silentIfSame) {
                tvResultRef?.text = "Frame layar belum tersedia, tekan Pindai lagi."
            }
            return
        }

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
                Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    width.coerceAtMost(fullBitmap.width),
                    height.coerceAtMost(fullBitmap.height)
                )
            }

            if (croppedBitmap !== fullBitmap) {
                fullBitmap.recycle()
            }

            ocrHelper.recognize(
                bitmap = croppedBitmap,
                srcLangCode = srcLang,
                onSuccess = { rawDetected ->
                    croppedBitmap.recycle()
                    isBusyOcr = false
                    val cleaned = cleanOcrText(rawDetected)
                    if (cleaned.isBlank()) {
                        if (!silentIfSame) {
                            tvSourcePreviewRef?.visibility = View.GONE
                            tvResultRef?.text = "Tidak ada teks jelas terdeteksi di area ini."
                        }
                        return@recognize
                    }
                    if (silentIfSame && cleaned == lastRecognizedText) {
                        return@recognize
                    }
                    lastRecognizedText = cleaned
                    tvSourcePreviewRef?.visibility = View.VISIBLE
                    tvSourcePreviewRef?.text = cleaned
                    panelView?.visibility = View.VISIBLE
                    translateText(cleaned)
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

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(autoLoopRunnable)
        releaseProjection()
        translator?.close()
        try {
            bubbleView?.let { windowManager?.removeView(it) }
            panelView?.let { windowManager?.removeView(it) }
            cropBoxView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        bubbleView = null
        panelView = null
        cropBoxView = null
        super.onDestroy()
    }
}
