package com.implacavel.alarme

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Serviço em primeiro plano que toca o alarme em andamento ([Ajustes.emAndamento], gravado pelo
 * [AlarmeReceiver]) até a missão ser cumprida, ou até o DESLIGAR, em alarme sem missão: esse é o
 * único fim do alarme ([concluir]). Controla a [Sirene] e a notificação que abre a
 * [AlarmeActivity] em tela cheia, e não deixa ninguém fugir:
 * - tela do alarme fechada (Home, arrastar o app, apagar a tela): em [TELA_FECHADA_MS] a música
 *   volta e a tela reabre; o anel da câmera fica onde estava;
 * - depois do "stop", a voz do celular dá bom dia, [PAUSA_DEPOIS_DA_SAUDACAO_MS] depois diz "Activity
 *   starting now", toca o bipe positivo e começa a câmera; a cada 10 min completos, a voz avisa;
 * - na metade, o anel para e vem a verificação: a voz fala 5 frases ([frasesDoDia]) e a pessoa repete
 *   cada uma; sem responder por [SILENCIO_APITO_MS], um apito alto, e em [SILENCIO_ZERA_MS] o anel zera e a
 *   música volta. Depois dela, a câmera fica mais exigente com os olhos;
 *   daí a música só fica calada enquanto a câmera avisa ("olhando") que a pessoa
 *   olha; sem aviso, a vigia bipa a cada [AVISO_SEM_OLHAR_MS], cada vez mais alto (o último, no
 *   volume máximo do celular), e em [DESISTENCIA_MS] religa a música (o anel zera);
 * - a cada [RENOVAR_MS], empurra pra frente a retomada no AlarmManager: se o app cair, travar ou
 *   for encerrado, ou se o celular desligar, o alarme volta sozinho;
 * - se o app caiu [LIMITE_QUEDAS] vezes neste alarme, é defeito, não truque: ele volta com o
 *   DESLIGAR, pra um defeito não prender ninguém num alarme que nunca desliga. Numa queda só,
 *   o desafio continua valendo.
 *
 * Comandos pela action do Intent, cada um com uma função no companion: tocar, missão cumprida,
 * desligar, tela, silenciar, olhando, volume e dar tempo. O comando reexibir vem da notificação
 * arrastada pro lado.
 */
/** Um passo da verificação do meio: a [frase] da vez ([numero] de 5; vazia na fala de abertura e no agradecimento) e se é a [suaVez] de repetir. */
data class Verificacao(val frase: String, val numero: Int, val suaVez: Boolean)

class AlarmeService : Service() {

    companion object {
        private const val ACAO_TOCAR = "tocar"
        private const val ACAO_CONCLUIR = "concluir"
        private const val ACAO_DESLIGAR = "desligar"
        private const val ACAO_REEXIBIR = "reexibir"
        private const val ACAO_TELA = "tela"
        private const val ACAO_SILENCIAR = "silenciar"
        private const val ACAO_OLHANDO = "olhando"
        private const val ACAO_VOLUME = "volume"
        private const val ACAO_DAR_TEMPO = "dar_tempo"
        private const val ACAO_OUVIU = "ouviu"
        private const val ACAO_REPETIU = "repetiu"
        private const val ACAO_SEM_VOZ = "sem_voz"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_ABERTA = "aberta"
        private const val EXTRA_OLHADO = "olhado"
        private const val EXTRA_COMEMORAR = "comemorar"

        /**
         * Tela do alarme fechada no meio: tempo até a música voltar e a tela reabrir. O Android ainda leva
         * de 0,5 a 3 s pra mostrar a tela cheia; com 1 s aqui, o total fica abaixo de 5 s.
         */
        private const val TELA_FECHADA_MS = 1_000L

        /** De quanto em quanto tempo a retomada é empurrada pra frente ([Agendador.RETOMADA_MS]). */
        private const val RENOVAR_MS = 5_000L

        /** Tempo sem a tela do alarme voltar, pra desbloquear o celular ou liberar a câmera. A música continua. */
        private const val TEMPO_FORA_MS = 60_000L

        private const val LIMITE_QUEDAS = 2

        /** Entre a saudação falada e o "Activity starting now". */
        private const val PAUSA_DEPOIS_DA_SAUDACAO_MS = 1_500L

        /** Entre o "Let's verify..." e a primeira frase da verificação. */
        private const val PAUSA_ANTES_DAS_FRASES_MS = 1_000L

        /** Se os sons do fim travarem, o alarme termina assim mesmo depois disso. */
        private const val COMEMORACAO_MAX_MS = 20_000L

        /** Se a voz travar, a câmera começa assim mesmo depois disso. */
        private const val SAUDACAO_MAX_MS = 20_000L

        /** Falas fixas, gravadas assim que a voz fica pronta: o início e os avisos de 10, 20 e 30 min. */
        private const val FALA_DO_INICIO = "inicio"
        private const val FALA_DO_FIM = "fim"
        private const val FALA_DE_VERIFICAR = "verificar"
        private const val FALA_DA_METADE = "metade"
        private val FALAS_FIXAS = mapOf(FALA_DO_INICIO to FALA_INICIO, FALA_DO_FIM to FALA_FIM, FALA_DE_VERIFICAR to FALA_VERIFICAR, FALA_DA_METADE to FALA_METADE) +
            (1 until (META_OLHAR_MS / MARCO_MS).toInt()).associate { "minutos_${it * 10}" to falaDosMinutos(it * 10) }

        private val _tocando = MutableStateFlow<Alarme?>(null)

        /** Alarme tocando agora (null = nenhum). A AlarmeActivity e a tela principal observam isso. */
        val tocando: StateFlow<Alarme?> = _tocando.asStateFlow()

        private val _silenciado = MutableStateFlow(false)

        /** true = a pessoa já disse "stop" e a música está calada: a tela do alarme mostra a etapa da câmera. */
        val silenciado: StateFlow<Boolean> = _silenciado.asStateFlow()

        private val _saudando = MutableStateFlow(false)

        /** true = acabou de dizer "stop" e a voz está dando bom dia: a tela mostra a saudação, ainda sem câmera. */
        val saudando: StateFlow<Boolean> = _saudando.asStateFlow()

        private val _comemorando = MutableStateFlow(false)

        /** true = a missão foi cumprida e o app comemora (bipe leve, parabéns e o estouro) antes de fechar. */
        val comemorando: StateFlow<Boolean> = _comemorando.asStateFlow()

        private val _verificacao = MutableStateFlow<Verificacao?>(null)

        /** A verificação do meio em andamento (null = fora dela): a tela mostra a frase e, na vez da pessoa, escuta. */
        val verificacao: StateFlow<Verificacao?> = _verificacao.asStateFlow()

        private val _metadeVerificada = MutableStateFlow(false)

        /** A verificação desta rodada já foi feita: o anel passa da metade e a câmera fica mais exigente. */
        val metadeVerificada: StateFlow<Boolean> = _metadeVerificada.asStateFlow()

        private val _ultimaResposta = MutableStateFlow(0L)

        /** Quando (SystemClock.elapsedRealtime) chegou a vez ou a última fala da pessoa na verificação: a tela conta daqui o tempo pra zerar. */
        val ultimaResposta: StateFlow<Long> = _ultimaResposta.asStateFlow()

        private val _ultimoOlhar = MutableStateFlow(0L)

        /**
         * Quando (SystemClock.elapsedRealtime) chegou o último sinal de "olhando", ou o "stop". A vigia
         * conta daqui os bipes e o tempo pra zerar, e a tela do alarme mostra daqui a contagem.
         */
        val ultimoOlhar: StateFlow<Long> = _ultimoOlhar.asStateFlow()

        private val _olhado = MutableStateFlow(0L)

        /**
         * Tempo de olhos abertos já somado no anel da câmera, em ms. Só sobe com a música calada e só
         * zera nos [DESISTENCIA_MS] sem olhar (e no fim do alarme). Sair da tela traz a música de
         * volta, mas o anel fica: depois do "stop", a câmera continua daqui, e a tela recriada no meio também.
         */
        val olhado: StateFlow<Long> = _olhado.asStateFlow()

        /** Toca o alarme em andamento (ou atualiza o que já toca). */
        fun tocar(ctx: Context) {
            // Só falha se o disparo veio sem alarme exato (Android 12 sem a permissão): aí o sistema
            // não deixa iniciar serviço em segundo plano e não há o que fazer
            runCatching { ContextCompat.startForegroundService(ctx, comando(ctx, ACAO_TOCAR)) }
                .onFailure { Log.e(TAG, "Serviço: o Android não deixou começar a tocar", it) }
        }

        /** O anel da câmera fechou (ou a câmera deu defeito): fim do alarme. */
        fun missaoCumprida(ctx: Context, comemorar: Boolean = true) {
            ctx.startService(comando(ctx, ACAO_CONCLUIR).putExtra(EXTRA_COMEMORAR, comemorar))
        }

        /** Botão DESLIGAR. Só vale pra alarme sem missão (ou liberado por defeito). */
        fun desligar(ctx: Context) { ctx.startService(comando(ctx, ACAO_DESLIGAR)) }

        /** A tela do alarme apareceu ou sumiu (a AlarmeActivity avisa em onStart e onStop). */
        fun tela(ctx: Context, aberta: Boolean) {
            if (_tocando.value != null) ctx.startService(comando(ctx, ACAO_TELA).putExtra(EXTRA_ABERTA, aberta))
        }

        /** A pessoa disse "stop": cala a música sem encerrar o alarme e liga a vigia. */
        fun silenciar(ctx: Context) { ctx.startService(comando(ctx, ACAO_SILENCIAR)) }

        /**
         * Sinal da etapa da câmera de que a pessoa está olhando (ou olhava até agora), com o tempo
         * [olhadoMs] já somado no anel: a vigia recomeça do zero.
         */
        fun olhando(ctx: Context, olhadoMs: Long) { ctx.startService(comando(ctx, ACAO_OLHANDO).putExtra(EXTRA_OLHADO, olhadoMs)) }

        /** Volume da música, de 0 a 1, relativo ao volume de alarme. */
        fun volume(ctx: Context, fator: Float) { ctx.startService(comando(ctx, ACAO_VOLUME).putExtra(EXTRA_VOLUME, fator)) }

        /** A pessoa vai desbloquear o celular ou liberar a câmera: por [TEMPO_FORA_MS] a tela do alarme não reabre. */
        fun darTempo(ctx: Context) { ctx.startService(comando(ctx, ACAO_DAR_TEMPO)) }

        /** Na verificação, o reconhecedor ouviu alguma fala: a pessoa está respondendo. */
        fun ouviu(ctx: Context) { ctx.startService(comando(ctx, ACAO_OUVIU)) }

        /** Na verificação, a pessoa repetiu a frase da vez. */
        fun repetiu(ctx: Context) { ctx.startService(comando(ctx, ACAO_REPETIU)) }

        /** Na verificação, o reconhecimento de voz não funciona (ex.: inglês sem internet): ela é pulada. */
        fun semVozNaVerificacao(ctx: Context) { ctx.startService(comando(ctx, ACAO_SEM_VOZ)) }

        private fun comando(ctx: Context, acao: String) = Intent(ctx, AlarmeService::class.java).setAction(acao)
    }

    private lateinit var sirene: Sirene
    private var voz: Voz? = null // só depois do primeiro desbloqueio ([prepararVoz])
    private var rodadas = 0 // saudações pedidas: cada uma grava num arquivo seu
    private var esperaDesbloqueio: BroadcastReceiver? = null
    private lateinit var processador: PowerManager.WakeLock
    private var telaAberta = true
    private var foraAte = 0L // SystemClock.elapsedRealtime
    private var bipes = 0 // da vigia, desde o último sinal de "olhando"
    private var cumprido = false // depois disso, só a comemoração: nada traz a música de volta
    private val frasesDeHoje = frasesDoDia(LocalDate.now())
    private var apitos = 0 // da verificação, desde a última fala da pessoa
    private var vezComecou = 0L // SystemClock.elapsedRealtime em que começou a vez da pessoa na frase atual
    private var rodadaVerificacao = 0 // muda ao começar ou cancelar: sons atrasados de uma rodada velha não avançam a nova
    private var volumeAoFim: Int? = null // o volume de antes do alarme cumprido, devolvido ao fechar
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Enquanto toca: empurra a retomada pra frente (se o app morrer, ela dispara e o alarme volta) e
     * mantém o processador acordado (quem acende a tela é a AlarmeActivity).
     */
    private val renovar = object : Runnable {
        override fun run() {
            Agendador.agendarRetomada(this@AlarmeService)
            processador.acquire(RENOVAR_MS * 12)
            handler.postDelayed(this, RENOVAR_MS)
        }
    }

    /**
     * Vigia da missão, com a música calada: a cada [AVISO_SEM_OLHAR_MS] sem sinal de que a pessoa
     * olha, um bipe mais alto que o anterior; em [DESISTENCIA_MS], o anel zera e a música volta.
     */
    private val vigia = object : Runnable {
        override fun run() {
            if (bipes < BIPES_ATE_ZERAR) {
                bipes++
                Log.i(TAG, "Missão: ${bipes * AVISO_SEM_OLHAR_MS / 1000} s sem olhar pra câmera, bipe $bipes")
                sirene.bipe(bipes)
                // O próximo bipe; depois do último, o resto do tempo até zerar
                handler.postDelayed(this, if (bipes < BIPES_ATE_ZERAR) AVISO_SEM_OLHAR_MS else DESISTENCIA_MS - bipes * AVISO_SEM_OLHAR_MS)
                return
            }
            Log.i(TAG, "Missão: ${DESISTENCIA_MS / 1000} s sem olhar pra câmera, anel zera (estava em ${_olhado.value / 1000} s) e a música volta")
            zerarAnel()
            religarMusica()
            _tocando.value?.let { mostrarNotificacao(it) } // alerta de novo
        }
    }

    /**
     * A tela do alarme sumiu e não voltou: música de volta e tela reaberta por uma nova notificação em
     * tela cheia. O anel da câmera fica onde estava (com 40 min, uma ligação custaria tudo).
     */
    private val chamarDeVolta = Runnable {
        val alarme = _tocando.value ?: return@Runnable
        Log.i(TAG, "Tela do alarme fechada: música volta e a tela reabre (anel guardado em ${_olhado.value / 1000} s)")
        religarMusica()
        notificacoes().notify(Notificacoes.ID_CHAMADA, criarNotificacao(alarme))
    }

    override fun onCreate() {
        super.onCreate()
        sirene = Sirene(this)
        prepararVoz()
        processador = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmeImplacavel:tocando")
            .apply { setReferenceCounted(false) }
    }

    /**
     * A voz (falas do alarme) abre só com o celular desbloqueado desde que ligou: antes disso o
     * sintetizador não abre e os arquivos não podem ser gravados. Alarme tocando antes do primeiro
     * desbloqueio: a voz abre no desbloqueio, que a missão pede antes do STOP (EtapaDesbloquear).
     */
    private fun prepararVoz() {
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            voz = Voz(this, falasFixas())
            return
        }
        esperaDesbloqueio = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                unregisterReceiver(this)
                esperaDesbloqueio = null
                if (voz == null) voz = Voz(this@AlarmeService, falasFixas())
            }
        }.also { ContextCompat.registerReceiver(this, it, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** As falas fixas mais as 5 frases de hoje da verificação. */
    private fun falasFixas() = FALAS_FIXAS + frasesDeHoje.mapIndexed { i, frase -> "frase_$i" to frase }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        if (acao in setOf(ACAO_TOCAR, ACAO_CONCLUIR, ACAO_DESLIGAR, ACAO_SILENCIAR, ACAO_DAR_TEMPO)) Log.i(TAG, "Serviço: comando $acao")
        val alarme = _tocando.value
        when {
            acao == ACAO_TOCAR -> iniciar()
            // Comando atrasado, de quando o alarme já tinha parado: só fecha o serviço
            alarme == null -> pararServico()
            acao == ACAO_CONCLUIR -> concluir(comemorar = intent.getBooleanExtra(EXTRA_COMEMORAR, false))
            // Nem repetindo o DESLIGAR de outro alarme (a notificação sempre traz o mesmo) dá pra fugir da missão
            acao == ACAO_DESLIGAR -> if (!alarme.missao) concluir()
            // Desde o Android 14 dá pra arrastar a notificação pro lado; ela volta enquanto o alarme durar
            acao == ACAO_REEXIBIR -> mostrarNotificacao(alarme)
            acao == ACAO_TELA -> telaMudou(intent.getBooleanExtra(EXTRA_ABERTA, true))
            acao == ACAO_SILENCIAR -> silenciarMusica()
            acao == ACAO_OLHANDO -> olhou(intent.getLongExtra(EXTRA_OLHADO, 0L))
            // Com a tela fechada não há janela de escuta: a música fica no volume cheio
            acao == ACAO_VOLUME -> if (telaAberta) sirene.volume(intent.getFloatExtra(EXTRA_VOLUME, 1f))
            acao == ACAO_DAR_TEMPO -> foraAte = SystemClock.elapsedRealtime() + TEMPO_FORA_MS
            // Fala que não acerta a frase só segura a contagem do silêncio até o prazo da frase
            acao == ACAO_OUVIU -> if (_verificacao.value?.suaVez == true && SystemClock.elapsedRealtime() - vezComecou < PRAZO_FRASE_MS) esperarResposta()
            acao == ACAO_REPETIU -> repetiuFrase()
            acao == ACAO_SEM_VOZ -> if (_verificacao.value != null && !cumprido) {
                Log.w(TAG, "Missão: sem reconhecimento de voz, a verificação é pulada")
                handler.removeCallbacks(silencio)
                agradecer()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sem a missão cumprida, a retomada continua armada no AlarmManager e o alarme volta
        Log.i(TAG, "Serviço: parou")
        handler.removeCallbacksAndMessages(null)
        notificacoes().cancel(Notificacoes.ID_CHAMADA)
        // Fechado no meio da comemoração (ex.: o Android encerrou o serviço): o volume de antes volta igual
        sirene.desligar(volumeAntes = volumeAoFim.takeIf { cumprido })
        if (cumprido) Ajustes.volumeParaDevolver = null
        esperaDesbloqueio?.let { unregisterReceiver(it) }
        voz?.desligar()
        _silenciado.value = false
        _saudando.value = false
        _comemorando.value = false
        _verificacao.value = null
        _metadeVerificada.value = false
        _olhado.value = 0
        _tocando.value = null
        if (processador.isHeld) processador.release()
        super.onDestroy()
    }

    private fun iniciar() {
        val em = Ajustes.emAndamento
        // Comemorando um alarme cumprido: um comando atrasado ou outro alarme que disparou agora não
        // corta a comemoração; o outro alarme toca logo depois dela ([encerrar])
        if (cumprido) {
            mostrarNotificacao(_tocando.value ?: Alarme.teste())
            return
        }
        if (em == null) {
            // Missão cumprida enquanto o comando chegava. Depois de startForegroundService o Android
            // exige mostrar a notificação, mesmo pra parar em seguida
            mostrarNotificacao(Alarme.teste())
            pararServico()
            return
        }
        val quedas = quedasDesde(em.desde)
        val alarme = if (quedas >= LIMITE_QUEDAS) em.alarme.copy(missao = false) else em.alarme
        val jaTocava = _tocando.value != null
        _tocando.value = alarme
        mostrarNotificacao(alarme)
        sirene.preparar(em, comeco = !jaTocava)
        // Retomada à toa, ou outro alarme disparou no meio (a foto já juntou os dois): segue de onde está.
        // Se a meta subiu (alarme de verdade no lugar do teste), a verificação da metade vale de novo
        if (jaTocava) {
            if (_olhado.value < metaOlhar(alarme) / 2) _metadeVerificada.value = false
            return
        }
        Log.i(TAG, "Serviço: tocando \"${alarme.nome}\" (missão=${alarme.missao}, remédio=${alarme.remedio}, quedas=$quedas)")
        telaAberta = true
        _silenciado.value = false
        _saudando.value = false
        _verificacao.value = null
        _metadeVerificada.value = false
        sirene.tocar()
        handler.post(renovar)
    }

    /** Quedas do app (erro, erro nativo ou travamento) desde [desde]. O Android só conta isso do 11 em diante. */
    private fun quedasDesde(desde: Long): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        val motivos = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
        return getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 0)
            .count { it.timestamp > desde && it.reason in motivos }
    }

    private fun telaMudou(aberta: Boolean) {
        if (cumprido) return
        Log.i(TAG, "Tela do alarme: ${if (aberta) "aberta" else "fechada"}")
        telaAberta = aberta
        handler.removeCallbacks(chamarDeVolta)
        if (aberta) {
            notificacoes().cancel(Notificacoes.ID_CHAMADA)
        } else {
            sirene.volume(1f)
            // Quem foi desbloquear o celular ou liberar a câmera ganha um tempo (a música não para por isso)
            val espera = maxOf(TELA_FECHADA_MS, foraAte - SystemClock.elapsedRealtime())
            handler.postDelayed(chamarDeVolta, espera)
        }
    }

    /**
     * A pessoa disse "stop": a música para e a voz dá bom dia ([textoDaSaudacao]); [PAUSA_DEPOIS_DA_SAUDACAO_MS]
     * depois que ela termina, diz [FALA_INICIO], toca o bipe positivo e aí começa a câmera e a vigia.
     * Sem voz, só a pausa e o bipe.
     */
    private fun silenciarMusica() {
        if (cumprido) return
        if (_silenciado.value) return // toque repetido no botão, ou o mesmo STOP chegando de novo
        sirene.calar()
        _silenciado.value = true
        _saudando.value = true
        handler.postDelayed(comecarAtividade, SAUDACAO_MAX_MS)
        val falar: (File?) -> Unit = { arquivo ->
            if (_saudando.value) sirene.falar(arquivo) { handler.postDelayed(anunciarInicio, PAUSA_DEPOIS_DA_SAUDACAO_MS) }
        }
        voz?.gravar("saudacao_${++rodadas}", textoDaSaudacao(LocalTime.now()), falar) ?: falar(null)
    }

    /** "Activity starting now", o bipe positivo e a câmera. */
    private val anunciarInicio: Runnable = Runnable {
        if (!_saudando.value) return@Runnable
        sirene.falar(voz?.fixa(FALA_DO_INICIO)) {
            if (_saudando.value) sirene.positivo { comecarAtividade.run() }
        }
    }

    /** Fim da saudação: a tela passa pra câmera e a vigia começa a contar. */
    private val comecarAtividade: Runnable = Runnable {
        if (!_saudando.value) return@Runnable
        Log.i(TAG, "Missão: saudação feita, começa a câmera")
        _saudando.value = false
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        adiarVigia()
    }

    /**
     * Sinal de "olhando" da etapa da câmera: guarda o tempo já somado no anel e zera a contagem da vigia.
     * A cada 10 min completos ([marcoCompletado]), a voz avisa ("10 minutes passed").
     */
    private fun olhou(olhadoMs: Long) {
        // Sinal atrasado de uma rodada que já acabou, ou de antes da verificação
        if (!_silenciado.value || _saudando.value || _verificacao.value != null || cumprido) return
        val antes = _olhado.value
        _olhado.value = maxOf(antes, olhadoMs)
        val meta = _tocando.value?.let(::metaOlhar) ?: return
        if (!_metadeVerificada.value && _olhado.value >= meta / 2) return iniciarVerificacao()
        marcoCompletado(antes, _olhado.value, meta)?.let { minutos ->
            Log.i(TAG, "Missão: $minutos min de câmera")
            sirene.falar(voz?.fixa("minutos_$minutos"))
        }
        adiarVigia()
    }

    /** O anel zerou (sem olhar, ou sem responder na verificação): a verificação da metade vale de novo. */
    private fun zerarAnel() {
        _olhado.value = 0
        _metadeVerificada.value = false
    }

    /**
     * Metade da atividade: o anel para (a tela da câmera sai), toca o bipe leve, a voz diz [FALA_VERIFICAR]
     * e, [PAUSA_ANTES_DAS_FRASES_MS] depois, começam as frases.
     */
    private fun iniciarVerificacao() {
        Log.i(TAG, "Missão: metade da atividade, verificação com ${frasesDeHoje.size} frases")
        handler.removeCallbacks(vigia)
        val rodada = ++rodadaVerificacao
        _verificacao.value = Verificacao(frase = "", numero = 0, suaVez = false)
        sirene.leve {
            sirene.falar(voz?.fixa(FALA_DE_VERIFICAR)) {
                if (rodada == rodadaVerificacao) handler.postDelayed({ if (rodada == rodadaVerificacao) falarFrase(0) }, PAUSA_ANTES_DAS_FRASES_MS)
            }
        }
    }

    /** A voz fala a frase [i] (a tela também mostra); quando termina, é a vez da pessoa repetir. */
    private fun falarFrase(i: Int) {
        val rodada = rodadaVerificacao
        _verificacao.value = Verificacao(frasesDeHoje[i], numero = i + 1, suaVez = false)
        sirene.falar(voz?.fixa("frase_$i")) {
            if (rodada != rodadaVerificacao) return@falar
            _verificacao.value = _verificacao.value?.copy(suaVez = true)
            vezComecou = SystemClock.elapsedRealtime()
            esperarResposta()
        }
    }

    /** A vez da pessoa (ou ela acabou de falar): a contagem sem resposta recomeça do zero. */
    private fun esperarResposta() {
        _ultimaResposta.value = SystemClock.elapsedRealtime()
        apitos = 0
        handler.removeCallbacks(silencio)
        handler.postDelayed(silencio, SILENCIO_APITO_MS)
    }

    /** Sem resposta na verificação: um apito alto a cada [SILENCIO_APITO_MS] e, em [SILENCIO_ZERA_MS], zera como a vigia. */
    private val silencio = object : Runnable {
        override fun run() {
            if (apitos < (SILENCIO_ZERA_MS / SILENCIO_APITO_MS).toInt() - 1) {
                apitos++
                Log.i(TAG, "Missão: ${apitos * SILENCIO_APITO_MS / 1000} s sem responder na verificação, apito $apitos")
                sirene.apito(apitos)
                handler.postDelayed(this, SILENCIO_APITO_MS)
                return
            }
            Log.i(TAG, "Missão: ${SILENCIO_ZERA_MS / 1000} s sem responder na verificação, anel zera e a música volta")
            zerarAnel()
            religarMusica()
            _tocando.value?.let { mostrarNotificacao(it) }
        }
    }

    /** A pessoa repetiu a frase da vez: o "plim" e a próxima frase, ou o agradecimento depois da última. */
    private fun repetiuFrase() {
        val v = _verificacao.value?.takeIf { it.suaVez } ?: return
        Log.i(TAG, "Missão: frase ${v.numero} de ${frasesDeHoje.size} repetida")
        handler.removeCallbacks(silencio)
        _verificacao.value = v.copy(suaVez = false)
        val rodada = rodadaVerificacao
        sirene.acerto {
            if (rodada != rodadaVerificacao) return@acerto
            if (v.numero < frasesDeHoje.size) falarFrase(v.numero) else agradecer()
        }
    }

    /** Fim da verificação: a voz agradece ([FALA_METADE]) e a câmera volta, agora mais exigente. */
    private fun agradecer() {
        val rodada = rodadaVerificacao
        _verificacao.value = Verificacao(frase = "", numero = frasesDeHoje.size, suaVez = false)
        sirene.falar(voz?.fixa(FALA_DA_METADE)) {
            if (rodada != rodadaVerificacao) return@falar
            Log.i(TAG, "Missão: verificação feita, a segunda metade é mais exigente com os olhos")
            _verificacao.value = null
            // A meta pode ter subido no meio (alarme de verdade tomou o lugar do teste): aí a metade ainda não chegou
            _metadeVerificada.value = _olhado.value >= (_tocando.value?.let(::metaOlhar) ?: 0L) / 2
            adiarVigia()
        }
    }

    /** Sinal de "olhando" (ou o "stop"): a contagem sem olhar recomeça do zero, sem bipes. */
    private fun adiarVigia() {
        _ultimoOlhar.value = SystemClock.elapsedRealtime()
        bipes = 0
        handler.removeCallbacks(vigia)
        handler.postDelayed(vigia, AVISO_SEM_OLHAR_MS)
    }

    /** Música de volta no volume cheio; se estava calada, a missão volta pra etapa de falar. O anel não muda aqui. */
    private fun religarMusica() {
        if (cumprido) return
        handler.removeCallbacks(vigia)
        // No meio da saudação: ela para (a música cala a fala) e a câmera não começa
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        _saudando.value = false
        voz?.cancelar()
        // No meio da verificação: ela para; volta do começo na próxima vez que o anel chegar na metade
        rodadaVerificacao++
        handler.removeCallbacks(silencio)
        _verificacao.value = null
        if (_silenciado.value) {
            _silenciado.value = false
            sirene.tocar()
        } else {
            sirene.volume(1f)
        }
    }

    /** Missão cumprida (ou DESLIGAR, em alarme sem missão): o único fim do alarme. A limpeza acontece em [onDestroy]. */
    private fun concluir(comemorar: Boolean = false) {
        if (cumprido) return
        cumprido = true
        Log.i(TAG, "Serviço: alarme cumprido")
        handler.removeCallbacksAndMessages(null) // nenhuma renovação atrasada pode rearmar a retomada
        // Cumprido já fica gravado: se o app cair na comemoração, o alarme não volta
        volumeAoFim = Ajustes.emAndamento?.volume
        Ajustes.volumeParaDevolver = volumeAoFim
        Ajustes.emAndamento = null
        Agendador.cancelarRetomada(this)
        if (!comemorar) return encerrar()
        // Missão cumprida: bipe leve, a fala de parabéns e o estouro, aí fecha
        _comemorando.value = true
        sirene.calar()
        handler.postDelayed(::encerrar, COMEMORACAO_MAX_MS)
        sirene.leve { sirene.falar(voz?.fixa(FALA_DO_FIM)) { sirene.comemoracao(::encerrar) } }
    }

    /**
     * O fim de verdade: devolve o volume de antes do alarme e fecha o serviço (a limpeza acontece em
     * [onDestroy]). Se outro alarme disparou na comemoração, ele toca em instantes, pela retomada.
     */
    private fun encerrar() {
        handler.removeCallbacksAndMessages(null)
        sirene.desligar(volumeAntes = volumeAoFim)
        Ajustes.volumeParaDevolver = null
        Ajustes.emAndamento?.let {
            Log.i(TAG, "Serviço: outro alarme disparou na comemoração, toca em seguida")
            Agendador.agendarRetomada(this, 0)
        }
        pararServico()
    }

    /** Fecha só este serviço, sem mexer no alarme em andamento. */
    private fun pararServico() {
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
        val desligar = if (alarme.missao) telaCheia else comandoPendente(1, ACAO_DESLIGAR)
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
}
