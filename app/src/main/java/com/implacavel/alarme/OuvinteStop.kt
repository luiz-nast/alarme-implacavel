package com.implacavel.alarme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Escuta o microfone sem parar até ouvir "stop" (regra em [disseStop]). Usa o reconhecedor de voz do
 * sistema (no Galaxy, o do Google), de preferência offline, e recomeça sozinho depois de cada frase,
 * silêncio ou erro passageiro. Depois de [LIMITE_FALHAS] erros seguidos, avisa [onIndisponivel].
 * Tudo roda na thread principal.
 */
class OuvinteStop(
    private val ctx: Context,
    private val onOuviu: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onIndisponivel: () -> Unit,
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var reconhecedor: SpeechRecognizer? = null
    private var falhasSeguidas = 0

    fun comecar() {
        if (!Poderes.concedida(ctx, Manifest.permission.RECORD_AUDIO) || !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onIndisponivel()
            return
        }
        reconhecedor = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) }
        escutar()
    }

    fun parar() {
        handler.removeCallbacksAndMessages(null)
        reconhecedor?.destroy()
        reconhecedor = null
    }

    private fun escutar() {
        val pedido = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        reconhecedor?.startListening(pedido)
    }

    private fun escutarDeNovo(esperaMs: Long) {
        handler.postDelayed({ escutar() }, esperaMs)
    }

    private fun conferir(resultado: Bundle?) {
        val frases = resultado?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        frases.firstOrNull()?.let(onOuviu)
        if (frases.any(::disseStop)) {
            parar()
            onStop()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) = conferir(partialResults)

    override fun onResults(results: Bundle?) {
        falhasSeguidas = 0
        conferir(results)
        if (reconhecedor != null) escutarDeNovo(100)
    }

    override fun onError(error: Int) {
        if (reconhecedor == null) return
        when (error) {
            // Silêncio ou fala que não virou texto: normal, só escutar de novo
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> falhasSeguidas = 0
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> falhasSeguidas = LIMITE_FALHAS
            else -> falhasSeguidas++
        }
        if (falhasSeguidas >= LIMITE_FALHAS) {
            parar()
            onIndisponivel()
            return
        }
        reconhecedor?.cancel()
        escutarDeNovo(if (falhasSeguidas == 0) 100 else 800)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        const val LIMITE_FALHAS = 5
    }
}
