package com.example.screenbubbletranslate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.screenbubbletranslate.databinding.ActivityMainBinding
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private data class LangOption(val label: String, val mlKitCode: String)

    private val modes = listOf(
        "Terjemah Layar Penuh",
        "Terjemah Sebagian",
        "Terjemah Otomatis",
        "Terjemahan Real-time"
    )

    private val languages = listOf(
        LangOption("Jepang (JP)", TranslateLanguage.JAPANESE),
        LangOption("Inggris (EN)", TranslateLanguage.ENGLISH),
        LangOption("Indonesia (ID)", TranslateLanguage.INDONESIAN),
        LangOption("Korea (KO)", TranslateLanguage.KOREAN),
        LangOption("Mandarin (ZH)", TranslateLanguage.CHINESE)
    )

    private lateinit var binding: ActivityMainBinding
    private var translator: Translator? = null
    private val modelManager = RemoteModelManager.getInstance()
    private var isUpdatingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        rebuildTranslator()
        checkSelectedModels()
        syncFloatingUi(FloatingBubbleService.isRunning)

        binding.btnDownloadModel.setOnClickListener {
            downloadSelectedModels()
        }

        binding.btnStartBubble.setOnClickListener {
            startBubbleWithCurrentSettings()
        }

        binding.btnStopBubble.setOnClickListener {
            stopBubbleService()
        }

        binding.switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                startBubbleWithCurrentSettings()
            } else {
                stopBubbleService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncFloatingUi(FloatingBubbleService.isRunning)
    }

    private fun setupSpinners() {
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spinnerMode.adapter = modeAdapter

        val langLabels = languages.map { it.label }
        val srcAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
        val dstAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)

        binding.spinnerSourceLang.adapter = srcAdapter
        binding.spinnerTargetLang.adapter = dstAdapter

        // Default: JP -> ID
        binding.spinnerSourceLang.setSelection(0)
        binding.spinnerTargetLang.setSelection(2)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                rebuildTranslator()
                checkSelectedModels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerMode.onItemSelectedListener = listener
        binding.spinnerSourceLang.onItemSelectedListener = listener
        binding.spinnerTargetLang.onItemSelectedListener = listener
    }

    private fun selectedMode(): String =
        modes.getOrElse(binding.spinnerMode.selectedItemPosition) { modes.first() }

    private fun selectedSource(): LangOption =
        languages.getOrElse(binding.spinnerSourceLang.selectedItemPosition) { languages.first() }

    private fun selectedTarget(): LangOption =
        languages.getOrElse(binding.spinnerTargetLang.selectedItemPosition) { languages[2] }

    private fun rebuildTranslator() {
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(selectedSource().mlKitCode)
            .setTargetLanguage(selectedTarget().mlKitCode)
            .build()
        translator = Translation.getClient(options)
    }

    private fun checkSelectedModels() {
        val src = selectedSource()
        val dst = selectedTarget()
        binding.tvModelStatus.text = "Status model (${src.mlKitCode} -> ${dst.mlKitCode}): Mengecek..."

        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val codes = models.map { it.language }.toSet()
                val hasSrc = codes.contains(src.mlKitCode)
                val hasDst = codes.contains(dst.mlKitCode)
                binding.tvModelStatus.text = if (hasSrc && hasDst) {
                    "Status model: Siap offline (${src.mlKitCode.uppercase()} -> ${dst.mlKitCode.uppercase()})"
                } else {
                    "Status model: Belum lengkap (${src.mlKitCode.uppercase()}: ${if (hasSrc) "OK" else "Belum"}, ${dst.mlKitCode.uppercase()}: ${if (hasDst) "OK" else "Belum"})"
                }
            }
            .addOnFailureListener { e ->
                binding.tvModelStatus.text = "Status model: Gagal cek (${e.localizedMessage ?: "error"})"
            }
    }

    private fun downloadSelectedModels() {
        val src = selectedSource()
        val dst = selectedTarget()
        binding.tvModelStatus.text = "Mengunduh paket ${src.mlKitCode.uppercase()} & ${dst.mlKitCode.uppercase()}..."

        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                checkSelectedModels()
                Toast.makeText(this, "Paket bahasa siap dipakai offline", Toast.LENGTH_SHORT).show()
            }
            ?.addOnFailureListener { e ->
                binding.tvModelStatus.text = "Gagal unduh: ${e.localizedMessage ?: "cek koneksi"}"
            }
    }

    private fun startBubbleWithCurrentSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setSwitchQuietly(false)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(
                this,
                "Aktifkan 'Tampil di atas aplikasi lain', lalu tekan Mulai",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val src = selectedSource()
        val dst = selectedTarget()
        val intent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_MODE, selectedMode())
            putExtra(FloatingBubbleService.EXTRA_SRC_LANG, src.mlKitCode)
            putExtra(FloatingBubbleService.EXTRA_DST_LANG, dst.mlKitCode)
        }
        startService(intent)
        syncFloatingUi(true)
    }

    private fun stopBubbleService() {
        stopService(Intent(this, FloatingBubbleService::class.java))
        syncFloatingUi(false)
    }

    private fun syncFloatingUi(running: Boolean) {
        setSwitchQuietly(running)
        binding.tvBubbleState.text = if (running) {
            "Bubble Overlay: ON (${selectedMode()})"
        } else {
            "Bubble Overlay: OFF"
        }
    }

    private fun setSwitchQuietly(checked: Boolean) {
        isUpdatingSwitch = true
        binding.switchFloating.isChecked = checked
        isUpdatingSwitch = false
    }

    override fun onDestroy() {
        translator?.close()
        super.onDestroy()
    }
}
