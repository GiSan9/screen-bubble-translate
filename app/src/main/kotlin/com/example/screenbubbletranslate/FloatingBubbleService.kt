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
            "Terjemah Sebagian",
            "Terjemah Layar Penuh",
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
                    "Anime Bubble Translate",
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
            }.setContentTitle("Anime Bubble TL Aktif")
                .setContentText("Overlay terjemahan siap digunakan")
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
        tvModeBadgeRef?.text = "✦ $currentMode • ${srcLang.uppercase()} → ${dstLang.uppercase()}"
        val showCrop = currentMode == "Terjemah Sebagian"
        cropBoxView?.visibility = if (showCrop) View.VISIBLE else View.GONE
    }

    /**
     * Memilih satu baris/kalimat dialog yang paling bersih agar tidak mencampur banyak kotak sekaligus.
     */
    private fun extractCleanDialog(raw: String): String {
        val dateRegex = Regex("""^\d{1,2}/\d{1,2}/\d{2,4}(\s*\+.*)?$""")
        val sizeRegex = Regex("""^\d+(\.\d+)?\s*(kb|mb|gb|b)$""", RegexOption.IGNORE_CASE)
        val clockRegex = Regex("""^\d{1,2}\.\d{2}(\s+[A-Z])?$""")
        val noiseTokens = setOf("zip", "rar", "7z", "apk", "xapk", "obb", "folder", "item", "items", "unduh")

        val cleanLines = raw.lines()
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

        if (cleanLines.isEmpty()) return ""

        val selected = if (currentMode == "Terjemah Layar Penuh") {
            cleanLines.maxByOrNull { it.length } ?: cleanLines.first()
        } else {
            cleanLines.take(3).joinToString(" ")
        }

        return selected
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\bouite\b""", RegexOption.IGNORE_CASE), "quite")
            .replace(Regex("""\bexclusve\b""", RegexOption.IGNORE_CASE), "exclusive")
            .replace(Regex("""\bconflct\b""", RegexOption.IGNORE_CASE), "conflict")
            .replace(Regex("""\bas wel\b""", RegexOption.IGNORE_CASE), "as well")
            .trim()
            .let { if (it.length > 140) it.take(140).substringBeforeLast(" ").ifBlank { it.take(140) } else it }
            .trim()
    }

    private fun makePillButton(label: String, bgHex: String, textHex: String, borderHex: String): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 999f
                setStroke(2, Color.parseColor(borderHex))
            }
            setPadding(10, 6, 10, 6)
        }
    }

    private fun initUiSafe() {
        val bubble = TextView(this).apply {
            text = "✦"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EC4899"))
                setStroke(4, Color.parseColor("#FBCFE8"))
            }
        }

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val sizePx = (50 * resources.displayMetrics.density).toInt()
        val bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - sizePx - 20).coerceAtLeast(16)
            y = (screenHeight * 0.24f).toInt().coerceAtLeast(80)
        }

        // Kotak area untuk mode "Terjemah Sebagian"
        val cropW = (screenWidth * 0.66f).toInt().coerceAtLeast(190)
        val cropH = (screenHeight * 0.14f).toInt().coerceAtLeast(92)
        val cParams = WindowManager.LayoutParams(
            cropW,
            cropH,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - cropW) / 2).coerceAtLeast(0)
            y = (screenHeight * 0.42f).toInt().coerceAtLeast(0)
        }
        cropParams = cParams

        val cropBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#24F472B6"))
                setStroke(3, Color.parseColor("#F472B6"))
                cornerRadius = 16f
            }
            visibility = View.GONE
        }

        val cropLabel = TextView(this).apply {
            text = "✦ Dialog"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9831843"))
                cornerRadius = 10f
            }
            textSize = 10f
            setPadding(12, 3, 12, 3)
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

        // Subtitle box anime minimalis & bersih di bagian bawah layar
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20B1120"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            visibility = View.GONE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvModeBadge = TextView(this).apply {
            text = "✦ $currentMode • JA → ID"
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#172554"))
                cornerRadius = 999f
            }
            setPadding(12, 4, 12, 4)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSwitchMode = TextView(this).apply {
            text = "Mode"
            setTextColor(Color.parseColor("#F9A8D4"))
            textSize = 11f
            setPadding(10, 4, 8, 4)
        }

        val btnZoomIn = TextView(this).apply {
            text = "+"
            setTextColor(Color.parseColor("#BAE6FD"))
            textSize = 14f
            setPadding(8, 2, 6, 2)
        }

        val btnZoomOut = TextView(this).apply {
            text = "-"
            setTextColor(Color.parseColor("#BAE6FD"))
            textSize = 14f
            setPadding(6, 2, 8, 2)
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FDA4AF"))
            textSize = 14f
            setPadding(8, 2, 2, 2)
        }

        topBar.addView(tvModeBadge)
        topBar.addView(btnSwitchMode)
        topBar.addView(btnZoomIn)
        topBar.addView(btnZoomOut)
        topBar.addView(btnClose)

        val tvResult = TextView(this).apply {
            text = "Arahkan kotak ke dialog lalu ketuk Pindai."
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(4f, 1.18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
                bottomMargin = 12
            }
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnScanNow = makePillButton("Pindai", "#DB2777", "#FFFFFF", "#F472B6").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 5
            }
        }

        val btnCopy = makePillButton("Salin", "#1E293B", "#E2E8F0", "#334155").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 5
                rightMargin = 5
            }
        }

        val btnOff = makePillButton("OFF", "#2A0D29", "#FDA4AF", "#831843").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 5
            }
        }

        actionRow.addView(btnScanNow)
        actionRow.addView(btnCopy)
        actionRow.addView(btnOff)

        panel.addView(topBar)
        panel.addView(tvResult)
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
            y = (screenHeight * 0.78f).toInt().coerceAtLeast(0)
        }

        fun showPanel(show: Boolean) {
            panel.visibility = if (show) View.VISIBLE else View.GONE
            bubble.visibility = if (show) View.GONE else View.VISIBLE
        }

        fun resizeCropBox(deltaW: Int, deltaH: Int) {
            val cp = cropParams ?: return
            val box = cropBoxView ?: return
            cp.width = (cp.width + deltaW).coerceIn(170, (screenWidth * 0.94f).toInt())
            cp.height = (cp.height + deltaH).coerceIn(80, (screenHeight * 0.45f).toInt())
            cp.x = cp.x.coerceIn(0, (screenWidth - cp.width).coerceAtLeast(0))
            cp.y = cp.y.coerceIn(0, (screenHeight - cp.height).coerceAtLeast(0))
            if (box.windowToken != null) {
                windowManager?.updateViewLayout(box, cp)
            }
        }

        btnZoomIn.setOnClickListener {
            currentMode = "Terjemah Sebagian"
            updateModeVisibility()
            resizeCropBox(56, 32)
        }

        btnZoomOut.setOnClickListener {
            currentMode = "Terjemah Sebagian"
            updateModeVisibility()
            resizeCropBox(-56, -32)
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
            if (out.isNotBlank() && !out.startsWith("Arahkan kotak")) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Anime TL", out))
                Toast.makeText(this, "Terjemahan disalin", Toast.LENGTH_SHORT).show()
            }
        }

        btnOff.setOnClickListener {
            stopSelf()
        }

        btnClose.setOnClickListener {
            showPanel(false)
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
                        showPanel(true)
                        if (currentMode == "Terjemah Layar Penuh" || currentMode == "Terjemah Sebagian") {
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
                    val cleaned = extractCleanDialog(rawDetected)
                    if (cleaned.isBlank()) {
                        if (!silentIfSame) {
                            tvResultRef?.text = "Tidak ada teks jelas di area ini."
                        }
                        return@recognize
                    }
                    if (silentIfSame && cleaned == lastRecognizedText) {
                        return@recognize
                    }
                    lastRecognizedText = cleaned
                    panelView?.visibility = View.VISIBLE
                    bubbleView?.visibility = View.GONE
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
