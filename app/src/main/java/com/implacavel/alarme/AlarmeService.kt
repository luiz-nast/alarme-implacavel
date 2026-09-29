package com.implacavel.alarme

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * Serviço em primeiro plano que mantém o alarme tocando até o usuário desligar ou adiar.
 * Liga a [Sirene] e mostra a notificação que abre a [AlarmeActivity] em tela cheia.
 *
 * Recebe comandos pela action do Intent: tocar, parar, adiar e reexibir.
 */
class AlarmeService : Service() {

    companion object {
        private const val ACAO_TOCAR = "tocar"
        private const val ACAO_PARAR = "parar"
        private const val ACAO_ADIAR = "adiar"
        private const val ACAO_REEXIBIR = "reexibir"

        /** Sem resposta por 10 minutos, adia sozinho (e volta a tocar na soneca). */
        private const val LIMITE_TOCANDO_MS = 10 * 60_000L

        private val _tocando = MutableStateFlow<Alarme?>(null)

        /** Alarme tocando agora (null = nenhum). A AlarmeActivity e a tela principal observam isso. */
        val tocando: StateFlow<Alarme?> = _tocando.asStateFlow()

        fun tocar(ctx: Context, id: Int) {
            // Só falha se o disparo veio sem alarme exato (Android 12 sem a permissão): aí o sistema
            // não deixa iniciar serviço em segundo plano e não há o que fazer
            runCatching {
                ContextCompat.startForegroundService(ctx, comando(ctx, ACAO_TOCAR).putExtra(Agendador.EXTRA_ID, id))
            }
        }

        fun parar(ctx: Context) {
            ctx.startService(comando(ctx, ACAO_PARAR))
        }

        fun adiar(ctx: Context) {
            ctx.startService(comando(ctx, ACAO_ADIAR))
        }

        private fun comando(ctx: Context, acao: String) = Intent(ctx, AlarmeService::class.java).setAction(acao)
    }

    private lateinit var sirene: Sirene
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val esgotou = Runnable { adiarEEncerrar() }

    override fun onCreate() {
        super.onCreate()
        sirene = Sirene(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_TOCAR -> iniciar(intent.getIntExtra(Agendador.EXTRA_ID, -1))
            ACAO_ADIAR -> adiarEEncerrar()
            // Desde o Android 14 dá pra arrastar a notificação pro lado; ela volta enquanto o alarme tocar
            ACAO_REEXIBIR -> _tocando.value?.let { mostrarNotificacao(it) } ?: encerrar()
            else -> encerrar()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(esgotou)
        sirene.desligar()
        _tocando.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun iniciar(id: Int) {
        val alarme = if (id == Alarme.ID_TESTE) Alarme.teste() else Alarmes.buscar(id)
        if (alarme == null) {
            // Depois de startForegroundService o Android exige mostrar a notificação, mesmo pra parar em seguida
            mostrarNotificacao(Alarme.teste())
            encerrar()
            return
        }
        _tocando.value = alarme
        mostrarNotificacao(alarme)
        sirene.ligar(alarme.volumeMaximo)
        segurarProcessador()
        handler.removeCallbacks(esgotou)
        handler.postDelayed(esgotou, LIMITE_TOCANDO_MS)
    }

    private fun adiarEEncerrar() {
        _tocando.value?.let {
            Agendador.agendarSoneca(this, it.id)
            val volta = hhmm(LocalTime.now().plusMinutes(Agendador.SONECA_MINUTOS.toLong()))
            Toast.makeText(this, "Adiado pra $volta", Toast.LENGTH_LONG).show()
        }
        encerrar()
    }

    /** A limpeza (sirene, wake lock, estado) acontece em [onDestroy]. */
    private fun encerrar() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Vira serviço em primeiro plano exibindo a notificação do alarme. */
    private fun mostrarNotificacao(alarme: Alarme) {
        val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notificacoes.ID_TOCANDO, criarNotificacao(alarme), tipo)
    }

    private fun criarNotificacao(alarme: Alarme): Notification {
        val telaCheia = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Com o desafio ligado, "Desligar" abre a tela da conta em vez de desligar direto
        val desligar = if (alarme.desafio) telaCheia else comandoPendente(1, ACAO_PARAR)
        val agora = hhmm(LocalTime.now())
        return NotificationCompat.Builder(this, Notificacoes.CANAL_ALARME)
            .setSmallIcon(R.drawable.ic_alarme)
            .setContentTitle(alarme.rotulo.ifBlank { "Alarme" })
            .setContentText(if (alarme.desafio) "$agora · resolva a conta pra desligar" else "$agora · tocando")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColor(0xFFB71C1C.toInt())
            .setColorized(true)
            .setContentIntent(telaCheia)
            .setFullScreenIntent(telaCheia, true)
            .setDeleteIntent(comandoPendente(2, ACAO_REEXIBIR))
            .addAction(0, "Adiar ${Agendador.SONECA_MINUTOS} min", comandoPendente(3, ACAO_ADIAR))
            .addAction(0, "Desligar", desligar)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun comandoPendente(codigo: Int, acao: String): PendingIntent =
        PendingIntent.getService(this, codigo, comando(this, acao), PendingIntent.FLAG_IMMUTABLE)

    /** Mantém o processador acordado enquanto toca (quem acende a tela é a AlarmeActivity). */
    private fun segurarProcessador() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmeImplacavel:tocando")
            .apply { acquire(LIMITE_TOCANDO_MS + 60_000L) }
    }
}
