package com.implacavel.alarme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Escuta o microfone sem parar até ouvir "stop" (regra em [disseStop]). Usa o reconhecedor de voz do
 * sistema (no Galaxy, o do Google), de preferência offline, e recomeça sozinho depois de cada frase,
 * silêncio ou erro passageiro. Depois de [LIMITE_FALHAS] erros seguidos, ou se o reconhecedor ficar
 * [ESPERA_RESPOSTA_MS] sem dar sinal de vida (o serviço do Google caiu), avisa [onIndisponivel].
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

    /** O reconhecedor parou de responder: vale como indisponível, pra aparecer o botão no lugar. */
    private val travou = Runnable {
        Log.w(TAG, "Voz: o reconhecedor não responde há ${ESPERA_RESPOSTA_MS / 1000} s")
        parar()
        onIndisponivel()
    }

    fun comecar() {
        val microfone = Poder.MICROFONE.liberado(ctx)
        val reconhecimento = SpeechRecognizer.isRecognitionAvailable(ctx)
        if (!microfone || !reconhecimento) {
            Log.w(TAG, "Voz: indisponível (microfone=$microfone, reconhecimento=$reconhecimento)")
            onIndisponivel()
            return
        }
        Log.i(TAG, "Voz: escutando")
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
        aguardarResposta()
    }

    /** Qualquer sinal do reconhecedor mostra que ele está vivo: o prazo de [travou] recomeça. */
    private fun aguardarResposta() {
        handler.removeCallbacks(travou)
        handler.postDelayed(travou, ESPERA_RESPOSTA_MS)
    }

    private fun escutarDeNovo(esperaMs: Long) {
        handler.postDelayed({ escutar() }, esperaMs)
    }

    private fun conferir(resultado: Bundle?) {
        aguardarResposta()
        val frases = resultado?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        frases.firstOrNull()?.let(onOuviu)
        if (frases.isNotEmpty()) Log.i(TAG, "Voz ouviu: $frases")
        if (frases.any(::disseStop)) {
            Log.i(TAG, "Voz: STOP reconhecido")
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
        aguardarResposta()
        when (error) {
            // Silêncio ou fala que não virou texto: normal, só escutar de novo
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> falhasSeguidas = 0
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> falhasSeguidas = LIMITE_FALHAS
            else -> falhasSeguidas++
        }
        Log.i(TAG, "Voz: erro $error do reconhecedor (falhas seguidas: $falhasSeguidas)")
        if (falhasSeguidas >= LIMITE_FALHAS) {
            parar()
            onIndisponivel()
            return
        }
        reconhecedor?.cancel()
        escutarDeNovo(if (falhasSeguidas == 0) 100 else 800)
    }

    override fun onReadyForSpeech(params: Bundle?) = aguardarResposta()
    override fun onBeginningOfSpeech() = aguardarResposta()
    override fun onRmsChanged(rmsdB: Float) = aguardarResposta()
    override fun onBufferReceived(buffer: ByteArray?) = aguardarResposta()
    override fun onEndOfSpeech() = aguardarResposta()
    override fun onEvent(eventType: Int, params: Bundle?) = aguardarResposta()

    private companion object {
        const val LIMITE_FALHAS = 5
        const val ESPERA_RESPOSTA_MS = 8_000L
    }
}
