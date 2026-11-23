package com.example.screenbubbletranslate

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrHelper {

    fun recognize(
        bitmap: Bitmap,
        srcLangCode: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val recognizer: TextRecognizer = when (srcLangCode) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                recognizer.close()
                onSuccess(text)
            }
            .addOnFailureListener { e ->
                recognizer.close()
                onError(e.localizedMessage ?: "OCR gagal")
            }
    }
}
