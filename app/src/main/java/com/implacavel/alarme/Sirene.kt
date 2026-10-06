package com.implacavel.alarme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.net.toUri

/**
 * O barulho do alarme: música ou toque em loop, bipes de aviso, vibração contínua, e do começo ao
 * fim (inclusive com a música calada depois do "stop") outras mídias pausadas e volume travado
 * ([EmAndamento.volumeTravado]). Só enquanto toca, cada bipe põe o volume de alarme do celular no
 * seu degrau, do levinho ao máximo, por cima da trava. Usada só pelo [AlarmeService].
 *
 * Tudo usa USAGE_ALARM: o volume de alarme não depende do modo silencioso, e o Não Perturbe
 * deixa passar quando "alarmes" estão permitidos (o padrão do Android). E todo som sai no
 * alto-falante do celular, nunca no Bluetooth, no fone ou em outro aparelho conectado.
 */
class Sirene(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var bipador: MediaPlayer? = null
    private var vibrador: Vibrator? = null
    private var foco: AudioFocusRequest? = null
    private var musica: String? = null
    private var volumeTravado = 0
    private var volumeAntesDoBipe: Int? = null // só enquanto um bipe toca no degrau dele

    /** Desfaz a cada segundo qualquer tentativa de abaixar o volume do alarme. Durante um bipe, quem manda é ele. */
    private val travarVolume = object : Runnable {
        override fun run() {
            if (volumeAntesDoBipe == null && audio.getStreamVolume(AudioManager.STREAM_ALARM) < volumeTravado) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volumeTravado, 0) }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    /** Fim do bipe: o volume de alarme volta ao de antes dele, nunca abaixo da trava. */
    private val voltarDoBipe = Runnable {
        volumeAntesDoBipe?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, maxOf(it, volumeTravado), 0) } }
        volumeAntesDoBipe = null
    }

    /**
     * Música e volume vêm da foto do alarme [em]; a trava do volume vale até [desligar]. No [comeco]
     * (o disparo, ou a volta depois de o app cair), o volume vai pro da foto: assim um app que caiu no
     * meio de um bipe não deixa o alarme preso no degrau dele.
     */
    fun preparar(em: EmAndamento, comeco: Boolean) {
        musica = em.musica
        volumeTravado = em.volumeTravado(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        if (comeco) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, maxOf(em.volume, volumeTravado), 0) }
        handler.removeCallbacks(travarVolume)
        handler.post(travarVolume)
    }

    /** Som e vibração (do começo, se já estavam tocando). */
    fun tocar() {
        calar()
        // Foco de áudio pausa a música ou o vídeo de outros apps até o alarme acabar ([desligar]).
        // Pedido de novo a cada vez: se outro app tomou o foco no meio, ele pausa de novo
        val pedido = foco ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ATRIBUTOS)
            .build()
            .also { foco = it }
        audio.requestAudioFocus(pedido)
        tocarSom()
        vibrar()
    }

    /** Cala som e vibração (um bipe no meio devolve o volume). O volume continua travado, e as outras mídias, pausadas. */
    fun calar() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        bipador?.release()
        bipador = null
        handler.removeCallbacks(voltarDoBipe)
        voltarDoBipe.run()
        vibrador?.cancel()
        vibrador = null
    }

    /** Volume da música, de 0 a 1, relativo ao volume de alarme (a missão abaixa pra ouvir a voz). */
    fun volume(fator: Float) {
        player?.setVolume(fator, fator)
    }

    /**
     * Bipe curto número [n] da vigia (a pessoa parou de olhar pra câmera): por [BIPE_MS], o volume de
     * alarme fica no degrau dele ([volumeDoBipe]), por cima da trava; depois, volta ao de antes.
     */
    fun bipe(n: Int) {
        val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val degrau = volumeDoBipe(n, maximo)
        if (volumeAntesDoBipe == null) volumeAntesDoBipe = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, degrau, 0) }
        Log.i(TAG, "Sirene: bipe $n no volume $degrau de $maximo")
        bipador?.release()
        bipador = tocarArquivo(somDoApp(R.raw.bipe), emLoop = false)
        handler.removeCallbacks(voltarDoBipe)
        handler.postDelayed(voltarDoBipe, BIPE_MS)
    }

    /** Fim: cala, solta a trava e as outras mídias e, com [volumeAntes], devolve o volume de alarme de antes. */
    fun desligar(volumeAntes: Int? = null) {
        handler.removeCallbacks(travarVolume)
        calar()
        foco?.let { audio.abandonAudioFocusRequest(it) }
        foco = null
        volumeAntes?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
    }

    /**
     * A música da foto do alarme; senão o toque de alarme do celular; se nada puder ser lido
     * (ex.: antes do primeiro desbloqueio), os bipes do app.
     */
    private fun tocarSom() {
        val candidatos = listOfNotNull(
            musica?.toUri(),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            somDoApp(R.raw.alarme_reserva),
        )
        for (uri in candidatos) {
            player = tocarArquivo(uri, emLoop = true)
            if (player != null) {
                Log.i(TAG, "Sirene: tocando $uri")
                return
            }
            Log.w(TAG, "Sirene: não conseguiu tocar $uri")
        }
    }

    /** Um som que vem dentro do app (res/raw). */
    private fun somDoApp(id: Int) = "android.resource://${ctx.packageName}/$id".toUri()

    /** Toca [uri] no alto-falante do celular, no volume de alarme. Null se não deu pra ler. */
    private fun tocarArquivo(uri: Uri, emLoop: Boolean): MediaPlayer? {
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(ATRIBUTOS)
            mp.setDataSource(ctx, uri)
            noAltoFalante(mp) // só depois do setDataSource: antes dele o MediaPlayer ignora a saída escolhida
            mp.isLooping = emLoop
            mp.prepare()
            mp.start()
            mp
        }.getOrElse {
            mp.release()
            null
        }
    }

    /**
     * Fixa a saída no alto-falante do celular: com fone ou Bluetooth conectado, o Android tocaria o
     * alarme neles também (ou só neles). Antes do Android 9 o MediaPlayer não tem como escolher a saída.
     */
    private fun noAltoFalante(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < 28) return
        val alto = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (alto == null || !mp.setPreferredDevice(alto)) Log.w(TAG, "Sirene: não conseguiu fixar o som no alto-falante")
    }

    @Suppress("DEPRECATION") // as APIs antigas só são usadas nas versões do Android que não têm as novas
    private fun vibrar() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        val efeito = VibrationEffect.createWaveform(longArrayOf(0, 900, 400, 900, 400, 1_800, 700), 1)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(efeito, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(efeito, ATRIBUTOS)
        }
        vibrador = v
    }

    private companion object {
        /** Quanto tempo o volume fica no degrau do bipe: o som dura 0,32 s, e o resto é folga pra ele sair do alto-falante. */
        const val BIPE_MS = 1_000L

        val ATRIBUTOS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
