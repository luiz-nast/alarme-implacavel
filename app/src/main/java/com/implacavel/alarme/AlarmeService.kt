package com.implacavel.alarme

import android.app.Notification
import android.app.NotificationManager
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
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * Serviço em primeiro plano que mantém o alarme ativo até a missão ser cumprida (ou até o botão
 * DESLIGAR, em alarme sem missão). Controla a [Sirene] e a notificação que abre a [AlarmeActivity]
 * em tela cheia, e não deixa o alarme ser enrolado:
 * - tela do alarme fechada (Home, arrastar o app, apagar a tela): em [TELA_FECHADA_MS] a música
 *   volta e a tela reabre;
 * - depois do "stop", a música só fica calada enquanto a câmera avisa ("olhando") que a pessoa
 *   olha; sem aviso por [DESISTENCIA_MS], a vigia religa a música;
 * - o alarme fica gravado em [Ajustes.alarmeEmAndamento] até ser cumprido: se o celular desligar
 *   ou o app morrer no meio, o [App] religa o alarme quando o processo voltar;
 * - sem a missão cumprida em [LIMITE_TOCANDO_MS] (ex.: ninguém em casa), pausa [PAUSA_MINUTOS] e volta.
 *
 * Comandos pela action do Intent, cada um com uma função no companion: tocar, parar, tela,
 * silenciar, olhando e volume. O comando reexibir vem da notificação arrastada pro lado.
 */
class AlarmeService : Service() {

    companion object {
        private const val ACAO_TOCAR = "tocar"
        private const val ACAO_PARAR = "parar"
        private const val ACAO_REEXIBIR = "reexibir"
        private const val ACAO_TELA = "tela"
        private const val ACAO_SILENCIAR = "silenciar"
        private const val ACAO_OLHANDO = "olhando"
        private const val ACAO_VOLUME = "volume"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_ABERTA = "aberta"

        private const val LIMITE_TOCANDO_MS = 10 * 60_000L
        private const val PAUSA_MINUTOS = 5L

        /**
         * Tela do alarme fechada no meio: tempo até a música voltar e a tela reabrir. O Android ainda leva
         * de 0,5 a 3 s pra mostrar a tela cheia; com 1 s aqui, o total fica abaixo de 5 s.
         */
        private const val TELA_FECHADA_MS = 1_000L

        private val _tocando = MutableStateFlow<Alarme?>(null)

        /** Alarme tocando agora (null = nenhum). A AlarmeActivity e a tela principal observam isso. */
        val tocando: StateFlow<Alarme?> = _tocando.asStateFlow()

        private val _silenciado = MutableStateFlow(false)

        /** true = a pessoa já disse "stop" e a música está calada: a tela do alarme mostra a etapa da câmera. */
        val silenciado: StateFlow<Boolean> = _silenciado.asStateFlow()

        fun tocar(ctx: Context, id: Int) {
            // Só falha se o disparo veio sem alarme exato (Android 12 sem a permissão): aí o sistema
            // não deixa iniciar serviço em segundo plano e não há o que fazer
            runCatching {
                ContextCompat.startForegroundService(ctx, comando(ctx, ACAO_TOCAR).putExtra(Agendador.EXTRA_ID, id))
            }.onFailure { Log.e(TAG, "Serviço: o Android não deixou começar a tocar", it) }
        }

        /** Missão cumprida (ou DESLIGAR, em alarme sem missão). */
        fun parar(ctx: Context) { ctx.startService(comando(ctx, ACAO_PARAR)) }

        /** A tela do alarme apareceu ou sumiu (a AlarmeActivity avisa em onStart e onStop). */
        fun tela(ctx: Context, aberta: Boolean) {
            if (_tocando.value != null) ctx.startService(comando(ctx, ACAO_TELA).putExtra(EXTRA_ABERTA, aberta))
        }

        /** A pessoa disse "stop": cala a música sem encerrar o alarme e liga a vigia. */
        fun silenciar(ctx: Context) { ctx.startService(comando(ctx, ACAO_SILENCIAR)) }

        /** Sinal da etapa da câmera de que a pessoa está olhando: a vigia espera mais [DESISTENCIA_MS]. */
        fun olhando(ctx: Context) { ctx.startService(comando(ctx, ACAO_OLHANDO)) }

        /** Volume da música, de 0 a 1, relativo ao volume de alarme. */
        fun volume(ctx: Context, fator: Float) { ctx.startService(comando(ctx, ACAO_VOLUME).putExtra(EXTRA_VOLUME, fator)) }

        private fun comando(ctx: Context, acao: String) = Intent(ctx, AlarmeService::class.java).setAction(acao)
    }

    private lateinit var sirene: Sirene
    private var wakeLock: PowerManager.WakeLock? = null
    private var telaAberta = true
    private val handler = Handler(Looper.getMainLooper())
    private val esgotou = Runnable { pausarEEncerrar() }

    /** Vigia da missão: dispara se a música ficou calada [DESISTENCIA_MS] sem sinal de que a pessoa olha. */
    private val vigia = Runnable {
        Log.i(TAG, "Missão: ${DESISTENCIA_MS / 1000} s sem olhar pra câmera, música volta")
        religarMusica()
        _tocando.value?.let { mostrarNotificacao(it) } // alerta de novo
    }

    /** A tela do alarme sumiu e não voltou: música de volta e tela reaberta por uma nova notificação em tela cheia. */
    private val chamarDeVolta = Runnable {
        val alarme = _tocando.value ?: return@Runnable
        Log.i(TAG, "Tela do alarme fechada há $TELA_FECHADA_MS ms: música volta e a tela reabre")
        religarMusica()
        notificacoes().notify(Notificacoes.ID_CHAMADA, criarNotificacao(alarme))
    }

    override fun onCreate() {
        super.onCreate()
        sirene = Sirene(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        if (acao == ACAO_TOCAR || acao == ACAO_PARAR || acao == ACAO_SILENCIAR) Log.i(TAG, "Serviço: comando $acao")
        val alarme = _tocando.value
        when {
            acao == ACAO_TOCAR -> iniciar(intent.getIntExtra(Agendador.EXTRA_ID, -1))
            // Comando que chega quando o alarme já acabou só encerra o serviço
            acao == ACAO_PARAR || alarme == null -> encerrar()
            // Desde o Android 14 dá pra arrastar a notificação pro lado; ela volta enquanto o alarme durar
            acao == ACAO_REEXIBIR -> mostrarNotificacao(alarme)
            acao == ACAO_TELA -> telaMudou(intent.getBooleanExtra(EXTRA_ABERTA, true))
            acao == ACAO_SILENCIAR -> silenciarMusica()
            acao == ACAO_OLHANDO -> if (_silenciado.value) adiarVigia()
            // Com a tela fechada não há janela de escuta: a música fica no volume cheio
            acao == ACAO_VOLUME -> if (telaAberta) sirene.volume(intent.getFloatExtra(EXTRA_VOLUME, 1f))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Serviço: alarme encerrado")
        handler.removeCallbacksAndMessages(null)
        notificacoes().cancel(Notificacoes.ID_CHAMADA)
        sirene.desligar()
        _silenciado.value = false
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
        if (_tocando.value?.id == alarme.id) {
            // Disparo repetido do mesmo alarme (ex.: a retomada depois de o app morrer): segue de onde está
            mostrarNotificacao(alarme)
            return
        }
        Log.i(TAG, "Serviço: tocando \"${alarme.nome}\" (missão=${alarme.missao}, volume máximo=${alarme.volumeMaximo})")
        // Se outro alarme estava tocando, este recomeça do zero: sem vigia, sem tela fechada, com limite novo
        handler.removeCallbacksAndMessages(null)
        telaAberta = true
        _silenciado.value = false
        _tocando.value = alarme
        Ajustes.alarmeEmAndamento = alarme.id
        mostrarNotificacao(alarme)
        sirene.ligar(alarme.volumeMaximo)
        segurarProcessador()
        handler.postDelayed(esgotou, LIMITE_TOCANDO_MS)
    }

    private fun telaMudou(aberta: Boolean) {
        Log.i(TAG, "Tela do alarme: ${if (aberta) "aberta" else "fechada"}")
        telaAberta = aberta
        handler.removeCallbacks(chamarDeVolta)
        if (aberta) {
            notificacoes().cancel(Notificacoes.ID_CHAMADA)
        } else {
            sirene.volume(1f)
            handler.postDelayed(chamarDeVolta, TELA_FECHADA_MS)
        }
    }

    /** A pessoa disse "stop": a música para e a vigia começa a contar. */
    private fun silenciarMusica() {
        sirene.desligar()
        _silenciado.value = true
        adiarVigia()
    }

    private fun adiarVigia() {
        handler.removeCallbacks(vigia)
        handler.postDelayed(vigia, DESISTENCIA_MS)
    }

    /** Música de volta no volume cheio; se estava calada, a missão volta pra etapa de falar. */
    private fun religarMusica() {
        handler.removeCallbacks(vigia)
        val alarme = _tocando.value ?: return
        if (_silenciado.value) {
            _silenciado.value = false
            sirene.ligar(alarme.volumeMaximo)
        } else {
            sirene.volume(1f)
        }
    }

    /** [LIMITE_TOCANDO_MS] sem a missão cumprida: pausa e volta depois de [PAUSA_MINUTOS]. */
    private fun pausarEEncerrar() {
        _tocando.value?.let {
            Agendador.tocarDaqui(this, it.id, PAUSA_MINUTOS * 60_000, gravar = true)
            val volta = hhmm(LocalTime.now().plusMinutes(PAUSA_MINUTOS))
            Log.i(TAG, "Serviço: ${LIMITE_TOCANDO_MS / 60_000} min sem cumprir a missão, volta às $volta")
            Toast.makeText(this, "Alarme volta às $volta", Toast.LENGTH_LONG).show()
        }
        encerrar()
    }

    /** Fim do alarme: missão cumprida, DESLIGAR ou pausa automática. A limpeza acontece em [onDestroy]. */
    private fun encerrar() {
        Ajustes.alarmeEmAndamento = null
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
        // Com a missão ligada, "Desligar" abre a tela da missão em vez de desligar direto
        val desligar = if (alarme.missao) telaCheia else comandoPendente(1, ACAO_PARAR)
        val agora = hhmm(LocalTime.now())
        return NotificationCompat.Builder(this, Notificacoes.CANAL_ALARME)
            .setSmallIcon(R.drawable.ic_alarme)
            .setContentTitle(alarme.nome)
            .setContentText(if (alarme.missao) "$agora · diga STOP e olhe pra câmera" else "$agora · tocando")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColor(getColor(R.color.vermelho_alarme))
            .setColorized(true)
            .setContentIntent(telaCheia)
            .setFullScreenIntent(telaCheia, true)
            .setDeleteIntent(comandoPendente(2, ACAO_REEXIBIR))
            .addAction(0, "Desligar", desligar)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun comandoPendente(codigo: Int, acao: String): PendingIntent =
        PendingIntent.getService(this, codigo, comando(this, acao), PendingIntent.FLAG_IMMUTABLE)

    private fun notificacoes() = getSystemService(NotificationManager::class.java)

    /** Mantém o processador acordado enquanto toca (quem acende a tela é a AlarmeActivity). */
    private fun segurarProcessador() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmeImplacavel:tocando")
            .apply { acquire(LIMITE_TOCANDO_MS + 60_000L) }
    }
}
