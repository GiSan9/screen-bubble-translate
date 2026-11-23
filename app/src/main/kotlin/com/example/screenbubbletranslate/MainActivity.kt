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
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var translator: Translator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        translator = Translation.getClient(options)

        binding.btnDownloadModel.setOnClickListener {
            binding.tvModelStatus.text = "Status model: Mengunduh JP -> ID..."
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    binding.tvModelStatus.text = "Status model: Siap digunakan!"
                }
                .addOnFailureListener { e ->
                    binding.tvModelStatus.text = "Status model: Gagal (${e.message})"
                }
        }

        binding.btnStartBubble.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(
                    this,
                    "Aktifkan 'Tampil di atas aplikasi lain', lalu tekan tombol lagi",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                startService(Intent(this, FloatingBubbleService::class.java))
            }
        }
    }

    override fun onDestroy() {
        translator.close()
        super.onDestroy()
    }
}
