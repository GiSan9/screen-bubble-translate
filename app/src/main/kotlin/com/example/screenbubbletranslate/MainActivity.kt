package com.example.screenbubbletranslate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var translator: Translator
    private val modelManager = RemoteModelManager.getInstance()
    private var isUpdatingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)

        checkDownloadedModels()
        syncFloatingUiState(FloatingBubbleService.isRunning)

        binding.btnDownloadModel.setOnClickListener {
            downloadModelsNow()
        }

        binding.btnStartBubble.setOnClickListener {
            enableFloatingBubble()
        }

        binding.btnStopBubble.setOnClickListener {
            disableFloatingBubble()
        }

        binding.switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                enableFloatingBubble()
            } else {
                disableFloatingBubble()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncFloatingUiState(FloatingBubbleService.isRunning)
    }

    private fun enableFloatingBubble() {
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
                "Aktifkan 'Tampil di atas aplikasi lain', lalu nyalakan lagi",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        startService(Intent(this, FloatingBubbleService::class.java))
        syncFloatingUiState(true)
        Toast.makeText(this, "Floating Bubble ON", Toast.LENGTH_SHORT).show()
    }

    private fun disableFloatingBubble() {
        stopService(Intent(this, FloatingBubbleService::class.java))
        syncFloatingUiState(false)
        Toast.makeText(this, "Floating Bubble OFF", Toast.LENGTH_SHORT).show()
    }

    private fun syncFloatingUiState(running: Boolean) {
        setSwitchQuietly(running)
        binding.tvBubbleState.text = if (running) "Status: ON (Melayang aktif)" else "Status: OFF"
    }

    private fun setSwitchQuietly(checked: Boolean) {
        isUpdatingSwitch = true
        binding.switchFloating.isChecked = checked
        isUpdatingSwitch = false
    }

    private fun checkDownloadedModels() {
        binding.tvModelStatus.text = "Status model: Mengecek model JP & ID..."
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val downloadedCodes = models.map { it.language }.toSet()
                val hasJp = downloadedCodes.contains(TranslateLanguage.JAPANESE)
                val hasId = downloadedCodes.contains(TranslateLanguage.INDONESIAN)
                if (hasJp && hasId) {
                    binding.tvModelStatus.text = "Status model: Siap digunakan! (JP & ID aktif)"
                } else {
                    binding.tvModelStatus.text =
                        "Status model: Belum lengkap (JP: ${if (hasJp) "OK" else "Belum"}, ID: ${if (hasId) "OK" else "Belum"})"
                }
            }
            .addOnFailureListener { e ->
                binding.tvModelStatus.text = "Status model: Gagal cek (${e.localizedMessage ?: "error"})"
            }
    }

    private fun downloadModelsNow() {
        binding.tvModelStatus.text = "Status model: Mengunduh model JP & ID..."
        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                checkDownloadedModels()
            }
            .addOnFailureListener { e ->
                binding.tvModelStatus.text =
                    "Status model: Gagal unduh (${e.localizedMessage ?: "cek koneksi / Play Services"})"
            }
            .addOnCanceledListener {
                binding.tvModelStatus.text = "Status model: Unduhan dibatalkan."
            }
    }

    override fun onDestroy() {
        translator.close()
        super.onDestroy()
    }
}
