package com.implacavel.alarme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.net.toUri

/**
 * O barulho do alarme: música ou toque em loop, vibração contínua, pausa de outras mídias e, se
 * pedido, volume travado no máximo. Usada só pelo [AlarmeService].
 *
 * Tudo usa USAGE_ALARM: o volume de alarme não depende do modo silencioso, e o Não Perturbe
 * deixa passar quando "alarmes" estão permitidos (o padrão do Android).
 */
class Sirene(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrador: Vibrator? = null
    private var foco: AudioFocusRequest? = null
    private var volumeOriginal: Int? = null

    /** Desfaz a cada segundo qualquer tentativa de abaixar o volume do alarme. */
    private val travarVolume = object : Runnable {
        override fun run() {
            val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < maximo) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, maximo, 0) }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    fun ligar(volumeMaximo: Boolean) {
        desligar()
        if (volumeMaximo) {
            volumeOriginal = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            handler.post(travarVolume)
        }
        // Foco de áudio pausa a música ou o vídeo que estiver tocando
        foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ATRIBUTOS)
            .build()
            .also { audio.requestAudioFocus(it) }
        tocarSom()
        vibrar()
    }

    /** Volume da música, de 0 a 1, relativo ao volume de alarme (a missão abaixa pra ouvir a voz). */
    fun volume(fator: Float) {
        player?.setVolume(fator, fator)
    }

    fun desligar() {
        handler.removeCallbacks(travarVolume)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrador?.cancel()
        vibrador = null
        foco?.let { audio.abandonAudioFocusRequest(it) }
        foco = null
        volumeOriginal?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        volumeOriginal = null
    }

    /**
     * A música escolhida em [Ajustes]; senão o toque de alarme do celular; se nada puder ser lido
     * (ex.: antes do primeiro desbloqueio), os bipes do app.
     */
    private fun tocarSom() {
        val candidatos = listOfNotNull(
            Ajustes.musica.value?.uri?.toUri(),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            "android.resource://${ctx.packageName}/${R.raw.alarme_reserva}".toUri(),
        )
        for (uri in candidatos) {
            val mp = MediaPlayer()
            val tocou = runCatching {
                mp.setAudioAttributes(ATRIBUTOS)
                mp.setDataSource(ctx, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
            }.isSuccess
            if (tocou) {
                player = mp
                return
            }
            mp.release()
        }
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
        val ATRIBUTOS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
